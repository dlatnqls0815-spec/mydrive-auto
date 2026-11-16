package io.github.k8ecs.probe;

import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;

/**
 * Read-only probe of the head unit's duplicated GPS UART (dupuart, /dev/ttyGPS2).
 *
 * Opens the port, immediately puts it in raw mode with echo off (hidden android.hardware.SerialPort,
 * which does cfmakeraw-like setup), reads for N seconds, never writes, and prints a summary of
 * UBX message classes/ids and NMEA sentence types plus a few raw samples of sensor messages.
 *
 * Usage: app_process /system/bin io.github.k8ecs.probe.GpsTap [/dev/ttyGPS2] [seconds] [speed]
 */
public final class GpsTap {
    private GpsTap() {}

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "/dev/ttyGPS2";
        final int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int speed = args.length > 2 ? Integer.parseInt(args[2]) : 115200;
        if (seconds < 1 || seconds > 30) throw new IllegalArgumentException("seconds 1..30");

        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(new File(path),
                ParcelFileDescriptor.MODE_READ_ONLY);
        Class<?> spClass = Class.forName("android.hardware.SerialPort");
        Constructor<?> ctor = spClass.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        Object port = ctor.newInstance(path);
        Method open = spClass.getDeclaredMethod("open", ParcelFileDescriptor.class, int.class);
        open.setAccessible(true);
        open.invoke(port, pfd, speed); // raw mode, ECHO off, then flush input
        System.out.println("opened " + path + " raw speed=" + speed);

        FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
        byte[] all = new byte[1 << 20];
        int len = 0;
        long end = System.currentTimeMillis() + seconds * 1000L;
        // Blocking reads; the stream at ~1 Hz+ keeps us moving. A watchdog closes on timeout.
        final ParcelFileDescriptor closeMe = pfd;
        Thread watchdog = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(end_ms(seconds)); closeMe.close(); } catch (Exception ignored) { }
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();
        byte[] buf = new byte[4096];
        while (System.currentTimeMillis() < end && len < all.length - buf.length) {
            int n;
            try { n = in.read(buf); } catch (IOException e) { break; }
            if (n <= 0) break;
            System.arraycopy(buf, 0, all, len, n);
            len += n;
        }
        try {
            Method close = spClass.getDeclaredMethod("close");
            close.setAccessible(true);
            close.invoke(port);
        } catch (Exception ignored) { }
        try { pfd.close(); } catch (Exception ignored) { }
        if (args.length > 3 && "hex".equals(args[3])) {
            for (int off = 0; off < len; off += 64) {
                System.out.println("RAW " + hex(all, off, Math.min(64, len - off)));
            }
        }
        report(all, len, seconds);
    }

    private static long end_ms(int seconds) { return seconds * 1000L + 1500L; }

    static void report(byte[] d, int len, int seconds) {
        System.out.println("bytes=" + len + " in ~" + seconds + "s");
        Map<String, Integer> ubx = new TreeMap<String, Integer>();
        Map<String, Integer> nmea = new TreeMap<String, Integer>();
        Map<String, Integer> samples = new TreeMap<String, Integer>();
        int bad = 0;
        int i = 0;
        while (i < len) {
            int b = d[i] & 0xff;
            if (b == 0xB5 && i + 7 < len && (d[i + 1] & 0xff) == 0x62) {
                int cls = d[i + 2] & 0xff, id = d[i + 3] & 0xff;
                int plen = (d[i + 4] & 0xff) | ((d[i + 5] & 0xff) << 8);
                int total = 6 + plen + 2;
                if (plen > 4096 || i + total > len) { i++; continue; }
                int ca = 0, cb = 0;
                for (int k = i + 2; k < i + 6 + plen; k++) { ca = (ca + (d[k] & 0xff)) & 0xff; cb = (cb + ca) & 0xff; }
                if (ca != (d[i + 6 + plen] & 0xff) || cb != (d[i + 7 + plen] & 0xff)) { bad++; i++; continue; }
                String key = String.format("UBX %02X-%02X len %d", cls, id, plen);
                inc(ubx, key);
                // Sensor fusion (ESF 0x10) and high-rate nav (HNR 0x28): keep a couple of raw samples.
                // HNR-PVT (28-00) carries position, so it is counted but never printed.
                if (cls == 0x10 || (cls == 0x28 && id != 0x00)) {
                    String sk = String.format("%02X-%02X", cls, id);
                    Integer c = samples.get(sk);
                    if (c == null || c < 2) {
                        samples.put(sk, c == null ? 1 : c + 1);
                        System.out.println("sample " + sk + ": " + hex(d, i + 6, Math.min(plen, 96)));
                    }
                }
                i += total;
            } else if (b == '$' && i + 6 < len) {
                StringBuilder s = new StringBuilder();
                int k = i + 1;
                while (k < len && k < i + 8 && d[k] != ',' && d[k] != '*') s.append((char) d[k++]);
                inc(nmea, s.toString());
                while (i < len && d[i] != '\n') i++;
            } else {
                i++;
            }
        }
        for (Map.Entry<String, Integer> e : ubx.entrySet()) System.out.println(e.getKey() + " x" + e.getValue());
        for (Map.Entry<String, Integer> e : nmea.entrySet()) System.out.println("NMEA " + e.getKey() + " x" + e.getValue());
        System.out.println("ubx_bad_checksum=" + bad);
        if (ubx.isEmpty() && nmea.isEmpty()) System.out.println("head: " + hex(d, 0, Math.min(len, 128)));
    }

    private static void inc(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        m.put(k, v == null ? 1 : v + 1);
    }

    private static String hex(byte[] d, int off, int n) {
        StringBuilder s = new StringBuilder();
        for (int k = 0; k < n; k++) s.append(String.format("%02x", d[off + k] & 0xff)).append(k + 1 < n ? " " : "");
        return s.toString();
    }
}
