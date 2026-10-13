package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.fml.loading.FMLLoader;

/**
 * Sable (the physics library under Create Aeronautics) in the cubic Earth world.
 *
 * <p>Sable keeps each ship's blocks in a "plot": real LevelChunks in a reserved square of chunk
 * columns far outside the map (chunks 1,280,000 and up on both axes), at heights inside the level's
 * build range. In the cubic level two things follow:
 * <ul>
 *   <li>Plot columns stay in chunks. Cube routing skips them, so the level reads and writes ship
 *       blocks in Sable's own chunks, as it does in any other dimension.</li>
 *   <li>The carrier gets the Overworld's build range (Y -64 to 319) instead of 16 blocks, because a
 *       plot can only hold a ship as tall as the level's build range.</li>
 * </ul>
 * Everything Sable reads or writes in the world itself (blocks gathered for a ship, terrain the
 * physics engine collides with, blocks placed back on disassembly) goes to the cubes, at any height.
 */
public final class SableCompat {
    /** Read once at class load; the mixins that call in here only apply when Sable is present. */
    public static final boolean LOADED = FMLLoader.getLoadingModList() != null && FMLLoader.getLoadingModList().getModFileById("sable") != null;
    /** The carrier's build range while Sable is installed: the Overworld's. */
    public static final int CARRIER_MIN_Y = -64, CARRIER_HEIGHT = 384;
    /** What the altitude sensor spans in Earth: sea level to 10 km up. */
    public static final int SENSOR_MIN_Y = 63, SENSOR_MAX_Y = 10063;

    private static final int PLOT_ORIGIN = 10000;
    /** SubLevelsBlockSkyMixinHelper.checkSkyWithSublevels; null until first use or if missing. */
    private static MethodHandle shade;
    private static boolean shadeTried;
    /** SubLevelContainer.getContainer, physicsSystem() and its handleBlockChange; null until first use or if missing. */
    private static MethodHandle container, physics, blockChange;
    private static boolean handlesTried;

    private SableCompat() {}

    /** Called once per level as it is built: in the cubic level, leave Sable's plot columns to chunks. */
    public static void keepPlots(Level level) {
        int logSide = staticInt("DEFAULT_LOG_SIZE_LENGTH", 7), logPlot = staticInt("DEFAULT_LOG_PLOT_SIZE", 7);
        int min = PLOT_ORIGIN << logPlot, max = ((PLOT_ORIGIN + (1 << logSide)) << logPlot) - 1;
        ((CubicLevel) level).earthplatform$keepInChunks(min, min, max, max);
    }

    public static boolean tallCarrier(Level level) { return LOADED && Cubic.isCubic(level.dimension()); }

    private static int staticInt(String field, int fallback) {
        try {
            return Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer").getField(field).getInt(null);
        } catch (ReflectiveOperationException | LinkageError failure) {
            EarthPlatformMod.LOGGER.warn("Sable plot size {} not found; assuming {}", field, fallback);
            return fallback;
        }
    }

    /** The cube store for a world block column of a cubic level, or null for other levels and plot columns. */
    @Nullable public static CubeWorld cubes(@Nullable Level level, int blockX, int blockZ) {
        return level == null ? null : CubicLevel.at(level, blockX, blockZ);
    }

    /** Writes a world block for Sable, loading its cube first so a ship never lands in a missing one. */
    @Nullable public static BlockState set(CubeWorld cubes, BlockPos pos, BlockState state, boolean moving) {
        if (cubes.cubeAtBlock(pos.getX(), pos.getY(), pos.getZ()) == null && cubes instanceof ServerCubeWorld server && server.isOwnerThread()) {
            server.loadNow(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        }
        return cubes.setBlockState(pos, state, moving);
    }

    /**
     * Tells Sable's physics about a world block edit in the cubic level, as it hears about chunk edits
     * elsewhere, so ships meet terrain that was dug or built after they arrived.
     */
    public static void worldEdit(ServerLevel level, CubeWorld cubes, BlockPos pos, BlockState old, BlockState state) {
        Cube cube = cubes.cubeAtBlock(pos.getX(), pos.getY(), pos.getZ());
        if (cube == null || !physicsHandles()) return;
        try {
            Object system = physics.invoke(container.invoke(level));
            if (system != null) blockChange.invoke(system, SectionPos.of(pos), cube.section, pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, old, state);
        } catch (Throwable failure) {
            EarthPlatformMod.LOGGER.warn("Sable physics did not take a block change at {}", pos, failure);
            blockChange = null;
        }
    }

    /**
     * Whether a ship overhead shades this spot of the cubic level above the carrier's build top, where
     * Sable's own check stops. Below it, and in other levels, Sable answers.
     */
    public static boolean highShade(Level level, BlockPos pos) {
        if (!LOADED || !Cubic.isCubic(level.dimension()) || pos.getY() < level.getMaxBuildHeight()) return false;
        if (!shadeTried) {
            shadeTried = true;
            try {
                shade = MethodHandles.publicLookup().findStatic(
                        Class.forName("dev.ryanhcode.sable.mixinhelpers.entity.sublevels_block_sky.SubLevelsBlockSkyMixinHelper"),
                        "checkSkyWithSublevels", MethodType.methodType(boolean.class, Level.class, BlockPos.class));
            } catch (ReflectiveOperationException | LinkageError failure) {
                EarthPlatformMod.LOGGER.warn("Sable ship shade check not found; ships won't shade mobs high up", failure);
            }
        }
        if (shade == null) return false;
        try {
            return (boolean) shade.invokeExact(level, pos);
        } catch (Throwable failure) {
            EarthPlatformMod.LOGGER.warn("Sable ship shade check failed at {}", pos, failure);
            shade = null;
            return false;
        }
    }

    private static boolean physicsHandles() {
        if (!handlesTried) {
            handlesTried = true;
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                Class<?> containers = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
                Class<?> server = Class.forName("dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer");
                Class<?> system = Class.forName("dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem");
                container = lookup.findStatic(containers, "getContainer", MethodType.methodType(server, ServerLevel.class));
                physics = lookup.findVirtual(server, "physicsSystem", MethodType.methodType(system));
                blockChange = lookup.findVirtual(system, "handleBlockChange", MethodType.methodType(void.class,
                        SectionPos.class, LevelChunkSection.class, int.class, int.class, int.class, BlockState.class, BlockState.class));
            } catch (ReflectiveOperationException | LinkageError failure) {
                EarthPlatformMod.LOGGER.warn("Sable physics hooks not found; ships won't see terrain edits made after they arrive", failure);
            }
        }
        return blockChange != null;
    }
}
