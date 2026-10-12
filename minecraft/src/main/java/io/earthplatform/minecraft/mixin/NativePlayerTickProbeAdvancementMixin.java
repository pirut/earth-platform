package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.gametest.NativePlayerTickProbeScope;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native criterion mutation precedes its event; refuse here before progress/rewards/chat. */
@Mixin(PlayerAdvancements.class)
abstract class NativePlayerTickProbeAdvancementMixin {
    @Shadow private ServerPlayer player;
    @Inject(method="award(Lnet/minecraft/advancements/AdvancementHolder;Ljava/lang/String;)Z",at=@At("HEAD"),require=1)
    private void earthplatform$rejectDiagnosticAdvancement(net.minecraft.advancements.AdvancementHolder advancement,
            String criterion,CallbackInfoReturnable<Boolean> result) {
        NativePlayerTickProbeScope.rejectAdvancement((PlayerAdvancements)(Object)this,player,advancement,criterion);
    }
}
