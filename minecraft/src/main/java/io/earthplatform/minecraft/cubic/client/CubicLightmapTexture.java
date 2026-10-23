package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.minecraft.cubic.diagnostics.PerfFailures;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL33C;

/**
 * The game's 16x16 lightmap, uploaded in turn to three textures instead of always to one.
 *
 * <p>The game rewrites the lightmap every tick. Written over while earlier frames still draw with it,
 * the one texture makes some drivers wait for the GPU to finish those frames first: on Apple's OpenGL
 * the upload measured about 17 ms of a 21 ms frame. Writing the next of three textures instead leaves
 * the ones in use alone. Every frame still draws with exactly the pixels the game last computed, with
 * the same sampling state; only the texture's name changes. With a shader pack in use, in a world that
 * isn't cubic or after anything goes wrong, it goes back to the one original texture.
 * -Dearthplatform.cubic.lightmapRing=false always uploads to the one texture, as before build 61.
 */
public final class CubicLightmapTexture extends DynamicTexture {
    public static final boolean ENABLED = !"false".equals(System.getProperty("earthplatform.cubic.lightmapRing"));
    private static final int[] INT_PARAMETERS = {GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_TEXTURE_MAG_FILTER,
            GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_TEXTURE_BASE_LEVEL, GL12C.GL_TEXTURE_MAX_LEVEL,
            GL14C.GL_TEXTURE_COMPARE_MODE, GL14C.GL_TEXTURE_COMPARE_FUNC, GL33C.GL_TEXTURE_SWIZZLE_R,
            GL33C.GL_TEXTURE_SWIZZLE_G, GL33C.GL_TEXTURE_SWIZZLE_B, GL33C.GL_TEXTURE_SWIZZLE_A};
    private static final int[] FLOAT_PARAMETERS = {GL12C.GL_TEXTURE_MIN_LOD, GL12C.GL_TEXTURE_MAX_LOD, GL14C.GL_TEXTURE_LOD_BIAS};
    // These are set after DynamicTexture's constructor, which already calls getId().
    private final LightmapRing ring = new LightmapRing();
    private final IntSupplier allocate = this::allocate;
    private final IntConsumer discard = this::discard;
    private int[] intParameters;
    private float[] floatParameters;
    private float[] border;
    private int storageFormat;
    private float anisotropy = Float.NaN;
    private boolean uploading;
    private boolean failed;

    public CubicLightmapTexture(int width, int height, boolean clear) {
        super(width, height, clear);
    }

    private boolean eligible() {
        NativeImage pixels = getPixels();
        return !failed && pixels != null && pixels.getWidth() == 16 && pixels.getHeight() == 16
                && pixels.format() == NativeImage.Format.RGBA && ClientCubeWorld.current() != null
                && !ShaderBlocks.packInUse() && GL.getCapabilities().OpenGL33;
    }

    /** Reads the original texture's storage and sampling state, for the extras to copy. */
    private void captureParameters(int original) {
        GlStateManager._bindTexture(original);
        if (GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH) != 16
                || GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT) != 16
                || GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_BASE_LEVEL) != 0
                || GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL) != 0) {
            throw new IllegalStateException("Unsupported lightmap storage");
        }
        storageFormat = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
        anisotropy = GL.getCapabilities().GL_EXT_texture_filter_anisotropic
                ? GL11C.glGetTexParameterf(GL11C.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT)
                : Float.NaN;
        intParameters = new int[INT_PARAMETERS.length];
        for (int i = 0; i < intParameters.length; i++) {
            intParameters[i] = GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, INT_PARAMETERS[i]);
        }
        floatParameters = new float[FLOAT_PARAMETERS.length];
        for (int i = 0; i < floatParameters.length; i++) {
            floatParameters[i] = GL11C.glGetTexParameterf(GL11C.GL_TEXTURE_2D, FLOAT_PARAMETERS[i]);
        }
        border = new float[4];
        GL11C.glGetTexParameterfv(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_BORDER_COLOR, border);
    }

    /** A texture with the original's storage and sampling state. */
    private int allocate() {
        int texture = TextureUtil.generateTextureId();
        if (texture == 0 || texture == NOT_ASSIGNED) throw new IllegalStateException("Lightmap texture allocation failed");
        try {
            TextureUtil.prepareImage(texture, 16, 16);
            if (GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH) != 16
                    || GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT) != 16
                    || GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT) != storageFormat) {
                throw new IllegalStateException("Lightmap backing storage unavailable");
            }
            if (!Float.isNaN(anisotropy)) {
                GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, anisotropy);
            }
            for (int i = 0; i < intParameters.length; i++) {
                GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, INT_PARAMETERS[i], intParameters[i]);
            }
            for (int i = 0; i < floatParameters.length; i++) {
                GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, FLOAT_PARAMETERS[i], floatParameters[i]);
            }
            GL11C.glTexParameterfv(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_BORDER_COLOR, border);
            return texture;
        } catch (RuntimeException failure) {
            try {
                TextureUtil.releaseTextureId(texture);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void discard(int texture) {
        TextureUtil.releaseTextureId(texture);
    }

    /** Goes back to the original texture and frees the extras; with synchronize, uploads the current pixels to it. */
    private void restore(boolean synchronize) {
        if (!ring.active()) return;
        int original = ring.original(id);
        if (ring.owns(RenderSystem.getShaderTexture(2))) RenderSystem.setShaderTexture(2, original);
        id = original;
        try {
            ring.release(discard);
        } finally {
            intParameters = null;
            floatParameters = null;
            border = null;
        }
        if (synchronize && getPixels() != null) super.upload();
    }

    @Override
    public void upload() {
        uploading = true;
        try {
            if (eligible()) {
                try {
                    int current = super.getId();
                    if (!ring.active()) captureParameters(current);
                    id = ring.advance(current, allocate, discard);
                    // Near terrain samples the lightmap slot, far terrain reads it from there too.
                    if (RenderSystem.getShaderTexture(2) == current) RenderSystem.setShaderTexture(2, id);
                } catch (RuntimeException failure) {
                    failed = true;
                    PerfFailures.record(PerfFailures.Stage.LIGHTMAP_TEXTURE, failure);
                    restore(false);
                }
            } else {
                restore(false);
            }
            super.upload();
        } finally {
            uploading = false;
        }
    }

    @Override
    public int getId() {
        // A pack or world change can come on a frame without a lightmap update.
        if (ring != null && ring.active() && !uploading && !eligible()) restore(true);
        return super.getId();
    }

    @Override
    public void releaseId() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(this::releaseId);
            return;
        }
        try {
            restore(false);
        } finally {
            super.releaseId();
        }
    }
}
