package de.dg1001.harness.tools;

import de.dg1001.harness.wire.Json;
import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ersetzt eine Textstelle in einer Datei.
 *
 * <p><b>Die Eindeutigkeitsregel ist der ganze Sinn der Sache.</b> Kommt der
 * gesuchte Text keinmal oder mehrfach vor, wird nichts geaendert und das Modell
 * bekommt gesagt, warum. Das zwingt es, genug Umgebung mitzugeben, statt auf gut
 * Glueck {@code return x} zu ersetzen und dabei drei andere Stellen zu treffen.
 * Ein stiller Mehrfachtreffer ist der teuerste Fehler in dieser Werkzeugklasse:
 * er faellt erst beim Testen auf, und dann sucht das Modell an der falschen
 * Stelle.
 *
 * <p>Passt nichts woertlich, sucht {@link Stellensuche} mit tolerantem
 * Leerraum -- und uebernimmt auch dann nur einen eindeutigen Treffer. Passt
 * auch so nichts, bekommt das Modell die aehnlichste Stelle woertlich zu sehen.
 *
 * <p>Gegenueber {@code write} spart das erheblich Tokens — eine Aenderung von
 * drei Zeilen kostet drei Zeilen statt der ganzen Datei. Mit {@code aenderungen}
 * gehen mehrere Stellen in einem Zug; lokal ist jeder gesparte Zug zehn
 * Sekunden bis Minuten.
 */
public final class EditTool implements Tool {

    @Override public String name() { return "edit"; }

    @Override public String beschreibung() {
        return "Ersetzt eine Textstelle in einer Datei. 'alt' muss genau einmal vorkommen — "
             + "gib genug Umgebung mit, damit die Stelle eindeutig ist. Mehrere Stellen "
             + "einer Datei auf einmal: 'aenderungen'.";
    }

    @Override public String parameterSchema() {
        return """
               {"type":"object",
                "properties":{
                  "pfad":{"type":"string","description":"Pfad relativ zum Projektverzeichnis"},
                  "alt":{"type":"string","description":"zu ersetzender Text, genau einmal vorkommend"},
                  "neu":{"type":"string","description":"Ersatztext"},
                  "alle":{"type":"boolean","description":"alle Vorkommen ersetzen, Vorgabe false"},
                  "aenderungen":{"type":"array","description":"statt alt/neu: mehrere, der Reihe nach; alle oder keine",
                    "items":{"type":"object","properties":{"alt":{"type":"string"},"neu":{"type":"string"},
                    "alle":{"type":"boolean"}},"required":["alt","neu"]}}},
                "required":["pfad"]}""";
    }

    /** Eine einzelne Ersetzung. */
    record Aenderung(String alt, String neu, boolean alle) {}

    @Override
    public ToolResult run(Map<String, Object> args, Workspace ws) throws IOException {
        String pfad;
        List<Aenderung> liste;
        try {
            pfad  = Tool.pflichtText(args, "pfad");
            liste = aenderungen(args);
        } catch (IllegalArgumentException e) {
            return ToolResult.fehler(e.getMessage());
        }

        Path p;
        try {
            p = ws.pruefeVorhandenen(ws.aufloesen(pfad));
        } catch (Workspace.AusbruchFehler e) {
            return ToolResult.fehler(e.getMessage());
        }
        if (!Files.exists(p))     return ToolResult.fehler("Datei gibt es nicht: " + pfad);
        if (Files.isDirectory(p)) return ToolResult.fehler(pfad + " ist ein Verzeichnis");

        String inhalt;
        try {
            inhalt = Files.readString(p);
        } catch (MalformedInputException e) {
            return ToolResult.fehler("keine Textdatei (nicht als UTF-8 lesbar): " + pfad);
        }

        // Alle Aenderungen im Speicher, geschrieben wird erst am Ende: eine
        // halb angewandte Liste liesse die Datei in einem Zustand zurueck, den
        // das Modell nicht kennt.
        String stand = inhalt;
        int stellen = 0;
        List<String> hinweise = new ArrayList<>();
        for (int k = 0; k < liste.size(); k++) {
            Schritt s = wende(stand, liste.get(k), pfad);
            if (s.fehler() != null) {
                String vorne = liste.size() == 1 ? ""
                        : "Aenderung " + (k + 1) + " von " + liste.size() + ": ";
                String hinten = liste.size() == 1 || k == 0 ? ""
                        : "\nNichts geschrieben, auch die " + k + " vorherigen nicht. "
                        + "Die Stelle wird im Stand NACH den vorherigen Aenderungen gesucht.";
                return ToolResult.fehler(vorne + s.fehler() + hinten);
            }
            stand = s.inhalt();
            stellen += s.stellen();
            if (s.hinweis() != null)
                hinweise.add((liste.size() == 1 ? "" : "Aenderung " + (k + 1) + ": ") + s.hinweis());
        }
        Files.writeString(p, stand);

        StringBuilder b = new StringBuilder("geaendert: ").append(ws.relativ(p)).append(" (");
        if (liste.size() > 1) b.append(liste.size()).append(" Aenderungen, ");
        b.append(stellen).append(stellen == 1 ? " Stelle" : " Stellen")
         .append(", ").append(inhalt.length()).append(" -> ").append(stand.length())
         .append(" Zeichen)");
        for (String h : hinweise) b.append('\n').append(h);
        return ToolResult.ok(b.toString());
    }

    /** Ergebnis einer Ersetzung: neuer Inhalt oder Fehlertext. */
    private record Schritt(String inhalt, int stellen, String hinweis, String fehler) {
        static Schritt fehler(String f) { return new Schritt(null, 0, null, f); }
    }

    private static Schritt wende(String inhalt, Aenderung a, String pfad) {
        int treffer = zaehle(inhalt, a.alt());

        if (treffer == 1 || (treffer > 1 && a.alle()))
            return new Schritt(a.alle() ? inhalt.replace(a.alt(), a.neu())
                                        : ersetzeErstes(inhalt, a.alt(), a.neu()),
                               a.alle() ? treffer : 1, null, null);

        if (treffer > 1)
            return Schritt.fehler("'alt' kommt " + treffer + "-mal in " + pfad
                    + " vor (ab Zeile " + liste(Stellensuche.zeilenDerVorkommen(inhalt, a.alt()))
                    + "), muss aber eindeutig sein. Gib mehr Umgebung mit "
                    + "(Zeile davor und danach) oder setze alle=true.");

        // Woertlich nichts. Toleranter Leerraum, aber nur fuer eine Stelle:
        // bei alle=true waere unklar, welche der Abweichungen gemeint sind.
        if (!a.alle()) {
            Stellensuche.Ergebnis t = Stellensuche.tolerant(inhalt, a.alt(), a.neu());
            if (t != null && t.treffer() == 1)
                return new Schritt(t.ersetzt(), 1, toleranzHinweis(t), null);
            if (t != null)
                return Schritt.fehler("'alt' kommt in " + pfad + " nicht woertlich vor, "
                        + "passt bei abweichendem Leerraum aber " + t.treffer() + "-mal (ab Zeile "
                        + liste(t.zeilen()) + "). Kopiere die gemeinte Stelle woertlich "
                        + "und gib mehr Umgebung mit.");
        }

        String naehe = Stellensuche.aehnlichste(inhalt, a.alt());
        return Schritt.fehler("'alt' kommt in " + pfad + " nicht vor. "
                + (naehe != null
                   ? "Kopiere die Stelle von unten woertlich.\n" + naehe
                   : "Lies die Datei und kopiere die Stelle woertlich — "
                     + "achte auf Einrueckung und Leerzeichen."));
    }

    private static String toleranzHinweis(Stellensuche.Ergebnis t) {
        String wo = "Zeile " + t.zeilen().get(0);
        return switch (t.art()) {
            case ZEILENENDEN -> "Hinweis: Die Datei hat CRLF-Zeilenenden; Stelle ab " + wo
                    + " trotzdem gefunden, Zeilenenden beibehalten.";
            case LEERRAUM -> "Hinweis: Stelle ab " + wo + " nur mit abweichendem Leerraum "
                    + "gefunden" + (t.eingerueckt() ? ", Einrueckung von 'neu' angepasst" : "")
                    + ". Pruefe das Ergebnis mit read.";
        };
    }

    /** Liest entweder alt/neu oder die Liste {@code aenderungen}. */
    static List<Aenderung> aenderungen(Map<String, Object> args) {
        Object roh = args.get("aenderungen");
        List<Aenderung> l = new ArrayList<>();
        if (roh != null) {
            if (args.containsKey("alt"))
                throw new IllegalArgumentException("entweder 'alt'/'neu' oder 'aenderungen', nicht beides");
            List<Object> eintraege;
            try { eintraege = Json.arr(roh); }
            catch (RuntimeException e) { throw new IllegalArgumentException("'aenderungen' muss eine Liste sein"); }
            if (eintraege.isEmpty()) throw new IllegalArgumentException("'aenderungen' ist leer");
            for (int i = 0; i < eintraege.size(); i++) {
                Map<String, Object> m;
                try { m = Json.obj(eintraege.get(i)); }
                catch (RuntimeException e) {
                    throw new IllegalArgumentException("aenderungen[" + i + "] ist kein Objekt");
                }
                l.add(eine(m, "aenderungen[" + i + "]."));
            }
            return l;
        }
        l.add(eine(args, ""));
        return l;
    }

    private static Aenderung eine(Map<String, Object> m, String wo) {
        String alt = Tool.text(m, "alt"), neu = Tool.text(m, "neu");
        if (alt == null) throw new IllegalArgumentException("Feld '" + wo + "alt' fehlt");
        if (neu == null) throw new IllegalArgumentException("Feld '" + wo + "neu' fehlt");
        if (alt.isEmpty()) throw new IllegalArgumentException("'" + wo + "alt' darf nicht leer sein");
        return new Aenderung(alt, neu, m.get("alle") instanceof Boolean b && b);
    }

    private static String liste(List<Integer> zeilen) {
        List<String> s = zeilen.stream().limit(8).map(String::valueOf).toList();
        return String.join(", ", s) + (zeilen.size() > 8 ? ", …" : "");
    }

    private static int zaehle(String heu, String nadel) {
        int n = 0, i = 0;
        while ((i = heu.indexOf(nadel, i)) >= 0) { n++; i += nadel.length(); }
        return n;
    }

    /** Bewusst nicht replaceFirst: das wuerde 'alt' als regulaeren Ausdruck lesen. */
    private static String ersetzeErstes(String heu, String alt, String neu) {
        int i = heu.indexOf(alt);
        return heu.substring(0, i) + neu + heu.substring(i + alt.length());
    }
}
