package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.EarthPlatformMod;
import java.util.List;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

/**
 * Diagnostic-only GPU timestamp pairs. No glFinish, TIME_ELAPSED nesting or synchronous reads.
 * All calls run on the render thread with its context current; disabled by default.
 */
final class FarGpuTimers {
    private static final boolean ENABLED = Boolean.getBoolean("earthplatform.far.gpuTimers");
    private static GpuQueryRing ring;
    private static boolean checked;
    private static long frame;
    private FarGpuTimers() {}

    static void frame() {
        if (!ENABLED) return;
        frame++;
        if (!checked) {
            checked = true;
            var caps = GL.getCapabilities();
            if (!(caps.OpenGL33 || caps.GL_ARB_timer_query) || caps.glQueryCounter == 0
                    || caps.glGetQueryObjectui64v == 0) {
                EarthPlatformMod.LOGGER.info("Far GPU timestamps unavailable on this context");
                return;
            }
            int bits = GL15C.glGetQueryi(GL33C.GL_TIMESTAMP, GL15C.GL_QUERY_COUNTER_BITS);
            if (bits <= 0 || bits > 64) {
                EarthPlatformMod.LOGGER.info("Far GPU timestamps unavailable: {} counter bits", bits);
                return;
            }
            ring = new GpuQueryRing(new GpuQueryRing.Queries() {
                public int create() { return GL15C.glGenQueries(); }
                public void stamp(int q) { GL33C.glQueryCounter(q, GL33C.GL_TIMESTAMP); }
                public boolean available(int q) { return GL15C.glGetQueryObjecti(q, GL15C.GL_QUERY_RESULT_AVAILABLE) != 0; }
                public long result(int q) { return GL33C.glGetQueryObjectui64(q, GL15C.GL_QUERY_RESULT); }
                public void delete(int q) { GL15C.glDeleteQueries(q); }
            }, System::nanoTime, 128, 120, bits);
            EarthPlatformMod.LOGGER.info("Far GPU timestamps enabled: {} bits, 128 pairs, 120-frame maximum age", bits);
        }
        if (ring != null) ring.poll(frame);
    }

    static long begin(String pass) { return ring == null ? 0 : ring.begin(pass, frame); }
    static void end(long token) { end(token, -1, -1); }
    static void end(long token, int ranges, int faces) { if (ring != null) ring.end(token, ranges, faces); }

    /** Bounded diagnostic readout, consumed by the local driver; no file IO in rendering. */
    static List<GpuQueryRing.Sample> drain() { return ring == null ? List.of() : ring.drain(); }
    static long dropped() { return ring == null ? 0 : ring.dropped(); }

    /** Render-thread context teardown only. Does not wait for pending queries. */
    static void close() {
        if (ring != null) { ring.close(); ring = null; }
        checked = false;
    }
}
