package de.dg1001.harness.agent;

import de.dg1001.harness.tools.GlobTool;
import de.dg1001.harness.tools.GrepTool;
import de.dg1001.harness.tools.ReadTool;
import de.dg1001.harness.tools.Tool;
import de.dg1001.harness.tools.ToolRegistry;
import de.dg1001.harness.wire.ChatEndpunkt;
import de.dg1001.harness.wire.Messages.ChatResponse;
import de.dg1001.harness.wire.Messages.ToolCall;
import de.dg1001.harness.ws.Workspace;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ein Unteragent, der eine Frage zum Projekt in eigenem Kontext beantwortet.
 *
 * <p><b>Was er sparen soll, ist Kontext, nicht Zuege.</b> Die Karte hat ueber
 * 735 Laeufe gezeigt, dass Navigationshilfen hier keine Zuege sparen. Das ist
 * eine andere Wette: bei 65k Fenster ist nicht die Zahl der Zuege der Engpass,
 * sondern was sie im Verlauf hinterlassen. Zehn Leseergebnisse, um eine Frage
 * zu klaeren, liegen danach im Hauptverlauf, bis die Kuerzung sie holt. Hier
 * liegen sie im Verlauf des Unteragenten, und zurueck kommt eine Antwort von
 * ein paar hundert Token.
 *
 * <p>Ob die Wette aufgeht, zeigen die Eingabetoken im Pruefstand -- seine
 * eigenen eingerechnet, die {@link #eingabeTokens()} dafuer liefert. Ohne sie
 * saehe der Unteragent im Vergleich billiger aus, als er ist. Darum ist er per
 * Vorgabe aus ({@code --erkunden}).
 *
 * <p>Nur lesende Werkzeuge: der Unteragent soll nichts veraendern, wovon der
 * Hauptagent nichts weiss.
 */
public final class ErkundeTool implements Tool {

    static final int MAX_ZUEGE = 20;
    static final int MAX_ANTWORT = 6_000;

    private static final String SYSTEMPROMPT = """
            Du beantwortest eine Frage zu einem Projekt fuer einen anderen Agenten, \
            der daran arbeitet. Du kannst nur lesen und suchen, nichts aendern.

            Fang mit einem konkreten Schritt an. Wenn du die Antwort hast, antworte \
            ohne Werkzeugaufruf, knapp und belastbar: die relevanten Dateien mit \
            Zeilennummern, was dort steht und warum es fuer die Frage zaehlt, und was \
            du nicht klaeren konntest. Keine Vermutungen als Tatsachen.""";

    private final ChatEndpunkt endpunkt;
    private final ContextBudget budget;
    private final Beobachter beobachter;
    private final AtomicLong eingabe = new AtomicLong(), ausgabe = new AtomicLong();

    public ErkundeTool(ChatEndpunkt endpunkt, ContextBudget budget, Beobachter beobachter) {
        this.endpunkt = endpunkt;
        this.budget = budget;
        this.beobachter = beobachter;
    }

    @Override public String name() { return "erkunde"; }

    @Override public String beschreibung() {
        return "Beantwortet eine Frage zum Projekt in eigenem Kontext: sucht und liest selbst "
             + "und liefert nur die Antwort. Fuer breite Fragen (wo passiert X, was haengt an Y), "
             + "damit die Lesearbeit nicht deinen Kontext fuellt.";
    }

    @Override public String parameterSchema() {
        return """
               {"type":"object",
                "properties":{"frage":{"type":"string","description":"die Frage, so konkret wie moeglich"}},
                "required":["frage"]}""";
    }

    @Override
    public ToolResult run(Map<String, Object> args, Workspace ws) {
        String frage;
        try {
            frage = Tool.pflichtText(args, "frage");
        } catch (IllegalArgumentException e) {
            return ToolResult.fehler(e.getMessage());
        }
        ToolRegistry nurLesen = new ToolRegistry()
                .fuegeHinzu(new GlobTool()).fuegeHinzu(new GrepTool()).fuegeHinzu(new ReadTool());
        Agent unter = new Agent(endpunkt, nurLesen, ws, budget, MAX_ZUEGE, darunter(beobachter));
        Agent.Ergebnis e = unter.lauf(SYSTEMPROMPT, frage);
        eingabe.addAndGet(unter.eingabeTokens());
        ausgabe.addAndGet(unter.ausgabeTokens());

        String fuss = "\n[erkundet in " + e.zuege() + " Zuegen, " + e.werkzeugaufrufe() + " Aufrufen]";
        if (e.status() != Agent.Status.FERTIG || e.abschluss() == null)
            return ToolResult.fehler("Erkundung ohne Antwort beendet (" + e.status()
                    + (e.hinweis() == null ? "" : ": " + e.hinweis()) + ")" + fuss);
        String a = e.abschluss().strip();
        if (a.length() > MAX_ANTWORT) a = a.substring(0, MAX_ANTWORT) + "\n[… gekuerzt]";
        return ToolResult.ok(a + fuss);
    }

    /** Verbrauch aller Erkundungen bisher -- fuer die Summen des Hauptagenten. */
    @Override public long eingabeTokens() { return eingabe.get(); }
    @Override public long ausgabeTokens() { return ausgabe.get(); }

    /**
     * Was der Unteragent tut, erscheint eingerueckt als Hinweis, nicht als
     * eigene Werkzeugzeile -- sonst sieht es aus, als haette der Hauptagent
     * gelesen. Eingriffe werden mit Vorsilbe weitergereicht, damit der
     * Pruefstand sie getrennt zaehlen kann.
     */
    private static Beobachter darunter(Beobachter b) {
        return new Beobachter() {
            @Override public void werkzeugFertig(ToolCall tc, ToolResult r) {
                b.hinweis("  erkunde · " + tc.name() + " " + tc.kurz() + (r.istFehler() ? " (Fehler)" : ""));
            }
            @Override public void ereignis(String art) { b.ereignis("erkunde_" + art); }
            @Override public void strom(int d, int t) { b.strom(d, t); }
            @Override public void zug(int n, ChatResponse a, int budget) { }
        };
    }
}
