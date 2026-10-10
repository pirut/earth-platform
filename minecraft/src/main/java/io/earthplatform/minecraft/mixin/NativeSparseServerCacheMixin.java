package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.fixture.NativeSparseBinding;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ServerChunkCache.class)
abstract class NativeSparseServerCacheMixin {
    @Shadow @Final public ServerLevel level;
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("HEAD"), cancellable = true)
    private void earthplatform$get(int x,int z,ChunkStatus status,boolean create,CallbackInfoReturnable<ChunkAccess> result) {
        var binding=NativeSparseBinding.find(level);if(binding!=null)result.setReturnValue(binding.facade(x,z,status));
    }
    @Inject(method = "getChunkNow(II)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("HEAD"), cancellable = true)
    private void earthplatform$getNow(int x,int z,CallbackInfoReturnable<LevelChunk> result) {
        var binding=NativeSparseBinding.find(level);if(binding!=null)result.setReturnValue(binding.facade(x,z,ChunkStatus.FULL));
    }
    @Inject(method = "hasChunk(II)Z", at = @At("HEAD"), cancellable = true)
    private void earthplatform$has(int x,int z,CallbackInfoReturnable<Boolean> result) {
        var binding=NativeSparseBinding.find(level);if(binding!=null)result.setReturnValue(binding.hasAnyResident(x,z));
    }
    @Inject(method = {"getChunkForLighting(II)Lnet/minecraft/world/level/chunk/LightChunk;",
            "getChunkFuture(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;",
            "getChunkFutureMainThread(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;"}, at = @At("HEAD"))
    private void earthplatform$rejectScheduling(CallbackInfoReturnable<?> result) {
        var binding=NativeSparseBinding.find(level);if(binding!=null)binding.reject(result.getId());
    }
    @Inject(method = "save(Z)V", at = @At("HEAD"), cancellable = true)
    private void earthplatform$save(boolean flush,CallbackInfo result) {
        var binding=NativeSparseBinding.find(level);if(binding!=null) {
            if(binding.isClosing())result.cancel();else binding.reject("column save; fixture has no persistence implementation");
        }
    }
    @Inject(method = {"tick(Ljava/util/function/BooleanSupplier;Z)V",
            "addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V",
            "addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;Z)V",
            "removeRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V",
            "removeRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;Z)V",
            "updateChunkForced(Lnet/minecraft/world/level/ChunkPos;Z)V", "move(Lnet/minecraft/server/level/ServerPlayer;)V"}, at = @At("HEAD"))
    private void earthplatform$rejectColumnLifecycle(CallbackInfo result) {
        var binding=NativeSparseBinding.find(level);if(binding!=null)binding.reject(result.getId());
    }
    @Inject(method = "getLightEngine()Lnet/minecraft/server/level/ThreadedLevelLightEngine;", at = @At("HEAD"))
    private void earthplatform$rejectLightAccess(CallbackInfoReturnable<?> result) {
        var binding=NativeSparseBinding.find(level);if(binding!=null)binding.reject("column light engine access");
    }

}
