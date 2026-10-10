package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.fixture.NativeSparseBinding;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ClientLevel.class)
abstract class NativeSparseClientLevelMixin {
    @Inject(method = {"tick(Ljava/util/function/BooleanSupplier;)V", "onChunkLoaded(Lnet/minecraft/world/level/ChunkPos;)V",
            "unload(Lnet/minecraft/world/level/chunk/LevelChunk;)V"}, at = @At("HEAD"))
    private void earthplatform$rejectLifecycle(CallbackInfo result) {
        var binding=NativeSparseBinding.find((ClientLevel)(Object)this);if(binding!=null)binding.reject(result.getId());
    }
}
