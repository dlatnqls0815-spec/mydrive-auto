package io.github.k8ecs.core;

/**
 * Lateral acceleration from speed x yaw rate. Yaw rate is the gyro reading projected on gravity,
 * so the phone may sit in any orientation and braking/acceleration does not leak in.
 */
public final class LateralEstimator {
    private static final double G = 9.80665;
    private final double tauSec;
    private double filteredMilliG;
    private long lastNanos = -1;

    /** @param tauSec low-pass time constant; 0.25 s removes hand/mount shake without much lag */
    public LateralEstimator(double tauSec) {
        this.tauSec = tauSec;
    }

    /**
     * @param gyro    rad/s in device axes
     * @param gravity gravity vector in device axes (any scale)
     * @return filtered lateral acceleration in milli-g; the sign is the turn direction
     */
    public int update(long tNanos, float[] gyro, float[] gravity, double speedMs) {
        double norm = Math.sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]);
        if (norm < 1e-3) return (int) Math.round(filteredMilliG);
        double yaw = (gyro[0] * gravity[0] + gyro[1] * gravity[1] + gyro[2] * gravity[2]) / norm;
        double raw = speedMs * yaw / G * 1000.0;
        if (lastNanos < 0) {
            filteredMilliG = raw;
        } else {
            double dt = (tNanos - lastNanos) / 1e9;
            if (dt > 0) filteredMilliG += dt / (tauSec + dt) * (raw - filteredMilliG);
        }
        lastNanos = tNanos;
        return (int) Math.round(filteredMilliG);
    }
}
