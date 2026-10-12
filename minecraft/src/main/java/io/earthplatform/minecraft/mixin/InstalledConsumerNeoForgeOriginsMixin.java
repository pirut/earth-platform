package io.earthplatform.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.survival.runtime.InstalledConsumerOrigins;
import java.util.function.Consumer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observation only. Each original operation executes once with unchanged operands.
 * These host-owned constructor hooks do not transform bus/library classes. */
@Mixin(targets="net.neoforged.neoforge.common.NeoForgeMod",remap=false)
abstract class InstalledConsumerNeoForgeOriginsMixin {
    @WrapOperation(method="<init>(Lnet/neoforged/bus/api/IEventBus;Lnet/neoforged/api/distmarker/Dist;Lnet/neoforged/fml/ModContainer;)V",at=@At(value="INVOKE",
            target="Lnet/neoforged/bus/api/IEventBus;addListener(Ljava/util/function/Consumer;)V",ordinal=6),require=1,allow=1)
    private void earthplatform$consumerNeoStopping(IEventBus bus,Consumer<?> consumer,Operation<Void> original) {
        Object receipt=null;boolean returned=false;Class<?> nativeFailure=null;
        try { receipt=InstalledConsumerOrigins.before(4,this,bus,consumer,bus==NeoForge.EVENT_BUS); }
        catch(Throwable observerFailure) {
            try { InstalledConsumerOrigins.observerFailed(observerFailure.getClass()); } catch(Throwable ignored) {}
        }
        try { original.call(bus,consumer);returned=true; }
        catch(Throwable failure) { nativeFailure=failure.getClass();throw failure; }
        finally {
            try { InstalledConsumerOrigins.after(receipt,returned,nativeFailure); }
            catch(Throwable observerFailure) {
                try { InstalledConsumerOrigins.observerFailed(observerFailure.getClass()); } catch(Throwable ignored) {}
            }
        }
    }
    @WrapOperation(method="<init>(Lnet/neoforged/bus/api/IEventBus;Lnet/neoforged/api/distmarker/Dist;Lnet/neoforged/fml/ModContainer;)V",at=@At(value="INVOKE",
            target="Lnet/neoforged/bus/api/IEventBus;addListener(Ljava/util/function/Consumer;)V",ordinal=11),require=1,allow=1)
    private void earthplatform$consumerCapabilityLoad(IEventBus bus,Consumer<?> consumer,Operation<Void> original) {
        Object receipt=null;boolean returned=false;Class<?> nativeFailure=null;
        try { receipt=InstalledConsumerOrigins.before(5,this,bus,consumer,bus==NeoForge.EVENT_BUS); }
        catch(Throwable observerFailure) {
            try { InstalledConsumerOrigins.observerFailed(observerFailure.getClass()); } catch(Throwable ignored) {}
        }
        try { original.call(bus,consumer);returned=true; }
        catch(Throwable failure) { nativeFailure=failure.getClass();throw failure; }
        finally {
            try { InstalledConsumerOrigins.after(receipt,returned,nativeFailure); }
            catch(Throwable observerFailure) {
                try { InstalledConsumerOrigins.observerFailed(observerFailure.getClass()); } catch(Throwable ignored) {}
            }
        }
    }
    @WrapOperation(method="<init>(Lnet/neoforged/bus/api/IEventBus;Lnet/neoforged/api/distmarker/Dist;Lnet/neoforged/fml/ModContainer;)V",at=@At(value="INVOKE",
            target="Lnet/neoforged/bus/api/IEventBus;addListener(Ljava/util/function/Consumer;)V",ordinal=12),require=1,allow=1)
    private void earthplatform$consumerCapabilityUnload(IEventBus bus,Consumer<?> consumer,Operation<Void> original) {
        Object receipt=null;boolean returned=false;Class<?> nativeFailure=null;
        try { receipt=InstalledConsumerOrigins.before(6,this,bus,consumer,bus==NeoForge.EVENT_BUS); }
        catch(Throwable observerFailure) {
            try { InstalledConsumerOrigins.observerFailed(observerFailure.getClass()); } catch(Throwable ignored) {}
        }
        try { original.call(bus,consumer);returned=true; }
        catch(Throwable failure) { nativeFailure=failure.getClass();throw failure; }
        finally {
            try { InstalledConsumerOrigins.after(receipt,returned,nativeFailure); }
            catch(Throwable observerFailure) {
                try { InstalledConsumerOrigins.observerFailed(observerFailure.getClass()); } catch(Throwable ignored) {}
            }
        }
    }
    @WrapOperation(method="<init>(Lnet/neoforged/bus/api/IEventBus;Lnet/neoforged/api/distmarker/Dist;Lnet/neoforged/fml/ModContainer;)V",at=@At(value="INVOKE",
            target="Lnet/neoforged/bus/api/IEventBus;addListener(Ljava/util/function/Consumer;)V",ordinal=13),require=1,allow=1)
    private void earthplatform$consumerCapabilityPost(IEventBus bus,Consumer<?> consumer,Operation<Void> original) {
        Object receipt=null;boolean returned=false;Class<?> nativeFailure=null;
        try { receipt=InstalledConsumerOrigins.before(7,this,bus,consumer,bus==NeoForge.EVENT_BUS); }
        catch(Throwable observerFailure) {
            try { InstalledConsumerOrigins.observerFailed(observerFailure.getClass()); } catch(Throwable ignored) {}
        }
        try { original.call(bus,consumer);returned=true; }
        catch(Throwable failure) { nativeFailure=failure.getClass();throw failure; }
        finally {
            try { InstalledConsumerOrigins.after(receipt,returned,nativeFailure); }
            catch(Throwable observerFailure) {
                try { InstalledConsumerOrigins.observerFailed(observerFailure.getClass()); } catch(Throwable ignored) {}
            }
        }
    }
}
