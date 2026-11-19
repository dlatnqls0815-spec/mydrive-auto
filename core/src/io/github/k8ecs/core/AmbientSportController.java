package io.github.k8ecs.core;

import java.util.Locale;

/**
 * Decides when to switch the factory ambient light (SML) to red for a sport state and when to restore
 * the driver's original 6-byte RGB/theme color. Delegates timing/feedback/manual-override rules to
 * {@link SportOverrideController}. Pure logic (no Android). Not thread-safe.
 */
public final class AmbientSportController {
    /** Returned by {@link #update} when nothing should be sent. */
    public static final long NO_COMMAND = SportOverrideController.NO_VALUE;
    /** Value when the current ambient color is unknown or invalid. */
    public static final long UNKNOWN_COLOR = SportOverrideController.NO_VALUE;
    /** Direct red observed in the K8 factory color selector and matching SML response. */
    public static final long RED = 0x00fa00040051L;
    private static final long PREVIOUS_RED = 0x00ff00000000L;

    public static final long DEFAULT_RELEASE_MS = SportOverrideController.DEFAULT_RELEASE_MS;
    public static final long PENDING_TIMEOUT_MS = SportOverrideController.PENDING_TIMEOUT_MS;
    public static final long MIN_GAP_MS = SportOverrideController.MIN_GAP_MS;
    public static final int MAX_MISSES = SportOverrideController.MAX_MISSES;

    public static final String REASON_SPORT_START = SportOverrideController.REASON_SPORT_START;
    public static final String REASON_SPORT_END = SportOverrideController.REASON_SPORT_END;
    public static final String REASON_OPTION_OFF = SportOverrideController.REASON_OPTION_OFF;
    public static final String REASON_RESTORE_RESTART = SportOverrideController.REASON_RESTORE_RESTART;

    private final SportOverrideController core;
    private boolean acceptPreviousRedOnStartup;

    public AmbientSportController() {
        this(DEFAULT_RELEASE_MS, UNKNOWN_COLOR);
    }

    public AmbientSportController(long releaseMs) {
        this(releaseMs, UNKNOWN_COLOR);
    }

    /**
     * @param releaseMs         continuous non-sport time before restoring the original color (0 = immediate)
     * @param persistedOriginal original 6-byte color saved before a restart, or {@link #UNKNOWN_COLOR} if none
     */
    public AmbientSportController(long releaseMs, long persistedOriginal) {
        this.core = new SportOverrideController(releaseMs,
                isValidColor(persistedOriginal) ? persistedOriginal : SportOverrideController.NO_VALUE);
        acceptPreviousRedOnStartup = isValidColor(persistedOriginal) && persistedOriginal != PREVIOUS_RED;
    }

    /** True when the 48-bit packed color has valid channel hi bytes (0x00 direct or 0x01 factory theme). */
    public static boolean isValidColor(long color) {
        if (color < 0 || (color >>> 48) != 0) return false;
        int rHi = (int) ((color >>> 40) & 0xff);
        int gHi = (int) ((color >>> 24) & 0xff);
        int bHi = (int) ((color >>> 8) & 0xff);
        return (rHi == 0 || rHi == 1) && (gHi == 0 || gHi == 1) && (bHi == 0 || bHi == 1);
    }

    /** Packs bytes 0..5 of an SML status report into a 48-bit color, or {@link #UNKNOWN_COLOR} if invalid. */
    public static long packColor(byte[] smlBytes) {
        if (smlBytes == null || smlBytes.length < 6) return UNKNOWN_COLOR;
        long c = ((long) (smlBytes[0] & 0xff) << 40)
                | ((long) (smlBytes[1] & 0xff) << 32)
                | ((long) (smlBytes[2] & 0xff) << 24)
                | ((long) (smlBytes[3] & 0xff) << 16)
                | ((long) (smlBytes[4] & 0xff) << 8)
                | ((long) (smlBytes[5] & 0xff));
        return isValidColor(c) ? c : UNKNOWN_COLOR;
    }

    /** Extracts the 2-byte red channel {hi, lo} from a packed color. */
    public static byte[] redBytes(long color) {
        return new byte[] {(byte) ((color >>> 40) & 0xff), (byte) ((color >>> 32) & 0xff)};
    }

    /** Extracts the 2-byte green channel {hi, lo} from a packed color. */
    public static byte[] greenBytes(long color) {
        return new byte[] {(byte) ((color >>> 24) & 0xff), (byte) ((color >>> 16) & 0xff)};
    }

    /** Extracts the 2-byte blue channel {hi, lo} from a packed color. */
    public static byte[] blueBytes(long color) {
        return new byte[] {(byte) ((color >>> 8) & 0xff), (byte) (color & 0xff)};
    }

    /** Encodes a valid color as 12 lowercase hex chars, or "?" otherwise. */
    public static String toHex(long color) {
        return isValidColor(color) ? String.format(Locale.US, "%012x", color) : "?";
    }

    /** Parses a 12-hex-char color string, or returns {@link #UNKNOWN_COLOR} if invalid. */
    public static long parseHex(String hex) {
        if (hex == null || hex.length() != 12) return UNKNOWN_COLOR;
        for (int i = 0; i < 12; i++) {
            char ch = hex.charAt(i);
            boolean ok = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
            if (!ok) return UNKNOWN_COLOR;
        }
        long v = Long.parseLong(hex, 16);
        return isValidColor(v) ? v : UNKNOWN_COLOR;
    }

    public long savedOriginal() { return core.savedOriginal(); }
    public long appliedTarget() { return core.appliedTarget(); }
    public boolean isHalted() { return core.isHalted(); }
    public int misses() { return core.misses(); }
    public long lastMissedColor() { return core.lastMissedValue(); }
    public boolean hasPending() { return core.hasPending(); }
    public String lastReason() { return core.lastReason(); }

    public void clearHalt() { core.clearHalt(); }
    public void onIgnitionOff() { core.onIgnitionOff(); }
    public void setReleaseMs(long releaseMs) { core.setReleaseMs(releaseMs); }

    /**
     * Evaluates one tick with the ignition on. Does nothing when ambient light is switched off
     * ({@code brightnessRaw == 1}) or status is unknown.
     *
     * @param nowMs         monotonic/wall timestamp in ms
     * @param enabled       feature enabled
     * @param sportActive   true when stock Sport mode or My Drive with any item in Sport (from car feedback)
     * @param currentColor  current 6-byte packed color from SYS_SML_STATUS_M_RESP, or {@link #UNKNOWN_COLOR}
     * @param brightnessRaw byte 6 of SYS_SML_STATUS_M_RESP (1 = off, 2..11 = level 1..10), or -1 if unknown
     * @return packed 6-byte color to send now, or {@link #NO_COMMAND}
     */
    public long update(long nowMs, boolean enabled, boolean sportActive, long currentColor, int brightnessRaw) {
        if (brightnessRaw == CarLog.SML_BRIGHTNESS_OFF) return NO_COMMAND;
        long cur = (brightnessRaw >= 2 && brightnessRaw <= 11 && isValidColor(currentColor))
                ? currentColor : UNKNOWN_COLOR;
        if (cur != UNKNOWN_COLOR && acceptPreviousRedOnStartup) {
            // A live update can inherit red sent by 0.4.7. Keep its saved original restorable.
            if (cur == PREVIOUS_RED && core.savedOriginal() != UNKNOWN_COLOR) cur = RED;
            else acceptPreviousRedOnStartup = false;
        }
        return core.update(nowMs, enabled, RED, sportActive, cur);
    }
}
