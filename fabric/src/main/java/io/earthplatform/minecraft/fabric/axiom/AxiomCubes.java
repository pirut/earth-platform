package io.earthplatform.minecraft.fabric.axiom;

import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.fabric.axiom.mixin.HeightmapAccess;
import io.earthplatform.minecraft.fabric.axiom.mixin.LightEngineAccess;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * What Axiom sees of a cubic level. Axiom reads and writes chunk sections directly, so
 * {@link AxiomMixins} points its calls here: in Earth worlds the chunk's height is the cubic
 * world's, its sections are the cubes', and blocks Axiom writes into a cube are folded into the
 * cube store's tops, light and what players hold once the tick ends. Everything else passes through.
 */
public final class AxiomCubes {
    private static final int MIN_SECTION = CubeWorld.MIN_Y >> 4, MAX_SECTION = (CubeWorld.MAX_Y >> 4) + 1;

    private record Owner(CubeWorld cubes, Cube cube) {}

    /** The cube behind each section handed to Axiom, so writes into it can be folded in. */
    private static final Map<LevelChunkSection, Owner> OWNERS = Collections.synchronizedMap(new WeakHashMap<>());
    /** One empty, never written section per level, for cubes that are not loaded. */
    private static final Map<Level, LevelChunkSection> EMPTY = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<LevelChunkSection> EMPTIES = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    /** Where the last empty section handed out on this thread stands, should Axiom write into it. */
    private static final ThreadLocal<long[]> LAST_EMPTY = new ThreadLocal<>();
    private static final ThreadLocal<CubeWorld> LAST_EMPTY_WORLD = new ThreadLocal<>();
    private static final Map<CubeWorld, Set<Cube>> WRITTEN = new WeakHashMap<>();

    private AxiomCubes() {}

    @Nullable private static CubeWorld cubic(Object of) {
        if (of instanceof Level level) return CubicLevel.of(level);
        if (of instanceof LevelChunk chunk) return CubicLevel.of(chunk.getLevel());
        return null;
    }

    // Height -------------------------------------------------------------------------------------

    public static int minBuildHeight(LevelHeightAccessor of) { return cubic(of) != null ? CubeWorld.MIN_Y : of.getMinBuildHeight(); }
    public static int maxBuildHeight(LevelHeightAccessor of) { return cubic(of) != null ? CubeWorld.MAX_Y + 1 : of.getMaxBuildHeight(); }
    public static int height(LevelHeightAccessor of) { return cubic(of) != null ? CubeWorld.MAX_Y + 1 - CubeWorld.MIN_Y : of.getHeight(); }
    public static int minSection(LevelHeightAccessor of) { return cubic(of) != null ? MIN_SECTION : of.getMinSection(); }
    public static int maxSection(LevelHeightAccessor of) { return cubic(of) != null ? MAX_SECTION : of.getMaxSection(); }
    public static int sectionsCount(LevelHeightAccessor of) { return cubic(of) != null ? MAX_SECTION - MIN_SECTION : of.getSectionsCount(); }
    public static int sectionIndex(LevelHeightAccessor of, int y) { return cubic(of) != null ? (y >> 4) - MIN_SECTION : of.getSectionIndex(y); }
    public static int sectionIndexFromSectionY(LevelHeightAccessor of, int sectionY) {
        return cubic(of) != null ? sectionY - MIN_SECTION : of.getSectionIndexFromSectionY(sectionY);
    }
    public static int sectionYFromSectionIndex(LevelHeightAccessor of, int index) {
        return cubic(of) != null ? index + MIN_SECTION : of.getSectionYFromSectionIndex(index);
    }
    public static boolean isOutsideBuildHeight(LevelHeightAccessor of, BlockPos pos) {
        return cubic(of) != null ? !CubeWorld.inRange(pos.getY()) : of.isOutsideBuildHeight(pos);
    }
    public static boolean isOutsideBuildHeight(LevelHeightAccessor of, int y) {
        return cubic(of) != null ? !CubeWorld.inRange(y) : of.isOutsideBuildHeight(y);
    }

    // Sections -----------------------------------------------------------------------------------

    public static LevelChunkSection getSection(ChunkAccess chunk, int index) {
        CubeWorld cubes = cubic(chunk);
        if (cubes == null) return chunk.getSection(index);
        int x = chunk.getPos().x, y = index + MIN_SECTION, z = chunk.getPos().z;
        Cube cube = cubes.cube(CubeKey.pack(x, y, z));
        if (cube != null) {
            OWNERS.put(cube.section, new Owner(cubes, cube));
            return cube.section;
        }
        LAST_EMPTY.set(new long[] {x, y, z});
        LAST_EMPTY_WORLD.set(cubes);
        Level level = ((LevelChunk) chunk).getLevel();
        return EMPTY.computeIfAbsent(level, l -> {
            LevelChunkSection empty = new LevelChunkSection(l.registryAccess().registryOrThrow(Registries.BIOME));
            EMPTIES.add(empty);
            return empty;
        });
    }

    public static BlockState setBlockState(LevelChunkSection section, int x, int y, int z, BlockState state, boolean lock) {
        Owner owner = OWNERS.get(section);
        if (owner == null && EMPTIES.contains(section)) {
            // A server write where no cube is loaded yet: load or generate it, then write there.
            long[] at = LAST_EMPTY.get();
            if (at == null || !(LAST_EMPTY_WORLD.get() instanceof ServerCubeWorld server)) return Blocks.AIR.defaultBlockState();
            Cube cube = server.loadNow((int) at[0], (int) at[1], (int) at[2]);
            if (cube == null) return Blocks.AIR.defaultBlockState();
            owner = new Owner(server, cube);
            OWNERS.put(cube.section, owner);
        }
        if (owner == null) return section.setBlockState(x, y, z, state, lock);
        BlockState old = owner.cube.section.setBlockState(x, y, z, state, lock);
        if (old != state) {
            synchronized (WRITTEN) { WRITTEN.computeIfAbsent(owner.cubes, w -> new LinkedHashSet<>()).add(owner.cube); }
        }
        return old;
    }

    /** Folds what Axiom wrote into the cubes of one side: client stores on the client thread, server ones on the server's. */
    public static void flush(boolean client) {
        Map<CubeWorld, Set<Cube>> due = new IdentityHashMap<>();
        synchronized (WRITTEN) {
            WRITTEN.entrySet().removeIf(entry -> {
                if (entry.getKey().level().isClientSide != client) return false;
                due.put(entry.getKey(), entry.getValue());
                return true;
            });
        }
        due.forEach((cubes, written) -> {
            for (Cube cube : written) if (cubes.cube(cube.key) == cube) cubes.rewritten(cube);
        });
    }

    // Block entities, block states and derived data of a cubic column's carrier chunk -------------

    public static BlockState chunkBlockState(LevelChunk chunk, BlockPos pos) {
        CubeWorld cubes = cubic(chunk);
        return cubes != null ? cubes.getBlockState(pos) : chunk.getBlockState(pos);
    }

    @Nullable public static BlockEntity chunkBlockEntity(LevelChunk chunk, BlockPos pos, LevelChunk.EntityCreationType type) {
        CubeWorld cubes = cubic(chunk);
        return cubes != null ? cubes.getBlockEntity(pos, type == LevelChunk.EntityCreationType.IMMEDIATE) : chunk.getBlockEntity(pos, type);
    }

    public static void removeBlockEntity(LevelChunk chunk, BlockPos pos) {
        CubeWorld cubes = cubic(chunk);
        if (cubes != null) cubes.removeBlockEntity(pos); else chunk.removeBlockEntity(pos);
    }

    public static void setBlockEntity(LevelChunk chunk, BlockEntity entity) {
        CubeWorld cubes = cubic(chunk);
        if (cubes != null) cubes.setBlockEntity(entity); else chunk.setBlockEntity(entity);
    }

    public static void addAndRegisterBlockEntity(LevelChunk chunk, BlockEntity entity) {
        CubeWorld cubes = cubic(chunk);
        if (cubes != null) cubes.addAndRegisterBlockEntity(entity); else chunk.addAndRegisterBlockEntity(entity);
    }

    /** The cube store keeps its own tops; the carrier's heightmaps only span the carrier's height. */
    public static boolean updateHeightmap(Heightmap heightmap, int x, int y, int z, BlockState state) {
        return cubic(((HeightmapAccess) heightmap).earthplatform$chunk()) == null && heightmap.update(x, y, z, state);
    }

    public static boolean updateSkyLightSources(ChunkSkyLightSources sources, BlockGetter chunk, int x, int y, int z) {
        return cubic(chunk) == null && sources.update(chunk, x, y, z);
    }

    private static volatile MethodHandle pendingLightUpdates;

    /**
     * Axiom marks the blocks to relight in a per-section array of the carrier; in a cubic level the
     * marks go to a scratch array, since the cubes Axiom wrote relight whole once rewritten.
     */
    public static short[] pendingLightUpdates(Object level, int x, int y, int z) throws Throwable {
        if (cubic(level) != null) return new short[16 * 16];
        MethodHandle handle = pendingLightUpdates;
        if (handle == null) {
            pendingLightUpdates = handle = MethodHandles.publicLookup().findVirtual(
                    Class.forName("com.moulberry.axiom.hooks.ServerLevelExt", false, level.getClass().getClassLoader()),
                    "axiom$getPendingLightUpdates", MethodType.methodType(short[].class, int.class, int.class, int.class))
                    .asType(MethodType.methodType(short[].class, Object.class, int.class, int.class, int.class));
        }
        return (short[]) handle.invokeExact(level, x, y, z);
    }

    /** Cubes light themselves once rewritten; the carrier's light engine has no sections out there. */
    public static void updateSectionStatus(LevelLightEngine engine, SectionPos pos, boolean empty) {
        if (cubic(((LightEngineAccess) engine).earthplatform$heightAccessor()) == null) engine.updateSectionStatus(pos, empty);
    }
}
