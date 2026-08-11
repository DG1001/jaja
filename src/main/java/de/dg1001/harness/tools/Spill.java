package de.dg1001.harness.tools;

import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Begrenzt Werkzeugausgaben, damit ein {@code cat} auf eine grosse Datei nicht
 * den Kontext kippt.
 *
 * <p>Das Modell bekommt Anfang, Ende und den Pfad zur vollstaendigen Ausgabe und
 * kann gezielt nachlesen, wenn es die Mitte braucht. Beide gemessenen Harnesses
 * machen es so.
 */
public final class Spill {

    /** Zeichen, nicht Tokens — grob 2000 Tokens bei 8000 Zeichen. */
    public static final int GRENZE = 8_000;
    private static final int KOPF = 3_000;
    private static final int FUSS = 1_000;

    private static final AtomicLong ZAEHLER = new AtomicLong();

    private Spill() {}

    public static Tool.ToolResult vielleichtAuslagern(String voll, Workspace ws,
                                                      String kennung, boolean istFehler)
            throws IOException {
        if (voll == null) voll = "";
        if (voll.length() <= GRENZE)
            return new Tool.ToolResult(voll, istFehler, null);

        Path p = ws.auslagerungsverzeichnis()
                   .resolve(kennung + "-" + ZAEHLER.incrementAndGet() + ".txt");
        Files.writeString(p, voll);

        int ausgelassen = voll.length() - KOPF - FUSS;
        String gekuerzt = voll.substring(0, KOPF)
                + "\n\n[… " + ausgelassen + " Zeichen ausgelassen. Vollstaendig in "
                + ws.relativ(p) + " — dort gezielt nachlesen, statt erneut auszugeben. …]\n\n"
                + voll.substring(voll.length() - FUSS);

        return new Tool.ToolResult(gekuerzt, istFehler, p);
    }
}
