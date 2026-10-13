package de.dg1001.harness.bench;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Die Statistik, die dieses Repo von Hand gerechnet hat, als Code.
 *
 * <p>Permutationstests statt t-Test: keine Annahme ueber die Verteilung.
 * Zugzahlen und Token sind schief -- ein Lauf mit 18 Zuegen kostet zwoelfmal
 * so viel wie einer mit 4 --, und genau dort taeuscht ein t-Test.
 *
 * <p><b>Gepaart, wo es geht.</b> Laufen in einer Runde dieselbe Aufgabe unter
 * zwei Konfigurationen, ist die Differenz je Paar das Mass, nicht die
 * Differenz der Mittel. Das nimmt die Streuung zwischen den Aufgaben heraus,
 * die bei gemischten Aufgaben ein Vielfaches des gesuchten Effekts betraegt.
 *
 * <p>Fester Startwert: derselbe Bericht zweimal erzeugt liefert dieselben
 * p-Werte. Alles andere waere bei einer Auswertung, die man zitiert, ein
 * Aergernis.
 */
public final class Statistik {

    private Statistik() {}

    static final int ZIEHUNGEN = 20_000;

    public static double mittel(double[] x) {
        if (x.length == 0) return Double.NaN;
        double s = 0;
        for (double v : x) s += v;
        return s / x.length;
    }

    public static double median(double[] x) {
        if (x.length == 0) return Double.NaN;
        double[] s = x.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    /** Stichprobenstandardabweichung. */
    public static double sd(double[] x) {
        if (x.length < 2) return Double.NaN;
        double m = mittel(x), q = 0;
        for (double v : x) q += (v - m) * (v - m);
        return Math.sqrt(q / (x.length - 1));
    }

    /**
     * Zweiseitiger p-Wert fuer "die mittlere Differenz ist null", durch
     * Vorzeichenwechsel. Bis 16 Paare exakt (2^16 Faelle), darueber gezogen.
     */
    public static double pGepaart(double[] d) {
        int n = d.length;
        if (n == 0) return Double.NaN;
        double beob = Math.abs(summe(d));
        long extrem = 0, gesamt;
        double eps = 1e-9 * (1 + beob);
        if (n <= 16) {
            gesamt = 1L << n;
            for (long maske = 0; maske < gesamt; maske++) {
                double s = 0;
                for (int i = 0; i < n; i++) s += ((maske >> i) & 1) == 1 ? -d[i] : d[i];
                if (Math.abs(s) >= beob - eps) extrem++;
            }
        } else {
            SplittableRandom r = new SplittableRandom(20_250_101L);
            gesamt = ZIEHUNGEN;
            for (int k = 0; k < ZIEHUNGEN; k++) {
                double s = 0;
                for (double v : d) s += r.nextBoolean() ? -v : v;
                if (Math.abs(s) >= beob - eps) extrem++;
            }
        }
        return (double) extrem / gesamt;
    }

    /** Zweiseitiger p-Wert fuer "gleiche Mittel", durch Umverteilen der Etiketten. */
    public static double pUnabhaengig(double[] a, double[] b) {
        if (a.length == 0 || b.length == 0) return Double.NaN;
        double[] alle = new double[a.length + b.length];
        System.arraycopy(a, 0, alle, 0, a.length);
        System.arraycopy(b, 0, alle, a.length, b.length);
        double beob = Math.abs(mittel(a) - mittel(b));
        double eps = 1e-9 * (1 + beob);
        SplittableRandom r = new SplittableRandom(20_250_101L);
        long extrem = 0;
        for (int k = 0; k < ZIEHUNGEN; k++) {
            for (int i = alle.length - 1; i > 0; i--) {             // Fisher-Yates
                int j = r.nextInt(i + 1);
                double t = alle[i]; alle[i] = alle[j]; alle[j] = t;
            }
            double sa = 0;
            for (int i = 0; i < a.length; i++) sa += alle[i];
            double sb = summe(alle) - sa;
            if (Math.abs(sa / a.length - sb / b.length) >= beob - eps) extrem++;
        }
        return (double) extrem / ZIEHUNGEN;
    }

    private static double summe(double[] x) {
        double s = 0;
        for (double v : x) s += v;
        return s;
    }
}
