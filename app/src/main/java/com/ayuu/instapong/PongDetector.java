package com.ayuu.instapong;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Fast temporal detector for the Instagram Pong screen.
 * The Instagram paddle is a roughly 200 px black bar on a 720 px screen,
 * positioned around 86% of the screen height. On some frames Instagram does
 * not render the bar clearly enough for pixel detection, so we use a stable
 * geometry fallback instead of accepting an unrelated dark component.
 */
public final class PongDetector {
    public static final class Observation {
        public boolean ballFound;
        public float ballX, ballY, ballRadius;
        public boolean paddleFound;
        public float paddleX, paddleY, paddleWidth;
        public boolean paddleEstimated;
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

        int paddleTop = (int) (h * 0.805f);
        int paddleBottom = (int) (h * 0.925f);
        int bestRun = 0, bestY = -1, bestStart = -1, bestEnd = -1, bestRows = 0;
        for (int y = paddleTop; y <= paddleBottom; y += 2) {
            int run = 0, runStart = -1;
            for (int x = 8; x < w - 8; x++) {
                int c = b.getPixel(x, y);
                int r = Color.red(c), g = Color.green(c), bl = Color.blue(c);
                boolean dark = r < 72 && g < 72 && bl < 72;
                if (dark) {
                    if (run == 0) runStart = x;
                    run++;
                } else {
                    if (run >= w * 0.18f && run <= w * 0.45f) {
                        int rows = horizontalConsistency(b, runStart, x - 1, y, h);
                        if (rows > bestRows || (rows == bestRows && run > bestRun)) {
                            bestRows = rows;
                            bestRun = run;
                            bestY = y;
                            bestStart = runStart;
                            bestEnd = x - 1;
                        }
                    }
                    run = 0;
                    runStart = -1;
                }
            }
            if (run >= w * 0.18f && run <= w * 0.45f) {
                int rows = horizontalConsistency(b, runStart, w - 9, y, h);
                if (rows > bestRows || (rows == bestRows && run > bestRun)) {
                    bestRows = rows;
                    bestRun = run;
                    bestY = y;
                    bestStart = runStart;
                    bestEnd = w - 9;
                }
            }
        }

        if (bestRun > 0 && bestRows >= 3) {
            o.paddleFound = true;
            o.paddleX = (bestStart + bestEnd) * 0.5f;
            // bestY is near the top of the black bar; use its geometric centre
            // for both trajectory timing and the injected touch point.
            o.paddleY = bestY + bestRun * 0.20f;
            o.paddleWidth = bestRun;
        } else {
            o.paddleFound = true;
            o.paddleEstimated = true;
            o.paddleX = w * 0.50f;
            o.paddleY = h * 0.8625f;
            o.paddleWidth = w * 0.278f;
        }

        final float playBottom = o.paddleY - Math.max(38f, o.paddleWidth * 0.35f);
        final int y0 = Math.max((int) (h * 0.10f), 110);
        final int y1 = Math.min(h - 1, (int) playBottom);
        if (y1 <= y0) return o;

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
                mask[row + gx] = mx > 145 && sat > 135 && r > 165 && g > 75
                        && r > bl * 1.30f && g > bl * 1.15f;
            }
        }

        boolean[] seen = new boolean[mask.length];
        List<Candidate> candidates = new ArrayList<>();
        for (int ly = 0; ly < gh; ly++) {
            for (int lx = 0; lx < gw; lx++) {
                int idx = ly * gw + lx;
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
                if (bw < 32 || bh < 32 || bw > 125 || bh > 125 || area < 450 || area > 6500) continue;
                float aspect = Math.min(bw, bh) / (float) Math.max(bw, bh);
                if (aspect < 0.58f) continue;
                float density = area / (float) Math.max(1, bw * bh);
                if (density < 0.16f) continue;

                float cx = sx / (float) count * step;
                float cy = sy / (float) count * step;
                candidates.add(new Candidate(cx, cy, Math.max(bw, bh) * 0.5f, area, density));
            }
        }

        if (candidates.isEmpty()) return o;

        final long now = SystemClock.uptimeMillis();
        Candidate best = null;
        float bestScore = -Float.MAX_VALUE;
        float dt = lastTs > 0L ? Math.max(0.008f, Math.min(0.12f, (now - lastTs) / 1000f)) : 0.016f;
        float predictedX = lastX >= 0f ? lastX + filteredVx * dt : -1f;
        float predictedY = lastY >= 0f ? lastY + filteredVy * dt : -1f;

        for (Candidate c : candidates) {
            float score = c.area * (0.45f + c.density);
            if (lastX >= 0f) {
                float dx = c.x - predictedX;
                float dy = c.y - predictedY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float speed = (float) Math.sqrt(filteredVx * filteredVx + filteredVy * filteredVy);
                float gate = Math.max(65f, speed * dt * 2.8f + 45f);
                if (dist > gate) continue;
                score *= 1.0f + 3.0f * Math.max(0f, 1f - dist / gate);
            } else {
                score *= 1f + Math.min(1f, Math.max(0f, (c.y - h * 0.13f) / (h * 0.25f)));
            }
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }

        if (best == null) return o;

        float rawVx = lastX >= 0f ? (best.x - lastX) / dt : 0f;
        float rawVy = lastY >= 0f ? (best.y - lastY) / dt : 0f;
        if (lastX >= 0f) {
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
        o.confidence = Math.max(0f, Math.min(1f, 0.55f + 0.40f * best.density));
        return o;
    }

    private static int horizontalConsistency(Bitmap b, int x0, int x1, int y, int h) {
        int rows = 1;
        int width = x1 - x0 + 1;
        for (int d = 1; d <= 4; d++) {
            int yy = y + d * 2;
            if (yy >= h) break;
            int count = 0;
            for (int x = x0; x <= x1; x += Math.max(1, width / 40)) {
                int c = b.getPixel(x, yy);
                if (Color.red(c) < 72 && Color.green(c) < 72 && Color.blue(c) < 72) count++;
            }
            int samples = Math.max(1, (width + Math.max(1, width / 40) - 1) / Math.max(1, width / 40));
            if (count >= samples * 0.75f) rows++;
        }
        return rows;
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
