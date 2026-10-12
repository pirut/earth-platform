package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.gametest.NativePlayerTickProbeScope;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Diagnostic only: replace exactly the legacy probe in the native no-argument fluid update. */
@Mixin(Entity.class)
abstract class NativePlayerTickProbeFluidMixin {
    @Redirect(method="updateFluidHeightAndDoFluidPushing()V", at=@At(value="INVOKE",
            target="Lnet/minecraft/world/entity/Entity;touchingUnloadedChunk()Z"), require=1)
    private boolean earthplatform$diagnosticFluidFootprint(Entity source) {
        return NativePlayerTickProbeScope.fluidResidency(source);
    }
}
