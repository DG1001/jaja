package de.dg1001.harness.wire;

/** Prueft, was als voruebergehend gilt und was nicht. */
public final class ProbeRetry {
    private static int f = 0;
    public static void main(String[] a) {
        // voruebergehend
        ja("HTTP 503: Server is temporarily at capacity ...; retry shortly");
        ja("HTTP 429: rate limited");
        ja("HTTP 500: internal error");
        ja("Verbindung zu http://127.0.0.1:8888/v1/chat/completions fehlgeschlagen");
        ja("Antwort nicht lesbar: {abgeschnitte");
        // dauerhaft
        nein("HTTP 400: maximum context length is 65536 tokens");
        nein("HTTP 401: unauthorized");
        nein("HTTP 404: model not found");
        nein("HTTP 422: unprocessable");
        System.out.println(f == 0 ? "\nAlle Pruefungen bestanden."
                                  : "\n" + f + " fehlgeschlagen.");
        if (f > 0) System.exit(1);
    }
    private static void ja(String m)   { p(m, true); }
    private static void nein(String m) { p(m, false); }
    private static void p(String m, boolean erwartet) {
        boolean ist = Retry.lohntWiederholung(new ChatClient.ChatFehler(m, null));
        boolean ok = ist == erwartet;
        System.out.printf("%-58s %s%n",
                (m.length() > 56 ? m.substring(0, 56) + "…" : m),
                ok ? (erwartet ? "wiederholen  ok" : "aufgeben     ok") : "FEHLGESCHLAGEN");
        if (!ok) f++;
    }
}
