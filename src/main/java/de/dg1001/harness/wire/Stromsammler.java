package de.dg1001.harness.wire;

import de.dg1001.harness.wire.Messages.AssistantMessage;
import de.dg1001.harness.wire.Messages.ChatResponse;
import de.dg1001.harness.wire.Messages.FinishReason;
import de.dg1001.harness.wire.Messages.ToolCall;
import de.dg1001.harness.wire.Messages.Usage;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Setzt eine gestreamte Antwort ({@code text/event-stream}) wieder zu einer
 * {@link ChatResponse} zusammen.
 *
 * <p>Am Ende muss dasselbe herauskommen wie ohne Streaming, Byte fuer Byte im
 * Verlauf -- sonst unterscheiden sich zwei Laeufe nur dadurch, wie die Antwort
 * transportiert wurde, und der Praefix-Cache greift nicht mehr.
 *
 * <p>Die Stolperstelle sind Werkzeugaufrufe. Sie kommen in Bruchstuecken,
 * zugeordnet ueber {@code index}, nicht ueber {@code id}: die Kennung steht nur
 * im ersten Stueck, die Argumente sind ueber viele verteilt. Wer nach Kennung
 * zusammensetzt, verliert alles ab dem zweiten Stueck.
 */
final class Stromsammler {

    private final StringBuilder text = new StringBuilder();
    private final StringBuilder denken = new StringBuilder();
    private final Map<Integer, String[]> aufrufe = new TreeMap<>();   // index -> {id, name, args}
    private int aufrufZeichen = 0;
    private String grund;
    private Usage nutzung = Usage.LEER;

    /**
     * Liest Ereignisse bis {@code [DONE]} oder Stromende.
     *
     * @return die Antwort; bei Abbruch durch {@code strom} mit
     *         {@link FinishReason#GEKAPPT} und ohne Werkzeugaufrufe, weil deren
     *         Argumente dann unvollstaendig sein koennen
     */
    static ChatResponse lies(BufferedReader r, Strom strom) throws IOException {
        Stromsammler s = new Stromsammler();
        String zeile;
        while ((zeile = r.readLine()) != null) {
            if (!zeile.startsWith("data:")) continue;          // Kommentare, event:, Leerzeilen
            String daten = zeile.substring(5).strip();
            if (daten.equals("[DONE]")) break;
            if (daten.isEmpty()) continue;
            Object stueck = Json.parse(daten);
            Object fehler = Json.feld(stueck, "error");
            if (fehler != null)
                throw new ChatClient.ChatFehler("Fehler im Strom: " + Messages.fehlertext(stueck), null);
            s.nimm(stueck);
            if (strom.fortschritt(s.denkZeichen(), s.textZeichen(), s.aufrufZeichen))
                return s.gekappt();
        }
        return s.antwort();
    }

    void nimm(Object stueck) {
        Object u = Json.feld(stueck, "usage");
        if (u instanceof Map)
            nutzung = new Usage(Json.num(Json.feld(u, "prompt_tokens"), 0),
                                Json.num(Json.feld(u, "completion_tokens"), 0),
                                Json.num(Json.feld(u, "total_tokens"), 0));

        List<Object> choices = Json.arr(Json.feld(stueck, "choices"));
        if (choices.isEmpty()) return;                          // das Nutzungsstueck am Ende
        Object c0 = choices.get(0);
        String g = Json.str(Json.feld(c0, "finish_reason"));
        if (g != null) grund = g;

        Object delta = Json.feld(c0, "delta");
        String t = Json.str(Json.feld(delta, "content"));
        if (t != null) text.append(t);
        String d = Json.str(Json.feld(delta, "reasoning_content"));
        if (d == null) d = Json.str(Json.feld(delta, "reasoning"));
        if (d != null) denken.append(d);

        List<Object> tcs = Json.arr(Json.feld(delta, "tool_calls"));
        for (int k = 0; k < tcs.size(); k++) {
            Object tc = tcs.get(k);
            int index = Json.num(Json.feld(tc, "index"), k);
            String[] a = aufrufe.computeIfAbsent(index, i -> new String[]{null, null, ""});
            String id = Json.str(Json.feld(tc, "id"));
            if (id != null && !id.isEmpty()) a[0] = id;
            Object fn = Json.feld(tc, "function");
            String name = Json.str(Json.feld(fn, "name"));
            // Der Name kommt im ersten Stueck. Manche Server wiederholen ihn
            // in jedem; anhaengen gaebe dann "readreadread".
            if (name != null && !name.isEmpty() && a[1] == null) { a[1] = name; aufrufZeichen += name.length(); }
            String args = Json.str(Json.feld(fn, "arguments"));
            if (args != null) { a[2] += args; aufrufZeichen += args.length(); }
        }
    }

    /**
     * Denkzeichen -- auch die, die ein Server ohne Trennung in {@code content}
     * schreibt. Solange ein {@code <think>} offen ist, ist es Denken, nicht
     * Text; sonst saehe die Wache den Fall, fuer den sie da ist, gar nicht.
     */
    int denkZeichen() {
        if (text.indexOf("<think>") >= 0 && text.indexOf("</think>") < 0)
            return denken.length() + text.length();
        return denken.length();
    }

    int textZeichen() {
        return denkZeichen() > denken.length() ? 0 : text.length();
    }

    ChatResponse antwort() {
        List<ToolCall> l = new ArrayList<>();
        for (String[] a : aufrufe.values())
            l.add(new ToolCall(a[0], a[1], a[2].isEmpty() ? "{}" : a[2]));
        return new ChatResponse(new AssistantMessage(inhalt(), denkText(), l),
                                FinishReason.von(grund), nutzung);
    }

    private ChatResponse gekappt() {
        return new ChatResponse(new AssistantMessage(inhalt(), denkText(), List.of()),
                                FinishReason.GEKAPPT, nutzung);
    }

    /** Wie {@link Messages#lies}: null statt leer, Denkblock aus dem Inhalt. */
    private String inhalt() {
        if (text.isEmpty()) return null;
        return Messages.ohneDenkblock(text.toString());
    }

    private String denkText() {
        return denken.isEmpty() ? null : denken.toString();
    }
}
