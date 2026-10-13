package de.dg1001.harness.agent;

import de.dg1001.harness.wire.Json;
import de.dg1001.harness.wire.Messages.ToolCall;
import de.dg1001.harness.ws.Projekt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Welche bash-Kommandos ohne Nachfrage laufen und welche nie.
 *
 * <p>Bisher gab es nur zwei Zustaende: jedes {@code ls} fragt, oder
 * {@code /frei} schaltet alles ab. Das erste nervt so, dass man beim zweiten
 * landet -- und dann ist die Frage auch fuer {@code rm -rf} weg.
 *
 * <p><b>Ein Muster gilt fuer ein Kommando, nicht fuer eine Zeile.</b>
 * {@code pytest*} darf {@code pytest && rm -rf ~} nicht durchwinken. Deshalb
 * wird an Shell-Operatoren zerlegt ({@code && || ; | &}, Zeilenumbruch,
 * ausserhalb von Anfuehrungszeichen), und jedes Teilkommando muss fuer sich
 * erlaubt sein. Was sich so nicht zerlegen laesst -- {@code $(...)},
 * Backticks, {@code <(...)} --, wird nie automatisch erlaubt.
 *
 * <p><b>Verbote sind bestmoeglich, nicht dicht.</b> {@code git push*} faengt
 * {@code cd x && git push}, aber nicht {@code bash -c 'git push'} oder ein
 * Skript, das es tut. Sie gelten auch im Stapelbetrieb und bei {@code /frei}
 * -- als Leitplanke gegen das Versehen, nicht gegen Absicht. Fuer Letzteres
 * gibt es {@code --sandkasten}.
 */
public final class Regeln {

    private final List<String> erlauben;
    private final List<String> verbieten;

    public Regeln(List<String> erlauben, List<String> verbieten) {
        this.erlauben = new CopyOnWriteArrayList<>(erlauben);
        this.verbieten = List.copyOf(verbieten);
    }

    public static Regeln aus(Projekt p) {
        return new Regeln(p.erlauben(), p.verbieten());
    }

    public static final Regeln KEINE = new Regeln(List.of(), List.of());

    /** Fuer diese Sitzung zusaetzlich erlauben. */
    public void erlaube(String muster) {
        if (!erlauben.contains(muster)) erlauben.add(muster);
    }

    public List<String> erlaubt() { return List.copyOf(erlauben); }

    /** Das Verbotsmuster, das greift, oder null. */
    public String verbotenDurch(String kommando) {
        if (kommando == null) return null;
        List<String> teile = teile(kommando);
        for (String m : verbieten) {
            if (passt(m, kommando.strip())) return m;
            for (String t : teile) if (passt(m, t)) return m;
        }
        return null;
    }

    /** Ob jedes Teilkommando von einem Erlaubnismuster gedeckt ist. */
    public boolean erlaubt(String kommando) {
        if (kommando == null || kommando.isBlank() || erlauben.isEmpty()) return false;
        if (kommando.contains("$(") || kommando.contains("`")
                || kommando.contains("<(") || kommando.contains(">(")) return false;
        List<String> teile = teile(kommando);
        if (teile.isEmpty()) return false;
        for (String t : teile)
            if (erlauben.stream().noneMatch(m -> passt(m, t))) return false;
        return true;
    }

    /** Freigabe fuer den Stapelbetrieb: nur Verbote, alles andere laeuft. */
    public Freigabe nurVerbote() {
        return tc -> {
            if (!"bash".equals(tc.name())) return null;
            String m = verbotenDurch(kommando(tc));
            return m == null ? null : verbotsmeldung(m);
        };
    }

    public static String verbotsmeldung(String muster) {
        return "Dieses Kommando ist fuer dieses Projekt verboten (.jaja.json: " + muster
             + "). Waehle einen anderen Weg.";
    }

    /**
     * Ein Muster fuer "das hier ab jetzt nicht mehr fragen": der Befehl mit
     * seinem Unterbefehl, nicht die ganze Zeile. {@code git status -s} wird zu
     * {@code git status*}, {@code python3 -m pytest -q x} zu
     * {@code python3 -m pytest*}, {@code ls -la} zu {@code ls*}. Bei einer
     * zusammengesetzten Zeile gibt es keinen Vorschlag -- welches Teil waere
     * gemeint?
     */
    public static String vorschlag(String kommando) {
        if (kommando == null) return null;
        List<String> teile = teile(kommando);
        if (teile.size() != 1 || kommando.contains("$(") || kommando.contains("`")) return null;
        String[] w = teile.get(0).split("\\s+");
        if (w.length == 0 || w[0].isEmpty() || w[0].contains("=")) return null;
        int n = 1;
        String b = w[0].replaceAll(".*/", "");
        if (MIT_UNTERBEFEHL.contains(b)) n = 2;
        if (b.startsWith("python") && w.length > 2 && w[1].equals("-m")) n = 3;
        n = Math.min(n, w.length);
        return String.join(" ", List.of(w).subList(0, n)) + "*";
    }

    private static final Set<String> MIT_UNTERBEFEHL = Set.of(
            "git", "npm", "npx", "yarn", "pnpm", "cargo", "go", "mvn", "gradle", "make",
            "docker", "kubectl", "pip", "pip3", "uv", "poetry", "dotnet", "bundle", "rake");

    // ------------------------------------------------------------- Zutaten

    /** Zerlegt an Operatoren ausserhalb von Anfuehrungszeichen. */
    static List<String> teile(String s) {
        List<String> l = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                b.append(c);
                if (c == '\\' && quote == '"' && i + 1 < s.length()) b.append(s.charAt(++i));
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; b.append(c); continue; }
            if (c == '\\' && i + 1 < s.length()) { b.append(c).append(s.charAt(++i)); continue; }
            boolean trenner = c == ';' || c == '\n' || c == '|' || c == '&';
            // Umleitungen wie 2>&1 oder &> sind keine Trenner.
            if (c == '&' && ((i > 0 && s.charAt(i - 1) == '>') || (i + 1 < s.length() && s.charAt(i + 1) == '>')))
                trenner = false;
            if (trenner) {
                if (!b.toString().isBlank()) l.add(b.toString().strip());
                b.setLength(0);
                continue;
            }
            b.append(c);
        }
        if (!b.toString().isBlank()) l.add(b.toString().strip());
        return l;
    }

    /** {@code *} steht fuer beliebig viel, sonst woertlich; ganzer Text. */
    static boolean passt(String muster, String text) {
        String m = muster.strip(), t = text.strip();
        StringBuilder re = new StringBuilder();
        for (String teil : m.split("\\*", -1)) {
            if (!re.isEmpty()) re.append(".*");
            re.append(java.util.regex.Pattern.quote(teil));
        }
        return t.matches("(?s)" + re);
    }

    static String kommando(ToolCall tc) {
        try {
            return Json.str(Json.feld(Json.parse(tc.argumentsJson()), "kommando"));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
