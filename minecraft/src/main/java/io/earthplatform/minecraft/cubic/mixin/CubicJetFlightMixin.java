package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.jet.GliderTrack;
import io.earthplatform.minecraft.cubic.jet.JetStream;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Elytra flight in the cubic Earth's jet stream, and the slow-down after leaving it. */
@Mixin(LivingEntity.class)
abstract class CubicJetFlightMixin {
    @Unique private final GliderTrack earthplatform$track = new GliderTrack();

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void earthplatform$jet(Vec3 input, CallbackInfo info) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!JetStream.riding(self) || !self.isControlledByLocalInstance()) { earthplatform$track.reset(); return; }
        boolean server = !self.level().isClientSide;
        if (server) earthplatform$track.at(self.level().getGameTime(), self.getX(), self.getZ());
        self.setDeltaMovement(JetStream.carry(self.getDeltaMovement(), self.getLookAngle(), self.getY()));
        self.move(MoverType.SELF, self.getDeltaMovement());
        // Flying into a wall hurts as in vanilla elytra flight, by the speed lost: at jet speed nothing survives it.
        if (server && self.horizontalCollision) {
            float damage = earthplatform$track.crash();
            if (damage > 0.0F) {
                earthplatform$track.reset();
                self.playSound(damage > 4.0F ? SoundEvents.GENERIC_BIG_FALL : SoundEvents.GENERIC_SMALL_FALL, 1.0F, 1.0F);
                self.hurt(self.damageSources().flyIntoWall(), damage);
            }
        }
        self.calculateEntityAnimation(false);
        info.cancel();
    }

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("TAIL"))
    private void earthplatform$thickAir(Vec3 input, CallbackInfo info) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.isFallFlying() && JetStream.inCubic(self)) self.setDeltaMovement(JetStream.slow(self.getDeltaMovement()));
    }
}
