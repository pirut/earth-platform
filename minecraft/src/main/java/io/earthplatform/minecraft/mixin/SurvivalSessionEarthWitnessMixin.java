package io.earthplatform.minecraft.mixin;
import java.util.function.Consumer;
import io.earthplatform.minecraft.survival.persistence.NativeSessionCallbackWitness;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
/** Bind exact original static method-reference arguments, never reconstruct a lambda as evidence. */
@Mixin(targets="io.earthplatform.minecraft.EarthPlatformMod",remap=false)
abstract class SurvivalSessionEarthWitnessMixin {
 @ModifyArg(method="<init>",at=@At(value="INVOKE",target="Lnet/neoforged/bus/api/IEventBus;addListener(Ljava/util/function/Consumer;)V",ordinal=3),index=0,require=1,allow=1)
 private Consumer<?> earthplatform$observeEarthDimension(Consumer<?> consumer){
  NativeSessionCallbackWitness.observe(NativeSessionCallbackWitness.Site.EARTH_DIMENSION,consumer);return consumer;
 }
 @ModifyArg(method="<init>",at=@At(value="INVOKE",target="Lnet/neoforged/bus/api/IEventBus;addListener(Ljava/util/function/Consumer;)V",ordinal=4),index=0,require=1,allow=1)
 private Consumer<?> earthplatform$observeEarthLogin(Consumer<?> consumer){
  NativeSessionCallbackWitness.observe(NativeSessionCallbackWitness.Site.EARTH_LOGIN,consumer);return consumer;
 }
}
