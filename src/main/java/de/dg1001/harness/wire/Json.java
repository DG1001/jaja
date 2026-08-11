package de.dg1001.harness.wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimaler JSON-Leser und -Schreiber, ohne Fremdbibliothek.
 *
 * <p>Der Umfang, den wir brauchen, ist klein: Objekte, Felder, Zeichenketten,
 * Zahlen, Wahrheitswerte, null. Wer lieber Jackson nimmt, ersetzt genau zwei
 * Stellen — {@link #parse} und {@link Writer} — und laesst den Rest stehen.
 *
 * <p>Gelesene Werte kommen als {@code Map<String,Object>}, {@code List<Object>},
 * {@code String}, {@code Double}, {@code Boolean} oder {@code null} zurueck.
 * Zum bequemen Zugriff gibt es {@link #obj}, {@link #str}, {@link #num} und
 * {@link #arr}, die statt einer ClassCastException schlicht null bzw. einen
 * Ersatzwert liefern — bei Modellantworten fehlen Felder haeufig.
 */
public final class Json {

    private Json() {}

    // ------------------------------------------------------------------ lesen

    public static Object parse(String s) {
        Leser l = new Leser(s);
        l.leerraum();
        Object v = l.wert();
        l.leerraum();
        if (l.i < l.n) throw new JsonFehler("Zeichen nach dem Ende bei " + l.i);
        return v;
    }

    public static class JsonFehler extends RuntimeException {
        public JsonFehler(String m) { super(m); }
    }

    private static final class Leser {
        private final String s;
        private final int n;
        private int i;

        Leser(String s) { this.s = s; this.n = s.length(); }

        void leerraum() {
            while (i < n) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        Object wert() {
            if (i >= n) throw new JsonFehler("unerwartetes Ende");
            char c = s.charAt(i);
            switch (c) {
                case '{': return objekt();
                case '[': return liste();
                case '"': return zeichenkette();
                case 't': erwarte("true");  return Boolean.TRUE;
                case 'f': erwarte("false"); return Boolean.FALSE;
                case 'n': erwarte("null");  return null;
                default:  return zahl();
            }
        }

        private void erwarte(String w) {
            if (!s.startsWith(w, i)) throw new JsonFehler("erwartet '" + w + "' bei " + i);
            i += w.length();
        }

        Map<String, Object> objekt() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;                       // '{'
            leerraum();
            if (i < n && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                leerraum();
                if (i >= n || s.charAt(i) != '"') throw new JsonFehler("Feldname erwartet bei " + i);
                String k = zeichenkette();
                leerraum();
                if (i >= n || s.charAt(i) != ':') throw new JsonFehler("':' erwartet bei " + i);
                i++;
                leerraum();
                m.put(k, wert());
                leerraum();
                if (i >= n) throw new JsonFehler("unerwartetes Ende im Objekt");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new JsonFehler("',' oder '}' erwartet bei " + (i - 1));
            }
        }

        List<Object> liste() {
            List<Object> a = new ArrayList<>();
            i++;                       // '['
            leerraum();
            if (i < n && s.charAt(i) == ']') { i++; return a; }
            while (true) {
                leerraum();
                a.add(wert());
                leerraum();
                if (i >= n) throw new JsonFehler("unerwartetes Ende in der Liste");
                char c = s.charAt(i++);
                if (c == ']') return a;
                if (c != ',') throw new JsonFehler("',' oder ']' erwartet bei " + (i - 1));
            }
        }

        String zeichenkette() {
            i++;                       // oeffnendes '"'
            StringBuilder b = new StringBuilder();
            while (i < n) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                if (i >= n) break;
                char e = s.charAt(i++);
                switch (e) {
                    case '"':  b.append('"');  break;
                    case '\\': b.append('\\'); break;
                    case '/':  b.append('/');  break;
                    case 'b':  b.append('\b'); break;
                    case 'f':  b.append('\f'); break;
                    case 'n':  b.append('\n'); break;
                    case 'r':  b.append('\r'); break;
                    case 't':  b.append('\t'); break;
                    case 'u':
                        if (i + 4 > n) throw new JsonFehler("abgeschnittenes \\u bei " + i);
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new JsonFehler("unbekannte Maskierung \\" + e + " bei " + (i - 1));
                }
            }
            throw new JsonFehler("nicht geschlossene Zeichenkette");
        }

        Double zahl() {
            int start = i;
            if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < n) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                        || c == '-' || c == '+') i++;
                else break;
            }
            if (start == i) throw new JsonFehler("Zahl erwartet bei " + start);
            return Double.valueOf(s.substring(start, i));
        }
    }

    // ------------------------------------------------------- bequemer Zugriff

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return (o instanceof List) ? (List<Object>) o : List.of();
    }

    /** Zeichenkette oder null. Bewusst null und nicht "", damit ein fehlendes
     *  Feld von einem leeren unterscheidbar bleibt (content ist bei reinen
     *  Werkzeugaufrufen null). */
    public static String str(Object o) {
        return (o instanceof String) ? (String) o : null;
    }

    public static int num(Object o, int ersatz) {
        return (o instanceof Number) ? ((Number) o).intValue() : ersatz;
    }

    /** Feld aus einem Objekt, ohne Umweg ueber obj(). */
    public static Object feld(Object o, String name) {
        return obj(o).get(name);
    }

    // ----------------------------------------------------------- schreiben

    /** Baut JSON-Text. Bewusst kein Objektmodell: die Anfragen sind flach genug,
     *  dass direktes Schreiben uebersichtlicher ist als ein Baum. */
    public static final class Writer {
        private final StringBuilder b = new StringBuilder();
        private boolean ersterImRahmen = true;

        public Writer objektAuf()  { trenner(); b.append('{'); ersterImRahmen = true; return this; }
        public Writer objektZu()   { b.append('}'); ersterImRahmen = false; return this; }
        public Writer listeAuf()   { trenner(); b.append('['); ersterImRahmen = true; return this; }
        public Writer listeZu()    { b.append(']'); ersterImRahmen = false; return this; }

        public Writer feld(String name) {
            trenner();
            maskiere(name);
            b.append(':');
            ersterImRahmen = true;      // der Wert braucht kein Komma davor
            return this;
        }

        public Writer text(String v) {
            if (v == null) return roh("null");
            trenner();
            maskiere(v);
            ersterImRahmen = false;
            return this;
        }

        public Writer zahl(long v)      { return roh(Long.toString(v)); }
        public Writer wahr(boolean v)   { return roh(v ? "true" : "false"); }

        /** Fuegt bereits fertiges JSON ein — fuer Werkzeugschemata, die als
         *  Zeichenkette vorliegen. */
        public Writer roh(String json) {
            trenner();
            b.append(json == null ? "null" : json);
            ersterImRahmen = false;
            return this;
        }

        /** Kurzform fuer Feld + Textwert; laesst null-Werte ganz weg. */
        public Writer textFeld(String name, String v) {
            if (v == null) return this;
            return feld(name).text(v);
        }

        private void trenner() {
            if (!ersterImRahmen) b.append(',');
            ersterImRahmen = false;
        }

        private void maskiere(String v) {
            b.append('"');
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                switch (c) {
                    case '"':  b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n");  break;
                    case '\r': b.append("\\r");  break;
                    case '\t': b.append("\\t");  break;
                    case '\b': b.append("\\b");  break;
                    case '\f': b.append("\\f");  break;
                    default:
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                }
            }
            b.append('"');
        }

        @Override public String toString() { return b.toString(); }
    }
}
