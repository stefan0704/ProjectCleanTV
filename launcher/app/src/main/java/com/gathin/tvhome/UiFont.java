package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

/**
 * The typeface behind one of the font_* strings in res/values/fonts.xml, for views built in code. The string
 * points at a bundled font, or, in locales Outfit can't draw (values-vi), names a system family (#833).
 *
 * <p>ui_font ("orbitron") swaps the whole UI to another bundled font: {@link #install} puts a view factory on the
 * activity's inflater that sets the typeface once on every TextView as it is created (no work afterwards), and
 * {@link #get} hands out the same face to views built in code.
 */
final class UiFont {
    private UiFont() {}

    static final String ORBITRON = "orbitron";

    /** The ui_font face, or null to keep the fonts the layouts and font_* strings name. */
    private static Typeface override;

    /** True for a ui_font value this build knows. */
    static boolean known(String name) {
        return ORBITRON.equals(name);
    }

    /** Reads ui_font; call before the first layout is inflated. */
    static void configure(Context context, String name) {
        override = null;
        if (!known(name)) return;
        try {
            override = context.getResources().getFont(R.font.orbitron);
        } catch (Resources.NotFoundException ignored) {
            // keep Outfit
        }
    }

    /** Makes every view this activity inflates from now on use the ui_font face. No-op without one. */
    static void install(Activity activity) {
        if (override == null) return;
        final LayoutInflater inflater = activity.getLayoutInflater();
        if (inflater.getFactory2() != null) return;
        inflater.setFactory2(new LayoutInflater.Factory2() {
            private final String[] prefixes = {"android.widget.", "android.view.", "android.webkit."};

            @Override
            public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
                View view = null;
                try {
                    if (name.indexOf('.') >= 0) {
                        view = inflater.createView(name, null, attrs);
                    } else {
                        for (String prefix : prefixes) {
                            try {
                                view = inflater.createView(name, prefix, attrs);
                                break;
                            } catch (ClassNotFoundException ignored) {
                                // try the next package
                            }
                        }
                    }
                } catch (ClassNotFoundException | RuntimeException e) {
                    return null;   // the inflater's own way
                }
                if (view instanceof TextView) ((TextView) view).setTypeface(override);
                return view;
            }

            @Override
            public View onCreateView(String name, Context context, AttributeSet attrs) {
                return onCreateView(null, name, context, attrs);
            }
        });
    }

    static Typeface get(Context context, int fontString) {
        if (override != null) return override;
        Resources res = context.getResources();
        TypedValue value = new TypedValue();
        res.getValue(fontString, value, true);
        String name = String.valueOf(value.string);
        if (name.startsWith("res/")) return res.getFont(value.resourceId);
        return Typeface.create(name, Typeface.NORMAL);
    }
}
