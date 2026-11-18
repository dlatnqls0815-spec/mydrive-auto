package io.github.k8ecs.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User settings shared by the car module, the head unit app, the phone app and the Seogaemo modules.
 * Text form: space-separated key=value pairs (also used one-per-line in the config file).
 *
 * Three My Drive items, each with two steps (0 = low, 1 = sport):
 * <ul>
 * <li>suspension (item 1): speed and lateral G rules ({@code speedUp hyst cornerOn cornerOff dwell})</li>
 * <li>steering (item 2): its own speed and lateral G rules ({@code steer*})</li>
 * <li>powertrain (item 0): accelerator pedal rule ({@code engPedal engHold}); low step is Eco (hybrid, value 2)
 *     or Normal ({@code engNormal=1}, value 0, for cars whose powertrain item has Normal/Sport)</li>
 * </ul>
 * {@code enabled} is the master switch; each item has its own on flag. All work only in My Drive.
 */
public final class Settings {
    public final boolean enabled;
    public final boolean suspOn;
    public final int speedUpKmh;      // 0 = no speed rule
    public final int hysteresisKmh;
    public final int cornerOnMilliG;  // 0 = no corner rule
    public final int cornerOffMilliG;
    public final int dwellMs;
    public final int maxPerMinute;    // per item
    public final double gyroScale;    // 0 = learn automatically (kept for the gyro comparison log)

    public final boolean steerOn;
    public final int steerSpeedUpKmh;
    public final int steerHysteresisKmh;
    public final int steerCornerOnMilliG;
    public final int steerCornerOffMilliG;
    public final int steerDwellMs;

    public final boolean engOn;
    public final int engPedalPct;     // pedal at or above this -> sport
    public final int engHoldMs;       // pedal below it this long -> low step
    public final boolean engNormal;   // low step is Normal (value 0) instead of Eco (value 2)

    // Links: when one item's own rule goes sport, also put the other item to sport.
    public final boolean engLinkSusp, engLinkSteer, suspLinkEng, suspLinkSteer;

    private Settings(Map<String, String> m) {
        enabled = flag(m, "enabled");
        suspOn = flag(m, "suspOn");
        speedUpKmh = num(m, "speedUp");
        hysteresisKmh = num(m, "hyst");
        cornerOnMilliG = num(m, "cornerOn");
        cornerOffMilliG = num(m, "cornerOff");
        dwellMs = num(m, "dwell");
        maxPerMinute = num(m, "maxPerMin");
        gyroScale = dbl(m, "gyroScale");
        // Suspension and steering auto are one or the other (user, 2026-11-16); links still drive the other.
        steerOn = flag(m, "steerOn") && !suspOn;
        steerSpeedUpKmh = num(m, "steerSpeedUp");
        steerHysteresisKmh = num(m, "steerHyst");
        steerCornerOnMilliG = num(m, "steerCornerOn");
        steerCornerOffMilliG = num(m, "steerCornerOff");
        steerDwellMs = num(m, "steerDwell");
        engOn = flag(m, "engOn");
        engPedalPct = num(m, "engPedal");
        engHoldMs = num(m, "engHold");
        engNormal = flag(m, "engNormal");
        engLinkSusp = flag(m, "engLinkSusp");
        engLinkSteer = flag(m, "engLinkSteer");
        suspLinkEng = flag(m, "suspLinkEng");
        suspLinkSteer = flag(m, "suspLinkSteer");

        speedRule("speedUp", speedUpKmh, hysteresisKmh, "speedHysteresis");
        speedRule("steerSpeedUp", steerSpeedUpKmh, steerHysteresisKmh, "steerHyst");
        if (Double.isNaN(gyroScale) || Math.abs(gyroScale) > 10) throw new IllegalArgumentException("gyroScale");
        if (engPedalPct < 5 || engPedalPct > 100) throw new IllegalArgumentException("engPedal 5..100");
        if (engHoldMs < 1000 || engHoldMs > 60000) throw new IllegalArgumentException("engHold 1..60 s");
        toRules(); // validates the rest
        steerRules();
    }

    private static void speedRule(String name, int up, int hyst, String hystName) {
        if (up != 0 && (up < 20 || up > 200)) throw new IllegalArgumentException(name + " 20..200 or 0");
        if (up != 0 && hyst >= up) throw new IllegalArgumentException(hystName + " must be below " + name);
    }

    private static Map<String, String> defaultMap() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("enabled", "0");
        m.put("suspOn", "1");
        m.put("speedUp", "100");
        m.put("hyst", "15");
        m.put("cornerOn", "250");
        m.put("cornerOff", "120");
        m.put("dwell", "3000");
        m.put("maxPerMin", "8");
        m.put("gyroScale", "0.0");
        m.put("steerOn", "0");
        m.put("steerSpeedUp", "100");
        m.put("steerHyst", "15");
        m.put("steerCornerOn", "300");
        m.put("steerCornerOff", "150");
        m.put("steerDwell", "3000");
        m.put("engOn", "0");
        m.put("engPedal", "60");
        m.put("engHold", "5000");
        m.put("engNormal", "0");
        m.put("engLinkSusp", "0");
        m.put("engLinkSteer", "0");
        m.put("suspLinkEng", "0");
        m.put("suspLinkSteer", "0");
        return m;
    }

    public static Settings defaults() {
        return new Settings(defaultMap());
    }

    public DampingRules toRules() {
        int[] up = speedUpKmh == 0 ? new int[0] : new int[] {speedUpKmh};
        return new DampingRules(2, up, hysteresisKmh, cornerOnMilliG, cornerOffMilliG, 1,
                300, 2500, dwellMs, maxPerMinute, 1000);
    }

    public DampingRules steerRules() {
        int[] up = steerSpeedUpKmh == 0 ? new int[0] : new int[] {steerSpeedUpKmh};
        return new DampingRules(2, up, steerHysteresisKmh, steerCornerOnMilliG, steerCornerOffMilliG, 1,
                300, 2500, steerDwellMs, maxPerMinute, 1000);
    }

    public String encode() {
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, String> e : toMap().entrySet()) {
            if (s.length() > 0) s.append(' ');
            s.append(e.getKey()).append('=').append(e.getValue());
        }
        return s.toString();
    }

    public Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("enabled", enabled ? "1" : "0");
        m.put("suspOn", suspOn ? "1" : "0");
        m.put("speedUp", Integer.toString(speedUpKmh));
        m.put("hyst", Integer.toString(hysteresisKmh));
        m.put("cornerOn", Integer.toString(cornerOnMilliG));
        m.put("cornerOff", Integer.toString(cornerOffMilliG));
        m.put("dwell", Integer.toString(dwellMs));
        m.put("maxPerMin", Integer.toString(maxPerMinute));
        m.put("gyroScale", Double.toString(gyroScale));
        m.put("steerOn", steerOn ? "1" : "0");
        m.put("steerSpeedUp", Integer.toString(steerSpeedUpKmh));
        m.put("steerHyst", Integer.toString(steerHysteresisKmh));
        m.put("steerCornerOn", Integer.toString(steerCornerOnMilliG));
        m.put("steerCornerOff", Integer.toString(steerCornerOffMilliG));
        m.put("steerDwell", Integer.toString(steerDwellMs));
        m.put("engOn", engOn ? "1" : "0");
        m.put("engPedal", Integer.toString(engPedalPct));
        m.put("engHold", Integer.toString(engHoldMs));
        m.put("engNormal", engNormal ? "1" : "0");
        m.put("engLinkSusp", engLinkSusp ? "1" : "0");
        m.put("engLinkSteer", engLinkSteer ? "1" : "0");
        m.put("suspLinkEng", suspLinkEng ? "1" : "0");
        m.put("suspLinkSteer", suspLinkSteer ? "1" : "0");
        return m;
    }

    /**
     * Applies key=value pairs (space or newline separated) on top of base. Unknown keys are errors, except
     * {@code allModes} from 0.1.7 configs, which is ignored (the option was removed: My Drive only).
     */
    public static Settings parse(String text, Settings base) {
        Map<String, String> m = base.toMap();
        for (String tok : text.trim().split("\\s+")) {
            if (tok.isEmpty() || tok.startsWith("#")) continue;
            int eq = tok.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("bad pair: " + tok);
            String k = tok.substring(0, eq);
            if (k.equals("allModes")) continue;
            if (!m.containsKey(k)) throw new IllegalArgumentException("unknown key: " + k);
            m.put(k, tok.substring(eq + 1));
        }
        return new Settings(m);
    }

    private static boolean flag(Map<String, String> m, String k) {
        String v = m.get(k);
        if ("1".equals(v)) return true;
        if ("0".equals(v)) return false;
        throw new IllegalArgumentException(k + " 0 or 1");
    }

    private static int num(Map<String, String> m, String k) {
        try {
            return Integer.parseInt(m.get(k));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("number: " + k);
        }
    }

    private static double dbl(Map<String, String> m, String k) {
        try {
            return Double.parseDouble(m.get(k));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("number: " + k);
        }
    }
}
