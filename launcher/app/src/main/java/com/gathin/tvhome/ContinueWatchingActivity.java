package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Full screen "Seguir viendo" (opened by the "Ver más" button of Home's row): the cached list as a grid of 4 columns, in a
 * SERIES and a PELÍCULAS section, over the big image of the focused title (Backdrop). OK on a card opens it in Kodi and
 * closes this screen; Back just closes it. A long press on a series opens its seasons (SeriesActivity). Type "list" shows one Trakt
 * list of Home's Listas tab (extra list_id) like the watchlist: covers, with PELÍCULAS and SERIES sections when it has both.
 */
public final class ContinueWatchingActivity extends Activity {
    /** 4 columns of 16:9 cards; the whole watchlist shows 2:3 covers in WATCH_COLUMNS columns. */
    private static final int CARD_COLUMNS = 4, WATCH_COLUMNS = 7;
    private int columns = CARD_COLUMNS;
    private float zoom = 1.05f;
    private Palette pal;
    /** The focused title's image behind the lists; null in the light look, whose dark texts need a light screen. */
    private Backdrop backdrop;
    /** The whole Trakt watchlist: its titles have no art yet, which is fetched a few rows ahead of the focus. */
    private boolean watchlist;
    /** The name of the Trakt list shown (type "list"), else null; such a list is laid out like the watchlist. */
    private String listName;
    private final List<TraktClient.Item> flat = new ArrayList<>();
    private final List<LinearLayout> flatCells = new ArrayList<>();
    private final java.util.Set<Integer> requested = new java.util.HashSet<>();
    private java.util.concurrent.ExecutorService art;
    private TraktClient.Item focusedItem;
    /** Hidden version this screen was built with; a newer one (a title hidden here or in SeriesActivity) rebuilds it. */
    private int hiddenSeen;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiFont.install(this);
        hiddenSeen = Hidden.version();
        Config cfg = Config.load(this);
        pal = Palette.of(cfg);
        zoom = pal.zoom();
        final boolean animate = cfg.animations && (!cfg.reducedMotion || cfg.softMotion) && state == null;
        final float density = getResources().getDisplayMetrics().density;

        String only = getIntent().getStringExtra("type");   // "episode", "movie" or "watchlist": just that section / the whole watchlist
        watchlist = "watchlist".equals(only);
        List<TraktClient.Item> source;
        if ("list".equals(only)) {
            TraktClient.TraktList l = TraktClient.listCached(this, getIntent().getIntExtra("list_id", 0));
            if (l == null) {
                finish();
                return;
            }
            listName = l.name;
            watchlist = true;
            source = l.items;
        } else {
            source = watchlist ? TraktClient.watchlistVisible(this) : ContinueWatching.cached(this);
        }
        if (watchlist) columns = WATCH_COLUMNS;
        List<TraktClient.Item> series = new ArrayList<>(), movies = new ArrayList<>();
        for (TraktClient.Item i : source) {
            if ("episode".equals(i.type) || "show".equals(i.type)) series.add(i);
            else movies.add(i);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(pal.screenBg);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        if (!(pal.apple && !pal.dark)) {
            backdrop = new Backdrop(this, animate);
            root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        // Both flags: with the side padding, clipToPadding alone cut the zoomed cards of the first and last column at the padding.
        page.setClipChildren(false);
        page.setClipToPadding(false);
        int side = (int) (56 * density);
        page.setPadding(side, (int) (32 * density), side, (int) (32 * density));
        scroll.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (watchlist) only = null;
        if (only != null) {
            if ("episode".equals(only)) movies.clear();
            else series.clear();
        }
        boolean split = !series.isEmpty() && !movies.isEmpty();
        boolean heads = listName == null || split;   // a list of one kind needs no section heading
        TextView title = label(listName != null ? (pal.apple ? listName : listName.toUpperCase(java.util.Locale.ROOT)) : watchlist ? (pal.apple ? "Explorar" : "EXPLORAR") : only != null ? ("episode".equals(only) ? "Series" : "Películas") : pal.apple ? "Seguir viendo" : "SEGUIR VIENDO", pal.titleContinue, pal.apple ? 28 : 22, pal.apple ? 0f : 0.2f, density);
        page.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int gap = (int) (16 * density);
        int w = (getResources().getDisplayMetrics().widthPixels - 2 * side - (columns - 1) * gap) / columns;
        int h = Math.round(w * (watchlist ? 3f / 2f : 9f / 16f));
        View first = null;
        if (watchlist && !movies.isEmpty()) first = section(page, !heads ? null : pal.apple ? "Películas" : "PELÍCULAS", pal.titleMovies, movies, w, h, gap, density, animate);
        if (!series.isEmpty()) {
            View sv = section(page, only != null || !heads ? null : pal.apple ? "Series" : "SERIES", pal.titleSeries, series, w, h, gap, density, animate);
            if (first == null) first = sv;
        }
        if (!watchlist && !movies.isEmpty()) {
            View m = section(page, only != null ? null : pal.apple ? "Películas" : "PELÍCULAS", pal.titleMovies, movies, w, h, gap, density, animate);
            if (first == null) first = m;
        }
        setContentView(root);
        if (first == null) {
            finish();
            return;
        }
        first.requestFocus();
        if (watchlist) ensureArt(0, 3 * columns - 1);
        if (animate) {
            root.setAlpha(0f);
            root.animate().alpha(1f).setDuration(160).start();
        }
    }

    /** Adds a titled grid; returns its first card. */
    private View section(LinearLayout page, String name, int color, List<TraktClient.Item> items, int w, int h, int gap,
                         float density, boolean animate) {
        if (name != null) {
            TextView t = label(name, color, pal.apple ? 18 : 12, pal.apple ? 0f : 0.25f, density);
            t.setPadding((int) (4 * density), (int) (24 * density), 0, (int) (8 * density));
            page.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        View first = null;
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = (int) (8 * density);
                page.addView(row, rlp);
            }
            TraktClient.Item item = items.get(i);
            LinearLayout cell = watchlist ? ContinueCard.boxCell(this, pal, item, w, h) : ContinueCard.cell(this, pal, item, w, h);
            View card = cell.getChildAt(0);
            final int index = flat.size();
            flat.add(item);
            flatCells.add(cell);
            card.setOnClickListener(v -> {
                ContinueWatching.open(this, item);
                finish();
            });
            if (SeriesActivity.canOpen(item)) card.setOnLongClickListener(v -> {
                SeriesActivity.start(this, item);
                return true;
            });
            else if (watchlist && "movie".equals(item.type) && item.tmdbId > 0) card.setOnLongClickListener(v -> {
                HideDialog.show(this, pal, item.title, () -> {
                    Hidden.add(this, Hidden.keyOf(item));
                    focusAt = Math.min(index, Math.max(0, flat.size() - 2));
                    recreate();
                });
                return true;
            });
            card.setOnFocusChangeListener((v, focused) -> {
                if (focused) {
                    focusedItem = item;
                    if (backdrop != null) backdrop.show(item.posterUrl);
                    if (watchlist) ensureArt(index, index + 2 * columns);   // this row and the two below
                }
                float scale = focused ? zoom : 1f;
                v.animate().cancel();
                float lift = 0f;
                if (animate) v.animate().scaleX(scale).scaleY(scale).translationZ(lift).setDuration(120).start();
                else {
                    v.setTranslationZ(lift);
                    v.setScaleX(scale);
                    v.setScaleY(scale);
                }
                float radius = watchlist ? ContinueCard.BOX_RADIUS_DP : 12;
                if (ContinueCard.framed(v)) ContinueCard.setFocusFrame(v, focused, pal, radius);
                else ((FrameLayout) v).setForeground(focused ? focusFrame(density, radius) : null);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % columns > 0) lp.setMarginStart(gap);
            row.addView(cell, lp);
            if (first == null) first = card;
        }
        return first;
    }

    /** Fetches the TMDB art and Spanish titles of the watchlist entries from..to (once each), on one background thread. */
    private void ensureArt(int from, int to) {
        List<TraktClient.Item> batch = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        for (int i = Math.max(0, from - columns); i <= to && i < flat.size(); i++) {
            if (requested.add(i)) {
                batch.add(flat.get(i));
                at.add(i);
            }
        }
        if (batch.isEmpty()) return;
        if (art == null) art = BgThreads.single("bg-art");
        final android.content.Context c = getApplicationContext();
        art.execute(() -> {
            Tmdb.fillArt(c, batch);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                for (int k = 0; k < batch.size(); k++) {
                    if (watchlist) ContinueCard.refreshBoxCell(this, flatCells.get(at.get(k)), batch.get(k));
                    else ContinueCard.refreshCell(this, flatCells.get(at.get(k)), batch.get(k));
                    if (backdrop != null && batch.get(k) == focusedItem) backdrop.show(focusedItem.posterUrl);
                }
            });
        });
    }

    private int focusAt = -1;

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (focusAt >= 0) out.putInt("focus", focusAt);
    }

    /** After hiding a title: the focus goes to the card that took its place (the saved view state would pick the first one). */
    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        if (state.containsKey("focus") && !flatCells.isEmpty()) {
            flatCells.get(Math.min(state.getInt("focus"), flatCells.size() - 1)).getChildAt(0).requestFocus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hiddenSeen != Hidden.version()) recreate();   // a series was hidden from its own screen
    }

    @Override
    protected void onDestroy() {
        if (art != null) art.shutdownNow();
        if (backdrop != null) backdrop.destroy();
        super.onDestroy();
    }

    private GradientDrawable focusFrame(float density, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x00000000);
        d.setCornerRadius(radiusDp * density);
        d.setStroke((int) ((pal.apple ? 2 : 3) * density), pal.apple ? pal.accent : ContinueCard.YELLOW);
        return d;
    }

    private TextView label(String text, int color, int sp, float spacing, float density) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(UiFont.get(this, R.string.font_medium));
        t.setLetterSpacing(spacing);
        t.setSingleLine(true);
        t.setPadding((int) (4 * density), 0, 0, 0);
        return t;
    }
}
