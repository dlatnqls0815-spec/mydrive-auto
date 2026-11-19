package io.github.k8ecs.core;

/**
 * Decides when to switch the instrument cluster theme for a sport state and when to restore the
 * driver's original theme. Pure logic (no Android): the caller feeds vehicle state and sends
 * whatever {@link #update} returns. Not thread-safe.
 */
public final class ClusterThemeController {
    /** Returned by {@link #update} when nothing should be sent. */
    public static final int NO_COMMAND = -1;
    /** Raw theme value when the cluster theme is not yet known. */
    public static final int UNKNOWN_THEME = -1;
    public static final int MIN_THEME = 1;
    public static final int MAX_THEME = 4;

    public static final long DEFAULT_RELEASE_MS = SportOverrideController.DEFAULT_RELEASE_MS;
    public static final long PENDING_TIMEOUT_MS = SportOverrideController.PENDING_TIMEOUT_MS;
    public static final long MIN_GAP_MS = SportOverrideController.MIN_GAP_MS;
    public static final int MAX_MISSES = SportOverrideController.MAX_MISSES;

    public static final String REASON_SPORT_START = SportOverrideController.REASON_SPORT_START;
    public static final String REASON_SPORT_END = SportOverrideController.REASON_SPORT_END;
    public static final String REASON_OPTION_OFF = SportOverrideController.REASON_OPTION_OFF;
    public static final String REASON_RESTORE_RESTART = SportOverrideController.REASON_RESTORE_RESTART;

    private final SportOverrideController core;

    public ClusterThemeController() {
        this(DEFAULT_RELEASE_MS, -1);
    }

    public ClusterThemeController(long releaseMs) {
        this(releaseMs, -1);
    }

    /**
     * @param releaseMs         continuous non-sport time before restoring the original theme (0 = immediate)
     * @param persistedOriginal original theme saved before a restart (1..4), or -1 if none
     */
    public ClusterThemeController(long releaseMs, int persistedOriginal) {
        this.core = new SportOverrideController(releaseMs,
                isValidTheme(persistedOriginal) ? persistedOriginal : SportOverrideController.NO_VALUE);
    }

    public static boolean isValidTheme(int theme) {
        return theme >= MIN_THEME && theme <= MAX_THEME;
    }

    /** Theme label A..D for raw values 1..4, or "?" otherwise. */
    public static String themeLetter(int theme) {
        if (theme == 1) return "A";
        if (theme == 2) return "B";
        if (theme == 3) return "C";
        if (theme == 4) return "D";
        return "?";
    }

    public int savedOriginal() { return (int) core.savedOriginal(); }
    public int appliedTarget() { return (int) core.appliedTarget(); }
    public boolean isHalted() { return core.isHalted(); }
    public int misses() { return core.misses(); }
    public int lastMissedTheme() { return (int) core.lastMissedValue(); }
    public boolean hasPending() { return core.hasPending(); }
    public String lastReason() { return core.lastReason(); }

    /** Clears a halt (for user resume / settings reload) while keeping any saved original theme. */
    public void clearHalt() { core.clearHalt(); }

    /** Called when ignition goes off so an in-flight command does not falsely time out when IGN returns. */
    public void onIgnitionOff() { core.onIgnitionOff(); }
    public void setReleaseMs(long releaseMs) { core.setReleaseMs(releaseMs); }

    /**
     * Evaluates one tick with the ignition on.
     *
     * @param nowMs        monotonic/wall timestamp in ms
     * @param enabled      feature enabled
     * @param targetTheme  user's chosen sport theme (1..4)
     * @param sportActive  true when stock Sport mode or My Drive with any item in Sport (from car feedback)
     * @param currentTheme cluster theme reported by the car (1..4), or -1 if unknown
     * @return theme value (1..4) to send now, or {@link #NO_COMMAND}
     */
    public int update(long nowMs, boolean enabled, int targetTheme, boolean sportActive, int currentTheme) {
        if (core.isHalted()) return NO_COMMAND;
        if (!isValidTheme(targetTheme)) return NO_COMMAND;
        long cur = isValidTheme(currentTheme) ? currentTheme : SportOverrideController.NO_VALUE;
        long cmd = core.update(nowMs, enabled, targetTheme, sportActive, cur);
        return cmd == SportOverrideController.NO_VALUE ? NO_COMMAND : (int) cmd;
    }
}
