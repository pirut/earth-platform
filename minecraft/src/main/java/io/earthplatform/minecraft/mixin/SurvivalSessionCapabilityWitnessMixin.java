package io.earthplatform.minecraft.mixin;
import java.util.function.Consumer;
import io.earthplatform.minecraft.survival.persistence.NativeSessionCallbackWitness;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
/** Pinned NeoForgeMod constructor's fourteenth one-argument Consumer registration: capability Post.
 * Original bytecode offset421, immediately following exact CapabilityHooks method-reference indy.
 * Returning the original argument preserves the native registration and all original dispatch.
 */
@Mixin(targets="net.neoforged.neoforge.common.NeoForgeMod",remap=false)
abstract class SurvivalSessionCapabilityWitnessMixin {
 @ModifyArg(method="<init>",at=@At(value="INVOKE",target="Lnet/neoforged/bus/api/IEventBus;addListener(Ljava/util/function/Consumer;)V",ordinal=13),index=0,require=1,allow=1)
 private Consumer<?> earthplatform$observeCapabilityPost(Consumer<?> consumer){
  NativeSessionCallbackWitness.observe(NativeSessionCallbackWitness.Site.CAPABILITY_POST,consumer);return consumer;
 }
}
