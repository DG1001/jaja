package de.dg1001.harness.bench;

import de.dg1001.harness.wire.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Laesst Aufgaben unter mehreren Konfigurationen laufen und haelt fest, was
 * herauskam -- das, was dieses Repo ueber tausend Laeufe lang von Hand getan hat.
 *
 * <p>Die Regeln stammen aus den Fehlern der Handarbeit:
 * <ul>
 * <li><b>Reihenfolge rotiert je Runde.</b> Ein Server, der ueber Stunden
 *     langsamer wird, soll keine Konfiguration beguenstigen.
 * <li><b>Jeder Lauf in eigenem Verzeichnis und eigenem Prozess.</b> Ein
 *     Lauf, der abstuerzt, haengt oder {@code System.exit} ruft, nimmt den
 *     Runner nicht mit, und hinterher liegt alles zum Nachsehen da.
 * <li><b>Versteckte Pruefungen kommen erst nach dem Lauf hinein.</b> Sonst
 *     liest das Modell sie und optimiert auf sie.
 * <li><b>Fortsetzbar.</b> Wer neun Stunden laufen laesst, unterbricht. Ein
 *     zweiter Aufruf ueberspringt, was in {@code ergebnisse.jsonl} schon steht.
 * </ul>
 *
 * <p>Die Definition ist JSON (siehe README, Abschnitt Benchmark); Pfade darin
 * gelten relativ zur Definitionsdatei.
 */
public final class Bench {

    record Aufgabe(String name, Path vorlage, String prompt, Path versteckt,
                   String pruefung, Pattern punkte) {}

    record Konfig(String name, List<String> argumente) {}

    record Definition(List<String> jaja, List<Aufgabe> aufgaben, List<Konfig> konfigs,
                      int runden, int parallel, int minuten) {}

    record Lauf(int runde, Aufgabe aufgabe, Konfig konfig) {
        String id() { return "r" + runde + "-" + aufgabe.name() + "-" + konfig.name(); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[0].equals("--bericht")) {
            System.out.print(Bericht.erzeuge(Path.of(args[1])));
            return;
        }
        if (args.length == 0) {
            System.err.println("""
                jaja bench <definition.json> [--ausgabe <verz>] [--plan]
                jaja bench --bericht <verz>""");
            System.exit(2);
        }
        Path def = Path.of(args[0]).toAbsolutePath();
        Path ausgabe = null;
        boolean nurPlan = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--ausgabe") && i + 1 < args.length) ausgabe = Path.of(args[++i]);
            else if (args[i].equals("--plan")) nurPlan = true;
        }
        if (ausgabe == null) {
            String n = def.getFileName().toString().replaceFirst("\\.json$", "");
            ausgabe = def.resolveSibling("bench-" + n);
        }
        Definition d = lies(def);
        List<Lauf> plan = plan(d);
        if (nurPlan) {
            for (Lauf l : plan) System.out.println(l.id());
            System.out.println(plan.size() + " Laeufe");
            return;
        }
        lauf(d, plan, ausgabe.toAbsolutePath(), Files.readString(def));
        System.out.print(Bericht.erzeuge(ausgabe));
    }

    // ---------------------------------------------------------- Definition

    static Definition lies(Path datei) throws IOException {
        Object w = Json.parse(Files.readString(datei));
        Path basis = datei.toAbsolutePath().getParent();
        List<String> jaja = texte(Json.feld(w, "jaja"));
        List<Aufgabe> aufgaben = new ArrayList<>();
        for (Object a : Json.arr(Json.feld(w, "aufgaben"))) {
            String name = pflicht(a, "name");
            if (!name.matches("[A-Za-z0-9_.-]+"))
                throw new IllegalArgumentException("Aufgabenname nur aus Buchstaben, Ziffern, _ . -: " + name);
            String prompt = Json.str(Json.feld(a, "prompt"));
            String promptDatei = Json.str(Json.feld(a, "prompt_datei"));
            if (prompt == null && promptDatei != null) prompt = Files.readString(basis.resolve(promptDatei));
            if (prompt == null) throw new IllegalArgumentException(name + ": prompt oder prompt_datei fehlt");
            String versteckt = Json.str(Json.feld(a, "versteckt"));
            String punkte = Json.str(Json.feld(a, "punkte"));
            String vorlage = Json.str(Json.feld(a, "vorlage"));
            aufgaben.add(new Aufgabe(name,
                    vorlage == null ? null : basis.resolve(vorlage).normalize(),
                    prompt,
                    versteckt == null ? null : basis.resolve(versteckt).normalize(),
                    pflicht(a, "pruefung"),
                    punkte == null ? null : Pattern.compile(punkte)));
        }
        List<Konfig> konfigs = new ArrayList<>();
        for (Object k : Json.arr(Json.feld(w, "konfigurationen"))) {
            String name = pflicht(k, "name");
            if (!name.matches("[A-Za-z0-9_.-]+"))
                throw new IllegalArgumentException("Konfigurationsname nur aus Buchstaben, Ziffern, _ . -: " + name);
            konfigs.add(new Konfig(name, texte(Json.feld(k, "argumente"))));
        }
        if (konfigs.isEmpty()) konfigs.add(new Konfig("basis", List.of()));
        if (aufgaben.isEmpty()) throw new IllegalArgumentException("keine Aufgaben");
        return new Definition(jaja, aufgaben, konfigs,
                Math.max(1, Json.num(Json.feld(w, "runden"), 1)),
                Math.max(1, Json.num(Json.feld(w, "parallel"), 1)),
                Math.max(1, Json.num(Json.feld(w, "zeitgrenze_minuten"), 60)));
    }

    /** Runde fuer Runde; innerhalb der Runde die Konfigurationen rotiert. */
    static List<Lauf> plan(Definition d) {
        List<Lauf> l = new ArrayList<>();
        int k = d.konfigs().size();
        for (int r = 1; r <= d.runden(); r++)
            for (Aufgabe a : d.aufgaben())
                for (int i = 0; i < k; i++)
                    l.add(new Lauf(r, a, d.konfigs().get((i + r - 1) % k)));
        return l;
    }

    // ----------------------------------------------------------- Ausfuehren

    static void lauf(Definition d, List<Lauf> plan, Path ausgabe, String definitionText)
            throws Exception {
        Files.createDirectories(ausgabe.resolve("laeufe"));
        Files.writeString(ausgabe.resolve("definition.json"), definitionText);
        Path ergebnisse = ausgabe.resolve("ergebnisse.jsonl");
        Set<String> fertig = erledigt(ergebnisse);
        List<Lauf> offen = plan.stream().filter(l -> !fertig.contains(l.id())).toList();
        System.err.printf("[bench] %d Laeufe, %d schon erledigt, %d offen, %d parallel%n",
                plan.size(), plan.size() - offen.size(), offen.size(), d.parallel());

        int[] zaehler = {plan.size() - offen.size()};
        try (ExecutorService exec = Executors.newFixedThreadPool(d.parallel())) {
            List<Future<?>> fs = new ArrayList<>();
            for (Lauf l : offen)
                fs.add(exec.submit(() -> {
                    Map<String, Object> e = einLauf(d, l, ausgabe.resolve("laeufe").resolve(l.id()));
                    synchronized (zaehler) {
                        Files.writeString(ergebnisse, new Json.Writer().wert(e) + "\n",
                                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        zaehler[0]++;
                        System.err.printf("[bench] %d/%d %s: %s, %s Zuege, %s s%s%n",
                                zaehler[0], plan.size(), l.id(), e.get("status"),
                                e.get("zuege") instanceof Number z ? z.intValue() : "?",
                                e.get("sekunden"), Boolean.TRUE.equals(e.get("bestanden")) ? ", bestanden" : "");
                    }
                    return null;
                }));
            for (Future<?> f : fs) f.get();
        }
    }

    static Map<String, Object> einLauf(Definition d, Lauf l, Path dir) throws Exception {
        if (Files.exists(dir)) loesche(dir);                  // Rest eines abgebrochenen Versuchs
        Path arbeit = dir.resolve("arbeit");
        Files.createDirectories(arbeit);
        if (l.aufgabe().vorlage() != null) kopiere(l.aufgabe().vorlage(), arbeit);
        Path aufgabe = dir.resolve("aufgabe.txt");
        Files.writeString(aufgabe, l.aufgabe().prompt());
        Path trace = dir.resolve("trace.jsonl");

        // jaja.klassenpfad fuer den Fall, dass der Runner selbst nicht aus dem
        // Jar laeuft (unter Maven zeigt java.class.path auf Maven).
        String cp = System.getProperty("jaja.klassenpfad", System.getProperty("java.class.path"));
        List<String> cmd = new ArrayList<>(List.of(java(), "-cp", cp, "de.dg1001.harness.Main"));
        cmd.addAll(d.jaja());
        cmd.addAll(l.konfig().argumente());
        cmd.addAll(List.of("--cwd", arbeit.toString(), "--prompt-file", aufgabe.toString(),
                           "--trace", trace.toString()));

        long t0 = System.nanoTime();
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(dir.resolve("agent.log").toFile());
        Process p = pb.start();
        boolean rechtzeitig = p.waitFor(d.minuten(), TimeUnit.MINUTES);
        if (!rechtzeitig) beende(p);
        double sekunden = Math.round((System.nanoTime() - t0) / 1e8) / 10.0;

        // Erst jetzt die versteckten Pruefungen -- das Modell soll sie nie sehen.
        if (l.aufgabe().versteckt() != null) kopiere(l.aufgabe().versteckt(), arbeit);
        Pruefung pr = pruefe(l.aufgabe(), arbeit, dir.resolve("pruefung.log"));

        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", l.id());
        e.put("runde", l.runde());
        e.put("aufgabe", l.aufgabe().name());
        e.put("konfig", l.konfig().name());
        e.put("rc", rechtzeitig ? p.exitValue() : -1);
        e.put("sekunden", sekunden);
        e.put("bestanden", pr.bestanden());
        e.put("punkte", pr.punkte());
        Map<String, Object> ende = letztesEnde(trace);
        e.put("status", !rechtzeitig ? "ZEITGRENZE" : ende == null ? "OHNE_PROTOKOLL" : ende.get("status"));
        for (String k : List.of("zuege", "aufrufe", "eingabe", "ausgabe", "ereignisse",
                                "werkzeugaufrufe", "werkzeugfehler"))
            e.put(k, ende == null ? null : ende.get(k));
        return e;
    }

    record Pruefung(boolean bestanden, Integer punkte) {}

    static Pruefung pruefe(Aufgabe a, Path arbeit, Path log) throws Exception {
        Process p = new ProcessBuilder("bash", "-lc", a.pruefung()).directory(arbeit.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!p.waitFor(10, TimeUnit.MINUTES)) { beende(p); return new Pruefung(false, null); }
        Integer punkte = null;
        if (a.punkte() != null) {
            Matcher m = a.punkte().matcher(Files.readString(log));
            while (m.find()) punkte = Integer.parseInt(m.group(1));   // der letzte Treffer zaehlt
            if (punkte == null) punkte = 0;
        }
        return new Pruefung(p.exitValue() == 0, punkte);
    }

    // -------------------------------------------------------------- Zutaten

    @SuppressWarnings("unchecked")
    static Map<String, Object> letztesEnde(Path trace) {
        if (!Files.exists(trace)) return null;
        try {
            List<String> z = Files.readAllLines(trace);
            for (int i = z.size() - 1; i >= 0; i--) {
                if (!z.get(i).contains("\"t\":\"ende\"")) continue;
                Object o = Json.parse(z.get(i));
                if (o instanceof Map) return (Map<String, Object>) o;
            }
        } catch (IOException | RuntimeException ignoriert) {
            // kaputtes Protokoll: dann eben ohne
        }
        return null;
    }

    static Set<String> erledigt(Path ergebnisse) throws IOException {
        Set<String> s = new HashSet<>();
        if (!Files.exists(ergebnisse)) return s;
        for (String z : Files.readAllLines(ergebnisse)) {
            if (z.isBlank()) continue;
            try { s.add(Json.str(Json.feld(Json.parse(z), "id"))); }
            catch (RuntimeException ignoriert) { /* halbe Zeile vom Abbruch */ }
        }
        return s;
    }

    private static String java() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    private static void beende(Process p) {
        List<ProcessHandle> kinder = p.descendants().toList();
        p.destroyForcibly();
        kinder.forEach(ProcessHandle::destroyForcibly);
    }

    private static List<String> texte(Object o) {
        List<String> l = new ArrayList<>();
        for (Object x : Json.arr(o)) l.add(String.valueOf(x));
        return l;
    }

    private static String pflicht(Object o, String feld) {
        String s = Json.str(Json.feld(o, feld));
        if (s == null || s.isBlank()) throw new IllegalArgumentException("Feld '" + feld + "' fehlt");
        return s;
    }

    static void kopiere(Path von, Path nach) throws IOException {
        if (!Files.isDirectory(von)) throw new IOException("kein Verzeichnis: " + von);
        Files.walkFileTree(von, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                Files.createDirectories(nach.resolve(von.relativize(d).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.copy(f, nach.resolve(von.relativize(f).toString()),
                           StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void loesche(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
