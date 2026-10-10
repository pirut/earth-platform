package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.BoundedCubeLoader;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnalyticNativeCubeProviderTest {
    private static EnginePaletteContext palette;
    @BeforeAll static void registries() { palette = NativeCubeCodecTest.createPalette(); }

    private static AnalyticNativeCubeProvider provider(AnalyticNativeCubeProvider.Config config) {
        return new AnalyticNativeCubeProvider(42, config, palette);
    }
    private static int read(AnalyticNativeCubeProvider provider, long x, long y, long z) {
        return provider.load(CubePos.fromBlock(x, y, z), new CancellationToken()).join().get(CubePos.indexOfBlock(x, y, z));
    }

    @Test void flatAndVoidFixturesUseTrueSignedYIncluding8849AndMinus11000() {
        for (long height : new long[]{-11000, -1, 0, 8849}) {
            var flat = provider(AnalyticNativeCubeProvider.Config.flat(height));
            assertEquals(flat.config().solidState(), read(flat, -1, height, -17));
            assertEquals(flat.config().airState(), read(flat, -1, height + 1, -17));
            assertEquals(flat.config().solidState(), read(flat, -1, height - 1, -17));
            assertEquals(CubePos.section(height), CubePos.fromBlock(-1, height, -17).y());
        }
        var empty = provider(AnalyticNativeCubeProvider.Config.voidWorld());
        for (int y : new int[]{Integer.MIN_VALUE, -688, -1, 0, 553, Integer.MAX_VALUE}) {
            ImmutableCube cube = empty.load(new CubePos(-1, y, -1), new CancellationToken()).join();
            assertTrue(cube.isUniform());
            assertEquals(4, cube.payloadBytes());
            assertEquals(empty.config().airState(), cube.get(0));
        }
    }

    @Test void slopeUsesFloorArithmeticAcrossNegativeAndPositiveCubeBoundaries() {
        var slope = provider(AnalyticNativeCubeProvider.Config.slope(-11000, 2, -1, 3));
        for (long x : new long[]{-33, -17, -16, -1, 0, 15, 16, 33}) {
            for (long z : new long[]{-17, -1, 0, 16, 17}) {
                long surface = -11000 + Math.floorDiv(2 * x - z, 3);
                assertEquals(slope.config().solidState(), read(slope, x, surface, z));
                assertEquals(slope.config().airState(), read(slope, x, surface + 1, z));
            }
        }
        var extreme = slope.load(new CubePos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE), new CancellationToken());
        assertTrue(extreme.isDone());
        assertNotNull(extreme.join());
    }

    @Test void overhangContainsGroundThenAirThenRoofThenAirAtTheSameXZ() {
        for (long ground : new long[]{-11000, -1, 8849}) {
            var overhang = provider(AnalyticNativeCubeProvider.Config.overhang(ground));
            var codec = new NativeCubeCodec(overhang.identity(), palette);
            for (long x : new long[]{-24, 8, 40}) {
                assertEquals(overhang.config().solidState(), read(overhang, x, ground, 8));
                for (int dy = 1; dy <= 5; dy++) assertEquals(overhang.config().airState(), read(overhang, x, ground + dy, 8));
                for (int dy = 6; dy <= 8; dy++) assertEquals(overhang.config().solidState(), read(overhang, x, ground + dy, 8));
                assertEquals(overhang.config().airState(), read(overhang, x, ground + 9, 8));
            }
            assertEquals(overhang.config().solidState(), read(overhang, 2, ground + 3, 2));
            assertEquals(overhang.config().airState(), read(overhang, 1, ground + 7, 8));
            for (long y : new long[]{ground, ground + 5, ground + 8}) {
                CubePos pos = CubePos.fromBlock(8, y, 8);
                ImmutableCube source = overhang.load(pos, new CancellationToken()).join();
                assertEquals(source, codec.capture(codec.materialize(pos, source, 7, 3, 0)));
            }
        }
    }

    @Test void identityPinsEveryConfigurationInputAndNativePalette() {
        var base = AnalyticNativeCubeProvider.Config.slope(8849, 2, -1, 3);
        var one = provider(base);
        var same = provider(AnalyticNativeCubeProvider.Config.slope(8849, 2, -1, 3));
        assertEquals(one.identity(), same.identity());
        assertEquals(one.config().fingerprint(), same.config().fingerprint());
        assertEquals(NativeCubeCodec.paletteIdentity(palette), one.identity().blockPaletteVersion());
        assertEquals(AnalyticNativeCubeProvider.GENERATOR_VERSION, one.identity().generatorVersion());
        assertTrue(one.identity().datasetVersion().startsWith(AnalyticNativeCubeProvider.CONFIG_VERSION + ":"));
        for (var config : new AnalyticNativeCubeProvider.Config[]{
                AnalyticNativeCubeProvider.Config.slope(8848, 2, -1, 3),
                AnalyticNativeCubeProvider.Config.slope(8849, 1, -1, 3),
                AnalyticNativeCubeProvider.Config.slope(8849, 2, 1, 3),
                AnalyticNativeCubeProvider.Config.slope(8849, 2, -1, 4),
                AnalyticNativeCubeProvider.Config.flat(8849),
                new AnalyticNativeCubeProvider.Config(base.fixture(), base.anchorY(), base.riseX(), base.riseZ(), base.run(),
                        base.airState(), Block.BLOCK_STATE_REGISTRY.getId(Blocks.GOLD_BLOCK.defaultBlockState()))}) {
            assertNotEquals(one.identity(), provider(config).identity());
        }
        assertNotEquals(one.identity(), new AnalyticNativeCubeProvider(43, base, palette).identity());
        var forged = new EnginePaletteContext(palette.biomes(), "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> new AnalyticNativeCubeProvider(42, base, forged));
    }

    @Test void rejectsUnknownMaterialIdsAndInvalidConfiguration() {
        var fixture = AnalyticNativeCubeProvider.Fixture.FLAT;
        int air = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        int stone = Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
        assertThrows(IllegalArgumentException.class, () -> new AnalyticNativeCubeProvider.Config(fixture, 0, 0, 0, 1, air, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new AnalyticNativeCubeProvider.Config(fixture, 0, 0, 0, 1, Integer.MAX_VALUE, stone));
        assertThrows(IllegalArgumentException.class, () -> new AnalyticNativeCubeProvider.Config(fixture, 0, 0, 0, 1, stone, stone));
        assertThrows(IllegalArgumentException.class, () -> new AnalyticNativeCubeProvider.Config(fixture, 0, 0, 0, 1, air, air));
        assertThrows(IllegalArgumentException.class, () -> AnalyticNativeCubeProvider.Config.slope(0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> AnalyticNativeCubeProvider.Config.slope(0, Integer.MAX_VALUE, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> AnalyticNativeCubeProvider.Config.overhang(Long.MAX_VALUE));
    }

    @Test void generationIsDeterministicSynchronousAndUsesOnlyCallerBoundedDispatch() {
        var jobs = new ArrayDeque<Runnable>();
        var analytic = provider(AnalyticNativeCubeProvider.Config.overhang(8849));
        var invokedOn = new AtomicReference<Thread>();
        try (var loader = new BoundedCubeLoader((pos, token) -> {
            invokedOn.set(Thread.currentThread());
            var result = analytic.load(pos, token);
            assertTrue(result.isDone());
            return result;
        }, jobs::add, 1, 1, 1)) {
            CubePos pos = CubePos.fromBlock(8, 8849, 8);
            var first = loader.request(pos);
            var second = loader.request(pos.offset(0, 1, 0));
            assertEquals(1, jobs.size());
            assertEquals(1, loader.stats().running());
            assertEquals(1, loader.stats().queued());
            assertFalse(first.future().isDone());
            jobs.remove().run();
            assertSame(Thread.currentThread(), invokedOn.get());
            assertEquals(analytic.load(pos, new CancellationToken()).join(), first.future().join());
            jobs.remove().run();
            assertTrue(second.future().isDone());
            assertEquals(0, loader.stats().running());
            assertEquals(0, loader.stats().queued());
            assertTrue(jobs.isEmpty());
        }
    }

    @Test void cooperativelyCancelledTokenSettlesWithoutGeneratingOrOwningBackgroundWork() {
        var token = new AtomicReference<CancellationToken>();
        var unfinished = new CompletableFuture<ImmutableCube>();
        try (var loader = new BoundedCubeLoader((pos, cancellation) -> {
            token.set(cancellation);
            return unfinished;
        }, Runnable::run, 1, 0, 1)) {
            var lease = loader.request(new CubePos(0, 0, 0));
            lease.close();
            assertTrue(token.get().isCancelled());
            for (var config : new AnalyticNativeCubeProvider.Config[]{
                    AnalyticNativeCubeProvider.Config.flat(-11000), AnalyticNativeCubeProvider.Config.slope(0, 1, 1, 2),
                    AnalyticNativeCubeProvider.Config.voidWorld(), AnalyticNativeCubeProvider.Config.overhang(8849)}) {
                var result = provider(config).load(new CubePos(0, 0, 0), token.get());
                assertTrue(result.isDone());
                assertThrows(CancellationException.class, result::join);
            }
            unfinished.cancel(false);
            assertEquals(0, loader.stats().running());
        }
    }
}
