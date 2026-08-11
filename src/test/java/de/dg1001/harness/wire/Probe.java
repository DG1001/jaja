package de.dg1001.harness.wire;

import de.dg1001.harness.wire.Messages.*;

import java.time.Duration;
import java.util.List;

/** Prueft ChatClient und Serialisierung gegen einen echten lokalen Server. */
public final class Probe {

    public static void main(String[] args) {
        String baseUrl = args.length > 0 ? args[0] : "http://127.0.0.1:8888/v1";
        String modell  = args.length > 1 ? args[1] : "deepseek-v4-flash";

        ChatClient c = new ChatClient(baseUrl, modell, "unused", 512,
                                      Duration.ofMinutes(20));

        // ---------------------------------------------------------- 1: Text
        System.out.println("== 1. einfache Antwort ==");
        ChatResponse a1 = c.complete(
                List.of(new SystemMessage("Antworte knapp."),
                        new UserMessage("Sag nur: ok")),
                List.of());
        System.out.println("  content : " + a1.message().content());
        System.out.println("  denken  : " + (a1.message().reasoningContent() == null
                ? "(getrennt geliefert: nein)"
                : a1.message().reasoningContent().length() + " Zeichen"));
        System.out.println("  grund   : " + a1.finishReason());
        System.out.println("  tokens  : " + a1.usage().promptTokens()
                + " ein / " + a1.usage().completionTokens() + " aus");

        // ------------------------------------------------- 2: Werkzeugaufruf
        System.out.println("\n== 2. Werkzeugaufruf ==");
        ToolSpec wetter = new ToolSpec("get_weather",
                "Aktuelles Wetter fuer einen Ort. Nimm das, wenn nach Wetter gefragt wird.",
                """
                {"type":"object",
                 "properties":{"ort":{"type":"string","description":"Stadtname"}},
                 "required":["ort"]}""");

        ChatResponse a2 = c.complete(
                List.of(new SystemMessage("Du hast Werkzeuge. Nutze sie."),
                        new UserMessage("Wie ist das Wetter in Hamburg?")),
                List.of(wetter));
        System.out.println("  grund   : " + a2.finishReason());
        System.out.println("  aufrufe : " + a2.message().toolCalls().size());
        for (ToolCall tc : a2.message().toolCalls())
            System.out.println("    " + tc.name() + " " + tc.argumentsJson()
                    + "  (id " + tc.id() + ")");

        if (!a2.message().hatWerkzeugaufrufe()) {
            System.out.println("  KEIN Werkzeugaufruf -- Rueckweg nicht pruefbar.");
            return;
        }

        // --------------------------------- 3: Rueckweg mit Werkzeugergebnis
        System.out.println("\n== 3. Ergebnis zurueckschicken ==");
        ToolCall tc = a2.message().toolCalls().get(0);
        ChatResponse a3 = c.complete(
                List.of(new SystemMessage("Du hast Werkzeuge. Nutze sie."),
                        new UserMessage("Wie ist das Wetter in Hamburg?"),
                        a2.message(),                       // Assistentenzug mit tool_calls
                        new ToolMessage(tc.id(), "17 Grad, bewoelkt")),
                List.of(wetter));
        System.out.println("  content : " + a3.message().content());
        System.out.println("  grund   : " + a3.finishReason());

        // --------------------------------------------- 4: Maskierung pruefen
        System.out.println("\n== 4. Maskierung ==");
        String heikel = "Zeile1\nTab\tAnfuehrung\"Backslash\\Umlaut-ae Emoji-😀";
        String json = new Json.Writer().objektAuf().feld("t").text(heikel).objektZu().toString();
        Object zurueck = Json.parse(json);
        boolean gleich = heikel.equals(Json.str(Json.feld(zurueck, "t")));
        System.out.println("  Rundlauf identisch: " + gleich);
        if (!gleich) throw new AssertionError("Maskierung fehlerhaft");
    }
}
