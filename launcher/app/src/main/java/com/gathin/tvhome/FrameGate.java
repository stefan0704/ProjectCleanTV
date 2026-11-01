package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * Cold start: waits until the window has really been drawn once and the UI thread is keeping up (several frames in a
 * row without a skipped one), or a maximum wait, and then runs the intro. Right after power-on the system is busy and
 * an intro started in onResume would play unseen before the first frame.
 */
final class FrameGate implements Choreographer.FrameCallback, ViewTreeObserver.OnDrawListener {
    /** SystemClock.elapsedRealtime() of the first frame this process drew; 0 until then. */
    static long firstDrawAt;

    private final View view;
    private final Runnable go;
    private final int calmFrames;
    private final long maxWaitMs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable giveUp = this::finish;
    private final long periodNs;
    private boolean drawn, done;
    private long drawnAt, lastNanos;
    private int calm;

    private FrameGate(View view, int calmFrames, long maxWaitMs, Runnable go) {
        this.view = view;
        this.calmFrames = calmFrames;
        this.maxWaitMs = maxWaitMs;
        this.go = go;
        float hz = view.getDisplay() != null ? view.getDisplay().getRefreshRate() : 60f;
        periodNs = (long) (1_000_000_000L / (hz > 20f ? hz : 60f));
    }

    /** Records the process's first drawn frame (for the deferral of background work). */
    static void watchFirstDraw(View view) {
        if (firstDrawAt != 0) return;
        view.getViewTreeObserver().addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
            @Override
            public void onDraw() {
                if (firstDrawAt == 0) firstDrawAt = SystemClock.elapsedRealtime();
                view.post(() -> view.getViewTreeObserver().removeOnDrawListener(this));
            }
        });
    }

    static FrameGate start(View view, int calmFrames, long maxWaitMs, Runnable go) {
        FrameGate g = new FrameGate(view, calmFrames, maxWaitMs, go);
        view.getViewTreeObserver().addOnDrawListener(g);
        view.invalidate();
        Choreographer.getInstance().postFrameCallback(g);
        g.handler.postDelayed(g.giveUp, 3000 + maxWaitMs);   // never drawn at all: go anyway
        return g;
    }

    void cancel() {
        done = true;
        handler.removeCallbacks(giveUp);
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    public void onDraw() {
        if (drawn) return;
        drawn = true;
        drawnAt = SystemClock.elapsedRealtime();
        view.post(() -> view.getViewTreeObserver().removeOnDrawListener(this));
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (done) return;
        if (drawn) {
            if (lastNanos != 0) calm = frameTimeNanos - lastNanos <= periodNs * 22 / 10 ? calm + 1 : 0;
            lastNanos = frameTimeNanos;
            if (calm >= calmFrames || SystemClock.elapsedRealtime() - drawnAt >= maxWaitMs) {
                finish();
                return;
            }
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void finish() {
        if (done) return;
        cancel();
        go.run();
    }
}
