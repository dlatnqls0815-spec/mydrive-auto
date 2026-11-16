package io.github.k8ecs.core;

/**
 * Lateral acceleration straight from the head unit GPS module's 3-axis accelerometer (16384 LSB/g), so no GPS
 * heading or gyro scale is needed. The caller passes the two horizontal axes: on the K8 head unit gravity is on
 * the middle axis, the first is roughly forward and the third lateral (2026-11-16 drive: 0.83 correlation of the
 * third axis with speed x gyro yaw rate).
 *
 * <ul>
 * <li>Offsets: the two horizontal axes are averaged while the car stands still for 2 s; this removes sensor
 *     bias and mounting tilt (and the slope of the spot where it stopped).</li>
 * <li>Orientation: the module's forward direction in the horizontal plane is learned by correlating the
 *     horizontal acceleration with the longitudinal acceleration derived from vehicle speed (accelerating and
 *     braking). Turning is uncorrelated with it and averages out.</li>
 * <li>Lateral: the horizontal acceleration component across the forward direction. Until the direction is
 *     learned, an estimate is used: the horizontal magnitude with the speed-derived longitudinal part removed.</li>
 * </ul>
 * Both inputs are compared over the same 1 s span. Not thread-safe.
 */
public final class AccelLateral {
    public static final double LSB_PER_G = 16384.0;
    static final int HISTORY = 64;          // windows kept (~6 s at 10 Hz)
    static final long SPAN_MS = 1000;       // span for the longitudinal comparison
    static final long SMOOTH_MS = 500;      // lateral smoothing
    static final double LEARN_TARGET = 0.25; // sum of squared longitudinal g needed to trust the direction
    static final double MIN_LEARN_KMH = 10;

    private final long[] t = new long[HISTORY];
    private final double[] hx = new double[HISTORY], hy = new double[HISTORY], v = new double[HISTORY];
    private int n, head;

    private double offX = Double.NaN, offY = Double.NaN;
    private long stillSinceMs = -1;

    private double cx, cy, a2;            // correlation sums
    private double angle = Double.NaN;    // forward direction (rad) in the sensor's horizontal plane
    private int lateralMilliG;
    private int longMilliG;

    public AccelLateral(double savedAngle) {
        if (!Double.isNaN(savedAngle)) {
            angle = savedAngle;
            cx = Math.cos(savedAngle) * LEARN_TARGET;
            cy = Math.sin(savedAngle) * LEARN_TARGET;
            a2 = LEARN_TARGET;
        }
    }

    public boolean ready() { return !Double.isNaN(offX); }
    public boolean oriented() { return !Double.isNaN(angle); }
    public double angle() { return angle; }
    /** Direction learning progress 0..100. */
    public int progress() { return oriented() ? 100 : (int) Math.min(99, a2 / LEARN_TARGET * 100); }
    /** Latest smoothed lateral acceleration (milli-g, unsigned); 0 until offsets are known. */
    public int lateralMilliG() { return lateralMilliG; }
    /** Latest speed-derived longitudinal acceleration (milli-g, signed). */
    public int longitudinalMilliG() { return longMilliG; }

    public void reset() {
        cx = cy = a2 = 0;
        angle = Double.NaN;
    }

    /** One accelerometer window: mean raw values of the two horizontal axes, at vehicle speed kmh (exact). */
    public void onAccel(long nowMs, double ax, double ay, double kmh) {
        double gx = ax / LSB_PER_G, gy = ay / LSB_PER_G;
        if (kmh <= 0) {
            if (stillSinceMs < 0) stillSinceMs = nowMs;
            if (nowMs - stillSinceMs >= 2000) {
                if (Double.isNaN(offX)) { offX = gx; offY = gy; }
                else { offX = offX * 0.9 + gx * 0.1; offY = offY * 0.9 + gy * 0.1; }
            }
        } else {
            stillSinceMs = -1;
        }
        if (Double.isNaN(offX)) return;

        head = (head + 1) % HISTORY;
        t[head] = nowMs;
        hx[head] = gx - offX;
        hy[head] = gy - offY;
        v[head] = kmh;
        if (n < HISTORY) n++;

        // Mean horizontal acceleration over the last span and over the smoothing window.
        double sx = 0, sy = 0, mx = 0, my = 0;
        int sc = 0, mc = 0;
        long oldestT = nowMs;
        double oldestV = kmh;
        for (int i = 0; i < n; i++) {
            int k = (head - i + HISTORY) % HISTORY;
            long age = nowMs - t[k];
            if (age > SPAN_MS) break;
            sx += hx[k]; sy += hy[k]; sc++;
            oldestT = t[k];
            oldestV = v[k];
            if (age <= SMOOTH_MS) { mx += hx[k]; my += hy[k]; mc++; }
        }
        mx /= mc; my /= mc;
        double dt = (nowMs - oldestT) / 1000.0;
        double along = Double.NaN;
        if (dt >= 0.6 && sc >= 4) {
            along = (kmh - oldestV) / 3.6 / dt / 9.80665; // g, positive when speeding up
            longMilliG = (int) Math.round(along * 1000);
            sx /= sc; sy /= sc;
            if (kmh >= MIN_LEARN_KMH && Math.abs(along) < 0.6) {
                cx += sx * along;
                cy += sy * along;
                a2 += along * along;
                if (a2 >= LEARN_TARGET) angle = Math.atan2(cy, cx);
            }
        }

        double lat;
        if (oriented()) {
            lat = Math.abs(mx * Math.sin(angle) - my * Math.cos(angle));
        } else {
            double h2 = mx * mx + my * my;
            double l2 = Double.isNaN(along) ? 0 : along * along;
            lat = Math.sqrt(Math.max(0, h2 - l2));
        }
        lateralMilliG = (int) Math.round(lat * 1000);
    }
}
