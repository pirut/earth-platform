package io.earthplatform.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.survival.interaction.host.NativeLocalCausalObservation;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PacketUtils.class)
abstract class NativeLocalPacketTaskObservationMixin {
    @WrapMethod(method="ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V")
    private static void earthplatform$observePacketOrigin(Packet<?> packet, PacketListener listener, BlockableEventLoop<?> executor, Operation<Void> original) {
        NativeLocalCausalObservation.Origin scope = null;
        try { scope = NativeLocalCausalObservation.packetOrigin(packet, listener, executor); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} }
        try { original.call(packet, listener, executor); }
        catch(Throwable failure){
            if(failure!=net.minecraft.server.RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD)
                try{NativeLocalCausalObservation.originFailed(scope,true);}catch(Throwable ignored){}
            throw failure;
        }
        finally { try { NativeLocalCausalObservation.originReturned(scope, true); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} } }
    }
    @WrapOperation(method="ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            at=@At(value="INVOKE", target="Lnet/minecraft/util/thread/BlockableEventLoop;executeIfPossible(Ljava/lang/Runnable;)V"), require=1, allow=1)
    private static void earthplatform$observePacketSubmit(BlockableEventLoop<?> executor, Runnable task, Operation<Void> original) {
        Runnable actual = task;
        try { actual = NativeLocalCausalObservation.taskAtSubmission(executor, task, true); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} }
        boolean failed = true;
        try { original.call(executor, actual); failed = false; }
        finally { try { NativeLocalCausalObservation.submissionReturned(true, failed); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} } }
    }
    @WrapOperation(method="lambda$ensureRunningOnSameThread$0(Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/protocol/Packet;)V",
            at=@At(value="INVOKE", target="Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"), require=1, allow=1)
    private static void earthplatform$observePacketFailure(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        boolean failed = true;
        try { original.call(packet, listener); failed = false; }
        finally { if (failed) try { NativeLocalCausalObservation.packetHandlerFailed(); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} } }
    }
}
