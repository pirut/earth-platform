package io.earthplatform.minecraft.cubic.mixin;

import com.google.common.collect.ImmutableSet;
import io.earthplatform.minecraft.cubic.client.FarLodIris;
import java.util.function.Supplier;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives every pack program far terrain's depth under Voxy's sampler names. Skipped when Iris is not installed. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.samplers.IrisSamplers", remap = false)
abstract class CubicIrisLodSamplersMixin {
    @Inject(method = "addRenderTargetSamplers", at = @At("TAIL"), require = 0, remap = false)
    private static void earthplatform$farDepth(SamplerHolder samplers, Supplier<ImmutableSet<Integer>> flipped, RenderTargets targets,
                                               boolean fullScreen, WorldRenderingPipeline pipeline, CallbackInfo info) {
        FarLodIris.addSamplers(samplers);
    }
}
