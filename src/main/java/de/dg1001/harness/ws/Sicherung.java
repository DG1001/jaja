package de.dg1001.harness.ws;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merkt sich je Auftrag, wie die Dateien vorher aussahen, die {@code write}
 * und {@code edit} anfassen -- damit {@code /zurueck} sie wiederherstellen kann.
 *
 * <p><b>Nur write und edit.</b> Was {@code bash} aendert, sieht diese Klasse
 * nicht: ein {@code sed -i}, ein Generator, ein {@code rm}. Das ist die
 * ehrliche Grenze, und {@code /zurueck} sagt sie bei jeder Benutzung dazu.
 * Wer mehr braucht, hat git.
 *
 * <p>Im Speicher, nicht auf der Platte: es ist eine Sitzungsfunktion, und eine
 * Sicherung, die einen Absturz ueberlebt, aber nach Tagen noch Dateien
 * "wiederherstellt", waere gefaehrlicher als keine. Im Stapelbetrieb wird nie
 * ein Stand begonnen, und dann tut sie nichts.
 */
public final class Sicherung {

    /** Groessere Dateien werden nicht gesichert, nur als nicht wiederherstellbar vermerkt. */
    static final long MAX_DATEI = 5_000_000;

    /** So viele Auftraege reicht {@code /zurueck} hoechstens zurueck. */
    static final int MAX_STAENDE = 20;

    /** Ein Auftrag: Pfad -> Inhalt davor (null: gab es nicht; ZU_GROSS: nicht gesichert). */
    private record Stand(String beschreibung, Map<Path, byte[]> vorher) {}

    private static final byte[] ZU_GROSS = new byte[0];

    private final Deque<Stand> staende = new ArrayDeque<>();

    /** Beginnt einen neuen Auftrag. Erst danach wird gesichert. */
    public synchronized void neuerStand(String beschreibung) {
        // Ein Auftrag, der nichts geaendert hat, ist kein Schritt zurueck wert.
        if (!staende.isEmpty() && staende.peekLast().vorher().isEmpty()) staende.removeLast();
        staende.addLast(new Stand(beschreibung, new LinkedHashMap<>()));
        while (staende.size() > MAX_STAENDE) staende.removeFirst();
    }

    /** Vor dem Schreiben aufrufen. Pro Auftrag zaehlt nur der erste Stand einer Datei. */
    public synchronized void vorAenderung(Path p) throws IOException {
        Stand s = staende.peekLast();
        if (s == null || s.vorher().containsKey(p)) return;
        if (!Files.exists(p))                       s.vorher().put(p, null);
        else if (Files.size(p) > MAX_DATEI)         s.vorher().put(p, ZU_GROSS);
        else                                        s.vorher().put(p, Files.readAllBytes(p));
    }

    /** Was {@link #zurueck} getan hat. */
    public record Ergebnis(String beschreibung, List<Path> wiederhergestellt,
                           List<Path> geloescht, List<Path> nichtMoeglich) {}

    /**
     * Stellt den Stand vor dem letzten Auftrag her, der etwas geaendert hat.
     *
     * @return null, wenn es nichts zurueckzunehmen gibt
     */
    public synchronized Ergebnis zurueck() throws IOException {
        while (!staende.isEmpty() && staende.peekLast().vorher().isEmpty()) staende.removeLast();
        Stand s = staende.pollLast();
        if (s == null) return null;
        List<Path> wieder = new ArrayList<>(), weg = new ArrayList<>(), nicht = new ArrayList<>();
        for (var e : s.vorher().entrySet()) {
            Path p = e.getKey();
            byte[] alt = e.getValue();
            if (alt == ZU_GROSS) { nicht.add(p); continue; }
            if (alt == null) {
                if (Files.deleteIfExists(p)) weg.add(p);
                continue;
            }
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.write(p, alt);
            wieder.add(p);
        }
        return new Ergebnis(s.beschreibung(), wieder, weg, nicht);
    }

    /** Wie viele Auftraege mit Aenderungen zurueckgenommen werden koennten. */
    public synchronized int anzahl() {
        return (int) staende.stream().filter(s -> !s.vorher().isEmpty()).count();
    }
}
