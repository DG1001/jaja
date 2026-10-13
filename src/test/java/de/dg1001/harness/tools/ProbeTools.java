package de.dg1001.harness.tools;

import de.dg1001.harness.wire.Messages.ToolCall;
import de.dg1001.harness.ws.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

        // -------------------------------------------------- edit, tolerant
        String py = "class Kasse:\n    def summe(self, posten):\n        gesamt = 0\n"
                  + "        for p in posten:\n            gesamt += p.preis\n        return gesamt\n";
        Files.writeString(tmp.resolve("k.py"), py);
        pruefe("edit: Einrueckung abgezaehlt statt kopiert wird gefunden",
               lauf(r, ws, "edit", "{\"pfad\":\"k.py\",\"alt\":\"for p in posten:\\n    gesamt += p.preis\","
                       + "\"neu\":\"for p in posten:\\n    gesamt += p.preis * p.menge\"}"),
               t -> t.contains("geaendert") && t.contains("abweichendem Leerraum")
                    && t.contains("Einrueckung"));
        pruefe("edit: ... und 'neu' wird an die Einrueckung der Datei angepasst",
               Files.readString(tmp.resolve("k.py")),
               t -> t.contains("        for p in posten:\n            gesamt += p.preis * p.menge\n"
                               + "        return gesamt\n"));

        Files.writeString(tmp.resolve("ws.txt"), "a = 1   \n\tb = 2\nc = 3\n");
        pruefe("edit: Tabulator und Leerzeichen am Zeilenende stoeren nicht",
               lauf(r, ws, "edit", "{\"pfad\":\"ws.txt\",\"alt\":\"a = 1\\n    b = 2\\n\",\"neu\":\"a = 10\\n    b = 20\\n\"}")
               + Files.readString(tmp.resolve("ws.txt")),
               t -> t.contains("geaendert") && t.contains("a = 10\n\tb = 20\nc = 3\n"));

        Files.writeString(tmp.resolve("crlf.txt"), "eins\r\nzwei\r\ndrei\r\n");
        pruefe("edit: CRLF-Datei, 'alt' mit LF",
               lauf(r, ws, "edit", "{\"pfad\":\"crlf.txt\",\"alt\":\"eins\\nzwei\",\"neu\":\"EINS\\nZWEI\"}")
               + Files.readString(tmp.resolve("crlf.txt")),
               t -> t.contains("CRLF") && t.contains("EINS\r\nZWEI\r\ndrei\r\n"));

        Files.writeString(tmp.resolve("doppelt.py"), "def a():\n    x = 1\n\ndef b():\n        x = 1\n");
        pruefe("edit: mehrdeutig bei tolerantem Leerraum wird abgelehnt",
               lauf(r, ws, "edit", "{\"pfad\":\"doppelt.py\",\"alt\":\"x = 1\",\"neu\":\"x = 2\"}"),
               t -> t.contains("2-mal") && t.contains("Zeile 2, 5"));
        pruefe("edit: ... und die Datei bleibt unveraendert",
               Files.readString(tmp.resolve("doppelt.py")), t -> !t.contains("x = 2"));

        pruefe("edit: woertlicher Mehrfachtreffer nennt die Zeilen",
               lauf(r, ws, "edit", "{\"pfad\":\"doppelt.py\",\"alt\":\"x = 1\\n\",\"neu\":\"x = 2\\n\"}"),
               t -> t.contains("2-mal") && t.contains("Zeile 2, 5"));

        Files.writeString(tmp.resolve("diag.py"),
                "def rabatt(menge, preis):\n    if menge > 10:\n        return preis * 0.9\n    return preis\n");
        String diag = lauf(r, ws, "edit", "{\"pfad\":\"diag.py\",\"alt\":\"    if menge >= 10:\\n"
                + "        return preis * 0.9\",\"neu\":\"x\"}");
        pruefe("edit: inhaltliche Abweichung wird NICHT ueberbrueckt",
               Files.readString(tmp.resolve("diag.py")), t -> t.contains("menge > 10") && !t.contains("x"));
        pruefe("edit: ... aber die aehnlichste Stelle wird gezeigt",
               diag, t -> t.contains("Zeilen 2-3") && t.contains("2\t    if menge > 10:"));
        pruefe("edit: ... samt erster abweichender Zeile",
               diag, t -> t.contains("Datei: if menge > 10:") && t.contains("alt:   if menge >= 10:"));

        Files.writeString(tmp.resolve("m.txt"), "rot\ngruen\nblau\n");
        pruefe("edit: mehrere Aenderungen in einem Aufruf",
               lauf(r, ws, "edit", "{\"pfad\":\"m.txt\",\"aenderungen\":["
                       + "{\"alt\":\"rot\",\"neu\":\"ROT\"},{\"alt\":\"blau\",\"neu\":\"BLAU\"}]}")
               + Files.readString(tmp.resolve("m.txt")),
               t -> t.contains("2 Aenderungen") && t.contains("ROT\ngruen\nBLAU\n"));
        pruefe("edit: scheitert eine Aenderung, wird keine geschrieben",
               lauf(r, ws, "edit", "{\"pfad\":\"m.txt\",\"aenderungen\":["
                       + "{\"alt\":\"ROT\",\"neu\":\"rot\"},{\"alt\":\"lila\",\"neu\":\"x\"}]}")
               + Files.readString(tmp.resolve("m.txt")),
               t -> t.contains("Aenderung 2 von 2") && t.contains("Nichts geschrieben")
                    && t.endsWith("ROT\ngruen\nBLAU\n"));
        pruefe("edit: spaetere Aenderung sieht den Stand nach der frueheren",
               lauf(r, ws, "edit", "{\"pfad\":\"m.txt\",\"aenderungen\":["
                       + "{\"alt\":\"gruen\",\"neu\":\"GELB\"},{\"alt\":\"GELB\\nBLAU\",\"neu\":\"ende\"}]}")
               + Files.readString(tmp.resolve("m.txt")),
               t -> t.endsWith("ROT\nende\n"));
        pruefe("edit: alt und aenderungen zugleich wird abgelehnt",
               lauf(r, ws, "edit", "{\"pfad\":\"m.txt\",\"alt\":\"a\",\"neu\":\"b\","
                       + "\"aenderungen\":[{\"alt\":\"a\",\"neu\":\"b\"}]}"),
               t -> t.contains("nicht beides"));

        // --------------------------------------------------- Syntaxpruefung
        pruefe("write: Python-Syntaxfehler wird mit Zeile gemeldet",
               lauf(r, ws, "write", "{\"pfad\":\"kaputt.py\",\"inhalt\":\"def f(:\\n    pass\\n\"}"),
               t -> t.contains("angelegt") && t.contains("Syntaxfehler in kaputt.py")
                    && t.contains("Zeile 1"));
        pruefe("write: ... ist aber kein Fehlschlag des Aufrufs",
               String.valueOf(r.fuehreAus(new ToolCall("id", "write",
                       "{\"pfad\":\"kaputt.py\",\"inhalt\":\"def f(:\\n\"}"), ws).istFehler()),
               t -> t.equals("false"));
        pruefe("write: gueltiges Python bleibt ohne Befund",
               lauf(r, ws, "write", "{\"pfad\":\"gut.py\",\"inhalt\":\"def f():\\n    return 1\\n\"}"),
               t -> !t.contains("Syntaxfehler"));
        pruefe("write: ... und hinterlaesst kein __pycache__",
               String.valueOf(Files.exists(tmp.resolve("__pycache__"))), t -> t.equals("false"));
        pruefe("edit: Syntaxfehler durch edit wird gemeldet",
               lauf(r, ws, "edit", "{\"pfad\":\"gut.py\",\"alt\":\"return 1\",\"neu\":\"return (1\"}"),
               t -> t.contains("geaendert") && t.contains("Syntaxfehler in gut.py"));
        pruefe("write: kaputtes JSON wird gemeldet",
               lauf(r, ws, "write", "{\"pfad\":\"k.json\",\"inhalt\":\"{\\\"a\\\": 1,}\"}"),
               t -> t.contains("Syntaxfehler in k.json"));
        pruefe("write: Shell-Syntaxfehler wird gemeldet",
               lauf(r, ws, "write", "{\"pfad\":\"k.sh\",\"inhalt\":\"if true; then\\necho x\\n\"}"),
               t -> t.contains("Syntaxfehler in k.sh"));
        pruefe("write: unbekannte Endung wird nicht geprueft",
               lauf(r, ws, "write", "{\"pfad\":\"x.unbekannt\",\"inhalt\":\"def f(:\"}"),
               t -> !t.contains("Syntaxfehler"));

        // ------------------------------------------- Sicherung und Diff
        {
            Path sw = Files.createTempDirectory("harness-sicherung");
            Workspace w2 = new Workspace(sw);
            Files.writeString(sw.resolve("alt.txt"), "eins\nzwei\ndrei\n");
            lauf(r, w2, "edit", "{\"pfad\":\"alt.txt\",\"alt\":\"zwei\",\"neu\":\"ZWEI\"}");
            pruefe("Sicherung: ohne begonnenen Stand wird nichts gesichert (Stapelbetrieb)",
                   String.valueOf(w2.sicherung().anzahl()), t -> t.equals("0"));

            w2.sicherung().neuerStand("erster Auftrag");
            Tool.ToolResult er = r.fuehreAus(new ToolCall("i", "edit",
                    "{\"pfad\":\"alt.txt\",\"alt\":\"ZWEI\",\"neu\":\"zwo\\nzwei-b\"}"), w2);
            pruefe("Diff: edit zeigt entfernte und neue Zeilen mit Nummer",
                   er.anzeige(), t -> t.contains("2 - ZWEI") && t.contains("2 + zwo")
                                      && t.contains("3 + zwei-b") && !t.contains("eins"));
            pruefe("Diff: geht nicht ans Modell", er.text(), t -> !t.contains("+ zwo"));
            lauf(r, w2, "edit", "{\"pfad\":\"alt.txt\",\"alt\":\"drei\",\"neu\":\"DREI\"}");
            lauf(r, w2, "write", "{\"pfad\":\"neu/datei.txt\",\"inhalt\":\"x\"}");
            w2.sicherung().neuerStand("Auftrag ohne Aenderung");

            var z = w2.sicherung().zurueck();
            pruefe("zurueck: Stand vor dem Auftrag, nicht vor dem letzten edit",
                   Files.readString(sw.resolve("alt.txt")), t -> t.equals("eins\nZWEI\ndrei\n"));
            pruefe("zurueck: neu angelegte Datei wird entfernt",
                   String.valueOf(Files.exists(sw.resolve("neu/datei.txt"))), t -> t.equals("false"));
            pruefe("zurueck: Auftrag ohne Aenderung wird uebersprungen",
                   z == null ? null : z.beschreibung(), t -> t.equals("erster Auftrag"));
            pruefe("zurueck: danach ist nichts mehr da",
                   String.valueOf(w2.sicherung().zurueck()), t -> t.equals("null"));

            Files.writeString(sw.resolve("w.txt"), "a\nb\n");
            w2.sicherung().neuerStand("ueberschreiben");
            Tool.ToolResult wr = r.fuehreAus(new ToolCall("i", "write",
                    "{\"pfad\":\"w.txt\",\"inhalt\":\"a\\nB\\n\"}"), w2);
            pruefe("Diff: ueberschreibendes write zeigt die Aenderung",
                   wr.anzeige(), t -> t.contains("2 - b") && t.contains("2 + B"));
            w2.sicherung().zurueck();
            pruefe("zurueck: ueberschriebene Datei hat wieder ihren Inhalt",
                   Files.readString(sw.resolve("w.txt")), t -> t.equals("a\nb\n"));
        }

        // ---------------------------------- zurueck mit Schnappschuessen
        if (new ProcessBuilder("git", "--version").start().waitFor() == 0) {
            Path gw = Files.createTempDirectory("harness-schnapp");
            Workspace w3 = new Workspace(gw);
            // Ein Projekt-git, das von alldem nichts merken darf.
            sh(gw, "git init -q && git config user.email p@p && git config user.name p"
                   + " && printf 'node_modules/\n' > .gitignore && echo alt > alt.txt"
                   + " && echo bleibt > hand.txt && git add -A && git commit -qm start");
            String kopf = sh(gw, "git rev-parse HEAD; git status --porcelain");

            w3.sicherung().neuerStand("mit bash");
            lauf(r, w3, "bash", "{\"kommando\":\"echo neu > neu.txt; rm alt.txt; echo x >> hand.txt\"}");
            lauf(r, w3, "write", "{\"pfad\":\"node_modules/paket.js\",\"inhalt\":\"x\"}");
            w3.sicherung().standAbschliessen();
            Files.writeString(gw.resolve("hand.txt"), "von Hand\n");     // nach dem Auftrag

            var z = w3.sicherung().zurueck();
            pruefe("zurueck: auch was bash geaendert hat",
                   String.valueOf(z != null && z.mitBash() && !Files.exists(gw.resolve("neu.txt"))
                                  && Files.exists(gw.resolve("alt.txt"))), t -> t.equals("true"));
            pruefe("zurueck: von Hand Geaendertes bleibt und wird genannt",
                   Files.readString(gw.resolve("hand.txt"))
                   + (z != null && z.vonHand().contains(w3.wurzel().resolve("hand.txt"))),
                   t -> t.equals("von Hand\ntrue"));
            pruefe("zurueck: was git ignoriert, kommt aus dem Speicher",
                   String.valueOf(Files.exists(gw.resolve("node_modules/paket.js"))), t -> t.equals("false"));
            pruefe("zurueck: das git des Projekts merkt nichts",
                   sh(gw, "git rev-parse HEAD; git status --porcelain"),
                   t -> t.replace(" M hand.txt\n", "").equals(kopf) && !t.contains(".harness"));
        }

        // ------------------------------------------------ Hintergrund
        {
            BashTool.ANLAUF_MS = 800;
            long t0 = System.nanoTime();
            String h = lauf(r, ws, "bash", "{\"kommando\":\"echo gestartet; sleep 30 & wait\",\"hintergrund\":true}");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            pruefe("hintergrund: kehrt nach dem Anlauf zurueck, mit PID und Log",
                   h, t -> ms < 3_000 && t.contains("laeuft im Hintergrund, PID")
                           && t.contains(".harness/hintergrund/") && t.contains("gestartet"));
            long pid = Long.parseLong(h.replaceAll("(?s).*PID (\\d+).*", "$1"));
            var kinder = ProcessHandle.of(pid).map(x -> x.descendants().toList()).orElse(List.of());
            pruefe("hintergrund: laeuft tatsaechlich weiter",
                   String.valueOf(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)),
                   t -> t.equals("true"));
            BashTool.beendeHintergrund();
            Thread.sleep(300);
            pruefe("hintergrund: beim Ende des Harness samt Kindern beendet",
                   String.valueOf(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                                  || kinder.stream().anyMatch(ProcessHandle::isAlive)),
                   t -> t.equals("false"));
            pruefe("hintergrund: kurzes Kommando ist ein gewoehnliches Ergebnis",
                   lauf(r, ws, "bash", "{\"kommando\":\"echo kurz\",\"hintergrund\":true}"),
                   t -> t.contains("kurz") && t.contains("Rueckgabewert 0")
                        && t.contains("lief nicht im Hintergrund weiter"));
        }

        // ------------------------------------------------- Sandkasten
        // Das "Draussen" liegt bewusst unter target/ und nicht im temp-Verzeichnis:
        // das ist im Sandkasten schreibbar und taugte nicht als Gegenprobe.
        Path aussen = Path.of("target", "sandkasten-aussen").toAbsolutePath();
        de.dg1001.harness.ws.Sandkasten sk = null;
        try {
            sk = de.dg1001.harness.ws.Sandkasten.fuerDiesesSystem(ws.wurzel());
        } catch (java.io.IOException e) {
            System.out.println("Sandkasten: " + e.getMessage() + " -- Pruefungen uebersprungen");
        }
        if (sk != null) {
            Files.createDirectories(aussen);
            Files.deleteIfExists(aussen.resolve("x"));
            Workspace ws2 = new Workspace(ws.wurzel()).mitSandkasten(sk);
            pruefe("Sandkasten: schreiben im Arbeitsbereich geht",
                   lauf(r, ws2, "bash", "{\"kommando\":\"echo drin > drin.txt && cat drin.txt\"}"),
                   t -> t.contains("drin") && t.contains("Rueckgabewert 0"));
            pruefe("Sandkasten: schreiben ausserhalb wird verweigert",
                   lauf(r, ws2, "bash", "{\"kommando\":\"touch '" + aussen.resolve("x") + "'\"}"),
                   t -> !t.contains("Rueckgabewert 0") && !Files.exists(aussen.resolve("x")));
            pruefe("Sandkasten: /tmp, /dev/null und stderr gehen",
                   lauf(r, ws2, "bash", "{\"kommando\":\"f=$(mktemp) && echo a > $f && rm $f"
                           + " && echo b > /dev/null && echo c >&2 && echo d > /dev/stdout\"}"),
                   t -> t.contains("c") && t.contains("d") && t.contains("Rueckgabewert 0"));
            pruefe("Sandkasten: lesen ausserhalb bleibt erlaubt",
                   lauf(r, ws2, "bash", "{\"kommando\":\"ls '" + aussen + "' && echo gelesen\"}"),
                   t -> t.contains("gelesen"));
            pruefe("Sandkasten: gilt auch im Hintergrund",
                   lauf(r, ws2, "bash", "{\"kommando\":\"touch '" + aussen.resolve("x")
                           + "'\",\"hintergrund\":true}"),
                   t -> !t.contains("Rueckgabewert 0") && !Files.exists(aussen.resolve("x")));
        }

        // -------------------------------------------------------------- glob
        {
            Path viele = Files.createTempDirectory("harness-glob");
            Workspace wg = new Workspace(viele);
            for (int i = 0; i < 350; i++)
                Files.writeString(viele.resolve(String.format("d%03d.txt", i)), "");
            String g = lauf(r, wg, "glob", "{\"muster\":\"*.txt\"}");
            pruefe("glob: gekappt wird nach dem Sortieren, nicht davor",
                   g, t -> t.startsWith("d000.txt\n") && t.contains("d299.txt")
                           && !t.contains("d300.txt") && t.contains("300 von 350"));
        }
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

    private static String sh(Path dir, String kommando) throws Exception {
        Process p = new ProcessBuilder("bash", "-c", kommando).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String aus = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return aus;
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
