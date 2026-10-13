package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeKey;
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
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
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

    /** Section indices handed to Sable in the cubic level count from the lowest cube, so every height has one. */
    private static final int MIN_SECTION = CubeWorld.MIN_Y >> 4;
    public static final int SECTION_COUNT = (CubeWorld.MAX_Y - CubeWorld.MIN_Y + 1) >> 4;

    private static final int PLOT_ORIGIN = 10000;
    private static LevelChunkSection empty;
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

    public static int sectionIndex(int sectionY) { return sectionY - MIN_SECTION; }

    /**
     * The section Sable asked for by a cubic index (see {@link #sectionIndex}): the cube's own storage
     * for world columns, loading it on the server if needed, or the plot chunk's section.
     */
    public static LevelChunkSection section(LevelChunk chunk, int index) {
        int sectionY = index + MIN_SECTION;
        Level level = chunk.getLevel();
        CubeWorld cubes = cubes(level, chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ());
        if (cubes == null) {
            int own = sectionY - chunk.getMinSection();
            return own >= 0 && own < chunk.getSectionsCount() ? chunk.getSection(own) : empty(level);
        }
        int x = chunk.getPos().x, z = chunk.getPos().z;
        Cube cube = cubes.cube(CubeKey.pack(x, sectionY, z));
        if (cube == null && cubes instanceof ServerCubeWorld server && server.isOwnerThread()) cube = server.loadNow(x, sectionY, z);
        return cube != null ? cube.section : empty(level);
    }

    private static LevelChunkSection empty(Level level) {
        LevelChunkSection section = empty;
        if (section == null) empty = section = new LevelChunkSection(level.registryAccess().registryOrThrow(Registries.BIOME));
        return section;
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
