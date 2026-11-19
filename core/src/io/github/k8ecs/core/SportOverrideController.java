package io.github.k8ecs.core;

/**
 * Reusable sport-state override logic for a non-negative encoded value (cluster theme, ambient RGB, etc.).
 * Switches to a target value while sport is active, restores the original immediately when sport ends
 * (or after a configured release delay) or when disabled, respects manual driver changes, rate-limits
 * sends, and halts after consecutive missed feedbacks.
 * Pure logic (no Android). Not thread-safe.
 */
public final class SportOverrideController {
    /** Sentinel meaning "no command", "none saved", or "unknown current value". */
    public static final long NO_VALUE = -1L;

    public static final long DEFAULT_RELEASE_MS = 0L;
    public static final long PENDING_TIMEOUT_MS = 3000L;
    public static final long MIN_GAP_MS = 3000L;
    public static final int MAX_MISSES = 2;

    public static final String REASON_SPORT_START = "sport start";
    public static final String REASON_SPORT_END = "sport end";
    public static final String REASON_OPTION_OFF = "option off";
    public static final String REASON_RESTORE_RESTART = "restore after restart";

    private long releaseMs;
    private long savedOriginal = NO_VALUE;
    private long appliedTarget = NO_VALUE;
    private boolean fromRestart;
    private boolean inSport;
    private long sportOffSinceMs = Long.MIN_VALUE;

    private long pendingValue = NO_VALUE;
    private long pendingSinceMs = Long.MIN_VALUE;
    private boolean awaitingSportEntry;
    private long lastMissedValue = NO_VALUE;
    private int misses;
    private boolean halted;

    private long lastSendMs = Long.MIN_VALUE;
    private String lastReason = "";

    public SportOverrideController(long releaseMs, long persistedOriginal) {
        setReleaseMs(releaseMs);
        if (persistedOriginal >= 0) {
            this.savedOriginal = persistedOriginal;
            this.fromRestart = true;
        }
    }

    public void setReleaseMs(long releaseMs) {
        if (releaseMs < 0) throw new IllegalArgumentException("releaseMs");
        this.releaseMs = releaseMs;
    }

    public long savedOriginal() { return savedOriginal; }
    public long appliedTarget() { return appliedTarget; }
    public boolean isHalted() { return halted; }
    public int misses() { return misses; }
    public long lastMissedValue() { return lastMissedValue; }
    public boolean hasPending() { return pendingValue != NO_VALUE; }
    public String lastReason() { return lastReason; }

    /** Clears a halt (for user resume / settings reload) while keeping any saved original value. */
    public void clearHalt() {
        halted = false;
        misses = 0;
        pendingValue = NO_VALUE;
    }

    /** Called when ignition goes off so an in-flight command does not falsely time out when IGN returns. */
    public void onIgnitionOff() {
        pendingValue = NO_VALUE;
        sportOffSinceMs = Long.MIN_VALUE;
    }

    /**
     * Evaluates one tick with the ignition on.
     *
     * @param nowMs        monotonic/wall timestamp in ms
     * @param enabled      feature enabled
     * @param targetValue  non-negative sport target value (negative = invalid, returns {@link #NO_VALUE})
     * @param sportActive  true when stock Sport mode or My Drive with any item in Sport (from car feedback)
     * @param currentValue non-negative value reported by the car, or {@link #NO_VALUE} if unknown
     * @return value to send now, or {@link #NO_VALUE}
     */
    public long update(long nowMs, boolean enabled, long targetValue, boolean sportActive, long currentValue) {
        if (targetValue < 0) return NO_VALUE;

        boolean known = currentValue >= 0;

        // A timeout means the sport write is unconfirmed. It can still take effect after sport ends.
        if (awaitingSportEntry) {
            if (known && currentValue == appliedTarget) {
                awaitingSportEntry = false;
                pendingValue = NO_VALUE;
                misses = 0;
                halted = false;
            } else if (known && currentValue != savedOriginal) {
                awaitingSportEntry = false;
                pendingValue = NO_VALUE;
                savedOriginal = NO_VALUE;
                appliedTarget = NO_VALUE;
                inSport = enabled && sportActive;
                sportOffSinceMs = Long.MIN_VALUE;
                misses = 0;
                return NO_VALUE;
            } else {
                trackSportExit(nowMs, sportActive);
                if (hasPending() && nowMs - pendingSinceMs >= PENDING_TIMEOUT_MS) {
                    recordTimeout();
                }
                if (!halted && !hasPending() && enabled && sportActive && known && canSend(nowMs)) {
                    return send(nowMs, appliedTarget, REASON_SPORT_START);
                }
                return NO_VALUE;
            }
        }
        if (halted) return NO_VALUE;
        long expectedApplied = appliedTarget != NO_VALUE ? appliedTarget : targetValue;

        // 1. Pending feedback check.
        if (pendingValue != NO_VALUE) {
            if (known && currentValue == pendingValue) {
                if (pendingValue == savedOriginal) {
                    savedOriginal = NO_VALUE;
                    appliedTarget = NO_VALUE;
                    fromRestart = false;
                    inSport = false;
                    sportOffSinceMs = Long.MIN_VALUE;
                }
                pendingValue = NO_VALUE;
                misses = 0;
            } else if (pendingValue == savedOriginal && known && currentValue != expectedApplied) {
                // A new manual value supersedes the restore, including while a retry is pending.
                pendingValue = NO_VALUE;
                savedOriginal = NO_VALUE;
                appliedTarget = NO_VALUE;
                fromRestart = false;
                inSport = sportActive;
                misses = 0;
            } else if (nowMs - pendingSinceMs >= PENDING_TIMEOUT_MS) {
                recordTimeout();
                if (halted) return NO_VALUE;
            } else {
                // Keep tracking sport-off elapsed time while waiting for pending feedback.
                if (inSport) trackSportExit(nowMs, sportActive);
                return NO_VALUE;
            }
        }

        if (!known) return NO_VALUE;

        expectedApplied = appliedTarget != NO_VALUE ? appliedTarget : targetValue;

        // 2. Option disabled: restore immediately (without release delay) if our sport value is still applied.
        if (!enabled) {
            if (savedOriginal != NO_VALUE) {
                if (currentValue == expectedApplied && savedOriginal != currentValue) {
                    if (!canSend(nowMs)) return NO_VALUE;
                    return send(nowMs, savedOriginal, REASON_OPTION_OFF);
                }
                // Driver already changed the value by hand: do not send, just clear.
                savedOriginal = NO_VALUE;
                appliedTarget = NO_VALUE;
                fromRestart = false;
            }
            inSport = false;
            sportOffSinceMs = Long.MIN_VALUE;
            return NO_VALUE;
        }

        // 3. Recovery after a module/head-unit restart with a persisted original value.
        if (fromRestart) {
            if (!sportActive) {
                if (savedOriginal != NO_VALUE && currentValue == targetValue && savedOriginal != currentValue) {
                    if (!canSend(nowMs)) return NO_VALUE;
                    return send(nowMs, savedOriginal, REASON_RESTORE_RESTART);
                }
                savedOriginal = NO_VALUE;
                appliedTarget = NO_VALUE;
                fromRestart = false;
                return NO_VALUE;
            }
            fromRestart = false;
            if (savedOriginal != NO_VALUE && currentValue == targetValue && savedOriginal != targetValue) {
                // Restarted while still in sport value: resume tracking with savedOriginal intact.
                inSport = true;
                appliedTarget = targetValue;
                sportOffSinceMs = Long.MIN_VALUE;
                return NO_VALUE;
            }
            savedOriginal = NO_VALUE;
            appliedTarget = NO_VALUE;
        }

        // 4. Sport active.
        if (sportActive) {
            sportOffSinceMs = Long.MIN_VALUE;
            if (!inSport) {
                if (currentValue == targetValue) {
                    // Already at the sport target: nothing to send or restore.
                    inSport = true;
                    appliedTarget = targetValue;
                    savedOriginal = NO_VALUE;
                    return NO_VALUE;
                }
                if (!canSend(nowMs)) return NO_VALUE;
                savedOriginal = currentValue;
                appliedTarget = targetValue;
                inSport = true;
                awaitingSportEntry = true;
                return send(nowMs, targetValue, REASON_SPORT_START);
            }
            if (currentValue != expectedApplied) {
                // Driver picked another value by hand during sport: cancel and do not fight back.
                savedOriginal = NO_VALUE;
                appliedTarget = NO_VALUE;
                return NO_VALUE;
            }
            if (targetValue != expectedApplied && savedOriginal != NO_VALUE) {
                // Target setting changed while our sport value is applied.
                if (!canSend(nowMs)) return NO_VALUE;
                if (targetValue != savedOriginal) appliedTarget = targetValue;
                return send(nowMs, targetValue, REASON_SPORT_START);
            }
            return NO_VALUE;
        }

        // 5. Sport inactive.
        if (!inSport) return NO_VALUE;

        if (savedOriginal != NO_VALUE && currentValue != expectedApplied) {
            savedOriginal = NO_VALUE;
            appliedTarget = NO_VALUE;
        }

        if (sportOffSinceMs == Long.MIN_VALUE) {
            sportOffSinceMs = nowMs;
        }
        if (nowMs - sportOffSinceMs < releaseMs) {
            return NO_VALUE;
        }

        if (savedOriginal != NO_VALUE) {
            if (currentValue == expectedApplied && savedOriginal != currentValue) {
                if (!canSend(nowMs)) return NO_VALUE;
                return send(nowMs, savedOriginal, REASON_SPORT_END);
            }
            savedOriginal = NO_VALUE;
            appliedTarget = NO_VALUE;
        }
        inSport = false;
        sportOffSinceMs = Long.MIN_VALUE;
        return NO_VALUE;
    }

    private boolean canSend(long nowMs) {
        return lastSendMs == Long.MIN_VALUE || nowMs - lastSendMs >= MIN_GAP_MS;
    }

    private void trackSportExit(long nowMs, boolean sportActive) {
        if (sportActive) sportOffSinceMs = Long.MIN_VALUE;
        else if (sportOffSinceMs == Long.MIN_VALUE) sportOffSinceMs = nowMs;
    }

    private void recordTimeout() {
        lastMissedValue = pendingValue;
        pendingValue = NO_VALUE;
        misses++;
        if (misses >= MAX_MISSES) halted = true;
    }

    private long send(long nowMs, long value, String reason) {
        pendingValue = value;
        pendingSinceMs = nowMs;
        lastSendMs = nowMs;
        lastReason = reason;
        return value;
    }
}
