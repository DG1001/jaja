package de.dg1001.harness.tools;

import de.dg1001.harness.wire.Json;
import de.dg1001.harness.wire.Messages.ToolCall;
import de.dg1001.harness.wire.Messages.ToolSpec;
import de.dg1001.harness.ws.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Name → Werkzeug, mit stabiler Reihenfolge.
 *
 * <p>Die Reihenfolge ist der Punkt. Die Werkzeugliste steht ganz vorn im Prompt,
 * noch vor dem Gespraech. Wechselt sie zwischen zwei Zuegen, ist der
 * Praefix-Cache hin — und Prefill ist auf lokaler Hardware der teure Teil (bei
 * einem gemessenen Lauf lag die Trefferquote bei 63,5 %, das ist nichts, was man
 * durch eine Umsortierung wegwerfen will).
 *
 * <p>Deshalb: {@link LinkedHashMap}, Einfuegereihenfolge, und {@link #specs()}
 * liefert immer dieselbe Liste. Nicht nachtraeglich sortieren, nicht nach
 * Verwendungshaeufigkeit umordnen, nicht je nach Aufgabe filtern.
 */
public final class ToolRegistry {

    private final Map<String, Tool> werkzeuge = new LinkedHashMap<>();
    private final List<ToolSpec> specs = new ArrayList<>();

    public ToolRegistry fuegeHinzu(Tool t) {
        if (werkzeuge.put(t.name(), t) != null)
            throw new IllegalStateException("Werkzeugname doppelt: " + t.name());
        specs.add(new ToolSpec(t.name(), t.beschreibung(), t.parameterSchema()));
        return this;
    }

    /** Die Vorgabeausstattung. Reihenfolge ab hier unveraenderlich.
     *
     *  <p>Sortierung nach Arbeitsablauf, nicht nach Alphabet: erst schauen
     *  (glob, grep, read), dann aendern (write, edit), zuletzt das Universal-
     *  werkzeug (bash). Das ist reine Lesbarkeit fuer Menschen -- fuers Modell
     *  zaehlt nur, dass sie sich nie aendert.
     *
     *  <p>karte kam spaeter dazu und steht deshalb hinten, nicht vorne bei den
     *  anderen Schau-Werkzeugen: jede Einfuegung weiter vorn haette den
     *  Praefix-Cache aller folgenden Werkzeuge verschoben. */
    public static ToolRegistry vorgabe() { return vorgabe(false); }

    /**
     * @param mitKarte true nimmt die Quellenkarte dazu.
     *
     * <p>Sie ist per Vorgabe <em>aus</em>, und das ist ein Messergebnis: ueber
     * 735 Laeufe in zwei Versuchsreihen brachte sie keinen nachweisbaren
     * Vorteil, kostete aber ungefaehr den einen Werkzeugaufruf, den sie
     * braucht (15,0 gegen 14,5 Aufrufe). Grundlast in jeder Anfrage fuer
     * etwas, das nichts eintraegt, ist der falsche Handel — auch wenn es
     * nicht schadet.
     */
    public static ToolRegistry vorgabe(boolean mitKarte) {
        ToolRegistry r = new ToolRegistry()
                .fuegeHinzu(new GlobTool())
                .fuegeHinzu(new GrepTool())
                .fuegeHinzu(new ReadTool())
                .fuegeHinzu(new WriteTool())
                .fuegeHinzu(new EditTool())
                .fuegeHinzu(new BashTool());
        return mitKarte ? r.fuegeHinzu(new KarteTool()) : r;
    }

    public List<ToolSpec> specs() { return List.copyOf(specs); }

    /** Was die Werkzeuge selbst beim Modell verbraucht haben (Unteragenten). */
    public long eingabeTokens() {
        return werkzeuge.values().stream().mapToLong(Tool::eingabeTokens).sum();
    }

    public long ausgabeTokens() {
        return werkzeuge.values().stream().mapToLong(Tool::ausgabeTokens).sum();
    }

    /**
     * Fuehrt einen Aufruf aus. Faengt alles ab, was das Werkzeug wirft:
     * ein Fehlschlag ist ein Ergebnis fuer das Modell, kein Abbruch des Laufs.
     * Das Modell kann daraufhin einen anderen Weg waehlen — genau dafuer ist
     * {@code istFehler} da.
     */
    public Tool.ToolResult fuehreAus(ToolCall tc, Workspace ws) {
        return fuehreAus(tc, ws, 0);
    }

    /**
     * Wie {@link #fuehreAus(ToolCall, Workspace)}, kennt aber die Ausgabegrenze
     * des Zuges, falls dieser an ihr endete.
     *
     * <p>Der Unterschied zeigt sich nur im Fehlerfall, und dort entscheidet er
     * daruber, ob das Modell sich erholen kann. Wird ein Aufruf mitten im
     * JSON abgeschnitten, ist die Meldung "kein gueltiges JSON" zwar wahr, aber
     * als Diagnose falsch: sie liest sich wie ein Tippfehler, und ein Modell,
     * das einen Tippfehler vermutet, schickt denselben zu grossen Aufruf noch
     * einmal. Gemessen an einem Lauf, der eine komplette index.html in einem
     * {@code write} unterbringen wollte, an der Grenze abgeschnitten wurde und
     * danach im Kreis lief, bis der Kontext voll war.
     *
     * @param ausgabegrenze die erschoepfte Grenze in Token, oder 0, wenn der
     *                      Zug nicht an ihr endete
     */
    public Tool.ToolResult fuehreAus(ToolCall tc, Workspace ws, int ausgabegrenze) {
        Tool t = werkzeuge.get(tc.name());
        if (t == null)
            return Tool.ToolResult.fehler("unbekanntes Werkzeug: " + tc.name()
                    + " (vorhanden: " + String.join(", ", werkzeuge.keySet()) + ")");

        Map<String, Object> args;
        try {
            args = Json.obj(Json.parse(tc.argumentsJson()));
        } catch (RuntimeException e) {
            // Kommt vor: manche Modelle liefern abgeschnittenes oder leicht
            // fehlerhaftes JSON in arguments. Dem Modell sagen, nicht abbrechen.
            if (ausgabegrenze > 0)
                return Tool.ToolResult.fehler(
                        "Deine Argumente wurden an der Ausgabegrenze von " + ausgabegrenze
                      + " Token abgeschnitten, bevor das JSON zu Ende war. Das ist kein "
                      + "Tippfehler — derselbe Aufruf noch einmal wird genauso enden. "
                      + "Schreibe weniger pro Aufruf: erst ein knappes Geruest anlegen, "
                      + "die weiteren Teile danach einzeln mit edit anhaengen. "
                      + "Erhalten: " + kurz(tc.argumentsJson()));
            return Tool.ToolResult.fehler("Argumente sind kein gueltiges JSON: "
                    + e.getMessage() + " — erhalten: " + kurz(tc.argumentsJson()));
        }

        try {
            return t.run(args, ws);
        } catch (Exception e) {
            String m = e.getMessage();
            return Tool.ToolResult.fehler(e.getClass().getSimpleName()
                    + (m == null ? "" : ": " + m));
        }
    }

    private static String kurz(String s) {
        if (s == null) return "(leer)";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
