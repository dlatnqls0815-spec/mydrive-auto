package io.github.k8ecs.car;

import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lets the HMG-Seogaemo app drive this module through its restricted module format (no shell, no daemons):
 * <ul>
 * <li>settings: the Seogaemo module writes persist.sys.k8ecs.* with set_prop while it is on, and restores
 *     them when it is turned off. Only changes are applied, so the head unit app can still edit settings.</li>
 * <li>status: this module publishes sys.k8ecs.* (ASCII, as Seogaemo shows only ASCII values).</li>
 * <li>log export: Seogaemo's button runs call_service k8ecs 1, served by a binder service registered here.</li>
 * </ul>
 */
final class SeogaemoBridge {
    static final String IN = "persist.sys.k8ecs.";
    static final String OUT = "sys.k8ecs.";
    static final String SERVICE = "k8ecs";
    static final int CODE_EXPORT_USB = 1;

    private final CarMain m;
    private final Map<String, String> published = new HashMap<String, String>();
    // One Seogaemo card (k8-mydrive) carries all three items, their links and the shared limits.
    static final String[] PROPS = {"mydrive", "suspon", "speedup", "hyst", "corner_on", "corner_off", "dwell", "lnk_se",
            "lnk_sh", "steeron", "steerup", "steerhyst", "steercon", "steercoff", "steerdwell", "engon", "engpedal",
            "enghold", "lnk_es", "lnk_eh", "maxpm"};
    private String lastKey;
    private boolean lastOn;
    private Method getProp, setProp;

    SeogaemoBridge(CarMain m) {
        this.m = m;
    }

    void run() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            getProp = sp.getMethod("get", String.class, String.class);
            setProp = sp.getMethod("set", String.class, String.class);
        } catch (Exception e) {
            CarMain.Log.i("seogaemo bridge unavailable: " + e);
            return;
        }
        registerService();
        while (true) {
            try {
                pollSettings();
                publishStatus();
            } catch (Throwable t) {
                CarMain.Log.i("seogaemo bridge error " + t);
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }

    private void registerService() {
        android.os.Binder service = new android.os.Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
                if (code != CODE_EXPORT_USB) return super.onTransact(code, data, reply, flags);
                String result = m.exportUsb();
                publish("export", result);
                publish("cfg", "log " + result);
                if (reply != null) {
                    reply.writeNoException();
                    reply.writeString(result);
                }
                return true;
            }
        };
        // "service call" (used by Seogaemo's call_service) refuses binders without an interface name.
        service.attachInterface(null, "io.github.k8ecs.IK8ecs");
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            sm.getMethod("addService", String.class, IBinder.class).invoke(null, SERVICE, service);
            CarMain.Log.i("binder service " + SERVICE + " registered");
        } catch (Exception e) {
            CarMain.Log.i("binder service " + SERVICE + " not registered: " + e);
        }
    }

    // ---------- settings changed in the phone / head unit app -> the Seogaemo card ----------

    /**
     * Card setting, its persist.sys.k8ecs.* property, the engine value divisor (0 = toggle) and the card's
     * choice list (mirrors seogaemo/k8-mydrive/module.properties; tests check they match).
     */
    static final String[][] CARD = {
        {"s_on", "suspon", "0", ""},
        {"s_up", "speedup", "1", "0|30|40|50|60|70|80|90|100|110|120|130|140|150|160"},
        {"s_hy", "hyst", "1", "5|10|15|20|25|30"},
        {"s_co", "corner_on", "10", "0|5|8|10|12|15|18|20|25|30|35|40|50"},
        {"s_cf", "corner_off", "10", "3|5|7|8|10|12|15|20|25|30"},
        {"s_dw", "dwell", "1000", "1|2|3|5|7|10|15|20|30"},
        {"s_eng", "lnk_se", "0", ""},
        {"s_str", "lnk_sh", "0", ""},
        {"h_on", "steeron", "0", ""},
        {"h_up", "steerup", "1", "0|30|40|50|60|70|80|90|100|110|120|130|140|150|160"},
        {"h_hy", "steerhyst", "1", "5|10|15|20|25|30"},
        {"h_co", "steercon", "10", "0|5|8|10|12|15|18|20|25|30|35|40|50"},
        {"h_cf", "steercoff", "10", "3|5|7|8|10|12|15|20|25|30"},
        {"h_dw", "steerdwell", "1000", "1|2|3|5|7|10|15|20|30"},
        {"e_on", "engon", "0", ""},
        {"e_pd", "engpedal", "1", "5|10|15|20|25|30|35|40|45|50|55|60|65|70|75|80|85|90|95|100"},
        {"e_hd", "enghold", "1000", "1|2|3|4|5|7|10|15|20|30|45|60"},
        {"e_sus", "lnk_es", "0", ""},
        {"e_str", "lnk_eh", "0", ""},
        {"mx", "maxpm", "1", "4|6|8|10|12|15|20"},
    };

    static final String SG_ROOT = "/data/nemesis/apps/hmg-seogaemo/bin/hmg-seogaemo-root";
    static final String SG_SETTINGS = "/data/nemesis/backup/hmg-seogaemo/settings/k8-mydrive/";
    private final Object syncLock = new Object();
    private boolean syncing;
    private String[] pendingWant;
    private volatile long settleUntil;

    /** Engine values in card units (on/off, km/h, 0.01 g, s), snapped to the card's lists. */
    static String[] cardValues(io.github.k8ecs.core.Settings s) {
        int[] v = {s.suspOn ? 1 : 0, s.speedUpKmh, s.hysteresisKmh, s.cornerOnMilliG, s.cornerOffMilliG, s.dwellMs,
                s.suspLinkEng ? 1 : 0, s.suspLinkSteer ? 1 : 0, s.steerOn ? 1 : 0, s.steerSpeedUpKmh,
                s.steerHysteresisKmh, s.steerCornerOnMilliG, s.steerCornerOffMilliG, s.steerDwellMs,
                s.engOn ? 1 : 0, s.engPedalPct, s.engHoldMs, s.engLinkSusp ? 1 : 0, s.engLinkSteer ? 1 : 0,
                s.maxPerMinute};
        String[] out = new String[CARD.length];
        for (int i = 0; i < CARD.length; i++) {
            int div = Integer.parseInt(CARD[i][2]);
            if (div == 0) { out[i] = v[i] != 0 ? "on" : "off"; continue; }
            out[i] = nearest(CARD[i][3], Math.round(v[i] / (float) div));
        }
        return out;
    }

    static String nearest(String list, int value) {
        String best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (String o : list.split("\\|")) {
            int d = Math.abs(Integer.parseInt(o) - value);
            if (d < bestDiff) { bestDiff = d; best = o; }
        }
        return best;
    }

    /**
     * Writes settings changed outside the card (phone, head unit app) into the card, so the card shows them
     * and they survive restarts. Several values are written to the card's settings files and one "set"
     * re-applies the card (Seogaemo validates it). Values outside the card's lists are snapped, and the
     * card then hands the snapped values back to the engine. Runs in the background.
     */
    void syncToCard(io.github.k8ecs.core.Settings s) {
        synchronized (syncLock) {
            // During a sync the card is briefly off ("set" reverts, then applies), so only check when idle.
            if (!syncing && !"1".equals(get("mydrive"))) return;  // card not installed or off
            pendingWant = cardValues(s);
            if (syncing) return;  // the running sync picks the newest values up when it finishes
            syncing = true;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    String[] want;
                    synchronized (syncLock) {
                        want = pendingWant;
                        pendingWant = null;
                        if (want == null) {
                            settleUntil = System.currentTimeMillis() + SETTLE_MS;
                            syncing = false;
                            return;
                        }
                    }
                    try {
                        syncOnce(want);
                    } catch (Throwable e) {
                        CarMain.Log.i("card sync error " + e);
                    }
                }
            }
        }, "card-sync");
        t.setDaemon(true);
        t.start();
    }

    /** Ignore the card's values this long after a sync, while Seogaemo finishes re-applying it. */
    private static final long SETTLE_MS = 3000;

    /** True while the card is being rewritten; the card's own values are then not handed to the engine. */
    boolean busy() {
        return syncing || System.currentTimeMillis() < settleUntil;
    }

    private void syncOnce(String[] want) throws Exception {
        StringBuilder writes = new StringBuilder();
        String lastName = null, lastValue = null;
        for (int i = 0; i < CARD.length; i++) {
            if (want[i].equals(get(CARD[i][1]))) continue;
            if (lastName != null) writes.append("printf %s ").append(lastValue).append(" > ").append(SG_SETTINGS).append(lastName).append("; ");
            lastName = CARD[i][0];
            lastValue = want[i];
        }
        if (lastName == null) return;
        String out = exec("/system/xbin/nemesis", "root", writes + "/system/bin/sh " + SG_ROOT + " k8-mydrive set " + lastName + " " + lastValue);
        String state = "?";
        for (String l : out.split("\n")) if (l.startsWith("state=")) state = l.substring(6).trim();
        CarMain.Log.i("card sync " + state);
        updateAppScreen(want);
    }

    static final String SG_APP = "com.seogaemo.hmg";
    static final String SG_PREFS = "/data/data/" + SG_APP + "/shared_prefs/module_settings.xml";

    /**
     * The Seogaemo app draws the card from its own module_settings preferences, not from the module's
     * settings files, so it keeps showing old values. Write the new values there as the app's user and
     * restart the app (reopened in front if it was in front) so the card shows them.
     */
    static void updateAppScreen(String[] want) throws Exception {
        String owner = exec("/system/xbin/nemesis", "root", "0", "ls -ld /data/data/" + SG_APP);
        java.util.regex.Matcher um = java.util.regex.Pattern.compile("u0_a(\\d+)").matcher(owner);
        if (!um.find()) { CarMain.Log.i("seogaemo app screen: app not found"); return; }
        String uid = Integer.toString(10000 + Integer.parseInt(um.group(1)));
        String xml = exec("/system/xbin/nemesis", "root", uid, "cat " + SG_PREFS);
        if (!xml.contains("<map")) xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n";
        if (xml.contains("<map />")) xml = xml.replace("<map />", "<map>\n</map>");
        for (int i = 0; i < CARD.length; i++) {
            String key = "k8-mydrive." + CARD[i][0];
            String line = "<string name=\"" + key + "\">" + want[i] + "</string>";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<string name=\"" + java.util.regex.Pattern.quote(key) + "\">[^<]*</string>").matcher(xml);
            if (m.find()) xml = xml.substring(0, m.start()) + line + xml.substring(m.end());
            else xml = xml.replace("</map>", "    " + line + "\n</map>");
        }
        java.io.File tmp = new java.io.File(CarMain.LOG_DIR, "sg-prefs.tmp");
        java.io.FileOutputStream o = new java.io.FileOutputStream(tmp);
        try { o.write(xml.getBytes("UTF-8")); } finally { o.close(); }
        tmp.setReadable(true, false);
        String top = exec("/system/xbin/nemesis", "root", "1000", "dumpsys activity activities");
        boolean front = false;
        for (String l : top.split("\n")) if (l.contains("mFocusedActivity") && l.contains(SG_APP)) front = true;
        exec("/system/xbin/nemesis", "root", uid, "cp " + tmp.getPath() + " " + SG_PREFS);
        tmp.delete();
        exec("/system/xbin/nemesis", "root", "1000", "am force-stop " + SG_APP
                + (front ? "; am start -n " + SG_APP + "/.MainActivity" : ""));
        CarMain.Log.i("seogaemo app screen updated" + (front ? " (reopened)" : ""));
    }

    static String exec(String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        StringBuilder b = new StringBuilder();
        java.io.InputStream in = p.getInputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) { if (b.length() < 200000) b.append(new String(buf, 0, n, "UTF-8")); }
        p.waitFor();
        return b.toString().replace("\r", "");
    }

    // ---------- settings from the Seogaemo module ----------

    private void pollSettings() {
        synchronized (syncLock) {
            if (busy()) return;  // half-applied card values during our own sync; read them once it settles
        }
        StringBuilder key = new StringBuilder();
        Map<String, String> v = new HashMap<String, String>();
        for (String p : PROPS) {
            String x = get(p);
            v.put(p, x);
            key.append(x).append('|');
        }
        if (key.toString().equals(lastKey)) return;
        lastKey = key.toString();
        boolean nowOn = "1".equals(v.get("mydrive"));
        try {
            if (nowOn) {
                m.apply(toSettings(v));
                publish("cfg", "ok");
                CarMain.Log.i("seogaemo settings applied");
            } else if (lastOn) {
                m.apply("enabled=0");
                publish("cfg", "off");
                CarMain.Log.i("seogaemo card off: auto switching off");
            }
        } catch (IllegalArgumentException e) {
            publish("cfg", "rejected " + e.getMessage());
            CarMain.Log.i("seogaemo settings rejected: " + e.getMessage());
        }
        lastOn = nowOn;
    }

    /** Seogaemo values (toggles on/off, km/h, 0.01 g, seconds) to the module's setting line. Throws on bad values. */
    static String toSettings(Map<String, String> v) {
        return "enabled=1 suspOn=" + tog(v.get("suspon")) + " speedUp=" + num(v.get("speedup")) + " hyst=" + num(v.get("hyst"))
                + " cornerOn=" + num(v.get("corner_on")) * 10 + " cornerOff=" + num(v.get("corner_off")) * 10
                + " dwell=" + num(v.get("dwell")) * 1000 + " suspLinkEng=" + tog(v.get("lnk_se"))
                + " suspLinkSteer=" + tog(v.get("lnk_sh"))
                + " steerOn=" + tog(v.get("steeron")) + " steerSpeedUp=" + num(v.get("steerup"))
                + " steerHyst=" + num(v.get("steerhyst")) + " steerCornerOn=" + num(v.get("steercon")) * 10
                + " steerCornerOff=" + num(v.get("steercoff")) * 10 + " steerDwell=" + num(v.get("steerdwell")) * 1000
                + " engOn=" + tog(v.get("engon")) + " engPedal=" + num(v.get("engpedal"))
                + " engHold=" + num(v.get("enghold")) * 1000 + " engLinkSusp=" + tog(v.get("lnk_es"))
                + " engLinkSteer=" + tog(v.get("lnk_eh")) + " maxPerMin=" + num(v.get("maxpm"));
    }

    static int tog(String x) {
        if ("on".equals(x) || "1".equals(x)) return 1;
        if ("off".equals(x) || "0".equals(x)) return 0;
        throw new IllegalArgumentException("toggle");
    }



    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("number");
        }
    }

    // ---------- status for the Seogaemo screen ----------

    private void publishStatus() {
        Map<String, String> st = pairs(m.statusLine());
        publish("alive", "1");
        publish("ver", st.get("ver"));
        for (Map.Entry<String, String> e : statusValues(st).entrySet()) publish(e.getKey(), e.getValue());
    }

    /** Status pairs (CarMain STATUS) to the ASCII values shown in Seogaemo. */
    static Map<String, String> statusValues(Map<String, String> st) {
        Map<String, String> out = new HashMap<String, String>();
        boolean enabled = "1".equals(st.get("enabled"));
        String halted = String.valueOf(st.get("haltedItems"));
        out.put("state", item(enabled, st.get("suspOn"), halted.contains("S")));
        out.put("steerstate", item(enabled, st.get("steerOn"), halted.contains("H")));
        out.put("engstate", item(enabled, st.get("engOn"), halted.contains("E")));
        String steer = st.get("steer");
        out.put("steer", "normal".equals(steer) ? "NORMAL" : "sport".equals(steer) ? "SPORT" : "unknown");
        String eng = st.get("eng");
        out.put("eng", "eco".equals(eng) ? "ECO" : "sport".equals(eng) ? "SPORT" : "unknown");
        String pedal = st.get("pedal");
        out.put("pedal", "?".equals(pedal) || pedal == null ? "unavailable" : pedal);  // Seogaemo shows % as ?
        out.put("steersent", String.valueOf(st.get("steerSent")));
        out.put("engsent", String.valueOf(st.get("engSent")));
        out.put("speed", st.get("kmh") + " km/h");
        out.put("latg", g(st.get("lat")) + " (peak " + g(st.get("peak")) + ")");
        String mode = st.get("mode");
        out.put("mode", "2".equals(mode) ? "ECO" : "3".equals(mode) ? "SPORT" : "6".equals(mode) ? ("1".equals(st.get("custom")) ? "MY DRIVE" : "SMART")
                : "?".equals(mode) || mode == null ? "unknown" : "CUSTOM (" + mode + ")");
        String step = st.get("step");
        out.put("susp", ("soft".equals(step) ? "ECO (soft)" : "firm".equals(step) ? "SPORT (firm)" : "unknown")
                + ("1".equals(st.get("corner")) ? " - corner" : ""));
        String latcal = String.valueOf(st.get("latcal"));
        out.put("calib", "ok".equals(latcal) ? "done" : "wait".equals(latcal) ? "starts after a stop"
                : "approx, learning " + latcal.replace("est", "") + " pct (accelerate/brake)");
        out.put("sent", st.get("suspSent") + " (last " + String.valueOf(st.get("last")).replace('_', ' ') + ")");
        // Merged card values.
        StringBuilder state = new StringBuilder();
        if (!enabled) state.append("OFF");
        else {
            state.append("1".equals(st.get("custom")) ? "ON S:" : "WAIT (not My Drive) S:").append(onOff(st.get("suspOn"), halted.contains("S")))
                    .append(" H:").append(onOff(st.get("steerOn"), halted.contains("H")))
                    .append(" E:").append(onOff(st.get("engOn"), halted.contains("E")));
        }
        out.put("st", state.toString());
        out.put("now", ("soft".equals(step) ? "ECO" : "firm".equals(step) ? "SPORT" : "?") + " / " + out.get("steer")
                + " / " + out.get("eng") + " (" + st.get("suspSent") + "/" + st.get("steerSent") + "/" + st.get("engSent") + ")");
        out.put("sp", st.get("kmh") + " km/h / " + g(st.get("lat")) + " / " + out.get("pedal"));
        out.put("sn", st.get("suspSent") + " / " + st.get("steerSent") + " / " + st.get("engSent"));
        return out;
    }

    static String onOff(String on, boolean halted) {
        return halted ? "STOPPED" : "1".equals(on) ? "on" : "off";
    }

    static String item(boolean enabled, String on, boolean halted) {
        if (halted) return "STOPPED - car did not follow";
        return enabled && "1".equals(on) ? "ON (My Drive only)" : "OFF";
    }

    static String g(String milli) {
        try {
            return String.format(Locale.US, "%.2f g", Integer.parseInt(milli) / 1000.0);
        } catch (Exception e) {
            return "?";
        }
    }

    static Map<String, String> pairs(String text) {
        Map<String, String> m = new HashMap<String, String>();
        for (String tok : text.trim().split("\\s+")) {
            int eq = tok.indexOf('=');
            if (eq > 0) m.put(tok.substring(0, eq), tok.substring(eq + 1));
        }
        return m;
    }

    /** Property values: ASCII only, at most 91 bytes (Android 4.4 PROP_VALUE_MAX - 1). */
    static String clean(String v) {
        if (v == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length() && b.length() < 91; i++) {
            char c = v.charAt(i);
            b.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        return b.toString();
    }

    private String get(String name) {
        try {
            return (String) getProp.invoke(null, IN + name, "");
        } catch (Exception e) {
            return "";
        }
    }

    private void publish(String name, String value) {
        String v = clean(value);
        synchronized (published) {
            if (v.equals(published.get(name))) return;
            try {
                setProp.invoke(null, OUT + name, v);
                published.put(name, v);
            } catch (Exception e) {
                CarMain.Log.i("could not set " + OUT + name + ": " + e);
                published.put(name, v); // do not retry every second
            }
        }
    }
}
