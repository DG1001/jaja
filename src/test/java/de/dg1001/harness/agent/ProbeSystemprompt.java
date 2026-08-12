package de.dg1001.harness.agent;

import de.dg1001.harness.ws.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Prueft, wie Grundlage und Projektregeln zusammenkommen.
 *
 * <p>Der wichtigste Fall ist der langweiligste: dass die Grundlage stehen
 * bleibt, wenn eine {@code AGENT.md} dazukommt. Dort steht die Anweisung,
 * zuerst zu handeln statt vorab durchzuplanen — eine Gegenmassnahme gegen
 * einen gemessenen Ausfall. Wer sie beim Anlegen einer Projektdatei verliert,
 * merkt es erst an einem Lauf, der nichts tut.
 */
public final class ProbeSystemprompt {

    private static final String GRUND = "Grundlage: fang mit einem konkreten Schritt an.";
    private static int fehlgeschlagen = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("jaja-sp");
        Workspace ws = new Workspace(tmp);

        // ------------------------------------------------------- ohne Datei
        Systemprompt.Ergebnis ohne = Systemprompt.baue(GRUND, ws, null, false);
        pruefe("ohne AGENT.md bleibt die Grundlage", ohne.prompt(), GRUND);
        pruefe("ohne AGENT.md keine Quelle", ohne.quelle(), null);

        // ---------------------------------------------------- mit AGENT.md
        Files.writeString(tmp.resolve("AGENT.md"),
                "# Regeln\n\nTests laufen mit `just test`.\nKeine Aenderungen unter vendor/.\n");
        Systemprompt.Ergebnis mit = Systemprompt.baue(GRUND, ws, null, false);

        pruefe("AGENT.md wird gefunden",
               mit.quelle() != null && mit.quelle().getFileName().toString().equals("AGENT.md"));
        pruefe("Grundlage bleibt erhalten", mit.prompt().contains(GRUND));
        pruefe("Projektregeln stehen drin", mit.prompt().contains("just test"));
        pruefe("die Datei wird benannt", mit.prompt().contains("AGENT.md"));
        // Ohne den Vorrang liest das Modell die Regeln als unverbindlichen Anhang.
        pruefe("Vorrang ist gesagt", mit.prompt().contains("gehen im Zweifel vor"));
        pruefe("Grundlage steht vor den Projektregeln",
               mit.prompt().indexOf(GRUND) < mit.prompt().indexOf("just test"));
        pruefe("Zeichenzahl zaehlt nur die Projektdatei",
               mit.zeichen() < 100 && mit.zeichen() > 30);

        // ------------------------------------------------------- abschalten
        pruefe("--kein-agent-md ignoriert die Datei",
               Systemprompt.baue(GRUND, ws, null, true).prompt(), GRUND);

        // --------------------------------------------------------- ersetzen
        Path eigen = tmp.resolve("eigen.txt");
        Files.writeString(eigen, "Nur das hier.");
        Systemprompt.Ergebnis ersetzt = Systemprompt.baue(GRUND, ws, eigen, false);
        pruefe("--systemprompt ersetzt wirklich ganz", ersetzt.prompt(), "Nur das hier.");
        pruefe("--systemprompt schlaegt AGENT.md",
               !ersetzt.prompt().contains("just test"));

        // ------------------------------------------------------- Reihenfolge
        Files.writeString(tmp.resolve("AGENTS.md"), "zweite Wahl");
        pruefe("AGENT.md hat Vorrang vor AGENTS.md",
               Systemprompt.baue(GRUND, ws, null, false).quelle()
                       .getFileName().toString(), "AGENT.md");
        Files.delete(tmp.resolve("AGENT.md"));
        pruefe("AGENTS.md wird auch genommen",
               Systemprompt.baue(GRUND, ws, null, false).prompt().contains("zweite Wahl"));

        // -------------------------------------------------------- Randfaelle
        Files.writeString(tmp.resolve("AGENTS.md"), "   \n\n  ");
        pruefe("leere AGENT.md aendert nichts",
               Systemprompt.baue(GRUND, ws, null, false).prompt(), GRUND);

        // Der Prompt steht in jeder Anfrage und ist unkuerzbar -- eine grosse
        // Projektdatei muss auffallen, nicht stillschweigend Budget fressen.
        Files.writeString(tmp.resolve("AGENTS.md"), "x".repeat(Systemprompt.GROSS + 1));
        Systemprompt.Ergebnis gross = Systemprompt.baue(GRUND, ws, null, false);
        pruefe("grosse Projektdatei wird gemeldet", gross.warnung() != null);
        pruefe("die Warnung nennt die Groesse",
               gross.warnung().contains(String.valueOf(Systemprompt.GROSS + 1)));
        pruefe("sie wird trotzdem verwendet", gross.prompt().length() > Systemprompt.GROSS);

        Files.writeString(tmp.resolve("AGENTS.md"), "klein");
        pruefe("kleine Projektdatei wird nicht gemeldet",
               Systemprompt.baue(GRUND, ws, null, false).warnung(), null);

        // Ein Verzeichnis namens AGENT.md ist keine Datei.
        Files.delete(tmp.resolve("AGENTS.md"));
        Files.createDirectory(tmp.resolve("AGENT.md"));
        pruefe("Verzeichnis mit dem Namen wird uebergangen",
               Systemprompt.baue(GRUND, ws, null, false).prompt(), GRUND);

        System.out.println(fehlgeschlagen == 0
                ? "\nAlle Pruefungen bestanden."
                : "\n" + fehlgeschlagen + " Pruefung(en) fehlgeschlagen.");
        if (fehlgeschlagen > 0) System.exit(1);
    }

    private static void pruefe(String was, Object erhalten, Object erwartet) {
        boolean ok = (erhalten == null) ? erwartet == null : erhalten.equals(erwartet);
        melde(was, ok);
        if (!ok) System.out.println("    erwartet: " + erwartet + "\n    erhalten: " + erhalten);
    }

    private static void pruefe(String was, boolean ok) { melde(was, ok); }

    private static void melde(String was, boolean ok) {
        System.out.printf("%-52s %s%n", was, ok ? "ok" : "FEHLGESCHLAGEN");
        if (!ok) fehlgeschlagen++;
    }
}
