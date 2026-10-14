package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cubic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * overworld spawn, which nobody in an Earth world ever sees; treeless starts get it too.
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

    /** How far round the first arrival to look for trees, in blocks. */
    private static final int WOOD_REACH = 192;

    /**
     * Puts vanilla's bonus chest, with its torches, next to where the first player arrives on the
     * Earth, once per world: when Create World's Bonus Chest is on, and also, with a word to the
     * player, where no trees grow anywhere near (the Sahara, Svalbard), since the chest is then the
     * only early wood.
     */
    static void bonusChest(ServerLevel level, Path cubicFolder, Vec3 arrival, ServerPlayer player) {
        Path mark = cubicFolder.resolve(BONUS_CHEST_MARK);
        if (Files.exists(mark)) return;
        boolean asked = level.getServer().getWorldData().worldGenOptions().generateBonusChest();
        boolean treeless = !asked && treeless(level, arrival);
        if (!asked && !treeless) { mark(mark, "none"); return; }
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
        mark(mark, spot.toShortString());
        if (treeless) player.sendSystemMessage(Component.literal("No trees grow near here, so a chest of starting supplies stands beside you.")
                .withStyle(ChatFormatting.YELLOW));
    }

    /** Whether no tree grows within {@link #WOOD_REACH} blocks of a spot, by the Earth's own cover; false for other generators. */
    private static boolean treeless(ServerLevel level, Vec3 at) {
        if (!(CubicLevel.of(level) instanceof ServerCubeWorld cubes) || !(cubes.generator() instanceof SurfaceGenerator surface)) return false;
        int x0 = (int) Math.floor(at.x), z0 = (int) Math.floor(at.z);
        for (int dz = -WOOD_REACH; dz <= WOOD_REACH; dz += 8)
            for (int dx = -WOOD_REACH; dx <= WOOD_REACH; dx += 8)
                if (dx * dx + dz * dz <= WOOD_REACH * WOOD_REACH && surface.wooded(x0 + dx, z0 + dz)) return false;
        return true;
    }

    private static void mark(Path mark, String text) {
        try {
            Files.createDirectories(mark.getParent());
            Files.writeString(mark, text + "\n");
        } catch (IOException failure) {
            EarthPlatformMod.LOGGER.warn("Could not record the Earth bonus chest", failure);
        }
    }

    private static boolean open(ServerLevel level, BlockPos at) {
        var state = level.getBlockState(at);
        return state.canBeReplaced() && !(state.getBlock() instanceof DoublePlantBlock) && level.getFluidState(at).isEmpty() && level.getBlockState(at.above()).isAir();
    }
}
