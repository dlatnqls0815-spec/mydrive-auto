package io.github.k8ecs.core;

/**
 * Parser for the head unit's GNSS/DR serial stream as duplicated on /dev/ttyGPS* (K8 Gen5W).
 *
 * Framing (observed 2026-11-15): 0x81 start, 0x82 end; inside a frame 0x80 n (n = 0..3) stands for
 * byte 0x80|n. Payload byte 0/1 = class/id. Two IMU messages are decoded:
 *   70-13: single-axis gyro, sum of raw samples (int32 at 14) and sample count (int32 at 18) per window.
 *   70-14: 3-axis accelerometer, per axis {sum int32, count int32, accumulator, time} from offset 13.
 * Both carry a millisecond head-unit timestamp (uint32 at 4). Units and axis signs are not documented;
 * they are calibrated by the caller (gyro bias at standstill, scale against GPS heading).
 */
public final class HippoStream {
    public interface Listener {
        void onGyro(long tsMs, int sum, int count);
        void onAccel(long tsMs, int[] sums, int count);
    }

    private final Listener listener;
    private final byte[] frame = new byte[512];
    private int len = -1; // -1: waiting for start byte
    private boolean escape;
    private int framesOk;
    private int framesDropped;

    public HippoStream(Listener listener) {
        this.listener = listener;
    }

    public int framesOk() { return framesOk; }
    public int framesDropped() { return framesDropped; }

    public void feed(byte[] data, int off, int n) {
        for (int i = off; i < off + n; i++) {
            int b = data[i] & 0xff;
            if (b == 0x81) { len = 0; escape = false; continue; }
            if (len < 0) continue;
            if (b == 0x82) { handle(len); len = -1; continue; }
            if (escape) {
                escape = false;
                if (b > 3) { framesDropped++; len = -1; continue; }
                b |= 0x80;
            } else if (b == 0x80) {
                escape = true;
                continue;
            }
            if (len >= frame.length) { framesDropped++; len = -1; continue; }
            frame[len++] = (byte) b;
        }
    }

    private void handle(int n) {
        if (n < 3) { framesDropped++; return; }
        int cls = frame[0] & 0xff, id = frame[1] & 0xff;
        if (cls != 0x70 || (id != 0x13 && id != 0x14)) { framesOk++; return; }
        if (id == 0x13) {
            if (n < 23) { framesDropped++; return; }
            int count = i32(18);
            if (count <= 0 || count > 1000) { framesDropped++; return; }
            framesOk++;
            listener.onGyro(u32(4), i32(14), count);
        } else {
            if (n < 13 + 48 + 1) { framesDropped++; return; }
            int count = i32(17);
            if (count <= 0 || count > 1000 || i32(33) != count || i32(49) != count) { framesDropped++; return; }
            framesOk++;
            listener.onAccel(u32(4), new int[] {i32(13), i32(29), i32(45)}, count);
        }
    }

    private int i32(int o) {
        return (frame[o] & 0xff) | ((frame[o + 1] & 0xff) << 8) | ((frame[o + 2] & 0xff) << 16)
                | ((frame[o + 3] & 0xff) << 24);
    }

    private long u32(int o) {
        return i32(o) & 0xffffffffL;
    }
}
