package dev.earbuds.popup;

/** Immutable battery readings. A value of -1 means the device did not report it. */
public final class BatterySnapshot {
    private static final long MAX_FRESH_AGE_MILLIS = 60_000L;

    public final int left;
    public final int right;
    public final int caseLevel;
    public final int overall;
    public final int leftCharging;
    public final int rightCharging;
    public final int caseCharging;
    public final long updatedAtMillis;

    /** Creates a snapshot; invalid levels and charge flags are normalized to unknown (-1). */
    public BatterySnapshot(int left, int right, int caseLevel, int overall,
                           int leftCharging, int rightCharging, int caseCharging,
                           long updatedAtMillis) {
        this.left = levelOrUnknown(left);
        this.right = levelOrUnknown(right);
        this.caseLevel = levelOrUnknown(caseLevel);
        this.overall = levelOrUnknown(overall);
        this.leftCharging = chargeOrUnknown(leftCharging);
        this.rightCharging = chargeOrUnknown(rightCharging);
        this.caseCharging = chargeOrUnknown(caseCharging);
        this.updatedAtMillis = updatedAtMillis;
    }

    public static BatterySnapshot unknown() {
        return new BatterySnapshot(-1, -1, -1, -1, -1, -1, -1, 0L);
    }

    /** Returns true when at least one individual earbud or case level was reported. */
    public boolean hasComponents() {
        return left >= 0 || right >= 0 || caseLevel >= 0;
    }

    /** Returns true when any battery level, including an aggregate level, was reported. */
    public boolean hasAny() {
        return hasComponents() || overall >= 0;
    }

    /** A level is fresh for at most 60 seconds; future-dated or empty snapshots are stale. */
    public boolean isFresh(long now) {
        return hasAny() && updatedAtMillis > 0L && now >= updatedAtMillis
                && now - updatedAtMillis <= MAX_FRESH_AGE_MILLIS;
    }

    private static int levelOrUnknown(int value) {
        return value >= 0 && value <= 100 ? value : -1;
    }

    private static int chargeOrUnknown(int value) {
        return value == 0 || value == 1 ? value : -1;
    }
}
