package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A mob, item or arrow in a cube not loaded, or standing on one, holds still until it loads, as vanilla holds
 * entities at the edge of the loaded chunks: unloaded cubes read as void air, and it would fall through them.
 * A firework rocket is never held: it burns out on its own clock, and a held one never would. A rocket held
 * in the air, as one left behind by a climb that outruns the cubes, stayed in the world forever while the
 * client, which can't hold it, flew it on.
 */
@Mixin(ServerLevel.class)
abstract class CubicEntityHoldMixin {
    @Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void earthplatform$hold(Entity entity, CallbackInfo info) {
        if (entity instanceof Player || entity instanceof FireworkRocketEntity || !(CubicLevel.of((ServerLevel) (Object) this) instanceof CubeWorld cubes)) return;
        int x = entity.getBlockX(), y = entity.getBlockY(), z = entity.getBlockZ();
        if (!CubeWorld.inRange(y) || cubes.cubeAtBlock(x, y, z) != null && cubes.cubeAtBlock(x, y - 1, z) != null) return;
        for (Entity passenger : entity.getIndirectPassengers()) if (passenger instanceof Player) return;
        info.cancel();
    }
}
