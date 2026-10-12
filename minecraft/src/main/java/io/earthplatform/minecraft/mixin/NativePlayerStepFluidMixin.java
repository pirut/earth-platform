package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
/** Production step scope only; normal and unscoped legacy behavior is unchanged. */
@Mixin(Entity.class)
abstract class NativePlayerStepFluidMixin {
 @Redirect(method="updateFluidHeightAndDoFluidPushing()V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;touchingUnloadedChunk()Z"),require=1)
 private boolean earthplatform$exactPlayerFluidResidency(Entity source){return NativePlayerStepReadScope.fluidResidency(source);}
}
