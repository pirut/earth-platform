package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.interaction.host.NativeTrackedPairingOrigin;
import java.util.Set;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
/** Retains the ORIGINAL native Sets.newIdentityHashSet result, never substitutes a collection. */
@Mixin(targets="net.minecraft.server.level.ChunkMap$TrackedEntity",priority=2300)
abstract class NativeTrackedPairingOriginMixin implements NativeTrackedPairingOrigin {
 @Unique private Object earthplatform$pairingSetFromNativeConstructor;
 @WrapOperation(method="<init>",at=@At(value="INVOKE",target="Lcom/google/common/collect/Sets;newIdentityHashSet()Ljava/util/Set;"),require=1,expect=1,allow=1)
 private Set<?> earthplatform$witnessOriginalPairingSet(Operation<Set<?>> original){
  Set<?> result=original.call();
  if(earthplatform$pairingSetFromNativeConstructor!=null)throw new IllegalStateException("Native pairing factory repeated");
  earthplatform$pairingSetFromNativeConstructor=result;return result;
 }
 @Override public Object earthplatform$originalPairingSet(){return earthplatform$pairingSetFromNativeConstructor;}
}
