package io.earthplatform.minecraft.cubic;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;

/**
 * Spawn rules vanilla ties to sea level, given their meaning in a cubic world, where the ground
 * and its water stand at their real heights: bats roost in dark caves under the ground at any
 * height, and fish and squid swim in the top 13 blocks of any water, such as a mountain lake, not
 * only in the 13 blocks under Y 62. Vanilla's own rules still apply alongside.
 */
public final class CubicSpawns {
    private CubicSpawns() {}

    /** Where the loader adds a spawn rule that lets a mob spawn when vanilla's or this one allows it. */
    public interface Rules {
        <T extends Entity> void or(EntityType<T> type, SpawnPlacements.SpawnPredicate<T> rule);
    }

    public static void placements(Rules rules) {
        rules.or(EntityType.BAT, CubicSpawns::bat);
        surfaceWater(rules, EntityType.COD);
        surfaceWater(rules, EntityType.SALMON);
        surfaceWater(rules, EntityType.PUFFERFISH);
        surfaceWater(rules, EntityType.TROPICAL_FISH);
        surfaceWater(rules, EntityType.SQUID);
        surfaceWater(rules, EntityType.DOLPHIN);
    }

    private static <T extends WaterAnimal> void surfaceWater(Rules rules, EntityType<T> type) {
        rules.or(type, CubicSpawns::nearWaterTop);
    }

    /** As vanilla's bats, but under the column's ground instead of under sea level. */
    private static boolean bat(EntityType<Bat> type, ServerLevelAccessor level, MobSpawnType spawn, BlockPos pos, RandomSource random) {
        if (!(CubicLevel.of(level.getLevel()) instanceof ServerCubeWorld cubes)) return false;
        if (pos.getY() >= cubes.generator().surfaceY(pos.getX(), pos.getZ()) || random.nextBoolean()) return false;
        return level.getMaxLocalRawBrightness(pos) <= random.nextInt(4) && Mob.checkMobSpawnRules(type, level, spawn, pos, random);
    }

    /** As vanilla's surface water animals, but within 13 blocks under the top of the water they are in. */
    private static <T extends WaterAnimal> boolean nearWaterTop(EntityType<T> type, ServerLevelAccessor level, MobSpawnType spawn, BlockPos pos, RandomSource random) {
        if (CubicLevel.of(level.getLevel()) == null || !level.getFluidState(pos.below()).is(FluidTags.WATER)
                || !level.getBlockState(pos.above()).is(Blocks.WATER)) return false;
        for (int dy = 2; dy <= 14; dy++) if (!level.getFluidState(pos.above(dy)).is(FluidTags.WATER)) return true;
        return false;
    }
}
