package de.dg1001.harness.tui;

/**
 * Prueft den Markdown-Setzer.
 *
 * <p>Geprueft wird durchweg gegen die <em>farblose</em> Fassung: was zaehlt,
 * ist die Anordnung — Einzuege, Spaltenbreiten, Umbruchstellen. Ob eine
 * Ueberschrift blau oder cyan ist, ist Geschmack und gehoert nicht in eine
 * Pruefung.
 *
 * <p>Die Ausnahme sind die Faelle, in denen Farbe und Breite zusammenhaengen:
 * eine Steuerfolge darf nie als Zeichen mitgezaehlt werden, sonst brechen
 * betonte Zeilen zu frueh um.
 */
public final class ProbeMarkdown {

    private static int fehlgeschlagen = 0;

    public static void main(String[] args) {

        // ------------------------------------------------------ sichtbare Laenge
        pruefe("sichtbar: reiner Text", Markdown.sichtbar("hallo"), 5);
        pruefe("sichtbar: Steuerfolgen zaehlen nicht",
               Markdown.sichtbar(Terminal.FETT + "hallo" + Terminal.NORMAL), 5);
        pruefe("sichtbar: nur Steuerfolgen sind nichts",
               Markdown.sichtbar(Terminal.GRAU + Terminal.NORMAL), 0);

        // -------------------------------------------------------------- inline
        pruefe("inline: Quelltext wird eingefaerbt",
               Markdown.inline("ein `wert` hier").contains(Terminal.CYAN), true);
        pruefe("inline: Quelltext behaelt seinen Text",
               roh(Markdown.inline("ein `wert` hier")), "ein wert hier");
        pruefe("inline: fett", roh(Markdown.inline("sehr **wichtig** hier")),
               "sehr wichtig hier");
        pruefe("inline: kursiv", roh(Markdown.inline("etwas *schraeg* hier")),
               "etwas schraeg hier");
        pruefe("inline: Link zeigt Text und Ziel",
               roh(Markdown.inline("siehe [dort](https://a.b)")), "siehe dort https://a.b");
        // Ein Sternchen in Quelltext ist keine Betonung. Genau daran scheitern
        // einfache Setzer, und das Ergebnis ist ein halb gefaerbter Absatz.
        pruefe("inline: Sternchen im Quelltext bleibt Text",
               roh(Markdown.inline("`a ** b`")), "a ** b");
        pruefe("inline: unpaariges Sternchen bleibt stehen",
               roh(Markdown.inline("2 * 3 = 6")), "2 * 3 = 6");

        // -------------------------------------------------------- Ueberschriften
        String h = setze("# Titel\n", 40);
        pruefe("h1: Text erscheint", h.contains("TITEL"), true);
        pruefe("h1: Unterstreichung so lang wie der Text",
               h.lines().filter(z -> z.startsWith("═")).findFirst().orElse("").length(), 5);
        pruefe("h3 bekommt keine Linie",
               setze("### Klein\n", 40).lines().anyMatch(z -> z.startsWith("─")), false);
        pruefe("Raute ohne Leerzeichen ist keine Ueberschrift",
               setze("#kein Titel\n", 40).contains("#kein Titel"), true);

        // ---------------------------------------------------------------- Listen
        String l = setze("- eins\n- zwei\n  - tief\n", 40);
        pruefe("Liste: Aufzaehlungszeichen", l.contains("• eins"), true);
        pruefe("Liste: Verschachtelung bleibt eingerueckt", l.contains("  • tief"), true);
        pruefe("nummerierte Liste behaelt ihre Zahl",
               setze("3. drei\n", 40).contains("3. drei"), true);

        // ------------------------------------------------------------- Tabellen
        String t = setze("""
                | a | Punkte |
                |---|---|
                | langer Eintrag | 1 |
                | b | 22 |
                """, 60);
        // Der eigentliche Nutzen: im Quelltext sind die Spalten nie ausgerichtet.
        pruefe("Tabelle: Spalten sind ausgerichtet",
               t.lines().filter(z -> z.contains("│")).map(z -> z.indexOf('│'))
                .distinct().count(), 1L);
        pruefe("Tabelle: Trennlinie unter dem Kopf",
               t.lines().anyMatch(z -> z.contains("┼")), true);
        pruefe("Tabelle: alle Zeilen sind da",
               t.contains("langer Eintrag") && t.contains("22"), true);

        // --------------------------------------------------------- Quelltext
        String q = setze("```python\ndef f(**kw):\n    return 1\n```\n", 40);
        pruefe("Quelltext: Sprache steht am Rahmen", q.contains("python"), true);
        // Im Quelltext darf ** nicht als Fettdruck gelesen und nichts
        // umgebrochen werden -- sonst laeuft der Code nicht mehr.
        pruefe("Quelltext: ** bleibt stehen", q.contains("def f(**kw):"), true);
        pruefe("Quelltext: Einrueckung bleibt", q.contains("    return 1"), true);

        // ---------------------------------------------------------- Umbruch
        String lang = "wort ".repeat(40).strip();
        for (String z : setze(lang + "\n", 40).lines().toList())
            if (z.length() > 40) {
                pruefe("Umbruch haelt die Breite ein (" + z.length() + " > 40)", false);
                break;
            }
        pruefe("Umbruch haelt die Breite ein", true);
        pruefe("Umbruch verliert kein Wort",
               setze(lang + "\n", 40).replace("\n", " ").split("wort", -1).length - 1, 40);

        // Betonter Text darf nicht zu frueh umbrechen: die Steuerfolgen sind
        // unsichtbar und duerfen nicht als Zeichen zaehlen.
        String fett = setze("**" + "wort ".repeat(6).strip() + "**\n", 40);
        pruefe("Umbruch: Steuerfolgen kosten keine Breite",
               fett.lines().filter(z -> !z.isBlank()).count(), 1L);

        // ------------------------------------------------------------- Zitat
        pruefe("Zitat bekommt einen Balken", setze("> Achtung\n", 40).contains("│ Achtung"), true);

        // -------------------------------------------------------- Trennlinie
        pruefe("Trennlinie fuellt die Breite",
               setze("---\n", 30).lines().filter(z -> z.startsWith("─")).findFirst()
                       .orElse("").length(), 30);

        // ----------------------------------------------------------- Robustheit
        pruefe("leere Eingabe stuerzt nicht ab", setze("", 40), "");
        pruefe("nicht geschlossener Quelltextblock endet sauber",
               setze("```\nabc\n", 40).contains("abc"), true);
        pruefe("Tabelle ohne Trennzeile bleibt Text",
               setze("| a | b |\n", 40).contains("| a | b |"), true);

        System.out.println(fehlgeschlagen == 0
                ? "\nAlle Pruefungen bestanden."
                : "\n" + fehlgeschlagen + " Pruefung(en) fehlgeschlagen.");
        if (fehlgeschlagen > 0) System.exit(1);
    }

    private static String setze(String md, int breite) {
        return Markdown.ohneFarbe(new Markdown(breite).setze(md));
    }

    private static String roh(String s) { return Markdown.ohneFarbe(s); }

    private static void pruefe(String was, Object erhalten, Object erwartet) {
        boolean ok = (erhalten == null) ? erwartet == null : erhalten.equals(erwartet);
        melde(was, ok);
        if (!ok) System.out.println("    erwartet: " + erwartet + "\n    erhalten: " + erhalten);
    }

    private static void pruefe(String was, boolean ok) { melde(was, ok); }

    private static void melde(String was, boolean ok) {
        System.out.printf("%-56s %s%n", was, ok ? "ok" : "FEHLGESCHLAGEN");
        if (!ok) fehlgeschlagen++;
    }
}
