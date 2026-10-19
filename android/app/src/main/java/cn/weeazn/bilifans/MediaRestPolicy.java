package cn.weeazn.bilifans;

final class MediaRestPolicy {
    static final long IMAGE_LIMIT = 30L * 1024 * 1024;
    static final long VIDEO_LIMIT = 200L * 1024 * 1024;
    static int interval(int seconds) { return Math.max(360, Math.min(7200, seconds)); }
    static int imageSeconds(int seconds) { return Math.max(5, Math.min(300, seconds)); }
    static void validate(boolean video, long bytes, long duration) {
        if (bytes <= 0 || bytes > (video ? VIDEO_LIMIT : IMAGE_LIMIT))
            throw new IllegalArgumentException(video ? "视频不能超过 200 MB" : "图片不能超过 30 MB");
        if (video && (duration <= 0 || duration > 60000))
            throw new IllegalArgumentException("视频时长须大于 0 且不超过 1 分钟");
    }
    static float[] cover(int w, int h, int viewW, int viewH) {
        if (w <= 0 || h <= 0 || viewW <= 0 || viewH <= 0) return new float[]{1,1};
        float scale = Math.max((float)viewW/w, (float)viewH/h);
        return new float[]{w*scale/viewW,h*scale/viewH};
    }
    static final class Clock {
        private long elapsed, since = -1;
        void running(boolean value, long now) {
            if (value && since < 0) since = now;
            else if (!value && since >= 0) { elapsed += Math.max(0,now-since); since = -1; }
        }
        long remaining(int seconds, long now) { return Math.max(0, seconds*1000L-elapsed-(since<0?0:Math.max(0,now-since))); }
        void reset(long now) { elapsed = 0; if (since >= 0) since = now; }
    }
}
