package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.minecraft.diagnostics.PerfFailures;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL33C;

/** Experimental: retains vanilla pixels and uploads, rotating only their backing texture names. */
public final class CubicLightmapTexture extends DynamicTexture {
    public static final boolean ENABLED = Boolean.getBoolean("earthplatform.cubic.lightmapRing");
    private static final int[] INT_PARAMETERS = {GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_TEXTURE_MAG_FILTER,
            GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_TEXTURE_BASE_LEVEL, GL12C.GL_TEXTURE_MAX_LEVEL,
            GL14C.GL_TEXTURE_COMPARE_MODE, GL14C.GL_TEXTURE_COMPARE_FUNC, GL33C.GL_TEXTURE_SWIZZLE_R,
            GL33C.GL_TEXTURE_SWIZZLE_G, GL33C.GL_TEXTURE_SWIZZLE_B, GL33C.GL_TEXTURE_SWIZZLE_A};
    private static final int[] FLOAT_PARAMETERS = {GL12C.GL_TEXTURE_MIN_LOD, GL12C.GL_TEXTURE_MAX_LOD, GL14C.GL_TEXTURE_LOD_BIAS};
    // These initialize after DynamicTexture's constructor, which calls virtual getId().
    private final LightmapRing ring = new LightmapRing();
    private final IntSupplier allocate = this::allocate;
    private final IntConsumer discard = this::discard;
    private int[] intParameters;
    private float[] floatParameters, border;
    private boolean uploading, failed;
    private long allocations, releases, rotations, restores, uploads;

    public CubicLightmapTexture(int width, int height, boolean clear) { super(width, height, clear); }

    private boolean eligible() {
        NativeImage pixels = getPixels();
        return ENABLED && !failed && pixels != null && pixels.getWidth() == 16 && pixels.getHeight() == 16
                && pixels.format() == NativeImage.Format.RGBA && ClientCubeWorld.current() != null
                && !ShaderBlocks.packInUse() && GL.getCapabilities().OpenGL33;
    }

    private void captureParameters(int original) {
        GlStateManager._bindTexture(original);
        intParameters = new int[INT_PARAMETERS.length];
        for (int i = 0; i < intParameters.length; i++)
            intParameters[i] = GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, INT_PARAMETERS[i]);
        floatParameters = new float[FLOAT_PARAMETERS.length];
        for (int i = 0; i < floatParameters.length; i++)
            floatParameters[i] = GL11C.glGetTexParameterf(GL11C.GL_TEXTURE_2D, FLOAT_PARAMETERS[i]);
        border = new float[4];
        GL11C.glGetTexParameterfv(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_BORDER_COLOR, border);
    }

    private int allocate() {
        int texture = TextureUtil.generateTextureId();
        if (texture <= 0) throw new IllegalStateException("Lightmap texture allocation failed");
        try {
            TextureUtil.prepareImage(texture, 16, 16);
            for (int i = 0; i < intParameters.length; i++)
                GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, INT_PARAMETERS[i], intParameters[i]);
            for (int i = 0; i < floatParameters.length; i++)
                GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, FLOAT_PARAMETERS[i], floatParameters[i]);
            GL11C.glTexParameterfv(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_BORDER_COLOR, border);
            allocations++;
            return texture;
        } catch (RuntimeException failure) {
            TextureUtil.releaseTextureId(texture);
            throw failure;
        }
    }

    private void discard(int texture) { TextureUtil.releaseTextureId(texture); releases++; }

    private void restore(boolean synchronizePixels) {
        if (!ring.active()) return;
        id = ring.original(id);
        try { ring.release(discard); }
        finally { intParameters = null; floatParameters = null; border = null; restores++; }
        if (synchronizePixels && getPixels() != null) { super.upload(); uploads++; }
    }

    @Override public void upload() {
        uploading = true;
        try {
            if (eligible()) {
                try {
                    int current = super.getId();
                    if (!ring.active()) captureParameters(current);
                    id = ring.advance(current, allocate, discard);
                    rotations++;
                } catch (RuntimeException failure) {
                    failed = true;
                    PerfFailures.record(PerfFailures.Stage.LIGHTMAP_TEXTURE, failure);
                    restore(false);
                }
            } else restore(false);
            super.upload();
            uploads++;
        } finally { uploading = false; }
    }

    @Override public int getId() {
        // Also handle a shader/world change on a frame with no vanilla lightmap update.
        if (ring != null && ring.active() && !uploading && !eligible()) restore(true);
        return super.getId();
    }

    @Override public void releaseId() {
        if (!RenderSystem.isOnRenderThread()) { RenderSystem.recordRenderCall(this::releaseId); return; }
        try { restore(false); } finally { super.releaseId(); }
    }

    record State(boolean active, int current, int[] ids, long allocations, long releases,
                 long rotations, long restores, long uploads, boolean failed) {}
    State state() { return new State(ring.active(), id, ring.snapshot(), allocations, releases, rotations, restores, uploads, failed); }
}
