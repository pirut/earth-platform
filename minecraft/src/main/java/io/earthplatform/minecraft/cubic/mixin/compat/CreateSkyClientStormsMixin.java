package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.earthplatform.minecraft.cubic.compat.CreateSkyCompat;
import net.minecraft.client.Minecraft;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Use the same relative altitude for the client's rain and thunder fades. */
@Pseudo
@Mixin(targets = "com.createskys.weather.Storms", remap = false)
abstract class CreateSkyClientStormsMixin {
    @ModifyExpressionValue(method = "atViewHeight", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lcom/createskys/weather/Storms;viewY:D"), require = 0)
    private static double earthplatform$relativeViewHeight(double y) {
        return y - CreateSkyCompat.cloudOffset(Minecraft.getInstance().level);
    }
}
