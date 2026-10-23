package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Living entities hold still until the cubes they stand in and on are present, except gliders far above any terrain. */
@Mixin(LivingEntity.class)
abstract class CubicTravelMixin {
    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void earthplatform$waitForCubes(Vec3 input, CallbackInfo info) {
        LivingEntity self = (LivingEntity) (Object) this;
        int x = self.getBlockX(), y = self.getBlockY(), z = self.getBlockZ();
        CubeWorld cubes = CubicLevel.at(self.level(), x, z);
        if (cubes == null || Cubic.inTransit(self)) return;
        if (cubes.cubeAtBlock(x, y, z) != null && cubes.cubeAtBlock(x, y - 2, z) != null) return;
        self.setDeltaMovement(Vec3.ZERO);
        info.cancel();
    }
}
