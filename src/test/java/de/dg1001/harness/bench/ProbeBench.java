package de.dg1001.harness.bench;

import com.sun.net.httpserver.HttpServer;
import de.dg1001.harness.wire.Json;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Prueft Statistik, Plan, Protokoll und den Runner -- Letzteren von Anfang bis
 * Ende: echte jaja-Prozesse gegen einen erfundenen Modellserver im selben
 * Prozess, echte Pruefbefehle, versteckte Tests, Fortsetzen, Bericht.
 */
public final class ProbeBench {

    private static int fehlgeschlagen = 0;

    public static void main(String[] args) throws Exception {
        // --------------------------------------------------- Statistik
        pruefe("gepaart exakt: fuenf gleiche Differenzen, p = 2/32",
               Math.abs(Statistik.pGepaart(new double[]{1, 1, 1, 1, 1}) - 0.0625) < 1e-12);
        pruefe("gepaart exakt: sechs gleiche Differenzen, p = 2/64",
               Math.abs(Statistik.pGepaart(new double[]{2, 2, 2, 2, 2, 2}) - 0.03125) < 1e-12);
        pruefe("gepaart: keine Differenz, p = 1", Statistik.pGepaart(new double[]{0, 0, 0}) == 1.0);
        double[] viele = new double[40];
        java.util.Arrays.fill(viele, 1);
        pruefe("gepaart gezogen: vierzig gleiche Differenzen, p winzig", Statistik.pGepaart(viele) < 0.001);
        pruefe("unabhaengig: weit getrennt, p winzig",
               Statistik.pUnabhaengig(new double[]{1, 2, 3, 4, 5, 6}, new double[]{101, 102, 103, 104, 105, 106}) < 0.01);
        pruefe("unabhaengig: gleich, p = 1",
               Statistik.pUnabhaengig(new double[]{1, 2, 3}, new double[]{1, 2, 3}) == 1.0);
        pruefe("Median gerade und ungerade",
               Statistik.median(new double[]{3, 1, 2}) == 2 && Statistik.median(new double[]{4, 1, 3, 2}) == 2.5);
        pruefe("p-Werte sind wiederholbar",
               Statistik.pGepaart(viele) == Statistik.pGepaart(viele));

        // -------------------------------------------------------- Plan
        {
            var a = new Bench.Aufgabe("t", null, "x", null, "true", null);
            var d = new Bench.Definition(List.of(), List.of(a),
                    List.of(new Bench.Konfig("A", List.of()), new Bench.Konfig("B", List.of())), 2, 1, 1);
            var ids = Bench.plan(d).stream().map(Bench.Lauf::id).toList();
            pruefe("Plan: Reihenfolge der Konfigurationen rotiert je Runde",
                   ids.equals(List.of("r1-t-A", "r1-t-B", "r2-t-B", "r2-t-A")));
        }

        // ------------------------------------------- von Anfang bis Ende
        List<String> anfragen = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/chat/completions", x -> {
            String rumpf = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            anfragen.add(rumpf);
            // Erst schreiben, dann pruefen (sonst kommt der Pruef-Anstoss), dann fertig.
            String antwort;
            if (!rumpf.contains("\"role\":\"tool\""))
                antwort = aufruf("write", "{\\\"pfad\\\":\\\"ergebnis.py\\\",\\\"inhalt\\\":\\\"x = 1\\\\n\\\"}");
            else if (!rumpf.contains("python3 ergebnis.py"))
                antwort = aufruf("bash", "{\\\"kommando\\\":\\\"python3 ergebnis.py\\\"}");
            else
                antwort = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"fertig\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":5}}";
            byte[] b = antwort.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

        Path tmp = Files.createTempDirectory("jaja-bench");
        Files.createDirectories(tmp.resolve("vorlage"));
        Files.writeString(tmp.resolve("vorlage/LIESMICH.md"), "# Aufgabe\n");
        Files.createDirectories(tmp.resolve("geheim"));
        Files.writeString(tmp.resolve("geheim/test_versteckt.sh"), "test -f ergebnis.py && echo '3 passed'\n");
        String def = """
                {"jaja": ["--model", "probe", "--base-url", "%s", "--max-turns", "6"],
                 "aufgaben": [{"name": "t1", "vorlage": "vorlage", "prompt": "Lege ergebnis.py an.",
                               "versteckt": "geheim", "pruefung": "bash test_versteckt.sh",
                               "punkte": "(\\\\d+) passed"}],
                 "konfigurationen": [{"name": "a", "argumente": []},
                                     {"name": "b", "argumente": ["--kein-stream"]}],
                 "runden": 2, "parallel": 2, "zeitgrenze_minuten": 2}
                """.formatted(url);
        Files.writeString(tmp.resolve("bench.json"), def);

        System.setProperty("jaja.klassenpfad", Path.of("target/classes").toAbsolutePath().toString());
        Path aus = tmp.resolve("ergebnis");
        Bench.main(new String[]{tmp.resolve("bench.json").toString(), "--ausgabe", aus.toString()});

        var erg = Bericht.lies(aus.resolve("ergebnisse.jsonl"));
        pruefe("Runner: vier Laeufe, alle mit Ergebnis", erg.size() == 4);
        pruefe("Runner: alle FERTIG und bestanden, Punkte aus dem Pruefbefehl",
               erg.stream().allMatch(e -> "FERTIG".equals(e.get("status"))
                       && Boolean.TRUE.equals(e.get("bestanden"))
                       && e.get("punkte") instanceof Number n && n.intValue() == 3));
        pruefe("Runner: Zuege und Token aus dem Protokoll",
               erg.stream().allMatch(e -> e.get("zuege") instanceof Number z && z.intValue() == 3
                       && e.get("eingabe") instanceof Number t && t.intValue() == 300));
        pruefe("Runner: versteckte Pruefung hat das Modell nie gesehen",
               !anfragen.isEmpty() && anfragen.stream().noneMatch(r -> r.contains("test_versteckt")));
        pruefe("Runner: ... liegt nach dem Lauf aber im Arbeitsverzeichnis",
               Files.exists(aus.resolve("laeufe/r1-t1-a/arbeit/test_versteckt.sh")));

        Path trace = aus.resolve("laeufe/r1-t1-a/trace.jsonl");
        List<String> zeilen = Files.readAllLines(trace);
        pruefe("Protokoll: start, zug, werkzeug, ende",
               zeilen.get(0).contains("\"t\":\"start\"") && zeilen.stream().anyMatch(z -> z.contains("\"t\":\"zug\""))
               && zeilen.stream().anyMatch(z -> z.contains("\"t\":\"werkzeug\"") && z.contains("\"name\":\"write\""))
               && zeilen.get(zeilen.size() - 1).contains("\"t\":\"ende\""));
        Object kopf = Json.feld(Json.parse(zeilen.get(0)), "kopf");
        pruefe("Protokoll: Kopf nennt Modell und Schalter, nie den Schluessel",
               "probe".equals(Json.str(Json.feld(kopf, "model"))) && Json.feld(kopf, "api-key") == null);
        Object ende = Json.parse(zeilen.get(zeilen.size() - 1));
        pruefe("Protokoll: Ende zaehlt Werkzeugaufrufe je Name",
               Json.num(Json.feld(Json.feld(ende, "werkzeugaufrufe"), "write"), 0) == 1
               && Json.num(Json.feld(Json.feld(ende, "werkzeugaufrufe"), "bash"), 0) == 1);

        int vorher = anfragen.size();
        Bench.main(new String[]{tmp.resolve("bench.json").toString(), "--ausgabe", aus.toString()});
        pruefe("Fortsetzen: erledigte Laeufe werden nicht wiederholt",
               Bericht.lies(aus.resolve("ergebnisse.jsonl")).size() == 4 && anfragen.size() == vorher);

        String bericht = Files.readString(aus.resolve("bericht.md"));
        pruefe("Bericht: Uebersicht, Vergleich gegen die Referenz, je Aufgabe",
               bericht.contains("| a | 2 | 2/2 |") && bericht.contains("## Gegen a")
               && bericht.contains("| t1 | 2/2 | 2/2 |"));
        server.stop(0);

        System.out.println(fehlgeschlagen == 0
                ? "\nAlle Pruefungen bestanden."
                : "\n" + fehlgeschlagen + " Pruefung(en) fehlgeschlagen.");
        if (fehlgeschlagen > 0) System.exit(1);
    }

    private static String aufruf(String name, String argsMaskiert) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c\","
             + "\"type\":\"function\",\"function\":{\"name\":\"" + name + "\",\"arguments\":\"" + argsMaskiert
             + "\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":5}}";
    }

    private static void pruefe(String was, boolean ok) {
        System.out.printf("%-62s %s%n", was, ok ? "ok" : "FEHLGESCHLAGEN");
        if (!ok) fehlgeschlagen++;
    }
}
