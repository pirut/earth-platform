package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every declared EmptyLevelChunk override is gated; unknown native cells never become void air. */
@Mixin({LevelChunk.class, EmptyLevelChunk.class})
abstract class SurvivalLevelChunkMixin {
    @Inject(method="getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",at=@At("HEAD"),cancellable=true)
    private void earthplatform$survivalGet(BlockPos pos,CallbackInfoReturnable<BlockState> result) {
        var binding=SurvivalLevelBinding.find(((LevelChunk)(Object)this).getLevel());
        if(binding!=null) result.setReturnValue(binding.get(pos));
    }
    @Inject(method="getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;",at=@At("HEAD"),cancellable=true)
    private void earthplatform$survivalFluid(BlockPos pos,CallbackInfoReturnable<FluidState> result) {
        var binding=SurvivalLevelBinding.find(((LevelChunk)(Object)this).getLevel());
        if(binding!=null) result.setReturnValue(binding.get(pos).getFluidState());
    }
    @Inject(method="getBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;",at=@At("HEAD"),cancellable=true)
    private void earthplatform$survivalEntity(BlockPos pos,LevelChunk.EntityCreationType type,CallbackInfoReturnable<BlockEntity> result) {
        var binding=SurvivalLevelBinding.find(((LevelChunk)(Object)this).getLevel());
        if(binding!=null) result.setReturnValue(binding.view().getBlockEntity(pos));
    }
    @Inject(method="setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",at=@At("HEAD"),cancellable=true)
    private void earthplatform$survivalSet(BlockPos pos,BlockState state,boolean moving,CallbackInfoReturnable<BlockState> result) {
        var binding=SurvivalLevelBinding.find(((LevelChunk)(Object)this).getLevel());
        if(binding!=null) result.setReturnValue(binding.setChunk(pos,state,moving));
    }
    @Inject(method="isEmpty()Z",at=@At("HEAD"))
    private void earthplatform$survivalNotColumn(CallbackInfoReturnable<?> result) {
        SurvivalLevelBinding.rejectTerrain(((LevelChunk)(Object)this).getLevel(),result.getId());
    }
    @Inject(method={"setBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            "addAndRegisterBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            "removeBlockEntity(Lnet/minecraft/core/BlockPos;)V"},at=@At("HEAD"))
    private void earthplatform$survivalNoBlockEntity(CallbackInfo result) {
        SurvivalLevelBinding.rejectTerrain(((LevelChunk)(Object)this).getLevel(),result.getId());
    }
}
