package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.inventory.ClickType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure admission/capacity checks; transformed menu conservation is covered by actual GameTests. */
class NativeInventoryMenuPreparationTest {
    @Test void allThirteenActualNativeItemsHaveTheAuditedCraftingCallbacks() {
        assertDoesNotThrow(NativeCraftingRecipeAdmission::requireOwnedItemCallbacks);
    }
    @Test void exactNativePickupSlotsAdmitBothNativeMouseButtons() {
        for (int slot = 0; slot < 46; slot++) for (int button = 0; button < 2; button++) {
            var click = new NativeInventoryMenuPreparation.Click(slot, button, ClickType.PICKUP);
            if (slot <= 4 || (slot >= 9 && slot <= 44))
                assertDoesNotThrow(() -> NativeInventoryMenuPreparation.requireClick(click));
            else assertThrows(SurvivalRefusal.class, () -> NativeInventoryMenuPreparation.requireClick(click));
        }
    }
    @Test void unsupportedClickModesButtonsAndOutsideSlotsRefuseBeforeNativeWork() {
        for (ClickType mode : ClickType.values()) if (mode != ClickType.PICKUP)
            assertThrows(SurvivalRefusal.class, () -> NativeInventoryMenuPreparation.requireClick(
                    new NativeInventoryMenuPreparation.Click(36, 0, mode)));
        for (int slot : new int[]{Integer.MIN_VALUE, -999, -1, 46, Integer.MAX_VALUE})
            assertThrows(SurvivalRefusal.class, () -> NativeInventoryMenuPreparation.requireClick(
                    new NativeInventoryMenuPreparation.Click(slot, 0, ClickType.PICKUP)));
        for (int button : new int[]{-1, 2, Integer.MAX_VALUE})
            assertThrows(SurvivalRefusal.class, () -> NativeInventoryMenuPreparation.requireClick(
                    new NativeInventoryMenuPreparation.Click(36, button, ClickType.PICKUP)));
        assertThrows(SurvivalRefusal.class, () -> NativeInventoryMenuPreparation.requireClick(null));
    }
    @Test void everyRetainedSnapshotMustFitTheSameCumulativeBudget() {
        var budget = new NativeInventoryMenuSnapshot.Budget(10);
        budget.charge(6);
        assertThrows(SurvivalRefusal.class, () -> budget.charge(5));
        budget.charge(4);
        assertThrows(SurvivalRefusal.class, () -> budget.charge(1));
        assertThrows(SurvivalRefusal.class, () -> budget.charge(-1));
        assertThrows(IllegalArgumentException.class, () -> new NativeInventoryMenuSnapshot.Budget(0));
    }
    @Test void clickAndMetadataAndSnapshotLimitsAreExplicit() {
        assertDoesNotThrow(() -> new NativeInventoryMenuPreparation.Limits(128, 1024, 1048576, 16*1024*1024));
        assertThrows(IllegalArgumentException.class, () -> new NativeInventoryMenuPreparation.Limits(129, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new NativeInventoryMenuPreparation.Limits(1, 1025, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new NativeInventoryMenuPreparation.Limits(1, 1, 1048577, 1));
        assertThrows(IllegalArgumentException.class, () -> new NativeInventoryMenuPreparation.Limits(1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new NativeInventoryMenuPreparation.Limits(1, 1, 1, 16*1024*1024+1));
    }
    @Test void customCompoundCollectionsAreRefusedWithoutCallingTheirMethods() {
        var calls = new int[1];
        var custom = new AbstractList<Object>() {
            @Override public Object get(int index) { calls[0]++; throw new AssertionError("Must not inspect custom collection"); }
            @Override public int size() { calls[0]++; throw new AssertionError("Must not inspect custom collection"); }
        };
        assertThrows(SurvivalRefusal.class, () -> NativeCraftingRecipeAdmission.requireTrustedList(custom));
        assertEquals(0, calls[0]);
        for (List<?> list : List.of(List.of(), List.of(1), List.of(1, 2, 3), new ArrayList<>(), Arrays.asList(1, 2)))
            assertDoesNotThrow(() -> NativeCraftingRecipeAdmission.requireTrustedList(list));
    }
    @Test void repeatedAcyclicCompoundChildrenStillConsumeExpandedCallbackVisits() {
        int cost = NativeCraftingRecipeAdmission.boundedTraversalCost(List.of(), 100);
        assertEquals(1, cost);
        for (int level = 0; level < 5; level++) cost = NativeCraftingRecipeAdmission.boundedTraversalCost(List.of(cost, cost), 100);
        assertEquals(63, cost);
        final int repeated = cost;
        assertThrows(SurvivalRefusal.class, () -> NativeCraftingRecipeAdmission.boundedTraversalCost(List.of(repeated, repeated), 100));
        assertThrows(SurvivalRefusal.class, () -> NativeCraftingRecipeAdmission.boundedTraversalCost(List.of(Integer.MAX_VALUE), Integer.MAX_VALUE));
        assertThrows(SurvivalRefusal.class, () -> NativeCraftingRecipeAdmission.boundedTraversalCost(List.of(0), 100));
    }

    @Test void exactNativeDifferenceAdmitsOrderedChildrenAndRetainsNativeSemantics() {
        var base = net.minecraft.world.item.crafting.Ingredient.of(net.minecraft.world.item.Items.STICK, net.minecraft.world.item.Items.OAK_LOG);
        var subtracted = net.minecraft.world.item.crafting.Ingredient.of(net.minecraft.world.item.Items.OAK_LOG);
        var difference = new net.neoforged.neoforge.common.crafting.DifferenceIngredient(base, subtracted);
        var children = NativeCraftingRecipeAdmission.nativeCustomChildren(difference, 2);
        assertSame(base, children.get(0)); assertSame(subtracted, children.get(1));
        assertThrows(SurvivalRefusal.class, () -> NativeCraftingRecipeAdmission.nativeCustomChildren(difference, 1));
        assertTrue(difference.isSimple());
        assertTrue(difference.test(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK)));
        assertFalse(difference.test(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG)));
        var values = difference.getItems().toList();
        assertEquals(1, values.size()); assertTrue(values.getFirst().is(net.minecraft.world.item.Items.STICK));
    }
    @Test void differenceFilterChargesLeafAlternativesAndCheckedCrossProductBeforeResolution() {
        int leafCost = NativeCraftingRecipeAdmission.boundedLeafCost(20, 1, 100);
        assertEquals(21, leafCost);
        assertEquals(2200, NativeCraftingRecipeAdmission.boundedFilterCost(100, leafCost, 2200));
        assertThrows(SurvivalRefusal.class, () -> NativeCraftingRecipeAdmission.boundedFilterCost(100, leafCost, 2199));
        assertThrows(SurvivalRefusal.class, () -> NativeCraftingRecipeAdmission.boundedFilterCost(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(SurvivalRefusal.class, () -> NativeCraftingRecipeAdmission.boundedLeafCost(0, 100, 100));
        assertEquals(0, NativeCraftingRecipeAdmission.boundedFilterCost(0, leafCost, 1));
    }
    @Test void otherCustomIngredientTypesAreRefusedBeforeExecutingAnyCallback() {
        int[] calls = {0};
        var custom = new net.neoforged.neoforge.common.crafting.ICustomIngredient() {
            public boolean test(net.minecraft.world.item.ItemStack stack) { calls[0]++; throw new AssertionError("Must not invoke"); }
            public java.util.stream.Stream<net.minecraft.world.item.ItemStack> getItems() { calls[0]++; throw new AssertionError("Must not invoke"); }
            public boolean isSimple() { calls[0]++; throw new AssertionError("Must not invoke"); }
            public net.neoforged.neoforge.common.crafting.IngredientType<?> getType() { calls[0]++; throw new AssertionError("Must not invoke"); }
        };
        var refusal = assertThrows(SurvivalRefusal.class, () -> NativeCraftingRecipeAdmission.nativeCustomChildren(custom, 10));
        assertTrue(refusal.getMessage().contains(custom.getClass().getName()));
        assertEquals(0, calls[0]);
    }

}
