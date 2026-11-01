package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The input switcher the remote's Input button opens (InputKeyService), and the "+" of Home's Sources: a full-height
 * sidebar sliding in from the left edge with Home plus every input (HDMI, tuners, AV), over whatever is on screen. Translucent and in a task of its own, so the HDMI picture behind stays put. The button
 * again (onNewIntent), Back or 10 s without a key close it. Same rows and labels as the Inputs menu on Home.
 */
public final class InputsOverlayActivity extends Activity {
    private static final long IDLE_MS = 10_000;
    private final Runnable close = this::dismiss;
    private static final long SLIDE_MS = 180;
    private View panel, scrim;
    private boolean closing;
    /** animations, and full motion or soft_motion: the panel slides in and out; otherwise it just appears and goes. */
    private boolean slide;
    private boolean rtl;
    private Config cfg;
    private int neon;
    private Palette pal;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.load(this);
        UiFont.configure(this, cfg.uiFont);
        UiFont.install(this);
        pal = Palette.of(cfg);
        neon = cfg.neonFocus && !pal.apple ? Neon.color(cfg.accentColor) : 0;
        setContentView(R.layout.activity_inputs_overlay);
        scrim = findViewById(R.id.scrim);
        scrim.setOnClickListener(v -> dismiss());
        panel = findViewById(R.id.panel);
        float d = getResources().getDisplayMetrics().density;
        rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        slide = cfg.animations && (!cfg.reducedMotion || cfg.softMotion);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        lp.gravity = rtl ? Gravity.RIGHT : Gravity.LEFT;
        panel.setLayoutParams(lp);
        panel.setBackgroundColor(pal.popup(0xF2));   // 95%
        View edge = findViewById(R.id.edge);
        edge.setBackgroundColor(pal.border);
        ((FrameLayout.LayoutParams) edge.getLayoutParams()).gravity = rtl ? Gravity.LEFT : Gravity.RIGHT;
        TextView title = findViewById(R.id.overlayTitle);
        title.setTextColor(pal.primary);
        title.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        LinearLayout rows = findViewById(R.id.rows);
        addRow(rows, R.drawable.ic_home, getString(R.string.go_home), -1, () -> startActivity(
                new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        View first = null;
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        for (InputList.Entry e : InputList.collect(this, tim, cfg.inputSources, cfg.inputsConnectedOnly)) {
            if (e.fold) continue;
            final String id = e.id;
            View row = addRow(rows, InputList.iconOf(e), e.label, e.state,
                    () -> InputList.open(this, id, cfg.inputSources, cfg.inputAction, cfg.inputUriScheme,
                            cfg.liveTvPackage));
            if (first == null) first = row;
        }
        // The TV does not tell which input is showing, so focus starts on the first input, not on Home.
        (first != null ? first : rows.getChildAt(0)).requestFocus();
        if (slide) {
            // From the left edge (right in RTL): the bar slides in over 180 ms while the screen dims.
            float width = 280 * d;
            panel.setTranslationX((rtl ? 1 : -1) * width);
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(SLIDE_MS).start();
            panel.animate().translationX(0f).setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator())
                    .withLayer().start();
        }
    }

    /** Closes sliding back out to the edge (180 ms) when motion allows it; Back, the scrim, the idle timer and the Input button. */
    private void dismiss() {
        if (closing || isFinishing()) return;
        closing = true;
        if (!slide || panel == null) {
            finish();
            return;
        }
        panel.animate().cancel();
        scrim.animate().cancel();
        scrim.animate().alpha(0f).setDuration(SLIDE_MS).start();
        panel.animate().translationX((rtl ? 1 : -1) * 280 * getResources().getDisplayMetrics().density)
                .setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator()).withLayer()
                .withEndAction(() -> {
                    finish();
                    overridePendingTransition(0, 0);
                }).start();
    }

    @Override
    public void onBackPressed() {
        dismiss();
    }

    /** The Input button again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        dismiss();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().postDelayed(close, IDLE_MS);
    }

    /** Any key restarts the idle timer. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        View decor = getWindow().getDecorView();
        decor.removeCallbacks(close);
        decor.postDelayed(close, IDLE_MS);
        return super.dispatchKeyEvent(event);
    }

    /** Anything else taking the screen (the input just opened, Home, a screensaver) closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        getWindow().getDecorView().removeCallbacks(close);
        finish();
        overridePendingTransition(0, 0);
    }

    private View addRow(LinearLayout rows, int icon, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ImageView image = row.findViewById(R.id.icon);
        image.setImageResource(icon);
        TextView text = row.findViewById(R.id.title);
        text.setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // No dot: a connected input is lit, one with nothing on it (standby included) is dimmed.
        dot.setVisibility(View.GONE);
        if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
        } else {
            status.setVisibility(View.GONE);
        }
        if (neon != 0) {
            float d = getResources().getDisplayMetrics().density;
            GradientDrawable on = new GradientDrawable();
            on.setCornerRadius(13 * d);
            on.setColor(0xD9101018);
            on.setStroke(Math.round(2.5f * d), neon);
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, on);
            bg.addState(new int[]{}, row.getBackground());
            row.setBackground(bg);
            text.setTextColor(Color.WHITE);
            image.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        }
        row.setOnClickListener(v -> {
            action.run();
            finish();
        });
        row.getLayoutParams().height = Math.round(44 * getResources().getDisplayMetrics().density);
        rows.addView(row);
        pal.skinTree(row, getResources().getDisplayMetrics().density);
        int look = InputList.look(state);
        if (look != 0) styleLit(row, image, text, look > 0);
        return row;
    }

    /** Connected: a lighter row with a faint accent edge. Nothing there: a darker row whose icon and name dim until focused. */
    private void styleLit(View row, ImageView image, TextView text, boolean on) {
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(13 * d);
        if (on) {
            rest.setColor(pal.dark ? 0x14FFFFFF : 0xCCFFFFFF);
            rest.setStroke(Math.round(d), (0x59 << 24) | (pal.accent & 0xFFFFFF));
        } else {
            rest.setColor(pal.dark ? 0x40000000 : 0x14000000);
            image.setAlpha(0.4f);
            text.setAlpha(0.4f);
            row.setOnFocusChangeListener((v, focused) -> {
                image.setAlpha(focused ? 1f : 0.4f);
                text.setAlpha(focused ? 1f : 0.4f);
            });
        }
        android.graphics.drawable.Drawable focused = row.getBackground();
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{android.R.attr.state_pressed}, focused);
        bg.addState(new int[]{}, rest);
        row.setBackground(bg);
    }
}
