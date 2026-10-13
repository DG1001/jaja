package de.dg1001.harness.ws;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Sperrt Schreibzugriffe von {@code bash} auf den Arbeitsbereich ein.
 *
 * <p>Die Dateiwerkzeuge gehen alle durch {@link Workspace#aufloesen}; bash
 * nicht. Die README sagt dazu, eine echte Grenze brauche einen Container. Es
 * geht kleiner: macOS bringt {@code sandbox-exec} mit, Linux hat
 * {@code bwrap} (bubblewrap, in den meisten Distributionen ein Paket). Beide
 * starten einen Prozess mit eingeschraenktem Dateisystem, ohne Image, ohne
 * Daemon, in Millisekunden.
 *
 * <p><b>Was eingesperrt wird: nur das Schreiben.</b> Lesen bleibt ueberall
 * erlaubt, ebenso das Netz -- {@code pip install}, {@code git fetch} und
 * Nachbarprojekte lesen sind legitim. Der Vorfall, der das hier rechtfertigt,
 * war ein Schreibvorgang: ein Lauf hat ueber Nacht eine Loesung in ein fremdes
 * Projekt geschrieben.
 *
 * <p>Schreibbar bleiben neben der Wurzel die temporaeren Verzeichnisse und die
 * ueblichen Paketcaches im Heimatverzeichnis ({@code ~/.m2}, {@code ~/.npm},
 * {@code ~/.cache}, ...). Ohne die scheitert jeder Build, der etwas
 * herunterlaedt -- und ein Harness, der Builds bricht, wird abgeschaltet.
 *
 * <p>Ist das Werkzeug nicht da, wird nicht stillschweigend ohne Sandkasten
 * gelaufen: {@link #fuerDiesesSystem} wirft. Wer {@code --sandkasten} sagt,
 * verlaesst sich darauf.
 */
public final class Sandkasten {

    /** Caches unter dem Heimatverzeichnis, die Builds beschreiben muessen. */
    static final List<String> CACHES = List.of(
            ".m2", ".gradle", ".npm", ".pnpm-store", ".yarn", ".cache", ".cargo",
            ".rustup", "go", ".nuget", ".ivy2", ".sbt", "Library/Caches");

    private final Path wurzel;
    private final List<Path> schreibbar;
    private final boolean mac;

    private Sandkasten(Path wurzel, List<Path> schreibbar, boolean mac) {
        this.wurzel = wurzel;
        this.schreibbar = schreibbar;
        this.mac = mac;
    }

    /** @throws IOException wenn es auf diesem System keinen Sandkasten gibt */
    public static Sandkasten fuerDiesesSystem(Path wurzel) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean mac = os.contains("mac");
        if (mac && !Files.isExecutable(Path.of("/usr/bin/sandbox-exec")))
            throw new IOException("--sandkasten: /usr/bin/sandbox-exec fehlt");
        if (!mac && !imPfad("bwrap"))
            throw new IOException("--sandkasten: bwrap (bubblewrap) nicht gefunden -- "
                    + "z. B. apt install bubblewrap");

        List<Path> schreibbar = new ArrayList<>();
        schreibbar.add(wurzel);
        for (String t : new String[]{"/tmp", "/var/tmp", System.getProperty("java.io.tmpdir")})
            echt(Path.of(t), schreibbar);
        if (mac) echt(Path.of("/private/var/folders"), schreibbar);
        Path heim = Path.of(System.getProperty("user.home"));
        for (String c : CACHES) echt(heim.resolve(c), schreibbar);
        return new Sandkasten(wurzel, List.copyOf(schreibbar), mac);
    }

    /** Das Kommando, eingewickelt. */
    public List<String> umhuelle(List<String> befehl) {
        List<String> l = new ArrayList<>();
        if (mac) {
            l.add("/usr/bin/sandbox-exec");
            l.add("-p");
            l.add(profil());
        } else {
            l.addAll(List.of("bwrap", "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc",
                             "--die-with-parent"));
            for (Path p : schreibbar) {
                l.add("--bind-try");
                l.add(p.toString());
                l.add(p.toString());
            }
            l.add("--chdir");
            l.add(wurzel.toString());
            l.add("--");
        }
        l.addAll(befehl);
        return l;
    }

    /** Das macOS-Profil: alles erlaubt, ausser Schreiben ausserhalb der Liste. */
    String profil() {
        StringBuilder b = new StringBuilder("(version 1)(allow default)(deny file-write*)"
                + "(allow file-write* (literal \"/dev/null\") (subpath \"/dev/fd\")"
                + " (regex #\"^/dev/tty\")");
        for (Path p : schreibbar) b.append(" (subpath \"").append(maskiere(p.toString())).append("\")");
        return b.append(')').toString();
    }

    public List<Path> schreibbar() { return schreibbar; }

    // ----------------------------------------------------------- Hilfen

    /** Echter Pfad, falls vorhanden -- /tmp ist auf macOS ein Verweis nach /private/tmp. */
    private static void echt(Path p, List<Path> l) {
        try {
            if (!Files.isDirectory(p)) return;
            Path r = p.toRealPath();
            if (!l.contains(r)) l.add(r);
        } catch (IOException | RuntimeException ignoriert) {
            // nicht da, nicht schlimm
        }
    }

    private static boolean imPfad(String name) {
        String pfad = System.getenv("PATH");
        if (pfad == null) return false;
        for (String d : pfad.split(java.io.File.pathSeparator))
            if (!d.isEmpty() && Files.isExecutable(Path.of(d, name))) return true;
        return false;
    }

    private static String maskiere(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
