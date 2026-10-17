package de.dg1001.harness.wire;

import de.dg1001.harness.wire.Messages.AssistantMessage;
import de.dg1001.harness.wire.Messages.ChatResponse;
import de.dg1001.harness.wire.Messages.FinishReason;
import de.dg1001.harness.wire.Messages.SystemMessage;
import de.dg1001.harness.wire.Messages.ToolCall;
import de.dg1001.harness.wire.Messages.ToolMessage;
import de.dg1001.harness.wire.Messages.UserMessage;

import java.util.List;

/**
 * Prueft die Abbildung auf das Protokoll.
 *
 * <p>Drei Details haben beim Bau Zeit gekostet, und alle drei scheitern leise:
 * der Server antwortet mit 400 oder liefert Unsinn, und im Protokoll steht
 * nichts, was auf die Ursache zeigt. Sie stehen als Kommentar in
 * {@link Messages} — hier stehen sie als Pruefung, damit ein spaeteres
 * Aufraeumen sie nicht versehentlich rueckgaengig macht.
 */
public final class ProbeMessages {

    private static int fehlgeschlagen = 0;

    public static void main(String[] args) {

        // ------------------------------------------------------------ Detail 1
        // 'arguments' ist im Protokoll eine ZEICHENKETTE, die JSON enthaelt --
        // nicht eingebettetes JSON. Wer es als Objekt schreibt, bekommt vom
        // Server eine Fehlermeldung ueber einen falschen Typ, die nicht sagt,
        // welches Feld gemeint ist.
        String mitAufruf = schreibe(new AssistantMessage(null, null,
                List.of(new ToolCall("call_1", "read", "{\"pfad\":\"a.txt\"}"))));
        pruefe("arguments ist eine Zeichenkette, kein eingebettetes Objekt",
               mitAufruf.contains("\"arguments\":\"{\\\"pfad\\\":\\\"a.txt\\\"}\""));
        pruefe("Werkzeugaufruf traegt id, type und function",
               mitAufruf.contains("\"id\":\"call_1\"")
                       && mitAufruf.contains("\"type\":\"function\"")
                       && mitAufruf.contains("\"name\":\"read\""));

        // ------------------------------------------------------------ Detail 2
        // content muss auch dann im Objekt stehen, wenn es null ist. Manche
        // Server lehnen eine Assistentennachricht ohne das Feld ab -- auch
        // dann, wenn sie Werkzeugaufrufe enthaelt.
        pruefe("content wird auch als null geschrieben",
               mitAufruf.contains("\"content\":null"));

        // ------------------------------------------------------------ Detail 3
        // reasoning_content wird gelesen, aber NIE zurueckgeschickt. Manche
        // Server quittieren ein unbekanntes Feld mit 400, und der Denkblock
        // ist ohnehin der groesste Posten im Verlauf.
        String mitDenken = schreibe(new AssistantMessage(
                "die Antwort", "sehr lange Ueberlegung", List.of()));
        pruefe("reasoning_content wird nicht zurueckgeschickt",
               !mitDenken.contains("reasoning") && !mitDenken.contains("Ueberlegung"));
        pruefe("der eigentliche Inhalt bleibt erhalten",
               mitDenken.contains("\"content\":\"die Antwort\""));

        // ---------------------------------------------------- uebrige Rollen
        pruefe("Systemnachricht",
               schreibe(new SystemMessage("sys")), "{\"role\":\"system\",\"content\":\"sys\"}");
        pruefe("Nutzernachricht",
               schreibe(new UserMessage("hallo")), "{\"role\":\"user\",\"content\":\"hallo\"}");
        pruefe("Werkzeugergebnis traegt tool_call_id",
               schreibe(new ToolMessage("call_1", "ergebnis")),
               "{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"ergebnis\"}");
        pruefe("kein tool_calls-Feld ohne Werkzeugaufrufe",
               !schreibe(new AssistantMessage("nur Text", null, List.of()))
                       .contains("tool_calls"));

        // -------------------------------------------------------- Denkbloecke
        // Beobachtet an einem Server, der den Denkblock nach content schrieb:
        // die Antwort begann mit Absaetzen Selbstgespraech und einem </think>.
        pruefe("Denkblock im Inhalt wird abgeschnitten",
               Messages.ohneDenkblock("ich ueberlege...</think>Die Antwort."), "Die Antwort.");
        pruefe("mehrere Schlusszeichen: das letzte zaehlt",
               Messages.ohneDenkblock("a</think>b</think>Antwort"), "Antwort");
        pruefe("gewoehnlicher Text bleibt unangetastet",
               Messages.ohneDenkblock("Ganz normale Antwort."), "Ganz normale Antwort.");
        pruefe("null bleibt null", Messages.ohneDenkblock(null), null);
        // Steht nach dem Schlusszeichen nichts, war alles Denken -- dann lieber
        // etwas Unschoenes zeigen als eine leere Antwort.
        pruefe("nur Denken: nichts wird weggeworfen",
               Messages.ohneDenkblock("nur denken</think>   "), "nur denken</think>   ");
        pruefe("gelesene Antwort ist bereits gefiltert",
               Messages.lies(Json.parse(
                   "{\"choices\":[{\"message\":{\"content\":\"denk</think>echt\"}}]}"))
                       .message().content(), "echt");

        // ------------------------------------------------------------- lesen
        ChatResponse r = Messages.lies(Json.parse("""
                {"choices":[{"message":{"content":"fertig","reasoning_content":"denk"},
                             "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}"""));
        pruefe("gelesen: Inhalt", r.message().content(), "fertig");
        pruefe("gelesen: reasoning_content", r.message().reasoningContent(), "denk");
        pruefe("gelesen: finish_reason", r.finishReason(), FinishReason.STOP);
        pruefe("gelesen: usage", r.usage().promptTokens(), 10);

        // vLLM nennt es reasoning_content, andere schlicht reasoning.
        ChatResponse r2 = Messages.lies(Json.parse(
                "{\"choices\":[{\"message\":{\"content\":\"x\",\"reasoning\":\"denk\"}}]}"));
        pruefe("gelesen: auch das Feld 'reasoning'", r2.message().reasoningContent(), "denk");

        ChatResponse r3 = Messages.lies(Json.parse("""
                {"choices":[{"message":{"content":null,"tool_calls":[
                   {"id":"c1","function":{"name":"bash","arguments":"{\\"kommando\\":\\"ls\\"}"}}]},
                  "finish_reason":"tool_calls"}]}"""));
        pruefe("gelesen: Werkzeugaufruf", r3.message().toolCalls().size(), 1);
        pruefe("gelesen: Werkzeugname", r3.message().toolCalls().get(0).name(), "bash");
        pruefe("gelesen: arguments bleibt Zeichenkette",
               r3.message().toolCalls().get(0).argumentsJson(), "{\"kommando\":\"ls\"}");
        pruefe("gelesen: TOOL_CALLS", r3.finishReason(), FinishReason.TOOL_CALLS);
        pruefe("gelesen: fehlende usage wird zu LEER", r3.usage().promptTokens(), 0);

        // Cache-Treffer: drei Dialekte fuer dieselbe Zahl.
        pruefe("Cache: ohne Angabe -1", r.usage().cachedTokens(), -1);
        pruefe("Cache: OpenAI/vLLM prompt_tokens_details",
               Messages.lies(Json.parse("""
                   {"choices":[{"message":{"content":"x"}}],
                    "usage":{"prompt_tokens":100,"completion_tokens":1,"total_tokens":101,
                             "prompt_tokens_details":{"cached_tokens":64}}}""")).usage().cachedTokens(), 64);
        pruefe("Cache: DeepSeek prompt_cache_hit_tokens",
               Messages.lies(Json.parse("""
                   {"choices":[{"message":{"content":"x"}}],
                    "usage":{"prompt_tokens":100,"completion_tokens":1,"total_tokens":101,
                             "prompt_cache_hit_tokens":90,"prompt_cache_miss_tokens":10}}""")).usage().cachedTokens(), 90);
        pruefe("Cache: llama.cpp timings.cache_n neben usage",
               Messages.lies(Json.parse("""
                   {"choices":[{"message":{"content":"x"}}],
                    "usage":{"prompt_tokens":100,"completion_tokens":1,"total_tokens":101},
                    "timings":{"cache_n":80,"prompt_n":20}}""")).usage().cachedTokens(), 80);
        // Strom von llama.cpp: timings im letzten Inhaltsstueck, usage danach.
        Stromsammler sam = new Stromsammler();
        sam.nimm(Json.parse("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"},"
                + "\"finish_reason\":\"stop\"}],\"timings\":{\"cache_n\":70}}"));
        sam.nimm(Json.parse("{\"choices\":[],\"usage\":{\"prompt_tokens\":100,"
                + "\"completion_tokens\":1,\"total_tokens\":101}}"));
        pruefe("Cache: Strom, timings vor usage", sam.antwort().usage().cachedTokens(), 70);

        pruefe("finish_reason 'length'",
               Messages.lies(Json.parse(
                   "{\"choices\":[{\"message\":{\"content\":\"x\"},\"finish_reason\":\"length\"}]}"))
                       .finishReason(), FinishReason.LENGTH);
        pruefe("unbekanntes finish_reason wird OTHER",
               FinishReason.von("etwas_neues"), FinishReason.OTHER);
        pruefe("fehlendes finish_reason wird OTHER", FinishReason.von(null), FinishReason.OTHER);

        // Eine Antwort ohne choices ist unbrauchbar und muss werfen: nur so
        // greift die Wiederholung in Retry.
        boolean geworfen = false;
        try { Messages.lies(Json.parse("{\"error\":\"overloaded\"}")); }
        catch (RuntimeException e) { geworfen = true; }
        pruefe("Antwort ohne choices wirft", geworfen, true);

        // ------------------------------------------------ Kennungen
        {
            var l = Messages.mitKennungen(List.of(
                    new Messages.ToolCall(null, "read", "{}"),
                    new Messages.ToolCall("x", "read", "{}"),
                    new Messages.ToolCall("x", "glob", "{}"),
                    new Messages.ToolCall("", "bash", "{}")));
            pruefe("Kennungen: fehlende und doppelte werden aufgefuellt",
                   l.get(0).id() != null && l.get(1).id().equals("x")
                   && !l.get(2).id().equals("x") && !l.get(3).id().isBlank()
                   && l.stream().map(Messages.ToolCall::id).distinct().count() == 4);
            ChatResponse ohne = Messages.lies(Json.parse("{\"choices\":[{\"message\":{\"tool_calls\":["
                    + "{\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}"));
            pruefe("Kennungen: Server ohne id bekommt trotzdem eine",
                   ohne.message().toolCalls().get(0).id() != null);
        }

        // ------------------------------------------- Aufrufe im Text
        {
            var namen = java.util.Set.of("read", "glob", "bash");
            var f = Textaufrufe.erkenne("Ich schaue nach.\n<tool_call>\n{\"name\": \"read\", "
                    + "\"arguments\": {\"pfad\": \"a.py\", \"zeilen\": 50}}\n</tool_call>", namen);
            pruefe("Text: Hermes/Qwen-Marke erkannt",
                   f != null && f.aufrufe().size() == 1 && f.aufrufe().get(0).name().equals("read"));
            pruefe("Text: Argumente als JSON-Zeichenkette, Zahl ohne .0",
                   f == null ? null : f.aufrufe().get(0).argumentsJson(), "{\"pfad\":\"a.py\",\"zeilen\":50}");
            pruefe("Text: der Rest bleibt als Inhalt", f == null ? null : f.rest(), "Ich schaue nach.");

            var zwei = Textaufrufe.erkenne("<tool_call>{\"name\":\"read\",\"arguments\":{\"pfad\":\"a\"}}</tool_call>"
                    + "<tool_call>{\"name\":\"glob\",\"arguments\":{\"muster\":\"*\"}}</tool_call>", namen);
            pruefe("Text: zwei Marken, zwei Aufrufe mit verschiedenen Kennungen",
                   zwei != null && zwei.aufrufe().size() == 2 && zwei.rest() == null
                   && !zwei.aufrufe().get(0).id().equals(zwei.aufrufe().get(1).id()));

            var mistral = Textaufrufe.erkenne("[TOOL_CALLS][{\"name\":\"bash\",\"arguments\":{\"kommando\":\"ls\"}}]", namen);
            pruefe("Text: Mistral [TOOL_CALLS]",
                   mistral != null && mistral.aufrufe().get(0).argumentsJson().equals("{\"kommando\":\"ls\"}"));

            var llama = Textaufrufe.erkenne("```json\n{\"name\": \"glob\", \"parameters\": {\"muster\": \"**/*.py\"}}\n```", namen);
            pruefe("Text: ganzer Inhalt ist ein Aufruf (Llama, im Codeblock)",
                   llama != null && llama.aufrufe().get(0).name().equals("glob"));

            pruefe("Text: JSON mitten in Prosa bleibt Text",
                   Textaufrufe.erkenne("So sieht ein Aufruf aus: {\"name\": \"read\", \"arguments\": {}} -- fertig.", namen),
                   null);
            pruefe("Text: unbekannter Name gilt nicht",
                   Textaufrufe.erkenne("{\"name\": \"loesche_alles\", \"arguments\": {}}", namen), null);
            pruefe("Text: abgeschnittene Marke gilt nicht",
                   Textaufrufe.erkenne("<tool_call>{\"name\": \"read\", \"argum", namen), null);
            pruefe("Text: gewoehnliche Antwort bleibt Antwort",
                   Textaufrufe.erkenne("Der Fehler lag in rabatt().", namen), null);
        }

        System.out.println(fehlgeschlagen == 0
                ? "\nAlle Pruefungen bestanden."
                : "\n" + fehlgeschlagen + " Pruefung(en) fehlgeschlagen.");
        if (fehlgeschlagen > 0) System.exit(1);
    }

    private static String schreibe(Messages.Message m) {
        Json.Writer w = new Json.Writer();
        Messages.schreibe(w, m);
        return w.toString();
    }

    private static void pruefe(String was, Object erhalten, Object erwartet) {
        boolean ok = (erhalten == null) ? erwartet == null : erhalten.equals(erwartet);
        melde(was, ok);
        if (!ok) System.out.println("    erwartet: " + erwartet + "\n    erhalten: " + erhalten);
    }

    private static void pruefe(String was, boolean ok) { melde(was, ok); }

    private static void melde(String was, boolean ok) {
        System.out.printf("%-56s %s%n", was, ok ? "ok" : "FEHLGESCHLAGEN");
        if (!ok) fehlgeschlagen++;
    }
}
