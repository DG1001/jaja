package de.dg1001.harness.karte;

import java.util.List;

/**
 * Ein Eintrag der Quellenkarte.
 *
 * <p>{@code groesse} und {@code mtime} entscheiden, ob die Datei ueberhaupt neu
 * gelesen werden muss — das ist der billige Vergleich. {@code hash} entscheidet,
 * ob die <em>Beschreibung</em> noch stimmt; er kostet einen Lesevorgang und
 * wird deshalb nur gebildet, wenn ohnehin gelesen wird.
 *
 * <p>Der Unterschied zwischen {@code hash} und {@code beschreibungFuerHash} ist
 * der ganze Umgang mit Veraltetem: stimmen beide ueberein, gilt die
 * Beschreibung; sonst faellt sie weg. Eine falsche Beschreibung ist schlimmer
 * als keine — sie fuehrt das Modell aktiv in die Irre, waehrend eine fehlende
 * es nur zum Lesen zwingt.
 *
 * <p>{@code mtime} steht bewusst als Zeichenkette da: {@code Json.num} liefert
 * nur {@code int}, und ein Zeitstempel in Millisekunden laeuft darin ueber.
 *
 * @param verweise    aufgeloeste Projektpfade, auf die diese Datei zeigt
 * @param rohImporte  die Modulnamen, wie sie im Quelltext stehen (auch die,
 *                    die zu keiner Projektdatei gehoeren — Fremdbibliotheken)
 */
public record Quelldatei(String pfad, int groesse, String mtime, String hash,
                         String sprache, int zeilen,
                         List<String> definitionen, List<String> rohImporte,
                         List<String> verweise,
                         String beschreibung, List<String> stichworte,
                         String beschreibungFuerHash) {

    public Quelldatei {
        definitionen = List.copyOf(definitionen);
        rohImporte   = List.copyOf(rohImporte);
        verweise     = List.copyOf(verweise);
        stichworte   = stichworte == null ? List.of() : List.copyOf(stichworte);
    }

    /** Gilt die gespeicherte Beschreibung noch fuer den heutigen Inhalt? */
    public boolean beschreibungGueltig() {
        return beschreibung != null && !beschreibung.isBlank()
                && hash != null && hash.equals(beschreibungFuerHash);
    }

    /** Es gab einmal eine Beschreibung, sie passt aber nicht mehr. */
    public boolean beschreibungVeraltet() {
        return beschreibung != null && !beschreibung.isBlank() && !beschreibungGueltig();
    }

    /** Neue Verweise setzen, ohne den Rest anzufassen. */
    public Quelldatei mitVerweisen(List<String> neu) {
        return new Quelldatei(pfad, groesse, mtime, hash, sprache, zeilen,
                definitionen, rohImporte, neu, beschreibung, stichworte, beschreibungFuerHash);
    }

    public Quelldatei mitBeschreibung(String text, List<String> worte) {
        return new Quelldatei(pfad, groesse, mtime, hash, sprache, zeilen,
                definitionen, rohImporte, verweise, text, worte, hash);
    }
}
