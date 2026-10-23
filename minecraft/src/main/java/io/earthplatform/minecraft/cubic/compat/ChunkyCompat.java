package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.util.NamedThreads;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;

/**
 * Chunky pre-generation in the Earth world. Chunky asks the level for full vanilla chunks, which
 * here are the empty 16-block carriers: the Earth itself lives in cubes, made on demand from map
 * data. For each chunk Chunky asks for, this generates and saves the cubes of that column from
 * just below the ground to the tops of its trees ({@link ServerCubeWorld#pregenerate}), so a visit
 * loads them from the save. Once per {@link #CELL}-block square it also fetches the map data the
 * far terrain there reads. Carrier chunks are left for the game to make when players come.
 */
public final class ChunkyCompat {
    /** Squares, in blocks, that the far terrain's data is fetched for once each. */
    static final int CELL = 2048;
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2)),
            NamedThreads.numbered("Cubic Chunky worker", Thread.MIN_PRIORITY));
    private static final Set<Long> CELLS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger FAILURES = new AtomicInteger();
    private static final AtomicLong LAST_WARNING = new AtomicLong();

    private ChunkyCompat() {}

    /** The Earth's cubes behind a level, or null where Chunky should work as usual. */
    @Nullable static ServerCubeWorld cubes(ServerLevel level) {
        return CubicLevel.of(level) instanceof ServerCubeWorld cubes ? cubes : null;
    }

    /**
     * Whether a chunk counts as done before Chunky asks for it: never in the Earth, where knowing needs
     * the ground's height; asking again keeps the cubes already saved. Null elsewhere.
     */
    @Nullable public static CompletableFuture<Boolean> generated(ServerLevel level) {
        return cubes(level) == null ? null : CompletableFuture.completedFuture(false);
    }

    /** Generates and saves a chunk's column in the Earth world, or null where Chunky should generate as usual. */
    @Nullable public static CompletableFuture<Void> generate(ServerLevel level, int chunkX, int chunkZ) {
        ServerCubeWorld cubes = cubes(level);
        if (cubes == null) return null;
        return CompletableFuture.runAsync(() -> pregenerate(cubes, chunkX, chunkZ), WORKERS);
    }

    static void pregenerate(ServerCubeWorld cubes, int chunkX, int chunkZ) {
        int x = (chunkX << 4) + 8, z = (chunkZ << 4) + 8;
        try {
            CubeGenerator generator = cubes.generator();
            if (CELLS.add(cell(x, z))) generator.prepare(Math.floorDiv(x, CELL) * CELL + CELL / 2, Math.floorDiv(z, CELL) * CELL + CELL / 2);
            cubes.pregenerate(chunkX, chunkZ);
        } catch (RuntimeException failure) {
            // Chunky carries on with the next chunk; with a data server down, each one fails, so tell it now and then.
            int failures = FAILURES.incrementAndGet();
            long now = System.currentTimeMillis(), last = LAST_WARNING.get();
            if (now - last > 30_000 && LAST_WARNING.compareAndSet(last, now)) {
                Cubic.LOGGER.warn("Chunky: could not generate cubic chunk {}, {} ({} chunks so far); those places generate when visited",
                        chunkX, chunkZ, failures, failure);
            }
        }
    }

    private static long cell(int x, int z) {
        return (long) Math.floorDiv(x, CELL) << 32 | Math.floorDiv(z, CELL) & 0xFFFFFFFFL;
    }
}
