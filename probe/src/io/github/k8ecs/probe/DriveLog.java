package io.github.k8ecs.probe;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;

import io.github.k8ecs.core.HippoStream;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only drive recorder for calibrating the head unit gyro as a lateral-G source.
 *
 * Records, with wall-clock ms:
 *   G,t,huTs,sum,count        gyro window from /dev/ttyGPS2 (raw, echo off, never written)
 *   A,t,huTs,s0,s1,s2,count   accelerometer windows
 *   S,t,kmh                   BCM getSpeed_KMHUnit (read-only Binder getter), 10 Hz
 *   H,t,deg                   navigation GPS heading from logcat (position fields are not stored)
 * Usage: app_process /system/bin io.github.k8ecs.probe.DriveLog <seconds> <out.csv>
 */
public final class DriveLog {
    private DriveLog() {}

    private static final Pattern HEADING = Pattern.compile("gis\\.extra\\.HEADING=(\\d+)");

    public static void main(String[] args) throws Exception {
        final int seconds = Integer.parseInt(args[0]);
        if (seconds < 5 || seconds > 3600) throw new IllegalArgumentException("seconds 5..3600");
        final PrintWriter out = new PrintWriter(new FileOutputStream(new File(args[1])), false);
        final long end = System.currentTimeMillis() + seconds * 1000L;
        out.println("# k8ecs drive log start " + System.currentTimeMillis());

        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(new File("/dev/ttyGPS2"),
                ParcelFileDescriptor.MODE_READ_ONLY);
        Class<?> spClass = Class.forName("android.hardware.SerialPort");
        Constructor<?> ctor = spClass.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        Object port = ctor.newInstance("/dev/ttyGPS2");
        Method open = spClass.getDeclaredMethod("open", ParcelFileDescriptor.class, int.class);
        open.setAccessible(true);
        open.invoke(port, pfd, 115200);

        Thread speed = new Thread(new Runnable() {
            @Override public void run() { pollSpeed(out, end); }
        });
        speed.setDaemon(true);
        speed.start();
        Thread heading = new Thread(new Runnable() {
            @Override public void run() { readHeading(out, end); }
        });
        heading.setDaemon(true);
        heading.start();

        HippoStream stream = new HippoStream(new HippoStream.Listener() {
            @Override public void onGyro(long ts, int sum, int count) {
                row("G," + System.currentTimeMillis() + "," + ts + "," + sum + "," + count, out);
            }
            @Override public void onAccel(long ts, int[] s, int count) {
                row("A," + System.currentTimeMillis() + "," + ts + "," + s[0] + "," + s[1] + "," + s[2] + "," + count, out);
            }
        });
        FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
        byte[] buf = new byte[4096];
        while (System.currentTimeMillis() < end) {
            int n = in.read(buf);
            if (n <= 0) break;
            stream.feed(buf, 0, n);
        }
        row("# end ok=" + stream.framesOk() + " dropped=" + stream.framesDropped(), out);
        try {
            Method close = spClass.getDeclaredMethod("close");
            close.setAccessible(true);
            close.invoke(port);
        } catch (Exception ignored) { }
        pfd.close();
        synchronized (out) { out.flush(); out.close(); }
        System.exit(0);
    }

    static void row(String line, PrintWriter out) {
        synchronized (out) {
            out.println(line);
            if (line.startsWith("S") || line.startsWith("#")) out.flush();
        }
    }

    static IBinder service(String name) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        return (IBinder) sm.getMethod("getService", String.class).invoke(null, name);
    }

    /** BCM getSpeed_KMHUnit(), transaction 0x2f, read-only. */
    static float speedKmh(IBinder bcm) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.hkmc.bcm.IBCMService");
            bcm.transact(0x2f, data, reply, 0);
            reply.readException();
            return reply.readFloat();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    static void pollSpeed(PrintWriter out, long end) {
        try {
            IBinder bcm = service("com.hkmc.micom.bcm");
            while (System.currentTimeMillis() < end) {
                row("S," + System.currentTimeMillis() + "," + speedKmh(bcm), out);
                Thread.sleep(100);
            }
        } catch (Exception e) {
            row("# speed error " + e, out);
        }
    }

    static String startYear() {
        return new java.text.SimpleDateFormat("yyyy", java.util.Locale.US).format(new java.util.Date());
    }

    static void readHeading(PrintWriter out, long end) {
        Process p = null;
        try {
            // API 19 logcat has no -T; it replays the buffer first. Skip lines logged before we started.
            String startStamp = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date());
            p = Runtime.getRuntime().exec(new String[] {"logcat", "-v", "time", "-s", "GisUtil:D"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while (System.currentTimeMillis() < end && (line = r.readLine()) != null) {
                if (line.length() < 18 || line.substring(0, 18).compareTo(startStamp) < 0) continue;
                Matcher m = HEADING.matcher(line);
                // logcat output to a pipe is block-buffered, so use the line's own time, not arrival time.
                if (m.find()) {
                    long t = new java.text.SimpleDateFormat("yyyy MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                            .parse(startYear() + " " + line.substring(0, 18)).getTime();
                    row("H," + t + "," + m.group(1), out);
                }
            }
        } catch (Exception e) {
            row("# heading error " + e, out);
        } finally {
            if (p != null) p.destroy();
        }
    }
}
