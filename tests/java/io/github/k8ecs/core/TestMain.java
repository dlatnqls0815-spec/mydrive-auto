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
        stoppedReturnsDown();
        unlimitedRate();
        suspSteerExclusive();
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
        clusterTheme();
        ambientSport();
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
        Settings t = Settings.parse("suspOn=0 steerOn=1 steerSpeedUp=60 steerHyst=10 engOn=1 engPedal=70 engHold=4000", d);
        check(t.steerOn && t.steerRules().speedUpKmh[0] == 60 && t.engOn && t.engPedalPct == 70 && t.engHoldMs == 4000, "item settings");
        check(Settings.parse(t.encode(), d).encode().equals(t.encode()), "item settings round trip");
        check(VehicleProfile.forProduct("GL3_KOR") == VehicleProfile.K8_GL3, "K8 product matches the K8 profile");
        check(VehicleProfile.forProduct("XYZ123") == VehicleProfile.GENERIC, "unknown product falls back to generic");
        check(VehicleProfile.forProduct(null) == VehicleProfile.GENERIC, "missing product falls back to generic");
        for (VehicleProfile v : VehicleProfile.ALL) {
            for (VehicleProfile.Item it : new VehicleProfile.Item[] {v.suspension, v.steering, v.engineEco, v.engineNormal}) {
                check(!it.supported || it.lowValue != it.sportValue, v.id + ": low and sport send different values");
                check(!it.supported || it.lowFeedback != it.sportFeedback, v.id + ": low and sport feedback differ");
            }
        }
        check(VehicleProfile.ALL[VehicleProfile.ALL.length - 1] == VehicleProfile.GENERIC, "generic profile is last");
        check(!d.engNormal, "engine low step is Eco by default (hybrid)");
        Settings gas = Settings.parse("engNormal=1", d);
        check(gas.engNormal && Settings.parse(gas.encode(), d).engNormal, "engine Normal choice round trip");
        check(Settings.parse("engOn=1", d).encode().contains("engNormal=0"), "config without engNormal (0.4.0) reads as Eco");
        boolean badNormal = false;
        try { Settings.parse("engNormal=2", d); } catch (IllegalArgumentException e) { badNormal = true; }
        check(badNormal, "engNormal must be 0 or 1");
        boolean badPedal = false;
        try { Settings.parse("engPedal=2", d); } catch (IllegalArgumentException e) { badPedal = true; }
        check(badPedal, "pedal threshold below 5% rejected");
        check(Settings.parse("speedUp=0", d).toRules().speedUpKmh.length == 0, "speed rule off");
        check(!d.clusterOn && d.clusterTheme == 1, "cluster sport theme off, theme A (1) by default");
        Settings cl = Settings.parse("clusterOn=1 clusterTheme=3", d);
        check(cl.clusterOn && cl.clusterTheme == 3, "cluster settings parsed");
        check(Settings.parse(cl.encode(), d).encode().equals(cl.encode()), "cluster settings round trip");
        check(Settings.parse("enabled=1", d).encode().contains("clusterOn=0 clusterTheme=1"),
                "old config without cluster keys keeps defaults");
        check(!d.ambOn, "ambient sport red off by default");
        Settings amb = Settings.parse("ambOn=1", d);
        check(amb.ambOn && Settings.parse(amb.encode(), d).encode().equals(amb.encode()), "ambOn round trip");
        check(Settings.parse("enabled=1", d).encode().contains("ambOn=0"),
                "old config without ambOn keeps default");
        String[] bad = {"speedUp=10", "cornerOn=50", "cornerOff=300", "dwell=100", "foo=1", "speedUp", "gyroScale=NaN",
                "clusterOn=2", "clusterTheme=0", "clusterTheme=5", "clusterTheme=abc", "ambOn=2"};
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
        check(CarLog.clusterAutoSet("11-20 05:07:19.680 V/USMService(  599): -  Cluster_ThemeAutoSetStatus: 2") == CarLog.CLUSTER_LINK_ON,
                "cluster link on (USMService)");
        check(CarLog.clusterAutoSet("11-20 05:07:55.080 D/ThemeSettingService( 1755): onClusterContentReceived, nThemeStatus : 0, nThemeCount : 0, nThemeAutoSetStatus : 1") == CarLog.CLUSTER_LINK_OFF,
                "cluster link off (ThemeSettingService)");
        check(CarLog.clusterAutoSet("11-20 05:07:19.690 V/USMService(  599): -  Cluster_ThemeStatusNew: 15") == -1, "theme line is not autoSet");
        check(CarLog.clusterTheme("11-20 05:07:19.690 V/USMService(  599): -  Cluster_ThemeStatusNew: 15") == -1, "15 (stock link) is not a theme");
        check(CarLog.clusterTheme("11-18 01:02:14.160 V/USMService(  609): -  Cluster_ThemeStatusNew: 4") == 4,
                "USMService cluster theme 4");
        check(CarLog.clusterTheme("11-18 01:02:15.030 D/ThemeSettingService( 1774): onClusterETCStatusReceived, nThemeStatusNew : 4, nDisplayType : 0") == 4,
                "ThemeSettingService cluster theme 4");
        check(CarLog.clusterTheme("11-18 01:02:14.160 V/USMService(  609): -  Cluster_ThemeStatusNew: 1") == 1,
                "USMService cluster theme 1");
        check(CarLog.clusterTheme("11-18 01:02:15.030 D/ThemeSettingService( 1774): onClusterETCStatusReceived, nThemeStatusNew : 2, nDisplayType : 0") == 2,
                "ThemeSettingService cluster theme 2");
        check(CarLog.clusterTheme("11-18 01:02:14.160 V/USMService(  609): -  Cluster_ThemeStatusNew: 0") == -1,
                "cluster theme 0 rejected");
        check(CarLog.clusterTheme("11-18 01:02:14.160 V/USMService(  609): -  Cluster_ThemeStatusNew: 5") == -1,
                "cluster theme 5 rejected");
        check(CarLog.clusterTheme(p + "nothing") == -1, "unrelated line returns -1");
        check(CarLog.clusterTheme(null) == -1, "null line returns -1");

        byte[] sml1 = CarLog.smlStatus("11-20 05:10:00.000 D/SystemService(  688): SYS_SML_STATUS_M_RESP: 0x00 0x9b 0x00 0x21 0x00 0xef 0x0b 0x01 0x01 ");
        check(sml1 != null && sml1.length == 9
                && (sml1[0] & 0xff) == 0x00 && (sml1[1] & 0xff) == 0x9b
                && (sml1[5] & 0xff) == 0xef && (sml1[6] & 0xff) == 0x0b
                && (sml1[8] & 0xff) == CarLog.SML_LINK_OFF, "SML status direct form parsed");
        check(AmbientSportController.packColor(sml1) == 0x009b002100efL
                && "009b002100ef".equals(AmbientSportController.toHex(AmbientSportController.packColor(sml1))),
                "SML direct color packed and formatted");
        byte[] sml2 = CarLog.smlStatus("11-20 05:10:01.000 D/SystemService(  688): [MicomEventHandler.handleMessage] SYS_SML_STATUS_M_RESP: 0x01 0x6b 0x01 0x5a 0x01 0x95 0x01 0x01 0x01");
        check(sml2 != null && sml2.length == 9
                && AmbientSportController.packColor(sml2) == 0x016b015a0195L
                && (sml2[6] & 0xff) == CarLog.SML_BRIGHTNESS_OFF, "SML status MicomEventHandler form parsed");
        check(CarLog.smlStatus(p + "SYS_SML_STATUS_M_RESP: 0x00 0x9b 0x00 0x21 0x00 0xef 0x0b 0x01") == null,
                "SML short line rejected");
        check(CarLog.smlStatus(p + "SYS_SML_STATUS_M_RESP: 0x00 0xzz 0x00 0x21 0x00 0xef 0x0b 0x01 0x01") == null,
                "SML garbage token rejected");
        check(CarLog.smlStatus(p + "SYS_SML_STATUS_M_RESP: 0x00 0x100 0x00 0x21 0x00 0xef 0x0b 0x01 0x01") == null,
                "SML out-of-range byte rejected");
        check(CarLog.smlStatus(p + "[SystemService.setSMLRGB] red: 0x00 0xc1 , green: 0x00 0x21 , blue: 0x00 0xe0 , called by 2060") == null,
                "SML command line ignored");
        check(CarLog.smlStatus(null) == null, "SML null line returns null");
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

    private static void suspSteerExclusive() {
        Settings both = Settings.parse("suspOn=1 steerOn=1", Settings.defaults());
        check(both.suspOn && !both.steerOn, "suspension and steering auto are exclusive, suspension wins");
        Settings steer = Settings.parse("suspOn=0 steerOn=1", Settings.defaults());
        check(!steer.suspOn && steer.steerOn, "steering alone stays on");
    }

    private static void unlimitedRate() {
        DampingController c = new DampingController(
                new DampingRules(2, new int[] {100}, 15, 250, 120, 1, 300, 2500, 1000, 0, 1000));
        c.onCarLevel(0);
        int sent = 0;
        for (int i = 0; i < 30; i++) {
            if (c.update(i * 1000L, i % 2 == 0 ? 120 : 50, true, true) != DampingController.NO_COMMAND) sent++;
        }
        check(sent == 30, "maxPerMin 0: only the dwell limits (" + sent + ")");
        PedalController p = new PedalController(60, 500, 0);
        p.onCarLevel(0);
        int ps = 0;
        for (int i = 0; i < 30; i++) {
            if (p.update(i * 1000L, i % 2 == 0 ? 90 : 0, i * 1000L, true, true) != PedalController.NO_COMMAND) ps++;
        }
        check(ps == 30, "pedal maxPerMin 0: only the 1 s gap limits (" + ps + ")");
    }

    private static void stoppedReturnsDown() {
        // Corner at parking speed is ignored.
        DampingController c = new DampingController(rules());
        c.onCarLevel(0);
        c.onLateral(0, 300);
        c.update(0, 5, true, true);
        c.onLateral(400, 300);
        check(c.update(400, 5, true, true) == DampingController.NO_COMMAND, "no corner below 15 km/h");
        // Raised while driving, then stopped: goes back down after the dwell, never up while stopped.
        DampingController d = new DampingController(rules());
        d.onCarLevel(0);
        check(d.update(0, 120, true, true) == 1, "speed raises");
        check(d.update(1000, 0, true, false) == DampingController.NO_COMMAND, "dwell still applies when stopped");
        check(d.update(4000, 0, true, false) == 0, "stopped: back to the bottom step");
        check(d.update(9000, 0, true, false) == DampingController.NO_COMMAND, "stopped and already down: nothing");
        DampingController e = new DampingController(rules());
        e.onCarLevel(0);
        check(e.update(0, 0, true, false, true, true) == DampingController.NO_COMMAND, "link never raises while stopped");
        PedalController p = new PedalController(60, 1000, 8);
        p.onCarLevel(0);
        check(p.update(0, 90, 0, true, true) == 1, "pedal sport while moving");
        check(p.update(4000, 90, 4000, true, false) == 0, "stopped with pedal down: back to eco");
        check(p.update(9000, 90, 9000, true, false) == PedalController.NO_COMMAND, "stopped: stays eco");
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

    private static void clusterTheme() {
        // 1. Sport start and immediate restore on sport end (release = 0 ms, respecting 3 s min gap).
        check(ClusterThemeController.DEFAULT_RELEASE_MS == 0L, "cluster default release is immediate (0 ms)");
        ClusterThemeController c = new ClusterThemeController();
        check(c.update(0, true, 1, true, -1) == ClusterThemeController.NO_COMMAND, "unknown current theme: no command");
        check(c.update(100, true, 1, true, 4) == 1, "sport start switches 4 -> 1");
        check(ClusterThemeController.REASON_SPORT_START.equals(c.lastReason()) && c.savedOriginal() == 4,
                "savedOriginal=4 and reason=sport start");
        check(c.update(500, true, 1, true, 1) == ClusterThemeController.NO_COMMAND && !c.hasPending(),
                "matching feedback clears pending");
        check(c.update(1000, true, 1, false, 1) == ClusterThemeController.NO_COMMAND,
                "sport off within 3 s min gap waits for rate limit");
        check(c.update(3099, true, 1, false, 1) == ClusterThemeController.NO_COMMAND,
                "2.999 s since last send: still rate-limited");
        check(c.update(3100, true, 1, false, 1) == 4,
                "3.0 s min gap reached: restores original theme 4 immediately");
        check(ClusterThemeController.REASON_SPORT_END.equals(c.lastReason()) && c.savedOriginal() == -1,
                "reason=sport end and savedOriginal cleared");
        check(c.update(3500, true, 1, false, 4) == ClusterThemeController.NO_COMMAND && !c.hasPending(),
                "restore confirmed");

        // Immediate restore with no delay when >= 3 s already elapsed since sport start.
        ClusterThemeController imm = new ClusterThemeController();
        check(imm.update(0, true, 1, true, 4) == 1, "imm: sport start");
        check(imm.update(300, true, 1, true, 1) == ClusterThemeController.NO_COMMAND, "imm: confirmed");
        check(imm.update(4000, true, 1, false, 1) == 4
                && ClusterThemeController.REASON_SPORT_END.equals(imm.lastReason())
                && imm.savedOriginal() == -1, "imm: sport off after >= 3 s restores at once");

        // 2. Flapping within 3 s min gap does not restore; custom non-zero releaseMs still delays restore.
        ClusterThemeController flap = new ClusterThemeController();
        check(flap.update(0, true, 2, true, 4) == 2, "flap: sport start");
        check(flap.update(200, true, 2, true, 2) == ClusterThemeController.NO_COMMAND, "flap: confirmed");
        check(flap.update(1000, true, 2, false, 2) == ClusterThemeController.NO_COMMAND, "flap: sport off within gap");
        check(flap.update(2000, true, 2, true, 2) == ClusterThemeController.NO_COMMAND, "flap: sport back on");
        check(flap.update(4000, true, 2, false, 2) == 4, "flap: sport off after gap restores immediately");

        ClusterThemeController delayed = new ClusterThemeController(5000L);
        check(delayed.update(0, true, 2, true, 4) == 2, "delayed: sport start");
        check(delayed.update(200, true, 2, true, 2) == ClusterThemeController.NO_COMMAND, "delayed: confirmed");
        check(delayed.update(1000, true, 2, false, 2) == ClusterThemeController.NO_COMMAND, "delayed: sport off");
        check(delayed.update(5000, true, 2, false, 2) == ClusterThemeController.NO_COMMAND, "delayed: 4 s off");
        check(delayed.update(5500, true, 2, true, 2) == ClusterThemeController.NO_COMMAND, "delayed: sport back on resets delay");
        check(delayed.update(6000, true, 2, false, 2) == ClusterThemeController.NO_COMMAND, "delayed: off again");
        check(delayed.update(10900, true, 2, false, 2) == ClusterThemeController.NO_COMMAND, "delayed: 4.9 s since second off");
        check(delayed.update(11000, true, 2, false, 2) == 4, "delayed: restores after full 5 s continuous off");

        // 3. Manual change during sport cancels without fighting back until next sport start.
        ClusterThemeController manual = new ClusterThemeController();
        check(manual.update(0, true, 1, true, 4) == 1, "manual: sport start");
        check(manual.update(300, true, 1, true, 1) == ClusterThemeController.NO_COMMAND, "manual: confirmed");
        check(manual.update(4000, true, 1, true, 3) == ClusterThemeController.NO_COMMAND && manual.savedOriginal() == -1,
                "manual change during sport cancels and clears savedOriginal");
        check(manual.update(8000, true, 1, true, 3) == ClusterThemeController.NO_COMMAND,
                "manual: does not fight back while still in sport");
        check(manual.update(9000, true, 1, false, 3) == ClusterThemeController.NO_COMMAND, "manual: sport off");
        check(manual.update(15000, true, 1, false, 3) == ClusterThemeController.NO_COMMAND,
                "manual: sport end does not overwrite driver choice");
        check(manual.update(16000, true, 1, true, 3) == 1 && manual.savedOriginal() == 3,
                "manual: next sport start saves new theme 3 and sends target 1");

        // 4. Manual change while waiting on 3 s rate limit after sport off cancels restore.
        ClusterThemeController manualEnd = new ClusterThemeController();
        check(manualEnd.update(0, true, 1, true, 4) == 1, "manualEnd: sport start");
        check(manualEnd.update(300, true, 1, true, 1) == ClusterThemeController.NO_COMMAND, "manualEnd: confirmed");
        check(manualEnd.update(1000, true, 1, false, 1) == ClusterThemeController.NO_COMMAND, "manualEnd: sport off within gap");
        check(manualEnd.update(2000, true, 1, false, 2) == ClusterThemeController.NO_COMMAND && manualEnd.savedOriginal() == -1,
                "manualEnd: driver changed before restore sent");
        check(manualEnd.update(4000, true, 1, false, 2) == ClusterThemeController.NO_COMMAND,
                "manualEnd: no restore after driver changed");

        // 5. Already at target: nothing sent, savedOriginal stays -1, nothing restored.
        ClusterThemeController same = new ClusterThemeController();
        check(same.update(0, true, 4, true, 4) == ClusterThemeController.NO_COMMAND && same.savedOriginal() == -1,
                "already at target: no command and no savedOriginal");
        check(same.update(1000, true, 4, false, 4) == ClusterThemeController.NO_COMMAND, "already at target: sport off");
        check(same.update(7000, true, 4, false, 4) == ClusterThemeController.NO_COMMAND, "already at target: nothing restored");

        // 6. Pending misses: matching feedback resets misses; 2 misses -> halted.
        ClusterThemeController recover = new ClusterThemeController();
        check(recover.update(0, true, 1, true, 4) == 1, "recover: first send");
        check(recover.update(2900, true, 1, true, 4) == ClusterThemeController.NO_COMMAND && recover.misses() == 0,
                "recover: still waiting before 3 s timeout");
        check(recover.update(3000, true, 1, true, 4) == 1 && recover.misses() == 1 && !recover.isHalted(),
                "recover: first miss retries at 3 s");
        check(recover.update(3500, true, 1, true, 1) == ClusterThemeController.NO_COMMAND && recover.misses() == 0,
                "recover: matching feedback resets misses");

        ClusterThemeController halt = new ClusterThemeController();
        check(halt.update(0, true, 1, true, 4) == 1, "halt: first send");
        check(halt.update(3000, true, 1, true, 4) == 1 && halt.misses() == 1 && !halt.isHalted(),
                "halt: 1st miss -> retry");
        check(halt.update(6000, true, 1, true, 4) == ClusterThemeController.NO_COMMAND && halt.misses() == 2 && halt.isHalted(),
                "halt: 2nd miss -> halted");
        check(halt.update(20000, true, 1, true, 4) == ClusterThemeController.NO_COMMAND && halt.isHalted(),
                "halt: stays halted");
        halt.clearHalt();
        check(!halt.isHalted() && halt.misses() == 0, "halt: clearHalt resets halt state");

        // 7. Option off restores immediately; respects rate limit (3 s).
        ClusterThemeController optOff = new ClusterThemeController();
        check(optOff.update(0, true, 1, true, 4) == 1, "optOff: sport start");
        check(optOff.update(400, true, 1, true, 1) == ClusterThemeController.NO_COMMAND, "optOff: confirmed");
        check(optOff.update(1000, false, 1, true, 1) == ClusterThemeController.NO_COMMAND && optOff.savedOriginal() == 4,
                "rate limit blocks restore within 3 s of last send");
        check(optOff.update(2999, false, 1, true, 1) == ClusterThemeController.NO_COMMAND, "rate limit at 2.999 s");
        check(optOff.update(3000, false, 1, true, 1) == 4, "optOff: restores at 3.0 s");
        check(ClusterThemeController.REASON_OPTION_OFF.equals(optOff.lastReason()) && optOff.savedOriginal() == -1,
                "optOff: reason=option off and savedOriginal cleared");

        ClusterThemeController optOffManual = new ClusterThemeController();
        check(optOffManual.update(0, true, 1, true, 4) == 1, "optOffManual: sport start");
        check(optOffManual.update(400, true, 1, true, 1) == ClusterThemeController.NO_COMMAND, "optOffManual: confirmed");
        check(optOffManual.update(4000, false, 1, true, 2) == ClusterThemeController.NO_COMMAND && optOffManual.savedOriginal() == -1,
                "optOffManual: user changed theme -> do not overwrite on option off");

        // 8. Restart restore from persisted savedOriginal.
        ClusterThemeController restart = new ClusterThemeController(ClusterThemeController.DEFAULT_RELEASE_MS, 4);
        check(restart.savedOriginal() == 4, "restart: persisted original loaded");
        check(restart.update(0, true, 1, false, -1) == ClusterThemeController.NO_COMMAND && restart.savedOriginal() == 4,
                "restart: waits for known current theme");
        check(restart.update(500, true, 1, false, 1) == 4, "restart: restores original when not sport and at target");
        check(ClusterThemeController.REASON_RESTORE_RESTART.equals(restart.lastReason()) && restart.savedOriginal() == -1,
                "restart: reason=restore after restart");

        ClusterThemeController restartChanged = new ClusterThemeController(ClusterThemeController.DEFAULT_RELEASE_MS, 4);
        check(restartChanged.update(0, true, 1, false, 2) == ClusterThemeController.NO_COMMAND
                && restartChanged.savedOriginal() == -1, "restart: user changed theme -> no restore");

        ClusterThemeController restartInSport = new ClusterThemeController(ClusterThemeController.DEFAULT_RELEASE_MS, 4);
        check(restartInSport.update(0, true, 1, true, 1) == ClusterThemeController.NO_COMMAND
                && restartInSport.savedOriginal() == 4, "restart in sport: keeps savedOriginal");
        check(restartInSport.update(1000, true, 1, false, 1) == 4
                && ClusterThemeController.REASON_SPORT_END.equals(restartInSport.lastReason()),
                "restart in sport: restores immediately when sport ends");

        // 9. Ignition off clears pending so no false miss occurs on return.
        ClusterThemeController ign = new ClusterThemeController();
        check(ign.update(0, true, 1, true, 4) == 1 && ign.hasPending(), "ign: send starts pending");
        ign.onIgnitionOff();
        check(!ign.hasPending(), "ign: onIgnitionOff clears pending");
        check(ign.update(10000, true, 1, true, 1) == ClusterThemeController.NO_COMMAND && ign.misses() == 0,
                "ign: no false miss after ignition returns");
    }

    private static void ambientSport() {
        long blue = 0x009b002100efL;
        long green = 0x001000c00020L;
        long theme = 0x016b015a0195L;

        // Color helpers & validation.
        check(AmbientSportController.DEFAULT_RELEASE_MS == 0L, "ambient default release is immediate (0 ms)");
        check(AmbientSportController.isValidColor(AmbientSportController.RED)
                && AmbientSportController.isValidColor(blue)
                && AmbientSportController.isValidColor(theme), "valid direct and theme colors accepted");
        check(!AmbientSportController.isValidColor(-1L)
                && !AmbientSportController.isValidColor(0x020000000000L), "invalid hi byte or negative rejected");
        check(AmbientSportController.parseHex("009b002100ef") == blue
                && AmbientSportController.parseHex("016B015A0195") == theme, "parseHex valid");
        check(AmbientSportController.parseHex("029b002100ef") == AmbientSportController.UNKNOWN_COLOR
                && AmbientSportController.parseHex("1234") == AmbientSportController.UNKNOWN_COLOR
                && AmbientSportController.parseHex("009b002100zz") == AmbientSportController.UNKNOWN_COLOR,
                "parseHex rejects invalid");
        check("00ff00000000".equals(AmbientSportController.toHex(AmbientSportController.RED))
                && "?".equals(AmbientSportController.toHex(AmbientSportController.UNKNOWN_COLOR)), "toHex format");
        byte[] rr = AmbientSportController.redBytes(AmbientSportController.RED);
        byte[] rg = AmbientSportController.greenBytes(AmbientSportController.RED);
        byte[] rb = AmbientSportController.blueBytes(AmbientSportController.RED);
        check(rr[0] == 0 && (rr[1] & 0xff) == 0xff && rg[0] == 0 && rg[1] == 0 && rb[0] == 0 && rb[1] == 0,
                "RED channel bytes");

        // 1. Sport start saves original & sends red; sport end restores immediately (respecting 3 s min gap).
        AmbientSportController a = new AmbientSportController();
        check(a.update(0, true, true, blue, 6) == AmbientSportController.RED, "amb: sport start sends red");
        check(AmbientSportController.REASON_SPORT_START.equals(a.lastReason()) && a.savedOriginal() == blue,
                "amb: savedOriginal=blue and reason=sport start");
        check(a.update(400, true, true, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND
                && !a.hasPending(), "amb: matching feedback clears pending");
        check(a.update(1000, true, false, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND,
                "amb: sport off within 3 s min gap waits");
        check(a.update(2999, true, false, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND,
                "amb: 2.999 s still rate-limited");
        check(a.update(3000, true, false, AmbientSportController.RED, 6) == blue,
                "amb: restores blue at 3.0 s gap");
        check(AmbientSportController.REASON_SPORT_END.equals(a.lastReason())
                && a.savedOriginal() == AmbientSportController.UNKNOWN_COLOR, "amb: reason=sport end and cleared");
        check(a.update(3400, true, false, blue, 6) == AmbientSportController.NO_COMMAND && !a.hasPending(),
                "amb: restore confirmed");

        AmbientSportController imm = new AmbientSportController();
        check(imm.update(0, true, true, blue, 6) == AmbientSportController.RED, "amb imm: start");
        check(imm.update(300, true, true, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND,
                "amb imm: confirmed");
        check(imm.update(4000, true, false, AmbientSportController.RED, 6) == blue
                && AmbientSportController.REASON_SPORT_END.equals(imm.lastReason()),
                "amb imm: restores at once after >= 3 s");

        // 2. Flapping within 3 s min gap and optional release delay.
        AmbientSportController flap = new AmbientSportController();
        check(flap.update(0, true, true, blue, 6) == AmbientSportController.RED, "amb flap: start");
        check(flap.update(200, true, true, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND,
                "amb flap: confirmed");
        check(flap.update(1000, true, false, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND,
                "amb flap: off within gap");
        check(flap.update(2000, true, true, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND,
                "amb flap: back on");
        check(flap.update(4000, true, false, AmbientSportController.RED, 6) == blue,
                "amb flap: off after gap restores immediately");

        AmbientSportController delayed = new AmbientSportController(5000L);
        check(delayed.update(0, true, true, blue, 6) == AmbientSportController.RED, "amb delayed: start");
        check(delayed.update(200, true, true, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND,
                "amb delayed: confirmed");
        check(delayed.update(1000, true, false, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND,
                "amb delayed: off");
        check(delayed.update(5500, true, true, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND,
                "amb delayed: back on resets delay");
        check(delayed.update(6000, true, false, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND,
                "amb delayed: off again");
        check(delayed.update(10900, true, false, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND,
                "amb delayed: 4.9 s");
        check(delayed.update(11000, true, false, AmbientSportController.RED, 6) == blue,
                "amb delayed: restores after 5 s");

        // 3. Manual change during sport cancels without fighting back.
        AmbientSportController manual = new AmbientSportController();
        check(manual.update(0, true, true, blue, 6) == AmbientSportController.RED, "amb manual: start");
        check(manual.update(300, true, true, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND,
                "amb manual: confirmed");
        check(manual.update(4000, true, true, green, 6) == AmbientSportController.NO_COMMAND
                && manual.savedOriginal() == AmbientSportController.UNKNOWN_COLOR,
                "amb manual: user change cancels and clears savedOriginal");
        check(manual.update(8000, true, true, green, 6) == AmbientSportController.NO_COMMAND,
                "amb manual: does not fight back");
        check(manual.update(9000, true, false, green, 6) == AmbientSportController.NO_COMMAND,
                "amb manual: sport end does not overwrite");
        check(manual.update(10000, true, true, green, 6) == AmbientSportController.RED
                && manual.savedOriginal() == green, "amb manual: next sport saves new color green");

        // 4. Already red at start -> nothing saved, nothing sent, nothing restored.
        AmbientSportController same = new AmbientSportController();
        check(same.update(0, true, true, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND
                && same.savedOriginal() == AmbientSportController.UNKNOWN_COLOR,
                "amb same: already red -> no send and nothing saved");
        check(same.update(4000, true, false, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND,
                "amb same: nothing restored");

        // 5. Off (brightness == 1) or unknown status -> no send.
        AmbientSportController off = new AmbientSportController();
        check(off.update(0, true, true, blue, CarLog.SML_BRIGHTNESS_OFF) == AmbientSportController.NO_COMMAND
                && off.savedOriginal() == AmbientSportController.UNKNOWN_COLOR, "amb off: brightness 1 -> no send");
        check(off.update(1000, true, true, AmbientSportController.UNKNOWN_COLOR, -1) == AmbientSportController.NO_COMMAND,
                "amb unknown: both unknown -> no send");
        check(off.update(2000, true, true, blue, -1) == AmbientSportController.NO_COMMAND,
                "amb unknown: brightness unknown -> no send");
        check(off.update(3000, true, true, AmbientSportController.UNKNOWN_COLOR, 6) == AmbientSportController.NO_COMMAND,
                "amb unknown: color unknown -> no send");
        check(off.update(4000, true, true, blue, 2) == AmbientSportController.RED && off.savedOriginal() == blue,
                "amb on: brightness 2 sends red");

        // 6. Misses -> halt (2 misses).
        AmbientSportController halt = new AmbientSportController();
        check(halt.update(0, true, true, blue, 6) == AmbientSportController.RED, "amb halt: first send");
        check(halt.update(3000, true, true, blue, 6) == AmbientSportController.RED
                && halt.misses() == 1 && !halt.isHalted(), "amb halt: 1st miss retries");
        check(halt.update(6000, true, true, blue, 6) == AmbientSportController.NO_COMMAND
                && halt.misses() == 2 && halt.isHalted(), "amb halt: 2nd miss halts");
        check(halt.update(20000, true, true, blue, 6) == AmbientSportController.NO_COMMAND && halt.isHalted(),
                "amb halt: stays halted");
        halt.clearHalt();
        check(!halt.isHalted() && halt.misses() == 0, "amb halt: clearHalt resets");

        // 7. Option off restores immediately (respecting 3 s gap).
        AmbientSportController optOff = new AmbientSportController();
        check(optOff.update(0, true, true, blue, 6) == AmbientSportController.RED, "amb optOff: start");
        check(optOff.update(300, true, true, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND,
                "amb optOff: confirmed");
        check(optOff.update(1000, false, true, AmbientSportController.RED, 6) == AmbientSportController.NO_COMMAND,
                "amb optOff: rate-limited within 3 s");
        check(optOff.update(3000, false, true, AmbientSportController.RED, 6) == blue
                && AmbientSportController.REASON_OPTION_OFF.equals(optOff.lastReason())
                && optOff.savedOriginal() == AmbientSportController.UNKNOWN_COLOR, "amb optOff: restores at 3 s");

        // 8. Restart restore from saved hex.
        long saved = AmbientSportController.parseHex("009b002100ef");
        AmbientSportController restart = new AmbientSportController(AmbientSportController.DEFAULT_RELEASE_MS, saved);
        check(restart.savedOriginal() == blue, "amb restart: persisted hex loaded");
        check(restart.update(0, true, false, AmbientSportController.UNKNOWN_COLOR, -1) == AmbientSportController.NO_COMMAND
                && restart.savedOriginal() == blue, "amb restart: waits for known status");
        check(restart.update(500, true, false, AmbientSportController.RED, 6) == blue
                && AmbientSportController.REASON_RESTORE_RESTART.equals(restart.lastReason())
                && restart.savedOriginal() == AmbientSportController.UNKNOWN_COLOR,
                "amb restart: restores original when not sport and at red");

        // 9. Factory theme original (hi 0x01) restored with exact bytes.
        AmbientSportController th = new AmbientSportController();
        check(th.update(0, true, true, theme, 8) == AmbientSportController.RED && th.savedOriginal() == theme,
                "amb theme: saves factory theme original");
        check(th.update(300, true, true, AmbientSportController.RED, 8) == AmbientSportController.NO_COMMAND,
                "amb theme: confirmed");
        long restored = th.update(4000, true, false, AmbientSportController.RED, 8);
        check(restored == theme, "amb theme: restores exact packed theme value");
        byte[] tr = AmbientSportController.redBytes(restored);
        byte[] tg = AmbientSportController.greenBytes(restored);
        byte[] tb = AmbientSportController.blueBytes(restored);
        check(tr[0] == 1 && (tr[1] & 0xff) == 0x6b
                && tg[0] == 1 && (tg[1] & 0xff) == 0x5a
                && tb[0] == 1 && (tb[1] & 0xff) == 0x95, "amb theme: exact hi=0x01 bytes restored");
    }

}
