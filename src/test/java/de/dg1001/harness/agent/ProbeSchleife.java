package de.dg1001.harness.agent;

import de.dg1001.harness.tools.ToolRegistry;
import de.dg1001.harness.wire.ChatEndpunkt;
import de.dg1001.harness.wire.Messages.AssistantMessage;
import de.dg1001.harness.wire.Messages.ChatResponse;
import de.dg1001.harness.wire.Messages.FinishReason;
import de.dg1001.harness.wire.Messages.Message;
import de.dg1001.harness.wire.Messages.ToolCall;
import de.dg1001.harness.wire.Messages.ToolSpec;
import de.dg1001.harness.wire.Messages.Usage;
import de.dg1001.harness.ws.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Prueft die Agentenschleife gegen einen erfundenen Endpunkt.
 *
 * <p>Genau dafuer gibt es {@link ChatEndpunkt} als Schnittstelle: hier laesst
 * sich jede Antwortfolge vorgeben, auch solche, die ein echtes Modell nur
 * selten und nur unter Last liefert.
 *
 * <p>Der wichtigste Fall ist der entartete Zug — Ausgabegrenze erreicht, kein
 * Werkzeugaufruf. Beobachtet wurde er einmal, in einem 17-minuetigen Lauf, der
 * mit Rueckgabewert 0 und leerem Verzeichnis endete. Die Abwehr dagegen ist die
 * Daseinsberechtigung dieses Harness; sie darf nicht davon abhaengen, dass
 * jemand einen Benchmark ueber ein 90-GB-Modell laufen laesst, um sie zu pruefen.
 */
public final class ProbeSchleife {

    private static int fehlgeschlagen = 0;

    /** Endpunkt, der eine vorgegebene Folge von Antworten abspult. */
    private static final class Skript implements ChatEndpunkt {
        private final Deque<ChatResponse> antworten = new ArrayDeque<>();
        final List<List<Message>> gesehen = new ArrayList<>();

        Skript(ChatResponse... a) { for (ChatResponse x : a) antworten.add(x); }

        @Override public ChatResponse complete(List<Message> verlauf, List<ToolSpec> w) {
            gesehen.add(List.copyOf(verlauf));
            if (antworten.isEmpty())
                throw new IllegalStateException("Skript erschoepft — Schleife lief zu oft");
            return antworten.removeFirst();
        }
    }

    private static ChatResponse text(String s, FinishReason f) {
        return new ChatResponse(new AssistantMessage(s, null, List.of()), f, Usage.LEER);
    }

    private static ChatResponse ruft(String werkzeug, String args) {
        return new ChatResponse(
                new AssistantMessage(null, null,
                        List.of(new ToolCall("id1", werkzeug, args))),
                FinishReason.TOOL_CALLS, Usage.LEER);
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("jaja-schleife");
        Files.writeString(tmp.resolve("a.txt"), "inhalt\n");

        // -------------------------------------------------- normaler Abschluss
        {
            Skript s = new Skript(ruft("read", "{\"pfad\":\"a.txt\"}"),
                                  text("fertig", FinishReason.STOP));
            Agent.Ergebnis e = lauf(s, tmp, 10);
            pruefe("Abschluss nach Werkzeugaufruf", e.status() == Agent.Status.FERTIG);
            pruefe("Werkzeugaufrufe gezaehlt", e.werkzeugaufrufe() == 1);
            pruefe("Schlusstext durchgereicht", "fertig".equals(e.abschluss()));
        }

        // ------------------------------------------------- entarteter Zug (1x)
        {
            Skript s = new Skript(text("ich denke noch...", FinishReason.LENGTH),
                                  ruft("read", "{\"pfad\":\"a.txt\"}"),
                                  text("fertig", FinishReason.STOP));
            Agent.Ergebnis e = lauf(s, tmp, 10);
            pruefe("Ausgabegrenze ohne Werkzeug gilt nicht als fertig",
                   e.status() == Agent.Status.FERTIG && e.werkzeugaufrufe() == 1);

            // Der Anstoss muss tatsaechlich im Verlauf stehen, nicht nur gezaehlt
            // worden sein -- sonst sieht das Modell in der naechsten Anfrage
            // dieselbe Lage wie zuvor und verplant sich erneut.
            String zweiteAnfrage = s.gesehen.get(1).toString();
            pruefe("Anstoss steht im Verlauf",
                   zweiteAnfrage.contains("Ausgabegrenze erreicht")
                           && zweiteAnfrage.contains("rufe jetzt ein Werkzeug auf"));
        }

        // ------------------------------------------- entarteter Zug (dreimal)
        {
            Skript s = new Skript(text("...", FinishReason.LENGTH),
                                  text("...", FinishReason.LENGTH),
                                  text("...", FinishReason.LENGTH));
            Agent.Ergebnis e = lauf(s, tmp, 10);
            pruefe("dreimal entartet endet als STECKEN",
                   e.status() == Agent.Status.STECKEN);
            pruefe("STECKEN nennt einen brauchbaren Grund",
                   e.hinweis() != null && e.hinweis().contains("Ausgabegrenze"));
        }

        // -------- Abgrenzung: LENGTH MIT Werkzeugaufruf ist kein entarteter Zug
        {
            ChatResponse abgeschnittenAberTaetig = new ChatResponse(
                    new AssistantMessage(null, null,
                            List.of(new ToolCall("id1", "read", "{\"pfad\":\"a.txt\"}"))),
                    FinishReason.LENGTH, Usage.LEER);
            Skript s = new Skript(abgeschnittenAberTaetig,
                                  text("fertig", FinishReason.STOP));
            Agent.Ergebnis e = lauf(s, tmp, 10);
            pruefe("Ausgabegrenze MIT Werkzeugaufruf laeuft normal weiter",
                   e.status() == Agent.Status.FERTIG && e.werkzeugaufrufe() == 1);
        }

        // ------------------------------------------------------------ Zuglimit
        {
            Skript s = new Skript(ruft("read", "{\"pfad\":\"a.txt\"}"),
                                  ruft("read", "{\"pfad\":\"a.txt\"}"),
                                  ruft("read", "{\"pfad\":\"a.txt\"}"));
            Agent.Ergebnis e = lauf(s, tmp, 2);
            pruefe("Zuglimit greift", e.status() == Agent.Status.ZUGLIMIT);
            pruefe("Zuglimit verbraucht nicht mehr Zuege als erlaubt", e.zuege() == 2);
        }

        // ------------------------------------------------- Fehler des Endpunkts
        {
            ChatEndpunkt kaputt = (v, w) -> { throw new RuntimeException("HTTP 400: nope"); };
            Agent.Ergebnis e = lauf(kaputt, tmp, 10);
            pruefe("Endpunktfehler beendet den Lauf sauber",
                   e.status() == Agent.Status.FEHLER && e.hinweis().contains("HTTP 400"));
        }

        // ------------------------------------- Ergebnisse in Aufrufreihenfolge
        // Die Reihenfolge entscheidet ueber den Praefix-Cache: zwei sonst
        // gleiche Laeufe muessen denselben Verlauf erzeugen.
        {
            ChatResponse dreiAufrufe = new ChatResponse(
                    new AssistantMessage(null, null, List.of(
                            new ToolCall("i1", "read", "{\"pfad\":\"a.txt\"}"),
                            new ToolCall("i2", "bash", "{\"kommando\":\"sleep 1; echo zweiter\"}"),
                            new ToolCall("i3", "bash", "{\"kommando\":\"echo dritter\"}"))),
                    FinishReason.TOOL_CALLS, Usage.LEER);
            Skript s = new Skript(dreiAufrufe, text("fertig", FinishReason.STOP));
            lauf(s, tmp, 10);

            List<Message> zweite = s.gesehen.get(1);
            String ids = zweite.stream()
                    .filter(m -> m instanceof de.dg1001.harness.wire.Messages.ToolMessage)
                    .map(m -> ((de.dg1001.harness.wire.Messages.ToolMessage) m).toolCallId())
                    .reduce("", (x, y) -> x + y);
            // i2 schlaeft eine Sekunde und ist damit sicher zuletzt fertig --
            // steht aber trotzdem an zweiter Stelle.
            pruefe("Werkzeugergebnisse in Aufruf-, nicht Fertigstellungsreihenfolge",
                   "i1i2i3".equals(ids));
        }

        System.out.println(fehlgeschlagen == 0
                ? "\nAlle Pruefungen bestanden."
                : "\n" + fehlgeschlagen + " Pruefung(en) fehlgeschlagen.");
        if (fehlgeschlagen > 0) System.exit(1);
    }

    private static Agent.Ergebnis lauf(ChatEndpunkt e, Path tmp, int maxZuege)
            throws java.io.IOException {
        Agent a = new Agent(e, ToolRegistry.vorgabe(), new Workspace(tmp),
                            ContextBudget.vorgabe(65536, 16384), maxZuege, false);
        return a.lauf("Du bist ein Testagent.", "Tu etwas.");
    }

    private static void pruefe(String was, boolean ok) {
        System.out.printf("%-58s %s%n", was, ok ? "ok" : "FEHLGESCHLAGEN");
        if (!ok) fehlgeschlagen++;
    }
}
