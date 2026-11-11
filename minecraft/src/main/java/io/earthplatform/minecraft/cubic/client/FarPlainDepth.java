package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.nio.FloatBuffer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

/**
 * Plain Minecraft's far opaque pass in 32-bit depth with an eight-block near plane. Comparing
 * far faces in the game's 0.05-block projection collapses whole metres to the same depth at
 * kilometres, making neighbouring steps fight in stripes. Colour goes straight into the game's
 * target; only after visibility is resolved is depth converted back for clouds and translucency.
 * Shader packs continue through FarLod. Render thread only; one instance per far renderer.
 */
final class FarPlainDepth {
    private int texture, framebuffer, vao, merge, width, height, drawnFrom;
    private boolean failed;
    private final Matrix4f game = new Matrix4f(), far = new Matrix4f();

    static Matrix4f projection(Matrix4f game) {
        float near = 8, reach = FarRenderer.DEPTH_FAR;
        return new Matrix4f(game).m22((reach + near) / (near - reach)).m32(2 * reach * near / (near - reach));
    }

    Matrix4f gameProjection() { return game; }
    int texture() { return texture; }
    Matrix4f projection() { return far; }

    boolean begin(Matrix4f projection) {
        var target = Minecraft.getInstance().getMainRenderTarget();
        return begin(projection, target.width, target.height);
    }

    boolean begin(Matrix4f projection, int targetWidth, int targetHeight) {
        if (failed || !Boolean.parseBoolean(System.getProperty("earthplatform.far.plainDepth", "true"))) return false;
        RenderSystem.assertOnRenderThread();
        drawnFrom = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (drawnFrom == 0) return false;
        int attachment = GL11C.glGetInteger(GL20C.GL_DRAW_BUFFER0);
        if (attachment < GL30C.GL_COLOR_ATTACHMENT0 || attachment > GL30C.GL_COLOR_ATTACHMENT0 + 15) return false;
        if (GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER, attachment,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL11C.GL_TEXTURE) return false;
        int colour = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER, attachment,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (!FarBlockDepth.copy(targetWidth, targetHeight)) return false;
        game.set(projection); far.set(projection(projection));
        int active = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        int bound = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            if (merge == 0) merge = GlShaders.link(FarLodShaders.FULL_SCREEN, FarLodShaders.MERGE, "plain far depth merge");
            if (texture == 0) texture = GL11C.glGenTextures();
            if (framebuffer == 0) framebuffer = GL30C.glGenFramebuffers();
            if (width != targetWidth || height != targetHeight) {
                width = targetWidth; height = targetHeight;
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_DEPTH_COMPONENT32F, width, height, 0,
                        GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, (FloatBuffer) null);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
            }
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colour, 0);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, texture, 0);
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Unsupported plain far framebuffer");
            boolean mask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK), scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
            GL11C.glDepthMask(true);
            if (scissor) GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[] {1});
            if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDepthMask(mask);
            return true;
        } catch (IllegalStateException error) {
            failed = true;
            if (GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING) == framebuffer)
                GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawnFrom);
            EarthPlatformMod.LOGGER.warn("Precise plain far depth unavailable; using the game's depth", error);
            return false;
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bound);
            GlStateManager._activeTexture(active);
        }
    }

    void end() {
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM), active = GlStateManager._getActiveTexture();
        int heldVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING), depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        boolean depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST), cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        boolean mask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        boolean[] colours = new boolean[4];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bits = stack.malloc(4); GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, bits);
            for (int i = 0; i < 4; i++) colours[i] = bits.get(i) != 0;
        }
        int[] textures = new int[2];
        for (int i = 0; i < 2; i++) {
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + i);
            textures[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, i == 0 ? FarBlockDepth.texture() : texture);
        }
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawnFrom);
        try {
            GL11C.glColorMask(false, false, false, false);
            GL11C.glEnable(GL11C.GL_DEPTH_TEST); GL11C.glDepthFunc(GL11C.GL_ALWAYS); GL11C.glDepthMask(true);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GlStateManager._glUseProgram(merge);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(merge, "earth_Game"), 0);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(merge, "earth_Far"), 1);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var matrix = stack.mallocFloat(16);
                GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(merge, "earth_FarInverse"), false, new Matrix4f(far).invert().get(matrix));
                GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(merge, "earth_GameProjection"), false, game.get(matrix));
            }
            if (vao == 0) vao = GL30C.glGenVertexArrays();
            GL30C.glBindVertexArray(vao);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        } finally {
            for (int i = 0; i < 2; i++) {
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + i);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[i]);
            }
            GlStateManager._activeTexture(active);
            GL30C.glBindVertexArray(heldVao); GlStateManager._glUseProgram(program);
            GL11C.glColorMask(colours[0], colours[1], colours[2], colours[3]);
            GL11C.glDepthFunc(depthFunc); GL11C.glDepthMask(mask);
            if (depthTest) GL11C.glEnable(GL11C.GL_DEPTH_TEST); else GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            if (cull) GL11C.glEnable(GL11C.GL_CULL_FACE); else GL11C.glDisable(GL11C.GL_CULL_FACE);
            // The colour belongs to the game; detach it before a resize/reload can delete it.
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawnFrom);
        }
    }

    void close() {
        if (texture != 0) GL11C.glDeleteTextures(texture);
        if (framebuffer != 0) GL30C.glDeleteFramebuffers(framebuffer);
        if (vao != 0) GL30C.glDeleteVertexArrays(vao);
        if (merge != 0) GL20C.glDeleteProgram(merge);
        texture = framebuffer = vao = merge = width = height = 0;
    }
}
