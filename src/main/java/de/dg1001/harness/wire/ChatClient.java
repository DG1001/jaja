package de.dg1001.harness.wire;

import de.dg1001.harness.wire.Messages.ChatResponse;
import de.dg1001.harness.wire.Messages.Message;
import de.dg1001.harness.wire.Messages.ToolSpec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Ein Aufruf an einen OpenAI-kompatiblen {@code /chat/completions}-Endpunkt.
 *
 * <p>Bewusst ohne Wiederholungslogik: die gehoert in einen Dekorator darum,
 * damit man sie beim Messen abschalten kann. Bewusst ohne Streaming: im
 * Stapelbetrieb bringt es nichts ausser Komplexitaet.
 *
 * <p>Der Zeitraum ist grosszuegig gesetzt. Auf dieser Maschine hat ein einzelner
 * Zug schon elf Minuten gebraucht (DeepSeek, 16.384 Ausgabetokens bei 18 tok/s)
 * — ein Vorgabewert von 30 Sekunden bricht solche Laeufe grundlos ab.
 */
public final class ChatClient implements ChatEndpunkt {

    private final HttpClient http;
    private final URI endpunkt;
    private final String modell;
    private final String apiKey;
    private final int maxOutput;
    private final Duration zeitraum;

    public ChatClient(String baseUrl, String modell, String apiKey,
                      int maxOutput, Duration zeitraum) {
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpunkt  = URI.create(b + "/chat/completions");
        this.modell    = modell;
        this.apiKey    = apiKey;
        this.maxOutput = maxOutput;
        this.zeitraum  = zeitraum;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)   // lokale Server sprechen selten h2
                .build();
    }

    @Override
    public ChatResponse complete(List<Message> verlauf, List<ToolSpec> werkzeuge) {
        String rumpf = baueAnfrage(verlauf, werkzeuge);

        HttpRequest.Builder b = HttpRequest.newBuilder(endpunkt)
                .timeout(zeitraum)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(rumpf));
        if (apiKey != null && !apiKey.isBlank())
            b.header("Authorization", "Bearer " + apiKey);

        HttpResponse<String> antwort;
        try {
            antwort = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ChatFehler("Verbindung zu " + endpunkt + " fehlgeschlagen", e);
        }

        if (antwort.statusCode() / 100 != 2) {
            String detail;
            try { detail = Messages.fehlertext(Json.parse(antwort.body())); }
            catch (RuntimeException ignored) { detail = kurz(antwort.body()); }
            throw new ChatFehler("HTTP " + antwort.statusCode() + ": " + detail, null);
        }

        try {
            return Messages.lies(Json.parse(antwort.body()));
        } catch (RuntimeException e) {
            throw new ChatFehler("Antwort nicht lesbar: " + kurz(antwort.body()), e);
        }
    }

    // ------------------------------------------------------------- Anfrage

    String baueAnfrage(List<Message> verlauf, List<ToolSpec> werkzeuge) {
        Json.Writer w = new Json.Writer();
        w.objektAuf();
        w.feld("model").text(modell);
        w.feld("max_tokens").zahl(maxOutput);
        w.feld("stream").wahr(false);

        w.feld("messages").listeAuf();
        for (Message m : verlauf) Messages.schreibe(w, m);
        w.listeZu();

        // Reihenfolge der Werkzeuge NICHT sortieren o. ae. -- die Liste kommt
        // bereits stabil aus der Registry. Jede Umsortierung zwischen zwei
        // Zuegen wirft den Prefix-Cache weg.
        if (werkzeuge != null && !werkzeuge.isEmpty()) {
            w.feld("tools").listeAuf();
            for (ToolSpec t : werkzeuge) {
                w.objektAuf();
                w.feld("type").text("function");
                w.feld("function").objektAuf();
                w.feld("name").text(t.name());
                w.feld("description").text(t.description());
                w.feld("parameters").roh(t.parametersJson());
                w.objektZu();
                w.objektZu();
            }
            w.listeZu();
            w.feld("tool_choice").text("auto");
        }

        w.objektZu();
        return w.toString();
    }

    private static String kurz(String s) {
        if (s == null) return "(leer)";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    public static class ChatFehler extends RuntimeException {
        public ChatFehler(String m, Throwable u) { super(m, u); }
    }
}
