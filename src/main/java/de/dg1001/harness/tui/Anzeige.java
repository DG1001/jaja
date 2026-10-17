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
    /** Cache-Anteil des letzten Zugs in Prozent, -1 wenn der Server es nicht sagt. */
    private volatile int cacheProzent = -1;
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
        if (cacheProzent >= 0) b.append("Cache ").append(cacheProzent).append(" % · ");
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
        this.cacheProzent = a.usage().cacheBekannt() && token > 0
                ? (int) Math.round(100.0 * a.usage().cachedTokens() / token) : -1;
        this.taetigkeit = a.message().hatWerkzeugaufrufe() ? "arbeitet" : "antwortet";
        // Was das Modell zu diesem Zug sagt, ueber die Statuszeile hinaus.
        //
        // Bisher stand hier nur die Statuszeile: Zugnummer, Token, "arbeitet".
        // Man sah, DASS gearbeitet wird, aber nicht, woran -- bei einem langen
        // Lauf ist das der Unterschied zwischen "es baut gerade die
        // Speicherschicht" und Ratenmuessen.
        //
        // Nur bei Zuegen MIT Werkzeugaufruf: der Schlusszug ohne Werkzeug ist
        // die eigentliche Antwort, und die druckt die Sitzung ohnehin ganz.
        // Hier waere sie doppelt und einmal davon verstuemmelt.
        // Faellt auf den Denkteil zurueck, wenn nichts gesagt wurde: bei
        // "mach weiter" arbeitet ein Modell oft wortlos los, und dann steht
        // das Vorhaben nur dort. Der Denkteil ist roher und haeufig englisch,
        // deshalb als zweite Wahl und mit vorangestelltem Punkt kenntlich.
        String gesagt = a.message().content();
        boolean gedacht = gesagt == null || gesagt.isBlank();
        if (gedacht) gesagt = a.message().reasoningContent();
        if (a.message().hatWerkzeugaufrufe() && gesagt != null && !gesagt.isBlank())
            zeile("  " + Terminal.GRAU + (gedacht ? "· " : "")
                  + kuerze(gesagt.strip(), Math.max(30, breite - 6)) + Terminal.NORMAL);
        synchronized (schloss) { statusZeichnen(); System.out.flush(); }
    }

    /**
     * Zeigt, dass etwas entsteht und wie viel. Ein lokaler Zug dauert
     * Minuten; "denkt" ueber elf Minuten unveraendert sieht aus wie ein
     * Haenger, "denkt · 6.2k" nicht. Gezeichnet wird vom Ticker, hier wird nur
     * der Text gesetzt -- sonst schriebe jedes Stueck des Stroms aufs Terminal.
     */
    @Override public void strom(int denkToken, int textToken) {
        this.taetigkeit = textToken > 0 ? "schreibt · " + kurz(textToken)
                                        : "denkt · " + kurz(denkToken);
    }

    @Override public void werkzeugStart(ToolCall tc) {
        this.taetigkeit = tc.name();
        synchronized (schloss) { statusZeichnen(); System.out.flush(); }
    }

    @Override public void werkzeugFertig(ToolCall tc, Tool.ToolResult r) {
        String farbe = r.istFehler() ? Terminal.ROT : Terminal.GRUEN;
        String name  = padde(tc.name(), 6);
        String arg   = kuerze(tc.kurz(), Math.max(20, breite - 40));
        String erg   = ergebnis(r);

        zeile("  " + farbe + "⏺" + Terminal.NORMAL + " " + Terminal.FETT + name
              + Terminal.NORMAL + "  " + arg
              + "  " + Terminal.GRAU + erg + Terminal.NORMAL);
        // Was sich geaendert hat, nicht nur dass: "geaendert, 1 Stelle" sagt
        // nicht, ob es die richtige war.
        if (r.anzeige() != null)
            for (String z : r.anzeige().split("\n")) {
                String f = z.length() > 6 && z.charAt(6) == '-' ? Terminal.ROT
                         : z.length() > 6 && z.charAt(6) == '+' ? Terminal.GRUEN : Terminal.GRAU;
                zeile("    " + f + kuerze(z, Math.max(20, breite - 6)) + Terminal.NORMAL);
            }
    }

    @Override public void hinweis(String text) {
        zeile("  " + Terminal.GELB + "· " + text + Terminal.NORMAL);
    }

    // ------------------------------------------------------------- Zutaten

    /** Das eine Argument, das den Aufruf kenntlich macht. */

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
