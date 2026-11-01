package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

// The Seguir viendo layout: a big featured card and a column of rows beside it.
final class FollowLayout {
    private FollowLayout() {}

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static TextView text(Context ctx, String s, int sp, int color, boolean medium, int maxLines) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(UiFont.get(ctx, medium ? R.string.font_medium : R.string.font_body));
        t.setMaxLines(maxLines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static int main(Palette pal) {
        return pal.apple ? pal.primary : Color.WHITE;
    }

    private static boolean isEpisode(TraktClient.Item it) {
        return "episode".equals(it.type);
    }

    /** "T1 E12" for an episode, the year for a movie ("" when unknown). */
    static String numberLine(TraktClient.Item it) {
        if (isEpisode(it)) return it.season > 0 || it.episode > 0 ? "T" + it.season + " E" + it.episode : "";
        return it.year > 0 ? String.valueOf(it.year) : "";
    }

    /** The episode's own title, or "" (a movie has none). */
    static String episodeName(TraktClient.Item it) {
        return isEpisode(it) && it.episodeTitle != null ? it.episodeTitle : "";
    }

    /** "T1 E12 · title" for an episode, the year for a movie. */
    static String subLine(TraktClient.Item it) {
        String n = numberLine(it), e = episodeName(it);
        if (n.isEmpty()) return e;
        return e.isEmpty() ? n : n + " · " + e;
    }

    /** "Quedan 21 min", or "" when the runtime is unknown or the title was not started. */
    static String leftLine(TraktClient.Item it) {
        if (it.progress <= 0) return "";
        int m = Tmdb.minutesLeft(it.runtime, it.progress);
        return m > 0 ? "Quedan " + m + " min" : "";
    }

    private static void bar(Context ctx, Palette pal, TraktClient.Item it, int w, FrameLayout card) {
        float d = ctx.getResources().getDisplayMetrics().density;
        if (it.progress <= 0) return;
        View bar = new View(ctx);
        bar.setBackgroundColor(pal.progress);
        int barW = Math.max((int) (3 * d), w * Math.min(100, it.progress) / 100);
        card.addView(bar, new FrameLayout.LayoutParams(barW, Math.max(2, (int) (3 * d)), Gravity.BOTTOM | Gravity.START));
    }

    private static ContinueCard.PosterView poster(Context ctx, FrameLayout into, float radius) {
        ContinueCard.PosterView p = new ContinueCard.PosterView(ctx, radius);
        p.setVisibility(View.GONE);
        p.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        into.addView(p, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return p;
    }

    private static TextView fallback(Context ctx, Palette pal, TraktClient.Item it, FrameLayout into, int sp) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView f = text(ctx, it.title, sp, main(pal), true, 3);
        f.setGravity(Gravity.CENTER);
        f.setPadding((int) (6 * d), 0, (int) (6 * d), 0);
        into.addView(f, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return f;
    }

    /** The featured card w x h px: the backdrop, title and "T1 E12 · episode" over a gradient, the progress bar on the edge. */
    static LinearLayout featured(Context ctx, Palette pal, TraktClient.Item it, int w, int h) {
        String sub = subLine(it);
        LinearLayout cell = ContinueCard.featuredCell(ctx, pal, it, w, h, sub.isEmpty() ? "Película" : sub, 0,
                ContinueCard.wideArt(it), !ContinueCard.hasTitledArt(it), leftLine(it));
        bar(ctx, pal, it, w, (FrameLayout) cell.getChildAt(0));
        return cell;
    }

    /** One row w x h px: a small 16:9 thumbnail and two lines; the whole row is the focusable child 0. */
    static LinearLayout row(Context ctx, Palette pal, TraktClient.Item it, int w, int h) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        FrameLayout card = ContinueCard.baseCard(ctx, pal, pal.cardFill, 10 * d);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (6 * d);
        row.setPadding(pad, pad, pad, pad);
        int thumbW = Math.min(Math.round((h - 2 * pad) * 16f / 9f), Math.round(w * 0.45f)), thumbH = Math.round(thumbW * 9f / 16f);
        FrameLayout img = new FrameLayout(ctx);
        TextView fb = fallback(ctx, pal, it, img, 9);
        ContinueCard.PosterView p = poster(ctx, img, 6 * d);
        ContinueCard.loadPoster(ctx, ContinueCard.wideArt(it), p, fb, 320);
        row.addView(img, new LinearLayout.LayoutParams(thumbW, thumbH));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(text(ctx, it.title, 13, main(pal), true, 1), wrap());
        String left = leftLine(it);
        col.addView(text(ctx, subLine(it), 11, pal.secondary, false, left.isEmpty() ? 2 : 1), wrap());
        if (!left.isEmpty()) col.addView(text(ctx, left, 11, pal.secondary, false, 1), wrap());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.setMarginStart((int) (10 * d));
        row.addView(col, clp);
        card.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cell.addView(card, new LinearLayout.LayoutParams(w, h));
        return cell;
    }
}
