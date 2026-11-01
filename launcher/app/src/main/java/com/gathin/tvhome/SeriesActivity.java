package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * A series, opened by a long press on its card: its backdrop behind, a row of "Temporada n" pills and the episodes of the chosen
 * season as a grid of 4 columns of 16:9 stills. The episodes already watched on Trakt are dimmed with a tick; the one to watch next
 * has the focus on arrival, in its season. Focusing a season pill (or OK on it) loads that season; OK on an episode opens it in Kodi
 * and closes this screen; Back just closes it. Seasons and episodes come from TMDB (cached for a day), progress from Trakt.
 */
public final class SeriesActivity extends Activity {
    private static final int COLUMNS = 4;

    private Palette pal;
    private Backdrop backdrop;
    private float zoom = 1.05f;
    private boolean animate;
    private float density;
    private ExecutorService exec;
    private LinearLayout page, seasonRow, grid;
    private final List<TextView> pills = new ArrayList<>();
    private final List<Integer> seasons = new ArrayList<>();
    private int selected;
    /** The episode to focus on arrival (0 once it has been done or when there is none). */
    private int focusEpisode;
    private int generation;
    private int showTmdb, showTrakt;
    private String showTitle = "";
    private TraktClient.Progress progress;
    /** The cards of the season on screen, their items, their still URLs and the ones whose still was asked for. */
    private final List<LinearLayout> cells = new ArrayList<>();
    private final List<TraktClient.Item> items = new ArrayList<>();
    private final List<String> stills = new ArrayList<>();
    private final List<String> captions = new ArrayList<>();
    private final Set<Integer> requested = new HashSet<>();

    /** True for an item that is a series (or an episode of one) TMDB knows. */
    static boolean canOpen(TraktClient.Item item) {
        return ("show".equals(item.type) || "episode".equals(item.type)) && item.tmdbId > 0;
    }

    static void start(Context c, TraktClient.Item item) {
        Intent i = new Intent(c, SeriesActivity.class).putExtra("tmdb", item.tmdbId).putExtra("trakt", item.showTraktId)
                .putExtra("title", item.title);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiFont.install(this);
        Config cfg = Config.load(this);
        pal = Palette.of(cfg);
        zoom = pal.zoom();
        animate = cfg.animations && (!cfg.reducedMotion || cfg.softMotion);
        density = getResources().getDisplayMetrics().density;
        showTmdb = getIntent().getIntExtra("tmdb", 0);
        showTrakt = getIntent().getIntExtra("trakt", 0);
        String t = getIntent().getStringExtra("title");
        showTitle = t == null ? "" : t;
        if (showTmdb <= 0) {
            finish();
            return;
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
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        int side = (int) (56 * density);
        page.setPadding(side, (int) (32 * density), side, (int) (32 * density));
        scroll.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText(pal.apple ? showTitle : showTitle.toUpperCase(java.util.Locale.ROOT));
        title.setTextColor(pal.titleContinue);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, pal.apple ? 28 : 22);
        title.setTypeface(UiFont.get(this, R.string.font_medium));
        title.setLetterSpacing(pal.apple ? 0f : 0.2f);
        title.setSingleLine(true);
        title.setPadding((int) (4 * density), 0, 0, 0);
        page.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView seasonScroll = new HorizontalScrollView(this);
        seasonScroll.setHorizontalScrollBarEnabled(false);
        seasonScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        seasonScroll.setClipChildren(false);
        seasonScroll.setClipToPadding(false);
        seasonRow = new LinearLayout(this);
        seasonRow.setOrientation(LinearLayout.HORIZONTAL);
        seasonRow.setClipChildren(false);
        seasonRow.setClipToPadding(false);
        seasonRow.setPadding((int) (4 * density), (int) (4 * density), (int) (4 * density), (int) (4 * density));
        seasonScroll.addView(seasonRow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = (int) (16 * density);
        page.addView(seasonScroll, slp);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = (int) (12 * density);
        page.addView(grid, glp);
        setContentView(root);
        if (animate) {
            root.setAlpha(0f);
            root.animate().alpha(1f).setDuration(160).start();
        }

        final Context c = getApplicationContext();
        final TraktClient.Item show = new TraktClient.Item();
        show.type = "show";
        show.title = showTitle;
        show.tmdbId = showTmdb;
        show.showTraktId = showTrakt;
        exec = BgThreads.single("bg-series");
        exec.execute(() -> {
            Tmdb.Series info = Tmdb.series(c, showTmdb);
            TraktClient.Progress p = TraktClient.fetchProgress(c, show);
            runOnUiThread(() -> arrived(info, p));
        });
    }

    /** The series and the user's progress are known: backdrop, season pills, and the season holding the next episode. */
    private void arrived(Tmdb.Series info, TraktClient.Progress p) {
        if (isDestroyed()) return;
        progress = p;
        if (info == null || info.seasons.isEmpty()) {
            addHidePill();
            message("No se han podido cargar las temporadas");
            return;
        }
        if (backdrop != null) backdrop.show(info.backdrop);
        seasons.addAll(info.seasons);
        TraktClient.Item next = p == null ? null : p.next;
        int season = next != null && seasons.contains(next.season) ? next.season : seasons.get(0);
        focusEpisode = next != null && next.season == season ? next.episode : 1;
        for (int n : seasons) seasonRow.addView(pill(n), pillParams());
        addHidePill();
        select(season);
    }

    /** "Ocultar serie" to the right of the season pills: asks, hides the series from Explorar and Listas, and closes this screen. */
    private void addHidePill() {
        TextView t = new TextView(this);
        t.setText("Ocultar serie");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(UiFont.get(this, R.string.font_medium));
        t.setTextColor(pal.secondary);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (16 * density), (int) (5 * density), (int) (16 * density), (int) (5 * density));
        t.setBackground(pal.pillBackground(density, 20));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(v -> HideDialog.show(this, pal, showTitle, () -> {
            Hidden.add(this, "show:" + showTmdb);
            finish();
        }));
        t.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (!cells.isEmpty()) cells.get(0).getChildAt(0).requestFocus();
            return true;
        });
        LinearLayout.LayoutParams lp = pillParams();
        lp.setMarginStart((int) (24 * density));
        seasonRow.addView(t, lp);
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (seasonRow.getChildCount() > 0) lp.setMarginStart((int) (8 * density));
        return lp;
    }

    private TextView pill(int season) {
        TextView t = new TextView(this);
        t.setText("Temporada " + season);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(UiFont.get(this, R.string.font_medium));
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (16 * density), (int) (5 * density), (int) (16 * density), (int) (5 * density));
        t.setFocusable(true);
        t.setClickable(true);
        t.setTag(season);
        t.setOnClickListener(v -> select(season));
        t.setOnFocusChangeListener((v, focused) -> {
            if (focused) select(season);
            stylePills();
        });
        t.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (!cells.isEmpty()) cells.get(0).getChildAt(0).requestFocus();
            return true;
        });
        pills.add(t);
        return t;
    }

    /** Selected: filled (accent, or a soft white/black); the others: text only. Focused: a ring in the accent colour. */
    private void stylePills() {
        for (TextView t : pills) {
            boolean sel = (Integer) t.getTag() == selected;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(20 * density);
            g.setColor(sel ? (pal.apple ? (pal.dark ? 0x26FFFFFF : 0x14000000) : pal.accent) : 0x00000000);
            if (t.isFocused()) g.setStroke(Math.round(2 * density), pal.accent);
            t.setBackground(g);
            t.setTextColor(sel ? pal.primary : pal.secondary);
        }
    }

    private void select(int season) {
        if (season == selected) return;
        selected = season;
        stylePills();
        final int mine = ++generation;
        final Context c = getApplicationContext();
        exec.execute(() -> {
            List<Tmdb.Episode> eps = Tmdb.season(c, showTmdb, season);
            runOnUiThread(() -> {
                if (!isDestroyed() && mine == generation) build(season, eps);
            });
        });
    }

    private void message(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(pal.secondary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(UiFont.get(this, R.string.font_body));
        t.setPadding((int) (4 * density), (int) (16 * density), 0, 0);
        grid.addView(t);
    }

    /** The grid of the season: cards are made at once, their stills are asked for as the focus nears them (ensure). */
    private void build(int season, List<Tmdb.Episode> eps) {
        grid.removeAllViews();
        cells.clear();
        items.clear();
        stills.clear();
        captions.clear();
        requested.clear();
        if (eps == null || eps.isEmpty()) {
            message(eps == null ? "No se han podido cargar los episodios" : "Esta temporada no tiene episodios");
            return;
        }
        int side = (int) (56 * density);
        int gap = (int) (16 * density);
        int w = (getResources().getDisplayMetrics().widthPixels - 2 * side - (COLUMNS - 1) * gap) / COLUMNS;
        int h = Math.round(w * 9f / 16f);
        LinearLayout row = null;
        int focusAt = 0;
        for (int i = 0; i < eps.size(); i++) {
            Tmdb.Episode ep = eps.get(i);
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = (int) (8 * density);
                grid.addView(row, rlp);
            }
            TraktClient.Item it = new TraktClient.Item();
            it.type = "episode";
            it.title = Tmdb.isRealName(ep.name) ? ep.name : "Episodio " + ep.number;
            it.tmdbId = showTmdb;
            it.season = season;
            it.episode = ep.number;
            it.episodeTitle = Tmdb.isRealName(ep.name) ? ep.name : null;
            String caption = Tmdb.episodeLabel(ep);
            boolean watched = progress != null && progress.watched.contains(TraktClient.epKey(season, ep.number));
            LinearLayout cell = ContinueCard.cell(this, pal, it, w, h, caption);
            FrameLayout card = (FrameLayout) cell.getChildAt(0);
            if (watched) {
                card.addView(tick(), tickParams());
                cell.setAlpha(0.5f);
            }
            final int index = cells.size();
            cells.add(cell);
            items.add(it);
            stills.add(ep.still);
            captions.add(caption);
            if (ep.number == focusEpisode) focusAt = index;
            card.setOnClickListener(v -> {
                TraktClient.Item play = new TraktClient.Item();
                play.type = "episode";
                play.title = showTitle;
                play.tmdbId = showTmdb;
                play.season = it.season;
                play.episode = it.episode;
                play.episodeTitle = it.episodeTitle;
                ContinueWatching.open(this, play);
                finish();
            });
            card.setOnFocusChangeListener((v, focused) -> {
                if (focused) ensure(index, index + 2 * COLUMNS);   // this row and the two below
                cell.setAlpha(focused || !watched ? 1f : 0.5f);
                float scale = focused ? zoom : 1f;
                v.animate().cancel();
                if (animate) v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
                else {
                    v.setScaleX(scale);
                    v.setScaleY(scale);
                }
                if (ContinueCard.framed(v)) ContinueCard.setFocusFrame(v, focused, pal, 12);
                else ((FrameLayout) v).setForeground(focused ? focusFrame(12) : null);
            });
            if (index < COLUMNS) card.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_UP) return false;
                for (TextView p : pills) if ((Integer) p.getTag() == selected) p.requestFocus();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % COLUMNS > 0) lp.setMarginStart(gap);
            row.addView(cell, lp);
        }
        ensure(focusAt, focusAt + 2 * COLUMNS);
        if (focusEpisode > 0) {   // only the first time: later the focus stays on the season pill
            focusEpisode = 0;
            cells.get(focusAt).getChildAt(0).requestFocus();
        }
    }

    /** Gives the cards from..to (and the row before) their stills, once each. */
    private void ensure(int from, int to) {
        for (int i = Math.max(0, from - COLUMNS); i <= to && i < items.size(); i++) {
            if (!requested.add(i)) continue;
            TraktClient.Item it = items.get(i);
            it.posterUrl = stills.get(i);
            ContinueCard.refreshCell(this, cells.get(i), it, captions.get(i));
        }
    }

    private TextView tick() {
        TextView t = new TextView(this);
        t.setText("✓");
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xB3000000);
        t.setBackground(g);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    private FrameLayout.LayoutParams tickParams() {
        int size = (int) (24 * density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        lp.setMargins(0, (int) (8 * density), (int) (8 * density), 0);
        return lp;
    }

    @Override
    protected void onDestroy() {
        if (exec != null) exec.shutdownNow();
        if (backdrop != null) backdrop.destroy();
        super.onDestroy();
    }

    private GradientDrawable focusFrame(float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x00000000);
        d.setCornerRadius(radiusDp * density);
        d.setStroke((int) ((pal.apple ? 2 : 3) * density), pal.apple ? pal.accent : ContinueCard.YELLOW);
        return d;
    }
}
