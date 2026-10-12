package io.earthplatform.minecraft.survival.interaction.host;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalPalette;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeScheduledTickQueueTest {
    private static SurvivalPalette palette;
    private static final UUID ACTOR = new UUID(401, 39);
    @BeforeAll static void setup() {
        var registries = VanillaRegistries.createLookup();
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = new SurvivalPalette(EnginePaletteContext.create(biomes.freeze()));
    }
    private static NativeSurvivalWorld world() {
        return new NativeSurvivalWorld(new WorldIdentity(39, "effect-tick-test", "v1", "none", palette.identity()), palette,
                new NativeSurvivalWorld.Bounds(new CubePos(-100, -1000, -100), new CubePos(100, 1000, 100)),
                new NativeSurvivalWorld.Limits(4, 4, 4, 4, 64, 8, 32, 65536, 4, 4));
    }
    private static NativeEffectCodec.ScheduledBlockTick tick(long x, long y, long time, int priority, long order) {
        return new NativeEffectCodec.ScheduledBlockTick(CubeCell.of(x, y, -3), ResourceLocation.withDefaultNamespace("oak_leaves"), time, priority, order);
    }
    @Test void preparationIsReadOnlyThenExactReceiptAppliesOnce() {
        var world = world(); var queue = new NativeScheduledTickQueue(world, 4);
        var id = world.actionIdentity(ACTOR, 1);
        try (var action = world.begin(id, Map.of())) {
            var prepared = queue.prepare(List.of(tick(-17, 8849, 10, 0, 1)), 0, id);
            assertEquals(0, queue.size()); assertEquals(1, prepared.pendingTickCount());
            var receipt = action.commit(); prepared.apply(receipt);
            assertEquals(1, queue.size()); assertEquals(0, prepared.pendingTickCount());
            assertThrows(SurvivalRefusal.class, () -> prepared.apply(receipt));
            assertEquals(8849, queue.snapshot().getFirst().cell().y());
        }
    }
    @Test void nativeFirstScheduleWinsWithinAndAcrossActions() {
        var world = world(); var queue = new NativeScheduledTickQueue(world, 1);
        var first = tick(0, -7, 20, 0, 7); var later = tick(0, -7, 5, -3, 1);
        var id = world.actionIdentity(ACTOR, 1);
        try (var action = world.begin(id, Map.of())) {
            var prepared = queue.prepare(List.of(first, later), 0, id); assertEquals(1, prepared.newTickCount());
            prepared.apply(action.commit());
        }
        var next = world.actionIdentity(ACTOR, 2);
        try (var action = world.begin(next, Map.of())) {
            var prepared = queue.prepare(List.of(later), 10, next); assertEquals(0, prepared.newTickCount());
            prepared.apply(action.commit());
        }
        assertEquals(List.of(first), queue.snapshot()); assertNull(queue.nextDue(19)); assertEquals(first, queue.nextDue(20));
        assertEquals(1, queue.size(), "due inspection never drops an uncommitted tick");
    }
    @Test void nativeDrainOrderPreservesPriorityAndSubtickOrder() {
        var world = world(); var queue = new NativeScheduledTickQueue(world, 4); var id = world.actionIdentity(ACTOR, 1);
        var a = tick(0, 0, 20, 0, 3); var b = tick(1, 0, 10, 0, 2);
        var c = tick(2, 0, 10, -1, 4); var d = tick(3, 0, 10, 0, 1);
        try (var action = world.begin(id, Map.of())) {
            var prepared = queue.prepare(List.of(a, b, c, d), 0, id); prepared.apply(action.commit());
        }
        assertEquals(List.of(c, d, b, a), queue.snapshot());
    }
    @Test void capacityFailureLeavesExistingQueueUntouched() {
        var world = world(); var queue = new NativeScheduledTickQueue(world, 1); var id = world.actionIdentity(ACTOR, 1);
        try (var action = world.begin(id, Map.of())) {
            assertThrows(SurvivalRefusal.class, () -> queue.prepare(List.of(tick(0, 0, 10, 0, 1), tick(1, 0, 10, 0, 2)), 0, id));
            assertEquals(0, queue.size());
        }
    }
    @Test void unsupportedTickAndForeignReceiptFailClosed() {
        var world = world(); var queue = new NativeScheduledTickQueue(world, 2); var id = world.actionIdentity(ACTOR, 1);
        try (var action = world.begin(id, Map.of())) {
            var unsupported = new NativeEffectCodec.ScheduledBlockTick(CubeCell.of(0, 0, 0), ResourceLocation.withDefaultNamespace("water"), 10, 0, 1);
            assertThrows(SurvivalRefusal.class, () -> queue.prepare(List.of(unsupported), 0, id));
            var prepared = queue.prepare(List.of(tick(0, 0, 10, 0, 1)), 0, id);
            var other = world();
            try (var foreign = other.begin(other.actionIdentity(ACTOR, 1), Map.of())) {
                var receipt = foreign.commit(); assertThrows(SurvivalRefusal.class, () -> prepared.apply(receipt));
            }
            assertEquals(0, queue.size());
        }
    }
    @Test void duplicateAndExhaustedOrdersRefuseBeforeCommit() {
        var world = world(); var queue = new NativeScheduledTickQueue(world, 4); var id = world.actionIdentity(ACTOR, 1);
        try (var action = world.begin(id, Map.of())) {
            assertThrows(SurvivalRefusal.class, () -> queue.prepare(List.of(tick(0, 0, 10, 0, 1), tick(1, 0, 10, 0, 1)), 0, id));
            assertThrows(SurvivalRefusal.class, () -> queue.prepare(List.of(tick(0, 0, 10, 0, Long.MAX_VALUE)), 0, id));
            assertEquals(0, queue.size());
            var prepared = queue.prepare(List.of(tick(0, 0, 10, 0, 1)), 0, id); prepared.apply(action.commit());
        }
        var next = world.actionIdentity(ACTOR, 2);
        try (var action = world.begin(next, Map.of())) {
            assertThrows(SurvivalRefusal.class, () -> queue.prepare(List.of(tick(1, 0, 10, 0, 1)), 0, next));
            assertEquals(1, queue.size());
        }
    }

    @Test void nextAvailableOrderIsReadOnlyAndRefusesExhaustion() {
        var world = world(); var queue = new NativeScheduledTickQueue(world, 4);
        assertEquals(0, queue.nextAvailableScheduledOrder());
        var first = world.actionIdentity(ACTOR, 1);
        try (var action = world.begin(first, Map.of())) {
            var prepared = queue.prepare(List.of(tick(0, 0, 10, 0, 9)), 0, first);
            assertEquals(0, queue.nextAvailableScheduledOrder(), "uncommitted staging cannot advance the live order");
            prepared.apply(action.commit());
        }
        assertEquals(10, queue.nextAvailableScheduledOrder());
        assertEquals(10, queue.nextAvailableScheduledOrder());
        assertEquals(1, queue.size());
        var next = world.actionIdentity(ACTOR, 2);
        try (var action = world.begin(next, Map.of())) {
            var prepared = queue.prepare(List.of(tick(1, 0, 10, 0, Long.MAX_VALUE - 1)), 0, next);
            prepared.apply(action.commit());
        }
        assertThrows(SurvivalRefusal.class, queue::nextAvailableScheduledOrder);
        assertEquals(2, queue.size());
    }

}
