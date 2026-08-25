package de.dg1001.harness.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Prueft das Umgebungsbild an angelegten Beispielprojekten.
 *
 * <p>Geprueft wird vor allem, was in der ersten Fassung falsch war: dass ein
 * Python-Projekt OHNE {@code pyproject.toml} erkannt wird, und dass tief
 * liegende Quelldateien gefunden werden. Beides fiel nur auf, weil das Bild
 * an echten Verzeichnissen ausprobiert wurde -- eine Pruefung nur gegen
 * Merkmalsdateien haette beides durchgehen lassen.
 */
public final class ProbeUmgebungsbild {

    private static int fehlgeschlagen = 0;

    public static void main(String[] args) throws IOException {
        Path basis = Files.createTempDirectory("probe-umgebung");
        try {
            leer(basis);
            pythonOhneMerkmal(basis);
            tiefLiegendeQuellen(basis);
            deckel(basis);
            unlesbar(basis);
        } finally {
            loesche(basis);
        }
        System.out.println(fehlgeschlagen == 0
                ? "ProbeUmgebungsbild: alles ok"
                : "ProbeUmgebungsbild: " + fehlgeschlagen + " fehlgeschlagen");
        if (fehlgeschlagen > 0) System.exit(1);
    }

    /** Ein leeres Verzeichnis ist eine Aussage, kein Fehler. */
    private static void leer(Path basis) throws IOException {
        Path d = Files.createDirectories(basis.resolve("leer"));
        String s = Umgebungsbild.erzeuge(d);
        pruefe("leeres Verzeichnis wird als solches benannt",
               s.contains("leeres Verzeichnis"));
        pruefe("leeres Verzeichnis nennt keine Werkzeuge", !s.contains("Werkzeuge:"));
    }

    /**
     * Der Fall, an dem die erste Fassung scheiterte: Python-Projekt, aber
     * kein pyproject.toml, keine requirements.txt -- wie t1-debug im
     * Pruefstand.
     */
    private static void pythonOhneMerkmal(Path basis) throws IOException {
        Path d = Files.createDirectories(basis.resolve("py"));
        Files.createDirectories(d.resolve("kasse"));
        Files.writeString(d.resolve("kasse/rabatt.py"), "def netto(x): return x\n");
        Files.writeString(d.resolve("README.md"), "# Kasse\n");
        String s = Umgebungsbild.erzeuge(d);
        pruefe("Python ohne Merkmalsdatei wird erkannt",
               s.contains("Python") || s.contains("python"));
        pruefe("Verzeichnisinhalt wird genannt", s.contains("kasse"));
    }

    /** jajas eigener Fall: Quellen liegen fuenf Ebenen tief. */
    private static void tiefLiegendeQuellen(Path basis) throws IOException {
        Path d = Files.createDirectories(basis.resolve("tief"));
        Path q = Files.createDirectories(d.resolve("src/de/dg1001/harness/agent"));
        Files.writeString(q.resolve("Etwas.java"), "class Etwas {}\n");
        String s = Umgebungsbild.erzeuge(d);
        pruefe("tief liegende .java werden gefunden",
               s.toLowerCase().contains("jdk") || s.toLowerCase().contains("java"));
    }

    /** Viele Dateien: die Liste wird gekuerzt, die Zahl bleibt richtig. */
    private static void deckel(Path basis) throws IOException {
        Path d = Files.createDirectories(basis.resolve("viele"));
        for (int i = 0; i < 45; i++)
            Files.writeString(d.resolve(String.format("datei-%02d.txt", i)), "x");
        String s = Umgebungsbild.erzeuge(d);
        pruefe("Deckel greift und nennt die Gesamtzahl",
               s.contains("(45 Eintraege)") && s.contains("weitere)"));
        pruefe("hoechstens 20 Namen aufgezaehlt",
               s.lines().filter(z -> z.startsWith("Inhalt")).findFirst()
                .orElse("").split(", ").length <= 21);
    }

    /**
     * Ein unlesbares Unterverzeichnis darf das Bild nicht kippen -- der Agent
     * soll dann weniger erfahren, nicht scheitern.
     */
    private static void unlesbar(Path basis) throws IOException {
        Path d = Files.createDirectories(basis.resolve("gesperrt"));
        Files.writeString(d.resolve("a.py"), "x = 1\n");
        Path zu = Files.createDirectories(d.resolve("verboten"));
        boolean gesetzt = zu.toFile().setReadable(false);
        try {
            String s = Umgebungsbild.erzeuge(d);
            pruefe("unlesbares Unterverzeichnis bricht nicht ab",
                   s.startsWith("[Umgebungsbild]"));
        } finally {
            if (gesetzt) zu.toFile().setReadable(true);
        }
    }

    private static void loesche(Path p) {
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        } catch (IOException ignoriert) {
            // Temp-Verzeichnis, das raeumt spaetestens das System auf
        }
    }

    private static void pruefe(String was, boolean ok) {
        System.out.printf("%-56s %s%n", was, ok ? "ok" : "FEHLGESCHLAGEN");
        if (!ok) fehlgeschlagen++;
    }
}
