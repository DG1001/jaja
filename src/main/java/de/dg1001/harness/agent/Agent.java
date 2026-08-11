package de.dg1001.harness.agent;

import de.dg1001.harness.tools.Tool;
import de.dg1001.harness.tools.ToolRegistry;
import de.dg1001.harness.wire.ChatEndpunkt;
import de.dg1001.harness.wire.Messages.ChatResponse;
import de.dg1001.harness.wire.Messages.FinishReason;
import de.dg1001.harness.wire.Messages.ToolCall;
import de.dg1001.harness.wire.Messages.ToolSpec;
import de.dg1001.harness.wire.Messages.UserMessage;
import de.dg1001.harness.ws.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Die Schleife. */
public final class Agent {

    private final ChatEndpunkt client;
    private final ToolRegistry registry;
    private final Workspace ws;
    private final ContextBudget budget;
    private final TokenSchaetzer schaetzer = new TokenSchaetzer();
    private final Elision elision;
    private final int maxZuege;
    private final Beobachter beobachter;

    /** Wird zwischen den Zuegen und nach jeder Antwort geprueft. Gesetzt wird
     *  sie von aussen (Ctrl-C in der Oberflaeche), gelesen nur hier. */
    private final AtomicBoolean abbruch = new AtomicBoolean(false);

    private Freigabe freigabe = Freigabe.ALLES;

    /** Zeichen der Werkzeugbeschreibungen. Muss bei der Kalibrierung
     *  mitgezaehlt werden: prompt_tokens des Servers enthaelt sie, die
     *  Nachrichtenlaenge nicht. Ohne das liegt das Verhaeltnis unter 1 und
     *  jede Kalibrierung wird als Ausreisser verworfen -- der Schaetzer bleibt
     *  dann fuer immer auf dem Startwert. */
    private int werkzeugZeichen = 0;

    public Agent(ChatEndpunkt client, ToolRegistry registry, Workspace ws,
                 ContextBudget budget, int maxZuege, boolean laut) {
        this(client, registry, ws, budget, maxZuege,
             laut ? Beobachter.STDERR : Beobachter.STILL);
    }

    public Agent(ChatEndpunkt client, ToolRegistry registry, Workspace ws,
                 ContextBudget budget, int maxZuege, Beobachter beobachter) {
        this.client     = client;
        this.registry   = registry;
        this.ws         = ws;
        this.budget     = budget;
        this.elision    = new Elision(budget);
        this.maxZuege   = maxZuege;
        this.beobachter = beobachter;
    }

    public Agent mitFreigabe(Freigabe f) { this.freigabe = f; return this; }

    /** Bricht den laufenden Zug ab. Darf aus einem anderen Faden gerufen
     *  werden -- genau dafuer ist sie da. */
    public void brichAb() { abbruch.set(true); }

    public void abbruchZuruecksetzen() { abbruch.set(false); }

    public enum Status { FERTIG, ZUGLIMIT, STECKEN, KONTEXT_ERSCHOEPFT, FEHLER, ABGEBROCHEN }

    public record Ergebnis(Status status, int zuege, int werkzeugaufrufe,
                           String abschluss, String hinweis) {
        public boolean erfolgreich() { return status == Status.FERTIG; }
    }

    /** Wie oft ein Zug ohne Aktion an der Ausgabegrenze geduldet wird. */
    private static final int MAX_ENTARTET = 2;

    private static final String ANSTOSS = """
            Du hast die Ausgabegrenze erreicht, ohne ein Werkzeug aufzurufen. \
            Plane nicht weiter vor. Fang mit einem einzigen konkreten Schritt an \
            und rufe jetzt ein Werkzeug auf.""";

    public Ergebnis lauf(String systemPrompt, String aufgabe) {
        Transcript t = new Transcript(schaetzer);
        t.beginne(systemPrompt, aufgabe);
        return lauf(t);
    }

    /**
     * Arbeitet auf einem bestehenden Verlauf weiter.
     *
     * <p>Das ist der Unterschied zwischen einem Skript und einem Agenten: die
     * Rueckfrage sieht alles, was vorher geschah, samt bereits gekuerzter
     * Werkzeugergebnisse. Der Aufrufer haengt die neue Nutzernachricht an und
     * ruft das hier.
     */
    public Ergebnis lauf(Transcript t) {
        List<ToolSpec> werkzeuge = registry.specs();
        t.setzeGrundlast(grundlast(werkzeuge));

        int entartet = 0;
        int aufrufeGesamt = 0;

        for (int zug = 1; zug <= maxZuege; zug++) {

            if (abbruch.get())
                return new Ergebnis(Status.ABGEBROCHEN, zug - 1, aufrufeGesamt, null,
                                    "vom Nutzer abgebrochen");

            Elision.Bericht kuerzung = elision.vielleichtKuerzen(t);
            if (kuerzung.ergebnis() == Elision.Ergebnis.AUSSICHTSLOS)
                return new Ergebnis(Status.KONTEXT_ERSCHOEPFT, zug, aufrufeGesamt,
                                    null, kuerzung.hinweis());
            if (kuerzung.ergebnis() == Elision.Ergebnis.GEKUERZT)
                beobachter.hinweis(String.format("gekuerzt: %d -> %d Token (%d Stufen)",
                        kuerzung.vorher(), kuerzung.nachher(), kuerzung.stufen()));

            int zeichenVorher = t.zeichenGesamt() + werkzeugZeichen;

            ChatResponse a;
            try {
                a = client.complete(t.nachrichten(), werkzeuge);
            } catch (RuntimeException e) {
                // Ein Abbruch unterbricht den Faden und laesst die Anfrage
                // scheitern. Das ist kein Fehler, sondern der Wunsch des Nutzers.
                if (abbruch.get() || Thread.currentThread().isInterrupted())
                    return new Ergebnis(Status.ABGEBROCHEN, zug, aufrufeGesamt, null,
                                        "vom Nutzer abgebrochen");
                return new Ergebnis(Status.FEHLER, zug, aufrufeGesamt, null, e.getMessage());
            }

            schaetzer.kalibriere(zeichenVorher, a.usage().promptTokens());
            t.add(a.message());

            beobachter.zug(zug, a, budget.nutzbareEingabe());

            // ------------------------------------------- entarteter Zug
            // Ausgabegrenze ausgeschoepft, aber nichts zu tun: das Modell hat
            // sich verplant statt zu handeln. Gemessen bei einem Modell, das
            // 16.384 Ausgabetokens in einen einzigen Denkblock steckte und nie
            // einen Werkzeugaufruf erzeugte -- der Lauf endete mit rc=0 und
            // leerem Verzeichnis. Ein Anstoss statt stillem Erfolg.
            if (a.finishReason() == FinishReason.LENGTH && !a.message().hatWerkzeugaufrufe()) {
                if (++entartet > MAX_ENTARTET)
                    return new Ergebnis(Status.STECKEN, zug, aufrufeGesamt, null,
                            "Dreimal die Ausgabegrenze ohne Werkzeugaufruf erreicht. "
                          + "Das Modell plant, statt zu handeln — hoehere Ausgabegrenze "
                          + "oder knappere Aufgabenstellung versuchen.");
                beobachter.hinweis(String.format("entarteter Zug %d/%d, stosse an",
                        entartet, MAX_ENTARTET));
                t.add(new UserMessage(ANSTOSS));
                continue;
            }
            entartet = 0;

            // ------------------------------------------------------ fertig
            if (!a.message().hatWerkzeugaufrufe())
                return new Ergebnis(Status.FERTIG, zug, aufrufeGesamt,
                                    a.message().content(), null);

            // ------------------------------------------------- Werkzeuge
            List<ToolCall> aufrufe = a.message().toolCalls();
            aufrufeGesamt += aufrufe.size();
            List<Tool.ToolResult> ergebnisse = fuehreAlleAus(aufrufe);

            // In AUFRUFREIHENFOLGE anhaengen, nicht in Fertigstellungsreihenfolge:
            // sonst unterscheidet sich der Kontext zwischen zwei sonst gleichen
            // Laeufen und der Praefix-Cache greift beim naechsten Zug nicht mehr.
            for (int i = 0; i < aufrufe.size(); i++) {
                Tool.ToolResult r = ergebnisse.get(i);
                t.addWerkzeugErgebnis(aufrufe.get(i), r.text());
                beobachter.werkzeugFertig(aufrufe.get(i), r);
            }
        }

        return new Ergebnis(Status.ZUGLIMIT, maxZuege, aufrufeGesamt, null,
                "Zuglimit " + maxZuege + " erreicht.");
    }

    /** Werkzeuge nebenlaeufig, Ergebnisse in Aufrufreihenfolge. */
    private List<Tool.ToolResult> fuehreAlleAus(List<ToolCall> aufrufe) {
        if (aufrufe.size() == 1)
            return List.of(fuehreEinesAus(aufrufe.get(0)));

        List<Tool.ToolResult> ergebnisse = new ArrayList<>(aufrufe.size());
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Tool.ToolResult>> futures = new ArrayList<>(aufrufe.size());
            for (ToolCall tc : aufrufe)
                futures.add(exec.submit(() -> fuehreEinesAus(tc)));
            for (Future<Tool.ToolResult> f : futures) {
                try {
                    ergebnisse.add(f.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    ergebnisse.add(Tool.ToolResult.fehler("unterbrochen"));
                } catch (ExecutionException e) {
                    ergebnisse.add(Tool.ToolResult.fehler(
                            "Werkzeug abgestuerzt: " + e.getCause()));
                }
            }
        }
        return ergebnisse;
    }

    /**
     * Einzelner Aufruf: erst fragen, dann ausfuehren.
     *
     * <p>Die Freigabe sitzt hier und nicht in der {@link ToolRegistry}, damit
     * die Werkzeuge nichts von Bildschirmen und Nutzern wissen muessen.
     */
    private Tool.ToolResult fuehreEinesAus(ToolCall tc) {
        String grund = freigabe.pruefe(tc);
        if (grund != null) return Tool.ToolResult.fehler(grund);
        beobachter.werkzeugStart(tc);
        return registry.fuehreAus(tc, ws);
    }

    /** Tokens der Werkzeugbeschreibungen — einmal berechnet, danach konstant. */
    private int grundlast(List<ToolSpec> werkzeuge) {
        int zeichen = 0;
        for (ToolSpec s : werkzeuge)
            zeichen += s.name().length() + s.description().length()
                     + s.parametersJson().length() + 60;
        werkzeugZeichen = zeichen;
        return schaetzer.schaetze(zeichen);
    }

    public TokenSchaetzer schaetzer() { return schaetzer; }
}
