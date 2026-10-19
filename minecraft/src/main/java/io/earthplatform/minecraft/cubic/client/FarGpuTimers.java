package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.EarthPlatformMod;
import java.util.List;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

/**
 * Diagnostic-only GPU queries. Timestamp pairs preferred; non-nested TIME_ELAPSED fallback.
 * No glFinish or synchronous result reads; an occupied elapsed target is skipped.
 * All calls run on the render thread with its context current; sampled once per 120 frames by default.
 * Off unless {@code -Dearthplatform.far.gpuTimers=true}: queries wrap other mods' and shader packs' draws.
 */
final class FarGpuTimers {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("earthplatform.far.gpuTimers", "false"));
    private static final int SAMPLE_EVERY = Math.clamp(Integer.getInteger("earthplatform.far.gpuSampleEvery", 120),1,3600);
    private static GpuQueryRing ring;
    private static boolean checked;
    private static String mode="inactive";
    static String mode(){return mode;}
    static int sampleEvery(){return SAMPLE_EVERY;}
    private static long frame;
    private FarGpuTimers() {}

    static void frame() {
        if (!ENABLED) return;
        frame++;
        if (!checked) {
            checked = true;
            mode="unavailable";
            var caps = GL.getCapabilities();
            if (!(caps.OpenGL33 || caps.GL_ARB_timer_query) || caps.glQueryCounter == 0
                    || caps.glGetQueryObjectui64v == 0) {
                EarthPlatformMod.LOGGER.info("Far GPU timestamps unavailable on this context");
                return;
            }
            int timestampBits = GL15C.glGetQueryi(GL33C.GL_TIMESTAMP, GL15C.GL_QUERY_COUNTER_BITS);
            int elapsedBits = GL15C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL15C.GL_QUERY_COUNTER_BITS);
            boolean timestamp = timestampBits > 0 && timestampBits <= 64;
            int bits = timestamp ? timestampBits : elapsedBits;
            if (bits <= 0 || bits > 64) {
                EarthPlatformMod.LOGGER.info("Far GPU queries unavailable: timestamp={} elapsed={} counter bits", timestampBits, elapsedBits);
                return;
            }
            mode=timestamp?"timestamp_pairs":"non_nested_elapsed";
            ring = new GpuQueryRing(new GpuQueryRing.Queries() {
                public int create() { return GL15C.glGenQueries(); }
                public void stamp(int q) { GL33C.glQueryCounter(q, GL33C.GL_TIMESTAMP); }
                public boolean available(int q) { return GL15C.glGetQueryObjecti(q, GL15C.GL_QUERY_RESULT_AVAILABLE) != 0; }
                public long result(int q) { return GL33C.glGetQueryObjectui64(q, GL15C.GL_QUERY_RESULT); }
                public boolean canBegin() {
                    return timestamp || GL15C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL15C.GL_CURRENT_QUERY) == 0;
                }
                public void begin(int start, int end) {
                    if (timestamp) stamp(start); else GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED, start);
                }
                public void end(int start, int end) {
                    if (timestamp) stamp(end);
                    else if (GL15C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL15C.GL_CURRENT_QUERY) == start)
                        GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);
                }
                public boolean finish(int start, int end) {
                    if (!timestamp && GL15C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL15C.GL_CURRENT_QUERY) != start) return false;
                    end(start, end); return true;
                }
                public boolean availablePair(int start, int end) {
                    return timestamp ? available(start) && available(end) : available(start);
                }
                public long elapsed(int start, int end) { return timestamp ? result(end) - result(start) : result(start); }
                public void delete(int q) {
                    // Aborted/overdue scopes end ONLY their own active query before deletion.
                    if (!timestamp && GL15C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL15C.GL_CURRENT_QUERY) == q)
                        GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);
                    GL15C.glDeleteQueries(q);
                }
            }, System::nanoTime, 128, 120, bits);
            EarthPlatformMod.LOGGER.info("Far GPU diagnostics enabled: {} {} bits, 128 pairs, 120-frame age cap; occupied elapsed-query targets are skipped",
                    timestamp ? "timestamp pairs" : "non-nested TIME_ELAPSED", bits);
        }
        if (ring != null) ring.poll(frame);
    }

    static long begin(String pass) { return !ENABLED || ring == null || frame % SAMPLE_EVERY != 0 ? 0 : ring.begin(pass, frame); }
    static void end(long token) { end(token, -1, -1); }
    static void end(long token, int ranges, int faces) { if (ring != null) ring.end(token, ranges, faces); }

    /** Bounded diagnostic readout, consumed by the local driver; no file IO in rendering. */
    static List<GpuQueryRing.Sample> drain() { return ring == null ? List.of() : ring.drain(); }
    static long dropped() { return ring == null ? 0 : ring.dropped(); }

    /** Render-thread context teardown only. Does not wait for pending queries. */
    static void close() {
        if (ring != null) { ring.close(); ring = null; }
        checked = false;
        mode="inactive";
    }
}
