package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Lifecycle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Blocks and sky light read from other threads, as Flywheel's workers read them while building and relighting machines. */
class CubeWorldSharedReadsTest {
    private static Registry<Biome> biomes;
    private static final BlockPos RAIL = new BlockPos(5, 1600, 9);

    @BeforeAll static void registries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).listElements()
                .forEach(h -> registry.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        biomes = registry.freeze();
    }

    private static CubeWorld world(boolean shared) {
        return new CubeWorld(null, shared) {
            @Override protected int baselineTop(int x, int z) { return 0; }
            @Override public void blockChanged(BlockPos pos) {}
        };
    }

    private static Cube railCube() {
        LevelChunkSection section = new LevelChunkSection(biomes);
        section.setBlockState(RAIL.getX() & 15, RAIL.getY() & 15, RAIL.getZ() & 15, Blocks.RAIL.defaultBlockState());
        return new Cube(RAIL.getX() >> 4, RAIL.getY() >> 4, RAIL.getZ() >> 4, section);
    }

    private static <T> T onAnotherThread(java.util.function.Supplier<T> read) throws InterruptedException {
        AtomicReference<T> out = new AtomicReference<>();
        Thread thread = new Thread(() -> out.set(read.get()));
        thread.start();
        thread.join();
        return out.get();
    }

    @Test void anotherThreadSeesALoadedRailNotVoidAir() throws Exception {
        CubeWorld cubes = world(true);
        Cube cube = railCube();
        cubes.adopt(cube);
        assertEquals(Blocks.RAIL, onAnotherThread(() -> cubes.getBlockState(RAIL)).getBlock());
        assertTrue(onAnotherThread(() -> cubes.isLoaded(RAIL)));
        cubes.forget(cube);
        assertEquals(CubeWorld.UNLOADED, onAnotherThread(() -> cubes.getBlockState(RAIL)));
    }

    @Test void withoutSharedReadsOtherThreadsStillSeeNothing() throws Exception {
        // The server's cube world keeps its owner-only rule.
        CubeWorld cubes = world(false);
        cubes.adopt(railCube());
        assertEquals(Blocks.RAIL, cubes.getBlockState(RAIL).getBlock());
        assertEquals(CubeWorld.UNLOADED, onAnotherThread(() -> cubes.getBlockState(RAIL)));
    }

    @Test void anotherThreadReadsSkyTops() throws Exception {
        CubeWorld cubes = world(true);
        int[] column = new int[256];
        java.util.Arrays.fill(column, 2000);
        cubes.putTops(CubeKey.column(RAIL.getX() >> 4, RAIL.getZ() >> 4), column);
        // Under a roof at Y 2000: no sky light at the rail, from either thread.
        assertEquals(0, cubes.getBrightness(LightLayer.SKY, RAIL));
        assertEquals(0, onAnotherThread(() -> cubes.getBrightness(LightLayer.SKY, RAIL)));
        cubes.clearTops();
        assertEquals(15, onAnotherThread(() -> cubes.getBrightness(LightLayer.SKY, RAIL)));
    }

    /** As LevelChunk.setBlockEntity, a block entity replacing another with the same block state is reported too. */
    @Test void sameStateReplacementIsReported() {
        List<net.minecraft.world.level.block.entity.BlockEntity> stored = new ArrayList<>();
        CubeWorld cubes = new CubeWorld(null, true) {
            @Override protected int baselineTop(int x, int z) { return 0; }
            @Override public void blockChanged(BlockPos pos) {}
            @Override protected void blockEntityStored(net.minecraft.world.level.block.entity.BlockEntity entity) { stored.add(entity); }
        };
        LevelChunkSection section = new LevelChunkSection(biomes);
        BlockState chest = Blocks.CHEST.defaultBlockState();
        section.setBlockState(RAIL.getX() & 15, RAIL.getY() & 15, RAIL.getZ() & 15, chest);
        cubes.adopt(new Cube(RAIL.getX() >> 4, RAIL.getY() >> 4, RAIL.getZ() >> 4, section));
        var first = new net.minecraft.world.level.block.entity.ChestBlockEntity(RAIL, chest);
        var second = new net.minecraft.world.level.block.entity.ChestBlockEntity(RAIL, chest);
        assertTrue(cubes.setBlockEntity(first));
        assertTrue(cubes.setBlockEntity(second));
        assertEquals(List.of(first, second), stored);
        assertTrue(first.isRemoved());
    }

    /** A cube sent again replaces the loaded one; readers on other threads never find it missing meanwhile. */
    @Test void replacingACubeNeverLeavesAHole() throws Exception {
        CubeWorld cubes = world(true);
        cubes.adopt(railCube());
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger(), missing = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(3);
        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            Thread reader = new Thread(() -> {
                started.countDown();
                try {
                    while (!done.get()) {
                        if (cubes.getBlockState(RAIL).getBlock() != Blocks.RAIL || !cubes.isLoaded(RAIL)) missing.incrementAndGet();
                        reads.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            reader.start();
            readers.add(reader);
        }
        started.await();
        for (int round = 0; round < 20_000; round++) cubes.adopt(railCube());
        done.set(true);
        for (Thread reader : readers) reader.join();
        if (failure.get() != null) fail(failure.get());
        assertEquals(0, missing.get());
        assertTrue(reads.get() > 0);
    }

    @Test void setTopNeverChangesASharedArray() {
        CubeWorld cubes = world(true);
        long column = CubeKey.column(RAIL.getX() >> 4, RAIL.getZ() >> 4);
        int[] shared = new int[256];
        cubes.putTops(column, shared);
        cubes.setTop(RAIL.getX(), RAIL.getZ(), 1500);
        assertEquals(0, shared[(RAIL.getZ() & 15) << 4 | (RAIL.getX() & 15)], "the array readers hold is left as it was");
        assertEquals(1500, cubes.skyTop(RAIL.getX(), RAIL.getZ()));
        assertNotSame(shared, cubes.columnTops(RAIL.getX() >> 4, RAIL.getZ() >> 4));
    }

    /** The owner loads, unloads and edits cubes while other threads read them. */
    @Test void readersDuringLoadsAndEditsSeeOnlyRealStates() throws Exception {
        CubeWorld cubes = world(true);
        BlockState rail = Blocks.RAIL.defaultBlockState(), stone = Blocks.STONE.defaultBlockState();
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger(), wrong = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(3);
        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            Thread reader = new Thread(() -> {
                started.countDown();
                try {
                    while (!done.get()) {
                        BlockState seen = cubes.getBlockState(RAIL);
                        if (seen != rail && seen != stone && seen != CubeWorld.UNLOADED) wrong.incrementAndGet();
                        // Sky tops: the baseline (0), or one of the two the owner writes through setTop.
                        int top = cubes.skyTop(RAIL.getX(), RAIL.getZ());
                        if (top != 0 && top != 1500 && top != 1700) wrong.incrementAndGet();
                        int sky = cubes.getBrightness(LightLayer.SKY, RAIL);
                        if (sky != 0 && sky != 15) wrong.incrementAndGet();
                        reads.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            reader.start();
            readers.add(reader);
        }
        started.await();
        long column = CubeKey.column(RAIL.getX() >> 4, RAIL.getZ() >> 4);
        for (int round = 0; round < 2_000; round++) {
            Cube cube = railCube();
            cubes.adopt(cube);
            cubes.putTops(column, new int[256]);
            // The roof over the rail rises and falls, through the owner's own sky-top edits.
            for (int edit = 0; edit < 4; edit++) cubes.setTop(RAIL.getX(), RAIL.getZ(), edit % 2 == 0 ? 1500 : 1700);
            // Neighbouring cubes, so the owner's maps grow and rehash under the readers.
            for (int n = 1; n <= 8; n++) cubes.adopt(new Cube(cube.x + n, cube.y, cube.z, new LevelChunkSection(biomes)));
            // Each write grows the palette of a fresh section, the case that tears an unguarded read.
            for (int edit = 0; edit < 4; edit++) cube.set(RAIL.getX() & 15, RAIL.getY() & 15, RAIL.getZ() & 15, edit % 2 == 0 ? stone : rail, true);
            cubes.forget(cube);
            if (round % 100 == 99) cubes.clearTops();
        }
        done.set(true);
        for (Thread reader : readers) reader.join();
        if (failure.get() != null) fail(failure.get());
        assertEquals(0, wrong.get());
        assertTrue(reads.get() > 0);
    }
}
