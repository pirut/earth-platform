package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.SortedSet;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block cracks are keyed by BlockPos.asLong, whose 12-bit Y wraps above Y 2047, so the overlay
 * landed thousands of blocks away. Remember the real position for each key and draw it there.
 */
@Mixin(LevelRenderer.class)
abstract class CubicDestroyProgressMixin {
    @Shadow @Final private Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress;
    @Unique private final Long2ObjectOpenHashMap<BlockPos> earthplatform$crackPositions = new Long2ObjectOpenHashMap<>();

    @Inject(method = "destroyBlockProgress", at = @At("HEAD"))
    private void earthplatform$remember(int breakerId, BlockPos pos, int progress, CallbackInfo info) {
        if (progress >= 0 && progress < 10) earthplatform$crackPositions.put(pos.asLong(), pos.immutable());
    }

    @Inject(method = "removeProgress", at = @At("TAIL"))
    private void earthplatform$forget(BlockDestructionProgress progress, CallbackInfo info) {
        long key = progress.getPos().asLong();
        if (!destructionProgress.containsKey(key)) earthplatform$crackPositions.remove(key);
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;of(J)Lnet/minecraft/core/BlockPos;"))
    private BlockPos earthplatform$realPosition(long key, Operation<BlockPos> original) {
        BlockPos pos = earthplatform$crackPositions.get(key);
        return pos != null ? pos : original.call(key);
    }
}
