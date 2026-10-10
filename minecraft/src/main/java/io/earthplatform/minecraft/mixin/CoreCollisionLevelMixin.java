package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.enginebridge.NativeCollisionLevelAccess;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only explicitly bound experimental Levels use the sparse collision getter. */
@Mixin(Level.class)
abstract class CoreCollisionLevelMixin implements NativeCollisionLevelAccess {
    @Unique private volatile BlockGetter earthplatform$coreCollision;
    @Override public BlockGetter earthplatform$coreCollisionView() { return earthplatform$coreCollision; }
    @Override public synchronized boolean earthplatform$replaceCoreCollisionView(BlockGetter expected, BlockGetter replacement) {
        if (earthplatform$coreCollision != expected) return false;
        earthplatform$coreCollision = replacement;
        return true;
    }
    @Inject(method = "getChunkForCollisions(II)Lnet/minecraft/world/level/BlockGetter;", at = @At("HEAD"), cancellable = true)
    private void earthplatform$collision(int chunkX, int chunkZ, CallbackInfoReturnable<BlockGetter> result) {
        BlockGetter getter = earthplatform$coreCollision;
        if (getter != null) result.setReturnValue(getter);
    }
}
