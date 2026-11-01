package com.gathin.tvhome;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.TextView;

/**
 * neon_focus for the top-bar buttons: the focused pill becomes a thin outline in the accent colour with a faint glow
 * inside its edge, as plain drawables built once (no animation, nothing recalculated per frame). Tiles use GlassView's
 * neon style instead.
 */
final class Neon {
    private Neon() {}

    /** Used when the theme has no accent_color. */
    static final int DEFAULT_COLOR = 0xFF39E6FF;

    /** The accent_color of the theme as opaque ARGB, or {@link #DEFAULT_COLOR}. */
    static int color(int accentColor) {
        return accentColor == 0 ? DEFAULT_COLOR : 0xFF000000 | accentColor;
    }

    /** Gives a pill the neon focus look; its normal look stays as it is. The text stays white, since the fill is dark. */
    static void pill(TextView pill, int color, float density, boolean tintIcon) {
        pill(pill, color, density, tintIcon, 0xD9101018, Color.WHITE);
    }

    /** The same with the focused fill and the text/icon colour chosen (light_ui uses a white tube and dark ink). */
    static void pill(TextView pill, int color, float density, boolean tintIcon, int tubeFill, int ink) {
        Drawable normal = pill.getBackground();
        if (normal == null) return;
        int rgb = color & 0xFFFFFF;
        float radius = 21f * density;

        GradientDrawable glow = new GradientDrawable();
        glow.setCornerRadius(radius);
        glow.setStroke(Math.round(6f * density), 0x38000000 | rgb);

        GradientDrawable tube = new GradientDrawable();
        tube.setCornerRadius(radius - density);
        tube.setColor(tubeFill);
        tube.setStroke(Math.round(2.5f * density), 0xFF000000 | rgb);

        LayerDrawable focused = new LayerDrawable(new Drawable[]{glow, tube});
        int inset = Math.round(density);
        focused.setLayerInset(1, inset, inset, inset, inset);

        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, focused);
        background.addState(new int[]{}, normal);
        pill.setBackground(background);
        pill.setTextColor(ink);
        if (tintIcon) pill.setCompoundDrawableTintList(ColorStateList.valueOf(ink));
    }
}
