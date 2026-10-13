package de.dg1001.harness.ws;

import java.nio.file.Files;
import java.nio.file.Path;

/** Prueft .jaja.json und die Erkennung des Testbefehls. */
public final class ProbeProjekt {

    private static int fehlgeschlagen = 0;

    public static void main(String[] args) throws Exception {
        // ----------------------------------------------------- Erkennung
        {
            Path w = Files.createTempDirectory("jaja-projekt");
            pruefe("leeres Verzeichnis: kein Testbefehl", Projekt.erkenneTest(w) == null);

            Files.createDirectories(w.resolve("tests"));
            Files.writeString(w.resolve("tests/test_kasse.py"), "def test_x(): pass\n");
            String py = Projekt.erkenneTest(w);
            pruefe("Python mit tests/: pytest oder unittest (" + py + ")",
                   py != null && (py.endsWith("-m pytest -q") || py.endsWith("-m unittest discover -q")));

            Files.createDirectories(w.resolve(".venv/bin"));
            Path venvPy = w.resolve(".venv/bin/python");
            Files.writeString(venvPy, "#!/bin/sh\n");
            venvPy.toFile().setExecutable(true);
            pruefe("Python mit .venv: dessen Interpreter",
                   Projekt.erkenneTest(w) != null && Projekt.erkenneTest(w).startsWith(".venv/bin/python"));
        }
        {
            Path w = Files.createTempDirectory("jaja-projekt");
            Files.writeString(w.resolve("package.json"),
                    "{\"scripts\":{\"test\":\"echo \\\"Error: no test specified\\\" && exit 1\"}}");
            pruefe("package.json mit npm-Vorgabe: kein Testbefehl", Projekt.erkenneTest(w) == null);
        }
        {
            Path w = Files.createTempDirectory("jaja-projekt");
            Files.writeString(w.resolve("pom.xml"), "<project/>");
            Path mvnw = w.resolve("mvnw");
            Files.writeString(mvnw, "#!/bin/sh\n");
            mvnw.toFile().setExecutable(true);
            pruefe("pom.xml mit mvnw: der Wrapper", "./mvnw -q test".equals(Projekt.erkenneTest(w)));
        }

        // --------------------------------------------------- .jaja.json
        {
            Path w = Files.createTempDirectory("jaja-projekt");
            Files.createDirectories(w.resolve("tests"));
            Files.writeString(w.resolve("tests/test_a.py"), "");
            Files.writeString(w.resolve(Projekt.DATEI),
                    "{\"test\": \"make check\", \"erlauben\": [\"pytest*\", \" git status \"],"
                    + " \"pruefen\": {\"*.py\": \"ruff check {datei}\"}}");
            Projekt p = Projekt.lies(w);
            pruefe(".jaja.json: eingestellter Test geht vor", "make check".equals(p.testJetzt(w)) && !p.testErkannt());
            pruefe(".jaja.json: erlauben und pruefen gelesen",
                   p.erlauben().equals(java.util.List.of("pytest*", "git status"))
                   && "ruff check {datei}".equals(p.pruefen().get("*.py")));
        }
        {
            Path w = Files.createTempDirectory("jaja-projekt");
            Files.writeString(w.resolve(Projekt.DATEI), "{\"test\": ");
            boolean geworfen = false;
            try { Projekt.lies(w); } catch (java.io.IOException e) { geworfen = e.getMessage().contains(Projekt.DATEI); }
            pruefe(".jaja.json kaputt: Fehler mit Dateinamen, nicht still leer", geworfen);
            pruefe("... Workspace bleibt trotzdem benutzbar",
                   new Workspace(w).projekt() == Projekt.LEER);
        }
        {
            // Greenfield: beim Start nichts, waehrend des Laufs entstehen Tests.
            Path w = Files.createTempDirectory("jaja-projekt");
            Projekt p = Projekt.lies(w);
            boolean vorher = p.testJetzt(w) == null;
            Files.writeString(w.resolve("test_neu.py"), "");
            pruefe("testJetzt erkennt neu entstandene Tests", vorher && p.testJetzt(w) != null);
        }

        System.out.println(fehlgeschlagen == 0
                ? "\nAlle Pruefungen bestanden."
                : "\n" + fehlgeschlagen + " Pruefung(en) fehlgeschlagen.");
        if (fehlgeschlagen > 0) System.exit(1);
    }

    private static void pruefe(String was, boolean ok) {
        System.out.printf("%-62s %s%n", was, ok ? "ok" : "FEHLGESCHLAGEN");
        if (!ok) fehlgeschlagen++;
    }
}
