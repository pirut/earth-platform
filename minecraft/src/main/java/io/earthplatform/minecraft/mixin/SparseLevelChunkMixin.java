package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.residency.SparseFacadeHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Both classes declare these overrides; EmptyLevelChunk must not silently return air/null. */
@Mixin({LevelChunk.class, EmptyLevelChunk.class})
abstract class SparseLevelChunkMixin {
    @Inject(method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"), cancellable = true)
    private void earthplatform$get(BlockPos position, CallbackInfoReturnable<BlockState> result) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        var storage = SparseFacadeHooks.storage(chunk.getLevel());
        if (storage != null) result.setReturnValue(storage.get(chunk.getPos(), position));
    }
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"), cancellable = true)
    private void earthplatform$set(BlockPos position, BlockState state, boolean moving, CallbackInfoReturnable<BlockState> result) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        var storage = SparseFacadeHooks.storage(chunk.getLevel());
        if (storage != null) result.setReturnValue(storage.set(chunk.getPos(), position, state, moving));
    }
    @Inject(method = "getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;", at = @At("HEAD"), cancellable = true)
    private void earthplatform$fluid(BlockPos position, CallbackInfoReturnable<FluidState> result) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        var storage = SparseFacadeHooks.storage(chunk.getLevel());
        if (storage != null) result.setReturnValue(storage.get(chunk.getPos(), position).getFluidState());
    }
    @Inject(method = {"isEmpty()Z", "getBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;"}, at = @At("HEAD"))
    private void earthplatform$rejectUnsupportedRead(CallbackInfoReturnable<?> result) {
        SparseFacadeHooks.rejectLegacy(((LevelChunk) (Object) this).getLevel(), result.getId());
    }
    @Inject(method = {"setBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            "addAndRegisterBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            "removeBlockEntity(Lnet/minecraft/core/BlockPos;)V"}, at = @At("HEAD"))
    private void earthplatform$rejectUnsupportedWrite(CallbackInfo result) {
        SparseFacadeHooks.rejectLegacy(((LevelChunk) (Object) this).getLevel(), result.getId());
    }
}
