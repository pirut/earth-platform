package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A free rocket gains 15% sideways speed a tick until the server explodes it, so one that outlives its flight
 * time (the client flies a rocket on until the server says it's gone) soon sweeps millions of blocks for
 * collisions a tick and stops the game. In the cubic world a free rocket flies at most a cube a tick; a real
 * rocket peaks near 2 blocks a tick, so only a runaway one is slowed.
 */
@Mixin(FireworkRocketEntity.class)
abstract class CubicFireworkSpeedMixin {
    @Unique private static final double MAX_SPEED = 16;

    @Shadow private boolean isAttachedToEntity() { throw new AssertionError(); }

    @Inject(method = "tick", at = @At("HEAD"))
    private void earthplatform$capSpeed(CallbackInfo info) {
        FireworkRocketEntity rocket = (FireworkRocketEntity) (Object) this;
        if (isAttachedToEntity() || CubicLevel.of(rocket.level()) == null) return;
        Vec3 motion = rocket.getDeltaMovement();
        double speed = motion.length();
        if (!Double.isFinite(speed)) rocket.setDeltaMovement(Vec3.ZERO);
        else if (speed > MAX_SPEED) rocket.setDeltaMovement(motion.scale(MAX_SPEED / speed));
    }
}
