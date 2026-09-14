package com.ayuu.instapong;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.atomic.AtomicBoolean;

public class PongAccessibilityService extends AccessibilityService {
    public static volatile PongAccessibilityService instance;
    private final AtomicBoolean gestureInFlight = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        BotController.status("Accessibility: connected\nDetector: waiting for screen frames");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {
        gestureInFlight.set(false);
    }

    @Override
    public void onDestroy() {
        gestureInFlight.set(false);
        instance = null;
        super.onDestroy();
    }

    public boolean isGestureInFlight() {
        return gestureInFlight.get();
    }

    public boolean movePaddle(float fromX, float toX, float y, long durationMs) {
        if (!gestureInFlight.compareAndSet(false, true)) {
            return false;
        }
        long safeDuration = Math.max(28L, Math.min(110L, durationMs));
        Path path = new Path();
        path.moveTo(fromX, y);
        path.lineTo(toX, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, safeDuration);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                gestureInFlight.set(false);
            }
            @Override public void onCancelled(GestureDescription g) {
                gestureInFlight.set(false);
            }
        }, mainHandler);

        if (!dispatched) {
            gestureInFlight.set(false);
        }
        return dispatched;
    }
}
