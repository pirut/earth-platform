package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EmptyLevelChunk.class)
abstract class SurvivalEmptyLevelChunkMixin {
    @Inject(method="getLightEmission(Lnet/minecraft/core/BlockPos;)I",at=@At("HEAD"),cancellable=true)
    private void earthplatform$survivalEmission(BlockPos pos,CallbackInfoReturnable<Integer> result) {
        var binding=SurvivalLevelBinding.find(((LevelChunk)(Object)this).getLevel());
        if(binding!=null) result.setReturnValue(binding.get(pos).getLightEmission(binding.view(),pos));
    }
    @Inject(method={"isYSpaceEmpty(II)Z","isSectionEmpty(I)Z","getNoiseBiome(III)Lnet/minecraft/core/Holder;"},at=@At("HEAD"))
    private void earthplatform$survivalUnsupported(CallbackInfoReturnable<?> result) {
        SurvivalLevelBinding.rejectTerrain(((LevelChunk)(Object)this).getLevel(),result.getId());
    }
}
