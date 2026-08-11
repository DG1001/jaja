package de.dg1001.harness.tools;

import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Sucht Text in Dateien und liefert {@code datei:zeile:text}.
 *
 * <p>Warum ueberhaupt, wenn es {@code bash} mit grep gibt: die Ausgabe ist hier
 * begrenzt und gleichbleibend geformt, Anfuehrungszeichen im Muster machen
 * keinen Aerger, und der Aufruf ist nebenlaeufig sicher — {@code grep} in einer
 * Shell ist es prinzipiell auch, aber der Agent kann es einem Shell-Aufruf nicht
 * ansehen.
 */
public final class GrepTool implements Tool {

    static final int MAX_TREFFER = 200;
    private static final long MAX_DATEIGROESSE = 2_000_000;   // Binaerkram ueberspringen

    @Override public String name() { return "grep"; }

    @Override public String beschreibung() {
        return "Sucht einen regulaeren Ausdruck in Dateien und liefert datei:zeile:text. "
             + "Nimm das, um Definitionen und Verwendungsstellen zu finden.";
    }

    @Override public String parameterSchema() {
        return """
               {"type":"object",
                "properties":{
                  "muster":{"type":"string","description":"regulaerer Ausdruck"},
                  "dateien":{"type":"string","description":"Glob zum Einschraenken, z. B. **/*.py"},
                  "ab":{"type":"string","description":"Startverzeichnis, Vorgabe Projektwurzel"}},
                "required":["muster"]}""";
    }

    @Override
    public ToolResult run(Map<String, Object> args, Workspace ws) throws IOException {
        String muster;
        try {
            muster = Tool.pflichtText(args, "muster");
        } catch (IllegalArgumentException e) {
            return ToolResult.fehler(e.getMessage());
        }

        Pattern p;
        try {
            p = Pattern.compile(muster);
        } catch (PatternSyntaxException e) {
            return ToolResult.fehler("Ausdruck nicht lesbar: " + e.getMessage());
        }

        Path start;
        try {
            String ab = Tool.text(args, "ab");
            start = (ab == null || ab.isBlank()) ? ws.wurzel() : ws.aufloesen(ab);
        } catch (Workspace.AusbruchFehler e) {
            return ToolResult.fehler(e.getMessage());
        }

        String dateiMuster = Tool.text(args, "dateien");
        List<PathMatcher> filter = null;
        if (dateiMuster != null && !dateiMuster.isBlank()) {
            try {
                filter = GlobTool.muster(dateiMuster);
            } catch (IllegalArgumentException e) {   // schliesst PatternSyntaxException ein
                return ToolResult.fehler("Dateimuster nicht lesbar: " + e.getMessage());
            }
        }
        final List<PathMatcher> f = filter;

        List<String> treffer = new ArrayList<>();
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                return GlobTool.UEBERSPRINGEN.contains(d.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path datei, BasicFileAttributes a) {
                if (a.size() > MAX_DATEIGROESSE) return FileVisitResult.CONTINUE;
                Path rel = ws.wurzel().relativize(datei);
                if (f != null && !GlobTool.passt(f, rel)) return FileVisitResult.CONTINUE;
                try {
                    List<String> zeilen = Files.readAllLines(datei);
                    for (int i = 0; i < zeilen.size(); i++) {
                        String z = zeilen.get(i);
                        if (!p.matcher(z).find()) continue;
                        treffer.add(rel + ":" + (i + 1) + ":"
                                + (z.length() > 200 ? z.substring(0, 200) + "…" : z));
                        if (treffer.size() >= MAX_TREFFER) return FileVisitResult.TERMINATE;
                    }
                } catch (MalformedInputException e) {
                    // keine Textdatei -- ueberspringen
                } catch (IOException e) {
                    // unlesbar -- ueberspringen
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path x, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });

        if (treffer.isEmpty())
            return ToolResult.ok("keine Treffer fuer /" + muster + "/");

        StringBuilder b = new StringBuilder();
        for (String s : treffer) b.append(s).append('\n');
        if (treffer.size() >= MAX_TREFFER)
            b.append("[bei ").append(MAX_TREFFER).append(" Treffern abgebrochen — Ausdruck enger fassen]\n");

        return Spill.vielleichtAuslagern(b.toString(), ws, "grep", false);
    }
}
