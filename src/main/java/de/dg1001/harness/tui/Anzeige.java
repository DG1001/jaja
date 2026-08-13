package de.dg1001.harness.tui;

import de.dg1001.harness.agent.Beobachter;
import de.dg1001.harness.tools.Tool;
import de.dg1001.harness.wire.Json;
import de.dg1001.harness.wire.Messages.ChatResponse;
import de.dg1001.harness.wire.Messages.ToolCall;

/**
 * Bildschirmausgabe: gewoehnlicher Ablauf plus eine lebende Statuszeile.
 *
 * <p>Bewusst <em>kein</em> Vollbild. Ein Vollbildfenster nimmt einem den
 * Rueckblick: was der Agent vor zehn Minuten getan hat, ist dann weg, und
 * Markieren und Kopieren funktioniert nur noch eingeschraenkt. Alles ausser der
 * Statuszeile laeuft deshalb als gewoehnliche Ausgabe durch und bleibt im
 * Puffer des Terminals.
 *
 * <p>Der Kniff dabei ist klein: vor jeder neuen Zeile wird die Statuszeile
 * geloescht, danach neu gezeichnet. Sie liegt immer unten und wandert nie in
 * den Rueckblick.
 */
public final class Anzeige implements Beobachter {

    private final Object schloss = new Object();
    private final int breite;

    private volatile boolean statusAn = false;
    private volatile int zug, maxZuege, token, tokenBudget;
    private volatile String taetigkeit = "";
    private volatile boolean frei = false;
    private volatile long begonnen;
    private Thread ticker;

    /** Ob bash ungefragt laeuft. Steht in der Statuszeile, weil man waehrend
     *  eines Zuges sonst nicht sieht, ob noch gefragt wird — und das ist die
     *  eine Einstellung, bei der Raten teuer werden kann. */
    public void setzeFrei(boolean f) { this.frei = f; }

    public Anzeige(int maxZuege) {
        this.maxZuege = maxZuege;
        this.breite = Terminal.breite();
    }

    // ------------------------------------------------------ gewoehnliche Ausgabe

    /**
     * Schreibt Text, ohne die Statuszeile zu zerstoeren.
     *
     * <p>Mehrzeiliges wird hier aufgeteilt, nicht beim Aufrufer. Im Rohmodus
     * springt ein blosses {@code \n} nicht an den Zeilenanfang — die Ausgabe
     * laeuft dann treppenfoermig nach rechts aus dem Bild. Das einmal an der
     * richtigen Stelle zu erledigen ist die einzige Fassung, bei der es nicht
     * beim naechsten mehrzeiligen Text wieder passiert. (Es ist passiert: die
     * Hilfe kam als Treppe.)
     */
    public void zeile(String text) {
        synchronized (schloss) {
            System.out.print(Terminal.ZEILE_LOESCHEN);
            if (text != null && text.indexOf('\n') >= 0) {
                String[] teile = text.split("\n", -1);
                for (int i = 0; i < teile.length; i++) {
                    if (i > 0) System.out.print(Terminal.ZEILE_LOESCHEN);
                    System.out.print(teile[i]);
                    System.out.print("\r\n");
                }
            } else {
                System.out.print(text);
                System.out.print("\r\n");
            }
            statusZeichnen();
            System.out.flush();
        }
    }

    public void leerzeile() { zeile(""); }

    // ---------------------------------------------------------- Statuszeile

    public void statusStarten(String taetigkeit) {
        this.taetigkeit = taetigkeit;
        this.begonnen = System.nanoTime();
        this.zug = 0;
        this.statusAn = true;
        synchronized (schloss) { System.out.print(Terminal.VERSTECKEN); statusZeichnen(); System.out.flush(); }

        ticker = Thread.ofVirtual().start(() -> {
            try {
                while (statusAn) {
                    Thread.sleep(1000);
                    synchronized (schloss) { statusZeichnen(); System.out.flush(); }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
    }

    public void statusBeenden() {
        statusAn = false;
        if (ticker != null) ticker.interrupt();
        synchronized (schloss) {
            System.out.print(Terminal.ZEILE_LOESCHEN);
            System.out.print(Terminal.ZEIGEN);
            System.out.flush();
        }
    }

    /** Nur aufrufen, wenn {@link #schloss} gehalten wird. */
    private void statusZeichnen() {
        if (!statusAn) return;
        long sek = (System.nanoTime() - begonnen) / 1_000_000_000L;
        StringBuilder b = new StringBuilder("  ");
        if (zug > 0) b.append("Zug ").append(zug).append('/').append(maxZuege).append(" · ");
        if (tokenBudget > 0)
            b.append(kurz(token)).append('/').append(kurz(tokenBudget)).append(" Token · ");
        b.append(dauer(sek));
        if (!taetigkeit.isEmpty()) b.append(" · ").append(taetigkeit);
        if (frei) b.append(" · frei");
        b.append("   ^C bricht ab");

        String s = b.toString();
        if (s.length() > breite - 1) s = s.substring(0, breite - 2) + "…";
        System.out.print(Terminal.ZEILE_LOESCHEN + Terminal.GRAU + s + Terminal.NORMAL + "\r");
    }

    // --------------------------------------------------------- als Beobachter

    @Override public void zug(int nummer, ChatResponse a, int budget) {
        this.zug = nummer;
        this.token = a.usage().promptTokens();
        this.tokenBudget = budget;
        this.taetigkeit = a.message().hatWerkzeugaufrufe() ? "arbeitet" : "antwortet";
        synchronized (schloss) { statusZeichnen(); System.out.flush(); }
    }

    @Override public void werkzeugStart(ToolCall tc) {
        this.taetigkeit = tc.name();
        synchronized (schloss) { statusZeichnen(); System.out.flush(); }
    }

    @Override public void werkzeugFertig(ToolCall tc, Tool.ToolResult r) {
        String farbe = r.istFehler() ? Terminal.ROT : Terminal.GRUEN;
        String name  = padde(tc.name(), 6);
        String arg   = kuerze(argument(tc), Math.max(20, breite - 40));
        String erg   = ergebnis(r);

        zeile("  " + farbe + "⏺" + Terminal.NORMAL + " " + Terminal.FETT + name
              + Terminal.NORMAL + "  " + arg
              + "  " + Terminal.GRAU + erg + Terminal.NORMAL);
    }

    @Override public void hinweis(String text) {
        zeile("  " + Terminal.GELB + "· " + text + Terminal.NORMAL);
    }

    // ------------------------------------------------------------- Zutaten

    /** Das eine Argument, das den Aufruf kenntlich macht. */
    private static String argument(ToolCall tc) {
        try {
            var m = Json.obj(Json.parse(tc.argumentsJson()));
            for (String k : new String[]{"kommando", "pfad", "muster", "alt",
                                         "datei", "stichwort"}) {
                String v = Json.str(m.get(k));
                if (v != null && !v.isBlank()) return v.replace("\n", "⏎");
            }
            return tc.argumentsJson();
        } catch (RuntimeException e) {
            return tc.argumentsJson();
        }
    }

    /** Ergebnisse einzeilig zusammenfassen -- die Ausgabe selbst sieht das
     *  Modell, der Mensch braucht nur zu wissen, dass etwas passiert ist. */
    private static String ergebnis(Tool.ToolResult r) {
        String t = r.text();
        if (t == null || t.isBlank()) return "leer";
        String erste = t.lines().findFirst().orElse("");
        long zeilen = t.lines().count();
        if (r.istFehler())  return kuerze(erste, 60);
        if (zeilen == 1)    return kuerze(erste, 60);
        return zeilen + " Zeilen";
    }

    private static String padde(String s, int n) {
        return s.length() >= n ? s : s + " ".repeat(n - s.length());
    }

    private static String kuerze(String s, int n) {
        if (s == null) return "";
        s = s.replace("\n", " ").replace("\t", " ");
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static String kurz(int n) {
        return n >= 1000 ? String.format("%.1fk", n / 1000.0) : Integer.toString(n);
    }

    private static String dauer(long sek) {
        return sek < 60 ? sek + " s" : String.format("%d:%02d min", sek / 60, sek % 60);
    }
}
