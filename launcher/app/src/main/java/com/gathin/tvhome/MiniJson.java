package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and string quoter with no Android classes (org.json is not available to the unit tests).
 * Objects come back as Map, arrays as List, numbers as Double, plus String, Boolean and null.
 */
final class MiniJson {
    private MiniJson() {}

    private static final int MAX_DEPTH = 32;

    /** The parsed value, or null for invalid input (a JSON null is also null). */
    static Object parse(String text) {
        if (text == null) return null;
        try {
            Reader r = new Reader(text);
            r.skip();
            Object v = r.value(0);
            r.skip();
            return r.pos == text.length() ? v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    static Object get(Object o, String key) {
        Map<String, Object> m = obj(o);
        return m == null ? null : m.get(key);
    }

    static String str(Object o, String key) {
        Object v = get(o, key);
        return v instanceof String ? (String) v : null;
    }

    /** A number as an int, or fallback when missing. */
    static int integer(Object o, String key, int fallback) {
        Object v = get(o, key);
        return v instanceof Double ? (int) Math.round((Double) v) : fallback;
    }

    static double number(Object o, String key, double fallback) {
        Object v = get(o, key);
        return v instanceof Double ? (Double) v : fallback;
    }

    /** s as a JSON string literal, quotes included. */
    static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static final class Reader {
        final String s;
        int pos;

        Reader(String s) {
            this.s = s;
        }

        void skip() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH || pos >= s.length()) throw new IllegalStateException();
            char c = s.charAt(pos);
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            if (start == pos) throw new IllegalStateException();
            return Double.valueOf(s.substring(start, pos));
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skip();
            if (s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skip();
                String k = string();
                skip();
                expect(':');
                skip();
                m.put(k, value(depth + 1));
                skip();
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalStateException();
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            pos++;
            skip();
            if (s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                skip();
                l.add(value(depth + 1));
                skip();
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalStateException();
            }
        }

        void expect(char c) {
            if (s.charAt(pos++) != c) throw new IllegalStateException();
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(pos++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default: b.append(e);
                }
            }
        }
    }
}
