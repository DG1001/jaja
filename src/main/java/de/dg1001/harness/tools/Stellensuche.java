package de.dg1001.harness.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Findet die Stelle, die {@code edit} ersetzen soll, wenn der woertliche
 * Vergleich scheitert -- und sagt sonst, wo sie vermutlich liegt.
 *
 * <p>Die haeufigste Ursache eines gescheiterten {@code edit} ist nicht eine
 * falsche Stelle, sondern falscher Leerraum: Einrueckung abgezaehlt statt
 * kopiert, Tabulator statt Leerzeichen, ein Leerzeichen am Zeilenende, CRLF in
 * der Datei. Die Meldung "kommt nicht vor" laesst das Modell dann raten, und
 * eine Folge solcher Fehlschlaege ist genau das, worauf
 * {@code Agent.MAX_FEHLERFOLGE} anspringt.
 *
 * <p><b>Die Eindeutigkeitsregel bleibt.</b> Toleranz heisst hier nur: Leerraum
 * zaehlt beim Suchen nicht. Uebernommen wird ein solcher Treffer ausschliesslich
 * dann, wenn es genau einen gibt. Inhaltliche Abweichungen werden nie
 * ueberbrueckt -- fuer sie gibt es nur die Diagnose, welche Zeile abweicht.
 */
final class Stellensuche {

    private Stellensuche() {}

    /** Wie die Stelle gefunden wurde, fuer die Meldung ans Modell. */
    enum Art { ZEILENENDEN, LEERRAUM }

    /**
     * @param treffer   wie viele Stellen tolerant passen; nur bei 1 ist
     *                  {@code ersetzt} gesetzt
     * @param ersetzt   der neue Dateiinhalt
     * @param zeilen    erste Zeile jedes Treffers, 1-basiert
     */
    record Ergebnis(int treffer, String ersetzt, Art art, boolean eingerueckt,
                    List<Integer> zeilen) {}

    /** Hoechstens so viele Fenster werden fuer die Diagnose verglichen. */
    private static final long MAX_VERGLEICHE = 500_000;

    // ------------------------------------------------------------ tolerant

    /** Sucht tolerant; {@code null}, wenn auch so nichts passt. */
    static Ergebnis tolerant(String inhalt, String alt, String neu) {
        // Erst die Zeilenenden: billig, und der einzige Fall, in dem auch ein
        // Ausschnitt mitten aus einer Zeile tolerant gefunden werden kann.
        if (inhalt.contains("\r\n") && alt.contains("\n") && !alt.contains("\r\n")) {
            String altCrlf = alt.replace("\n", "\r\n");
            List<Integer> orte = vorkommen(inhalt, altCrlf);
            if (orte.size() == 1) {
                int i = orte.get(0);
                String neuCrlf = neu.replace("\r\n", "\n").replace("\n", "\r\n");
                return new Ergebnis(1, inhalt.substring(0, i) + neuCrlf
                        + inhalt.substring(i + altCrlf.length()), Art.ZEILENENDEN, false,
                        List.of(zeileVon(inhalt, i)));
            }
            if (orte.size() > 1)
                return new Ergebnis(orte.size(), null, Art.ZEILENENDEN, false,
                        orte.stream().map(i -> zeileVon(inhalt, i)).toList());
        }
        return zeilenweise(inhalt, alt, neu);
    }

    /**
     * Vergleicht ganze Zeilen mit zusammengefasstem Leerraum.
     *
     * <p>Ersetzt wird der Bereich vom Anfang der ersten bis zum Ende der
     * letzten passenden Zeile. Weicht die Einrueckung ab, wird {@code neu}
     * um denselben Betrag verschoben -- sonst landete ein ohne Einrueckung
     * geschriebener Ersatz am linken Rand, was in Python ein Syntaxfehler ist
     * und anderswo ein stiller Stilbruch.
     */
    private static Ergebnis zeilenweise(String inhalt, String alt, String neu) {
        List<String> altZ = new ArrayList<>(List.of(alt.replace("\r", "").split("\n", -1)));
        List<String> neuZ = new ArrayList<>(List.of(neu.replace("\r", "").split("\n", -1)));
        // Leere Raender gehoeren nicht zur Stelle. Was am Rand von 'alt'
        // wegfaellt, faellt auch am Rand von 'neu' weg, sofern dort ebenso leer.
        while (!altZ.isEmpty() && altZ.get(altZ.size() - 1).isBlank()) {
            altZ.remove(altZ.size() - 1);
            if (!neuZ.isEmpty() && neuZ.get(neuZ.size() - 1).isBlank()) neuZ.remove(neuZ.size() - 1);
        }
        while (!altZ.isEmpty() && altZ.get(0).isBlank()) {
            altZ.remove(0);
            if (!neuZ.isEmpty() && neuZ.get(0).isBlank()) neuZ.remove(0);
        }
        if (altZ.isEmpty()) return null;

        Zeilen datei = Zeilen.von(inhalt);
        int n = altZ.size();
        List<String> altNorm = altZ.stream().map(Stellensuche::norm).toList();

        List<Integer> anfaenge = new ArrayList<>();
        for (int i = 0; i + n <= datei.anzahl(); i++) {
            boolean gleich = true;
            for (int j = 0; j < n && gleich; j++)
                gleich = norm(datei.text(i + j)).equals(altNorm.get(j));
            if (gleich) anfaenge.add(i);
        }
        if (anfaenge.isEmpty()) return null;
        List<Integer> zeilen = anfaenge.stream().map(i -> i + 1).toList();
        if (anfaenge.size() > 1)
            return new Ergebnis(anfaenge.size(), null, Art.LEERRAUM, false, zeilen);

        int i = anfaenge.get(0);
        // Welche Einrueckung in 'alt' welcher in der Datei entspricht, Zeile
        // fuer Zeile. Eine einzige Verschiebung reicht nicht: wer einen
        // Tabulator als vier Leerzeichen abschreibt, tut das in jeder Zeile,
        // nicht nur in der ersten.
        Map<String, String> zuordnung = new HashMap<>();
        for (int j = 0; j < n; j++)
            if (!altZ.get(j).isBlank())
                zuordnung.putIfAbsent(einrueckung(altZ.get(j)), einrueckung(datei.text(i + j)));
        String einrDatei = einrueckung(ersteNichtleere(datei, i, n));
        String einrAlt   = einrueckung(ersteNichtleere(altZ));
        boolean verschoben = false;
        for (int k = 0; k < neuZ.size(); k++) {
            String z = neuZ.get(k);
            if (z.isBlank()) continue;
            String e = einrueckung(z), rest = z.substring(e.length());
            String ziel = zuordnung.containsKey(e) ? zuordnung.get(e)
                        // Neue, tiefere Zeile: um denselben Betrag wie die erste.
                        : e.startsWith(einrAlt) ? einrDatei + e.substring(einrAlt.length())
                        : e;
            if (!ziel.equals(e)) { neuZ.set(k, ziel + rest); verschoben = true; }
        }

        String trenner = inhalt.contains("\r\n") ? "\r\n" : "\n";
        int von = datei.anfang(i), bis = datei.ende(i + n - 1);
        String ersetzt = inhalt.substring(0, von) + String.join(trenner, neuZ)
                       + inhalt.substring(bis);
        return new Ergebnis(1, ersetzt, Art.LEERRAUM, verschoben, zeilen);
    }

    // ----------------------------------------------------------- Diagnose

    /**
     * Beschreibt die aehnlichste Stelle, oder {@code null}, wenn keine
     * nennenswert aehnlich ist.
     *
     * <p>Gezeigt wird die Stelle woertlich mit Zeilennummern, dazu die erste
     * abweichende Zeile beider Seiten. Das ist die Information, die das Modell
     * sonst mit einem zusaetzlichen {@code read} holen muesste -- und mit der es
     * statt zu raten kopieren kann.
     */
    static String aehnlichste(String inhalt, String alt) {
        List<String> altZ = new ArrayList<>(List.of(alt.replace("\r", "").split("\n", -1)));
        while (!altZ.isEmpty() && altZ.get(altZ.size() - 1).isBlank()) altZ.remove(altZ.size() - 1);
        while (!altZ.isEmpty() && altZ.get(0).isBlank()) altZ.remove(0);
        if (altZ.isEmpty()) return null;

        Zeilen datei = Zeilen.von(inhalt);
        int n = Math.min(altZ.size(), datei.anzahl());
        if (n == 0 || (long) datei.anzahl() * n > MAX_VERGLEICHE) return null;

        List<String> altNorm = altZ.stream().map(Stellensuche::norm).toList();
        List<String> dateiNorm = new ArrayList<>(datei.anzahl());
        for (int i = 0; i < datei.anzahl(); i++) dateiNorm.add(norm(datei.text(i)));

        double bester = -1;
        int beste = 0;
        for (int i = 0; i + n <= datei.anzahl(); i++) {
            double s = 0;
            for (int j = 0; j < n; j++) s += aehnlichkeit(dateiNorm.get(i + j), altNorm.get(j));
            s /= altZ.size();                       // fehlende Zeilen zaehlen als 0
            if (s > bester) { bester = s; beste = i; }
        }
        if (bester < 0.5) return null;

        StringBuilder b = new StringBuilder();
        b.append("Am aehnlichsten sind die Zeilen ").append(beste + 1).append('-')
         .append(beste + n).append(" (").append(Math.round(bester * 100))
         .append(" % gleich), woertlich:\n");
        int zeigen = Math.min(n, 30);
        for (int j = 0; j < zeigen; j++)
            b.append(beste + j + 1).append('\t').append(kurz(datei.text(beste + j))).append('\n');
        if (zeigen < n) b.append("[… ").append(n - zeigen).append(" weitere Zeilen]\n");

        for (int j = 0; j < n; j++) {
            if (dateiNorm.get(beste + j).equals(altNorm.get(j))) continue;
            b.append("Erste Abweichung in Zeile ").append(beste + j + 1).append(":\n")
             .append("  Datei: ").append(kurz(datei.text(beste + j).strip())).append('\n')
             .append("  alt:   ").append(kurz(altZ.get(j).strip())).append('\n');
            break;
        }
        return b.toString();
    }

    /** Erste Zeile jedes woertlichen Vorkommens, fuer die Mehrfachmeldung. */
    static List<Integer> zeilenDerVorkommen(String inhalt, String alt) {
        return vorkommen(inhalt, alt).stream().map(i -> zeileVon(inhalt, i)).toList();
    }

    // ------------------------------------------------------------- Hilfen

    /** Zeilen samt Lage im Text, ohne den Text zu kopieren. */
    private record Zeilen(String text, int[] anfaenge, int[] enden) {
        static Zeilen von(String s) {
            List<int[]> l = new ArrayList<>();
            int a = 0;
            while (true) {
                int nl = s.indexOf('\n', a);
                int e = nl < 0 ? s.length() : nl;
                int inhaltEnde = (e > a && s.charAt(e - 1) == '\r') ? e - 1 : e;
                // Die leere "Zeile" hinter einem abschliessenden Umbruch zaehlt nicht.
                if (nl >= 0 || a < s.length()) l.add(new int[]{a, inhaltEnde});
                if (nl < 0) break;
                a = nl + 1;
            }
            int[] an = new int[l.size()], en = new int[l.size()];
            for (int i = 0; i < l.size(); i++) { an[i] = l.get(i)[0]; en[i] = l.get(i)[1]; }
            return new Zeilen(s, an, en);
        }
        int anzahl()       { return anfaenge.length; }
        int anfang(int i)  { return anfaenge[i]; }
        int ende(int i)    { return enden[i]; }
        String text(int i) { return text.substring(anfaenge[i], enden[i]); }
    }

    static String norm(String s) {
        return s.strip().replaceAll("\\s+", " ");
    }

    private static String einrueckung(String z) {
        int i = 0;
        while (i < z.length() && (z.charAt(i) == ' ' || z.charAt(i) == '\t')) i++;
        return z.substring(0, i);
    }

    private static String ersteNichtleere(Zeilen d, int ab, int n) {
        for (int i = ab; i < ab + n; i++) if (!d.text(i).isBlank()) return d.text(i);
        return "";
    }

    private static String ersteNichtleere(List<String> l) {
        for (String z : l) if (!z.isBlank()) return z;
        return "";
    }

    private static List<Integer> vorkommen(String heu, String nadel) {
        List<Integer> l = new ArrayList<>();
        int i = 0;
        while ((i = heu.indexOf(nadel, i)) >= 0) { l.add(i); i += nadel.length(); }
        return l;
    }

    private static int zeileVon(String s, int pos) {
        int z = 1;
        for (int i = 0; i < pos; i++) if (s.charAt(i) == '\n') z++;
        return z;
    }

    /** Dice-Koeffizient ueber Zeichenpaare: schnell, und gut genug zum Zeigen. */
    static double aehnlichkeit(String a, String b) {
        if (a.equals(b)) return 1;
        if (a.length() < 2 || b.length() < 2) return 0;
        Map<Integer, Integer> paare = new HashMap<>();
        for (int i = 0; i + 1 < a.length(); i++)
            paare.merge(a.charAt(i) << 16 | a.charAt(i + 1), 1, Integer::sum);
        int gemeinsam = 0;
        for (int i = 0; i + 1 < b.length(); i++) {
            int k = b.charAt(i) << 16 | b.charAt(i + 1);
            Integer c = paare.get(k);
            if (c != null && c > 0) { gemeinsam++; paare.put(k, c - 1); }
        }
        return 2.0 * gemeinsam / (a.length() - 1 + b.length() - 1);
    }

    private static String kurz(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
