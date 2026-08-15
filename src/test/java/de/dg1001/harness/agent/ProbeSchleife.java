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

        // ------------------------------------------------------------ Freigabe
        // Eine Ablehnung muss das Werkzeug wirklich verhindern -- nicht bloss
        // im Nachhinein bemaengeln. Geprueft wird am Dateisystem, nicht an der
        // Rueckmeldung: nur die Datei beweist, dass nichts gelaufen ist.
        {
            Path marke = tmp.resolve("darf-nicht-entstehen.txt");
            Files.deleteIfExists(marke);
            ChatResponse ruftBash = new ChatResponse(
                    new AssistantMessage(null, null, List.of(new ToolCall("b1", "bash",
                            "{\"kommando\":\"touch darf-nicht-entstehen.txt\"}"))),
                    FinishReason.TOOL_CALLS, Usage.LEER);
            Skript s = new Skript(ruftBash, text("na gut", FinishReason.STOP));

            Agent a = new Agent(s, ToolRegistry.vorgabe(), new Workspace(tmp),
                                ContextBudget.vorgabe(65536, 16384), 10, false);
            a.mitFreigabe(tc -> tc.name().equals("bash") ? "Der Nutzer hat abgelehnt." : null);
            Agent.Ergebnis e = a.lauf("sys", "tu was");

            pruefe("abgelehntes bash wird nicht ausgefuehrt", !Files.exists(marke));
            pruefe("Lauf geht nach einer Ablehnung weiter",
                   e.status() == Agent.Status.FERTIG);
            pruefe("das Modell erfaehrt den Grund",
                   s.gesehen.get(1).toString().contains("Der Nutzer hat abgelehnt."));
        }
        {
            // Gegenprobe: ohne Ablehnung laeuft dasselbe Kommando durch.
            Path marke = tmp.resolve("darf-entstehen.txt");
            Files.deleteIfExists(marke);
            ChatResponse ruftBash = new ChatResponse(
                    new AssistantMessage(null, null, List.of(new ToolCall("b1", "bash",
                            "{\"kommando\":\"touch darf-entstehen.txt\"}"))),
                    FinishReason.TOOL_CALLS, Usage.LEER);
            lauf(new Skript(ruftBash, text("fertig", FinishReason.STOP)), tmp, 10);
            pruefe("ohne Ablehnung laeuft es durch", Files.exists(marke));
        }
        {
            // Die Freigabe darf nur fragen, wonach sie gefragt wird: read muss
            // durchlaufen, auch wenn bash abgelehnt wird.
            Skript s = new Skript(ruft("read", "{\"pfad\":\"a.txt\"}"),
                                  text("fertig", FinishReason.STOP));
            Agent a = new Agent(s, ToolRegistry.vorgabe(), new Workspace(tmp),
                                ContextBudget.vorgabe(65536, 16384), 10, false);
            a.mitFreigabe(tc -> tc.name().equals("bash") ? "abgelehnt" : null);
            a.lauf("sys", "lies mal");
            pruefe("nur bash wird gefragt, read laeuft",
                   s.gesehen.get(1).toString().contains("inhalt"));
        }

        // ------------------------------------------------ Schleifenerkennung
        // Kalibriert an einem echten Lauf: zehn Fehlschlaege in Folge, 80 Zuege,
        // Zuglimit. Erfolgreiche Laeufe derselben Modelle kamen auf hoechstens
        // fuenf. Die Schwelle liegt bei sechs.
        {
            ChatResponse faellt = ruft("read", "{\"pfad\":\"gibtsnicht.txt\"}");
            ChatResponse[] folge = new ChatResponse[7];
            for (int i = 0; i < 6; i++) folge[i] = faellt;
            folge[6] = text("fertig", FinishReason.STOP);
            Skript s = new Skript(folge);
            Agent.Ergebnis e = lauf(s, tmp, 20);

            pruefe("sechs Fehlschlaege in Folge werden angestossen",
                   e.status() == Agent.Status.FERTIG);
            // Die letzte gesehene Anfrage muss den Anstoss tragen.
            String letzte = s.gesehen.get(s.gesehen.size() - 1).toString();
            pruefe("Schleifenanstoss steht im Verlauf",
                   letzte.contains("alle fehlgeschlagen")
                           && letzte.contains("anderen Ansatz"));
        }
        {
            // Gegenprobe: fuenf Fehlschlaege sind noch keine Schleife.
            ChatResponse faellt = ruft("read", "{\"pfad\":\"gibtsnicht.txt\"}");
            Skript s = new Skript(faellt, faellt, faellt, faellt, faellt,
                                  text("fertig", FinishReason.STOP));
            lauf(s, tmp, 20);
            String letzte = s.gesehen.get(s.gesehen.size() - 1).toString();
            pruefe("fuenf Fehlschlaege loesen noch nichts aus",
                   !letzte.contains("alle fehlgeschlagen"));
        }
        {
            // Ein Erfolg dazwischen setzt den Zaehler zurueck -- sonst wuerde
            // jeder lange Lauf mit verstreuten Fehlern irgendwann angestossen.
            ChatResponse faellt = ruft("read", "{\"pfad\":\"gibtsnicht.txt\"}");
            ChatResponse klappt = ruft("read", "{\"pfad\":\"a.txt\"}");
            Skript s = new Skript(faellt, faellt, faellt, klappt,
                                  faellt, faellt, faellt,
                                  text("fertig", FinishReason.STOP));
            lauf(s, tmp, 20);
            String letzte = s.gesehen.get(s.gesehen.size() - 1).toString();
            pruefe("ein Erfolg dazwischen setzt die Folge zurueck",
                   !letzte.contains("alle fehlgeschlagen"));
        }

        // ------------------------------------------------------------ Abgleich
        {
            Skript s = new Skript(ruft("read", "{\"pfad\":\"a.txt\"}"),
                                  text("bin fertig", FinishReason.STOP),
                                  text("alles geprueft", FinishReason.STOP));
            Agent a = new Agent(s, ToolRegistry.vorgabe(), new Workspace(tmp),
                                ContextBudget.vorgabe(65536, 16384), 10, false)
                          .mitAbgleich(true);
            Agent.Ergebnis e = a.lauf("sys", "tu was");

            pruefe("Abgleich laesst den ersten Abschluss nicht gelten",
                   e.status() == Agent.Status.FERTIG
                           && "alles geprueft".equals(e.abschluss()));
            String nachfrage = s.gesehen.get(2).toString();
            pruefe("Abgleich fragt die Aufgabenstellung ab",
                   nachfrage.contains("Satz fuer Satz")
                           && nachfrage.contains("Fliesstext"));
        }
        {
            // Nur einmal: der zweite Abschluss muss durchgehen, sonst laeuft
            // der Agent bis zum Zuglimit im Kreis.
            Skript s = new Skript(text("fertig", FinishReason.STOP),
                                  text("wirklich fertig", FinishReason.STOP));
            Agent a = new Agent(s, ToolRegistry.vorgabe(), new Workspace(tmp),
                                ContextBudget.vorgabe(65536, 16384), 10, false)
                          .mitAbgleich(true);
            Agent.Ergebnis e = a.lauf("sys", "tu was");
            pruefe("Abgleich fragt genau einmal nach",
                   e.status() == Agent.Status.FERTIG
                           && "wirklich fertig".equals(e.abschluss()));
        }
        {
            // Vorgabe aus: ohne Schalter aendert sich nichts.
            Skript s = new Skript(text("fertig", FinishReason.STOP));
            Agent.Ergebnis e = lauf(s, tmp, 10);
            pruefe("ohne --abgleich bleibt der erste Abschluss gueltig",
                   e.status() == Agent.Status.FERTIG && "fertig".equals(e.abschluss()));
        }

        // ------------------------------------------- Kurzfassung im Protokoll
        {
            de.dg1001.harness.wire.Messages.ToolCall tc =
                new de.dg1001.harness.wire.Messages.ToolCall("i", "bash",
                    "{\"kommando\":\"pytest -q\"}");
            pruefe("Kurzfassung zieht das Kommando heraus", "pytest -q".equals(tc.kurz()));

            var lang = new de.dg1001.harness.wire.Messages.ToolCall("i", "write",
                    "{\"pfad\":\"" + "a".repeat(400) + "\"}");
            pruefe("Kurzfassung ist gedeckelt", lang.kurz().length() <= 201
                   && lang.kurz().endsWith("\u2026"));

            var kaputt = new de.dg1001.harness.wire.Messages.ToolCall("i", "bash", "{kein json");
            pruefe("kaputtes JSON stuerzt die Kurzfassung nicht ab",
                   kaputt.kurz().contains("kein json"));

            var mehrzeilig = new de.dg1001.harness.wire.Messages.ToolCall("i", "bash",
                    "{\"kommando\":\"eins\\nzwei\"}");
            pruefe("Zeilenumbruch wird ersetzt, nicht durchgereicht",
                   !mehrzeilig.kurz().contains("\n"));
        }

        // --------------------------------- Hinweis beim Verlassen des Bereichs
        // Gebaut nach einem Lauf, der ueber Nacht in einem fremden Projekt
        // geschrieben hat und dessen Protokoll das nicht festhielt.
        {
            Workspace w = new Workspace(tmp);
            pruefe("fremdes Projekt wird gemeldet",
                   w.verlaesstBereich("cd /home/jemand/anderes-projekt && ls") != null);
            // Der Arbeitsbereich liegt unter /tmp/..., also fuehrt ../.. auf /
            // und von dort in ein fremdes Projekt -- kein Systempfad.
            pruefe("relativer Ausbruch wird gemeldet",
                   w.verlaesstBereich("cp bestand.py ../../home/jemand/projekt/") != null);
            pruefe("Ausbruch nach /etc gilt als Systempfad, nicht als Fund",
                   w.verlaesstBereich("cat ../../../../etc/hosts") == null);
            pruefe("Arbeit im Bereich meldet nichts",
                   w.verlaesstBereich("./.venv/bin/python -m pytest tests/") == null);
            pruefe("Systempfade melden nichts",
                   w.verlaesstBereich("/usr/bin/env python3 -c 'print(1)'") == null);
            pruefe("/tmp meldet nichts",
                   w.verlaesstBereich("cp x /tmp/y") == null);
            pruefe("Kommando ohne Pfad meldet nichts",
                   w.verlaesstBereich("pytest -q") == null);
            pruefe("leeres Kommando stuerzt nicht ab",
                   w.verlaesstBereich("") == null && w.verlaesstBereich(null) == null);
        }
        {
            // Der Hinweis muss auch wirklich beim Beobachter ankommen.
            java.util.List<String> hinweise = new ArrayList<>();
            Beobachter b = new Beobachter() {
                @Override public void hinweis(String s) { hinweise.add(s); }
            };
            ChatResponse raus = new ChatResponse(
                new AssistantMessage(null, null, List.of(new ToolCall("b1", "bash",
                    "{\"kommando\":\"ls /home/jemand/anderes-projekt\"}"))),
                FinishReason.TOOL_CALLS, Usage.LEER);
            Skript s = new Skript(raus, text("fertig", FinishReason.STOP));
            new Agent(s, ToolRegistry.vorgabe(), new Workspace(tmp),
                      ContextBudget.vorgabe(65536, 16384), 10, b).lauf("sys", "tu was");
            pruefe("Beobachter erfaehrt vom Hinausgreifen",
                   hinweise.stream().anyMatch(h -> h.startsWith("ausserhalb des Arbeitsbereichs")));
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
