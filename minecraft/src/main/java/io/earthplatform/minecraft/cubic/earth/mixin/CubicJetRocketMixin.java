package io.earthplatform.minecraft.cubic.earth.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.jet.JetStream;
import javax.annotation.Nullable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Rockets in the cubic world climb fast enough to reach the jet stream, and don't hold a rider back inside it. */
@Mixin(FireworkRocketEntity.class)
abstract class CubicJetRocketMixin {
    @Shadow @Nullable private LivingEntity attachedToEntity;

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void earthplatform$climb(LivingEntity rider, Vec3 boosted, Operation<Void> original) {
        if (rider != attachedToEntity || !JetStream.inCubic(rider)) { original.call(rider, boosted); return; }
        original.call(rider, JetStream.rocket(rider.getDeltaMovement(), rider.getLookAngle(), rider.getY()));
    }
}
