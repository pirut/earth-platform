package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.WorldEditCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * WorldEdit's block writer reads, writes and broadcasts through the carrier LevelChunk and its
 * 16-block section array; in the cubic level each of those goes to the cubes (see {@link WorldEditCompat}).
 */
@Pseudo
@Mixin(targets = "com.sk89q.worldedit.neoforge.internal.NeoForgeWorldNativeAccess", remap = false)
abstract class WorldEditAccessMixin {
    @Shadow private ServerLevel getWorld() { throw new AssertionError(); }

    @Inject(method = "getBlockState(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$cubeState(LevelChunk chunk, BlockPos pos, CallbackInfoReturnable<BlockState> result) {
        CubeWorld cubes = WorldEditCompat.cubes(chunk.getLevel(), pos);
        if (cubes != null) result.setReturnValue(cubes.getBlockState(pos));
    }

    @Inject(method = "setBlockState(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$cubeSet(LevelChunk chunk, BlockPos pos, BlockState state, CallbackInfoReturnable<BlockState> result) {
        CubeWorld cubes = WorldEditCompat.cubes(chunk.getLevel(), pos);
        if (cubes != null) result.setReturnValue(WorldEditCompat.write(cubes, pos, state, this));
    }

    /** Cube light follows each stored block by itself; the carrier's light engine has no section there. */
    @Inject(method = "updateLightingForBlock(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$cubeLight(BlockPos pos, CallbackInfo info) {
        if (CubicLevel.at(getWorld(), pos.getX(), pos.getZ()) != null) info.cancel();
    }

    @WrapOperation(method = "updateTileEntity", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity earthplatform$cubeEntity(LevelChunk chunk, BlockPos pos, Operation<BlockEntity> original) {
        return WorldEditCompat.cubes(chunk.getLevel(), pos) != null ? chunk.getLevel().getBlockEntity(pos) : original.call(chunk, pos);
    }

    @Inject(method = "notifyBlockUpdate(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$cubeNotify(LevelChunk chunk, BlockPos pos, BlockState old, BlockState state, CallbackInfo info) {
        if (CubicLevel.at(getWorld(), pos.getX(), pos.getZ()) == null) return;
        getWorld().sendBlockUpdated(pos, old, state, 3);
        info.cancel();
    }

    /** Cubes are sent to whoever holds them, so the carrier's ticking status says nothing about who sees the change. */
    @Inject(method = "isChunkTicking(Lnet/minecraft/world/level/chunk/LevelChunk;)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$cubeTicking(LevelChunk chunk, CallbackInfoReturnable<Boolean> result) {
        if (CubicLevel.at(chunk.getLevel(), chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ()) != null) result.setReturnValue(true);
    }

    @Inject(method = "markBlockChanged(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$cubeChanged(LevelChunk chunk, BlockPos pos, CallbackInfo info) {
        if (CubicLevel.at(getWorld(), pos.getX(), pos.getZ()) == null) return;
        getWorld().getChunkSource().blockChanged(pos);
        info.cancel();
    }
}
