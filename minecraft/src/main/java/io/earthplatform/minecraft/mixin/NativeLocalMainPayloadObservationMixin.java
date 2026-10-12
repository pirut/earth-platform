package io.earthplatform.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.survival.interaction.host.NativeLocalPayloadObservation;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.MainThreadPayloadHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observe the unmodified original MAIN-payload Runnable admission and callback body. */
@Mixin(MainThreadPayloadHandler.class)
abstract class NativeLocalMainPayloadObservationMixin {
    @WrapMethod(method="handle(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V")
    private void earthplatform$payloadSource(CustomPacketPayload payload,IPayloadContext context,Operation<Void> original){
        NativeLocalPayloadObservation.Scope scope=null;
        try{scope=NativeLocalPayloadObservation.sourceEntered(this,payload,context);}catch(Throwable failure){try{NativeLocalPayloadObservation.observerFailed();}catch(Throwable ignored){}}
        boolean failed=true;
        try{original.call(payload,context);failed=false;}
        finally{try{NativeLocalPayloadObservation.sourceReturned(scope,failed);}catch(Throwable failure){try{NativeLocalPayloadObservation.observerFailed();}catch(Throwable ignored){}}}
    }
    @WrapOperation(method="handle(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V",
            at=@At(value="INVOKE",target="Lnet/neoforged/neoforge/network/handling/IPayloadContext;enqueueWork(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;"),require=1,allow=1)
    private CompletableFuture<Void> earthplatform$payloadEnqueue(IPayloadContext context,Runnable task,Operation<CompletableFuture<Void>> original){
        NativeLocalPayloadObservation.Scope scope=null;
        try{scope=NativeLocalPayloadObservation.beforeEnqueue(context,task);}catch(Throwable failure){try{NativeLocalPayloadObservation.observerFailed();}catch(Throwable ignored){}}
        CompletableFuture<Void> returned=null;boolean failed=true;
        try{returned=original.call(context,task);failed=false;return returned;}
        finally{try{NativeLocalPayloadObservation.enqueueReturned(scope,returned,failed);}catch(Throwable failure){try{NativeLocalPayloadObservation.observerFailed();}catch(Throwable ignored){}}}
    }
    @WrapMethod(method="lambda$handle$0(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V")
    private void earthplatform$payloadCallback(CustomPacketPayload payload,IPayloadContext context,Operation<Void> original){
        NativeLocalPayloadObservation.Scope scope=null;
        try{scope=NativeLocalPayloadObservation.callbackEntered(this,payload,context);}catch(Throwable failure){try{NativeLocalPayloadObservation.observerFailed();}catch(Throwable ignored){}}
        boolean failed=true;
        try{original.call(payload,context);failed=false;}
        finally{try{NativeLocalPayloadObservation.callbackReturned(scope,failed);}catch(Throwable failure){try{NativeLocalPayloadObservation.observerFailed();}catch(Throwable ignored){}}}
    }
}
