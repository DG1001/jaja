package de.dg1001.harness;

import de.dg1001.harness.agent.Agent;
import de.dg1001.harness.agent.ContextBudget;
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
        String apiKey  = o.getOrDefault("api-key", "unused");
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
        ContextBudget budget = ContextBudget.vorgabe(fenster, maxAusgabe);
        ChatClient client = new ChatClient(baseUrl, modell, apiKey, maxAusgabe,
                                           Duration.ofMinutes(minuten));

        if (laut) {
            System.err.println("[harness] Modell " + modell + " an " + baseUrl);
            System.err.println("[harness] Arbeitsbereich " + ws.wurzel());
            System.err.println("[harness] " + budget.bericht(0));
        }

        // Wiederholung als Huelle. Lokale Server melden unter Last 503 mit der
        // ausdruecklichen Bitte, es gleich nochmal zu versuchen -- ohne das
        // geht die Arbeit vieler Zuege verloren.
        Retry endpunkt = Retry.vorgabe(client,
                m -> { if (laut) System.err.println("[harness] " + m); });

        // Ohne Aufgabe auf der Kommandozeile: Sitzung am Bildschirm.
        if (aufgabe == null || aufgabe.isBlank()) {
            sitzung(endpunkt, ws, budget, maxZuege, modell, !o.containsKey("frei"));
            return;
        }

        Agent agent = new Agent(endpunkt, ToolRegistry.vorgabe(), ws, budget, maxZuege, laut);

        long t0 = System.nanoTime();
        Agent.Ergebnis e = agent.lauf(SYSTEM_PROMPT, aufgabe);
        long sek = (System.nanoTime() - t0) / 1_000_000_000L;

        System.err.printf("[harness] %s nach %d Zuegen, %d Werkzeugaufrufen, %d s (%s)%n",
                e.status(), e.zuege(), e.werkzeugaufrufe(), sek, agent.schaetzer());
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
    private static void sitzung(ChatEndpunkt endpunkt, Workspace ws, ContextBudget budget,
                                int maxZuege, String modell, boolean fragen) throws Exception {
        Terminal term = Terminal.oeffne();
        if (term == null) {
            System.err.println("--prompt oder --prompt-file fehlt "
                             + "(und dies ist kein Terminal, also keine Sitzung moeglich)");
            System.exit(2);
        }
        try (term) {
            Anzeige anzeige = new Anzeige(maxZuege);
            Agent agent = new Agent(endpunkt, ToolRegistry.vorgabe(), ws, budget,
                                    maxZuege, anzeige);
            new Sitzung(agent, agent.schaetzer(), anzeige, System.in,
                        ws, SYSTEM_PROMPT, modell, fragen).lauf();
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
              --api-key <schluessel>    Vorgabe "unused"
              --cwd <pfad>              Arbeitsbereich, Vorgabe .
              --prompt <text>           Aufgabe
              --prompt-file <pfad>      Aufgabe aus Datei
              --context-window <n>      Vorgabe 65536
              --max-output <n>          Vorgabe 16384
              --max-turns <n>           Vorgabe 60
              --timeout-minutes <n>     je Anfrage, Vorgabe 25
              --leise                   keine Fortschrittsmeldungen
              --frei                    Sitzung: bash ohne Nachfrage ausfuehren

            Ohne --prompt/--prompt-file startet eine Sitzung am Bildschirm.

            Rueckgabewert 0 nur bei ordentlichem Abschluss.""");
    }
}
