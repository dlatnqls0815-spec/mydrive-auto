package io.github.k8ecs.car;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;

import io.github.k8ecs.core.AccelLateral;
import io.github.k8ecs.core.CarLog;
import io.github.k8ecs.core.DampingController;
import io.github.k8ecs.core.GyroCalibrator;
import io.github.k8ecs.core.PedalController;
import io.github.k8ecs.core.HippoStream;
import io.github.k8ecs.core.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * K8 suspension auto-switch, runs on the head unit as uid 1000 (NEMESIS service, app_process).
 *
 * Inputs (all read-only): BCM speed via Binder getter, GNSS/DR gyro from the unused duplicate GPS port
 * /dev/ttyGPS2 (raw mode, never written), drive mode / suspension feedback / heading from logcat.
 * Output: SystemService.setIndividualDriveMode(1, step) only, while enabled, in the custom drive mode,
 * moving forward. Settings over 127.0.0.1 TCP (head unit app) and Bluetooth RFCOMM (phone).
 */
public final class CarMain {
    static final String VERSION = "0.4.2";
    /** BCM getSpeed_KMHUnit() returns the display speed raw value in 0.5 km/h steps (65 = 32.5 km/h on the cluster). */
    static final float BCM_SPEED_RAW_PER_KMH = 2f;
    static final String LOG_DIR = "/data/nemesis/logs/k8ecs.damping";
    /** Settings and learned state: 0700, owned by this service (see svc/damping.sh). */
    static final String DIR = LOG_DIR + "/private";
    static final int TCP_PORT = 47231;
    /**
     * Abstract Unix socket for the head unit app. A NEMESIS service started at boot has no inet group, so
     * its TCP socket fails with EACCES (seen 2026-11-16); a local socket needs no group (SELinux is off).
     */
    static final String LOCAL_SOCKET = "k8ecs.damping";
    static final UUID RFCOMM_UUID = UUID.fromString("5d1c9b7e-2a4f-4e61-9c3b-7f0e8a6d2c41");
    static final String GPS_PORT = "/dev/ttyGPS2";

    private final Object lock = new Object();
    private Settings settings;
    private DampingController controller;       // suspension
    private DampingController steerController;  // steering
    private PedalController pedalController;    // powertrain
    private final Channel susp = new Channel("suspension", CarLog.SUSPENSION_ITEM, 0, 1, 2, 5, "soft", "firm");
    private final Channel steer = new Channel("steering", CarLog.STEERING_ITEM, 0, 1, 2, 5, "normal", "sport");
    private final Channel eng = new Channel("powertrain", CarLog.POWERTRAIN_ITEM, 2, 1, 4, 5, "eco", "sport");
    private double pedalPct;
    private long pedalAtMs = Long.MIN_VALUE;
    private String pedalState = "starting";
    private GyroCalibrator gyro;
    private AccelLateral accel;
    private double savedAccelAngle = Double.NaN;
    private double kmhExact;
    private double lastGyroMean = Double.NaN;
    private int lastGyroSegments;
    private final ImuLog imuLog = new ImuLog(LOG_DIR);

    // Vehicle state (guarded by lock).
    private int kmh = -1;
    private long kmhAtMs;
    private int mode = -1;          // drive mode byte, -1 unknown
    private int slot = -1;          // fourth mode slot: 1 My Drive, 2 Smart, -1 unknown (kept in state.txt)
    private int lastLoggedMode = -1;
    private int savedMode = -1;     // last drive mode from state.txt
    volatile SeogaemoBridge seogaemo;
    private boolean reverse;
    private int lateralMilliG;
    private int peakMilliG;
    private long imuFrames;
    private String imuState = "starting";

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        launch(false);
        Looper.loop();
    }

    /** Where the HMG-Seogaemo card puts the USB export script when there is no NEMESIS package. */
    static final String CARD_EXPORT_SCRIPT = "/data/nemesis/backup/k8ecs/export_usb.sh";
    static volatile boolean hosted;

    /**
     * Starts the engine threads and returns. hosted = inside a head unit app process (HMG-Seogaemo hook,
     * see io.github.k8ecs.hook.K8Entry) instead of the NEMESIS service's own app_process.
     */
    public static CarMain launch(boolean inHost) {
        hosted = inHost;
        if (inHost) new File(DIR, "dalvik-cache").mkdirs();
        final CarMain m = new CarMain();
        m.load();
        m.initialState();
        start("imu", new Runnable() { public void run() { m.readImu(); } });
        start("logcat", new Runnable() { public void run() { m.readLog(); } });
        start("control", new Runnable() { public void run() { m.control(); } });
        start("tcp", new Runnable() { public void run() { m.serveTcp(); } });
        start("local", new Runnable() { public void run() { m.serveLocal(); } });
        start("rfcomm", new Runnable() { public void run() { m.serveRfcomm(); } });
        final SeogaemoBridge sg = new SeogaemoBridge(m);
        m.seogaemo = sg;
        start("seogaemo", new Runnable() { public void run() { sg.run(); } });
        Log.i("started " + VERSION + (inHost ? " (seogaemo host)" : "") + " " + m.settings.encode());
        return m;
    }

    static void start(String name, final Runnable r) {
        // Never let a failure end the process: inside a host app that would crash the host.
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    r.run();
                } catch (Throwable e) {
                    Log.i("thread " + Thread.currentThread().getName() + " stopped: " + e);
                }
            }
        }, name);
        t.setDaemon(true);
        t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread th, Throwable e) { Log.i("thread " + th.getName() + " died: " + e); }
        });
        t.start();
    }

    /**
     * Drive mode and suspension step at start. The car reports the drive mode only when it changes, so the
     * last mode is kept in state.txt: the car resets to ECO when the ignition goes off (SYS_IGN_STATUS_M: 0,
     * saved then), and a head unit restart alone keeps the car's mode (2026-11-16: restarting the head unit
     * in My Drive left the module on "ECO" for a whole drive). The current step comes from the read-only
     * getter getIndividualDriveMode() (feedback array, index 1). Later changes come from logcat.
     */
    void initialState() {
        synchronized (lock) {
            if (savedMode > 0) {
                mode = savedMode;
                Log.i("drive mode restored " + Integer.toHexString(mode));
            } else {
                mode = CarLog.MODE_ECO;
                Log.i("no saved drive mode: eco");
            }
        }
        try {
            int[] fb = Binder.feedbacks(Binder.service("com.hkmc.micom.system"));
            if (fb == null) throw new IOException("layout");
            synchronized (lock) {
                for (Channel c : channels()) {
                    c.lastFb = fb[c.item];
                    int step = c.stepFromFeedback(fb[c.item]);
                    Log.i(c.name + " at start: feedback " + fb[c.item] + " = " + c.stepName(step));
                    if (step == 0 || step == 1) { c.carStep = step; deciderLevel(c, step); }
                }
            }
        } catch (Exception e) {
            Log.i("item state at start unknown: " + e);
        }
    }

    // ---------- settings and learned state ----------

    void load() {
        Settings s = Settings.defaults();
        try {
            String text = Files.read(new File(DIR, "config.txt"));
            if (text != null) s = Settings.parse(text, s);
        } catch (IllegalArgumentException e) {
            Log.i("config ignored: " + e.getMessage());
        }
        double saved = 0;
        int savedSlot = -1;
        String st = Files.read(new File(DIR, "state.txt"));
        if (st != null) {
            for (String tok : st.trim().split("\\s+")) {
                if (tok.startsWith("learnedScale=")) {
                    try { saved = Double.parseDouble(tok.substring(13)); } catch (NumberFormatException ignored) { }
                }
                if (tok.startsWith("driveMode=")) {
                    try { savedMode = Integer.parseInt(tok.substring(10)); } catch (NumberFormatException ignored) { }
                }
                if (tok.startsWith("myDriveSlot=")) {
                    try { savedSlot = Integer.parseInt(tok.substring(12)); } catch (NumberFormatException ignored) { }
                }
                if (tok.startsWith("feedbackMap=")) {
                    for (String kv : tok.substring(12).split(",")) {
                        int colon = kv.indexOf(':');
                        int dot = kv.indexOf('.');
                        if (colon < 0 || dot < 0) continue;
                        try {
                            int item = Integer.parseInt(kv.substring(0, dot));
                            int f = Integer.parseInt(kv.substring(colon + 1));
                            for (Channel c : channels()) if (c.item == item) c.learned.put(kv.substring(0, colon), f);
                        } catch (NumberFormatException ignored) { }
                    }
                }
                if (tok.startsWith("accelAxes02=")) {
                    try { savedAccelAngle = Double.parseDouble(tok.substring(12)); } catch (NumberFormatException ignored) { }
                }
            }
        }
        synchronized (lock) {
            settings = s;
            buildControllersLocked(s);
            gyro = new GyroCalibrator(s.gyroScale, saved);
            accel = new AccelLateral(savedAccelAngle);
            slot = savedSlot;
        }
        if (saved != 0) Log.i("gyro scale restored " + saved);
        if (!Double.isNaN(savedAccelAngle)) Log.i("accel direction restored " + savedAccelAngle);
    }

    String apply(String pairs) {
        synchronized (lock) {
            Settings next = Settings.parse(pairs, settings);
            Files.write(new File(DIR, "config.txt"), next.encode().replace(' ', '\n') + "\n");
            settings = next;
            String low = eng.name0;
            buildControllersLocked(next);
            gyro.setManualScale(next.gyroScale);
            Log.i("settings " + next.encode());
            if (!low.equals(eng.name0)) {
                // Eco/Normal choice changed: read the powertrain item again so its step uses the new mapping.
                try {
                    int[] fb = Binder.feedbacks(Binder.service("com.hkmc.micom.system"));
                    if (fb != null) onFeedbackLocked(eng, fb[eng.item]);
                } catch (Exception e) {
                    Log.i("powertrain re-read failed: " + e);
                }
            }
            return next.encode();
        }
    }

    Channel[] channels() {
        return new Channel[] {susp, steer, eng};
    }

    /** New controllers for new settings or after a halt; they start from the steps the car last reported. */
    void buildControllersLocked(Settings s) {
        controller = new DampingController(s.toRules());
        steerController = new DampingController(s.steerRules());
        pedalController = new PedalController(s.engPedalPct, s.engHoldMs, s.maxPerMinute);
        if (eng.setLow(s.engNormal)) {
            Log.i("powertrain low step " + eng.name0 + " (value " + eng.value0 + ", feedback " + eng.fb0 + ")");
            eng.carStep = -1; // re-read from the next feedback with the new mapping
        }
        for (Channel c : channels()) {
            c.clearHalt();
            if (c.carStep == 0 || c.carStep == 1) deciderLevel(c, c.carStep);
        }
    }

    void deciderLevel(Channel c, int step) {
        if (c == susp) controller.onCarLevel(step);
        else if (c == steer) steerController.onCarLevel(step);
        else pedalController.onCarLevel(step);
    }

    void haltLocked(Channel c, String why) {
        c.halted = true;
        if (c == susp) controller.halt();
        else if (c == steer) steerController.halt();
        else pedalController.halt();
        Log.i("HALT " + c.name + ": " + why);
    }

    /** The item is driven: its own rule is on, or another item links into it. */
    boolean channelOn(Channel c) {
        Settings s = settings;
        return s.enabled && (c == susp ? s.suspOn || s.engLinkSusp
                : c == steer ? s.steerOn || s.engLinkSteer || s.suspLinkSteer
                : s.engOn || s.suspLinkEng);
    }

    // ---------- IMU from the duplicated GPS port ----------

    void readImu() {
        while (true) {
            ParcelFileDescriptor pfd = null;
            Object port = null;
            Class<?> spClass = null;
            try {
                pfd = ParcelFileDescriptor.open(new File(GPS_PORT), ParcelFileDescriptor.MODE_READ_ONLY);
                spClass = Class.forName("android.hardware.SerialPort");
                Constructor<?> ctor = spClass.getDeclaredConstructor(String.class);
                ctor.setAccessible(true);
                port = ctor.newInstance(GPS_PORT);
                Method open = spClass.getDeclaredMethod("open", ParcelFileDescriptor.class, int.class);
                open.setAccessible(true);
                open.invoke(port, pfd, 115200); // raw, echo off: nothing ever goes back to the GPS module
                synchronized (lock) { imuState = "ok"; }
                Log.i("imu open");
                HippoStream stream = new HippoStream(new HippoStream.Listener() {
                    @Override public void onGyro(long ts, int sum, int count) { onGyroWindow(sum, count); }
                    @Override public void onAccel(long ts, int[] sums, int count) { onAccelWindow(sums, count); }
                });
                InputStream in = new FileInputStream(pfd.getFileDescriptor());
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) stream.feed(buf, 0, n);
                Log.i("imu closed");
            } catch (Exception e) {
                synchronized (lock) { imuState = "error " + e.getClass().getSimpleName(); }
                Log.i("imu error " + e);
            } finally {
                if (port != null) {
                    try {
                        Method close = spClass.getDeclaredMethod("close");
                        close.setAccessible(true);
                        close.invoke(port);
                    } catch (Exception ignored) { }
                }
                if (pfd != null) try { pfd.close(); } catch (IOException ignored) { }
            }
            sleep(5000);
        }
    }

    void onGyroWindow(int sum, int count) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            imuFrames++;
            int v = kmhFresh(now) ? kmh : 0;
            lastGyroMean = sum / (double) count;
            gyro.onGyro(now, lastGyroMean, v);
        }
    }

    /** Lateral G from the accelerometer (the gyro is only learned and logged for comparison). */
    void onAccelWindow(int[] sums, int count) {
        long now = System.currentTimeMillis();
        double ax = sums[0] / (double) count, ay = sums[1] / (double) count, az = sums[2] / (double) count;
        synchronized (lock) {
            double v = kmhFresh(now) ? kmhExact : 0;
            boolean wasOriented = accel.oriented();
            // Gravity is on the middle axis; the first is forward-ish, the third lateral.
            accel.onAccel(now, ax, az, v);
            if (accel.ready()) {
                lateralMilliG = accel.lateralMilliG();
                peakMilliG = Math.max(peakMilliG, lateralMilliG);
                controller.onLateral(now, lateralMilliG);
            }
            if (!wasOriented && accel.oriented()) {
                Log.i("accel direction learned " + accel.angle());
                saveStateLocked();
            }
            imuLog.add(now, v, ax, ay, az, lastGyroMean, lateralMilliG, accel.longitudinalMilliG(),
                    now - pedalAtMs <= 1000 ? pedalPct : Double.NaN);
        }
    }

    void saveStateLocked() {
        StringBuilder b = new StringBuilder();
        if (!Double.isNaN(gyro.learnedScale())) b.append("learnedScale=").append(gyro.learnedScale()).append(' ');
        if (accel.oriented()) b.append("accelAxes02=").append(accel.angle()).append(' ');
        if (slot > 0) b.append("myDriveSlot=").append(slot).append(' ');
        if (mode > 0) b.append("driveMode=").append(mode).append(' ');
        StringBuilder fm = new StringBuilder();
        for (Channel c : channels())
            for (Map.Entry<String, Integer> e : c.learned.entrySet())
                fm.append(fm.length() > 0 ? "," : "").append(e.getKey()).append(':').append(e.getValue());
        if (fm.length() > 0) b.append("feedbackMap=").append(fm).append(' ');
        Files.write(new File(DIR, "state.txt"), b.toString().trim() + "\n");
    }

    boolean kmhFresh(long now) {
        return kmh >= 0 && now - kmhAtMs < 1000;
    }

    // ---------- logcat: mode, feedback, ignition, reverse, heading ----------

    void readLog() {
        while (true) {
            Process p = null;
            try {
                p = new ProcessBuilder("logcat", "-b", "main", "-b", "system", "-v", "time", "-s",
                        "SystemService:V", "GisUtil:D").redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) handleLine(line);
                Log.i("logcat ended");
            } catch (IOException e) {
                Log.i("logcat error " + e);
            } finally {
                if (p != null) p.destroy();
            }
            sleep(2000);
        }
    }

    void handleLine(String line) {
        int m = CarLog.driveMode(line);
        if (m >= 0) {
            synchronized (lock) {
                boolean changed = m != mode;
                mode = m;
                if (changed) saveStateLocked();
                if (m != lastLoggedMode) Log.i("drive mode " + Integer.toHexString(m) + (CarLog.isMyDrive(m, slot) ? " (my drive)" : ""));
                lastLoggedMode = m;
            }
            return;
        }
        int sl = CarLog.myDriveSlot(line);
        if (sl > 0) {
            synchronized (lock) {
                if (sl != slot) {
                    slot = sl;
                    Log.i("fourth mode slot " + (sl == CarLog.SLOT_MY_DRIVE ? "my drive" : "smart"));
                    saveStateLocked();
                }
            }
            return;
        }
        int[] fb = CarLog.feedbacks(line);
        if (fb != null) {
            synchronized (lock) {
                for (Channel c : channels()) onFeedbackLocked(c, fb[c.item]);
            }
            return;
        }
        Boolean ign = CarLog.ignition(line);
        if (ign != null) {
            synchronized (lock) {
                if (!ign.booleanValue()) {
                    // Ignition off: the car comes back in ECO next time.
                    mode = CarLog.MODE_ECO;
                    Log.i("ignition off, next start eco");
                    saveStateLocked();
                }
                peakMilliG = 0;
            }
            return;
        }
        Boolean rev = CarLog.reverse(line);
        if (rev != null) {
            synchronized (lock) { reverse = rev.booleanValue(); }
            return;
        }
        int h = CarLog.heading(line);
        if (h >= 0) {
            long now = System.currentTimeMillis();
            synchronized (lock) {
                int v = kmhFresh(now) ? kmh : 0;
                double before = gyro.learnedScale();
                gyro.onHeading(now, h, v);
                double after = gyro.learnedScale();
                if (gyro.segmentCount() != lastGyroSegments) {
                    lastGyroSegments = gyro.segmentCount();
                    Log.i("gyro turn segment " + gyro.lastSegment() + " (" + lastGyroSegments + ")");
                }
                if (!(Double.isNaN(before) && Double.isNaN(after)) && (Double.isNaN(before) || before != after)) {
                    Log.i("gyro scale learned " + after + " segments=" + gyro.segmentCount() + " bias=" + gyro.bias());
                    saveStateLocked();
                }
            }
        }
    }

    void onFeedbackLocked(Channel c, int fb) {
        long now = System.currentTimeMillis();
        int prev = c.lastFb;
        c.lastFb = fb;
        int step = c.stepFromFeedback(fb);
        if (c.pendingStep >= 0) {
            int want = c.pendingStep;
            boolean moved = fb != c.fbAtSend && fb != c.fbFor(1 - want);
            if (step == want || (step == 9 || !c.confirmed(want)) && moved) {
                // The car changed in answer to our command: this is what it reports for that step.
                // Only an actual change teaches; a value that was already there proves nothing.
                if (fb != c.fbAtSend && (!c.confirmed(want) || c.fbFor(want) != fb)) {
                    c.learned.put(c.key(want), fb);
                    Log.i("learned " + c.name + " " + c.stepName(want) + " = feedback " + fb);
                    saveStateLocked();
                }
                step = want;
            }
        }
        if (fb == prev && step == c.carStep && c.pendingStep < 0) return; // nothing new for this item
        if (step != c.carStep) Log.i("car " + c.name + " " + c.stepName(step) + (step == 9 ? " (" + fb + ")" : ""));
        c.carStep = step;
        if (c.pendingStep >= 0) {
            if (step == c.pendingStep) {
                c.mismatches = 0;
                c.pendingStep = -1;
            } else if (now - c.pendingSinceMs > 300) {
                c.mismatches++;
                Log.i(c.name + " feedback " + c.stepName(step) + " but sent " + c.stepName(c.pendingStep) + " (" + c.mismatches + ")");
                c.pendingStep = -1;
                if (c.mismatches >= 2) haltLocked(c, "car did not follow twice");
            }
        } else if (step == 0 || step == 1) {
            deciderLevel(c, step); // driver changed it on the screen
        }
        // An unknown value halts only once both steps are known on this car; before that it may be a value
        // the car starts in (other models), and the first command will teach the mapping.
        if (step == 9 && channelOn(c) && !c.halted && c.confirmed(0) && c.confirmed(1))
            haltLocked(c, "unexpected value " + fb);
    }

    // ---------- control loop ----------

    void control() {
        IBinder bcm = null, sys = null, hscan = null;
        long lastSummary = 0, lastPedalTry = 0;
        while (true) {
            sleep(100);
            long now = System.currentTimeMillis();
            try {
                if (bcm == null) bcm = Binder.service("com.hkmc.micom.bcm");
                if (sys == null) sys = Binder.service("com.hkmc.micom.system");
                float v = Binder.speedKmh(bcm) / BCM_SPEED_RAW_PER_KMH;
                // Accelerator pedal (read-only getter); a failure only disables the powertrain rule.
                float pedal = Float.NaN;
                if (hscan != null || now - lastPedalTry > 5000) {
                    try {
                        if (hscan == null) { lastPedalTry = now; hscan = Binder.service("tm_hscan_service"); }
                        pedal = Binder.pedalPct(hscan);
                    } catch (Exception e) {
                        hscan = null;
                        synchronized (lock) { if (!pedalState.startsWith("error")) Log.i("pedal read failed: " + e); pedalState = "error"; }
                    }
                }
                Channel[] sendCh = new Channel[3];
                int[] sendStep = new int[3];
                String[] sendWhy = new String[3];
                int n = 0;
                synchronized (lock) {
                    kmh = Math.round(v);
                    kmhExact = v;
                    kmhAtMs = now;
                    if (!Float.isNaN(pedal)) { pedalPct = pedal; pedalAtMs = now; pedalState = "ok"; }
                    for (Channel c : channels()) {
                        if (c.pendingStep >= 0 && now - c.pendingSinceMs > 2000) {
                            c.mismatches++;
                            Log.i("no feedback for " + c.name + " " + c.stepName(c.pendingStep) + " (" + c.mismatches + ")");
                            c.pendingStep = -1;
                            if (c.mismatches >= 2) haltLocked(c, "no feedback twice");
                        }
                    }
                    boolean custom = CarLog.isMyDrive(mode, slot);
                    boolean moving = kmh > 0 && !reverse;
                    // Own rules first (from the previous tick), then the link options.
                    boolean suspWants = settings.suspOn && controller.ownLevel() >= 1;
                    boolean engWants = settings.engOn && pedalController.isSportWanted(now);
                    boolean suspLinked = settings.engLinkSusp && engWants;
                    boolean steerLinked = (settings.engLinkSteer && engWants) || (settings.suspLinkSteer && suspWants);
                    boolean engLinked = settings.suspLinkEng && suspWants;
                    int s1 = controller.update(now, kmh, custom && channelOn(susp), moving, suspLinked, settings.suspOn);
                    int s2 = steerController.update(now, kmh, custom && channelOn(steer), moving, steerLinked, settings.steerOn);
                    int s3 = pedalController.update(now, pedalPct, pedalAtMs, custom && channelOn(eng), moving, engLinked,
                            settings.engOn);
                    if (s1 != DampingController.NO_COMMAND) {
                        sendCh[n] = susp; sendStep[n] = s1;
                        sendWhy[n++] = !moving ? "stopped" : suspLinked && controller.ownLevel() < 1 && s1 == 1 ? "link from engine"
                                : controller.isCornerActive() ? "corner " + lateralMilliG + "mg" : "speed " + kmh + "km/h";
                    }
                    if (s2 != DampingController.NO_COMMAND) {
                        sendCh[n] = steer; sendStep[n] = s2;
                        sendWhy[n++] = !moving ? "stopped" : steerLinked && steerController.ownLevel() < 1 && s2 == 1
                                ? (settings.engLinkSteer && engWants ? "link from engine" : "link from suspension")
                                : steerController.isCornerActive() ? "corner " + lateralMilliG + "mg" : "speed " + kmh + "km/h";
                    }
                    if (s3 != PedalController.NO_COMMAND) {
                        sendCh[n] = eng; sendStep[n] = s3;
                        sendWhy[n++] = !moving ? "stopped" : engLinked && !pedalController.isSportWanted(now) && s3 == 1 ? "link from suspension"
                                : "pedal " + Math.round(pedalPct) + "%";
                    }
                    for (int i = 0; i < n; i++) {
                        Channel c = sendCh[i];
                        c.pendingStep = sendStep[i];
                        c.pendingSinceMs = now;
                        c.fbAtSend = c.lastFb;
                        c.sent++;
                        c.lastReason = sendWhy[i];
                    }
                    if (now - lastSummary > 10000 && kmh > 0) {
                        lastSummary = now;
                        Log.i("drive " + statusLocked());
                    }
                }
                for (int i = 0; i < n; i++) {
                    Channel c = sendCh[i];
                    Binder.setItem(sys, c.item, sendStep[i] == 1 ? c.value1 : c.value0);
                    Log.i("SEND " + c.name + " " + c.stepName(sendStep[i]) + " (" + sendWhy[i] + ")");
                }
            } catch (Exception e) {
                Log.i("control error " + e);
                bcm = null;
                sys = null;
                sleep(2000);
            }
        }
    }



    String statusLine() {
        synchronized (lock) { return statusLocked(); }
    }

    String statusLocked() {
        boolean custom = CarLog.isMyDrive(mode, slot);
        String scale = Double.isNaN(gyro.scale()) ? "learning" : String.format(Locale.US, "%.5f", gyro.scale());
        int sent = susp.sent + steer.sent + eng.sent;
        String last = susp.lastReason;
        return "enabled=" + (settings.enabled ? 1 : 0) + " kmh=" + kmh + " lat=" + lateralMilliG + " peak=" + peakMilliG
                + " pedal=" + (pedalState.equals("ok") ? Integer.toString((int) Math.round(pedalPct)) : "?")
                + " mode=" + (mode < 0 ? "?" : Integer.toHexString(mode)) + " custom=" + (custom ? 1 : 0) + " slot=" + slot
                + " step=" + susp.stepName(susp.carStep) + " corner=" + (controller.isCornerActive() ? 1 : 0)
                + " steer=" + steer.stepName(steer.carStep) + " eng=" + eng.stepName(eng.carStep)
                + " suspOn=" + (channelOn(susp) ? 1 : 0) + " steerOn=" + (channelOn(steer) ? 1 : 0) + " engOn=" + (channelOn(eng) ? 1 : 0)
                + " sent=" + sent + " suspSent=" + susp.sent + " steerSent=" + steer.sent + " engSent=" + eng.sent
                + " last=" + last.replace(' ', '_')
                + " halted=" + (susp.halted || steer.halted || eng.halted ? 1 : 0)
                + " haltedItems=" + (susp.halted ? "S" : "") + (steer.halted ? "H" : "") + (eng.halted ? "E" : "") + "-"
                + " imu=" + imuState.replace(' ', '_')
                + " frames=" + imuFrames + " gyroScale=" + scale + " turns=" + gyro.segmentCount()
                + " bias=" + (Double.isNaN(gyro.bias()) ? "?" : String.format(Locale.US, "%.1f", gyro.bias()))
                + " latcal=" + (!accel.ready() ? "wait" : accel.oriented() ? "ok" : "est" + accel.progress())
                + " cfg=" + cfgId(settings.encode()) + " ver=" + VERSION;
    }

    /** Settings fingerprint in status, so the apps notice changes made elsewhere (phone, head unit, card). */
    static String cfgId(String encoded) {
        return Integer.toHexString(encoded.hashCode());
    }

    // ---------- settings protocol (TCP for the head unit app, RFCOMM for the phone) ----------

    String handleCommand(String line) {
        line = line.trim();
        try {
            if (line.equals("HELLO")) return "K8ECS 1 " + VERSION;
            if (line.equals("GET")) { synchronized (lock) { return "CFG " + settings.encode(); } }
            if (line.equals("STATUS")) { synchronized (lock) { return "ST " + statusLocked(); } }
            if (line.startsWith("SET ")) {
                String r = apply(line.substring(4));
                SeogaemoBridge sg = seogaemo;
                if (sg != null) { synchronized (lock) { sg.syncToCard(settings); } }
                return "OK " + r;
            }
            if (line.equals("RESETCAL")) {
                synchronized (lock) {
                    new File(DIR, "state.txt").delete();
                    gyro = new GyroCalibrator(settings.gyroScale, 0);
                    accel.reset();
                    lastGyroSegments = 0;
                    saveStateLocked();
                    Log.i("gyro calibration reset");
                }
                return "OK";
            }
            if (line.equals("EXPORTUSB")) return exportUsb();
            if (line.equals("RESUME")) {
                synchronized (lock) {
                    buildControllersLocked(settings);
                    Log.i("resumed after halt");
                }
                return "OK";
            }
            return "ERR unknown command";
        } catch (IllegalArgumentException e) {
            return "ERR " + e.getMessage();
        }
    }

    private final Object exportLock = new Object();

    /**
     * Copies the logs to a plugged-in USB stick. The package's export script runs through NEMESIS root
     * (group sdcard_rw), the context already seen writing USB sticks on this head unit. Returns "OK ..." or "ERR ...".
     */
    String exportUsb() {
        synchronized (exportLock) {
            String pkg = System.getenv("K8ECS_PKG");
            File script = new File(pkg == null || pkg.length() == 0 ? "/data/nemesis/apps/k8ecs.damping" : pkg,
                    "bin/export_usb.sh");
            // The card's copy sits in a root-only directory: the engine cannot see it, NEMESIS root runs it.
            if (!script.isFile()) script = new File(CARD_EXPORT_SCRIPT);
            if (!hosted && !script.isFile()) return "ERR script_missing";
            Log.i("usb export requested");
            Process p = null;
            Thread killer = null;
            try {
                p = new ProcessBuilder("/system/xbin/nemesis", "root", "/system/bin/sh " + script.getPath())
                        .redirectErrorStream(true).start();
                final Process fp = p;
                killer = new Thread(new Runnable() {
                    public void run() {
                        try { Thread.sleep(30000); fp.destroy(); } catch (InterruptedException ignored) { }
                    }
                }, "export-timeout");
                killer.setDaemon(true);
                killer.start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                String result = null, l;
                while ((l = r.readLine()) != null) {
                    l = l.trim();
                    if (l.startsWith("OK ") || l.startsWith("ERR ")) result = l;
                }
                p.waitFor();
                if (result == null) result = "ERR no_result";
                Log.i("usb export " + result);
                return result;
            } catch (Exception e) {
                Log.i("usb export error " + e);
                return "ERR failed";
            } finally {
                if (killer != null) killer.interrupt();
                if (p != null) p.destroy();
            }
        }
    }

    void session(InputStream in, OutputStream out) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            if (line.length() > 1000) break;
            out.write((handleCommand(line) + "\n").getBytes("UTF-8"));
            out.flush();
        }
    }

    void serveTcp() {
        String lastError = null;
        while (true) {
            ServerSocket server = null;
            try {
                server = new ServerSocket(TCP_PORT, 4, InetAddress.getByName("127.0.0.1"));
                while (true) {
                    final Socket s = server.accept();
                    start("tcp-client", new Runnable() {
                        public void run() {
                            try { s.setSoTimeout(120000); session(s.getInputStream(), s.getOutputStream()); }
                            catch (IOException ignored) { }
                            finally { try { s.close(); } catch (IOException ignored) { } }
                        }
                    });
                }
            } catch (IOException e) {
                // A boot-started NEMESIS service gets EACCES every time; the head unit app uses the local socket.
                if (!String.valueOf(e).equals(lastError)) Log.i("tcp error " + e + " (logged once)");
                lastError = String.valueOf(e);
            } finally {
                if (server != null) try { server.close(); } catch (IOException ignored) { }
            }
            sleep(5000);
        }
    }

    void serveLocal() {
        while (true) {
            android.net.LocalServerSocket server = null;
            try {
                server = new android.net.LocalServerSocket(LOCAL_SOCKET);
                while (true) {
                    final android.net.LocalSocket s = server.accept();
                    start("local-client", new Runnable() {
                        public void run() {
                            try { s.setSoTimeout(120000); session(s.getInputStream(), s.getOutputStream()); }
                            catch (IOException ignored) { }
                            finally { try { s.close(); } catch (IOException ignored) { } }
                        }
                    });
                }
            } catch (IOException e) {
                Log.i("local socket error " + e);
            } finally {
                if (server != null) try { server.close(); } catch (IOException ignored) { }
            }
            sleep(5000);
        }
    }

    void serveRfcomm() {
        while (true) {
            BluetoothServerSocket server = null;
            try {
                BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                if (a == null || !a.isEnabled()) { sleep(10000); continue; }
                server = a.listenUsingRfcommWithServiceRecord("K8 Suspension", RFCOMM_UUID);
                while (true) {
                    BluetoothSocket c = server.accept();
                    Log.i("phone connected");
                    try { session(c.getInputStream(), c.getOutputStream()); }
                    catch (IOException ignored) { }
                    finally { try { c.close(); } catch (IOException ignored) { } }
                }
            } catch (Exception e) {
                Log.i("rfcomm error " + e.getClass().getSimpleName());
            } finally {
                if (server != null) try { server.close(); } catch (IOException ignored) { }
            }
            sleep(10000);
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    // ---------- helpers ----------

    static final class Binder {
        static IBinder service(String name) throws Exception {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, name);
            if (b == null) throw new IOException("no service " + name);
            return b;
        }

        /** BCM getSpeed_KMHUnit(), transaction 0x2f (read-only). */
        static float speedKmh(IBinder bcm) throws Exception {
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken("com.hkmc.bcm.IBCMService");
                bcm.transact(0x2f, d, r, 0);
                r.readException();
                return r.readFloat();
            } finally { d.recycle(); r.recycle(); }
        }

        /**
         * getIndividualDriveMode(), transaction 0x31 (read-only). Reply layout seen on K8: exception, non-null
         * flag, 5 ints (ASD mode, ASD on, valid, current mode, type), device-on byte[16], feedback byte[16], ...
         * Returns feedback of items 0..2, or null when the layout does not match.
         */
        static int[] feedbacks(IBinder sys) throws Exception {
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken("com.hkmc.micom.ISystemService");
                sys.transact(0x31, d, r, 0);
                r.readException();
                if (r.readInt() == 0) return null;
                for (int i = 0; i < 5; i++) r.readInt();
                byte[] on = r.createByteArray();
                byte[] fb = r.createByteArray();
                if (on == null || fb == null || on.length != 16 || fb.length != 16) return null;
                return new int[] {fb[0] & 0xff, fb[1] & 0xff, fb[2] & 0xff};
            } finally { d.recycle(); r.recycle(); }
        }

        /** tm_hscan_service getAccelPdlVal(), transaction 0xbd (read-only; name checked in the K8 framework). */
        static float pedalPct(IBinder hscan) throws Exception {
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken("com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService");
                hscan.transact(0xbd, d, r, 0);
                r.readException();
                float v = r.readFloat();
                if (Float.isNaN(v) || Float.isInfinite(v) || v < 0 || v > 100.01f) return Float.NaN;
                return Math.min(v, 100f);
            } finally { d.recycle(); r.recycle(); }
        }

        /** SystemService.setIndividualDriveMode(item, value), transaction 0x30 — same call as the stock screen. */
        static void setItem(IBinder sys, int item, int value) throws Exception {
            if (item < 0 || item > 2 || value < 0 || value > 2) throw new IllegalArgumentException("item/value");
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken("com.hkmc.micom.ISystemService");
                d.writeInt(item);
                d.writeInt(value);
                sys.transact(0x30, d, r, 0);
                r.readException();
            } finally { d.recycle(); r.recycle(); }
        }
    }

    /** One My Drive item: its command values, the feedback it reports, and the send/feedback bookkeeping. */
    static final class Channel {
        final String name;
        final int item, value1, fb1;
        final String name1;
        int value0, fb0;   // the low step; the powertrain one switches between Eco and Normal
        String name0;
        int carStep = -1, pendingStep = -1, mismatches, sent;
        int lastFb = -1, fbAtSend = -1;
        /**
         * Feedback learned on this car, keyed "item.value" (value = what is sent). Other Hyundai/Kia cars may
         * answer with other feedback numbers than the K8; the first answer to each value is remembered.
         */
        Map<String, Integer> learned = new HashMap<String, Integer>();
        long pendingSinceMs;
        String lastReason = "-";
        boolean halted;

        Channel(String name, int item, int value0, int value1, int fb0, int fb1, String name0, String name1) {
            this.name = name; this.item = item; this.value0 = value0; this.value1 = value1;
            this.fb0 = fb0; this.fb1 = fb1; this.name0 = name0; this.name1 = name1;
        }

        /**
         * Powertrain low step: Eco (value 2, feedback 4, confirmed on the hybrid K8) or Normal (value 0;
         * feedback 2 as on the steering and suspension items, not yet seen on a powertrain). Returns true if changed.
         */
        boolean setLow(boolean normal) {
            int v = normal ? 0 : 2, f = normal ? 2 : 4;
            if (v == value0 && f == fb0) return false;
            value0 = v; fb0 = f; name0 = normal ? "normal" : "eco";
            return true;
        }

        int value(int step) { return step == 1 ? value1 : value0; }
        String key(int step) { return item + "." + value(step); }
        boolean confirmed(int step) { return learned.containsKey(key(step)); }
        int fbFor(int step) {
            Integer f = learned.get(key(step));
            return f != null ? f : step == 1 ? fb1 : fb0;
        }
        int stepFromFeedback(int fb) {
            int f0 = fbFor(0), f1 = fbFor(1);
            return fb == f0 && f0 != f1 ? 0 : fb == f1 && f0 != f1 ? 1 : 9;
        }
        String stepName(int s) { return s == 0 ? name0 : s == 1 ? name1 : s == 9 ? "other" : "unknown"; }
        void clearHalt() { halted = false; mismatches = 0; pendingStep = -1; }
    }

    static final class Files {
        static String read(File f) {
            if (!f.isFile() || f.length() > 65536) return null;
            try {
                FileInputStream in = new FileInputStream(f);
                try {
                    byte[] b = new byte[(int) f.length()];
                    int n = 0;
                    while (n < b.length) { int k = in.read(b, n, b.length - n); if (k < 0) break; n += k; }
                    return new String(b, 0, n, "UTF-8");
                } finally { in.close(); }
            } catch (IOException e) {
                return null;
            }
        }

        static void write(File f, String text) {
            try {
                File tmp = new File(f.getPath() + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                try { out.write(text.getBytes("UTF-8")); out.getFD().sync(); } finally { out.close(); }
                if (!tmp.renameTo(f)) Log.i("could not save " + f.getName());
            } catch (IOException e) {
                Log.i("save error " + f.getName() + " " + e);
            }
        }
    }

    /** Event log: /data/nemesis/logs/k8ecs.damping/events.log (world-readable), rotated at 2 MB, one old file kept. */
    static final class Log {
        private static final long MAX = 2L * 1024 * 1024;
        private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

        static synchronized void i(String msg) {
            String line = FMT.format(new Date()) + " " + msg + "\n";
            try {
                File f = new File(LOG_DIR, "events.log");
                if (f.length() > MAX) f.renameTo(new File(LOG_DIR, "events.1.log"));
                FileOutputStream out = new FileOutputStream(f, true);
                try { out.write(line.getBytes("UTF-8")); } finally { out.close(); }
                f.setReadable(true, false);
            } catch (IOException ignored) { }
        }
    }
}
