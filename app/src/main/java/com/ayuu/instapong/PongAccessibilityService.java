package com.ayuu.instapong;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

public class PongAccessibilityService extends AccessibilityService {
    public static volatile PongAccessibilityService instance;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        BotController.status("Accessibility: connected\nDetector: waiting for screen frames");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    public boolean movePaddle(float fromX, float toX, float y, long durationMs) {
        // The service configuration declares canPerformGestures. There is no
        // canPerformGestures() method on AccessibilityService itself.
        long safeDuration = Math.max(20L, durationMs);
        Path path = new Path();
        path.moveTo(fromX, y);
        path.lineTo(toX, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, safeDuration);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        return dispatchGesture(gesture, null, null);
    }
}
