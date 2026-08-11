package de.dg1001.harness.tools;

import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>Gegenueber {@code write} spart das erheblich Tokens — eine Aenderung von
 * drei Zeilen kostet drei Zeilen statt der ganzen Datei.
 */
public final class EditTool implements Tool {

    @Override public String name() { return "edit"; }

    @Override public String beschreibung() {
        return "Ersetzt eine Textstelle in einer Datei. 'alt' muss genau einmal vorkommen — "
             + "gib genug Umgebung mit, damit die Stelle eindeutig ist.";
    }

    @Override public String parameterSchema() {
        return """
               {"type":"object",
                "properties":{
                  "pfad":{"type":"string","description":"Pfad relativ zum Projektverzeichnis"},
                  "alt":{"type":"string","description":"zu ersetzender Text, genau einmal vorkommend"},
                  "neu":{"type":"string","description":"Ersatztext"},
                  "alle":{"type":"boolean","description":"alle Vorkommen ersetzen, Vorgabe false"}},
                "required":["pfad","alt","neu"]}""";
    }

    @Override
    public ToolResult run(Map<String, Object> args, Workspace ws) throws IOException {
        String pfad, alt, neu;
        try {
            pfad = Tool.pflichtText(args, "pfad");
            alt  = Tool.text(args, "alt");
            neu  = Tool.text(args, "neu");
            if (alt == null) throw new IllegalArgumentException("Feld 'alt' fehlt");
            if (neu == null) throw new IllegalArgumentException("Feld 'neu' fehlt");
            if (alt.isEmpty()) throw new IllegalArgumentException("'alt' darf nicht leer sein");
        } catch (IllegalArgumentException e) {
            return ToolResult.fehler(e.getMessage());
        }

        boolean alle = args.get("alle") instanceof Boolean b && b;

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

        int treffer = zaehle(inhalt, alt);

        if (treffer == 0)
            return ToolResult.fehler("'alt' kommt in " + pfad + " nicht vor. "
                    + "Lies die Datei und kopiere die Stelle woertlich — "
                    + "achte auf Einrueckung und Leerzeichen.");

        if (treffer > 1 && !alle)
            return ToolResult.fehler("'alt' kommt " + treffer + "-mal in " + pfad
                    + " vor, muss aber eindeutig sein. Gib mehr Umgebung mit "
                    + "(Zeile davor und danach) oder setze alle=true.");

        String ergebnis = alle ? inhalt.replace(alt, neu)
                               : ersetzeErstes(inhalt, alt, neu);
        Files.writeString(p, ergebnis);

        return ToolResult.ok("geaendert: " + ws.relativ(p)
                + " (" + (alle ? treffer : 1) + " Stelle" + ((alle && treffer > 1) ? "n" : "")
                + ", " + inhalt.length() + " -> " + ergebnis.length() + " Zeichen)");
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
