package de.dg1001.harness.agent;

/**
 * Tokenzaehlung ohne Tokenizer.
 *
 * <p>Ein echter Tokenizer waere fuer jedes Modell ein anderer, muesste
 * mitgeliefert und gepflegt werden — und braucht man nicht. Was zaehlt, ist
 * nicht Genauigkeit, sondern rechtzeitig zu kuerzen. Deshalb: grobe Schaetzung
 * aus der Zeichenzahl, nach jeder Antwort an {@code usage.prompt_tokens}
 * nachgezogen.
 *
 * <p>Nach zwei, drei Zuegen sitzt der Faktor. Der Startwert 3,5 passt fuer
 * Quelltext und deutschen Prosatext gleichermassen brauchbar; reiner ASCII-Code
 * liegt eher bei 4, Text mit Umlauten darunter.
 */
public final class TokenSchaetzer {

    private static final double START = 3.5;
    private static final double TRAEGHEIT = 0.8;   // Anteil des alten Werts

    private double zeichenProToken = START;
    private int kalibrierungen = 0;

    public int schaetze(int zeichen) {
        return (int) Math.ceil(zeichen / zeichenProToken);
    }

    public int schaetze(String s) {
        return s == null ? 0 : schaetze(s.length());
    }

    /**
     * Zieht den Faktor an der tatsaechlichen Zaehlung des Servers nach.
     *
     * @param zeichenGesendet Laenge dessen, was tatsaechlich rausging
     * @param tokensLautServer {@code usage.prompt_tokens} aus der Antwort
     */
    public void kalibriere(int zeichenGesendet, int tokensLautServer) {
        if (tokensLautServer <= 0 || zeichenGesendet <= 0) return;
        double gemessen = (double) zeichenGesendet / tokensLautServer;
        // Ausreisser abwehren: ein voellig abweichender Wert deutet eher auf
        // einen Messfehler als auf eine echte Aenderung des Verhaeltnisses.
        if (gemessen < 1.0 || gemessen > 12.0) return;
        zeichenProToken = TRAEGHEIT * zeichenProToken + (1 - TRAEGHEIT) * gemessen;
        kalibrierungen++;
    }

    public double faktor()        { return zeichenProToken; }
    public int    kalibrierungen(){ return kalibrierungen; }

    @Override public String toString() {
        return String.format("%.2f Zeichen/Token nach %d Kalibrierungen",
                zeichenProToken, kalibrierungen);
    }
}
