package de.dg1001.harness.wire;

import de.dg1001.harness.wire.Messages.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest Werkzeugaufrufe, die als Text im Inhalt gelandet sind.
 *
 * <p>Lokale Server uebersetzen die Ausgabe des Modells mit einem Parser je
 * Modellfamilie in {@code tool_calls}. Passt der Parser nicht zum Modell --
 * falsche Vorlage, neue Modellversion, Quantisierung, die ein Steuerzeichen
 * verschluckt --, steht der Aufruf woertlich in {@code content}:
 * {@code <tool_call>{"name": "read", ...}</tool_call>}. Der Harness saehe dann
 * einen Zug ohne Aufruf und haelt ihn fuer die Schlussantwort.
 *
 * <p><b>Bewusst eng.</b> Erkannt werden nur Formen, die nichts anderes sein
 * koennen: die Marken {@code <tool_call>} (Hermes, Qwen) und
 * {@code [TOOL_CALLS]} (Mistral), oder ein Inhalt, der <em>vollstaendig</em>
 * aus einem Aufruf-JSON besteht (Llama, auch im Codeblock). JSON mitten in
 * Prosa bleibt Text -- eine Antwort, die erklaert, wie ein Aufruf aussieht,
 * ist kein Aufruf. Und jeder Name muss ein vorhandenes Werkzeug sein, sonst
 * gilt nichts davon.
 */
public final class Textaufrufe {

    private Textaufrufe() {}

    /** @param rest was vom Inhalt ohne die Aufrufe bleibt, oder null */
    public record Fund(List<ToolCall> aufrufe, String rest) {}

    private static final Pattern MARKE = Pattern.compile(
            "<tool_call>\\s*(.*?)\\s*(?:</tool_call>|$)", Pattern.DOTALL);
    private static final Pattern ZAUN = Pattern.compile(
            "^```[a-zA-Z]*\\s*\\n?(.*?)\\n?```$", Pattern.DOTALL);

    /** @return die erkannten Aufrufe, oder null, wenn der Inhalt keine ist */
    public static Fund erkenne(String inhalt, Set<String> werkzeuge) {
        if (inhalt == null || inhalt.isBlank()) return null;
        String s = inhalt.strip();

        // Hermes / Qwen
        if (s.contains("<tool_call>")) {
            List<ToolCall> l = new ArrayList<>();
            Matcher m = MARKE.matcher(s);
            StringBuilder rest = new StringBuilder();
            int bis = 0;
            while (m.find()) {
                ToolCall tc = aufruf(ohneZaun(m.group(1)), werkzeuge);
                if (tc == null) return null;
                l.add(tc);
                rest.append(s, bis, m.start());
                bis = m.end();
            }
            rest.append(s.substring(bis));
            return l.isEmpty() ? null : fund(l, rest.toString());
        }

        // Mistral
        int mistral = s.indexOf("[TOOL_CALLS]");
        if (mistral >= 0) {
            List<ToolCall> l = alle(s.substring(mistral + "[TOOL_CALLS]".length()).strip(), werkzeuge);
            return l == null ? null : fund(l, s.substring(0, mistral));
        }

        // Llama: der ganze Inhalt ist der Aufruf, wahlweise im Codeblock
        if (s.startsWith("<|python_tag|>")) s = s.substring("<|python_tag|>".length()).strip();
        List<ToolCall> l = alle(ohneZaun(s), werkzeuge);
        return l == null ? null : fund(l, null);
    }

    private static Fund fund(List<ToolCall> l, String rest) {
        String r = rest == null ? null : Messages.ohneDenkblock(rest.strip());
        return new Fund(Messages.mitKennungen(l), r == null || r.isBlank() ? null : r);
    }

    /** Ein Objekt oder eine Liste von Objekten, jedes ein Aufruf. */
    private static List<ToolCall> alle(String json, Set<String> werkzeuge) {
        if (!(json.startsWith("{") || json.startsWith("["))) return null;
        Object o;
        try { o = Json.parse(json); } catch (RuntimeException e) { return null; }
        List<Object> roh = o instanceof List<?> ? Json.arr(o) : List.of(o);
        if (roh.isEmpty()) return null;
        List<ToolCall> l = new ArrayList<>();
        for (Object x : roh) {
            ToolCall tc = aufruf(x, werkzeuge);
            if (tc == null) return null;
            l.add(tc);
        }
        return l;
    }

    private static ToolCall aufruf(String json, Set<String> werkzeuge) {
        try { return aufruf(Json.parse(json), werkzeuge); }
        catch (RuntimeException e) { return null; }
    }

    /** {"name":..,"arguments":..}, auch "parameters" und die function-Huelle. */
    private static ToolCall aufruf(Object o, Set<String> werkzeuge) {
        if (!(o instanceof Map)) return null;
        Object fn = Json.feld(o, "function");
        if (fn instanceof Map) o = fn;
        String name = Json.str(Json.feld(o, "name"));
        if (name == null || !werkzeuge.contains(name)) return null;
        Object args = Json.feld(o, "arguments");
        if (args == null) args = Json.feld(o, "parameters");
        String argsJson;
        if (args == null)              argsJson = "{}";
        else if (args instanceof String a) argsJson = a;
        else if (args instanceof Map)  argsJson = new Json.Writer().wert(args).toString();
        else return null;
        return new ToolCall(Json.str(Json.feld(o, "id")), name, argsJson);
    }

    private static String ohneZaun(String s) {
        Matcher m = ZAUN.matcher(s.strip());
        return m.matches() ? m.group(1).strip() : s.strip();
    }
}
