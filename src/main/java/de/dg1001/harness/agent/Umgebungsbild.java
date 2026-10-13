package de.dg1001.harness.agent;

import de.dg1001.harness.tools.GlobTool;
import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Ein kurzer Blick auf die Umgebung, EINMAL vor dem ersten Zug.
 *
 * <p>Der Agent verbringt sonst zwei bis fuenf Zuege damit, sich zu
 * orientieren: {@code ls}, {@code which python3}, {@code cat pom.xml}. Auf
 * lokaler Hardware kostet jeder dieser Zuege zehn Sekunden bis Minuten, dazu
 * Werkzeugaufruf und Ergebnis im Kontext. Das hier kostet einen Bruchteil
 * davon und beantwortet dieselben Fragen.
 *
 * <p>Die Idee stammt aus dem Harness, den Meta-Harness (arXiv 2603.28052)
 * fuer TerminalBench-2 gefunden hat -- dort die einzige inhaltliche
 * Aenderung gegenueber der Vorlage, mit der Begruendung, sie spare "2-5 early
 * exploration turns".
 *
 * <p><b>Nicht auf Python zugeschnitten.</b> Die Vorlage fragt eine feste
 * Liste ab (python3, gcc, node, java, rustc, go). Das sah nach Python aus,
 * weil die Aufgaben Python waren. Hier wird stattdessen erst geschaut, was im
 * Verzeichnis LIEGT, und nur die dazu passende Werkzeugkette abgefragt. Ein
 * Java-Projekt bekommt {@code java}/{@code mvn}, ein Rust-Projekt
 * {@code cargo}. Das ist billiger -- und es beantwortet die Frage, die sich
 * der Agent tatsaechlich stellt.
 *
 * <p>Scheitert irgendetwas, kommt eine leere Zeichenkette zurueck und der
 * Agent erkundet wie bisher. Ein Umgebungsbild ist eine Abkuerzung, kein
 * Bestandteil des Ablaufs; es darf nie der Grund sein, dass ein Lauf
 * ausfaellt.
 */
public final class Umgebungsbild {

    private Umgebungsbild() {}

    /** Hoechstens so viele Eintraege des Verzeichnisses werden gezeigt. */
    private static final int MAX_EINTRAEGE = 20;

    /** Zeitgrenze fuer einen einzelnen Aufruf wie {@code java -version}. */
    private static final long FRIST_MS = 3000;

    /** Zeitgrenze fuer das Ganze. Lieber unvollstaendig als langsam. */
    private static final long GESAMTFRIST_MS = 8000;

    /**
     * Merkmalsdatei -> Werkzeuge, die dann interessieren.
     *
     * <p>Reihenfolge ist die Reihenfolge der Ausgabe. LinkedHashMap deshalb,
     * nicht HashMap: bei zwei Laeufen ueber dasselbe Projekt soll dieselbe
     * Zeichenkette herauskommen, sonst zerfaellt der Praefix-Cache an einer
     * Stelle, die niemand vermutet.
     */
    private static final Map<String, String[][]> KETTEN = new LinkedHashMap<>();

    static {
        KETTEN.put("pyproject.toml", new String[][] {
                {"python3", "--version"}, {"pytest", "--version"}});
        KETTEN.put("requirements.txt", new String[][] {
                {"python3", "--version"}, {"pytest", "--version"}});
        KETTEN.put("setup.py", new String[][] {{"python3", "--version"}});
        KETTEN.put("pom.xml", new String[][] {
                {"java", "-version"}, {"mvn", "-v"}});
        KETTEN.put("build.gradle", new String[][] {
                {"java", "-version"}, {"gradle", "-v"}});
        KETTEN.put("package.json", new String[][] {
                {"node", "--version"}, {"npm", "--version"}});
        KETTEN.put("Cargo.toml", new String[][] {{"cargo", "--version"}});
        KETTEN.put("go.mod", new String[][] {{"go", "version"}});
        KETTEN.put("CMakeLists.txt", new String[][] {
                {"cmake", "--version"}, {"gcc", "--version"}});
        KETTEN.put("Makefile", new String[][] {{"make", "--version"}});
    }

    /** Dateiendung -> Werkzeuge. Der zweite Weg, falls kein Merkmal da ist. */
    private static final Map<String, String[][]> ENDUNGEN = new LinkedHashMap<>();

    static {
        ENDUNGEN.put(".py", new String[][] {
                {"python3", "--version"}, {"pytest", "--version"}});
        ENDUNGEN.put(".java", new String[][] {{"java", "-version"}});
        ENDUNGEN.put(".rs", new String[][] {{"cargo", "--version"}});
        ENDUNGEN.put(".go", new String[][] {{"go", "version"}});
        ENDUNGEN.put(".ts", new String[][] {{"node", "--version"}});
        ENDUNGEN.put(".js", new String[][] {{"node", "--version"}});
        ENDUNGEN.put(".c", new String[][] {{"gcc", "--version"}});
        ENDUNGEN.put(".cpp", new String[][] {{"g++", "--version"}});
    }

    /** Wie viele Dateien beim Suchen nach Endungen hoechstens angesehen werden. */
    private static final int MAX_GESICHTET = 400;

    /**
     * Alle Dateinamen bis zu einer festen Zahl, in Breite gesucht.
     *
     * <p>Erst stand hier {@code Files.walk(wurzel, 2)}. Das fand jajas eigene
     * {@code .java}-Dateien nicht -- die liegen unter
     * {@code src/de/dg1001/harness/agent/}, also fuenf Ebenen tief. In die
     * Breite und mit Deckel statt in die Tiefe und flach: so wird ein
     * Java-Projekt genauso erkannt wie ein Python-Projekt, und ein grosses
     * Repo kostet trotzdem keine Sekunde.
     */
    private static List<String> dateinamen(Path wurzel) {
        List<String> namen = new ArrayList<>();
        List<Path> welle = new ArrayList<>(List.of(wurzel));
        while (!welle.isEmpty() && namen.size() < MAX_GESICHTET) {
            List<Path> naechste = new ArrayList<>();
            for (Path d : welle) {
                if (namen.size() >= MAX_GESICHTET) break;
                try (Stream<Path> s = Files.list(d)) {
                    for (Path p : s.toList()) {
                        String n = p.getFileName().toString();
                        if (Files.isDirectory(p)) {
                            if (!GlobTool.UEBERSPRINGEN.contains(n)) naechste.add(p);
                        } else {
                            namen.add(n);
                            if (namen.size() >= MAX_GESICHTET) break;
                        }
                    }
                } catch (IOException ignoriert) {
                    // unlesbares Verzeichnis ist kein Grund aufzuhoeren
                }
            }
            welle = naechste;
        }
        return namen;
    }

    /**
     * Haengt das Bild an die Aufgabe an. Bei jedem Fehler bleibt die Aufgabe,
     * wie sie war.
     */
    public static String anhaengen(String aufgabe, Workspace ws) {
        try {
            String bild = erzeuge(ws.wurzel());
            // Der Testbefehl gehoert zu dem, wonach der Agent sonst zuerst
            // sucht: pytest oder unittest, mvn oder ./mvnw.
            String test = ws.projekt().testJetzt(ws.wurzel());
            if (test != null) bild += "\nTests: " + test;
            return bild.isEmpty() ? aufgabe : aufgabe + "\n\n" + bild;
        } catch (Throwable ignoriert) {
            return aufgabe;
        }
    }

    /** Das Bild selbst. Sichtbar fuer die Pruefung. */
    public static String erzeuge(Path wurzel) {
        long ende = System.currentTimeMillis() + GESAMTFRIST_MS;
        List<String> teile = new ArrayList<>();
        teile.add("Arbeitsverzeichnis: " + wurzel);

        List<String> namen = eintraege(wurzel);
        if (namen.isEmpty()) {
            teile.add("Inhalt: leeres Verzeichnis");
        } else if (namen.size() > MAX_EINTRAEGE) {
            teile.add("Inhalt (" + namen.size() + " Eintraege): "
                    + String.join(", ", namen.subList(0, MAX_EINTRAEGE))
                    + " ... (" + (namen.size() - MAX_EINTRAEGE) + " weitere)");
        } else {
            teile.add("Inhalt: " + String.join(", ", namen));
        }

        // Nur die Werkzeugketten abfragen, die hier ueberhaupt in Frage
        // kommen. Zwei Wege dorthin, weil einer allein zu kurz greift:
        // Merkmalsdateien (pom.xml, Cargo.toml) UND die tatsaechlich
        // vorhandenen Endungen. Die Pruefstandsaufgabe t1-debug ist ein
        // Python-Projekt ganz ohne pyproject.toml -- nur "kasse/" und
        // "tests/". Ueber Merkmalsdateien allein haette der Agent nicht
        // erfahren, dass es python3 und pytest gibt.
        List<String[][]> gesucht = new ArrayList<>();
        for (Map.Entry<String, String[][]> e : KETTEN.entrySet())
            if (namen.contains(e.getKey())) gesucht.add(e.getValue());
        List<String> tiefe = dateinamen(wurzel);
        for (Map.Entry<String, String[][]> e : ENDUNGEN.entrySet())
            if (tiefe.stream().anyMatch(n -> n.endsWith(e.getKey())))
                gesucht.add(e.getValue());

        List<String> werkzeuge = new ArrayList<>();
        List<String> gesehen = new ArrayList<>();
        for (String[][] kette : gesucht) {
            for (String[] befehl : kette) {
                if (System.currentTimeMillis() > ende) break;
                if (gesehen.contains(befehl[0])) continue;   // nicht zweimal fragen
                gesehen.add(befehl[0]);
                String v = version(wurzel, befehl);
                if (!v.isEmpty()) werkzeuge.add(v);
            }
        }
        if (!werkzeuge.isEmpty())
            teile.add("Werkzeuge: " + String.join("; ", werkzeuge));

        if (Files.isDirectory(wurzel.resolve(".venv")))
            teile.add("Virtuelle Umgebung: .venv vorhanden");

        if (Files.isDirectory(wurzel.resolve(".git"))
                && System.currentTimeMillis() < ende) {
            String stand = ausgabe(wurzel, "git", "status", "--short");
            long n = stand.isEmpty() ? 0 : stand.lines().count();
            teile.add("git: " + (n == 0 ? "sauber" : n + " geaenderte Datei(en)"));
        }

        return "[Umgebungsbild]\n" + String.join("\n", teile);
    }

    /** Namen im Wurzelverzeichnis, sortiert, ohne die uebliche Unordnung. */
    private static List<String> eintraege(Path wurzel) {
        try (Stream<Path> s = Files.list(wurzel)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> !n.equals(".git") && !n.equals(".harness")
                            && !n.equals("__pycache__") && !n.equals("node_modules"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Erste Zeile der Versionsausgabe, oder leer. */
    private static String version(Path wurzel, String... befehl) {
        String roh = ausgabe(wurzel, befehl);
        if (roh.isEmpty()) return "";
        String erste = roh.lines().findFirst().orElse("").trim();
        return erste.length() > 60 ? erste.substring(0, 60) : erste;
    }

    /**
     * Ein Befehl, seine Ausgabe. Kein Shell, kein {@code sh -c}: was hier
     * laeuft, steht in dieser Datei und nirgends sonst.
     */
    private static String ausgabe(Path wurzel, String... befehl) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(befehl);
            pb.directory(wurzel.toFile());
            pb.redirectErrorStream(true);          // java -version schreibt nach stderr
            p = pb.start();
            p.getOutputStream().close();
            // Lesen nebenlaeufig zum Warten -- derselbe Fehler, den BashTool
            // schon einmal hatte: readAllBytes() vor waitFor() blockiert, bis
            // der Prozess endet, und die Frist wird nie geprueft. Ein haengendes
            // "mvn -v" oder "git status" auf einem Netzlaufwerk hielte dann den
            // ersten Zug unbegrenzt auf.
            Process proz = p;
            var aus = new java.util.concurrent.CompletableFuture<String>();
            Thread.ofVirtual().start(() -> {
                try (var in = proz.getInputStream()) {
                    aus.complete(new String(in.readAllBytes()));
                } catch (IOException e) {
                    aus.complete("");
                }
            });
            if (!p.waitFor(FRIST_MS, TimeUnit.MILLISECONDS)) return "";
            String text = aus.get(500, TimeUnit.MILLISECONDS);
            return p.exitValue() == 0 || !text.isBlank() ? text.strip() : "";
        } catch (IOException | InterruptedException
                 | java.util.concurrent.ExecutionException
                 | java.util.concurrent.TimeoutException e) {
            // Werkzeug nicht vorhanden ist der Normalfall, kein Fehler.
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "";
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }
}
