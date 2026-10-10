package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.residency.SparseFacadeHooks;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EmptyLevelChunk.class)
abstract class SparseEmptyChunkExtrasMixin {
    @Inject(method = {"isYSpaceEmpty(II)Z", "isSectionEmpty(I)Z",
            "getNoiseBiome(III)Lnet/minecraft/core/Holder;", "getLightEmission(Lnet/minecraft/core/BlockPos;)I"}, at = @At("HEAD"))
    private void earthplatform$rejectImplicitEmpty(CallbackInfoReturnable<?> result) {
        SparseFacadeHooks.rejectLegacy(((EmptyLevelChunk) (Object) this).getLevel(), result.getId());
    }
}
