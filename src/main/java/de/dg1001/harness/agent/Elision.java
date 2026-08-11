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

        int stand = vorher;
        int stufe = 0;

        for (; stufe < STUFEN.length; stufe++) {
            int ersetzt = t.kuerze(STUFEN[stufe]);
            int neu = t.schaetzeTokens();

            if (!budget.mussKuerzen(neu)) {
                durchgaenge++;
                return new Bericht(Ergebnis.GEKUERZT, vorher, neu, stufe + 1, null);
            }

            double wirkung = stand == 0 ? 0 : (double) (stand - neu) / stand;
            stand = neu;

            // Kein Kandidat mehr uebrig oder Wirkung vernachlaessigbar:
            // haerter kuerzen bringt nichts, weiterprobieren waere Zeitverschwendung.
            if (ersetzt == 0 && wirkung < MINDESTWIRKUNG) break;
        }

        durchgaenge++;
        return new Bericht(Ergebnis.AUSSICHTSLOS, vorher, stand, stufe,
                "Kuerzen wirkt nicht mehr — die Last liegt ausserhalb des Verlaufs. "
                + "Pruefe Systemprompt und Werkzeugbeschreibungen (Grundlast), "
                + "oder erhoehe das Kontextfenster. " + budget.bericht(stand));
    }

    public int durchgaenge() { return durchgaenge; }
}
