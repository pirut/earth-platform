package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.fixture.NativeSparseBinding;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ServerLevel.class)
abstract class NativeSparseServerLevelMixin {
    @Inject(method = {"tick(Ljava/util/function/BooleanSupplier;)V", "save(Lnet/minecraft/util/ProgressListener;ZZ)V"}, at = @At("HEAD"))
    private void earthplatform$rejectSimulation(CallbackInfo result) {
        var binding=NativeSparseBinding.find((ServerLevel)(Object)this);if(binding!=null)binding.reject(result.getId());
    }
    @Inject(method = {"getBlockTicks()Lnet/minecraft/world/ticks/LevelTicks;", "getFluidTicks()Lnet/minecraft/world/ticks/LevelTicks;"}, at = @At("HEAD"))
    private void earthplatform$rejectTicks(CallbackInfoReturnable<?> result) {
        var binding=NativeSparseBinding.find((ServerLevel)(Object)this);if(binding!=null)binding.reject(result.getId());
    }
}
