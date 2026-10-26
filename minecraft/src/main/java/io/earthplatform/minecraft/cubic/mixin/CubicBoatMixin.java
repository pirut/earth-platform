package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.jet.OceanCurrent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boats on the cubic Earth's sea: the ocean current carries them, and the far terrain's sea holds one up
 * where its cube hasn't come yet (see {@link OceanCurrent}). A boat keeps ticking where no chunk is loaded,
 * as a player does, since one in the current leaves the loaded chunks behind (see {@link OceanCurrent#keepsTicking}).
 */
@Mixin(Boat.class)
abstract class CubicBoatMixin extends VehicleEntity implements OceanCurrent.Ride {
    @Shadow private float deltaRotation;
    @Unique private boolean earthplatform$riding;
    @Unique private double earthplatform$step;
    @Unique private long earthplatform$steppedAt;

    CubicBoatMixin(EntityType<?> type, Level level) { super(type, level); }

    @Inject(method = "floatBoat", at = @At("HEAD"), cancellable = true)
    private void earthplatform$atSea(CallbackInfo info) {
        Vec3 next = OceanCurrent.floatBoat((Boat) (Object) this);
        if (next == null) return;
        setDeltaMovement(next);
        deltaRotation *= (float) OceanCurrent.WATER;
        // Held at the sea's height, never on the ground below: no fall to break the boat when it lands.
        resetFallDistance();
        info.cancel();
    }

    @Override public boolean isAlwaysTicking() { return OceanCurrent.keepsTicking((Boat) (Object) this) || super.isAlwaysTicking(); }

    @Override public boolean earthplatform$riding() { return earthplatform$riding; }
    @Override public void earthplatform$setRiding(boolean riding) { earthplatform$riding = riding; }
    @Override public double earthplatform$step() { return earthplatform$step; }
    @Override public long earthplatform$steppedAt() { return earthplatform$steppedAt; }
    @Override public void earthplatform$stepped(double step, long time) { earthplatform$step = step; earthplatform$steppedAt = time; }
}
