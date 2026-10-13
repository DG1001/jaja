package de.dg1001.harness.tools;

import java.util.List;

/**
 * Was sich an einer Datei geaendert hat, fuer Menschen.
 *
 * <p>Kein Diff-Algorithmus: gemeinsamer Anfang und gemeinsames Ende werden
 * abgeschnitten, der Rest ist der geaenderte Block. Fuer ein einzelnes
 * {@code edit} ist das genau die Aenderung; bei mehreren weit auseinander
 * liegenden Stellen ein Block von der ersten bis zur letzten, gekappt. Das
 * reicht, um zu sehen, was passiert ist -- und mehr soll es nicht.
 *
 * <p>Geht nie ans Modell. Das kennt seine Aenderung, es hat sie geschrieben.
 */
final class Unterschied {

    private Unterschied() {}

    static final int MAX_ZEILEN = 8;

    /** Zeilen mit "-" und "+" samt Zeilennummer; null, wenn nichts anders ist. */
    static String zeige(String vorher, String nachher) {
        if (vorher.equals(nachher)) return null;
        List<String> a = vorher.lines().toList(), b = nachher.lines().toList();
        int anfang = 0;
        while (anfang < a.size() && anfang < b.size() && a.get(anfang).equals(b.get(anfang))) anfang++;
        int ea = a.size(), eb = b.size();
        while (ea > anfang && eb > anfang && a.get(ea - 1).equals(b.get(eb - 1))) { ea--; eb--; }

        StringBuilder s = new StringBuilder();
        block(s, a, anfang, ea, '-');
        block(s, b, anfang, eb, '+');
        return s.isEmpty() ? null : s.toString().stripTrailing();
    }

    private static void block(StringBuilder s, List<String> z, int von, int bis, char zeichen) {
        int n = bis - von;
        for (int i = von; i < Math.min(bis, von + MAX_ZEILEN); i++) {
            String t = z.get(i);
            if (t.length() > 160) t = t.substring(0, 160) + "…";
            s.append(String.format("%5d %c %s%n", i + 1, zeichen, t));
        }
        if (n > MAX_ZEILEN) s.append(String.format("      %c … %d weitere Zeilen%n", zeichen, n - MAX_ZEILEN));
    }
}
