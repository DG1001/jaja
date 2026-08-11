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
