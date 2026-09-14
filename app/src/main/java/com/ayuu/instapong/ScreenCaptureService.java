package com.ayuu.instapong;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.util.Locale;

public class ScreenCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";
    private static final String CHANNEL = "pong_bot";

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private final PongDetector detector = new PongDetector();
    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            BotController.status("Screen capture stopped");
            stopSelf();
        }
    };

    private float lastBallX = -1f;
    private float lastBallY = -1f;
    private float vx = 0f;
    private float vy = 0f;
    private long lastTs = 0L;
    private boolean actuatorPrimed = false;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        thread = new HandlerThread("pong-capture");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = getIntentExtra(intent);
        if (data == null || resultCode == 0) {
            BotController.status("Missing screen-capture permission");
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(42, notification("Pong bot active"));
        stopProjectionResources();
        detector.reset();
        BotController.reset();
        lastBallX = lastBallY = -1f;
        vx = vy = 0f;
        lastTs = 0L;
        actuatorPrimed = false;
        startProjection(resultCode, data);
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Intent getIntentExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        return intent.getParcelableExtra(EXTRA_DATA);
    }

    private void startProjection(int resultCode, Intent data) {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) {
            BotController.status("Unable to create screen capture");
            stopSelf();
            return;
        }
        projection.registerCallback(projectionCallback, handler);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        int dpi = dm.densityDpi;
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(r -> processLatest(), handler);
        display = projection.createVirtualDisplay("InstagramPongBot", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        BotController.status(String.format(Locale.US,
                "Capture live %dx%d\nWaiting for Pong trajectory...", w, h));
    }

    private void processLatest() {
        if (reader == null) return;
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        try {
            Image.Plane p = image.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int w = image.getWidth();
            int h = image.getHeight();
            int pixelStride = p.getPixelStride();
            int rowStride = p.getRowStride();
            int rowPadding = rowStride - pixelStride * w;
            Bitmap bmp = Bitmap.createBitmap(
                    w + Math.max(0, rowPadding / Math.max(1, pixelStride)), h, Bitmap.Config.ARGB_8888);
            buf.rewind();
            bmp.copyPixelsFromBuffer(buf);
            if (bmp.getWidth() != w) {
                Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, w, h);
                bmp.recycle();
                bmp = cropped;
            }
            update(bmp);
            bmp.recycle();
        } catch (Throwable t) {
            BotController.status("Capture error: " + t.getClass().getSimpleName());
        } finally {
            image.close();
        }
    }

    private void update(Bitmap b) {
        long now = SystemClock.uptimeMillis();
        PongDetector.Observation o = detector.detect(b);

        if (o.paddleEstimated) {
            o.paddleX = BotController.getEstimatedPaddleX(b.getWidth() * 0.50f);
        } else if (o.paddleFound) {
            BotController.syncPaddleX(o.paddleX);
        }

        if (!o.paddleFound) return;

        // First make a large, slow drag at the actual paddle height. This is
        // deliberately independent of ball detection so the bot cannot sit
        // there reporting successful gestures while never moving the paddle.
        PongAccessibilityService svc = PongAccessibilityService.instance;
        if (!actuatorPrimed && svc != null && !svc.isGestureInFlight()) {
            float center = b.getWidth() * 0.50f;
            float target = o.paddleX < center ? center + b.getWidth() * 0.30f
                    : center - b.getWidth() * 0.30f;
            float half = Math.max(25f, o.paddleWidth * 0.48f);
            target = Math.max(half, Math.min(b.getWidth() - half, target));

            // Instagram's paddle is about 86–88% down the screen. Use a fixed
            // normalized touch point instead of a detector-derived Y so small
            // rendering changes cannot place the injected finger above/below it.
            float touchY = b.getHeight() * 0.8675f;
            boolean sent = svc.movePaddle(o.paddleX, target, touchY, 420L);
            if (sent) {
                actuatorPrimed = true;
                BotController.recordMove(target);
                BotController.status(String.format(Locale.US,
                        "ACTUATOR TEST: X %.0f -> %.0f, Y %.0f\n420 ms drag sent",
                        o.paddleX, target, touchY));
            }
            return;
        }

        if (!o.ballFound || o.confidence < 0.55f) {
            if (o.ballFound) {
                BotController.status(String.format(Locale.US,
                        "Tracking ball x=%.0f y=%.0f\nPaddle x=%.0f\nWaiting for stronger frame",
                        o.ballX, o.ballY, o.paddleX));
            }
            return;
        }

        float dt = lastTs > 0L
                ? Math.max(0.008f, Math.min(0.10f, (now - lastTs) / 1000f))
                : 0.016f;
        if (lastBallX >= 0f) {
            float rawVx = (o.ballX - lastBallX) / dt;
            float rawVy = (o.ballY - lastBallY) / dt;
            float maxSpeed = Math.max(2200f, b.getWidth() * 6.0f);
            if (Math.abs(rawVx) < maxSpeed && Math.abs(rawVy) < maxSpeed) {
                vx = vx * 0.55f + rawVx * 0.45f;
                vy = vy * 0.55f + rawVy * 0.45f;
            }
        }
        lastBallX = o.ballX;
        lastBallY = o.ballY;
        lastTs = now;

        float timeToHit = timeToPaddle(o.ballY, o.paddleY, vy, o.ballRadius,
                b.getHeight(), o.paddleWidth);
        float predictionTime = timeToHit > 0f ? Math.min(timeToHit, 1.20f) : 0.08f;
        float predictedTarget = predictInterceptX(o.ballX, vx, predictionTime,
                o.ballRadius, b.getWidth());

        float target = o.ballY >= b.getHeight() * 0.52f ? o.ballX : predictedTarget;
        if (Math.abs(vx) < 70f && Math.abs(vy) < 70f) target = o.ballX;

        float halfPaddle = Math.max(25f, o.paddleWidth * 0.48f);
        target = Math.max(halfPaddle, Math.min(b.getWidth() - halfPaddle, target));

        boolean moveSent = false;
        if (svc != null && !svc.isGestureInFlight()
                && BotController.shouldMove(target, o.paddleX, b.getWidth(), o.confidence)) {
            float distance = Math.abs(target - o.paddleX);
            long duration = (long) Math.max(180f, Math.min(320f, 190f + distance * 0.20f));
            float touchY = b.getHeight() * 0.8675f;
            moveSent = svc.movePaddle(o.paddleX, target, touchY, duration);
            if (moveSent) BotController.recordMove(target);
        }

        BotController.status(String.format(Locale.US,
                "Ball x=%.0f y=%.0f v=(%.0f,%.0f)\nPaddle x=%.0f target=%.0f t=%.2fs c=%.2f%s\nGesture: %s",
                o.ballX, o.ballY, vx, vy, o.paddleX, target,
                Math.max(0f, timeToHit), o.confidence,
                o.paddleEstimated ? " (estimated)" : "",
                moveSent ? "SENT" : "idle"));
    }

    private float timeToPaddle(float y, float paddleY, float vy, float radius,
                               int h, float paddleWidth) {
        final float top = Math.max(76f, h * 0.055f);
        final float paddleHalfHeight = Math.max(10f, paddleWidth * 0.20f);
        final float hitY = Math.max(top + 2f, paddleY - paddleHalfHeight - radius);
        if (hitY <= y + 2f) return 0.03f;
        if (Math.abs(vy) < 65f) return -1f;

        float yy = y;
        float vv = vy;
        float elapsed = 0f;
        for (int i = 0; i < 8; i++) {
            if (vv > 0f) {
                float t = (hitY - yy) / vv;
                if (t >= 0f) return elapsed + t;
                return -1f;
            }
            float t = Math.max(0f, (yy - top) / (-vv));
            elapsed += t;
            if (elapsed > 1.5f) return -1f;
            yy = top;
            vv = -vv;
        }
        return -1f;
    }

    private float predictInterceptX(float x, float vx, float t, float radius, int width) {
        return reflectX(x + vx * t, radius, width);
    }

    private float reflectX(float x, float radius, int width) {
        float left = Math.max(1f, radius);
        float right = Math.max(left + 1f, width - radius);
        float span = right - left;
        float period = span * 2f;
        float z = (x - left) % period;
        if (z < 0f) z += period;
        if (z > span) z = period - z;
        return left + z;
    }

    private Notification notification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Instagram Pong Bot")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Pong Bot", NotificationManager.IMPORTANCE_LOW));
    }

    private void stopProjectionResources() {
        if (projection != null) {
            try { projection.unregisterCallback(projectionCallback); } catch (Throwable ignored) {}
        }
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (projection != null) { projection.stop(); projection = null; }
    }

    @Override public void onDestroy() {
        stopProjectionResources();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
