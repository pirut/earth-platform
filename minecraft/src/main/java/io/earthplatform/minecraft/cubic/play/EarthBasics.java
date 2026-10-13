package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cubic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;

/**
 * Survival basics the Earth's level would otherwise miss because it is not the overworld: its time
 * and weather are the overworld's (read through derived level data, which ignores writes), so a
 * night slept through has to be skipped there; and vanilla's bonus chest is placed beside the
 * overworld spawn, which nobody in an Earth world ever sees.
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID)
public final class EarthBasics {
    private static final String BONUS_CHEST_MARK = "bonus_chest_placed";

    private EarthBasics() {}

    /** Sleeping in the Earth moves the shared clock and clears the shared weather, as sleeping in the overworld does. */
    @SubscribeEvent public static void sleptThrough(SleepFinishedTimeEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Cubic.isCubic(level.dimension())) return;
        ServerLevel overworld = level.getServer().overworld();
        if (level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) overworld.setDayTime(event.getNewTime());
        if (level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE) && overworld.isRaining()) overworld.resetWeatherCycle();
    }

    /**
     * When Create World's Bonus Chest is on, puts vanilla's bonus chest, with its torches, next to
     * where the first player arrives on the Earth; once per world.
     */
    static void bonusChest(ServerLevel level, Path cubicFolder, Vec3 arrival) {
        if (!level.getServer().getWorldData().worldGenOptions().generateBonusChest()) return;
        Path mark = cubicFolder.resolve(BONUS_CHEST_MARK);
        if (Files.exists(mark)) return;
        BlockPos feet = BlockPos.containing(arrival);
        BlockPos spot = null;
        // The nearest open, solid-floored block a couple of steps from the player, so the chest doesn't block the way.
        search:
        for (int radius = 2; radius <= 6; radius++)
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -radius; dx <= radius; dx++)
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                        BlockPos at = feet.offset(dx, dy, dz);
                        if (open(level, at) && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) { spot = at; break search; }
                    }
        if (spot == null) return;
        RandomSource random = level.getRandom();
        level.setBlock(spot, Blocks.CHEST.defaultBlockState(), 3);
        RandomizableContainer.setBlockEntityLootTable(level, random, spot, BuiltInLootTables.SPAWN_BONUS_CHEST);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos torch = spot.relative(side);
            if (open(level, torch) && level.getBlockState(torch.below()).isFaceSturdy(level, torch.below(), Direction.UP))
                level.setBlock(torch, Blocks.TORCH.defaultBlockState(), 3);
        }
        try {
            Files.createDirectories(cubicFolder);
            Files.writeString(mark, spot.toShortString() + "\n");
        } catch (IOException failure) {
            EarthPlatformMod.LOGGER.warn("Could not record the Earth bonus chest at {}", spot, failure);
        }
    }

    private static boolean open(ServerLevel level, BlockPos at) {
        var state = level.getBlockState(at);
        return state.canBeReplaced() && !(state.getBlock() instanceof DoublePlantBlock) && level.getFluidState(at).isEmpty() && level.getBlockState(at.above()).isAir();
    }
}
