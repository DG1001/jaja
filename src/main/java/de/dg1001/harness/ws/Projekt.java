package de.dg1001.harness.ws;

import de.dg1001.harness.wire.Json;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Einstellungen eines Projekts, die der Harness selbst liest -- nicht das Modell.
 *
 * <p>{@code AGENT.md} ist Prosa fuer das Modell: Stil, Verbote, Gepflogenheiten.
 * Was der Harness auswerten muss -- welcher Befehl die Tests startet, welche
 * Kommandos ohne Nachfrage laufen duerfen --, gehoert nicht in Prosa, die man
 * mit regulaeren Ausdruecken liest. Deshalb {@code .jaja.json} in der Wurzel,
 * zum Einchecken gedacht:
 *
 * <pre>
 * {"test": "python3 -m pytest -q",
 *  "pruefen": {"*.py": "ruff check {datei}"},
 *  "erlauben": ["pytest*", "git status", "git diff*"]}
 * </pre>
 *
 * <p>Alles ist freiwillig. Fehlt {@code test}, wird er aus dem erkannt, was im
 * Verzeichnis liegt ({@link #erkenneTest}) -- ohne einen Prozess zu starten.
 */
public record Projekt(String test, boolean testErkannt, Map<String, String> pruefen,
                      List<String> erlauben) {

    public static final String DATEI = ".jaja.json";

    public static final Projekt LEER = new Projekt(null, false, Map.of(), List.of());

    /** Liest {@code .jaja.json}; ein Fehler darin wird gemeldet, nicht verschluckt. */
    public static Projekt lies(Path wurzel) throws IOException {
        Path p = wurzel.resolve(DATEI);
        String test = null;
        Map<String, String> pruefen = new LinkedHashMap<>();
        List<String> erlauben = new ArrayList<>();
        if (Files.isRegularFile(p)) {
            Object w;
            try {
                w = Json.parse(Files.readString(p));
            } catch (RuntimeException e) {
                throw new IOException(DATEI + " ist kein gueltiges JSON: " + e.getMessage());
            }
            test = Json.str(Json.feld(w, "test"));
            for (var e : Json.obj(Json.feld(w, "pruefen")).entrySet())
                if (e.getValue() instanceof String s) pruefen.put(e.getKey(), s);
            for (Object o : Json.arr(Json.feld(w, "erlauben")))
                if (o instanceof String s && !s.isBlank()) erlauben.add(s.strip());
        }
        boolean erkannt = false;
        if (test == null || test.isBlank()) { test = erkenneTest(wurzel); erkannt = test != null; }
        return new Projekt(test, erkannt, Map.copyOf(pruefen), List.copyOf(erlauben));
    }

    /**
     * Der Testbefehl, wie er jetzt gilt: der eingestellte, sonst neu erkannt.
     * Neu, weil in einem leeren Verzeichnis die Tests erst waehrend des Laufs
     * entstehen -- ein beim Start ermittelter Wert waere dann fuer immer null.
     */
    public String testJetzt(Path wurzel) {
        return test != null && !testErkannt ? test : erkenneTest(wurzel);
    }

    /**
     * Der uebliche Testbefehl, aus Merkmalsdateien abgeleitet; null, wenn
     * keiner erkennbar ist.
     *
     * <p>Nur, was eindeutig ist und dessen Werkzeug im PATH liegt. Ein falscher
     * Vorschlag kostet einen Zug und lenkt ab; kein Vorschlag kostet nichts.
     */
    public static String erkenneTest(Path w) {
        try {
            if (Files.exists(w.resolve("pom.xml")))
                return Files.isExecutable(w.resolve("mvnw")) ? "./mvnw -q test" : imPfad("mvn") ? "mvn -q test" : null;
            if (Files.exists(w.resolve("build.gradle")) || Files.exists(w.resolve("build.gradle.kts")))
                return Files.isExecutable(w.resolve("gradlew")) ? "./gradlew test" : imPfad("gradle") ? "gradle test" : null;
            if (Files.exists(w.resolve("Cargo.toml")))  return imPfad("cargo") ? "cargo test" : null;
            if (Files.exists(w.resolve("go.mod")))      return imPfad("go") ? "go test ./..." : null;
            if (Files.exists(w.resolve("package.json"))) {
                String pj = Files.readString(w.resolve("package.json"));
                boolean echt = pj.contains("\"test\"") && !pj.contains("no test specified");
                return echt && imPfad("npm") ? "npm test" : null;
            }
            if (pythonMitTests(w)) {
                Path venv = w.resolve(".venv/bin/python");
                String py = Files.isExecutable(venv) ? ".venv/bin/python" : imPfad("python3") ? "python3" : null;
                if (py == null) return null;
                boolean pytest = Files.isExecutable(w.resolve(".venv/bin/pytest")) || imPfad("pytest");
                return py + (pytest ? " -m pytest -q" : " -m unittest discover -q");
            }
            if (Files.exists(w.resolve("Makefile"))
                    && Files.readAllLines(w.resolve("Makefile")).stream().anyMatch(z -> z.startsWith("test:")))
                return imPfad("make") ? "make test" : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    /** Ein Python-Projekt mit erkennbaren Tests: tests/ oder test_*.py bis zwei Ebenen tief. */
    private static boolean pythonMitTests(Path w) throws IOException {
        if (Files.isDirectory(w.resolve("tests")) || Files.isDirectory(w.resolve("test"))) {
            try (Stream<Path> s = Files.walk(w, 3)) {
                if (s.anyMatch(p -> p.toString().endsWith(".py"))) return true;
            }
        }
        try (Stream<Path> s = Files.walk(w, 2)) {
            return s.map(p -> p.getFileName().toString())
                    .anyMatch(n -> n.startsWith("test_") && n.endsWith(".py") || n.endsWith("_test.py"));
        }
    }

    static boolean imPfad(String name) {
        String pfad = System.getenv("PATH");
        if (pfad == null) return false;
        for (String d : pfad.split(File.pathSeparator))
            if (!d.isEmpty() && Files.isExecutable(Path.of(d, name))) return true;
        return false;
    }
}
