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
@Mixin(targets="io.earthplatform.minecraft.EarthPlatformMod",remap=false)
abstract class InstalledConsumerEarthOriginsMixin {
    @WrapOperation(method="<init>(Lnet/neoforged/bus/api/IEventBus;)V",at=@At(value="INVOKE",
            target="Lnet/neoforged/bus/api/IEventBus;addListener(Ljava/util/function/Consumer;)V",ordinal=2),require=1,allow=1)
    private void earthplatform$consumerEarthCommands(IEventBus bus,Consumer<?> consumer,Operation<Void> original) {
        Object receipt=null;boolean returned=false;Class<?> nativeFailure=null;
        try { receipt=InstalledConsumerOrigins.before(0,this,bus,consumer,bus==NeoForge.EVENT_BUS); }
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
    @WrapOperation(method="<init>(Lnet/neoforged/bus/api/IEventBus;)V",at=@At(value="INVOKE",
            target="Lnet/neoforged/bus/api/IEventBus;addListener(Ljava/util/function/Consumer;)V",ordinal=3),require=1,allow=1)
    private void earthplatform$consumerEarthDimension(IEventBus bus,Consumer<?> consumer,Operation<Void> original) {
        Object receipt=null;boolean returned=false;Class<?> nativeFailure=null;
        try { receipt=InstalledConsumerOrigins.before(1,this,bus,consumer,bus==NeoForge.EVENT_BUS); }
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
    @WrapOperation(method="<init>(Lnet/neoforged/bus/api/IEventBus;)V",at=@At(value="INVOKE",
            target="Lnet/neoforged/bus/api/IEventBus;addListener(Ljava/util/function/Consumer;)V",ordinal=4),require=1,allow=1)
    private void earthplatform$consumerEarthLogin(IEventBus bus,Consumer<?> consumer,Operation<Void> original) {
        Object receipt=null;boolean returned=false;Class<?> nativeFailure=null;
        try { receipt=InstalledConsumerOrigins.before(2,this,bus,consumer,bus==NeoForge.EVENT_BUS); }
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
    @WrapOperation(method="<init>(Lnet/neoforged/bus/api/IEventBus;)V",at=@At(value="INVOKE",
            target="Lnet/neoforged/bus/api/IEventBus;addListener(Ljava/util/function/Consumer;)V",ordinal=5),require=1,allow=1)
    private void earthplatform$consumerEarthPreflight(IEventBus bus,Consumer<?> consumer,Operation<Void> original) {
        Object receipt=null;boolean returned=false;Class<?> nativeFailure=null;
        try { receipt=InstalledConsumerOrigins.before(3,this,bus,consumer,bus==NeoForge.EVENT_BUS); }
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
