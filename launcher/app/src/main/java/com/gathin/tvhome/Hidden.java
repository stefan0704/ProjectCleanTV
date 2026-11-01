package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What Explorar (the watchlist) and the Listas grids do not show, because Palantir would almost surely fail on it: titles
 * without a TMDB id, titles not released yet, and the ones the user hid by hand (long press, or "Ocultar serie"). The filter
 * is applied when the cache is read for display, so hiding takes effect at once. Seguir viendo is never filtered.
 * Undo from the PC: adb shell am broadcast -n com.gathin.tvhome/.TraktReceiver -a com.gathin.tvhome.HIDDEN_CLEAR
 */
final class Hidden {
    private Hidden() {}

    static final int VISIBLE = 0, NO_TMDB = 1, MANUAL = 2, UNRELEASED = 3;

    private static final String KEY = "hidden";
    /** Bumped whenever the manual set changes, so an open screen knows it must read the cache again. */
    private static volatile int version;

    static int version() {
        return version;
    }

    /** "movie:<tmdb>" for a movie, "show:<tmdb>" for a series (or anything else). */
    static String keyOf(TraktClient.Item it) {
        return ("movie".equals(it.type) ? "movie:" : "show:") + it.tmdbId;
    }

    static Set<String> load(Context c) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(TraktClient.PREFS, Context.MODE_PRIVATE);
        return new HashSet<>(p.getStringSet(KEY, new HashSet<String>()));
    }

    static void add(Context c, String key) {
        Set<String> s = load(c);
        if (!s.add(key)) return;
        c.getApplicationContext().getSharedPreferences(TraktClient.PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, s).apply();
        version++;
    }

    static void clear(Context c) {
        c.getApplicationContext().getSharedPreferences(TraktClient.PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
        version++;
    }

    /** Why the item is not shown (NO_TMDB, MANUAL, UNRELEASED) or VISIBLE. */
    static int reason(TraktClient.Item it, Set<String> manual, Predicate<TraktClient.Item> unreleased) {
        if (it.tmdbId <= 0) return NO_TMDB;
        if (manual != null && manual.contains(keyOf(it))) return MANUAL;
        if (unreleased != null && unreleased.test(it)) return UNRELEASED;
        return VISIBLE;
    }

    /** The items worth showing, in order. */
    static List<TraktClient.Item> visible(List<TraktClient.Item> items, Set<String> manual, Predicate<TraktClient.Item> unreleased) {
        List<TraktClient.Item> out = new ArrayList<>();
        for (TraktClient.Item it : items) if (reason(it, manual, unreleased) == VISIBLE) out.add(it);
        return out;
    }

    /** Same filter over everything cached, with the TMDB statuses saved so far. Cheap enough for the main thread. */
    static List<TraktClient.Item> visible(Context c, List<TraktClient.Item> items) {
        return visible(items, load(c), Tmdb.unreleased(c));
    }
}
