package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.enginebridge.NativeCollisionLevelAccess;
import io.earthplatform.minecraft.enginebridge.NativeCollisionMotionFence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An invalidated experimental collision frame cannot move a walking player through unknown data. */
@Mixin(Entity.class)
abstract class CoreCollisionMotionMixin {
    @Inject(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void earthplatform$motion(MoverType type, Vec3 motion, CallbackInfo result) {
        Entity entity = (Entity)(Object)this;
        if (entity instanceof Player player && !player.isSpectator()
                && ((NativeCollisionLevelAccess)entity.level()).earthplatform$coreCollisionView() instanceof NativeCollisionMotionFence fence
                && (fence.blocksPlayerMovement() || !NativeCollisionMotionFence.bounded(entity.getBoundingBox(), motion))) {
            entity.setDeltaMovement(Vec3.ZERO);
            entity.resetFallDistance();
            result.cancel();
        }
    }
}
