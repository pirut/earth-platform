package io.earthplatform.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.survival.interaction.host.NativeMainTaskDispatchAccess;
import io.earthplatform.minecraft.survival.interaction.host.NativeMainTaskDispatchOwner;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** One shared native dispatch seam. No default registration or foreign-server owner broadcast. */
@Mixin(BlockableEventLoop.class)
abstract class NativeMainTaskDispatchMixin implements NativeMainTaskDispatchAccess {
    @Unique private volatile NativeMainTaskDispatchOwner earthplatform$networkTaskOwner;
    @Unique private volatile NativeMainTaskDispatchOwner earthplatform$shutdownTaskOwner;

    @Override public final synchronized void earthplatform$bindTaskDispatch(BlockableEventLoop<?> exact,
            NativeMainTaskDispatchOwner owner) {
        if (exact != (Object) this || owner == null || !owner.matches(exact))
            throw new IllegalStateException("Foreign native dispatch owner");
        if (owner.lane() == NativeMainTaskDispatchOwner.Lane.NETWORK) {
            if (earthplatform$networkTaskOwner != null) throw new IllegalStateException("Network dispatch owner already retained");
            earthplatform$networkTaskOwner = owner;
        } else {
            if (earthplatform$shutdownTaskOwner != null) throw new IllegalStateException("Shutdown dispatch owner already retained");
            earthplatform$shutdownTaskOwner = owner;
        }
    }

    @WrapOperation(method="doRunTask(Ljava/lang/Runnable;)V",
            at=@At(value="INVOKE", target="Ljava/lang/Runnable;run()V", remap=false), require=1, allow=1)
    private void earthplatform$observeTaskRun(Runnable task, Operation<Void> original) {
        var network = earthplatform$networkTaskOwner;
        var shutdown = earthplatform$shutdownTaskOwner;
        if (network == null && shutdown == null) { original.call(task); return; }
        NativeMainTaskDispatchOwner.originalRun((BlockableEventLoop<?>) (Object) this, task, network, shutdown, original);
    }
}
