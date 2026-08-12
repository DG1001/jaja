package de.dg1001.harness.tui;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown lesbar machen, ohne es zu veraendern.
 *
 * <p>Entstanden, weil der Agent staendig Markdown schreibt — Uebergaben,
 * READMEs, Notizen — und {@code cat} davon nur den Quelltext zeigt. Es geht
 * hier nicht um eine vollstaendige Umsetzung der Spezifikation, sondern um die
 * Handvoll Dinge, die den Unterschied zwischen "lesbar" und "Zeichensalat"
 * ausmachen: Ueberschriften, Listen, Quelltextbloecke, Tabellen mit
 * ausgerichteten Spalten und Umbruch auf die Fensterbreite.
 *
 * <p><b>Was bewusst fehlt:</b> verschachtelte Betonung, Fussnoten, HTML,
 * Referenzlinks, Bilder. Wer die braucht, nimmt glow oder bat. Dieser hier
 * kostet keine Installation und keine Abhaengigkeit.
 *
 * <p>Aufruf: {@code java -cp jaja.jar de.dg1001.harness.tui.Markdown datei.md}
 */
public final class Markdown {

    private final int breite;
    private final List<String> aus = new ArrayList<>();

    public Markdown(int breite) { this.breite = Math.max(20, breite); }

    public static void main(String[] args) throws IOException {
        List<String> dateien = new ArrayList<>();
        int breite = Terminal.breite();
        boolean farbe = System.console() != null || System.getenv("FORCE_COLOR") != null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--breite" -> breite = Integer.parseInt(args[++i]);
                case "--farbe"  -> farbe = true;
                case "--roh"    -> farbe = false;
                case "--hilfe", "-h" -> { hilfe(); return; }
                default -> dateien.add(args[i]);
            }
        }
        if (dateien.isEmpty()) { hilfe(); System.exit(2); }

        for (String d : dateien) {
            Path p = Path.of(d);
            if (!Files.isReadable(p)) {
                System.err.println("nicht lesbar: " + d);
                System.exit(1);
            }
            String text;
            try {
                text = Files.readString(p);
            } catch (MalformedInputException e) {
                System.err.println("keine UTF-8-Textdatei: " + d);
                System.exit(1);
                return;
            }
            String gesetzt = new Markdown(breite).setze(text);
            System.out.println(farbe ? gesetzt : ohneFarbe(gesetzt));
        }
    }

    private static void hilfe() {
        System.err.println("""
            markdown — Markdown im Terminal lesbar setzen

              java -cp jaja.jar de.dg1001.harness.tui.Markdown [Optionen] datei.md ...

              --breite <n>   Zeilenbreite, Vorgabe: Terminalbreite
              --farbe        Farben erzwingen (z. B. fuer '| less -R')
              --roh          ohne Farben

            Beispiel:  md NOTIZEN.md | less -R""");
    }

    // ------------------------------------------------------------------ setzen

    public String setze(String markdown) {
        aus.clear();
        List<String> zeilen = markdown.lines().toList();

        for (int i = 0; i < zeilen.size(); i++) {
            String z = zeilen.get(i);

            // ------------------------------------------- Quelltextblock
            String zaun = zaunAnfang(z);
            if (zaun != null) {
                String sprache = z.strip().substring(zaun.length()).strip();
                i = quelltext(zeilen, i + 1, zaun, sprache);
                continue;
            }

            // ------------------------------------------------- Trennlinie
            if (istTrennlinie(z)) {
                aus.add(Terminal.GRAU + "─".repeat(breite) + Terminal.NORMAL);
                continue;
            }

            // ------------------------------------------------ Ueberschrift
            int grad = ueberschriftsgrad(z);
            if (grad > 0) { ueberschrift(z, grad); continue; }

            // ---------------------------------------------------- Tabelle
            if (istTabellenzeile(z) && i + 1 < zeilen.size()
                    && istTrennzeile(zeilen.get(i + 1))) {
                i = tabelle(zeilen, i);
                continue;
            }

            // ------------------------------------------------------ Zitat
            if (z.stripLeading().startsWith(">")) {
                String inhalt = z.stripLeading().substring(1).stripLeading();
                umbrechen(inline(inhalt), Terminal.GRAU + "│ " + Terminal.NORMAL, "  ");
                continue;
            }

            // ------------------------------------------------------ Liste
            String[] punkt = listenpunkt(z);
            if (punkt != null) {
                String einzug = punkt[0];
                umbrechen(inline(punkt[2]),
                          einzug + Terminal.GELB + punkt[1] + Terminal.NORMAL + " ",
                          einzug + " ".repeat(sichtbar(punkt[1]) + 1));
                continue;
            }

            // ------------------------------------------------------ leer
            if (z.isBlank()) { aus.add(""); continue; }

            // --------------------------------------------------- Absatz
            umbrechen(inline(z.strip()), "", "");
        }
        return String.join("\n", aus);
    }

    // ---------------------------------------------------------- Bausteine

    private static String zaunAnfang(String z) {
        String s = z.stripLeading();
        if (s.startsWith("```")) return "```";
        if (s.startsWith("~~~")) return "~~~";
        return null;
    }

    /** Quelltext wird woertlich uebernommen — dort darf nichts umgebrochen
     *  und nichts als Betonung gelesen werden. */
    private int quelltext(List<String> zeilen, int ab, String zaun, String sprache) {
        aus.add(Terminal.GRAU + "┌─ " + (sprache.isEmpty() ? "" : sprache) + Terminal.NORMAL);
        int i = ab;
        for (; i < zeilen.size(); i++) {
            String z = zeilen.get(i);
            if (z.stripLeading().startsWith(zaun)) break;
            aus.add(Terminal.GRAU + "│ " + Terminal.NORMAL + Terminal.CYAN
                    + kappen(z, breite - 2) + Terminal.NORMAL);
        }
        aus.add(Terminal.GRAU + "└─" + Terminal.NORMAL);
        return i;
    }

    private static boolean istTrennlinie(String z) {
        String s = z.strip().replace(" ", "");
        return s.length() >= 3
            && (s.chars().allMatch(c -> c == '-')
             || s.chars().allMatch(c -> c == '*')
             || s.chars().allMatch(c -> c == '_'));
    }

    private static int ueberschriftsgrad(String z) {
        String s = z.stripLeading();
        int n = 0;
        while (n < s.length() && s.charAt(n) == '#') n++;
        return (n >= 1 && n <= 6 && n < s.length() && s.charAt(n) == ' ') ? n : 0;
    }

    private void ueberschrift(String z, int grad) {
        String text = z.stripLeading().substring(grad).strip();
        if (!aus.isEmpty() && !aus.get(aus.size() - 1).isEmpty()) aus.add("");
        switch (grad) {
            case 1 -> {
                aus.add(Terminal.FETT + Terminal.BLAU + text.toUpperCase() + Terminal.NORMAL);
                aus.add(Terminal.BLAU + "═".repeat(Math.min(breite, sichtbar(text)))
                        + Terminal.NORMAL);
            }
            case 2 -> {
                aus.add(Terminal.FETT + Terminal.CYAN + text + Terminal.NORMAL);
                aus.add(Terminal.GRAU + "─".repeat(Math.min(breite, sichtbar(text)))
                        + Terminal.NORMAL);
            }
            default -> aus.add(Terminal.FETT + text + Terminal.NORMAL);
        }
    }

    /** @return {Einzug, Aufzaehlungszeichen, Inhalt} oder null */
    private static String[] listenpunkt(String z) {
        int e = 0;
        while (e < z.length() && z.charAt(e) == ' ') e++;
        String s = z.substring(e);
        if (s.length() > 1 && (s.startsWith("- ") || s.startsWith("* ") || s.startsWith("+ ")))
            return new String[]{" ".repeat(e), "•", s.substring(2)};

        int p = 0;
        while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
        if (p > 0 && p + 1 < s.length() && (s.charAt(p) == '.' || s.charAt(p) == ')')
                && s.charAt(p + 1) == ' ')
            return new String[]{" ".repeat(e), s.substring(0, p + 1), s.substring(p + 2)};
        return null;
    }

    // ----------------------------------------------------------- Tabellen

    private static boolean istTabellenzeile(String z) {
        String s = z.strip();
        return s.startsWith("|") && s.length() > 1;
    }

    /** Die Zeile aus ---, :--- und ---: unter der Kopfzeile. */
    private static boolean istTrennzeile(String z) {
        String s = z.strip();
        if (!s.startsWith("|")) return false;
        for (String feld : felder(s)) {
            String f = feld.strip();
            if (f.isEmpty()) return false;
            if (!f.chars().allMatch(c -> c == '-' || c == ':' || c == ' ')) return false;
        }
        return true;
    }

    private static List<String> felder(String zeile) {
        String s = zeile.strip();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|"))   s = s.substring(0, s.length() - 1);
        List<String> f = new ArrayList<>();
        for (String t : s.split("\\|", -1)) f.add(t.strip());
        return f;
    }

    /**
     * Setzt eine Tabelle mit ausgerichteten Spalten.
     *
     * <p>Das ist der Teil, der sich am meisten lohnt: eine Markdown-Tabelle
     * ist im Quelltext fast immer unausgerichtet, und genau dann ist sie von
     * Hand nicht mehr zu lesen.
     */
    private int tabelle(List<String> zeilen, int ab) {
        List<List<String>> reihen = new ArrayList<>();
        reihen.add(felder(zeilen.get(ab)));

        int i = ab + 2;                       // Kopf und Trennzeile ueberspringen
        for (; i < zeilen.size() && istTabellenzeile(zeilen.get(i)); i++)
            reihen.add(felder(zeilen.get(i)));

        int spalten = reihen.stream().mapToInt(List::size).max().orElse(0);
        int[] weite = new int[spalten];
        for (List<String> r : reihen)
            for (int s = 0; s < r.size(); s++)
                weite[s] = Math.max(weite[s], sichtbar(inline(r.get(s))));

        for (int r = 0; r < reihen.size(); r++) {
            StringBuilder b = new StringBuilder();
            List<String> reihe = reihen.get(r);
            for (int s = 0; s < spalten; s++) {
                String feld = s < reihe.size() ? inline(reihe.get(s)) : "";
                if (s > 0) b.append(Terminal.GRAU).append(" │ ").append(Terminal.NORMAL);
                if (r == 0) b.append(Terminal.FETT);
                b.append(feld).append(" ".repeat(weite[s] - sichtbar(feld)));
                if (r == 0) b.append(Terminal.NORMAL);
            }
            aus.add(b.toString().stripTrailing());
            if (r == 0) {
                StringBuilder t = new StringBuilder(Terminal.GRAU);
                for (int s = 0; s < spalten; s++) {
                    if (s > 0) t.append("─┼─");
                    t.append("─".repeat(weite[s]));
                }
                aus.add(t.append(Terminal.NORMAL).toString());
            }
        }
        return i - 1;
    }

    // ------------------------------------------------------------- inline

    /**
     * Wandelt Betonung in Steuerfolgen um.
     *
     * <p>Reihenfolge zaehlt: Quelltext zuerst, sonst wuerde ein {@code **} in
     * einem {@code `a ** b`} als Fettdruck gelesen. Ein vollstaendiger Parser
     * waere hier deutlich mehr Arbeit fuer sehr wenig zusaetzlichen Nutzen.
     */
    static String inline(String s) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);

            if (c == '`') {                                    // `Quelltext`
                int e = s.indexOf('`', i + 1);
                if (e > i) {
                    b.append(Terminal.CYAN).append(s, i + 1, e).append(Terminal.NORMAL);
                    i = e + 1; continue;
                }
            }
            if (c == '[') {                                    // [Text](Ziel)
                int zu = s.indexOf(']', i);
                if (zu > i && zu + 1 < s.length() && s.charAt(zu + 1) == '(') {
                    int ende = s.indexOf(')', zu);
                    if (ende > zu) {
                        b.append(Terminal.BLAU).append(s, i + 1, zu).append(Terminal.NORMAL)
                         .append(Terminal.GRAU).append(' ').append(s, zu + 2, ende)
                         .append(Terminal.NORMAL);
                        i = ende + 1; continue;
                    }
                }
            }
            if (s.startsWith("**", i)) {                       // **fett**
                int e = s.indexOf("**", i + 2);
                if (e > i) {
                    b.append(Terminal.FETT).append(inline(s.substring(i + 2, e)))
                     .append(Terminal.NORMAL);
                    i = e + 2; continue;
                }
            }
            if ((c == '*' || c == '_') && i + 1 < s.length() && s.charAt(i + 1) != ' ') {
                int e = s.indexOf(c, i + 1);
                if (e > i) {                                   // *kursiv*
                    b.append(Terminal.GELB).append(s, i + 1, e).append(Terminal.NORMAL);
                    i = e + 1; continue;
                }
            }
            b.append(c);
            i++;
        }
        return b.toString();
    }

    // ------------------------------------------------------------ umbrechen

    /**
     * Bricht auf die Fensterbreite um, ohne Steuerfolgen mitzuzaehlen.
     *
     * <p>Am Ende jeder Zeile wird zurueckgesetzt. Ohne das faerbte eine
     * Betonung, die ueber den Umbruch reicht, den Einzug der naechsten Zeile
     * mit ein — und das sieht aus wie ein Anzeigefehler, weil es einer ist.
     */
    private void umbrechen(String text, String erstesPraefix, String weiteresPraefix) {
        if (text.isBlank()) { aus.add(erstesPraefix.stripTrailing()); return; }

        int platz = breite - sichtbar(weiteresPraefix);
        if (platz < 10) platz = 10;

        StringBuilder zeile = new StringBuilder();
        int laenge = 0;
        String praefix = erstesPraefix;

        for (String wort : text.split(" ")) {
            int w = sichtbar(wort);
            if (laenge > 0 && laenge + 1 + w > platz) {
                aus.add(praefix + zeile + Terminal.NORMAL);
                praefix = weiteresPraefix;
                zeile.setLength(0);
                laenge = 0;
            }
            if (laenge > 0) { zeile.append(' '); laenge++; }
            zeile.append(wort);
            laenge += w;
        }
        if (laenge > 0) aus.add(praefix + zeile + Terminal.NORMAL);
    }

    /** Laenge ohne ANSI-Steuerfolgen. */
    static int sichtbar(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\033') {
                while (i < s.length() && s.charAt(i) != 'm') i++;
                continue;
            }
            n++;
        }
        return n;
    }

    private static String kappen(String s, int n) {
        return sichtbar(s) <= n ? s : s.substring(0, Math.max(0, n - 1)) + "…";
    }

    static String ohneFarbe(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }
}
