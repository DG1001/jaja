package de.dg1001.harness.tools;

import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Schreibt eine Datei vollstaendig.
 *
 * <p>Der Gewinn gegenueber {@code bash} mit Hier-Dokument ist nicht die
 * Bequemlichkeit, sondern die Zuverlaessigkeit: bei {@code cat > x <<'EOF'}
 * kann der Dateiinhalt die Begrenzung enthalten, Anfuehrungszeichen muessen
 * maskiert werden, und ein Modell, das sich dabei vertut, erzeugt stillschweigend
 * Unsinn. Hier ist der Inhalt ein JSON-Feld und damit eindeutig.
 */
public final class WriteTool implements Tool {

    @Override public String name() { return "write"; }

    @Override public String beschreibung() {
        return "Schreibt eine Datei vollstaendig neu (legt fehlende Verzeichnisse an). "
             + "Nimm das fuer neue Dateien; zum Aendern einzelner Stellen nimm edit.";
    }

    @Override public String parameterSchema() {
        return """
               {"type":"object",
                "properties":{
                  "pfad":{"type":"string","description":"Pfad relativ zum Projektverzeichnis"},
                  "inhalt":{"type":"string","description":"vollstaendiger neuer Dateiinhalt"}},
                "required":["pfad","inhalt"]}""";
    }

    @Override
    public ToolResult run(Map<String, Object> args, Workspace ws) throws IOException {
        String pfad;
        String inhalt;
        try {
            pfad   = Tool.pflichtText(args, "pfad");
            inhalt = Tool.text(args, "inhalt");
            if (inhalt == null) throw new IllegalArgumentException("Feld 'inhalt' fehlt");
        } catch (IllegalArgumentException e) {
            return ToolResult.fehler(e.getMessage());
        }

        Path p;
        try {
            p = ws.aufloesen(pfad);
        } catch (Workspace.AusbruchFehler e) {
            return ToolResult.fehler(e.getMessage());
        }

        if (Files.isDirectory(p))
            return ToolResult.fehler(pfad + " ist ein Verzeichnis");

        boolean neu = !Files.exists(p);
        Path eltern = p.getParent();
        if (eltern != null) Files.createDirectories(eltern);
        Files.writeString(p, inhalt);

        int zeilen = inhalt.isEmpty() ? 0
                : (int) inhalt.chars().filter(c -> c == '\n').count()
                  + (inhalt.endsWith("\n") ? 0 : 1);

        return ToolResult.ok((neu ? "angelegt: " : "ueberschrieben: ") + ws.relativ(p)
                + " (" + zeilen + " Zeilen, " + inhalt.length() + " Zeichen)");
    }
}
