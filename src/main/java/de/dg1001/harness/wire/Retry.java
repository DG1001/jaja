package de.dg1001.harness.wire;

import de.dg1001.harness.wire.Messages.ChatResponse;
import de.dg1001.harness.wire.Messages.Message;
import de.dg1001.harness.wire.Messages.ToolSpec;

import java.util.List;
import java.util.function.Consumer;

/**
 * Wiederholung als Huelle um {@link ChatClient} — bewusst getrennt, damit man
 * sie beim Messen abschalten kann.
 *
 * <p>Der Anlass war handfest: ein Lauf gegen einen lokalen Server brach nach
 * sieben Zuegen ab mit
 * <pre>HTTP 503: Server is temporarily at capacity ...; retry shortly</pre>
 * Der Server bittet woertlich um einen erneuten Versuch, und die Arbeit von
 * sechs Zuegen war trotzdem verloren.
 *
 * <p><b>Was wiederholt wird und was nicht</b> ist die eigentliche Entscheidung.
 * Voruebergehende Zustaende (429, 503, 500, Verbindungsabbruch, Zeitablauf)
 * bessern sich durch Warten. Ein 400 dagegen bedeutet, dass die Anfrage selbst
 * nicht passt — etwa weil Eingabe plus Ausgabegrenze das Kontextfenster
 * sprengen. Den zu wiederholen kostet nur Zeit und verschleiert die Ursache.
 */
public final class Retry implements ChatEndpunkt {

    private final ChatClient innen;
    private final int versuche;
    private final long grundWartezeitMs;
    private final Consumer<String> melder;

    public Retry(ChatClient innen, int versuche, long grundWartezeitMs,
                 Consumer<String> melder) {
        if (versuche < 1) throw new IllegalArgumentException("versuche < 1");
        this.innen = innen;
        this.versuche = versuche;
        this.grundWartezeitMs = grundWartezeitMs;
        this.melder = melder == null ? s -> {} : melder;
    }

    /** Vorgabe: vier Versuche, 2 s Grundwartezeit (2, 4, 8 s). */
    public static Retry vorgabe(ChatClient innen, Consumer<String> melder) {
        return new Retry(innen, 4, 2_000, melder);
    }

    @Override
    public ChatResponse complete(List<Message> verlauf, List<ToolSpec> werkzeuge) {
        ChatClient.ChatFehler letzter = null;

        for (int versuch = 1; versuch <= versuche; versuch++) {
            try {
                return innen.complete(verlauf, werkzeuge);
            } catch (ChatClient.ChatFehler e) {
                letzter = e;
                // Ein unterbrochener Faden will abbrechen, nicht warten.
                // (Der Schlaf unten wuerfe zwar auch, aber sich darauf zu
                // verlassen macht die Absicht unlesbar.)
                if (Thread.currentThread().isInterrupted()) throw e;
                if (!lohntWiederholung(e) || versuch == versuche) throw e;

                long warten = grundWartezeitMs * (1L << (versuch - 1));
                melder.accept(String.format(
                        "Versuch %d/%d fehlgeschlagen (%s) — warte %d ms",
                        versuch, versuche, kurz(e.getMessage()), warten));
                try {
                    Thread.sleep(warten);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw letzter;   // unerreichbar, beruhigt den Uebersetzer
    }

    /**
     * Voruebergehend oder dauerhaft?
     *
     * <p>Bewusst ueber den Meldungstext statt ueber einen Statuscode: der
     * {@link ChatClient} wirft eine einzige Fehlerklasse, und den Code
     * durchzureichen waere mehr Umbau als Nutzen. Die Muster unten decken ab,
     * was lokale Server tatsaechlich schicken.
     */
    static boolean lohntWiederholung(ChatClient.ChatFehler e) {
        String m = e.getMessage();
        if (m == null) return false;

        // Netzwerkseitig: gar keine Antwort bekommen.
        if (m.startsWith("Verbindung zu")) return true;

        // 400/401/403/404/422: die Anfrage passt nicht. Warten aendert nichts.
        if (m.startsWith("HTTP 4") && !m.startsWith("HTTP 429")) return false;

        // 429 und alles ab 500: Ueberlast oder Serverfehler.
        if (m.startsWith("HTTP 429") || m.startsWith("HTTP 5")) return true;

        // Antwort kam an, war aber unlesbar -- kann ein abgeschnittener
        // Stromabbruch sein, ein zweiter Versuch ist billig.
        return m.startsWith("Antwort nicht lesbar");
    }

    private static String kurz(String s) {
        if (s == null) return "?";
        String eine = s.split("\n", 2)[0];
        return eine.length() > 100 ? eine.substring(0, 100) + "…" : eine;
    }
}
