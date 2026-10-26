package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.nio.FloatBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * The game's depth as its own blocks left it, before any far terrain draws: far terrain inside
 * the blocks' reach reads it to draw only where they drew nothing (see {@link FarShaders#GROUND}).
 * It is copied from whatever framebuffer the game, or a shader pack, draws its terrain into, only
 * on frames that need it. Render thread only.
 */
final class FarBlockDepth {
    private static int texture, framebuffer, width, height, format;
    /** Whether it copies into 32-bit float depth by {@code glCopyTexSubImage2D}, after a blit failed for a depth format it could not match. */
    private static boolean copying;
    /** Neither way works on this GL: far terrain draws as before. */
    private static boolean failed;

    private FarBlockDepth() {}

    static int texture() { return texture; }

    /** Copies the bound framebuffer's depth; false when there is none it can copy, and far terrain then draws as before. */
    static boolean copy() {
        if (failed) return false;
        RenderSystem.assertOnRenderThread();
        int drawn = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (drawn == 0) return false;
        int wanted = copying ? GL30C.GL_DEPTH_COMPONENT32F : sourceFormat();
        if (wanted == 0) return false;
        var target = Minecraft.getInstance().getMainRenderTarget();
        boolean fresh = texture == 0 || target.width != width || target.height != height || wanted != format;
        if (fresh) {
            allocate(target.width, target.height, wanted);
            while (GL11C.glGetError() != GL11C.GL_NO_ERROR) {}
        }
        int read = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        // Some drivers leave depth alone in a blit while depth writes are off, and a scissor clips it.
        boolean mask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK), scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        GL11C.glDepthMask(true);
        if (scissor) GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, drawn);
        if (copying) {
            int active = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
            int bound = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            GL11C.glCopyTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bound);
            GlStateManager._activeTexture(active);
        } else {
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11C.GL_DEPTH_BUFFER_BIT, GL11C.GL_NEAREST);
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawn);
        }
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, read);
        if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
        GL11C.glDepthMask(mask);
        if (!fresh || GL11C.glGetError() == GL11C.GL_NO_ERROR) return true;
        if (!copying) {
            copying = true;
            EarthPlatformMod.LOGGER.info("The game's depth does not blit into a matching format; far terrain copies it instead");
            return copy();
        }
        failed = true;
        EarthPlatformMod.LOGGER.warn("Could not copy the game's depth; far terrain may cover nearby blocks while they load");
        return false;
    }

    /** The sized format of the bound framebuffer's depth, which a blit needs to match, or 0 for one it does not know. */
    private static int sourceFormat() {
        int target = GL30C.GL_DRAW_FRAMEBUFFER, attachment = GL30C.GL_DEPTH_ATTACHMENT;
        if (GL30C.glGetFramebufferAttachmentParameteri(target, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) == GL11C.GL_NONE) return 0;
        int depth = GL30C.glGetFramebufferAttachmentParameteri(target, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
        int stencil = GL30C.glGetFramebufferAttachmentParameteri(target, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE);
        int type = GL30C.glGetFramebufferAttachmentParameteri(target, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE);
        if (type == GL11C.GL_FLOAT && depth == 32) return stencil == 8 ? GL30C.GL_DEPTH32F_STENCIL8 : stencil == 0 ? GL30C.GL_DEPTH_COMPONENT32F : 0;
        if (type != GL30C.GL_UNSIGNED_NORMALIZED) return 0;
        if (depth == 24) return stencil == 8 ? GL30C.GL_DEPTH24_STENCIL8 : stencil == 0 ? GL14C.GL_DEPTH_COMPONENT24 : 0;
        if (stencil != 0) return 0;
        return depth == 16 ? GL14C.GL_DEPTH_COMPONENT16 : depth == 32 ? GL14C.GL_DEPTH_COMPONENT32 : 0;
    }

    private static void allocate(int newWidth, int newHeight, int newFormat) {
        width = newWidth;
        height = newHeight;
        format = newFormat;
        if (texture == 0) texture = GL11C.glGenTextures();
        if (framebuffer == 0) framebuffer = GL30C.glGenFramebuffers();
        boolean stencil = format == GL30C.GL_DEPTH24_STENCIL8 || format == GL30C.GL_DEPTH32F_STENCIL8;
        int pixels = stencil ? GL30C.GL_DEPTH_STENCIL : GL11C.GL_DEPTH_COMPONENT;
        int type = format == GL30C.GL_DEPTH24_STENCIL8 ? GL30C.GL_UNSIGNED_INT_24_8
                : format == GL30C.GL_DEPTH32F_STENCIL8 ? GL30C.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL11C.GL_FLOAT;
        int active = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        int bound = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, format, width, height, 0, pixels, type, (FloatBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL13C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL13C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bound);
        GlStateManager._activeTexture(active);
        int drawn = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, texture, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_STENCIL_ATTACHMENT, GL11C.GL_TEXTURE_2D, stencil ? texture : 0, 0);
        GL20C.glDrawBuffers(GL11C.GL_NONE);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawn);
    }
}
