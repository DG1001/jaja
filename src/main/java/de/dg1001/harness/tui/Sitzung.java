package de.dg1001.harness.tui;

import de.dg1001.harness.agent.Agent;
import de.dg1001.harness.agent.Freigabe;
import de.dg1001.harness.agent.TokenSchaetzer;
import de.dg1001.harness.agent.Transcript;
import de.dg1001.harness.wire.Json;
import de.dg1001.harness.wire.Messages;
import de.dg1001.harness.wire.Messages.Message;
import de.dg1001.harness.wire.Messages.SystemMessage;
import de.dg1001.harness.wire.Messages.ToolCall;
import de.dg1001.harness.wire.Messages.UserMessage;
import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;

/**
 * Die Sitzung: Aufgabe stellen, zusehen, nachfassen.
 *
 * <p>Der Unterschied zum Stapelbetrieb ist der {@link Transcript}, der zwischen
 * den Auftraegen stehen bleibt. Eine Rueckfrage sieht damit alles, was vorher
 * geschah — auch die bereits gekuerzten Werkzeugergebnisse. Ohne das waere es
 * kein Agent, sondern ein Skript mit Farben.
 *
 * <p><b>Nur dieser Faden liest die Tastatur.</b> Das ist die wichtigste Regel
 * hier. Waehrend der Agent arbeitet, laeuft er in einem eigenen Faden, und der
 * Hauptfaden fragt die Tastatur ab — fuer Ctrl-C und fuer Freigaben. Wuerde
 * auch der Werkzeugfaden lesen, verschwaende mal der eine und mal der andere
 * einen Tastendruck, und der Fehler traete genau dann auf, wenn man ihn am
 * wenigsten gebrauchen kann.
 */
public final class Sitzung {

    private final Agent agent;
    private final Anzeige anzeige;
    private final Eingabe eingabe;
    private final Workspace ws;
    private final String systemPrompt;
    private final String modell;
    private final InputStream in;
    private final TokenSchaetzer schaetzer;

    private Transcript verlauf;
    private boolean fragen;              // vor bash nachfragen?

    /** Offene Freigabefrage; nur der Hauptfaden beantwortet sie. */
    private volatile ToolCall offeneFrage;
    private final SynchronousQueue<Boolean> antwort = new SynchronousQueue<>();

    public Sitzung(Agent agent, TokenSchaetzer schaetzer, Anzeige anzeige, InputStream in,
                   Workspace ws, String systemPrompt, String modell, boolean fragen) {
        this.agent        = agent;
        this.schaetzer    = schaetzer;
        this.anzeige      = anzeige;
        this.eingabe      = new Eingabe(in);
        this.in           = in;
        this.ws           = ws;
        this.systemPrompt = systemPrompt;
        this.modell       = modell;
        this.fragen       = fragen;
        this.verlauf      = new Transcript(schaetzer);

        agent.mitFreigabe(freigabe());
    }

    // ------------------------------------------------------------ Hauptschleife

    public void lauf() throws IOException {
        kopf();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            while (true) {
                Eingabe.Ergebnis e = eingabe.lies("  " + Terminal.CYAN + "› " + Terminal.NORMAL);
                if (e.art() == Eingabe.Art.ENDE) { anzeige.zeile("  " + Terminal.GRAU + "tschuess" + Terminal.NORMAL); return; }
                if (e.art() == Eingabe.Art.LEER) continue;

                String text = e.text();
                eingabe.merke(text);

                if (text.startsWith("/")) {
                    if (befehl(text)) return;
                    continue;
                }
                beauftrage(text, exec);
            }
        }
    }

    private void kopf() {
        anzeige.leerzeile();
        anzeige.zeile("  " + Terminal.FETT + "jaja" + Terminal.NORMAL + " · " + modell
                      + " · " + Terminal.GRAU + ws.wurzel() + Terminal.NORMAL);
        anzeige.zeile("  " + Terminal.GRAU
                      + (fragen ? "bash fragt nach · " : "bash laeuft ungefragt · ")
                      + "/hilfe zeigt die Befehle" + Terminal.NORMAL);
        anzeige.leerzeile();
    }

    // ------------------------------------------------------------- ein Auftrag

    private void beauftrage(String text, ExecutorService exec) throws IOException {
        if (verlauf.anzahl() == 0) verlauf.beginne(systemPrompt, text);
        else                       verlauf.add(new UserMessage(text));

        anzeige.leerzeile();
        agent.abbruchZuruecksetzen();
        anzeige.statusStarten("denkt");

        Future<Agent.Ergebnis> f = exec.submit(() -> agent.lauf(verlauf));
        tastaturWaehrendArbeit(f);

        Agent.Ergebnis erg;
        try {
            erg = f.get();
        } catch (Exception ex) {
            anzeige.statusBeenden();
            anzeige.zeile("  " + Terminal.ROT + "abgestuerzt: " + ex.getCause() + Terminal.NORMAL);
            return;
        }
        anzeige.statusBeenden();

        anzeige.leerzeile();
        if (erg.abschluss() != null && !erg.abschluss().isBlank())
            for (String z : erg.abschluss().split("\n")) anzeige.zeile("  " + z);

        if (erg.status() != Agent.Status.FERTIG)
            anzeige.zeile("  " + Terminal.GELB + erg.status()
                          + (erg.hinweis() == null ? "" : ": " + erg.hinweis()) + Terminal.NORMAL);

        anzeige.zeile("  " + Terminal.GRAU + erg.zuege() + " Zuege · "
                      + erg.werkzeugaufrufe() + " Werkzeugaufrufe · "
                      + verlauf.schaetzeTokens() + " Token im Verlauf" + Terminal.NORMAL);
        anzeige.leerzeile();
    }

    /**
     * Tastatur abfragen, solange der Agent arbeitet.
     *
     * <p>Abfragen statt blockierend lesen: ein blockierender Lesevorgang haenge
     * noch, wenn der Agent laengst fertig ist, und verschluckte dann den ersten
     * Tastendruck der naechsten Eingabe.
     */
    private void tastaturWaehrendArbeit(Future<?> f) throws IOException {
        while (!f.isDone()) {
            if (in.available() <= 0) {
                try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                continue;
            }
            int c = Terminal.liesZeichen(in);

            ToolCall frage = offeneFrage;
            if (frage != null) {
                if (c == 'j' || c == 'J' || c == '\r' || c == '\n') gib(true);
                else if (c == 'n' || c == 'N' || c == 3)            gib(false);
                continue;
            }

            if (c == 3) {
                agent.brichAb();
                anzeige.hinweis("Abbruch angefordert — der laufende Zug wird noch beendet");
            }
        }
    }

    private void gib(boolean ja) {
        try { antwort.put(ja); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // -------------------------------------------------------------- Freigabe

    private Freigabe freigabe() {
        return tc -> {
            if (!fragen || !tc.name().equals("bash")) return null;

            // Werkzeuge laufen nebenlaeufig: immer nur eine Frage auf einmal,
            // sonst beantwortet ein Tastendruck die falsche.
            synchronized (this) {
                String kommando = kommando(tc);
                anzeige.zeile("");
                anzeige.zeile("  " + Terminal.GELB + "bash?" + Terminal.NORMAL + "  " + kommando);
                anzeige.zeile("  " + Terminal.GRAU + "  [j] ausfuehren   [n] ablehnen" + Terminal.NORMAL);

                offeneFrage = tc;
                boolean ja;
                try { ja = antwort.take(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); ja = false; }
                finally { offeneFrage = null; }

                anzeige.zeile("  " + (ja ? Terminal.GRUEN + "  ausgefuehrt" : Terminal.ROT + "  abgelehnt")
                              + Terminal.NORMAL);
                return ja ? null : "Der Nutzer hat dieses Kommando abgelehnt. "
                                 + "Versuche einen anderen Weg oder frage nach.";
            }
        };
    }

    private static String kommando(ToolCall tc) {
        try {
            String k = Json.str(Json.obj(Json.parse(tc.argumentsJson())).get("kommando"));
            return k == null ? tc.argumentsJson() : k;
        } catch (RuntimeException e) { return tc.argumentsJson(); }
    }

    // --------------------------------------------------------------- Befehle

    /** @return true, wenn die Sitzung enden soll. */
    private boolean befehl(String zeile) {
        String[] teile = zeile.trim().split("\\s+", 2);
        String was = teile[0];
        String rest = teile.length > 1 ? teile[1] : null;

        switch (was) {
            case "/ende", "/quit", "/exit" -> { return true; }

            case "/neu" -> {
                verlauf = new Transcript(schaetzer);
                anzeige.zeile("  " + Terminal.GRAU + "Verlauf geleert" + Terminal.NORMAL);
            }

            case "/frei" -> {
                fragen = !fragen;
                anzeige.zeile("  " + Terminal.GRAU
                        + (fragen ? "bash fragt wieder nach" : "bash laeuft ab jetzt ungefragt")
                        + Terminal.NORMAL);
            }

            case "/speichern" -> speichern(rest);
            case "/laden"     -> laden(rest);

            case "/verlauf" -> anzeige.zeile("  " + Terminal.GRAU + verlauf.anzahl()
                    + " Eintraege · " + verlauf.schaetzeTokens() + " Token · "
                    + schaetzer + Terminal.NORMAL);

            case "/hilfe", "/?" -> hilfe();

            default -> anzeige.zeile("  " + Terminal.ROT + "unbekannt: " + was
                    + Terminal.NORMAL + Terminal.GRAU + " — /hilfe zeigt die Befehle" + Terminal.NORMAL);
        }
        return false;
    }

    private void hilfe() {
        anzeige.zeile("  " + Terminal.GRAU + """
                /neu             Verlauf verwerfen und neu anfangen
                  /speichern [n]   Sitzung nach .harness/sitzung-<n>.json schreiben
                  /laden [n]       gespeicherte Sitzung zurueckholen
                  /frei            Nachfragen vor bash an- und abschalten
                  /verlauf         Groesse des Verlaufs
                  /ende            Schluss (auch Ctrl-D)
                  Ctrl-C           laufenden Zug abbrechen, Sitzung bleibt""".stripIndent()
                + Terminal.NORMAL);
    }

    // ------------------------------------------------------- speichern / laden

    private Path datei(String name) {
        return ws.wurzel().resolve(".harness")
                 .resolve("sitzung-" + (name == null || name.isBlank() ? "letzte" : name) + ".json");
    }

    private void speichern(String name) {
        try {
            Path p = datei(name);
            Files.createDirectories(p.getParent());
            Files.writeString(p, Messages.schreibeListe(verlauf.nachrichten()));
            anzeige.zeile("  " + Terminal.GRAU + "gespeichert: " + ws.relativ(p)
                          + " (" + verlauf.anzahl() + " Eintraege)" + Terminal.NORMAL);
        } catch (IOException | RuntimeException e) {
            anzeige.zeile("  " + Terminal.ROT + "speichern fehlgeschlagen: " + e.getMessage() + Terminal.NORMAL);
        }
    }

    private void laden(String name) {
        try {
            Path p = datei(name);
            if (!Files.exists(p)) {
                anzeige.zeile("  " + Terminal.ROT + "gibt es nicht: " + ws.relativ(p) + Terminal.NORMAL);
                return;
            }
            List<Message> ms = Messages.lieseListe(Files.readString(p));
            if (ms.isEmpty()) {
                anzeige.zeile("  " + Terminal.ROT + "leere Sitzung" + Terminal.NORMAL);
                return;
            }

            // Systemprompt und erste Aufgabe wieder festnageln, damit die
            // Kuerzung sie spaeter nicht wegwirft.
            Transcript neu = new Transcript(schaetzer);
            String sys = (ms.get(0) instanceof SystemMessage s) ? s.content() : systemPrompt;
            int ab = (ms.get(0) instanceof SystemMessage) ? 1 : 0;
            String erste = (ms.size() > ab && ms.get(ab) instanceof UserMessage u) ? u.content() : "";
            neu.beginne(sys, erste);
            for (int i = ab + 1; i < ms.size(); i++) neu.add(ms.get(i));

            verlauf = neu;
            anzeige.zeile("  " + Terminal.GRAU + "geladen: " + ws.relativ(p)
                          + " (" + verlauf.anzahl() + " Eintraege, "
                          + verlauf.schaetzeTokens() + " Token)" + Terminal.NORMAL);
        } catch (IOException | RuntimeException e) {
            anzeige.zeile("  " + Terminal.ROT + "laden fehlgeschlagen: " + e.getMessage() + Terminal.NORMAL);
        }
    }
}
