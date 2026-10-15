package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * Chunky pre-generation in the Earth world. Chunky asks the level for full vanilla chunks, which
 * here are the empty 16-block carriers: the Earth itself lives in cubes, made on demand from map
 * data. What a first visit waits on is that data's download, so for each chunk Chunky asks for,
 * this makes the chunk's surface cube the way the server would, which downloads and caches the
 * elevation, land cover, climate, rivers and imagery it reads, and throws the cube away. Once per
 * {@link #CELL}-block square it also fetches what the far terrain there reads. Later visits then
 * make cubes from the disk cache. Cubes are not saved, so terrain updates in later versions still
 * show; carrier chunks are left for the game to make when players come.
 */
public final class ChunkyCompat {
    /** Squares, in blocks, that the far terrain's data is fetched for once each. */
    static final int CELL = 2048;
    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2)), runnable -> {
        Thread thread = new Thread(runnable, "Earth Chunky worker " + THREADS.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static final Set<Long> CELLS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger FAILURES = new AtomicInteger();
    private static final AtomicLong LAST_WARNING = new AtomicLong();

    private ChunkyCompat() {}

    /** The Earth generator behind a level, or null where Chunky should work as usual. */
    @Nullable static CubeGenerator generator(ServerLevel level) {
        return CubicLevel.of(level) instanceof ServerCubeWorld cubes ? cubes.generator() : null;
    }

    /** Whether a chunk counts as done before Chunky asks for it: never in the Earth, since only data is cached. Null elsewhere. */
    @Nullable public static CompletableFuture<Boolean> generated(ServerLevel level) {
        return generator(level) == null ? null : CompletableFuture.completedFuture(false);
    }

    /** Prepares a chunk's column in the Earth world, or null where Chunky should generate as usual. */
    @Nullable public static CompletableFuture<Void> generate(ServerLevel level, int chunkX, int chunkZ) {
        CubeGenerator generator = generator(level);
        if (generator == null) return null;
        return CompletableFuture.runAsync(() -> prepare(generator, chunkX, chunkZ), WORKERS);
    }

    static void prepare(CubeGenerator generator, int chunkX, int chunkZ) {
        int x = (chunkX << 4) + 8, z = (chunkZ << 4) + 8;
        try {
            if (CELLS.add(cell(x, z))) generator.prepare(Math.floorDiv(x, CELL) * CELL + CELL / 2, Math.floorDiv(z, CELL) * CELL + CELL / 2);
            List<CompoundTag> blockEntities = new ArrayList<>(), entities = new ArrayList<>();
            generator.generate(chunkX, generator.surfaceY(x, z) >> 4, chunkZ, blockEntities, entities);
        } catch (RuntimeException failure) {
            // Chunky carries on with the next chunk; with a data server down, each one fails, so tell it now and then.
            int failures = FAILURES.incrementAndGet();
            long now = System.currentTimeMillis(), last = LAST_WARNING.get();
            if (now - last > 30_000 && LAST_WARNING.compareAndSet(last, now)) {
                EarthPlatformMod.LOGGER.warn("Chunky: could not fetch Earth data for chunk {}, {} ({} chunks so far); those places download when visited",
                        chunkX, chunkZ, failures, failure);
            }
        }
    }

    private static long cell(int x, int z) {
        return (long) Math.floorDiv(x, CELL) << 32 | Math.floorDiv(z, CELL) & 0xFFFFFFFFL;
    }
}
