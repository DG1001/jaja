package de.dg1001.harness.tools;

import de.dg1001.harness.ws.Workspace;

import java.nio.file.Path;
import java.util.Map;

/**
 * Ein Werkzeug, das das Modell aufrufen kann.
 *
 * <p>{@code beschreibung()} sagt, WANN man das Werkzeug nimmt, nicht was es
 * technisch tut. Das ist keine Stilfrage: die Beschreibung steht in jedem
 * einzelnen Zug im Kontext, und drei Absaetze ueber Kodierung und Zeilenenden
 * kosten bei jedem Aufruf Tokens, ohne die Entscheidung zu verbessern.
 * Zielgroesse fuer alle Werkzeuge zusammen: unter 400 Tokens.
 */
public interface Tool {

    String name();

    String beschreibung();

    /** JSON-Schema der Parameter, als fertige Zeichenkette. */
    String parameterSchema();

    /**
     * @param args geparste Argumente; fehlende Felder sind schlicht nicht
     *             enthalten, das Werkzeug prueft selbst
     */
    ToolResult run(Map<String, Object> args, Workspace ws) throws Exception;

    /**
     * Token, die das Werkzeug selbst beim Modell verbraucht hat, insgesamt.
     * Null fuer fast alle; ein Werkzeug, das einen Unteragenten startet,
     * meldet hier dessen Verbrauch, damit die Summen des Laufs stimmen.
     */
    default long eingabeTokens() { return 0; }
    default long ausgabeTokens() { return 0; }

    // ---------------------------------------------------------- Ergebnis

    /**
     * @param text        was das Modell zu sehen bekommt
     * @param istFehler   getrennt vom Text, damit der Agent Fehlschlaege zaehlen
     *                    kann, ohne im Text zu suchen
     * @param ausgelagert Pfad der vollstaendigen Ausgabe, falls gekuerzt wurde
     * @param anzeige     nur fuer Menschen, etwa die geaenderten Zeilen eines
     *                    edit; geht nie ans Modell, oder null
     */
    record ToolResult(String text, boolean istFehler, Path ausgelagert, String anzeige) {

        public ToolResult(String text, boolean istFehler, Path ausgelagert) {
            this(text, istFehler, ausgelagert, null);
        }

        public ToolResult mitAnzeige(String a) {
            return new ToolResult(text, istFehler, ausgelagert, a);
        }

        public static ToolResult ok(String t) {
            return new ToolResult(t, false, null);
        }

        public static ToolResult fehler(String t) {
            return new ToolResult(t, true, null);
        }
    }

    // ------------------------------------------------ Hilfen fuer Argumente

    static String text(Map<String, Object> args, String name) {
        Object o = args.get(name);
        return (o instanceof String s) ? s : null;
    }

    static String pflichtText(Map<String, Object> args, String name) {
        String s = text(args, name);
        if (s == null || s.isBlank())
            throw new IllegalArgumentException("Feld '" + name + "' fehlt");
        return s;
    }

    static int zahl(Map<String, Object> args, String name, int ersatz) {
        Object o = args.get(name);
        return (o instanceof Number n) ? n.intValue() : ersatz;
    }
}
