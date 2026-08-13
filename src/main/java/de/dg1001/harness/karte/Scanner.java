package de.dg1001.harness.karte;

import de.dg1001.harness.tools.GlobTool;
import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durchlaeuft den Baum und haelt die Karte aktuell.
 *
 * <p><b>Inkrementell, und das ist der Punkt.</b> Bei jedem Aufruf wird zwar der
 * Baum gelaufen — das kostet nur Verzeichniseintraege —, aber gelesen wird eine
 * Datei nur, wenn sich Groesse oder Aenderungszeit unterscheiden. Ohne das
 * waere die Karte bei jedem Werkzeugaufruf ein vollstaendiger Lesevorgang ueber
 * das Projekt, und die Ersparnis waere dahin, die sie verspricht.
 *
 * <p>{@link #gelesen()} zaehlt die tatsaechlichen Lesevorgaenge. Das ist keine
 * Statistik fuer den Nutzer, sondern das, woran die Pruefung erkennt, dass die
 * Inkrementalitaet wirklich greift.
 */
public final class Scanner {

    /** Groesser als das ist kein Quelltext mehr. Wie in {@code GrepTool}. */
    static final long MAX_DATEIGROESSE = 2_000_000;

    /** Deckel gegen versehentliche Riesenbaeume (node_modules ohne .gitignore o. ae.). */
    static final int MAX_DATEIEN = 4_000;

    private final Workspace ws;
    private int gelesen = 0;
    private int uebersprungen = 0;
    private boolean deckelErreicht = false;

    public Scanner(Workspace ws) { this.ws = ws; }

    public int gelesen() { return gelesen; }
    public int uebersprungen() { return uebersprungen; }

    /** Wurde der Durchlauf am Deckel abgeschnitten? Muss gesagt werden: eine
     *  Karte, die stillschweigend ein Sechstel des Projekts weglaesst, sieht
     *  aus wie eine vollstaendige. */
    public boolean deckelErreicht() { return deckelErreicht; }

    /**
     * Baut die Karte neu auf Basis der alten.
     *
     * @param alt bisheriger Stand, Schluessel ist der Projektpfad
     * @return neuer Stand; verschwundene Dateien fehlen darin
     */
    public Map<String, Quelldatei> aktualisiere(Map<String, Quelldatei> alt) throws IOException {
        Map<String, Quelldatei> neu = new LinkedHashMap<>();

        Files.walkFileTree(ws.wurzel(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                return GlobTool.UEBERSPRINGEN.contains(d.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                if (neu.size() >= MAX_DATEIEN) {
                    deckelErreicht = true;
                    return FileVisitResult.TERMINATE;
                }
                String rel = ws.wurzel().relativize(f).toString().replace('\\', '/');

                Sprachen.Sprache sprache = Sprachen.fuer(rel);
                if (sprache == null) { uebersprungen++; return FileVisitResult.CONTINUE; }
                if (a.size() > MAX_DATEIGROESSE) { uebersprungen++; return FileVisitResult.CONTINUE; }

                String mtime = Long.toString(a.lastModifiedTime().toMillis());
                Quelldatei vorher = alt.get(rel);

                // Der billige Vergleich: unveraendert heisst nicht lesen.
                if (vorher != null && vorher.groesse() == (int) a.size()
                        && mtime.equals(vorher.mtime())) {
                    neu.put(rel, vorher);
                    return FileVisitResult.CONTINUE;
                }

                try {
                    neu.put(rel, untersuche(f, rel, sprache, (int) a.size(), mtime, vorher));
                } catch (MalformedInputException e) {
                    uebersprungen++;                 // keine Textdatei
                } catch (IOException e) {
                    uebersprungen++;                 // unlesbar
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });

        return verweiseAufloesen(neu);
    }

    // ------------------------------------------------------------ eine Datei

    private Quelldatei untersuche(Path f, String rel, Sprachen.Sprache sprache,
                                  int groesse, String mtime, Quelldatei vorher) throws IOException {
        String text = Files.readString(f);
        gelesen++;

        String hash = hash(text);
        int zeilen = text.isEmpty() ? 0 : (int) text.chars().filter(c -> c == '\n').count() + 1;

        // Beschreibung wandert mit, auch wenn sie jetzt veraltet ist: erst beim
        // Anzeigen wird entschieden, ob sie noch gilt. So bleibt sie erhalten,
        // wenn jemand eine Aenderung zurueckdreht.
        return new Quelldatei(rel, groesse, mtime, hash, sprache.name(), zeilen,
                treffer(text, sprache.definitionen(), true),
                treffer(text, sprache.importe(), false),
                List.of(),
                vorher == null ? null : vorher.beschreibung(),
                vorher == null ? List.of() : vorher.stichworte(),
                vorher == null ? null : vorher.beschreibungFuerHash());
    }

    /** @param alsSignatur true fuer Definitionen (art + name + args), sonst nur die Modulgruppe */
    private static List<String> treffer(String text, List<Pattern> muster, boolean alsSignatur) {
        Set<String> aus = new LinkedHashSet<>();
        for (Pattern p : muster) {
            Matcher m = p.matcher(text);
            while (m.find() && aus.size() < 200) {
                aus.add(alsSignatur ? signatur(m) : gruppe(m, "modul"));
            }
        }
        aus.remove(null);
        return new ArrayList<>(aus);
    }

    private static String signatur(Matcher m) {
        String art  = gruppe(m, "art");
        String name = gruppe(m, "name");
        String args = gruppe(m, "args");
        if (name == null) return null;
        StringBuilder b = new StringBuilder();
        if (art != null) b.append(art).append(' ');
        b.append(name);
        if (args != null) b.append(args.length() > 40 ? "(…)" : args);
        return b.toString();
    }

    /** Benannte Gruppen sind je Muster verschieden vorhanden — fehlende geben null. */
    private static String gruppe(Matcher m, String name) {
        try {
            return m.group(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------- Verweise

    /**
     * Loest Modulnamen auf Projektpfade auf und traegt die Vorwaertsverweise ein.
     *
     * <p>Das Verfahren ist absichtlich schlicht: aus dem Modulnamen wird ein
     * Pfadende gebaut ({@code de.dg1001.wire.Json} → {@code de/dg1001/wire/Json.java}),
     * und gesucht wird nach einer Projektdatei, die auf dieses Ende passt.
     * Damit stimmt es fuer Java-Pakete, Python-Module und relative JS-Pfade
     * gleichermassen, ohne dass irgendwo Projekteinstellungen gelesen werden
     * muessten. Importe aus Fremdbibliotheken finden nichts und fallen still
     * heraus — sie stehen weiterhin in {@code rohImporte}.
     */
    private static Map<String, Quelldatei> verweiseAufloesen(Map<String, Quelldatei> karte) {
        Map<String, List<String>> nachDateiname = new HashMap<>();
        for (String pfad : karte.keySet())
            nachDateiname.computeIfAbsent(dateiname(pfad), k -> new ArrayList<>()).add(pfad);

        Map<String, Quelldatei> aus = new LinkedHashMap<>();
        for (Quelldatei q : karte.values()) {
            Sprachen.Sprache sprache = Sprachen.nachName(q.sprache());
            Set<String> ziele = new LinkedHashSet<>();
            for (String roh : q.rohImporte()) {
                String ziel = finde(roh, sprache, q.pfad(), nachDateiname);
                if (ziel != null && !ziel.equals(q.pfad())) ziele.add(ziel);
            }
            aus.put(q.pfad(), q.mitVerweisen(new ArrayList<>(ziele)));
        }
        return aus;
    }

    private static String finde(String roh, Sprachen.Sprache sprache, String eigenerPfad,
                                Map<String, List<String>> nachDateiname) {
        if (roh == null || roh.isBlank() || sprache == null) return null;

        // Ob ein Pfadende genuegt oder der Pfad genau stimmen muss.
        //
        // Ein mehrteiliger Name wie de.dg1001.wire.Json ist spezifisch genug,
        // dass ein Pfadende reicht -- man weiss ja nicht, unter welchem
        // Quellverzeichnis das Paket liegt. Ein einteiliger Name ist es nicht:
        // 'collections.py' als Ende trifft django/contrib/gis/geos/collections.py,
        // obwohl 'import collections' die Standardbibliothek meint. Dasselbe gilt
        // fuer aufgeloeste relative Pfade -- die stehen bereits vollstaendig da.
        boolean genau = !roh.contains(String.valueOf(sprache.modulTrenner()))
                     || roh.startsWith(".");

        for (String kandidat : kandidaten(roh, sprache, eigenerPfad)) {
            List<String> moegliche = nachDateiname.get(dateiname(kandidat));
            if (moegliche == null) continue;
            for (String p : moegliche) {
                if (p.equals(kandidat)) return p;
                if (!genau && p.endsWith("/" + kandidat)) return p;
            }
        }
        return null;
    }

    /** Mögliche Pfadenden fuer einen Modulnamen, in der Reihenfolge der Plausibilitaet. */
    private static List<String> kandidaten(String roh, Sprachen.Sprache sprache,
                                           String eigenerPfad) {
        List<String> aus = new ArrayList<>();

        // Relative Angaben (JS, Shell) zeigen vom eigenen Verzeichnis aus.
        if (roh.startsWith(".") && roh.contains("/")) {
            String basis = eigenerPfad.contains("/")
                    ? eigenerPfad.substring(0, eigenerPfad.lastIndexOf('/')) : "";
            String zusammen = normalisiere(basis.isEmpty() ? roh : basis + "/" + roh);
            for (String e : sprache.endungen()) aus.add(zusammen + e);
            for (String e : sprache.endungen()) aus.add(zusammen + "/index" + e);
            aus.add(zusammen);
            return aus;
        }

        String pfad = roh.replace(sprache.modulTrenner(), '/');

        // Einteilige Namen sind fast immer Fremdcode: 'import uuid' meint die
        // Standardbibliothek, nicht irgendeine uuid.py fuenf Verzeichnisse
        // weiter. Gemessen an Django, wo 'from collections import defaultdict'
        // auf django/contrib/gis/geos/collections.py zeigte -- eine erfundene
        // Kante, die obendrein den Eingangsgrad dieser Datei aufblies.
        // Erlaubt sind deshalb nur Nachbardateien und die Projektwurzel.
        if (!pfad.contains("/")) {
            String basis = eigenerPfad.contains("/")
                    ? eigenerPfad.substring(0, eigenerPfad.lastIndexOf('/') + 1) : "";
            for (String e : sprache.endungen()) aus.add(basis + pfad + e);
            if (!basis.isEmpty())
                for (String e : sprache.endungen()) aus.add(pfad + e);
            if (sprache.modulTrenner() == '.') {
                aus.add(basis + pfad + "/__init__.py");
                aus.add(pfad + "/__init__.py");
            }
            return aus;
        }

        for (String e : sprache.endungen()) aus.add(pfad + e);
        // Python-Pakete: modelle.artikel kann auch modelle/artikel/__init__.py sein
        if (sprache.modulTrenner() == '.') aus.add(pfad + "/__init__.py");
        aus.add(pfad);

        // Java importiert Klassen, nicht Dateien: bei inneren Klassen ist die
        // letzte Ebene keine Datei. Dann die Ebene darueber probieren.
        int letzter = pfad.lastIndexOf('/');
        if (letzter > 0)
            for (String e : sprache.endungen()) aus.add(pfad.substring(0, letzter) + e);
        return aus;
    }

    /** Loest ./ und ../ auf, ohne das Dateisystem zu fragen. */
    static String normalisiere(String pfad) {
        List<String> teile = new ArrayList<>();
        for (String t : pfad.split("/")) {
            if (t.isEmpty() || t.equals(".")) continue;
            if (t.equals("..")) { if (!teile.isEmpty()) teile.remove(teile.size() - 1); }
            else teile.add(t);
        }
        return String.join("/", teile);
    }

    private static String dateiname(String pfad) {
        int i = pfad.lastIndexOf('/');
        return i < 0 ? pfad : pfad.substring(i + 1);
    }

    // ----------------------------------------------------------------- Hash

    /** SHA-256, auf 16 Stellen gekuerzt. Aus dem JDK, keine Abhaengigkeit. */
    static String hash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(16);
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt in dieser Laufzeitumgebung", e);
        }
    }
}
