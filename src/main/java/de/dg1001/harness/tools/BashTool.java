package de.dg1001.harness.tools;

import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Fuehrt ein Shell-Kommando im Arbeitsbereich aus.
 *
 * <p>Zwei Dinge, die man beim ersten Versuch gern falsch macht:
 *
 * <ul>
 * <li><b>Stromausgabe zusammenlegen und vollstaendig lesen.</b> Wer stdout und
 *     stderr getrennt laesst und nacheinander liest, blockiert, sobald ein Puffer
 *     volllaeuft — der klassische Verklemmer bei ProcessBuilder.
 * <li><b>Zeitgrenze mit Nachfassen.</b> {@code destroy()} schickt SIGTERM; ein
 *     haengender Prozess ignoriert das. Ohne {@code destroyForcibly()} bleibt er
 *     stehen und der Lauf endet nie.
 * </ul>
 */
public final class BashTool implements Tool {

    private static final int VORGABE_SEKUNDEN = 120;
    private static final int MAX_SEKUNDEN     = 600;

    @Override public String name() { return "bash"; }

    @Override public String beschreibung() {
        return "Fuehrt ein Shell-Kommando im Projektverzeichnis aus und liefert die "
             + "zusammengelegte Ausgabe samt Rueckgabewert. Nimm das zum Bauen, Testen, "
             + "Suchen und fuer alles, wofuer es kein eigenes Werkzeug gibt.";
    }

    @Override public String parameterSchema() {
        return """
               {"type":"object",
                "properties":{
                  "kommando":{"type":"string","description":"Shell-Kommando"},
                  "sekunden":{"type":"integer","description":"Zeitgrenze, Vorgabe 120, hoechstens 600"}},
                "required":["kommando"]}""";
    }

    @Override
    public ToolResult run(Map<String, Object> args, Workspace ws) throws IOException {
        String kommando;
        try {
            kommando = Tool.pflichtText(args, "kommando");
        } catch (IllegalArgumentException e) {
            return ToolResult.fehler(e.getMessage());
        }

        int sekunden = Math.min(MAX_SEKUNDEN,
                Math.max(1, Tool.zahl(args, "sekunden", VORGABE_SEKUNDEN)));

        ProcessBuilder pb = new ProcessBuilder("bash", "-lc", kommando);
        pb.directory(ws.wurzel().toFile());
        pb.redirectErrorStream(true);          // sonst Verklemmungsgefahr
        // Der Arbeitsbereich als Umgebungsvariable, damit Skripte ihn kennen,
        // ohne dass das Modell absolute Pfade raten muss.
        pb.environment().put("HARNESS_ROOT", ws.wurzel().toString());

        long t0 = System.nanoTime();
        Process p = pb.start();

        // Lesen MUSS nebenlaeufig zum Warten laufen. Wer erst readAllBytes()
        // aufruft und dann waitFor(zeitgrenze), wartet in Wahrheit bis der
        // Strom schliesst -- und der schliesst erst, wenn der Prozess endet.
        // Die Zeitgrenze greift dann nie. (Genau so beim ersten Versuch
        // passiert; der Test hat es gefunden.)
        var puffer = new java.io.ByteArrayOutputStream();
        Thread leser = Thread.ofVirtual().start(() -> {
            try (InputStream in = p.getInputStream()) {
                in.transferTo(puffer);
            } catch (IOException ignored) {
                // Strom bricht beim Abwuergen des Prozesses ab -- erwartet.
            }
        });

        boolean rechtzeitig;
        try {
            // Die Nachkommen VOR dem Toeten einsammeln: sobald die Shell weg
            // ist, werden ihre Kinder an init umgehaengt und sind ueber
            // descendants() nicht mehr auffindbar.
            rechtzeitig = p.waitFor(sekunden, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return ToolResult.fehler("unterbrochen");
        }

        if (!rechtzeitig) {
            java.util.List<ProcessHandle> nachkommen = p.descendants().toList();
            p.destroy();                              // SIGTERM
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS))
                    p.destroyForcibly();              // SIGKILL, sonst bleibt er stehen
                beendeNachkommen(nachkommen);
                leser.join(java.time.Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
p.destroyForcibly();
                beendeNachkommen(p.descendants().toList());
            }
            String t = puffer.toString(StandardCharsets.UTF_8)
                     + "\n[abgebrochen nach " + sekunden + " s]";
            return Spill.vielleichtAuslagern(t, ws, "bash-timeout", true);
        }

        try {
            leser.join(java.time.Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String ausgabe = puffer.toString(StandardCharsets.UTF_8);

        int code = p.exitValue();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        StringBuilder b = new StringBuilder();
        if (ausgabe.isBlank()) b.append("(keine Ausgabe)\n");
        else                   b.append(ausgabe).append(ausgabe.endsWith("\n") ? "" : "\n");
        b.append("[Rueckgabewert ").append(code).append(", ").append(ms).append(" ms]");

        return Spill.vielleichtAuslagern(b.toString(), ws, "bash", code != 0);
    }

    /**
     * Raeumt auf, was die Shell hinterlassen hat.
     *
     * <p>{@code destroy()} trifft nur den Prozess selbst. Startet die Shell ein
     * Kind — {@code python3 app.py}, ein Entwicklungsserver, irgendetwas mit
     * {@code &} — dann ueberlebt dieses Kind das Abwuergen der Shell, wird an
     * init umgehaengt und haelt seinen Port weiter. Genau so gesehen: nach dem
     * Ablauf der Zeitgrenze lief ein Flask-Server als Waise weiter und blockierte
     * Port 5000, waehrend der Harness laengst weitergemacht hatte.
     *
     * <p>Die Liste muss <b>vor</b> dem Toeten des Elternprozesses aufgenommen
     * werden; danach sind die Kinder keine Nachkommen mehr.
     */
    private static void beendeNachkommen(java.util.List<ProcessHandle> nachkommen) {
        for (ProcessHandle h : nachkommen) h.destroy();
        for (ProcessHandle h : nachkommen) {
            try {
                h.onExit().get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                h.destroyForcibly();
            }
        }
    }
}
