package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Trakt API v2: device-flow sign-in and the "continue watching" list (paused items plus the next episode of the shows
 * in progress). Every network method blocks, so the caller runs it on a background thread; none of them throws. The
 * parsing is in static methods with no Android classes so it can be unit tested.
 */
final class TraktClient {
    private TraktClient() {}

    static final String TAG = "TvHomeTrakt";
    static final String PREFS = "trakt";
    private static final String API = "https://api.trakt.tv";
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_SHOWS = 15;
    private static final int MAX_ITEMS = 20;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final String OOB = "urn:ietf:wg:oauth:2.0:oob";

    static final class Item {
        String type = "movie"; // movie, episode, or show (a series of the watchlist, opened at S1E1)
        String title = "";     // the movie, or the series
        int year;
        int tmdbId;            // of the movie, or of the series
        int season;
        int episode;
        String episodeTitle;
        int progress;          // 0-100; 0 for a next episode
        String posterUrl;
        String portraitUrl;    // TMDB vertical poster (w342) of the movie or series; only the watchlist picks carry it
        String updatedAt = ""; // ISO 8601 UTC, so it sorts as text
        int showTraktId;       // series only, used to drop duplicates
    }

    static final class DeviceCode {
        String deviceCode, userCode, verificationUrl;
        int interval = 5, expiresIn = 600;
    }

    static final class Token {
        String access, refresh;
        long expiresAtMs;
    }

    // ---------------------------------------------------------------- parsing (pure)

    /** "media.trakt.tv/x.jpg" to "https://media.trakt.tv/x.jpg"; the first poster of a list is used. */
    static String poster(Object images) {
        Object p = MiniJson.get(images, "poster");
        if (p instanceof List && !((List<?>) p).isEmpty()) p = ((List<?>) p).get(0);
        if (!(p instanceof String) || ((String) p).isEmpty()) return null;
        String u = (String) p;
        if (u.startsWith("//")) return "https:" + u;
        return u.startsWith("http") ? u : "https://" + u;
    }

    private static String latest(String a, String b) {
        return a != null && !a.isEmpty() ? a : (b == null ? "" : b);
    }

    /** The body of GET /sync/playback: paused movies and episodes. */
    static List<Item> parsePlayback(String body) {
        List<Item> out = new ArrayList<>();
        List<Object> list = MiniJson.arr(MiniJson.parse(body));
        if (list == null) return out;
        for (Object o : list) {
            String type = MiniJson.str(o, "type");
            Item it = new Item();
            Object holder;
            if ("movie".equals(type)) {
                holder = MiniJson.get(o, "movie");
            } else if ("episode".equals(type)) {
                holder = MiniJson.get(o, "show");
                Object ep = MiniJson.get(o, "episode");
                it.type = "episode";
                it.season = MiniJson.integer(ep, "season", 0);
                it.episode = MiniJson.integer(ep, "number", 0);
                it.episodeTitle = MiniJson.str(ep, "title");
            } else {
                continue;
            }
            String title = MiniJson.str(holder, "title");
            if (title == null || title.isEmpty()) continue;
            Object ids = MiniJson.get(holder, "ids");
            it.title = title;
            it.year = MiniJson.integer(holder, "year", 0);
            it.tmdbId = MiniJson.integer(ids, "tmdb", 0);
            it.showTraktId = "episode".equals(type) ? MiniJson.integer(ids, "trakt", 0) : 0;
            it.progress = (int) Math.max(0, Math.min(100, Math.round(MiniJson.number(o, "progress", 0))));
            it.posterUrl = poster(MiniJson.get(holder, "images"));
            it.updatedAt = latest(MiniJson.str(o, "paused_at"), "");
            out.add(it);
        }
        return out;
    }

    /** The body of GET /sync/watched/shows: the series (as episode items still lacking their episode), newest first. */
    static List<Item> parseWatchedShows(String body, int max) {
        List<Item> out = new ArrayList<>();
        List<Object> list = MiniJson.arr(MiniJson.parse(body));
        if (list == null) return out;
        for (Object o : list) {
            Object show = MiniJson.get(o, "show");
            String title = MiniJson.str(show, "title");
            int trakt = MiniJson.integer(MiniJson.get(show, "ids"), "trakt", 0);
            if (title == null || title.isEmpty() || trakt <= 0) continue;
            Item it = new Item();
            it.type = "episode";
            it.title = title;
            it.year = MiniJson.integer(show, "year", 0);
            it.tmdbId = MiniJson.integer(MiniJson.get(show, "ids"), "tmdb", 0);
            it.showTraktId = trakt;
            it.posterUrl = poster(MiniJson.get(show, "images"));
            it.updatedAt = latest(MiniJson.str(o, "last_watched_at"), "");
            out.add(it);
        }
        Collections.sort(out, (a, b) -> b.updatedAt.compareTo(a.updatedAt));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** The body of GET /shows/{id}/progress/watched: show filled with its next episode, or null when none is left. */
    static Item parseNextEpisode(String body, Item show) {
        Object next = MiniJson.get(MiniJson.parse(body), "next_episode");
        if (MiniJson.obj(next) == null) return null;
        int season = MiniJson.integer(next, "season", -1);
        int number = MiniJson.integer(next, "number", -1);
        if (season < 1 || number < 1) return null; // specials are not offered
        Item it = new Item();
        it.type = "episode";
        it.title = show.title;
        it.year = show.year;
        it.tmdbId = show.tmdbId;
        it.showTraktId = show.showTraktId;
        it.season = season;
        it.episode = number;
        it.episodeTitle = MiniJson.str(next, "title");
        it.posterUrl = show.posterUrl != null ? show.posterUrl : poster(MiniJson.get(next, "images"));
        it.updatedAt = show.updatedAt;
        return it;
    }

    /** Playback entries first win over the next episode of the same series; newest first; at most max. */
    static List<Item> merge(List<Item> playback, List<Item> nextEpisodes, int max) {
        List<Item> all = new ArrayList<>(playback);
        Set<String> seen = new HashSet<>();
        for (Item p : playback) seen.add(key(p));
        for (Item n : nextEpisodes) {
            if (seen.add(key(n))) all.add(n);
        }
        // Several paused entries of the same movie or series: keep the newest.
        Collections.sort(all, (a, b) -> b.updatedAt.compareTo(a.updatedAt));
        List<Item> out = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        for (Item it : all) {
            if (kept.add(key(it)) && out.size() < max) out.add(it);
        }
        return out;
    }

    private static String key(Item it) {
        if ("movie".equals(it.type)) return "m" + (it.tmdbId > 0 ? it.tmdbId : it.title);
        return "s" + (it.showTraktId > 0 ? it.showTraktId : it.tmdbId > 0 ? it.tmdbId : it.title.hashCode());
    }

    /** The body of GET /sync/watchlist/movies or /shows (in the order Trakt gives): items of type movie or show. */
    static List<Item> parseWatchlist(String body, boolean shows) {
        List<Item> out = new ArrayList<>();
        List<Object> list = MiniJson.arr(MiniJson.parse(body));
        if (list == null) return out;
        for (Object o : list) {
            Object holder = MiniJson.get(o, shows ? "show" : "movie");
            String title = MiniJson.str(holder, "title");
            if (title == null || title.isEmpty()) continue;
            Item it = new Item();
            it.type = shows ? "show" : "movie";
            it.title = title;
            it.year = MiniJson.integer(holder, "year", 0);
            it.tmdbId = MiniJson.integer(MiniJson.get(holder, "ids"), "tmdb", 0);
            if (shows) {
                it.season = 1;
                it.episode = 1;
                it.showTraktId = MiniJson.integer(MiniJson.get(holder, "ids"), "trakt", 0);
            }
            it.updatedAt = latest(MiniJson.str(o, "listed_at"), "");
            out.add(it);
        }
        return out;
    }

    // ---------------------------------------------------------------- progress of a series (pure)

    /** Where the user is in a series: the episode to watch next (null when finished) and the episodes completed. */
    static final class Progress {
        Item next;
        final Set<Integer> watched = new HashSet<>();
    }

    /** The key of an episode in Progress.watched. */
    static int epKey(int season, int number) {
        return season * 10000 + number;
    }

    /** The body of GET /shows/{id}/progress/watched: the next episode and the completed ones (specials are not offered). */
    static Progress parseProgress(String body, Item show) {
        Progress p = new Progress();
        p.next = parseNextEpisode(body, show);
        List<Object> seasons = MiniJson.arr(MiniJson.get(MiniJson.parse(body), "seasons"));
        if (seasons == null) return p;
        for (Object s : seasons) {
            int season = MiniJson.integer(s, "number", -1);
            List<Object> eps = MiniJson.arr(MiniJson.get(s, "episodes"));
            if (season < 1 || eps == null) continue;
            for (Object e : eps) {
                int number = MiniJson.integer(e, "number", -1);
                if (number > 0 && Boolean.TRUE.equals(MiniJson.get(e, "completed"))) p.watched.add(epKey(season, number));
            }
        }
        return p;
    }

    /** The episode to open for a series: the next one, or the first when it is finished or unknown. */
    static Item openTarget(Item show, Item next) {
        if (next != null) return next;
        Item it = new Item();
        it.type = "episode";
        it.title = show.title;
        it.year = show.year;
        it.tmdbId = show.tmdbId;
        it.showTraktId = show.showTraktId;
        it.season = 1;
        it.episode = 1;
        return it;
    }

    /** The body of GET /search/tmdb/{id}?type=show: the Trakt id of the first match, or 0. */
    static int parseSearchShowId(String body) {
        List<Object> list = MiniJson.arr(MiniJson.parse(body));
        if (list == null) return 0;
        for (Object o : list) {
            int id = MiniJson.integer(MiniJson.get(MiniJson.get(o, "show"), "ids"), "trakt", 0);
            if (id > 0) return id;
        }
        return 0;
    }

    // ---------------------------------------------------------------- lists (pure)

    /** A Trakt list (own or liked) with its movies and series. */
    static final class TraktList {
        String name = "";
        String user = "me";   // the owner's slug; "me" for the user's own
        int id;               // Trakt id of the list
        List<Item> items = new ArrayList<>();
    }

    /** "movie" when every item is a movie, "series" when every item is a series, else "mixed". */
    static String listKind(List<Item> items) {
        boolean movies = false, shows = false;
        for (Item i : items) {
            if ("movie".equals(i.type)) movies = true;
            else shows = true;
        }
        return movies && !shows ? "movie" : shows && !movies ? "series" : "mixed";
    }

    /** The body of GET /users/me/lists or /users/likes/lists (entries wrap the list in "list"): lists without items. */
    static List<TraktList> parseLists(String body) {
        List<TraktList> out = new ArrayList<>();
        List<Object> list = MiniJson.arr(MiniJson.parse(body));
        if (list == null) return out;
        for (Object o : list) {
            Object l = MiniJson.get(o, "list") != null ? MiniJson.get(o, "list") : o;
            String name = MiniJson.str(l, "name");
            int id = MiniJson.integer(MiniJson.get(l, "ids"), "trakt", 0);
            if (name == null || name.isEmpty() || id <= 0) continue;
            TraktList t = new TraktList();
            t.name = name;
            t.id = id;
            String user = MiniJson.str(MiniJson.get(MiniJson.get(l, "user"), "ids"), "slug");
            if (user != null && !user.isEmpty()) t.user = user;
            out.add(t);
        }
        return out;
    }

    /** The body of GET /users/{slug}/lists/{id}/items: its movies and series, in the list's order. */
    static List<Item> parseListItems(String body) {
        List<Item> out = new ArrayList<>();
        List<Object> list = MiniJson.arr(MiniJson.parse(body));
        if (list == null) return out;
        for (Object o : list) {
            String type = MiniJson.str(o, "type");
            if (!"movie".equals(type) && !"show".equals(type)) continue;
            Object holder = MiniJson.get(o, type);
            String title = MiniJson.str(holder, "title");
            if (title == null || title.isEmpty()) continue;
            Object ids = MiniJson.get(holder, "ids");
            Item it = new Item();
            it.type = type;
            it.title = title;
            it.year = MiniJson.integer(holder, "year", 0);
            it.tmdbId = MiniJson.integer(ids, "tmdb", 0);
            if ("show".equals(type)) {
                it.season = 1;
                it.episode = 1;
                it.showTraktId = MiniJson.integer(ids, "trakt", 0);
            }
            it.updatedAt = latest(MiniJson.str(o, "listed_at"), "");
            out.add(it);
        }
        return out;
    }

    /** The lists whose name is in the comma separated filter (any case); all of them when the filter is empty. */
    static List<TraktList> filterLists(List<TraktList> lists, String filter) {
        Set<String> names = new HashSet<>();
        if (filter != null) {
            for (String n : filter.split(",")) if (!n.trim().isEmpty()) names.add(n.trim().toLowerCase(java.util.Locale.ROOT));
        }
        if (names.isEmpty()) return lists;
        List<TraktList> out = new ArrayList<>();
        for (TraktList l : lists) if (names.contains(l.name.trim().toLowerCase(java.util.Locale.ROOT))) out.add(l);
        return out;
    }

    static String serializeLists(List<TraktList> lists) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < lists.size(); i++) {
            TraktList l = lists.get(i);
            if (i > 0) b.append(',');
            b.append("{\"name\":").append(MiniJson.quote(l.name)).append(",\"user\":").append(MiniJson.quote(l.user))
                    .append(",\"id\":").append(l.id).append(",\"items\":").append(serialize(l.items)).append('}');
        }
        return b.append(']').toString();
    }

    static List<TraktList> deserializeLists(String text) {
        List<TraktList> out = new ArrayList<>();
        List<Object> list = MiniJson.arr(MiniJson.parse(text));
        if (list == null) return out;
        for (Object o : list) {
            String name = MiniJson.str(o, "name");
            if (name == null) continue;
            TraktList l = new TraktList();
            l.name = name;
            String user = MiniJson.str(o, "user");
            if (user != null && !user.isEmpty()) l.user = user;
            l.id = MiniJson.integer(o, "id", 0);
            l.items = items(MiniJson.arr(MiniJson.get(o, "items")));
            out.add(l);
        }
        return out;
    }

    /** n items picked at random (the same ones for the same list and seed); all of them, shuffled, when there are fewer. */
    static List<Item> pickRandom(List<Item> items, int n, long seed) {
        List<Item> copy = items == null ? new ArrayList<Item>() : new ArrayList<>(items);
        Collections.shuffle(copy, new Random(seed));
        return copy.size() > n ? new ArrayList<>(copy.subList(0, Math.max(0, n))) : copy;
    }

    /**
     * The day's picks: the whole watchlist shuffled with the day's seed, then the hidden titles dropped, so hiding one only
     * brings in the next title instead of a new draw.
     */
    static List<Item> dayPicks(Context c) {
        List<Item> vis = Hidden.visible(c, pickRandom(watchlistCached(c), Integer.MAX_VALUE, daySeed()));
        return vis.size() > WATCHLIST_PICKS ? new ArrayList<>(vis.subList(0, WATCHLIST_PICKS)) : vis;
    }

    /** Changes once a day: year * 1000 + day of the year. */
    static long daySeed() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR);
    }

    static DeviceCode parseDeviceCode(String body) {
        Object o = MiniJson.parse(body);
        String dc = MiniJson.str(o, "device_code");
        String uc = MiniJson.str(o, "user_code");
        if (dc == null || uc == null) return null;
        DeviceCode d = new DeviceCode();
        d.deviceCode = dc;
        d.userCode = uc;
        d.verificationUrl = MiniJson.str(o, "verification_url");
        d.interval = Math.max(1, MiniJson.integer(o, "interval", 5));
        d.expiresIn = MiniJson.integer(o, "expires_in", 600);
        return d;
    }

    static Token parseToken(String body, long nowMs) {
        Object o = MiniJson.parse(body);
        String access = MiniJson.str(o, "access_token");
        if (access == null || access.isEmpty()) return null;
        Token t = new Token();
        t.access = access;
        t.refresh = MiniJson.str(o, "refresh_token");
        t.expiresAtMs = nowMs + MiniJson.integer(o, "expires_in", 7776000) * 1000L;
        return t;
    }

    // ---------------------------------------------------------------- cache (pure)

    static String serialize(List<Item> items) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (i > 0) b.append(',');
            b.append("{\"type\":").append(MiniJson.quote(it.type))
                    .append(",\"title\":").append(MiniJson.quote(it.title))
                    .append(",\"year\":").append(it.year)
                    .append(",\"tmdb\":").append(it.tmdbId)
                    .append(",\"season\":").append(it.season)
                    .append(",\"episode\":").append(it.episode)
                    .append(",\"progress\":").append(it.progress)
                    .append(",\"show\":").append(it.showTraktId)
                    .append(",\"updated\":").append(MiniJson.quote(it.updatedAt));
            if (it.episodeTitle != null) b.append(",\"episodeTitle\":").append(MiniJson.quote(it.episodeTitle));
            if (it.posterUrl != null) b.append(",\"poster\":").append(MiniJson.quote(it.posterUrl));
            if (it.portraitUrl != null) b.append(",\"portrait\":").append(MiniJson.quote(it.portraitUrl));
            b.append('}');
        }
        return b.append(']').toString();
    }

    static List<Item> deserialize(String text) {
        return items(MiniJson.arr(MiniJson.parse(text)));
    }

    private static List<Item> items(List<Object> list) {
        List<Item> out = new ArrayList<>();
        if (list == null) return out;
        for (Object o : list) {
            String title = MiniJson.str(o, "title");
            if (title == null) continue;
            Item it = new Item();
            String type = MiniJson.str(o, "type");
            it.type = "episode".equals(type) || "show".equals(type) ? type : "movie";
            it.title = title;
            it.year = MiniJson.integer(o, "year", 0);
            it.tmdbId = MiniJson.integer(o, "tmdb", 0);
            it.season = MiniJson.integer(o, "season", 0);
            it.episode = MiniJson.integer(o, "episode", 0);
            it.progress = MiniJson.integer(o, "progress", 0);
            it.showTraktId = MiniJson.integer(o, "show", 0);
            it.updatedAt = latest(MiniJson.str(o, "updated"), "");
            it.episodeTitle = MiniJson.str(o, "episodeTitle");
            it.posterUrl = MiniJson.str(o, "poster");
            it.portraitUrl = MiniJson.str(o, "portrait");
            out.add(it);
        }
        return out;
    }

    // ---------------------------------------------------------------- state

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean hasToken(Context c) {
        return !prefs(c).getString("access_token", "").isEmpty();
    }

    static List<Item> cached(Context c) {
        try {
            return deserialize(prefs(c).getString("cache", ""));
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    private static final long WATCHLIST_TTL_MS = 6L * 60 * 60 * 1000;
    static final int WATCHLIST_PICKS = 15;   // Explorar's three pages: the featured title + 3, then 6, then 5 and "Ver más"

    /** The whole watchlist as last fetched; empty when there is none. */
    static List<Item> watchlistCached(Context c) {
        try {
            return deserialize(prefs(c).getString("watchlist_cache", ""));
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    /** The watchlist without what Hidden filters out. Cheap enough for the main thread. */
    static List<Item> watchlistVisible(Context c) {
        return Hidden.visible(c, watchlistCached(c));
    }

    /**
     * The lists as last fetched (only those named in the filter, if any), each with just its visible items (see Hidden); the
     * cover of a list comes from its first visible item, from the art cache only. Empty when there are none.
     */
    static List<TraktList> listsCached(Context c, String filter) {
        try {
            List<TraktList> lists = filterLists(deserializeLists(prefs(c).getString("lists_cache", "")), filter);
            java.util.Set<String> manual = Hidden.load(c);
            java.util.function.Predicate<Item> unreleased = Tmdb.unreleased(c);
            for (TraktList l : lists) {
                l.items = Hidden.visible(l.items, manual, unreleased);
                if (!l.items.isEmpty() && l.items.get(0).posterUrl == null) Tmdb.fillArt(c, Collections.singletonList(l.items.get(0)), false);
            }
            return lists;
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    /** The list with this Trakt id from the saved copy, or null. */
    static TraktList listCached(Context c, int id) {
        for (TraktList l : listsCached(c, "")) if (l.id == id) return l;
        return null;
    }

    /**
     * The 10 random titles of the day, drawn among the visible ones, with the art that is already cached; cheap, for the main
     * thread. The same draw every time on the same day and the same watchlist.
     */
    static List<Item> watchlistPicks(Context c) {
        try {
            List<Item> picks = dayPicks(c);
            Tmdb.fillArt(c, picks, false);
            return picks;
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    /** The size of the visible watchlist. */
    static int watchlistTotal(Context c) {
        return watchlistVisible(c).size();
    }

    static String status(Context c) {
        SharedPreferences p = prefs(c);
        long exp = p.getLong("expires_at", 0);
        return "client_id=" + (p.getString("client_id", "").isEmpty() ? "no" : "si")
                + " token=" + (hasToken(c) ? "si" : "no")
                + " caduca_en_dias=" + (exp == 0 ? "?" : String.valueOf((exp - System.currentTimeMillis()) / DAY_MS))
                + " cache=" + cached(c).size()
                + " user_code=" + p.getString("user_code", "") + " url=" + p.getString("verification_url", "");
    }

    private static void saveToken(Context c, Token t) {
        SharedPreferences.Editor e = prefs(c).edit().putString("access_token", t.access)
                .putLong("expires_at", t.expiresAtMs).remove("user_code").remove("verification_url");
        if (t.refresh != null) e.putString("refresh_token", t.refresh);
        e.apply();
    }

    // ---------------------------------------------------------------- network

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /** Blocking. Code -1 on a network error. */
    private static Response request(Context c, String method, String path, String json, boolean auth) {
        HttpURLConnection conn = null;
        try {
            SharedPreferences p = prefs(c);
            conn = (HttpURLConnection) new URL(API + path).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("trakt-api-version", "2");
            conn.setRequestProperty("trakt-api-key", p.getString("client_id", ""));
            if (auth) conn.setRequestProperty("Authorization", "Bearer " + p.getString("access_token", ""));
            if (json != null) {
                conn.setDoOutput(true);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return new Response(code, "");
            try (InputStream s = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = s.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > MAX_BYTES) return new Response(-1, "");
                }
                return new Response(code, new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "request " + path + " failed: " + e);
            return new Response(-1, "");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** GET with the bearer token; refreshes the token once on 401. Null on failure. */
    private static String get(Context c, String path) {
        Response r = request(c, "GET", path, null, true);
        if (r.code == 401 && refresh(c)) r = request(c, "GET", path, null, true);
        return r.code == 200 ? r.body : null;
    }

    /** Swaps the refresh token for a new access token. */
    /** PKCE-era Trakt apps have no secret: the device flow and refresh then go with the client id alone. */
    private static String secretField(SharedPreferences p) {
        String secret = p.getString("client_secret", "");
        return secret.isEmpty() ? "" : ",\"client_secret\":" + MiniJson.quote(secret);
    }

    static boolean refresh(Context c) {
        SharedPreferences p = prefs(c);
        String rt = p.getString("refresh_token", "");
        if (rt.isEmpty()) return false;
        String json = "{\"refresh_token\":" + MiniJson.quote(rt)
                + ",\"client_id\":" + MiniJson.quote(p.getString("client_id", ""))
                + secretField(p)
                + ",\"redirect_uri\":" + MiniJson.quote(OOB)
                + ",\"grant_type\":\"refresh_token\"}";
        Response r = request(c, "POST", "/oauth/token", json, false);
        Token t = r.code == 200 ? parseToken(r.body, System.currentTimeMillis()) : null;
        if (t == null) {
            Log.w(TAG, "refresh failed: " + r.code);
            return false;
        }
        saveToken(c, t);
        return true;
    }

    /** Refreshes when the token has under a day left. */
    private static void keepTokenFresh(Context c) {
        long exp = prefs(c).getLong("expires_at", 0);
        if (exp != 0 && exp - System.currentTimeMillis() < DAY_MS) refresh(c);
    }

    private static boolean polling;

    /**
     * Starts the device flow on its own thread: logs the code to enter at the verification URL (tag TvHomeTrakt),
     * keeps it in the prefs, and polls until the token arrives, the code expires or it is denied.
     */
    static synchronized void startDeviceFlow(Context context) {
        if (polling) return;
        polling = true;
        final Context c = context.getApplicationContext();
        new Thread(() -> {
            try {
                deviceFlow(c);
            } catch (RuntimeException e) {
                Log.w(TAG, "device flow failed: " + e);
            } finally {
                synchronized (TraktClient.class) {
                    polling = false;
                }
            }
        }, "trakt-device").start();
    }

    private static void deviceFlow(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getString("client_id", "").isEmpty()) {
            Log.w(TAG, "TRAKT_ERROR falta client_id");
            return;
        }
        Response r = request(c, "POST", "/oauth/device/code",
                "{\"client_id\":" + MiniJson.quote(p.getString("client_id", "")) + "}", false);
        DeviceCode d = r.code == 200 ? parseDeviceCode(r.body) : null;
        if (d == null) {
            Log.w(TAG, "TRAKT_ERROR device/code " + r.code);
            return;
        }
        String url = d.verificationUrl == null ? "https://trakt.tv/activate" : d.verificationUrl;
        p.edit().putString("user_code", d.userCode).putString("verification_url", url).apply();
        Log.i(TAG, "TRAKT_CODE user_code=" + d.userCode + " verification_url=" + url + " expires_in=" + d.expiresIn);
        long deadline = System.currentTimeMillis() + d.expiresIn * 1000L;
        long interval = d.interval * 1000L;
        String json = "{\"code\":" + MiniJson.quote(d.deviceCode)
                + ",\"client_id\":" + MiniJson.quote(p.getString("client_id", ""))
                + secretField(p) + "}";
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Response t = request(c, "POST", "/oauth/device/token", json, false);
            if (t.code == 200) {
                Token token = parseToken(t.body, System.currentTimeMillis());
                if (token != null) {
                    saveToken(c, token);
                    Log.i(TAG, "TRAKT_OK");
                    return;
                }
            } else if (t.code == 429) {
                interval += 1000; // slow down
            } else if (t.code != 400 && t.code != -1) { // 400 = not approved yet
                Log.w(TAG, "TRAKT_ERROR device/token " + t.code);
                return;
            }
        }
        Log.w(TAG, "TRAKT_ERROR el codigo ha caducado");
    }

    /**
     * Paused items plus the next episode of the series in progress, newest first, at most 10. Empty on any failure
     * (logged). The cache is only replaced when the playback request worked.
     */
    static List<Item> fetchContinue(Context c) {
        try {
            if (!hasToken(c)) return new ArrayList<>();
            keepTokenFresh(c);
            String playbackBody = get(c, "/sync/playback?extended=images");
            if (playbackBody == null) return new ArrayList<>();
            List<Item> playback = parsePlayback(playbackBody);
            Set<Integer> paused = new HashSet<>();
            for (Item p : playback) if (p.showTraktId > 0) paused.add(p.showTraktId);

            List<Item> next = new ArrayList<>();
            String watched = get(c, "/sync/watched/shows?extended=noseasons,images");
            if (watched != null) {
                for (Item show : parseWatchedShows(watched, MAX_SHOWS)) {
                    if (paused.contains(show.showTraktId)) continue;
                    String progress = get(c, "/shows/" + show.showTraktId + "/progress/watched?extended=images");
                    Item n = progress == null ? null : parseNextEpisode(progress, show);
                    if (n != null) next.add(n);
                }
            }
            List<Item> result = merge(playback, next, MAX_ITEMS);
            Tmdb.fillArt(c, result);
            prefs(c).edit().putString("cache", serialize(result)).apply();
            return result;
        } catch (RuntimeException e) {
            Log.w(TAG, "fetchContinue failed: " + e);
            return new ArrayList<>();
        }
    }

    /**
     * The whole watchlist (movies, then series). Asks Trakt only when the saved copy is missing or over 6 hours old;
     * otherwise, and when a request fails, the saved copy. Blocking.
     */
    static List<Item> fetchWatchlist(Context c) {
        try {
            SharedPreferences p = prefs(c);
            long age = System.currentTimeMillis() - p.getLong("watchlist_at", 0);
            if (p.contains("watchlist_cache") && age >= 0 && age < WATCHLIST_TTL_MS) return watchlistCached(c);
            if (!hasToken(c)) return watchlistCached(c);
            keepTokenFresh(c);
            String movies = get(c, "/sync/watchlist/movies");
            String shows = movies == null ? null : get(c, "/sync/watchlist/shows");
            if (movies == null || shows == null) return watchlistCached(c);
            List<Item> all = parseWatchlist(movies, false);
            int movieCount = all.size();
            all.addAll(parseWatchlist(shows, true));
            Log.i(TAG, "watchlist fetched: " + movieCount + " movies, " + (all.size() - movieCount) + " shows");
            p.edit().putString("watchlist_cache", serialize(all)).putLong("watchlist_at", System.currentTimeMillis()).apply();
            return all;
        } catch (RuntimeException e) {
            Log.w(TAG, "fetchWatchlist failed: " + e);
            return watchlistCached(c);
        }
    }

    /**
     * Brings the watchlist up to date (6 h gate) and returns the day's picks with their art (fetched from TMDB when missing).
     * Blocking.
     */
    static List<Item> refreshWatchlistPicks(Context c) {
        try {
            fetchWatchlist(c);
            List<Item> picks = dayPicks(c);
            Tmdb.fillArt(c, picks);
            return picks;
        } catch (RuntimeException e) {
            Log.w(TAG, "refreshWatchlistPicks failed: " + e);
            return watchlistPicks(c);
        }
    }

    private static final int LIST_PAGE = 100, LIST_PAGES_MAX = 20;

    /** All the items of one list, page by page; null when a request fails. */
    private static List<Item> fetchListItems(Context c, TraktList l) {
        List<Item> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int page = 1; page <= LIST_PAGES_MAX; page++) {
            String body = get(c, "/users/" + l.user + "/lists/" + l.id + "/items?page=" + page + "&limit=" + LIST_PAGE);
            if (body == null) return null;
            List<Object> raw = MiniJson.arr(MiniJson.parse(body));
            boolean added = false;
            for (Item it : parseListItems(body)) {
                if (seen.add(it.type + (it.tmdbId > 0 ? String.valueOf(it.tmdbId) : it.title))) {
                    all.add(it);
                    added = true;
                }
            }
            if (raw == null || raw.size() < LIST_PAGE || !added) break;
        }
        return all;
    }

    /**
     * The user's own lists and the ones they like, with their items (those named in the filter). Same 6 hour gate as the
     * watchlist, and the saved copy when a request fails. Only the first item of each list gets its art. Blocking.
     */
    static List<TraktList> fetchLists(Context c, String filter) {
        try {
            SharedPreferences p = prefs(c);
            long age = System.currentTimeMillis() - p.getLong("lists_at", 0);
            boolean same = p.getString("lists_filter", "").equals(filter);
            if (p.contains("lists_cache") && same && age >= 0 && age < WATCHLIST_TTL_MS) return listsCached(c, filter);
            if (!hasToken(c)) return listsCached(c, filter);
            keepTokenFresh(c);
            String own = get(c, "/users/me/lists");
            String liked = own == null ? null : get(c, "/users/likes/lists");
            if (own == null || liked == null) return listsCached(c, filter);
            List<TraktList> found = new ArrayList<>(parseLists(own));
            Set<String> keys = new HashSet<>();
            for (TraktList l : found) keys.add(l.user + "/" + l.id);
            for (TraktList l : parseLists(liked)) if (keys.add(l.user + "/" + l.id)) found.add(l);
            found = filterLists(found, filter);
            for (TraktList l : found) {
                List<Item> items = fetchListItems(c, l);
                if (items == null) return listsCached(c, filter);
                l.items = items;
                if (!items.isEmpty()) Tmdb.fillArt(c, Collections.singletonList(items.get(0)));
            }
            Log.i(TAG, "lists fetched: " + found.size());
            p.edit().putString("lists_cache", serializeLists(found)).putLong("lists_at", System.currentTimeMillis())
                    .putString("lists_filter", filter).apply();
            return found;
        } catch (RuntimeException e) {
            Log.w(TAG, "fetchLists failed: " + e);
            return listsCached(c, filter);
        }
    }

    /** The Trakt id of a series: the one it carries, the one remembered for its TMDB id, or a search. 0 if unknown. */
    static int showId(Context c, Item show) {
        if (show.showTraktId > 0) return show.showTraktId;
        if (show.tmdbId <= 0) return 0;
        SharedPreferences p = prefs(c);
        int known = p.getInt("showid:" + show.tmdbId, 0);
        if (known > 0) return known;
        String body = get(c, "/search/tmdb/" + show.tmdbId + "?type=show");
        int id = body == null ? 0 : parseSearchShowId(body);
        if (id > 0) p.edit().putInt("showid:" + show.tmdbId, id).apply();
        return id;
    }

    /** Where the user is in this series; null when it cannot be known (no token, no id, network). Blocking. */
    static Progress fetchProgress(Context c, Item show) {
        try {
            if (!hasToken(c)) return null;
            keepTokenFresh(c);
            int id = showId(c, show);
            if (id <= 0) return null;
            String body = get(c, "/shows/" + id + "/progress/watched?hidden=false&specials=false&count_specials=false");
            return body == null ? null : parseProgress(body, show);
        } catch (RuntimeException e) {
            Log.w(TAG, "fetchProgress failed: " + e);
            return null;
        }
    }
}
