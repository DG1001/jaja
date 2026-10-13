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
        final List<List<String>> werkzeuge = new ArrayList<>();

        Skript(ChatResponse... a) { for (ChatResponse x : a) antworten.add(x); }

        @Override public ChatResponse complete(List<Message> verlauf, List<ToolSpec> w) {
            gesehen.add(List.copyOf(verlauf));
            werkzeuge.add(w == null ? List.of() : w.stream().map(ToolSpec::name).toList());
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

        // ------------------------------------ Abschluss ohne Werkzeugaufruf
        // Gemessen: erster Zug STOP, vier Ausgabetokens, Inhalt "[README]" --
        // und der Lauf galt als fertig, auf einem unberuehrten Verzeichnis.
        {
            Skript s = new Skript(text("[README]", FinishReason.STOP),
                                  ruft("read", "{\"pfad\":\"a.txt\"}"),
                                  text("jetzt wirklich fertig", FinishReason.STOP));
            Agent.Ergebnis e = lauf(s, tmp, 10);
            pruefe("Abschluss ohne Werkzeug gilt nicht sofort",
                   e.status() == Agent.Status.FERTIG && e.werkzeugaufrufe() == 1
                           && "jetzt wirklich fertig".equals(e.abschluss()));
            pruefe("der Anstoss steht im Verlauf",
                   s.gesehen.get(1).toString().contains("kein einziges Werkzeug benutzt"));
        }
        {
            // Eine reine Wissensfrage darf ohne Werkzeug beantwortet werden --
            // aber erst nach der einen Rueckfrage.
            Skript s = new Skript(text("vier", FinishReason.STOP),
                                  text("vier, und dafuer brauche ich nichts", FinishReason.STOP));
            Agent.Ergebnis e = lauf(s, tmp, 10);
            pruefe("nach dem Anstoss wird der Abschluss angenommen",
                   e.status() == Agent.Status.FERTIG && e.werkzeugaufrufe() == 0);
        }
        {
            // Gegenprobe: wer gearbeitet hat, wird nicht angestossen.
            Skript s = new Skript(ruft("read", "{\"pfad\":\"a.txt\"}"),
                                  text("fertig", FinishReason.STOP));
            Skript s2 = s;
            lauf(s, tmp, 10);
            pruefe("nach getaner Arbeit kein Anstoss",
                   !s2.gesehen.get(s2.gesehen.size()-1).toString()
                       .contains("kein einziges Werkzeug benutzt"));
        }

        // ------------------------------------- Pruefung nach Aenderung
        {
            Skript s = new Skript(ruft("write", "{\"pfad\":\"neu.py\",\"inhalt\":\"x = 1\\n\"}"),
                                  text("fertig", FinishReason.STOP),
                                  ruft("bash", "{\"kommando\":\"python3 neu.py\"}"),
                                  text("geprueft und fertig", FinishReason.STOP));
            Agent.Ergebnis e = lauf(s, tmp, 10);
            String gesehen = s.gesehen.get(2).toString();
            pruefe("Aenderung ohne Ausfuehrung: Anstoss vor dem Abschluss",
                   gesehen.contains("neu.py geaendert und danach nichts mehr ausgefuehrt"));
            pruefe("... danach wird der Abschluss angenommen",
                   e.status() == Agent.Status.FERTIG && "geprueft und fertig".equals(e.abschluss()));
        }
        {
            // Mit erkennbarem Testbefehl nennt der Anstoss ihn woertlich.
            Path pt = Files.createTempDirectory("jaja-testbefehl");
            Files.writeString(pt.resolve("Makefile"), "test:\n\ttrue\n");
            Skript s = new Skript(ruft("write", "{\"pfad\":\"neu.c\",\"inhalt\":\"int x;\\n\"}"),
                                  text("fertig", FinishReason.STOP),
                                  text("gut", FinishReason.STOP));
            lauf(s, pt, 10);
            pruefe("Anstoss nennt den erkannten Testbefehl",
                   s.gesehen.get(2).toString().contains("die Tests mit make test"));
        }
        {
            Skript s = new Skript(ruft("write", "{\"pfad\":\"neu2.py\",\"inhalt\":\"x = 1\\n\"}"),
                                  ruft("bash", "{\"kommando\":\"python3 neu2.py\"}"),
                                  text("fertig", FinishReason.STOP));
            Agent.Ergebnis e = lauf(s, tmp, 10);
            pruefe("bash nach der Aenderung: kein Anstoss", e.status() == Agent.Status.FERTIG
                   && s.gesehen.size() == 3);
        }
        {
            Skript s = new Skript(ruft("write", "{\"pfad\":\"LIESMICH.md\",\"inhalt\":\"# x\\n\"}"),
                                  text("fertig", FinishReason.STOP));
            Agent.Ergebnis e = lauf(s, tmp, 10);
            pruefe("nur Doku geaendert: kein Anstoss",
                   e.status() == Agent.Status.FERTIG && s.gesehen.size() == 2);
        }
        {
            // Einmal je Auftrag: wer nach dem Anstoss begruendet, warum es
            // nichts auszufuehren gibt, wird nicht erneut gedraengt.
            Skript s = new Skript(ruft("write", "{\"pfad\":\"neu3.py\",\"inhalt\":\"x = 1\\n\"}"),
                                  text("fertig", FinishReason.STOP),
                                  text("laesst sich hier nicht ausfuehren", FinishReason.STOP));
            Agent.Ergebnis e = lauf(s, tmp, 10);
            pruefe("Anstoss hoechstens einmal", e.status() == Agent.Status.FERTIG
                   && s.gesehen.size() == 3
                   && "laesst sich hier nicht ausfuehren".equals(e.abschluss()));
        }

        // ------------------------------------- Kreisen mit gleichem Ergebnis
        {
            Skript s = new Skript(ruft("read", "{\"pfad\":\"a.txt\"}"),
                                  ruft("read", "{\"pfad\":\"a.txt\"}"),
                                  ruft("read", "{\"pfad\":\"a.txt\"}"),
                                  text("fertig", FinishReason.STOP));
            lauf(s, tmp, 10);
            pruefe("dreimal derselbe Aufruf, dasselbe Ergebnis: Anstoss",
                   s.gesehen.get(3).toString().contains("3-mal aufgerufen")
                   && !s.gesehen.get(2).toString().contains("-mal aufgerufen"));
        }
        {
            // Laufzeit in ms aendert sich jedes Mal -- darf den Kreis nicht verdecken.
            Skript s = new Skript(ruft("bash", "{\"kommando\":\"echo gleich\"}"),
                                  ruft("bash", "{\"kommando\":\"echo gleich\"}"),
                                  ruft("bash", "{\"kommando\":\"echo gleich\"}"),
                                  text("fertig", FinishReason.STOP));
            lauf(s, tmp, 10);
            pruefe("bash mit gleicher Ausgabe: Laufzeit verdeckt den Kreis nicht",
                   s.gesehen.get(3).toString().contains("3-mal aufgerufen"));
        }
        {
            // Gegenprobe: derselbe Befehl mit wechselndem Ergebnis ist Arbeit.
            Skript s = new Skript(ruft("bash", "{\"kommando\":\"echo x >> z.txt; wc -l < z.txt\"}"),
                                  ruft("bash", "{\"kommando\":\"echo x >> z.txt; wc -l < z.txt\"}"),
                                  ruft("bash", "{\"kommando\":\"echo x >> z.txt; wc -l < z.txt\"}"),
                                  text("fertig", FinishReason.STOP));
            lauf(s, tmp, 10);
            pruefe("gleicher Befehl, anderes Ergebnis: kein Anstoss",
                   !s.gesehen.get(3).toString().contains("-mal aufgerufen"));
        }

        // --------------------------------------- Aufruf als Text
        {
            Skript s = new Skript(text("<tool_call>{\"name\":\"read\",\"arguments\":{\"pfad\":\"a.txt\"}}</tool_call>",
                                       FinishReason.STOP),
                                  text("fertig", FinishReason.STOP));
            Agent.Ergebnis e = lauf(s, tmp, 10);
            pruefe("Aufruf im Text wird ausgefuehrt statt als Antwort genommen",
                   e.status() == Agent.Status.FERTIG && e.werkzeugaufrufe() == 1
                   && s.gesehen.get(1).toString().contains("inhalt"));
        }

        // ------------------------------------------------- Unteragent
        {
            Usage hundert = new Usage(100, 10, 110);
            Skript s = new Skript(
                    new ChatResponse(new AssistantMessage(null, null, List.of(new ToolCall("e1", "erkunde",
                            "{\"frage\":\"wo steht der Inhalt?\"}"))), FinishReason.TOOL_CALLS, hundert),
                    new ChatResponse(new AssistantMessage(null, null, List.of(new ToolCall("u1", "read",
                            "{\"pfad\":\"a.txt\"}"))), FinishReason.TOOL_CALLS, hundert),
                    new ChatResponse(new AssistantMessage("a.txt ist relevant", null, List.of()),
                            FinishReason.STOP, hundert),
                    new ChatResponse(new AssistantMessage("fertig", null, List.of()), FinishReason.STOP, hundert));
            ToolRegistry reg = ToolRegistry.vorgabe();
            reg.fuegeHinzu(new ErkundeTool(s, ContextBudget.vorgabe(65536, 16384), Beobachter.STILL));
            Agent a = new Agent(s, reg, new Workspace(tmp), ContextBudget.vorgabe(65536, 16384), 10, false);
            Agent.Ergebnis e = a.lauf("sys", "Finde den Inhalt.");
            String haupt = s.gesehen.get(3).toString();
            pruefe("erkunde: Antwort des Unteragenten kommt als Werkzeugergebnis",
                   e.status() == Agent.Status.FERTIG && haupt.contains("a.txt ist relevant")
                   && haupt.contains("erkundet in 2 Zuegen"));
            pruefe("erkunde: seine Leseergebnisse bleiben in seinem Kontext",
                   s.gesehen.get(2).toString().contains("1\tinhalt") && !haupt.contains("1\tinhalt"));
            pruefe("erkunde: Unteragent hat nur lesende Werkzeuge",
                   s.werkzeuge.get(1).equals(List.of("glob", "grep", "read"))
                   && s.werkzeuge.get(0).contains("erkunde"));
            pruefe("erkunde: seine Token zaehlen zum Lauf (" + a.eingabeTokens() + ")",
                   a.eingabeTokens() == 400 && a.ausgabeTokens() == 40);
        }

        // ------------------------------------------------ Best-of-N
        {
            Path pv = Files.createTempDirectory("jaja-versuche-probe");
            Files.writeString(pv.resolve(".jaja.json"),
                    "{\"test\": \"python3 -c 'import loesung; assert loesung.f() == 2' && echo '1 passed'\"}");
            Files.createDirectories(pv.resolve(".venv/bin"));
            Files.writeString(pv.resolve(".venv/bin/marke"), "bleibt");
            Files.writeString(pv.resolve("vorher.txt"), "unveraendert\n");
            ChatEndpunkt je = (verlauf, w) -> {
                String alles = verlauf.toString();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("/v(\\d+)").matcher(alles);
                int k = m.find() ? Integer.parseInt(m.group(1)) : 0;
                if (!alles.contains("ToolMessage"))
                    return ruft("write", "{\"pfad\":\"loesung.py\",\"inhalt\":\"def f():\\n    return " + k + "\\n\"}");
                return text("fertig " + k, FinishReason.STOP);
            };
            var ws = new Workspace(pv);
            var aus = Versuche.lauf(ws, 3, "sys", "Schreib f.", (w, k) ->
                    new Agent(je, ToolRegistry.vorgabe(), w, ContextBudget.vorgabe(65536, 16384), 10, false),
                    m -> {});
            pruefe("Versuche: der mit gruenen Tests gewinnt",
                   aus.sieger().nummer() == 2 && Boolean.TRUE.equals(aus.sieger().testsGruen())
                   && aus.sieger().bestanden() == 1);
            pruefe("Versuche: nur seine Aenderung kommt zurueck",
                   Files.readString(pv.resolve("loesung.py")).contains("return 2")
                   && aus.geaendert().equals(List.of("loesung.py")));
            pruefe("Versuche: Unveraendertes bleibt, die echte .venv ueberlebt das Aufraeumen",
                   Files.readString(pv.resolve("vorher.txt")).equals("unveraendert\n")
                   && Files.readString(pv.resolve(".venv/bin/marke")).equals("bleibt"));
            pruefe("Versuche: alle drei sind gelaufen",
                   aus.alle().size() == 3 && aus.alle().stream().allMatch(v -> v.ergebnis().erfolgreich()));
        }
        {
            // Zurueckspielen: neu, geaendert, geloescht -- und .git bleibt, wie es ist.
            Path sieger = Files.createTempDirectory("jaja-sieger"), ziel = Files.createTempDirectory("jaja-ziel");
            Files.writeString(sieger.resolve("neu.txt"), "n");
            Files.writeString(sieger.resolve("geaendert.txt"), "neu");
            Files.writeString(ziel.resolve("geaendert.txt"), "alt");
            Files.writeString(ziel.resolve("weg.txt"), "w");
            Files.createDirectories(ziel.resolve(".git"));
            Files.writeString(ziel.resolve(".git/HEAD"), "ref");
            var g = Versuche.spieleZurueck(sieger, ziel);
            pruefe("Zurueckspielen: neu, geaendert, geloescht; .git unberuehrt",
                   g.equals(List.of("geaendert.txt", "neu.txt", "weg.txt"))
                   && Files.readString(ziel.resolve("geaendert.txt")).equals("neu")
                   && !Files.exists(ziel.resolve("weg.txt")) && Files.exists(ziel.resolve(".git/HEAD")));
        }

        // ----------------------------------------------------- Abbruch
        // Ctrl-C darf nicht warten, bis die laufende Anfrage von selbst endet:
        // lokal dauert ein Zug Minuten.
        {
            ChatEndpunkt haengt = (verlauf, w) -> {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("unterbrochen");
                }
                return text("zu spaet", FinishReason.STOP);
            };
            Agent a = new Agent(haengt, ToolRegistry.vorgabe(), new Workspace(tmp),
                                ContextBudget.vorgabe(65536, 16384), 10, false);
            var f = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> a.lauf("sys", "Tu etwas."));
            Thread.sleep(300);
            long t0 = System.nanoTime();
            a.brichAb();
            Agent.Ergebnis e = f.get(5, java.util.concurrent.TimeUnit.SECONDS);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            pruefe("Abbruch unterbricht die laufende Anfrage (" + ms + " ms)",
                   e.status() == Agent.Status.ABGEBROCHEN && ms < 2_000);
        }
        {
            // Und ein laufendes bash: Abbruch statt Zeitgrenze abwarten.
            Skript s = new Skript(ruft("bash", "{\"kommando\":\"sleep 30\"}"),
                                  text("fertig", FinishReason.STOP));
            Agent a = new Agent(s, ToolRegistry.vorgabe(), new Workspace(tmp),
                                ContextBudget.vorgabe(65536, 16384), 10, false);
            var f = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> a.lauf("sys", "Tu etwas."));
            Thread.sleep(800);
            long t0 = System.nanoTime();
            a.brichAb();
            Agent.Ergebnis e = f.get(10, java.util.concurrent.TimeUnit.SECONDS);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            pruefe("Abbruch beendet ein laufendes bash (" + ms + " ms)",
                   e.status() == Agent.Status.ABGEBROCHEN && ms < 5_000);
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
