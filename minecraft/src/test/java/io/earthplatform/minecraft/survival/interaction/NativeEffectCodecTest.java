package io.earthplatform.minecraft.survival.interaction;

import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeActionEffect;
import io.earthplatform.core.WorldIdentity;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation;
import io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuSnapshot;
import net.minecraft.world.inventory.ClickType;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeEffectCodecTest {
    private static HolderLookup.Provider registries;
    @BeforeAll static void setup() { registries = VanillaRegistries.createLookup(); }

    @Test void itemEntitiesPreserveEveryStagedFieldAtSignedEarthHeights() {
        for (double y : new double[]{-11000.125, -2048.25, 2048.25, 8849.875}) {
            var stack = NativeStackSnapshot.capture(new ItemStack(Items.COBBLESTONE, 9), registries);
            var value = new NativeEffectCodec.ItemSpawn(UUID.randomUUID(), stack, -19876543.125, y,
                    19876543.875, -0.03125, 0.2, 0.00125, 29, 10);
            assertEquals(value, NativeEffectCodec.decode(NativeEffectCodec.encode(value), registries));
        }
    }

    @Test void ticksUseThreeSignedCoordinatesAndNeverPackedBlockPositions() {
        var id = ResourceLocation.parse("minecraft:oak_leaves");
        var low = new NativeEffectCodec.ScheduledBlockTick(CubeCell.of(3, -2049, -7), id, 57, 0, 8);
        var alias = new NativeEffectCodec.ScheduledBlockTick(CubeCell.of(3, 2047, -7), id, 57, 0, 8);
        assertNotEquals(NativeEffectCodec.encode(low), NativeEffectCodec.encode(alias));
        assertEquals(low, NativeEffectCodec.decode(NativeEffectCodec.encode(low), registries));
        assertEquals(alias, NativeEffectCodec.decode(NativeEffectCodec.encode(alias), registries));
    }

    @Test void inventoryEffectsKeepBeforeAndAfterStacksAndRefuseOutOfRangeSlots() {
        var before = NativeStackSnapshot.capture(new ItemStack(Items.OAK_LOG, 4), registries);
        var after = NativeStackSnapshot.capture(new ItemStack(Items.OAK_LOG, 3), registries);
        var slot = new NativeEffectCodec.InventorySlot(0, before, after);
        assertEquals(slot, NativeEffectCodec.decode(NativeEffectCodec.encode(slot), registries));
        assertThrows(IllegalArgumentException.class, () -> new NativeEffectCodec.InventorySlot(-1, before, after));
        assertThrows(IllegalArgumentException.class, () -> new NativeEffectCodec.InventorySlot(41, before, after));
    }

    @Test void everyTruncationAndTrailingByteIsRefusedBeforeCommit() {
        var value = new NativeEffectCodec.ExperienceSpawn(UUID.randomUUID(), 3, 1, 8849.5, 3, .1, .2, .3, 9);
        NativeActionEffect encoded = NativeEffectCodec.encode(value);
        byte[] bytes = encoded.payload();
        for (int length = 0; length < bytes.length; length++) {
            var truncated = new NativeActionEffect(encoded.type(), Arrays.copyOf(bytes, length));
            assertThrows(RuntimeException.class, () -> NativeEffectCodec.decode(truncated, registries));
        }
        var trailing = new NativeActionEffect(encoded.type(), Arrays.copyOf(bytes, bytes.length + 1));
        assertThrows(IllegalArgumentException.class, () -> NativeEffectCodec.decode(trailing, registries));
        var unknown = new NativeActionEffect("unknown_v1", bytes);
        assertThrows(IllegalArgumentException.class, () -> NativeEffectCodec.decode(unknown, registries));
    }

    @Test void unsupportedEntityValuesCannotEnterTheJournal() {
        var stack = NativeStackSnapshot.capture(new ItemStack(Items.OAK_LOG), registries);
        assertThrows(IllegalArgumentException.class, () -> new NativeEffectCodec.ItemSpawn(UUID.randomUUID(), stack,
                0, Double.NaN, 0, 0, 0, 0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new NativeEffectCodec.ExperienceSpawn(UUID.randomUUID(),
                0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new NativeEffectCodec.ScheduledBlockTick(CubeCell.of(0, 0, 0),
                ResourceLocation.parse("minecraft:oak_leaves"), 1, 4, 0));
    }

    @Test void blockNotificationBindsWorldPaletteAndSignedPositionWithoutClaimingCallbackDelivery() {
        var world = new WorldIdentity(39, "source", "v1", "dataset", "pinned-native-palette");
        var before = new NativeEffectCodec.BlockNotification(world, CubeCell.of(-9, -2049, 3), 1, 2, 11, 512);
        var alias = new NativeEffectCodec.BlockNotification(world, CubeCell.of(-9, 2047, 3), 1, 2, 11, 512);
        assertEquals(before, NativeEffectCodec.decode(NativeEffectCodec.encode(before), registries));
        assertNotEquals(NativeEffectCodec.encode(before), NativeEffectCodec.encode(alias));
        assertThrows(IllegalArgumentException.class, () -> new NativeEffectCodec.BlockNotification(world,
                before.cell(), 1, 2, 128, 512));
        assertThrows(IllegalArgumentException.class, () -> new NativeEffectCodec.BlockNotification(world,
                before.cell(), 1, 2, 11, 513));
    }
    @Test void nativeExhaustionUsesVersionedFiniteBoundedAmounts() {
        var exhaustion = new NativeEffectCodec.PlayerExhaustion(0.005F);
        assertEquals(exhaustion, NativeEffectCodec.decode(NativeEffectCodec.encode(exhaustion), registries));
        for (float amount : new float[]{0.0F, -0.005F, Float.NaN, Float.POSITIVE_INFINITY, 40.001F})
            assertThrows(IllegalArgumentException.class, () -> new NativeEffectCodec.PlayerExhaustion(amount));
        var encoded = NativeEffectCodec.encode(exhaustion);
        for (int length = 0; length < encoded.payloadBytes(); length++) {
            var truncated = new NativeActionEffect(encoded.type(), Arrays.copyOf(encoded.payload(), length));
            assertThrows(RuntimeException.class, () -> NativeEffectCodec.decode(truncated, registries));
        }
    }

    @Test void resolvedMenuTranscriptRetainsEveryImageAndOrderedNativeNotice() {
        var empty = NativeStackSnapshot.capture(ItemStack.EMPTY, registries);
        var log = NativeStackSnapshot.capture(new ItemStack(Items.OAK_LOG, 2), registries);
        var planks = NativeStackSnapshot.capture(new ItemStack(Items.OAK_PLANKS, 8), registries);
        var before = image(log, empty, empty, null, 17, 5);
        var interim = image(empty, log, planks, ResourceLocation.parse("minecraft:oak_planks"), 18, 6);
        var after = image(planks, empty, empty, ResourceLocation.parse("minecraft:oak_pressure_plate"), 19, 7);
        List<NativeInventoryMenuPreparation.Notice> notices = List.of(
                new NativeInventoryMenuPreparation.SlotPacket(0, 18, 0, planks),
                new NativeInventoryMenuPreparation.Statistic(new NativeEffectCodec.PlayerStat(
                        ResourceLocation.parse("minecraft:crafted"), ResourceLocation.parse("minecraft:oak_planks"), 8)),
                new NativeInventoryMenuPreparation.Crafted(ResourceLocation.parse("minecraft:oak_planks"), List.of(log)),
                new NativeInventoryMenuPreparation.Unlocked(List.of(ResourceLocation.parse("minecraft:oak_planks"))),
                new NativeInventoryMenuPreparation.SlotPacket(0, 19, 0, empty));
        var script = script(List.of(new NativeInventoryMenuPreparation.Click(36, 0, ClickType.PICKUP),
                new NativeInventoryMenuPreparation.Click(1, 1, ClickType.PICKUP)), before, after, List.of(interim, after), notices);
        var encoded = NativeEffectCodec.encode(script);
        assertEquals("resolved_menu_script_v1", encoded.type());
        assertTrue(encoded.payloadBytes() <= NativeActionEffect.MAX_PAYLOAD_BYTES);
        assertEquals(script, NativeEffectCodec.decode(encoded, registries));
        assertEquals(notices, ((NativeEffectCodec.ResolvedMenuScript) NativeEffectCodec.decode(encoded, registries)).transcript().notices());
    }

    @Test void resolvedMenuScriptRejectsUnresolvedBoundariesAndForeignClickShapes() {
        var empty = NativeStackSnapshot.capture(ItemStack.EMPTY, registries);
        var log = NativeStackSnapshot.capture(new ItemStack(Items.OAK_LOG), registries);
        var resolved = image(log, empty, empty, null, 0, 0);
        var unresolved = image(empty, log, empty, null, 1, 1);
        var clicks = List.of(new NativeInventoryMenuPreparation.Click(1, 0, ClickType.PICKUP));
        assertThrows(IllegalArgumentException.class, () -> script(clicks, resolved, unresolved, List.of(unresolved), List.of()));
        assertThrows(IllegalArgumentException.class, () -> script(clicks, unresolved, resolved, List.of(resolved), List.of()));
        assertThrows(IllegalArgumentException.class, () -> script(clicks, resolved, resolved, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> script(clicks, resolved, resolved, List.of(unresolved), List.of()));
        for (int slot : new int[]{-999, -1, 5, 6, 7, 8, 45, 46}) {
            var forbidden = List.of(new NativeInventoryMenuPreparation.Click(slot, 0, ClickType.PICKUP));
            assertThrows(IllegalArgumentException.class, () -> script(forbidden, resolved, resolved, List.of(resolved), List.of()));
        }
        for (var type : ClickType.values()) if (type != ClickType.PICKUP) {
            var forbidden = List.of(new NativeInventoryMenuPreparation.Click(1, 0, type));
            assertThrows(IllegalArgumentException.class, () -> script(forbidden, resolved, resolved, List.of(resolved), List.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> script(List.of(new NativeInventoryMenuPreparation.Click(1, 2, ClickType.PICKUP)),
                resolved, resolved, List.of(resolved), List.of()));
    }

    @Test void menuVectorsAndTranscriptCountsAreBoundedBeforeDecodingAllocations() throws Exception {
        var encoded = NativeEffectCodec.encode(emptyScript(1));
        byte[] original = encoded.payload();
        int clicksOffset = menuClicksOffset(original);
        for (int count : new int[]{-1, 0, 129, Integer.MAX_VALUE}) {
            byte[] malformed = original.clone(); ByteBuffer.wrap(malformed).putInt(clicksOffset, count);
            assertThrows(IllegalArgumentException.class, () -> NativeEffectCodec.decode(new NativeActionEffect(encoded.type(), malformed), registries));
        }
        int inventoryOffset = clicksOffset + Integer.BYTES + 2 * Integer.BYTES;
        for (int count : new int[]{0, 40, 42, Integer.MAX_VALUE}) {
            byte[] malformed = original.clone(); ByteBuffer.wrap(malformed).putInt(inventoryOffset, count);
            assertThrows(IllegalArgumentException.class, () -> NativeEffectCodec.decode(new NativeActionEffect(encoded.type(), malformed), registries));
        }
        byte[] unknownNotice = Arrays.copyOf(original, original.length + 1);
        ByteBuffer.wrap(unknownNotice).putInt(original.length - Integer.BYTES, 1);
        unknownNotice[original.length] = (byte) 255;
        assertThrows(IllegalArgumentException.class, () -> NativeEffectCodec.decode(new NativeActionEffect(encoded.type(), unknownNotice), registries));
        for (int length : new int[]{0, 1, 4, clicksOffset, inventoryOffset, original.length / 2, original.length - 1}) {
            byte[] truncated = Arrays.copyOf(original, length);
            assertThrows(RuntimeException.class, () -> NativeEffectCodec.decode(new NativeActionEffect(encoded.type(), truncated), registries));
        }
        assertThrows(IllegalArgumentException.class, () -> NativeEffectCodec.decode(
                new NativeActionEffect(encoded.type(), Arrays.copyOf(original, original.length + 1)), registries));
    }

    @Test void completeTwentyThreeClickTranscriptFitsButOversizedJournalFailsExistingEffectCap() {
        var script = emptyScript(23);
        assertEquals(script, NativeEffectCodec.decode(NativeEffectCodec.encode(script), registries));
        assertThrows(IllegalArgumentException.class, () -> NativeEffectCodec.encode(emptyScript(128)));
        assertThrows(IllegalArgumentException.class, () -> emptyScript(129));
    }

    @Test void menuTranscriptAndNoticeListsAreImmutableAndRejectUnboundedNoticeShapes() {
        var empty = NativeStackSnapshot.capture(ItemStack.EMPTY, registries);
        var resolved = image(empty, empty, empty, null, 0, 0);
        var input = new ArrayList<NativeInventoryMenuPreparation.Click>();
        input.add(new NativeInventoryMenuPreparation.Click(9, 0, ClickType.PICKUP));
        var script = script(input, resolved, resolved, List.of(resolved), List.of());
        input.clear(); assertEquals(1, script.clicks().size());
        assertThrows(UnsupportedOperationException.class, () -> script.clicks().clear());
        assertThrows(UnsupportedOperationException.class, () -> script.transcript().steps().clear());
        assertThrows(UnsupportedOperationException.class, () -> script.transcript().after().inventory().clear());
        var click = script.clicks();
        var stat = new NativeInventoryMenuPreparation.Statistic(new NativeEffectCodec.PlayerStat(
                ResourceLocation.parse("minecraft:crafted"), ResourceLocation.parse("minecraft:oak_planks"), 1));
        assertThrows(IllegalArgumentException.class, () -> script(click, resolved, resolved, List.of(resolved), Collections.nCopies(1025, stat)));
        assertThrows(IllegalArgumentException.class, () -> script(click, resolved, resolved, List.of(resolved),
                List.of(new NativeInventoryMenuPreparation.Crafted(ResourceLocation.parse("minecraft:oak_planks"), Collections.nCopies(5, empty)))));
        assertThrows(IllegalArgumentException.class, () -> script(click, resolved, resolved, List.of(resolved),
                List.of(new NativeInventoryMenuPreparation.Unlocked(List.of()))));
        assertThrows(IllegalArgumentException.class, () -> script(click, resolved, resolved, List.of(resolved),
                List.of(new NativeInventoryMenuPreparation.SlotPacket(0, 32768, 0, empty))));
    }

    @Test void nativeIdleDragSentinelAndOnlyPinnedDragKindsRoundTripExactly() {
        var empty = NativeStackSnapshot.capture(ItemStack.EMPTY, registries);
        var resolved = image(empty, empty, empty, null, 0, 0);
        var clicks = List.of(new NativeInventoryMenuPreparation.Click(9, 0, ClickType.PICKUP));
        for (int kind : new int[]{-1, 0, 1, 2}) {
            var exact = withMenuFlags(resolved, 0, false, kind);
            var value = script(clicks, exact, exact, List.of(exact), List.of());
            assertEquals(value, NativeEffectCodec.decode(NativeEffectCodec.encode(value), registries));
        }
        for (int kind : new int[]{Integer.MIN_VALUE, -2, 3, Integer.MAX_VALUE}) {
            var invalid = withMenuFlags(resolved, 0, false, kind);
            assertThrows(IllegalArgumentException.class, () -> script(clicks, invalid, invalid, List.of(invalid), List.of()));
        }
        var idle = withMenuFlags(resolved, 0, false, -1);
        assertThrows(IllegalArgumentException.class, () -> script(clicks, idle, resolved, List.of(resolved), List.of()));
    }

    @Test void menuObserverSuppressionSelectionAndDragMetadataCannotChangeInPickupScript() {
        var empty = NativeStackSnapshot.capture(ItemStack.EMPTY, registries);
        var resolved = image(empty, empty, empty, null, 0, 0);
        var click = List.of(new NativeInventoryMenuPreparation.Click(9, 0, ClickType.PICKUP));
        for (var changed : List.of(withMenuFlags(resolved, 0, true, 0),
                withMenuFlags(resolved, 1, false, 0), withMenuFlags(resolved, 0, false, 1))) {
            assertThrows(IllegalArgumentException.class, () -> script(click, resolved, changed, List.of(changed), List.of()));
        }
        var suppressed = withMenuFlags(resolved, 0, true, 0);
        assertThrows(IllegalArgumentException.class, () -> script(click, suppressed, resolved, List.of(resolved), List.of()));
    }

    private static NativeInventoryMenuSnapshot.Data withMenuFlags(NativeInventoryMenuSnapshot.Data source,
            int selected, boolean suppressed, int quickcraftType) {
        return new NativeInventoryMenuSnapshot.Data(source.inventory(), selected, source.inventoryChanges(), source.grid(),
                source.result(), source.carried(), source.recipeUsed(), source.removeCount(), source.stateId(),
                source.lastSlots(), source.remoteSlots(), source.remoteCarried(), suppressed, quickcraftType);
    }

    private static NativeEffectCodec.ResolvedMenuScript emptyScript(int count) {
        var empty = NativeStackSnapshot.capture(ItemStack.EMPTY, registries);
        var resolved = image(empty, empty, empty, null, 0, 0);
        return script(Collections.nCopies(count, new NativeInventoryMenuPreparation.Click(9, 0, ClickType.PICKUP)),
                resolved, resolved, Collections.nCopies(count, resolved), List.of());
    }
    private static NativeEffectCodec.ResolvedMenuScript script(List<NativeInventoryMenuPreparation.Click> clicks,
            NativeInventoryMenuSnapshot.Data before, NativeInventoryMenuSnapshot.Data after,
            List<NativeInventoryMenuSnapshot.Data> steps, List<NativeInventoryMenuPreparation.Notice> notices) {
        return new NativeEffectCodec.ResolvedMenuScript(new WorldIdentity(39, "source", "v1", "dataset", "palette"),
                new UUID(1, 2), new UUID(3, 4), 7, clicks, new NativeInventoryMenuPreparation.Result(before, after, notices, steps));
    }
    private static NativeInventoryMenuSnapshot.Data image(NativeStackSnapshot owned, NativeStackSnapshot gridValue,
            NativeStackSnapshot carried, ResourceLocation recipe, int state, int changes) {
        var empty = NativeStackSnapshot.capture(ItemStack.EMPTY, registries);
        var inventory = new ArrayList<>(Collections.nCopies(41, empty)); inventory.set(0, owned);
        var grid = new ArrayList<>(Collections.nCopies(4, empty)); grid.set(0, gridValue);
        var last = new ArrayList<>(Collections.nCopies(46, empty)); last.set(36, owned);
        var remote = new ArrayList<>(Collections.nCopies(46, empty)); remote.set(0, gridValue);
        return new NativeInventoryMenuSnapshot.Data(inventory, 0, changes, grid, empty, carried,
                recipe, 0, state, last, remote, empty, false, 0);
    }
    private static int menuClicksOffset(byte[] bytes) throws Exception {
        var input = new DataInputStream(new ByteArrayInputStream(bytes));
        input.readInt(); input.readLong();
        for (int i = 0; i < 4; i++) input.readUTF();
        for (int i = 0; i < 5; i++) input.readLong();
        return bytes.length - input.available();
    }


}
