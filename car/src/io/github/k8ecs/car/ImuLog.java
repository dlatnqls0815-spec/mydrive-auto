package io.github.k8ecs.car;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Motion record for tuning: imu.csv in the log directory, one row per accelerometer window (~10 Hz) while moving,
 * rotated at 4 MB to imu.1.csv. Speed, raw accelerometer and gyro means, computed lateral and longitudinal G, accelerator pedal.
 * No GPS position or heading. World-readable so the USB export (NEMESIS root without DAC override) can copy it.
 */
final class ImuLog {
    private static final long MAX = 4L * 1024 * 1024;
    private final File file, old;  // imu2.csv: 0.2.0 added the pedal column
    private final StringBuilder buf = new StringBuilder();
    private long lastFlushMs;
    private long stillSinceMs = -1;

    ImuLog(String dir) {
        file = new File(dir, "imu2.csv");
        old = new File(dir, "imu2.1.csv");
    }

    /** Called under the engine lock. Rows are kept while moving and for 5 s after stopping. */
    void add(long nowMs, double kmh, double ax, double ay, double az, double gyroMean, int latMilliG, int longMilliG,
             double pedalPct) {
        if (kmh > 0) stillSinceMs = -1;
        else if (stillSinceMs < 0) stillSinceMs = nowMs;
        boolean keep = stillSinceMs < 0 || nowMs - stillSinceMs < 5000;
        if (keep) {
            buf.append(nowMs).append(',')
                    .append(String.format(Locale.US, "%.1f,%.1f,%.1f,%.1f,%.2f", kmh, ax, ay, az, gyroMean))
                    .append(',').append(latMilliG).append(',').append(longMilliG).append(',')
                    .append(Double.isNaN(pedalPct) ? "" : String.format(Locale.US, "%.0f", pedalPct)).append('\n');
        }
        if (nowMs - lastFlushMs >= 2000 || buf.length() > 16384) {
            lastFlushMs = nowMs;
            flush();
        }
    }

    private void flush() {
        if (buf.length() == 0) return;
        try {
            if (file.length() > MAX) file.renameTo(old);
            boolean fresh = !file.exists();
            FileOutputStream out = new FileOutputStream(file, true);
            try {
                if (fresh) out.write("time_ms,kmh,acc0,acc1,acc2,gyro,lat_mg,long_mg,pedal\n".getBytes("US-ASCII"));
                out.write(buf.toString().getBytes("US-ASCII"));
            } finally {
                out.close();
            }
            file.setReadable(true, false);
        } catch (IOException ignored) {
        }
        buf.setLength(0);
    }
}
