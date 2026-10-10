package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.residency.SparseFacadeHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class SparseLevelChunkExtrasMixin {
    @Inject(method = "getFluidState(III)Lnet/minecraft/world/level/material/FluidState;", at = @At("HEAD"), cancellable = true)
    private void earthplatform$fluid(int x, int y, int z, CallbackInfoReturnable<FluidState> result) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        var storage = SparseFacadeHooks.storage(chunk.getLevel());
        if (storage != null) result.setReturnValue(storage.get(chunk.getPos(), new BlockPos(x, y, z)).getFluidState());
    }
    @Inject(method = {"getBlockTicks()Lnet/minecraft/world/ticks/TickContainerAccess;",
            "getFluidTicks()Lnet/minecraft/world/ticks/TickContainerAccess;",
            "getTicksForSerialization()Lnet/minecraft/world/level/chunk/ChunkAccess$TicksToSave;"}, at = @At("HEAD"))
    private void earthplatform$rejectTickAccess(CallbackInfoReturnable<?> result) {
        SparseFacadeHooks.rejectLegacy(((LevelChunk) (Object) this).getLevel(), result.getId());
    }
    @Inject(method = {"registerTickContainerInLevel(Lnet/minecraft/server/level/ServerLevel;)V",
            "unregisterTickContainerFromLevel(Lnet/minecraft/server/level/ServerLevel;)V",
            "replaceWithPacketData(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/nbt/CompoundTag;Ljava/util/function/Consumer;)V",
            "replaceBiomes(Lnet/minecraft/network/FriendlyByteBuf;)V"}, at = @At("HEAD"))
    private void earthplatform$rejectColumnInstallation(CallbackInfo result) {
        SparseFacadeHooks.rejectLegacy(((LevelChunk) (Object) this).getLevel(), result.getId());
    }
}
