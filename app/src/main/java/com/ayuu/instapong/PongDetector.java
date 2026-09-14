package com.ayuu.instapong;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Fast temporal detector for the Instagram Pong screen.
 * It first finds the actual black paddle, then searches only the playable area for
 * compact saturated emoji candidates. Candidate selection is gated by the previous
 * trajectory so static profile/score emojis cannot hijack the track.
 */
public final class PongDetector {
    public static final class Observation {
        public boolean ballFound;
        public float ballX, ballY, ballRadius;
        public boolean paddleFound;
        public float paddleX, paddleY, paddleWidth;
        public float confidence;
    }

    private float lastX = -1f, lastY = -1f;
    private float filteredVx = 0f, filteredVy = 0f;
    private long lastTs = 0L;

    public void reset() {
        lastX = lastY = -1f;
        filteredVx = filteredVy = 0f;
        lastTs = 0L;
    }

    public Observation detect(Bitmap b) {
        Observation o = new Observation();
        final int w = b.getWidth();
        final int h = b.getHeight();

        // Paddle first: its geometry gives us the true lower game boundary.
        int paddleTop = (int) (h * 0.76f);
        int paddleBottom = (int) (h * 0.96f);
        int bestRun = 0, bestY = -1, bestStart = -1, bestEnd = -1;
        for (int y = paddleTop; y <= paddleBottom; y += 2) {
            int run = 0, runStart = 0;
            for (int x = 6; x < w - 6; x++) {
                int c = b.getPixel(x, y);
                int r = Color.red(c), g = Color.green(c), bl = Color.blue(c);
                boolean dark = r < 70 && g < 70 && bl < 70;
                if (dark) {
                    if (run == 0) runStart = x;
                    run++;
                } else if (run > 0) {
                    if (run > bestRun) {
                        bestRun = run; bestY = y; bestStart = runStart; bestEnd = x - 1;
                    }
                    run = 0;
                }
            }
            if (run > bestRun) {
                bestRun = run; bestY = y; bestStart = runStart; bestEnd = w - 7;
            }
        }
        if (bestRun >= w * 0.14f && bestRun <= w * 0.60f) {
            o.paddleFound = true;
            o.paddleX = (bestStart + bestEnd) * 0.5f;
            o.paddleY = bestY;
            o.paddleWidth = bestRun;
        }

        final float playBottom = o.paddleFound ? o.paddleY - Math.max(38f, o.paddleWidth * 0.35f) : h * 0.88f;
        final int y0 = Math.max((int) (h * 0.10f), 110);
        final int y1 = Math.min(h - 1, (int) playBottom);
        if (y1 <= y0) return o;

        // Compact yellow/orange component extraction. Threshold is deliberately broader
        // than the original and uses saturation/contrast instead of one exact RGB range.
        final int step = 3;
        final int gw = (w + step - 1) / step;
        final int gy0 = y0 / step;
        final int gy1 = y1 / step;
        final int gh = gy1 - gy0 + 1;
        final boolean[] mask = new boolean[gw * gh];

        for (int gy = gy0; gy <= gy1; gy++) {
            int row = (gy - gy0) * gw;
            int y = Math.min(h - 1, gy * step);
            for (int gx = 0; gx < gw; gx++) {
                int x = Math.min(w - 1, gx * step);
                int c = b.getPixel(x, y);
                int r = Color.red(c), g = Color.green(c), bl = Color.blue(c);
                int mx = Math.max(r, Math.max(g, bl));
                int mn = Math.min(r, Math.min(g, bl));
                int sat = mx - mn;
                // Yellow/orange family, including antialiased edges of the emoji.
                mask[row + gx] = mx > 150 && sat > 55 && r > 150 && g > 75
                        && r > bl * 1.35f && g > bl * 1.20f;
            }
        }

        boolean[] seen = new boolean[mask.length];
        List<Candidate> candidates = new ArrayList<>();
        for (int ly = 0; ly < gh; ly++) {
            int row = ly * gw;
            for (int lx = 0; lx < gw; lx++) {
                int idx = row + lx;
                if (!mask[idx] || seen[idx]) continue;

                int minX = lx, maxX = lx, minY = ly, maxY = ly, count = 0;
                long sx = 0, sy = 0;
                ArrayDeque<Integer> q = new ArrayDeque<>();
                q.add(idx);
                seen[idx] = true;

                while (!q.isEmpty()) {
                    int cur = q.removeFirst();
                    int cy = cur / gw;
                    int cx = cur - cy * gw;
                    count++;
                    sx += cx;
                    sy += cy + gy0;
                    minX = Math.min(minX, cx); maxX = Math.max(maxX, cx);
                    minY = Math.min(minY, cy); maxY = Math.max(maxY, cy);

                    if (cx > 0) add(mask, seen, q, cur - 1);
                    if (cx + 1 < gw) add(mask, seen, q, cur + 1);
                    if (cy > 0) add(mask, seen, q, cur - gw);
                    if (cy + 1 < gh) add(mask, seen, q, cur + gw);
                }

                int bw = (maxX - minX + 1) * step;
                int bh = (maxY - minY + 1) * step;
                int area = count * step * step;
                if (bw < 34 || bh < 34 || bw > 120 || bh > 120 || area < 500 || area > 6500) continue;
                float aspect = Math.min(bw, bh) / (float) Math.max(bw, bh);
                if (aspect < 0.58f) continue;
                float density = area / (float) Math.max(1, bw * bh);
                if (density < 0.18f) continue;

                float cx = sx / (float) count * step;
                float cy = sy / (float) count * step;
                candidates.add(new Candidate(cx, cy, Math.max(bw, bh) * 0.5f, area, density));
            }
        }

        if (candidates.isEmpty()) return o;

        final long now = SystemClock.uptimeMillis();
        Candidate best = null;
        float bestScore = -Float.MAX_VALUE;
        float dt = lastTs > 0 ? Math.max(0.008f, Math.min(0.12f, (now - lastTs) / 1000f)) : 0.016f;
        float predictedX = lastX >= 0 ? lastX + filteredVx * dt : -1f;
        float predictedY = lastY >= 0 ? lastY + filteredVy * dt : -1f;

        for (Candidate c : candidates) {
            float score = c.area * (0.45f + c.density);
            // Strong temporal gating after initialization.
            if (lastX >= 0) {
                float dx = c.x - predictedX;
                float dy = c.y - predictedY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float speed = (float) Math.sqrt(filteredVx * filteredVx + filteredVy * filteredVy);
                float gate = Math.max(65f, speed * dt * 2.8f + 45f);
                if (dist > gate) continue;
                score *= 1.0f + 3.0f * Math.max(0f, 1f - dist / gate);
            } else {
                // Before a track exists, prefer the larger compact emoji below the scoreboard.
                score *= 1f + Math.min(1f, Math.max(0f, (c.y - h * 0.13f) / (h * 0.25f)));
            }
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }

        if (best == null) return o;

        float rawVx = lastX >= 0 ? (best.x - lastX) / dt : 0f;
        float rawVy = lastY >= 0 ? (best.y - lastY) / dt : 0f;
        if (lastX >= 0) {
            // Alpha-beta style smoothing; resistant to one-frame segmentation jitter.
            filteredVx = filteredVx * 0.65f + rawVx * 0.35f;
            filteredVy = filteredVy * 0.65f + rawVy * 0.35f;
        }
        lastX = best.x;
        lastY = best.y;
        lastTs = now;

        o.ballFound = true;
        o.ballX = best.x;
        o.ballY = best.y;
        o.ballRadius = best.radius;
        o.confidence = Math.max(0f, Math.min(1f, 0.35f + 0.40f * best.density + (lastX >= 0 ? 0.25f : 0f)));
        return o;
    }

    private static void add(boolean[] mask, boolean[] seen, ArrayDeque<Integer> q, int i) {
        if (mask[i] && !seen[i]) {
            seen[i] = true;
            q.add(i);
        }
    }

    private static final class Candidate {
        final float x, y, radius, density;
        final int area;
        Candidate(float x, float y, float radius, int area, float density) {
            this.x = x; this.y = y; this.radius = radius; this.area = area; this.density = density;
        }
    }
}
