package io.earthplatform.minecraft.survival.interaction;

import java.nio.charset.StandardCharsets;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeStackSnapshotTest {
    private static HolderLookup.Provider registries;
    @BeforeAll static void setup() { registries = VanillaRegistries.createLookup(); }

    @Test void nativeCodecPreservesCountDurabilityAndComponentPatchWithoutAliasing() {
        ItemStack source = new ItemStack(Items.WOODEN_PICKAXE);
        source.setDamageValue(37);
        source.set(DataComponents.CUSTOM_NAME, Component.literal("First tool"));
        NativeStackSnapshot frozen = NativeStackSnapshot.capture(source, registries);
        source.setDamageValue(1); source.remove(DataComponents.CUSTOM_NAME);
        ItemStack restored = frozen.materialize(registries);
        assertSame(Items.WOODEN_PICKAXE, restored.getItem());
        assertEquals(37, restored.getDamageValue());
        assertEquals(Component.literal("First tool"), restored.get(DataComponents.CUSTOM_NAME));
        assertEquals(frozen, NativeStackSnapshot.decode(frozen.encoded(), registries));
        byte[] leaked = frozen.encoded(); leaked[0] = 0;
        assertEquals(37, frozen.materialize(registries).getDamageValue());
        restored.setDamageValue(2);
        assertEquals(37, frozen.materialize(registries).getDamageValue());
    }

    @Test void fullCountsAndRemovedComponentsSurviveNativeRoundtrip() {
        ItemStack stack = new ItemStack(Items.OAK_PLANKS, 47);
        stack.remove(DataComponents.MAX_STACK_SIZE);
        NativeStackSnapshot frozen = NativeStackSnapshot.capture(stack, registries);
        assertEquals(47, frozen.materialize(registries).getCount());
        assertNull(frozen.materialize(registries).get(DataComponents.MAX_STACK_SIZE));
        assertTrue(frozen.matches(stack, registries));
        stack.shrink(1);
        assertFalse(frozen.matches(stack, registries));
    }

    @Test void emptyStackAndMalformedPayloadsAreExplicit() {
        NativeStackSnapshot empty = NativeStackSnapshot.capture(ItemStack.EMPTY, registries);
        assertTrue(empty.isEmpty()); assertTrue(empty.materialize(registries).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> NativeStackSnapshot.decode(new byte[0], registries));
        assertThrows(IllegalArgumentException.class, () -> NativeStackSnapshot.decode(new byte[NativeStackSnapshot.MAX_BYTES + 1], registries));
        assertThrows(RuntimeException.class, () -> NativeStackSnapshot.decode("{}".getBytes(StandardCharsets.UTF_8), registries));
        assertThrows(RuntimeException.class, () -> NativeStackSnapshot.decode("null false".getBytes(StandardCharsets.UTF_8), registries));
        assertThrows(RuntimeException.class, () -> NativeStackSnapshot.decode(new byte[]{(byte) 0xc3, 0x28}, registries));
    }

    @Test void deeplyNestedBoundedPayloadIsRefusedBeforeRecursiveJsonParsing() {
        byte[] deep = ("[".repeat(1000) + "0" + "]".repeat(1000)).getBytes(StandardCharsets.UTF_8);
        assertTrue(deep.length < NativeStackSnapshot.MAX_BYTES);
        var refusal = assertThrows(IllegalArgumentException.class, () -> NativeStackSnapshot.decode(deep, registries));
        assertTrue(refusal.getMessage().contains("nesting exceeds"));
    }

    @Test void quotedEscapedBracketsDoNotCountAsStructuralDepth() {
        String name = "[{\\\"".repeat(100) + "}]";
        ItemStack stack = new ItemStack(Items.OAK_LOG);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        NativeStackSnapshot snapshot = NativeStackSnapshot.capture(stack, registries);
        assertEquals(Component.literal(name), NativeStackSnapshot.decode(snapshot.encoded(), registries)
                .materialize(registries).get(DataComponents.CUSTOM_NAME));
    }

    @Test void nativeTransientComponentsRefuseInsteadOfSilentlyDisappearing() {
        DataComponentType<Integer> transientComponent = DataComponentType.<Integer>builder()
                .networkSynchronized(ByteBufCodecs.INT).build();
        assertTrue(transientComponent.isTransient());
        ItemStack stack = new ItemStack(Items.OAK_LOG, 2);
        stack.set(transientComponent, 39);
        var refusal = assertThrows(IllegalArgumentException.class, () -> NativeStackSnapshot.capture(stack, registries));
        assertTrue(refusal.getMessage().contains("non-roundtripping"));
        assertEquals(39, stack.get(transientComponent));
        assertEquals(2, stack.getCount());
    }
}
