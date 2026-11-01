package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.media.tv.TvContract;
import android.widget.Toast;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The passthrough inputs (HDMI ports, CEC devices, AV jacks) that the Inputs menu and the input_row tiles list: the
 * order, the name given in Settings > Inputs ("PS5"), the connection state and which rows inputs_connected_only
 * folds. One place, so the menu and the row never disagree. Opening one is {@link #open}.
 */
final class InputList {
    private InputList() {}

    static final class Entry {
        final String id;
        /** What the row says: "PS5 · HDMI 1", "Apple TV · HDMI 2" or just "HDMI 3". */
        final String label;
        /** The custom name alone ("PS5"), or the plain label when the input has none; short enough for a tile. */
        final String shortLabel;
        /** TvInputManager input state, or -1 when there is none to show. */
        final int state;
        /** An HDMI port or a CEC device (otherwise an AV jack). */
        final boolean hdmi;
        /** inputs_connected_only is on and this row is one it folds behind "More inputs". */
        final boolean fold;
        /** A hardware tuner (antenna, cable, satellite): not a passthrough input, opened through the Live TV app. */
        final boolean tuner;

        Entry(String id, String label, String shortLabel, int state, boolean hdmi, boolean fold) {
            this(id, label, shortLabel, state, hdmi, fold, false);
        }

        Entry(String id, String label, String shortLabel, int state, boolean hdmi, boolean fold, boolean tuner) {
            this.tuner = tuner;
            this.id = id;
            this.label = label;
            this.shortLabel = shortLabel;
            this.state = state;
            this.hdmi = hdmi;
            this.fold = fold;
        }
    }

    /** The rows, HDMI first in port order, then AV and the rest. Empty without a TvInputManager. */
    static List<Entry> collect(Context ctx, TvInputManager tim, List<String> sourceMappings, boolean connectedOnly) {
        List<Entry> out = new ArrayList<>();
        if (tim == null) return out;
        List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
        // HDMI ports first in port order, then AV and the rest. Labels are read once: loadLabel goes to
        // PackageManager, and the comparator would otherwise ask twice per comparison.
        Map<String, String> labels = new HashMap<>();
        for (TvInputInfo info : inputs) labels.put(info.getId(), String.valueOf(info.loadLabel(ctx)));
        Collections.sort(inputs, (a, b) -> {
            int ra = rank(ctx, a), rb = rank(ctx, b);
            if (ra != rb) return ra - rb;
            return labels.get(a.getId()).compareToIgnoreCase(labels.get(b.getId()));
        });
        // An HDMI-CEC device (an Apple TV, a soundbar) gets an input of its own whose parent is the
        // port it sits on, so the port and the device would each get a row (Vestel MT7332: "HDMI 1"
        // and "HDMI Apple TV"). The device takes its port's row instead, and a bare port is listed
        // only when nothing on it speaks CEC.
        Set<String> inputIds = new HashSet<>();
        for (TvInputInfo info : inputs) inputIds.add(info.getId());
        Map<String, List<TvInputInfo>> cecDevices = new HashMap<>();
        for (TvInputInfo info : inputs) {
            String parent = info.getParentId();
            if (parent == null || !inputIds.contains(parent)) continue;
            if (!info.isPassthroughInput() || info.isHidden(ctx)) continue;
            List<TvInputInfo> onPort = cecDevices.get(parent);
            if (onPort == null) cecDevices.put(parent, onPort = new ArrayList<>());
            onPort.add(info);
        }
        // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
        // so drop the HDMI status then. A TV with every port really in use loses it too.
        int hdmiPorts = 0, hdmiConnected = 0;
        for (TvInputInfo info : inputs) {
            if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
            if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
            hdmiPorts++;
            if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
        }
        boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
        for (TvInputInfo info : inputs) {
            // Inputs hidden in the TV's Settings > Inputs stay out too.
            if (!(info.isPassthroughInput() || isTuner(ctx, info)) || info.isHidden(ctx)) continue;
            // input_sources can leave out ports the board reports but the set does not have ("HW8=-").
            if (Integer.valueOf(-1).equals(InputSources.mapped(sourceMappings, info.getId()))) continue;
            // A CEC device's row is added with its port, below.
            if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
            CharSequence l = info.loadLabel(ctx);
            String label = l == null ? info.getId() : l.toString();
            if (!info.isPassthroughInput()) {
                // A tuner: the name given in Settings > Inputs ("Antena"), else the label with its HW number to tell
                // apart the several tuners one board lists.
                CharSequence name = InputNames.tidy(info.loadCustomLabel(ctx));
                if (name != null && name.length() > 0) label = name.toString();
                else if (info.getId().matches(".*/HW\\d+$")) label += " " + info.getId().substring(info.getId().lastIndexOf("/HW") + 3);
                out.add(new Entry(info.getId(), label, label, -1, false, false, true));
                continue;
            }
            boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
            if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
            CharSequence custom = InputNames.tidy(info.loadCustomLabel(ctx));
            boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
            List<TvInputInfo> devices = cecDevices.get(info.getId());
            if (devices != null) {
                // "Apple TV · HDMI 1": the device's own name (or the one given to it or to its port in
                // Settings > Inputs), then the port. Opening the device's input also makes it the CEC
                // active source, which wakes it.
                for (TvInputInfo device : devices) {
                    CharSequence name = InputNames.tidy(device.loadCustomLabel(ctx));
                    if (name == null || name.length() == 0) name = named ? custom : device.loadLabel(ctx);
                    boolean noName = name == null || name.length() == 0;
                    String deviceLabel = noName ? label : name + " · " + label;
                    int state = hdmiStateBogus ? -1 : tim.getInputState(device.getId());
                    out.add(new Entry(device.getId(), deviceLabel, noName ? label : name.toString(), state, true,
                            connectedOnly && InputFold.folds(true, state)));
                }
                continue;
            }
            // The name given in Settings > Inputs (e.g. "MacBook Pro") goes first, the port after it.
            String shortLabel = named ? custom.toString() : label;
            if (named) label = custom + " · " + label;
            // Analog AV jacks have no hotplug detection, and some TVs report them as always connected
            // (TCL Smart TV Pro G03 says "Connected" with nothing plugged in), so AV rows show no state.
            int state = !hdmi || hdmiStateBogus ? -1 : tim.getInputState(info.getId());
            out.add(new Entry(info.getId(), label, shortLabel, state, hdmi, connectedOnly && InputFold.folds(hdmi, state)));
        }
        return out;
    }


    /**
     * How an input looks: 1 when a device is connected (lit), -1 when the state says nothing is there, including
     * standby (dimmed), 0 when the TV gives no state (tuners, AV jacks, a board that reports every port connected).
     */
    static int look(int state) {
        if (state == -1) return 0;
        return state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED ? 1 : -1;
    }

    /** The row's icon: the antenna for a tuner, else from its name with the HDMI connector or the AV plugs as fallback. */
    static int iconOf(Entry e) {
        return e.tuner ? R.drawable.ic_antenna : iconFor(e.shortLabel, e.hdmi ? R.drawable.ic_input : R.drawable.ic_av);
    }

    /** A hardware tuner of the TV (not a streaming app's software input of type tuner). */
    static boolean isTuner(Context ctx, TvInputInfo info) {
        if (info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_TUNER) return false;
        try {
            return (info.getServiceInfo().applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Menu order: HDMI, then tuners, then AV and the rest. */
    private static int rank(Context ctx, TvInputInfo info) {
        if (info.getType() == TvInputInfo.TYPE_HDMI) return 0;
        return isTuner(ctx, info) ? 1 : 2;
    }

    /** The icon for an input from the name given to it ("PS5", "Mi portatil"); {@code fallback} when it says nothing. */
    static int iconFor(String label, int fallback) {
        if (label == null) return fallback;
        String n = java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
        if (hasAny(n, "ps5", "ps4", "playstation", "xbox")) return R.drawable.ic_dev_gamepad;
        if (hasAny(n, "steamdeck", "deck", "switch", "rog", "legion")) return R.drawable.ic_dev_handheld;
        if (hasAny(n, "portatil", "laptop", "notebook")) return R.drawable.ic_dev_laptop;
        if (hasAny(n, "pc", "ordenador", "computer", "desktop")) return R.drawable.ic_dev_desktop;
        if (hasAny(n, "appletv", "chromecast", "firestick", "firetv", "shield", "box")) return R.drawable.ic_dev_box;
        if (hasAny(n, "dvd", "bluray")) return R.drawable.ic_dev_disc;
        return fallback;
    }

    /** {@link #iconFor(String, int)} with the HDMI connector as the fallback. */
    static int iconFor(String label) {
        return iconFor(label, R.drawable.ic_input);
    }

    private static boolean hasAny(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }

    /**
     * A tuner has no passthrough URI: ACTION_VIEW on its channel collection (dir MIME type), aimed at the system app
     * that handles it (the configured Live TV package first), makes that app tune the input.
     */
    private static void openTuner(Context ctx, String inputId, String liveTvPkg) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelsUriForInput(inputId), TvContract.Channels.CONTENT_TYPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ComponentName target = null;
        for (ResolveInfo r : ctx.getPackageManager().queryIntentActivities(view, 0)) {
            if (r.activityInfo == null) continue;
            if (r.activityInfo.packageName.equals(liveTvPkg)) {
                target = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
                break;
            }
            if (target == null && (r.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                target = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            }
        }
        if (target != null) view.setComponent(target);
        try {
            ctx.startActivity(view);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    static void open(Context ctx, String inputId, List<String> sources, String action, String scheme, String liveTvPkg) {
        // A CEC device is a child input of its HDMI port; the vendor source number belongs to the port
        // (MiTV-MFFU0). Only that lookup uses the parent: the generic path below still opens the child.
        String sourceId = inputId;
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            try {
                TvInputInfo info = tim.getTvInputInfo(inputId);
                if (info != null && info.getParentId() != null) sourceId = info.getParentId();
            } catch (RuntimeException ignored) {
            }
        }
        Integer source = InputSources.mapped(sources, sourceId);
        if (source != null && source >= 0 && !action.isEmpty()) {
            try {
                ctx.startActivity(new Intent(action).putExtra("input", source.intValue())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // fall through to the standard way
            }
        }
        if (tim != null) {
            try {
                TvInputInfo info = tim.getTvInputInfo(inputId);
                if (info != null && isTuner(ctx, info)) {
                    openTuner(ctx, inputId, liveTvPkg);
                    return;
                }
            } catch (RuntimeException ignored) {
            }
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = (scheme.isEmpty()
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                : new Intent(Intent.ACTION_VIEW, uri.buildUpon().scheme(scheme).build()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Aim it at the TV app first: apps installed later (a streaming app, a file manager) can claim the same
        // channel URI too, and with two or more handlers Android shows a chooser instead of the input (WhaleOS).
        if (!liveTvPkg.isEmpty()) {
            try {
                ctx.startActivity(new Intent(view).setPackage(liveTvPkg));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        try {
            ctx.startActivity(view);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (action.isEmpty()) {
                Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        try {
            ctx.startActivity(new Intent(action).setDataAndType(uri, "vnd.android.cursor.item/channel")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
