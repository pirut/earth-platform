package io.earthplatform.minecraft.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.survival.interaction.host.NativeMiningScheduledOrigin;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
/** Observes one genuine native connection dispatch; it does not release player or world ticks. */
@Mixin(ServerConnectionListener.class)
abstract class NativeMiningSchedulerOriginMixin {
    @WrapOperation(method="tick()V",at=@At(value="INVOKE",target="Lnet/minecraft/network/Connection;tick()V"),require=1,allow=1)
    private void earthplatform$exactMiningClock(Connection connection,Operation<Void> original) {
        NativeMiningScheduledOrigin.runOriginalConnectionTick((ServerConnectionListener)(Object)this,connection,()->original.call(connection));
    }
}
