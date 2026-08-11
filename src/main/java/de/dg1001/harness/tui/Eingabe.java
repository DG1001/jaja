package de.dg1001.harness.tui;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Eine Eingabezeile im Rohmodus: Bearbeiten, Verlauf, Abbruch.
 *
 * <p>Im Rohmodus macht das Terminal nichts mehr von allein — keine
 * Ruecktaste, kein Echo, keine Pfeiltasten. Das alles steht hier. Der Umfang
 * ist ueberschaubar, solange man sich auf das beschraenkt, was man an einer
 * Aufgabenzeile wirklich tut: tippen, korrigieren, den letzten Auftrag
 * zurueckholen.
 *
 * <p>Nicht enthalten und auch nicht vermisst: Wortsprung, Suche im Verlauf,
 * Zwischenablage. Wer das braucht, nimmt JLine — und damit die erste
 * Abhaengigkeit des Projekts.
 */
public final class Eingabe {

    /** Was der Nutzer zuletzt getan hat. */
    public enum Art { ZEILE, ENDE, LEER }

    public record Ergebnis(Art art, String text) {}

    private final InputStream in;
    private final List<String> verlauf = new ArrayList<>();

    public Eingabe(InputStream in) { this.in = in; }

    public void merke(String zeile) {
        if (zeile != null && !zeile.isBlank()
                && (verlauf.isEmpty() || !verlauf.get(verlauf.size() - 1).equals(zeile)))
            verlauf.add(zeile);
    }

    public Ergebnis lies(String eingabeaufforderung) throws IOException {
        StringBuilder b = new StringBuilder();
        int cursor = 0;
        int imVerlauf = verlauf.size();     // hinter dem letzten Eintrag
        String angefangen = "";

        zeichne(eingabeaufforderung, b, cursor);

        while (true) {
            int c = Terminal.liesZeichen(in);

            if (c < 0 || c == 4) {                       // Strom zu Ende / Ctrl-D
                if (b.length() == 0) { System.out.print("\r\n"); return new Ergebnis(Art.ENDE, null); }
                continue;
            }

            if (c == 3) {                                // Ctrl-C: Zeile verwerfen
                System.out.print("\r\n");
                return new Ergebnis(Art.LEER, null);
            }

            if (c == '\r' || c == '\n') {
                System.out.print("\r\n");
                String s = b.toString().trim();
                return s.isEmpty() ? new Ergebnis(Art.LEER, null)
                                   : new Ergebnis(Art.ZEILE, s);
            }

            if (c == 127 || c == 8) {                    // Ruecktaste
                if (cursor > 0) { b.deleteCharAt(cursor - 1); cursor--; }
                zeichne(eingabeaufforderung, b, cursor);
                continue;
            }

            if (c == 27) {                               // Steuerfolge
                int a = Terminal.liesZeichen(in);
                if (a != '[' && a != 'O') continue;
                int d = Terminal.liesZeichen(in);
                switch (d) {
                    case 'A' -> {                        // hoch
                        if (imVerlauf == verlauf.size()) angefangen = b.toString();
                        if (imVerlauf > 0) {
                            imVerlauf--;
                            b.setLength(0); b.append(verlauf.get(imVerlauf));
                            cursor = b.length();
                        }
                    }
                    case 'B' -> {                        // runter
                        if (imVerlauf < verlauf.size()) {
                            imVerlauf++;
                            b.setLength(0);
                            b.append(imVerlauf == verlauf.size() ? angefangen
                                                                 : verlauf.get(imVerlauf));
                            cursor = b.length();
                        }
                    }
                    case 'C' -> { if (cursor < b.length()) cursor++; }   // rechts
                    case 'D' -> { if (cursor > 0) cursor--; }            // links
                    case 'H' -> cursor = 0;
                    case 'F' -> cursor = b.length();
                    case '3' -> {                        // Entf: "^[[3~"
                        Terminal.liesZeichen(in);
                        if (cursor < b.length()) b.deleteCharAt(cursor);
                    }
                    default -> { }
                }
                zeichne(eingabeaufforderung, b, cursor);
                continue;
            }

            if (c == 1)  { cursor = 0;          zeichne(eingabeaufforderung, b, cursor); continue; }
            if (c == 5)  { cursor = b.length(); zeichne(eingabeaufforderung, b, cursor); continue; }
            if (c == 21) { b.delete(0, cursor); cursor = 0; zeichne(eingabeaufforderung, b, cursor); continue; }
            if (c < 32) continue;                        // uebrige Steuerzeichen

            b.insert(cursor, Character.toChars(c));
            cursor += Character.charCount(c);
            zeichne(eingabeaufforderung, b, cursor);
        }
    }

    /**
     * Zeichnet die Zeile neu.
     *
     * <p>Immer ganz, nie stueckweise: bei einer Zeile Text ist der Aufwand
     * belanglos, und jede Sonderbehandlung waere eine Gelegenheit fuer einen
     * Anzeigefehler, den man dann bei genau einer Zeichenfolge sieht.
     */
    private static void zeichne(String eingabeaufforderung, StringBuilder b, int cursor) {
        System.out.print(Terminal.ZEILE_LOESCHEN);
        System.out.print(eingabeaufforderung);
        System.out.print(b);
        // Cursor an die richtige Stelle zuruecksetzen
        int zurueck = b.length() - cursor;
        if (zurueck > 0) System.out.print(Terminal.ESC + zurueck + "D");
        System.out.flush();
    }
}
