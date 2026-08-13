package de.dg1001.harness.tools;

import de.dg1001.harness.karte.Karte;
import de.dg1001.harness.karte.Quelldatei;
import de.dg1001.harness.karte.Scanner;
import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Map;

/**
 * Ueberblick ueber das Projekt, ohne Dateien zu lesen.
 *
 * <p>Der Zweck ist gemessen und schlicht: bei groesseren Projekten gehen mehrere
 * Zuege fuer Orientierung drauf — glob, grep, drei Dateien lesen — bevor die
 * eine gefunden ist, um die es geht. Ein Kartenaufruf ersetzt das.
 *
 * <p>Warum ein Werkzeug und nicht ein Anhang am Systemprompt: der Prompt ist
 * der Teil des Kontexts, an den keine Kuerzung herankommt. Eine Karte dort
 * kostete in jedem Zug und liesse den Verlauf frueher an die Wand fahren. Als
 * Werkzeugergebnis kostet sie nur, wenn sie gebraucht wird, und laesst sich
 * spaeter wegkuerzen wie jedes andere Ergebnis.
 */
public final class KarteTool implements Tool {

    @Override public String name() { return "karte"; }

    @Override public String beschreibung() {
        return "Zeigt Projektdateien mit ihren Definitionen und Verweisen, ohne sie zu lesen. "
             + "Nimm das zuerst, um dich zu orientieren. Mit doppelte=true zeigt es Namen, "
             + "die an mehreren Stellen definiert sind. Aus Mustern geschaetzt, also ein "
             + "Hinweis und keine Gewaehr.";
    }

    @Override public String parameterSchema() {
        return """
               {"type":"object",
                "properties":{
                  "stichwort":{"type":"string","description":"filtert ueber Pfad, Definitionen, Stichworte"},
                  "muster":{"type":"string","description":"Glob wie bei glob, z. B. src/**/*.py"},
                  "datei":{"type":"string","description":"Einzelansicht mit allen Verweisen"},
                  "doppelte":{"type":"boolean","description":"Namen zeigen, die an mehreren Stellen definiert sind"}},
                "required":[]}""";
    }

    @Override
    public ToolResult run(Map<String, Object> args, Workspace ws) throws IOException {
        Karte karte = new Karte(ws);
        Scanner s = karte.auffrischen();

        String datei = Tool.text(args, "datei");
        if (datei != null && !datei.isBlank()) {
            String rel;
            try {
                rel = ws.relativ(ws.aufloesen(datei.trim()));
            } catch (Workspace.AusbruchFehler e) {
                return ToolResult.fehler(e.getMessage());
            }
            Quelldatei q = karte.dateien().get(rel.replace('\\', '/'));
            if (q == null)
                return ToolResult.fehler("nicht in der Karte: " + datei
                        + " (unbekannte Endung, zu gross, oder gibt es nicht)");
            return ToolResult.ok(karte.einzeln(q));
        }

        if (args.get("doppelte") instanceof Boolean b && b)
            return Spill.vielleichtAuslagern(karte.doppelteAlsText(), ws, "karte", false);

        String stichwort = leerAlsNull(Tool.text(args, "stichwort"));
        String glob      = leerAlsNull(Tool.text(args, "muster"));

        List<PathMatcher> muster = null;
        if (glob != null) {
            try {
                muster = GlobTool.muster(glob);
            } catch (IllegalArgumentException e) {   // schliesst PatternSyntaxException ein
                return ToolResult.fehler("Muster nicht lesbar: " + e.getMessage());
            }
        }

        String wonach = stichwort != null && glob != null ? "'" + stichwort + "' und " + glob
                      : stichwort != null ? "'" + stichwort + "'"
                      : glob != null ? glob : "der Auswahl";

        String text = karte.uebersicht(karte.suche(stichwort, muster), wonach);

        // Nur beim ersten Aufbau interessant, danach steht da meist 0.
        if (s.gelesen() > 0)
            text += "\n[" + s.gelesen() + " Datei(en) neu eingelesen]";

        return Spill.vielleichtAuslagern(text, ws, "karte", false);
    }

    private static String leerAlsNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
