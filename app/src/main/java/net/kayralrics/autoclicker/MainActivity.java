package net.kayralrics.autoclicker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int SCREEN_REQUEST = 901;
    private static final int RED = 0xffd52b47;
    private static final int WHITE = 0xfff0edf0;
    private static final int MUTED = 0xffb5aeb5;
    private TargetStore store;
    private TextView status;
    private LinearLayout targetItems;
    private EditText interval, threshold;

    private int dp(int n) { return OverlayController.dp(this, n); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff090909);
        getWindow().setNavigationBarColor(0xff090909);
        store = new TargetStore(this);
        createLayout();
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 300);
        }
    }

    private TextView text(String content, int size, int color) {
        TextView t = new TextView(this);
        t.setText(content);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String content) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(content);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(RED));
        return b;
    }

    private EditText number(String initial) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setText(initial);
        field.setTextColor(WHITE);
        field.setSingleLine(true);
        field.setSelectAllOnFocus(true);
        field.setTextSize(16);
        field.setBackgroundTintList(android.content.res.ColorStateList.valueOf(RED));
        return field;
    }

    private void createLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff08080b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(24));
        scroll.addView(root);

        root.addView(text("kayralrics", 27, WHITE));
        root.addView(text("auto clicker  •  android", 14, RED));
        root.addView(text("Local visual matching + automatic touch. No account, cloud or internet permission.",
            12, MUTED));
        root.addView(text("", 10, MUTED));
        status = text("", 14, MUTED);
        root.addView(status);

        Button overlays = button("1. allow floating controls");
        overlays.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        root.addView(overlays);

        Button accessibility = button("2. enable accessibility taps");
        accessibility.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle("Accessibility disclosure")
                .setMessage("The kayralrics accessibility service sends automatic taps to coordinates on the screen only while you activate scanning. It does not read page text, monitor keystrokes, send data or use the network. Enable it manually in Android Settings.")
                .setNegativeButton("cancel", null)
                .setPositiveButton("open settings", (d, w) ->
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .show();
        });
        root.addView(accessibility);

        Button screen = button("3. share full screen");
        screen.setOnClickListener(v -> requestScreen());
        root.addView(screen);

        Button controls = button("show floating controls");
        controls.setOnClickListener(v -> send(CaptureService.ACTION_SHOW));
        root.addView(controls);
        root.addView(text("To select a button: open the app you want to automate, tap '+ target' in the floating controls, then drag a red rectangle or tap its two corners. Save the selection.",
            13, MUTED));

        root.addView(text("settings", 20, WHITE));
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = new LinearLayout(this), right = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        right.setOrientation(LinearLayout.VERTICAL);
        left.addView(text("interval (ms)", 13, MUTED));
        right.addView(text("match (%)", 13, MUTED));
        interval = number("500");
        threshold = number("85");
        left.addView(interval);
        right.addView(threshold);
        fields.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        fields.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(fields);
        Button save = button("save settings");
        save.setOnClickListener(v -> saveSettings());
        root.addView(save);

        LinearLayout actions = new LinearLayout(this);
        Button start = button("start clicking");
        Button stop = button("stop clicking");
        actions.addView(start, new LinearLayout.LayoutParams(0, dp(55), 1));
        actions.addView(stop, new LinearLayout.LayoutParams(0, dp(55), 1));
        start.setOnClickListener(v -> { if (saveSettings()) send(CaptureService.ACTION_START); });
        stop.setOnClickListener(v -> send(CaptureService.ACTION_STOP_SCAN));
        root.addView(actions);
        Button end = button("disconnect screen");
        end.setOnClickListener(v -> send(CaptureService.ACTION_END));
        root.addView(end);

        root.addView(text("saved targets", 20, WHITE));
        targetItems = new LinearLayout(this);
        targetItems.setOrientation(LinearLayout.VERTICAL);
        root.addView(targetItems);
        Button reload = button("refresh targets");
        reload.setOnClickListener(v -> refresh());
        root.addView(reload);
        TextView footer = text("kayralrics.net", 12, MUTED);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer);
        setContentView(scroll);
    }

    private void requestScreen() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow floating controls first"); return;
        }
        if (CaptureService.isConnected()) {
            send(CaptureService.ACTION_SHOW);
            toast("Sharing is already active"); return;
        }
        MediaProjectionManager manager = (MediaProjectionManager)
            getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_REQUEST);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SCREEN_REQUEST) return;
        if (result != RESULT_OK || data == null) {
            toast("Screen capture not granted"); return;
        }
        Intent service = new Intent(this, CaptureService.class);
        service.setAction(CaptureService.ACTION_CAPTURE);
        service.putExtra(CaptureService.EXTRA_RESULT, result);
        service.putExtra(CaptureService.EXTRA_DATA, data);
        try {
            startForegroundService(service);
            toast("Screen sharing started. Use the floating controls on your target app.");
        } catch (RuntimeException e) {
            toast("Could not start screen service: " + e.getMessage());
        }
    }

    private void send(String action) {
        if (CaptureService.active() == null) {
            toast("Share the screen first"); return;
        }
        CaptureService.send(this, action);
    }

    private boolean saveSettings() {
        try {
            int milliseconds = Integer.parseInt(interval.getText().toString().trim());
            int percent = Integer.parseInt(threshold.getText().toString().trim());
            if (milliseconds < 100 || milliseconds > 10000 || percent < 50 || percent > 99) {
                toast("Interval 100–10000 ms, threshold 50–99%."); return false;
            }
            TargetStore.Settings s = store.read();
            s.interval = milliseconds;
            s.threshold = percent;
            store.write(s);
            CaptureService service = CaptureService.active();
            if (service != null) service.reloadTargets();
            toast("Settings saved");
            return true;
        } catch (Exception e) { toast("Settings error: " + e.getMessage()); return false; }
    }

    private void refresh() {
        status.setText("screen: " + (CaptureService.isConnected() ? "connected" : "not shared") +
            "  •  touch: " + (TapAccessibilityService.isEnabled() ? "enabled" : "disabled") +
            "\n" + CaptureService.getStatus());
        targetItems.removeAllViews();
        try {
            TargetStore.Settings s = store.read();
            interval.setText(Integer.toString(s.interval));
            threshold.setText(Integer.toString(s.threshold));
            if (s.targets.isEmpty()) {
                targetItems.addView(text("No targets yet. Add one from the floating controls.",
                    13, MUTED));
                return;
            }
            for (TargetStore.Target target : s.targets) {
                LinearLayout line = new LinearLayout(this);
                line.setGravity(Gravity.CENTER_VERTICAL);
                CheckBox box = new CheckBox(this);
                box.setText(target.name);
                box.setTextSize(13);
                box.setTextColor(WHITE);
                box.setButtonTintList(android.content.res.ColorStateList.valueOf(RED));
                box.setChecked(target.enabled);
                box.setOnCheckedChangeListener((view, enabled) -> {
                    try {
                        TargetStore.Settings updated = store.read();
                        for (TargetStore.Target t : updated.targets) {
                            if (t.id.equals(target.id)) t.enabled = enabled;
                        }
                        store.write(updated);
                        reloadService();
                    } catch (Exception e) { toast(e.getMessage()); }
                });
                Button rename = button("edit");
                rename.setTextSize(11);
                rename.setOnClickListener(v -> editName(target));
                Button delete = button("remove");
                delete.setTextSize(11);
                delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Remove target?")
                    .setMessage(target.name)
                    .setNegativeButton("cancel", null)
                    .setPositiveButton("remove", (d, w) -> {
                        try { store.remove(target.id); reloadService(); refresh(); }
                        catch (Exception e) { toast(e.getMessage()); }
                    }).show());
                line.addView(box, new LinearLayout.LayoutParams(0, dp(62), 1));
                line.addView(rename, new LinearLayout.LayoutParams(dp(75), dp(52)));
                line.addView(delete, new LinearLayout.LayoutParams(dp(92), dp(52)));
                targetItems.addView(line);
            }
        } catch (Exception e) {
            targetItems.addView(text("Failed reading saved targets: " + e.getMessage(),
                13, RED));
        }
    }

    private void editName(TargetStore.Target target) {
        EditText edit = new EditText(this);
        edit.setText(target.name);
        edit.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("Target name").setView(edit)
            .setNegativeButton("cancel", null)
            .setPositiveButton("save", (d, w) -> {
                try {
                    TargetStore.Settings s = store.read();
                    for (TargetStore.Target t : s.targets) if (t.id.equals(target.id)) {
                        String name = edit.getText().toString().trim();
                        if (!name.isEmpty()) t.name = name.substring(0, Math.min(60, name.length()));
                    }
                    store.write(s); reloadService(); refresh();
                } catch (Exception e) { toast(e.getMessage()); }
            }).show();
    }

    private void reloadService() {
        CaptureService service = CaptureService.active();
        if (service != null) service.reloadTargets();
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    @Override protected void onResume() { super.onResume(); refresh(); }
}
