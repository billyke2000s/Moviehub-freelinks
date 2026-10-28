package local.tv.home;

/** Incoming payload bytes over elapsed time; idle windows report zero, not an old peak. */
final class StreamRate {

    private long sampledAt = -1,
        sampledBytes;
    private float mbps;

    synchronized void reset(long nowMs, long totalBytes) {
        sampledAt = nowMs;
        sampledBytes = totalBytes;
        mbps = 0;
    }

    synchronized float sample(long nowMs, long totalBytes) {
        if (sampledAt < 0 || nowMs < sampledAt || totalBytes < sampledBytes) {
            sampledAt = nowMs;
            sampledBytes = totalBytes;
            mbps = 0;
        } else if (nowMs - sampledAt >= 250) {
            mbps = ((totalBytes - sampledBytes) * 8f) / ((nowMs - sampledAt) * 1000f);
            sampledAt = nowMs;
            sampledBytes = totalBytes;
        }
        return mbps;
    }
}
