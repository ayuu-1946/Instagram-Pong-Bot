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

        float clampedFrom = clampX(fromX);
        float clampedTo = clampX(toX);
        if (Math.abs(clampedTo - clampedFrom) < 2f) {
            gestureInFlight.set(false);
            return false;
        }

        // A real drag needs a definite DOWN on the paddle, a brief grip/wiggle,
        // then a continuous horizontal move and UP. Keep it short enough that
        // the next ball-position sample can issue another correction quickly.
        long safeDuration = Math.max(65L, Math.min(150L, durationMs));
        float direction = clampedTo >= clampedFrom ? 1f : -1f;
        float gripX = clampX(clampedFrom + direction * Math.min(3f, Math.abs(clampedTo - clampedFrom) * 0.02f));

        Path path = new Path();
        path.moveTo(clampedFrom, y);
        path.lineTo(gripX, y);
        path.lineTo(clampedFrom + (clampedTo - clampedFrom) * 0.25f, y);
        path.lineTo(clampedFrom + (clampedTo - clampedFrom) * 0.55f, y);
        path.lineTo(clampedFrom + (clampedTo - clampedFrom) * 0.82f, y);
        path.lineTo(clampedTo, y);

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

    private float clampX(float x) {
        float w = getResources().getDisplayMetrics().widthPixels;
        return Math.max(1f, Math.min(Math.max(2f, w - 1f), x));
    }
}
