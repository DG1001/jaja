package de.dg1001.harness.tools;

import de.dg1001.harness.wire.Json;
import de.dg1001.harness.ws.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Prueft eine gerade geschriebene Datei auf Syntaxfehler und haengt den Befund
 * an das Werkzeugergebnis.
 *
 * <p>Ein Syntaxfehler, den das Modell selbst eingebaut hat, faellt sonst erst
 * beim naechsten Testlauf auf -- falls es einen gibt -- und dann mit einem
 * Stapelabzug, der auf den Import zeigt statt auf die Zeile. Hier steht er im
 * Ergebnis des Aufrufs, der ihn verursacht hat: eine Beobachtung, kein Glaube.
 *
 * <p>Bewusst nur Syntax, kein Linter, kein Typpruefer: die Meldung muss in
 * jedem Fall stimmen. Eine Warnung ueber Stil, die das Modell zu einer
 * Aenderung verleitet, kostet mehr als sie bringt.
 *
 * <p>Fehlt der Pruefer (kein {@code node}, kein {@code python3}), passiert
 * nichts. Der Befund ist ein Zusatz; er darf nie der Grund sein, dass ein
 * {@code write} scheitert.
 */
final class Syntaxpruefung {

    private Syntaxpruefung() {}

    private static final long FRIST_MS = 5_000;

    /**
     * Ein kleines Python-Skript statt {@code py_compile}: das legt
     * {@code __pycache__} an, und das soll im Projekt nicht auftauchen. Die
     * Version steht in der Meldung, weil ein zu altes {@code python3} neuere
     * Syntax ({@code match}, {@code type X = ...}) fuer falsch haelt -- ohne
     * diesen Hinweis wuerde das Modell korrekten Code "reparieren".
     */
    private static final String PYTHON = """
            import ast, sys
            p = sys.argv[1]
            try:
                ast.parse(open(p, encoding='utf-8').read(), p)
            except SyntaxError as e:
                print(f"Zeile {e.lineno}: {e.msg} (geprueft mit Python {sys.version_info[0]}.{sys.version_info[1]})")
                sys.exit(1)
            """;

    /** Frist fuer den Pruefbefehl des Projekts -- ein Typpruefer braucht laenger als ast. */
    static long PROJEKT_FRIST_MS = 60_000;

    /**
     * Syntax, und wenn die stimmt, der Pruefbefehl des Projekts.
     *
     * @return Befund fuer das Modell, oder {@code null}, wenn nichts auffiel
     */
    static String befund(Path datei, Workspace ws) {
        String syntax = syntax(datei, ws);
        // Bei einem Syntaxfehler meldet der Linter dasselbe noch einmal, nur
        // laenger. Erst die Syntax in Ordnung bringen.
        return syntax != null ? syntax : projekt(datei, ws);
    }

    /**
     * Der Befehl aus {@code .jaja.json} unter {@code pruefen}, dessen Muster
     * auf die Datei passt; {@code {datei}} wird durch ihren Pfad ersetzt.
     *
     * <p>Das ist, was opencode mit LSP-Diagnosen und Claude Code mit Hooks
     * macht, in der Form, die ohne Abhaengigkeit auskommt: der Linter oder
     * Typpruefer, den das Projekt ohnehin hat. {@code from typing import dict}
     * ist syntaktisch einwandfrei -- ein {@code ruff check} oder
     * {@code python -c 'import modul'} findet es sofort, nicht erst beim
     * Testlauf, falls es einen gibt.
     *
     * <p>Laeuft ueber dieselbe Shell wie bash, also auch im Sandkasten.
     */
    static String projekt(Path datei, Workspace ws) {
        try {
            String rel = ws.relativ(datei);
            for (var e : ws.projekt().pruefen().entrySet()) {
                if (!GlobTool.passt(GlobTool.muster(e.getKey()), Path.of(rel))) continue;
                String befehl = e.getValue().replace("{datei}", "'" + rel.replace("'", "'\\''") + "'");
                String aus = aufrufMitFrist(ws, PROJEKT_FRIST_MS, BashTool.shell(befehl, ws));
                if (aus == null || aus.isBlank()) return null;
                return "Pruefung (" + e.getValue() + ") meldet:\n" + kuerze(aus.strip(), ws, 20);
            }
        } catch (RuntimeException ignoriert) {
            // Ein kaputtes Muster soll das write nicht scheitern lassen.
        }
        return null;
    }

    private static String syntax(Path datei, Workspace ws) {
        try {
            String name = datei.getFileName().toString().toLowerCase();
            String fehler;
            if (name.endsWith(".py"))                                fehler = python(datei, ws);
            else if (name.endsWith(".json"))                         fehler = json(datei);
            else if (name.endsWith(".js") || name.endsWith(".mjs")
                  || name.endsWith(".cjs"))                          fehler = aufruf(ws, "node", "--check", datei.toString());
            else if (name.endsWith(".sh") || name.endsWith(".bash")) fehler = aufruf(ws, "bash", "-n", datei.toString());
            else return null;
            if (fehler == null || fehler.isBlank()) return null;
            return "Syntaxfehler in " + ws.relativ(datei) + ":\n" + kuerze(fehler.strip(), ws, 8);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String python(Path datei, Workspace ws) {
        // Das Projekt-venv, falls vorhanden: dort laeuft der Code auch.
        Path venv = ws.wurzel().resolve(".venv/bin/python");
        String py = Files.isExecutable(venv) ? venv.toString() : "python3";
        return aufruf(ws, py, "-c", PYTHON, datei.toString());
    }

    private static String json(Path datei) {
        try {
            Json.parse(Files.readString(datei));
            return null;
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    /**
     * Fuehrt den Pruefer aus. Ausgabe nur bei Rueckgabewert ungleich 0 -- und
     * nur, wenn der Pruefer ueberhaupt lief: "command not found" ist kein
     * Syntaxfehler der Datei.
     */
    private static String aufruf(Workspace ws, String... befehl) {
        return aufrufMitFrist(ws, FRIST_MS, List.of(befehl));
    }

    private static String aufrufMitFrist(Workspace ws, long fristMs, List<String> befehl) {
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(befehl);
            pb.directory(ws.wurzel().toFile());
            pb.redirectErrorStream(true);
            p = pb.start();
        } catch (IOException e) {
            return null;                                   // Pruefer fehlt
        }
        try {
            p.getOutputStream().close();
            CompletableFuture<String> aus = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try (var in = p.getInputStream()) {
                    aus.complete(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    aus.complete("");
                }
            });
            if (!p.waitFor(fristMs, TimeUnit.MILLISECONDS)) return null;
            if (p.exitValue() == 0) return null;
            return aus.get(500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        } finally {
            if (p.isAlive()) p.destroyForcibly();
        }
    }

    /**
     * Hoechstens {@code max} Zeilen, absolute Pfade der Wurzel herausgenommen. Leere
     * Zeilen und Stapelrahmen des Pruefers ({@code at wrapSafe (node:...)})
     * sagen nichts ueber die Datei und fallen weg.
     */
    private static String kuerze(String s, Workspace ws, int max) {
        s = s.replace(ws.wurzel() + "/", "");
        List<String> alle = s.lines()
                .filter(l -> !l.isBlank())
                .filter(l -> !l.strip().startsWith("at ") && !l.startsWith("Node.js v"))
                .toList();
        String text = String.join("\n", alle.subList(0, Math.min(max, alle.size())));
        return alle.size() > max ? text + "\n[… " + (alle.size() - max) + " weitere Zeilen]" : text;
    }
}
