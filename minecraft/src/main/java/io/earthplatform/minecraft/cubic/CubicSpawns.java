package io.earthplatform.minecraft.cubic;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;

/**
 * Spawn rules vanilla ties to sea level, given their meaning in a cubic world, where the ground
 * and its water stand at their real heights: bats roost in dark caves under the ground at any
 * height, and fish and squid swim in the top 13 blocks of any water, such as a mountain lake, not
 * only in the 13 blocks under Y 62. Vanilla's own rules still apply alongside.
 */
@EventBusSubscriber(modid = Cubic.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class CubicSpawns {
    private CubicSpawns() {}

    @SubscribeEvent public static void placements(RegisterSpawnPlacementsEvent event) {
        event.register(EntityType.BAT, CubicSpawns::bat, RegisterSpawnPlacementsEvent.Operation.OR);
        surfaceWater(event, EntityType.COD);
        surfaceWater(event, EntityType.SALMON);
        surfaceWater(event, EntityType.PUFFERFISH);
        surfaceWater(event, EntityType.TROPICAL_FISH);
        surfaceWater(event, EntityType.SQUID);
        surfaceWater(event, EntityType.DOLPHIN);
    }

    private static <T extends WaterAnimal> void surfaceWater(RegisterSpawnPlacementsEvent event, EntityType<T> type) {
        event.register(type, CubicSpawns::nearWaterTop, RegisterSpawnPlacementsEvent.Operation.OR);
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
