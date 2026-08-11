package de.dg1001.harness.wire;

/**
 * Prueft den handgeschriebenen JSON-Leser und -Schreiber.
 *
 * <p>Bis hierher war der Parser nur indirekt abgedeckt: ueber einen Rundlauf
 * gegen einen laufenden Modellserver. Das prueft den glatten Fall und sonst
 * nichts — und ausgerechnet die Faelle, die zaehlen, treten nur unter Last auf:
 * abgeschnittene Antworten, Sonderzeichen in Quelltext, eingebettetes JSON in
 * {@code arguments}.
 */
public final class ProbeJson {

    private static int fehlgeschlagen = 0;

    public static void main(String[] args) {

        // ----------------------------------------------------------- Grundformen
        pruefe("Objekt mit Feldern",
               Json.str(Json.feld(Json.parse("{\"a\":\"b\"}"), "a")), "b");
        pruefe("verschachteltes Objekt",
               Json.str(Json.feld(Json.feld(Json.parse("{\"a\":{\"b\":\"c\"}}"), "a"), "b")), "c");
        pruefe("leeres Objekt", Json.obj(Json.parse("{}")).size(), 0);
        pruefe("leere Liste", Json.arr(Json.parse("[]")).size(), 0);
        pruefe("Liste mit Werten", Json.arr(Json.parse("[1,2,3]")).size(), 3);
        pruefe("Wahrheitswert", Json.feld(Json.parse("{\"a\":true}"), "a"), Boolean.TRUE);
        pruefe("null bleibt null", Json.feld(Json.parse("{\"a\":null}"), "a"), null);
        pruefe("Zahl", Json.num(Json.feld(Json.parse("{\"a\":42}"), "a"), -1), 42);
        pruefe("negative Zahl", Json.num(Json.feld(Json.parse("{\"a\":-7}"), "a"), 0), -7);
        pruefe("Exponentialschreibweise",
               Json.num(Json.feld(Json.parse("{\"a\":1.5e3}"), "a"), 0), 1500);
        pruefe("Leerraum ueberall",
               Json.str(Json.feld(Json.parse("  {  \"a\" :  \"b\"  }  "), "a")), "b");

        // -------------------------------------------------------- Maskierungen
        pruefe("Zeilenumbruch entmaskiert",
               Json.str(Json.feld(Json.parse("{\"a\":\"x\\ny\"}"), "a")), "x\ny");
        pruefe("Anfuehrungszeichen entmaskiert",
               Json.str(Json.feld(Json.parse("{\"a\":\"sagt \\\"hallo\\\"\"}"), "a")),
               "sagt \"hallo\"");
        pruefe("Backslash entmaskiert",
               Json.str(Json.feld(Json.parse("{\"a\":\"C:\\\\pfad\"}"), "a")), "C:\\pfad");
        pruefe("Tabulator entmaskiert",
               Json.str(Json.feld(Json.parse("{\"a\":\"x\\ty\"}"), "a")), "x\ty");
        pruefe("\\u entmaskiert",
               Json.str(Json.feld(Json.parse("{\"a\":\"\\u00fcber\"}"), "a")), "über");
        pruefe("Umlaut roh bleibt erhalten",
               Json.str(Json.feld(Json.parse("{\"a\":\"grün\"}"), "a")), "grün");

        // ------------------------------------------- kaputte Eingaben MUESSEN werfen
        // Der Retry-Entscheider haengt daran: nur eine geworfene Ausnahme fuehrt
        // zu "Antwort nicht lesbar" und damit zu einem zweiten Versuch. Ein
        // stillschweigend halb geparster Wert waere schlimmer als ein Fehler.
        wirft("abgeschnittenes Objekt",        "{\"a\":\"b\"");
        wirft("abgeschnittene Zeichenkette",   "{\"a\":\"b");
        wirft("fehlender Doppelpunkt",         "{\"a\" \"b\"}");
        wirft("Feldname ohne Anfuehrungszeichen", "{a:\"b\"}");
        wirft("Zeichen nach dem Ende",         "{\"a\":\"b\"} sonstwas");
        wirft("leere Eingabe",                 "");
        wirft("abgeschnittenes \\u",           "{\"a\":\"\\u00\"}");
        wirft("unbekannte Maskierung",         "{\"a\":\"\\q\"}");

        // ------------------------------------------- nachsichtiger Zugriff
        // Bei Modellantworten fehlen Felder staendig. Die Helfer sollen einen
        // Ersatzwert liefern, statt den Lauf mit einer ClassCastException zu
        // beenden -- die Entscheidung, ob etwas fehlt, gehoert an die Aufrufstelle.
        pruefe("obj() auf Unsinn gibt leere Map", Json.obj("kein Objekt").size(), 0);
        pruefe("arr() auf Unsinn gibt leere Liste", Json.arr(42.0).size(), 0);
        pruefe("str() auf Zahl gibt null", Json.str(42.0), null);
        pruefe("str() unterscheidet fehlend von leer",
               Json.str(Json.feld(Json.parse("{\"a\":\"\"}"), "a")), "");
        pruefe("num() nimmt den Ersatzwert", Json.num("keine Zahl", 99), 99);
        pruefe("feld() auf Unsinn gibt null", Json.feld("kein Objekt", "a"), null);

        // ------------------------------------------------------------ Schreiber
        pruefe("Schreiber: einfaches Objekt",
               new Json.Writer().objektAuf().feld("a").text("b").objektZu().toString(),
               "{\"a\":\"b\"}");
        pruefe("Schreiber: zwei Felder bekommen ein Komma",
               new Json.Writer().objektAuf().feld("a").text("b")
                       .feld("c").zahl(1).objektZu().toString(),
               "{\"a\":\"b\",\"c\":1}");
        pruefe("Schreiber: Liste",
               new Json.Writer().listeAuf().text("a").text("b").listeZu().toString(),
               "[\"a\",\"b\"]");
        pruefe("Schreiber: null wird zu null, nicht zu \"\"",
               new Json.Writer().objektAuf().feld("a").text(null).objektZu().toString(),
               "{\"a\":null}");
        pruefe("Schreiber: textFeld laesst null ganz weg",
               new Json.Writer().objektAuf().textFeld("a", null)
                       .textFeld("b", "x").objektZu().toString(),
               "{\"b\":\"x\"}");
        pruefe("Schreiber: roh fuegt fertiges JSON ein",
               new Json.Writer().objektAuf().feld("s").roh("{\"type\":\"object\"}")
                       .objektZu().toString(),
               "{\"s\":{\"type\":\"object\"}}");
        pruefe("Schreiber: Steuerzeichen werden maskiert",
               new Json.Writer().text("a\u0001b").toString(), "\"a\\u0001b\"");

        // -------------------------------------------------------------- Rundlauf
        // Der Fall, der im Betrieb wirklich vorkommt: Quelltext mit Umbruechen,
        // Anfuehrungszeichen und Backslashes geht als Werkzeugargument raus und
        // muss unveraendert wieder ankommen.
        String quelltext = "def f(x):\n\treturn \"a\\tb\" + '\\n'  # grün\r\n";
        String geschrieben = new Json.Writer().objektAuf()
                .feld("inhalt").text(quelltext).objektZu().toString();
        pruefe("Rundlauf: Quelltext kommt unveraendert zurueck",
               Json.str(Json.feld(Json.parse(geschrieben), "inhalt")), quelltext);

        System.out.println(fehlgeschlagen == 0
                ? "\nAlle Pruefungen bestanden."
                : "\n" + fehlgeschlagen + " Pruefung(en) fehlgeschlagen.");
        if (fehlgeschlagen > 0) System.exit(1);
    }

    private static void pruefe(String was, Object erhalten, Object erwartet) {
        boolean ok = (erhalten == null) ? erwartet == null : erhalten.equals(erwartet);
        System.out.printf("%-52s %s%n", was, ok ? "ok" : "FEHLGESCHLAGEN");
        if (!ok) {
            fehlgeschlagen++;
            System.out.println("    erwartet: " + zeige(erwartet)
                             + "\n    erhalten: " + zeige(erhalten));
        }
    }

    private static void wirft(String was, String eingabe) {
        boolean ok;
        try { Json.parse(eingabe); ok = false; }
        catch (RuntimeException e) { ok = true; }
        System.out.printf("%-52s %s%n", "wirft: " + was, ok ? "ok" : "FEHLGESCHLAGEN");
        if (!ok) {
            fehlgeschlagen++;
            System.out.println("    kaputte Eingabe wurde stillschweigend angenommen");
        }
    }

    private static String zeige(Object o) {
        return o == null ? "null" : "\"" + o.toString().replace("\n", "\\n")
                                              .replace("\t", "\\t") + "\"";
    }
}
