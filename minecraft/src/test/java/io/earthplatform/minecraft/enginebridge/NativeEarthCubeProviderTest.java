package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.BoundedCubeLoader;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeProvider;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.scene.SceneCommands;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import io.earthplatform.minecraft.scene.SceneTerrain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import static org.junit.jupiter.api.Assertions.*;

/** Accepted offline source and CPU provider contracts, with no GL or gameplay claim. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@Timeout(30)
class NativeEarthCubeProviderTest {
    private static EnginePaletteContext palette;
    @BeforeAll static void registries() { palette = NativeCubeCodecTest.createPalette(); }

    private static <T> T withRelief(boolean relief, Supplier<T> preparation) {
        String previous = System.getProperty(SceneTerrain.NATURAL_RELIEF_OPT_IN);
        try {
            System.setProperty(SceneTerrain.NATURAL_RELIEF_OPT_IN, Boolean.toString(relief));
            return preparation.get();
        } finally {
            if (previous == null) System.clearProperty(SceneTerrain.NATURAL_RELIEF_OPT_IN);
            else System.setProperty(SceneTerrain.NATURAL_RELIEF_OPT_IN, previous);
        }
    }

    private static NativeEarthCubeProvider provider(boolean relief) {
        return withRelief(relief, () -> NativeEarthCubeProvider.prepare(palette));
    }

    private static ImmutableCube load(CubeProvider provider, CubePos position) {
        var future = provider.load(position, new CancellationToken());
        assertTrue(future.isDone(), "The caller's worker must own all generation work");
        return future.join();
    }

    @Test void everyBlockMatchesExistingSceneSnapshotAtActualFixtureInBothReliefModes() {
        for (boolean relief : new boolean[]{false, true}) {
            var accepted = withRelief(relief, SceneTerrain::new);
            var earth = provider(relief);
            var codec = new NativeCubeCodec(earth.identity(), palette);
            long ground = accepted.surfaceY(SceneCommands.START_X, SceneCommands.START_Z);
            CubePos center = CubePos.fromBlock(SceneCommands.START_X, ground, SceneCommands.START_Z);
            if (!relief) assertEquals(7500, ground);
            List<CubePos> positions = new ArrayList<>();
            for (int dy = -1; dy <= 1; dy++) {
                positions.add(center.offset(-1, dy, -1));
                positions.add(center.offset(0, dy, 0));
                positions.add(center.offset(1, dy, 1));
            }
            positions.add(new CubePos(center.x(), -688, center.z()));
            positions.add(new CubePos(center.x(), 553, center.z()));
            for (CubePos position : positions) {
                var expected = accepted.snapshot(position, palette, accepted.world(palette), 3, 7).materialize(palette);
                ImmutableCube actual = load(earth, position);
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                    assertSame(expected.get(x, y, z), Block.BLOCK_STATE_REGISTRY.byId(actual.get(x, y, z)));
                assertEquals(actual, codec.capture(codec.materialize(position, actual, 11, 13, 0)));
                int original = actual.get(0);
                int[] detached = actual.toArray();
                detached[0] = original == 0 ? 1 : 0;
                assertEquals(original, actual.get(0));
                assertEquals(actual, load(earth, position));
            }
            // The optional fixture and analytic worlds use precisely the same full-cube API.
            CubeProvider analytic = new AnalyticNativeCubeProvider(42,
                    AnalyticNativeCubeProvider.Config.flat(ground), palette);
            assertNotNull(load(analytic, center));
        }
    }

    @Test void identityPinsSourcePolicyNativeNumericPaletteAndVersionedGenerator() {
        var earth = provider(false);
        var accepted = withRelief(false, SceneTerrain::new);
        assertEquals(earth.identity(), provider(false).identity());
        assertEquals(NativeEarthCubeProvider.GENERATOR_ID, earth.identity().generatorId());
        assertEquals(NativeEarthCubeProvider.GENERATOR_VERSION, earth.identity().generatorVersion());
        assertEquals(accepted.world(palette).seed(), earth.identity().seed());
        assertEquals(accepted.identity(), earth.identity().datasetVersion());
        assertEquals(NativeCubeCodec.paletteIdentity(palette), earth.identity().blockPaletteVersion());
        assertNotEquals(accepted.world(palette).blockPaletteVersion(), earth.identity().blockPaletteVersion());
        assertNotEquals(earth.identity(), provider(true).identity());
        assertNotEquals(earth.identity(), new AnalyticNativeCubeProvider(earth.identity().seed(),
                AnalyticNativeCubeProvider.Config.flat(7500), palette).identity());
        for (String component : List.of(earth.identity().generatorId(), earth.identity().generatorVersion(),
                earth.identity().datasetVersion(), earth.identity().blockPaletteVersion()))
            assertTrue(component.length() <= 128);
        assertThrows(IllegalArgumentException.class,
                () -> NativeEarthCubeProvider.prepare(new EnginePaletteContext(palette.biomes(), "0".repeat(64))));
        // Later ambient property changes cannot silently change an already prepared world.
        CubePos position = CubePos.fromBlock(SceneCommands.START_X, 7500, SceneCommands.START_Z);
        ImmutableCube before = load(earth, position);
        assertEquals(before, withRelief(true, () -> load(earth, position)));
    }

    @Test void optionalHeightfieldSummaryIsSeparatePinnedAndSharesOnlyGuardedSourceAccess() throws Exception {
        var earth = provider(false);
        SceneSurfaceSampler sampler = earth.surfaceSampler();
        var accepted = withRelief(false, SceneTerrain::new);
        assertFalse(SceneSurfaceSampler.class.isAssignableFrom(NativeEarthCubeProvider.class));
        assertFalse(sampler instanceof SceneTerrain, "Never expose the unguarded source cache");
        assertTrue(sampler.supportsConcurrentReads(), "Only the provider-owned guarded view advertises concurrency");
        assertFalse(accepted.supportsConcurrentReads(), "The mutable raw SceneTerrain cache remains owner-confined");
        assertEquals(accepted.identity(), sampler.identity());
        long x = SceneCommands.START_X, z = SceneCommands.START_Z;
        long y = sampler.surfaceY(x, z);
        assertEquals(accepted.surfaceY(x, z), y);
        assertEquals(accepted.argb(x, z, y), sampler.argb(x, z, y));
        assertThrows(IllegalArgumentException.class, () -> sampler.argb(x, z, y + 1));
        assertNotSame(privateSource(earth), privateSource(provider(false)));
        CubePos position = CubePos.fromBlock(x, y, z);
        var base = load(earth, position);
        int surfaceIndex = CubePos.indexOfBlock(x, y, z);
        var edited = base.withEdits(Map.of(surfaceIndex, Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState())));
        assertNotEquals(base.get(surfaceIndex), edited.get(surfaceIndex));
        assertEquals(y, sampler.surfaceY(x, z), "This explicitly unedited summary is not edit-aware LOD");
    }

    @Test void missingCoverageAndInvalidEnvelopeFailInsteadOfInventingKnownAirOrStone() {
        var earth = provider(false);
        for (int y : new int[]{-688, 0, 553}) assertMissing(earth, new CubePos(0, y, 0));
        CubePos known = CubePos.fromBlock(SceneCommands.START_X, 0, SceneCommands.START_Z);
        for (int y : new int[]{Integer.MIN_VALUE, -689, 554, Integer.MAX_VALUE})
            assertMissing(earth, new CubePos(known.x(), y, known.z()));
        assertMissing(earth, new CubePos(Integer.MAX_VALUE, 0, known.z()));
        assertMissing(earth, new CubePos(known.x(), 0, Integer.MIN_VALUE));
        assertThrows(MissingTerrainException.class, () -> earth.surfaceSampler().surfaceY(0, 0));
        assertThrows(MissingTerrainException.class, () -> earth.surfaceSampler().argb(0, 0, 0));
        assertThrows(MissingTerrainException.class, () -> earth.surfaceSampler().surfaceY(Long.MAX_VALUE, 0));
        ImmutableCube air = load(earth, new CubePos(known.x(), 553, known.z()));
        assertTrue(air.isUniform());
        assertEquals(Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState()), air.get(0));
        ImmutableCube rock = load(earth, new CubePos(known.x(), -688, known.z()));
        assertTrue(rock.isUniform());
        assertEquals(Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState()), rock.get(0));
    }

    private static void assertMissing(NativeEarthCubeProvider provider, CubePos position) {
        var result = provider.load(position, new CancellationToken());
        assertTrue(result.isDone());
        assertInstanceOf(MissingTerrainException.class, assertThrows(CompletionException.class, result::join).getCause());
    }

    @Test void reorderedBoundedConcurrentRequestsAndSummaryReadsRemainDeterministicAcrossLruEviction() throws Exception {
        var baseline = provider(false);
        var concurrent = provider(false);
        CubePos center = CubePos.fromBlock(SceneCommands.START_X, 7500, SceneCommands.START_Z);
        var expected = new HashMap<CubePos, ImmutableCube>();
        for (int z = -4; z < 4; z++) for (int x = -5; x < 5; x++) {
            CubePos position = center.offset(x, Math.floorMod(x + z, 3) - 1, z);
            expected.put(position, load(baseline, position));
        }
        assertTrue(expected.size() * 256 > SceneTerrain.MAX_CACHED_COLUMNS);
        var workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(82));
        try {
            for (int pass = 0; pass < 3; pass++) {
                var order = new ArrayList<>(expected.keySet());
                Collections.shuffle(order, new Random(921 + pass));
                try (var loader = new BoundedCubeLoader(concurrent, workers, 2, order.size(), 1)) {
                    var leases = new ArrayList<BoundedCubeLoader.Lease>();
                    for (CubePos position : order) leases.add(loader.request(position));
                    var summary = workers.submit(() -> {
                        for (CubePos position : order) {
                            long x = position.minBlockX(), z = position.minBlockZ();
                            long y = concurrent.surfaceSampler().surfaceY(x, z);
                            assertEquals(y, baseline.surfaceSampler().surfaceY(x, z));
                            assertEquals(baseline.surfaceSampler().argb(x, z, y), concurrent.surfaceSampler().argb(x, z, y));
                        }
                    });
                    for (var lease : leases) {
                        assertEquals(expected.get(lease.position()), lease.future().get(10, TimeUnit.SECONDS));
                        lease.close();
                    }
                    summary.get(10, TimeUnit.SECONDS);
                    assertEquals(0, loader.stats().running());
                    assertEquals(0, loader.stats().queued());
                }
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertTrue(privateSource(concurrent).cachedColumns() <= SceneTerrain.MAX_CACHED_COLUMNS);
    }

    @Test void cancellationWhileWaitingForOneSourceColumnSettlesBeforeLoaderReleasesItsSlot() throws Exception {
        var earth = provider(false);
        var lockField = NativeEarthCubeProvider.class.getDeclaredField("sourceLock");
        lockField.setAccessible(true);
        Object sourceLock = lockField.get(earth);
        var token = new AtomicReference<CancellationToken>();
        var providerResult = new AtomicReference<CompletableFuture<ImmutableCube>>();
        var thread = new AtomicReference<Thread>();
        var invoked = new CountDownLatch(1);
        var settled = new CountDownLatch(1);
        try (var loader = new BoundedCubeLoader((position, cancellation) -> {
            token.set(cancellation); invoked.countDown();
            var result = earth.load(position, cancellation);
            providerResult.set(result);
            return result;
        }, job -> {
            var worker = new Thread(() -> { try { job.run(); } finally { settled.countDown(); } }, "earth-cancellation-test");
            thread.set(worker); worker.start();
        }, 1, 0, 1)) {
            synchronized (sourceLock) {
                var lease = loader.request(CubePos.fromBlock(SceneCommands.START_X, 7500, SceneCommands.START_Z));
                assertTrue(invoked.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (thread.get().getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield();
                assertEquals(Thread.State.BLOCKED, thread.get().getState());
                lease.close();
                assertTrue(token.get().isCancelled());
                assertEquals(1, loader.stats().running(), "Cancellation cannot free a worker still inside load");
                assertNull(providerResult.get());
            }
            assertTrue(settled.await(5, TimeUnit.SECONDS));
            assertThrows(CancellationException.class, () -> providerResult.get().join());
            assertEquals(0, privateSource(earth).cachedColumns(), "Cancelled lock wait must not sample any source column");
            assertEquals(0, loader.stats().running());
            assertEquals(1, loader.stats().cancelled());
            // A pre-cancelled request also settles synchronously, without consulting coverage.
            var cancelled = earth.load(new CubePos(0, 0, 0), token.get());
            assertTrue(cancelled.isDone());
            assertThrows(CancellationException.class, cancelled::join);
        } finally {
            if (thread.get() != null) thread.get().join(5000);
        }
    }

    @Test void preparedUncachedCubeLoadsAndSummarySamplingPerformNoFileOrSocketIo(@TempDir Path directory) throws Exception {
        var earth = provider(false);
        CubePos center = CubePos.fromBlock(SceneCommands.START_X, 7500, SceneCommands.START_Z);
        // Warm classes before recording; the measured columns below remain absent from the LRU.
        load(earth, center);
        long ground = earth.surfaceSampler().surfaceY(SceneCommands.START_X, SceneCommands.START_Z);
        earth.surfaceSampler().argb(SceneCommands.START_X, SceneCommands.START_Z, ground);
        Path recordingPath = directory.resolve("prepared-earth-load.jfr");
        try (var recording = new Recording()) {
            for (String event : List.of("jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite"))
                recording.enable(event).withThreshold(Duration.ZERO).withStackTrace();
            recording.start();
            // A positive control prevents a disabled recorder from making this an empty assertion.
            Files.writeString(directory.resolve("io-positive-control.txt"), "record this write");
            for (int offset = 1; offset <= 4; offset++) {
                load(earth, center.offset(offset, 0, -offset));
                long x = center.minBlockX() + offset * 32L, z = center.minBlockZ();
                long y = earth.surfaceSampler().surfaceY(x, z);
                earth.surfaceSampler().argb(x, z, y);
            }
            recording.stop();
            recording.dump(recordingPath);
        }
        var events = RecordingFile.readAllEvents(recordingPath);
        assertTrue(events.stream().anyMatch(event -> event.getEventType().getName().equals("jdk.FileWrite")
                && event.getString("path").endsWith("io-positive-control.txt")));
        for (var event : events) {
            if (event.getStackTrace() == null) continue;
            boolean providerIo = event.getStackTrace().getFrames().stream().anyMatch(frame ->
                    (frame.getMethod().getType().getName().equals(NativeEarthCubeProvider.class.getName())
                            || frame.getMethod().getType().getName().startsWith(NativeEarthCubeProvider.class.getName() + "$")));
            assertFalse(providerIo, () -> "Unexpected sampling I/O: " + event);
        }
    }

    @Test void explicitDescriptorPolicyIgnoresAmbientPropertiesWhileLegacyDefaultsRemainIdentical() {
        var explicitRelief = withRelief(false, () -> NativeEarthCubeProvider.prepare(palette, true));
        var explicitRaw = withRelief(true, () -> NativeEarthCubeProvider.prepare(palette, false));
        assertEquals(provider(true).identity(), explicitRelief.identity());
        assertEquals(provider(false).identity(), explicitRaw.identity());
        assertNotEquals(explicitRaw.identity(), explicitRelief.identity());
        var reliefSource = withRelief(false, () -> new SceneTerrain(true));
        var rawSource = withRelief(true, () -> new SceneTerrain(false));
        assertTrue(reliefSource.naturalRelief()); assertFalse(rawSource.naturalRelief());
        assertTrue(explicitRelief.naturalRelief()); assertFalse(explicitRaw.naturalRelief());
        assertEquals(reliefSource.sourceUrl(),explicitRelief.sourceUrl());
        assertEquals(reliefSource.datum(),explicitRelief.verticalDatum());
        assertEquals(rawSource.sourceUrl(),explicitRaw.sourceUrl());
        assertEquals(rawSource.datum(),explicitRaw.verticalDatum());
        assertTrue(withRelief(false,explicitRelief::naturalRelief));
        assertFalse(withRelief(true,explicitRaw::naturalRelief));
        assertEquals(reliefSource.identity(), explicitRelief.surfaceSampler().identity());
        assertEquals(rawSource.identity(), explicitRaw.surfaceSampler().identity());
        assertFalse(reliefSource.supportsConcurrentReads()); assertFalse(rawSource.supportsConcurrentReads());
        long x=SceneCommands.START_X, z=SceneCommands.START_Z;
        CubePos cube=CubePos.fromBlock(x, explicitRelief.surfaceSampler().surfaceY(x,z), z);
        var pinned=load(explicitRelief,cube);
        assertEquals(pinned,withRelief(false,()->load(explicitRelief,cube)));
        assertEquals(pinned,withRelief(true,()->load(explicitRelief,cube)));
    }

    @Test void advertisedSummaryCapabilityReallySharesTheGenerationSourceLock() throws Exception {
        var earth=NativeEarthCubeProvider.prepare(palette,true);
        var field=NativeEarthCubeProvider.class.getDeclaredField("sourceLock");field.setAccessible(true);
        Object lock=field.get(earth);var entered=new CountDownLatch(1);var thread=new AtomicReference<Thread>();
        var workers=java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<Long> result;
        try {
            synchronized(lock) {
                result=workers.submit(()->{thread.set(Thread.currentThread());entered.countDown();return earth.surfaceSampler().surfaceY(SceneCommands.START_X,SceneCommands.START_Z);});
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(thread.get().getState()!=Thread.State.BLOCKED&&System.nanoTime()<deadline)Thread.onSpinWait();
                assertEquals(Thread.State.BLOCKED,thread.get().getState());assertFalse(result.isDone());
                assertEquals(0,privateSource(earth).cachedColumns());
            }
            assertEquals(new SceneTerrain(true).surfaceY(SceneCommands.START_X,SceneCommands.START_Z),result.get(5,TimeUnit.SECONDS));
        } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void explicitNaturalSourceSupportsTwoGeneratorsAndIndependentSummaryThroughEviction() throws Exception {
        var baseline=NativeEarthCubeProvider.prepare(palette,true);var earth=NativeEarthCubeProvider.prepare(palette,true);
        CubePos center=CubePos.fromBlock(SceneCommands.START_X,baseline.surfaceSampler().surfaceY(SceneCommands.START_X,SceneCommands.START_Z),SceneCommands.START_Z);
        var positions=new ArrayList<CubePos>();var expected=new HashMap<CubePos,ImmutableCube>();
        for(int z=-4;z<4;z++)for(int x=-5;x<5;x++){var pos=center.offset(x,Math.floorMod(x+z,3)-1,z);positions.add(pos);expected.put(pos,load(baseline,pos));}
        var workers=java.util.concurrent.Executors.newFixedThreadPool(3);var started=new CountDownLatch(3);var go=new CountDownLatch(1);
        var results=new ArrayList<java.util.concurrent.Future<?>>();
        try {
            for(int side=0;side<2;side++){final int parity=side;results.add(workers.submit(()->{started.countDown();awaitSourceGate(go);for(int i=parity;i<positions.size();i+=2){var pos=positions.get(i);assertEquals(expected.get(pos),load(earth,pos));}}));}
            results.add(workers.submit(()->{started.countDown();awaitSourceGate(go);var actual=earth.surfaceSampler();var wanted=baseline.surfaceSampler();
                for(var pos:positions)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                    long ax=pos.minBlockX()+x,az=pos.minBlockZ()+z,y=actual.surfaceY(ax,az);
                    assertEquals(wanted.surfaceY(ax,az),y);assertEquals(wanted.argb(ax,az,y),actual.argb(ax,az,y));
                }
            }));
            assertTrue(started.await(5,TimeUnit.SECONDS));go.countDown();for(var result:results)result.get(20,TimeUnit.SECONDS);
        } finally {go.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
        assertTrue(positions.size()*256>SceneTerrain.MAX_CACHED_COLUMNS);
        assertTrue(privateSource(earth).cachedColumns()<=SceneTerrain.MAX_CACHED_COLUMNS);
        assertEquals(baseline.identity(),earth.identity());assertTrue(earth.surfaceSampler().supportsConcurrentReads());
    }
    private static void awaitSourceGate(CountDownLatch gate) {
        try {if(!gate.await(5,TimeUnit.SECONDS))throw new AssertionError("Source start gate timed out");}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
    }

    private static SceneTerrain privateSource(NativeEarthCubeProvider provider) throws Exception {
        var field = NativeEarthCubeProvider.class.getDeclaredField("source");
        field.setAccessible(true);
        return (SceneTerrain) field.get(provider);
    }
}
