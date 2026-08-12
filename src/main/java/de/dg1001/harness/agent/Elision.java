package de.dg1001.harness.agent;

/**
 * Entscheidet, wann und wie stark gekuerzt wird.
 *
 * <p>Die Trennung zu {@link Transcript} ist bewusst: dort steht der
 * Mechanismus (was ersetzt wird), hier die Politik (wie oft, wie hart, wann
 * aufgeben).
 *
 * <p><b>Warum Eskalation noetig ist.</b> Ein einzelner Kuerzungsdurchgang reicht
 * nicht immer. Der gemessene Ausfall bei einem etablierten Harness sah so aus:
 * kuerzen, Kontext ist binnen drei Zuegen wieder voll, kuerzen, wieder voll —
 * dreimal, dann Abbruch mit <i>"Autocompact is thrashing"</i>. Vier von vier
 * Aufgaben endeten so, auch die, die auf leerem Verzeichnis startete.
 *
 * <p>Die Gegenmassnahme ist nicht, haerter zu kuerzen, sondern zu erkennen,
 * <i>dass</i> Kuerzen nichts mehr bringt: wenn ein Durchgang den Verbrauch nicht
 * nennenswert senkt, ist die Ursache nicht der Verlauf, sondern etwas
 * Unkuerzbares — ein zu grosser Systemprompt, ein riesiges Werkzeugergebnis im
 * geschuetzten Bereich, oder schlicht ein zu kleines Fenster. Dann ist Aufgeben
 * mit klarer Meldung besser als eine Endlosschleife.
 */
public final class Elision {

    /** Wie viele der juengsten Werkzeugergebnisse ungekuerzt bleiben. */
    private static final int[] STUFEN = {6, 3, 1, 0};

    /** Unter diesem Anteil gilt ein Durchgang als wirkungslos. */
    private static final double MINDESTWIRKUNG = 0.05;

    private final ContextBudget budget;
    private int durchgaenge = 0;

    public Elision(ContextBudget budget) {
        this.budget = budget;
    }

    public enum Ergebnis {
        /** Nichts zu tun, der Verlauf passt. */
        NICHT_NOETIG,
        /** Gekuerzt, es passt jetzt. */
        GEKUERZT,
        /** Auch nach der haertesten Stufe zu gross. */
        AUSSICHTSLOS
    }

    public record Bericht(Ergebnis ergebnis, int vorher, int nachher, int stufen, String hinweis) {}

    /**
     * Kuerzt, bis der Verlauf unter die Schwelle passt oder klar ist, dass es
     * nichts bringt.
     */
    public Bericht vielleichtKuerzen(Transcript t) {
        int vorher = t.schaetzeTokens();
        if (!budget.mussKuerzen(vorher))
            return new Bericht(Ergebnis.NICHT_NOETIG, vorher, vorher, 0, null);

        int[] stand = {vorher};
        int stufen = 0;

        // Erste Stufe: Werkzeugergebnisse.
        for (int s : STUFEN) {
            stufen++;
            if (schritt(t, stand, () -> t.kuerze(s))) break;
        }

        // Zweite Stufe: die Argumente alter Werkzeugaufrufe. Ohne sie steckt
        // jede laengere Sitzung fest, sobald ein paar Dateien geschrieben
        // wurden -- der Dateiinhalt steht im Aufruf, nicht im Ergebnis.
        if (budget.mussKuerzen(stand[0]))
            for (int s : STUFEN) {
                stufen++;
                if (schritt(t, stand, () -> t.kuerzeArgumente(s))) break;
            }

        durchgaenge++;

        if (!budget.mussKuerzen(stand[0]))
            return new Bericht(Ergebnis.GEKUERZT, vorher, stand[0], stufen, null);

        // Unter der Schwelle sind wir nicht mehr angekommen. Das ist aber nur
        // dann aussichtslos, wenn es auch nicht mehr *passt*. Die Schwelle ist
        // ein Komfortwert; an ihr aufzugeben verschenkte in einem gemessenen
        // Lauf 14.000 freie Token und beendete eine laufende Sitzung.
        if (budget.passt(stand[0]))
            return new Bericht(stand[0] < vorher ? Ergebnis.GEKUERZT : Ergebnis.NICHT_NOETIG,
                    vorher, stand[0], stufen,
                    gewarnt ? null : warnung(stand[0]));

        return new Bericht(Ergebnis.AUSSICHTSLOS, vorher, stand[0], stufen,
                "Kuerzen wirkt nicht mehr — die Last liegt ausserhalb des Verlaufs. "
                + "Pruefe Systemprompt und Werkzeugbeschreibungen (Grundlast), "
                + "oder erhoehe das Kontextfenster. " + budget.bericht(stand[0]));
    }

    /**
     * Ein Kuerzungsdurchgang.
     *
     * @return true, wenn nicht weitergemacht werden muss — entweder weil es
     *         jetzt passt oder weil weiteres Kuerzen erkennbar nichts bringt
     */
    private boolean schritt(Transcript t, int[] stand, java.util.function.IntSupplier kuerzen) {
        int ersetzt = kuerzen.getAsInt();
        int neu = t.schaetzeTokens();
        double wirkung = stand[0] == 0 ? 0 : (double) (stand[0] - neu) / stand[0];
        stand[0] = neu;
        if (!budget.mussKuerzen(neu)) return true;
        return ersetzt == 0 && wirkung < MINDESTWIRKUNG;
    }

    private boolean gewarnt = false;

    private String warnung(int stand) {
        gewarnt = true;
        return "nicht weiter kuerzbar, passt aber noch — " + budget.bericht(stand)
             + ". Ab hier waechst der Verlauf ungebremst; /neu oder ein groesseres "
             + "Kontextfenster verschafft Luft.";
    }

    public int durchgaenge() { return durchgaenge; }
}
