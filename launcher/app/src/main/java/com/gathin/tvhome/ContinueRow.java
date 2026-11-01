package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The panel of Home (config.xml continue_watching) inside the continueRow container: pill tabs, "Explorar", "Seguir viendo" and
 * (when there are Trakt lists) "Listas", centred above the framed panel. Explorar: the day's watchlist picks, a featured rectangle as wide as three box columns first, then
 * vertical box covers (2:3). Seguir viendo: a fixed vertical selector (movies / series) at the left of the panel, then up to 10 16:9
 * cards of that type, exactly three whole ones in view. A round "+ Ver más" button stays fixed at the right when there are more
 * titles. Listas: one wide card per Trakt list (its first title's backdrop, its name and size), OK opens the list. The row scrolls itself one cell at a time and its width is a whole number of columns, so no cell is ever cut and no
 * gap is left at either end. Cards take the focus like every other tile and draw their own focus frame; images are fetched on
 * one background thread (ContinueCard), decoded at a small size and cached.
 */
final class ContinueRow {

    interface Host {
        /** A card took the focus: move the highlight to it. */
        void cardFocused(View card, float zoom);

        void leaveUp(View card);

        void leaveDown(View card);

        void open(TraktClient.Item item);

        /** The "Ver más" button was chosen: open the full list of this type (TYPE_SERIES, TYPE_MOVIES or TYPE_WATCHLIST). */
        void openAll(String type);

        /** A title was hidden by hand: read the cache again (Hidden). */
        void hiddenChanged();
    }

    /** Side padding of the strip (room for the focus zoom) and the gaps between cells in each tab. */
    private static final int STRIP_PAD_DP = 12, FOLLOW_GAP_DP = 16, BOX_GAP_DP = 16;
    /**
     * Explorar shows between FEAT_COLS + 1 and EXPLORE_COLS_MAX box-cover columns (as many as keep the cover inside the panel's
     * height, see fit()), the featured rectangle taking the first 3 (and their gaps); Seguir viendo 3 cards.
     */
    private static final int EXPLORE_COLS_MAX = 7, FEAT_COLS = 3;
    /** Vertical room (dp) of the column around the covers: tabs and their margin 34, panel padding 8, strip padding 16. */
    /** Room under the strip for Explorar's page dots (every tab keeps it, so all have the same height). */
    private static final int DOTS_BAND_DP = 12;
    private static final int CHROME_DP = 34 + 8 + 16 + DOTS_BAND_DP;
    /** The round "Ver más" button, the width of the selector, and its round icon buttons. */
    private static final int MORE_BUTTON_DP = 56, SELECTOR_W_DP = 80, KIND_BUTTON_DP = 48;
    /** Width of the fixed "Ver más" slot (circle and its text). */
    private static final int MORE_CELL_DP = 72;
    private static final int TAB_EXPLORE = 0, TAB_FOLLOW = 1, TAB_LISTS = 2;
    static final String TYPE_SERIES = "episode", TYPE_MOVIES = "movie", TYPE_WATCHLIST = "watchlist";
    /** Most titles of one type in the row; more than that go behind "Ver más". */
    static final int MAX_SHOWN = 10;
    private static final int FADE_MS = 75;

    private final float zoom;
    private final Palette pal;
    private final Context ctx;
    private final ViewGroup container;
    private final float density;
    private final boolean animate;
    private final int zoomMs;
    private final Host host;
    private final LinearLayout cards, panel;
    /** Explorar's page dots, drawn over the bottom edge of the panel (no space of their own). */
    private final PageDots dots;
    /** Explorar: the cell (child of cards) each page scrolls to; fewer than 2 entries means no dots and no carousel. */
    private int[] pageStarts = new int[0];
    /** What to focus when Home comes back from a screen of ours (list, Ver más, series): see markReturn. */
    private boolean retPending, retFirst;
    private int retTab, retIndex;
    private String retKind, retKey;
    private final List<String> cellKeys = new ArrayList<>();
    private final FrameLayout moreSlot;
    private final HorizontalScrollView scroll;
    private final TextView tabExplore, tabFollow, tabLists;
    private final LinearLayout selector;
    private final ImageView iconMovies, iconSeries;
    private final TextView labelMovies, labelSeries;
    /** Sizes in px, set by layout(): Explorar box cover and featured. */
    private int boxW, boxH, featW;
    /** Tallest cover (px) the panel's height allows, from fit(); 0 until Home has measured it. */
    private int coverMax;
    /** Width in px of the area the panel gives its content (between its paddings). */
    private final int panelInnerW;
    private int first;   // index (child of cards) of the cell at the left edge of the visible area
    private android.animation.ValueAnimator scroller;
    /** The focusable cards and "Ver más" button of the current list, in order (the selector's icons are not here; the button is last). */
    private final List<View> cells = new ArrayList<>();
    private String signature = "";
    /** The day's random picks of the Trakt watchlist (Explorar) and the size of the whole watchlist. */
    private List<TraktClient.Item> watch = new ArrayList<>();
    private int watchTotal;
    /** The Trakt lists of the Listas tab. */
    private List<TraktClient.TraktList> lists = new ArrayList<>();
    private List<TraktClient.Item> all = new ArrayList<>();
    /** The tab and the type of Seguir viendo chosen (kept while Home lives); -1 / null until the first list arrives. */
    private int tab = -1;
    private String kind;
    /** True once the user picked a tab; until then Explorar wins as soon as it has something. */
    private boolean userChose;

    ContinueRow(Context ctx, Palette pal, ViewGroup container, boolean animate, int zoomMs, Host host) {
        this.ctx = ctx;
        this.pal = pal;
        this.zoom = pal.zoom();
        this.container = container;
        this.animate = animate;
        this.zoomMs = zoomMs;
        this.host = host;
        density = ctx.getResources().getDisplayMetrics().density;
        // Home's body has 56dp side margins and the panel 8dp more a side.
        panelInnerW = Math.round(ctx.getResources().getDisplayMetrics().widthPixels - (2 * 56 + 2 * 8) * density);
        // The tabs float above the framed panel, centred; the panel holds the fixed selector, the strip of cells (scrolls
        // inside it, clipped with rounded corners) and the fixed "Ver más" slot.
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        // No pal.lift: the elevation shadows of the two big panels cost ~20 ms of GPU per frame on the TV (measured), and
        // together with a non-layered aurora made every key press janky; the panel frame already has its own border.
        LinearLayout tabs = new LinearLayout(ctx);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabExplore = tab("Explorar", TAB_EXPLORE);
        tabFollow = tab("Seguir viendo", TAB_FOLLOW);
        tabLists = tab("Listas", TAB_LISTS);
        tabs.addView(tabExplore);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.setMarginStart((int) (8 * density));
        tabs.addView(tabFollow, flp);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.setMarginStart((int) (8 * density));
        tabs.addView(tabLists, llp);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER_HORIZONTAL;
        tlp.bottomMargin = (int) (6 * density);
        column.addView(tabs, tlp);
        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setPadding((int) (8 * density), (int) (4 * density), (int) (8 * density), (int) ((4 + DOTS_BAND_DP) * density));
        panel.setBackground(pal.panelFrame(density));
        FrameLayout panelHost = new FrameLayout(ctx);
        panelHost.setClipChildren(false);
        panelHost.setClipToPadding(false);
        panelHost.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dots = new PageDots(ctx, pal.accent, density);
        dots.setVisibility(View.GONE);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        // Centred in the space under the covers: the strip's 8 dp padding plus the panel's bottom padding, less the 8 dp dot.
        dlp.bottomMargin = Math.round((8 + 4 + DOTS_BAND_DP - 8) / 2f * density);
        panelHost.addView(dots, dlp);
        column.addView(panelHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll = new HorizontalScrollView(ctx) {
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                return 0;   // the row scrolls itself, one cell at a time (scrollTo)
            }
        };
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(false);
        scroll.setFocusable(false);
        final float clipRadius = 14 * density;
        scroll.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), clipRadius);
            }
        });
        scroll.setClipToOutline(true);
        cards = new LinearLayout(ctx);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setClipChildren(false);
        cards.setClipToPadding(false);   // default true: the strip clipped the zoomed card to its own padding box
        // Room for the zoom around the first and last cells (less than the gap, so a neighbour never shows in it).
        cards.setPadding((int) (STRIP_PAD_DP * density), (int) (8 * density), (int) (STRIP_PAD_DP * density), (int) (8 * density));
        scroll.addView(cards, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        moreSlot = new FrameLayout(ctx);
        moreSlot.setClipChildren(false);
        moreSlot.setClipToPadding(false);
        container.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        selector = new LinearLayout(ctx);
        selector.setOrientation(LinearLayout.VERTICAL);
        selector.setGravity(Gravity.CENTER);
        selector.setClipChildren(false);
        iconMovies = new ImageView(ctx);
        iconSeries = new ImageView(ctx);
        labelMovies = new TextView(ctx);
        labelSeries = new TextView(ctx);
        addKind(iconMovies, labelMovies, R.drawable.ic_kind_movie, "Pelis", TYPE_MOVIES, false);
        addKind(iconSeries, labelSeries, R.drawable.ic_kind_series, "Series", TYPE_SERIES, true);
        selector.setVisibility(View.GONE);
        panel.addView(selector, new LinearLayout.LayoutParams(Math.round(SELECTOR_W_DP * density), ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(panelInnerW, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(moreSlot, new LinearLayout.LayoutParams(Math.round(MORE_CELL_DP * density), ViewGroup.LayoutParams.WRAP_CONTENT));
        moreSlot.setVisibility(View.GONE);
    }

    /**
     * Width of one box cover (px) so that {@code cols} columns (cover + gap) fill {@code innerPx} exactly, the last without
     * its gap: the featured rectangle is FEAT_COLS columns wide, so the row is a grid and any window of cols columns is full.
     */
    static int boxWidth(int innerPx, int gapPx, int cols) {
        return (innerPx + gapPx) / cols - gapPx;
    }

    /**
     * The new first (left) cell for focusing cell {@code i}, from the cells' left and right edges: the row moves to the
     * nearest start of a cell so that cells first..i fit in {@code inner} px; nothing is ever cut at the left.
     */
    static int snapFirst(int first, int i, int[] left, int[] right, int inner) {
        if (i < first) return i;
        int want = first;
        while (want < i && right[i] - left[want] > inner) want++;
        return want;
    }

    /**
     * The first cell of each Explorar page: a page is the whole cells that fit in {@code inner} px from its first one, and no
     * cell is on two pages (a short last page keeps just its own).
     */
    static int[] pageStarts(int[] left, int[] right, int inner) {
        int n = left.length;
        if (n == 0) return new int[0];
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        int s = 0;
        while (true) {
            int j = s + 1;
            while (j < n && right[j] - left[s] <= inner) j++;
            if (j >= n) break;
            starts.add(j);
            s = j;
        }
        int[] out = new int[starts.size()];
        for (int k = 0; k < out.length; k++) out[k] = starts.get(k);
        return out;
    }

    /** The page showing the row when its first cell is {@code first}. */
    static int pageOf(int[] starts, int first) {
        int page = 0;
        for (int k = 0; k < starts.length; k++) if (starts[k] <= first) page = k;
        return page;
    }

    /** Small page dots: the current one a wider pill in the accent colour, the rest translucent grey. Only invalidates. */
    private static final class PageDots extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final int dot, wide, gap, on;
        private int count, cur;

        PageDots(Context c, int accent, float density) {
            super(c);
            dot = Math.round(8 * density);
            wide = Math.round(20 * density);
            gap = Math.round(10 * density);
            on = accent;
            setFocusable(false);
            setClickable(false);
        }

        void set(int pages, int page) {
            if (pages != count) {
                count = pages;
                requestLayout();
            }
            if (page != cur) {
                cur = page;
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(count <= 0 ? 0 : (count - 1) * (dot + gap) + wide, dot);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float x = 0;
            for (int i = 0; i < count; i++) {
                int w = i == cur ? wide : dot;
                r.set(x, 0, x + w, dot);
                paint.setColor(i == cur ? on : 0x66A0A0A0);
                canvas.drawRoundRect(r, dot / 2f, dot / 2f, paint);
                x += w + gap;
            }
        }
    }

    /**
     * Home gives the whole column (tabs and panel) {@code columnPx} of height, from under the date line to the bottom panel: the
     * covers, the featured rectangle and the cards are sized to fill it, whole columns wide, so the panel has no spare air.
     */
    void fit(int columnPx) {
        int max = columnPx - Math.round(CHROME_DP * density);
        if (max == coverMax) return;
        coverMax = max;
        if (!cells.isEmpty()) rebuild(true);
    }

    /**
     * Sizes the panel for the current tab: the selector (Seguir viendo only) and the "Ver más" slot (when there is more) take
     * their width off the strip, and the cells are sized so the strip holds whole columns: three cards (with their two gaps)
     * or seven box columns.
     */
    private void layout(boolean follow, boolean more) {
        int selW = follow ? Math.round(SELECTOR_W_DP * density) : 0;
        boolean slot = more && tab != TAB_EXPLORE;   // Explorar: "Ver más" is the strip's last cell, seen on the last page only
        int moreW = slot ? Math.round(MORE_CELL_DP * density) : 0;
        int pad = Math.round(STRIP_PAD_DP * density);
        int scrollW = panelInnerW - selW - moreW;
        int inner = scrollW - 2 * pad;
        int boxGap = Math.round(BOX_GAP_DP * density);
        int cols = EXPLORE_COLS_MAX;
        if (coverMax > 0) {
            for (cols = FEAT_COLS + 1; cols < EXPLORE_COLS_MAX; cols++) {
                if (boxWidth(inner, boxGap, cols) * 1.5f <= coverMax) break;
            }
        }
        boxW = boxWidth(inner, boxGap, cols);
        boxH = Math.round(boxW * 1.5f);
        featW = FEAT_COLS * boxW + (FEAT_COLS - 1) * boxGap;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) scroll.getLayoutParams();
        if (lp.width != scrollW) {
            lp.width = scrollW;
            scroll.setLayoutParams(lp);
        }
        // Both tabs have the same height: the tallest cover Home allows (before it is measured: the Explorar one with its slot).
        int refBox = coverMax > 0 ? coverMax
                : Math.round(boxWidth(panelInnerW - Math.round(MORE_CELL_DP * density) - 2 * pad, boxGap, EXPLORE_COLS_MAX) * 1.5f);
        cards.setMinimumHeight(refBox + (int) (16 * density));
        cards.setGravity(Gravity.CENTER_VERTICAL);
        selector.setVisibility(follow ? View.VISIBLE : View.GONE);
        moreSlot.setVisibility(slot ? View.VISIBLE : View.GONE);
    }

    boolean hasCards() {
        return !cells.isEmpty();
    }

    boolean isShown() {
        return container.getVisibility() == View.VISIBLE && hasCards();
    }

    /** The newest {@code max} items of this type, newest first. The list itself is not changed. */
    static List<TraktClient.Item> filterByType(List<TraktClient.Item> all, String type, int max) {
        List<TraktClient.Item> sorted = new ArrayList<>();
        if (all != null) {
            for (TraktClient.Item i : all) if (type.equals(TYPE_SERIES) == "episode".equals(i.type)) sorted.add(i);
        }
        Collections.sort(sorted, (x, y) -> y.updatedAt.compareTo(x.updatedAt));
        return sorted.size() > max ? new ArrayList<>(sorted.subList(0, max)) : sorted;
    }

    private static int count(List<TraktClient.Item> all, String type) {
        return filterByType(all, type, Integer.MAX_VALUE).size();
    }

    /** Shows the row for this list; an empty list hides the row. Does nothing when nothing changed. */
    void show(List<TraktClient.Item> items) {
        all = items == null ? new ArrayList<TraktClient.Item>() : items;
        update();
    }

    /** The Explorar picks (movies and series) and how many titles the whole watchlist has. */
    void showWatchlist(List<TraktClient.Item> picks, int total) {
        watch = picks == null ? new ArrayList<TraktClient.Item>() : picks;
        watchTotal = Math.max(total, watch.size());
        update();
    }

    /** The Trakt lists (with their items); none hides the Listas tab. */
    void showLists(List<TraktClient.TraktList> found) {
        lists = new ArrayList<>();
        // A list with no visible title (all hidden, unreleased or without TMDB id) has nothing to open.
        if (found != null) for (TraktClient.TraktList l : found) if (!l.items.isEmpty()) lists.add(l);
        update();
    }

    /** The list cards: one pseudo item per list (type "list": title = name, episode = size, episodeTitle = kind, showTraktId = id). */
    private List<TraktClient.Item> listCards() {
        List<TraktClient.Item> out = new ArrayList<>();
        for (TraktClient.TraktList l : lists) {
            TraktClient.Item it = new TraktClient.Item();
            it.type = "list";
            it.title = l.name;
            it.showTraktId = l.id;
            it.episode = l.items.size();
            it.episodeTitle = TraktClient.listKind(l.items);
            if (!l.items.isEmpty()) it.posterUrl = l.items.get(0).posterUrl;
            out.add(it);
        }
        return out;
    }

    private void update() {
        boolean hasMovies = count(all, TYPE_MOVIES) > 0, hasSeries = count(all, TYPE_SERIES) > 0;
        boolean hasFollow = hasMovies || hasSeries, hasExplore = !watch.isEmpty();
        tabExplore.setVisibility(hasExplore ? View.VISIBLE : View.GONE);
        tabFollow.setVisibility(hasFollow ? View.VISIBLE : View.GONE);
        boolean hasLists = !lists.isEmpty();
        tabLists.setVisibility(hasLists ? View.VISIBLE : View.GONE);
        boolean valid = tab == TAB_FOLLOW ? hasFollow : tab == TAB_LISTS ? hasLists : tab == TAB_EXPLORE && hasExplore;
        if (valid && tab == TAB_FOLLOW && !userChose && hasExplore) valid = false;
        if (!valid) tab = hasExplore ? TAB_EXPLORE : hasFollow ? TAB_FOLLOW : hasLists ? TAB_LISTS : -1;
        if (kind == null || count(all, kind) == 0) {
            // The type of the most recent title.
            String newest = null, at = "";
            for (TraktClient.Item i : all) {
                if (newest == null || i.updatedAt.compareTo(at) > 0) {
                    newest = "episode".equals(i.type) ? TYPE_SERIES : TYPE_MOVIES;
                    at = i.updatedAt;
                }
            }
            kind = newest;
        }
        styleTabs();
        styleKinds();
        rebuild(false);
    }

    private void rebuild(boolean force) {
        boolean follow = tab == TAB_FOLLOW;
        boolean listTab = tab == TAB_LISTS;
        List<TraktClient.Item> list = tab < 0 ? new ArrayList<TraktClient.Item>() : follow ? filterByType(all, kind, MAX_SHOWN)
                : listTab ? listCards() : watch;
        if (follow && list.size() > 4) list = new ArrayList<>(list.subList(0, 4));   // the featured card and three rows
        int total = tab < 0 || listTab ? 0 : follow ? count(all, kind) : watchTotal;
        boolean more = total > list.size();
        StringBuilder sig = new StringBuilder().append(tab).append(follow ? kind : "").append('\n');
        for (TraktClient.Item i : list) {
            sig.append(i.type).append('|').append(i.title).append('|').append(i.year).append('|').append(i.season).append('|')
                    .append(i.episode).append('|').append(i.progress).append('|').append(i.posterUrl).append('|')
                    .append(i.portraitUrl).append('|').append(i.runtime).append('|').append(i.titledArtUrl).append('\n');
        }
        if (more) sig.append("more|").append(total);
        if (!force && sig.toString().equals(signature) && (list.isEmpty() == !isShown())) return;
        signature = sig.toString();
        // A card that had the focus is about to go (a title was hidden, new art came in): give it to the card in its place.
        int focusedAt = -1;
        for (int k = 0; k < cells.size(); k++) if (cells.get(k).hasFocus()) focusedAt = k;
        // The selector stays (and keeps the focus) while the cards behind it change.
        cards.removeAllViews();
        moreSlot.removeAllViews();
        cells.clear();
        cellKeys.clear();
        if (scroller != null) scroller.cancel();
        first = 0;
        scroll.scrollTo(0, 0);
        layout(follow && !list.isEmpty(), more);
        int gap = Math.round((follow ? FOLLOW_GAP_DP : BOX_GAP_DP) * density);
        float radius = follow ? 12 : ContinueCard.BOX_RADIUS_DP;
        // A list card is as tall as the featured one and two of them fill the strip exactly (no cell is ever cut).
        int listW = (panelInnerW - 2 * Math.round(STRIP_PAD_DP * density) - gap) / 2;
        if (follow) buildFollow(list);
        for (int i = 0; !follow && i < list.size(); i++) {
            TraktClient.Item item = list.get(i);
            LinearLayout cell = listTab ? ContinueCard.featuredCell(ctx, pal, item, listW, boxH,
                            item.episode + (item.episode == 1 ? " título" : " títulos"), kindIcon(item.episodeTitle))
                    : i == 0 ? ContinueCard.featuredCell(ctx, pal, item, featW, boxH)
                    : ContinueCard.boxCell(ctx, pal, item, boxW, boxH);
            View card = listTab ? listCard(item, i, cell, radius) : card(item, i, cell, radius);
            cells.add(card);
            cellKeys.add(keyFor(item));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(gap);
            cards.addView((View) card.getParent(), lp);
        }
        if (more) {
            View button = moreButton(total, list.size(), radius);
            cells.add(button);
            if (tab == TAB_EXPLORE) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(boxW, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (!list.isEmpty()) lp.setMarginStart(gap);
                cards.addView((View) button.getParent(), lp);
            } else {
                moreSlot.addView((View) button.getParent(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            }
        }
        container.setVisibility(cells.isEmpty() ? View.GONE : View.VISIBLE);
        pageStarts = new int[0];
        if (tab == TAB_EXPLORE) {
            // From the sizes, not the views: the cells have not been laid out yet.
            int n = cards.getChildCount();   // the covers and, when there is more, the "Ver más" cell (one cover wide)
            int[] left = new int[n], right = new int[n];
            int x = 0;
            for (int i = 0; i < n; i++) {
                left[i] = x;
                right[i] = x + (i == 0 ? featW : boxW);
                x = right[i] + gap;
            }
            pageStarts = pageStarts(left, right, scrollWidthInner());
        }
        updateDots();
        if (focusedAt >= 0 && !cells.isEmpty()) cells.get(Math.min(focusedAt, cells.size() - 1)).requestFocus();
    }

    /** A big featured card and the next three as rows; no scrolling, the rest is behind "Ver más". */
    private void buildFollow(List<TraktClient.Item> list) {
        if (list.isEmpty()) return;
        int inner = ((LinearLayout.LayoutParams) scroll.getLayoutParams()).width - 2 * Math.round(STRIP_PAD_DP * density);   // set by layout()
        int gap = Math.round(FOLLOW_GAP_DP * density), rowGap = Math.round(8 * density);
        int availH = coverMax > 0 ? coverMax : boxH;
        int fw = Math.min(Math.round(availH * 16f / 9f), Math.round(inner * 0.62f)), fh = Math.round(fw * 9f / 16f);
        int colW = inner - fw - gap, rowH = (fh - 2 * rowGap) / 3;
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setClipChildren(false);
        wrap.setClipToPadding(false);
        LinearLayout feat = FollowLayout.featured(ctx, pal, list.get(0), fw, fh);
        cells.add(card(list.get(0), 0, feat, ContinueCard.BOX_RADIUS_DP));
        cellKeys.add(keyFor(list.get(0)));
        wrap.addView(feat, new LinearLayout.LayoutParams(fw, fh));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        final int rows = list.size() - 1;
        for (int r = 1; r <= rows; r++) {
            LinearLayout cell = FollowLayout.row(ctx, pal, list.get(r), colW, rowH);
            cells.add(card(list.get(r), r, cell, 10));
            cellKeys.add(keyFor(list.get(r)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(colW, rowH);
            if (r > 1) lp.topMargin = rowGap;
            col.addView(cell, lp);
        }
        if (rows > 0) {
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(colW, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMarginStart(gap);
            wrap.addView(col, clp);
        }
        cards.addView(wrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Keys: Right from the featured card goes to the first row, from a row to "Ver más"; Up/Down walk the rows; Left goes back.
        for (int k = 0; k <= rows; k++) {
            final int at = k;
            cells.get(k).setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                View moreBtn = cells.size() > rows + 1 ? cells.get(rows + 1) : null;   // "Ver más" is registered after the rows
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (at > 0) cells.get(0).requestFocus();
                        else if (selector.getVisibility() == View.VISIBLE) (TYPE_SERIES.equals(kind) ? iconSeries : iconMovies).requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (at == 0 && rows > 0) cells.get(1).requestFocus();
                        else if (moreBtn != null) moreBtn.requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (at > 1) cells.get(at - 1).requestFocus();
                        else activeTab().requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (at >= 1 && at < rows) cells.get(at + 1).requestFocus();
                        else host.leaveDown(v);
                        return true;
                    default:
                        return false;
                }
            });
        }
    }

    private int scrollWidthInner() {
        boolean more = moreSlot.getVisibility() == View.VISIBLE;
        return panelInnerW - (more ? Math.round(MORE_CELL_DP * density) : 0) - 2 * Math.round(STRIP_PAD_DP * density);
    }

    private void updateDots() {
        boolean show = tab == TAB_EXPLORE && pageStarts.length > 1;
        dots.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) dots.set(pageStarts.length, pageOf(pageStarts, first));
    }

    /** True when Explorar is showing with more than one page: the carousel has something to turn. */
    boolean canTurn() {
        return isShown() && tab == TAB_EXPLORE && pageStarts.length > 1;
    }

    /**
     * Carousel step: scrolls Explorar to its next page (the first after the last). A focus inside the strip goes along to the
     * first card of the page; anywhere else (a tab, the "Ver más" button, another panel) it is left where it is.
     */
    void nextPage() {
        if (!canTurn() || turning || (fader != null && fader.isRunning())) return;
        int target = pageStarts[(pageOf(pageStarts, first) + 1) % pageStarts.length];
        boolean inStrip = false;
        for (int k = 0; k < cards.getChildCount() && k < cells.size(); k++) if (cells.get(k).hasFocus()) inStrip = true;
        final boolean moveFocus = inStrip;
        Runnable jump = () -> {
            scrollToCell(target, null, true);
            if (moveFocus && target < cells.size()) cells.get(target).requestFocus();
        };
        if (!animate) {
            jump.run();
            return;
        }
        // The page fades out, the next one is put in place unseen and fades in: one layer for the strip, only while it fades.
        turning = true;
        scroll.animate().cancel();
        scroll.animate().alpha(0f).setDuration(220).withLayer().withEndAction(() -> {
            jump.run();
            scroll.animate().alpha(1f).setDuration(280).withLayer().withEndAction(() -> turning = false).start();
        }).start();
    }

    /** The carousel's fade is running: a second turn waits for it. */
    private boolean turning;

    // ---- coming back from a screen of ours ---------------------------------------------------------

    private static String keyFor(TraktClient.Item it) {
        return "list".equals(it.type) ? "list:" + it.showTraktId : it.type + ":" + (it.tmdbId > 0 ? String.valueOf(it.tmdbId) : it.title);
    }

    /**
     * Remembers where to put the focus when Home is back (a list opened, "Ver más", a series): the card, or the first one of the row.
     * Home restores it with restoreReturn(); the Home button of the remote clears it (clearReturn).
     */
    private void markReturn(TraktClient.Item item, int index, boolean firstOfRow) {
        retPending = true;
        retFirst = firstOfRow;
        retTab = tab;
        retKind = kind;
        retIndex = index;
        retKey = item == null ? null : keyFor(item);
    }

    void clearReturn() {
        retPending = false;
    }

    /**
     * Gives the focus back to what markReturn saved: the same card (or the one now in its place when it is gone), the first card
     * for "Ver más", the active tab when the row has no card left. False when there is nothing to restore or the panel is empty.
     */
    boolean restoreReturn() {
        if (!retPending) return false;
        retPending = false;
        if (!isShown()) return false;
        boolean other = tab != retTab || (retTab == TAB_FOLLOW && retKind != null && !retKind.equals(kind));
        boolean tabOk = retTab == TAB_EXPLORE ? tabExplore.getVisibility() == View.VISIBLE
                : retTab == TAB_FOLLOW ? tabFollow.getVisibility() == View.VISIBLE : tabLists.getVisibility() == View.VISIBLE;
        if (other && tabOk) {
            if (fader != null) fader.cancel();
            tab = retTab;
            if (retTab == TAB_FOLLOW && retKind != null && count(all, retKind) > 0) kind = retKind;
            styleTabs();
            styleKinds();
            rebuild(true);
        }
        int n = tab == TAB_FOLLOW ? cellKeys.size() : cards.getChildCount();   // Seguir viendo has one child
        View target = null;
        if (n > 0 && tab == retTab) {
            int at = 0;
            if (!retFirst) {
                at = Math.min(retIndex, n - 1);
                for (int k = 0; k < n && k < cellKeys.size(); k++) if (cellKeys.get(k).equals(retKey)) at = k;
            }
            target = cells.get(at);
        }
        if (target == null) target = activeTab();
        target.requestFocus();
        return true;
    }

    private android.animation.ValueAnimator fader;

    private void setCardsAlpha(float a) {
        for (int k = 0; k < cards.getChildCount(); k++) cards.getChildAt(k).setAlpha(a);
        moreSlot.setAlpha(a);
    }

    /** After a tab or type change: the cards (never the selector) fade out, are rebuilt and fade in (~150 ms in all). */
    private void transition() {
        styleTabs();
        styleKinds();
        if (fader != null) fader.cancel();
        if (!animate || !isShown()) {
            rebuild(true);
            return;
        }
        fader = android.animation.ValueAnimator.ofFloat(1f, 0f);
        fader.setDuration(FADE_MS);
        fader.addUpdateListener(a -> setCardsAlpha((Float) a.getAnimatedValue()));
        fader.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
                setCardsAlpha(1f);
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled) return;
                rebuild(true);
                setCardsAlpha(0f);
                fader = android.animation.ValueAnimator.ofFloat(0f, 1f);
                fader.setDuration(FADE_MS);
                fader.addUpdateListener(u -> setCardsAlpha((Float) u.getAnimatedValue()));
                fader.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationCancel(android.animation.Animator u) {
                        setCardsAlpha(1f);
                    }
                });
                fader.start();
            }
        });
        fader.start();
    }

    private void selectTab(int which) {
        userChose = true;
        if (which == tab) return;
        tab = which;
        transition();
    }

    private void selectKind(String type) {
        if (type.equals(kind) || count(all, type) == 0) return;
        kind = type;
        transition();
    }

    private static int kindIcon(String kind) {
        return "movie".equals(kind) ? R.drawable.ic_kind_movie : "series".equals(kind) ? R.drawable.ic_kind_series : 0;
    }

    private TextView tab(String label, int which) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(UiFont.get(ctx, R.string.font_medium));
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (16 * density), (int) (5 * density), (int) (16 * density), (int) (5 * density));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(v -> selectTab(which));
        t.setOnFocusChangeListener((v, focused) -> {
            if (focused) selectTab(which);
            styleTabs();
        });
        t.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    neighbourTab((TextView) v, -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    neighbourTab((TextView) v, 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    host.leaveUp(v);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    focusFirst();
                    return true;
                default:
                    return false;
            }
        });
        return t;
    }

    /** DOWN from a tab: the selector's icon when the row is at its start, else the first card in view. */
    private void focusFirst() {
        if (cells.isEmpty()) return;
        if (selector.getVisibility() == View.VISIBLE && first == 0) {
            (TYPE_SERIES.equals(kind) ? iconSeries : iconMovies).requestFocus();
            return;
        }
        cells.get(Math.min(cells.size() - 1, first)).requestFocus();
    }

    /** Moves the focus to the next visible tab in this direction; stays on the last one. */
    private void neighbourTab(TextView from, int dir) {
        TextView[] order = {tabExplore, tabFollow, tabLists};
        int i = from == tabExplore ? 0 : from == tabFollow ? 1 : 2;
        for (i += dir; i >= 0 && i < order.length; i += dir) {
            if (order[i].getVisibility() == View.VISIBLE) {
                order[i].requestFocus();
                return;
            }
        }
    }

    /** Selected: filled (accent, or a soft white/black); the other: text only. Focused: a ring in the accent colour. */
    private void styleTabs() {
        for (TextView t : new TextView[]{tabExplore, tabFollow, tabLists}) {
            boolean sel = (t == tabFollow ? TAB_FOLLOW : t == tabLists ? TAB_LISTS : TAB_EXPLORE) == tab;
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(20 * density);
            if (sel) g.setColor(pal.apple ? (pal.dark ? 0x26FFFFFF : 0x14000000) : pal.accent);
            else g.setColor(0x00000000);
            if (t.isFocused()) g.setStroke(Math.round(2 * density), pal.accent);
            t.setBackground(g);
            t.setTextColor(sel ? pal.primary : pal.secondary);
        }
    }

    private View activeTab() {
        if (tab == TAB_EXPLORE && tabExplore.getVisibility() == View.VISIBLE) return tabExplore;
        if (tab == TAB_LISTS && tabLists.getVisibility() == View.VISIBLE) return tabLists;
        return tabFollow.getVisibility() == View.VISIBLE ? tabFollow : tabExplore;
    }

    // ---- the selector of Seguir viendo ----------------------------------------------------------------

    private void addKind(ImageView icon, TextView label, int drawable, String text, String type, boolean second) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (12 * density);
        icon.setImageResource(drawable);
        icon.setPadding(pad, pad, pad, pad);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setFocusable(true);
        icon.setClickable(true);
        icon.setContentDescription(text);
        icon.setOnClickListener(v -> selectKind(type));
        icon.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                selectKind(type);
                reveal(selector, v);
            }
            styleKinds();
        });
        icon.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            ImageView other = icon == iconMovies ? iconSeries : iconMovies;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (!cells.isEmpty()) cells.get(Math.min(first, cells.size() - 1)).requestFocus();   // the first card in view
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (second && other.isFocusable()) other.requestFocus();
                    else activeTab().requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (!second && other.isFocusable()) other.requestFocus();
                    else host.leaveDown(v);
                    return true;
                default:
                    return false;
            }
        });
        int size = (int) (KIND_BUTTON_DP * density);
        col.addView(icon, new LinearLayout.LayoutParams(size, size));
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(UiFont.get(ctx, R.string.font_medium));
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, (int) (2 * density), 0, 0);
        col.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (second) lp.topMargin = (int) (10 * density);
        selector.addView(col, lp);
    }

    /** Selected icon: soft accent fill; a type with nothing in it: dimmed and not focusable; focused: accent ring. */
    private void styleKinds() {
        for (ImageView icon : new ImageView[]{iconMovies, iconSeries}) {
            String type = icon == iconMovies ? TYPE_MOVIES : TYPE_SERIES;
            boolean has = count(all, type) > 0, sel = type.equals(kind);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(sel ? (pal.accent & 0xFFFFFF) | 0x59000000 : 0x00000000);
            if (icon.isFocused()) g.setStroke(Math.round(2 * density), pal.accent);
            else g.setStroke(Math.max(1, Math.round(density)), pal.border);
            icon.setBackground(g);
            icon.setColorFilter(sel ? pal.primary : pal.secondary);
            icon.setFocusable(has);
            icon.setClickable(has);
            ((View) icon.getParent()).setAlpha(has ? 1f : 0.35f);
            (icon == iconMovies ? labelMovies : labelSeries).setTextColor(sel ? pal.primary : pal.secondary);
        }
    }

    /** The card whose centre is nearest to this x on the screen, or null without cards. */
    View nearestCard(float screenX) {
        View best = null;
        float bestD = Float.MAX_VALUE;
        int[] loc = new int[2];
        for (View c : cells) {
            c.getLocationOnScreen(loc);
            float d = Math.abs(loc[0] + c.getWidth() / 2f - screenX);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    private View card(TraktClient.Item item, int index, LinearLayout cell, float radiusDp) {
        View card = cell.getChildAt(0);
        card.setOnClickListener(v -> {
            markReturn(item, index, false);   // back from Kodi (Home button or Back): this card again
            host.open(item);
        });
        // Long press on a series: its seasons and episodes.
        if (SeriesActivity.canOpen(item)) card.setOnLongClickListener(v -> {
            markReturn(item, index, false);
            SeriesActivity.start(ctx, item);
            return true;
        });
        // Long press on a movie of Explorar: offer to hide it.
        else if (tab == TAB_EXPLORE && "movie".equals(item.type) && item.tmdbId > 0 && ctx instanceof Activity) {
            card.setOnLongClickListener(v -> {
                HideDialog.show((Activity) ctx, pal, item.title, () -> {
                    Hidden.add(ctx, Hidden.keyOf(item));
                    host.hiddenChanged();
                });
                return true;
            });
        }
        wire(card, cell, index, radiusDp);
        return card;
    }

    /** A card of the Listas tab: OK opens the list as a grid of covers. */
    private View listCard(TraktClient.Item item, int index, LinearLayout cell, float radiusDp) {
        View card = cell.getChildAt(0);
        card.setOnClickListener(v -> {
            markReturn(item, index, false);
            ctx.startActivity(new Intent(ctx, ContinueWatchingActivity.class)
                    .putExtra("type", "list").putExtra("list_id", item.showTraktId));
        });
        wire(card, cell, index, radiusDp);
        return card;
    }

    /**
     * The end of the row: a 56dp round "+" button with "Ver más" and "N títulos" under it, the three centred in the height of
     * the row's images (the text is outside the circle).
     */
    private View moreButton(int total, int index, float radiusDp) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setClipChildren(false);
        int size = (int) (MORE_BUTTON_DP * density);
        TextView button = new TextView(ctx);
        button.setText("+");
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        button.setTypeface(UiFont.get(ctx, R.string.font_medium));
        button.setTextColor(pal.ink());
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setBackground(pal.buttonBackground(density, MORE_BUTTON_DP / 2f));
        if (pal.apple) pal.lift(button, 3, density);
        button.setFocusable(true);
        button.setClickable(true);
        button.setContentDescription("Ver más, " + total + " títulos");
        button.setOnClickListener(v -> {
            markReturn(null, 0, true);
            host.openAll(tab == TAB_FOLLOW ? kind : TYPE_WATCHLIST);
        });
        cell.addView(button, new LinearLayout.LayoutParams(size, size));
        TextView title = new TextView(ctx);
        title.setText("Ver más");
        title.setTextColor(pal.apple ? pal.primary : ContinueCard.YELLOW);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(UiFont.get(ctx, R.string.font_medium));
        title.setSingleLine(true);
        title.setPadding(0, (int) (8 * density), 0, 0);
        cell.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView count = new TextView(ctx);
        count.setText(total + (total == 1 ? " título" : " títulos"));
        count.setTextColor(pal.secondary);
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        count.setTypeface(UiFont.get(ctx, R.string.font_body));
        count.setSingleLine(true);
        cell.addView(count, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wire(button, cell, index, radiusDp);
        return button;
    }

    private void wire(View card, View cell, int index, float radiusDp) {
        card.setOnFocusChangeListener((v, focused) -> {
            v.animate().cancel();
            float scale = focused ? zoom : 1f;
            float lift = 0f;   // no translationZ: the card is rounded by its own drawing and sits flat in its panel
            if (animate) v.animate().scaleX(scale).scaleY(scale).translationZ(lift).setDuration(zoomMs).setInterpolator(new DecelerateInterpolator()).start();
            else {
                v.setTranslationZ(lift);
                v.setScaleX(scale);
                v.setScaleY(scale);
            }
            ContinueCard.setFocusFrame(v, focused, pal, radiusDp);
            if (!focused) return;
            reveal(cell, v);
            if (v.getId() != R.id.inputTile) return;   // the round button has its own look, no floating highlight
            if (ContinueCard.framed(v)) host.cardFocused(v, zoom);   // no floating glass: the card carries its own frame
            else if (v.getWidth() == 0) v.post(() -> { if (v.isFocused()) host.cardFocused(v, zoom); });
            else host.cardFocused(v, zoom);
        });
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (index > 0) cells.get(index - 1).requestFocus();
                    else if (selector.getVisibility() == View.VISIBLE) (TYPE_SERIES.equals(kind) ? iconSeries : iconMovies).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (index < cells.size() - 1) cells.get(index + 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    activeTab().requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    host.leaveDown(v);
                    return true;
                default:
                    return false;
            }
        });
    }

    /**
     * Keeps the focused cell whole: the row moves one cell at a time, so the left edge of the visible area is always the start
     * of a cell (snapFirst) and, being a whole number of columns wide, the right edge is the end of one: no cell is cut and no
     * gap is left, at the end of the row either. Animated unless animations are off; the highlight is placed again when it ends.
     */
    private void reveal(View card, View focusView) {
        View cell = card;
        while (cell.getParent() instanceof View && cell.getParent() != cards) cell = (View) cell.getParent();
        int i = cards.indexOfChild(cell);
        if (i < 0) return;   // the selector and the "Ver más" slot do not scroll
        int n = cards.getChildCount();
        int[] left = new int[n], right = new int[n];
        for (int k = 0; k < n; k++) {
            left[k] = cards.getChildAt(k).getLeft();
            right[k] = cards.getChildAt(k).getRight();
        }
        int pad = Math.round(STRIP_PAD_DP * density);
        // Explorar turns whole pages: past the last cell of a page the next one comes in at once, all of it.
        int want = tab == TAB_EXPLORE && pageStarts.length > 0 ? pageStarts[pageOf(pageStarts, i)]
                : snapFirst(first, i, left, right, scroll.getWidth() - 2 * pad);
        if (want == first) return;
        scrollToCell(want, focusView);
    }

    /** Scrolls the strip so that cell {@code want} is its first (animated unless animations are off) and moves the dots. */
    private void scrollToCell(int want, View focusView) {
        scrollToCell(want, focusView, false);
    }

    private void scrollToCell(int want, View focusView, boolean instant) {
        if (want < 0 || want >= cards.getChildCount()) return;
        first = want;
        updateDots();
        int to = Math.max(0, cards.getChildAt(first).getLeft() - Math.round(STRIP_PAD_DP * density));
        if (scroller != null) scroller.cancel();
        if (!animate || instant) {
            scroll.scrollTo(to, 0);
            return;
        }
        scroller = android.animation.ValueAnimator.ofInt(scroll.getScrollX(), to);
        scroller.setDuration(250);
        scroller.setInterpolator(new DecelerateInterpolator());
        scroller.addUpdateListener(a -> scroll.scrollTo((Integer) a.getAnimatedValue(), 0));
        scroller.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled && focusView != null && focusView.isFocused() && focusView.getId() == R.id.inputTile
                        && !ContinueCard.framed(focusView)) {
                    host.cardFocused(focusView, zoom);
                }
            }
        });
        scroller.start();
    }

    static String caption(TraktClient.Item item) {
        if ("episode".equals(item.type)) {
            // The episode's own title reads better than the series name (the still already says which series).
            String name = item.episodeTitle != null && !item.episodeTitle.isEmpty() ? item.episodeTitle
                    : item.title != null && !item.title.isEmpty() ? item.title : "Serie";
            return item.season > 0 || item.episode > 0 ? "T" + item.season + " E" + item.episode + " · " + name : name;
        }
        if ("show".equals(item.type)) return (item.title != null && !item.title.isEmpty() ? item.title : "Serie") + " · Serie";
        String t = item.title != null && !item.title.isEmpty() ? item.title : "Película";
        return item.year > 0 ? t + " (" + item.year + ")" : t;
    }
}
