package io.github.k8ecs.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns the head unit's raw single-axis gyro windows into a yaw rate, learning the unknowns on the road.
 *
 * Bias: average raw value while the car stands still (speed 0 for at least 2 s).
 * Scale (deg/s per raw LSB, signed): compared against navigation GPS heading changes in turns.
 * A segment between two heading samples counts when the car kept moving above 15 km/h, the heading
 * changed by at least 30 degrees and it took 3..20 s. The scale is the median of accepted segments
 * once at least 3 agree in sign and lie within 20% of the median. A manual scale overrides learning.
 * Not thread-safe.
 */
public final class GyroCalibrator {
    static final int MIN_SEGMENTS = 3;
    static final double MIN_TURN_DEG = 30.0;
    static final int MIN_KMH = 15;

    private double bias = Double.NaN;
    private double biasSum;
    private int biasCount;
    private long stillSinceMs = -1;

    private double manualScale;
    private double learnedScale = Double.NaN;
    private final List<Double> segments = new ArrayList<Double>();

    // Integration since the last heading sample.
    private double integral;     // sum of (raw - bias) * dt, LSB*s
    private long lastGyroMs = -1;
    private long segStartMs = -1;
    private int segStartHeading = -1;
    private boolean segValid;

    private double yawDegPerSec;
    private long yawAtMs = -1;

    public GyroCalibrator(double manualScale, double savedScale) {
        this.manualScale = manualScale;
        if (savedScale != 0 && !Double.isNaN(savedScale)) learnedScale = savedScale;
    }

    public void setManualScale(double scale) { manualScale = scale; }

    /** Scale in use (deg/s per LSB), or NaN while unknown. */
    public double scale() {
        return manualScale != 0 ? manualScale : learnedScale;
    }

    public double learnedScale() { return learnedScale; }
    public double bias() { return bias; }
    public int segmentCount() { return segments.size(); }
    /** Last accepted turn segment (deg/s per LSB), NaN if none. */
    public double lastSegment() { return segments.isEmpty() ? Double.NaN : segments.get(segments.size() - 1); }

    public boolean ready() {
        return !Double.isNaN(bias) && !Double.isNaN(scale());
    }

    /** Latest yaw rate in deg/s (0 until ready). */
    public double yawDegPerSec() { return yawDegPerSec; }
    public long yawAtMs() { return yawAtMs; }

    /** One gyro window: mean raw value over its samples, received at nowMs, while driving at kmh. */
    public void onGyro(long nowMs, double rawMean, int kmh) {
        if (kmh == 0) {
            if (stillSinceMs < 0) { stillSinceMs = nowMs; biasSum = 0; biasCount = 0; }
            if (nowMs - stillSinceMs >= 2000) {
                biasSum += rawMean;
                biasCount++;
                if (biasCount >= 10) {
                    double fresh = biasSum / biasCount;
                    bias = Double.isNaN(bias) ? fresh : bias * 0.7 + fresh * 0.3;
                    biasSum = 0;
                    biasCount = 0;
                }
            }
        } else {
            stillSinceMs = -1;
        }
        if (Double.isNaN(bias)) {
            lastGyroMs = nowMs;
            return;
        }
        double rate = rawMean - bias;
        if (lastGyroMs >= 0) {
            long dt = nowMs - lastGyroMs;
            if (dt > 0 && dt < 500) integral += rate * dt / 1000.0;
            else segValid = false; // gap in data
        }
        lastGyroMs = nowMs;
        if (kmh < MIN_KMH) segValid = false;
        double s = scale();
        yawDegPerSec = Double.isNaN(s) ? 0 : rate * s;
        yawAtMs = nowMs;
    }

    /** Navigation GPS heading sample (whole degrees, 0..359). */
    public void onHeading(long nowMs, int headingDeg, int kmh) {
        if (segStartMs >= 0 && segValid && kmh >= MIN_KMH) {
            double turn = wrap(headingDeg - segStartHeading);
            long dur = nowMs - segStartMs;
            if (Math.abs(turn) >= MIN_TURN_DEG && dur >= 3000 && dur <= 20000 && Math.abs(integral) > 1e-6) {
                addSegment(turn / integral);
                restart(nowMs, headingDeg, kmh);
                return;
            }
            if (dur <= 20000) return; // keep accumulating this turn
        }
        restart(nowMs, headingDeg, kmh);
    }

    private void restart(long nowMs, int headingDeg, int kmh) {
        segStartMs = nowMs;
        segStartHeading = headingDeg;
        integral = 0;
        segValid = kmh >= MIN_KMH && !Double.isNaN(bias);
    }

    private void addSegment(double k) {
        segments.add(k);
        if (segments.size() > 41) segments.remove(0);
        if (segments.size() < MIN_SEGMENTS) return;
        List<Double> sorted = new ArrayList<Double>(segments);
        Collections.sort(sorted);
        double median = sorted.get(sorted.size() / 2);
        int agree = 0;
        for (double v : segments) {
            if (Math.signum(v) == Math.signum(median) && Math.abs(v - median) <= Math.abs(median) * 0.2) agree++;
        }
        if (agree >= MIN_SEGMENTS && agree * 2 > segments.size()) learnedScale = median;
    }

    static double wrap(double deg) {
        while (deg > 180) deg -= 360;
        while (deg <= -180) deg += 360;
        return deg;
    }

    /** Lateral acceleration in milli-g from speed and yaw rate. */
    public static int lateralMilliG(int kmh, double yawDegPerSec) {
        double v = kmh / 3.6;
        return (int) Math.round(v * Math.toRadians(yawDegPerSec) / 9.80665 * 1000.0);
    }
}
