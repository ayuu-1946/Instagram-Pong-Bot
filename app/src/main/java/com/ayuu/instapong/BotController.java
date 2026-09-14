package com.ayuu.instapong;

import android.os.SystemClock;
import java.util.function.Consumer;

public final class BotController {
    private BotController() {}

    private static Consumer<String> uiCallback;
    private static long lastMoveMs = 0;
    private static float lastTargetX = -1f;
    private static float estimatedPaddleX = -1f;

    public static synchronized void setUiCallback(Consumer<String> cb) {
        uiCallback = cb;
    }

    public static synchronized void status(String text) {
        if (uiCallback != null) uiCallback.accept(text);
    }

    public static synchronized boolean shouldMove(float targetX, float actualPaddleX, float screenWidth, float confidence) {
        if (confidence < 0.55f) return false;
        long now = SystemClock.uptimeMillis();
        float error = Math.abs(targetX - actualPaddleX);
        float deadband = Math.max(10f, screenWidth * 0.016f);
        if (error < deadband) return false;
        if (lastTargetX >= 0f && Math.abs(targetX - lastTargetX) < Math.max(8f, screenWidth * 0.012f)
                && now - lastMoveMs < 90L) return false;
        return now - lastMoveMs >= 65L || error > screenWidth * 0.12f;
    }

    public static synchronized void recordMove(float targetX) {
        lastTargetX = targetX;
        estimatedPaddleX = targetX;
        lastMoveMs = SystemClock.uptimeMillis();
    }

    public static synchronized void syncPaddleX(float actualX) {
        estimatedPaddleX = actualX;
    }

    public static synchronized float getEstimatedPaddleX(float fallback) {
        return estimatedPaddleX >= 0f ? estimatedPaddleX : fallback;
    }

    public static synchronized void reset() {
        lastMoveMs = 0L;
        lastTargetX = -1f;
        estimatedPaddleX = -1f;
    }
}
