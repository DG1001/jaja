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
 * damit man sie beim Messen abschalten kann.
 *
 * <p><b>Streaming</b> stand hier frueher als "bringt im Stapelbetrieb nichts
 * ausser Komplexitaet". Das war richtig, solange man eine Antwort nur
 * abwarten wollte. Es wurde falsch, sobald man eine entgleisende Antwort
 * <em>abbrechen</em> will, statt siebzehn Minuten auf ihr Ende zu warten --
 * siehe {@link Strom}. Das Ergebnis ist dasselbe wie ohne Streaming; lehnt ein
 * Server es ab, faellt der Client fuer den Rest des Laufs darauf zurueck.
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

    /** Ob gestreamt wird. Faellt auf false, wenn der Server es ablehnt. */
    private volatile boolean streamen;

    public ChatClient(String baseUrl, String modell, String apiKey,
                      int maxOutput, Duration zeitraum) {
        this(baseUrl, modell, apiKey, maxOutput, zeitraum, true);
    }

    public ChatClient(String baseUrl, String modell, String apiKey,
                      int maxOutput, Duration zeitraum, boolean streamen) {
        this.streamen  = streamen;
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
        return complete(verlauf, werkzeuge, Strom.STILL);
    }

    @Override
    public ChatResponse complete(List<Message> verlauf, List<ToolSpec> werkzeuge, Strom strom) {
        if (streamen) {
            try {
                return gestreamt(verlauf, werkzeuge, strom);
            } catch (ChatFehler e) {
                if (!lehntStreamAb(e)) throw e;
                // Einmal ablehnen genuegt; jede weitere Anfrage wuerde es
                // genauso tun und kostete einen Rundgang.
                streamen = false;
            }
        }
        return amStueck(verlauf, werkzeuge);
    }

    private ChatResponse amStueck(List<Message> verlauf, List<ToolSpec> werkzeuge) {
        HttpResponse<String> antwort;
        try {
            antwort = http.send(anfrage(baueAnfrage(verlauf, werkzeuge, false)),
                                HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ChatFehler("Verbindung zu " + endpunkt + " fehlgeschlagen", e);
        }
        if (antwort.statusCode() / 100 != 2) throw httpFehler(antwort.statusCode(), antwort.body());
        return lies(antwort.body());
    }

    /**
     * Die gestreamte Anfrage.
     *
     * <p>Die Zeitgrenze der Anfrage gilt bei Java nur bis zu den Kopfzeilen.
     * Danach koennte ein Server, der mitten in der Antwort verstummt, den Lauf
     * fuer immer anhalten. Deshalb ein Waechter, der den Strom nach
     * {@link #zeitraum} schliesst -- dieselbe Gesamtfrist wie ohne Streaming.
     */
    private ChatResponse gestreamt(List<Message> verlauf, List<ToolSpec> werkzeuge, Strom strom) {
        HttpResponse<java.io.InputStream> antwort;
        try {
            antwort = http.send(anfrage(baueAnfrage(verlauf, werkzeuge, true)),
                                HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ChatFehler("Verbindung zu " + endpunkt + " fehlgeschlagen", e);
        }

        try (java.io.InputStream in = antwort.body()) {
            if (antwort.statusCode() / 100 != 2)
                throw httpFehler(antwort.statusCode(),
                        new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));

            // Manche Server ignorieren "stream" und antworten am Stueck.
            String art = antwort.headers().firstValue("content-type").orElse("");
            if (!art.contains("event-stream"))
                return lies(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));

            Thread leser = Thread.currentThread();
            var fertig = new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread waechter = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(zeitraum.toMillis());
                    if (!fertig.get()) in.close();
                } catch (InterruptedException | java.io.IOException ignoriert) {
                    // fertig geworden, oder der Strom ist schon zu
                }
            });
            try {
                var r = new java.io.BufferedReader(new java.io.InputStreamReader(
                        in, java.nio.charset.StandardCharsets.UTF_8));
                return Stromsammler.lies(r, strom);
            } catch (java.io.IOException e) {
                // Ein Abbruch (Ctrl-C) kommt als IOException aus dem Lesen.
                // Das Kennzeichen wieder setzen, sonst wartet Retry geduldig
                // auf den naechsten Versuch.
                if (e instanceof java.io.InterruptedIOException
                        || e.getCause() instanceof InterruptedException || leser.isInterrupted())
                    Thread.currentThread().interrupt();
                throw new ChatFehler("Verbindung zu " + endpunkt + " fehlgeschlagen "
                        + "(Strom abgebrochen)", e);
            } catch (RuntimeException e) {
                if (e instanceof ChatFehler cf) throw cf;
                throw new ChatFehler("Antwort nicht lesbar: " + kurz(e.getMessage()), e);
            } finally {
                fertig.set(true);
                waechter.interrupt();
            }
        } catch (java.io.IOException e) {
            throw new ChatFehler("Verbindung zu " + endpunkt + " fehlgeschlagen", e);
        }
    }

    private HttpRequest anfrage(String rumpf) {
        HttpRequest.Builder b = HttpRequest.newBuilder(endpunkt)
                .timeout(zeitraum)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(rumpf));
        if (apiKey != null && !apiKey.isBlank())
            b.header("Authorization", "Bearer " + apiKey);
        return b.build();
    }

    private static ChatFehler httpFehler(int code, String rumpf) {
        String detail;
        try { detail = Messages.fehlertext(Json.parse(rumpf)); }
        catch (RuntimeException ignored) { detail = kurz(rumpf); }
        return new ChatFehler("HTTP " + code + ": " + detail, null);
    }

    private static ChatResponse lies(String rumpf) {
        try {
            return Messages.lies(Json.parse(rumpf));
        } catch (RuntimeException e) {
            throw new ChatFehler("Antwort nicht lesbar: " + kurz(rumpf), e);
        }
    }

    /**
     * Lehnt der Server Streaming ab? Nur dann zurueckfallen -- ein 400 wegen
     * eines zu langen Prompts ist kein Grund, das Streaming aufzugeben.
     */
    static boolean lehntStreamAb(ChatFehler e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return m.startsWith("http 4") || m.startsWith("http 5")
                ? m.contains("stream") : false;
    }

    // ------------------------------------------------------------- Anfrage

    String baueAnfrage(List<Message> verlauf, List<ToolSpec> werkzeuge) {
        return baueAnfrage(verlauf, werkzeuge, false);
    }

    String baueAnfrage(List<Message> verlauf, List<ToolSpec> werkzeuge, boolean stream) {
        Json.Writer w = new Json.Writer();
        w.objektAuf();
        w.feld("model").text(modell);
        w.feld("max_tokens").zahl(maxOutput);
        w.feld("stream").wahr(stream);
        // Ohne das fehlt beim Streamen die Nutzung -- und damit die
        // Kalibrierung des Tokenschaetzers und die Kostenzaehlung.
        if (stream) w.feld("stream_options").objektAuf().feld("include_usage").wahr(true).objektZu();

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
