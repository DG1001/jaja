package de.dg1001.harness.tools;

import de.dg1001.harness.wire.Messages.ToolCall;
import de.dg1001.harness.ws.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;

/** Prueft Registry, ReadTool, BashTool, Auslagerung und Pfadeingrenzung. */
public final class ProbeTools {

    private static int fehlgeschlagen = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("harness-probe");
        Workspace ws = new Workspace(tmp);
        ToolRegistry r = ToolRegistry.vorgabe();

        Files.writeString(tmp.resolve("hallo.txt"), "erste\nzweite\ndritte\n");
        Files.createDirectories(tmp.resolve("unter"));
        Files.writeString(tmp.resolve("unter/tief.txt"), "drin\n");

        pruefe("read: Zeilennummern",
               lauf(r, ws, "read", "{\"pfad\":\"hallo.txt\"}"),
               t -> t.contains("1\terste") && t.contains("3\tdritte"));

        pruefe("read: Ausschnitt",
               lauf(r, ws, "read", "{\"pfad\":\"hallo.txt\",\"ab_zeile\":2,\"zeilen\":1}"),
               t -> t.contains("2\tzweite") && !t.contains("erste"));

        // 300 Zeilen a ~50 Zeichen: frueher Kopf und Fuss, die Mitte nur als
        // Verweis auf eine Auslagerungsdatei.
        StringBuilder mittel = new StringBuilder();
        for (int i = 1; i <= 300; i++)
            mittel.append("    public int feld_nummer_").append(i).append(" = 0; // Kommentar\n");
        Files.writeString(tmp.resolve("Gross.java"), mittel.toString());
        pruefe("read: mittlere Datei kommt am Stueck",
               lauf(r, ws, "read", "{\"pfad\":\"Gross.java\"}"),
               t -> t.contains("150\t") && t.contains("300\t") && !t.contains("ausgelassen")
                    && !t.contains("weiterlesen"));

        StringBuilder riesig = new StringBuilder();
        for (int i = 1; i <= 2000; i++)
            riesig.append("zeile_").append(i).append(" ").append("x".repeat(40)).append('\n');
        Files.writeString(tmp.resolve("riesig.txt"), riesig.toString());
        String seite = lauf(r, ws, "read", "{\"pfad\":\"riesig.txt\"}");
        pruefe("read: grosse Datei endet an einer Zeilengrenze mit Weiterlesen",
               seite, t -> t.length() < ReadTool.MAX_ZEICHEN + 200
                       && t.contains("weiterlesen") && t.contains("1\tzeile_1 "));
        int weiter = Integer.parseInt(seite.replaceAll("(?s).*ab_zeile=(\\d+).*", "$1"));
        pruefe("read: Weiterlesen schliesst lueckenlos an",
               lauf(r, ws, "read", "{\"pfad\":\"riesig.txt\",\"ab_zeile\":" + weiter + "}"),
               t -> t.startsWith(weiter + "\tzeile_" + weiter + " ")
                    && !seite.contains("\n" + weiter + "\t"));

        Files.writeString(tmp.resolve("lang.js"), "a".repeat(50_000) + "\nzwei\n");
        pruefe("read: ueberlange Zeile wird gekuerzt, die naechste bleibt",
               lauf(r, ws, "read", "{\"pfad\":\"lang.js\"}"),
               t -> t.contains("Zeile gekuerzt") && t.contains("2\tzwei"));

        pruefe("read: fehlende Datei meldet Fehler",
               lauf(r, ws, "read", "{\"pfad\":\"gibtsnicht.txt\"}"),
               t -> t.toLowerCase().contains("gibt es nicht"));

        pruefe("read: Verzeichnis liefert Inhalt statt Fehler",
               lauf(r, ws, "read", "{\"pfad\":\"unter\"}"),
               t -> t.contains("tief.txt"));

        pruefe("Pfadeingrenzung: ../ wird abgewiesen",
               lauf(r, ws, "read", "{\"pfad\":\"../../etc/passwd\"}"),
               t -> t.contains("ausserhalb des Arbeitsbereichs"));

        pruefe("Pfadeingrenzung: absoluter Pfad wird abgewiesen",
               lauf(r, ws, "read", "{\"pfad\":\"/etc/passwd\"}"),
               t -> t.contains("ausserhalb des Arbeitsbereichs"));

        pruefe("bash: Ausgabe und Rueckgabewert",
               lauf(r, ws, "bash", "{\"kommando\":\"echo hallo && pwd\"}"),
               t -> t.contains("hallo") && t.contains("Rueckgabewert 0"));

        pruefe("bash: Arbeitsverzeichnis ist die Wurzel",
               lauf(r, ws, "bash", "{\"kommando\":\"ls\"}"),
               t -> t.contains("hallo.txt"));

        pruefe("bash: Fehlercode wird durchgereicht",
               lauf(r, ws, "bash", "{\"kommando\":\"exit 3\"}"),
               t -> t.contains("Rueckgabewert 3"));

        pruefe("bash: stderr landet in der Ausgabe",
               lauf(r, ws, "bash", "{\"kommando\":\"echo fehler >&2\"}"),
               t -> t.contains("fehler"));

        pruefe("bash: Zeitgrenze greift",
               lauf(r, ws, "bash", "{\"kommando\":\"sleep 30\",\"sekunden\":2}"),
               t -> t.contains("abgebrochen nach 2"));

        // Ein Server, den die Shell startet, ueberlebt sonst die Zeitgrenze:
        // destroy() trifft nur die Shell, das Kind wird an init umgehaengt und
        // haelt seinen Port weiter. Genau so an einem laufenden Flask-Server
        // beobachtet, der Port 5000 blockierte, nachdem der Aufruf laengst
        // abgebrochen war.
        {
            Path marke = tmp.resolve("kind-lebt-noch.txt");
            Files.deleteIfExists(marke);
            lauf(r, ws, "bash", "{\"kommando\":\"(sleep 30; touch kind-lebt-noch.txt) & sleep 30\","
                    + "\"sekunden\":2}");
            Thread.sleep(4000);
            pruefe("Zeitgrenze beendet auch die Kinder der Shell",
                   Files.exists(marke) ? "Kind lebt weiter" : "Kind beendet",
                   t -> t.equals("Kind beendet"));
        }

        // Auslagerung: 40.000 Zeichen erzeugen
        Tool.ToolResult gross = r.fuehreAus(
                new ToolCall("x", "bash",
                        "{\"kommando\":\"python3 -c \\\"print('z'*40000)\\\"\"}"), ws);
        try (var k = new BashTool.KopfFuss(10); var klein = new BashTool.KopfFuss(10)) {
            k.write("0123456789ABCDEFGHIJ".getBytes());
            pruefe("bash: Puffer behaelt Anfang und Ende, sagt was fehlt",
                   k.text(), t -> t.startsWith("01234") && t.endsWith("FGHIJ")
                                  && t.contains("10 Bytes verworfen"));
            klein.write("abc".getBytes());
            pruefe("bash: kleiner Puffer bleibt unveraendert", klein.text(), t -> t.equals("abc"));
        }

        pruefe("Auslagerung: gekuerzt und Pfad genannt",
               gross.text(),
               t -> t.contains("ausgelassen") && gross.ausgelagert() != null
                       && Files.exists(gross.ausgelagert()));

        pruefe("Registry: unbekanntes Werkzeug",
               lauf(r, ws, "gibtsnicht", "{}"),
               t -> t.contains("unbekanntes Werkzeug"));

        pruefe("Registry: kaputte Argumente brechen nicht ab",
               lauf(r, ws, "read", "{\"pfad\": \"hallo.txt\"  <-- kaputt"),
               t -> t.contains("kein gueltiges JSON"));

        pruefe("Registry: Werkzeugreihenfolge stabil",
               String.join(",", r.specs().stream().map(s -> s.name()).toList()),
               t -> t.equals("glob,grep,read,write,edit,bash"));

        // Die Karte kommt nur auf Wunsch dazu, und dann hinten -- vorne
        // eingefuegt verschoebe sie den Praefix-Cache aller anderen.
        pruefe("Karte nur auf Wunsch, und dann hinten",
               String.join(",", ToolRegistry.vorgabe(true).specs().stream()
                       .map(s -> s.name()).toList()),
               t -> t.equals("glob,grep,read,write,edit,bash,karte"));

        // ------------------------------------------------------------- write
        pruefe("write: legt Datei mit Verzeichnissen an",
               lauf(r, ws, "write", "{\"pfad\":\"neu/tief/a.txt\",\"inhalt\":\"eins\\nzwei\\n\"}"),
               t -> t.contains("angelegt") && t.contains("2 Zeilen"));
        pruefe("write: Inhalt stimmt",
               Files.readString(tmp.resolve("neu/tief/a.txt")),
               t -> t.equals("eins\nzwei\n"));
        pruefe("write: zweites Mal meldet ueberschrieben",
               lauf(r, ws, "write", "{\"pfad\":\"neu/tief/a.txt\",\"inhalt\":\"x\"}"),
               t -> t.contains("ueberschrieben"));
        Path draussen = Files.createTempDirectory("harness-draussen");
        Files.createSymbolicLink(tmp.resolve("link"), draussen);
        pruefe("write: Verweis nach draussen wird abgewiesen",
               lauf(r, ws, "write", "{\"pfad\":\"link/boese.txt\",\"inhalt\":\"x\"}"),
               t -> t.contains("nach draussen") && !Files.exists(draussen.resolve("boese.txt")));
        pruefe("write: auch in tieferen, noch fehlenden Verzeichnissen",
               lauf(r, ws, "write", "{\"pfad\":\"link/a/b/c.txt\",\"inhalt\":\"x\"}"),
               t -> t.contains("nach draussen") && !Files.exists(draussen.resolve("a")));
        Files.createSymbolicLink(tmp.resolve("leer-link"), draussen.resolve("gibts-nicht.txt"));
        pruefe("write: Verweis ins Leere wird abgewiesen",
               lauf(r, ws, "write", "{\"pfad\":\"leer-link\",\"inhalt\":\"x\"}"),
               t -> t.contains("Verweis ohne Ziel")
                    && !Files.exists(draussen.resolve("gibts-nicht.txt")));

        pruefe("write: Ausbruch wird abgewiesen",
               lauf(r, ws, "write", "{\"pfad\":\"../weg.txt\",\"inhalt\":\"x\"}"),
               t -> t.contains("ausserhalb"));

        // -------------------------------------------------------------- edit
        Files.writeString(tmp.resolve("e.txt"), "alpha\nbeta\ngamma\nbeta\n");
        pruefe("edit: eindeutige Stelle wird ersetzt",
               lauf(r, ws, "edit", "{\"pfad\":\"e.txt\",\"alt\":\"alpha\",\"neu\":\"ALPHA\"}"),
               t -> t.contains("geaendert"));
        pruefe("edit: Ersetzung wirklich erfolgt",
               Files.readString(tmp.resolve("e.txt")), t -> t.startsWith("ALPHA"));
        pruefe("edit: Mehrfachtreffer wird abgelehnt",
               lauf(r, ws, "edit", "{\"pfad\":\"e.txt\",\"alt\":\"beta\",\"neu\":\"B\"}"),
               t -> t.contains("2-mal") && t.contains("eindeutig"));
        pruefe("edit: Datei bleibt bei Mehrfachtreffer unveraendert",
               Files.readString(tmp.resolve("e.txt")), t -> t.contains("beta\ngamma\nbeta"));
        pruefe("edit: alle=true ersetzt alle",
               lauf(r, ws, "edit", "{\"pfad\":\"e.txt\",\"alt\":\"beta\",\"neu\":\"B\",\"alle\":true}"),
               t -> t.contains("2 Stellen"));
        pruefe("edit: fehlender Text meldet klaren Grund",
               lauf(r, ws, "edit", "{\"pfad\":\"e.txt\",\"alt\":\"gibtsnicht\",\"neu\":\"x\"}"),
               t -> t.contains("kommt in") && t.contains("nicht vor"));
        Files.writeString(tmp.resolve("re.txt"), "a.c und abc\n");
        pruefe("edit: 'alt' wird nicht als regulaerer Ausdruck gelesen",
               lauf(r, ws, "edit", "{\"pfad\":\"re.txt\",\"alt\":\"a.c\",\"neu\":\"X\"}")
               + Files.readString(tmp.resolve("re.txt")),
               t -> t.contains("X und abc"));

        // -------------------------------------------------------------- glob
        pruefe("glob: findet nach Endung",
               lauf(r, ws, "glob", "{\"muster\":\"**/*.txt\"}"),
               t -> t.contains("hallo.txt") && t.contains("neu/tief/a.txt"));
        pruefe("glob: ueberspringt .venv und __pycache__",
               lauf(r, ws, "glob", "{\"muster\":\"**/*\"}"),
               t -> !t.contains(".venv") && !t.contains("__pycache__"));
        pruefe("glob: ohne Treffer meldet das klar",
               lauf(r, ws, "glob", "{\"muster\":\"**/*.rs\"}"),
               t -> t.contains("keine Treffer"));

        // -------------------------------------------------------------- grep
        pruefe("grep: findet mit datei:zeile:text",
               lauf(r, ws, "grep", "{\"muster\":\"zweite\"}"),
               t -> t.contains("hallo.txt:2:zweite"));
        pruefe("grep: Dateimuster schraenkt ein",
               lauf(r, ws, "grep", "{\"muster\":\"a\",\"dateien\":\"**/*.md\"}"),
               t -> t.contains("keine Treffer"));
        pruefe("grep: kaputter Ausdruck meldet Fehler statt abzustuerzen",
               lauf(r, ws, "grep", "{\"muster\":\"[unfertig\"}"),
               t -> t.contains("nicht lesbar"));

        abgeschnitten(r, ws);

        System.out.println(fehlgeschlagen == 0
                ? "\nAlle Pruefungen bestanden."
                : "\n" + fehlgeschlagen + " Pruefung(en) fehlgeschlagen.");
        if (fehlgeschlagen > 0) System.exit(1);
    }

    /**
     * Abgeschnittene Argumente sollen als das gemeldet werden, was sie sind.
     *
     * <p>Ohne die Grenze lautet die Meldung "kein gueltiges JSON" — wahr, aber
     * als Diagnose irrefuehrend: ein Modell, das einen Tippfehler vermutet,
     * wiederholt denselben zu grossen Aufruf.
     */
    static void abgeschnitten(ToolRegistry r, Workspace ws) {
        ToolCall halb = new ToolCall("id", "write",
                "{\"pfad\":\"x.html\",\"inhalt\":\"<html>unvollstaend");

        pruefe("ohne Grenze: JSON-Meldung wie bisher",
               r.fuehreAus(halb, ws).text(), t -> t.contains("kein gueltiges JSON"));

        pruefe("mit Grenze: nennt die Ausgabegrenze",
               r.fuehreAus(halb, ws, 8192).text(), t -> t.contains("8192"));
        pruefe("mit Grenze: widerspricht dem Tippfehler-Verdacht",
               r.fuehreAus(halb, ws, 8192).text(), t -> t.contains("kein Tippfehler"));
        pruefe("mit Grenze: nennt den Ausweg",
               r.fuehreAus(halb, ws, 8192).text(), t -> t.contains("edit"));
        pruefe("mit Grenze: keine irrefuehrende JSON-Meldung",
               r.fuehreAus(halb, ws, 8192).text(), t -> !t.contains("kein gueltiges JSON"));
        pruefe("abgeschnitten bleibt ein Fehler",
               String.valueOf(r.fuehreAus(halb, ws, 8192).istFehler()), "true"::equals);
        pruefe("die Datei wurde nicht angelegt",
               String.valueOf(java.nio.file.Files.exists(ws.wurzel().resolve("x.html"))),
               "false"::equals);
    }

    private static String lauf(ToolRegistry r, Workspace ws, String werkzeug, String args) {
        return r.fuehreAus(new ToolCall("id", werkzeug, args), ws).text();
    }

    private static void pruefe(String was, String text,
                               java.util.function.Predicate<String> bedingung) {
        boolean ok = text != null && bedingung.test(text);
        System.out.printf("%-48s %s%n", was, ok ? "ok" : "FEHLGESCHLAGEN");
        if (!ok) {
            fehlgeschlagen++;
            System.out.println("    erhalten: "
                    + (text == null ? "null"
                       : text.length() > 160 ? text.substring(0, 160) + "…" : text)
                      .replace("\n", "\\n"));
        }
    }
}
