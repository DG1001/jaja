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
 * Merkt sich je Auftrag, wie der Arbeitsbereich vorher aussah -- damit
 * {@code /zurueck} ihn wiederherstellen kann.
 *
 * <p><b>Zwei Wege.</b> Gibt es git, haelt ein {@link Schnappschuss} den ganzen
 * Arbeitsbereich am Anfang und am Ende jedes Auftrags fest; dann wird auch
 * zurueckgenommen, was {@code bash} geaendert hat. Daneben merkt sich die
 * Sicherung im Speicher den Inhalt jeder Datei, bevor write oder edit sie zum
 * ersten Mal anfassen -- das deckt ab, was git ignoriert, und ist der ganze
 * Mechanismus, wenn es kein git gibt.
 *
 * <p><b>Von Hand Geaendertes bleibt.</b> Zurueckgenommen wird nur, was sich
 * <em>waehrend</em> des Auftrags geaendert hat. Hat jemand eine dieser Dateien
 * danach selbst bearbeitet, bleibt sie, wie sie ist, und wird genannt. Sonst
 * loeschte {@code /zurueck} ausgerechnet die Arbeit des Menschen.
 *
 * <p>Im Stapelbetrieb wird nie ein Stand begonnen; dann tut sie nichts, und
 * es entsteht auch kein Schatten-Repo.
 */
public final class Sicherung {

    /** Groessere Dateien werden nicht im Speicher gesichert, nur vermerkt. */
    static final long MAX_DATEI = 5_000_000;

    /** So viele Auftraege reicht {@code /zurueck} hoechstens zurueck. */
    static final int MAX_STAENDE = 20;

    private static final byte[] ZU_GROSS = new byte[0];

    private static final class Stand {
        final String beschreibung;
        /** Pfad -> Inhalt davor (null: gab es nicht; ZU_GROSS: nicht gesichert). */
        final Map<Path, byte[]> vorher = new LinkedHashMap<>();
        String vorBaum, nachBaum;

        Stand(String beschreibung) { this.beschreibung = beschreibung; }

        boolean leer() {
            boolean gitLeer = vorBaum == null || (nachBaum != null && vorBaum.equals(nachBaum));
            return gitLeer && vorher.isEmpty();
        }
    }

    private final Path wurzel;
    private final Deque<Stand> staende = new ArrayDeque<>();
    private Schnappschuss schnapp;
    private boolean schnappVersucht = false;
    private String warnung;

    public Sicherung(Path wurzel) {
        this.wurzel = wurzel;
    }

    /** Beginnt einen neuen Auftrag. Erst danach wird gesichert. */
    public synchronized void neuerStand(String beschreibung) {
        Stand s = new Stand(beschreibung);
        String baum = baum();
        Stand vorher = staende.peekLast();
        if (vorher != null && vorher.vorBaum != null && vorher.nachBaum == null) vorher.nachBaum = baum;
        // Ein Auftrag, der nichts geaendert hat, ist kein Schritt zurueck wert.
        if (vorher != null && vorher.leer()) staende.removeLast();
        s.vorBaum = baum;
        staende.addLast(s);
        while (staende.size() > MAX_STAENDE) staende.removeFirst();
    }

    /** Am Ende eines Auftrags: der Stand, den der Agent hinterlassen hat. */
    public synchronized void standAbschliessen() {
        Stand s = staende.peekLast();
        if (s != null && s.vorBaum != null && s.nachBaum == null) s.nachBaum = baum();
    }

    /** Vor dem Schreiben aufrufen. Pro Auftrag zaehlt nur der erste Stand einer Datei. */
    public synchronized void vorAenderung(Path p) throws IOException {
        Stand s = staende.peekLast();
        if (s == null || s.vorher.containsKey(p)) return;
        if (!Files.exists(p))                       s.vorher.put(p, null);
        else if (Files.size(p) > MAX_DATEI)         s.vorher.put(p, ZU_GROSS);
        else                                        s.vorher.put(p, Files.readAllBytes(p));
    }

    /**
     * Was {@link #zurueck} getan hat.
     *
     * @param vonHand      waehrend des Auftrags geaendert, danach aber noch
     *                     einmal -- vermutlich von Hand; nicht angefasst
     * @param mitBash      ob Aenderungen durch bash erfasst waren (git da)
     */
    public record Ergebnis(String beschreibung, List<Path> wiederhergestellt, List<Path> geloescht,
                           List<Path> nichtMoeglich, List<Path> vonHand, boolean mitBash) {}

    /**
     * Stellt den Stand vor dem letzten Auftrag her, der etwas geaendert hat.
     *
     * @return null, wenn es nichts zurueckzunehmen gibt
     */
    public synchronized Ergebnis zurueck() throws IOException {
        String jetzt = baum();
        while (!staende.isEmpty()) {
            Stand s = staende.peekLast();
            if (s.vorBaum != null && s.nachBaum == null) s.nachBaum = jetzt;
            if (!s.leer()) break;
            staende.removeLast();
        }
        Stand s = staende.pollLast();
        if (s == null) return null;

        List<Path> wieder = new ArrayList<>(), weg = new ArrayList<>(),
                   nicht = new ArrayList<>(), hand = new ArrayList<>();
        Map<String, Character> imAuftrag = Map.of();
        boolean mitGit = s.vorBaum != null && jetzt != null && schnapp != null;
        if (mitGit) {
            imAuftrag = schnapp.unterschied(s.vorBaum, s.nachBaum);
            var danach = schnapp.unterschied(s.nachBaum, jetzt).keySet();
            List<String> herstellen = new ArrayList<>();
            for (var e : imAuftrag.entrySet()) {
                Path p = wurzel.resolve(e.getKey());
                if (danach.contains(e.getKey())) { hand.add(p); continue; }
                if (e.getValue() == 'A') { if (Files.deleteIfExists(p)) weg.add(p); }
                else { herstellen.add(e.getKey()); wieder.add(p); }
            }
            schnapp.stelleHer(s.vorBaum, herstellen);
        }
        // Was git nicht kennt (ignorierte Pfade) oder ohne git: aus dem Speicher.
        for (var e : s.vorher.entrySet()) {
            Path p = e.getKey();
            if (mitGit && imAuftrag.containsKey(wurzel.relativize(p).toString().replace('\\', '/'))) continue;
            byte[] alt = e.getValue();
            if (alt == ZU_GROSS) { nicht.add(p); continue; }
            if (alt == null) { if (Files.deleteIfExists(p)) weg.add(p); continue; }
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.write(p, alt);
            if (!wieder.contains(p)) wieder.add(p);
        }
        return new Ergebnis(s.beschreibung, wieder, weg, nicht, hand, mitGit);
    }

    /** Wie viele Auftraege mit Aenderungen zurueckgenommen werden koennten. */
    public synchronized int anzahl() {
        return (int) staende.stream().filter(s -> !s.leer()).count();
    }

    /** Einmalige Meldung, falls die Schnappschuesse aufgegeben wurden; sonst null. */
    public synchronized String warnung() {
        String w = warnung;
        warnung = null;
        return w;
    }

    /** Der jetzige Baum, oder null ohne git. Ein Fehler schaltet git fuer die Sitzung ab. */
    private String baum() {
        if (!schnappVersucht) { schnappVersucht = true; schnapp = Schnappschuss.fuer(wurzel); }
        if (schnapp == null) return null;
        try {
            return schnapp.baum();
        } catch (IOException e) {
            schnapp = null;
            for (Stand s : staende) { s.vorBaum = null; s.nachBaum = null; }
            warnung = "Schnappschuesse abgeschaltet (" + e.getMessage() + ") -- /zurueck erfasst "
                    + "ab jetzt nur noch write und edit";
            return null;
        }
    }
}
