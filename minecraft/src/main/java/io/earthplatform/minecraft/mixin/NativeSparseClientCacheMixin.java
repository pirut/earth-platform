package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.fixture.NativeSparseBinding;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ClientChunkCache.class)
abstract class NativeSparseClientCacheMixin implements io.earthplatform.minecraft.fixture.NativeSparseCacheDiagnostics {
    @Shadow @Final private net.minecraft.world.level.chunk.LevelChunk emptyChunk;
    @Override public int earthplatform$emptySectionSlots() { return ((io.earthplatform.minecraft.residency.SparseFacadeDiagnostics)emptyChunk).earthplatform$legacySectionSlots(); }
    @Override public int earthplatform$emptyPostprocessingSlots() { return ((io.earthplatform.minecraft.residency.SparseFacadeDiagnostics)emptyChunk).earthplatform$legacyPostprocessingSlots(); }
    @Shadow @Final private ClientLevel level;
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("HEAD"), cancellable = true)
    private void earthplatform$get(int x,int z,ChunkStatus status,boolean create,CallbackInfoReturnable<LevelChunk> result) {
        var binding=NativeSparseBinding.find(level);if(binding!=null)result.setReturnValue(binding.facade(x,z,status));
    }
    @Inject(method = "replaceWithPacketData(IILnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/nbt/CompoundTag;Ljava/util/function/Consumer;)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("HEAD"))
    private void earthplatform$rejectColumnPacket(CallbackInfoReturnable<?> result) {
        var binding=NativeSparseBinding.find(level);if(binding!=null)binding.reject(result.getId());
    }
    @Inject(method = {"drop(Lnet/minecraft/world/level/ChunkPos;)V", "tick(Ljava/util/function/BooleanSupplier;Z)V",
            "onLightUpdate(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/SectionPos;)V",
            "replaceBiomes(IILnet/minecraft/network/FriendlyByteBuf;)V"}, at = @At("HEAD"))
    private void earthplatform$rejectColumnLifecycle(CallbackInfo result) {
        var binding=NativeSparseBinding.find(level);if(binding!=null)binding.reject(result.getId());
    }
    @Inject(method = "getLightEngine()Lnet/minecraft/world/level/lighting/LevelLightEngine;", at = @At("HEAD"))
    private void earthplatform$rejectLightAccess(CallbackInfoReturnable<?> result) {
        var binding=NativeSparseBinding.find(level);if(binding!=null)binding.reject("column light engine access");
    }

}
