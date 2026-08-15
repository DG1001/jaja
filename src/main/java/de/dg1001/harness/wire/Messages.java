package de.dg1001.harness.wire;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Die Nachrichtentypen des Gespraechs und ihre Serialisierung.
 *
 * <p>Der versiegelte Verbund zahlt sich beim Schreiben aus: das {@code switch}
 * in {@link #schreibe} ist erschoepfend geprueft. Kommt spaeter ein fuenfter
 * Typ dazu, zeigt der Uebersetzer jede Stelle, die ihn behandeln muss.
 */
public final class Messages {

    private Messages() {}

    // ------------------------------------------------------------- Typen

    public sealed interface Message
            permits SystemMessage, UserMessage, AssistantMessage, ToolMessage {
        String role();
    }

    public record SystemMessage(String content) implements Message {
        public String role() { return "system"; }
    }

    public record UserMessage(String content) implements Message {
        public String role() { return "user"; }
    }

    /**
     * @param content          kann null sein — bei reinen Werkzeugaufrufen
     *                         liefern die Server kein Textfeld
     * @param reasoningContent getrennte Gedankenkette, sofern der Server sie
     *                         liefert. Wird NICHT zurueckgeschickt (siehe
     *                         schreibe), aber mitgefuehrt, damit man sie
     *                         protokollieren kann
     * @param toolCalls        nie null, notfalls leer
     */
    public record AssistantMessage(String content,
                                   String reasoningContent,
                                   List<ToolCall> toolCalls) implements Message {
        public AssistantMessage {
            toolCalls = (toolCalls == null) ? List.of() : List.copyOf(toolCalls);
        }
        public String role() { return "assistant"; }
        public boolean hatWerkzeugaufrufe() { return !toolCalls.isEmpty(); }
    }

    public record ToolMessage(String toolCallId, String content) implements Message {
        public String role() { return "tool"; }
    }

    /** {@code argumentsJson} bleibt bewusst Zeichenkette: so liefern es die
     *  Server, und so muss es beim Zurueckschicken wieder hinein. Geparst wird
     *  erst im Werkzeug. */
    public record ToolCall(String id, String name, String argumentsJson) {

        /** Hoechstlaenge der Kurzfassung — dieselbe Grenze wie beim Kuerzen
         *  der Argumente im Verlauf. */
        private static final int GROSS = 200;

        /**
         * Das wesentliche Argument in einer Zeile, fuer Protokoll und Anzeige.
         *
         * <p>Bis ein Lauf ueber Nacht in einem fremden Projekt geschrieben hat,
         * protokollierte der Stapelbetrieb nur den Werkzeugnamen. Damit liess
         * sich hinterher nicht feststellen, welcher Lauf es war — das
         * Protokoll enthielt die Information schlicht nicht.
         */
        public String kurz() {
            String v = null;
            try {
                var m = Json.obj(Json.parse(argumentsJson));
                for (String k : new String[]{"kommando", "pfad", "muster", "alt",
                                             "datei", "stichwort"}) {
                    String x = Json.str(m.get(k));
                    if (x != null && !x.isBlank()) { v = x; break; }
                }
            } catch (RuntimeException e) {
                // kaputtes JSON: dann eben das Rohe
            }
            if (v == null) v = argumentsJson == null ? "" : argumentsJson;
            v = v.replace("\n", "\u23ce");
            return v.length() > GROSS ? v.substring(0, GROSS) + "…" : v;
        }
    }

    // -------------------------------------------------------- Abbruchgrund

    public enum FinishReason {
        STOP, TOOL_CALLS, LENGTH, OTHER;

        public static FinishReason von(String s) {
            if (s == null) return OTHER;
            return switch (s) {
                case "stop"       -> STOP;
                case "tool_calls" -> TOOL_CALLS;
                case "length"     -> LENGTH;
                default           -> OTHER;
            };
        }
    }

    public record Usage(int promptTokens, int completionTokens, int totalTokens) {
        public static final Usage LEER = new Usage(0, 0, 0);
    }

    public record ChatResponse(AssistantMessage message,
                               FinishReason finishReason,
                               Usage usage) {}

    /** Werkzeugbeschreibung fuer die Anfrage. {@code parametersJson} ist ein
     *  fertiges JSON-Schema als Zeichenkette. */
    public record ToolSpec(String name, String description, String parametersJson) {}

    // -------------------------------------------------------- schreiben

    static void schreibe(Json.Writer w, Message m) {
        w.objektAuf();
        w.feld("role").text(m.role());
        switch (m) {
            case SystemMessage s -> w.feld("content").text(s.content());
            case UserMessage u   -> w.feld("content").text(u.content());
            case ToolMessage t   -> {
                w.feld("tool_call_id").text(t.toolCallId());
                w.feld("content").text(t.content());
            }
            case AssistantMessage a -> {
                // content muss auch dann gesetzt sein, wenn es null ist:
                // manche Server lehnen die Nachricht sonst ab.
                w.feld("content").text(a.content());
                // reasoning_content wird bewusst NICHT zurueckgeschickt.
                // vLLM und ds4-server erwarten es nicht, und manche Server
                // quittieren ein unbekanntes Feld mit 400.
                if (a.hatWerkzeugaufrufe()) {
                    w.feld("tool_calls").listeAuf();
                    for (ToolCall tc : a.toolCalls()) {
                        w.objektAuf();
                        w.feld("id").text(tc.id());
                        w.feld("type").text("function");
                        w.feld("function").objektAuf();
                        w.feld("name").text(tc.name());
                        // arguments ist im Protokoll eine ZEICHENKETTE, die
                        // JSON enthaelt -- nicht eingebettetes JSON.
                        w.feld("arguments").text(tc.argumentsJson());
                        w.objektZu();
                        w.objektZu();
                    }
                    w.listeZu();
                }
            }
        }
        w.objektZu();
    }

    /**
     * Ganze Nachrichtenliste als JSON — fuer gespeicherte Sitzungen.
     *
     * <p>Bewusst dasselbe Format wie in der Anfrage: eine gespeicherte Sitzung
     * ist damit lesbar und man sieht sofort, was das Modell zu sehen bekaeme.
     * Denkbloecke fehlen darin, weil sie auch in der Anfrage fehlen.
     */
    public static String schreibeListe(List<Message> nachrichten) {
        Json.Writer w = new Json.Writer();
        w.listeAuf();
        for (Message m : nachrichten) schreibe(w, m);
        w.listeZu();
        return w.toString();
    }

    public static List<Message> lieseListe(String json) {
        List<Message> aus = new ArrayList<>();
        for (Object o : Json.arr(Json.parse(json))) {
            String rolle  = Json.str(Json.feld(o, "role"));
            String inhalt = Json.str(Json.feld(o, "content"));
            switch (rolle == null ? "" : rolle) {
                case "system"    -> aus.add(new SystemMessage(inhalt));
                case "user"      -> aus.add(new UserMessage(inhalt));
                case "tool"      -> aus.add(new ToolMessage(
                                        Json.str(Json.feld(o, "tool_call_id")), inhalt));
                case "assistant" -> aus.add(new AssistantMessage(
                                        inhalt, null, werkzeugaufrufe(o)));
                default -> { }   // unbekannte Rolle: ueberspringen
            }
        }
        return aus;
    }

    /**
     * Entfernt einen Denkblock, der im Inhalt gelandet ist.
     *
     * <p>Eigentlich gehoert er nach {@code reasoning_content}. Manche Server
     * schreiben ihn trotzdem in {@code content} — beobachtet an einer Antwort,
     * die mit mehreren Absaetzen Selbstgespraech begann und erst nach einem
     * {@code </think>} die eigentliche Antwort brachte. Ungefiltert steht das
     * dann im Protokoll, im Verlauf und vor den Augen des Nutzers.
     *
     * <p>Genommen wird alles nach dem <em>letzten</em> Schlusszeichen. Ein
     * Text, der ueber Denkbloecke schreibt und das Zeichen woertlich enthaelt,
     * wuerde dabei beschnitten — das ist selten genug und immer noch besser
     * als seitenweise Selbstgespraech als Antwort auszuliefern.
     */
    static String ohneDenkblock(String inhalt) {
        if (inhalt == null) return null;
        int ende = inhalt.lastIndexOf("</think>");
        if (ende < 0) return inhalt;
        String rest = inhalt.substring(ende + "</think>".length()).strip();
        return rest.isEmpty() ? inhalt : rest;      // nur Denken, kein Text: lieber alles behalten
    }

    /** Liest {@code tool_calls} aus einer Nachricht. */
    private static List<ToolCall> werkzeugaufrufe(Object nachricht) {
        List<ToolCall> aufrufe = new ArrayList<>();
        for (Object o : Json.arr(Json.feld(nachricht, "tool_calls"))) {
            Object fn = Json.feld(o, "function");
            String args = Json.str(Json.feld(fn, "arguments"));
            aufrufe.add(new ToolCall(
                    Json.str(Json.feld(o, "id")),
                    Json.str(Json.feld(fn, "name")),
                    args == null ? "{}" : args));
        }
        return aufrufe;
    }

    // --------------------------------------------------------- lesen

    static ChatResponse lies(Object wurzel) {
        List<Object> choices = Json.arr(Json.feld(wurzel, "choices"));
        if (choices.isEmpty())
            throw new Json.JsonFehler("Antwort ohne choices: " + kurz(wurzel));

        Object c0  = choices.get(0);
        Object msg = Json.feld(c0, "message");

        String inhalt = ohneDenkblock(Json.str(Json.feld(msg, "content")));

        // vLLM nennt es reasoning_content, andere reasoning. Beide annehmen.
        String denken = Json.str(Json.feld(msg, "reasoning_content"));
        if (denken == null) denken = Json.str(Json.feld(msg, "reasoning"));

        List<ToolCall> aufrufe = werkzeugaufrufe(msg);

        FinishReason grund = FinishReason.von(Json.str(Json.feld(c0, "finish_reason")));

        Object u = Json.feld(wurzel, "usage");
        Usage nutzung = (u == null) ? Usage.LEER : new Usage(
                Json.num(Json.feld(u, "prompt_tokens"), 0),
                Json.num(Json.feld(u, "completion_tokens"), 0),
                Json.num(Json.feld(u, "total_tokens"), 0));

        return new ChatResponse(
                new AssistantMessage(inhalt, denken, aufrufe), grund, nutzung);
    }

    private static String kurz(Object o) {
        String s = String.valueOf(o);
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** Nur fuer Fehlermeldungen: die Server verpacken Fehler unterschiedlich. */
    static String fehlertext(Object wurzel) {
        Object e = Json.feld(wurzel, "error");
        if (e instanceof Map) {
            String m = Json.str(Json.feld(e, "message"));
            if (m != null) return m;
        }
        String m = Json.str(Json.feld(wurzel, "message"));
        return m != null ? m : kurz(wurzel);
    }
}
