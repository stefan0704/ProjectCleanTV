package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The plugin:// URL that opens a movie or an episode in the Palantir 3 Kodi addon. The payload is a Python-style dict
 * (keys sorted), base64 without padding, rotated and reversed. Pure Java, so it can be unit tested.
 */
final class PalantirLink {
    private PalantirLink() {}

    static final String BASE = "plugin://plugin.video.palantir3/?";

    static String forItem(TraktClient.Item it) {
        Map<String, Object> m = new TreeMap<>();
        m.put("action", "selectvideos_");
        m.put("isPlayable", Boolean.TRUE);
        if (it.tmdbId > 0) m.put("tmdb", String.valueOf(it.tmdbId));
        boolean show = "show".equals(it.type);   // a watchlist series opens at S1E1
        if ("episode".equals(it.type) || show) {
            int season = show ? Math.max(1, it.season) : it.season, episode = show ? Math.max(1, it.episode) : it.episode;
            m.put("content", "episodes");
            m.put("tvshowtitle", it.title);
            m.put("season", season);
            m.put("episode", episode);
            String label = it.episodeTitle;
            m.put("label", label == null || label.isEmpty() ? season + "x" + episode : label);
        } else {
            m.put("content", "movies");
            m.put("title", it.title);
            m.put("label", it.title);
            if (it.year > 0) m.put("fecha", it.year + "-01-01");
        }
        return BASE + encode(text(m));
    }

    /** Python's repr of a dict as the addon writes it: double-quoted keys, repr'd values, sorted keys. */
    static String text(Map<String, Object> values) {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : new TreeMap<>(values).entrySet()) {
            if (!first) b.append(", ");
            first = false;
            b.append('"').append(e.getKey()).append("\": ").append(repr(e.getValue()));
        }
        return b.append('}').toString();
    }

    private static String repr(Object v) {
        if (v instanceof Boolean) return ((Boolean) v) ? "True" : "False";
        if (v instanceof Number) return String.valueOf(v);
        if (v instanceof List) {
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) b.append(", ");
                first = false;
                b.append(repr(o));
            }
            return b.append(']').toString();
        }
        return reprString(String.valueOf(v));
    }

    /** Python str repr: single quotes, or double quotes when the text has ' and no ". */
    static String reprString(String s) {
        char q = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder b = new StringBuilder().append(q);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == q || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else b.append(c);
        }
        return b.append(q).toString();
    }

    static String encode(String text) {
        String b = Base64.getEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        int n = b.length();
        int i = (int) Math.floor(0.75 * n);
        String r = b.substring(n - i) + b.substring(0, n - i);
        return new StringBuilder(r).reverse().toString();
    }

    /** The text behind a payload, or null when it is not one. */
    static String decode(String payload) {
        try {
            String r = new StringBuilder(payload).reverse().toString();
            int n = r.length();
            int i = (int) Math.floor(0.75 * n);
            String s = r.substring(i) + r.substring(0, i);
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
