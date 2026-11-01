package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * The full screen background of ContinueWatchingActivity: the big image of the focused title, centre-cropped under a
 * static dark scrim (no blur). show() waits 200 ms so a fast scroll loads nothing, then fetches the image (ContinueCard's
 * download and cache directory, decoded at about 1280px) and crossfades to it over 250 ms between two ImageViews. At most
 * two bitmaps are alive; a bitmap that is no longer shown is recycled.
 */
final class Backdrop extends FrameLayout {
    private static final int WIDTH = 1280, DEBOUNCE_MS = 200, FADE_MS = 250;

    private final boolean animate;
    private final ImageView[] views = new ImageView[2];
    private final Bitmap[] bitmaps = new Bitmap[2];
    private final View scrim;
    /** The view that is fully shown, or -1; and the one fading in over it, or -1. */
    private int current = -1, fadingIn = -1;
    private String wanted, shownUrl;
    private boolean destroyed;
    private Runnable pending;

    Backdrop(Context ctx, boolean animate) {
        super(ctx);
        this.animate = animate;
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(ctx);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(v, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            views[i] = v;
        }
        // Darker at the bottom-left, where the titles and the first cards are, and 45% at the top.
        GradientDrawable down = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x73000000, 0x99000000});
        GradientDrawable right = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x59000000, 0x00000000});
        scrim = new View(ctx);
        scrim.setBackground(new LayerDrawable(new GradientDrawable[]{down, right}));
        scrim.setAlpha(0f);
        addView(scrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** The image to show for the focused title (null: none); the change happens after the debounce. */
    void show(String url) {
        if (destroyed) return;
        removeCallbacks(pending);
        wanted = url;
        if (url == null) {
            clear();
            return;
        }
        if (url.equals(shownUrl)) return;
        pending = () -> load(url);
        postDelayed(pending, DEBOUNCE_MS);
    }

    private void load(String url) {
        // Cards carry a 780px image; the same TMDB path at 1280 is the backdrop. Any other URL is used as it is.
        String big = url.replace("/w780/", "/w1280/");
        ContinueCard.loadBitmap(getContext(), big, WIDTH, bmp -> {
            if (bmp == null && !big.equals(url)) {
                ContinueCard.loadBitmap(getContext(), url, WIDTH, again -> arrived(url, again));
            } else arrived(url, bmp);
        });
    }

    private void arrived(String url, Bitmap bmp) {
        if (bmp == null) return;
        if (destroyed || !url.equals(wanted)) {
            bmp.recycle();   // the focus moved on while it loaded
            return;
        }
        shownUrl = url;
        int slot = fadingIn >= 0 ? fadingIn : current >= 0 ? 1 - current : 0;
        ImageView view = views[slot];
        view.animate().cancel();
        release(slot);
        bitmaps[slot] = bmp;
        view.setImageBitmap(bmp);
        if (fadingIn < 0) {
            view.setAlpha(0f);
            view.bringToFront();
            scrim.bringToFront();
        }
        if (!animate) {
            // No motion: the new image replaces the old one at once.
            view.setAlpha(1f);
            scrim.setAlpha(1f);
            if (current >= 0 && current != slot) release(current);
            current = slot;
            fadingIn = -1;
            return;
        }
        fadingIn = slot;
        scrim.animate().cancel();
        scrim.animate().alpha(1f).setDuration(FADE_MS).start();
        view.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
            if (current >= 0 && current != slot) release(current);
            current = slot;
            fadingIn = -1;
        }).start();
    }

    /** Nothing to show: both images go away at once. */
    private void clear() {
        shownUrl = null;
        for (int i = 0; i < 2; i++) {
            views[i].animate().cancel();
            views[i].setAlpha(0f);
            release(i);
        }
        scrim.animate().cancel();
        scrim.setAlpha(0f);
        current = fadingIn = -1;
    }

    private void release(int slot) {
        views[slot].setImageDrawable(null);
        if (bitmaps[slot] != null) {
            bitmaps[slot].recycle();
            bitmaps[slot] = null;
        }
    }

    /** The screen is closing: stop the pending load and free the bitmaps. */
    void destroy() {
        destroyed = true;
        removeCallbacks(pending);
        clear();
    }
}
