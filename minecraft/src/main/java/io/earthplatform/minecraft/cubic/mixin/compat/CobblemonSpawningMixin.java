package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Cobblemon in the Earth world. Its spawner reads blocks and sky light straight from LevelChunks and
 * clamps spawn areas to the build height, so in the cubic level it saw the empty 16-block carrier and
 * no Pokémon ever spawned. Its PC, pasture and healing tank refused placement above the carrier, and
 * Pokémon path searches stopped at the carrier's floor and ceiling. All of them read the cubes and
 * the cubic world's height range here instead.
 */
@Pseudo
@Mixin(targets = {
        "com.cobblemon.mod.common.api.spawning.CobblemonSpawningZoneGenerator",
        "com.cobblemon.mod.common.api.spawning.SpawningZone",
        "com.cobblemon.mod.common.entity.ai.OmniPathNavigation",
        "com.cobblemon.mod.common.pokemon.ai.OmniPathNodeMaker",
        "com.cobblemon.mod.common.block.PCBlock",
        "com.cobblemon.mod.common.block.PastureBlock",
        "com.cobblemon.mod.common.block.RestorationTankBlock"}, remap = false)
abstract class CobblemonSpawningMixin {
    @WrapOperation(method = "generate", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMinBuildHeight()I"))
    private int earthplatform$zoneBottom(ServerLevel level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MIN_Y : original.call(level);
    }

    @WrapOperation(method = {"generate", "skySpaceAbove"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMaxBuildHeight()I"))
    private int earthplatform$zoneTop(ServerLevel level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }

    @WrapOperation(method = {"createPath", "tryFindFirstGroundNodeBelow", "tryFindFirstNonWaterBelow"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private int earthplatform$bottom(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MIN_Y : original.call(level);
    }

    @WrapOperation(method = "createPath", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private int earthplatform$top(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }

    @WrapOperation(method = "getStateForPlacement", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isOutsideBuildHeight(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean earthplatform$outside(Level level, BlockPos pos, Operation<Boolean> original) {
        return CubicLevel.of(level) != null ? !CubeWorld.inRange(pos.getY()) : original.call(level, pos);
    }

    @WrapOperation(method = "generate", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState earthplatform$zoneBlock(ChunkAccess chunk, BlockPos pos, Operation<BlockState> original) {
        return chunk instanceof LevelChunk carrier && CubicLevel.of(carrier.getLevel()) != null
                ? carrier.getLevel().getBlockState(pos) : original.call(chunk, pos);
    }

    /** Each column asks this first, so the sky light reads below know which level they are in. */
    @WrapOperation(method = "generate", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;canSeeSkyFromBelowWater(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean earthplatform$zoneColumn(ServerLevel level, BlockPos pos, Operation<Boolean> original,
            @Share("cubic") LocalRef<Level> cubic) {
        cubic.set(CubicLevel.of(level) != null ? level : null);
        return original.call(level, pos);
    }

    @WrapOperation(method = "generate", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LayerLightEventListener;getLightValue(Lnet/minecraft/core/BlockPos;)I"))
    private int earthplatform$zoneSkyLight(LayerLightEventListener light, BlockPos pos, Operation<Integer> original,
            @Share("cubic") LocalRef<Level> cubic) {
        Level level = cubic.get();
        return level != null ? level.getBrightness(LightLayer.SKY, pos) : original.call(light, pos);
    }
}
