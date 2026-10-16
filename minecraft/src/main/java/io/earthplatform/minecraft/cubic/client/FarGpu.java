package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.far.Far;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
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
 *   <li>its bottom (sides) or height (tops and bottoms) above the column's base, in 1/16 blocks,
 *       or for smooth ground (directions 6 and 7, see {@link FarMesher}) the texel its points start at;</li>
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
    private final TextureBuffer columns = new TextureBuffer(GL30C.GL_RGBA32I, 4096, true);
    private final TextureBuffer looks = new TextureBuffer(GL30C.GL_RGBA32UI, 8192, true);
    private final it.unimi.dsi.fastutil.ints.IntArrayList freeSlots = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private int nextSlot, looksUploaded;
    private final String version;
    /** Programs by fade ({@link #NO_FADE}, {@link #FADE_OUT}, {@link #FADE_IN}); a fade's program is built when first asked for. */
    private final Program[] vanilla = new Program[3], pack = new Program[3];
    private final boolean[] vanillaFailed = new boolean[3], packFailed = new boolean[3];
    @Nullable private FarShaders.PackPatch packSource;
    @Nullable private Object packPipeline;
    private boolean packOwnDepth, packJittered;
    private long quadBytes;

    /** Faces drawn whole; faces fading out over the outer ring of their level; faces of the coarser level fading in there. */
    static final int NO_FADE = 0, FADE_OUT = 1, FADE_IN = 2;
    /** The top bit of a column slot's stamp: its fade is a merge, the coarser level taking back over (see {@link #stamp}). */
    static final int MERGING = 1 << 31;

    /** A column's faces in one arena: {@code counts} faces per group, starting at {@code start}. */
    /** A column's faces in an arena; smooth when they include smooth ground, whose slopes can face the camera from below. */
    record Allocation(Arena arena, int start, int size, int[] counts, int[] firsts, boolean smooth) {}

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

    /** Now on the fade clock: hundredths of a second, wrapping in 27 bits; never 0, which stands for "not fading". */
    static int now() {
        int now = (int) (System.nanoTime() / 10_000_000L) & 0x7FFFFFF;
        return now == 0 ? 1 : now;
    }

    /**
     * Marks when a column's faces in a slot began to fade in or out over another level's (see
     * {@link FarShaders#fade}), or with 0 clears the mark. A merging fade runs the other way: the
     * coarser level takes back over from the finer one.
     */
    void stamp(int slot, int now, boolean merging) {
        if (slot < 0) return;
        int[] texel = columns.texel(slot);
        texel[3] = texel[3] & 15 | now << 4 | (merging && now != 0 ? MERGING : 0);
        columns.upload(slot, texel);
    }

    /**
     * Uploads a column's faces, grouped, and the points of its smooth ground right after them (one
     * texel each; its faces are pointed at them), or null when there are none.
     */
    /** Whether the shader pack's far program is jittered for its TAA, so a dither changes every frame. */
    boolean jittered() { return packJittered; }

    @Nullable Allocation upload(int[] quads, int[] counts, @Nullable int[] grid) {
        int faces = quads.length / 4, size = faces + (grid == null ? 0 : grid.length / 4);
        if (faces == 0) return null;
        Arena arena = null;
        int start = -1;
        for (Arena candidate : arenas) if ((start = candidate.allocate(size)) >= 0) { arena = candidate; break; }
        if (arena == null) {
            arena = new Arena(arenaQuads);
            arenas.add(arena);
            start = arena.allocate(size);
            if (start < 0) return null;
        }
        if (grid != null) {
            for (int i = 0; i < quads.length; i += 4) if ((quads[i] >> 10 & 7) >= FarMesher.SMOOTH_TOP) quads[i + 1] = start + faces;
            arena.buffer.upload(start + faces, grid);
        }
        arena.buffer.upload(start, quads);
        int[] firsts = new int[counts.length];
        for (int g = 0, at = start; g < counts.length; at += counts[g], g++) firsts[g] = at;
        quadBytes += size * 16L;
        return new Allocation(arena, start, size, counts.clone(), firsts, grid != null);
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
     * When far terrain has its own depth ({@link FarLod}), the pack program drops fragments where
     * the game's depth (on {@link FarLod#vanillaUnit()}) already holds terrain. Returns false when
     * there is nothing usable to draw with.
     */
    boolean begin(boolean packMode, @Nullable FarShaders.PackPatch patch, Matrix4f modelView, Matrix4f projection, Vec3 camera, float[] shade,
            int frame) {
        Program program = packMode ? packProgram(patch, NO_FADE) : vanillaProgram(NO_FADE);
        if (program == null) return false;
        uploadLooks();
        this.packMode = packMode;
        this.patch = patch;
        this.modelView = modelView;
        this.projection = projection;
        this.camera = camera;
        this.shade = shade;
        this.frame = frame;
        previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        use(program);

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

    /**
     * Draws what is queued so far, then switches to the program for a fade (see {@link FarShaders}).
     * Returns false when that fade's program does not build: faces fading out are then drawn whole
     * by the plain program, and faces fading in should be left out.
     */
    boolean fade(int fade) {
        flush();
        Program program = packMode ? packProgram(patch, fade) : vanillaProgram(fade);
        boolean built = program != null;
        if (program == null) program = packMode ? packProgram(patch, NO_FADE) : vanillaProgram(NO_FADE);
        if (program != null && program != current) {
            if (current.irisUniforms != null) FarIrisUniforms.done();
            use(program);
        }
        return built;
    }

    private boolean packMode;
    @Nullable private FarShaders.PackPatch patch;
    private Matrix4f modelView, projection;
    private Vec3 camera;
    private float[] shade;
    private int frame;

    /** Puts a program in use with this draw's uniforms. */
    private void use(Program program) {
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
        GL20C.glUniform1i(program.location("earth_GameDepth"), FarLod.vanillaUnit());
        if (packMode && packOwnDepth) FarLod.shadowUniforms(program::location);
        GL20C.glUniform1f(program.location("earth_FadeHole"), Far.hole());
        GL30C.glUniform1ui(program.location("earth_Now"), now());
        // Under a pack's TAA the dither moves every frame and blends into a smooth fade; without it, it holds still.
        GL20C.glUniform1f(program.location("earth_DitherFrame"), program.jittered ? frame & 63 : 0);
    }

    private Program current;
    private int previousProgram, previousVao, previousActive;
    private final int[] previous2d = new int[16];

    /** Queues one group of a column's faces. */
    void add(Allocation allocation, int group) {
        int count = allocation.counts[group];
        if (count > 0) allocation.arena.queue(allocation.firsts[group] * 6, count * 6);
    }

    /** Draws everything queued and puts GL back as it was. */
    void end() {
        flush();
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

    /** Draws everything queued, one multi-draw per buffer. */
    private void flush() {
        for (Arena arena : arenas) {
            if (arena.draws == 0) continue;
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + QUAD_UNIT);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, arena.buffer.texture);
            GlStateManager._activeTexture(previousActive);
            arena.firsts.limit(arena.draws);
            arena.counts.limit(arena.draws);
            GL14C.glMultiDrawArrays(GL11C.GL_TRIANGLES, arena.firsts, arena.counts);
            arena.clearDraws();
        }
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

    @Nullable private Program vanillaProgram(int fade) {
        if (vanilla[fade] == null && !vanillaFailed[fade]) {
            String header = version + FarShaders.fade(fade);
            try { vanilla[fade] = Program.link(header + FarShaders.VERTEX, header + FarShaders.FRAGMENT, "far terrain"); }
            catch (IllegalStateException failure) {
                vanillaFailed[fade] = true;
                if (fade == NO_FADE) EarthPlatformMod.LOGGER.error("Far terrain shader failed to build; far terrain is off", failure);
                else EarthPlatformMod.LOGGER.warn("Far terrain's fade between levels failed to build; levels meet without it", failure);
            }
        }
        return vanilla[fade];
    }

    /**
     * The program for the active pack: its Voxy patch when it has one that builds, else the generic
     * one, jittered for the pack's TAA when it asks for that, and given the pack's uniforms. Rebuilt
     * when the pack or its pipeline changes.
     */
    @Nullable private Program packProgram(@Nullable FarShaders.PackPatch patch, int fade) {
        Object pipeline = ShaderBlocks.IRIS ? FarIrisUniforms.pipeline() : null;
        boolean ownDepth = FarLod.active();
        if (!java.util.Objects.equals(patch, packSource) || pipeline != packPipeline || ownDepth != packOwnDepth) {
            for (int i = 0; i < pack.length; i++) {
                if (pack[i] != null) pack[i].close();
                pack[i] = null;
                packFailed[i] = false;
            }
            packSource = patch;
            packPipeline = pipeline;
            packOwnDepth = ownDepth;
            packPatched = patch != null;
            packJittered = true;
        }
        if (pack[fade] != null || packFailed[fade]) return pack[fade];
        // Every fade builds as the plain program did: the same patch, jittered or not.
        String jitter = packJittered && patch != null && ShaderBlocks.IRIS ? FarIrisUniforms.jitter(patch.taaOffset()) : null;
        String header = version + FarShaders.fade(fade);
        if (packPatched) {
            pack[fade] = linkPack(jitter, FarShaders.packFragment(version, patch.fragment(), ownDepth, fade), "far terrain (shader pack)", header);
            if (pack[fade] != null) {
                if (fade == NO_FADE) {
                    packJittered = pack[fade].jittered;
                    EarthPlatformMod.LOGGER.info("Far terrain uses the shader pack's Voxy fragment code{}", packJittered ? ", jittered for its TAA" : "");
                }
                return pack[fade];
            }
            if (fade != NO_FADE) {
                packFailed[fade] = true;
                EarthPlatformMod.LOGGER.warn("Far terrain's fade between levels does not build with the shader pack; levels meet without it");
                return null;
            }
            packPatched = false;
            EarthPlatformMod.LOGGER.warn("The shader pack's Voxy fragment code does not build for far terrain; using plain colours");
        }
        pack[fade] = linkPack(jitter, header + FarShaders.PACK_FALLBACK, "far terrain (plain, shader pack)", header);
        if (pack[fade] == null) {
            packFailed[fade] = true;
            if (fade == NO_FADE) EarthPlatformMod.LOGGER.error("Far terrain shader failed to build");
        } else if (fade == NO_FADE) {
            packJittered = pack[fade].jittered;
        }
        return pack[fade];
    }

    /** Whether the pack's Voxy code builds; once it fails, every fade uses the plain colours too. */
    private boolean packPatched;

    /** Links a pack program, jittered when {@code jitter} builds, and finds the pack's uniforms in it. */
    @Nullable private Program linkPack(@Nullable String jitter, String fragment, String name, String header) {
        Program program = null;
        if (jitter != null) {
            try { program = Program.link(FarShaders.packVertex(header, jitter), fragment, name); program.jittered = true; }
            catch (IllegalStateException failure) {
                EarthPlatformMod.LOGGER.warn("The shader pack's TAA jitter does not build for far terrain; far terrain goes without it", failure);
            }
        }
        if (program == null) {
            try { program = Program.link(FarShaders.packVertex(header, null), fragment, name); }
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
        for (Program program : vanilla) if (program != null) program.close();
        for (Program program : pack) if (program != null) program.close();
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

    /**
     * A GL buffer seen by shaders as a buffer texture of 16-byte texels, with a copy of its contents
     * kept here: it grows by uploading that copy into a bigger buffer. Copying on the GPU with
     * glCopyBufferSubData did nothing on Apple's OpenGL ("gldCopyBufferSubData: NEEDS
     * IMPLEMENTATION" in the log), so once the column table outgrew its first size every far column
     * read zeros or junk for its place and scale and drew as stretched, slanted sheets.
     */
    private static final class TextureBuffer {
        final int format;
        int buffer, texture, capacity;
        /** What the buffer holds, for one that grows; null for a fixed-size one (the face arenas). */
        @Nullable private int[] contents;

        TextureBuffer(int format, int capacity, boolean grows) {
            this.format = format;
            this.capacity = capacity;
            if (grows) contents = new int[capacity * 4];
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
            if (contents == null) throw new IllegalStateException("A fixed-size far buffer cannot grow");
            int grown = Math.max(texels, capacity * 2);
            contents = Arrays.copyOf(contents, grown * 4);
            int bigger = GL15C.glGenBuffers();
            IntBuffer ints = MemoryUtil.memAllocInt(contents.length);
            try {
                ints.put(contents).flip();
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, bigger);
                GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, ints, GL15C.GL_DYNAMIC_DRAW);
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
            } finally {
                MemoryUtil.memFree(ints);
            }
            GL15C.glDeleteBuffers(buffer);
            buffer = bigger;
            capacity = grown;
            attach();
        }

        /** A copy of one texel's ints, of a buffer that grows. */
        int[] texel(int texel) { return Arrays.copyOfRange(contents, texel * 4, texel * 4 + 4); }

        /** Writes ints starting at a texel. */
        void upload(int texel, int[] data) {
            IntBuffer ints = MemoryUtil.memAllocInt(data.length);
            try {
                ints.put(data).flip();
                if (contents != null) System.arraycopy(data, 0, contents, texel * 4, data.length);
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
            buffer = new TextureBuffer(GL30C.GL_RGBA32UI, quads, false);
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
