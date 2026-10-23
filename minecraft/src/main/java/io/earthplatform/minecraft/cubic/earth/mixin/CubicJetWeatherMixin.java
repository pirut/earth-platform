package io.earthplatform.minecraft.cubic.earth.mixin;

import io.github.pirut.chunks3.client.ClientCubeWorld;
import io.earthplatform.minecraft.cubic.jet.JetStream;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Weather stays below the jet stream: the rain, its gloom and thunder fade out as a player climbs above the clouds. */
@Mixin(Level.class)
abstract class CubicJetWeatherMixin {
    @Inject(method = {"getRainLevel", "getThunderLevel"}, at = @At("RETURN"), cancellable = true)
    private void earthplatform$clearAbove(float partialTick, CallbackInfoReturnable<Float> result) {
        if (!((Level) (Object) this).isClientSide || result.getReturnValueF() <= 0.0F || ClientCubeWorld.current() == null) return;
        var player = Minecraft.getInstance().player;
        if (player == null || player.level() != (Object) this) return;
        result.setReturnValue(result.getReturnValueF() * JetStream.weather(player.getY()));
    }
}
