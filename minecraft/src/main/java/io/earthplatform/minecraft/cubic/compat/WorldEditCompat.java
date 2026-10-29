package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * WorldEdit in the Earth world. Its NeoForge build reads and writes blocks straight through the
 * LevelChunk under each column, which in the cubic level is the empty 16-block carrier, and takes
 * the world's height from that carrier, so every edit outside Y 0 to 15 failed or crashed. The
 * WorldEdit mixins send those reads and writes to the cubes here instead. A cube no player is near
 * is loaded first, so a paste, a fill or an undo reaches it rather than reading it as air; once it
 * unloads its save refreshes the far terrain over it.
 */
public final class WorldEditCompat {
    private static volatile boolean reflected;
    @Nullable private static Field sideEffects;
    @Nullable private static Method shouldApply;
    @Nullable private static Object update;

    private WorldEditCompat() {}

    /** The cube store for a block of a cubic level, with its cube loaded; null where vanilla chunks hold the blocks. */
    @Nullable public static CubeWorld cubes(Level level, BlockPos pos) {
        CubeWorld cubes = CubicLevel.at(level, pos.getX(), pos.getZ());
        if (cubes instanceof ServerCubeWorld server && CubeWorld.inRange(pos.getY()) && !server.isLoaded(pos) && server.isOwnerThread()) {
            server.loadNow(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        }
        return cubes;
    }

    /** Writes a block as WorldEdit's own chunk hook would: {@code access} is its native access, whose side effects say whether to run onPlace. */
    @Nullable public static BlockState write(CubeWorld cubes, BlockPos pos, BlockState state, Object access) {
        if (!CubeWorld.inRange(pos.getY())) return null;
        return cubes.setBlockState(pos, state, false, placeUpdates(access));
    }

    /** The section holding a block's biomes, for //setbiome; the cube is saved and sent to players again. */
    @Nullable public static LevelChunkSection biomeSection(CubeWorld cubes, BlockPos pos) {
        Cube cube = cubes.cubeAtBlock(pos.getX(), pos.getY(), pos.getZ());
        if (cube == null) return null;
        cubes.rewritten(cube);
        return cube.section;
    }

    /**
     * //regen in the Earth world: each block of {@code region} (WorldEdit's Region) is set through
     * {@code extent} (its edit, so //undo restores what was there) to the block the Earth generator
     * makes there, and with biomes on in {@code options} (RegenOptions) its biome too. Each cube is
     * generated once, the same way an unvisited cube is, on the worker threads. Block entity contents, such as a loot
     * chest's table, are not carried over; the block itself is.
     */
    public static void regenerate(ServerLevel level, Object region, Object extent, Object options) {
        if (!(CubicLevel.of(level) instanceof ServerCubeWorld cubes)) throw new IllegalStateException("not a cubic level");
        ClassLoader loader = region.getClass().getClassLoader();
        try {
            Class<?> adapter = Class.forName("com.sk89q.worldedit.neoforge.NeoForgeAdapter", false, loader);
            Class<?> vector = Class.forName("com.sk89q.worldedit.math.BlockVector3", false, loader);
            Class<?> holder = Class.forName("com.sk89q.worldedit.world.block.BlockStateHolder", false, loader);
            Class<?> biomeType = Class.forName("com.sk89q.worldedit.world.biome.BiomeType", false, loader);
            Method toBlockPos = adapter.getMethod("toBlockPos", vector);
            Method adaptState = adapter.getMethod("adapt", BlockState.class);
            Method adaptBiome = adapter.getMethod("adapt", net.minecraft.world.level.biome.Biome.class);
            Method setBlock = Class.forName("com.sk89q.worldedit.extent.OutputExtent", false, loader).getMethod("setBlock", vector, holder);
            Method setBiome = Class.forName("com.sk89q.worldedit.extent.OutputExtent", false, loader).getMethod("setBiome", vector, biomeType);
            boolean biomes = (Boolean) options.getClass().getMethod("shouldRegenBiomes").invoke(options);
            // Every cube the region's box reaches is generated first, on the worker threads, as unvisited cubes are.
            Method x = vector.getMethod("x"), y = vector.getMethod("y"), z = vector.getMethod("z");
            Object min = region.getClass().getMethod("getMinimumPoint").invoke(region), max = region.getClass().getMethod("getMaximumPoint").invoke(region);
            int minY = Math.max((Integer) y.invoke(min), CubeWorld.MIN_Y) >> 4, maxY = Math.min((Integer) y.invoke(max), CubeWorld.MAX_Y) >> 4;
            Map<Long, CompletableFuture<LevelChunkSection>> pending = new HashMap<>();
            for (int cx = (Integer) x.invoke(min) >> 4; cx <= (Integer) x.invoke(max) >> 4; cx++)
                for (int cz = (Integer) z.invoke(min) >> 4; cz <= (Integer) z.invoke(max) >> 4; cz++)
                    for (int cy = minY; cy <= maxY; cy++) {
                        int gx = cx, gy = cy, gz = cz;
                        pending.put(CubeKey.pack(cx, cy, cz), CompletableFuture.supplyAsync(
                                () -> cubes.generator().generate(gx, gy, gz, new ArrayList<>(), new ArrayList<>()), Util.backgroundExecutor()));
                    }
            Map<Long, LevelChunkSection> generated = new HashMap<>();
            for (Map.Entry<Long, CompletableFuture<LevelChunkSection>> cube : pending.entrySet()) generated.put(cube.getKey(), cube.getValue().join());
            for (Object at : (Iterable<?>) region) {
                BlockPos pos = (BlockPos) toBlockPos.invoke(null, at);
                if (!CubeWorld.inRange(pos.getY())) continue;
                int cx = pos.getX() >> 4, cy = pos.getY() >> 4, cz = pos.getZ() >> 4;
                LevelChunkSection section = generated.computeIfAbsent(CubeKey.pack(cx, cy, cz),
                        key -> cubes.generator().generate(cx, cy, cz, new ArrayList<>(), new ArrayList<>()));
                setBlock.invoke(extent, at, adaptState.invoke(null, section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15)));
                if (biomes) {
                    var biome = section.getNoiseBiome((pos.getX() & 15) >> 2, (pos.getY() & 15) >> 2, (pos.getZ() & 15) >> 2);
                    setBiome.invoke(extent, at, adaptBiome.invoke(null, biome.value()));
                }
            }
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException("Regen failed", failure.getCause());
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("This WorldEdit version's //regen can't be run in the Earth world", failure);
        }
    }

    /** WorldEdit's SideEffect.UPDATE for the edit in progress; on unless WorldEdit turned it off. */
    private static boolean placeUpdates(Object access) {
        if (!reflected) reflect(access.getClass());
        if (sideEffects == null || shouldApply == null || update == null) return true;
        try {
            Object set = sideEffects.get(access);
            return set == null || (Boolean) shouldApply.invoke(set, update);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return true;
        }
    }

    private static synchronized void reflect(Class<?> accessClass) {
        if (reflected) return;
        try {
            Field field = accessClass.getDeclaredField("sideEffectSet");
            field.setAccessible(true);
            Class<?> effect = Class.forName("com.sk89q.worldedit.util.SideEffect", false, accessClass.getClassLoader());
            Object value = null;
            for (Object constant : effect.getEnumConstants()) if (((Enum<?>) constant).name().equals("UPDATE")) value = constant;
            Method method = field.getType().getMethod("shouldApply", effect);
            sideEffects = field;
            shouldApply = method;
            update = value;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            EarthPlatformMod.LOGGER.warn("WorldEdit side effects not found; its edits in the Earth world will always run block updates", failure);
        }
        reflected = true;
    }
}
