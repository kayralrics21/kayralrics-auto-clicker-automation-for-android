package net.kayralrics.autoclicker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class OverlayController {
    interface Listener {
        void onPick();
        void onToggle();
        void onClose();
        void onSave(Bitmap image);
        void onSelectionClosed();
    }

    private final Context context;
    private final WindowManager manager;
    private final Listener listener;
    private View bubble, selection;
    private RegionSelector region;
    private Bitmap captured;
    private Button toggle;
    private boolean runningState;

    OverlayController(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    private WindowManager.LayoutParams params(int width, int height, int flags) {
        return new WindowManager.LayoutParams(width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags,
            android.graphics.PixelFormat.TRANSLUCENT);
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private Button button(String title) {
        Button b = new Button(context);
        b.setAllCaps(false);
        b.setText(title);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffb71c35));
        return b;
    }

    void showBubble(boolean running) {
        runningState = running;
        if (!Settings.canDrawOverlays(context) || selection != null) return;
        if (bubble != null) { setRunning(running); return; }
        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3));
        controls.setBackgroundColor(0xee111115);
        Button add = button("+ target");
        toggle = button(running ? "stop" : "start");
        Button hide = button("hide");
        controls.addView(add);
        controls.addView(toggle);
        controls.addView(hide);
        add.setOnClickListener(v -> listener.onPick());
        toggle.setOnClickListener(v -> listener.onToggle());
        hide.setOnClickListener(v -> listener.onClose());
        WindowManager.LayoutParams lp = params(dp(context, 114),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(context, 8);
        lp.y = dp(context, 175);
        manager.addView(controls, lp);
        bubble = controls;
    }

    void setRunning(boolean running) {
        runningState = running;
        if (toggle != null) toggle.setText(running ? "stop" : "start");
    }

    void hideBubble() {
        if (bubble != null) {
            manager.removeView(bubble);
            bubble = null;
            toggle = null;
        }
    }

    boolean selecting() { return selection != null; }

    void beginSelection(Bitmap bitmap) {
        closeSelection();
        hideBubble();
        captured = bitmap;
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xff000000);
        region = new RegionSelector(context, bitmap);
        root.addView(region, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 6));
        header.setBackgroundColor(0xee101014);
        TextView title = new TextView(context);
        title.setText("select target  •  drag or tap two corners");
        title.setTextSize(13);
        title.setTextColor(Color.WHITE);
        header.addView(title);
        LinearLayout buttons = new LinearLayout(context);
        Button reset = button("reset");
        Button save = button("save target");
        Button cancel = button("cancel");
        buttons.addView(reset, new LinearLayout.LayoutParams(0, dp(context, 52), 1));
        buttons.addView(save, new LinearLayout.LayoutParams(0, dp(context, 52), 1.4f));
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(context, 52), 1));
        header.addView(buttons);
        FrameLayout.LayoutParams bar = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(header, bar);

        reset.setOnClickListener(v -> region.reset());
        cancel.setOnClickListener(v -> {
            closeSelection();
            showBubble(runningState);
        });
        save.setOnClickListener(v -> {
            try {
                Bitmap crop = region.crop();
                if (crop == null) {
                    android.widget.Toast.makeText(context, "Select at least 12 x 12 pixels",
                        android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                listener.onSave(crop);
                closeSelection();
                showBubble(runningState);
            } catch (RuntimeException e) {
                android.widget.Toast.makeText(context, e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
            }
        });

        WindowManager.LayoutParams lp = params(-1, -1,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            manager.addView(root, lp);
            selection = root;
        } catch (RuntimeException error) {
            root.removeAllViews();
            bitmap.recycle();
            captured = null;
            region = null;
            throw error;
        }
    }

    void closeSelection() {
        boolean wasSelecting = selection != null;
        if (selection != null) {
            manager.removeView(selection);
            selection = null;
        }
        region = null;
        if (captured != null && !captured.isRecycled()) captured.recycle();
        captured = null;
        if (wasSelecting) listener.onSelectionClosed();
    }

    void destroy() { closeSelection(); hideBubble(); }

    private static final class RegionSelector extends View {
        private final Bitmap screenshot;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF destination = new RectF();
        private float scale;
        private float ax, ay, bx, by, downX, downY;
        private boolean started, finished, dragged, held;

        RegionSelector(Context context, Bitmap screenshot) {
            super(context);
            this.screenshot = screenshot;
        }

        private void recalc() {
            if (getWidth() == 0 || getHeight() == 0) return;
            scale = Math.min(getWidth() / (float) screenshot.getWidth(),
                             getHeight() / (float) screenshot.getHeight());
            float w = screenshot.getWidth() * scale;
            float h = screenshot.getHeight() * scale;
            destination.set((getWidth() - w) / 2f, (getHeight() - h) / 2f,
                (getWidth() + w) / 2f, (getHeight() + h) / 2f);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            recalc();
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawBitmap(screenshot, null, destination, paint);
            if (started) {
                RectF rect = new RectF(Math.min(ax, bx), Math.min(ay, by),
                    Math.max(ax, bx), Math.max(ay, by));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x44ee2947);
                canvas.drawRect(rect, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(4f * getResources().getDisplayMetrics().density);
                paint.setColor(0xffff3154);
                canvas.drawRect(rect, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }

        private float safeX(float x) { return Math.max(destination.left, Math.min(destination.right, x)); }
        private float safeY(float y) { return Math.max(destination.top, Math.min(destination.bottom, y)); }
        private boolean valid() {
            return started && scale > 0 && Math.abs(bx - ax) / scale >= 12 &&
                Math.abs(by - ay) / scale >= 12;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = safeX(e.getX()), y = safeY(e.getY());
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (finished) return true; // Never reset on a stray click.
                    downX = x; downY = y; held = true; dragged = false;
                    if (!started) { started = true; ax = x; ay = y; bx = x; by = y; }
                    else { bx = x; by = y; }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!held || finished) return true;
                    bx = x; by = y;
                    if (Math.hypot(x - downX, y - downY) >
                        9 * getResources().getDisplayMetrics().density) dragged = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!held || finished) return true;
                    held = false;
                    bx = x; by = y;
                    // One finger drag: complete on release. Two separate taps: second tap completes.
                    if (valid() && (dragged || Math.abs(downX - ax) > 0.5f ||
                        Math.abs(downY - ay) > 0.5f)) finished = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    held = false;
                    invalidate();
                    return true;
                default: return true;
            }
        }

        void reset() {
            started = false; finished = false; held = false; dragged = false;
            invalidate();
        }

        Bitmap crop() {
            if (!finished || !valid()) return null;
            int x1 = Math.max(0, Math.min(screenshot.getWidth(),
                Math.round((Math.min(ax, bx) - destination.left) / scale)));
            int x2 = Math.max(0, Math.min(screenshot.getWidth(),
                Math.round((Math.max(ax, bx) - destination.left) / scale)));
            int y1 = Math.max(0, Math.min(screenshot.getHeight(),
                Math.round((Math.min(ay, by) - destination.top) / scale)));
            int y2 = Math.max(0, Math.min(screenshot.getHeight(),
                Math.round((Math.max(ay, by) - destination.top) / scale)));
            if (x2 - x1 < 12 || y2 - y1 < 12) return null;
            return Bitmap.createBitmap(screenshot, x1, y1, x2 - x1, y2 - y1);
        }
    }
}
