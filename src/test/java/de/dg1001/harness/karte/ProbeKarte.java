package de.dg1001.harness.karte;

import de.dg1001.harness.tools.ToolRegistry;
import de.dg1001.harness.wire.Messages.ToolCall;
import de.dg1001.harness.ws.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

/**
 * Prueft die Quellenkarte an einem kleinen erfundenen Projekt.
 *
 * <p>Zwei Dinge stehen hier im Mittelpunkt, weil sie im Betrieb still
 * scheitern wuerden. Erstens die <b>Inkrementalitaet</b>: liest die Karte bei
 * jedem Aufruf alles neu, ist die Ersparnis dahin, die sie verspricht — und man
 * merkt es nur an der Uhr. Zweitens der <b>Umgang mit Veraltetem</b>: eine
 * Beschreibung, die zum heutigen Inhalt nicht mehr passt, fuehrt das Modell
 * aktiver in die Irre als gar keine.
 */
public final class ProbeKarte {

    private static int fehlgeschlagen = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("jaja-karte");
        Workspace ws = new Workspace(tmp);

        // ------------------------------------------------ erfundenes Projekt
        schreibe(tmp, "modelle/artikel.py", """
                import konfig

                class Artikel:
                    def __init__(self, name):
                        self.name = name

                def leer():
                    return None
                """);
        schreibe(tmp, "preise/rabatt.py", """
                from modelle.artikel import Artikel
                import konfig

                def staffel(menge):
                    return 0.9

                async def netto(betrag, satz):
                    return betrag
                """);
        schreibe(tmp, "konfig.py", "WERT = 1\n");
        // Namensgleiche Datei tief im Baum: darf 'import konfig' nicht anziehen.
        schreibe(tmp, "tief/im/baum/konfig.py", "ANDERS = 2\n");
        schreibe(tmp, "kasse/beleg.py", """
                from preise.rabatt import staffel
                import konfig


                class Beleg:
                    pass
                """);
        schreibe(tmp, "Haupt.java", """
                package de.beispiel;

                import de.beispiel.hilfe.Rechner;
                import java.util.List;

                public class Haupt {
                    public static void main(String[] a) { }
                    private int zaehle(String s) { return 1; }
                }
                """);
        schreibe(tmp, "de/beispiel/hilfe/Rechner.java", """
                package de.beispiel.hilfe;

                public record Rechner(int a) {
                    public int summe(int b) { return a + b; }
                }
                """);
        schreibe(tmp, "web/haupt.js", """
                import { hilf } from './werkzeug.js';

                export function start(x) { return x; }
                export const klein = (y) => y;
                """);
        schreibe(tmp, "web/werkzeug.js", "export function hilf() { return 1; }\n");
        // Wird nie indiziert: uebersprungenes Verzeichnis, unbekannte Endung, binaer
        schreibe(tmp, ".venv/heimlich.py", "def darf_nicht_auftauchen(): pass\n");
        schreibe(tmp, "node_modules/x/y.js", "export function auch_nicht() {}\n");
        schreibe(tmp, "daten.bin_unbekannt", "egal");
        Files.write(tmp.resolve("bild.png"), new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        Karte k = new Karte(ws);
        Scanner s1 = k.auffrischen();
        Map<String, Quelldatei> d = k.dateien();

        // --------------------------------------------------------- Erfassung
        pruefe("erfasst die Quelldateien", d.size(), 9);
        pruefe("ueberspringt .venv", !d.containsKey(".venv/heimlich.py"));
        pruefe("ueberspringt node_modules", !d.containsKey("node_modules/x/y.js"));
        pruefe("ignoriert unbekannte Endungen", !d.containsKey("daten.bin_unbekannt"));
        pruefe("indiziert sich nicht selbst",
               d.keySet().stream().noneMatch(p -> p.startsWith(".harness")));

        // ------------------------------------------------------ Definitionen
        Quelldatei rabatt = d.get("preise/rabatt.py");
        pruefe("Python: def mit Argumenten",
               rabatt.definitionen().contains("def staffel(menge)"));
        pruefe("Python: async def",
               rabatt.definitionen().contains("async def netto(betrag, satz)"));
        pruefe("Python: class",
               d.get("modelle/artikel.py").definitionen().contains("class Artikel"));

        Quelldatei haupt = d.get("Haupt.java");
        pruefe("Java: class", haupt.definitionen().contains("class Haupt"));
        pruefe("Java: Methode mit Sichtbarkeit",
               haupt.definitionen().contains("main(String[] a)"));
        pruefe("Java: private Methode",
               haupt.definitionen().contains("zaehle(String s)"));
        pruefe("Java: record",
               d.get("de/beispiel/hilfe/Rechner.java").definitionen().contains("record Rechner(int a)"));

        Quelldatei js = d.get("web/haupt.js");
        pruefe("JS: export function", js.definitionen().contains("function start(x)"));
        pruefe("JS: Pfeilfunktion als const", js.definitionen().contains("const klein(y)"));

        // ---------------------------------------------------------- Verweise
        // Der eigentliche Nutzen: aus 'from modelle.artikel import Artikel'
        // muss der Projektpfad werden, nicht der Modulname.
        pruefe("Python: Modulname wird zum Pfad",
               rabatt.verweise().contains("modelle/artikel.py"));
        pruefe("Python: Import ohne from", rabatt.verweise().contains("konfig.py"));
        pruefe("Java: Paketpfad wird aufgeloest",
               haupt.verweise().contains("de/beispiel/hilfe/Rechner.java"));
        pruefe("Java: Fremdbibliothek faellt heraus",
               haupt.verweise().stream().noneMatch(v -> v.contains("java/util")));
        pruefe("Fremdimport bleibt aber roh erhalten",
               haupt.rohImporte().contains("java.util.List"));
        pruefe("JS: relativer Pfad wird aufgeloest",
               js.verweise().contains("web/werkzeug.js"));

        // An Django gemessen: 'from collections import defaultdict' meint die
        // Standardbibliothek, landete aber auf django/contrib/gis/geos/collections.py,
        // weil ein Pfadende genuegte. Einteilige Namen brauchen deshalb einen
        // genauen Treffer -- Nachbardatei oder Projektwurzel.
        pruefe("einteiliger Import trifft keine tief liegende Namensgleiche",
               d.get("preise/rabatt.py").verweise().stream()
                       .noneMatch(v -> v.startsWith("tief/")));
        pruefe("einteiliger Import findet aber die Datei in der Wurzel",
               d.get("preise/rabatt.py").verweise().contains("konfig.py"));

        Map<String, List<String>> rueck = k.rueckverweise();
        pruefe("Rueckverweise passen zu den Vorwaertsverweisen",
               rueck.get("modelle/artikel.py"), List.of("preise/rabatt.py"));
        // konfig.py wird von drei Dateien benutzt, alles andere von einer --
        // die Rangfolge muss das und nicht die Alphabetik abbilden.
        pruefe("Rangfolge: meistgenutzte Datei steht oben",
               k.suche(null, null).get(0).pfad(), "konfig.py");
        pruefe("Rangfolge beruht auf dem Eingangsgrad",
               k.rueckverweise().get("konfig.py").size(), 3);

        // ------------------------------------------------------ inkrementell
        Karte k2 = new Karte(ws);
        Scanner s2 = k2.auffrischen();
        pruefe("erster Lauf liest alles", s1.gelesen(), 9);
        pruefe("zweiter Lauf liest nichts", s2.gelesen(), 0);
        pruefe("zweiter Lauf kennt trotzdem alles", k2.dateien().size(), 9);

        schreibe(tmp, "konfig.py", "WERT = 1\nZWEITER = 2\n");
        Karte k3 = new Karte(ws);
        Scanner s3 = k3.auffrischen();
        pruefe("nur die geaenderte Datei wird gelesen", s3.gelesen(), 1);

        Files.delete(tmp.resolve("kasse/beleg.py"));
        Karte k4 = new Karte(ws);
        k4.auffrischen();
        pruefe("geloeschte Datei verschwindet", !k4.dateien().containsKey("kasse/beleg.py"));

        // -------------------------------------------------------- Veraltetes
        Karte k5 = new Karte(ws);
        k5.laden();
        Quelldatei mitText = k5.dateien().get("konfig.py")
                .mitBeschreibung("Haelt die Einstellungen.", List.of("konfig"));
        pruefe("frische Beschreibung gilt", mitText.beschreibungGueltig());
        pruefe("frische Beschreibung ist nicht veraltet", !mitText.beschreibungVeraltet());

        // Inhalt aendern -> derselbe Eintrag mit neuem Hash
        Quelldatei geaendert = new Quelldatei(mitText.pfad(), mitText.groesse(), mitText.mtime(),
                "anderer-hash", mitText.sprache(), mitText.zeilen(), mitText.definitionen(),
                mitText.rohImporte(), mitText.verweise(), mitText.importierteNamen(),
                mitText.beschreibung(), mitText.stichworte(), mitText.beschreibungFuerHash());
        pruefe("nach Aenderung gilt die Beschreibung nicht mehr", !geaendert.beschreibungGueltig());
        pruefe("und sie ist als veraltet erkennbar", geaendert.beschreibungVeraltet());

        // ---------------------------------------------------- JSON-Rundlauf
        Karte k6 = new Karte(ws);
        k6.laden();
        pruefe("gespeichert und geladen ergibt dasselbe",
               k6.dateien().keySet(), k4.dateien().keySet());
        Quelldatei vorher = k4.dateien().get("preise/rabatt.py");
        Quelldatei nachher = k6.dateien().get("preise/rabatt.py");
        pruefe("Rundlauf: Definitionen", nachher.definitionen(), vorher.definitionen());
        pruefe("Rundlauf: Verweise", nachher.verweise(), vorher.verweise());
        pruefe("Rundlauf: Hash", nachher.hash(), vorher.hash());
        // mtime in Millisekunden passt in kein int -- deshalb steht es als Text da.
        pruefe("Rundlauf: mtime ueberlebt (kein int-Ueberlauf)",
               nachher.mtime(), vorher.mtime());
        pruefe("mtime ist wirklich gross", Long.parseLong(nachher.mtime()) > Integer.MAX_VALUE);

        // ---------------------------------------------------------- Ausgabe
        String uebersicht = k6.uebersicht(k6.suche(null, null), "der Auswahl");
        pruefe("Ausgabe nennt die Gesamtzahl", uebersicht.contains("Dateien in der Karte"));
        pruefe("Ausgabe zeigt volle Pfade zum Lesen",
               uebersicht.contains("preise/rabatt.py"));
        pruefe("Ausgabe zeigt Verweise", uebersicht.contains("→"));

        String einzeln = k6.einzeln(k6.dateien().get("Haupt.java"));
        pruefe("Einzelansicht nennt Fremdimporte",
               einzeln.contains("ausserhalb des Projekts") && einzeln.contains("java.util.List"));

        // ------------------------------------------------ ueber die Registry
        ToolRegistry r = ToolRegistry.vorgabe(true);
        pruefe("Werkzeug liefert eine Uebersicht",
               werkzeug(r, ws, "{}").contains("Dateien in der Karte"));
        pruefe("Stichwort filtert",
               werkzeug(r, ws, "{\"stichwort\":\"rabatt\"}").contains("preise/rabatt.py"));
        pruefe("Stichwort ohne Treffer sagt das",
               werkzeug(r, ws, "{\"stichwort\":\"gibtsnichthier\"}").contains("Keine Datei passt"));
        pruefe("Muster filtert",
               !werkzeug(r, ws, "{\"muster\":\"**/*.java\"}").contains("rabatt.py"));
        pruefe("Einzelansicht ueber datei",
               werkzeug(r, ws, "{\"datei\":\"Haupt.java\"}").contains("Rechner.java"));
        pruefe("unbekannte Datei wird gemeldet",
               werkzeug(r, ws, "{\"datei\":\"gibtsnicht.py\"}").contains("nicht in der Karte"));
        // Die Karte darf so wenig aus dem Arbeitsbereich herausfuehren wie read.
        pruefe("Pfadeingrenzung greift",
               werkzeug(r, ws, "{\"datei\":\"../../etc/passwd\"}").contains("ausserhalb"));
        pruefe("kaputtes Muster stuerzt nicht ab",
               werkzeug(r, ws, "{\"muster\":\"[unfertig\"}").contains("nicht lesbar"));

        // -------------------------------------------------- Ueberschattung
        // Der gemessene Ausfall aus dem eigenen Pruefstand: ein Modell legte
        // STANDARD an und dann ein zweites in __init__.py, das den Import
        // ueberschattete. Alles Sichtbare lief weiter, nur der Pfad, den die
        // Aufgabe nannte, blieb leer. Drei Punkte an etwas verloren, das hier
        // in einer Zeile steht.
        {
            Path w = Files.createTempDirectory("jaja-schatten");
            Workspace ws2 = new Workspace(w);
            schreibe(w, "paket/kern.py", "class Register:\n    pass\n\nSTANDARD = Register()\n");
            schreibe(w, "paket/__init__.py",
                     "from paket.kern import Register, STANDARD\n\nSTANDARD = Register()\n");
            Karte ks = new Karte(ws2);
            ks.auffrischen();

            pruefe("Modulzuweisung zaehlt als Definition",
                   ks.dateien().get("paket/kern.py").definitionen().contains("STANDARD"));
            pruefe("importierte Namen werden erfasst",
                   ks.dateien().get("paket/__init__.py").importierteNamen().contains("STANDARD"));
            pruefe("Ueberschattung wird erkannt", ks.ueberschattet().containsKey("STANDARD"));
            pruefe("und ungefragt gemeldet",
                   ks.uebersicht(ks.suche(null, null), "x").contains("Achtung: STANDARD"));

            // Namensgleichheit ohne Import ist kein Befund.
            Path w2 = Files.createTempDirectory("jaja-zufall");
            Workspace ws3 = new Workspace(w2);
            schreibe(w2, "a/ding.py", "class Werkzeug:\n    pass\n");
            schreibe(w2, "b/ding.py", "class Werkzeug:\n    pass\n");
            Karte kz = new Karte(ws3);
            kz.auffrischen();
            pruefe("blosse Namensgleichheit ist keine Ueberschattung",
                   kz.ueberschattet().isEmpty());
            pruefe("sie taucht aber in der Liste auf", kz.doppelte().containsKey("Werkzeug"));
            pruefe("und wird nicht ungefragt gemeldet",
                   !kz.uebersicht(kz.suche(null, null), "x").contains("Achtung"));

            // Methoden leben im Klassenraum und koennen nichts ueberschatten.
            Path w3 = Files.createTempDirectory("jaja-methode");
            Workspace ws4 = new Workspace(w3);
            schreibe(w3, "kern.py", "def melde(x):\n    return x\n");
            schreibe(w3, "nutzer.py",
                     "from kern import melde\n\nclass K:\n    def melde(self, x):\n        return x\n");
            Karte km = new Karte(ws4);
            km.auffrischen();
            pruefe("Methode ueberschattet keinen Import", km.ueberschattet().isEmpty());
        }

        // ---------------------------------------------------- Verzeichnisse
        // Bei vielen Treffern ist eine Dateiliste die falsche Form: gezeigt
        // wuerden zwanzig von hunderten, sortiert nach globalem Eingangsgrad.
        {
            Path w = Files.createTempDirectory("jaja-verz");
            Workspace ws2 = new Workspace(w);
            for (int i = 0; i < 20; i++) schreibe(w, "kern/m" + i + ".py", "def f" + i + "():\n    pass\n");
            for (int i = 0; i < 20; i++) schreibe(w, "tests/t" + i + ".py", "def f" + i + "():\n    pass\n");
            schreibe(w, "kern/zentral.py", "def zentral():\n    pass\n");
            for (int i = 0; i < 20; i++)
                schreibe(w, "kern/m" + i + ".py",
                         "from kern.zentral import zentral\n\ndef f" + i + "():\n    pass\n");
            Karte kv = new Karte(ws2);
            kv.auffrischen();
            String t = kv.uebersicht(kv.suche(null, null), "allem");

            pruefe("viele Treffer ergeben eine Verzeichnisansicht",
                   t.contains("nach Verzeichnis"));
            pruefe("das gewichtigste Verzeichnis steht oben",
                   t.indexOf("kern/") < t.indexOf("tests/"));
            pruefe("mit einem Vorschlag zum Eingrenzen", t.contains("muster=\"kern/**\""));
            pruefe("wenige Treffer bleiben eine Dateiliste",
                   !kv.uebersicht(kv.suche("zentral", null), "zentral").contains("nach Verzeichnis"));
        }

        // ------------------------------------------------------------ Deckel
        Path gross = Files.createDirectories(tmp.resolve("viele"));
        for (int i = 0; i < 90; i++)
            Files.writeString(gross.resolve("m" + i + ".py"),
                    "def f" + i + "():\n    return " + i + "\n");
        Karte k7 = new Karte(ws);
        k7.auffrischen();
        String viel = k7.uebersicht(k7.suche(null, null), "der Auswahl");
        pruefe("Ausgabe bleibt unter dem Zeichendeckel", viel.length() < 8000);
        // Bei so vielen Treffern ist die Verzeichnisansicht die richtige Form.
        pruefe("und fasst nach Verzeichnis zusammen", viel.contains("nach Verzeichnis"));

        System.out.println(fehlgeschlagen == 0
                ? "\nAlle Pruefungen bestanden."
                : "\n" + fehlgeschlagen + " Pruefung(en) fehlgeschlagen.");
        if (fehlgeschlagen > 0) System.exit(1);
    }

    // --------------------------------------------------------------- Zutaten

    private static void schreibe(Path wurzel, String pfad, String inhalt) throws Exception {
        Path p = wurzel.resolve(pfad);
        Files.createDirectories(p.getParent());
        Files.writeString(p, inhalt);
        // Aenderungszeit ausdruecklich setzen: zwei Schreibvorgaenge in derselben
        // Millisekunde saehen fuer die Inkrementalpruefung sonst gleich aus.
        Files.setLastModifiedTime(p, FileTime.fromMillis(1_700_000_000_000L + inhalt.length()));
    }

    private static String werkzeug(ToolRegistry r, Workspace ws, String args) {
        return r.fuehreAus(new ToolCall("i", "karte", args), ws).text();
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
