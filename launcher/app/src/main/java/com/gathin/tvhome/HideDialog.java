package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/** "Ocultar" / "Cancelar" prompt for a title of Explorar or Listas; the focus starts on Cancelar. */
final class HideDialog {
    private HideDialog() {}

    static void show(Activity a, Palette pal, String title, Runnable onHide) {
        float density = a.getResources().getDisplayMetrics().density;
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding((int) (28 * density), (int) (24 * density), (int) (28 * density), (int) (24 * density));
        panel.setBackground(pal.panelBackground(density, pal.popup(0xF2)));

        TextView head = new TextView(a);
        head.setText("¿Ocultar “" + title + "”?");
        head.setTextColor(pal.primary);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        head.setTypeface(UiFont.get(a, R.string.font_medium));
        panel.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView note = new TextView(a);
        note.setText("Dejará de aparecer en Explorar y Listas.");
        note.setTextColor(pal.secondary);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        note.setTypeface(UiFont.get(a, R.string.font_body));
        note.setPadding(0, (int) (6 * density), 0, (int) (20 * density));
        panel.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_HORIZONTAL);
        final Dialog dialog = new Dialog(a);
        TextView hide = pill(a, pal, density, "Ocultar");
        TextView cancel = pill(a, pal, density, "Cancelar");
        hide.setOnClickListener(v -> {
            dialog.dismiss();
            onHide.run();
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(hide, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart((int) (16 * density));
        buttons.addView(cancel, lp);
        panel.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (460 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        dialog.show();
        cancel.requestFocus();
    }

    private static TextView pill(Activity a, Palette pal, float density, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(pal.ink());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(UiFont.get(a, R.string.font_medium));
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding((int) (28 * density), (int) (10 * density), (int) (28 * density), (int) (10 * density));
        t.setBackground(pal.pillBackground(density, 22));
        t.setFocusable(true);
        t.setClickable(true);
        return t;
    }
}
