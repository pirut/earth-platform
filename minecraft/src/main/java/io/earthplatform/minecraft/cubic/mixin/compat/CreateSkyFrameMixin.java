package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.compat.CreateSkyCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Raise the visible clouds while keeping the client storm thresholds relative to the deck. */
@Pseudo
@Mixin(targets = "com.createskys.client.SkyFrame", remap = false)
abstract class CreateSkyFrameMixin {
    @ModifyExpressionValue(method = "compute", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lcom/createskys/config/SkySettings;cloudHeight:I"), require = 0)
    private int earthplatform$earthDeck(int height, @Local(argsOnly = true) ClientLevel level) {
        return height + CreateSkyCompat.cloudOffset(level);
    }

    @WrapOperation(method = "compute", at = @At(value = "INVOKE",
            target = "Lcom/createskys/weather/Storms;setClientClouds(DD)V"), require = 0)
    private void earthplatform$relativeStormDeck(double base, double top, Operation<Void> original,
                                                  @Local(argsOnly = true) ClientLevel level) {
        int offset = CreateSkyCompat.cloudOffset(level);
        original.call(base - offset, top - offset);
    }
}
