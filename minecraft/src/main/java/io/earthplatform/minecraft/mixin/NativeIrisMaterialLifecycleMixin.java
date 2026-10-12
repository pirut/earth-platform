package io.earthplatform.minecraft.mixin;
import io.earthplatform.compat.dh.InstalledIrisRuntime;
import io.earthplatform.compat.dh.shader.ShaderCarrierHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Revoke BEFORE mutations, including setter calls that retain a mutable map identity. */
@Mixin(targets="net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings", remap=false)
public abstract class NativeIrisMaterialLifecycleMixin {
    @Inject(method={"setBlockStateIds", "setBlockTypeIds", "setAmbientOcclusionLevel",
            "setDisableDirectionalShading", "setUseSeparateAo", "setVertexFormat", "setVoxelizeLightBlocks"},
            at=@At("HEAD"), require=7)
    private void earthplatform$beforeMaterialMutation(CallbackInfo callback) {
        InstalledIrisRuntime.invalidateLifecycle("Iris terrain material settings mutation");
        ShaderCarrierHooks.invalidateAdmitted("Iris terrain material settings mutation");
    }
}
