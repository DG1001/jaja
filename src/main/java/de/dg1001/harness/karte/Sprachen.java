package de.dg1001.harness.karte;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Was in welcher Sprache wie eine Definition und wie ein Import aussieht.
 *
 * <p><b>Regulaere Ausdruecke, kein Parser.</b> Das ist eine bewusste
 * Entscheidung und die groesste Schwaeche der Karte zugleich. tree-sitter waere
 * genauer und koennte 130 Sprachen — aber es waere die erste echte
 * Abhaengigkeit dieses Projekts, und der Zweck der Karte ist Orientierung, nicht
 * Analyse. Eine Zeile, die aussieht wie eine Funktionsdefinition, ist fuer
 * diesen Zweck eine.
 *
 * <p>Was dadurch durchfaellt: alles Dynamische. Importe zur Laufzeit,
 * Reflexion, erzeugter Quelltext, Makros. Die Karte ist ein Hinweis, keine
 * Wahrheit — und genau so steht es auch in der Werkzeugbeschreibung.
 */
public final class Sprachen {

    /**
     * @param name         Anzeigename
     * @param definitionen Muster mit den Gruppen art, name und optional args
     * @param importe      Muster mit einer Gruppe: das importierte Modul
     * @param modulTrenner Zeichen, das im Modulnamen die Ebenen trennt
     * @param endungen     womit Modulnamen zu Dateien werden
     */
    public record Sprache(String name, List<Pattern> definitionen, List<Pattern> importe,
                          char modulTrenner, List<String> endungen) {}

    private Sprachen() {}

    private static Pattern p(String s) { return Pattern.compile(s, Pattern.MULTILINE); }

    static final Sprache PYTHON = new Sprache("Python",
            List.of(p("^\\s*(?<art>async def|def|class)\\s+(?<name>\\w+)\\s*(?<args>\\([^)]*\\))?")),
            // import a.b.c  /  from a.b import c  /  from . import c
            List.of(p("^\\s*from\\s+(?<modul>[\\w.]+)\\s+import\\b"),
                    p("^\\s*import\\s+(?<modul>[\\w.]+)")),
            '.', List.of(".py"));

    static final Sprache JAVA = new Sprache("Java",
            List.of(p("^\\s*(?:public\\s+|private\\s+|protected\\s+|static\\s+|final\\s+"
                    + "|abstract\\s+|sealed\\s+|non-sealed\\s+)*"
                    + "(?<art>class|interface|record|enum)\\s+(?<name>\\w+)\\s*(?<args>\\([^)]*\\))?"),
                    // Methoden: eingerueckt, Sichtbarkeit, Rueckgabetyp, Name, Klammer
                    p("^\\s+(?:public|private|protected)\\s+(?:static\\s+|final\\s+|synchronized\\s+"
                    + "|default\\s+|abstract\\s+)*[\\w<>\\[\\],.?\\s]+?\\s(?<name>\\w+)\\s*"
                    + "(?<args>\\([^)]*\\))\\s*(?:throws [\\w,.\\s]+)?[{;]")),
            List.of(p("^\\s*import\\s+(?:static\\s+)?(?<modul>[\\w.]+)\\s*;")),
            '.', List.of(".java"));

    static final Sprache JS = new Sprache("JavaScript",
            List.of(p("^\\s*(?:export\\s+)?(?:default\\s+)?(?<art>async function|function|class)"
                    + "\\s+(?<name>\\w+)\\s*(?<args>\\([^)]*\\))?"),
                    p("^\\s*(?:export\\s+)?(?<art>const|let)\\s+(?<name>\\w+)\\s*=\\s*"
                    + "(?<args>\\([^)]*\\))\\s*=>")),
            List.of(p("\\bfrom\\s+['\"](?<modul>[^'\"]+)['\"]"),
                    p("\\brequire\\(\\s*['\"](?<modul>[^'\"]+)['\"]\\s*\\)")),
            '/', List.of(".js", ".mjs", ".ts", ".tsx", ".jsx"));

    static final Sprache GO = new Sprache("Go",
            List.of(p("^(?<art>func)\\s+(?:\\([^)]*\\)\\s*)?(?<name>\\w+)\\s*(?<args>\\([^)]*\\))"),
                    p("^(?<art>type)\\s+(?<name>\\w+)\\s")),
            List.of(p("^\\s*(?:import\\s+)?(?:\\w+\\s+)?\"(?<modul>[\\w./-]+)\"")),
            '/', List.of(".go"));

    static final Sprache SHELL = new Sprache("Shell",
            List.of(p("^\\s*(?:function\\s+)?(?<name>\\w+)\\s*\\(\\)\\s*\\{")),
            List.of(p("^\\s*(?:\\.|source)\\s+(?<modul>[\\w./-]+)")),
            '/', List.of(".sh", ".bash"));

    /** Reiner Text: keine Definitionen, keine Importe, aber gezaehlt und gelistet. */
    static final Sprache TEXT = new Sprache("Text", List.of(), List.of(), '/', List.of());

    private static final Map<String, Sprache> NACH_ENDUNG = Map.ofEntries(
            Map.entry(".py", PYTHON),
            Map.entry(".java", JAVA),
            Map.entry(".js", JS), Map.entry(".mjs", JS), Map.entry(".jsx", JS),
            Map.entry(".ts", JS), Map.entry(".tsx", JS),
            Map.entry(".go", GO),
            Map.entry(".sh", SHELL), Map.entry(".bash", SHELL),
            Map.entry(".md", TEXT), Map.entry(".txt", TEXT),
            Map.entry(".json", TEXT), Map.entry(".yml", TEXT), Map.entry(".yaml", TEXT),
            Map.entry(".toml", TEXT), Map.entry(".xml", TEXT), Map.entry(".cfg", TEXT),
            Map.entry(".ini", TEXT), Map.entry(".sql", TEXT), Map.entry(".html", TEXT),
            Map.entry(".css", TEXT));

    /** @return null, wenn die Endung nichts sagt — solche Dateien bleiben draussen. */
    public static Sprache fuer(String pfad) {
        int punkt = pfad.lastIndexOf('.');
        if (punkt < 0) return null;
        return NACH_ENDUNG.get(pfad.substring(punkt).toLowerCase());
    }

    /** Alle bekannten Sprachen, fuer die Pruefungen. */
    public static Sprache nachName(String name) {
        for (Sprache s : List.of(PYTHON, JAVA, JS, GO, SHELL, TEXT))
            if (s.name().equals(name)) return s;
        return null;
    }
}
