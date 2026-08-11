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
     *  zaehlt nur, dass sie sich nie aendert. */
    public static ToolRegistry vorgabe() {
        return new ToolRegistry()
                .fuegeHinzu(new GlobTool())
                .fuegeHinzu(new GrepTool())
                .fuegeHinzu(new ReadTool())
                .fuegeHinzu(new WriteTool())
                .fuegeHinzu(new EditTool())
                .fuegeHinzu(new BashTool());
    }

    public List<ToolSpec> specs() { return List.copyOf(specs); }

    /**
     * Fuehrt einen Aufruf aus. Faengt alles ab, was das Werkzeug wirft:
     * ein Fehlschlag ist ein Ergebnis fuer das Modell, kein Abbruch des Laufs.
     * Das Modell kann daraufhin einen anderen Weg waehlen — genau dafuer ist
     * {@code istFehler} da.
     */
    public Tool.ToolResult fuehreAus(ToolCall tc, Workspace ws) {
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
