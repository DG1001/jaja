package de.dg1001.harness.agent;

import de.dg1001.harness.tools.Tool;
import de.dg1001.harness.wire.Messages.ChatResponse;
import de.dg1001.harness.wire.Messages.ToolCall;

/**
 * Sieht der Schleife bei der Arbeit zu.
 *
 * <p>Existiert, damit {@link Agent} nichts darueber weiss, wohin sein
 * Fortschritt geht. Im Stapelbetrieb ist das {@link #STDERR} und landet im
 * Protokoll; in der Oberflaeche ist es die Anzeige, die daraus Zeilen und eine
 * lebende Statuszeile macht.
 *
 * <p>{@link #STDERR} bildet das frueher fest eingebaute Verhalten Zeichen fuer
 * Zeichen nach. Das ist kein Zufall: der Pruefstand liest die Schlusszeile mit
 * grep, und die Messwerte im Bericht stammen aus genau diesem Format.
 */
public interface Beobachter {

    /** Ein Zug ist beantwortet. */
    default void zug(int nummer, ChatResponse a, int budget) {}

    /** Ein Werkzeug wird gleich ausgefuehrt. */
    default void werkzeugStart(ToolCall tc) {}

    /** Ein Werkzeug ist fertig. */
    default void werkzeugFertig(ToolCall tc, Tool.ToolResult r) {}

    /** Kuerzung, Wiederholung, Anstoss — alles, was der Harness selbst tut. */
    default void hinweis(String text) {}

    Beobachter STILL = new Beobachter() {};

    Beobachter STDERR = new Beobachter() {
        @Override public void zug(int nummer, ChatResponse a, int budget) {
            melde("Zug %d: %s, %d Werkzeug(e), %d/%d Token",
                  nummer, a.finishReason(), a.message().toolCalls().size(),
                  a.usage().promptTokens(), budget);
            // Und das, was das Modell selbst dazu sagt.
            //
            // Ohne diese Zeile stehen im Protokoll nur Zahlen und Werkzeug-
            // namen -- man sieht, DASS gearbeitet wird, aber nicht, woran. Bei
            // einem zweistuendigen Lauf ist das der Unterschied zwischen "es
            // baut gerade die Speicherschicht" und Ratenmuessen.
            //
            // Gekuerzt, weil der Text ganze Absaetze lang sein kann. Wer alles
            // sehen will, nimmt die Sitzung; hier geht es um den Stapelbetrieb.
            String gesagt = a.message().content();
            if (gesagt == null || gesagt.isBlank()) gesagt = a.message().reasoningContent();
            sag(gesagt);
        }
        @Override public void werkzeugFertig(ToolCall tc, Tool.ToolResult r) {
            // Mit Argument: ohne das war im Stapelbetrieb nicht rekonstruierbar,
            // WAS ein Lauf getan hat -- nur wie viele Zeichen dabei herauskamen.
            // Der Pruefstand liest die Schlusszeile, nicht diese, also aendert
            // die zusaetzliche Spalte keine veroeffentlichte Messung.
            melde("  %s%s -> %d Zeichen | %s", tc.name(), r.istFehler() ? " (Fehler)" : "",
                  r.text() == null ? 0 : r.text().length(), tc.kurz());
        }
        @Override public void hinweis(String text) { melde("%s", text); }

        private void melde(String fmt, Object... a) {
            System.err.println("[harness] " + String.format(fmt, a));
        }

        /** Hoechstens so viele Zeichen des Modelltextes je Zug. */
        private static final int MAX_TEXT = 220;

        private void sag(String text) {
            if (text == null || text.isBlank()) return;
            String s = text.strip().replaceAll("\\s+", " ");
            if (s.length() > MAX_TEXT) s = s.substring(0, MAX_TEXT) + " ...";
            melde("  \u00bb%s\u00ab", s);
        }
    };
}
