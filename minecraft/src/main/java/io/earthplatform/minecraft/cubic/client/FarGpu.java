package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Far terrain on the GPU, the way Voxy keeps it: every column's faces live in a few large buffer
 * textures, 16 bytes a face, and one multi-draw per buffer draws every visible group of faces with
 * the far terrain's own shader. A face is four ints:
 * <ol>
 *   <li>x and z in voxels (5 bits each), direction (3), width in voxels less one (4), light (block
 *       level in the high nibble, sky in the low; 8), and the low 7 bits of the column slot;</li>
 *   <li>its bottom (sides) or height (tops and bottoms) above the column's base, in 1/16 blocks;</li>
 *   <li>its height in 1/16 blocks (sides) or depth in voxels less one (tops and bottoms; 20 bits),
 *       and the rest of the column slot;</li>
 *   <li>its look in {@link FarLooks} (20 bits) and, under a shader pack, its ground slope's normal
 *       (12 bits, see {@link FarMesher#normalCode}).</li>
 * </ol>
 * A column slot holds the column's origin and level. Everything here runs on the render thread.
 */
final class FarGpu {
    private static final int QUAD_UNIT = 3, LOOK_UNIT = 4, COLUMN_UNIT = 5, ATLAS_UNIT = 0, LIGHT_UNIT = 2;
    /** Faces per buffer texture: 64 MB each. */
    private static final int ARENA_QUADS = 1 << 22;
    /** Ints per column slot: origin x, base y, origin z, level. */
    private static final int SLOT_INTS = 4;

    private final int vao;
    private final int arenaQuads;
    private final List<Arena> arenas = new ArrayList<>();
    private final TextureBuffer columns = new TextureBuffer(GL30C.GL_RGBA32I, 4096);
    private final TextureBuffer looks = new TextureBuffer(GL30C.GL_RGBA32UI, 8192);
    private final it.unimi.dsi.fastutil.ints.IntArrayList freeSlots = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private int nextSlot, looksUploaded;
    private final String version;
    @Nullable private Program vanilla, pack;
    @Nullable private FarShaders.PackPatch packSource;
    @Nullable private Object packPipeline;
    private boolean vanillaFailed;
    private long quadBytes;

    /** A column's faces in one arena: {@code counts} faces per group, starting at {@code start}. */
    record Allocation(Arena arena, int start, int size, int[] counts, int[] firsts) {}

    FarGpu() {
        RenderSystem.assertOnRenderThread();
        vao = GL30C.glGenVertexArrays();
        int max = GL11C.glGetInteger(GL31C.GL_MAX_TEXTURE_BUFFER_SIZE);
        arenaQuads = Math.max(1 << 16, Math.min(ARENA_QUADS, max));
        version = glslVersion();
    }

    /** True when this GL can draw far terrain at all (OpenGL 3.3). */
    static boolean supported() {
        GLCapabilities caps = GL.getCapabilities();
        return caps.OpenGL33;
    }

    private static String glslVersion() {
        GLCapabilities caps = GL.getCapabilities();
        String number = caps.OpenGL46 ? "460" : caps.OpenGL45 ? "450" : caps.OpenGL44 ? "440" : caps.OpenGL43 ? "430"
                : caps.OpenGL42 ? "420" : caps.OpenGL41 ? "410" : caps.OpenGL40 ? "400" : "330";
        return "#version " + number + " core\n";
    }

    long quadBytes() { return quadBytes; }

    // Columns ------------------------------------------------------------------------------------

    /** A slot for a column whose faces start at block (x0, baseY, z0) and whose voxels are 2^level blocks. */
    int slot(int x0, int baseY, int z0, int level) {
        int slot = freeSlots.isEmpty() ? nextSlot++ : freeSlots.popInt();
        columns.ensure(slot + 1);
        columns.upload(slot, new int[] {x0, baseY, z0, level});
        return slot;
    }

    void freeSlot(int slot) { freeSlots.add(slot); }

    /** Uploads a column's faces, grouped, or null when there are none. */
    @Nullable Allocation upload(int[] quads, int[] counts) {
        int size = quads.length / 4;
        if (size == 0) return null;
        Arena arena = null;
        int start = -1;
        for (Arena candidate : arenas) if ((start = candidate.allocate(size)) >= 0) { arena = candidate; break; }
        if (arena == null) {
            arena = new Arena(arenaQuads);
            arenas.add(arena);
            start = arena.allocate(size);
            if (start < 0) return null;
        }
        arena.buffer.upload(start, quads);
        int[] firsts = new int[counts.length];
        for (int g = 0, at = start; g < counts.length; at += counts[g], g++) firsts[g] = at;
        quadBytes += size * 16L;
        return new Allocation(arena, start, size, counts.clone(), firsts);
    }

    void free(@Nullable Allocation allocation) {
        if (allocation == null) return;
        allocation.arena.release(allocation.start, allocation.size);
        quadBytes -= allocation.size * 16L;
    }

    // Drawing ------------------------------------------------------------------------------------

    /**
     * Starts a far terrain draw with the vanilla program, or with the shader pack's Voxy patch
     * ({@code patch}, preprocessed by the pack) or the generic pack program when {@code packMode}.
     * Returns false when there is nothing usable to draw with.
     */
    boolean begin(boolean packMode, @Nullable FarShaders.PackPatch patch, Matrix4f modelView, Matrix4f projection, Vec3 camera, float[] shade) {
        Program program = packMode ? packProgram(patch) : vanillaProgram();
        if (program == null) return false;
        uploadLooks();
        previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        GlStateManager._glUseProgram(program.id);
        current = program;
        if (program.irisUniforms != null) program.irisUniforms.apply();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrix = stack.mallocFloat(16);
            GL20C.glUniformMatrix4fv(program.location("earth_ModelView"), false, modelView.get(matrix));
            GL20C.glUniformMatrix4fv(program.location("earth_Projection"), false, projection.get(matrix));
            GL20C.glUniform1fv(program.location("earth_Shade"), shade);
        }
        int bx = (int) Math.floor(camera.x), by = (int) Math.floor(camera.y), bz = (int) Math.floor(camera.z);
        GL20C.glUniform3i(program.location("earth_CameraBlock"), bx, by, bz);
        GL20C.glUniform3f(program.location("earth_CameraFraction"), (float) (camera.x - bx), (float) (camera.y - by), (float) (camera.z - bz));
        GL20C.glUniform1i(program.location("earth_FogShape"), RenderSystem.getShaderFogShape().getIndex());
        GL20C.glUniform1f(program.location("earth_FogStart"), RenderSystem.getShaderFogStart());
        GL20C.glUniform1f(program.location("earth_FogEnd"), RenderSystem.getShaderFogEnd());
        float[] fog = RenderSystem.getShaderFogColor(), colour = RenderSystem.getShaderColor();
        GL20C.glUniform4f(program.location("earth_FogColor"), fog[0], fog[1], fog[2], fog[3]);
        GL20C.glUniform4f(program.location("earth_ColorModulator"), colour[0], colour[1], colour[2], colour[3]);
        GL20C.glUniform1i(program.location("earth_Quads"), QUAD_UNIT);
        GL20C.glUniform1i(program.location("earth_Looks"), LOOK_UNIT);
        GL20C.glUniform1i(program.location("earth_Columns"), COLUMN_UNIT);
        GL20C.glUniform1i(program.location("earth_Atlas"), ATLAS_UNIT);
        GL20C.glUniform1i(program.location("earth_Lightmap"), LIGHT_UNIT);

        int atlas = Minecraft.getInstance().getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId();
        int lightmap = RenderSystem.getShaderTexture(2);
        previousActive = GlStateManager._getActiveTexture();
        bind2d(ATLAS_UNIT, atlas, packMode);
        bind2d(LIGHT_UNIT, lightmap, packMode);
        bindBuffer(LOOK_UNIT, looks.texture);
        bindBuffer(COLUMN_UNIT, columns.texture);
        GlStateManager._activeTexture(previousActive);
        previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        GL30C.glBindVertexArray(vao);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        for (Arena arena : arenas) arena.clearDraws();
        return true;
    }

    private Program current;
    private int previousProgram, previousVao, previousActive;
    private final int[] previous2d = new int[16];

    /** Queues one group of a column's faces. */
    void add(Allocation allocation, int group) {
        int count = allocation.counts[group];
        if (count > 0) allocation.arena.queue(allocation.firsts[group] * 6, count * 6);
    }

    /** Draws everything queued, one multi-draw per buffer, and puts GL back as it was. */
    void end() {
        for (Arena arena : arenas) {
            if (arena.draws == 0) continue;
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + QUAD_UNIT);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, arena.buffer.texture);
            GlStateManager._activeTexture(previousActive);
            arena.firsts.limit(arena.draws);
            arena.counts.limit(arena.draws);
            GL14C.glMultiDrawArrays(GL11C.GL_TRIANGLES, arena.firsts, arena.counts);
            arena.firsts.clear();
            arena.counts.clear();
        }
        for (int unit : new int[] {QUAD_UNIT, LOOK_UNIT, COLUMN_UNIT}) bindBuffer(unit, 0);
        restore2d(ATLAS_UNIT);
        restore2d(LIGHT_UNIT);
        GlStateManager._activeTexture(previousActive);
        GL30C.glBindVertexArray(previousVao);
        BufferUploader.invalidate();
        if (current.irisUniforms != null) FarIrisUniforms.done();
        GlStateManager._glUseProgram(previousProgram);
        current = null;
    }

    private void bind2d(int unit, int texture, boolean packMode) {
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
        previous2d[unit] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(texture);
        // A pack may leave a sampler object on the unit; the atlas's own filtering is wanted.
        if (packMode) IrisSamplers.unbind(unit);
    }

    private void restore2d(int unit) {
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(previous2d[unit]);
    }

    private static void bindBuffer(int unit, int texture) {
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
        GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, texture);
    }

    private void uploadLooks(FarLooks source) {
        int count = source.count();
        if (count == looksUploaded) return;
        looks.ensure(count * 2);
        looks.upload(looksUploaded * 2, source.copy(looksUploaded, count));
        looksUploaded = count;
    }

    private FarLooks lookSource;

    void looks(FarLooks source) { lookSource = source; }

    private void uploadLooks() { if (lookSource != null) uploadLooks(lookSource); }

    // Programs -----------------------------------------------------------------------------------

    @Nullable private Program vanillaProgram() {
        if (vanilla == null && !vanillaFailed) {
            try { vanilla = Program.link(version + FarShaders.VERTEX, version + FarShaders.FRAGMENT, "far terrain"); }
            catch (IllegalStateException failure) {
                vanillaFailed = true;
                EarthPlatformMod.LOGGER.error("Far terrain shader failed to build; far terrain is off", failure);
            }
        }
        return vanilla;
    }

    /**
     * The program for the active pack: its Voxy patch when it has one that builds, else the generic
     * one, jittered for the pack's TAA when it asks for that, and given the pack's uniforms. Rebuilt
     * when the pack or its pipeline changes.
     */
    @Nullable private Program packProgram(@Nullable FarShaders.PackPatch patch) {
        Object pipeline = ShaderBlocks.IRIS ? FarIrisUniforms.pipeline() : null;
        if (pack != null && java.util.Objects.equals(patch, packSource) && pipeline == packPipeline) return pack;
        if (pack != null) pack.close();
        pack = null;
        packSource = patch;
        packPipeline = pipeline;
        String jitter = patch != null && ShaderBlocks.IRIS ? FarIrisUniforms.jitter(patch.taaOffset()) : null;
        if (patch != null) {
            pack = linkPack(jitter, FarShaders.packFragment(version, patch.fragment()), "far terrain (shader pack)");
            if (pack != null) {
                EarthPlatformMod.LOGGER.info("Far terrain uses the shader pack's Voxy fragment code{}", pack.jittered ? ", jittered for its TAA" : "");
                return pack;
            }
            EarthPlatformMod.LOGGER.warn("The shader pack's Voxy fragment code does not build for far terrain; using plain colours");
        }
        pack = linkPack(jitter, version + FarShaders.PACK_FALLBACK, "far terrain (plain, shader pack)");
        if (pack == null) EarthPlatformMod.LOGGER.error("Far terrain shader failed to build");
        return pack;
    }

    /** Links a pack program, jittered when {@code jitter} builds, and finds the pack's uniforms in it. */
    @Nullable private Program linkPack(@Nullable String jitter, String fragment, String name) {
        Program program = null;
        if (jitter != null) {
            try { program = Program.link(FarShaders.packVertex(version, jitter), fragment, name); program.jittered = true; }
            catch (IllegalStateException failure) {
                EarthPlatformMod.LOGGER.warn("The shader pack's TAA jitter does not build for far terrain; far terrain goes without it", failure);
            }
        }
        if (program == null) {
            try { program = Program.link(FarShaders.packVertex(version, null), fragment, name); }
            catch (IllegalStateException failure) {
                EarthPlatformMod.LOGGER.warn("Could not build {}", name, failure);
                return null;
            }
        }
        if (ShaderBlocks.IRIS) program.irisUniforms = FarIrisUniforms.create(program.id, name);
        return program;
    }

    void close() {
        RenderSystem.assertOnRenderThread();
        for (Arena arena : arenas) arena.close();
        arenas.clear();
        columns.close();
        looks.close();
        if (vanilla != null) vanilla.close();
        if (pack != null) pack.close();
        GL30C.glDeleteVertexArrays(vao);
    }

    /** A linked program and its uniform locations. */
    private static final class Program {
        final int id;
        private final Map<String, Integer> locations = new java.util.HashMap<>();
        /** The shader pack's uniforms; only ever set with Iris, so FarIrisUniforms never loads without it. */
        @Nullable FarIrisUniforms irisUniforms;
        boolean jittered;

        private Program(int id) { this.id = id; }

        int location(String name) { return locations.computeIfAbsent(name, key -> GL20C.glGetUniformLocation(id, key)); }

        static Program link(String vertexSource, String fragmentSource, String name) {
            int vertex = compile(GL20C.GL_VERTEX_SHADER, vertexSource, name);
            int fragment;
            try { fragment = compile(GL20C.GL_FRAGMENT_SHADER, fragmentSource, name); }
            catch (IllegalStateException failure) { GL20C.glDeleteShader(vertex); throw failure; }
            int program = GL20C.glCreateProgram();
            GL20C.glAttachShader(program, vertex);
            GL20C.glAttachShader(program, fragment);
            GL20C.glLinkProgram(program);
            GL20C.glDetachShader(program, vertex);
            GL20C.glDetachShader(program, fragment);
            GL20C.glDeleteShader(vertex);
            GL20C.glDeleteShader(fragment);
            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
                String log = GL20C.glGetProgramInfoLog(program);
                GL20C.glDeleteProgram(program);
                throw new IllegalStateException("Could not link " + name + ": " + log);
            }
            return new Program(program);
        }

        private static int compile(int type, String source, String name) {
            int shader = GL20C.glCreateShader(type);
            GL20C.glShaderSource(shader, source);
            GL20C.glCompileShader(shader);
            if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
                String log = GL20C.glGetShaderInfoLog(shader);
                GL20C.glDeleteShader(shader);
                throw new IllegalStateException("Could not compile " + name + (type == GL20C.GL_VERTEX_SHADER ? " vertex" : " fragment")
                        + " shader: " + log + "\n" + numbered(source));
            }
            return shader;
        }

        private static String numbered(String source) {
            StringBuilder out = new StringBuilder();
            String[] lines = source.split("\n", -1);
            for (int i = 0; i < lines.length; i++) out.append(i + 1).append(": ").append(lines[i]).append('\n');
            return out.toString();
        }

        void close() {
            if (irisUniforms != null) irisUniforms.close();
            GL20C.glDeleteProgram(id);
        }
    }

    /** A GL buffer seen by shaders as a buffer texture of 16-byte texels, grown by copying on the GPU. */
    private static final class TextureBuffer {
        final int format;
        int buffer, texture, capacity;

        TextureBuffer(int format, int capacity) {
            this.format = format;
            this.capacity = capacity;
            buffer = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, buffer);
            GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, capacity * 16L, GL15C.GL_DYNAMIC_DRAW);
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
            texture = GL11C.glGenTextures();
            attach();
        }

        private void attach() {
            int active = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + QUAD_UNIT);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, texture);
            GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, format, buffer);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, 0);
            GlStateManager._activeTexture(active);
        }

        void ensure(int texels) {
            if (texels <= capacity) return;
            int grown = Math.max(texels, capacity * 2);
            int bigger = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, bigger);
            GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, grown * 16L, GL15C.GL_DYNAMIC_DRAW);
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, buffer);
            GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0, 0, capacity * 16L);
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, 0);
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
            GL15C.glDeleteBuffers(buffer);
            buffer = bigger;
            capacity = grown;
            attach();
        }

        /** Writes ints starting at a texel. */
        void upload(int texel, int[] data) {
            IntBuffer ints = MemoryUtil.memAllocInt(data.length);
            try {
                ints.put(data).flip();
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, buffer);
                GL15C.glBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, texel * 16L, ints);
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
            } finally {
                MemoryUtil.memFree(ints);
            }
        }

        void close() {
            GL11C.glDeleteTextures(texture);
            GL15C.glDeleteBuffers(buffer);
        }
    }

    /** One buffer texture of faces, handed out to columns first-fit, with its queued draws. */
    static final class Arena {
        final TextureBuffer buffer;
        private final TreeMap<Integer, Integer> free = new TreeMap<>();
        IntBuffer firsts = MemoryUtil.memAllocInt(1024), counts = MemoryUtil.memAllocInt(1024);
        int draws;

        Arena(int quads) {
            buffer = new TextureBuffer(GL30C.GL_RGBA32UI, quads);
            free.put(0, quads);
        }

        int allocate(int size) {
            for (Map.Entry<Integer, Integer> entry : free.entrySet()) {
                if (entry.getValue() < size) continue;
                int start = entry.getKey(), left = entry.getValue() - size;
                free.remove(start);
                if (left > 0) free.put(start + size, left);
                return start;
            }
            return -1;
        }

        void release(int start, int size) {
            Map.Entry<Integer, Integer> before = free.floorEntry(start), after = free.ceilingEntry(start);
            if (before != null && before.getKey() + before.getValue() == start) { start = before.getKey(); size += before.getValue(); }
            if (after != null && start + size == after.getKey()) { size += after.getValue(); free.remove(after.getKey()); }
            free.put(start, size);
        }

        void clearDraws() { draws = 0; firsts.clear(); counts.clear(); }

        void queue(int first, int count) {
            if (draws == firsts.capacity()) {
                firsts = MemoryUtil.memRealloc(firsts, draws * 2).clear();
                counts = MemoryUtil.memRealloc(counts, draws * 2).clear();
            }
            firsts.put(draws, first);
            counts.put(draws, count);
            draws++;
        }

        void close() {
            buffer.close();
            MemoryUtil.memFree(firsts);
            MemoryUtil.memFree(counts);
        }
    }

    /** Only loaded with Iris: keeps Iris's record of bound sampler objects right. */
    private static final class IrisSamplers {
        static void unbind(int unit) {
            if (ShaderBlocks.IRIS) net.irisshaders.iris.gl.IrisRenderSystem.bindSamplerToUnit(unit, 0);
        }
    }
}
