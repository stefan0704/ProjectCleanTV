package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Wide art for Continue watching cards from TMDB: the episode's still, or the movie's backdrop. Paths are cached
 * per episode or title, so each costs one request ever. The API key is set from the PC with TraktReceiver's TMDB_CONFIG.
 */
final class Tmdb {
    private Tmdb() {}

    private static final String PREFS = "tmdb";
    private static final String IMAGE_BASE = "https://image.tmdb.org/t/p/w780";
    private static final String PORTRAIT_BASE = "https://image.tmdb.org/t/p/w342";

    static void setKey(Context c, String key) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("key", key).apply();
    }

    static boolean hasKey(Context c) {
        return !c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("key", "").isEmpty();
    }

    /**
     * Sets each card's art from TMDB, replacing Trakt's (often a portrait poster, which a 16:9 card crops badly):
     * an episode gets its own still, falling back to the series backdrop; a movie gets its backdrop. Network: call
     * off the main thread.
     */
    static void fillArt(Context c, List<TraktClient.Item> items) {
        fillArt(c, items, true);
    }

    /** Same, but with network false only what is already cached is used (fine for the main thread). */
    static void fillArt(Context c, List<TraktClient.Item> items, boolean network) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = p.getString("key", "");
        if (key.isEmpty()) return;
        SharedPreferences.Editor cache = p.edit();
        for (TraktClient.Item it : items) {
            if (it.tmdbId <= 0) continue;
            boolean movie = "movie".equals(it.type);
            String[] art = null;
            if ("episode".equals(it.type) && it.season > 0 && it.episode > 0) {
                art = cached(p, cache, "ep:" + it.tmdbId + ":" + it.season + ":" + it.episode,
                        "tv/" + it.tmdbId + "/season/" + it.season + "/episode/" + it.episode, key, "still_path", null, "name", network);
                if (art != null && isRealName(art[1])) it.episodeTitle = art[1];
                if (art != null) it.runtime = parseInt(art[3]);
            }
            if (art == null || art[0].isEmpty()) {
                String kind = movie ? "movie" : "tv";
                String[] title = cached(p, cache, kind + "2:" + it.tmdbId, kind + "/" + it.tmdbId, key,
                        "backdrop_path", "poster_path", movie ? "title" : "name", network);
                if (title != null && !title[0].isEmpty()) art = title;
                if ((movie || "show".equals(it.type)) && title != null && !title[1].isEmpty()) it.title = title[1];
                if (title != null && !title[2].isEmpty()) it.portraitUrl = PORTRAIT_BASE + title[2];
                if (movie && title != null) it.runtime = parseInt(title[3]);
            }
            if (movie) {
                String titled = titledBackdrop(p, cache, it.tmdbId, key, network);
                if (titled != null && !titled.isEmpty()) it.titledArtUrl = IMAGE_BASE + titled;
            }
            if (art != null && !art[0].isEmpty()) it.posterUrl = IMAGE_BASE + art[0];
        }
        cache.apply();
    }

    // ---- release status (Explorar and Listas hide what is not out yet) ---------------------------------

    private static final long STATUS_RECHECK_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long STATUS_PAUSE_MS = 100;

    /** Today as yyyy-MM-dd (UTC), the format of TMDB's dates. */
    static String today() {
        Calendar c = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /**
     * True when the title is known not to be out. A movie: it has a status and it is not "Released". A series: "Planned" or
     * "In Production", a first air date in the future, or no date and no status of an aired series. Unknown data (the
     * status was never fetched) is the caller's business: it must not call this.
     */
    static boolean isUnreleased(boolean movie, String status, String date, String today) {
        if (status == null) status = "";
        if (date == null) date = "";
        if (movie) return !status.isEmpty() && !"Released".equals(status);
        if ("Planned".equals(status) || "In Production".equals(status)) return true;
        if (!date.isEmpty()) return date.compareTo(today) > 0;
        return !("Returning Series".equals(status) || "Ended".equals(status) || "Canceled".equals(status));
    }

    /** Released titles are kept for ever; the others are asked again when the data is over 7 days old. */
    static boolean needsStatus(boolean movie, long at, String status, String date, long now, String today) {
        if (at <= 0) return true;
        return isUnreleased(movie, status, date, today) && (now - at > STATUS_RECHECK_MS || now < at);
    }

    private static void putStatus(SharedPreferences.Editor e, String apiPath, Object o) {
        String status = MiniJson.str(o, "status"), date = MiniJson.str(o, apiPath.startsWith("movie") ? "release_date" : "first_air_date");
        e.putString("st:" + apiPath, status == null ? "" : status).putString("st:" + apiPath + ":date", date == null ? "" : date)
                .putLong("st:" + apiPath + ":at", System.currentTimeMillis());
    }

    /** Hides what TMDB says is not out yet; titles whose status was never fetched are not hidden. Reads the prefs once. */
    static Predicate<TraktClient.Item> unreleased(Context c) {
        final SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String today = today();
        return it -> {
            boolean movie = "movie".equals(it.type);
            String path = (movie ? "movie/" : "tv/") + it.tmdbId;
            if (p.getLong("st:" + path + ":at", 0) <= 0) return false;
            return isUnreleased(movie, p.getString("st:" + path, ""), p.getString("st:" + path + ":date", ""), today);
        };
    }

    /**
     * Fetches the status of every movie and series of these items that has none saved (or whose unreleased status is over a week
     * old), 100 ms apart. Network: call off the main thread. Returns how many requests were made.
     */
    static int fillStatus(Context c, List<TraktClient.Item> items) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = p.getString("key", "");
        if (key.isEmpty()) return 0;
        String today = today();
        long now = System.currentTimeMillis();
        Set<String> seen = new HashSet<>();
        SharedPreferences.Editor e = p.edit();
        int fetched = 0;
        for (TraktClient.Item it : items) {
            if (it.tmdbId <= 0) continue;
            boolean movie = "movie".equals(it.type);
            String path = (movie ? "movie/" : "tv/") + it.tmdbId;
            if (!seen.add(path)) continue;
            if (!needsStatus(movie, p.getLong("st:" + path + ":at", 0), p.getString("st:" + path, ""),
                    p.getString("st:" + path + ":date", ""), now, today)) continue;
            String body = get("https://api.themoviedb.org/3/" + path + "?api_key=" + key);
            if (body != null) {
                putStatus(e, path, MiniJson.parse(body));
                if (++fetched % 20 == 0) e.apply();
            }
            try {
                Thread.sleep(STATUS_PAUSE_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        e.apply();
        return fetched;
    }

    // ---- the series screen ---------------------------------------------------------------------------

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** A series as SeriesActivity needs it: its backdrop (w780 URL, or null) and its season numbers (not 0). */
    static final class Series {
        String backdrop;
        final List<Integer> seasons = new ArrayList<>();
    }

    /** One episode of a season: its number, Spanish name ("" when TMDB has none) and still (w780 URL, or null). */
    static final class Episode {
        int number;
        String name = "";
        String still;
    }

    /** The body of GET /tv/{id}, or its compact copy in the cache (same keys). */
    static Series parseSeries(String body) {
        Series s = new Series();
        Object o = MiniJson.parse(body);
        String b = MiniJson.str(o, "backdrop_path");
        if (b != null && !b.isEmpty()) s.backdrop = IMAGE_BASE + b;
        List<Object> list = MiniJson.arr(MiniJson.get(o, "seasons"));
        if (list != null) {
            for (Object x : list) {
                int n = MiniJson.integer(x, "season_number", 0);
                if (n > 0 && !s.seasons.contains(n)) s.seasons.add(n);
            }
        }
        return s;
    }

    /** The body of GET /tv/{id}/season/{n}, or its compact copy in the cache (same keys): the episodes in order. */
    static List<Episode> parseSeason(String body) {
        List<Episode> out = new ArrayList<>();
        List<Object> list = MiniJson.arr(MiniJson.get(MiniJson.parse(body), "episodes"));
        if (list == null) return out;
        for (Object x : list) {
            int n = MiniJson.integer(x, "episode_number", 0);
            if (n <= 0) continue;
            Episode e = new Episode();
            e.number = n;
            String name = MiniJson.str(x, "name");
            if (isRealName(name)) e.name = name;
            String still = MiniJson.str(x, "still_path");
            if (still != null && !still.isEmpty()) e.still = IMAGE_BASE + still;
            out.add(e);
        }
        return out;
    }

    /** The text to show for an episode: "E3 · name", or "Episodio 3" when TMDB has no real name. */
    static String episodeLabel(Episode e) {
        return "E" + e.number + " · " + (isRealName(e.name) ? e.name : "Episodio " + e.number);
    }

    /** The series' backdrop and seasons (es-ES), from the cache when under a day old; null when there is nothing. Blocking. */
    static Series series(Context c, int tmdbId) {
        String body = cachedBody(c, "series:" + tmdbId, "tv/" + tmdbId, false);
        return body == null ? null : parseSeries(body);
    }

    /** The episodes of one season (es-ES), from the cache when under a day old; null when there is nothing. Blocking. */
    static List<Episode> season(Context c, int tmdbId, int season) {
        String body = cachedBody(c, "season:" + tmdbId + ":" + season, "tv/" + tmdbId + "/season/" + season, true);
        return body == null ? null : parseSeason(body);
    }

    /**
     * A TMDB response kept in the prefs with its date: served while under a day old, else fetched again; the old copy
     * when the request fails. Only the keys SeriesActivity reads are kept, so it stays small.
     */
    private static String cachedBody(Context c, String key, String apiPath, boolean season) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String api = p.getString("key", "");
        String saved = p.getString(key, null);
        long age = System.currentTimeMillis() - p.getLong(key + ":at", 0);
        if (saved != null && age >= 0 && age < DAY_MS) return saved;
        if (api.isEmpty()) return saved;
        String body = get("https://api.themoviedb.org/3/" + apiPath + "?language=es-ES&api_key=" + api);
        if (body == null) return saved;
        String compact = season ? compactSeason(body) : compactSeries(body);
        p.edit().putString(key, compact).putLong(key + ":at", System.currentTimeMillis()).apply();
        return compact;
    }

    private static String compactSeries(String body) {
        Object o = MiniJson.parse(body);
        String backdrop = MiniJson.str(o, "backdrop_path");
        StringBuilder b = new StringBuilder("{\"backdrop_path\":").append(MiniJson.quote(backdrop == null ? "" : backdrop))
                .append(",\"seasons\":[");
        List<Object> list = MiniJson.arr(MiniJson.get(o, "seasons"));
        boolean first = true;
        if (list != null) {
            for (Object x : list) {
                if (!first) b.append(',');
                first = false;
                b.append("{\"season_number\":").append(MiniJson.integer(x, "season_number", 0)).append('}');
            }
        }
        return b.append("]}").toString();
    }

    private static String compactSeason(String body) {
        StringBuilder b = new StringBuilder("{\"episodes\":[");
        List<Object> list = MiniJson.arr(MiniJson.get(MiniJson.parse(body), "episodes"));
        boolean first = true;
        if (list != null) {
            for (Object x : list) {
                if (!first) b.append(',');
                first = false;
                String name = MiniJson.str(x, "name"), still = MiniJson.str(x, "still_path");
                b.append("{\"episode_number\":").append(MiniJson.integer(x, "episode_number", 0))
                        .append(",\"name\":").append(MiniJson.quote(name == null ? "" : name))
                        .append(",\"still_path\":").append(MiniJson.quote(still == null ? "" : still)).append('}');
            }
        }
        return b.append("]}").toString();
    }

    /** TMDB fills a missing Spanish episode title with a placeholder; keep Trakt's title then. */
    static boolean isRealName(String name) {
        return name != null && !name.isEmpty() && !name.matches("(?i)(episodio|cap[ií]tulo|episode) \\d+");
    }

    /**
     * {image path, Spanish name, own poster_path} for one TMDB object, from the cache or one request (es-ES, which falls back to the
     * original title when there is no translation, so "True Detective" stays as it is). "" for what TMDB lacks;
     * null on failure.
     */
    private static String[] cached(SharedPreferences p, SharedPreferences.Editor cache, String cacheKey,
                                   String apiPath, String key, String field, String fallbackField, String nameField, boolean network) {
        cacheKey = "es:" + cacheKey;
        String path = p.getString(cacheKey, null);
        // Episodes and movies also keep their runtime; an entry saved before that is fetched once more to get it.
        boolean wantRuntime = apiPath.startsWith("movie/") || apiPath.contains("/episode/");
        String old = null;
        if (path != null) {
            old = p.getInt(cacheKey + ":rt", 0) + "";
            String[] hit = new String[]{path, p.getString(cacheKey + ":name", ""), p.getString(cacheKey + ":portrait", ""), old};
            if (!wantRuntime || p.contains(cacheKey + ":rt") || !network) return hit;
        }
        if (!network) return null;
        String body = get("https://api.themoviedb.org/3/" + apiPath + "?language=es-ES&api_key=" + key);
        if (body == null) return path != null ? new String[]{path, p.getString(cacheKey + ":name", ""), p.getString(cacheKey + ":portrait", ""), old} : null;
        Object o = MiniJson.parse(body);
        if (apiPath.matches("(movie|tv)/\\d+")) putStatus(cache, apiPath, o);
        String portrait = MiniJson.str(o, "poster_path");
        if (portrait == null) portrait = "";
        path = MiniJson.str(o, field);
        if ((path == null || path.isEmpty()) && fallbackField != null) path = MiniJson.str(o, fallbackField);
        if (path == null) path = "";
        String name = MiniJson.str(o, nameField);
        if (name == null) name = "";
        int runtime = Math.max(0, MiniJson.integer(o, "runtime", 0));
        cache.putString(cacheKey, path).putString(cacheKey + ":name", name).putString(cacheKey + ":portrait", portrait);
        if (wantRuntime) cache.putInt(cacheKey + ":rt", runtime);   // 0 = TMDB has none; the key marks it as asked
        return new String[]{path, name, portrait, String.valueOf(runtime)};
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The backdrop with the title drawn in (file path) of a movie: Spanish first, else English, the best voted of the language.
     * "" when it has none; null when not known yet (no network, or the request failed). One request ever per movie.
     */
    private static String titledBackdrop(SharedPreferences p, SharedPreferences.Editor cache, int id, String key, boolean network) {
        String ck = "es:titled:" + id;
        String saved = p.getString(ck, null);
        if (saved != null || !network) return saved;
        String body = get("https://api.themoviedb.org/3/movie/" + id + "/images?include_image_language=es,en,null&api_key=" + key);
        if (body == null) return null;
        String path = pickTitledBackdrop(body);
        cache.putString(ck, path);
        return path;
    }

    /** From the body of /movie/{id}/images: the backdrop with Spanish text, else English, the highest vote_average; "" if neither. */
    static String pickTitledBackdrop(String body) {
        List<Object> list = MiniJson.arr(MiniJson.get(MiniJson.parse(body), "backdrops"));
        if (list == null) return "";
        for (String lang : new String[]{"es", "en"}) {
            String best = "";
            double score = -1;
            for (Object x : list) {
                if (!lang.equals(MiniJson.str(x, "iso_639_1"))) continue;
                String path = MiniJson.str(x, "file_path");
                if (path == null || path.isEmpty()) continue;
                double v = MiniJson.number(x, "vote_average", 0);
                if (v > score) {
                    score = v;
                    best = path;
                }
            }
            if (!best.isEmpty()) return best;
        }
        return "";
    }

    /** Minutes left of a title: round(runtime x (100 - progress) / 100); 0 when the runtime is unknown or nothing is left. */
    static int minutesLeft(int runtime, int progress) {
        if (runtime <= 0) return 0;
        return Math.round(runtime * (100 - Math.max(0, Math.min(100, progress))) / 100f);
    }

    private static String get(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > 1024 * 1024) return null;
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TraktClient.TAG, "tmdb failed: " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
