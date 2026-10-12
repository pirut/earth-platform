package io.earthplatform.minecraft.survival.interaction;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalPalette;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeWholeActionTest {
    private static SurvivalPalette palette;
    private static HolderLookup.Provider registries;
    private static final CubeCell TARGET = CubeCell.of(-17, 8849, 3);
    private static final UUID ACTOR = new UUID(31, 39);
    @BeforeAll static void setup() {
        registries = VanillaRegistries.createLookup();
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = new SurvivalPalette(EnginePaletteContext.create(biomes.freeze()));
    }
    private static NativeSurvivalWorld world(int effects) {
        var identity = new WorldIdentity(39, "native-test", "v1", "none", palette.identity());
        var world = new NativeSurvivalWorld(identity, palette, new NativeSurvivalWorld.Bounds(
                new CubePos(-100, -1000, -100), new CubePos(100, 1000, 100)),
                new NativeSurvivalWorld.Limits(4, 4, 2, 4, 128, 8, effects, 65536, 4, 4));
        world.admitGenerated(TARGET.cube(), ImmutableCube.uniform(palette.id(Blocks.AIR.defaultBlockState())));
        return world;
    }
    private static final class Inventory implements NativeWholeAction.InventoryView {
        final ItemStack[] stacks = new ItemStack[41]; int selected;
        Inventory() { Arrays.fill(stacks, ItemStack.EMPTY); stacks[0] = new ItemStack(Items.OAK_LOG, 2); }
        public int size() { return stacks.length; }
        public ItemStack getItem(int slot) { return stacks[slot]; }
        public int selectedSlot() { return selected; }
    }
    private static NativeWholeAction begin(NativeWholeAction.Owner owner, NativeSurvivalWorld world, Inventory inventory, long sequence) {
        return owner.begin(world.actionIdentity(ACTOR, sequence), Map.of(TARGET.cube(), world.stamp(TARGET.cube())), inventory);
    }

    @Test void callbacksPrepareOnceThenCubesAndFullInventoryApplyOnce() {
        var world = world(8); var inventory = new Inventory(); var prepared = new AtomicInteger(); var applied = new AtomicInteger();
        var owner = new NativeWholeAction.Owner(world, registries, (identity, effects) -> {
            prepared.incrementAndGet(); assertEquals(2, effects.size());
            var slot = effects.stream().filter(NativeEffectCodec.InventorySlot.class::isInstance)
                    .map(NativeEffectCodec.InventorySlot.class::cast).findFirst().orElseThrow();
            assertEquals(2, inventory.stacks[0].getCount());
            return receipt -> {
                applied.incrementAndGet(); assertSame(Blocks.OAK_LOG.defaultBlockState(), world.read(TARGET));
                inventory.stacks[slot.slot()] = slot.after().materialize(registries);
            };
        });
        try (var action = begin(owner, world, inventory, 1)) {
            action.set(TARGET, Blocks.OAK_LOG.defaultBlockState()); action.stack(0).shrink(1);
            assertSame(Blocks.OAK_LOG.defaultBlockState(), action.read(TARGET)); assertEquals(2, inventory.stacks[0].getCount());
            var receipt = action.commit(); assertEquals(2, receipt.effects().size());
            assertThrows(SurvivalRefusal.class, action::commit);
        }
        assertEquals(1, prepared.get()); assertEquals(1, applied.get()); assertEquals(1, inventory.stacks[0].getCount());
        assertFalse(owner.fenced()); assertNull(NativeWholeAction.current());
    }

    @Test void unsupportedPrepareAndEffectOverflowLeaveLiveBlockAndInventoryUntouched() {
        var world = world(2); var inventory = new Inventory(); var applies = new AtomicInteger();
        var owner = new NativeWholeAction.Owner(world, registries, (identity, effects) -> { throw new SurvivalRefusal("unsupported effect"); });
        try (var action = begin(owner, world, inventory, 1)) {
            action.set(TARGET, Blocks.OAK_LOG.defaultBlockState()); action.stack(0).shrink(1);
            assertThrows(SurvivalRefusal.class, action::commit);
        }
        assertSame(Blocks.AIR.defaultBlockState(), world.read(TARGET)); assertEquals(2, inventory.stacks[0].getCount());
        var bounded = new NativeWholeAction.Owner(world, registries, (identity, effects) -> receipt -> applies.incrementAndGet());
        try (var action = begin(bounded, world, inventory, 2)) {
            action.set(TARGET, Blocks.OAK_LOG.defaultBlockState()); action.stack(0).shrink(1);
            action.replaceStack(1, new ItemStack(Items.STICK));
            assertThrows(SurvivalRefusal.class, action::commit);
        }
        assertEquals(0, applies.get()); assertEquals(0, world.commitSequence());
        assertSame(Blocks.AIR.defaultBlockState(), world.read(TARGET)); assertEquals(2, inventory.stacks[0].getCount());
    }

    @Test void changedLiveInventoryCancelsBeforeCubeCommitAndNoPreparedEffectsApply() {
        var world = world(8); var inventory = new Inventory(); var applies = new AtomicInteger();
        var owner = new NativeWholeAction.Owner(world, registries, (identity, effects) -> receipt -> applies.incrementAndGet());
        try (var action = begin(owner, world, inventory, 1)) {
            action.set(TARGET, Blocks.OAK_LOG.defaultBlockState()); action.stack(0).shrink(1);
            inventory.stacks[0] = new ItemStack(Items.STONE_PICKAXE);
            assertThrows(SurvivalRefusal.class, action::commit);
        }
        assertEquals(0, applies.get()); assertSame(Blocks.AIR.defaultBlockState(), world.read(TARGET));
        assertSame(Items.STONE_PICKAXE, inventory.stacks[0].getItem());
    }

    @Test void postcommitFailureRetainsExactReceiptAndPermanentlyFencesWithoutRetry() {
        var world = world(8); var inventory = new Inventory(); var applies = new AtomicInteger();
        var receiptRef = new AtomicReference<NativeSurvivalWorld.CommitReceipt>();
        var failure = new IllegalStateException("simulated native delivery failure");
        var owner = new NativeWholeAction.Owner(world, registries, (identity, effects) -> receipt -> {
            applies.incrementAndGet(); receiptRef.set(receipt); throw failure;
        });
        try (var action = begin(owner, world, inventory, 1)) {
            action.set(TARGET, Blocks.OAK_LOG.defaultBlockState()); action.stack(0).shrink(1);
            var refused = assertThrows(SurvivalRefusal.class, action::commit);
            assertSame(failure, refused.getCause());
            assertEquals(NativeWholeAction.State.DELIVERY_FAILED, action.state());
            assertThrows(SurvivalRefusal.class, action::commit);
        }
        assertTrue(owner.fenced()); assertSame(receiptRef.get(), owner.pendingDelivery().receipt());
        assertSame(failure, owner.pendingDelivery().failure()); assertTrue(owner.pendingDelivery().hasRetainedPreparedBatch());
        assertEquals(1, applies.get()); assertSame(Blocks.OAK_LOG.defaultBlockState(), world.read(TARGET));
        assertEquals(2, inventory.stacks[0].getCount());
        assertThrows(SurvivalRefusal.class, () -> begin(owner, world, inventory, 2));
    }

    @Test void nestedActionsAndAbortCannotLeakDetachedChanges() {
        var world = world(8); var inventory = new Inventory();
        var owner = new NativeWholeAction.Owner(world, registries, (identity, effects) -> receipt -> {});
        var expected = Map.of(TARGET.cube(), world.stamp(TARGET.cube()));
        assertSame(world, owner.world()); assertFalse(owner.hasCurrentAction());
        try (var action = begin(owner, world, inventory, 1)) {
            assertTrue(owner.hasCurrentAction()); assertTrue(owner.owns(action));
            action.set(TARGET, Blocks.OAK_LOG.defaultBlockState()); action.stack(0).shrink(1);
            assertThrows(SurvivalRefusal.class, () -> owner.begin(world.actionIdentity(ACTOR, 2), expected, inventory));
        }
        assertSame(Blocks.AIR.defaultBlockState(), world.read(TARGET)); assertEquals(2, inventory.stacks[0].getCount());
        assertNull(NativeWholeAction.current()); assertFalse(owner.hasCurrentAction());
    }

    @Test void scopeStreamIsNeverResetByAccessRefusedNestedIntentOrReplay() {
        var world = world(8); var inventory = new Inventory();
        var owner = new NativeWholeAction.Owner(world, registries, (identity, effects) -> receipt -> {});
        var identity = world.actionIdentity(ACTOR, 1);
        var expected = Map.of(TARGET.cube(), world.stamp(TARGET.cube()));
        NativeWholeAction old;
        try (var action = owner.begin(identity, expected, inventory)) {
            old = action;
            var reference = net.minecraft.util.RandomSource.create(NativeActionRandom.derive(world.world(), identity));
            assertSame(action.random(), action.random());
            assertEquals(reference.nextLong(), action.random().nextLong());
            assertThrows(SurvivalRefusal.class, () -> owner.begin(identity, expected, inventory));
            assertEquals(reference.nextLong(), action.random().nextLong());
        }
        assertThrows(SurvivalRefusal.class, old::random);
        assertThrows(SurvivalRefusal.class, () -> owner.begin(identity, expected, inventory));
        assertFalse(owner.hasCurrentAction()); assertFalse(world.hasActiveTransaction());
    }

    @Test void committedReceiptContainsExactlyOneBoundRandomSeedAndCallbacksCannotReplaceIt() {
        var world = world(8); var inventory = new Inventory();
        var owner = new NativeWholeAction.Owner(world, registries, (identity, effects) -> receipt -> {});
        try (var action = begin(owner, world, inventory, 1)) {
            action.random().nextLong(); action.random().nextLong();
            var expectedSeed = new NativeActionRandom(world.world(), action.identity()).seedEffect();
            var receipt = action.commit();
            assertEquals(1, receipt.effects().size());
            assertEquals(expectedSeed, NativeEffectCodec.decode(receipt.effects().getFirst(), registries));
        }
        try (var action = begin(owner, world, inventory, 2)) {
            var replacement = new NativeActionRandom(world.world(), action.identity()).seedEffect();
            assertThrows(SurvivalRefusal.class, () -> action.stage(replacement));
            assertEquals(NativeWholeAction.State.ABORTED, action.state());
            assertThrows(SurvivalRefusal.class, action::commit);
        }
    }

    @Test void prevalidationCannotAlterTheAlreadyFrozenBlockInventoryOrEffectPlan() {
        var world = world(8); var inventory = new Inventory();
        var owner = new NativeWholeAction.Owner(world, registries, (identity, effects) -> {
            var current = NativeWholeAction.current();
            assertEquals(NativeWholeAction.State.PREVALIDATING, current.state());
            assertThrows(SurvivalRefusal.class, () -> current.set(TARGET, Blocks.STONE.defaultBlockState()));
            assertThrows(SurvivalRefusal.class, () -> current.stack(0));
            assertThrows(SurvivalRefusal.class, current::close);
            return receipt -> {};
        });
        try (var action = begin(owner, world, inventory, 1)) { action.commit(); }
        assertSame(Blocks.AIR.defaultBlockState(), world.read(TARGET));
        assertEquals(2, inventory.stacks[0].getCount());
    }
    @Test void caughtCallbackPoisonCannotCommitOrReenterBeforeClose() {
        var world = world(8); var inventory = new Inventory(); var applies = new AtomicInteger();
        var owner = new NativeWholeAction.Owner(world, registries, (identity, effects) -> receipt -> applies.incrementAndGet());
        try (var action = begin(owner, world, inventory, 1)) {
            action.set(TARGET, Blocks.OAK_LOG.defaultBlockState()); action.stack(0).shrink(1);
            action.poisonPreparation();
            assertEquals(NativeWholeAction.State.ABORTED, action.state());
            assertTrue(owner.hasCurrentAction()); assertSame(action, NativeWholeAction.current());
            assertThrows(SurvivalRefusal.class, action::commit);
            assertThrows(SurvivalRefusal.class, () -> begin(owner, world, inventory, 2));
            assertThrows(SurvivalRefusal.class, () -> action.set(TARGET, Blocks.STONE.defaultBlockState()));
        }
        assertEquals(0, applies.get()); assertEquals(0, world.commitSequence());
        assertSame(Blocks.AIR.defaultBlockState(), world.read(TARGET)); assertEquals(2, inventory.stacks[0].getCount());
        assertFalse(owner.hasCurrentAction()); assertFalse(owner.fenced());
    }

}
