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

        // ----------------------------------------------------- Freigabetasten
        // Vorher galt jede Taste ausser 'j' als Ablehnung. Wer waehrend eines
        // langen Zuges weitertippte, lehnte damit unbemerkt Kommandos ab --
        // gemessen an einem '/', dem ersten Zeichen von "/ende".
        pruefe("j fuehrt aus",     Sitzung.freigabeAntwort('j'), Sitzung.Antwort.JA);
        pruefe("n lehnt ab",       Sitzung.freigabeAntwort('n'), Sitzung.Antwort.NEIN);
        pruefe("Ctrl-C lehnt ab",  Sitzung.freigabeAntwort(3),   Sitzung.Antwort.NEIN);
        pruefe("f fuehrt aus und schaltet das Fragen ab",
               Sitzung.freigabeAntwort('f'), Sitzung.Antwort.IMMER);
        pruefe("F wie f", Sitzung.freigabeAntwort('F'), Sitzung.Antwort.IMMER);
        pruefe("Eingabetaste ist keine Zustimmung",
               Sitzung.freigabeAntwort('\r'), Sitzung.Antwort.KEINE);
        pruefe("Schraegstrich ist keine Antwort",
               Sitzung.freigabeAntwort('/'), Sitzung.Antwort.KEINE);
        pruefe("Buchstabe eines Auftrags ist keine Antwort",
               Sitzung.freigabeAntwort('e'), Sitzung.Antwort.KEINE);
        pruefe("Leertaste ist keine Antwort",
               Sitzung.freigabeAntwort(' '), Sitzung.Antwort.KEINE);
        // Ctrl-F schaltet waehrend eines Laufs um und darf deshalb keine
        // Antwort auf eine offene Freigabefrage sein -- sonst wuerde derselbe
        // Tastendruck je nach Zeitpunkt zwei verschiedene Dinge tun.
        pruefe("Ctrl-F ist keine Freigabeantwort",
               Sitzung.freigabeAntwort(6), Sitzung.Antwort.KEINE);
        // 'f' und 'j' duerfen nicht verwechselbar sein: 'f' schaltet dauerhaft
        // ab, das soll kein Vertipper erledigen.
        pruefe("j und f sind verschiedene Antworten",
               Sitzung.freigabeAntwort('j') != Sitzung.freigabeAntwort('f'), true);

        // ----------------------------------------------------------- Uebergabe
        // Der Auftrag muss alles nennen, was jemand braucht, der den Verlauf
        // nicht kennt. Fehlt hier ein Punkt, merkt man es erst, wenn der
        // Verlauf schon weg ist -- und dann ist es zu spaet.
        {
            String a = Sitzung.uebergabeAuftrag("NOTIZEN.md");
            pruefe("Uebergabe: nennt die Zieldatei", a.contains("NOTIZEN.md"), true);
            pruefe("Uebergabe: verlangt das write-Werkzeug", a.contains("write"), true);
            pruefe("Uebergabe: fragt nach dem Ziel", a.contains("Ziel der Aufgabe"), true);
            pruefe("Uebergabe: fragt nach Fertigem", a.contains("was fertig ist"), true);
            pruefe("Uebergabe: fragt nach Offenem", a.contains("noch offen"), true);
            pruefe("Uebergabe: fragt nach Entscheidungen", a.contains("Entscheidungen"), true);
            pruefe("Uebergabe: fragt nach Ungepruefsem", a.contains("ungeprueft"), true);
            pruefe("Uebergabe: verbietet Weiterarbeit danach",
                   a.contains("danach nichts weiter"), true);
            pruefe("Uebergabe: eigener Dateiname wird uebernommen",
                   Sitzung.uebergabeAuftrag("projekt.md").contains("projekt.md"), true);
        }

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
        // Der Modus muss waehrend eines Zuges sichtbar sein -- ob noch gefragt
        // wird, ist die eine Einstellung, bei der Raten teuer werden kann.
        pruefe("Statuszeile zeigt den freien Modus",
               gezeichnet(a -> { a.setzeFrei(true); a.statusStarten("denkt"); a.statusBeenden(); }),
               t -> t.contains("frei"));
        pruefe("ohne freien Modus steht da nichts",
               gezeichnet(a -> { a.setzeFrei(false); a.statusStarten("denkt"); a.statusBeenden(); }),
               t -> !t.contains("frei"));

        pruefe("Ausgabe loescht die Statuszeile",
               gezeichnet(a -> a.zeile("text")),
               t -> t.startsWith(Terminal.ZEILE_LOESCHEN));

        // Mehrzeiliger Text muss je Zeile zurueckspringen. Ohne das laeuft die
        // Ausgabe treppenfoermig nach rechts aus dem Bild -- so gesehen bei
        // /hilfe, das den ganzen Block in einem Aufruf schickte.
        pruefe("mehrzeiliger Text bekommt ueberall Wagenruecklauf",
               gezeichnet(a -> a.zeile("eins\nzwei\ndrei")),
               t -> t.contains("eins\r\n") && t.contains("zwei\r\n") && t.contains("drei\r\n"));
        pruefe("mehrzeilig: kein nacktes \\n bleibt uebrig",
               gezeichnet(a -> a.zeile("eins\nzwei")),
               t -> !t.replace("\r\n", "").contains("\n"));
        pruefe("mehrzeilig: jede Zeile loescht die Statuszeile",
               gezeichnet(a -> a.zeile("eins\nzwei")),
               t -> t.split("eins")[1].startsWith("\r\n" + Terminal.ZEILE_LOESCHEN));

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
