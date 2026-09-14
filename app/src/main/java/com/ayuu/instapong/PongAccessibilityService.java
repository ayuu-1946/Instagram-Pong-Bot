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
        if (!gestureInFlight.compareAndSet(false, true)) return false;

        float width = getResources().getDisplayMetrics().widthPixels;
        float clampedFrom = clampX(fromX, width);
        float clampedTo = clampX(toX, width);
        float clampedY = Math.max(1f, Math.min(getResources().getDisplayMetrics().heightPixels - 1f, y));
        float distance = Math.abs(clampedTo - clampedFrom);
        if (distance < 2f) {
            gestureInFlight.set(false);
            return false;
        }

        // Use a slow, unmistakable swipe. The first few path points keep the
        // finger moving a few pixels before the large translation so apps that
        // require a real drag MOVE sequence reliably recognize the gesture.
        long safeDuration = Math.max(180L, Math.min(500L, durationMs));
        float dir = clampedTo >= clampedFrom ? 1f : -1f;
        float nudge = Math.min(6f, Math.max(2f, distance * 0.015f));

        Path path = new Path();
        path.moveTo(clampedFrom, clampedY);
        path.lineTo(clampX(clampedFrom + dir * nudge, width), clampedY);
        path.lineTo(clampX(clampedFrom + (clampedTo - clampedFrom) * 0.12f, width), clampedY);
        path.lineTo(clampX(clampedFrom + (clampedTo - clampedFrom) * 0.30f, width), clampedY);
        path.lineTo(clampX(clampedFrom + (clampedTo - clampedFrom) * 0.50f, width), clampedY);
        path.lineTo(clampX(clampedFrom + (clampedTo - clampedFrom) * 0.72f, width), clampedY);
        path.lineTo(clampX(clampedFrom + (clampedTo - clampedFrom) * 0.90f, width), clampedY);
        path.lineTo(clampedTo, clampedY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, safeDuration);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                gestureInFlight.set(false);
                BotController.statusGesture("COMPLETED");
            }

            @Override public void onCancelled(GestureDescription g) {
                gestureInFlight.set(false);
                BotController.statusGesture("CANCELLED");
            }
        }, mainHandler);

        if (!dispatched) {
            gestureInFlight.set(false);
            BotController.statusGesture("REJECTED");
        } else {
            BotController.statusGesture("SENT");
        }
        return dispatched;
    }

    private float clampX(float x, float width) {
        return Math.max(1f, Math.min(Math.max(2f, width - 1f), x));
    }
}
