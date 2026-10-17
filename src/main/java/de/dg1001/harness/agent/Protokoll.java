package de.dg1001.harness.agent;

import de.dg1001.harness.tools.Tool;
import de.dg1001.harness.wire.Json;
import de.dg1001.harness.wire.Messages.ChatResponse;
import de.dg1001.harness.wire.Messages.ToolCall;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Schreibt mit, was ein Lauf tut, als JSONL: eine Zeile je Ereignis.
 *
 * <p>Bisher stand das nur im menschenlesbaren Protokoll auf stderr, und jede
 * Auswertung hiess grep. Ueber tausend Laeufe dieses Repos wurden so
 * ausgewertet -- mit Mustern, die beim ersten geaenderten Meldungstext still
 * nichts mehr finden. Hier steht jedes Feld unter einem Namen.
 *
 * <p>Die letzte Zeile eines Laufs ({@code "t":"ende"}) traegt die Summen:
 * Status, Zuege, Token, Dauer, wie oft der Harness eingegriffen hat und welche
 * Werkzeuge wie oft scheiterten. Wer nur vergleichen will, liest nur sie.
 *
 * <p>Reicht alles an einen zweiten Beobachter weiter: das Protokoll ersetzt
 * weder die Ausgabe auf stderr noch die Anzeige, es liegt daneben.
 */
public final class Protokoll implements Beobachter, AutoCloseable {

    private final Beobachter weiter;
    private final BufferedWriter aus;
    private final long beginn = System.nanoTime();
    private volatile long anfrageSeit = beginn;
    private final Map<String, Long> werkzeugSeit = new ConcurrentHashMap<>();
    private final Map<String, Integer> ereignisse = new ConcurrentHashMap<>();
    private final Map<String, Integer> werkzeugfehler = new ConcurrentHashMap<>();
    private final Map<String, Integer> werkzeugaufrufe = new ConcurrentHashMap<>();

    private Protokoll(Path datei, Beobachter weiter) throws IOException {
        this.weiter = weiter;
        if (datei.getParent() != null) Files.createDirectories(datei.getParent());
        this.aus = Files.newBufferedWriter(datei, StandardCharsets.UTF_8);
    }

    /** @param kopf was den Lauf ausmacht: Modell, Adresse, Schalter */
    public static Protokoll oeffne(Path datei, Beobachter weiter, Map<String, ?> kopf)
            throws IOException {
        Protokoll p = new Protokoll(datei, weiter);
        p.zeile(Map.of("t", "start", "zeit", Instant.now().toString(), "kopf", kopf));
        return p;
    }

    // ---------------------------------------------------------- Ereignisse

    @Override public void anfrage(int zug) {
        anfrageSeit = System.nanoTime();
        weiter.anfrage(zug);
    }

    @Override public void zug(int nummer, ChatResponse a, int budget) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("t", "zug");
        m.put("zug", nummer);
        m.put("ms", ms(anfrageSeit));
        m.put("grund", a.finishReason().name().toLowerCase());
        m.put("eingabe", a.usage().promptTokens());
        m.put("ausgabe", a.usage().completionTokens());
        if (a.usage().cacheBekannt()) m.put("cache", a.usage().cachedTokens());
        m.put("aufrufe", a.message().toolCalls().size());
        m.put("denk_zeichen", laenge(a.message().reasoningContent()));
        m.put("text_zeichen", laenge(a.message().content()));
        zeile(m);
        weiter.zug(nummer, a, budget);
    }

    @Override public void werkzeugStart(ToolCall tc) {
        if (tc.id() != null) werkzeugSeit.put(tc.id(), System.nanoTime());
        weiter.werkzeugStart(tc);
    }

    @Override public void werkzeugFertig(ToolCall tc, Tool.ToolResult r) {
        Long seit = tc.id() == null ? null : werkzeugSeit.remove(tc.id());
        werkzeugaufrufe.merge(tc.name(), 1, Integer::sum);
        if (r.istFehler()) werkzeugfehler.merge(tc.name(), 1, Integer::sum);
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("t", "werkzeug");
        m.put("name", tc.name());
        m.put("arg", tc.kurz());
        m.put("fehler", r.istFehler());
        m.put("zeichen", laenge(r.text()));
        m.put("ms", seit == null ? 0 : ms(seit));
        zeile(m);
        weiter.werkzeugFertig(tc, r);
    }

    @Override public void ereignis(String art) {
        ereignisse.merge(art, 1, Integer::sum);
        zeile(Map.of("t", "ereignis", "art", art));
        weiter.ereignis(art);
    }

    @Override public void hinweis(String text) {
        zeile(Map.of("t", "hinweis", "text", text == null ? "" : text));
        weiter.hinweis(text);
    }

    @Override public void strom(int denkToken, int textToken) {
        weiter.strom(denkToken, textToken);        // zu haeufig fuer eine Zeile je Stueck
    }

    @Override public void laufEnde(Agent.Ergebnis e, long eingabe, long ausgabe, long ms) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("t", "ende");
        m.put("status", e.status().name());
        m.put("zuege", e.zuege());
        m.put("aufrufe", e.werkzeugaufrufe());
        m.put("eingabe", eingabe);
        m.put("ausgabe", ausgabe);
        m.put("ms", ms);
        m.put("ereignisse", new TreeMap<>(ereignisse));
        m.put("werkzeugaufrufe", new TreeMap<>(werkzeugaufrufe));
        m.put("werkzeugfehler", new TreeMap<>(werkzeugfehler));
        if (e.hinweis() != null) m.put("hinweis", e.hinweis());
        zeile(m);
        // Ein Protokoll je Lauf zaehlt je Lauf: in der Sitzung folgen mehrere.
        ereignisse.clear();
        werkzeugaufrufe.clear();
        werkzeugfehler.clear();
        weiter.laufEnde(e, eingabe, ausgabe, ms);
    }

    // ------------------------------------------------------------- Zutaten

    /**
     * Sofort geschrieben und geleert: ein Lauf, der abstuerzt oder per
     * Zeitgrenze abgewuergt wird, soll bis zur letzten Zeile lesbar sein --
     * genau dann braucht man das Protokoll.
     */
    private synchronized void zeile(Map<String, ?> m) {
        try {
            var w = new Json.Writer();
            var mit = new java.util.LinkedHashMap<String, Object>(m);
            mit.put("s", Math.round((System.nanoTime() - beginn) / 1e7) / 100.0);
            aus.write(w.wert(mit).toString());
            aus.newLine();
            aus.flush();
        } catch (IOException ignoriert) {
            // Ein volles Laufwerk soll den Lauf nicht beenden.
        }
    }

    private static long ms(long seitNanos) { return (System.nanoTime() - seitNanos) / 1_000_000; }

    private static int laenge(String s) { return s == null ? 0 : s.length(); }

    @Override public synchronized void close() throws IOException { aus.close(); }
}
