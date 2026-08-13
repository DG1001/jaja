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
    private boolean unvollstaendig = false;
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
        liste(w, "importierteNamen", q.importierteNamen());
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
                texte(o, "importierteNamen"),
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
        unvollstaendig = s.deckelErreicht();
        sichern();
        return s;
    }

    /** Ersetzt einen Eintrag. Fuer den Indexer, der Beschreibungen nachtraegt. */
    public void setze(Quelldatei q) {
        if (dateien.containsKey(q.pfad())) dateien.put(q.pfad(), q);
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

    // --------------------------------------------------------- Doppeltes

    /**
     * Namen, die an mehr als einer Stelle definiert werden.
     *
     * <p>Die haeufigste Art, wie ein langer Agentenlauf auf gruener Wiese
     * schiefgeht — und wir haben den Beleg im eigenen Pruefstand: ein Modell
     * legte {@code STANDARD = Register()} wie verlangt an und dann ein zweites
     * in {@code __init__.py}, das den Import ueberschattete. Alles Sichtbare
     * funktionierte, die Kommandozeile, die eigenen Tests — nur der Pfad, den
     * die Aufgabe ausdruecklich nannte, blieb fuer immer leer. Drei Punkte
     * verloren an etwas, das eine Liste doppelter Namen sofort gezeigt haette.
     *
     * <p>Ab sechs Fundstellen gilt ein Name als Konvention und nicht als
     * Versehen: {@code save} in vierzig Modellen ist kein Fehler.
     */
    /**
     * Ein Name an zwei Stellen, von denen eine die andere importiert.
     *
     * <p>Das ist der gefaehrliche Fall und der einzige, der sich von blosser
     * Namensgleichheit unterscheiden laesst. Django hat 1 288 doppelte Namen —
     * fast alle Zufall. Ueberschattet wird nur dort, wo eine Datei die andere
     * einbindet und den Namen erneut belegt; dann gewinnt die spaetere Zuweisung
     * und der Import laeuft ins Leere, ohne dass irgendetwas bricht.
     */
    public Map<String, List<String>> ueberschattet() {
        Map<String, List<String>> aus = new TreeMap<>();
        doppelte().forEach((name, wo) -> {
            for (String a : wo)
                for (String b : wo) {
                    if (a.equals(b)) continue;
                    Quelldatei qb = dateien.get(b);
                    // Nicht: b bindet irgendetwas aus a ein. Sondern: b holt
                    // genau diesen Namen herein und belegt ihn danach neu.
                    // Der Unterschied ist gross -- bei Django meldete die
                    // grobe Fassung 437 Faelle, fast alle nur Namensgleichheit
                    // zwischen Dateien, die sich zufaellig kennen.
                    if (qb != null && qb.verweise().contains(a)
                            && qb.importierteNamen().contains(name)) {
                        aus.put(name, wo);
                        return;
                    }
                }
        });
        return aus;
    }

    public Map<String, List<String>> doppelte() {
        Map<String, List<String>> nachName = new TreeMap<>();
        for (Quelldatei q : dateien.values())
            for (String d : q.definitionen()) {
                String n = nameVon(d);
                if (n == null) continue;
                List<String> wo = nachName.computeIfAbsent(n, k -> new ArrayList<>());
                if (!wo.contains(q.pfad())) wo.add(q.pfad());
            }
        Map<String, List<String>> aus = new TreeMap<>();
        nachName.forEach((n, wo) -> { if (wo.size() >= 2 && wo.size() <= 5) aus.put(n, wo); });
        return aus;
    }

    /** Namen, die ueberall vorkommen und nichts bedeuten. */
    private static final java.util.Set<String> ALLTAEGLICH = java.util.Set.of(
            "main", "init", "setup", "teardown", "close", "open", "read", "write",
            "run", "start", "stop", "get", "set", "add", "remove", "clear", "size",
            "name", "value", "toString", "equals", "hashCode", "clone", "handle",
            "process", "execute", "call", "apply", "build", "create", "parse",
            "format", "load", "save", "update", "delete", "check", "validate",
            "reset", "next", "count", "keys", "values", "items", "copy", "deconstruct");

    /** Aus "def staffel(menge)" wird staffel; null, wenn uninteressant. */
    static String nameVon(String signatur) {
        // Methoden koennen nichts ueberschatten: sie leben im Klassenraum.
        // Die Einrueckung sieht die Regex nicht, das erste Argument schon --
        // damit faellt Djangos Client.login heraus, das sonst gegen das
        // importierte login gemeldet wurde.
        if (signatur.contains("(self") || signatur.contains("(cls")) return null;

        String s = signatur;
        for (String art : new String[]{"async def ", "def ", "class ", "record ", "interface ",
                                       "enum ", "function ", "async function ", "const ",
                                       "let ", "func ", "type "})
            if (s.startsWith(art)) { s = s.substring(art.length()); break; }
        int klammer = s.indexOf('(');
        if (klammer >= 0) s = s.substring(0, klammer);
        s = s.strip();
        if (s.length() < 4 || s.startsWith("_") || s.startsWith("test")) return null;
        return ALLTAEGLICH.contains(s) || ALLTAEGLICH.contains(s.toLowerCase()) ? null : s;
    }

    /** Als Text: erst die gefaehrlichen, dann der Rest. */
    public String doppelteAlsText() {
        Map<String, List<String>> gefaehrlich = ueberschattet();
        Map<String, List<String>> alle = doppelte();
        if (alle.isEmpty()) return "Kein Name wird an zwei Stellen definiert.";

        StringBuilder b = new StringBuilder();
        if (!gefaehrlich.isEmpty()) {
            b.append(gefaehrlich.size()).append(" Name(n) werden ueberschattet — eine Datei "
                    + "definiert neu, was sie selbst importiert:\n\n");
            for (var e : gefaehrlich.entrySet())
                b.append(String.format("  %-24s %s%n", e.getKey(),
                        kurzListe(kurz(e.getValue(), kurznamen()), 4)));
            b.append('\n');
        }

        Map<String, List<String>> rest = new TreeMap<>(alle);
        rest.keySet().removeAll(gefaehrlich.keySet());
        if (!rest.isEmpty()) {
            b.append(rest.size()).append(" weitere Name(n) an mehreren Stellen — meist Zufall:\n\n");
            int n = 0;
            for (var e : rest.entrySet()) {
                if (n++ >= 30 || b.length() > MAX_ZEICHEN) break;
                b.append(String.format("  %-24s %s%n", e.getKey(),
                        kurzListe(kurz(e.getValue(), kurznamen()), 4)));
            }
            if (n < rest.size()) b.append("  [").append(rest.size() - n).append(" weitere]\n");
        }
        return b.toString();
    }

    // -------------------------------------------------------------- Ausgabe

    /** Ab so vielen Treffern lohnt die Datei-Liste nicht mehr. */
    static final int AB_HIER_VERZEICHNISSE = 25;

    /**
     * Bei vielen Treffern: nach Verzeichnis zusammenfassen statt abschneiden.
     *
     * <p>Der gemessene Schwachpunkt. In Django trifft 'migration' 374 von 3 050
     * Dateien; gezeigt wurden zwanzig davon, sortiert nach globalem
     * Eingangsgrad — also die zentralsten Dateien des Projekts, die zufaellig
     * das Wort enthalten, nicht die zentralsten Migrationsdateien. Genau dort
     * verlor die Karte ihre Laeufe.
     *
     * <p>Zwoelf Verzeichnisse mit Anzahl und Hauptdateien sind dieselbe Menge
     * Text, aber die richtige Form: das Modell grenzt in einem weiteren Schritt
     * ein, statt zu raten.
     */
    private String nachVerzeichnis(List<Quelldatei> auswahl, String wonach) {
        Map<String, List<Quelldatei>> je = new TreeMap<>();
        for (Quelldatei q : auswahl) je.computeIfAbsent(verzeichnis(q.pfad()),
                                                        k -> new ArrayList<>()).add(q);

        // Nach Gewicht, nicht nach Menge. Die reine Anzahl belohnt genau das
        // Falsche: bei 'migration' in Django belegten Testfixtures acht der
        // zwoelf Plaetze, waehrend django/db/migrations/ dazwischen unterging --
        // hundert Dateien, auf die niemand verweist, schlugen vierzehn, die den
        // Kern ausmachen. Gezaehlt wird deshalb, wie oft auf die Dateien eines
        // Verzeichnisses verwiesen wird.
        Map<String, List<String>> rueck = rueckverweise();
        Map<String, Integer> gewicht = new TreeMap<>();
        je.forEach((verz, drin) -> gewicht.put(verz, drin.stream()
                .mapToInt(q -> rueck.getOrDefault(q.pfad(), List.of()).size()).sum()));

        List<Map.Entry<String, List<Quelldatei>>> sortiert = new ArrayList<>(je.entrySet());
        sortiert.sort(Comparator
                .comparingInt((Map.Entry<String, List<Quelldatei>> e) -> -gewicht.get(e.getKey()))
                .thenComparingInt(e -> -e.getValue().size())
                .thenComparing(Map.Entry::getKey));

        StringBuilder b = new StringBuilder(kopf(auswahl));
        b.append(auswahl.size()).append(" Treffer fuer ").append(wonach)
         .append(" — zu viele fuer eine Liste, deshalb nach Verzeichnis:\n\n");

        int gezeigt = 0;
        for (var e : sortiert) {
            if (gezeigt >= 15 || b.length() > MAX_ZEICHEN) break;
            List<Quelldatei> drin = new ArrayList<>(e.getValue());
            drin.sort(Comparator.comparingInt(
                    q -> -rueck.getOrDefault(q.pfad(), List.of()).size()));
            b.append(String.format("%-40s %4d  ", kappe(e.getKey() + "/", 40), drin.size()));
            b.append(kurzListe(drin.subList(0, Math.min(3, drin.size())).stream()
                            .map(q -> dateiname(q.pfad())).toList(), 3));
            b.append('\n');
            gezeigt++;
        }
        if (gezeigt < sortiert.size())
            b.append("[").append(sortiert.size() - gezeigt).append(" weitere Verzeichnisse]\n");

        b.append("\nWeiter mit muster, z. B. muster=\"").append(sortiert.get(0).getKey())
         .append("/**\"\n");
        return b.toString();
    }

    private static String dateiname(String pfad) {
        int i = pfad.lastIndexOf('/');
        return i < 0 ? pfad : pfad.substring(i + 1);
    }

    private static String verzeichnis(String pfad) {
        int i = pfad.lastIndexOf('/');
        return i < 0 ? "." : pfad.substring(0, i);
    }

    /** Uebersicht: eine Datei je Block, gedeckelt. */
    public String uebersicht(List<Quelldatei> auswahl, String wonach) {
        if (dateien.isEmpty())
            return "Die Karte ist leer — im Projekt wurde keine bekannte Quelldatei gefunden.";
        if (auswahl.isEmpty())
            return "Keine Datei passt zu " + wonach + ". Insgesamt " + dateien.size()
                 + " Dateien in der Karte.";

        if (auswahl.size() > AB_HIER_VERZEICHNISSE) return nachVerzeichnis(auswahl, wonach);

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

        // Ungefragter Hinweis, aber nur wenn er ueberschaubar bleibt. In einem
        // jungen Projekt sind zwei gleiche Namen fast immer ein Versehen; in
        // einem gewachsenen sind sie Konvention und der Hinweis waere Laerm.
        // Ungefragt gemeldet wird nur die Ueberschattung, und die immer: sie ist
        // kein Geschmacksfall, sondern fast sicher ein Versehen.
        Map<String, List<String>> gefaehrlich = ueberschattet();
        if (!gefaehrlich.isEmpty()) {
            b.append('\n');
            int n = 0;
            for (var e : gefaehrlich.entrySet()) {
                if (n++ >= 3) { b.append("[").append(gefaehrlich.size() - 3)
                        .append(" weitere — karte mit doppelte=true]\n"); break; }
                b.append("Achtung: ").append(e.getKey()).append(" wird in ")
                 .append(String.join(" und ", kurz(e.getValue(), kurznamen())))
                 .append(" definiert — eine davon importiert die andere.\n");
            }
        }
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
        if (unvollstaendig)
            b.append("\n[unvollstaendig: beim Deckel von ").append(Scanner.MAX_DATEIEN)
             .append(" Dateien abgebrochen, es gibt mehr]");
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

        // Wo eine Beschreibung den Zweck schon nennt, ist die volle
        // Definitionsliste Ballast: mit beidem passten gemessen 15 statt 27
        // Dateien in dasselbe Budget. In der Uebersicht gewinnt der Zweck,
        // Einzelheiten holt man sich ueber 'datei'.
        boolean beschrieben = q.beschreibungGueltig();
        int wieVieleDefs = alles ? 100 : (beschrieben ? 3 : 6);

        if (beschrieben) {
            b.append("  ").append(kappe(q.beschreibung(), alles ? 300 : 150)).append('\n');
            if (alles && !q.stichworte().isEmpty())
                b.append("  [").append(String.join(", ", q.stichworte())).append("]\n");
        } else if (q.beschreibungVeraltet()) {
            // Bewusst nicht anzeigen: eine Beschreibung, die zum heutigen Inhalt
            // nicht mehr passt, fuehrt aktiver in die Irre als gar keine.
            b.append("  [Beschreibung veraltet — 'jaja --index' erneuert sie]\n");
        }

        if (!q.definitionen().isEmpty())
            b.append("  ").append(kurzListe(q.definitionen(), wieVieleDefs)).append('\n');
        if (!q.verweise().isEmpty())
            b.append("  → ").append(kurzListe(kurz(q.verweise(), namen), alles ? 100 : 8)).append('\n');
        if (!rueck.isEmpty())
            b.append("  ← ").append(kurzListe(kurz(rueck, namen), alles ? 100 : 8)).append('\n');
        b.append('\n');
    }

    private static String kappe(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static String kurzListe(List<String> werte, int hoechstens) {
        if (werte.size() <= hoechstens) return String.join("  ", werte);
        return String.join("  ", werte.subList(0, hoechstens))
             + "  … +" + (werte.size() - hoechstens);
    }
}
