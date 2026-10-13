package de.dg1001.harness.wire;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.dg1001.harness.wire.Messages.ChatResponse;
import de.dg1001.harness.wire.Messages.FinishReason;
import de.dg1001.harness.wire.Messages.UserMessage;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Prueft das Streaming gegen einen Server im selben Prozess.
 *
 * <p>Ein echter Server liefert die schwierigen Faelle nur selten: einen
 * Werkzeugaufruf in zwanzig Bruchstuecken, einen Server, der mitten in der
 * Antwort verstummt, einen, der "stream" ablehnt. Hier laesst sich jeder davon
 * gezielt herstellen -- ohne Abhaengigkeit, {@code com.sun.net.httpserver}
 * liegt dem JDK bei.
 */
public final class ProbeStrom {

    private static int fehlgeschlagen = 0;

    /** Was der Testserver als naechstes tut. */
    interface Verhalten { void antworte(HttpExchange x) throws Exception; }

    private static volatile Verhalten verhalten;
    private static final List<String> anfragen = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/chat/completions", x -> {
            anfragen.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try { verhalten.antworte(x); }
            catch (Exception e) { /* Client hat aufgelegt -- erwartet in manchen Faellen */ }
            finally { x.close(); }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        List<Messages.Message> verlauf = List.of(new UserMessage("hallo"));

        // ----------------------------------------------- Zusammensetzen
        {
            verhalten = x -> sse(x,
                stueck("{\"reasoning_content\":\"Ich lese \"}", null),
                stueck("{\"reasoning_content\":\"die Datei.\"}", null),
                stueck("{\"content\":\"Mal sehen.\"}", null),
                stueck("{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\","
                     + "\"function\":{\"name\":\"read\",\"arguments\":\"\"}}]}", null),
                stueck("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"pfad\\\":\"}}]}", null),
                stueck("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"a.py\\\"}\"}}]}", null),
                stueck("{\"tool_calls\":[{\"index\":1,\"id\":\"c2\",\"function\":{\"name\":\"glob\","
                     + "\"arguments\":\"{\\\"muster\\\":\\\"*\\\"}\"}}]}", null),
                stueck("{}", "tool_calls"),
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":30,\"total_tokens\":150}}",
                "[DONE]");
            anfragen.clear();
            int[] meldungen = {0};
            ChatResponse a = client(url, Duration.ofSeconds(10))
                    .complete(verlauf, List.of(), (d, t, w) -> { meldungen[0]++; return false; });
            var tc = a.message().toolCalls();
            pruefe("Text und Denkblock zusammengesetzt",
                   "Mal sehen.".equals(a.message().content())
                   && "Ich lese die Datei.".equals(a.message().reasoningContent()));
            pruefe("Werkzeugaufruf aus Bruchstuecken, nach index",
                   tc.size() == 2 && "c1".equals(tc.get(0).id()) && "read".equals(tc.get(0).name())
                   && "{\"pfad\":\"a.py\"}".equals(tc.get(0).argumentsJson())
                   && "glob".equals(tc.get(1).name()));
            pruefe("Abbruchgrund und Nutzung aus dem Strom",
                   a.finishReason() == FinishReason.TOOL_CALLS && a.usage().promptTokens() == 120);
            pruefe("Mithoerer bekommt jedes Stueck, auch das Nutzungsstueck", meldungen[0] == 9);
            pruefe("Anfrage verlangt Strom samt Nutzung",
                   anfragen.get(0).contains("\"stream\":true") && anfragen.get(0).contains("include_usage"));
        }

        // ------------------------------------ Server antwortet am Stueck
        {
            verhalten = x -> json(x, 200, ganzeAntwort("am Stueck"));
            ChatResponse a = client(url, Duration.ofSeconds(10)).complete(verlauf, List.of());
            pruefe("Server ignoriert stream: Antwort trotzdem gelesen",
                   "am Stueck".equals(a.message().content()));
        }

        // ----------------------------------- Server lehnt Streaming ab
        {
            verhalten = x -> {
                if (anfragen.get(anfragen.size() - 1).contains("\"stream\":true"))
                    json(x, 400, "{\"error\":{\"message\":\"Cannot use tools with stream\"}}");
                else json(x, 200, ganzeAntwort("ohne Strom"));
            };
            anfragen.clear();
            ChatClient c = client(url, Duration.ofSeconds(10));
            ChatResponse a = c.complete(verlauf, List.of());
            c.complete(verlauf, List.of());
            pruefe("Ablehnung: dieselbe Anfrage ohne Strom wiederholt",
                   "ohne Strom".equals(a.message().content()));
            pruefe("... und danach gar nicht mehr gestreamt",
                   anfragen.size() == 3 && anfragen.get(2).contains("\"stream\":false"));
        }
        {
            verhalten = x -> json(x, 400, "{\"error\":{\"message\":\"prompt is too long\"}}");
            String m = fehler(() -> client(url, Duration.ofSeconds(10)).complete(verlauf, List.of()));
            pruefe("anderer 400: kein Rueckfall, Fehler bleibt sichtbar",
                   m != null && m.contains("prompt is too long"));
        }

        // ------------------------------------------------------- kappen
        {
            AtomicBoolean aufgelegt = new AtomicBoolean(false);
            verhalten = x -> {
                x.getResponseHeaders().add("Content-Type", "text/event-stream");
                x.sendResponseHeaders(200, 0);
                OutputStream o = x.getResponseBody();
                try {
                    for (int i = 0; i < 400; i++) {
                        o.write(("data: " + stueck("{\"reasoning_content\":\"hmm \"}", null) + "\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                        o.flush();
                        Thread.sleep(20);
                    }
                } catch (IOException e) {
                    aufgelegt.set(true);
                }
            };
            long t0 = System.nanoTime();
            ChatResponse a = client(url, Duration.ofSeconds(30))
                    .complete(verlauf, List.of(), (d, t, w) -> d > 20);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            pruefe("Wache kappt: GEKAPPT nach " + ms + " ms statt nach 8 s",
                   a.finishReason() == FinishReason.GEKAPPT && ms < 2_000);
            for (int i = 0; i < 50 && !aufgelegt.get(); i++) Thread.sleep(100);
            pruefe("... und der Server merkt es (er soll aufhoeren zu rechnen)", aufgelegt.get());
        }

        // ------------------------------------------ Server verstummt
        {
            verhalten = x -> {
                x.getResponseHeaders().add("Content-Type", "text/event-stream");
                x.sendResponseHeaders(200, 0);
                x.getResponseBody().write(("data: " + stueck("{\"content\":\"a\"}", null) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                x.getResponseBody().flush();
                Thread.sleep(20_000);
            };
            long t0 = System.nanoTime();
            String m = fehler(() -> client(url, Duration.ofSeconds(1)).complete(verlauf, List.of()));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            pruefe("verstummter Server: Gesamtfrist greift (" + ms + " ms)",
                   m != null && m.contains("Verbindung") && ms < 4_000);
        }

        // ------------------------------------------------- Unterbrechen
        {
            verhalten = x -> {
                x.getResponseHeaders().add("Content-Type", "text/event-stream");
                x.sendResponseHeaders(200, 0);
                Thread.sleep(20_000);
            };
            var ergebnis = new CompletableFuture<Boolean>();
            Thread t = Thread.ofVirtual().start(() -> {
                try {
                    client(url, Duration.ofSeconds(30)).complete(verlauf, List.of());
                    ergebnis.complete(false);
                } catch (ChatClient.ChatFehler e) {
                    ergebnis.complete(Thread.currentThread().isInterrupted());
                }
            });
            Thread.sleep(300);
            t.interrupt();
            Boolean kennzeichen = ergebnis.get(5, TimeUnit.SECONDS);
            pruefe("Unterbrechung beendet das Lesen, Kennzeichen bleibt gesetzt", kennzeichen);
        }

        // --------------------------------------- Fehler mitten im Strom
        {
            verhalten = x -> sse(x, stueck("{\"content\":\"a\"}", null),
                                 "{\"error\":{\"message\":\"out of memory\"}}");
            String m = fehler(() -> client(url, Duration.ofSeconds(10)).complete(verlauf, List.of()));
            pruefe("Fehler im Strom wird gemeldet", m != null && m.contains("out of memory"));
        }

        // ------------------------------------ Denkblock im Inhalt
        {
            AtomicBoolean gesehen = new AtomicBoolean(false);
            verhalten = x -> sse(x, stueck("{\"content\":\"<think>ich ueberlege lange\"}", null),
                                 stueck("{\"content\":\" und laenger</think>Antwort\"}", "stop"), "[DONE]");
            ChatResponse a = client(url, Duration.ofSeconds(10)).complete(verlauf, List.of(),
                    (d, t, w) -> { if (d > 10 && t == 0) gesehen.set(true); return false; });
            pruefe("offenes <think> im Inhalt zaehlt als Denken", gesehen.get());
            pruefe("... und faellt aus der Antwort heraus", "Antwort".equals(a.message().content()));
        }

        server.stop(0);
        System.out.println(fehlgeschlagen == 0
                ? "\nAlle Pruefungen bestanden."
                : "\n" + fehlgeschlagen + " Pruefung(en) fehlgeschlagen.");
        if (fehlgeschlagen > 0) System.exit(1);
    }

    // ------------------------------------------------------------ Zutaten

    private static ChatClient client(String url, Duration frist) {
        return new ChatClient(url, "probe", "unused", 512, frist, true);
    }

    private static String stueck(String delta, String grund) {
        return "{\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":"
             + (grund == null ? "null" : "\"" + grund + "\"") + "}]}";
    }

    private static String ganzeAntwort(String text) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + text
             + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1}}";
    }

    private static void sse(HttpExchange x, String... daten) throws IOException {
        x.getResponseHeaders().add("Content-Type", "text/event-stream");
        x.sendResponseHeaders(200, 0);
        OutputStream o = x.getResponseBody();
        for (String d : daten) {
            o.write(("data: " + d + "\n\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
        }
    }

    private static void json(HttpExchange x, int code, String rumpf) throws IOException {
        byte[] b = rumpf.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(code, b.length);
        x.getResponseBody().write(b);
    }

    private static String fehler(Runnable r) {
        try { r.run(); return null; }
        catch (ChatClient.ChatFehler e) { return e.getMessage(); }
    }

    private static void pruefe(String was, boolean ok) {
        System.out.printf("%-62s %s%n", was, ok ? "ok" : "FEHLGESCHLAGEN");
        if (!ok) fehlgeschlagen++;
    }
}
