package com.gathin.tvhome;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The colours of the launcher's own UI in one place (config.xml / theme light_ui, dark_ui). CYBER (neither) holds exactly
 * the values the code had before, so the Cyberpunk look does not change; LIGHT is the white, Apple-like minimal look:
 * near-black text, translucent white surfaces with a hairline border and a static shadow (elevation, no live blur),
 * and a thin accent frame for the focus; DARK is the same design in the dark tvOS palette. {@link #apple} is true for
 * both (the layout: rounded panels, Title Case, no neon); {@link #dark} only picks the colours.
 */
final class Palette {
    static final int LIGHT_ACCENT = 0xFF0A84FF;
    private static final int INK = 0xFF1D1D1F, INK_SECONDARY = 0xFF6E6E73;

    enum Style { CYBER, LIGHT, DARK }

    final Style style;
    /** LIGHT or DARK: the Apple-like layout. */
    final boolean apple;
    /** DARK: the Apple-like layout in the dark palette. */
    final boolean dark;
    /** Solid fill of a focused pill or list row, and the base colour of popups; the hairline of separators and the clock divider. */
    final int focusFill, popupBase, separator;
    /** Main and secondary text. */
    final int primary, secondary;
    /** The focus frame, the progress bar and the "Ver más" mark; opaque ARGB. */
    final int accent;
    /** Section titles: sources, apps, continue watching, and the full screen list's two sections. */
    final int titleSources, titleApps, titleContinue, titleSeries, titleMovies;
    /** The fill behind icon tiles (sources) and behind the continue cards. */
    final int tileFill, cardFill, moreFill;
    /** Caption under a continue card. */
    final int caption;
    /** Full screen background of the "Seguir viendo" list. */
    final int screenBg;
    /** Progress bar of a continue card. */
    final int progress;
    /** Panel fill and its hairline border, and the divider between the panel's halves. */
    final int surface, border, divider;

    private Palette(Style style, int accentColor) {
        this.style = style;
        this.apple = style != Style.CYBER;
        this.dark = style == Style.DARK;
        if (apple) {
            accent = accentColor == 0 ? LIGHT_ACCENT : 0xFF000000 | accentColor;
            progress = accent;
            if (dark) {
                primary = 0xFFF5F5F7;
                secondary = 0xFF98989D;
                titleSources = titleApps = titleContinue = titleSeries = titleMovies = primary;
                tileFill = cardFill = moreFill = 0xFF3A3A3C;
                caption = 0xFFE5E5EA;
                screenBg = 0xFF1C1C1E;
                surface = 0xD92C2C2E;
                border = 0x1FFFFFFF;
                divider = 0x1FFFFFFF;
                focusFill = 0xFF48484A;
                popupBase = 0x2C2C2E;
                separator = 0x1FFFFFFF;
            } else {
                primary = INK;
                secondary = INK_SECONDARY;
                titleSources = titleApps = titleContinue = titleSeries = titleMovies = INK;
                tileFill = cardFill = moreFill = 0xFFFFFFFF;
                caption = INK;
                screenBg = 0xFFF5F5F7;
                surface = 0xB8FFFFFF;
                border = 0x14000000;
                divider = 0x1F000000;
                focusFill = 0xFFFFFFFF;
                popupBase = 0xFFFFFF;
                separator = 0x1F000000;
            }
        } else {
            focusFill = 0xD9101018;
            popupBase = 0x101018;
            separator = 0x1FFFFFFF;
            primary = Color.WHITE;
            secondary = 0xCCFFFFFF;
            accent = Neon.color(accentColor);
            titleSources = 0xFF00F0FF;
            titleApps = 0xFFFF003C;
            titleContinue = 0xFFFCEE0A;
            titleSeries = 0xFF00F0FF;
            titleMovies = 0xFFFF003C;
            tileFill = 0xFF1C1C1E;
            cardFill = 0xFF1A1A26;
            moreFill = 0xFF12121C;
            caption = 0xCCFFFFFF;
            screenBg = 0xFF0B0B12;
            progress = 0xFFFF003C;
            surface = 0xB3141420;
            border = (0x59 << 24) | (accent & 0xFFFFFF);
            divider = (0x4D << 24) | (accent & 0xFFFFFF);
        }
    }

    static Palette of(Config cfg) {
        return new Palette(cfg.darkUi ? Style.DARK : cfg.lightUi ? Style.LIGHT : Style.CYBER, cfg.accentColor);
    }

    /** Focus zoom of cards and tiles in the panel and the continue row. */
    float zoom() {
        return apple ? 1.06f : 1.05f;
    }

    /** A section title: Apple-like gets normal Title Case at 15sp, medium weight; cyber keeps its small spaced capitals. */
    void styleTitle(TextView t, int darkColor) {
        if (!apple) {
            t.setTextColor(darkColor);
            return;
        }
        t.setTextColor(primary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setAllCaps(false);
        t.setLetterSpacing(0f);
        t.setTypeface(UiFont.get(t.getContext(), R.string.font_medium));
    }

    /** Resting elevation of light surfaces: a static shadow, drawn by the GPU once per layer, never blurred live. */
    void lift(View v, float dp, float density) {
        if (apple) v.setElevation(dp * density);
    }

    /** The background of the Home panels (bottom panel, continue row): the rounded Apple-like surface or the cyber accent frame. */
    Drawable panelFrame(float density) {
        return apple ? new PanelFrameDrawable(density, surface, border) : new PanelFrameDrawable(accent & 0xFFFFFF, density);
    }

    /** Translucent white with the hairline border. */
    GradientDrawable surfaceDrawable(float density, float radiusDp, int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusDp * density);
        d.setStroke(Math.max(1, Math.round(density)), border);
        return d;
    }

    /** The fill of a popup or the remote's input panel in the Apple-like looks: the surface base colour at this alpha (0..255). */
    int popup(int alpha) {
        return (alpha << 24) | popupBase;
    }

    /** The background of a dropdown or the remote's input panel in the Apple-like looks: 20dp corners. */
    Drawable panelBackground(float density, int fill) {
        return surfaceDrawable(density, 20, fill);
    }

    /** A header button: surface with the hairline at rest, solid white with the accent frame when focused. */
    Drawable pillBackground(float density) {
        return pillBackground(density, 21);
    }

    /** The same button with this corner radius (dp): half its size makes a circle. */
    Drawable pillBackground(float density, float radiusDp) {
        float r = radiusDp * density;
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(focusFill);
        focused.setCornerRadius(r);
        focused.setStroke(Math.round(2 * density), accent);
        GradientDrawable active = new GradientDrawable();
        active.setColor(focusFill);
        active.setCornerRadius(r);
        active.setStroke(Math.round(1.5f * density), accent);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_activated}, active);
        s.addState(new int[]{}, surfaceDrawable(density, radiusDp, surface));
        return s;
    }

    /** A round button: the header pill in the Apple-like looks, an accent outline in the cyber one; this corner radius (dp). */
    Drawable buttonBackground(float density, float radiusDp) {
        if (apple) return pillBackground(density, radiusDp);
        float r = radiusDp * density;
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(focusFill);
        focused.setCornerRadius(r);
        focused.setStroke(Math.round(2.5f * density), accent);
        GradientDrawable rest = new GradientDrawable();
        rest.setColor(0x00000000);
        rest.setCornerRadius(r);
        rest.setStroke(Math.max(1, Math.round(density)), border);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, rest);
        return s;
    }

    /** A list row of a dropdown: nothing at rest, white with the accent frame when focused. */
    Drawable rowBackground(float density) {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(focusFill);
        focused.setCornerRadius(13 * density);
        focused.setStroke(Math.round(2 * density), accent);
        GradientDrawable rest = new GradientDrawable();
        rest.setColor(0x00000000);
        rest.setCornerRadius(13 * density);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, rest);
        return s;
    }

    /** The icon tint and text colour of the header buttons (the same focused or not: the focused fill is white too). */
    ColorStateList ink() {
        return ColorStateList.valueOf(primary);
    }

    /**
     * Apple-like only: recolours a dropdown after it has been built. White texts become the primary colour (the dimmed
     * ones the secondary), tinted icons the primary, and focusable rows get the Apple look. Cyber is left alone.
     */
    void skinTree(View v, float density) {
        if (!apple) return;
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            int c = t.getTextColors().getDefaultColor();
            if ((c & 0xFFFFFF) == 0xFFFFFF) t.setTextColor(Color.alpha(c) >= 0xE0 ? primary : secondary);
        } else if (v instanceof ImageView) {
            ImageView i = (ImageView) v;
            if (i.getImageTintList() != null) i.setImageTintList(ink());
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            if (g.isFocusable() && g.isClickable()) g.setBackground(rowBackground(density));
            for (int i = 0; i < g.getChildCount(); i++) skinTree(g.getChildAt(i), density);
        }
    }
}
