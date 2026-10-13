package de.dg1001.harness;

import de.dg1001.harness.agent.Agent;
import de.dg1001.harness.agent.ContextBudget;
import de.dg1001.harness.agent.Systemprompt;
import de.dg1001.harness.karte.Indexer;
import de.dg1001.harness.karte.Karte;
import de.dg1001.harness.tools.ToolRegistry;
import de.dg1001.harness.tui.Anzeige;
import de.dg1001.harness.tui.Sitzung;
import de.dg1001.harness.tui.Terminal;
import de.dg1001.harness.wire.ChatClient;
import de.dg1001.harness.wire.ChatEndpunkt;
import de.dg1001.harness.wire.Retry;
import de.dg1001.harness.ws.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public final class Main {

    /**
     * Knapp halten. Jedes Token hier zahlt man bei JEDEM Zug — bei sechzig
     * Zuegen wird aus einem ueberfluessigen Absatz schnell ein Prozent des
     * Kontextfensters, und der Praefix-Cache traegt ihn zwar guenstig, aber
     * nicht umsonst.
     *
     * <p>Der letzte Absatz ist keine Hoeflichkeit, sondern eine Gegenmassnahme:
     * ein gemessenes Modell verbrachte einen ganzen Zug (16.384 Tokens) damit,
     * die gesamte Umsetzung vorab durchzudenken, und rief nie ein Werkzeug auf.
     */
    private static final String SYSTEM_PROMPT = """
            Du bist ein Programmierassistent und arbeitest in einem Projektverzeichnis.

            Arbeite selbststaendig: lies, aendere und pruefe, bis die Aufgabe erledigt \
            ist. Nutze Werkzeuge, statt zu beschreiben, was zu tun waere.

            Fang mit einem konkreten ersten Schritt an, statt alles vorab durchzuplanen. \
            Wenn du fertig bist, antworte ohne Werkzeugaufruf mit zwei bis drei Saetzen \
            dazu, was du getan hast.""";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("bench")) {
            de.dg1001.harness.bench.Bench.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Map<String, String> o = argumente(args);

        if (o.containsKey("hilfe") || o.containsKey("h")) { hilfe(); return; }

        String baseUrl = o.getOrDefault("base-url", "http://127.0.0.1:8888/v1");
        String modell  = o.get("model");
        String apiKey  = schluessel(o);
        Path   cwd     = Path.of(o.getOrDefault("cwd", "."));
        int fenster    = Integer.parseInt(o.getOrDefault("context-window", "65536"));
        int maxAusgabe = Integer.parseInt(o.getOrDefault("max-output", "16384"));
        int maxZuege   = Integer.parseInt(o.getOrDefault("max-turns", "60"));
        int minuten    = Integer.parseInt(o.getOrDefault("timeout-minutes", "25"));
        boolean laut   = !o.containsKey("leise");
        int maxDenken  = Integer.parseInt(o.getOrDefault("max-denken", "0"));

        if (modell == null) { System.err.println("--model fehlt"); hilfe(); System.exit(2); }

        String aufgabe = o.containsKey("prompt-file")
                ? Files.readString(Path.of(o.get("prompt-file")))
                : o.get("prompt");

        Workspace ws = new Workspace(cwd);
        // Vorab gelesen, damit ein Fehler in .jaja.json gemeldet wird statt
        // still zu "keine Einstellungen" zu werden.
        try {
            ws.mitProjekt(de.dg1001.harness.ws.Projekt.lies(ws.wurzel()));
        } catch (java.io.IOException e) {
            System.err.println("[harness] " + e.getMessage());
            System.exit(2);
        }
        if (o.containsKey("sandkasten"))
            ws.mitSandkasten(de.dg1001.harness.ws.Sandkasten.fuerDiesesSystem(ws.wurzel()));

        // Projektregeln aus AGENT.md, sofern vorhanden.
        // Die Ausgabegrenze gehoert in den Prompt, weil sie das Modell sonst
        // erst erfaehrt, wenn ein Aufruf daran zerschellt.
        Systemprompt.Ergebnis sp = Systemprompt.baue(
                SYSTEM_PROMPT + "\n\n" + Systemprompt.ausgabehinweis(maxAusgabe), ws,
                o.containsKey("systemprompt") ? Path.of(o.get("systemprompt")) : null,
                o.containsKey("kein-agent-md"));
        ContextBudget budget = ContextBudget.vorgabe(fenster, maxAusgabe);
        ChatClient client = new ChatClient(baseUrl, modell, apiKey, maxAusgabe,
                                           Duration.ofMinutes(minuten),
                                           !o.containsKey("kein-stream"));

        // Ohne Aufgabe auf der Kommandozeile: Sitzung am Bildschirm. Muss vor
        // jeder stderr-Ausgabe entschieden werden -- eine einzige Zeile neben
        // der Oberflaeche her zerschneidet die Statuszeile.
        boolean amBildschirm = (aufgabe == null || aufgabe.isBlank());

        if (laut && !amBildschirm) {
            System.err.println("[harness] Modell " + modell + " an " + baseUrl);
            System.err.println("[harness] Arbeitsbereich " + ws.wurzel()
                    + (ws.sandkasten() != null ? " (bash im Sandkasten)" : ""));
            System.err.println("[harness] " + budget.bericht(0));
        }
        if (laut && sp.quelle() != null)
            System.err.println("[harness] Systemprompt aus " + sp.quelle().getFileName()
                    + " (" + sp.zeichen() + " Zeichen)");
        if (sp.warnung() != null)
            System.err.println("[harness] Achtung: " + sp.warnung());

        // Eigener Modus: nur die Karte beschreiben, dann Schluss. Steht vor der
        // Weiche, weil er weder Sitzung noch Aufgabe braucht -- nur das Modell.
        if (o.containsKey("index")) {
            Retry e = Retry.vorgabe(client, m -> System.err.println("[index] " + m));
            var muster = o.containsKey("muster")
                    ? de.dg1001.harness.tools.GlobTool.muster(o.get("muster")) : null;
            Indexer.Ergebnis erg = new Indexer(e, ws)
                    .lauf(new Karte(ws), muster, m -> System.err.println("[index] " + m));
            System.err.printf("[index] %d beschrieben, %d offen, %d Anfragen%s%n",
                    erg.beschrieben(), erg.offen(), erg.buendel(),
                    erg.abgebrochen() ? " (abgebrochen)" : "");
            System.exit(erg.offen() == 0 ? 0 : 1);
        }

        if (amBildschirm) {
            sitzung(client, ws, budget, maxZuege, modell, !o.containsKey("frei"), sp,
                    o.containsKey("karte"), herkunft(baseUrl), maxDenken,
                    o.containsKey("trace") ? Path.of(o.get("trace")) : null, kopf(o, ws), o);
            return;
        }

        // Wiederholung als Huelle. Lokale Server melden unter Last 503 mit der
        // ausdruecklichen Bitte, es gleich nochmal zu versuchen -- ohne das
        // geht die Arbeit vieler Zuege verloren.
        Retry endpunkt = Retry.vorgabe(client,
                m -> { if (laut) System.err.println("[harness] " + m); });

        de.dg1001.harness.agent.Beobachter beob = laut
                ? de.dg1001.harness.agent.Beobachter.STDERR : de.dg1001.harness.agent.Beobachter.STILL;
        de.dg1001.harness.agent.Protokoll protokoll = o.containsKey("trace")
                ? de.dg1001.harness.agent.Protokoll.oeffne(Path.of(o.get("trace")), beob, kopf(o, ws))
                : null;
        de.dg1001.harness.agent.Beobachter b = protokoll != null ? protokoll : beob;
        Agent agent = new Agent(endpunkt, werkzeuge(o, endpunkt, budget, b), ws, budget, maxZuege, b)
                .mitDenkgrenze(maxDenken)
                // Im Stapelbetrieb fragt niemand -- aber Verbote gelten trotzdem.
                .mitFreigabe(de.dg1001.harness.agent.Regeln.aus(ws.projekt()).nurVerbote());

        long t0 = System.nanoTime();
        Agent.Ergebnis e = agent.lauf(sp.prompt(), aufgabe);
        long sek = (System.nanoTime() - t0) / 1_000_000_000L;

        System.err.printf("[harness] %s nach %d Zuegen, %d Werkzeugaufrufen, %d s (%s)%n",
                e.status(), e.zuege(), e.werkzeugaufrufe(), sek, agent.schaetzer());
        System.err.printf("[harness] Token: %d Eingabe, %d Ausgabe%n",
                agent.eingabeTokens(), agent.ausgabeTokens());
        if (e.hinweis() != null) System.err.println("[harness] " + e.hinweis());
        if (e.abschluss() != null) System.out.println(e.abschluss());
        if (protokoll != null) protokoll.close();

        // Rueckgabewert: 0 nur bei ordentlichem Abschluss. Wichtig fuer den
        // Pruefstand -- ein "fertig", das nichts getan hat, soll auffallen.
        System.exit(e.erfolgreich() ? 0 : 1);
    }

    /**
     * Der Sitzungsbetrieb.
     *
     * <p>Das Terminal muss unbedingt zurueckgesetzt werden, auch wenn etwas
     * schiefgeht — ein im Rohmodus verlassenes Terminal zeigt keine Eingabe
     * mehr an, und der Nutzer haelt das zu Recht fuer einen Absturz.
     */
    private static void sitzung(ChatClient client, Workspace ws, ContextBudget budget,
                                int maxZuege, String modell, boolean fragen,
                                Systemprompt.Ergebnis sp, boolean mitKarte,
                                String herkunft, int maxDenken, Path trace,
                                Map<String, Object> kopf, Map<String, String> schalter)
            throws Exception {
        Terminal term = Terminal.oeffne();
        if (term == null) {
            System.err.println("--prompt oder --prompt-file fehlt "
                             + "(und dies ist kein Terminal, also keine Sitzung moeglich)");
            System.exit(2);
        }
        try (term) {
            Anzeige anzeige = new Anzeige(maxZuege);
            // Die Wiederholung meldet sich hier ueber die Anzeige. Ginge sie
            // wie im Stapelbetrieb nach stderr, faende sie sich mitten in der
            // Statuszeile wieder -- gemessen an einem Server, der nicht antwortete.
            ChatEndpunkt endpunkt = Retry.vorgabe(client, anzeige::hinweis);
            de.dg1001.harness.agent.Protokoll protokoll = trace == null ? null
                    : de.dg1001.harness.agent.Protokoll.oeffne(trace, anzeige, kopf);
            de.dg1001.harness.agent.Beobachter b = protokoll != null ? protokoll : anzeige;
            Agent agent = new Agent(endpunkt, werkzeuge(schalter, endpunkt, budget, b), ws, budget,
                                    maxZuege, b)
                    .mitDenkgrenze(maxDenken);
            if (sp.quelle() != null)
                anzeige.hinweis("Systemprompt aus " + sp.quelle().getFileName()
                        + " (" + sp.zeichen() + " Zeichen)");
            if (sp.warnung() != null) anzeige.hinweis("Achtung: " + sp.warnung());
            new Sitzung(agent, agent.schaetzer(), anzeige, System.in,
                        ws, budget, sp.prompt(), modell, fragen, endpunkt, herkunft).lauf();
        }
    }

    /**
     * Die Werkzeuge. Was spaeter dazukam, steht hinten -- jede Einfuegung weiter
     * vorn verschoebe den Praefix-Cache aller folgenden Beschreibungen.
     */
    private static ToolRegistry werkzeuge(Map<String, String> o, ChatEndpunkt endpunkt,
                                          ContextBudget budget, de.dg1001.harness.agent.Beobachter b) {
        ToolRegistry r = ToolRegistry.vorgabe(o.containsKey("karte"));
        if (o.containsKey("erkunden"))
            r.fuegeHinzu(new de.dg1001.harness.agent.ErkundeTool(endpunkt, budget, b));
        return r;
    }

    /**
     * Was im Kopf des Protokolls steht: alle Schalter, ausser dem Schluessel
     * und der Aufgabe selbst (die kann Seiten lang sein und steht in der
     * Aufgabendatei). Dazu der Stand von jaja, damit eine Auswertung Laeufe
     * verschiedener Fassungen auseinanderhalten kann.
     */
    private static Map<String, Object> kopf(Map<String, String> o, Workspace ws) {
        Map<String, Object> k = new java.util.TreeMap<>();
        for (var e : o.entrySet())
            if (!e.getKey().equals("api-key") && !e.getKey().equals("prompt"))
                k.put(e.getKey(), e.getValue());
        k.put("arbeitsbereich", ws.wurzel().toString());
        String version = Main.class.getPackage().getImplementationVersion();
        k.put("jaja", version == null ? "dev" : version);
        return k;
    }

    /**
     * Der API-Schluessel, vorzugsweise aus der Umgebung.
     *
     * <p>{@code --api-key} steht in {@code /proc/<pid>/cmdline}, und die ist
     * fuer <em>jeden</em> Nutzer der Maschine lesbar — ein {@code ps} genuegt.
     * {@code /proc/<pid>/environ} gehoert dagegen dem Eigentuemer allein.
     * Deshalb hat die Umgebungsvariable Vorrang, und die Option bleibt nur
     * fuer den Fall, dass jemand sie ausdruecklich will.
     */
    private static String schluessel(Map<String, String> o) {
        String ausUmgebung = System.getenv("JAJA_API_KEY");
        if (ausUmgebung != null && !ausUmgebung.isBlank()) return ausUmgebung;
        return o.getOrDefault("api-key", "unused");
    }

    /**
     * Kurzform der Adresse fuer die Kopfzeile.
     *
     * <p>Notwendig, weil Modellnamen nichts darueber sagen, wo gerechnet wird:
     * der lokale Server meldet sich als {@code deepseek-v4-flash}, die
     * gehostete Schnittstelle ebenso. Die Kopfzeilen waren Zeichen fuer
     * Zeichen gleich — bei einem Dienst, der abrechnet, ist das zu wenig.
     */
    public static String herkunft(String baseUrl) {
        try {
            java.net.URI u = java.net.URI.create(baseUrl);
            String host = u.getHost() == null ? baseUrl : u.getHost();
            if (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1"))
                return "lokal" + (u.getPort() > 0 ? " :" + u.getPort() : "");
            return host;
        } catch (RuntimeException e) {
            return baseUrl;
        }
    }

    private static Map<String, String> argumente(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) continue;
            String schluessel = a.substring(2);
            int gl = schluessel.indexOf('=');
            if (gl >= 0) {
                m.put(schluessel.substring(0, gl), schluessel.substring(gl + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                m.put(schluessel, args[++i]);
            } else {
                m.put(schluessel, "");
            }
        }
        return m;
    }

    private static void hilfe() {
        System.err.println("""
            harness — schlanker agentischer Coding-Harness

              --model <name>            Modellname (Pflicht)
              --base-url <url>          OpenAI-kompatibler Endpunkt
                                        (Vorgabe http://127.0.0.1:8888/v1)
              --api-key <schluessel>    Vorgabe "unused"; besser ueber die
                                        Umgebungsvariable JAJA_API_KEY, weil die
                                        Kommandozeile fuer alle lesbar ist
              --cwd <pfad>              Arbeitsbereich, Vorgabe .
              --prompt <text>           Aufgabe
              --prompt-file <pfad>      Aufgabe aus Datei
              --context-window <n>      Vorgabe 65536
              --max-output <n>          Vorgabe 16384
              --max-turns <n>           Vorgabe 60
              --timeout-minutes <n>     je Anfrage, Vorgabe 25
              --leise                   keine Fortschrittsmeldungen
              --trace <datei>           jedes Ereignis als JSONL mitschreiben
              --kein-stream             Antworten am Stueck statt gestreamt
              --max-denken <n>          Zug kappen, wenn er so viele Token denkt,
                                        ohne ein Werkzeug aufzurufen (Vorgabe aus;
                                        braucht Streaming)
              --frei                    Sitzung: bash ohne Nachfrage ausfuehren
              --sandkasten              bash darf nur im Arbeitsbereich, in /tmp und
                                        in Paketcaches schreiben (macOS sandbox-exec,
                                        Linux bwrap); fehlt das Werkzeug, Abbruch
              --systemprompt <pfad>     ersetzt den eingebauten Systemprompt ganz
              --kein-agent-md           AGENT.md im Projekt ignorieren
              --erkunden                Werkzeug 'erkunde': ein Unteragent beantwortet
                                        Fragen zum Projekt in eigenem Kontext (Versuch)
              --karte                   das Werkzeug 'karte' dazunehmen (Vorgabe: aus,
                                        siehe README — ohne messbaren Nutzen)
              --index                   Kurzbeschreibungen fuer die Karte erzeugen
                                        und beenden (fortsetzbar, sichert laufend)
              --muster <glob>           mit --index: nur diesen Teil beschreiben,
                                        z. B. --muster 'src/kern/**'

            AGENT.md (oder AGENTS.md) im Arbeitsbereich wird automatisch gelesen
            und ergaenzt den eingebauten Prompt um die Projektregeln.

            Ohne --prompt/--prompt-file startet eine Sitzung am Bildschirm.

            Rueckgabewert 0 nur bei ordentlichem Abschluss.

            jaja bench <definition.json>     Benchmark laufen lassen (siehe README)
            jaja bench --bericht <verz>      Bericht aus vorhandenen Ergebnissen""");
    }
}
