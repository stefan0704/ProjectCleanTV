package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Background of the bottom_panel: dark translucent fill, a 1dp border in the accent at about 35% alpha, 12dp corners and
 * the top-right corner cut in a diagonal. The path is built once per size, not per frame.
 */
final class PanelFrameDrawable extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float radius, cut, stroke;
    /** light_ui: a plain rounded rectangle (no cut corner) whose outline casts a soft static shadow. */
    private final boolean rounded;

    /** The light panel: a plain 20dp rounded rectangle with this fill and hairline border (1dp). */
    PanelFrameDrawable(float density, int fillArgb, int borderArgb) {
        rounded = true;
        radius = 20f * density;
        cut = 0f;
        stroke = Math.max(1f, density);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(fillArgb);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(stroke);
        border.setColor(borderArgb);
    }

    PanelFrameDrawable(int accentRgb, float density) {
        rounded = false;
        radius = 12f * density;
        cut = 14f * density;
        stroke = Math.max(1f, density);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xB3141420);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(stroke);
        border.setColor((0x59 << 24) | (accentRgb & 0xFFFFFF));   // 0x59 = 35%
    }

    @Override
    protected void onBoundsChange(Rect b) {
        float h = stroke / 2f;
        RectF r = new RectF(b.left + h, b.top + h, b.right - h, b.bottom - h);
        path.reset();
        if (rounded) {
            path.addRoundRect(r, radius, radius, Path.Direction.CW);
            return;
        }
        path.moveTo(r.left + radius, r.top);
        path.lineTo(r.right - cut, r.top);
        path.lineTo(r.right, r.top + cut);               // the diagonal cut
        path.lineTo(r.right, r.bottom - radius);
        path.arcTo(r.right - 2 * radius, r.bottom - 2 * radius, r.right, r.bottom, 0, 90, false);
        path.lineTo(r.left + radius, r.bottom);
        path.arcTo(r.left, r.bottom - 2 * radius, r.left + 2 * radius, r.bottom, 90, 90, false);
        path.lineTo(r.left, r.top + radius);
        path.arcTo(r.left, r.top, r.left + 2 * radius, r.top + 2 * radius, 180, 90, false);
        path.close();
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawPath(path, fill);
        canvas.drawPath(path, border);
    }

    @Override
    public void getOutline(Outline outline) {
        outline.setRoundRect(getBounds(), radius);
        outline.setAlpha(rounded ? 0.6f : 0f);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
