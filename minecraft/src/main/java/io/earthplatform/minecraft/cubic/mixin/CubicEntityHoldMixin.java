package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A mob, item or arrow in a cube not loaded, or standing on one, holds still until it loads, as vanilla holds
 * entities at the edge of the loaded chunks: unloaded cubes read as void air, and it would fall through them.
 */
@Mixin(ServerLevel.class)
abstract class CubicEntityHoldMixin {
    @Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void earthplatform$hold(Entity entity, CallbackInfo info) {
        if (entity instanceof Player || !(CubicLevel.of((ServerLevel) (Object) this) instanceof CubeWorld cubes)) return;
        int x = entity.getBlockX(), y = entity.getBlockY(), z = entity.getBlockZ();
        if (!CubeWorld.inRange(y) || cubes.cubeAtBlock(x, y, z) != null && cubes.cubeAtBlock(x, y - 1, z) != null) return;
        for (Entity passenger : entity.getIndirectPassengers()) if (passenger instanceof Player) return;
        info.cancel();
    }
}
