package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.function.Consumer;

/** What Home needs for the "Seguir viendo" row; the rest of the Trakt and Kodi code stays behind this. */
final class ContinueWatching {
    private ContinueWatching() {}

    private static boolean running;

    /** The last list fetched, from the preferences; empty when there is none. Cheap enough for the main thread. */
    static List<TraktClient.Item> cached(Context context) {
        return TraktClient.cached(context);
    }

    /** The day's "Para ver" picks, from the preferences. */
    static List<TraktClient.Item> cachedWatchlistPicks(Context context) {
        return TraktClient.watchlistPicks(context);
    }

    /**
     * Fetches the list on a background thread and hands it to onMainThread (the cached list when the request fails).
     * Then, on the same thread, brings the watchlist picks up to date (Trakt is asked at most every 6 hours) and hands
     * them to onWatchlist. Does nothing without a Trakt token, or while a refresh is already running.
     */
    static void refresh(Context context, Consumer<List<TraktClient.Item>> onMainThread,
                        Consumer<List<TraktClient.Item>> onWatchlist, Consumer<List<TraktClient.TraktList>> onLists) {
        final Context c = context.getApplicationContext();
        if (!TraktClient.hasToken(c)) return;
        synchronized (ContinueWatching.class) {
            if (running) return;
            running = true;
        }
        BgThreads.thread("trakt-continue", () -> {
            List<TraktClient.Item> items = null;
            try {
                items = TraktClient.fetchContinue(c);
                Log.i(TraktClient.TAG, "continue: " + items.size() + " items");
                if (items.isEmpty()) items = TraktClient.cached(c);
            } catch (RuntimeException e) {
                Log.w(TraktClient.TAG, "refresh failed: " + e);
            }
            final List<TraktClient.Item> result = items == null ? TraktClient.cached(c) : items;
            Handler main = new Handler(Looper.getMainLooper());
            main.post(() -> onMainThread.accept(result));
            List<TraktClient.Item> picks = null;
            try {
                picks = TraktClient.refreshWatchlistPicks(c);
                Log.i(TraktClient.TAG, "watchlist: " + picks.size() + " picks of " + TraktClient.watchlistTotal(c));
            } catch (RuntimeException e) {
                Log.w(TraktClient.TAG, "watchlist refresh failed: " + e);
            }
            final List<TraktClient.Item> wl = picks == null ? TraktClient.watchlistPicks(c) : picks;
            main.post(() -> onWatchlist.accept(wl));
            String filter = "";
            List<TraktClient.TraktList> lists = null;
            try {
                filter = Config.load(c).traktLists;
                lists = TraktClient.fetchLists(c, filter);
                Log.i(TraktClient.TAG, "lists: " + lists.size());
            } catch (RuntimeException e) {
                Log.w(TraktClient.TAG, "lists refresh failed: " + e);
            }
            // Explorar and Listas show only what Hidden lets through, so the cache is read again for them.
            final String shown = filter;
            main.post(() -> onLists.accept(TraktClient.listsCached(c, shown)));
            try {
                fillStatus(c, filter);
            } catch (RuntimeException e) {
                Log.w(TraktClient.TAG, "status refresh failed: " + e);
            } finally {
                synchronized (ContinueWatching.class) {
                    running = false;
                }
            }
            final String f = filter;
            final List<TraktClient.Item> wl2 = TraktClient.refreshWatchlistPicks(c);
            final List<TraktClient.TraktList> ls2 = TraktClient.listsCached(c, f);
            main.post(() -> onWatchlist.accept(wl2));
            main.post(() -> onLists.accept(ls2));
        }).start();
    }

    /**
     * Gives every title of the watchlist and the lists its TMDB release status (only what is missing, 100 ms between requests),
     * then logs how many titles the automatic filters leave out. Blocking.
     */
    private static void fillStatus(Context c, String filter) {
        if (!Tmdb.hasKey(c)) return;
        List<TraktClient.Item> all = new java.util.ArrayList<>(TraktClient.watchlistCached(c));
        List<TraktClient.TraktList> raw = TraktClient.filterLists(TraktClient.deserializeLists(
                c.getSharedPreferences(TraktClient.PREFS, Context.MODE_PRIVATE).getString("lists_cache", "")), filter);
        for (TraktClient.TraktList l : raw) all.addAll(l.items);
        int fetched = Tmdb.fillStatus(c, all);
        for (TraktClient.TraktList l : raw) {   // the cover of a list is its first visible title
            List<TraktClient.Item> vis = Hidden.visible(c, l.items);
            if (!vis.isEmpty()) Tmdb.fillArt(c, java.util.Collections.singletonList(vis.get(0)));
        }
        java.util.Set<String> manual = Hidden.load(c);
        java.util.function.Predicate<TraktClient.Item> unreleased = Tmdb.unreleased(c);
        java.util.Set<String> seen = new java.util.HashSet<>();
        int[] n = new int[4];
        for (TraktClient.Item it : all) {
            if (!seen.add(it.type + ":" + (it.tmdbId > 0 ? String.valueOf(it.tmdbId) : it.title))) continue;
            int why = Hidden.reason(it, manual, unreleased);
            n[why]++;
            if (why == Hidden.NO_TMDB || why == Hidden.UNRELEASED) {
                Log.i(TraktClient.TAG, "hidden (" + (why == Hidden.NO_TMDB ? "no tmdb" : "unreleased") + "): " + it.title + " (" + it.year + ")");
            }
        }
        Log.i(TraktClient.TAG, "status: " + fetched + " fetched; of " + seen.size() + " titles hidden: no tmdb " + n[Hidden.NO_TMDB]
                + ", unreleased " + n[Hidden.UNRELEASED] + ", manual " + n[Hidden.MANUAL] + ", visible " + n[Hidden.VISIBLE]);
    }

    /** The saved lists of the "Listas" tab, filtered by trakt_lists. Cheap enough for the main thread. */
    static List<TraktClient.TraktList> cachedLists(Context context, String filter) {
        return TraktClient.listsCached(context, filter);
    }

    /**
     * Opens Kodi (if it is not open) and starts the item in Palantir 3. A series (type show) opens at the episode that
     * is next for the user on Trakt, found in the background; S1E1 if it is finished or Trakt does not answer.
     */
    static void open(Context context, TraktClient.Item item) {
        if (!"show".equals(item.type)) {
            KodiRpc.play(context, PalantirLink.forItem(item));
            return;
        }
        final Context c = context.getApplicationContext();
        BgThreads.thread("trakt-next", () -> {
            TraktClient.Progress p = TraktClient.fetchProgress(c, item);
            TraktClient.Item target = TraktClient.openTarget(item, p == null ? null : p.next);
            Log.i(TraktClient.TAG, "next episode of " + item.title + ": " + target.season + "x" + target.episode
                    + (p == null ? " (no progress)" : ""));
            new Handler(Looper.getMainLooper()).post(() -> KodiRpc.play(c, PalantirLink.forItem(target)));
        }).start();
    }
}
