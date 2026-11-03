package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The cards of "Seguir viendo", shared by the Home row (ContinueRow) and the full screen list (ContinueWatchingActivity):
 * an image with a thin red progress bar and one line of text under it. Images are fetched on one background thread,
 * decoded at about 320px wide and kept in a small memory cache and in the cache directory. loadBitmap serves the full
 * screen backdrop of the list the same way, without the memory cache.
 */
final class ContinueCard {
    private ContinueCard() {}

    private static final int POSTER_WIDTH = 320;
    static final int YELLOW = 0xFFFCEE0A;
    private static final String FRAMED = "framed";

    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService FETCH = BgThreads.single("bg-io");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** A cell (vertical: card, then a caption) w x h px for the card; the focusable card is child 0. */
    static LinearLayout cell(Context ctx, Palette pal, TraktClient.Item item, int w, int h) {
        return cell(ctx, pal, item, w, h, ContinueRow.caption(item));
    }

    /** The same with this caption under the card. */
    static LinearLayout cell(Context ctx, Palette pal, TraktClient.Item item, int w, int h, String caption) {
        float density = ctx.getResources().getDisplayMetrics().density;
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        FrameLayout card = baseCard(ctx, pal, pal.cardFill, 12 * density);

        TextView fallback = new TextView(ctx);
        fallback.setText(item.title);
        fallback.setTextColor(pal.apple ? pal.primary : Color.WHITE);
        fallback.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        fallback.setTypeface(UiFont.get(ctx, R.string.font_medium));
        fallback.setGravity(Gravity.CENTER);
        fallback.setMaxLines(3);
        fallback.setPadding((int) (14 * density), 0, (int) (14 * density), 0);
        card.addView(fallback, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        PosterView poster = new PosterView(ctx, 12 * density);
        poster.setVisibility(View.GONE);
        poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(poster, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (item.progress > 0) {
            View bar = new View(ctx);
            bar.setBackgroundColor(pal.progress);
            int barW = Math.max((int) (3 * density), w * Math.min(100, item.progress) / 100);
            card.addView(bar, new FrameLayout.LayoutParams(barW, Math.max(2, (int) (3 * density)), Gravity.BOTTOM | Gravity.START));
        }
        loadPoster(ctx, wideArt(item), poster, fallback);

        cell.addView(card, new LinearLayout.LayoutParams(w, h));
        cell.addView(captionView(ctx, pal, caption), new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        String left = FollowLayout.leftLine(item);
        if (!left.isEmpty()) {
            TextView t = captionView(ctx, pal, left);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setTextColor(pal.apple ? pal.secondary : 0xCCFFFFFF);
            t.setPadding(t.getPaddingLeft(), (int) (1 * density), 0, 0);
            cell.addView(t, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return cell;
    }


    /** The wide art of a card: a movie's backdrop with its title when TMDB has one, else the scene. */
    static String wideArt(TraktClient.Item item) {
        boolean titled = "movie".equals(item.type) && item.titledArtUrl != null && !item.titledArtUrl.isEmpty();
        return titled ? item.titledArtUrl : item.posterUrl;
    }

    /** True when {@link #wideArt} is the backdrop with the title drawn in. */
    static boolean hasTitledArt(TraktClient.Item item) {
        return "movie".equals(item.type) && item.titledArtUrl != null && !item.titledArtUrl.isEmpty();
    }

    /** Corner radius (dp) of the Explorar boxes. */
    static final float BOX_RADIUS_DP = 10;

    /** A vertical box cover (2:3) w x h px, no caption (the cover art carries the title); the focusable card is child 0. */
    static LinearLayout boxCell(Context ctx, Palette pal, TraktClient.Item item, int w, int h) {
        float density = ctx.getResources().getDisplayMetrics().density;
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        FrameLayout card = baseCard(ctx, pal, pal.cardFill, BOX_RADIUS_DP * density);
        TextView fallback = new TextView(ctx);
        fallback.setText(item.title);
        fallback.setTextColor(pal.apple ? pal.primary : Color.WHITE);
        fallback.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        fallback.setTypeface(UiFont.get(ctx, R.string.font_medium));
        fallback.setGravity(Gravity.CENTER);
        fallback.setMaxLines(5);
        fallback.setPadding((int) (8 * density), 0, (int) (8 * density), 0);
        card.addView(fallback, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        PosterView poster = new PosterView(ctx, BOX_RADIUS_DP * density);
        poster.setVisibility(View.GONE);
        poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(poster, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadPoster(ctx, item.portraitUrl, poster, fallback, POSTER_WIDTH);
        cell.addView(card, new LinearLayout.LayoutParams(w, h));
        return cell;
    }

    /**
     * The big first element of Explorar, w x h px: the backdrop with a static dark gradient at the bottom, the title over
     * it and the year and "Serie" small under the title. Only the card itself (child 0 of a plain cell).
     */
    static LinearLayout featuredCell(Context ctx, Palette pal, TraktClient.Item item, int w, int h) {
        // Not the titled art: this card is wider than 16:9, so the crop would cut the title drawn in it.
        return featuredCell(ctx, pal, item, w, h, null, 0, item.posterUrl, true, null);
    }

    /** The same with this small line instead of "year · Serie" (null: the default) and a small icon before it (0: none). */
    static LinearLayout featuredCell(Context ctx, Palette pal, TraktClient.Item item, int w, int h, String subText, int subIcon) {
        return featuredCell(ctx, pal, item, w, h, subText, subIcon, item.posterUrl, true, null);
    }

    /**
     * The same with this art, the big title only when showTitle (the art may carry it) and, when extra is not empty, a third
     * small line under the small one.
     */
    static LinearLayout featuredCell(Context ctx, Palette pal, TraktClient.Item item, int w, int h, String subText, int subIcon,
                                     String artUrl, boolean showTitle, String extra) {
        float density = ctx.getResources().getDisplayMetrics().density;
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        FrameLayout card = baseCard(ctx, pal, pal.cardFill, BOX_RADIUS_DP * density);
        PosterView poster = new PosterView(ctx, BOX_RADIUS_DP * density);
        poster.setVisibility(View.GONE);
        poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(poster, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(ctx);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xE6000000});
        g.setCornerRadii(new float[]{0, 0, 0, 0, BOX_RADIUS_DP * density, BOX_RADIUS_DP * density, BOX_RADIUS_DP * density, BOX_RADIUS_DP * density});
        shade.setBackground(g);
        card.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h * 3 / 5, Gravity.BOTTOM));
        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding((int) (14 * density), 0, (int) (14 * density), (int) (12 * density));
        TextView title = new TextView(ctx);
        title.setText(item.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(UiFont.get(ctx, R.string.font_medium));
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // When the art carries the title, the text one stays until that art is on screen (it may never load).
        text.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView sub = new TextView(ctx);
        String kind = "show".equals(item.type) || "episode".equals(item.type) ? "Serie" : "Película";
        sub.setText(subText != null ? subText : item.year > 0 ? item.year + " · " + kind : kind);
        sub.setTextColor(0xCCFFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTypeface(UiFont.get(ctx, R.string.font_body));
        sub.setSingleLine(true);
        if (subIcon != 0) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.ImageView icon = new android.widget.ImageView(ctx);
            icon.setImageResource(subIcon);
            icon.setColorFilter(0xCCFFFFFF);
            int size = (int) (16 * density);
            line.addView(icon, new LinearLayout.LayoutParams(size, size));
            sub.setPadding((int) (6 * density), 0, 0, 0);
            line.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            text.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            text.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (extra != null && !extra.isEmpty()) {
            TextView more = new TextView(ctx);
            more.setText(extra);
            more.setTextColor(0xB3FFFFFF);
            more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            more.setTypeface(UiFont.get(ctx, R.string.font_body));
            more.setSingleLine(true);
            text.addView(more, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        card.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        loadPoster(ctx, artUrl, poster, showTitle ? new View(ctx) : title, 560);
        cell.addView(card, new LinearLayout.LayoutParams(w, h));
        return cell;
    }

    /** A cell made earlier whose item got its art and Spanish title later (the full watchlist fills them as they appear). */
    static void refreshCell(Context ctx, LinearLayout cell, TraktClient.Item item) {
        refreshCell(ctx, cell, item, ContinueRow.caption(item));
    }

    /** The same with this caption. */
    static void refreshCell(Context ctx, LinearLayout cell, TraktClient.Item item, String caption) {
        FrameLayout card = (FrameLayout) cell.getChildAt(0);
        ((TextView) card.getChildAt(0)).setText(item.title);
        ((TextView) cell.getChildAt(1)).setText(caption);
        loadPoster(ctx, wideArt(item), (PosterView) card.getChildAt(1), card.getChildAt(0));
    }

    /** Same as refreshCell for a boxCell (vertical cover, no caption). */
    static void refreshBoxCell(Context ctx, LinearLayout cell, TraktClient.Item item) {
        FrameLayout card = (FrameLayout) cell.getChildAt(0);
        ((TextView) card.getChildAt(0)).setText(item.title);
        loadPoster(ctx, item.portraitUrl, (PosterView) card.getChildAt(1), card.getChildAt(0));
    }

    static FrameLayout baseCard(Context ctx, Palette pal, int fill, float radius) {
        FrameLayout card = new FrameLayout(ctx);
        card.setId(R.id.inputTile);
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(radius);
        card.setBackground(bg);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        // No elevation and no translationZ: the card sits inside a panel, and the image is rounded by PosterView itself.
        if (pal.apple) card.setTag(FRAMED);
        if (pal.apple) bg.setStroke(Math.max(1, Math.round(ctx.getResources().getDisplayMetrics().density)), pal.border);
        return card;
    }

    /** True for a card that draws its own focus frame (the Apple-like looks); Home's floating glass stays away from it. */
    static boolean framed(View card) {
        return FRAMED.equals(card.getTag());
    }

    /**
     * The focus frame of a framed card: a 2dp accent stroke with the card's own 12dp radius, drawn as the card's foreground,
     * i.e. inside the card's bounds, on top of the image. It is scaled with the card by the focus zoom, so frame and image
     * cannot drift apart (the floating glass, placed from outside with its own size, did).
     */
    static void setFocusFrame(View card, boolean focused, Palette pal) {
        setFocusFrame(card, focused, pal, 12);
    }

    /** The same frame with this corner radius (dp). */
    static void setFocusFrame(View card, boolean focused, Palette pal, float radiusDp) {
        if (!framed(card)) return;
        FrameLayout f = (FrameLayout) card;
        if (!focused) {
            f.setForeground(null);
            return;
        }
        float density = card.getResources().getDisplayMetrics().density;
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x00000000);
        d.setCornerRadius(radiusDp * density);
        d.setStroke(Math.max(2, Math.round(2 * density)), pal.accent);
        f.setForeground(d);
    }

    private static TextView captionView(Context ctx, Palette pal, String text) {
        float density = ctx.getResources().getDisplayMetrics().density;
        TextView line = new TextView(ctx);
        line.setText(text);
        line.setTextColor(pal.apple ? pal.primary : pal.caption);
        line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        line.setTypeface(UiFont.get(ctx, R.string.font_body));
        line.setSingleLine(true);
        line.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.setPadding((int) (4 * density), (int) (6 * density), 0, 0);
        return line;
    }

    /**
     * The poster, centre-cropped and drawn with rounded corners by its own shader, so the rounding never depends on the
     * parent's outline clip (which did not hold on the TV once the card was scaled by the focus zoom).
     */
    static final class PosterView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF rect = new RectF();
        private final Matrix matrix = new Matrix();
        private final float radius;
        private Bitmap bitmap;
        private BitmapShader shader;

        PosterView(Context ctx, float radius) {
            super(ctx);
            this.radius = radius;
        }

        void setBitmap(Bitmap bmp) {
            bitmap = bmp;
            shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            paint.setShader(shader);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
            float s = Math.max(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
            matrix.setScale(s, s);
            matrix.postTranslate((getWidth() - bitmap.getWidth() * s) / 2f, (getHeight() - bitmap.getHeight() * s) / 2f);
            shader.setLocalMatrix(matrix);
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }

    // ---- posters ----------------------------------------------------------------------------------

    private static void loadPoster(Context ctx, String url, PosterView view, View fallback) {
        loadPoster(ctx, url, view, fallback, POSTER_WIDTH);
    }

    static void loadPoster(Context ctx, String url, PosterView view, View fallback, int width) {
        if (url == null || url.isEmpty() || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        view.setTag(url);
        Bitmap hit = MEMORY.get(url);
        if (hit != null) {
            show(view, fallback, hit);
            return;
        }
        final File dir = new File(ctx.getCacheDir(), "posters");
        final File file = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
        FETCH.execute(() -> {
            Bitmap bmp = null;
            try {
                if (!file.isFile() || file.length() == 0) download(url, dir, file);
                bmp = decode(file, width);
            } catch (IOException | RuntimeException ignored) {
                // no poster: the card keeps its title
            }
            final Bitmap result = bmp;
            if (result == null) return;
            MEMORY.put(url, result);
            MAIN.post(() -> {
                if (url.equals(view.getTag())) show(view, fallback, result);
            });
        });
    }

    /**
     * Fetches the image of this URL (cache directory first) and hands it to {@code done} on the main thread, decoded at
     * about {@code width} px; {@code done} gets null when it cannot be had. The caller owns the bitmap.
     */
    static void loadBitmap(Context ctx, String url, int width, java.util.function.Consumer<Bitmap> done) {
        final File dir = new File(ctx.getCacheDir(), "posters");
        final File file = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
        FETCH.execute(() -> {
            Bitmap bmp = null;
            try {
                if (!file.isFile() || file.length() == 0) download(url, dir, file);
                bmp = decode(file, width);
            } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
                // no backdrop: the screen keeps its plain background
            }
            final Bitmap result = bmp;
            MAIN.post(() -> done.accept(result));
        });
    }

    private static void show(PosterView view, View fallback, Bitmap bmp) {
        view.setBitmap(bmp);
        view.setVisibility(View.VISIBLE);
        fallback.setVisibility(View.GONE);
    }

    private static void download(String url, File dir, File file) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 4 * 1024 * 1024) throw new IOException("too large");
            }
            File tmp = new File(dir, file.getName() + ".tmp");
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                out.writeTo(fo);
            }
            if (!tmp.renameTo(file)) throw new IOException("rename");
        } finally {
            c.disconnect();
        }
    }

    private static Bitmap decode(File file, int width) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getPath(), o);
    }
}
