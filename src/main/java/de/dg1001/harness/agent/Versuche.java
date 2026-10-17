package de.dg1001.harness.agent;

import de.dg1001.harness.tools.Tool;
import de.dg1001.harness.wire.Messages.ChatResponse;
import de.dg1001.harness.wire.Messages.ToolCall;
import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mehrere Versuche derselben Aufgabe nebeneinander, die Tests entscheiden.
 *
 * <p><b>Warum das lokal etwas anderes ist als gehostet.</b> Gehostet kostet
 * ein zweiter Versuch einen zweiten Rechnungsposten. Lokal ist das Dekodieren
 * durch die Speicherbandbreite begrenzt, nicht durch die Rechenleistung: ein
 * Server, der mehrere Folgen zusammen rechnet (vLLM; llama.cpp mit
 * {@code --parallel}), liefert drei Antworten in kaum mehr Zeit als eine. Der
 * Preis ist Speicher fuer den KV-Cache -- bei llama.cpp teilen sich die Slots
 * das Kontextfenster.
 *
 * <p><b>Ablauf.</b> Jeder Versuch bekommt eine Kopie des Arbeitsbereichs;
 * {@code .venv}, {@code venv} und {@code node_modules} werden verlinkt statt
 * kopiert (eine venv ist nicht verschiebbar). Danach laeuft in jeder Kopie der
 * Testbefehl des Projekts. Gewinner: gruene Tests vor FERTIG vor mehr
 * bestandenen Tests vor weniger Eingabetoken. Nur seine Aenderungen kommen in
 * den Arbeitsbereich zurueck; {@code .git} der Kopien nicht.
 *
 * <p><b>Gezaehlt wird alles.</b> Die Summen im Protokoll sind die aller
 * Versuche -- sonst gewaenne diese Konfiguration jeden Vergleich um den Preis,
 * der nirgends auftaucht.
 */
public final class Versuche {

    /** Werden verlinkt statt kopiert und beim Zurueckspielen nicht angefasst. */
    static final Set<String> VERLINKT = Set.of(".venv", "venv", "node_modules");

    /** Werden weder kopiert noch zurueckgespielt. */
    static final Set<String> AUSGELASSEN = Set.of(".harness", ".git");

    /**
     * Was erst der Testlauf im Sieger erzeugt, in jeder Tiefe. Ohne das spielte
     * ein Python-Projekt seinen {@code __pycache__} mit zurueck -- unter Linux
     * aufgefallen, sobald die Pruefung dort ueberhaupt lief.
     */
    static final Set<String> TESTSPUREN = Set.of("__pycache__", ".pytest_cache");

    private static final Pattern BESTANDEN = Pattern.compile("(\\d+) passed");

    public record Versuch(int nummer, Agent.Ergebnis ergebnis, long eingabe, long ausgabe,
                          Boolean testsGruen, int bestanden, Path kopie) {}

    public record Ausgang(Versuch sieger, List<Versuch> alle, String test, List<String> geaendert) {}

    private Versuche() {}

    /**
     * @param agentFuer baut den Agenten fuer einen Versuch: Arbeitsbereich und Nummer
     * @param melde     Fortschritt fuer Menschen
     */
    public static Ausgang lauf(Workspace ws, int n, String systemPrompt, String aufgabe,
                               BiFunction<Workspace, Integer, Agent> agentFuer,
                               java.util.function.Consumer<String> melde) throws Exception {
        Path basis = Files.createTempDirectory("jaja-versuche");
        List<Versuch> alle = new ArrayList<>();
        try {
            List<Path> kopien = new ArrayList<>();
            for (int k = 1; k <= n; k++) {
                Path z = basis.resolve("v" + k);
                kopiere(ws.wurzel(), z);
                kopien.add(z);
            }
            List<Future<Versuch>> fs = new ArrayList<>();
            try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int k = 1; k <= n; k++) {
                    int nr = k;
                    Path kopie = kopien.get(k - 1);
                    fs.add(exec.submit(() -> {
                        Workspace w = new Workspace(kopie)
                                .mitProjekt(ws.projekt()).mitSandkasten(ws.sandkasten());
                        Agent a = agentFuer.apply(w, nr);
                        Agent.Ergebnis e = a.lauf(systemPrompt, aufgabe);
                        String test = ws.projekt().testJetzt(kopie);
                        Boolean gruen = null;
                        int bestanden = 0;
                        if (test != null) {
                            String[] aus = new String[1];
                            gruen = teste(w, test, aus);
                            Matcher m = BESTANDEN.matcher(aus[0]);
                            while (m.find()) bestanden = Integer.parseInt(m.group(1));
                        }
                        melde.accept(String.format("Versuch %d: %s nach %d Zuegen, Tests %s",
                                nr, e.status(), e.zuege(),
                                gruen == null ? "unbekannt" : gruen ? "gruen" : "rot"));
                        return new Versuch(nr, e, a.eingabeTokens(), a.ausgabeTokens(), gruen, bestanden, kopie);
                    }));
                }
            }
            for (Future<Versuch> f : fs) alle.add(f.get());

            Versuch sieger = alle.stream().min(Comparator
                    .comparing((Versuch v) -> !Boolean.TRUE.equals(v.testsGruen()))
                    .thenComparing(v -> !v.ergebnis().erfolgreich())
                    .thenComparing(v -> -v.bestanden())
                    .thenComparingLong(Versuch::eingabe)
                    .thenComparingInt(Versuch::nummer)).orElseThrow();
            List<String> geaendert = spieleZurueck(sieger.kopie(), ws.wurzel());
            return new Ausgang(sieger, alle, ws.projekt().testJetzt(ws.wurzel()), geaendert);
        } finally {
            loesche(basis);
        }
    }

    // --------------------------------------------------------------- Tests

    private static boolean teste(Workspace w, String befehl, String[] aus) throws Exception {
        List<String> cmd = w.sandkasten() != null
                ? w.sandkasten().umhuelle(List.of("bash", "-lc", befehl))
                : List.of("bash", "-lc", befehl);
        Process p = new ProcessBuilder(cmd).directory(w.wurzel().toFile()).redirectErrorStream(true).start();
        p.getOutputStream().close();
        var puffer = new java.util.concurrent.CompletableFuture<String>();
        Thread.ofVirtual().start(() -> {
            try (var in = p.getInputStream()) { puffer.complete(new String(in.readAllBytes())); }
            catch (IOException e) { puffer.complete(""); }
        });
        if (!p.waitFor(10, TimeUnit.MINUTES)) { p.destroyForcibly(); aus[0] = ""; return false; }
        aus[0] = puffer.get(5, TimeUnit.SECONDS);
        return p.exitValue() == 0;
    }

    // ------------------------------------------------------------ Dateien

    static void kopiere(Path von, Path nach) throws IOException {
        Files.createDirectories(nach);
        Files.walkFileTree(von, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                Path rel = von.relativize(d);
                if (rel.getNameCount() == 1 && !rel.toString().isEmpty()) {
                    String n = rel.toString();
                    if (n.equals(".harness")) return FileVisitResult.SKIP_SUBTREE;
                    if (VERLINKT.contains(n)) {
                        Files.createSymbolicLink(nach.resolve(n), d);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                Files.createDirectories(nach.resolve(rel.toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Path ziel = nach.resolve(von.relativize(f).toString());
                if (a.isSymbolicLink()) Files.copy(f, ziel, LinkOption.NOFOLLOW_LINKS);
                else Files.copy(f, ziel, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Uebertraegt, was der Sieger gegenueber dem Arbeitsbereich veraendert hat:
     * geaenderte und neue Dateien hin, geloeschte weg. Laesst .git, .harness
     * und die verlinkten Verzeichnisse aus.
     *
     * @return die betroffenen Pfade, relativ
     */
    static List<String> spieleZurueck(Path sieger, Path ziel) throws IOException {
        Set<String> imSieger = dateien(sieger), imZiel = dateien(ziel);
        List<String> geaendert = new ArrayList<>();
        for (String r : imSieger) {
            Path q = sieger.resolve(r), z = ziel.resolve(r);
            if (Files.exists(z, LinkOption.NOFOLLOW_LINKS) && gleich(q, z)) continue;
            if (z.getParent() != null) Files.createDirectories(z.getParent());
            if (Files.isSymbolicLink(q)) {
                Files.deleteIfExists(z);
                Files.copy(q, z, LinkOption.NOFOLLOW_LINKS);
            } else {
                Files.copy(q, z, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            geaendert.add(r);
        }
        for (String r : imZiel)
            if (!imSieger.contains(r)) { Files.deleteIfExists(ziel.resolve(r)); geaendert.add(r); }
        geaendert.sort(null);
        return geaendert;
    }

    private static Set<String> dateien(Path wurzel) throws IOException {
        Set<String> s = new HashSet<>();
        Files.walkFileTree(wurzel, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                Path rel = wurzel.relativize(d);
                if (rel.getNameCount() == 1 && (AUSGELASSEN.contains(rel.toString())
                        || VERLINKT.contains(rel.toString()))) return FileVisitResult.SKIP_SUBTREE;
                if (d.getFileName() != null && TESTSPUREN.contains(d.getFileName().toString()))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                Path rel = wurzel.relativize(f);
                if (!(rel.getNameCount() == 1 && VERLINKT.contains(rel.toString())))
                    s.add(rel.toString());
                return FileVisitResult.CONTINUE;
            }
        });
        return s;
    }

    private static boolean gleich(Path a, Path b) throws IOException {
        if (Files.isSymbolicLink(a) || Files.isSymbolicLink(b))
            return Files.isSymbolicLink(a) && Files.isSymbolicLink(b)
                   && Files.readSymbolicLink(a).equals(Files.readSymbolicLink(b));
        return Files.size(a) == Files.size(b) && Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b));
    }

    private static void loesche(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                // Ohne FOLLOW_LINKS kommt ein verlinktes Verzeichnis (.venv) hier
                // als Datei an: geloescht wird der Verweis, nie sein Ziel.
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    Files.delete(f);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignoriert) {
            // Reste im temp-Verzeichnis sind aergerlich, aber kein Fehler des Laufs.
        }
    }

    /** Fortschritt eines Versuchs, mit Nummer davor -- sonst sind N Laeufe auf stderr ein Brei. */
    public static Beobachter mitNummer(int k, boolean laut) {
        if (!laut) return Beobachter.STILL;
        return new Beobachter() {
            @Override public void zug(int n, ChatResponse a, int budget) {
                System.err.printf("[versuch %d] Zug %d: %s, %d Werkzeug(e)%n",
                        k, n, a.finishReason(), a.message().toolCalls().size());
            }
            @Override public void werkzeugFertig(ToolCall tc, Tool.ToolResult r) {
                System.err.printf("[versuch %d]   %s%s | %s%n", k, tc.name(),
                        r.istFehler() ? " (Fehler)" : "", tc.kurz());
            }
            @Override public void hinweis(String text) { System.err.printf("[versuch %d] %s%n", k, text); }
        };
    }
}
