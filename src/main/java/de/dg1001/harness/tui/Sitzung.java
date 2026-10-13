package de.dg1001.harness.tui;

import de.dg1001.harness.agent.Agent;
import de.dg1001.harness.agent.ContextBudget;
import de.dg1001.harness.agent.Freigabe;
import de.dg1001.harness.agent.TokenSchaetzer;
import de.dg1001.harness.agent.Transcript;
import de.dg1001.harness.agent.Umgebungsbild;
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
    private final String herkunft;
    private final InputStream in;
    private final TokenSchaetzer schaetzer;

    private final ContextBudget budget;
    private final de.dg1001.harness.wire.ChatEndpunkt endpunkt;
    private Transcript verlauf;
    private boolean fragen;              // vor bash nachfragen?
    private final de.dg1001.harness.agent.Regeln regeln;

    /** Standardname der Uebergabe. Bewusst im Projekt und nicht in .harness:
     *  die Datei ist fuer Menschen mitgedacht, nicht nur fuer den naechsten Lauf. */
    private String notizdatei = "NOTIZEN.md";

    /** Damit das Angebot nicht nach jedem Auftrag erneut kommt. */
    private boolean schonAngeboten = false;

    /** Offene Freigabefrage; nur der Hauptfaden beantwortet sie. */
    private volatile ToolCall offeneFrage;
    /** Was [v] bei der offenen Frage erlauben wuerde, oder null. */
    private volatile String offeneVorschlag;
    private final SynchronousQueue<Antwort> antwort = new SynchronousQueue<>();

    /**
     * Hinweis an das Modell, der dem naechsten Auftrag vorangestellt wird.
     * Nach /zurueck stimmt sein Bild der Dateien nicht mehr -- ohne Hinweis
     * arbeitete es an einem Stand weiter, den es nicht mehr gibt.
     */
    private String vorbemerkung = null;

    /** Was waehrend eines laufenden Zuges getippt wurde. */
    private final StringBuilder vorgetippt = new StringBuilder();

    public Sitzung(Agent agent, TokenSchaetzer schaetzer, Anzeige anzeige, InputStream in,
                   Workspace ws, ContextBudget budget, String systemPrompt, String modell,
                   boolean fragen, de.dg1001.harness.wire.ChatEndpunkt endpunkt,
                   String herkunft) {
        this.herkunft     = herkunft;
        this.endpunkt     = endpunkt;
        this.budget       = budget;
        this.agent        = agent;
        this.schaetzer    = schaetzer;
        this.anzeige      = anzeige;
        this.eingabe      = new Eingabe(in);
        this.in           = in;
        this.ws           = ws;
        this.systemPrompt = systemPrompt;
        this.modell       = modell;
        this.fragen       = fragen;
        this.regeln       = de.dg1001.harness.agent.Regeln.aus(ws.projekt());
        this.verlauf      = new Transcript(schaetzer);
        anzeige.setzeFrei(!fragen);

        agent.mitFreigabe(freigabe());
    }

    // ------------------------------------------------------------ Hauptschleife

    public void lauf() throws IOException {
        kopf();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            while (true) {
                String schon = vorgetippt.toString();
                vorgetippt.setLength(0);
                Eingabe.Ergebnis e = eingabe.lies(
                        "  " + Terminal.CYAN + "› " + Terminal.NORMAL, schon);
                if (e.art() == Eingabe.Art.ENDE) { anzeige.zeile("  " + Terminal.GRAU + "tschuess" + Terminal.NORMAL); return; }
                if (e.art() == Eingabe.Art.LEER) continue;

                String text = e.text();
                eingabe.merke(text);

                if (text.startsWith("/")) {
                    if (befehl(text, exec)) return;
                    continue;
                }
                beauftrage(text, exec);
            }
        }
    }

    private void kopf() {
        anzeige.leerzeile();
        // Die Herkunft gehoert in die Kopfzeile, nicht nur der Modellname:
        // der lokale Server meldet sich unter demselben Namen wie die
        // gehostete Schnittstelle, und eines von beiden rechnet ab.
        boolean bezahlt = !herkunft.startsWith("lokal");
        anzeige.zeile("  " + Terminal.FETT + "jaja" + Terminal.NORMAL + " · " + modell
                      + " · " + (bezahlt ? Terminal.GELB : Terminal.GRUEN) + herkunft
                      + Terminal.NORMAL
                      + " · " + Terminal.GRAU + ws.wurzel() + Terminal.NORMAL);
        anzeige.zeile("  " + Terminal.GRAU
                      + (fragen ? "bash fragt nach · " : "bash laeuft ungefragt · ")
                      + (ws.sandkasten() != null ? "im Sandkasten · " : "")
                      + "/hilfe zeigt die Befehle" + Terminal.NORMAL);
        anzeige.leerzeile();
    }

    // ------------------------------------------------------------- ein Auftrag

    private void beauftrage(String text, ExecutorService exec) throws IOException {
        ws.sicherung().neuerStand(text);
        if (vorbemerkung != null) { text = vorbemerkung + "\n\n" + text; vorbemerkung = null; }
        // Das Umgebungsbild auch hier, nicht nur im Stapelbetrieb: gemessen
        // halbierte es dort die Zuege bis zum ersten Schreiben (12 -> 6).
        if (verlauf.anzahl() == 0)
            verlauf.beginne(systemPrompt, Umgebungsbild.anhaengen(text, ws));
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
            ws.sicherung().standAbschliessen();
            anzeige.zeile("  " + Terminal.ROT + "abgestuerzt: " + ex.getCause() + Terminal.NORMAL);
            return;
        }
        anzeige.statusBeenden();
        // Der Stand, den der Agent hinterlassen hat -- was danach von Hand
        // geaendert wird, nimmt /zurueck nicht mit zurueck.
        ws.sicherung().standAbschliessen();
        String w = ws.sicherung().warnung();
        if (w != null) anzeige.hinweis(w);

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

        vielleichtUebergabeAnbieten(erg, exec);
    }

    // ------------------------------------------------------------- Uebergabe

    /**
     * Bietet an, den Stand zu sichern, bevor der Kontext ausgeht.
     *
     * <p>Kuerzen verschiebt das Problem nur: irgendwann ist auch das
     * kuerzbare aufgebraucht, und dann endet die Sitzung mitten in der
     * Arbeit. Eine Uebergabedatei loest es — sie kostet einen Zug, ist
     * hinterher lesbar, und der frische Verlauf faengt bei ein paar hundert
     * Token an statt bei zehntausend.
     *
     * <p>Gefragt wird, nicht gemacht. Den Verlauf wegzuwerfen ist nicht
     * umkehrbar, und nur der Mensch weiss, ob gerade ein guter Moment ist.
     */
    private void vielleichtUebergabeAnbieten(Agent.Ergebnis erg, ExecutorService exec)
            throws IOException {
        boolean erschoepft = erg.status() == Agent.Status.KONTEXT_ERSCHOEPFT;
        boolean knapp = budget.mussKuerzen(verlauf.schaetzeTokens());
        if (!erschoepft && !knapp) { schonAngeboten = false; return; }
        if (schonAngeboten && !erschoepft) return;
        schonAngeboten = true;

        anzeige.zeile("  " + Terminal.GELB + (erschoepft
                ? "Der Kontext ist voll."
                : "Der Kontext wird knapp.") + Terminal.NORMAL
                + "  " + Terminal.GRAU + budget.bericht(verlauf.schaetzeTokens()) + Terminal.NORMAL);
        if (!jaNein("Stand als " + notizdatei + " sichern und mit frischem Verlauf weitermachen?"))
            return;
        uebergabe(exec);
    }

    /**
     * Laesst das Modell eine Uebergabe schreiben und faengt danach neu an.
     *
     * <p>Das Modell schreibt sie, nicht der Harness: was von zwanzig Zuegen
     * wichtig war, weiss nur, wer sie gemacht hat. Ein mechanischer Auszug
     * aus dem Verlauf waere eine Liste von Dateinamen ohne das Warum.
     *
     * <p><b>Der Verlauf wird erst verworfen, wenn die Datei wirklich da ist.</b>
     * Andernfalls stuende man ohne beides da — und das ist der eine Fehler,
     * den man hier nicht machen darf.
     */
    private void uebergabe(ExecutorService exec) throws IOException {
        verlauf.add(new UserMessage(uebergabeAuftrag(notizdatei)));

        anzeige.leerzeile();
        agent.abbruchZuruecksetzen();
        anzeige.statusStarten("schreibt die Uebergabe");
        Future<Agent.Ergebnis> f = exec.submit(() -> agent.lauf(verlauf));
        tastaturWaehrendArbeit(f);
        Agent.Ergebnis e;
        try { e = f.get(); }
        catch (Exception ex) {
            anzeige.statusBeenden();
            anzeige.zeile("  " + Terminal.ROT + "Uebergabe fehlgeschlagen: " + ex.getCause()
                          + " — der Verlauf bleibt" + Terminal.NORMAL);
            return;
        }
        anzeige.statusBeenden();

        Path ziel = ws.wurzel().resolve(notizdatei);
        if (!Files.exists(ziel)) {
            anzeige.zeile("  " + Terminal.ROT + notizdatei + " wurde nicht geschrieben ("
                          + e.status() + ") — der Verlauf bleibt unangetastet" + Terminal.NORMAL);
            return;
        }

        long zeilen;
        try { zeilen = Files.readAllLines(ziel).size(); } catch (IOException ex) { zeilen = 0; }

        verlauf = new Transcript(schaetzer);
        verlauf.beginne(systemPrompt, Umgebungsbild.anhaengen(
                "Wir setzen eine laufende Arbeit fort. Der Stand steht in "
                + notizdatei + ". Lies die Datei zuerst und arbeite dann dort weiter.", ws));
        schonAngeboten = false;

        anzeige.zeile("  " + Terminal.GRUEN + "Uebergabe in " + notizdatei + " (" + zeilen
                      + " Zeilen)" + Terminal.NORMAL + Terminal.GRAU
                      + " — frischer Verlauf, " + verlauf.schaetzeTokens() + " Token"
                      + Terminal.NORMAL);
        anzeige.leerzeile();
    }

    /** Der Auftrag an das Modell. Er beschreibt, was jemand braucht, der den
     *  Verlauf nicht kennt — nicht, was schoen aussieht. */
    static String uebergabeAuftrag(String datei) {
        return """
               Der bisherige Verlauf wird gleich verworfen, damit wir mit frischem \
               Kontext weiterarbeiten koennen. Schreibe jetzt mit dem write-Werkzeug \
               eine Uebergabe nach %s.

               Sie muss allein genuegen, um die Arbeit fortzusetzen, ohne diesen \
               Verlauf zu kennen:

               - Ziel der Aufgabe in eigenen Worten
               - was fertig ist, mit Dateinamen
               - was noch offen ist, als Liste
               - Entscheidungen und Annahmen, die man sonst neu treffen muesste
               - was zuletzt schiefging und was noch ungeprueft ist

               Kurz fassen, aber nichts weglassen, was man sich sonst neu \
               erarbeiten muesste. Schreibe die Datei und tue danach nichts weiter."""
               .formatted(datei);
    }

    /** Einzelne Taste, gelesen wie eine Freigabe. */
    private boolean jaNein(String frage) throws IOException {
        anzeige.zeile("  " + Terminal.GELB + frage + Terminal.NORMAL);
        anzeige.zeile("  " + Terminal.GRAU + "  [j] ja   [n] nein" + Terminal.NORMAL);
        while (true) {
            int c = Terminal.liesZeichen(in);
            if (c < 0) return false;
            Antwort a = freigabeAntwort(c);
            if (a == Antwort.JA || a == Antwort.NEIN) {
                boolean ja = a == Antwort.JA;
                anzeige.zeile("  " + Terminal.GRAU + (ja ? "  ja" : "  nein") + Terminal.NORMAL);
                return ja;
            }
        }
    }

    /**
     * Tastatur abfragen, solange der Agent arbeitet.
     *
     * <p>Abfragen statt blockierend lesen: ein blockierender Lesevorgang haenge
     * noch, wenn der Agent laengst fertig ist, und verschluckte dann den ersten
     * Tastendruck der naechsten Eingabe.
     */
    private void tastaturWaehrendArbeit(Future<?> f) throws IOException {
        tastaturWaehrendArbeit(f, agent::brichAb);
    }

    private void tastaturWaehrendArbeit(Future<?> f, Runnable beiAbbruch) throws IOException {
        while (!f.isDone()) {
            if (in.available() <= 0) {
                try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                continue;
            }
            int c = Terminal.liesZeichen(in);

            if (offeneFrage != null) {
                Antwort a = freigabeAntwort(c);
                // [v] ohne angebotenes Muster ist keine Antwort -- sonst
                // lehnte die Taste ab, was sie haette erlauben sollen.
                if (a == Antwort.MUSTER && offeneVorschlag == null) continue;
                if (a != Antwort.KEINE) gib(a);
                continue;
            }

            if (c == 3) {
                beiAbbruch.run();
                anzeige.hinweis("Abbruch angefordert");
                continue;
            }

            // Ctrl-F schaltet das Nachfragen um, waehrend der Agent laeuft.
            // Am Prompt gibt es dafuer /frei und /fragen; hier gibt es keinen
            // Prompt, und gerade waehrend eines langen Laufs merkt man, dass
            // man doch lieber gefragt werden moechte -- oder eben nicht mehr.
            // Ein Steuerzeichen, damit es nie im vorgetippten Text landet.
            if (c == 6) {
                fragen = !fragen;
                anzeige.setzeFrei(!fragen);
                anzeige.hinweis(fragen
                        ? "bash fragt ab dem naechsten Aufruf wieder nach"
                        : "bash laeuft ab dem naechsten Aufruf ungefragt");
                continue;
            }

            // Alles andere ist der naechste Auftrag, schon getippt. Aufheben
            // statt wegwerfen: waehrend eines Zuges, der Minuten dauert,
            // tippt man selbstverstaendlich weiter, und stillschweigend
            // verschluckter Text ist der aergerlichste Fehler einer Eingabe.
            // Der Wagenruecklauf wird verworfen -- abgeschickt wird bewusst,
            // nicht durch ein Zeitfenster, das der Nutzer nicht sieht.
            if (c >= 32) vorgetippt.appendCodePoint(c);
            else if (c == 127 && vorgetippt.length() > 0)
                vorgetippt.deleteCharAt(vorgetippt.length() - 1);
        }
    }

    /** Antwort auf eine Freigabefrage. */
    public enum Antwort { JA, NEIN, IMMER, MUSTER, KEINE }

    /**
     * Deutet einen Tastendruck als Antwort auf eine Freigabefrage.
     *
     * <p>Nur {@code j}, {@code n} und {@code f} zaehlen; alles andere wird
     * ignoriert und die Frage bleibt stehen. Das klingt kleinlich, ist aber
     * der Unterschied zwischen einer Frage und einer Falle: einmal galt jede
     * Taste ausser {@code j} als Ablehnung, und wer waehrend eines langen
     * Zuges seinen naechsten Auftrag tippte, lehnte damit unbemerkt ein
     * Kommando ab. Beobachtet an einem {@code /} — dem ersten Zeichen von
     * {@code /ende}.
     *
     * <p>Auch die Eingabetaste bedeutet <em>nichts</em>. Eine Vorgabe per
     * Enter waere bequem, aber bei einer Frage, die eine Shell startet, soll
     * die Zustimmung ausdruecklich sein. Aus demselben Grund liegt {@code f}
     * nicht neben {@code j}: es schaltet das Fragen dauerhaft ab, und das
     * soll kein Vertipper erledigen.
     */
    static Antwort freigabeAntwort(int c) {
        if (c == 'j' || c == 'J' || c == 'y' || c == 'Y') return Antwort.JA;
        if (c == 'n' || c == 'N' || c == 3)               return Antwort.NEIN;
        if (c == 'f' || c == 'F')                         return Antwort.IMMER;
        // v wie "vertrauen" -- bewusst nicht e: das ist der haeufigste
        // Buchstabe, und wer waehrend einer Frage seinen naechsten Auftrag
        // tippt, wuerde damit ein Muster fuer die ganze Sitzung freigeben.
        if (c == 'v' || c == 'V')                         return Antwort.MUSTER;
        return Antwort.KEINE;
    }

    private void gib(Antwort a) {
        try { antwort.put(a); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // -------------------------------------------------------------- Freigabe

    private Freigabe freigabe() {
        return tc -> {
            if (!tc.name().equals("bash")) return null;
            String kommando = kommando(tc);
            // Verbote vor allem anderen, auch wenn gerade nicht gefragt wird.
            String verboten = regeln.verbotenDurch(kommando);
            if (verboten != null) {
                anzeige.zeile("  " + Terminal.ROT + "bash verboten (" + verboten + "): "
                              + Terminal.NORMAL + kommando);
                return de.dg1001.harness.agent.Regeln.verbotsmeldung(verboten);
            }
            if (!fragen || regeln.erlaubt(kommando)) return null;

            // Werkzeuge laufen nebenlaeufig: immer nur eine Frage auf einmal,
            // sonst beantwortet ein Tastendruck die falsche.
            synchronized (this) {
                String vorschlag = de.dg1001.harness.agent.Regeln.vorschlag(kommando);
                anzeige.zeile("");
                anzeige.zeile("  " + Terminal.GELB + "bash?" + Terminal.NORMAL + "  " + kommando);
                anzeige.zeile("  " + Terminal.GRAU
                        + "  [j] ausfuehren   [n] ablehnen   "
                        + (vorschlag != null ? "[v] " + vorschlag + " ungefragt   " : "")
                        + "[f] ab jetzt nichts mehr fragen" + Terminal.NORMAL);
                offeneVorschlag = vorschlag;

                offeneFrage = tc;
                Antwort a;
                try { a = antwort.take(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); a = Antwort.NEIN; }
                finally { offeneFrage = null; }

                if (a == Antwort.MUSTER && offeneVorschlag != null) {
                    regeln.erlaube(offeneVorschlag);
                    anzeige.zeile("  " + Terminal.GRUEN + "  ausgefuehrt" + Terminal.NORMAL
                            + Terminal.GRAU + " — " + offeneVorschlag + " laeuft in dieser Sitzung "
                            + "ungefragt; dauerhaft: in .jaja.json unter \"erlauben\"" + Terminal.NORMAL);
                    return null;
                }
                if (a == Antwort.IMMER) {
                    fragen = false;
                    anzeige.setzeFrei(true);
                    anzeige.zeile("  " + Terminal.GRUEN + "  ausgefuehrt" + Terminal.NORMAL
                            + Terminal.GRAU + " — bash laeuft ab jetzt ungefragt, "
                            + "/fragen schaltet es wieder ein" + Terminal.NORMAL);
                    return null;
                }
                boolean ja = a == Antwort.JA;
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
    private boolean befehl(String zeile, ExecutorService exec) throws IOException {
        String[] teile = zeile.trim().split("\\s+", 2);
        String was = teile[0];
        String rest = teile.length > 1 ? teile[1] : null;

        switch (was) {
            case "/ende", "/quit", "/exit" -> { return true; }

            case "/neu" -> {
                verlauf = new Transcript(schaetzer);
                anzeige.zeile("  " + Terminal.GRAU + "Verlauf geleert" + Terminal.NORMAL);
            }

            // Zwei Befehle statt eines Umschalters: bei einem Umschalter muss
            // man wissen, wo man gerade steht, und genau das weiss man nach
            // zwanzig Minuten Arbeit nicht mehr.
            case "/frei" -> {
                fragen = false; anzeige.setzeFrei(true);
                anzeige.zeile("  " + Terminal.GRAU
                        + "bash laeuft ab jetzt ungefragt — /fragen schaltet es wieder ein"
                        + Terminal.NORMAL);
            }

            case "/fragen" -> {
                fragen = true; anzeige.setzeFrei(false);
                anzeige.zeile("  " + Terminal.GRAU + "bash fragt wieder nach" + Terminal.NORMAL);
            }

            case "/zusammenfassen", "/uebergabe" -> {
                if (rest != null && !rest.isBlank()) notizdatei = rest.trim();
                if (verlauf.anzahl() == 0)
                    anzeige.zeile("  " + Terminal.GRAU + "noch nichts zu uebergeben"
                                  + Terminal.NORMAL);
                else uebergabe(exec);
            }

            case "/zurueck", "/undo" -> zurueck();

            case "/zeige", "/lies" -> zeige(rest);

            case "/karte" -> karte(rest);

            case "/index" -> index(exec, rest);

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
        String[] zeilen = {
            "/neu                 Verlauf verwerfen und neu anfangen",
            "/zusammenfassen [d]  Stand nach NOTIZEN.md (oder d) sichern, frisch weiter",
            "/zurueck             Aenderungen des letzten Auftrags zuruecknehmen",
            "/speichern [n]       Sitzung nach .harness/sitzung-<n>.json schreiben",
            "/laden [n]           gespeicherte Sitzung zurueckholen",
            "/zeige [d]           Datei anzeigen, Markdown gesetzt (Vorgabe NOTIZEN.md)",
            "/karte [stichwort]   Ueberblick ueber das Projekt",
            "/index [glob]        Kurzbeschreibungen erzeugen, wahlweise nur fuer einen Teil",
            "/frei                bash ohne Nachfrage ausfuehren",
            "/fragen              vor bash wieder nachfragen",
            "/verlauf             Groesse des Verlaufs",
            "/ende                Schluss (auch Ctrl-D)",
            "",
            "waehrend ein Zug laeuft:",
            "Ctrl-C               Zug abbrechen, Sitzung und Verlauf bleiben",
            "Ctrl-F               zwischen frei und fragen wechseln",
            "",
            "bei einer bash-Frage:  [j] ausfuehren  [n] ablehnen  [v] Muster ungefragt  [f] nicht mehr fragen",
        };
        for (String z : zeilen)
            anzeige.zeile(z.isEmpty() ? "" : "  " + Terminal.GRAU + z + Terminal.NORMAL);
    }

    /**
     * Zeigt die Quellenkarte.
     *
     * <p>Dasselbe, was das Modell ueber das Werkzeug bekommt — hilfreich, um
     * zu sehen, womit es arbeitet, bevor man ihm eine Aufgabe stellt.
     */
    private void karte(String stichwort) {
        try {
            de.dg1001.harness.karte.Karte k = new de.dg1001.harness.karte.Karte(ws);
            var s = k.auffrischen();
            String wort = (stichwort == null || stichwort.isBlank()) ? null : stichwort.trim();
            anzeige.leerzeile();
            anzeige.zeile(k.uebersicht(k.suche(wort, null),
                    wort == null ? "der Auswahl" : "'" + wort + "'"));
            if (s.gelesen() > 0)
                anzeige.zeile("  " + Terminal.GRAU + s.gelesen()
                        + " Datei(en) neu eingelesen" + Terminal.NORMAL);
            anzeige.leerzeile();
        } catch (IOException | RuntimeException e) {
            anzeige.zeile("  " + Terminal.ROT + "Karte nicht lesbar: " + e.getMessage()
                          + Terminal.NORMAL);
        }
    }

    /**
     * Laesst das Modell die Karte beschreiben.
     *
     * <p>Laeuft wie ein Auftrag: eigener Faden, Statuszeile, Ctrl-C bricht ab.
     * Ein Durchlauf ueber ein groesseres Projekt dauert Minuten, und wer ihn
     * startet, will ihn auch wieder loswerden koennen.
     */
    private void index(ExecutorService exec, String glob) throws IOException {
        var karte = new de.dg1001.harness.karte.Karte(ws);
        var indexer = new de.dg1001.harness.karte.Indexer(endpunkt, ws);

        anzeige.leerzeile();
        anzeige.statusStarten("beschreibt die Karte");
        var muster = (glob == null || glob.isBlank()) ? null
                : de.dg1001.harness.tools.GlobTool.muster(glob.trim());
        Future<de.dg1001.harness.karte.Indexer.Ergebnis> f =
                exec.submit(() -> indexer.lauf(karte, muster, anzeige::hinweis));
        tastaturWaehrendArbeit(f, indexer::brichAb);

        try {
            var e = f.get();
            anzeige.statusBeenden();
            anzeige.zeile("  " + Terminal.GRUEN + e.beschrieben() + " Datei(en) beschrieben"
                    + Terminal.NORMAL + Terminal.GRAU
                    + (e.offen() > 0 ? ", " + e.offen() + " offen" : "")
                    + (e.abgebrochen() ? " (abgebrochen — /index macht weiter)" : "")
                    + Terminal.NORMAL);
        } catch (Exception ex) {
            anzeige.statusBeenden();
            anzeige.zeile("  " + Terminal.ROT + "Index fehlgeschlagen: " + ex.getCause()
                          + Terminal.NORMAL);
        }
        anzeige.leerzeile();
    }

    /**
     * Nimmt zurueck, was write und edit im letzten Auftrag geaendert haben.
     *
     * <p>Sagt jedes Mal dazu, was es nicht kann: Aenderungen durch bash. Wer
     * das vergisst und sich auf /zurueck verlaesst, nachdem das Modell per
     * {@code sed -i} gearbeitet hat, verliert genau das, was er retten wollte.
     */
    private void zurueck() {
        try {
            var e = ws.sicherung().zurueck();
            if (e == null) {
                anzeige.zeile("  " + Terminal.GRAU + "nichts zurueckzunehmen" + Terminal.NORMAL);
                return;
            }
            List<String> namen = new java.util.ArrayList<>();
            for (Path p : e.wiederhergestellt()) namen.add(ws.relativ(p));
            for (Path p : e.geloescht()) namen.add(ws.relativ(p) + " (entfernt)");
            anzeige.zeile("  " + Terminal.GRUEN + "zurueckgenommen" + Terminal.NORMAL + " — "
                    + String.join(", ", namen));
            for (Path p : e.nichtMoeglich())
                anzeige.zeile("  " + Terminal.ROT + "nicht gesichert (zu gross): " + ws.relativ(p)
                              + Terminal.NORMAL);
            for (Path p : e.vonHand())
                anzeige.zeile("  " + Terminal.GELB + "nach dem Auftrag noch einmal geaendert, "
                              + "nicht angefasst: " + ws.relativ(p) + Terminal.NORMAL);
            anzeige.zeile("  " + Terminal.GRAU + "Auftrag: " + kuerze(e.beschreibung(), 60)
                    + (e.mitBash() ? " · auch Aenderungen durch bash"
                                   : " · Aenderungen durch bash sind nicht erfasst (kein git)")
                    + (ws.sicherung().anzahl() > 0 ? " · /zurueck geht noch "
                       + ws.sicherung().anzahl() + "x weiter" : "") + Terminal.NORMAL);
            String hinweis = "[Hinweis des Harness: Der Nutzer hat die Aenderungen des letzten "
                    + "Auftrags zurueckgenommen. Wieder im Stand davor: "
                    + String.join(", ", namen) + ". Lies diese Dateien neu, bevor du dich auf "
                    + "ihren Inhalt verlaesst.]";
            vorbemerkung = vorbemerkung == null ? hinweis : vorbemerkung + "\n" + hinweis;
        } catch (IOException | RuntimeException ex) {
            anzeige.zeile("  " + Terminal.ROT + "zuruecknehmen fehlgeschlagen: " + ex.getMessage()
                          + Terminal.NORMAL);
        }
    }

    private static String kuerze(String s, int n) {
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    /** Zeigt eine Datei aus dem Arbeitsbereich; Markdown wird gesetzt. */
    private void zeige(String name) {
        if (name == null || name.isBlank()) name = notizdatei;
        try {
            Path p = ws.pruefeVorhandenen(ws.aufloesen(name.trim()));
            if (!Files.exists(p)) {
                anzeige.zeile("  " + Terminal.ROT + "gibt es nicht: " + name + Terminal.NORMAL);
                return;
            }
            String text = Files.readString(p);
            anzeige.leerzeile();
            anzeige.zeile(name.trim().toLowerCase().endsWith(".md")
                    ? new Markdown(Terminal.breite() - 4).setze(text)
                    : text);
            anzeige.leerzeile();
        } catch (IOException | RuntimeException e) {   // AusbruchFehler ist eine RuntimeException
            anzeige.zeile("  " + Terminal.ROT + "kann " + name + " nicht zeigen: "
                          + e.getMessage() + Terminal.NORMAL);
        }
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
