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
        var puffer = new KopfFuss(MAX_PUFFER);
        Thread leser = Thread.ofVirtual().start(() -> {
            try (InputStream in = p.getInputStream()) {
                in.transferTo(puffer);
            } catch (IOException ignored) {
                // Strom bricht beim Abwuergen des Prozesses ab -- erwartet.
            }
        });

        boolean rechtzeitig;
        try {
            rechtzeitig = p.waitFor(sekunden, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Ctrl-C in der Sitzung. Dieselbe Sorgfalt wie bei der Zeitgrenze:
            // sonst laeuft der Testlauf oder Server, den die Shell gestartet
            // hat, nach dem Abbruch als Waise weiter.
            Thread.currentThread().interrupt();
            beende(p);
            return ToolResult.fehler("unterbrochen");
        }

        if (!rechtzeitig) {
            beende(p);
            try {
                leser.join(java.time.Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String t = puffer.text()
                     + "\n[abgebrochen nach " + sekunden + " s]";
            return Spill.vielleichtAuslagern(t, ws, "bash-timeout", true);
        }

        try {
            leser.join(java.time.Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String ausgabe = puffer.text();

        int code = p.exitValue();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        StringBuilder b = new StringBuilder();
        if (ausgabe.isBlank()) b.append("(keine Ausgabe)\n");
        else                   b.append(ausgabe).append(ausgabe.endsWith("\n") ? "" : "\n");
        b.append("[Rueckgabewert ").append(code).append(", ").append(ms).append(" ms]");

        return Spill.vielleichtAuslagern(b.toString(), ws, "bash", code != 0);
    }

    /**
     * Beendet die Shell samt allem, was sie gestartet hat.
     *
     * <p>Die Nachkommen werden VOR dem Toeten eingesammelt: sobald die Shell
     * weg ist, werden ihre Kinder an init umgehaengt und sind ueber
     * {@code descendants()} nicht mehr auffindbar.
     */
    private static void beende(Process p) {
        java.util.List<ProcessHandle> nachkommen = p.descendants().toList();
        p.destroy();                                  // SIGTERM
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS))
                p.destroyForcibly();                  // SIGKILL, sonst bleibt er stehen
        } catch (InterruptedException e) {
            // Schon unterbrochen (Ctrl-C): nicht warten, gleich hart.
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        beendeNachkommen(nachkommen);
    }

    /**
     * Wie viel Ausgabe hoechstens im Speicher gehalten wird.
     *
     * <p>Ohne Deckel haelt ein {@code yes} oder ein Endlos-Log bis zur
     * Zeitgrenze alles im Speicher -- bei 120 Sekunden Gigabytes, und der
     * ganze Harness stirbt an OutOfMemoryError statt nur der Aufruf. Behalten
     * werden Anfang und Ende: bei Testlaeufen steht das Wesentliche hinten.
     */
    static final int MAX_PUFFER = 4_000_000;

    /** Haelt die erste und die letzte Haelfte von {@code deckel} Bytes. */
    static final class KopfFuss extends java.io.OutputStream {
        private final byte[] kopf, fuss;
        private int imKopf = 0;
        private long imFuss = 0;                      // insgesamt in den Fuss geschrieben

        KopfFuss(int deckel) {
            kopf = new byte[deckel / 2];
            fuss = new byte[deckel - deckel / 2];
        }

        @Override public synchronized void write(int b) {
            if (imKopf < kopf.length) { kopf[imKopf++] = (byte) b; return; }
            fuss[(int) (imFuss++ % fuss.length)] = (byte) b;
        }

        @Override public synchronized void write(byte[] b, int off, int len) {
            for (int i = 0; i < len; i++) write(b[off + i]);
        }

        synchronized String text() {
            String vorn = new String(kopf, 0, imKopf, StandardCharsets.UTF_8);
            if (imFuss <= fuss.length)
                return vorn + new String(fuss, 0, (int) imFuss, StandardCharsets.UTF_8);
            int start = (int) (imFuss % fuss.length);
            byte[] hinten = new byte[fuss.length];
            System.arraycopy(fuss, start, hinten, 0, fuss.length - start);
            System.arraycopy(fuss, 0, hinten, fuss.length - start, start);
            return vorn + "\n[… " + (imFuss - fuss.length) + " Bytes verworfen …]\n"
                 + new String(hinten, StandardCharsets.UTF_8);
        }
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
