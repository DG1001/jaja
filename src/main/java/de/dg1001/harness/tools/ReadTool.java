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
        for (int i = ab - 1; i < bis; i++)
            b.append(i + 1).append('\t').append(zeilen.get(i)).append('\n');

        if (bis < zeilen.size())
            b.append("[… noch ").append(zeilen.size() - bis)
             .append(" Zeilen. Mit ab_zeile=").append(bis + 1).append(" weiterlesen. …]\n");

        return Spill.vielleichtAuslagern(b.toString(), ws, "read", false);
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
