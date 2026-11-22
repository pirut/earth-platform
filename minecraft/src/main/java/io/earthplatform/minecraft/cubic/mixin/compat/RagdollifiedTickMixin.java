package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubicLevel;
import java.util.concurrent.ExecutorService;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Ragdollified runs client ragdoll physics on a dedicated background thread ({@code Ragdollified-Physics}),
 * calling {@code ClientLevel.getBlockState} from there. In a cubic level {@code CubeWorld.cubeAtBlock}
 * rejects off-thread reads and returns void air, so ragdolls fall straight through the ground.
 * Running the tick on the client thread in cubic worlds lets physics read real blocks.
 */
@Pseudo
@Mixin(targets = "com.raiiiden.ragdollified.client.ClientRagdollManager", remap = false)
abstract class RagdollifiedTickMixin {
    @WrapOperation(method = "submitTick()V", require = 0,
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/ExecutorService;execute(Ljava/lang/Runnable;)V"))
    private static void earthplatform$runOnClientThread(ExecutorService executor, Runnable task, Operation<Void> original) {
        var level = Minecraft.getInstance().level;
        if (level != null && CubicLevel.of(level) != null) {
            task.run();
        } else {
            original.call(executor, task);
        }
    }
}
