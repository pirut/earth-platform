package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.nio.FloatBuffer;
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
public final class FarBlockDepth {
    private static int texture, framebuffer, width, height, format;
    /** The fixed-point range of the depth last copied (see {@link #steps}). */
    private static float fixed;
    /** Whether it copies into 32-bit float depth by {@code glCopyTexSubImage2D}, after a blit failed for a depth format it could not match. */
    private static boolean copying;
    /** Neither way works on this GL: far terrain draws as before. */
    private static boolean failed;

    private FarBlockDepth() {}

    public static int texture() { return texture; }

    /**
     * Copies the depth of the framebuffer being drawn into, at that framebuffer's own size (see {@link #boundSize}); false
     * when there is none it can copy, and far terrain then draws as before.
     */
    public static boolean copy() {
        int[] size = boundSize();
        return size != null && copy(size[0], size[1]);
    }

    /**
     * The size of the framebuffer being drawn into, read from what it actually holds rather than from the game's main
     * target, which a mod may draw the world away from: null unless its colour and its depth are the same size and
     * neither is multisampled (a multisampled depth is resolved by a blit, not copied, and far terrain tests itself
     * against it by hand), which {@link FarPlainDepth} and the copy then leave alone.
     */
    static int[] boundSize() {
        if (GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING) == 0) return null;
        if (GL11C.glGetInteger(GL13C.GL_SAMPLES) > 1) return null;
        int[] depth = attachmentSize(GL30C.GL_DEPTH_ATTACHMENT);
        if (depth == null) return null;
        int draw = GL11C.glGetInteger(GL20C.GL_DRAW_BUFFER0);
        if (draw >= GL30C.GL_COLOR_ATTACHMENT0 && draw <= GL30C.GL_COLOR_ATTACHMENT0 + 15) {
            int[] colour = attachmentSize(draw);
            if (colour == null || colour[0] != depth[0] || colour[1] != depth[1]) return null;
        }
        return depth;
    }

    /** The size of a texture or renderbuffer attached to the framebuffer being drawn into, or null for any other kind or none. */
    static int[] attachmentSize(int attachment) {
        int target = GL30C.GL_DRAW_FRAMEBUFFER;
        int type = GL30C.glGetFramebufferAttachmentParameteri(target, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int name = GL30C.glGetFramebufferAttachmentParameteri(target, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int[] size = null;
        if (type == GL11C.GL_TEXTURE && name != 0) {
            int level = GL30C.glGetFramebufferAttachmentParameteri(target, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
            int active = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
            int bound = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            while (GL11C.glGetError() != GL11C.GL_NO_ERROR) {}
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, name);
            // A texture of another kind (an array, a cube map, a multisampled one) refuses the bind.
            if (GL11C.glGetError() == GL11C.GL_NO_ERROR)
                size = new int[] {GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, level, GL11C.GL_TEXTURE_WIDTH),
                        GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, level, GL11C.GL_TEXTURE_HEIGHT)};
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bound);
            GlStateManager._activeTexture(active);
        } else if (type == GL30C.GL_RENDERBUFFER && name != 0) {
            int bound = GL11C.glGetInteger(GL30C.GL_RENDERBUFFER_BINDING);
            GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, name);
            size = new int[] {GL30C.glGetRenderbufferParameteri(GL30C.GL_RENDERBUFFER, GL30C.GL_RENDERBUFFER_WIDTH),
                    GL30C.glGetRenderbufferParameteri(GL30C.GL_RENDERBUFFER, GL30C.GL_RENDERBUFFER_HEIGHT)};
            GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, bound);
        }
        return size != null && size[0] > 0 && size[1] > 0 ? size : null;
    }

    /**
     * The same for a framebuffer of this size; false unless the one being drawn into is that size (see {@link #boundSize}),
     * and false, with the copy let go of, when the driver rejects the copy: the depth is never left from an earlier frame.
     */
    static boolean copy(int targetWidth, int targetHeight) {
        if (failed) return false;
        RenderSystem.assertOnRenderThread();
        int drawn = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (drawn == 0) return false;
        int[] bound = boundSize();
        if (bound == null || bound[0] != targetWidth || bound[1] != targetHeight) return false;
        int source = sourceFormat();
        if (source == 0) return false;
        int wanted = copying ? GL30C.GL_DEPTH_COMPONENT32F : source;
        fixed = fixedRange(source);
        boolean fresh = texture == 0 || targetWidth != width || targetHeight != height || wanted != format;
        if (fresh) allocate(targetWidth, targetHeight, wanted);
        // Only this copy's own errors count, whether the allocation is new or reused.
        while (GL11C.glGetError() != GL11C.GL_NO_ERROR) {}
        int read = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        // Some drivers leave depth alone in a blit while depth writes are off, and a scissor clips it.
        boolean mask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK), scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        GL11C.glDepthMask(true);
        if (scissor) GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, drawn);
        if (copying) {
            int active = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
            int boundTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            GL11C.glCopyTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, boundTexture);
            GlStateManager._activeTexture(active);
        } else {
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11C.GL_DEPTH_BUFFER_BIT, GL11C.GL_NEAREST);
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawn);
        }
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, read);
        if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
        GL11C.glDepthMask(mask);
        if (GL11C.glGetError() == GL11C.GL_NO_ERROR) return true;
        if (!fresh) {
            // The driver rejected a copy into what it had: whatever the texture holds is an earlier frame's. Start from a new
            // allocation next time, and draw this pass as before.
            width = height = 0;
            return false;
        }
        if (!copying) {
            copying = true;
            EarthPlatformMod.LOGGER.info("The game's depth does not blit into a matching format; far terrain copies it instead");
            return copy(targetWidth, targetHeight);
        }
        failed = true;
        EarthPlatformMod.LOGGER.warn("Could not copy the game's depth; far terrain may cover nearby blocks while they load");
        return false;
    }

    /** The game's depth buffer's range as fixed point, or 0 for a floating-point one: what a depth is stored as (see {@link FarLodShaders#FAR_AS_GAME}). */
    static float steps() { return fixed; }

    /** The most a fixed-point depth of this format counts to ({@code 2^bits - 1}); 0 for a float format. */
    static float fixedRange(int sized) {
        return switch (sized) {
            case GL14C.GL_DEPTH_COMPONENT16 -> 65535F;
            case GL14C.GL_DEPTH_COMPONENT24, GL30C.GL_DEPTH24_STENCIL8 -> 16777215F;
            case GL14C.GL_DEPTH_COMPONENT32 -> 4294967295F;
            default -> 0F;
        };
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
