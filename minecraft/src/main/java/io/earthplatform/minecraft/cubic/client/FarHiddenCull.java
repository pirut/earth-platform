package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.GlStateManager;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import javax.annotation.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Leaves out of far terrain's shading pass the ranges of faces the depth pre-pass has shown to lie
 * wholly behind other far terrain. The shading pass tests depth EQUAL against the faces the
 * pre-pass kept, so such a range shades no pixel, but every one of its faces still went through
 * the vertex shader and the rasterizer: at Yosemite under Photon, three faces in five. After the
 * pre-pass, the faces' depth is folded into a pyramid of the farthest depth in each square of
 * pixels, a compute pass tests each range's box against it, and the shading pass draws each range
 * through an indirect command whose instance count that test set to 0 or 1. A range is left out
 * only when its nearest point lies farther than every pixel its box covers, with room for the
 * faces reaching past their columns' edges, the pack's jitter and rounding, so what is drawn is
 * exactly what was. Needs OpenGL 4.3; off with {@code -Dearthplatform.far.hiddenCull=false}.
 * Render thread only.
 */
final class FarHiddenCull {
    static final boolean ENABLED = !"false".equals(System.getProperty("earthplatform.far.hiddenCull"));
    /** Window-depth margin a range must lie past the pyramid by: some sixteen float steps near 1. */
    private static final float DEPTH_MARGIN = 1.0e-6F;

    private static final String REDUCE = """
            #version 430 core
            layout(local_size_x = 8, local_size_y = 8) in;
            uniform sampler2D earth_From;
            uniform int earth_FromLevel;
            uniform ivec2 earth_FromSize;
            uniform ivec2 earth_ToSize;
            layout(r32f) uniform writeonly image2D earth_To;

            void main() {
                ivec2 to = ivec2(gl_GlobalInvocationID.xy);
                if (to.x >= earth_ToSize.x || to.y >= earth_ToSize.y) return;
                ivec2 from = to * 2;
                // The last texel of a row or column also takes the odd one left over below it.
                ivec2 last = ivec2(to.x == earth_ToSize.x - 1 ? earth_FromSize.x - 1 : min(from.x + 1, earth_FromSize.x - 1),
                                   to.y == earth_ToSize.y - 1 ? earth_FromSize.y - 1 : min(from.y + 1, earth_FromSize.y - 1));
                float farthest = 0.0;
                for (int y = from.y; y <= last.y; y++)
                    for (int x = from.x; x <= last.x; x++)
                        farthest = max(farthest, texelFetch(earth_From, ivec2(x, y), earth_FromLevel).r);
                imageStore(earth_To, to, vec4(farthest));
            }
            """;

    private static final String CULL = """
            #version 430 core
            layout(local_size_x = 64) in;
            layout(std430, binding = 0) readonly buffer EarthBoxes { vec4 earth_boxes[]; };
            layout(std430, binding = 1) buffer EarthCommands { uint earth_commands[]; };
            uniform mat4 earth_ViewProjection;
            uniform vec2 earth_Screen;
            uniform int earth_Ranges;
            uniform int earth_Levels;
            uniform float earth_Grow;
            uniform float earth_Margin;
            uniform sampler2D earth_Pyramid;

            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= uint(earth_Ranges)) return;
                vec3 low = earth_boxes[i * 2u].xyz, high = earth_boxes[i * 2u + 1u].xyz;
                // Faces reach past their columns' edges by a share of their distance (see FarGpu#grow).
                float grow = earth_Grow * 1.5 * length(max(abs(low), abs(high))) + 1.0;
                low -= grow;
                high += grow;
                bool shown = false;
                vec2 from = vec2(1e30), to = vec2(-1e30);
                float nearest = 1e30;
                for (int corner = 0; corner < 8; corner++) {
                    vec4 clip = earth_ViewProjection * vec4((corner & 1) != 0 ? high.x : low.x, (corner & 2) != 0 ? high.y : low.y,
                            (corner & 4) != 0 ? high.z : low.z, 1.0);
                    // Up to or behind the camera's plane its pixels can't be bounded: drawn.
                    if (clip.w <= 1.0) { shown = true; break; }
                    vec3 ndc = clip.xyz / clip.w;
                    from = min(from, ndc.xy);
                    to = max(to, ndc.xy);
                    nearest = min(nearest, ndc.z);
                }
                if (!shown) {
                    // In pixels, with two to spare for the pack's jitter and rounding.
                    vec2 a = (from * 0.5 + 0.5) * earth_Screen - 2.0, b = (to * 0.5 + 0.5) * earth_Screen + 2.0;
                    if (b.x < 0.0 || b.y < 0.0 || a.x >= earth_Screen.x || a.y >= earth_Screen.y) shown = true;
                    else {
                        a = clamp(a, vec2(0.0), earth_Screen - 1.0);
                        b = clamp(b, vec2(0.0), earth_Screen - 1.0);
                        // The level whose texels (2^(level + 1) pixels) the box spans at most two of each way.
                        float span = max(b.x - a.x, b.y - a.y);
                        int level = clamp(int(ceil(log2(max(span, 1.0)))) - 1, 0, earth_Levels - 1);
                        ivec2 size = textureSize(earth_Pyramid, level);
                        ivec2 t0 = min(ivec2(a) >> (level + 1), size - 1), t1 = min(ivec2(b) >> (level + 1), size - 1);
                        float farthest = 0.0;
                        for (int y = t0.y; y <= t1.y; y++)
                            for (int x = t0.x; x <= t1.x; x++)
                                farthest = max(farthest, texelFetch(earth_Pyramid, ivec2(x, y), level).r);
                        shown = nearest * 0.5 + 0.5 <= farthest + earth_Margin;
                    }
                }
                earth_commands[i * 5u + 1u] = shown ? 1u : 0u;
            }
            """;

    private int reduce, cull, pyramid, boxes, commands;
    private int width, height, levels;
    private boolean failed;
    /** The recording and version whose ranges the buffers hold, and how many. */
    @Nullable private FarGpu.Recording uploaded;
    private int uploadedVersion = -1, ranges;
    /** Whether {@link #cull} ran for the recording now uploaded, this frame, so the shading pass may draw from it. */
    private boolean ready;

    /** True where this GL can do it: OpenGL 4.3, for compute, storage buffers and indirect multi-draws. */
    static boolean supported() { return GL.getCapabilities().OpenGL43; }

    /**
     * After the depth pre-pass: tests every range of the pre-pass's recording against the faces'
     * depth (a full-size R32F texture), seen through {@code viewProjection}. False when it can't,
     * and the shading pass then draws every range.
     */
    boolean cull(FarGpu.Recording recording, int faces, int faceWidth, int faceHeight, Matrix4f viewProjection, float grow) {
        ready = false;
        if (failed || recording.rangeCount() == 0 || faceWidth < 2 || faceHeight < 2) return false;
        if (!programs()) return false;
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int active = GlStateManager._getActiveTexture();
        Bindings bindings = Bindings.save();
        try {
            if (width != faceWidth || height != faceHeight) resize(faceWidth, faceHeight);
            if (uploaded != recording || uploadedVersion != recording.version()) upload(recording);
            build(faces);
            test(viewProjection, grow);
            ready = true;
            return true;
        } finally {
            bindings.restore();
            GL20C.glUseProgram(program);
            GlStateManager._activeTexture(active);
        }
    }

    /** The indirect commands for the recording {@link #cull} last ran for this frame, or 0 when there are none. */
    int commandsFor(FarGpu.Recording recording) {
        return ready && uploaded == recording && uploadedVersion == recording.version() ? commands : 0;
    }

    /** Ends this frame's culling: the next shading pass draws every range unless {@link #cull} runs again. */
    void done() { ready = false; }

    private void upload(FarGpu.Recording recording) {
        ranges = recording.rangeCount();
        ByteBuffer boxData = MemoryUtil.memAlloc(ranges * 32), commandData = MemoryUtil.memAlloc(ranges * 20);
        try {
            recording.writeRanges(boxData.asFloatBuffer(), commandData.asIntBuffer());
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, boxes);
            GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, boxData, GL15C.GL_DYNAMIC_DRAW);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, commands);
            GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, commandData, GL15C.GL_DYNAMIC_DRAW);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(boxData);
            MemoryUtil.memFree(commandData);
        }
        uploaded = recording;
        uploadedVersion = recording.version();
    }

    /** Folds the faces' depth into the pyramid: level 0 is half size, each level half the last. */
    private void build(int faces) {
        GL20C.glUseProgram(reduce);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + Bindings.TEXTURE_UNIT);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(reduce, "earth_From"), Bindings.TEXTURE_UNIT);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(reduce, "earth_To"), Bindings.IMAGE_UNIT);
        int fromLevel = GL20C.glGetUniformLocation(reduce, "earth_FromLevel"), fromSize = GL20C.glGetUniformLocation(reduce, "earth_FromSize"),
                toSize = GL20C.glGetUniformLocation(reduce, "earth_ToSize");
        int fromWidth = width, fromHeight = height;
        for (int level = 0; level < levels; level++) {
            int toWidth = Math.max(1, fromWidth / 2), toHeight = Math.max(1, fromHeight / 2);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, level == 0 ? faces : pyramid);
            GL20C.glUniform1i(fromLevel, level == 0 ? 0 : level - 1);
            GL20C.glUniform2i(fromSize, fromWidth, fromHeight);
            GL20C.glUniform2i(toSize, toWidth, toHeight);
            GL42C.glBindImageTexture(Bindings.IMAGE_UNIT, pyramid, level, false, 0, GL15C.GL_WRITE_ONLY, GL30C.GL_R32F);
            GL43C.glDispatchCompute((toWidth + 7) / 8, (toHeight + 7) / 8, 1);
            GL42C.glMemoryBarrier(GL42C.GL_TEXTURE_FETCH_BARRIER_BIT | GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            fromWidth = toWidth;
            fromHeight = toHeight;
        }
    }

    private void test(Matrix4f viewProjection, float grow) {
        GL20C.glUseProgram(cull);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + Bindings.TEXTURE_UNIT);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, pyramid);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(cull, "earth_Pyramid"), Bindings.TEXTURE_UNIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrix = stack.mallocFloat(16);
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(cull, "earth_ViewProjection"), false, viewProjection.get(matrix));
        }
        GL20C.glUniform2f(GL20C.glGetUniformLocation(cull, "earth_Screen"), width, height);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(cull, "earth_Ranges"), ranges);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(cull, "earth_Levels"), levels);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(cull, "earth_Grow"), grow);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(cull, "earth_Margin"), DEPTH_MARGIN);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, Bindings.boxBinding(), boxes);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, Bindings.commandBinding(), commands);
        GL43C.glShaderStorageBlockBinding(cull, GL43C.glGetProgramResourceIndex(cull, GL43C.GL_SHADER_STORAGE_BLOCK, "EarthBoxes"), Bindings.boxBinding());
        GL43C.glShaderStorageBlockBinding(cull, GL43C.glGetProgramResourceIndex(cull, GL43C.GL_SHADER_STORAGE_BLOCK, "EarthCommands"), Bindings.commandBinding());
        GL43C.glDispatchCompute((ranges + 63) / 64, 1, 1);
        GL42C.glMemoryBarrier(GL42C.GL_COMMAND_BARRIER_BIT);
    }

    private void resize(int newWidth, int newHeight) {
        width = newWidth;
        height = newHeight;
        if (pyramid != 0) GL11C.glDeleteTextures(pyramid);
        int baseWidth = Math.max(1, width / 2), baseHeight = Math.max(1, height / 2);
        levels = 1;
        for (int w = baseWidth, h = baseHeight; w > 1 || h > 1; w = Math.max(1, w / 2), h = Math.max(1, h / 2)) levels++;
        pyramid = GL11C.glGenTextures();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + Bindings.TEXTURE_UNIT);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, pyramid);
        GL42C.glTexStorage2D(GL11C.GL_TEXTURE_2D, levels, GL30C.GL_R32F, baseWidth, baseHeight);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST_MIPMAP_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
    }

    private boolean programs() {
        if (reduce != 0) return true;
        try {
            reduce = compute(REDUCE, "far depth pyramid");
            cull = compute(CULL, "far hidden ranges");
            boxes = GL15C.glGenBuffers();
            commands = GL15C.glGenBuffers();
            return true;
        } catch (IllegalStateException failure) {
            failed = true;
            if (reduce != 0) { GL20C.glDeleteProgram(reduce); reduce = 0; }
            EarthPlatformMod.LOGGER.warn("Far terrain can't leave hidden faces out of its shading pass; it shades them all", failure);
            return false;
        }
    }

    private static int compute(String source, String name) {
        int shader = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IllegalStateException("Could not compile " + name + ": " + log);
        }
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, shader);
        GL20C.glLinkProgram(program);
        GL20C.glDetachShader(program, shader);
        GL20C.glDeleteShader(shader);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetProgramInfoLog(program);
            GL20C.glDeleteProgram(program);
            throw new IllegalStateException("Could not link " + name + ": " + log);
        }
        return program;
    }

    void close() {
        if (reduce != 0) GL20C.glDeleteProgram(reduce);
        if (cull != 0) GL20C.glDeleteProgram(cull);
        if (pyramid != 0) GL11C.glDeleteTextures(pyramid);
        if (boxes != 0) GL15C.glDeleteBuffers(boxes);
        if (commands != 0) GL15C.glDeleteBuffers(commands);
        reduce = cull = pyramid = boxes = commands = 0;
    }

    /**
     * The texture unit, image unit and storage buffer bindings the passes here use, put back as
     * they were afterwards: a shader pack's programs keep theirs bound across draws. The last of
     * each kind the GL offers, which packs are least likely to use.
     */
    private static final class Bindings {
        static final int TEXTURE_UNIT = 31;
        static int IMAGE_UNIT;
        private static int buffers = -1;
        private final int texture, image, imageLevel, imageLayer, imageAccess, imageFormat;
        private final boolean imageLayered;
        private final int[] buffer = new int[2];
        private final long[] start = new long[2], size = new long[2];

        static int boxBinding() { return buffers - 2; }

        static int commandBinding() { return buffers - 1; }

        private Bindings() {
            if (buffers < 0) {
                buffers = GL11C.glGetInteger(GL43C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
                IMAGE_UNIT = GL11C.glGetInteger(GL42C.GL_MAX_IMAGE_UNITS) - 1;
            }
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + TEXTURE_UNIT);
            texture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            image = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_NAME, IMAGE_UNIT);
            imageLevel = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LEVEL, IMAGE_UNIT);
            imageLayered = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYERED, IMAGE_UNIT) != 0;
            imageLayer = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYER, IMAGE_UNIT);
            imageAccess = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_ACCESS, IMAGE_UNIT);
            imageFormat = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_FORMAT, IMAGE_UNIT);
            for (int i = 0; i < 2; i++) {
                int binding = buffers - 2 + i;
                buffer[i] = GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, binding);
                start[i] = GL40C.glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_START, binding);
                size[i] = GL40C.glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_SIZE, binding);
            }
        }

        static Bindings save() { return new Bindings(); }

        void restore() {
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + TEXTURE_UNIT);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            GL42C.glBindImageTexture(IMAGE_UNIT, image, imageLevel, imageLayered, imageLayer,
                    imageAccess == 0 ? GL15C.GL_READ_ONLY : imageAccess, imageFormat == 0 ? GL30C.GL_R32F : imageFormat);
            for (int i = 0; i < 2; i++) {
                int binding = buffers - 2 + i;
                if (buffer[i] != 0 && size[i] > 0) GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer[i], start[i], size[i]);
                else GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer[i]);
            }
        }
    }
}
