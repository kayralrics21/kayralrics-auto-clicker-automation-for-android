package net.kayralrics.autoclicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;
import android.provider.Settings;

import org.opencv.android.OpenCVLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CaptureService extends Service implements OverlayController.Listener {
    static final String ACTION_CAPTURE = "net.kayralrics.autoclicker.CAPTURE";
    static final String ACTION_START = "net.kayralrics.autoclicker.START";
    static final String ACTION_STOP_SCAN = "net.kayralrics.autoclicker.STOP_SCAN";
    static final String ACTION_END = "net.kayralrics.autoclicker.END";
    static final String ACTION_SHOW = "net.kayralrics.autoclicker.SHOW";
    static final String ACTION_PICK = "net.kayralrics.autoclicker.PICK";
    static final String EXTRA_RESULT = "result";
    static final String EXTRA_DATA = "data";
    private static final String CHANNEL = "screen_service";
    private static final int NOTICE_ID = 1001;

    private static volatile CaptureService active;
    static CaptureService active() { return active; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler worker;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;
    private OverlayController overlay;
    private TargetStore store;
    private final VisualMatcher matcher = new VisualMatcher();

    private volatile boolean running;
    private volatile boolean tapPending;
    private volatile boolean selectionPending;
    private volatile boolean selectionActive;
    private volatile boolean openCvReady;
    private volatile String status = "not connected";
    private long selectAt, lastScanAt;
    private int interval = 500, threshold = 85, nextIndex = 0;
    private int screenWidth, screenHeight, screenDpi;
    private volatile boolean closing;

    static boolean isConnected() { return active != null && active.projection != null; }
    static boolean isRunning() { return active != null && active.running; }
    static String getStatus() { return active == null ? "screen not shared" : active.status; }

    static void send(Context context, String action) {
        Intent i = new Intent(context, CaptureService.class);
        i.setAction(action);
        context.startService(i); // Only used after a user-started capture has already activated service.
    }

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        store = new TargetStore(this);
        overlay = new OverlayController(this, this);
        thread = new HandlerThread("kayralrics-screen-worker");
        thread.start();
        worker = new Handler(thread.getLooper());
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        displayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) { }
            @Override public void onDisplayRemoved(int displayId) { }
            @Override public void onDisplayChanged(int displayId) {
                if (displayId == android.view.Display.DEFAULT_DISPLAY && projection != null) {
                    worker.post(() -> resizeCapture());
                }
            }
        };
        displayManager.registerDisplayListener(displayListener, main);
        try {
            openCvReady = OpenCVLoader.initLocal();
            if (!openCvReady) status = "OpenCV could not initialize";
        } catch (Throwable e) {
            Log.e("kayralrics", "OpenCV load failed", e);
            status = "OpenCV load failed";
        }
    }

    @Override public int onStartCommand(Intent command, int flags, int startId) {
        if (command == null) {
            // Android can restart a Service after process death, but not a projection grant.
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = command.getAction();
        if (ACTION_END.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP_SCAN.equals(action)) {
            stopScanning();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            startScanning();
            return START_NOT_STICKY;
        }
        if (ACTION_SHOW.equals(action)) {
            showControls();
            return START_NOT_STICKY;
        }
        if (ACTION_PICK.equals(action)) {
            onPick();
            return START_NOT_STICKY;
        }
        if (ACTION_CAPTURE.equals(action)) {
            if (projection != null) {
                showControls();
                return START_NOT_STICKY;
            }
            int result = command.getIntExtra(EXTRA_RESULT, 0);
            Intent permissionData = command.getParcelableExtra(EXTRA_DATA);
            if (result != android.app.Activity.RESULT_OK || permissionData == null) {
                status = "screen permission was not granted";
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                createNotificationChannel();
                startForeground(NOTICE_ID, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                MediaProjectionManager m = (MediaProjectionManager)
                    getSystemService(MEDIA_PROJECTION_SERVICE);
                projection = m.getMediaProjection(result, permissionData);
                if (projection == null) throw new IllegalStateException("No projection token");
                projectionCallback = new MediaProjection.Callback() {
                    @Override public void onStop() {
                        status = "screen share was stopped by Android";
                        stopSelf();
                    }
                    @Override public void onCapturedContentResize(int width, int height) {
                        if (width > 0 && height > 0 && projection != null) {
                            worker.post(() -> resizeCaptureTo(width, height));
                        }
                    }
                };
                projection.registerCallback(projectionCallback, main);
                worker.post(() -> {
                    try {
                        Point p = displaySize();
                        resizeCaptureTo(p.x, p.y);
                        main.post(() -> {
                            status = "screen sharing active";
                            showControls();
                        });
                    } catch (Exception e) {
                        Log.e("kayralrics", "Screen capture initialization failed", e);
                        status = "capture failed: " + e.getMessage();
                        main.post(() -> { toast(status); stopSelf(); });
                    }
                });
            } catch (Exception e) {
                Log.e("kayralrics", "Screen sharing setup failed", e);
                status = "screen sharing error: " + e.getMessage();
                toast(status);
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 5, open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent show = pending(ACTION_SHOW, 11);
        PendingIntent stop = pending(ACTION_END, 12);
        Icon actionIcon = Icon.createWithResource(this, android.R.drawable.ic_menu_view);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("kayralrics - auto clicker")
            .setContentText("Screen sharing active. Tap to manage.")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(content)
            .addAction(new Notification.Action.Builder(actionIcon, "controls", show).build())
            .addAction(new Notification.Action.Builder(actionIcon, "end share", stop).build())
            .build();
    }

    private PendingIntent pending(String action, int code) {
        Intent i = new Intent(this, CaptureService.class).setAction(action);
        return PendingIntent.getService(this, code, i,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Screen capture",
            NotificationManager.IMPORTANCE_LOW));
    }

    private Point displaySize() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Point p = new Point();
        wm.getDefaultDisplay().getRealSize(p);
        return p;
    }

    private void resizeCapture() {
        Point p = displaySize();
        resizeCaptureTo(p.x, p.y);
    }

    /** Resize the existing virtual display: Android 14+ allows only one createVirtualDisplay per grant. */
    private void resizeCaptureTo(int width, int height) {
        if (closing || projection == null || width < 64 || height < 64) return;
        if (screenWidth == width && screenHeight == height && imageReader != null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenDpi = metrics.densityDpi;
        ImageReader next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        next.setOnImageAvailableListener(this::frameAvailable, worker);
        try {
            if (virtualDisplay == null) {
                // Called once for each user-approved projection session.
                virtualDisplay = projection.createVirtualDisplay("kayralrics-screen", width,
                    height, screenDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    next.getSurface(), null, worker);
                if (virtualDisplay == null) throw new IllegalStateException("No virtual display");
            } else {
                virtualDisplay.resize(width, height, screenDpi);
                virtualDisplay.setSurface(next.getSurface());
            }
            ImageReader old = imageReader;
            imageReader = next;
            screenWidth = width;
            screenHeight = height;
            if (old != null) old.close();
        } catch (Exception e) {
            next.close();
            Log.e("kayralrics", "resizeCaptureTo", e);
            throw e;
        }
    }

    private void frameAvailable(ImageReader reader) {
        Image image = null;
        Bitmap screenshot = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            if (reader != imageReader || closing) return;
            long now = SystemClock.elapsedRealtime();
            boolean picking = selectionPending && now >= selectAt;
            boolean scanning = running && !tapPending && !selectionPending && !selectionActive &&
                now - lastScanAt >= interval;
            if (!picking && !scanning) return;
            screenshot = toBitmap(image);
            if (picking) {
                selectionPending = false;
                selectionActive = true;
                Bitmap snapshot = screenshot;
                screenshot = null; // Selector takes ownership until closed.
                main.post(() -> {
                    if (closing) { snapshot.recycle(); return; }
                    try {
                        overlay.beginSelection(snapshot);
                        status = "selecting visual target";
                    } catch (Exception e) {
                        Log.e("kayralrics", "Selection overlay failed", e);
                        selectionActive = false;
                        snapshot.recycle();
                        toast("Selection failed: " + e.getMessage());
                        showControls();
                    }
                });
                return;
            }
            lastScanAt = now;
            if (!openCvReady || !TapAccessibilityService.isEnabled()) {
                running = false;
                status = "enable Accessibility service / OpenCV first";
                main.post(() -> {
                    overlay.setRunning(false);
                    toast(status);
                });
                return;
            }
            VisualMatcher.Match match = matcher.findOne(screenshot, threshold / 100.0, nextIndex);
            if (match != null && running) {
                nextIndex = match.nextIndex;
                tapPending = true;
                boolean tapped = TapAccessibilityService.tap(match.x, match.y,
                    () -> tapPending = false);
                if (tapped) {
                    status = "clicked " + match.name + " (" +
                        Math.round(match.score * 100) + "%)";
                } else {
                    tapPending = false;
                    status = "tap could not be dispatched";
                    running = false;
                    main.post(() -> overlay.setRunning(false));
                }
            }
        } catch (Exception e) {
            Log.e("kayralrics", "Screen frame failed", e);
            status = "capture/matching issue: " + e.getMessage();
            running = false;
            main.post(() -> overlay.setRunning(false));
        } finally {
            if (image != null) image.close();
            if (screenshot != null) screenshot.recycle();
        }
    }

    private Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        int w = image.getWidth(), h = image.getHeight();
        int pixelStride = plane.getPixelStride(), rowStride = plane.getRowStride();
        if (pixelStride != 4 || rowStride < w * 4 || (rowStride & 3) != 0) {
            throw new IllegalStateException("Unexpected RGBA buffer layout");
        }
        ByteBuffer buffer = plane.getBuffer().duplicate();
        buffer.rewind();
        int paddedWidth = rowStride / 4;
        if (buffer.remaining() >= (long) rowStride * h) {
            Bitmap padded = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888);
            try {
                padded.copyPixelsFromBuffer(buffer);
                if (w == paddedWidth) return padded;
                return Bitmap.createBitmap(padded, 0, 0, w, h);
            } finally {
                if (w != paddedWidth) padded.recycle();
            }
        }
        // Some devices omit padding at the end of the last row. Decode safely.
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int[] colors = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgba = buffer.getInt(y * rowStride + x * 4);
                colors[y * w + x] = (rgba & 0xff00ff00)
                    | ((rgba & 0x000000ff) << 16)
                    | ((rgba & 0x00ff0000) >>> 16);
            }
        }
        return Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888);
    }

    private void startScanning() {
        if (projection == null || !openCvReady) { toast("Share the screen / initialize OpenCV first"); return; }
        if (!TapAccessibilityService.isEnabled()) {
            toast("Enable kayralrics accessibility access first"); return;
        }
        if (running) return;
        worker.post(() -> {
            try {
                TargetStore.Settings s = store.read();
                int enabled = 0;
                for (TargetStore.Target t : s.targets) if (t.enabled) enabled++;
                if (enabled == 0) { main.post(() -> toast("Add an enabled target first")); return; }
                matcher.reload(store);
                interval = s.interval;
                threshold = s.threshold;
                nextIndex = 0;
                lastScanAt = 0;
                tapPending = false;
                running = true;
                status = "scanning for targets";
                main.post(() -> overlay.setRunning(true));
            } catch (Exception e) {
                status = "unable to start: " + e.getMessage();
                main.post(() -> toast(status));
            }
        });
    }

    private void stopScanning() {
        running = false;
        status = projection == null ? "screen not shared" : "clicking paused";
        if (overlay != null) overlay.setRunning(false);
    }

    private void showControls() {
        if (projection == null) return;
        if (!Settings.canDrawOverlays(this)) {
            toast("Enable 'Display over other apps' to show controls");
            return;
        }
        try { overlay.showBubble(running); }
        catch (Exception e) { toast("Unable to show overlay: " + e.getMessage()); }
    }

    @Override public void onPick() {
        if (projection == null || closing) { toast("Start screen sharing first"); return; }
        if (!Settings.canDrawOverlays(this)) { toast("Overlay permission required"); return; }
        if (selectionPending || selectionActive || overlay.selecting()) return;
        overlay.hideBubble();
        status = "preparing screenshot";
        selectAt = SystemClock.elapsedRealtime() + 450;
        selectionPending = true;
    }

    @Override public void onToggle() {
        if (running) stopScanning(); else startScanning();
    }

    @Override public void onClose() { overlay.hideBubble(); }

    @Override public void onSelectionClosed() {
        selectionActive = false;
        lastScanAt = SystemClock.elapsedRealtime();
        if (running) status = "scanning for targets";
        else status = "screen sharing active";
    }

    @Override public void onSave(Bitmap crop) {
        try {
            TargetStore.Target t = store.add(crop);
            status = "saved " + t.name;
            worker.post(() -> {
                try { matcher.reload(store); }
                catch (Exception e) { Log.e("kayralrics", "Reload failed", e); }
            });
            toast("Saved " + t.name);
        } finally { crop.recycle(); }
    }

    void reloadTargets() {
        worker.post(() -> {
            try {
                TargetStore.Settings s = store.read();
                interval = s.interval;
                threshold = s.threshold;
                matcher.reload(store);
                nextIndex = 0;
            } catch (Exception e) {
                status = "target reload error: " + e.getMessage();
                Log.e("kayralrics", status, e);
                running = false;
                main.post(() -> overlay.setRunning(false));
            }
        });
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show());
    }

    @Override public void onDestroy() {
        closing = true;
        running = false;
        selectionPending = false;
        selectionActive = false;
        stopScanning();
        if (active == this) active = null;
        if (overlay != null) overlay.destroy();
        if (displayManager != null && displayListener != null) {
            displayManager.unregisterDisplayListener(displayListener);
        }
        // Stop projection before shutting down worker; callback may be invoked during stop.
        if (projection != null) {
            if (projectionCallback != null) projection.unregisterCallback(projectionCallback);
            projection.stop();
            projection = null;
        }
        // All capture/matcher usage is serialized on the worker Looper.
        worker.post(() -> {
            if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
            if (imageReader != null) { imageReader.close(); imageReader = null; }
            matcher.clear();
            thread.quitSafely();
        });
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
