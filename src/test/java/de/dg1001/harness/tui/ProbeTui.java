package de.dg1001.harness.tui;

import de.dg1001.harness.tools.Tool;
import de.dg1001.harness.wire.Messages.ToolCall;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Prueft Zeileneditor und Anzeige — ohne Terminal und ohne Modellserver.
 *
 * <p>Beides schreibt nach {@code System.out}; fuer die Pruefung wird der Strom
 * umgeleitet. Der Editor liest aus einem {@code ByteArrayInputStream}, also
 * genau dem, was im Rohmodus auch von der Tastatur kaeme: rohe Bytes samt
 * Steuerfolgen.
 *
 * <p>Der Rohmodus selbst ({@code stty}) laesst sich so nicht pruefen — das
 * braucht ein echtes Terminal. Was hier steht, ist alles darueber: die
 * Tastenbehandlung, die UTF-8-Zusammensetzung und die Formatierung.
 */
public final class ProbeTui {

    private static int fehlgeschlagen = 0;

    public static void main(String[] args) throws Exception {

        // ------------------------------------------------------ Zeileneditor
        pruefe("einfache Zeile", tippe("hallo\r").text(), "hallo");
        pruefe("Leerraum wird abgeschnitten", tippe("  hallo  \r").text(), "hallo");
        pruefe("leere Zeile meldet LEER", tippe("\r").art(), Eingabe.Art.LEER);

        pruefe("Ruecktaste loescht", tippe("abcX\177\r").text(), "abc");
        pruefe("Ruecktaste am Anfang tut nichts", tippe("\177\177ab\r").text(), "ab");

        pruefe("Ctrl-D auf leerer Zeile beendet", tippe("\004").art(), Eingabe.Art.ENDE);
        pruefe("Ctrl-C verwirft die Zeile", tippe("abc\003").art(), Eingabe.Art.LEER);
        // Wichtig: Ctrl-C darf die Sitzung NICHT beenden -- sonst verliert man
        // bei einem Vertipper den ganzen Verlauf.
        pruefe("Ctrl-C beendet die Sitzung nicht", tippe("abc\003").art() != Eingabe.Art.ENDE, true);

        // Steuerfolgen: ESC [ D ist "links"
        pruefe("links und einfuegen", tippe("ac\033[Db\r").text(), "abc");
        pruefe("Ctrl-A springt an den Anfang", tippe("bc\001a\r").text(), "abc");
        pruefe("Ctrl-E springt ans Ende", tippe("bc\001a\005d\r").text(), "abcd");
        pruefe("Ctrl-U loescht bis zum Anfang", tippe("weg\025neu\r").text(), "neu");
        pruefe("Entf loescht nach rechts", tippe("abXc\033[D\033[D\033[3~\r").text(), "abc");

        // ---------------------------------------------- Verlauf mit Pfeiltasten
        {
            Eingabe e = neu("zweiter\r");
            e.merke("erster");
            e.merke("zweiter");
            pruefe("Pfeil hoch holt den letzten Auftrag",
                   lies(e, "\033[A\r").text(), "zweiter");
            pruefe("zweimal hoch holt den vorletzten",
                   lies(e, "\033[A\033[A\r").text(), "erster");
            pruefe("hoch und runter kehrt zurueck",
                   lies(e, "\033[A\033[A\033[B\r").text(), "zweiter");
            pruefe("hoch, dann weitertippen",
                   lies(e, "\033[A!\r").text(), "zweiter!");
        }
        {
            Eingabe e = neu("");
            e.merke("einmal"); e.merke("einmal");
            pruefe("gleiche Zeile landet nicht doppelt im Verlauf",
                   lies(e, "\033[A\033[A\r").text(), "einmal");
        }

        // ------------------------------------------------- vorbelegte Zeile
        // Was waehrend eines laufenden Zuges getippt wurde, muss beim naechsten
        // Prompt dastehen -- und bearbeitbar sein, nicht bloss angezeigt.
        pruefe("vorbelegter Text steht da", lies(neu("\r"), null, "schon getippt").text(),
               "schon getippt");
        pruefe("vorbelegt und weitergetippt",
               lies(neu("!\r"), null, "schon").text(), "schon!");
        pruefe("Cursor steht am Ende des Vorbelegten",
               lies(neu("\177x\r"), null, "abc").text(), "abx");

        // --------------------------------------------------------- UTF-8
        pruefe("Umlaut kommt als ein Zeichen an", tippe("gr\u00fcn\r").text(), "grün");
        pruefe("Ruecktaste loescht den ganzen Umlaut", tippe("gr\u00fc\177n\r").text(), "grn");
        pruefe("drei Byte: Zeichen jenseits der BMP-Grenze",
               tippe("a\u20acb\r").text(), "a€b");
        pruefe("vier Byte: Emoji", tippe("a\uD83D\uDE00b\r").text(), "a\uD83D\uDE00b");
        pruefe("liesZeichen liefert Codepunkte",
               Terminal.liesZeichen(strom("\u00fc")), (int) 'ü');

        // ------------------------------------------------------------ Anzeige
        pruefe("Werkzeugzeile nennt Name und Argument",
               gezeichnet(a -> a.werkzeugFertig(
                       new ToolCall("i", "read", "{\"pfad\":\"src/haupt.py\"}"),
                       Tool.ToolResult.ok("eins\nzwei\ndrei"))),
               t -> t.contains("read") && t.contains("src/haupt.py") && t.contains("3 Zeilen"));

        pruefe("bash zeigt das Kommando, nicht das JSON",
               gezeichnet(a -> a.werkzeugFertig(
                       new ToolCall("i", "bash", "{\"kommando\":\"pytest -q\"}"),
                       Tool.ToolResult.ok("12 passed"))),
               t -> t.contains("pytest -q") && !t.contains("kommando"));

        pruefe("einzeiliges Ergebnis wird gezeigt",
               gezeichnet(a -> a.werkzeugFertig(
                       new ToolCall("i", "bash", "{\"kommando\":\"ls\"}"),
                       Tool.ToolResult.ok("12 passed"))),
               t -> t.contains("12 passed"));

        pruefe("Fehler wird rot gezeichnet",
               gezeichnet(a -> a.werkzeugFertig(
                       new ToolCall("i", "read", "{\"pfad\":\"weg.txt\"}"),
                       Tool.ToolResult.fehler("gibt es nicht"))),
               t -> t.contains(Terminal.ROT) && t.contains("gibt es nicht"));

        pruefe("kaputte Argumente stuerzen die Anzeige nicht ab",
               gezeichnet(a -> a.werkzeugFertig(
                       new ToolCall("i", "read", "{kaputt"),
                       Tool.ToolResult.ok("x"))),
               t -> t.contains("read"));

        pruefe("Zeilenumbruch im Argument bleibt einzeilig",
               gezeichnet(a -> a.werkzeugFertig(
                       new ToolCall("i", "bash", "{\"kommando\":\"eins\\nzwei\"}"),
                       Tool.ToolResult.ok("x"))),
               t -> !t.substring(0, t.length() - 2).contains("\n"));

        // Jede gewoehnliche Zeile muss die Statuszeile vorher wegwischen --
        // sonst bleiben Reste der alten Statuszeile im Rueckblick stehen.
        pruefe("Ausgabe loescht die Statuszeile",
               gezeichnet(a -> a.zeile("text")),
               t -> t.startsWith(Terminal.ZEILE_LOESCHEN));

        pruefe("Rohmodus braucht Wagenruecklauf",
               gezeichnet(a -> a.zeile("text")), t -> t.endsWith("\r\n"));

        System.out.flush();
        System.out.println(fehlgeschlagen == 0
                ? "\nAlle Pruefungen bestanden."
                : "\n" + fehlgeschlagen + " Pruefung(en) fehlgeschlagen.");
        if (fehlgeschlagen > 0) System.exit(1);
    }

    // --------------------------------------------------------------- Zutaten

    private static ByteArrayInputStream strom(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Eingabe neu(String s) { return new Eingabe(strom(s)); }

    /** Tippt die Zeichenfolge in einen frischen Editor. */
    private static Eingabe.Ergebnis tippe(String s) throws IOException {
        return lies(neu(s), null);
    }

    /** Liest aus dem Editor; Ausgabe wird verworfen. */
    private static Eingabe.Ergebnis lies(Eingabe e, String nachschub) throws IOException {
        return lies(e, nachschub, "");
    }

    private static Eingabe.Ergebnis lies(Eingabe e, String nachschub, String vorbelegt)
            throws IOException {
        if (nachschub != null) e = uebernimm(e, nachschub);
        PrintStream alt = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try { return e.lies("› ", vorbelegt); }
        finally { System.setOut(alt); }
    }

    /** Neuer Editor mit demselben Verlauf, aber frischer Eingabe. */
    private static Eingabe uebernimm(Eingabe alt, String eingabe) {
        Eingabe e = neu(eingabe);
        for (String z : verlaufVon(alt)) e.merke(z);
        return e;
    }

    /** Der Verlauf ist privat; fuer die Pruefung reicht die Reihenfolge, in
     *  der wir ihn selbst gefuellt haben. */
    private static java.util.List<String> verlaufVon(Eingabe e) {
        try {
            var f = Eingabe.class.getDeclaredField("verlauf");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var l = (java.util.List<String>) f.get(e);
            return java.util.List.copyOf(l);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Faengt ab, was die Anzeige schreibt. */
    private static String gezeichnet(java.util.function.Consumer<Anzeige> was) {
        PrintStream alt = System.out;
        ByteArrayOutputStream puffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(puffer, true, StandardCharsets.UTF_8));
        try { was.accept(new Anzeige(60)); }
        finally { System.setOut(alt); }
        return puffer.toString(StandardCharsets.UTF_8);
    }

    // --------------------------------------------------------------- Pruefen

    private static void pruefe(String was, Object erhalten, Object erwartet) {
        boolean ok = (erhalten == null) ? erwartet == null : erhalten.equals(erwartet);
        melde(was, ok);
        if (!ok) System.out.println("    erwartet: " + zeige(erwartet)
                                  + "\n    erhalten: " + zeige(erhalten));
    }

    private static void pruefe(String was, String text,
                               java.util.function.Predicate<String> bedingung) {
        boolean ok = text != null && bedingung.test(text);
        melde(was, ok);
        if (!ok) System.out.println("    erhalten: " + zeige(text));
    }

    private static void melde(String was, boolean ok) {
        System.out.printf("%-56s %s%n", was, ok ? "ok" : "FEHLGESCHLAGEN");
        if (!ok) fehlgeschlagen++;
    }

    private static String zeige(Object o) {
        if (o == null) return "null";
        return o.toString().replace("\033", "\\e").replace("\n", "\\n").replace("\r", "\\r");
    }
}
