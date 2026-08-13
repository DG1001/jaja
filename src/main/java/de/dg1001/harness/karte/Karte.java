package de.dg1001.harness.karte;

import de.dg1001.harness.wire.Json;
import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Die Quellenkarte: was im Projekt liegt, was es kann, was mit was zusammenhaengt.
 *
 * <p>Sie loest ein gemessenes Problem: der Agent verbringt bei groesseren
 * Projekten mehrere Zuege mit Orientierung — glob, grep, drei Dateien lesen —
 * bevor er die eine findet, um die es geht. Auf lokaler Hardware kostet jeder
 * dieser Zuege zehn Sekunden bis Minuten.
 *
 * <p>Abgelegt unter {@code .harness/karte.json}, einzeilig. Das ist ein
 * Zwischenspeicher, kein Dokument: lesbar gemacht wird ueber das Werkzeug und
 * {@code /karte}. {@code .harness} steht in {@code UEBERSPRINGEN}, die Karte
 * indiziert sich also nicht selbst.
 */
public final class Karte {

    /** Wie viele Dateien eine Uebersicht hoechstens zeigt. */
    static final int MAX_ZEILEN = 60;

    /** Und wie viele Zeichen. Deutlich unter {@code Spill.GRENZE}: eine Karte,
     *  die in eine Auslagerungsdatei kippt, hat ihren Zweck verfehlt. */
    static final int MAX_ZEICHEN = 6_000;

    private final Workspace ws;
    private final Path datei;
    private Map<String, Quelldatei> dateien = new LinkedHashMap<>();

    public Karte(Workspace ws) {
        this.ws = ws;
        this.datei = ws.wurzel().resolve(".harness").resolve("karte.json");
    }

    public Map<String, Quelldatei> dateien() { return Map.copyOf(dateien); }
    public int anzahl() { return dateien.size(); }
    public Path ablage() { return datei; }

    // ------------------------------------------------------------ Persistenz

    public void laden() {
        if (!Files.isRegularFile(datei)) return;
        try {
            Map<String, Quelldatei> neu = new LinkedHashMap<>();
            for (Object o : Json.arr(Json.parse(Files.readString(datei)))) {
                Quelldatei q = ausJson(o);
                if (q != null) neu.put(q.pfad(), q);
            }
            dateien = neu;
        } catch (IOException | RuntimeException e) {
            // Eine kaputte oder alte Karte ist kein Grund aufzugeben: sie wird
            // beim naechsten Durchlauf ohnehin neu gebaut.
            dateien = new LinkedHashMap<>();
        }
    }

    public void sichern() throws IOException {
        Files.createDirectories(datei.getParent());
        Json.Writer w = new Json.Writer().listeAuf();
        for (Quelldatei q : dateien.values()) nachJson(w, q);
        Files.writeString(datei, w.listeZu().toString());
    }

    private static void nachJson(Json.Writer w, Quelldatei q) {
        w.objektAuf();
        w.feld("pfad").text(q.pfad());
        w.feld("groesse").zahl(q.groesse());
        w.feld("mtime").text(q.mtime());              // als Text: Json.num kann nur int
        w.feld("hash").text(q.hash());
        w.feld("sprache").text(q.sprache());
        w.feld("zeilen").zahl(q.zeilen());
        liste(w, "definitionen", q.definitionen());
        liste(w, "rohImporte", q.rohImporte());
        liste(w, "verweise", q.verweise());
        w.textFeld("beschreibung", q.beschreibung());
        if (!q.stichworte().isEmpty()) liste(w, "stichworte", q.stichworte());
        w.textFeld("beschreibungFuerHash", q.beschreibungFuerHash());
        w.objektZu();
    }

    private static void liste(Json.Writer w, String name, List<String> werte) {
        w.feld(name).listeAuf();
        for (String s : werte) w.text(s);
        w.listeZu();
    }

    private static Quelldatei ausJson(Object o) {
        String pfad = Json.str(Json.feld(o, "pfad"));
        if (pfad == null) return null;
        return new Quelldatei(pfad,
                Json.num(Json.feld(o, "groesse"), 0),
                Json.str(Json.feld(o, "mtime")),
                Json.str(Json.feld(o, "hash")),
                Json.str(Json.feld(o, "sprache")),
                Json.num(Json.feld(o, "zeilen"), 0),
                texte(o, "definitionen"), texte(o, "rohImporte"), texte(o, "verweise"),
                Json.str(Json.feld(o, "beschreibung")),
                texte(o, "stichworte"),
                Json.str(Json.feld(o, "beschreibungFuerHash")));
    }

    private static List<String> texte(Object o, String feld) {
        List<String> aus = new ArrayList<>();
        for (Object x : Json.arr(Json.feld(o, feld))) {
            String s = Json.str(x);
            if (s != null) aus.add(s);
        }
        return aus;
    }

    // ------------------------------------------------------------ Aufbauen

    /** Liest die Ablage, laeuft den Baum, schreibt zurueck. */
    public Scanner auffrischen() throws IOException {
        laden();
        Scanner s = new Scanner(ws);
        dateien = s.aktualisiere(dateien);
        sichern();
        return s;
    }

    /** Wer zeigt auf diese Datei? Wird bei Bedarf berechnet, nicht gespeichert. */
    public Map<String, List<String>> rueckverweise() {
        Map<String, List<String>> aus = new TreeMap<>();
        for (Quelldatei q : dateien.values())
            for (String ziel : q.verweise())
                aus.computeIfAbsent(ziel, k -> new ArrayList<>()).add(q.pfad());
        return aus;
    }

    // --------------------------------------------------------------- Suchen

    /**
     * Waehlt aus und ordnet.
     *
     * <p>Sortiert nach Eingangsgrad: die Datei, auf die am oeftesten verwiesen
     * wird, steht oben. Das ist der arme Verwandte von PageRank, aber es
     * beantwortet dieselbe Frage — was ist hier wichtig — in fuenf Zeilen und
     * ohne dass jemand nachschlagen muss, wie das Ergebnis zustande kam.
     */
    public List<Quelldatei> suche(String stichwort, List<java.nio.file.PathMatcher> muster) {
        Map<String, List<String>> rueck = rueckverweise();
        List<Quelldatei> treffer = new ArrayList<>();

        for (Quelldatei q : dateien.values()) {
            if (muster != null && !de.dg1001.harness.tools.GlobTool.passt(muster, Path.of(q.pfad())))
                continue;
            if (stichwort != null && !passtStichwort(q, stichwort)) continue;
            treffer.add(q);
        }
        treffer.sort(Comparator
                .comparingInt((Quelldatei q) -> -rueck.getOrDefault(q.pfad(), List.of()).size())
                .thenComparing(Quelldatei::pfad));
        return treffer;
    }

    private static boolean passtStichwort(Quelldatei q, String stichwort) {
        String s = stichwort.toLowerCase();
        if (q.pfad().toLowerCase().contains(s)) return true;
        for (String d : q.definitionen()) if (d.toLowerCase().contains(s)) return true;
        for (String k : q.stichworte()) if (k.toLowerCase().contains(s)) return true;
        return q.beschreibungGueltig() && q.beschreibung().toLowerCase().contains(s);
    }

    // -------------------------------------------------------------- Ausgabe

    /** Uebersicht: eine Datei je Block, gedeckelt. */
    public String uebersicht(List<Quelldatei> auswahl, String wonach) {
        if (dateien.isEmpty())
            return "Die Karte ist leer — im Projekt wurde keine bekannte Quelldatei gefunden.";
        if (auswahl.isEmpty())
            return "Keine Datei passt zu " + wonach + ". Insgesamt " + dateien.size()
                 + " Dateien in der Karte.";

        Map<String, List<String>> rueck = rueckverweise();
        Map<String, String> namen = kurznamen();
        StringBuilder b = new StringBuilder(kopf(auswahl));

        int gezeigt = 0;
        for (Quelldatei q : auswahl) {
            if (gezeigt >= MAX_ZEILEN || b.length() > MAX_ZEICHEN) break;
            block(b, q, rueck.getOrDefault(q.pfad(), List.of()), false, namen);
            gezeigt++;
        }
        if (gezeigt < auswahl.size())
            b.append("[").append(auswahl.size() - gezeigt).append(" weitere — 'stichwort' "
                    + "oder 'muster' enger fassen, oder 'datei' fuer eine einzelne]\n");
        return b.toString();
    }

    /** Einzelansicht mit vollstaendigen Verweisen in beide Richtungen. */
    public String einzeln(Quelldatei q) {
        StringBuilder b = new StringBuilder();
        block(b, q, rueckverweise().getOrDefault(q.pfad(), List.of()), true, kurznamen());
        List<String> fremd = new ArrayList<>(q.rohImporte());
        fremd.removeIf(r -> q.verweise().stream().anyMatch(v -> v.contains(kern(r))));
        if (!fremd.isEmpty())
            b.append("  ausserhalb des Projekts: ").append(kurzListe(fremd, 12)).append('\n');
        return b.toString();
    }

    private static String kern(String modul) {
        String s = modul.replace('.', '/');
        int i = s.lastIndexOf('/');
        return i < 0 ? s : s.substring(i + 1);
    }

    private String kopf(List<Quelldatei> auswahl) {
        Map<String, Integer> jeSprache = new TreeMap<>();
        for (Quelldatei q : dateien.values())
            jeSprache.merge(q.sprache(), 1, Integer::sum);
        List<String> teile = new ArrayList<>();
        jeSprache.forEach((k, v) -> teile.add(v + " " + k));

        StringBuilder b = new StringBuilder();
        b.append(dateien.size()).append(" Dateien in der Karte · ")
         .append(String.join(", ", teile));
        if (auswahl.size() != dateien.size()) b.append(" · ").append(auswahl.size()).append(" passen");
        return b.append("\n\n").toString();
    }

    /**
     * Kurznamen fuer die Verweislisten.
     *
     * <p>Voller Pfad steht in der Kopfzeile jedes Blocks — dort braucht ihn das
     * Modell zum Lesen. In den Pfeillisten ist er Ballast: gemessen an diesem
     * Projekt passten mit vollen Pfaden 17 von 46 Dateien in die Ausgabe, mit
     * Kurznamen alle. Zweideutige Namen bekommen das Elternverzeichnis dazu.
     */
    private Map<String, String> kurznamen() {
        Map<String, Integer> haeufig = new TreeMap<>();
        for (String pfad : dateien.keySet())
            haeufig.merge(letztes(pfad, 1), 1, Integer::sum);

        Map<String, String> aus = new LinkedHashMap<>();
        for (String pfad : dateien.keySet())
            aus.put(pfad, haeufig.get(letztes(pfad, 1)) > 1 ? letztes(pfad, 2) : letztes(pfad, 1));
        return aus;
    }

    private static String letztes(String pfad, int wieviele) {
        String[] t = pfad.split("/");
        int ab = Math.max(0, t.length - wieviele);
        return String.join("/", java.util.Arrays.copyOfRange(t, ab, t.length));
    }

    private List<String> kurz(List<String> pfade, Map<String, String> namen) {
        List<String> aus = new ArrayList<>(pfade.size());
        for (String p : pfade) aus.add(namen.getOrDefault(p, p));
        return aus;
    }

    private void block(StringBuilder b, Quelldatei q, List<String> rueck, boolean alles,
                       Map<String, String> namen) {
        b.append(q.pfad()).append("  ").append(q.zeilen()).append(" Zeilen\n");

        if (q.beschreibungGueltig()) {
            b.append("  ").append(q.beschreibung()).append('\n');
            if (!q.stichworte().isEmpty())
                b.append("  [").append(String.join(", ", q.stichworte())).append("]\n");
        } else if (q.beschreibungVeraltet()) {
            // Bewusst nicht anzeigen: eine Beschreibung, die zum heutigen Inhalt
            // nicht mehr passt, fuehrt aktiver in die Irre als gar keine.
            b.append("  [Beschreibung veraltet — 'jaja --index' erneuert sie]\n");
        }

        if (!q.definitionen().isEmpty())
            b.append("  ").append(kurzListe(q.definitionen(), alles ? 100 : 6)).append('\n');
        if (!q.verweise().isEmpty())
            b.append("  → ").append(kurzListe(kurz(q.verweise(), namen), alles ? 100 : 8)).append('\n');
        if (!rueck.isEmpty())
            b.append("  ← ").append(kurzListe(kurz(rueck, namen), alles ? 100 : 8)).append('\n');
        b.append('\n');
    }

    private static String kurzListe(List<String> werte, int hoechstens) {
        if (werte.size() <= hoechstens) return String.join("  ", werte);
        return String.join("  ", werte.subList(0, hoechstens))
             + "  … +" + (werte.size() - hoechstens);
    }
}
