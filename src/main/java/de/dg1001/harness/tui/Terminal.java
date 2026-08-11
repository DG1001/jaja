package de.dg1001.harness.tui;

import java.io.IOException;
import java.io.InputStream;

/**
 * Das bisschen Terminal, das wir brauchen — ohne Fremdbibliothek.
 *
 * <p>Java kennt keinen Rohmodus. Der uebliche Weg dorthin fuehrt ueber
 * {@code stty} gegen {@code /dev/tty}; das ist kein schoener Trick, aber ein
 * stabiler, und er spart die Abhaengigkeit, die dieses Projekt sonst nirgends
 * braucht.
 *
 * <p>Rohmodus heisst: keine Zeilenpufferung, kein Echo, und vor allem <b>kein
 * SIGINT</b>. Ctrl-C kommt als Byte 0x03 bei uns an, statt die Anwendung zu
 * beenden — genau das wollen wir, denn Ctrl-C soll den laufenden Zug abbrechen
 * und nicht die Sitzung.
 *
 * <p>Der Preis: wer den Modus nicht zuruecksetzt, hinterlaesst ein unbrauchbares
 * Terminal. Deshalb sowohl {@code try/finally} beim Aufrufer als auch ein
 * Abschalthaken hier — auch ein {@code kill} soll das Terminal heil lassen.
 */
public final class Terminal implements AutoCloseable {

    public static final String ESC     = "\033[";
    public static final String ZEILE_LOESCHEN = ESC + "2K\r";
    public static final String VERSTECKEN     = ESC + "?25l";
    public static final String ZEIGEN         = ESC + "?25h";

    public static final String GRAU    = ESC + "90m";
    public static final String ROT     = ESC + "31m";
    public static final String GRUEN   = ESC + "32m";
    public static final String GELB    = ESC + "33m";
    public static final String BLAU    = ESC + "34m";
    public static final String CYAN    = ESC + "36m";
    public static final String FETT    = ESC + "1m";
    public static final String NORMAL  = ESC + "0m";

    private final String vorher;
    private boolean offen = true;

    private Terminal(String vorher) { this.vorher = vorher; }

    /** @return null, wenn wir nicht an einem Terminal haengen (Pipe, cron). */
    public static Terminal oeffne() {
        if (System.console() == null) return null;
        String vorher = stty("-g");
        if (vorher == null) return null;
        if (stty("raw -echo") == null) return null;

        Terminal t = new Terminal(vorher.trim());
        Runtime.getRuntime().addShutdownHook(new Thread(t::zuruecksetzen));
        return t;
    }

    @Override public void close() { zuruecksetzen(); }

    private synchronized void zuruecksetzen() {
        if (!offen) return;
        offen = false;
        System.out.print(ZEIGEN + NORMAL);
        System.out.flush();
        stty(vorher);
    }

    /** Spaltenzahl; 80, wenn sie sich nicht ermitteln laesst. */
    public static int breite() {
        String s = stty("size");           // "zeilen spalten"
        if (s == null) return 80;
        String[] teile = s.trim().split("\\s+");
        if (teile.length < 2) return 80;
        try {
            int b = Integer.parseInt(teile[1]);
            return b > 20 ? b : 80;
        } catch (NumberFormatException e) { return 80; }
    }

    private static String stty(String argumente) {
        try {
            Process p = new ProcessBuilder("sh", "-c",
                    "stty " + argumente + " < /dev/tty").redirectErrorStream(true).start();
            String aus = new String(p.getInputStream().readAllBytes());
            return p.waitFor() == 0 ? aus : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    // ------------------------------------------------------------ Eingabe

    /**
     * Liest ein Zeichen und setzt dabei UTF-8 zusammen.
     *
     * <p>Byteweises Lesen und Weiterreichen als {@code char} wuerde bei jedem
     * Umlaut Unsinn erzeugen — und die Beschriftungen dieses Programms sind
     * voller Umlaute.
     *
     * @return der Codepunkt, oder -1 am Ende des Stroms
     */
    public static int liesZeichen(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0x80) return b;                       // ASCII oder Ende

        int weitere;
        int wert;
        if      ((b & 0xE0) == 0xC0) { weitere = 1; wert = b & 0x1F; }
        else if ((b & 0xF0) == 0xE0) { weitere = 2; wert = b & 0x0F; }
        else if ((b & 0xF8) == 0xF0) { weitere = 3; wert = b & 0x07; }
        else return '?';                              // Folgebyte ohne Anfang

        for (int i = 0; i < weitere; i++) {
            int f = in.read();
            if (f < 0) return -1;
            wert = (wert << 6) | (f & 0x3F);
        }
        return wert;
    }
}
