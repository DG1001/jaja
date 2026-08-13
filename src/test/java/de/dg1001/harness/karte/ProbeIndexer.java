package de.dg1001.harness.karte;

import de.dg1001.harness.wire.ChatEndpunkt;
import de.dg1001.harness.wire.Messages.AssistantMessage;
import de.dg1001.harness.wire.Messages.ChatResponse;
import de.dg1001.harness.wire.Messages.FinishReason;
import de.dg1001.harness.wire.Messages.Message;
import de.dg1001.harness.wire.Messages.ToolSpec;
import de.dg1001.harness.wire.Messages.Usage;
import de.dg1001.harness.ws.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Prueft das Beschreiben der Karte — ohne Modellserver.
 *
 * <p>Geprueft wird vor allem, was schiefgeht: eine Antwort ohne JSON, ein
 * erfundener Dateipfad, ein Buendel, das der Server verweigert. Ein Indexlauf
 * dauert Minuten; er darf an keiner dieser Stellen alles wegwerfen, was schon
 * geschafft war.
 */
public final class ProbeIndexer {

    private static int fehlgeschlagen = 0;

    /** Endpunkt, der vorgegebene Antworten abspult und die Fragen mitschreibt. */
    private static final class Skript implements ChatEndpunkt {
        private final List<String> antworten = new ArrayList<>();
        final List<String> fragen = new ArrayList<>();
        private int i = 0;
        private final boolean wirftBeimErsten;

        Skript(boolean wirftBeimErsten, String... a) {
            this.wirftBeimErsten = wirftBeimErsten;
            antworten.addAll(List.of(a));
        }
        @Override public ChatResponse complete(List<Message> verlauf, List<ToolSpec> w) {
            fragen.add(verlauf.get(verlauf.size() - 1).toString());
            if (wirftBeimErsten && i == 0) { i++; throw new RuntimeException("HTTP 500: kaputt"); }
            String a = i < antworten.size() ? antworten.get(i) : "[]";
            i++;
            return new ChatResponse(new AssistantMessage(a, null, List.of()),
                                    FinishReason.STOP, Usage.LEER);
        }
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("jaja-indexer");
        Workspace ws = new Workspace(tmp);
        schreibe(tmp, "rabatt.py", "def staffel(m):\n    return 0.9\n");
        schreibe(tmp, "beleg.py", "import rabatt\n\ndef summe(x):\n    return x\n");

        // -------------------------------------------------- glatter Durchlauf
        {
            Skript s = new Skript(false, """
                    [{"pfad":"rabatt.py","beschreibung":"Berechnet Mengenrabatte.",
                      "stichworte":["preis","rabatt"]},
                     {"pfad":"beleg.py","beschreibung":"Summiert Belege.",
                      "stichworte":["beleg"]}]""");
            Karte k = new Karte(ws);
            Indexer.Ergebnis e = new Indexer(s, ws).lauf(k, m -> {});

            pruefe("beide Dateien beschrieben", e.beschrieben(), 2);
            pruefe("nichts offen", e.offen(), 0);
            pruefe("in einer Anfrage erledigt", e.buendel(), 1);
            pruefe("Beschreibung steht in der Karte",
                   k.dateien().get("rabatt.py").beschreibung(), "Berechnet Mengenrabatte.");
            pruefe("Stichworte auch",
                   k.dateien().get("rabatt.py").stichworte(), List.of("preis", "rabatt"));
            pruefe("und sie gilt", k.dateien().get("rabatt.py").beschreibungGueltig());

            // Der Auftrag muss den Quelltext mitschicken, sonst raet das Modell.
            pruefe("die Anfrage enthaelt den Quelltext",
                   s.fragen.get(0).contains("def staffel"));
            pruefe("die Anfrage nennt die Pfade", s.fragen.get(0).contains("rabatt.py"));
        }

        // ------------------------------------------------------- gesichert?
        {
            Karte frisch = new Karte(ws);
            frisch.laden();
            pruefe("Beschreibung ueberlebt das Speichern",
                   frisch.dateien().get("beleg.py").beschreibung(), "Summiert Belege.");
            pruefe("und wird als gueltig geladen",
                   frisch.dateien().get("beleg.py").beschreibungGueltig());
        }

        // ------------------------------------------------------ fortsetzbar
        {
            Skript s = new Skript(false, "[]");
            Indexer.Ergebnis e = new Indexer(s, ws).lauf(new Karte(ws), m -> {});
            pruefe("zweiter Lauf hat nichts zu tun", e.beschrieben(), 0);
            pruefe("und fragt gar nicht erst", s.fragen.size(), 0);
        }

        // -------------------------------------------------------- Veraltetes
        {
            schreibe(tmp, "rabatt.py", "def staffel(m):\n    return 0.8   # geaendert\n");
            Karte k = new Karte(ws);
            k.auffrischen();
            Quelldatei q = k.dateien().get("rabatt.py");
            pruefe("nach der Aenderung gilt die Beschreibung nicht mehr",
                   !q.beschreibungGueltig());
            pruefe("sie ist als veraltet erkennbar", q.beschreibungVeraltet());
            // Und sie darf nicht mehr angezeigt werden -- das ist der Punkt.
            String text = k.uebersicht(k.suche("rabatt", null), "'rabatt'");
            pruefe("veraltete Beschreibung wird nicht gezeigt",
                   !text.contains("Berechnet Mengenrabatte"));
            pruefe("stattdessen steht da ein Hinweis", text.contains("veraltet"));
            pruefe("die Struktur wird weiterhin gezeigt", text.contains("def staffel(m)"));
        }

        // ---------------------------------------------- kaputte Antworten
        {
            Karte k = new Karte(ws);
            Skript s = new Skript(false, "Klar, hier ist die Antwort! Aber ohne JSON.");
            Indexer.Ergebnis e = new Indexer(s, ws).lauf(k, m -> {});
            pruefe("Antwort ohne JSON wirft den Lauf nicht um", e.beschrieben(), 0);
            pruefe("und wird als offen gemeldet", e.offen() > 0);
        }
        {
            Karte k = new Karte(ws);
            // Zaun drumherum ist der haeufigste Verstoss gegen "nur JSON".
            Skript s = new Skript(false,
                    "```json\n[{\"pfad\":\"rabatt.py\",\"beschreibung\":\"Rabatte.\"}]\n```");
            new Indexer(s, ws).lauf(k, m -> {});
            pruefe("Codeblock-Zaun stoert nicht",
                   k.dateien().get("rabatt.py").beschreibung(), "Rabatte.");
        }
        {
            Karte k = new Karte(ws);
            k.auffrischen();
            int vorher = zaehleBeschriebene(k);
            Skript s = new Skript(false,
                    "[{\"pfad\":\"gibtsnichthier.py\",\"beschreibung\":\"Erfunden.\"}]");
            new Indexer(s, ws).lauf(k, m -> {});
            pruefe("erfundener Pfad wird verworfen", zaehleBeschriebene(k), vorher);
        }
        {
            // Nur der Dateiname statt des Pfades ist ein haeufiger Kurzschluss.
            schreibe(tmp, "tief/versteckt.py", "def x():\n    return 1\n");
            Karte k = new Karte(ws);
            Skript s = new Skript(false,
                    "[{\"pfad\":\"versteckt.py\",\"beschreibung\":\"Trotzdem zugeordnet.\"}]");
            new Indexer(s, ws).lauf(k, m -> {});
            pruefe("abgekuerzter Pfad wird zugeordnet",
                   k.dateien().get("tief/versteckt.py").beschreibung(), "Trotzdem zugeordnet.");
        }

        // ------------------------------------------------ Fehler des Servers
        {
            Karte k = new Karte(ws);
            k.auffrischen();
            for (Quelldatei q : k.dateien().values())
                k.setze(new Quelldatei(q.pfad(), q.groesse(), q.mtime(), q.hash(), q.sprache(),
                        q.zeilen(), q.definitionen(), q.rohImporte(), q.verweise(),
                        null, List.of(), null));
            k.sichern();

            Skript s = new Skript(true, "ignoriert");
            Indexer.Ergebnis e = new Indexer(s, ws).lauf(new Karte(ws), m -> {});
            pruefe("ein gescheitertes Buendel beendet den Lauf nicht", e.buendel() >= 1);
            pruefe("es kostet nur seine eigenen Dateien", e.beschrieben(), 0);
        }

        // ------------------------------------------------------------ Buendel
        {
            List<Quelldatei> viele = new ArrayList<>();
            for (int i = 0; i < 20; i++) viele.add(attrappe("d" + i + ".py", 500));
            pruefe("Buendel bleiben unter der Dateigrenze",
                   Indexer.buendeln(viele).stream().allMatch(b -> b.size() <= Indexer.JE_ANFRAGE));
            pruefe("alle Dateien landen in einem Buendel",
                   Indexer.buendeln(viele).stream().mapToInt(List::size).sum(), 20);

            List<Quelldatei> dicke = new ArrayList<>();
            for (int i = 0; i < 10; i++) dicke.add(attrappe("g" + i + ".py", 9_000));
            // Grosse Dateien muessen frueher trennen, sonst sprengt das Buendel
            // das Kontextfenster -- und zwar genau bei den wichtigen Dateien.
            pruefe("grosse Dateien ergeben kleinere Buendel",
                   Indexer.buendeln(dicke).get(0).size() < Indexer.JE_ANFRAGE);

            pruefe("nurJson schaelt aus Text heraus",
                   Indexer.nurJson("Bitte sehr: [{\"a\":1}] — fertig."), "[{\"a\":1}]");
            pruefe("nurJson laesst reines JSON in Ruhe",
                   Indexer.nurJson("[{\"a\":1}]"), "[{\"a\":1}]");
        }

        // ------------------------------------------------------------ Abbruch
        {
            Karte k = new Karte(ws);
            k.auffrischen();
            for (Quelldatei q : k.dateien().values())
                k.setze(new Quelldatei(q.pfad(), q.groesse(), q.mtime(), q.hash(), q.sprache(),
                        q.zeilen(), q.definitionen(), q.rohImporte(), q.verweise(),
                        null, List.of(), null));
            k.sichern();

            Indexer i = new Indexer(new Skript(false, "[]"), ws);
            i.brichAb();
            Indexer.Ergebnis e = i.lauf(new Karte(ws), m -> {});
            pruefe("Abbruch vor dem ersten Buendel greift", e.abgebrochen());
            pruefe("und meldet, was offen blieb", e.offen() > 0);
        }

        System.out.println(fehlgeschlagen == 0
                ? "\nAlle Pruefungen bestanden."
                : "\n" + fehlgeschlagen + " Pruefung(en) fehlgeschlagen.");
        if (fehlgeschlagen > 0) System.exit(1);
    }

    private static Quelldatei attrappe(String pfad, int groesse) {
        return new Quelldatei(pfad, groesse, "1", "h", "Python", 10,
                List.of("def x()"), List.of(), List.of(), null, List.of(), null);
    }

    private static int zaehleBeschriebene(Karte k) {
        return (int) k.dateien().values().stream().filter(Quelldatei::beschreibungGueltig).count();
    }

    private static void schreibe(Path wurzel, String pfad, String inhalt) throws Exception {
        Path p = wurzel.resolve(pfad);
        Files.createDirectories(p.getParent());
        Files.writeString(p, inhalt);
        Files.setLastModifiedTime(p, FileTime.fromMillis(1_700_000_000_000L + inhalt.length()));
    }

    private static void pruefe(String was, Object erhalten, Object erwartet) {
        boolean ok = (erhalten == null) ? erwartet == null : erhalten.equals(erwartet);
        melde(was, ok);
        if (!ok) System.out.println("    erwartet: " + erwartet + "\n    erhalten: " + erhalten);
    }

    private static void pruefe(String was, boolean ok) { melde(was, ok); }

    private static void melde(String was, boolean ok) {
        System.out.printf("%-54s %s%n", was, ok ? "ok" : "FEHLGESCHLAGEN");
        if (!ok) fehlgeschlagen++;
    }
}
