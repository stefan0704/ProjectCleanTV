package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * ambient_motion: three very soft colour blobs behind Home that drift for tens of seconds. Each blob is drawn once into a
 * 128 px bitmap (a radial gradient) and stretched over about 900 dp by an ImageView; only its
 * translation changes, a few times a second, so nothing is drawn per frame and there is no live blur.
 */
final class Aurora {
    private static final int BITMAP_PX = 128;
    private static final float SIZE_DP = 900f;
    private static final float ALPHA = 0.22f;

    /** The drift moves in steps this far apart: every step repaints the screen behind and above the blobs, which a TV GPU
     *  feels at 60 fps (measured: 30% CPU, every frame janky), while 4 steps a second of a couple of pixels are invisible. */
    private static final long STEP_MS = 250;

    private final List<View> views = new ArrayList<>();
    private final List<Blob> blobs = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private final float half;
    private final boolean moving;
    /** Drift time spent so far; it only counts while running, so a resume goes on from where it stopped. */
    private long elapsed;
    private long lastTick;
    private boolean running;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            elapsed += now - lastTick;
            lastTick = now;
            place();
            if (running) handler.postDelayed(this, STEP_MS);
        }
    };

    /** One blob: colour, resting centre (dp from the top left of the screen) and the centre it drifts to, with its period (ms). */
    private static final class Blob {
        final int color;
        final float x0, y0, x1, y1;
        final long periodX, periodY;

        Blob(int color, float x0, float y0, float x1, float y1, long periodX, long periodY) {
            this.color = color;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.periodX = periodX;
            this.periodY = periodY;
        }
    }

    private static final Blob[] BLOBS = {
            new Blob(0xFF0A84FF, 230f, 130f, 520f, 300f, 47_000L, 61_000L),    // blue, upper left
            new Blob(0xFF5E5CE6, 760f, 400f, 520f, 170f, 58_000L, 43_000L),    // violet, right
            new Blob(0xFFFF6B5A, 420f, 560f, 700f, 470f, 67_000L, 53_000L),    // warm touch, bottom
    };

    /** Fills {@code host} (a FrameLayout) with the blobs; {@code animate} false leaves them still at their resting place. */
    Aurora(Context context, FrameLayout host, boolean animate) {
        // One hardware layer for all three blobs: the quads are composed once per drift step (4 a second) instead of every frame.
        host.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        density = context.getResources().getDisplayMetrics().density;
        int size = Math.round(SIZE_DP * density);
        half = size / 2f;
        moving = animate;
        for (Blob b : BLOBS) {
            ImageView v = new ImageView(context);
            v.setImageBitmap(blobBitmap(b.color));
            v.setScaleType(ImageView.ScaleType.FIT_XY);
            v.setAlpha(ALPHA);
            // No hardware layer: on the TV's GPU a layer per blob doubled the frame time while navigating (27-31 ms against
            // 15 ms); the plain bitmap quads cost nothing measurable.
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            host.addView(v, new FrameLayout.LayoutParams(size, size));
            views.add(v);
            blobs.add(b);
        }
        place();
    }

    /** Smoothstep along a there-and-back path: 0 to 1 over one period, then back to 0 over the next. */
    private static float ease(long t, long period) {
        float phase = (t % (2 * period)) / (float) period;
        float p = phase <= 1f ? phase : 2f - phase;
        return p * p * (3f - 2f * p);
    }

    private void place() {
        for (int i = 0; i < views.size(); i++) {
            Blob b = blobs.get(i);
            View v = views.get(i);
            v.setTranslationX((b.x0 + (b.x1 - b.x0) * ease(elapsed, b.periodX)) * density - half);
            v.setTranslationY((b.y0 + (b.y1 - b.y0) * ease(elapsed, b.periodY)) * density - half);
        }
    }

    /** Starts the drift, or continues it from where pause() stopped it. */
    void resume() {
        if (!moving || running) return;
        running = true;
        lastTick = SystemClock.elapsedRealtime();
        handler.postDelayed(tick, STEP_MS);
    }

    void pause() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(tick);
        elapsed += SystemClock.elapsedRealtime() - lastTick;
    }

    private static Bitmap blobBitmap(int color) {
        Bitmap bmp = Bitmap.createBitmap(BITMAP_PX, BITMAP_PX, Bitmap.Config.ARGB_8888);
        float r = BITMAP_PX / 2f;
        int rgb = color & 0xFFFFFF;
        // Many stops on a smooth fall-off, so the edge dissolves without a visible ring.
        int[] colors = new int[6];
        float[] stops = {0f, 0.2f, 0.45f, 0.7f, 0.88f, 1f};
        float[] alphas = {1f, 0.8f, 0.45f, 0.18f, 0.04f, 0f};
        for (int i = 0; i < colors.length; i++) colors[i] = (Math.round(alphas[i] * 255) << 24) | rgb;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(r, r, r, colors, stops, Shader.TileMode.CLAMP));
        new Canvas(bmp).drawCircle(r, r, r, p);
        return bmp;
    }
}
