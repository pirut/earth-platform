package io.earthplatform.minecraft.survival.world;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Separate simulation preparation/reentrancy regressions, not render publication evidence. */
class NativeSurvivalWorldPreparationTest {
    private static SurvivalPalette palette;
    private static WorldIdentity identity;
    private static final CubeCell HIGH = CubeCell.of(-19344520, 7314, -4297490);
    private static final UUID ACTOR = new UUID(3, 9);

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        var context = EnginePaletteContext.create(registry.freeze());
        palette = new SurvivalPalette(context);
        identity = new WorldIdentity(39, "bounded-native-survival", "first039", "marin-test",
                NativeCubeCodec.paletteIdentity(context));
    }
    private static NativeSurvivalWorld world() {
        return world(new NativeSurvivalWorld.Limits(32, 64, 8, 16, 4096, 128, 16, 65536, 16, 64));
    }
    private static NativeSurvivalWorld world(NativeSurvivalWorld.Limits limits) {
        return new NativeSurvivalWorld(identity, palette,
                new NativeSurvivalWorld.Bounds(new CubePos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE),
                        new CubePos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)), limits);
    }
    private static ImmutableCube uniform(BlockState state) { return ImmutableCube.uniform(palette.id(state)); }
    private static void air(NativeSurvivalWorld owner, CubePos position) { owner.admitGenerated(position, uniform(Blocks.AIR.defaultBlockState())); }
    private static NativeSurvivalWorld.ActionTransaction begin(NativeSurvivalWorld owner, long sequence, CubePos... cubes) {
        Map<CubePos, NativeSurvivalWorld.CubeStamp> expected = new HashMap<>();
        for (CubePos cube : cubes) expected.put(cube, owner.stamp(cube));
        return owner.begin(owner.actionIdentity(ACTOR, sequence), expected);
    }
    private static NativeSurvivalWorld.CommitReceipt set(NativeSurvivalWorld owner, long sequence, CubeCell cell, BlockState state) {
        try (var tx = begin(owner, sequence, cell.cube())) { tx.set(cell, state); return tx.commit(); }
    }

    private static Map<CubePos, NativeSurvivalWorld.CubeStamp> callbacks(NativeSurvivalWorld world,
            Runnable action, boolean duringSize, AtomicInteger invoked) {
        // Map.copyOf may return immediately for an empty map. A real owned entry forces the
        // iteration path, and its stamp is captured before begin so the fixture does not reenter.
        air(world, HIGH.cube());
        var entry = Map.entry(HIGH.cube(), world.stamp(HIGH.cube()));
        return new AbstractMap<>() {
            private void callback() { invoked.incrementAndGet(); action.run(); }
            public int size() { if (duringSize) callback(); return 1; }
            public Set<Entry<CubePos, NativeSurvivalWorld.CubeStamp>> entrySet() {
                if (!duringSize) callback(); return Set.of(entry);
            }
        };
    }
    private static void assertUnconsumedAndReusable(NativeSurvivalWorld world) {
        assertFalse(world.hasActiveTransaction()); assertEquals(0, world.actorCount());
        assertEquals(1, world.nextActionSequence(ACTOR)); assertEquals(0, world.commitSequence());
        try (var next = begin(world, 1)) { assertEquals(1, next.commit().commitSequence()); }
        assertFalse(world.hasActiveTransaction()); assertEquals(2, world.nextActionSequence(ACTOR));
    }

    @Test void swallowedNestedBeginPoisonsOuterBeforeEitherActionIsConsumed() {
        for (boolean size : new boolean[]{true, false}) {
            var world = world(); var nested = new AtomicReference<NativeSurvivalWorld.ActionTransaction>();
            var invoked = new AtomicInteger();
            var map = callbacks(world, () -> {
                assertTrue(world.hasActiveTransaction());
                assertThrows(SurvivalRefusal.class, () -> nested.set(begin(world, 99)));
            }, size, invoked);
            var oldRender = world.renderPublicationFence().captureStamp();
            assertThrows(SurvivalRefusal.class, () -> world.begin(world.actionIdentity(ACTOR, 1), map));
            assertTrue(invoked.get() > 0, "The intended size/iteration callback must actually run");
            assertNull(nested.get(), "No nested transaction can escape and be overwritten by the outer begin");
            assertThrows(SurvivalRefusal.class, oldRender::latchBeforeRevocation);
            assertUnconsumedAndReusable(world);
        }
    }

    @Test void uncaughtNestedBeginAndCallbackErrorsAlwaysClearPreparationGuard() {
        for (boolean size : new boolean[]{true, false}) {
            var nestedWorld = world(); var nestedCalls = new AtomicInteger();
            assertThrows(SurvivalRefusal.class, () -> nestedWorld.begin(nestedWorld.actionIdentity(ACTOR, 1),
                    callbacks(nestedWorld, () -> begin(nestedWorld, 99), size, nestedCalls)));
            assertTrue(nestedCalls.get() > 0); assertUnconsumedAndReusable(nestedWorld);
            var errorWorld = world(); var errorCalls = new AtomicInteger();
            assertThrows(AssertionError.class, () -> errorWorld.begin(errorWorld.actionIdentity(ACTOR, 1),
                    callbacks(errorWorld, () -> { throw new AssertionError("collection failed"); }, size, errorCalls)));
            assertTrue(errorCalls.get() > 0); assertUnconsumedAndReusable(errorWorld);
            var exceptionWorld = world(); var exceptionCalls = new AtomicInteger();
            assertThrows(IllegalArgumentException.class, () -> exceptionWorld.begin(exceptionWorld.actionIdentity(ACTOR, 1),
                    callbacks(exceptionWorld, () -> { throw new IllegalArgumentException("collection failed"); }, size, exceptionCalls)));
            assertTrue(exceptionCalls.get() > 0); assertUnconsumedAndReusable(exceptionWorld);
        }
    }

    @Test void swallowedOwnershipMutationCannotChangeWorldOrLetOuterPreparationProceed() {
        for (boolean size : new boolean[]{true, false}) {
            var world = world(); var invoked = new AtomicInteger(); var other = HIGH.cube().offset(1, 0, 0);
            var map = callbacks(world, () -> assertThrows(SurvivalRefusal.class, () -> air(world, other)), size, invoked);
            assertThrows(SurvivalRefusal.class, () -> world.begin(world.actionIdentity(ACTOR, 1), map));
            assertTrue(invoked.get() > 0);
            assertEquals(1, world.ownedCount()); assertEquals(NativeSurvivalWorld.Presence.UNKNOWN, world.presence(other));
            assertSame(Blocks.AIR.defaultBlockState(), world.read(HIGH));
            assertUnconsumedAndReusable(world);
        }
    }

    @Test void purePreparationObservationIsAllowedAndAllInputFailuresUnwind() {
        var observed = world(); var invoked = new AtomicInteger();
        var map = callbacks(observed, () -> {
            assertTrue(observed.hasActiveTransaction()); assertEquals(0, observed.commitSequence());
            assertEquals(1, observed.nextActionSequence(ACTOR));
        }, false, invoked);
        try (var transaction = observed.begin(observed.actionIdentity(ACTOR, 1), map)) { transaction.commit(); }
        assertTrue(invoked.get() > 0);
        assertFalse(observed.hasActiveTransaction()); assertEquals(1, observed.commitSequence());
        var nullIdentity = world();
        assertThrows(NullPointerException.class, () -> nullIdentity.begin(null, Map.of()));
        assertUnconsumedAndReusable(nullIdentity);
        var nullMap = world();
        assertThrows(NullPointerException.class, () -> nullMap.begin(nullMap.actionIdentity(ACTOR, 1), null));
        assertUnconsumedAndReusable(nullMap);
    }
}
