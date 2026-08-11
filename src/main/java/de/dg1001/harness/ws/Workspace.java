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
