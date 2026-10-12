package io.earthplatform.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.earthplatform.minecraft.survival.interaction.host.NativeLocalCausalObservation;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ClientLevel.class)
abstract class NativeLocalLightTaskObservationMixin {
    @WrapMethod(method="queueLightUpdate(Ljava/lang/Runnable;)V")
    private void earthplatform$observeLightTask(Runnable task, Operation<Void> original) {
        NativeLocalCausalObservation.Origin scope = null; Runnable actual = task;
        try { scope = NativeLocalCausalObservation.lightOrigin(this); actual = NativeLocalCausalObservation.lightTask(scope, this, task); }
        catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} }
        boolean failed = true;
        try { original.call(actual); failed = false; }
        finally { try { NativeLocalCausalObservation.lightSubmitted(scope, failed); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} } }
    }
}
