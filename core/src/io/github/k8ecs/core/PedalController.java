package io.github.k8ecs.core;

/**
 * Powertrain step from the accelerator pedal: at or above {@code onPct} -> sport (1); after the pedal has stayed
 * below it for {@code holdMs} -> eco (0). Same safety pattern as {@link DampingController}: commands only while
 * active and moving, at most {@code maxPerMinute} per minute, 1 s apart, and a halt that only a restart clears.
 * A stale pedal reading counts as released. Not thread-safe.
 */
public final class PedalController {
    public static final int NO_COMMAND = -1;
    static final long MIN_GAP_MS = 1000;
    static final long STALE_MS = 1000;

    private final int onPct;
    private final long holdMs;
    private final long[] recentCommands;
    private int recentIndex;
    private long lastAboveMs = Long.MIN_VALUE;
    private int commandedLevel = NO_COMMAND;
    private final boolean capped;
    private long lastCommandMs = Long.MIN_VALUE;
    private boolean halted;

    public PedalController(int onPct, long holdMs, int maxPerMinute) {
        if (onPct < 1 || onPct > 100 || holdMs < 0 || maxPerMinute < 0) throw new IllegalArgumentException("pedal rule");
        this.capped = maxPerMinute > 0;  // 0 = no per-minute cap (the 1 s gap still applies)
        this.onPct = onPct;
        this.holdMs = holdMs;
        this.recentCommands = new long[Math.max(1, maxPerMinute)];
        java.util.Arrays.fill(recentCommands, Long.MIN_VALUE);
    }

    public void onCarLevel(int level) { commandedLevel = level; }
    public void halt() { halted = true; }
    public boolean isHalted() { return halted; }

    /** True while the pedal rule wants sport (pressed now or within the hold time). */
    public boolean isSportWanted(long nowMs) {
        return lastAboveMs != Long.MIN_VALUE && nowMs - lastAboveMs < holdMs;
    }

    /**
     * @param pedalPct   latest pedal reading (percent as the car reports it)
     * @param pedalAtMs  when it was read
     */
    public int update(long nowMs, double pedalPct, long pedalAtMs, boolean active, boolean moving) {
        return update(nowMs, pedalPct, pedalAtMs, active, moving, false, true);
    }

    /**
     * @param linkedSport another item asks for sport (link option)
     * @param useOwn      false when the pedal rule is off and only links drive it
     */
    public int update(long nowMs, double pedalPct, long pedalAtMs, boolean active, boolean moving,
                      boolean linkedSport, boolean useOwn) {
        boolean fresh = pedalAtMs != Long.MIN_VALUE && nowMs - pedalAtMs <= STALE_MS;
        if (fresh && pedalPct >= onPct) lastAboveMs = nowMs;
        if (halted || !active) return NO_COMMAND;

        int target = (useOwn && isSportWanted(nowMs)) || linkedSport ? 1 : 0;
        if (!moving) {
            // Stopped or reversing: never raise, but bring sport back to eco.
            if (commandedLevel <= 0) return NO_COMMAND;
            target = 0;
        }
        if (target == commandedLevel) return NO_COMMAND;
        if (lastCommandMs != Long.MIN_VALUE && nowMs - lastCommandMs < MIN_GAP_MS) return NO_COMMAND;
        long oldest = recentCommands[recentIndex];
        if (capped && oldest != Long.MIN_VALUE && nowMs - oldest < 60000L) return NO_COMMAND;

        recentCommands[recentIndex] = nowMs;
        recentIndex = (recentIndex + 1) % recentCommands.length;
        lastCommandMs = nowMs;
        commandedLevel = target;
        return target;
    }
}
