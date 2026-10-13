package de.dg1001.harness.ws;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Stand des ganzen Arbeitsbereichs als git-Baum, in einem Schatten-Repo unter
 * {@code .harness/snap} -- getrennt vom git des Projekts, das nichts davon
 * merkt: kein Commit, kein Stash, kein Eintrag im Reflog.
 *
 * <p>Der Grund: {@link Sicherung} sah nur, was write und edit anfassten. Was
 * ein {@code sed -i}, ein Generator oder ein {@code rm} tat, war fuer
 * {@code /zurueck} unsichtbar -- und die Shell ist das Werkzeug, mit dem ein
 * Modell am leichtesten viel auf einmal aendert. {@code git write-tree} ueber
 * einen eigenen Index haelt alles fest, was nicht ignoriert ist, und ist nach
 * dem ersten Mal schnell, weil der Index nur Geaendertes neu liest.
 *
 * <p>Ignoriert wird, was das Projekt per {@code .gitignore} ignoriert, dazu
 * {@code .harness} und die ueblichen Abhaengigkeitsverzeichnisse. Was dort
 * passiert, nimmt {@code /zurueck} nicht zurueck -- das ist Bauausgabe, kein
 * Quelltext.
 */
public final class Schnappschuss {

    /** Wie lange ein Schnappschuss dauern darf, bevor er fuer die Sitzung aufgegeben wird. */
    static final long FRIST_S = 60;

    private final Path wurzel;
    private final Path gitDir;

    private Schnappschuss(Path wurzel, Path gitDir) {
        this.wurzel = wurzel;
        this.gitDir = gitDir;
    }

    /** @return null, wenn es kein git gibt oder das Anlegen scheitert */
    public static Schnappschuss fuer(Path wurzel) {
        try {
            Path g = wurzel.resolve(".harness/snap");
            Schnappschuss s = new Schnappschuss(wurzel, g);
            if (!Files.exists(g.resolve("HEAD"))) {
                Files.createDirectories(g);
                s.git("init", "-q");
                Files.createDirectories(g.resolve("info"));
                Files.writeString(g.resolve("info/exclude"), String.join("\n",
                        "/.harness/", "node_modules/", ".venv/", "venv/", "__pycache__/",
                        ".pytest_cache/", ".mypy_cache/", "target/", "build/", "") );
            }
            return s;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Der jetzige Stand als Baum-Kennung. */
    public String baum() throws IOException {
        git("add", "-A", "--", ".");
        return git("write-tree").strip();
    }

    /** Was sich zwischen zwei Baeumen geaendert hat: Pfad -> A, M oder D. */
    public Map<String, Character> unterschied(String von, String nach) throws IOException {
        Map<String, Character> m = new LinkedHashMap<>();
        if (von.equals(nach)) return m;
        String aus = git("diff-tree", "-r", "--no-renames", "--name-status", "-z", von, nach);
        String[] t = aus.split("\0");
        for (int i = 0; i + 1 < t.length; i += 2)
            if (!t[i].isEmpty()) m.put(t[i + 1], t[i].charAt(0));
        return m;
    }

    /** Stellt die Pfade aus einem Baum wieder her. */
    public void stelleHer(String baum, List<String> pfade) throws IOException {
        for (int i = 0; i < pfade.size(); i += 200)
            git(concat(List.of("checkout", baum, "--"), pfade.subList(i, Math.min(pfade.size(), i + 200))));
    }

    // ------------------------------------------------------------- git

    private String git(String... args) throws IOException {
        return git(List.of(args));
    }

    private String git(List<String> args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git", "--git-dir=" + gitDir, "--work-tree=" + wurzel));
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(wurzel.toFile()).redirectErrorStream(true);
        // Nichts vom Projekt-git oder der Umgebung soll hineinregieren.
        for (String v : List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY"))
            pb.environment().remove(v);
        Process p = pb.start();
        p.getOutputStream().close();
        var aus = new java.util.concurrent.CompletableFuture<String>();
        Thread.ofVirtual().start(() -> {
            try (var in = p.getInputStream()) {
                aus.complete(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                aus.complete("");
            }
        });
        try {
            if (!p.waitFor(FRIST_S, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("git " + args.get(0) + " brauchte laenger als " + FRIST_S + " s");
            }
            String text = aus.get(5, TimeUnit.SECONDS);
            if (p.exitValue() != 0)
                throw new IOException("git " + args.get(0) + ": " + text.strip());
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("unterbrochen");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IOException("git " + args.get(0) + ": Ausgabe nicht lesbar");
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> l = new ArrayList<>(a);
        l.addAll(b);
        return l;
    }
}
