package io.earthplatform.minecraft.cubic.client;

import java.lang.ref.WeakReference;

/** Rotation is useful only after a continuously settled view; this never skips an upload. */
final class LightmapActivity {
    static final long QUIET_NANOS = 500_000_000L;
    private WeakReference<Object> world;
    private double x, y, z;
    private float yaw, pitch;
    private int width, height;
    private long changedAt, previousTime;
    private boolean sampled;

    boolean rotate(Object currentWorld, double px, double py, double pz, float currentYaw, float currentPitch,
                   int currentWidth, int currentHeight, boolean settled, long now) {
        if (currentWorld == null || !Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz)
                || !Float.isFinite(currentYaw) || !Float.isFinite(currentPitch)
                || currentWidth <= 0 || currentHeight <= 0) {
            reset();
            return false;
        }
        boolean worldChanged = world == null || currentWorld != world.get();
        boolean changed = !sampled || worldChanged || px != x || py != y || pz != z
                || currentYaw != yaw || currentPitch != pitch || currentWidth != width || currentHeight != height
                || now < previousTime;
        if (worldChanged) world = new WeakReference<>(currentWorld);
        x = px; y = py; z = pz; yaw = currentYaw; pitch = currentPitch;
        width = currentWidth; height = currentHeight; previousTime = now; sampled = true;
        if (changed || !settled) changedAt = now;
        return settled && !changed && now - changedAt >= QUIET_NANOS;
    }

    void reset() { world = null; sampled = false; }
}
