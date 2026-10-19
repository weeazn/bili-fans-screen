package cn.weeazn.bilifans;

/** Device-independent limits shared by native brightness and pixel shifting. */
final class OledPolicy {
    static final int SHIFT_DP = 6;
    static final int[][] POSITIONS = {{-6,-4},{0,6},{6,-4},{-4,3},{4,3},{0,-6},{-6,0},{6,0}};
    static boolean active(boolean enabled, boolean visible, boolean focused, boolean paused) {
        return enabled && visible && focused && !paused;
    }
    static float dim(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? .15f : (float)Math.max(0, Math.min(.6, value));
    }
    static int interval(int seconds) { return Math.max(10, Math.min(3600, seconds)); }
    static float brightness(int base, float dim, boolean active, boolean visible) {
        if (!visible) return -1f; // Restore system policy when this Activity is paused.
        float value = Math.max(1, Math.min(100, base)) / 100f;
        return Math.max(.01f, value * (active ? 1f - dim : 1f));
    }
}
