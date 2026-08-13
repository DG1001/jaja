package de.dg1001.harness.karte;

import de.dg1001.harness.wire.ChatEndpunkt;
import de.dg1001.harness.wire.Json;
import de.dg1001.harness.wire.Messages.ChatResponse;
import de.dg1001.harness.wire.Messages.Message;
import de.dg1001.harness.wire.Messages.SystemMessage;
import de.dg1001.harness.wire.Messages.UserMessage;
import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Laesst das Modell beschreiben, wozu die Dateien da sind.
 *
 * <p>Die zweite Haelfte der Karte. Die Struktur sagt, <em>was</em> in einer
 * Datei steht — Definitionen, Verweise —, aber nicht, <em>wozu</em> sie da ist.
 * Genau das ist die Frage, die man sich sonst durch Lesen beantwortet.
 *
 * <p><b>Gebuendelt und nach jedem Buendel gesichert.</b> Auf lokaler Hardware
 * dauert ein Durchlauf ueber ein paar hundert Dateien zehn bis zwanzig Minuten.
 * Wer das startet, wird zwischendurch abbrechen — und darf dabei nichts
 * verlieren. Deshalb wird nach jeder Anfrage geschrieben, und ein zweiter
 * Aufruf macht dort weiter, wo der erste aufhoerte.
 *
 * <p>Angefangen wird bei den Dateien mit dem hoechsten Eingangsgrad. Bricht
 * jemand nach der Haelfte ab, sind die beschrieben, auf die es ankommt.
 */
public final class Indexer {

    /** Hoechstens so viele Dateien in eine Anfrage. */
    static final int JE_ANFRAGE = 8;

    /** Und hoechstens so viele Zeichen Quelltext — sonst sprengt ein Buendel
     *  das Fenster, und zwar ausgerechnet bei den grossen Dateien. */
    static final int ZEICHEN_JE_ANFRAGE = 12_000;

    /** Vom Anfang jeder Datei. Wozu etwas da ist, steht fast immer oben. */
    static final int ZEILEN_JE_DATEI = 60;
    static final int ZEICHEN_JE_DATEI = 2_500;

    private static final String AUFTRAG = """
            Du beschreibst Quelldateien eines Projekts, damit sich ein anderer \
            Entwickler zurechtfindet, ohne sie zu lesen.

            Schreibe zu jeder Datei genau einen Satz und drei bis fuenf Stichworte.

            Der Satz sagt, wozu die Datei da ist, nicht was in ihr steht. Also \
            nicht "enthaelt die Klasse Rabatt", sondern "berechnet Mengenrabatte \
            und Nettopreise". Keine Einleitung, kein Wiederholen des Dateinamens.

            Antworte ausschliesslich mit einem JSON-Array, ohne Text davor oder \
            danach, ohne Codeblock-Zaun:
            [{"pfad":"...","beschreibung":"...","stichworte":["...","..."]}]""";

    private final ChatEndpunkt client;
    private final Workspace ws;
    private final AtomicBoolean abbruch = new AtomicBoolean(false);

    public Indexer(ChatEndpunkt client, Workspace ws) {
        this.client = client;
        this.ws = ws;
    }

    /** Darf aus einem anderen Faden gerufen werden. */
    public void brichAb() { abbruch.set(true); }

    public record Ergebnis(int beschrieben, int offen, int buendel, boolean abgebrochen) {}

    /**
     * @param melder bekommt je Buendel eine Zeile Fortschritt
     */
    public Ergebnis lauf(Karte karte, Consumer<String> melder) throws IOException {
        karte.auffrischen();

        List<Quelldatei> offen = new ArrayList<>();
        for (Quelldatei q : karte.suche(null, null))          // nach Eingangsgrad sortiert
            if (!q.beschreibungGueltig() && !q.definitionen().isEmpty()) offen.add(q);

        if (offen.isEmpty()) {
            melder.accept("Alle " + karte.anzahl() + " Dateien haben eine gueltige Beschreibung.");
            return new Ergebnis(0, 0, 0, false);
        }
        melder.accept(offen.size() + " von " + karte.anzahl() + " Dateien brauchen eine Beschreibung.");

        int beschrieben = 0, buendel = 0;
        for (List<Quelldatei> gruppe : buendeln(offen)) {
            if (abbruch.get())
                return new Ergebnis(beschrieben, offen.size() - beschrieben, buendel, true);

            buendel++;
            int neu = beschreibe(karte, gruppe, melder);
            beschrieben += neu;
            karte.sichern();                                  // nach jedem Buendel

            melder.accept("Buendel " + buendel + ": " + neu + "/" + gruppe.size()
                    + " beschrieben (" + beschrieben + "/" + offen.size() + " gesamt)");
        }
        return new Ergebnis(beschrieben, offen.size() - beschrieben, buendel, false);
    }

    // ------------------------------------------------------------- buendeln

    static List<List<Quelldatei>> buendeln(List<Quelldatei> offen) {
        List<List<Quelldatei>> aus = new ArrayList<>();
        List<Quelldatei> jetzt = new ArrayList<>();
        int zeichen = 0;

        for (Quelldatei q : offen) {
            int kosten = Math.min(q.groesse(), ZEICHEN_JE_DATEI);
            if (!jetzt.isEmpty()
                    && (jetzt.size() >= JE_ANFRAGE || zeichen + kosten > ZEICHEN_JE_ANFRAGE)) {
                aus.add(jetzt);
                jetzt = new ArrayList<>();
                zeichen = 0;
            }
            jetzt.add(q);
            zeichen += kosten;
        }
        if (!jetzt.isEmpty()) aus.add(jetzt);
        return aus;
    }

    // ------------------------------------------------------------ ein Buendel

    private int beschreibe(Karte karte, List<Quelldatei> gruppe, Consumer<String> melder) {
        StringBuilder frage = new StringBuilder();
        for (Quelldatei q : gruppe) {
            frage.append("=== ").append(q.pfad()).append(" ===\n")
                 .append(anfang(q)).append("\n\n");
        }

        ChatResponse a;
        try {
            List<Message> verlauf = List.of(new SystemMessage(AUFTRAG),
                                            new UserMessage(frage.toString()));
            a = client.complete(verlauf, List.of());
        } catch (RuntimeException e) {
            // Ein gescheitertes Buendel kostet seine Dateien, nicht den Lauf.
            melder.accept("Buendel fehlgeschlagen: " + e.getMessage());
            return 0;
        }

        return uebernimm(karte, gruppe, a.message().content(), melder);
    }

    /** Der Anfang der Datei, gedeckelt. Wozu sie da ist, steht fast immer oben. */
    private String anfang(Quelldatei q) {
        try {
            String text = Files.readString(ws.wurzel().resolve(q.pfad()));
            String[] zeilen = text.split("\n", -1);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < Math.min(zeilen.length, ZEILEN_JE_DATEI); i++) {
                if (b.length() + zeilen[i].length() > ZEICHEN_JE_DATEI) break;
                b.append(zeilen[i]).append('\n');
            }
            return b.toString();
        } catch (MalformedInputException e) {
            return "(keine Textdatei)";
        } catch (IOException e) {
            return "(nicht lesbar)";
        }
    }

    private int uebernimm(Karte karte, List<Quelldatei> gruppe, String antwort,
                          Consumer<String> melder) {
        if (antwort == null || antwort.isBlank()) {
            melder.accept("Buendel ohne Antwort uebersprungen");
            return 0;
        }
        List<Object> eintraege;
        try {
            eintraege = Json.arr(Json.parse(nurJson(antwort)));
        } catch (RuntimeException e) {
            melder.accept("Antwort nicht lesbar, Buendel uebersprungen: " + e.getMessage());
            return 0;
        }

        int uebernommen = 0;
        for (Object o : eintraege) {
            String pfad = Json.str(Json.feld(o, "pfad"));
            String text = Json.str(Json.feld(o, "beschreibung"));
            if (pfad == null || text == null || text.isBlank()) continue;

            Quelldatei ziel = passendeDatei(gruppe, pfad);
            if (ziel == null) continue;          // erfundener Pfad: still verwerfen

            List<String> worte = new ArrayList<>();
            for (Object w : Json.arr(Json.feld(o, "stichworte"))) {
                String s = Json.str(w);
                if (s != null && !s.isBlank() && worte.size() < 6) worte.add(s.strip());
            }
            karte.setze(ziel.mitBeschreibung(einzeilig(text), worte));
            uebernommen++;
        }
        return uebernommen;
    }

    /** Das Modell kuerzt Pfade gern ab — auf den Dateinamen zurueckfallen. */
    private static Quelldatei passendeDatei(List<Quelldatei> gruppe, String pfad) {
        for (Quelldatei q : gruppe) if (q.pfad().equals(pfad)) return q;
        for (Quelldatei q : gruppe)
            if (q.pfad().endsWith("/" + pfad) || pfad.endsWith("/" + q.pfad())) return q;
        for (Quelldatei q : gruppe)
            if (dateiname(q.pfad()).equals(dateiname(pfad))) return q;
        return null;
    }

    private static String dateiname(String p) {
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    private static String einzeilig(String s) {
        String t = s.replace('\n', ' ').replace('\r', ' ').strip();
        while (t.contains("  ")) t = t.replace("  ", " ");
        return t.length() > 300 ? t.substring(0, 299) + "…" : t;
    }

    /**
     * Schaelt das JSON-Array aus der Antwort.
     *
     * <p>Der Auftrag verbietet Text davor und danach; Modelle halten sich nicht
     * immer daran und schreiben gern einen Codeblock-Zaun drumherum. Von der
     * ersten eckigen Klammer bis zur letzten ist die verlaesslichste Regel, die
     * ohne Parser auskommt.
     */
    static String nurJson(String antwort) {
        int auf = antwort.indexOf('[');
        int zu  = antwort.lastIndexOf(']');
        return (auf >= 0 && zu > auf) ? antwort.substring(auf, zu + 1) : antwort;
    }
}
