package net.kayralrics.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

public final class TapAccessibilityService extends AccessibilityService {
    private static volatile TapAccessibilityService current;
    private final Handler main = new Handler(Looper.getMainLooper());

    static boolean isEnabled() { return current != null; }

    static boolean tap(int x, int y, Runnable finished) {
        TapAccessibilityService service = current;
        if (service == null) return false;
        return service.tapInternal(x, y, finished);
    }

    private boolean tapInternal(int x, int y, Runnable finished) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, 60);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) { finished.run(); }
            @Override public void onCancelled(GestureDescription description) { finished.run(); }
        }, main);
    }

    @Override protected void onServiceConnected() { super.onServiceConnected(); current = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }
}
