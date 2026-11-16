package io.github.k8ecs.core;

public final class TestMain {
    private static int passed;

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        passed++;
    }

    private static DampingRules rules() {
        // 2 levels, up at 100, down below 85, corner on 250 mg / off 120 mg, dwell 3 s, 4 per minute.
        return new DampingRules(2, new int[] {100}, 15, 250, 120, 1, 300, 2500, 3000, 4, 1000);
    }

    public static void main(String[] args) {
        speedHysteresis();
        gating();
        corner();
        cornerLinkLoss();
        rateLimit();
        threeLevels();
        validation();
        lateral();
        hippo();
        calibrator();
        settings();
        carLog();
        accelLateral();
        pedal();
        links();
        System.out.println("core tests passed: " + passed);
    }

    private static byte[] hex(String s) {
        String[] p = s.trim().split("\\s+");
        byte[] b = new byte[p.length];
        for (int i = 0; i < p.length; i++) b[i] = (byte) Integer.parseInt(p[i], 16);
        return b;
    }

    private static void hippo() {
        // Real frames captured parked (2026-11-15); escaped form as on the wire. No position content.
        String gyro = "81 70 13 0b c0 bd ca 2f 00 24 10 26 02 00 00 85 02 00 00 13 00 00 00 a7 ca 2f 00 04 00 00 00"
                + " 00 00 00 00 00 00 00 00 00 00 00 00 a8 7a 00 00 25 c5 c5 01 ca 8d 09 00 33 0b 00 00 85 02 00 00"
                + " d2 00 00 00 01 fd 29 5a 42 37 00 01 df bf 54 42 5e 8d 18 00 64 82";
        String accel = "81 70 14 03 c0 bd ca 2f 00 24 10 26 02 01 b4 1b 00 00 13 00 00 00 1f 1e 30 0a ca 8d 09 00"
                + " ce c7 04 00 13 00 00 00 ac 04 01 66 ca 8d 09 00 1e 07 00 00 13 00 00 00 b0 cc ab fd ca 8d 09 00"
                + " 01 fd 29 5a 42 75 01 00 00 d2 82";
        String escaped = "81 70 05 02 be ca 2f 00 14 80 02 00 00 bb 82"; // 0x80 0x02 -> 0x82 inside a frame
        final long[] got = new long[8];
        HippoStream s = new HippoStream(new HippoStream.Listener() {
            @Override public void onGyro(long ts, int sum, int count) { got[0] = ts; got[1] = sum; got[2] = count; got[7]++; }
            @Override public void onAccel(long ts, int[] sums, int count) {
                got[3] = sums[0]; got[4] = sums[1]; got[5] = sums[2]; got[6] = count; got[7] += 10;
            }
        });
        byte[] all = hex("00 82 " + gyro + " " + escaped + " " + accel);
        // Feed in odd-sized chunks to exercise frame reassembly.
        for (int i = 0; i < all.length; i += 7) s.feed(all, i, Math.min(7, all.length - i));
        check(got[7] == 11, "one gyro and one accel frame decoded");
        check(got[0] == 0x2fcabdL && got[1] == 645 && got[2] == 19, "gyro fields");
        check(got[3] == 7092 && got[4] == 313294 && got[5] == 1822 && got[6] == 19, "accel fields");
        check(Math.abs(got[4] / (double) got[6] / 16384.0 - 1.0) < 0.02, "parked: the middle accel axis carries gravity, ~1 g at 16384/g");
        check(s.framesDropped() == 0 && s.framesOk() == 3, "escaped frame accepted");
        HippoStream bad = new HippoStream(new HippoStream.Listener() {
            @Override public void onGyro(long ts, int sum, int count) { throw new AssertionError("bad frame"); }
            @Override public void onAccel(long ts, int[] sums, int count) { throw new AssertionError("bad frame"); }
        });
        byte[] broken = hex("81 70 13 0b 80 09 00 82");
        bad.feed(broken, 0, broken.length);
        check(bad.framesDropped() == 1, "invalid escape dropped");
    }

    /** Simulated drive: bias 34 LSB, true scale -0.05 deg/s per LSB (sign unknown to the calibrator). */
    private static void calibrator() {
        final double bias = 34, scale = -0.05;
        GyroCalibrator c = new GyroCalibrator(0, 0);
        long t = 0;
        double heading = 100;
        for (; t < 4000; t += 100) c.onGyro(t, bias + 1.5 * Math.sin(t), 0);
        check(Math.abs(c.bias() - bias) < 0.5, "bias learned at standstill");
        check(!c.ready(), "scale still unknown");
        double[] turns = {90, -90, 60, -120, 90};
        int kmh = 40;
        for (double turn : turns) {
            // 5 s straight, then the turn at 15 deg/s.
            double rate = 0;
            long turnEnd = -1;
            long straightEnd = t + 5000;
            for (; ; t += 100) {
                if (t >= straightEnd && turnEnd < 0) turnEnd = t + (long) (Math.abs(turn) / 15.0 * 1000);
                rate = (turnEnd > 0 && t < turnEnd) ? Math.signum(turn) * 15 : 0;
                if (turnEnd > 0 && t >= turnEnd) break;
                heading = (heading + rate * 0.1 + 360) % 360;
                c.onGyro(t, bias + rate / scale, kmh);
                if (t % 1000 == 0) c.onHeading(t, (int) Math.round(heading) % 360, kmh);
            }
        }
        check(c.segmentCount() >= 3, "turn segments collected: " + c.segmentCount());
        check(c.ready() && Math.abs(c.scale() - scale) < 0.005, "scale and sign learned: " + c.scale());
        c.onGyro(t, bias + 15 / scale, 40);
        check(Math.abs(c.yawDegPerSec() - 15) < 1, "yaw rate from raw");
        int mg = GyroCalibrator.lateralMilliG(40, 15);
        check(mg > 280 && mg < 310, "40 km/h at 15 deg/s is ~0.3 g: " + mg);
        GyroCalibrator manual = new GyroCalibrator(0.02, -0.05);
        check(manual.scale() == 0.02, "manual scale wins over saved");
        check(new GyroCalibrator(0, -0.05).scale() == -0.05, "saved scale restored");
    }

    private static void settings() {
        Settings d = Settings.defaults();
        check(!d.enabled, "ships disabled");
        Settings s = Settings.parse("enabled=1 speedUp=90 cornerOn=0", d);
        check(s.enabled && s.speedUpKmh == 90 && s.cornerOnMilliG == 0 && s.dwellMs == d.dwellMs, "partial update");
        check(Settings.parse(s.encode(), d).encode().equals(s.encode()), "round trip");
        boolean badMargin = false;
        try { Settings.parse("speedUp=20 hyst=210", d); } catch (IllegalArgumentException e) { badMargin = true; }
        check(badMargin, "margin larger than the speed is rejected");
        check(Settings.parse("allModes=1", d).encode().equals(d.encode()), "removed allModes key from 0.1.7 configs is ignored");
        check(d.suspOn && !d.steerOn && !d.engOn, "suspension on, steering and engine off by default");
        Settings t = Settings.parse("steerOn=1 steerSpeedUp=60 steerHyst=10 engOn=1 engPedal=70 engHold=4000", d);
        check(t.steerOn && t.steerRules().speedUpKmh[0] == 60 && t.engOn && t.engPedalPct == 70 && t.engHoldMs == 4000, "item settings");
        check(Settings.parse(t.encode(), d).encode().equals(t.encode()), "item settings round trip");
        boolean badPedal = false;
        try { Settings.parse("engPedal=2", d); } catch (IllegalArgumentException e) { badPedal = true; }
        check(badPedal, "pedal threshold below 5% rejected");
        check(Settings.parse("speedUp=0", d).toRules().speedUpKmh.length == 0, "speed rule off");
        String[] bad = {"speedUp=10", "cornerOn=50", "cornerOff=300", "dwell=100", "foo=1", "speedUp", "gyroScale=NaN"};
        for (String b : bad) {
            boolean threw = false;
            try { Settings.parse(b, d); } catch (IllegalArgumentException e) { threw = true; }
            check(threw, "rejects " + b);
        }
    }

    private static void carLog() {
        String p = "11-15 19:36:17.090 D/SystemService(  603): ";
        check(CarLog.driveMode(p + "SYS_DRIVE_MODE_DATA_M_RESP: 0x0f 0x01 0x03 0x00 0x60 0x00 0x45 0x52 0xff") == 3, "sport");
        check(CarLog.driveMode(p + "SYS_DRIVE_MODE_DATA_M_RESP: 0x0f 0x01 0x00 0x00 0x60") == -1, "transition ignored");
        check(CarLog.driveMode(p + "SYS_DRIVE_MODE_DATA_M_RESP: 0x0f 0x01 0x0f") == -1, "boot value ignored");
        check(CarLog.suspensionStep(p + "  mIndividualModeFeedback:   [0] 4  [1] 5  [2] 5  [3] 2  [4] 15") == 1, "firm");
        check(CarLog.suspensionStep(p + "  mIndividualModeFeedback:   [0] 4  [1] 2  [2] 2  [3] 2") == 0, "soft");
        check(CarLog.suspensionStep(p + "  mIndividualModeFeedback:   [0] 4  [1] 7  [2] 2") == 9, "unknown value");
        check(CarLog.suspensionStep(p + "  mFaultStatus:   [0] 0  [1] 0") == -1, "other array");
        check(CarLog.ignition(p + "SYS_IGN_STATUS_M: 1") == Boolean.TRUE, "ignition on");
        check(CarLog.heading("11-15 D/GisUtil (  786): {com.hkmc.telematics.gis.extra.LAT=1, com.hkmc.telematics.gis.extra.HEADING=330, x=1}") == 330, "heading");
        check(CarLog.heading(p + "nothing") == -1, "no heading");
        check(CarLog.isStockMode(2) && CarLog.isStockMode(6) && !CarLog.isStockMode(5), "stock modes");
        String q = "11-16 12:00:48.130 D/SystemService(  603): [SystemService.requestNewMyDriveMode] data: ";
        check(CarLog.myDriveSlot(q + "0x01 0x01 , called by 1933") == CarLog.SLOT_MY_DRIVE, "slot my drive");
        check(CarLog.myDriveSlot(q + "0x01 0x02 , called by 1933") == CarLog.SLOT_SMART, "slot smart");
        check(CarLog.myDriveSlot(q + "0x02 0x01 , called by 1933") == -1, "other request");
        check(CarLog.myDriveSlot(p + "nothing") == -1, "no slot");
        check(CarLog.isMyDrive(6, CarLog.SLOT_MY_DRIVE) && CarLog.isMyDrive(6, -1), "mode 6 is my drive by default");
        check(!CarLog.isMyDrive(6, CarLog.SLOT_SMART), "mode 6 after smart chosen");
        check(!CarLog.isMyDrive(2, CarLog.SLOT_MY_DRIVE) && !CarLog.isMyDrive(3, -1) && !CarLog.isMyDrive(-1, 1), "eco sport unknown");
        check(CarLog.isMyDrive(5, CarLog.SLOT_SMART), "other mode byte counts as custom");
    }

    private static void speedHysteresis() {
        DampingController c = new DampingController(rules());
        check(c.update(0, 50, true, true) == 0, "first update states the soft level");
        check(c.update(5000, 99, true, true) == DampingController.NO_COMMAND, "below threshold");
        check(c.update(6000, 100, true, true) == 1, "rises at threshold");
        check(c.update(20000, 90, true, true) == DampingController.NO_COMMAND, "holds inside hysteresis");
        check(c.update(21000, 84, true, true) == 0, "drops below hysteresis");
    }

    private static void gating() {
        DampingController c = new DampingController(rules());
        check(c.update(0, 120, false, true) == DampingController.NO_COMMAND, "not in custom mode");
        check(c.update(1000, 0, true, false) == DampingController.NO_COMMAND, "parked");
        check(c.update(2000, 120, true, true) == 1, "sends once allowed");
        c.halt();
        check(c.update(60000, 20, true, true) == DampingController.NO_COMMAND && c.isHalted(), "halt is final");
    }

    private static void corner() {
        DampingController c = new DampingController(rules());
        c.onCarLevel(0);
        c.onLateral(0, -300);
        check(c.update(0, 60, true, true) == DampingController.NO_COMMAND, "corner needs on-delay");
        c.onLateral(300, 300);
        check(c.update(300, 60, true, true) == 1, "corner raises level");
        c.onLateral(4000, 200);
        check(c.update(4000, 60, true, true) == DampingController.NO_COMMAND, "between thresholds holds");
        c.onLateral(5000, 50);
        check(c.update(5000, 60, true, true) == DampingController.NO_COMMAND, "release delay not elapsed");
        c.onLateral(7000, 50);
        check(c.update(7000, 60, true, true) == DampingController.NO_COMMAND, "still 2.0 s");
        c.onLateral(7500, 50);
        check(c.update(7500, 60, true, true) == 0, "released after 2.5 s straight");
    }

    private static void cornerLinkLoss() {
        DampingController c = new DampingController(rules());
        c.onCarLevel(0);
        c.onLateral(0, 400);
        c.update(0, 60, true, true);
        c.onLateral(400, 400);
        check(c.update(400, 60, true, true) == 1, "corner active");
        // Phone link drops: no more samples.
        check(c.update(1500, 60, true, true) == DampingController.NO_COMMAND, "stale sample starts release");
        check(c.update(4100, 60, true, true) == 0, "falls back to speed level after release delay");
        check(!c.isCornerActive(), "corner cleared");
    }

    private static void rateLimit() {
        DampingController c = new DampingController(rules());
        long t = 0;
        int sent = 0;
        // Oscillate hard across the band every 3 s for one minute.
        for (int i = 0; i < 20; i++, t += 3000) {
            if (c.update(t, i % 2 == 0 ? 120 : 50, true, true) != DampingController.NO_COMMAND) sent++;
        }
        check(sent == 4, "at most 4 commands per minute, got " + sent);
        check(c.update(1000, 120, true, true) == DampingController.NO_COMMAND, "dwell blocks immediate repeat");
    }

    private static void threeLevels() {
        DampingRules r = new DampingRules(3, new int[] {60, 110}, 10, 0, 0, 2, 0, 0, 1000, 20, 1000);
        DampingController c = new DampingController(r);
        check(c.update(0, 130, true, true) == 2, "jumps straight to top level");
        check(c.update(5000, 70, true, true) == 1, "steps down one");
        c.onLateral(6000, 900);
        check(c.update(9000, 70, true, true) == DampingController.NO_COMMAND, "corner rule disabled");
        c.onCarLevel(0);
        check(c.update(12000, 70, true, true) == 1, "re-asserts after driver changed the step");
    }

    private static void lateral() {
        // 72 km/h, 0.2 rad/s about vertical = 4 m/s^2 = 408 mg, whatever the phone orientation.
        float[][] gravities = {{0, 0, 9.81f}, {9.81f, 0, 0}, {0, 6.94f, 6.94f}};
        for (float[] g : gravities) {
            double n = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
            float[] gyro = {(float) (0.2 * g[0] / n), (float) (0.2 * g[1] / n), (float) (0.2 * g[2] / n)};
            LateralEstimator e = new LateralEstimator(0.25);
            check(Math.abs(e.update(0, gyro, g, 20.0) - 408) <= 1, "first sample passes through");
        }
        LateralEstimator e = new LateralEstimator(0.25);
        float[] flat = {0, 0, 9.81f};
        check(e.update(0, new float[] {0.5f, 0.5f, 0}, flat, 20.0) == 0, "pitch/roll rotation ignored");
        int v = 0;
        for (int i = 1; i <= 50; i++) v = e.update(i * 20000000L, new float[] {0, 0, -0.2f}, flat, 20.0);
        check(v < -395 && v >= -408, "converges within 1 s, sign kept: " + v);
        check(e.update(51 * 20000000L, new float[] {0, 0, -0.2f}, flat, 0.0) > v, "no speed, decays");
        check(e.update(52 * 20000000L, new float[] {0, 0, 1}, new float[] {0, 0, 0}, 20.0) < 0, "zero gravity keeps value");
    }

    private static void validation() {
        boolean threw = false;
        try {
            new DampingRules(2, new int[] {100, 120}, 15, 250, 120, 1, 300, 2500, 3000, 4, 1000);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "too many speed thresholds rejected");
        threw = false;
        try {
            new DampingRules(2, new int[] {100}, 15, 250, 300, 1, 300, 2500, 3000, 4, 1000);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "corner off >= on rejected");
        check(DampingRules.defaults(3).cornerLevel == 2, "defaults");
    }

    /** Sensor mounted 30 degrees off the car axis, 3 mg tilt offset, with sensor noise-free windows at 10 Hz. */
    private static void accelLateral() {
        final double rot = Math.toRadians(30), offX = 0.003, offY = -0.002;
        AccelLateral a = new AccelLateral(Double.NaN);
        long t = 0;
        double kmh = 0;
        // Parked: offsets learned.
        for (int i = 0; i < 40; i++, t += 100) a.onAccel(t, offX * 16384, offY * 16384, 0);
        check(a.ready() && !a.oriented(), "offsets learned while parked");
        // Accelerate / brake cycles straight ahead (car frame: forward = +x).
        for (int cycle = 0; cycle < 12 && !a.oriented(); cycle++) {
            double acc = (cycle % 2 == 0) ? 0.25 : -0.25; // g
            for (int i = 0; i < 40; i++, t += 100) {
                kmh = Math.max(20, kmh + acc * 9.80665 * 0.1 * 3.6);
                if (kmh <= 20 && acc < 0) acc = 0;
                double fx = acc, fy = 0;
                double sx = fx * Math.cos(rot) - fy * Math.sin(rot) + offX, sy = fx * Math.sin(rot) + fy * Math.cos(rot) + offY;
                a.onAccel(t, sx * 16384, sy * 16384, kmh);
            }
        }
        check(a.oriented(), "forward direction learned from speed changes, progress " + a.progress());
        double err = Math.abs(AccelLateral.class.cast(a).angle() - rot);
        check(err < Math.toRadians(5), "direction within 5 deg: " + Math.toDegrees(a.angle()));
        // Constant speed corner at 0.30 g lateral.
        for (int i = 0; i < 20; i++, t += 100) {
            double fx = 0, fy = 0.30;
            double sx = fx * Math.cos(rot) - fy * Math.sin(rot) + offX, sy = fx * Math.sin(rot) + fy * Math.cos(rot) + offY;
            a.onAccel(t, sx * 16384, sy * 16384, 60);
        }
        check(Math.abs(a.lateralMilliG() - 300) < 25, "0.30 g corner reads " + a.lateralMilliG());
        // Pure braking reads little lateral.
        double v = 60;
        for (int i = 0; i < 10; i++, t += 100) {
            v -= 0.3 * 9.80665 * 0.1 * 3.6;
            double fx = -0.3, fy = 0;
            double sx = fx * Math.cos(rot) - fy * Math.sin(rot) + offX, sy = fx * Math.sin(rot) + fy * Math.cos(rot) + offY;
            a.onAccel(t, sx * 16384, sy * 16384, v);
        }
        check(a.lateralMilliG() < 60, "braking is not lateral: " + a.lateralMilliG());
        // Estimate before orientation: magnitude minus speed-derived longitudinal part.
        AccelLateral b = new AccelLateral(Double.NaN);
        t = 0;
        for (int i = 0; i < 40; i++, t += 100) b.onAccel(t, 0, 0, 0);
        for (int i = 0; i < 20; i++, t += 100) b.onAccel(t, 0.3 * Math.cos(rot + 1.5708) * 16384, 0.3 * Math.sin(rot + 1.5708) * 16384, 50);
        check(!b.oriented() && Math.abs(b.lateralMilliG() - 300) < 25, "estimate before learning: " + b.lateralMilliG());
        check(new AccelLateral(0.5).oriented(), "saved direction restored");
    }


    private static void pedal() {
        PedalController p = new PedalController(60, 3000, 8);
        p.onCarLevel(0);
        long t = 0;
        check(p.update(t, 30, t, true, true) == PedalController.NO_COMMAND, "light pedal keeps eco");
        t += 100;
        check(p.update(t, 70, t, true, true) == 1, "pedal over threshold -> sport");
        t += 100;
        check(p.update(t, 10, t, true, true) == PedalController.NO_COMMAND, "released: hold sport");
        t += 2800;
        check(p.update(t, 10, t, true, true) == PedalController.NO_COMMAND, "still within hold");
        t += 200;
        check(p.update(t, 10, t, true, true) == 0, "hold over -> eco");
        t += 100;
        check(p.update(t, 90, t, false, true) == PedalController.NO_COMMAND, "inactive (not My Drive): nothing sent");
        t += 4000; // past the hold of the press above
        check(p.update(t, 90, t - 5000, true, true) == PedalController.NO_COMMAND, "stale pedal reading counts as released");
        PedalController q = new PedalController(60, 1000, 8);
        q.onCarLevel(0);
        check(q.update(0, 90, 0, true, false) == PedalController.NO_COMMAND, "parked: nothing sent");
        q.halt();
        check(q.update(100, 90, 100, true, true) == PedalController.NO_COMMAND, "halted: nothing sent");
        int[] fb = CarLog.feedbacks("D/SystemService(  603):   mIndividualModeFeedback:   [0] 4  [1] 5  [2] 2  [3] 2  [4] 15");
        check(fb != null && fb[0] == 4 && fb[1] == 5 && fb[2] == 2, "three item feedbacks parsed");
    }


    private static void links() {
        // Suspension rules off, linked from the engine only: sport while linked, back to low when not.
        DampingController d = new DampingController(Settings.defaults().toRules());
        d.onCarLevel(0);
        check(d.update(0, 30, true, true, true, false) == 1, "linked high -> sport even with own rules off");
        check(d.update(4000, 30, true, true, false, false) == 0, "link released -> back to low");
        check(d.update(8000, 150, true, true, false, false) == DampingController.NO_COMMAND && d.ownLevel() == 0,
                "own rules off: speed does not count");
        DampingController e = new DampingController(Settings.defaults().toRules());
        e.update(0, 150, false, true);
        check(e.ownLevel() == 1, "own level tracked even while inactive");
        PedalController p = new PedalController(60, 3000, 8);
        p.onCarLevel(0);
        check(p.update(0, 0, 0, true, true, true, false) == 1, "engine linked from suspension -> sport");
        check(p.update(2000, 90, 2000, true, true, false, false) == 0, "own pedal rule off: pedal ignored, back to eco");
        Settings s = Settings.parse("engLinkSusp=1 suspLinkSteer=1", Settings.defaults());
        check(s.engLinkSusp && s.suspLinkSteer && !s.engLinkSteer && !s.suspLinkEng, "link options parsed");
    }

}
