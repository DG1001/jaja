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

        // Auslagerung: 40.000 Zeichen erzeugen
        Tool.ToolResult gross = r.fuehreAus(
                new ToolCall("x", "bash",
                        "{\"kommando\":\"python3 -c \\\"print('z'*40000)\\\"\"}"), ws);
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

        System.out.println(fehlgeschlagen == 0
                ? "\nAlle Pruefungen bestanden."
                : "\n" + fehlgeschlagen + " Pruefung(en) fehlgeschlagen.");
        if (fehlgeschlagen > 0) System.exit(1);
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
