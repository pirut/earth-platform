package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.WakesCompat;
import java.util.ArrayList;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wakes allocates per-Y {@code QuadTree} and {@code QueueSet} arrays spanning the level's build
 * height (0..16 in the cubic carrier), dropping every wake at real water elevations such as sea
 * level (Y 62). In cubic worlds, active water elevations are stored sparsely instead.
 */
@Pseudo
@Mixin(targets = "com.leclowndu93150.wakes.simulation.WakeHandler", remap = false)
abstract class WakesCubicHeightMixin {
    @Shadow public Level world;
    @Shadow public static boolean resolutionResetScheduled;

    @Unique @Nullable private WakesCompat.SparseLayers<Object, Object> earthplatform$layers;

    @Unique
    @Nullable
    private WakesCompat.SparseLayers<Object, Object> earthplatform$cubicLayers() {
        if (this.world == null || CubicLevel.of(this.world) == null) return null;
        if (this.earthplatform$layers == null) {
            this.earthplatform$layers = WakesCompat.newRuntimeLayers();
        }
        return this.earthplatform$layers;
    }

    @Inject(method = "insert(Lcom/leclowndu93150/wakes/simulation/WakeNode;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$insertSparse(@Coerce Object node, CallbackInfo ci) {
        var layers = earthplatform$cubicLayers();
        if (layers == null) return;
        layers.insert(node, this.world, resolutionResetScheduled);
        ci.cancel();
    }

    @Inject(method = "tick()V",
            at = @At(value = "INVOKE", target = "Ljava/util/ArrayList;size()I"), require = 0)
    private void earthplatform$tickSparse(CallbackInfo ci) {
        var layers = earthplatform$cubicLayers();
        if (layers != null) layers.tick(this, resolutionResetScheduled);
    }

    @Inject(method = "getVisible(Lnet/minecraft/client/renderer/culling/Frustum;Ljava/lang/Class;)Ljava/util/ArrayList;",
            at = @At("RETURN"), require = 0)
    private <T> void earthplatform$querySparse(Frustum frustum, Class<T> type,
            CallbackInfoReturnable<ArrayList<T>> cir) {
        var layers = earthplatform$cubicLayers();
        if (layers != null) layers.queryVisible(frustum, cir.getReturnValue(), type);
    }

    @Inject(method = "recolorWakes()V", at = @At("TAIL"), require = 0)
    private void earthplatform$recolorSparse(CallbackInfo ci) {
        var layers = earthplatform$cubicLayers();
        if (layers != null) layers.recolorWakes();
    }

    @Inject(method = "reset()V", at = @At("TAIL"), require = 0)
    private void earthplatform$resetSparse(CallbackInfo ci) {
        var layers = earthplatform$cubicLayers();
        if (layers != null) layers.reset();
    }

    @Inject(method = "cleanupChunk(IIII)V", at = @At("TAIL"), require = 0)
    private void earthplatform$cleanupSparse(int minX, int minZ, int maxX, int maxZ, CallbackInfo ci) {
        var layers = earthplatform$cubicLayers();
        if (layers != null) layers.cleanupChunk(minX, minZ, maxX, maxZ);
    }
}
