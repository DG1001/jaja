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

    /** Der Faden, der gerade {@link #lauf(Transcript)} ausfuehrt, sonst null. */
    private volatile Thread laeufer;

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

    /**
     * Bricht den laufenden Zug ab. Darf aus einem anderen Faden gerufen
     * werden -- genau dafuer ist sie da.
     *
     * <p>Unterbricht den Faden, statt nur das Kennzeichen zu setzen. Vorher
     * wartete ein Ctrl-C das Ende der laufenden Anfrage ab, und die dauert
     * lokal Minuten (gemessen: elf fuer einen Zug). Unterbrochen wird die
     * HTTP-Anfrage, ein laufendes bash samt Kindern und eine offene
     * Freigabefrage; der Verlauf bleibt dabei gueltig, weil Ergebnisse nur
     * vollstaendig und in Aufrufreihenfolge angehaengt werden.
     */
    public void brichAb() {
        abbruch.set(true);
        Thread t = laeufer;
        if (t != null) t.interrupt();
    }

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

    /**
     * Angehaengt, wenn ein Lauf enden will, nachdem er Dateien geaendert, aber
     * danach nichts mehr ausgefuehrt hat.
     *
     * <p>Das ist die Lehre aus {@code --abgleich}, als Mechanik statt als
     * Frage. Dort sollte das Modell vor dem Abschluss seine Anforderungen
     * durchgehen; in vier von sechs Faellen hakte es sie in Prosa ab und rief
     * kein Werkzeug mehr auf -- einmal, waehrend {@code from typing import dict}
     * jeden Import brach. Ein Modell, das sich fertig glaubt, bestaetigt das,
     * wenn man es fragt. Hier wird nicht nach einer Ueberzeugung gefragt,
     * sondern eine Beobachtung verlangt: ein Aufruf, dessen Ausgabe falsch sein
     * kann.
     *
     * <p>Ausgeloest wird mechanisch, nicht nach Gefuehl: ein erfolgreiches
     * {@code write}/{@code edit} an etwas anderem als Doku, und kein
     * {@code bash} danach. Einmal je Auftrag, und mit Ausweg -- nicht alles
     * laesst sich ausfuehren.
     */
    private static final String UNGEPRUEFT = """
            Du hast %s geaendert und danach nichts mehr ausgefuehrt. Pruefe das \
            Ergebnis jetzt mit einem Werkzeugaufruf, dessen Ausgabe dir widersprechen \
            kann: die Tests, ein Build oder ein kurzer Aufruf des geaenderten Codes. \
            Falls sich das hier nicht pruefen laesst, sag in einem Satz warum.""";

    /** Endungen, deren Aenderung keinen Pruefaufruf verlangt. */
    private static final java.util.Set<String> DOKU = java.util.Set.of(
            ".md", ".txt", ".rst", ".adoc");

    public Ergebnis lauf(String systemPrompt, String aufgabe) {
        Transcript t = new Transcript(schaetzer);
        // Ein Blick auf die Umgebung, bevor der erste Zug faellt: der Agent
        // fragt sonst zwei bis fuenf Zuege lang, wo er ist und was da ist.
        // Bewusst in die Aufgabe hinein und nicht als eigene Nachricht --
        // der Systemprompt bleibt byte-gleich, der Praefix-Cache ueber
        // Systemprompt und Werkzeugbeschreibungen bleibt heil.
        t.beginne(systemPrompt, Umgebungsbild.anhaengen(aufgabe, ws));
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
        laeufer = Thread.currentThread();
        try {
            return schleife(t);
        } finally {
            laeufer = null;
            // Ein Abbruch kurz vor Schluss darf nicht am Faden kleben bleiben.
            Thread.interrupted();
        }
    }

    private Ergebnis schleife(Transcript t) {
        List<ToolSpec> werkzeuge = registry.specs();
        t.setzeGrundlast(grundlast(werkzeuge));

        int entartet = 0;
        int aufrufeGesamt = 0;
        int fehlerfolge = 0;
        boolean untaetigAngestossen = false;
        boolean pruefungAngestossen = false;
        // Geaendert seit dem letzten bash, in Aufrufreihenfolge. Leer heisst:
        // nichts zu pruefen, oder schon geprueft.
        java.util.LinkedHashSet<String> ungeprueft = new java.util.LinkedHashSet<>();

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
                if (!ungeprueft.isEmpty() && !pruefungAngestossen) {
                    pruefungAngestossen = true;
                    beobachter.hinweis("Abschluss ohne Pruefung nach Aenderung, stosse an");
                    t.add(new UserMessage(String.format(UNGEPRUEFT, aufzaehlung(ungeprueft))));
                    continue;
                }
                return new Ergebnis(Status.FERTIG, zug, aufrufeGesamt,
                                    a.message().content(), null);
            }

            // ------------------------------------------------- Werkzeuge
            List<ToolCall> aufrufe = a.message().toolCalls();
            aufrufeGesamt += aufrufe.size();
            // Endete der Zug an der Ausgabegrenze, ist der letzte Aufruf
            // mitten im JSON abgeschnitten. Die Grenze wandert mit, damit die
            // Fehlermeldung die Ursache nennen kann statt nur das Symptom.
            int grenze = a.finishReason() == FinishReason.LENGTH ? budget.maxAusgabe() : 0;
            List<Tool.ToolResult> ergebnisse = fuehreAlleAus(aufrufe, grenze);

            // In AUFRUFREIHENFOLGE anhaengen, nicht in Fertigstellungsreihenfolge:
            // sonst unterscheidet sich der Kontext zwischen zwei sonst gleichen
            // Laeufen und der Praefix-Cache greift beim naechsten Zug nicht mehr.
            for (int i = 0; i < aufrufe.size(); i++) {
                Tool.ToolResult r = ergebnisse.get(i);
                t.addWerkzeugErgebnis(aufrufe.get(i), r.text());
                beobachter.werkzeugFertig(aufrufe.get(i), r);
                fehlerfolge = r.istFehler() ? fehlerfolge + 1 : 0;
                merkeAenderung(aufrufe.get(i), r, ungeprueft);
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

    /** Fuehrt die Liste der ungeprueften Aenderungen nach einem Aufruf nach. */
    private static void merkeAenderung(ToolCall tc, Tool.ToolResult r,
                                       java.util.Set<String> ungeprueft) {
        // Jedes bash zaehlt, auch ein abgelehntes: wer es versucht hat und
        // abgewiesen wurde, soll nicht gleich noch einmal dazu gedraengt werden.
        if ("bash".equals(tc.name())) { ungeprueft.clear(); return; }
        if (r.istFehler() || !("write".equals(tc.name()) || "edit".equals(tc.name()))) return;
        String pfad = tc.kurz();
        int punkt = pfad.lastIndexOf('.');
        if (punkt >= 0 && DOKU.contains(pfad.substring(punkt).toLowerCase())) return;
        ungeprueft.add(pfad);
    }

    private static String aufzaehlung(java.util.Set<String> pfade) {
        List<String> l = new ArrayList<>(pfade);
        if (l.size() == 1) return l.get(0);
        if (l.size() <= 3) return String.join(", ", l);
        return String.join(", ", l.subList(0, 3)) + " und " + (l.size() - 3) + " weitere Dateien";
    }

    /** Werkzeuge nebenlaeufig, Ergebnisse in Aufrufreihenfolge. */
    private List<Tool.ToolResult> fuehreAlleAus(List<ToolCall> aufrufe, int ausgabegrenze) {
        if (aufrufe.size() == 1)
            return List.of(fuehreEinesAus(aufrufe.get(0), ausgabegrenze));

        List<Tool.ToolResult> ergebnisse = new ArrayList<>(aufrufe.size());
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Tool.ToolResult>> futures = new ArrayList<>(aufrufe.size());
            for (ToolCall tc : aufrufe)
                futures.add(exec.submit(() -> fuehreEinesAus(tc, ausgabegrenze)));
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
    private Tool.ToolResult fuehreEinesAus(ToolCall tc, int ausgabegrenze) {
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
        return registry.fuehreAus(tc, ws, ausgabegrenze);
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
