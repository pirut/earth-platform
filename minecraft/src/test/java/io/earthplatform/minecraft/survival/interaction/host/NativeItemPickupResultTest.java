package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.persistence.NativeItemEntitySnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Data-only preflight tests, not evidence of live native playerTouch or tracking execution. */
class NativeItemPickupResultTest {
    private static HolderLookup.Provider registries;
    private static final UUID ID = new UUID(391, 724);
    @BeforeAll static void setup() { registries = VanillaRegistries.createLookup(); }

    @Test void fullAndPartialPickupsConserveExactNativeQuantityAndComponents() throws Exception {
        var before = item(7); var after = item(3);
        var full = new NativeEffectCodec.ItemPickup(ID, 39, before, null, 7, 7);
        var partial = new NativeEffectCodec.ItemPickup(ID, 39, before, after, 4, 4);
        assertTrue(NativeEffectDeliveryHost.validatePickupResult(full, registries).isEmpty());
        assertEquals(3, NativeEffectDeliveryHost.validatePickupResult(partial, registries).getCount());
        assertEquals(full, NativeEffectCodec.decode(NativeEffectCodec.encode(full), registries));
        assertEquals(partial, NativeEffectCodec.decode(NativeEffectCodec.encode(partial), registries));
    }
    @Test void nativePartialTransferWithoutTakePreservesZeroNotificationMetadata() throws Exception {
        var before = item(2); var after = item(1);
        var partial = new NativeEffectCodec.ItemPickup(ID, 39, before, after, 1, 0);
        assertEquals(1, NativeEffectDeliveryHost.validatePickupResult(partial, registries).getCount());
        assertEquals(0, partial.takeAmount());
        assertEquals(partial, NativeEffectCodec.decode(NativeEffectCodec.encode(partial), registries));
        assertThrows(IllegalArgumentException.class, () -> new NativeEffectCodec.ItemPickup(ID, 39, before, after, 2, 1));
    }
    @Test void missingOrIncorrectRemaindersFailBeforeDelivery() throws Exception {
        var before = item(7); var after = item(3);
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.validatePickupResult(
                new NativeEffectCodec.ItemPickup(ID, 39, before, null, 6, 6), registries));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.validatePickupResult(
                new NativeEffectCodec.ItemPickup(ID, 39, before, after, 5, 5), registries));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.validatePickupResult(
                new NativeEffectCodec.ItemPickup(ID, 39, before, after, 8, 8), registries));
    }
    @Test void unrelatedNativeFieldAndComponentChangesAreRefused() throws Exception {
        var before = item(7); var after = item(3);
        var alteredHealth = after.tagCopy(); alteredHealth.putShort("Health", (short) 4);
        var health = snapshot(alteredHealth);
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.validatePickupResult(
                new NativeEffectCodec.ItemPickup(ID, 39, before, health, 4, 4), registries));
        var alteredStack = after.tagCopy(); alteredStack.put("Item", new ItemStack(Items.COBBLESTONE, 3).save(registries));
        var stack = snapshot(alteredStack);
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.validatePickupResult(
                new NativeEffectCodec.ItemPickup(ID, 39, before, stack, 4, 4), registries));
    }
    @Test void ownerAndThrowerCriteriaRefuseUntilCaptured() throws Exception {
        for (String key : new String[]{"Owner", "Thrower"}) {
            var tag = item(1).tagCopy(); tag.putUUID(key, new UUID(393, 1));
            var before = snapshot(tag);
            assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.validatePickupResult(
                    new NativeEffectCodec.ItemPickup(ID, 39, before, null, 1, 1), registries));
        }
    }
    @Test void pickupCodecRejectsTruncationTrailingBytesAndForeignIdentity() throws Exception {
        var before = item(4); var after = item(1);
        var encoded = NativeEffectCodec.encode(new NativeEffectCodec.ItemPickup(ID, 39, before, after, 3, 3));
        byte[] bytes = encoded.payload();
        for (int length = 0; length < bytes.length; length++) {
            var truncated = new io.earthplatform.minecraft.survival.world.NativeActionEffect(encoded.type(), java.util.Arrays.copyOf(bytes, length));
            assertThrows(RuntimeException.class, () -> NativeEffectCodec.decode(truncated, registries));
        }
        var trailing = new io.earthplatform.minecraft.survival.world.NativeActionEffect(encoded.type(), java.util.Arrays.copyOf(bytes, bytes.length + 1));
        assertThrows(IllegalArgumentException.class, () -> NativeEffectCodec.decode(trailing, registries));
        assertThrows(IllegalArgumentException.class, () -> new NativeEffectCodec.ItemPickup(new UUID(0, 0), 39, before, null, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> new NativeEffectCodec.ItemPickup(ID, 0, before, null, 4, 4));
    }
    @Test void acceptedNativeTickCodecBindsFullSourceAndCannotOverflowNativeTickCount() throws Exception {
        var source = item(4);
        var tick = new NativeEffectCodec.ItemTick(ID, 39, source, 7);
        assertEquals(tick, NativeEffectCodec.decode(NativeEffectCodec.encode(tick), registries));
        assertThrows(IllegalArgumentException.class, () -> new NativeEffectCodec.ItemTick(ID, 39, source, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new NativeEffectCodec.ItemTick(ID, 0, source, 7));
        assertThrows(IllegalArgumentException.class, () -> new NativeEffectCodec.ItemTick(new UUID(1, 2), 39, source, 7));
    }
    private static NativeItemEntitySnapshot item(int count) throws Exception {
        ItemStack stack = new ItemStack(Items.OAK_LOG, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Native fixture log"));
        CompoundTag tag = new CompoundTag(); tag.putUUID("UUID", ID); tag.put("Item", stack.save(registries));
        tag.putShort("Health", (short) 5); tag.putShort("Age", (short) 8); tag.putShort("PickupDelay", (short) 0); tag.putInt("Lifespan", 6000);
        ListTag pos = new ListTag(); pos.add(DoubleTag.valueOf(-17.5)); pos.add(DoubleTag.valueOf(8849)); pos.add(DoubleTag.valueOf(-3)); tag.put("Pos", pos);
        ListTag motion = new ListTag(); for (int i = 0; i < 3; i++) motion.add(DoubleTag.valueOf(0)); tag.put("Motion", motion);
        ListTag rotation = new ListTag(); rotation.add(FloatTag.valueOf(0)); rotation.add(FloatTag.valueOf(0)); tag.put("Rotation", rotation);
        return snapshot(tag);
    }
    private static NativeItemEntitySnapshot snapshot(CompoundTag tag) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) { NbtIo.write(tag, output); }
        return NativeItemEntitySnapshot.decode(bytes.toByteArray(), registries);
    }
}
