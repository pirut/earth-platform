package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.fixture.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
abstract class NativeSparseLevelMixin implements NativeSparseLevelAccess {
    @Unique private NativeSparseBinding earthplatform$nativeBinding;
    @Override public NativeSparseBinding earthplatform$nativeSparseBinding() { return earthplatform$nativeBinding; }
    @Inject(method = "<init>(Lnet/minecraft/world/level/storage/WritableLevelData;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/core/Holder;Ljava/util/function/Supplier;ZZJI)V", at = @At("RETURN"))
    private void earthplatform$claim(CallbackInfo result) {
        earthplatform$nativeBinding = SparseFixtureConstruction.claim((Level) (Object) this);
    }
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
    private void earthplatform$set(BlockPos position, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> result) {
        NativeSparseBinding binding = earthplatform$nativeBinding;
        if (binding != null) {
            binding.checkOpen();
            if (((Level) (Object) this).isClientSide) binding.reject("client mutations require an authoritative admitted snapshot");
            if (flags != 0 && flags != 16) binding.reject("neighbor/notification mutation flags; fixture data-only writes require 0 or 16");
            result.setReturnValue(binding.facade(position.getX() >> 4, position.getZ() >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL)
                    .setBlockState(position, state, false) != null);
        }
    }
    @Inject(method = "isLoaded(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void earthplatform$isLoaded(BlockPos position, CallbackInfoReturnable<Boolean> result) {
        if (earthplatform$nativeBinding != null) result.setReturnValue(earthplatform$nativeBinding.isLoaded(position));
    }
    @Inject(method = {"getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
            "getLightEngine()Lnet/minecraft/world/level/lighting/LevelLightEngine;"}, at = @At("HEAD"))
    private void earthplatform$unsupportedRead(CallbackInfoReturnable<?> result) {
        if (earthplatform$nativeBinding != null) earthplatform$nativeBinding.reject(result.getId());
    }
}
