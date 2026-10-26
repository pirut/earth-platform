package io.earthplatform.minecraft.cubic.mixin;

import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * A gliding player is turned by the angle between where they look and where they move, an arc
 * cosine of the two directions' dot product. Flying straight, as the jet stream carries a glider
 * along its look, rounding can put that product a hair over 1; the arc cosine is then NaN and
 * the whole player drops out of that frame. Replays of glides blinked this way.
 */
@Mixin(PlayerRenderer.class)
abstract class GliderTurnMixin {
    @ModifyArg(method = "setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;acos(D)D"))
    private double earthplatform$inRange(double cosine) {
        return Mth.clamp(cosine, -1.0, 1.0);
    }
}
