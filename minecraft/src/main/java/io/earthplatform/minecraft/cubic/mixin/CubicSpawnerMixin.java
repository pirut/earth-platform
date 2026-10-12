package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla picks spawn heights from the carrier chunk's 16-block column. In a cubic level, pick
 * them in the loaded band around the nearest player instead; the rest of the spawn checks
 * (light, ground, collisions, mob caps) run unchanged through the cube-backed Level.
 */
@Mixin(NaturalSpawner.class)
abstract class CubicSpawnerMixin {
    @Inject(method = "spawnCategoryForChunk", at = @At("HEAD"), cancellable = true)
    private static void earthplatform$spawnAtTrueHeight(MobCategory category, ServerLevel level, LevelChunk chunk,
                                                        NaturalSpawner.SpawnPredicate filter, NaturalSpawner.AfterSpawnCallback callback,
                                                        CallbackInfo info) {
        if (!(CubicLevel.of(level) instanceof ServerCubeWorld cubes)) return;
        info.cancel();
        BlockPos pos = cubes.spawnCandidate(chunk.getPos(), level.random);
        if (pos != null) NaturalSpawner.spawnCategoryForPosition(category, level, chunk, pos, filter, callback);
    }
}
