package de.dg1001.harness.agent;

import de.dg1001.harness.wire.Messages.ToolCall;

/**
 * Entscheidet, ob ein Werkzeugaufruf ausgefuehrt werden darf.
 *
 * <p>Der Grund ist {@code bash}: es fuehrt aus, was das Modell schreibt, mit
 * den Rechten des Aufrufers. Im Stapelbetrieb ist das gewollt — dort laeuft
 * ohnehin ein Wegwerfverzeichnis. Am Bildschirm sollte man gefragt werden.
 *
 * <p>Eine Ablehnung ist <em>kein</em> Fehler des Laufs: das Modell bekommt ein
 * Werkzeugergebnis mit der Begruendung und kann einen anderen Weg waehlen.
 * Genau dafuer traegt {@code ToolResult} ein {@code istFehler}-Kennzeichen.
 */
public interface Freigabe {

    /** @return null, wenn erlaubt; sonst die Begruendung fuer das Modell. */
    String pruefe(ToolCall tc);

    Freigabe ALLES = tc -> null;
}
