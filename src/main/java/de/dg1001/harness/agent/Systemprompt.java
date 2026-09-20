package de.dg1001.harness.agent;

import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Stellt den Systemprompt zusammen: Grundlage plus Projektregeln.
 *
 * <p><b>Warum {@code AGENT.md} ergaenzt und nicht ersetzt.</b> In der Grundlage
 * stehen Anweisungen, die aus Fehlschlaegen stammen — vor allem die, zuerst zu
 * handeln statt vorab durchzuplanen; ein gemessener Lauf verbrachte sonst einen
 * ganzen Zug in einem Denkblock und rief nie ein Werkzeug auf. Eine
 * {@code AGENT.md} enthaelt dagegen fast immer Projektwissen: welcher Testbefehl
 * gilt, welcher Stil, welche Verzeichnisse tabu sind. Zwei verschiedene Dinge.
 * Wuerde die Datei die Grundlage verdraengen, verloere man die Gegenmassnahme
 * stillschweigend — und zwar genau dann, wenn jemand zum ersten Mal eine
 * Projektdatei anlegt.
 *
 * <p>Wer wirklich ersetzen will, sagt es ausdruecklich: {@code --systemprompt}.
 *
 * <p>Der Prompt steht in <em>jeder</em> Anfrage. Eine lange Projektdatei kostet
 * also in jedem Zug, und zwar aus dem Teil des Budgets, den keine Kuerzung je
 * anfassen kann. Deshalb wird die Groesse gemeldet statt verschwiegen.
 */
public final class Systemprompt {

    /** Dateinamen, in dieser Reihenfolge. Die erste gefundene gilt. */
    public static final List<String> NAMEN = List.of("AGENT.md", "AGENTS.md", "agent.md");

    /** Ab hier wird die Projektdatei als bedenklich gross gemeldet. */
    static final int GROSS = 6000;

    public record Ergebnis(String prompt, Path quelle, int zeichen, String warnung) {}

    private Systemprompt() {}

    /**
     * Ein Satz, der dem Modell sagt, wie viel es pro Zug schreiben kann.
     *
     * <p>Bewusst in <em>Zeilen</em> und nicht in Token. Die Grenze ist eine
     * Tokengrenze, aber ein Modell kann seine eigene Tokenzahl nicht schaetzen —
     * "hoechstens 8192 Token" ist keine Anweisung, der es folgen kann, "keine
     * Datei ueber 400 Zeilen" schon.
     *
     * <p>Die Umrechnung ist grob und soll es sein: eine Zeile Code liegt je
     * nach Sprache zwischen 8 und 15 Token, dazu kommen JSON-Maskierung, der
     * Pfad und ein etwaiger Denkblock. Mit einem Zwanzigstel der Grenze bleibt
     * genug Luft, dass die Zahl auch dann noch stimmt, wenn das Modell sie nur
     * ungefaehr einhaelt — und darum geht es: eine Groessenordnung, keine
     * Schranke. Durchgesetzt wird nichts hier, sondern erst durch die Meldung
     * beim Abschneiden.
     */
    public static String ausgabehinweis(int maxAusgabe) {
        int zeilen = Math.max(50, (maxAusgabe / 20) / 50 * 50);
        return "Du kannst pro Zug hoechstens " + maxAusgabe + " Token erzeugen, "
             + "Werkzeugargumente eingerechnet. Schreibe deshalb keine Datei ueber "
             + "etwa " + zeilen + " Zeilen in einem Aufruf: lege erst ein knappes "
             + "Geruest an und fuege die weiteren Teile danach einzeln mit edit hinzu. "
             + "Wird ein Aufruf an der Grenze abgeschnitten, ist er verloren.";
    }

    /**
     * @param grundlage    der eingebaute Prompt
     * @param ws           Arbeitsbereich, in dem gesucht wird
     * @param ersatzdatei  ersetzt die Grundlage ganz; null, wenn keine angegeben
     * @param ohneProjekt  true, wenn AGENT.md ignoriert werden soll
     */
    public static Ergebnis baue(String grundlage, Workspace ws, Path ersatzdatei,
                                boolean ohneProjekt) throws IOException {
        if (ersatzdatei != null) {
            String text = lies(ersatzdatei);
            return new Ergebnis(text, ersatzdatei, text.length(), warnungWennGross(text));
        }
        if (ohneProjekt) return new Ergebnis(grundlage, null, 0, null);

        Path gefunden = null;
        for (String name : NAMEN) {
            Path p = ws.wurzel().resolve(name);
            if (Files.isRegularFile(p)) { gefunden = p; break; }
        }
        if (gefunden == null) return new Ergebnis(grundlage, null, 0, null);

        String projekt = lies(gefunden).strip();
        if (projekt.isEmpty()) return new Ergebnis(grundlage, null, 0, null);

        // Die Ueberschrift sagt dem Modell, woher die Regeln kommen und dass
        // sie Vorrang haben. Ohne das liest es die Datei als blossen Anhang.
        String prompt = grundlage + "\n\n"
                + "Fuer dieses Projekt gelten zusaetzlich die folgenden Regeln aus "
                + gefunden.getFileName() + ". Sie gehen im Zweifel vor:\n\n"
                + projekt;

        return new Ergebnis(prompt, gefunden, projekt.length(), warnungWennGross(projekt));
    }

    private static String lies(Path p) throws IOException {
        try {
            return Files.readString(p);
        } catch (MalformedInputException e) {
            throw new IOException("keine UTF-8-Textdatei: " + p);
        }
    }

    private static String warnungWennGross(String text) {
        if (text.length() <= GROSS) return null;
        return text.length() + " Zeichen — das steht in jeder Anfrage und laesst sich "
             + "nicht kuerzen. Kuerzer fassen oder Einzelheiten in Dateien auslagern, "
             + "die der Agent bei Bedarf liest.";
    }
}
