package io.earthplatform.minecraft.cubic.client;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.gl.uniform.DynamicUniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Hands {@link FarLod}'s depth and projection to Iris's programs under Voxy's names. Only loaded with Iris. */
public final class FarLodIris {
    private FarLodIris() {}

    public static void addSamplers(SamplerHolder samplers) {
        if (!FarLod.voxyDefined()) return;
        // Far water is drawn with the pack's own water, so the translucent layer's depth is the opaque one.
        samplers.addDynamicSampler(TextureType.TEXTURE_2D, FarLod::depthTexture, null, "vxDepthTexOpaque", "vxDepthTexTrans");
    }

    public static void addUniforms(DynamicUniformHolder uniforms) {
        if (!FarLod.voxyDefined()) return;
        uniforms.uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxProj", () -> current(FarLod.projection()))
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxProjInv", () -> current(FarLod.projectionInverse()))
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxProjPrev", () -> current(FarLod.previousProjection()))
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "vxRenderDistance", FarLod::renderDistance)
                // The rest of what Voxy gives a pack's Voxy code: the view, and the view and far projection together.
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxModelView", () -> views().modelView)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxModelViewInv", () -> views().modelViewInverse)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxModelViewPrev", () -> views().previousModelView)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxViewProj", () -> views().viewProjection)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxViewProjInv", () -> views().viewProjectionInverse)
                .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxViewProjPrev", () -> views().previousViewProjection);
    }

    /** This frame's view matrices for Voxy's uniforms, and last frame's, worked out once a frame. */
    private static final class Views {
        final Matrix4f modelView = new Matrix4f(), modelViewInverse = new Matrix4f(), previousModelView = new Matrix4f();
        final Matrix4f viewProjection = new Matrix4f(), viewProjectionInverse = new Matrix4f(), previousViewProjection = new Matrix4f();
        int frame = Integer.MIN_VALUE;
        boolean seen;
    }

    private static final Views VIEWS = new Views();

    /**
     * The game's view as Iris captured it ({@code gbufferModelView}, which far terrain is drawn
     * with too), with far terrain's projection ({@code vxProj}); "previous" ones are last frame's.
     */
    private static Views views() {
        int frame = net.irisshaders.iris.uniforms.SystemTimeUniforms.COUNTER.getAsInt();
        Views views = VIEWS;
        if (frame != views.frame) {
            views.frame = frame;
            Matrix4fc projection = current(FarLod.projection());
            Matrix4fc view = CapturedRenderingState.INSTANCE.getGbufferModelView();
            if (views.seen) {
                views.previousModelView.set(views.modelView);
                views.previousViewProjection.set(views.viewProjection);
            }
            if (view != null) views.modelView.set(view);
            views.modelView.invert(views.modelViewInverse);
            projection.mul(views.modelView, views.viewProjection);
            views.viewProjection.invert(views.viewProjectionInverse);
            if (!views.seen) {
                views.previousModelView.set(views.modelView);
                views.previousViewProjection.set(views.viewProjection);
                views.seen = true;
            }
        }
        return views;
    }

    private static Matrix4fc current(Matrix4fc matrix) {
        FarLod.projectIfNeeded(CapturedRenderingState.INSTANCE.getGbufferProjection());
        return matrix;
    }

    static void unbindSampler(int unit) {
        IrisRenderSystem.bindSamplerToUnit(unit, 0);
    }

    /** The pack's tilt of the sun's path, in degrees. */
    static float sunPathRotation() {
        var pipeline = Iris.getPipelineManager().getPipelineNullable();
        return pipeline == null ? 0.0F : pipeline.getSunPathRotation();
    }
}
