package com.gathin.tvhome;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.function.Supplier;

/**
 * config video_wallpaper: a silent video looping behind Home, on one full-screen SurfaceView at the bottom of the root (the
 * video is composited by the system, so Home does not redraw its whole window for every video frame as it does with a
 * TextureView: measured 30 % against 3 % CPU on the TV). The MediaPlayer exists only while Home is in front (start / stop):
 * it is released the moment Home pauses, so Kodi, HDMI or a streaming app get the hardware decoder, and rebuilt on resume.
 * A SurfaceView punches a hole in the window, black until the first frame is out, so the theme's wallpaper picture (the
 * video's first frame, already dark) is laid over it until then and again whenever the player is released: no black flash.
 * A static dark scrim over the video keeps the text readable.
 */
final class VideoWallpaper implements SurfaceHolder.Callback {
    private static final String TAG = "TvHome.Video";
    /** The scrim over the video: 35 % black. */
    private static final int SCRIM = 0x59000000;

    private final SurfaceView view;
    private final View scrim;
    private final ImageView poster;
    private final Supplier<Drawable> picture;
    private final File file;
    private SurfaceHolder holder;
    private MediaPlayer player;
    private boolean active;

    VideoWallpaper(Context context, FrameLayout root, File file, Supplier<Drawable> picture) {
        this.file = file;
        this.picture = picture;
        view = new SurfaceView(context);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setFocusable(false);
        view.getHolder().addCallback(this);
        scrim = new View(context);
        scrim.setBackgroundColor(SCRIM);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        poster = new ImageView(context);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Just above the wallpaper picture (the root's first child when there is one), under the rest of Home.
        int at = root.getChildAt(0) instanceof ImageView ? 1 : 0;
        // Order bottom to top: the video, its scrim, the poster; everything else of Home stays above.
        root.addView(poster, at, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(scrim, at, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(view, at, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Home is in front: play (as soon as the surface exists). */
    void start() {
        active = true;
        if (poster.getVisibility() == View.VISIBLE && poster.getDrawable() == null) poster.setImageDrawable(picture.get());
        play();
    }

    /** Home is in front but the video waits (the intro is playing): only the poster shows. */
    void showPoster() {
        if (poster.getVisibility() == View.VISIBLE && poster.getDrawable() == null) poster.setImageDrawable(picture.get());
    }

    /** Home is not in front, or is gone: free the decoder. */
    void stop() {
        active = false;
        release();
    }

    private void play() {
        if (!active || player != null || holder == null) return;
        MediaPlayer p = new MediaPlayer();
        player = p;
        try {
            p.setDataSource(file.getPath());
            p.setDisplay(holder);
            p.setLooping(true);
            p.setVolume(0f, 0f);
            p.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            p.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && player == mp) show(true);
                return false;
            });
            p.setOnPreparedListener(mp -> {
                if (player == mp && active) mp.start();
            });
            p.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "video wallpaper failed: " + what + "/" + extra);
                if (player == mp) release();
                return true;
            });
            p.prepareAsync();
        } catch (Exception | Error e) {
            Log.w(TAG, "video wallpaper not played: " + e);
            release();
        }
    }

    private void release() {
        MediaPlayer p = player;
        player = null;
        show(false);
        if (p == null) return;
        try {
            p.release();
        } catch (Exception ignored) {
            // already gone
        }
    }

    /** The video in view (the poster is dropped, with its memory), or the poster over it again. */
    private void show(boolean on) {
        if (on) {
            poster.setVisibility(View.GONE);
            poster.setImageDrawable(null);
        } else if (poster.getVisibility() != View.VISIBLE) {
            poster.setImageDrawable(picture.get());
            poster.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        play();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        release();
        holder = null;
    }
}
