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

        if (modell == null) { System.err.println("--model fehlt"); hilfe(); System.exit(2); }

        String aufgabe = o.containsKey("prompt-file")
                ? Files.readString(Path.of(o.get("prompt-file")))
                : o.get("prompt");

        Workspace ws = new Workspace(cwd);

        // Projektregeln aus AGENT.md, sofern vorhanden.
        Systemprompt.Ergebnis sp = Systemprompt.baue(
                SYSTEM_PROMPT, ws,
                o.containsKey("systemprompt") ? Path.of(o.get("systemprompt")) : null,
                o.containsKey("kein-agent-md"));
        ContextBudget budget = ContextBudget.vorgabe(fenster, maxAusgabe);
        ChatClient client = new ChatClient(baseUrl, modell, apiKey, maxAusgabe,
                                           Duration.ofMinutes(minuten));

        // Ohne Aufgabe auf der Kommandozeile: Sitzung am Bildschirm. Muss vor
        // jeder stderr-Ausgabe entschieden werden -- eine einzige Zeile neben
        // der Oberflaeche her zerschneidet die Statuszeile.
        boolean amBildschirm = (aufgabe == null || aufgabe.isBlank());

        if (laut && !amBildschirm) {
            System.err.println("[harness] Modell " + modell + " an " + baseUrl);
            System.err.println("[harness] Arbeitsbereich " + ws.wurzel());
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
                    o.containsKey("karte"), o.containsKey("abgleich"),
                    herkunft(baseUrl));
            return;
        }

        // Wiederholung als Huelle. Lokale Server melden unter Last 503 mit der
        // ausdruecklichen Bitte, es gleich nochmal zu versuchen -- ohne das
        // geht die Arbeit vieler Zuege verloren.
        Retry endpunkt = Retry.vorgabe(client,
                m -> { if (laut) System.err.println("[harness] " + m); });

        Agent agent = new Agent(endpunkt, ToolRegistry.vorgabe(o.containsKey("karte")),
                                ws, budget, maxZuege, laut)
                          .mitAbgleich(o.containsKey("abgleich"));

        long t0 = System.nanoTime();
        Agent.Ergebnis e = agent.lauf(sp.prompt(), aufgabe);
        long sek = (System.nanoTime() - t0) / 1_000_000_000L;

        System.err.printf("[harness] %s nach %d Zuegen, %d Werkzeugaufrufen, %d s (%s)%n",
                e.status(), e.zuege(), e.werkzeugaufrufe(), sek, agent.schaetzer());
        System.err.printf("[harness] Token: %d Eingabe, %d Ausgabe%n",
                agent.eingabeTokens(), agent.ausgabeTokens());
        if (e.hinweis() != null) System.err.println("[harness] " + e.hinweis());
        if (e.abschluss() != null) System.out.println(e.abschluss());

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
                                boolean mitAbgleich, String herkunft) throws Exception {
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
            Agent agent = new Agent(endpunkt, ToolRegistry.vorgabe(mitKarte), ws, budget,
                                    maxZuege, anzeige)
                              .mitAbgleich(mitAbgleich);
            if (sp.quelle() != null)
                anzeige.hinweis("Systemprompt aus " + sp.quelle().getFileName()
                        + " (" + sp.zeichen() + " Zeichen)");
            if (sp.warnung() != null) anzeige.hinweis("Achtung: " + sp.warnung());
            new Sitzung(agent, agent.schaetzer(), anzeige, System.in,
                        ws, budget, sp.prompt(), modell, fragen, endpunkt, herkunft).lauf();
        }
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
              --abgleich                vor dem Abschluss einmal die Aufgaben-
                                        stellung Punkt fuer Punkt abfragen
              --timeout-minutes <n>     je Anfrage, Vorgabe 25
              --leise                   keine Fortschrittsmeldungen
              --frei                    Sitzung: bash ohne Nachfrage ausfuehren
              --systemprompt <pfad>     ersetzt den eingebauten Systemprompt ganz
              --kein-agent-md           AGENT.md im Projekt ignorieren
              --karte                   das Werkzeug 'karte' dazunehmen (Vorgabe: aus,
                                        siehe README — ohne messbaren Nutzen)
              --index                   Kurzbeschreibungen fuer die Karte erzeugen
                                        und beenden (fortsetzbar, sichert laufend)
              --muster <glob>           mit --index: nur diesen Teil beschreiben,
                                        z. B. --muster 'src/kern/**'

            AGENT.md (oder AGENTS.md) im Arbeitsbereich wird automatisch gelesen
            und ergaenzt den eingebauten Prompt um die Projektregeln.

            Ohne --prompt/--prompt-file startet eine Sitzung am Bildschirm.

            Rueckgabewert 0 nur bei ordentlichem Abschluss.""");
    }
}
