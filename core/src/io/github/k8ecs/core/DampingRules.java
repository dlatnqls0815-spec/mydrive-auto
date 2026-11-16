package io.github.k8ecs.core;

/**
 * User-editable switching rules. Levels are indexes into the stock suspension options
 * (0 = softest), not damping percentages: the head unit only exposes discrete steps.
 */
public final class DampingRules {
    /** Number of stock suspension options (2 or 3). */
    public final int levelCount;
    /** Ascending speeds (km/h) at which the level rises by one; length = levelCount - 1 or shorter. */
    public final int[] speedUpKmh;
    /** The level drops back only below (speedUpKmh - speedHysteresisKmh). */
    public final int speedHysteresisKmh;
    /** Lateral acceleration (milli-g) that raises the level to cornerLevel; 0 disables the corner rule. */
    public final int cornerOnMilliG;
    /** The corner rule releases below this (milli-g). */
    public final int cornerOffMilliG;
    public final int cornerLevel;
    /** Lateral acceleration must stay above the threshold this long before the level rises. */
    public final long cornerOnDelayMs;
    /** ...and below the release threshold this long before it drops. */
    public final long cornerReleaseMs;
    /** Minimum time between two commands. */
    public final long minDwellMs;
    public final int maxSwitchesPerMinute;
    /** Lateral samples older than this are ignored (phone link lost). */
    public final long lateralStaleMs;

    public DampingRules(int levelCount, int[] speedUpKmh, int speedHysteresisKmh,
            int cornerOnMilliG, int cornerOffMilliG, int cornerLevel,
            long cornerOnDelayMs, long cornerReleaseMs,
            long minDwellMs, int maxSwitchesPerMinute, long lateralStaleMs) {
        if (levelCount < 2 || levelCount > 4) throw new IllegalArgumentException("levelCount");
        if (speedUpKmh == null || speedUpKmh.length > levelCount - 1) throw new IllegalArgumentException("speedUpKmh");
        for (int i = 0; i < speedUpKmh.length; i++) {
            if (speedUpKmh[i] <= 0 || (i > 0 && speedUpKmh[i] <= speedUpKmh[i - 1])) {
                throw new IllegalArgumentException("speedUpKmh order");
            }
        }
        if (speedHysteresisKmh < 3) throw new IllegalArgumentException("speedHysteresisKmh");
        if (cornerOnMilliG != 0 && (cornerOnMilliG < 100 || cornerOffMilliG <= 0 || cornerOffMilliG >= cornerOnMilliG)) {
            throw new IllegalArgumentException("corner thresholds");
        }
        if (cornerLevel < 0 || cornerLevel >= levelCount) throw new IllegalArgumentException("cornerLevel");
        if (minDwellMs < 1000 || maxSwitchesPerMinute < 1 || maxSwitchesPerMinute > 20) {
            throw new IllegalArgumentException("rate limits");
        }
        this.levelCount = levelCount;
        this.speedUpKmh = speedUpKmh.clone();
        this.speedHysteresisKmh = speedHysteresisKmh;
        this.cornerOnMilliG = cornerOnMilliG;
        this.cornerOffMilliG = cornerOffMilliG;
        this.cornerLevel = cornerLevel;
        this.cornerOnDelayMs = cornerOnDelayMs;
        this.cornerReleaseMs = cornerReleaseMs;
        this.minDwellMs = minDwellMs;
        this.maxSwitchesPerMinute = maxSwitchesPerMinute;
        this.lateralStaleMs = lateralStaleMs;
    }

    /** Conservative starting point for a two-step (normal / sport) suspension. */
    public static DampingRules defaults(int levelCount) {
        int top = levelCount - 1;
        return new DampingRules(levelCount, new int[] {100}, 15, 250, 120, top, 300, 2500, 3000, 8, 1000);
    }
}
