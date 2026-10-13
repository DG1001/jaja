package de.dg1001.harness.wire;

/**
 * Hoert einer Antwort beim Entstehen zu -- und darf sie abbrechen.
 *
 * <p>Der Grund, warum es Streaming hier ueberhaupt gibt. Der Fehler, mit dem
 * dieses Projekt angefangen hat, war ein Zug, der 16.384 Ausgabetokens in einen
 * einzigen Denkblock steckte: siebzehn Minuten, und das Ergebnis stand erst
 * fest, als alles vorbei war. Ohne Streaming kann man so einen Zug nur
 * abwarten. Mit Streaming sieht man ihn wachsen und kann ihn kappen.
 *
 * <p>Gezaehlt wird in Zeichen, nicht Token: die Zahl muss nur monoton und
 * grob richtig sein, und Token kennt erst der Server.
 */
@FunctionalInterface
public interface Strom {

    /**
     * Nach jedem Stueck der Antwort.
     *
     * @param denken  Zeichen Denkblock bisher
     * @param text    Zeichen sichtbarer Text bisher
     * @param aufrufe Zeichen in Werkzeugaufrufen bisher (Name und Argumente)
     * @return true, um die Antwort an dieser Stelle abzubrechen
     */
    boolean fortschritt(int denken, int text, int aufrufe);

    Strom STILL = (d, t, a) -> false;
}
