package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.interaction.host.NativeEmbeddedTransformMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Inert, opt-in bridge marker: observes the original boundary, never runs or cancels work. */
@Mixin(targets="io.netty.channel.embedded.EmbeddedChannel",remap=false)
abstract class NativeEmbeddedTransformMarkerMixin implements NativeEmbeddedTransformMarker {
    @Unique private long earthplatform$originalTaskBoundaryEntries;
    @Override public long earthplatform$originalTaskBoundaryEntries(){return earthplatform$originalTaskBoundaryEntries;}
    @Inject(method="runPendingTasks()V",at=@At("HEAD"),require=1,allow=1,remap=false)
    private void earthplatform$observeOriginalTaskBoundary(CallbackInfo ci){earthplatform$originalTaskBoundaryEntries++;}
}
