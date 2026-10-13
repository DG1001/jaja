package de.dg1001.harness.tools;

import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Liest eine Datei mit Zeilennummern.
 *
 * <p>Die Nummern sind kein Schmuck: sie geben dem Modell etwas, worauf es sich
 * beim spaeteren Aendern beziehen kann ("Zeile 42"), und sie machen Auslassungen
 * beim Kuerzen nachvollziehbar.
 */
public final class ReadTool implements Tool {

    private static final int VORGABE_ZEILEN = 800;

    /**
     * Hoechstens so viele Zeichen pro Aufruf, an einer Zeilengrenze beendet.
     *
     * <p>Vorher lief die Ausgabe durch {@link Spill}, und dessen Grenze von
     * 8.000 Zeichen liegt bei rund 200 Zeilen Code. Eine 400-Zeilen-Datei kam
     * dadurch als Kopf (~60 Zeilen) und Fuss (~20 Zeilen) an, die Mitte nur
     * als Verweis auf eine Auslagerungsdatei -- obwohl die Beschreibung 800
     * Zeilen verspricht. Fuer eine Datei ist Blaettern die richtige Antwort,
     * nicht Kopf und Fuss: das Modell bekommt einen zusammenhaengenden
     * Ausschnitt und die Zeile, ab der es weitergeht.
     *
     * <p>20.000 Zeichen sind gut 5.000 Token -- bei 65k Fenster ein Zehntel
     * des nutzbaren Platzes, und aeltere Leseergebnisse fallen der Kuerzung
     * zuerst zum Opfer.
     */
    static final int MAX_ZEICHEN = 20_000;

    /** Einzelne Zeilen darueber werden abgeschnitten (minifiziertes JS, Daten). */
    static final int MAX_ZEILENLAENGE = 2_000;

    @Override public String name() { return "read"; }

    @Override public String beschreibung() {
        return "Liest eine Datei mit Zeilennummern. Nimm das, bevor du eine Datei aenderst, "
             + "und um dir einen Ueberblick ueber vorhandenen Code zu verschaffen.";
    }

    @Override public String parameterSchema() {
        return """
               {"type":"object",
                "properties":{
                  "pfad":{"type":"string","description":"Pfad relativ zum Projektverzeichnis"},
                  "ab_zeile":{"type":"integer","description":"erste Zeile, 1-basiert, Vorgabe 1"},
                  "zeilen":{"type":"integer","description":"Anzahl Zeilen, Vorgabe 800"}},
                "required":["pfad"]}""";
    }

    @Override
    public ToolResult run(Map<String, Object> args, Workspace ws) throws IOException {
        String pfad;
        try {
            pfad = Tool.pflichtText(args, "pfad");
        } catch (IllegalArgumentException e) {
            return ToolResult.fehler(e.getMessage());
        }

        Path p;
        try {
            p = ws.pruefeVorhandenen(ws.aufloesen(pfad));
        } catch (Workspace.AusbruchFehler e) {
            return ToolResult.fehler(e.getMessage());
        }

        if (!Files.exists(p))         return ToolResult.fehler("Datei gibt es nicht: " + pfad);
        if (Files.isDirectory(p))     return verzeichnis(p, ws);
        if (!Files.isReadable(p))     return ToolResult.fehler("nicht lesbar: " + pfad);

        List<String> zeilen;
        try {
            zeilen = Files.readAllLines(p);
        } catch (MalformedInputException e) {
            return ToolResult.fehler("keine Textdatei (nicht als UTF-8 lesbar): " + pfad);
        }

        int ab  = Math.max(1, Tool.zahl(args, "ab_zeile", 1));
        int wie = Math.max(1, Tool.zahl(args, "zeilen", VORGABE_ZEILEN));
        int bis = Math.min(zeilen.size(), ab - 1 + wie);

        if (ab > zeilen.size())
            return ToolResult.fehler("Datei hat nur " + zeilen.size()
                    + " Zeilen, ab_zeile war " + ab);

        StringBuilder b = new StringBuilder();
        int i = ab - 1;
        for (; i < bis; i++) {
            String z = zeilen.get(i);
            if (z.length() > MAX_ZEILENLAENGE)
                z = z.substring(0, MAX_ZEILENLAENGE) + "… [Zeile gekuerzt, "
                  + z.length() + " Zeichen]";
            // Mindestens eine Zeile, sonst kaeme man nie voran.
            if (i > ab - 1 && b.length() + z.length() > MAX_ZEICHEN) break;
            b.append(i + 1).append('\t').append(z).append('\n');
        }

        if (i < zeilen.size())
            b.append("[… noch ").append(zeilen.size() - i)
             .append(" Zeilen. Mit ab_zeile=").append(i + 1).append(" weiterlesen. …]\n");

        return ToolResult.ok(b.toString());
    }

    /** Ein Verzeichnis zu lesen ist ein haeufiger Vertipper — statt eines
     *  Fehlers gleich den Inhalt liefern, das spart einen Zug. */
    private ToolResult verzeichnis(Path p, Workspace ws) throws IOException {
        StringBuilder b = new StringBuilder("Verzeichnis " + ws.relativ(p) + ":\n");
        try (var s = Files.list(p)) {
            s.sorted().forEach(e -> b.append("  ")
                    .append(Files.isDirectory(e) ? "d " : "  ")
                    .append(e.getFileName()).append('\n'));
        }
        return Spill.vielleichtAuslagern(b.toString(), ws, "read-dir", false);
    }
}
