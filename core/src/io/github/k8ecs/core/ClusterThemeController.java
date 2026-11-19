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

    public static final long DEFAULT_RELEASE_MS = 5000L;
    public static final long PENDING_TIMEOUT_MS = 3000L;
    public static final long MIN_GAP_MS = 3000L;
    public static final int MAX_MISSES = 2;

    public static final String REASON_SPORT_START = "sport start";
    public static final String REASON_SPORT_END = "sport end";
    public static final String REASON_OPTION_OFF = "option off";
    public static final String REASON_RESTORE_RESTART = "restore after restart";

    private final long releaseMs;
    private int savedOriginal = -1;
    private int appliedTarget = -1;
    private boolean fromRestart;
    private boolean inSport;
    private long sportOffSinceMs = Long.MIN_VALUE;

    private int pendingTheme = -1;
    private long pendingSinceMs = Long.MIN_VALUE;
    private boolean pendingWasSportStart;
    private int lastMissedTheme = -1;
    private int misses;
    private boolean halted;

    private long lastSendMs = Long.MIN_VALUE;
    private String lastReason = "";

    public ClusterThemeController() {
        this(DEFAULT_RELEASE_MS, -1);
    }

    public ClusterThemeController(long releaseMs) {
        this(releaseMs, -1);
    }

    /**
     * @param releaseMs         continuous non-sport time before restoring the original theme
     * @param persistedOriginal original theme saved before a restart (1..4), or -1 if none
     */
    public ClusterThemeController(long releaseMs, int persistedOriginal) {
        if (releaseMs < 0) throw new IllegalArgumentException("releaseMs");
        this.releaseMs = releaseMs;
        if (isValidTheme(persistedOriginal)) {
            this.savedOriginal = persistedOriginal;
            this.fromRestart = true;
        }
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

    public int savedOriginal() { return savedOriginal; }
    public int appliedTarget() { return appliedTarget; }
    public boolean isHalted() { return halted; }
    public int misses() { return misses; }
    public int lastMissedTheme() { return lastMissedTheme; }
    public boolean hasPending() { return pendingTheme != -1; }
    public String lastReason() { return lastReason; }

    /** Clears a halt (for user resume / settings reload) while keeping any saved original theme. */
    public void clearHalt() {
        halted = false;
        misses = 0;
        pendingTheme = -1;
        pendingWasSportStart = false;
    }

    /** Called when ignition goes off so an in-flight command does not falsely time out when IGN returns. */
    public void onIgnitionOff() {
        pendingTheme = -1;
        pendingWasSportStart = false;
        sportOffSinceMs = Long.MIN_VALUE;
    }

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
        if (halted) return NO_COMMAND;
        if (!isValidTheme(targetTheme)) return NO_COMMAND;

        boolean known = isValidTheme(currentTheme);

        // 1. Pending feedback check.
        if (pendingTheme != -1) {
            if (known && currentTheme == pendingTheme) {
                pendingTheme = -1;
                pendingWasSportStart = false;
                misses = 0;
            } else if (nowMs - pendingSinceMs >= PENDING_TIMEOUT_MS) {
                lastMissedTheme = pendingTheme;
                pendingTheme = -1;
                misses++;
                if (pendingWasSportStart) {
                    savedOriginal = -1;
                    appliedTarget = -1;
                    inSport = false;
                    sportOffSinceMs = Long.MIN_VALUE;
                    pendingWasSportStart = false;
                }
                if (misses >= MAX_MISSES) {
                    halted = true;
                    return NO_COMMAND;
                }
            } else {
                // Keep tracking sport-off elapsed time while waiting for pending feedback.
                if (inSport) {
                    if (sportActive) {
                        sportOffSinceMs = Long.MIN_VALUE;
                    } else if (sportOffSinceMs == Long.MIN_VALUE) {
                        sportOffSinceMs = nowMs;
                    }
                }
                return NO_COMMAND;
            }
        }

        if (!known) return NO_COMMAND;

        int expectedApplied = appliedTarget != -1 ? appliedTarget : targetTheme;

        // 2. Option disabled: restore immediately (without release delay) if our sport theme is still applied.
        if (!enabled) {
            if (savedOriginal != -1) {
                if (currentTheme == expectedApplied && savedOriginal != currentTheme) {
                    if (!canSend(nowMs)) return NO_COMMAND;
                    int restore = savedOriginal;
                    savedOriginal = -1;
                    appliedTarget = -1;
                    fromRestart = false;
                    inSport = false;
                    sportOffSinceMs = Long.MIN_VALUE;
                    return send(nowMs, restore, REASON_OPTION_OFF, false);
                }
                // Driver already changed the theme by hand: do not send, just clear.
                savedOriginal = -1;
                appliedTarget = -1;
                fromRestart = false;
            }
            inSport = false;
            sportOffSinceMs = Long.MIN_VALUE;
            return NO_COMMAND;
        }

        // 3. Recovery after a module/head-unit restart with a persisted original theme.
        if (fromRestart) {
            if (!sportActive) {
                if (savedOriginal != -1 && currentTheme == targetTheme && savedOriginal != currentTheme) {
                    if (!canSend(nowMs)) return NO_COMMAND;
                    int restore = savedOriginal;
                    savedOriginal = -1;
                    appliedTarget = -1;
                    fromRestart = false;
                    return send(nowMs, restore, REASON_RESTORE_RESTART, false);
                }
                savedOriginal = -1;
                appliedTarget = -1;
                fromRestart = false;
                return NO_COMMAND;
            }
            fromRestart = false;
            if (savedOriginal != -1 && currentTheme == targetTheme && savedOriginal != targetTheme) {
                // Restarted while still in sport theme: resume tracking with savedOriginal intact.
                inSport = true;
                appliedTarget = targetTheme;
                sportOffSinceMs = Long.MIN_VALUE;
                return NO_COMMAND;
            }
            savedOriginal = -1;
            appliedTarget = -1;
        }

        // 4. Sport active.
        if (sportActive) {
            sportOffSinceMs = Long.MIN_VALUE;
            if (!inSport) {
                if (currentTheme == targetTheme) {
                    // Already at the chosen theme: nothing to send or restore.
                    inSport = true;
                    appliedTarget = targetTheme;
                    savedOriginal = -1;
                    return NO_COMMAND;
                }
                if (!canSend(nowMs)) return NO_COMMAND;
                savedOriginal = currentTheme;
                appliedTarget = targetTheme;
                inSport = true;
                return send(nowMs, targetTheme, REASON_SPORT_START, true);
            }
            if (currentTheme != expectedApplied) {
                // Driver picked another theme by hand during sport: cancel and do not fight back.
                savedOriginal = -1;
                appliedTarget = -1;
                return NO_COMMAND;
            }
            if (targetTheme != expectedApplied && savedOriginal != -1) {
                // Target theme setting changed while our sport theme is applied.
                if (!canSend(nowMs)) return NO_COMMAND;
                if (targetTheme == savedOriginal) {
                    int restore = savedOriginal;
                    savedOriginal = -1;
                    appliedTarget = targetTheme;
                    return send(nowMs, restore, REASON_SPORT_START, false);
                }
                appliedTarget = targetTheme;
                return send(nowMs, targetTheme, REASON_SPORT_START, false);
            }
            return NO_COMMAND;
        }

        // 5. Sport inactive.
        if (!inSport) return NO_COMMAND;

        if (savedOriginal != -1 && currentTheme != expectedApplied) {
            savedOriginal = -1;
            appliedTarget = -1;
        }

        if (sportOffSinceMs == Long.MIN_VALUE) {
            sportOffSinceMs = nowMs;
        }
        if (nowMs - sportOffSinceMs < releaseMs) {
            return NO_COMMAND;
        }

        if (savedOriginal != -1) {
            if (currentTheme == expectedApplied && savedOriginal != currentTheme) {
                if (!canSend(nowMs)) return NO_COMMAND;
                int restore = savedOriginal;
                savedOriginal = -1;
                appliedTarget = -1;
                inSport = false;
                sportOffSinceMs = Long.MIN_VALUE;
                return send(nowMs, restore, REASON_SPORT_END, false);
            }
            savedOriginal = -1;
            appliedTarget = -1;
        }
        inSport = false;
        sportOffSinceMs = Long.MIN_VALUE;
        return NO_COMMAND;
    }

    private boolean canSend(long nowMs) {
        return lastSendMs == Long.MIN_VALUE || nowMs - lastSendMs >= MIN_GAP_MS;
    }

    private int send(long nowMs, int theme, String reason, boolean sportStart) {
        pendingTheme = theme;
        pendingSinceMs = nowMs;
        pendingWasSportStart = sportStart;
        lastSendMs = nowMs;
        lastReason = reason;
        return theme;
    }
}
