package de.dg1001.harness.bench;

import de.dg1001.harness.wire.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Macht aus {@code ergebnisse.jsonl} einen Bericht in Markdown.
 *
 * <p>Die erste Konfiguration der Definition ist die Referenz; jede andere wird
 * gegen sie verglichen, gepaart ueber Runde und Aufgabe. Zu jeder Differenz
 * steht ein p-Wert und die Zahl der Paare -- und eine Zeile, die daran
 * erinnert, was ein p-Wert ueber 0,05 heisst. Diese README hat zweimal Rauschen
 * als Befund veroeffentlicht; der Bericht soll das schwerer machen, nicht
 * leichter.
 */
public final class Bericht {

    private Bericht() {}

    /** Kennzahl: Name, wie sie aus einem Ergebnis gelesen wird, und ob kleiner besser ist. */
    private record Kennzahl(String name, Function<Map<String, Object>, Double> wert, boolean kleinerBesser) {}

    private static final List<Kennzahl> KENNZAHLEN = List.of(
            new Kennzahl("bestanden", e -> Boolean.TRUE.equals(e.get("bestanden")) ? 1.0 : 0.0, false),
            new Kennzahl("punkte", e -> zahl(e.get("punkte")), false),
            new Kennzahl("zuege", e -> zahl(e.get("zuege")), true),
            new Kennzahl("eingabe", e -> zahl(e.get("eingabe")), true),
            new Kennzahl("sekunden", e -> zahl(e.get("sekunden")), true),
            new Kennzahl("werkzeugfehler", e -> summe(e.get("werkzeugfehler")), true));

    public static String erzeuge(Path verz) throws IOException {
        List<Map<String, Object>> alle = lies(verz.resolve("ergebnisse.jsonl"));
        StringBuilder b = new StringBuilder("# Bericht: ").append(verz.getFileName()).append("\n\n");
        if (alle.isEmpty()) return b.append("Keine Ergebnisse.\n").toString();

        List<String> konfigs = reihenfolge(verz, alle);
        Set<String> aufgaben = new LinkedHashSet<>();
        for (var e : alle) aufgaben.add(String.valueOf(e.get("aufgabe")));

        // ---------------------------------------------------- Uebersicht
        b.append("| Konfiguration | n | bestanden | Punkte | Zuege Median (Mittel ± sd) "
               + "| Eingabetoken Median | Sekunden Median | nicht FERTIG |\n");
        b.append("|---|---|---|---|---|---|---|---|\n");
        for (String k : konfigs) {
            List<Map<String, Object>> l = von(alle, k);
            double[] best = werte(l, KENNZAHLEN.get(0)), pkt = werte(l, KENNZAHLEN.get(1)),
                     zg = werte(l, KENNZAHLEN.get(2)), ein = werte(l, KENNZAHLEN.get(3)),
                     sek = werte(l, KENNZAHLEN.get(4));
            long nichtFertig = l.stream().filter(e -> !"FERTIG".equals(e.get("status"))).count();
            b.append("| ").append(k).append(" | ").append(l.size())
             .append(" | ").append((int) Math.round(Statistik.mittel(best) * best.length)).append('/').append(best.length)
             .append(" | ").append(pkt.length == 0 ? "–" : f(sum(pkt), 0))
             .append(" | ").append(f(Statistik.median(zg), 1)).append(" (").append(f(Statistik.mittel(zg), 2))
             .append(" ± ").append(f(Statistik.sd(zg), 2)).append(")")
             .append(" | ").append(f(Statistik.median(ein), 0))
             .append(" | ").append(f(Statistik.median(sek), 0))
             .append(" | ").append(nichtFertig).append(" |\n");
        }

        // ---------------------------------------------------- Vergleich
        if (konfigs.size() > 1) {
            String ref = konfigs.get(0);
            b.append("\n## Gegen ").append(ref).append(" (gepaart ueber Runde und Aufgabe)\n\n");
            b.append("| Konfiguration | Kennzahl | Paare | Differenz der Mittel | p |\n|---|---|---|---|---|\n");
            for (String k : konfigs.subList(1, konfigs.size()))
                for (Kennzahl kz : KENNZAHLEN) {
                    double[] d = paarDifferenzen(alle, ref, k, kz);
                    if (d.length == 0) continue;
                    double m = Statistik.mittel(d);
                    double p = Statistik.pGepaart(d);
                    String richtung = m == 0 ? "" : ((m < 0) == kz.kleinerBesser() ? " (besser)" : " (schlechter)");
                    b.append("| ").append(k).append(" | ").append(kz.name()).append(" | ").append(d.length)
                     .append(" | ").append(m > 0 ? "+" : "").append(f(m, 2)).append(richtung)
                     .append(" | ").append(f(p, 3)).append(p < 0.05 ? " *" : "").append(" |\n");
                }
            b.append("\np ist zweiseitig, aus Vorzeichenwechseln der Paardifferenzen. "
                   + "Ein p ueber 0,05 heisst: von Rauschen nicht zu unterscheiden -- "
                   + "nicht \"kein Effekt\", und erst recht nicht \"der Effekt ist so gross "
                   + "wie die Differenz\". Mehrere Kennzahlen gleichzeitig zu testen macht "
                   + "einzelne * wahrscheinlicher, als sie aussehen.\n");
        }

        // ------------------------------------------------- je Aufgabe
        b.append("\n## Bestanden je Aufgabe\n\n| Aufgabe |");
        for (String k : konfigs) b.append(' ').append(k).append(" |");
        b.append("\n|---|").append("---|".repeat(konfigs.size())).append('\n');
        for (String a : aufgaben) {
            b.append("| ").append(a).append(" |");
            for (String k : konfigs) {
                List<Map<String, Object>> l = von(alle, k).stream()
                        .filter(e -> a.equals(String.valueOf(e.get("aufgabe")))).toList();
                long ok = l.stream().filter(e -> Boolean.TRUE.equals(e.get("bestanden"))).count();
                b.append(' ').append(ok).append('/').append(l.size()).append(" |");
            }
            b.append('\n');
        }

        // ------------------------------------------------- Eingriffe
        b.append("\n## Eingriffe des Harness (Summe ueber alle Laeufe)\n\n");
        Map<String, Map<String, Integer>> eingriffe = new TreeMap<>();
        for (String k : konfigs)
            for (var e : von(alle, k))
                if (e.get("ereignisse") instanceof Map<?, ?> m)
                    for (var x : m.entrySet())
                        eingriffe.computeIfAbsent(String.valueOf(x.getKey()), z -> new LinkedHashMap<>())
                                 .merge(k, (int) Math.round(zahl(x.getValue())), Integer::sum);
        if (eingriffe.isEmpty()) b.append("keine\n");
        else {
            b.append("| Ereignis |");
            for (String k : konfigs) b.append(' ').append(k).append(" |");
            b.append("\n|---|").append("---|".repeat(konfigs.size())).append('\n');
            for (var e : eingriffe.entrySet()) {
                b.append("| ").append(e.getKey()).append(" |");
                for (String k : konfigs) b.append(' ').append(e.getValue().getOrDefault(k, 0)).append(" |");
                b.append('\n');
            }
        }

        String text = b.toString();
        Files.writeString(verz.resolve("bericht.md"), text);
        return text;
    }

    // ------------------------------------------------------------- Zutaten

    /** Reihenfolge aus der Definition, sonst nach erstem Auftreten. */
    private static List<String> reihenfolge(Path verz, List<Map<String, Object>> alle) {
        Set<String> s = new LinkedHashSet<>();
        try {
            Object d = Json.parse(Files.readString(verz.resolve("definition.json")));
            for (Object k : Json.arr(Json.feld(d, "konfigurationen")))
                s.add(Json.str(Json.feld(k, "name")));
        } catch (IOException | RuntimeException ignoriert) {
            // ohne Definition: wie sie kommen
        }
        Set<String> vorhanden = new LinkedHashSet<>();
        for (var e : alle) vorhanden.add(String.valueOf(e.get("konfig")));
        s.retainAll(vorhanden);
        s.addAll(vorhanden);
        return new ArrayList<>(s);
    }

    private static double[] paarDifferenzen(List<Map<String, Object>> alle, String ref, String k, Kennzahl kz) {
        Map<String, Double> r = new LinkedHashMap<>();
        for (var e : von(alle, ref)) {
            Double v = kz.wert().apply(e);
            if (v != null) r.put(e.get("runde") + "/" + e.get("aufgabe"), v);
        }
        List<Double> d = new ArrayList<>();
        for (var e : von(alle, k)) {
            Double v = kz.wert().apply(e), w = r.get(e.get("runde") + "/" + e.get("aufgabe"));
            if (v != null && w != null) d.add(v - w);
        }
        return d.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double[] werte(List<Map<String, Object>> l, Kennzahl kz) {
        return l.stream().map(kz.wert()).filter(v -> v != null).mapToDouble(Double::doubleValue).toArray();
    }

    private static List<Map<String, Object>> von(List<Map<String, Object>> alle, String konfig) {
        return alle.stream().filter(e -> konfig.equals(String.valueOf(e.get("konfig")))).toList();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> lies(Path datei) throws IOException {
        List<Map<String, Object>> l = new ArrayList<>();
        if (!Files.exists(datei)) return l;
        for (String z : Files.readAllLines(datei)) {
            if (z.isBlank()) continue;
            try {
                Object o = Json.parse(z);
                if (o instanceof Map) l.add((Map<String, Object>) o);
            } catch (RuntimeException ignoriert) {
                // halbe Zeile vom Abbruch
            }
        }
        return l;
    }

    private static Double zahl(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static Double summe(Object o) {
        if (!(o instanceof Map<?, ?> m)) return null;
        double s = 0;
        for (Object v : m.values()) if (v instanceof Number n) s += n.doubleValue();
        return s;
    }

    private static double sum(double[] x) {
        double s = 0;
        for (double v : x) s += v;
        return s;
    }

    private static String f(double v, int stellen) {
        if (Double.isNaN(v)) return "–";
        return String.format(Locale.ROOT, "%." + stellen + "f", v);
    }
}
