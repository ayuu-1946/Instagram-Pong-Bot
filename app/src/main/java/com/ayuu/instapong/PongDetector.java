package com.ayuu.instapong;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.ArrayDeque;

/** Lightweight detector tuned for the Instagram Pong visuals seen in the supplied recording. */
public final class PongDetector {
    public static final class Observation {
        public boolean ballFound;
        public float ballX, ballY, ballRadius;
        public boolean paddleFound;
        public float paddleX, paddleY, paddleWidth;
    }

    public Observation detect(Bitmap b) {
        Observation o = new Observation();
        final int w = b.getWidth(), h = b.getHeight();

        // The game background is a broad pastel field. The ball is a much more saturated,
        // darker yellow/orange object. Work on a 3x reduced grid for speed and use connected
        // components instead of averaging every yellow pixel (the old detector accidentally
        // treated the whole orange background as the ball).
        final int step = 3;
        final int gw = (w + step - 1) / step;
        final int y0 = Math.max(0, (int) (h * 0.08f));
        final int y1 = Math.min(h - 1, (int) (h * 0.90f));
        final int gy0 = y0 / step;
        final int gy1 = y1 / step;
        final boolean[] mask = new boolean[gw * (gy1 - gy0 + 1)];
        for (int gy = gy0; gy <= gy1; gy++) {
            int y = Math.min(h - 1, gy * step);
            int row = (gy - gy0) * gw;
            for (int gx = 0; gx < gw; gx++) {
                int x = Math.min(w - 1, gx * step);
                int c = b.getPixel(x, y);
                int r = Color.red(c), g = Color.green(c), bl = Color.blue(c);
                // Empirically separates the saturated emoji from the pale yellow/orange field.
                mask[row + gx] = r > 205 && g > 90 && g < 220 && bl < 95 && (r - bl) > 115;
            }
        }

        boolean[] seen = new boolean[mask.length];
        float bestScore = -1f;
        float bestCx = -1f, bestCy = -1f, bestR = 0f;
        for (int gy = gy0; gy <= gy1; gy++) {
            int localY = gy - gy0;
            int row = localY * gw;
            for (int gx = 0; gx < gw; gx++) {
                int idx = row + gx;
                if (!mask[idx] || seen[idx]) continue;
                int minX = gx, maxX = gx, minY = gy, maxY = gy, count = 0;
                long sumX = 0, sumY = 0;
                ArrayDeque<Integer> q = new ArrayDeque<>();
                q.add(idx);
                seen[idx] = true;
                while (!q.isEmpty()) {
                    int cur = q.removeFirst();
                    int ly = cur / gw;
                    int lx = cur - ly * gw;
                    int absGy = gy0 + ly;
                    count++;
                    sumX += lx;
                    sumY += absGy;
                    minX = Math.min(minX, lx); maxX = Math.max(maxX, lx);
                    minY = Math.min(minY, absGy); maxY = Math.max(maxY, absGy);
                    if (lx > 0) { int n = cur - 1; if (mask[n] && !seen[n]) { seen[n] = true; q.add(n); } }
                    if (lx + 1 < gw) { int n = cur + 1; if (mask[n] && !seen[n]) { seen[n] = true; q.add(n); } }
                    if (ly > 0) { int n = cur - gw; if (mask[n] && !seen[n]) { seen[n] = true; q.add(n); } }
                    if (ly + 1 <= gy1 - gy0) { int n = cur + gw; if (mask[n] && !seen[n]) { seen[n] = true; q.add(n); } }
                }

                int bw = (maxX - minX + 1) * step;
                int bh = (maxY - minY + 1) * step;
                int areaPx = count * step * step;
                if (bw < 30 || bh < 30 || bw > 140 || bh > 140 || areaPx < 250 || areaPx > 7000) continue;
                float aspect = Math.min(bw, bh) / (float) Math.max(bw, bh);
                if (aspect < 0.55f) continue;
                float cx = (sumX / (float) count) * step;
                float cy = (sumY / (float) count) * step;
                float density = areaPx / (float) Math.max(1, bw * bh);
                if (density < 0.16f) continue;

                // Prefer a candidate near the previous position only when one exists; otherwise
                // prefer the largest compact component. This prevents the top crown/avatar from
                // winning when the actual ball is in play.
                float score = areaPx * (0.5f + density);
                if (lastBallXHint >= 0) {
                    float dx = cx - lastBallXHint, dy = cy - lastBallYHint;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    score *= 1f + Math.max(0f, 1f - dist / 450f) * 2.5f;
                }
                if (score > bestScore) {
                    bestScore = score; bestCx = cx; bestCy = cy; bestR = 0.5f * Math.max(bw, bh);
                }
            }
        }

        if (bestScore >= 0f) {
            o.ballFound = true;
            o.ballX = bestCx;
            o.ballY = bestCy;
            o.ballRadius = bestR;
            lastBallXHint = bestCx;
            lastBallYHint = bestCy;
        }

        // Find the long black rounded paddle near the bottom.
        int yStart = (int) (h * 0.78f), yEnd = (int) (h * 0.96f);
        int bestY = -1, bestRun = 0, bestStart = 0, bestEnd = 0;
        for (int y = yStart; y <= yEnd; y += 2) {
            int run = 0, runStart = 0;
            for (int x = 8; x < w - 8; x++) {
                int c = b.getPixel(x, y);
                int r = Color.red(c), g = Color.green(c), bl = Color.blue(c);
                boolean dark = r < 80 && g < 80 && bl < 80;
                if (dark) {
                    if (run == 0) runStart = x;
                    run++;
                } else {
                    if (run > bestRun) { bestRun = run; bestY = y; bestStart = runStart; bestEnd = x - 1; }
                    run = 0;
                }
            }
            if (run > bestRun) { bestRun = run; bestY = y; bestStart = runStart; bestEnd = w - 9; }
        }
        if (bestRun > w * 0.12f && bestRun < w * 0.55f) {
            o.paddleFound = true;
            o.paddleX = (bestStart + bestEnd) * 0.5f;
            o.paddleY = bestY;
            o.paddleWidth = bestRun;
        }
        return o;
    }

    private float lastBallXHint = -1f;
    private float lastBallYHint = -1f;
}
