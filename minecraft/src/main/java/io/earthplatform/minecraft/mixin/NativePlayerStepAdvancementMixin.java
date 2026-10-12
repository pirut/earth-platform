package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(PlayerAdvancements.class)
abstract class NativePlayerStepAdvancementMixin {
 @Shadow private ServerPlayer player;
 @Inject(method="award(Lnet/minecraft/advancements/AdvancementHolder;Ljava/lang/String;)Z",at=@At("HEAD"),require=1)
 private void earthplatform$stepAward(AdvancementHolder holder,String criterion,CallbackInfoReturnable<Boolean> ci){
  NativePlayerStepReadScope.beforeAward((PlayerAdvancements)(Object)this,player,holder,criterion);
 }
}
