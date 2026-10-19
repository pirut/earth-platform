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
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.PointerBuffer;
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

    private final int vao;
    /**
     * Quad indices (0 1 2 0 2 3 for each face's four corners) for {@link #INDEXED} draws: with them a
     * face's corners are worked out four times instead of six, the GPU reusing the two that its
     * triangles share. Ranges longer than this split, which changes nothing drawn.
     */
    private int indices;
    static final int INDEX_QUADS = 1 << 16;
    /** Draws faces by index; -Dearthplatform.far.indexed=false draws six corners per face as before build 52. */
    static final boolean INDEXED = !"false".equals(System.getProperty("earthplatform.far.indexed"));
    private static final boolean INDEXED_ALLOCATOR = !"false".equals(System.getProperty("earthplatform.far.indexedAllocator"));
    private final int arenaQuads;
    private final List<Arena> arenas = new ArrayList<>();
    private final TextureBuffer columns = new TextureBuffer(GL30C.GL_RGBA32I, 4096, true);
    private final TextureBuffer looks = new TextureBuffer(GL30C.GL_RGBA32UI, 8192, true);
    private final it.unimi.dsi.fastutil.ints.IntArrayList freeSlots = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private int nextSlot, looksUploaded;
    private final String version;
    /** Programs by fade ({@link #NO_FADE}, {@link #FADE_OUT}, {@link #FADE_IN}); a fade's program is built when first asked for. */
    private final Program[] vanilla = new Program[3], pack = new Program[3], packNear = new Program[3];
    private final boolean[] vanillaFailed = new boolean[3], packFailed = new boolean[3], packNearFailed = new boolean[3];
    @Nullable private FarShaders.PackPatch packSource;
    @Nullable private Object packPipeline;
    private boolean packOwnDepth, packJittered;
    private long quadBytes;

    /**
     * Under a shader pack with far terrain's own depth, draws the far terrain twice: a pre-pass
     * with only the program's fragment dropping and depth, then the shaded pass tested EQUAL
     * against the depth of the faces the pre-pass kept, with nothing in it that stops the GPU
     * testing depth before shading. The one-pass program drops fragments and writes depth, so
     * every face under a pixel was shaded, pack code and all, however many lay behind.
     * {@code -Dearthplatform.far.depthPrepass=true}; off by default until measured.
     */
    static final boolean DEPTH_PREPASS = Boolean.getBoolean("earthplatform.far.depthPrepass");
    /** What {@link #begin} draws: the one-pass draw, the depth pre-pass, or the shading after it. */
    static final int ONE_PASS = 0, PREPASS = 1, SHADE = 2;
    private int phase = ONE_PASS;
    private final Program[] prepass = new Program[3], shading = new Program[3];
    private final boolean[] phasedFailed = new boolean[3];
    /** The fade clock both passes of a pre-passed draw read, so a fade's dither drops the same fragments in both; 0 when not. */
    private int frozenNow;
    private int shadeDepthFunc;

    /** Faces drawn whole; faces fading out over the outer ring of their level; faces of the coarser level fading in there. */
    static final int NO_FADE = 0, FADE_OUT = 1, FADE_IN = 2;
    /** The top bit of a column slot's stamp: its fade is a merge, the coarser level taking back over (see {@link #stamp}). */
    static final int MERGING = 1 << 31;

    /** A column's faces in one arena: {@code counts} faces per group, starting at {@code start}. */
    /** A column's faces in an arena; smooth when they include smooth ground, whose slopes can face the camera from below. */
    record Allocation(Arena arena, FarArenaRanges.Allocation range, int start, int size, int[] counts, int[] firsts, boolean smooth) {}

    FarGpu() {
        RenderSystem.assertOnRenderThread();
        vao = GL30C.glGenVertexArrays();
        if (INDEXED) {
            indices = GL15C.glGenBuffers();
            IntBuffer quad = MemoryUtil.memAllocInt(INDEX_QUADS * 6);
            try {
                for (int q = 0; q < INDEX_QUADS; q++) {
                    int c = q * 4;
                    quad.put(c).put(c + 1).put(c + 2).put(c).put(c + 2).put(c + 3);
                }
                quad.flip();
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, indices);
                GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, quad, GL15C.GL_STATIC_DRAW);
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
            } finally {
                MemoryUtil.memFree(quad);
            }
        }
        int max = GL11C.glGetInteger(GL31C.GL_MAX_TEXTURE_BUFFER_SIZE);
        arenaQuads = Math.max(1 << 16, Math.min(ARENA_QUADS, max));
        version = glslVersion() + (INDEXED ? "#define EARTH_INDEXED\n" : "");
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
        FarArenaRanges.Allocation range = null;
        for (Arena candidate : arenas) if ((range = candidate.allocate(size)) != null) { arena = candidate; break; }
        if (arena == null) {
            arena = new Arena(arenaQuads);
            arenas.add(arena);
            range = arena.allocate(size);
            if (range == null) return null;
        }
        int start = range.start();
        if (grid != null) {
            for (int i = 0; i < quads.length; i += 4) if ((quads[i] >> 10 & 7) >= FarMesher.SMOOTH_TOP) quads[i + 1] = start + faces;
            arena.buffer.upload(start + faces, grid);
        }
        arena.buffer.upload(start, quads);
        int[] firsts = new int[counts.length];
        for (int i = 0, at = start; i < counts.length; at += counts[FarMesher.STORED[i]], i++) firsts[FarMesher.STORED[i]] = at;
        quadBytes += size * 16L;
        return new Allocation(arena, range, start, size, counts.clone(), firsts, grid != null);
    }

    void free(@Nullable Allocation allocation) {
        if (allocation == null) return;
        allocation.arena.release(allocation.range);
        quadBytes -= allocation.size * 16L;
    }

    // Drawing ------------------------------------------------------------------------------------

    /**
     * Starts a far terrain draw with the vanilla program, or with the shader pack's Voxy patch
     * ({@code patch}, preprocessed by the pack) or the generic pack program when {@code packMode}.
     * When far terrain has its own depth ({@link FarLod}), the pack program drops fragments where
     * the game's depth (on {@link FarLod#vanillaUnit()}) already holds terrain, and those inside the
     * game's blocks' reach, which a {@code near} draw puts in the game's own depth beforehand.
     * Returns false when there is nothing usable to draw with.
     */
    boolean begin(boolean packMode, @Nullable FarShaders.PackPatch patch, Matrix4f modelView, Matrix4f projection, Vec3 camera, float[] shade,
            int frame, boolean near) {
        this.near = near;
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
        // Bound again every draw, as the near cubes' are, so nothing outside can leave another there.
        if (INDEXED) GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indices);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        if (phase == PREPASS) frozenNow = now();
        if (phase == SHADE) {
            shadeDepthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
            RenderSystem.depthMask(false);
            RenderSystem.depthFunc(GL11C.GL_EQUAL);
        }
        for (Arena arena : arenas) arena.clearDraws();
        ranges = 0;
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

    private boolean packMode, near;
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
        if (packMode && packOwnDepth && !near) FarLod.shadowUniforms(program::location);
        GL20C.glUniform1f(program.location("earth_FadeHole"), Far.hole());
        GL20C.glUniform1f(program.location("earth_NearReach"), FarLod.nearReach());
        GL30C.glUniform1ui(program.location("earth_Now"), frozenNow != 0 ? frozenNow : now());
        // Under a pack's TAA the dither moves every frame and blends into a smooth fade; without it, it holds still.
        GL20C.glUniform1f(program.location("earth_DitherFrame"), program.jittered ? frame & 63 : 0);
    }

    /** Draws every group as a range of its own, as before build 52, to compare the two (-Dearthplatform.far.splitRanges=true). */
    private static final boolean SPLIT_RANGES = Boolean.getBoolean("earthplatform.far.splitRanges");

    /** Ranges drawn since {@link #begin}: each is a draw of its own inside the multi-draws. */
    int ranges;

    private Program current;
    private int previousProgram, previousVao, previousActive;
    private final int[] previous2d = new int[16];

    /**
     * Queues the groups of a column's faces in a mask (bit per group), as few ranges as they make
     * in the order they are stored: an empty group between two wanted ones joins them. Each range
     * is a draw of its own in the multi-draw, and those cost the driver and the GPU more than the
     * faces in them at far terrain's sizes.
     */
    void add(Allocation allocation, int groups) {
        if (SPLIT_RANGES) {
            for (int group = 0; group < FarMesher.GROUPS; group++) {
                int size = allocation.counts[group];
                if ((groups & 1 << group) != 0 && size > 0) allocation.arena.queue(allocation.firsts[group], size);
            }
            return;
        }
        int first = -1, count = 0;
        for (int group : FarMesher.STORED) {
            int size = allocation.counts[group];
            if ((groups & 1 << group) != 0 || size == 0 && first >= 0) {
                if (first < 0) first = allocation.firsts[group];
                count += size;
            } else if (first >= 0) {
                if (count > 0) allocation.arena.queue(first, count);
                first = -1;
                count = 0;
            }
        }
        if (first >= 0 && count > 0) allocation.arena.queue(first, count);
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
        if (phase == SHADE) {
            RenderSystem.depthFunc(shadeDepthFunc);
            RenderSystem.depthMask(true);
        }
    }

    /**
     * After {@link #begin} for the far pass: whether its programs drop every fragment inside the
     * game's blocks' reach, as the pack's own-depth programs do (not the plain-colour fallback).
     */
    boolean dropsNearReach() { return packMode && !near && packPatched && packOwnDepth; }

    /** Sets what the next {@link #begin} draws; back to {@link #ONE_PASS} after the shading pass. */
    void phase(int phase) {
        this.phase = phase;
        if (phase == ONE_PASS) frozenNow = 0;
    }

    /**
     * Whether the far draw can go in two passes this frame: the pack's Voxy code builds with far
     * terrain's own depth and leaves its fragments and depth alone, every fade the one-pass draw
     * has a program for has both passes' programs too, and nothing blends.
     */
    boolean prepassReady(@Nullable FarShaders.PackPatch patch) {
        if (!DEPTH_PREPASS || patch == null || !FarShaders.prepassSafe(patch.fragment()) || GL11C.glIsEnabled(GL11C.GL_BLEND)) return false;
        boolean wasNear = near;
        int was = phase;
        near = false;
        try {
            for (int fade = NO_FADE; fade <= FADE_IN; fade++) {
                phase = ONE_PASS;
                if (packProgram(patch, fade) == null) continue;
                if (!packPatched || !packOwnDepth) return false;
                phase = PREPASS;
                if (packProgram(patch, fade) == null) return false;
                phase = SHADE;
                if (packProgram(patch, fade) == null) return false;
            }
            return true;
        } finally {
            near = wasNear;
            phase = was;
        }
    }

    /** A pass of the pre-passed draw's program for a fade, built from the same patch as the one-pass program. */
    @Nullable private Program phasedProgram(FarShaders.PackPatch patch, int fade) {
        Program[] programs = phase == PREPASS ? prepass : shading;
        if (programs[fade] != null || phasedFailed[fade]) return programs[fade];
        int was = phase;
        phase = ONE_PASS;
        Program main = packProgram(patch, fade);
        phase = was;
        if (main == null || !packPatched || !packOwnDepth) return null;
        String jitter = main.jittered && ShaderBlocks.IRIS ? FarIrisUniforms.jitter(patch.taaOffset()) : null;
        String header = version + FarShaders.fade(fade) + FarShaders.INVARIANT;
        String fragment = phase == PREPASS ? FarShaders.packPrepassFragment(version, patch.fragment(), fade) : FarShaders.packShadeFragment(version, patch.fragment(), fade);
        Program built = linkPack(jitter, fragment, phase == PREPASS ? "far terrain depth pre-pass" : "far terrain after its pre-pass", header);
        if (built != null && built.jittered != main.jittered) {
            built.close();
            built = null;
        }
        if (built == null) {
            phasedFailed[fade] = true;
            EarthPlatformMod.LOGGER.warn("Far terrain's depth pre-pass does not build with the shader pack; it draws in one pass");
        }
        programs[fade] = built;
        return built;
    }

    /** Draws everything queued, one multi-draw per buffer. */
    private void flush() {
        for (Arena arena : arenas) {
            if (arena.draws == 0) continue;
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + QUAD_UNIT);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, arena.buffer.texture);
            GlStateManager._activeTexture(previousActive);
            arena.draw();
            ranges += arena.draws;
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
                if (packNear[i] != null) packNear[i].close();
                pack[i] = packNear[i] = null;
                packFailed[i] = packNearFailed[i] = false;
                if (prepass[i] != null) prepass[i].close();
                if (shading[i] != null) shading[i].close();
                prepass[i] = shading[i] = null;
                phasedFailed[i] = false;
            }
            packSource = patch;
            packPipeline = pipeline;
            packOwnDepth = ownDepth;
            packPatched = patch != null;
            packJittered = true;
        }
        if (near) return nearProgram(patch, fade);
        if (phase != ONE_PASS) return patch == null ? null : phasedProgram(patch, fade);
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
            EarthPlatformMod.LOGGER.warn("The shader pack's Voxy fragment code does not build for far terrain; using plain colors");
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

    /**
     * The pack's program for far terrain inside the game's blocks' reach while it has its own depth
     * (see {@link FarShaders#packFragment}): built only once the main one is, from the same patch.
     */
    @Nullable private Program nearProgram(@Nullable FarShaders.PackPatch patch, int fade) {
        if (packNear[fade] != null || packNearFailed[fade]) return packNear[fade];
        near = false;
        Program main = packProgram(patch, fade);
        near = true;
        if (main == null || patch == null || !packPatched || !packOwnDepth) { packNearFailed[fade] = true; return null; }
        String jitter = main.jittered && ShaderBlocks.IRIS ? FarIrisUniforms.jitter(patch.taaOffset()) : null;
        packNear[fade] = linkPack(jitter, FarShaders.packNearFragment(version, patch.fragment(), fade), "far terrain near by (shader pack)",
                version + FarShaders.fade(fade));
        if (packNear[fade] == null) {
            packNearFailed[fade] = true;
            EarthPlatformMod.LOGGER.warn("Far terrain near by does not build with the shader pack; it is left out until the game's blocks load");
        }
        return packNear[fade];
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
        for (Program program : prepass) if (program != null) program.close();
        for (Program program : shading) if (program != null) program.close();
        GL30C.glDeleteVertexArrays(vao);
        if (indices != 0) GL15C.glDeleteBuffers(indices);
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
            return new Program(GlShaders.link(vertexSource, fragmentSource, name));
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

    /** One buffer texture of faces, with indexed free ranges and its queued draws. */
    static final class Arena {
        final TextureBuffer buffer;
        private final FarArenaRanges free;
        /** Queued ranges: first face and face count. */
        IntBuffer firsts = MemoryUtil.memAllocInt(1024), counts = MemoryUtil.memAllocInt(1024);
        /** The same ranges as the multi-draw takes them: corner counts, and where each range starts. */
        private IntBuffer drawCounts = MemoryUtil.memAllocInt(1024), drawStarts = MemoryUtil.memAllocInt(1024);
        private PointerBuffer offsets = MemoryUtil.memAllocPointer(1024);
        int draws;

        Arena(int quads) {
            buffer = new TextureBuffer(GL30C.GL_RGBA32UI, quads, false);
            free = new FarArenaRanges(quads, INDEXED_ALLOCATOR);
        }

        @Nullable FarArenaRanges.Allocation allocate(int size) { return free.allocate(size); }

        void release(FarArenaRanges.Allocation range) { free.release(range); }

        void clearDraws() { draws = 0; firsts.clear(); counts.clear(); }

        /** Queues a range of faces. */
        void queue(int first, int count) {
            // Right after the last range, as the next column's faces often are: one longer range.
            if (!SPLIT_RANGES && draws > 0 && firsts.get(draws - 1) + counts.get(draws - 1) == first
                    && (!INDEXED || counts.get(draws - 1) + count <= INDEX_QUADS)) {
                counts.put(draws - 1, counts.get(draws - 1) + count);
                return;
            }
            // An indexed range reaches only as far as the quad indices do.
            while (INDEXED && count > INDEX_QUADS) {
                queue(first, INDEX_QUADS);
                first += INDEX_QUADS;
                count -= INDEX_QUADS;
            }
            if (draws == firsts.capacity()) {
                firsts = MemoryUtil.memRealloc(firsts, draws * 2).clear();
                counts = MemoryUtil.memRealloc(counts, draws * 2).clear();
            }
            firsts.put(draws, first);
            counts.put(draws, count);
            draws++;
        }

        /** Draws the queued ranges in one multi-draw, with the far program and this arena's faces bound. */
        void draw() {
            if (drawCounts.capacity() < draws) {
                drawCounts = MemoryUtil.memRealloc(drawCounts, firsts.capacity());
                drawStarts = MemoryUtil.memRealloc(drawStarts, firsts.capacity());
                offsets = MemoryUtil.memRealloc(offsets, firsts.capacity());
            }
            drawCounts.clear().limit(draws);
            drawStarts.clear().limit(draws);
            for (int i = 0; i < draws; i++) {
                drawCounts.put(i, counts.get(i) * 6);
                // Indexed, a range's corners count from its first face's (gl_VertexID adds the base); else its six per face.
                drawStarts.put(i, INDEXED ? firsts.get(i) * 4 : firsts.get(i) * 6);
            }
            if (INDEXED) {
                offsets.clear().limit(draws);
                for (int i = 0; i < draws; i++) offsets.put(i, 0L);
                GL32C.glMultiDrawElementsBaseVertex(GL11C.GL_TRIANGLES, drawCounts, GL11C.GL_UNSIGNED_INT, offsets, drawStarts);
            }
            else GL14C.glMultiDrawArrays(GL11C.GL_TRIANGLES, drawStarts, drawCounts);
        }

        void close() {
            buffer.close();
            MemoryUtil.memFree(firsts);
            MemoryUtil.memFree(counts);
            MemoryUtil.memFree(drawCounts);
            MemoryUtil.memFree(drawStarts);
            MemoryUtil.memFree(offsets);
        }
    }

    /** Only loaded with Iris: keeps Iris's record of bound sampler objects right. */
    private static final class IrisSamplers {
        static void unbind(int unit) {
            if (ShaderBlocks.IRIS) net.irisshaders.iris.gl.IrisRenderSystem.bindSamplerToUnit(unit, 0);
        }
    }
}
