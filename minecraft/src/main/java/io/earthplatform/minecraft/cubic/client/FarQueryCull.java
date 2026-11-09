package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.GlStateManager;
import io.earthplatform.minecraft.EarthPlatformMod;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Leaves out of far terrain's one-pass draw the ranges of faces that lie wholly behind terrain already drawn, with
 * OpenGL 3.3's occlusion queries and conditional rendering, as {@link CubeOcclusion} culls near cubes: for the GLs
 * that get neither the depth pre-pass nor {@link FarHiddenCull} (which needs OpenGL 4.3), a Mac's above all. Without
 * it every range in view goes through the vertex shader and the rasterizer, and under a shader pack every one of its
 * fragments through the pack's code, however much of it lies behind a nearer ridge.
 *
 * <p>While the far view is drawn again as it was recorded, the ranges the last tests saw are drawn first, in their
 * order. Then, against the depth those left, the boxes of the others are drawn in groups under a query each, with no
 * colour or depth written, and each group's ranges are drawn on condition that some of its boxes passed the depth
 * test: the GPU decides in the same frame, so nothing shows a frame late. A group that passed has its ranges tested
 * one by one over the next frames, and those seen join the ones drawn first; a share of the ranges drawn first is
 * tested each frame too, to notice when they go out of sight. Those answers are read a frame or more later, never
 * waited for.
 *
 * <p>A box is its range's columns' box grown by the widest of those columns (a slope's depth is written up to that far
 * from its face, see FarShaders), by the share of its distance the faces reach past their columns (FarGpu#grow), and
 * by two pixels for a pack's jitter; a box that crosses the near plane always passes. Under a pack's own far depth
 * the far programs drop their fragments wherever the game drew, and so does the box, so terrain behind the game's
 * own blocks is left out too. So a range is left out only
 * where every fragment of it would have failed the depth test: the picture is the one without culling, except that
 * where two faces of different ranges lie at exactly the same depth, the one drawn first may differ for a frame.
 * {@code -Dearthplatform.far.queryCull=false} draws every range, for comparing.
 */
final class FarQueryCull {
    static final boolean ENABLED = !"false".equals(System.getProperty("earthplatform.far.queryCull"));
    /** Ranges tested as one. */
    private static final int GROUP = 32;
    /** Ranges tested on their own each frame, at most. */
    private static final int BUDGET = 128;
    /** Frames of answers waited on at once; older ones are dropped unread. */
    private static final int WAITING = 3;

    private static final String VERTEX = """
            #version 150
            uniform mat4 earth_ViewProjection;
            uniform samplerBuffer earth_Boxes;
            uniform int earth_Base;
            uniform float earth_Grow;
            uniform float earth_Moved;
            // A box's twelve triangles, by corner: bit 0 is x, bit 1 y, bit 2 z.
            const int CORNERS[36] = int[36](0, 2, 6, 0, 6, 4, 1, 5, 7, 1, 7, 3, 0, 4, 5, 0, 5, 1,
                                            2, 3, 7, 2, 7, 6, 0, 1, 3, 0, 3, 2, 4, 6, 7, 4, 7, 5);
            void main() {
                int box = (earth_Base + gl_InstanceID) * 2;
                vec4 first = texelFetch(earth_Boxes, box), second = texelFetch(earth_Boxes, box + 1);
                vec3 low = first.xyz, high = second.xyz;
                // The widest column's width (first.w), the faces' reach past their edges and the jitter, a block to spare.
                float grow = first.w + earth_Grow * (length(max(abs(low), abs(high))) + earth_Moved) + 1.0;
                low -= grow;
                high += grow;
                bool cut = false;
                for (int c = 0; c < 8; c++) {
                    vec4 clip = earth_ViewProjection * vec4((c & 1) != 0 ? high.x : low.x, (c & 2) != 0 ? high.y : low.y,
                                                            (c & 4) != 0 ? high.z : low.z, 1.0);
                    if (clip.w <= 0.0 || clip.z < -clip.w) cut = true;
                }
                int corner = CORNERS[gl_VertexID];
                if (cut) {
                    // Up to or through the near plane the box's sides are clipped away: the whole screen, nearest, stands in.
                    gl_Position = vec4((corner & 1) != 0 ? 1.0 : -1.0, (corner & 2) != 0 ? 1.0 : -1.0, (corner & 4) != 0 ? 0.0 : -0.999, 1.0);
                    return;
                }
                vec3 at = vec3((corner & 1) != 0 ? high.x : low.x, (corner & 2) != 0 ? high.y : low.y, (corner & 4) != 0 ? high.z : low.z);
                gl_Position = earth_ViewProjection * vec4(at, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 150
            uniform sampler2D earth_GameDepth;
            uniform int earth_Own;
            out vec4 earth_Colour;
            void main() {
                // Under far terrain's own depth its fragments are dropped wherever the game drew (see FarShaders): a box
                // shows nothing there either, and a dropped fragment does not count for the query.
                if (earth_Own != 0 && texelFetch(earth_GameDepth, ivec2(gl_FragCoord.xy), 0).r < 1.0) discard;
                earth_Colour = vec4(0.0);
            }
            """;

    /** What one frame asked: its queries, and for each a group (its number, from 0) or a range on its own (-1 - its number). */
    private static final class Asked {
        final IntArrayList queries = new IntArrayList(), what = new IntArrayList();
        int epoch, version;
    }

    private int program, array, buffer, texture, viewProjection, boxes, base, grow, moved, gameDepth, own;
    private boolean failed;
    private final IntArrayList spareQueries = new IntArrayList();
    private final ArrayDeque<Asked> waiting = new ArrayDeque<>();
    private final ArrayDeque<Asked> spareAsked = new ArrayDeque<>();

    /** The recording and version the state below is for, how many ranges it has, and each multi-draw's first range. */
    private FarGpu.Recording recording;
    private int version = -1, ranges;
    private int[] firstRange = new int[16];
    /** By range: seen when last tested (or never tested); to be tested on its own since its group passed. */
    private boolean[] seen = new boolean[0], probe = new boolean[0];
    /**
     * By range: so near for its size (nearer than its box's longest side) that its box would fill most of the screen.
     * Such a range is rarely hidden and its box costs the most to test, so it is always drawn and never tested.
     */
    private boolean[] close = new boolean[0];
    /** Counts up whenever {@link #seen} changes, and the count the lists below were made for. */
    private int epoch, listed = -1;
    /** By multi-draw: where its seen ranges start in the lists, how many there are, and the same for its hidden ones' groups. */
    private int[] seenFrom = new int[16], seenCount = new int[16], groupFrom = new int[16], groupCount = new int[16];
    /** The seen ranges' corner counts and base vertices, multi-draw after multi-draw, then every group's the same way. */
    private IntBuffer counts = MemoryUtil.memAllocInt(1024), bases = MemoryUtil.memAllocInt(1024);
    private PointerBuffer offsets = MemoryUtil.memCallocPointer(1024);
    /** By group: where its ranges start in the lists and in the boxes' second half, how many, and this frame's query. */
    private final IntArrayList groupStart = new IntArrayList(), groupSize = new IntArrayList(), groupQuery = new IntArrayList();
    /** The hidden ranges, in the groups' order. */
    private final IntArrayList hidden = new IntArrayList();
    private FloatBuffer boxData = MemoryUtil.memAllocFloat(1024);
    private int next;
    /** Ranges the last frame left out or drew on condition, for the performance log. */
    private int lastHidden;

    int hiddenCount() { return lastHidden; }

    static boolean supported() { return GL.getCapabilities().OpenGL33; }

    /** Whether the box program is there; false for good once it fails to build. */
    private boolean programs() {
        if (program != 0) return true;
        if (failed) return false;
        try {
            program = link();
            array = GL30C.glGenVertexArrays();
            buffer = GL15C.glGenBuffers();
            texture = GL11C.glGenTextures();
            viewProjection = GL20C.glGetUniformLocation(program, "earth_ViewProjection");
            boxes = GL20C.glGetUniformLocation(program, "earth_Boxes");
            base = GL20C.glGetUniformLocation(program, "earth_Base");
            grow = GL20C.glGetUniformLocation(program, "earth_Grow");
            moved = GL20C.glGetUniformLocation(program, "earth_Moved");
            gameDepth = GL20C.glGetUniformLocation(program, "earth_GameDepth");
            own = GL20C.glGetUniformLocation(program, "earth_Own");
            return true;
        } catch (IllegalStateException failure) {
            failed = true;
            EarthPlatformMod.LOGGER.warn("Far terrain's hidden ranges can't be culled by occlusion queries; every range in view is drawn", failure);
            return false;
        }
    }

    /**
     * Before a recording is drawn again: reads the answers that have come and sorts its ranges into those drawn first
     * and the groups drawn on condition. False when it can't be culled; the caller then draws every range.
     */
    boolean prepare(FarGpu.Recording recording, FarGpu.RangeSource source) {
        if (!programs()) return false;
        if (this.recording != recording || version != recording.version()) reset(recording, source);
        if (ranges == 0) return false;
        read();
        if (listed != epoch) list(source);
        return true;
    }

    private void reset(FarGpu.Recording recording, FarGpu.RangeSource source) {
        this.recording = recording;
        version = recording.version();
        debugResets++;
        forget();
        int draws = source.draws();
        if (firstRange.length < draws + 1) {
            firstRange = new int[draws + 1];
            seenFrom = new int[draws];
            seenCount = new int[draws];
            groupFrom = new int[draws];
            groupCount = new int[draws];
        }
        ranges = 0;
        for (int i = 0; i < draws; i++) { firstRange[i] = ranges; ranges += source.ranges(i); }
        firstRange[draws] = ranges;
        if (seen.length < ranges) { seen = new boolean[ranges]; probe = new boolean[ranges]; close = new boolean[ranges]; }
        java.util.Arrays.fill(seen, 0, ranges, true);
        java.util.Arrays.fill(probe, 0, ranges, false);
        if (boxData.capacity() < ranges * 16) {
            MemoryUtil.memFree(boxData);
            boxData = MemoryUtil.memAllocFloat(Integer.highestOneBit(Math.max(1, ranges * 16 - 1)) << 1);
        }
        if (counts.capacity() < ranges) {
            int size = Integer.highestOneBit(Math.max(1, ranges - 1)) << 1;
            MemoryUtil.memFree(counts);
            MemoryUtil.memFree(bases);
            MemoryUtil.memFree(offsets);
            counts = MemoryUtil.memAllocInt(size);
            bases = MemoryUtil.memAllocInt(size);
            offsets = MemoryUtil.memCallocPointer(size);
        }
        // Every range's box, in order, for testing it on its own; the hidden ones' follow, group by group (see #list).
        for (int i = 0; i < draws; i++) {
            for (int r = 0, count = source.ranges(i); r < count; r++) {
                int range = firstRange[i] + r, at = range * 8;
                source.box(i, r, boxData, at);
                float sx = boxData.get(at + 4) - boxData.get(at), sy = boxData.get(at + 5) - boxData.get(at + 1), sz = boxData.get(at + 6) - boxData.get(at + 2);
                // From where the boxes are measured from, the camera or within a few blocks of it, to the box.
                float dx = Math.max(0.0F, Math.max(boxData.get(at), -boxData.get(at + 4)));
                float dy = Math.max(0.0F, Math.max(boxData.get(at + 1), -boxData.get(at + 5)));
                float dz = Math.max(0.0F, Math.max(boxData.get(at + 2), -boxData.get(at + 6)));
                float side = Math.max(sx, Math.max(sy, sz)) + 2.0F * boxData.get(at + 3);
                close[range] = dx * dx + dy * dy + dz * dz < side * side;
            }
        }
        next = 0;
        epoch++;
        listed = -1;
    }

    /** Drops every answer waited on, as the ranges they were about are gone. */
    private void forget() {
        for (Asked asked : waiting) recycle(asked);
        waiting.clear();
    }

    private void recycle(Asked asked) {
        spareQueries.addAll(asked.queries);
        asked.queries.clear();
        asked.what.clear();
        spareAsked.add(asked);
    }

    /** Reads the oldest answers, where they have all come. */
    private void read() {
        while (!waiting.isEmpty()) {
            Asked asked = waiting.peekFirst();
            int size = asked.queries.size();
            // Asked in order, so the last one's answer comes last.
            if (size != 0 && GL15C.glGetQueryObjecti(asked.queries.getInt(size - 1), GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) {
                if (waiting.size() <= WAITING) return;
                waiting.removeFirst();
                recycle(asked);
                continue;
            }
            waiting.removeFirst();
            boolean changed = false;
            for (int i = 0; i < size && asked.version == version; i++) {
                boolean passed = GL15C.glGetQueryObjecti(asked.queries.getInt(i), GL15C.GL_QUERY_RESULT) != 0;
                debugRead++;
                if (passed) debugPassed++;
                int what = asked.what.getInt(i);
                if (what >= 0) {
                    // A group that passed: which of its ranges did is found out one by one. Only the groups as they still are.
                    if (!passed || asked.epoch != epoch || listed != epoch) continue;
                    for (int k = groupStart.getInt(what), end = k + groupSize.getInt(what); k < end; k++) probe[hidden.getInt(k)] = true;
                } else {
                    int range = -1 - what;
                    probe[range] = false;
                    if (seen[range] != passed) { seen[range] = passed; changed = true; }
                }
            }
            recycle(asked);
            if (changed) epoch++;
        }
    }

    /** Sorts the ranges into the lists the draws read: each multi-draw's seen ranges, then its hidden ones in groups. */
    private void list(FarGpu.RangeSource source) {
        listed = epoch;
        int draws = source.draws(), at = 0;
        for (int i = 0; i < draws; i++) {
            seenFrom[i] = at;
            for (int r = 0, count = source.ranges(i), range = firstRange[i]; r < count; r++, range++) {
                if (!seen[range]) continue;
                counts.put(at, source.count(i, r) * 6);
                bases.put(at, source.first(i, r) * 4);
                at++;
            }
            seenCount[i] = at - seenFrom[i];
        }
        hidden.clear();
        groupStart.clear();
        groupSize.clear();
        for (int i = 0; i < draws; i++) {
            groupFrom[i] = groupStart.size();
            int inGroup = GROUP;
            for (int r = 0, count = source.ranges(i), range = firstRange[i]; r < count; r++, range++) {
                if (seen[range]) continue;
                if (inGroup == GROUP) { groupStart.add(hidden.size()); groupSize.add(0); inGroup = 0; }
                int place = hidden.size();
                hidden.add(range);
                // After the seen ranges in the draw lists, and after every range's box in the boxes.
                counts.put(at + place, source.count(i, r) * 6);
                bases.put(at + place, source.first(i, r) * 4);
                for (int k = 0; k < 8; k++) boxData.put((ranges + place) * 8 + k, boxData.get(range * 8 + k));
                groupSize.set(groupSize.size() - 1, ++inGroup);
            }
            groupCount[i] = groupStart.size() - groupFrom[i];
        }
        seenTotal = at;
        // Boxes: two texels a range.
        GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER, buffer);
        boxData.clear().limit((ranges + hidden.size()) * 8);
        GL15C.glBufferData(GL31C.GL_TEXTURE_BUFFER, boxData, GL15C.GL_DYNAMIC_DRAW);
        boxData.clear();
        GL15C.glBindBuffer(GL31C.GL_TEXTURE_BUFFER, 0);
    }

    private int seenTotal;
    private static final boolean DEBUG = Boolean.getBoolean("earthplatform.far.queryCull.debug");
    private int debugFrames, debugRead, debugPassed, debugResets;

    /** How many multi-draw entries a multi-draw's seen ranges make, for {@link #drawSeen}. */
    int seenRanges(int draw) { return seenCount[draw]; }

    /** Draws a multi-draw's seen ranges, with the far program and that multi-draw's faces bound. */
    void drawSeen(int draw) {
        int count = seenCount[draw];
        if (count == 0) return;
        multiDraw(seenFrom[draw], count);
    }

    private void multiDraw(int from, int count) {
        counts.position(from).limit(from + count);
        bases.position(from).limit(from + count);
        offsets.position(0).limit(count);
        GL32C.glMultiDrawElementsBaseVertex(GL11C.GL_TRIANGLES, counts, GL11C.GL_UNSIGNED_INT, offsets, bases);
        counts.clear();
        bases.clear();
        offsets.clear();
    }

    /** Whether any range waits to be drawn on condition. */
    boolean anyHidden() { return !hidden.isEmpty(); }

    /**
     * After the seen ranges are drawn: tests every group's boxes against the depth they left, and some ranges on their
     * own. {@code matrix} takes the boxes' space (from where the view was worked out) to clip space;
     * {@code growShare} is how far faces reach past their columns and the jitter, per block of distance, and
     * {@code movedBy} how far the camera is from where the boxes are measured from. {@code ownDepth}: the far programs
     * drop their fragments wherever the game's depth, bound on FarLod's unit, has something.
     */
    void test(Matrix4f matrix, float growShare, float movedBy, boolean ownDepth) {
        lastHidden = hidden.size();
        if (DEBUG && ++debugFrames % 20 == 0) {
            int seenNow = 0, closeNow = 0;
            for (int i = 0; i < ranges; i++) { if (seen[i]) seenNow++; if (close[i]) closeNow++; }
            EarthPlatformMod.LOGGER.info("far query cull: {} ranges, {} seen, {} close, {} hidden in {} groups, {} waiting, version {} epoch {}, read {} passed {} resets {}",
                    ranges, seenNow, closeNow, hidden.size(), groupStart.size(), waiting.size(), version, epoch, debugRead, debugPassed, debugResets);
        }
        Asked asked = spareAsked.isEmpty() ? new Asked() : spareAsked.removeFirst();
        asked.epoch = epoch;
        asked.version = version;
        int savedProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM), savedArray = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int active = GlStateManager._getActiveTexture();
        boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK), cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE), blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        boolean[] colour = new boolean[4];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bytes = stack.malloc(4);
            GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, bytes);
            for (int i = 0; i < 4; i++) colour[i] = bytes.get(i) != 0;
            GlStateManager._colorMask(false, false, false, false);
            GlStateManager._depthMask(false);
            // Both sides of a box: nothing here counts on which way a triangle winds.
            GlStateManager._disableCull();
            GlStateManager._disableBlend();
            GlStateManager._glUseProgram(program);
            GL30C.glBindVertexArray(array);
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + FarGpu.QUERY_BOX_UNIT);
            int savedBuffer = GL11C.glGetInteger(GL31C.GL_TEXTURE_BINDING_BUFFER);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, texture);
            GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GL30C.GL_RGBA32F, buffer);
            GL20C.glUniform1i(boxes, FarGpu.QUERY_BOX_UNIT);
            GL20C.glUniformMatrix4fv(viewProjection, false, matrix.get(stack.mallocFloat(16)));
            GL20C.glUniform1f(grow, growShare);
            GL20C.glUniform1f(moved, movedBy);
            GL20C.glUniform1i(gameDepth, FarLod.vanillaUnit());
            GL20C.glUniform1i(own, ownDepth ? 1 : 0);
            groupQuery.clear();
            for (int g = 0; g < groupStart.size(); g++) {
                int query = query();
                groupQuery.add(query);
                asked.queries.add(query);
                asked.what.add(g);
                GL20C.glUniform1i(base, ranges + groupStart.getInt(g));
                GL15C.glBeginQuery(GL33C.GL_ANY_SAMPLES_PASSED, query);
                GL31C.glDrawArraysInstanced(GL11C.GL_TRIANGLES, 0, 36, groupSize.getInt(g));
                GL15C.glEndQuery(GL33C.GL_ANY_SAMPLES_PASSED);
            }
            // On their own: the ranges of groups that passed, and round and round the ones drawn first.
            int left = waiting.size() < WAITING ? BUDGET : 0;
            for (int k = 0; k < hidden.size() && left > 0; k++) {
                int range = hidden.getInt(k);
                if (probe[range]) { alone(asked, range); left--; }
            }
            for (int tried = 0; tried < ranges && left > 0; tried++) {
                int range = next;
                next = next + 1 == ranges ? 0 : next + 1;
                if (seen[range] && !close[range]) { alone(asked, range); left--; }
            }
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, savedBuffer);
        } finally {
            GlStateManager._activeTexture(active);
            GL30C.glBindVertexArray(savedArray);
            GlStateManager._glUseProgram(savedProgram);
            GlStateManager._colorMask(colour[0], colour[1], colour[2], colour[3]);
            GlStateManager._depthMask(depthMask);
            if (cull) GlStateManager._enableCull(); else GlStateManager._disableCull();
            if (blend) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
        }
        waiting.addLast(asked);
    }

    private void alone(Asked asked, int range) {
        int query = query();
        asked.queries.add(query);
        asked.what.add(-1 - range);
        GL20C.glUniform1i(base, range);
        GL15C.glBeginQuery(GL33C.GL_ANY_SAMPLES_PASSED, query);
        GL31C.glDrawArraysInstanced(GL11C.GL_TRIANGLES, 0, 36, 1);
        GL15C.glEndQuery(GL33C.GL_ANY_SAMPLES_PASSED);
    }

    private int query() { return spareQueries.isEmpty() ? GL15C.glGenQueries() : spareQueries.removeInt(spareQueries.size() - 1); }

    /** How many groups of a multi-draw's ranges are drawn on condition, for {@link #drawGroup}. */
    int groups(int draw) { return groupCount[draw]; }

    /**
     * Draws a group of a multi-draw's hidden ranges on condition of the boxes {@link #test} drew for it, with the far
     * program and that multi-draw's faces bound; returns how many ranges that was.
     */
    int drawGroup(int draw, int group) {
        int g = groupFrom[draw] + group, size = groupSize.getInt(g);
        GL30C.glBeginConditionalRender(groupQuery.getInt(g), GL30C.GL_QUERY_WAIT);
        multiDraw(seenTotal + groupStart.getInt(g), size);
        GL30C.glEndConditionalRender();
        return size;
    }

    private static int link() {
        int vertex = shader(GL20C.GL_VERTEX_SHADER, VERTEX), fragment = shader(GL20C.GL_FRAGMENT_SHADER, FRAGMENT);
        int linked = GL20C.glCreateProgram();
        GL20C.glAttachShader(linked, vertex);
        GL20C.glAttachShader(linked, fragment);
        GL20C.glLinkProgram(linked);
        GL20C.glDetachShader(linked, vertex);
        GL20C.glDetachShader(linked, fragment);
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        if (GL20C.glGetProgrami(linked, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetProgramInfoLog(linked);
            GL20C.glDeleteProgram(linked);
            throw new IllegalStateException("Could not link the far range box test: " + log);
        }
        return linked;
    }

    private static int shader(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IllegalStateException("Could not compile the far range box test: " + log);
        }
        return shader;
    }

    void close() {
        forget();
        for (int i = 0; i < spareQueries.size(); i++) GL15C.glDeleteQueries(spareQueries.getInt(i));
        spareQueries.clear();
        if (program != 0) GL20C.glDeleteProgram(program);
        if (array != 0) GL30C.glDeleteVertexArrays(array);
        if (buffer != 0) GL15C.glDeleteBuffers(buffer);
        if (texture != 0) GL11C.glDeleteTextures(texture);
        program = array = buffer = texture = 0;
        MemoryUtil.memFree(counts);
        MemoryUtil.memFree(bases);
        MemoryUtil.memFree(offsets);
        MemoryUtil.memFree(boxData);
        recording = null;
    }
}
