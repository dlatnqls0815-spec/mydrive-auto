package io.github.k8ecs.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses head unit "logcat -v time" lines the car module needs (K8 Gen5W, observed 2026-11-15).
 * Every method returns null / -1 when the line is not of its kind.
 */
public final class CarLog {
    private CarLog() {}

    /** My Drive items in setIndividualDriveMode / feedback index (K8, confirmed 2026-11-15/16 from the stock screen). */
    public static final int POWERTRAIN_ITEM = 0, SUSPENSION_ITEM = 1, STEERING_ITEM = 2;
    /** Feedback values the car reports for the two suspension steps. */
    public static final int FEEDBACK_SOFT = 2, FEEDBACK_FIRM = 5;

    public static final int MODE_ECO = 0x02, MODE_SPORT = 0x03, MODE_SMART = 0x06;

    private static final String DRIVE_MODE = "SYS_DRIVE_MODE_DATA_M_RESP:";
    private static final Pattern FEEDBACK = Pattern.compile("mIndividualModeFeedback:\\s+\\[0\\]\\s+\\d+\\s+\\[1\\]\\s+(\\d+)");
    private static final Pattern FEEDBACK3 = Pattern.compile(
            "mIndividualModeFeedback:\\s+\\[0\\]\\s+(\\d+)\\s+\\[1\\]\\s+(\\d+)\\s+\\[2\\]\\s+(\\d+)");
    private static final String IGNITION = "SYS_IGN_STATUS_M:";
    private static final String REVERSE = "Reverse status changed to :";
    private static final Pattern HEADING = Pattern.compile("gis\\.extra\\.HEADING=(\\d+)");
    private static final Pattern CLUSTER_THEME_USM = Pattern.compile("\\bCluster_ThemeStatusNew\\s*:\\s*(\\d+)");
    private static final Pattern CLUSTER_THEME_SETTING = Pattern.compile(
            "onClusterETCStatusReceived\\b.*\\bnThemeStatusNew\\s*:\\s*(\\d+)");
    private static final Pattern CLUSTER_AUTOSET = Pattern.compile(
            "\\b(?:Cluster_ThemeAutoSetStatus|nThemeAutoSetStatus)\\s*:\\s*(\\d+)");
    /** Stock cluster "drive mode link" status: 1 off, 2 on (K8, seen on the car 2026-11-20). */
    public static final int CLUSTER_LINK_OFF = 1, CLUSTER_LINK_ON = 2;
    private static final String SML_STATUS = "SYS_SML_STATUS_M_RESP:";
    /** Ambient light brightness raw byte (byte 6): 1 = off, 2..11 = level 1..10. */
    public static final int SML_BRIGHTNESS_OFF = 1;
    /** Factory "ambient follows drive mode" link byte (byte 8): 1 = off, 2 = on. */
    public static final int SML_LINK_OFF = 1, SML_LINK_ON = 2;

    /** Drive mode byte (byte 2) of a drive mode report; 0 (in transition) and 0x0f (boot) return -1. */
    public static int driveMode(String line) {
        int at = line.indexOf(DRIVE_MODE);
        if (at < 0) return -1;
        String[] p = line.substring(at + DRIVE_MODE.length()).trim().split("\\s+");
        if (p.length < 3) return -1;
        try {
            int v = Integer.decode(p[2]);
            return (v == 0 || v == 0x0f) ? -1 : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Suspension step from a feedback dump line: 0 soft, 1 firm, 9 other value, -1 not this line. */
    public static int suspensionStep(String line) {
        Matcher m = FEEDBACK.matcher(line);
        if (!m.find()) return -1;
        int v = Integer.parseInt(m.group(1));
        if (v == FEEDBACK_SOFT) return 0;
        if (v == FEEDBACK_FIRM) return 1;
        return 9;
    }

    /** Feedback values of items 0..2 (powertrain, suspension, steering) from a dump line, or null. */
    public static int[] feedbacks(String line) {
        Matcher m = FEEDBACK3.matcher(line);
        if (!m.find()) return null;
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }

    /** Ignition on/off. */
    public static Boolean ignition(String line) {
        int at = line.indexOf(IGNITION);
        if (at < 0) return null;
        String v = line.substring(at + IGNITION.length()).trim();
        if (v.startsWith("1")) return Boolean.TRUE;
        if (v.startsWith("0")) return Boolean.FALSE;
        return null;
    }

    /** Reverse gear engaged / released. */
    public static Boolean reverse(String line) {
        int at = line.indexOf(REVERSE);
        if (at < 0) return null;
        String v = line.substring(at + REVERSE.length()).trim().toLowerCase(java.util.Locale.US);
        if (v.startsWith("1") || v.startsWith("true") || v.startsWith("on")) return Boolean.TRUE;
        if (v.startsWith("0") || v.startsWith("false") || v.startsWith("off")) return Boolean.FALSE;
        return null;
    }

    /** Navigation GPS heading in degrees, or -1. Position fields on the same line are ignored. */
    public static int heading(String line) {
        Matcher m = HEADING.matcher(line);
        if (!m.find()) return -1;
        int v = Integer.parseInt(m.group(1));
        return v >= 0 && v < 360 ? v : -1;
    }

    /** True for modes where custom item settings are not in effect (eco, sport, smart). */
    public static boolean isStockMode(int mode) {
        return mode == MODE_ECO || mode == MODE_SPORT || mode == MODE_SMART;
    }

    private static final String MY_DRIVE_SLOT = "requestNewMyDriveMode] data:";
    /** Fourth drive mode slot chosen in the stock screen: My Drive (custom items) or Smart. */
    public static final int SLOT_MY_DRIVE = 1, SLOT_SMART = 2;

    /**
     * The stock vehicle settings app sends requestNewMyDriveMode "01 01" (My Drive) or "01 02" (Smart).
     * On K8 both report drive mode byte 0x06, so this is the only way to tell them apart (seen 2026-11-16:
     * "01 02" then mode 6 = Smart test; "01 01" then mode 6 = My Drive drive). Returns 1, 2, or -1.
     */
    public static int myDriveSlot(String line) {
        int at = line.indexOf(MY_DRIVE_SLOT);
        if (at < 0) return -1;
        String[] p = line.substring(at + MY_DRIVE_SLOT.length()).trim().split("[\\s,]+");
        if (p.length < 2) return -1;
        try {
            if (Integer.decode(p[0]) != 1) return -1;
            int v = Integer.decode(p[1]);
            return v == SLOT_MY_DRIVE || v == SLOT_SMART ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Whether the car uses the My Drive items now. Mode 0x06 is My Drive unless the stock screen last chose
     * Smart (slot 2); an unknown slot counts as My Drive because the items only act in My Drive anyway.
     */
    public static boolean isMyDrive(int mode, int slot) {
        if (mode < 0) return false;
        if (mode == MODE_SMART) return slot != SLOT_SMART;
        return !isStockMode(mode);
    }

    /**
     * Instrument cluster theme raw value (1..4) from USMService or ThemeSettingService logcat lines,
     * or -1 when the line is not a cluster theme status line or the value is out of range.
     */
    public static int clusterTheme(String line) {
        return clusterThemeRaw(line);
    }

    /** Stock cluster drive-mode-link status ({@link #CLUSTER_LINK_OFF}/{@link #CLUSTER_LINK_ON}) from a log line, or -1. */
    public static int clusterAutoSet(String line) {
        if (line == null) return -1;
        Matcher m = CLUSTER_AUTOSET.matcher(line);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int clusterThemeRaw(String line) {
        if (line == null) return -1;
        Matcher m = CLUSTER_THEME_USM.matcher(line);
        if (!m.find()) {
            m = CLUSTER_THEME_SETTING.matcher(line);
            if (!m.find()) return -1;
        }
        try {
            int v = Integer.parseInt(m.group(1));
            return ClusterThemeController.isValidTheme(v) ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Parses a SystemService {@code SYS_SML_STATUS_M_RESP:} line into its 9+ raw bytes, or returns null.
     * Bytes 0..5 = red hi/lo, green hi/lo, blue hi/lo; byte 6 = brightness raw (1 off, 2..11 level 1..10);
     * byte 7 = dim while driving; byte 8 = drive-mode link (1 off, 2 on).
     */
    public static byte[] smlStatus(String line) {
        if (line == null) return null;
        int at = line.indexOf(SML_STATUS);
        if (at < 0) return null;
        String rest = line.substring(at + SML_STATUS.length()).trim();
        if (rest.isEmpty()) return null;
        String[] p = rest.split("\\s+");
        if (p.length < 9) return null;
        byte[] out = new byte[p.length];
        for (int i = 0; i < p.length; i++) {
            try {
                int v = Integer.decode(p[i]);
                if (v < 0 || v > 0xff) return null;
                out[i] = (byte) v;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }
}
