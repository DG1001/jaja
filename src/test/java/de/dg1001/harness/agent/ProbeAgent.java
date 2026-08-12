package de.dg1001.harness.agent;

import de.dg1001.harness.wire.Messages.AssistantMessage;
import de.dg1001.harness.wire.Messages.Message;
import de.dg1001.harness.wire.Messages.SystemMessage;
import de.dg1001.harness.wire.Messages.ToolCall;
import de.dg1001.harness.wire.Messages.ToolMessage;
import de.dg1001.harness.wire.Messages.UserMessage;

import java.util.List;

/** Prueft ContextBudget, TokenSchaetzer, Transcript und Elision. */
public final class ProbeAgent {

    private static int fehlgeschlagen = 0;

    public static void main(String[] args) {

        // ------------------------------------------------------ ContextBudget
        ContextBudget b = ContextBudget.vorgabe(65_536, 16_384);
        pruefe("Budget: nutzbare Eingabe = Fenster - Ausgabe - Reserve",
               b.nutzbareEingabe() == 65_536 - 16_384 - 2_000);
        pruefe("Budget: Schwelle bei 70 %",
               b.kuerzungsSchwelle() == (int) ((65_536 - 16_384 - 2_000) * 0.70));
        pruefe("Budget: unter der Schwelle wird nicht gekuerzt", !b.mussKuerzen(1_000));
        pruefe("Budget: ueber der Schwelle wird gekuerzt",       b.mussKuerzen(40_000));

        boolean geworfen = false;
        try { new ContextBudget(1_000, 2_000, 0, 0.7); } catch (IllegalArgumentException e) { geworfen = true; }
        pruefe("Budget: maxAusgabe >= Fenster wird abgewiesen", geworfen);

        // ---------------------------------------------------- TokenSchaetzer
        TokenSchaetzer ts = new TokenSchaetzer();
        int vor = ts.schaetze(3_500);
        pruefe("Schaetzer: Startfaktor 3,5", vor == 1_000);
        ts.kalibriere(4_000, 1_000);                 // tatsaechlich 4,0
        pruefe("Schaetzer: bewegt sich zur Messung", ts.faktor() > 3.5 && ts.faktor() < 4.0);
        double f = ts.faktor();
        ts.kalibriere(100, 0);                       // ungueltig
        ts.kalibriere(100_000, 10);                  // Ausreisser (10000:1)
        pruefe("Schaetzer: Ausreisser und Unsinn werden verworfen", ts.faktor() == f);

        // -------------------------------------------------------- Transcript
        Transcript t = neuerVerlauf(20, 400);
        int vorherTokens = t.schaetzeTokens();
        pruefe("Transcript: Schaetzung > 0", vorherTokens > 0);

        int ersetzt = t.kuerze(3);
        pruefe("Transcript: alle bis auf die letzten drei gekuerzt", ersetzt == 17);
        pruefe("Transcript: Schaetzung sinkt", t.schaetzeTokens() < vorherTokens);

        List<Message> n = t.nachrichten();
        pruefe("Transcript: Systemprompt unangetastet",
               n.get(0) instanceof SystemMessage s && s.content().startsWith("SYSTEM"));
        pruefe("Transcript: erste Nutzernachricht unangetastet",
               n.get(1) instanceof UserMessage u && u.content().startsWith("AUFGABE"));

        long gekuerzte = n.stream().filter(m -> m instanceof ToolMessage tm
                && tm.content().startsWith("[gekuerzt:")).count();
        pruefe("Transcript: 17 Eintraege tragen die Kurzfassung", gekuerzte == 17);

        String kurz = n.stream().filter(m -> m instanceof ToolMessage tm
                        && tm.content().startsWith("[gekuerzt:"))
                .map(m -> ((ToolMessage) m).content()).findFirst().orElse("");
        pruefe("Kurzfassung nennt Werkzeug und Argument",
               kurz.contains("bash") && kurz.contains("datei-"));
        pruefe("Kurzfassung nennt den Rueckgabewert",
               kurz.contains("Rueckgabewert"));

        pruefe("Transcript: zweites Kuerzen aendert nichts (idempotent)",
               t.kuerze(3) == 0);

        pruefe("Transcript: Assistentenzuege bleiben erhalten",
               n.stream().anyMatch(m -> m instanceof AssistantMessage));

        // ----------------------------------------------------------- Elision
        ContextBudget klein = new ContextBudget(20_000, 4_000, 500, 0.70);
        Elision e = new Elision(klein);

        Transcript kleinerVerlauf = neuerVerlauf(2, 100);
        pruefe("Elision: kleiner Verlauf braucht nichts",
               e.vielleichtKuerzen(kleinerVerlauf).ergebnis() == Elision.Ergebnis.NICHT_NOETIG);

        Transcript grosserVerlauf = neuerVerlauf(40, 900);
        Elision.Bericht ber = e.vielleichtKuerzen(grosserVerlauf);
        pruefe("Elision: grosser Verlauf wird gekuerzt",
               ber.ergebnis() == Elision.Ergebnis.GEKUERZT && ber.nachher() < ber.vorher());

        // ---- Der gemeldete Ausfall, nachgebaut ------------------------------
        // Lange Sitzung: alle Werkzeugergebnisse laengst gekuerzt, aber dreissig
        // geschriebene Dateien stehen noch als Argumente im Verlauf. Vorher gab
        // die Kuerzung hier auf und meldete "Kontext erschoepft" -- bei 70 %
        // Fuellstand und 14.000 freien Token.
        {
            ContextBudget lang = ContextBudget.vorgabe(65536, 16384);   // nutzbar 47152
            Transcript v = new Transcript(new TokenSchaetzer());
            v.beginne("Systemprompt", "Baue eine Wiki-Anwendung");
            for (int i = 0; i < 30; i++) {
                String inhalt = ("inhalt-von-" + i + " ").repeat(300);   // eine ganze Datei
                v.add(new AssistantMessage(null, null, List.of(new ToolCall(
                        "c" + i, "write",
                        "{\"pfad\":\"seite" + i + ".py\",\"inhalt\":\"" + inhalt + "\"}"))));
                v.addWerkzeugErgebnis(new ToolCall("c" + i, "write", "{}"),
                                      "angelegt: seite" + i + ".py");
            }
            int vorher = v.schaetzeTokens();
            Elision el = new Elision(lang);
            Elision.Bericht r1 = el.vielleichtKuerzen(v);

            pruefe("lange Sitzung: kein falsches Aufgeben",
                   r1.ergebnis() != Elision.Ergebnis.AUSSICHTSLOS);
            pruefe("lange Sitzung: Werkzeugargumente werden gekuerzt",
                   r1.nachher() < vorher / 2);
            pruefe("lange Sitzung: der Pfad bleibt lesbar",
                   v.nachrichten().toString().contains("seite3.py"));
            String verlaufText = v.nachrichten().toString();
            pruefe("lange Sitzung: alte Dateiinhalte sind weg",
                   !verlaufText.contains("inhalt-von-0 "));
            // Die juengsten Aufrufe bleiben mit Absicht vollstaendig: dort
            // arbeitet das Modell gerade.
            pruefe("lange Sitzung: der juengste Aufruf bleibt vollstaendig",
                   verlaufText.contains("inhalt-von-29 "));
            pruefe("lange Sitzung: gekuerzte Aufrufe sind als solche erkennbar",
                   verlaufText.contains("\"gekuerzt\":true"));

            // Zweiter Durchgang darf nicht in eine Endlosschleife laufen
            Elision.Bericht r2 = el.vielleichtKuerzen(v);
            pruefe("lange Sitzung: zweiter Durchgang bleibt ruhig",
                   r2.ergebnis() == Elision.Ergebnis.NICHT_NOETIG);
        }

        // Passt noch, aber nichts mehr zu kuerzen: weitermachen, nicht abbrechen.
        {
            ContextBudget eng = ContextBudget.vorgabe(65536, 16384);
            Transcript w = new Transcript(new TokenSchaetzer());
            // Ueber der 70-%-Schwelle (33006), aber weit unter 47152.
            w.beginne("S".repeat(120_000), "A");
            Elision.Bericht r = new Elision(eng).vielleichtKuerzen(w);
            pruefe("passt noch: kein AUSSICHTSLOS trotz voller Schwelle",
                   r.ergebnis() != Elision.Ergebnis.AUSSICHTSLOS);
            pruefe("passt noch: Hinweis sagt, dass es eng wird",
                   r.hinweis() != null && r.hinweis().contains("passt aber noch"));
        }

        pruefe("Budget: passt() misst an der echten Grenze, nicht an der Schwelle",
               ContextBudget.vorgabe(65536, 16384).passt(33098)
               && ContextBudget.vorgabe(65536, 16384).mussKuerzen(33098));

        // Unkuerzbare Last: riesiger Systemprompt, nichts zu kuerzen
        Transcript unkuerzbar = new Transcript(new TokenSchaetzer());
        unkuerzbar.beginne("S".repeat(60_000), "A");
        Elision.Bericht b2 = new Elision(klein).vielleichtKuerzen(unkuerzbar);
        pruefe("Elision: erkennt aussichtslose Lage statt endlos zu kuerzen",
               b2.ergebnis() == Elision.Ergebnis.AUSSICHTSLOS && b2.hinweis() != null);
        pruefe("Elision: Hinweis nennt die Grundlast als Ursache",
               b2.hinweis().contains("Grundlast"));

        System.out.println(fehlgeschlagen == 0
                ? "\nAlle Pruefungen bestanden."
                : "\n" + fehlgeschlagen + " Pruefung(en) fehlgeschlagen.");
        if (fehlgeschlagen > 0) System.exit(1);
    }

    /** Verlauf mit n Werkzeugrunden, jede Ausgabe zeichen Zeichen lang. */
    private static Transcript neuerVerlauf(int n, int zeichen) {
        Transcript t = new Transcript(new TokenSchaetzer());
        t.beginne("SYSTEM: knapp bleiben.", "AUFGABE: irgendwas bauen.");
        for (int i = 0; i < n; i++) {
            ToolCall tc = new ToolCall("call" + i, "bash",
                    "{\"kommando\":\"cat datei-" + i + ".txt\"}");
            t.add(new AssistantMessage(null, "gedacht", List.of(tc)));
            t.addWerkzeugErgebnis(tc,
                    "x".repeat(zeichen) + "\n[Rueckgabewert 0, 12 ms]");
        }
        return t;
    }

    private static void pruefe(String was, boolean ok) {
        System.out.printf("%-56s %s%n", was, ok ? "ok" : "FEHLGESCHLAGEN");
        if (!ok) fehlgeschlagen++;
    }
}
