package de.dg1001.harness.agent;

/**
 * Die Rechnung, die man falsch machen kann.
 *
 * <p>Ein gemessener Fall: ein etablierter Harness bekommt die Fenstergroesse
 * genannt, laesst die Eingabe bis genau dorthin wachsen und legt dann
 * {@code max_tokens} obendrauf. Ergebnis war HTTP 500 mit
 * {@code "you requested 16384 output tokens and your prompt contains at least
 * 49153 input tokens, for a total of at least 65537"} — um genau ein Token zu
 * viel, und zwar mitten in der Arbeit statt beim Start.
 *
 * <p>Nutzbar ist deshalb {@code fenster - maxAusgabe - reserve}, nicht das
 * Fenster.
 *
 * @param fenster     Kontextfenster des Modells
 * @param maxAusgabe  was pro Zug hoechstens erzeugt werden darf
 * @param reserve     Puffer fuer Schaetzfehler des Tokenzaehlers
 * @param schwelle    ab welchem Anteil des nutzbaren Platzes gekuerzt wird
 */
public record ContextBudget(int fenster, int maxAusgabe, int reserve, double schwelle) {

    public ContextBudget {
        if (fenster <= 0)       throw new IllegalArgumentException("fenster <= 0");
        if (maxAusgabe <= 0)    throw new IllegalArgumentException("maxAusgabe <= 0");
        if (maxAusgabe >= fenster)
            throw new IllegalArgumentException(
                    "maxAusgabe (" + maxAusgabe + ") >= fenster (" + fenster
                    + ") — es bliebe kein Platz fuer die Eingabe");
        if (reserve < 0)        throw new IllegalArgumentException("reserve < 0");
        if (schwelle <= 0 || schwelle >= 1)
            throw new IllegalArgumentException("schwelle muss zwischen 0 und 1 liegen");
    }

    /** Vorgabe: 2000 Token Reserve, kuerzen ab 70 % des nutzbaren Platzes.
     *
     *  <p>Die 0,7 sind bewusst frueh. Lieber einmal zu viel gekuerzt als in die
     *  Lage zu kommen, in der jede Anfrage scheitert — dann hilft naemlich auch
     *  Kuerzen nicht mehr, weil der Zug zum Kuerzen selbst schon zu gross ist. */
    public static ContextBudget vorgabe(int fenster, int maxAusgabe) {
        return new ContextBudget(fenster, maxAusgabe, 2_000, 0.70);
    }

    public int nutzbareEingabe() {
        return fenster - maxAusgabe - reserve;
    }

    public int kuerzungsSchwelle() {
        return (int) (nutzbareEingabe() * schwelle);
    }

    public boolean mussKuerzen(int geschaetzteEingabe) {
        return geschaetzteEingabe > kuerzungsSchwelle();
    }

    /** Fuer Protokoll und Fehlersuche. */
    public String bericht(int geschaetzteEingabe) {
        return String.format("%d/%d Token (%d%% des nutzbaren Platzes, Kuerzung ab %d)",
                geschaetzteEingabe, nutzbareEingabe(),
                nutzbareEingabe() == 0 ? 0 : geschaetzteEingabe * 100 / nutzbareEingabe(),
                kuerzungsSchwelle());
    }
}
