package io.github.k8ecs.core;

/**
 * Decides when to ask the car for a different suspension step. Pure logic: the caller feeds
 * vehicle state and sends whatever {@link #update} returns. Not thread-safe.
 */
public final class DampingController {
    /** Returned by {@link #update} when nothing should be sent. */
    public static final int NO_COMMAND = -1;

    private final DampingRules rules;
    private int speedLevel;
    private boolean cornerActive;
    private long cornerAboveSinceMs = -1;
    private long cornerBelowSinceMs = -1;
    private int lateralMilliG;
    private long lateralAtMs = Long.MIN_VALUE;
    private int commandedLevel = NO_COMMAND;
    private long lastCommandMs = Long.MIN_VALUE;
    private final long[] recentCommands;
    private int recentIndex;
    private boolean halted;
    private int ownLevel;

    public DampingController(DampingRules rules) {
        this.rules = rules;
        this.recentCommands = new long[rules.maxSwitchesPerMinute];
        java.util.Arrays.fill(recentCommands, Long.MIN_VALUE);
    }

    /** Lateral acceleration sample from the phone (sign ignored). */
    public void onLateral(long nowMs, int milliG) {
        lateralMilliG = Math.abs(milliG);
        lateralAtMs = nowMs;
    }

    /** The car reported a step (after our command, or because the driver changed it). */
    public void onCarLevel(int level) {
        commandedLevel = level;
    }

    /** Stop sending for good (unexpected feedback or fault). Only a restart clears it. */
    public void halt() {
        halted = true;
    }

    public boolean isHalted() {
        return halted;
    }

    public boolean isCornerActive() {
        return cornerActive;
    }

    /**
     * @param customModeActive the driver selected the custom drive mode (item settings only apply there)
     * @param moving           false while parked or in reverse: no commands then
     * @return the step to request now, or {@link #NO_COMMAND}
     */
    public int update(long nowMs, int kmh, boolean customModeActive, boolean moving) {
        return update(nowMs, kmh, customModeActive, moving, false, true);
    }

    /** Level this item's own rules (speed, corner) want, from the last update. */
    public int ownLevel() {
        return ownLevel;
    }

    /**
     * @param linkedHigh another item asks for the top step (link option)
     * @param useOwn     false when this item's own rules are off and only links drive it
     */
    public int update(long nowMs, int kmh, boolean customModeActive, boolean moving, boolean linkedHigh, boolean useOwn) {
        trackSpeed(kmh);
        trackCorner(nowMs);
        int own = speedLevel;
        if (cornerActive && rules.cornerLevel > own) own = rules.cornerLevel;
        ownLevel = useOwn ? own : 0;
        if (halted || !customModeActive || !moving) return NO_COMMAND;

        int target = ownLevel;
        if (linkedHigh) target = rules.levelCount - 1;
        if (target == commandedLevel) return NO_COMMAND;
        if (lastCommandMs != Long.MIN_VALUE && nowMs - lastCommandMs < rules.minDwellMs) return NO_COMMAND;
        long oldest = recentCommands[recentIndex];
        if (oldest != Long.MIN_VALUE && nowMs - oldest < 60000L) return NO_COMMAND;

        recentCommands[recentIndex] = nowMs;
        recentIndex = (recentIndex + 1) % recentCommands.length;
        lastCommandMs = nowMs;
        commandedLevel = target;
        return target;
    }

    private void trackSpeed(int kmh) {
        int[] up = rules.speedUpKmh;
        while (speedLevel < up.length && kmh >= up[speedLevel]) speedLevel++;
        while (speedLevel > 0 && kmh < up[speedLevel - 1] - rules.speedHysteresisKmh) speedLevel--;
    }

    private void trackCorner(long nowMs) {
        if (rules.cornerOnMilliG == 0) {
            cornerActive = false;
            return;
        }
        boolean fresh = lateralAtMs != Long.MIN_VALUE && nowMs - lateralAtMs <= rules.lateralStaleMs;
        // Without fresh samples treat the car as going straight: the corner rule fades out
        // through the normal release delay instead of latching.
        int g = fresh ? lateralMilliG : 0;
        if (g >= rules.cornerOnMilliG) {
            cornerBelowSinceMs = -1;
            if (cornerAboveSinceMs < 0) cornerAboveSinceMs = nowMs;
            if (nowMs - cornerAboveSinceMs >= rules.cornerOnDelayMs) cornerActive = true;
        } else {
            cornerAboveSinceMs = -1;
            if (g < rules.cornerOffMilliG) {
                if (cornerBelowSinceMs < 0) cornerBelowSinceMs = nowMs;
                if (nowMs - cornerBelowSinceMs >= rules.cornerReleaseMs) cornerActive = false;
            } else {
                cornerBelowSinceMs = -1;
            }
        }
    }
}
