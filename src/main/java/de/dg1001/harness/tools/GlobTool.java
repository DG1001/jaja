package de.dg1001.harness.tools;

import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
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
import java.util.Set;

/** Sucht Dateien nach Namensmuster. */
public final class GlobTool implements Tool {

    static final int MAX_TREFFER = 300;

    /** Verzeichnisse, die nie interessieren und die Ausgabe zumuellen wuerden. */
    static final Set<String> UEBERSPRINGEN = Set.of(
            ".git", ".venv", "venv", "__pycache__", "node_modules",
            ".harness", ".pytest_cache", "target", "build", ".mypy_cache");

    @Override public String name() { return "glob"; }

    @Override public String beschreibung() {
        return "Findet Dateien nach Namensmuster, z. B. **/*.py. Nimm das, um dir einen "
             + "Ueberblick zu verschaffen, bevor du liest.";
    }

    @Override public String parameterSchema() {
        return """
               {"type":"object",
                "properties":{
                  "muster":{"type":"string","description":"Glob, z. B. **/*.py oder src/**/*.java"},
                  "ab":{"type":"string","description":"Startverzeichnis, Vorgabe Projektwurzel"}},
                "required":["muster"]}""";
    }

    /**
     * Baut die Muster fuer einen Glob-Ausdruck.
     *
     * <p>Javas PathMatcher verlangt bei {@code **}{@code /} mindestens eine
     * Verzeichnisebene: {@code **}{@code /*.py} trifft {@code src/a.py}, aber
     * nicht {@code a.py} im Wurzelverzeichnis. Modelle schreiben das Muster
     * aber selbstverstaendlich so und meinen "alle, auch die oben". Deshalb
     * zusaetzlich die Fassung ohne den Praefix.
     */
    static List<PathMatcher> muster(String glob) {
        List<PathMatcher> l = new ArrayList<>(2);
        l.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
        if (glob.startsWith("**/"))
            l.add(FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3)));
        return l;
    }

    static boolean passt(List<PathMatcher> muster, Path rel) {
        for (PathMatcher m : muster)
            if (m.matches(rel) || m.matches(rel.getFileName())) return true;
        return false;
    }

    @Override
    public ToolResult run(Map<String, Object> args, Workspace ws) throws IOException {
        String muster;
        try {
            muster = Tool.pflichtText(args, "muster");
        } catch (IllegalArgumentException e) {
            return ToolResult.fehler(e.getMessage());
        }

        Path start;
        try {
            String ab = Tool.text(args, "ab");
            start = (ab == null || ab.isBlank()) ? ws.wurzel() : ws.aufloesen(ab);
        } catch (Workspace.AusbruchFehler e) {
            return ToolResult.fehler(e.getMessage());
        }
        if (!Files.isDirectory(start))
            return ToolResult.fehler("kein Verzeichnis: " + ws.relativ(start));

        List<PathMatcher> m;
        try {
            m = muster(muster);
        } catch (IllegalArgumentException e) {   // schliesst PatternSyntaxException ein
            return ToolResult.fehler("Muster nicht lesbar: " + e.getMessage());
        }

        List<String> treffer = new ArrayList<>();
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                return UEBERSPRINGEN.contains(d.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                Path rel = ws.wurzel().relativize(f);
                if (passt(m, rel)) treffer.add(rel.toString());
                return treffer.size() >= MAX_TREFFER
                        ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) {
                return FileVisitResult.CONTINUE;   // unlesbares stillschweigend ueberspringen
            }
        });

        if (treffer.isEmpty())
            return ToolResult.ok("keine Treffer fuer " + muster);

        treffer.sort(null);
        StringBuilder b = new StringBuilder();
        for (String s : treffer) b.append(s).append('\n');
        if (treffer.size() >= MAX_TREFFER)
            b.append("[bei ").append(MAX_TREFFER).append(" Treffern abgebrochen — Muster enger fassen]\n");

        return Spill.vielleichtAuslagern(b.toString(), ws, "glob", false);
    }
}
