package de.dg1001.harness.ws;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Der Arbeitsbereich: eine Wurzel, aus der die Werkzeuge nicht ausbrechen.
 *
 * <p>Das ist keine Theorie. In der Diagnose eines fehlgeschlagenen Laufs hat ein
 * Modell ueber einen etablierten Harness {@code find / -maxdepth 10 -type d
 * -name ".venv"} ausgefuehrt und dabei fremde Verzeichnisse inspiziert — bei
 * nominell auf das Projektverzeichnis gesetztem Arbeitsbereich. Wer mit
 * Selbstgenehmigung arbeitet, sollte die Grenze echt ziehen.
 *
 * <p>Vollstaendig dicht bekommt man eine Shell damit nicht — dafuer braeuchte es
 * einen Container. Aber die versehentlichen Ausbrueche faengt es, und die sind
 * der Normalfall.
 */
public final class Workspace {

    private final Path wurzel;
    private final Path auslagerung;

    public Workspace(Path wurzel) throws IOException {
        this.wurzel = wurzel.toAbsolutePath().normalize().toRealPath();
        this.auslagerung = this.wurzel.resolve(".harness/spill");
        Files.createDirectories(this.auslagerung);
    }

    public Path wurzel() { return wurzel; }
    public Path auslagerungsverzeichnis() { return auslagerung; }

    /**
     * Loest einen vom Modell gelieferten Pfad auf und stellt sicher, dass er
     * innerhalb der Wurzel liegt.
     *
     * <p>Bewusst {@code normalize()} vor der Pruefung: sonst kaeme {@code
     * a/../../etc/passwd} durch. Und bewusst KEIN {@code toRealPath()} auf dem
     * Ergebnis — das wuerde bei noch nicht existierenden Dateien werfen, die
     * beim Schreiben ja der Normalfall sind. Symlinks, die nach draussen
     * zeigen, prueft {@link #pruefeVorhandenen} zusaetzlich.
     */
    public Path aufloesen(String pfad) {
        if (pfad == null || pfad.isBlank())
            throw new AusbruchFehler("leerer Pfad");
        Path p = wurzel.resolve(pfad).normalize().toAbsolutePath();
        if (!p.startsWith(wurzel))
            throw new AusbruchFehler("ausserhalb des Arbeitsbereichs: " + pfad);
        return p;
    }

    /**
     * Sucht in einem Shell-Kommando nach Pfaden, die aus dem Arbeitsbereich
     * herausfuehren, und beschreibt sie — oder gibt {@code null} zurueck.
     *
     * <p>Das ist eine <b>Heuristik zur Diagnose, keine Absicherung.</b> Sie
     * sieht nur, was als Pfad im Kommando steht: nicht, was ein aufgerufenes
     * Skript tut, nicht berechnete Pfade, nicht {@code $VAR}. Wer eine Grenze
     * braucht, die haelt, braucht einen Container.
     *
     * <p>Gebaut nach einem Vorfall, der genau deshalb nicht aufzuklaeren war:
     * ein Lauf hat ueber Nacht in einem fremden Projekt geschrieben, und das
     * Protokoll hielt von jedem Werkzeugaufruf nur den Namen fest. Ein Hinweis
     * hier haette gereicht, um zu wissen, welcher Lauf es war.
     *
     * <p>Systemverzeichnisse und {@code /tmp} gelten nicht als Ausbruch — sonst
     * warnt jedes {@code /usr/bin/env} und der Hinweis geht im Rauschen unter.
     */
    public String verlaesstBereich(String kommando) {
        if (kommando == null || kommando.isBlank()) return null;
        java.util.LinkedHashSet<String> draussen = new java.util.LinkedHashSet<>();
        for (String roh : kommando.split("[\\s;|&()<>\"']+")) {
            String s = roh.trim();
            // Nur was wie ein Pfad aussieht. Ein blosses "pytest" ist keiner.
            if (s.isEmpty() || (!s.contains("/") && !s.startsWith("~"))) continue;
            if (s.startsWith("-")) continue;                 // Schalter wie --dir=/x
            if (s.startsWith("~")) s = System.getProperty("user.home") + s.substring(1);
            Path p;
            try { p = wurzel.resolve(s).normalize().toAbsolutePath(); }
            catch (RuntimeException e) { continue; }
            if (p.startsWith(wurzel)) continue;
            String t2 = p.toString();
            if (t2.startsWith("/usr") || t2.startsWith("/bin") || t2.startsWith("/sbin")
             || t2.startsWith("/lib") || t2.startsWith("/etc") || t2.startsWith("/proc")
             || t2.startsWith("/sys") || t2.startsWith("/dev") || t2.startsWith("/opt")
             || t2.startsWith("/tmp") || t2.startsWith("/var/tmp")) continue;
            draussen.add(t2);
            if (draussen.size() >= 3) break;
        }
        return draussen.isEmpty() ? null : String.join(", ", draussen);
    }

    /** Zusaetzliche Pruefung fuer existierende Dateien: folgt Symlinks und
     *  prueft das echte Ziel. */
    public Path pruefeVorhandenen(Path p) throws IOException {
        if (!Files.exists(p)) return p;
        Path echt = p.toRealPath();
        if (!echt.startsWith(wurzel))
            throw new AusbruchFehler("Verweis zeigt nach draussen: " + relativ(p));
        return echt;
    }

    /** Fuer Ausgaben ans Modell: kurze, wurzelrelative Pfade statt absoluter. */
    public String relativ(Path p) {
        Path a = p.toAbsolutePath().normalize();
        return a.startsWith(wurzel) ? wurzel.relativize(a).toString() : a.toString();
    }

    public static class AusbruchFehler extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public AusbruchFehler(String m) { super(m); }
    }
}
