package de.dg1001.harness.agent;

import de.dg1001.harness.wire.Messages.AssistantMessage;
import de.dg1001.harness.wire.Messages.Message;
import de.dg1001.harness.wire.Messages.SystemMessage;
import de.dg1001.harness.wire.Messages.ToolCall;
import de.dg1001.harness.wire.Messages.ToolMessage;
import de.dg1001.harness.wire.Messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Der Gespraechsverlauf, mit genug Nebenwissen, um sinnvoll kuerzen zu koennen.
 *
 * <p>Eine reine {@code List<Message>} reicht dafuer nicht: eine
 * {@link ToolMessage} traegt nur ihre Kennung und den Text, nicht aber, welches
 * Werkzeug sie erzeugt hat. Fuer eine brauchbare Kurzfassung ("read
 * src/motor.py, 240 Zeilen") braucht man das aber. Deshalb der interne
 * {@link Eintrag}.
 *
 * <p><b>Fest verankert</b> sind der Systemprompt und die erste Nutzernachricht.
 * Sie bilden den Praefix, auf den der Cache des Servers greift; wer sie anfasst,
 * wirft ihn weg. Gemessen: 63,5 % Praefix-Treffer bei einem Harness, der das
 * beachtet.
 */
public final class Transcript {

    /**
     * @param nachricht die eigentliche Nachricht
     * @param werkzeug  bei Werkzeugergebnissen der Werkzeugname, sonst null
     * @param argKurz   knappe Fassung der Argumente, fuer die Kurzfassung
     * @param gekuerzt  schon gekuerzt? verhindert doppeltes Kuerzen
     * @param fest      darf nie gekuerzt werden (Praefix)
     */
    private record Eintrag(Message nachricht, String werkzeug, String argKurz,
                           boolean gekuerzt, boolean fest) {

        Eintrag alsGekuerzt(Message ersatz) {
            return new Eintrag(ersatz, werkzeug, argKurz, true, fest);
        }
    }

    private final List<Eintrag> eintraege = new ArrayList<>();
    private final TokenSchaetzer schaetzer;

    /** Tokens fuer Werkzeugbeschreibungen — einmal berechnet, konstant. */
    private int grundlast = 0;

    public Transcript(TokenSchaetzer schaetzer) {
        this.schaetzer = schaetzer;
    }

    // ------------------------------------------------------------ befuellen

    /** Systemprompt und Aufgabe. Beide werden fest verankert. */
    public void beginne(String systemPrompt, String aufgabe) {
        if (!eintraege.isEmpty())
            throw new IllegalStateException("beginne() nur einmal");
        eintraege.add(new Eintrag(new SystemMessage(systemPrompt), null, null, false, true));
        eintraege.add(new Eintrag(new UserMessage(aufgabe), null, null, false, true));
    }

    public void add(Message m) {
        eintraege.add(new Eintrag(m, null, null, false, false));
    }

    /** Werkzeugergebnis samt Herkunft, damit spaeter sinnvoll gekuerzt werden kann. */
    public void addWerkzeugErgebnis(ToolCall tc, String inhalt) {
        eintraege.add(new Eintrag(new ToolMessage(tc.id(), inhalt),
                tc.name(), kurzArgument(tc.argumentsJson()), false, false));
    }

    /** Wird nach dem Bau der Werkzeugliste einmal gesetzt. */
    public void setzeGrundlast(int tokens) { this.grundlast = tokens; }

    // -------------------------------------------------------------- lesen

    public List<Message> nachrichten() {
        return eintraege.stream().map(Eintrag::nachricht).toList();
    }

    public int anzahl() { return eintraege.size(); }

    /** Geschaetzte Eingabetokens der naechsten Anfrage, Werkzeuge eingerechnet. */
    public int schaetzeTokens() {
        int zeichen = 0;
        for (Eintrag e : eintraege) zeichen += laenge(e.nachricht());
        return grundlast + schaetzer.schaetze(zeichen);
    }

    /** Zeichenzahl dessen, was tatsaechlich rausgeht — fuer die Kalibrierung. */
    public int zeichenGesamt() {
        int z = 0;
        for (Eintrag e : eintraege) z += laenge(e.nachricht());
        return z;
    }

    private static int laenge(Message m) {
        // Grobe Naeherung inklusive Protokoll-Beiwerk (role, Klammern, Kommas).
        int rahmen = 24;
        return rahmen + switch (m) {
            case SystemMessage s -> s.content() == null ? 0 : s.content().length();
            case UserMessage u   -> u.content() == null ? 0 : u.content().length();
            case ToolMessage t   -> (t.content() == null ? 0 : t.content().length())
                                    + (t.toolCallId() == null ? 0 : t.toolCallId().length());
            case AssistantMessage a -> {
                int n = a.content() == null ? 0 : a.content().length();
                for (ToolCall tc : a.toolCalls())
                    n += tc.name().length() + tc.argumentsJson().length() + 40;
                // reasoningContent zaehlt NICHT mit: es wird nicht mitgeschickt.
                yield n;
            }
        };
    }

    // ------------------------------------------------------------- kuerzen

    /**
     * Ersetzt alte Werkzeugergebnisse durch eine Zeile. Rueckgabe: wie viele
     * Eintraege gekuerzt wurden.
     *
     * <p>Bewusst <b>Ersetzen statt Zusammenfassen</b>. Ein Modellaufruf zum
     * Zusammenfassen kostet einen ganzen Zug, und die Zusammenfassung fuellt den
     * Kontext binnen weniger Zuege wieder — gemessen viermal hintereinander bei
     * einem Harness, der so vorgeht, bis er aufgab. Eine Zeile Ersatztext kostet
     * nichts und ist stabil.
     *
     * <p>Die Denkschritte und Werkzeugaufrufe des Modells bleiben unangetastet.
     * Dort steht, <i>warum</i> etwas getan wurde, und das ist teurer neu zu
     * erzeugen als eine Dateiliste.
     */
    public int kuerze(int behalteLetzte) {
        // Indizes der kuerzbaren Werkzeugergebnisse einsammeln
        List<Integer> kandidaten = new ArrayList<>();
        for (int i = 0; i < eintraege.size(); i++) {
            Eintrag e = eintraege.get(i);
            if (!e.fest() && !e.gekuerzt() && e.nachricht() instanceof ToolMessage)
                kandidaten.add(i);
        }
        int bisAusschliesslich = kandidaten.size() - behalteLetzte;
        int gekuerzt = 0;
        for (int k = 0; k < bisAusschliesslich; k++) {
            int i = kandidaten.get(k);
            Eintrag e = eintraege.get(i);
            ToolMessage alt = (ToolMessage) e.nachricht();
            eintraege.set(i, e.alsGekuerzt(
                    new ToolMessage(alt.toolCallId(), kurzfassung(e, alt))));
            gekuerzt++;
        }
        return gekuerzt;
    }

    /** Kurzfassung ohne Modellaufruf. */
    private static String kurzfassung(Eintrag e, ToolMessage m) {
        String inhalt = m.content() == null ? "" : m.content();
        int zeilen = inhalt.isEmpty() ? 0 : (int) inhalt.chars().filter(c -> c == '\n').count() + 1;

        StringBuilder b = new StringBuilder("[gekuerzt: ");
        b.append(e.werkzeug() == null ? "Werkzeug" : e.werkzeug());
        if (e.argKurz() != null && !e.argKurz().isBlank()) b.append(' ').append(e.argKurz());
        b.append(", ").append(zeilen).append(" Zeilen");

        // Rueckgabewert mitnehmen, wenn erkennbar — das ist die eine Information,
        // die man aus einem bash-Ergebnis spaeter noch braucht.
        int p = inhalt.lastIndexOf("[Rueckgabewert ");
        if (p >= 0) {
            int q = inhalt.indexOf(',', p);
            if (q > p) b.append(", ").append(inhalt, p + 1, q);
        }
        return b.append(']').toString();
    }

    /** Aus {"pfad":"src/motor.py"} wird src/motor.py — nur der erste Wert. */
    private static String kurzArgument(String argumentsJson) {
        if (argumentsJson == null) return null;
        try {
            var m = de.dg1001.harness.wire.Json.obj(
                    de.dg1001.harness.wire.Json.parse(argumentsJson));
            for (Object v : m.values()) {
                if (v instanceof String s && !s.isBlank())
                    return s.length() > 60 ? s.substring(0, 60) + "…" : s;
            }
        } catch (RuntimeException ignored) {
            // kaputte Argumente sind anderswo schon gemeldet
        }
        return null;
    }
}
