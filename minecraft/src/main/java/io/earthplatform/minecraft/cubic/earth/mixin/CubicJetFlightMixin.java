package io.earthplatform.minecraft.cubic.earth.mixin;

import io.earthplatform.minecraft.cubic.jet.JetStream;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Elytra flight in the cubic Earth's jet stream, and the slow-down after leaving it. */
@Mixin(LivingEntity.class)
abstract class CubicJetFlightMixin {
    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void earthplatform$jet(Vec3 input, CallbackInfo info) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!JetStream.riding(self) || !self.isControlledByLocalInstance()) return;
        self.setDeltaMovement(JetStream.carry(self.getDeltaMovement(), self.getLookAngle(), self.getY()));
        self.move(MoverType.SELF, self.getDeltaMovement());
        self.calculateEntityAnimation(false);
        info.cancel();
    }

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("TAIL"))
    private void earthplatform$thickAir(Vec3 input, CallbackInfo info) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.isFallFlying() && JetStream.inCubic(self)) self.setDeltaMovement(JetStream.slow(self.getDeltaMovement()));
    }
}
