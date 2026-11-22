package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Cold Sweat's Hearth scans room enclosures by calling {@code workingChunk.getBlockState(pos)} on
 * the carrier chunk, seeing air everywhere outside Y [0, 15]. Route those block reads to the cubic
 * store so hearths detect walls and insulate rooms at any Earth elevation.
 */
@Pseudo
@Mixin(targets = "com.momosoftworks.coldsweat.common.blockentity.HearthBlockEntity", remap = false)
abstract class ColdSweatHearthMixin {
    @WrapOperation(method = "getBlockStateAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState earthplatform$hearthBlock(ChunkAccess chunk, BlockPos pos, Operation<BlockState> original) {
        if (chunk instanceof LevelChunk carrier) {
            CubeWorld cubes = CubicLevel.at(carrier.getLevel(), pos.getX(), pos.getZ());
            if (cubes != null) return cubes.getBlockState(pos);
        }
        return original.call(chunk, pos);
    }
}
