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

    /** Summe ueber alle Zuege. Interessant, sobald jemand fuer Token bezahlt:
     *  die Eingabe wird in jedem Zug erneut geschickt und waechst dabei. */
    private long eingabeTokens = 0, ausgabeTokens = 0;

    public long eingabeTokens() { return eingabeTokens; }
    public long ausgabeTokens() { return ausgabeTokens; }

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

    /**
     * Wie viele fehlgeschlagene Werkzeugaufrufe hintereinander als Schleife
     * gelten.
     *
     * <p>Kalibriert an echten Laeufen: der eine Lauf, der sich festgefressen
     * hat (Zirkelimport, 80 Zuege, Zuglimit) hatte eine Folge von <b>zehn</b>
     * Fehlschlaegen am Stueck. Die erfolgreichen Laeufe derselben Modelle kamen
     * auf hoechstens fuenf. Sechs trennt beides auf den vorliegenden Daten --
     * das sind fuenf Laeufe, also eher eine Faustregel als eine Schwelle. Sie
     * darf ruhig zu frueh greifen: ein Anstoss kostet einen Zug, eine Schleife
     * kostet den Lauf.
     */
    private static final int MAX_FEHLERFOLGE = 6;

    private static final String SCHLEIFE = """
            Die letzten %d Werkzeugaufrufe sind alle fehlgeschlagen. Hoer auf, \
            weiter zu reparieren. Lies die beteiligten Dateien vollstaendig, \
            schreib hin, was du ueber den Zustand sicher weisst und was du nur \
            vermutest, und waehle danach einen anderen Ansatz -- nicht denselben \
            noch einmal.""";

    /**
     * Nachgefragt, bevor ein Abschluss ohne Werkzeugaufruf als fertig gilt.
     *
     * <p>Der haeufigste Fehler im Pruefstand war nicht Unvermoegen, sondern ein
     * Modell, das etwas Funktionierendes gebaut hat statt des Verlangten, und
     * dessen eigene Tests dazu gruen waren. Dreimal ist dieselbe Aufgabe an
     * derselben Stelle gescheitert -- an einem Importpfad, den die Aufgabe
     * woertlich nennt. Und der einzige ueber zwei Laeufe stabile Fehler stand
     * im Fliesstext der Aufgabe, nicht in der Signaturliste darunter.
     */

    /**
     * Angehaengt, wenn ein Lauf ohne einen einzigen Werkzeugaufruf enden will.
     *
     * <p>{@link #ANSTOSS} deckt nur den Fall ab, dass die Ausgabegrenze erreicht
     * wurde. Gemessen wurde aber auch das hier: erster Zug, {@code STOP}, vier
     * Ausgabetokens, Inhalt {@code "[README]"} — und der Harness meldete FERTIG
     * mit Rueckgabewert 0 auf einem unberuehrten Verzeichnis. In rund sechzig
     * Aufgabenlaeufen zweimal aufgetreten; beide Male ging die Aufgabe als
     * Modellergebnis in eine Messung ein, obwohl nichts versucht worden war.
     */
    private static final String NICHTS_GETAN = """
            Du hast noch kein einziges Werkzeug benutzt und damit weder den \
            vorhandenen Stand gelesen noch etwas geaendert. Falls die Aufgabe \
            Arbeit am Verzeichnis verlangt, fang jetzt damit an: sieh dir zuerst \
            an, was da ist. Falls sie wirklich ohne Werkzeug zu beantworten war, \
            sag das ausdruecklich und begruende es.""";

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
        int fehlerfolge = 0;
        boolean untaetigAngestossen = false;

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
            eingabeTokens += a.usage().promptTokens();
            ausgabeTokens += a.usage().completionTokens();
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
            if (!a.message().hatWerkzeugaufrufe()) {
                // Fertig ohne je ein Werkzeug angefasst zu haben: das ist bei
                // einer Arbeitsaufgabe fast immer ein Missverstaendnis, kein
                // Ergebnis. Einmal anstossen, dann gelten lassen -- eine reine
                // Wissensfrage darf ohne Werkzeug beantwortet werden.
                if (aufrufeGesamt == 0 && !untaetigAngestossen) {
                    untaetigAngestossen = true;
                    beobachter.hinweis("Abschluss ohne jeden Werkzeugaufruf, stosse an");
                    t.add(new UserMessage(NICHTS_GETAN));
                    continue;
                }
                return new Ergebnis(Status.FERTIG, zug, aufrufeGesamt,
                                    a.message().content(), null);
            }

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
                fehlerfolge = r.istFehler() ? fehlerfolge + 1 : 0;
            }

            // -------------------------------------------------- Schleife
            // Nicht der einzelne Fehlschlag ist das Problem, sondern die Folge:
            // ein Modell, das dieselbe Stelle immer wieder anders anfasst und
            // dabei nie zurueckgeht, um den ganzen Zustand zu lesen.
            if (fehlerfolge >= MAX_FEHLERFOLGE) {
                beobachter.hinweis(String.format(
                        "%d Fehlschlaege in Folge, stosse zum Umdenken an", fehlerfolge));
                t.add(new UserMessage(String.format(SCHLEIFE, fehlerfolge)));
                fehlerfolge = 0;
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
        // bash ist das einzige Werkzeug ohne Eingrenzung -- die Dateiwerkzeuge
        // gehen alle durch Workspace.aufloesen. Ein Hinweis statt eines Verbots:
        // pip, git und Nachbarprojekte sind legitime Gruende, hinauszugreifen.
        // Wer es hinterher wissen will, findet es jetzt wenigstens im Protokoll.
        if ("bash".equals(tc.name())) {
            String draussen = ws.verlaesstBereich(tc.kurz());
            if (draussen != null)
                beobachter.hinweis("ausserhalb des Arbeitsbereichs: " + draussen);
        }
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
