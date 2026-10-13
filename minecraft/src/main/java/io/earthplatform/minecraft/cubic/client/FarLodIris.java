package io.earthplatform.minecraft.cubic.client;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.gl.uniform.DynamicUniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
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
                .uniform1i(UniformUpdateFrequency.PER_FRAME, "vxRenderDistance", FarLod::renderDistance);
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
