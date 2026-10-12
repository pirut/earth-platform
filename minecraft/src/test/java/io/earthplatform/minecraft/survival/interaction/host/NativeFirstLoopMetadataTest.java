package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.advancements.critereon.RecipeCraftedTrigger;
import net.minecraft.advancements.critereon.RecipeUnlockedTrigger;
import net.minecraft.commands.CacheableFunction;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native record/holder fixtures test the pure audit, not survival progression or a live menu. */
class NativeFirstLoopMetadataTest {
    private static final NativeFirstLoopMetadata.Limits LIMITS =
            new NativeFirstLoopMetadata.Limits(4096, 16384, 8192, 65536, 128, 128, 1);

    @Test void conservativeMatchingCriterionTreatsPreexistingProgressAsPotentiallyComplete() {
        var reachable = advancement("unsafe", Map.of(
                "log", InventoryChangeTrigger.TriggerInstance.hasItems(Items.OAK_LOG),
                "later", RecipeUnlockedTrigger.unlocked(id("not_yet_known"))), xp(1));
        assertThrows(SurvivalRefusal.class, () -> audit(reachable));
    }
    @Test void recursiveRecipeOnlyUnlocksReachFixedPointAndPermitMetadataCycles() {
        var log = advancement("log", Map.of("log", InventoryChangeTrigger.TriggerInstance.hasItems(Items.OAK_LOG)), recipes("wooden_axe"));
        var axe = advancement("axe", Map.of("known", RecipeUnlockedTrigger.unlocked(id("wooden_axe"))), recipes("furnace"));
        var furnace = advancement("furnace", Map.of("known", RecipeUnlockedTrigger.unlocked(id("furnace"))), recipes("wooden_axe"));
        var result = audit(log, axe, furnace).summary;
        assertEquals(3, result.reachableAdvancements().size());
        assertTrue(result.unlockRecipes().containsAll(Set.of(id("wooden_axe"), id("furnace"))));
        assertThrows(SurvivalRefusal.class, () -> NativeFirstLoopMetadata.requireCraftedId(id("wooden_axe")));
        assertThrows(SurvivalRefusal.class, () -> NativeFirstLoopMetadata.requireCraftedId(id("furnace")));
        for (var recipe : NativeFirstLoopMetadata.craftedRecipes())
            assertDoesNotThrow(() -> NativeFirstLoopMetadata.requireCraftedId(recipe));
    }
    @Test void bothActualNativeLeafLootItemsBelongToTheClosedUniverse() {
        assertTrue(NativeFirstLoopMetadata.itemUniverse().containsAll(Set.of(id("apple"), id("oak_sapling"))));
        for (var item : List.of(Items.APPLE, Items.OAK_SAPLING)) {
            var reachable = advancement("leaf_reward", Map.of("item", InventoryChangeTrigger.TriggerInstance.hasItems(item)), xp(5));
            assertThrows(SurvivalRefusal.class, () -> audit(reachable));
        }
    }
    @Test void unachievableNetheriteRewardIsNotMistakenForReachable() {
        var unreachable = advancement("netherite", Map.of("item", InventoryChangeTrigger.TriggerInstance.hasItems(Items.NETHERITE_HOE)), xp(100));
        assertTrue(audit(unreachable).summary.reachableAdvancements().isEmpty());
    }
    @Test void everyConservedRewardKindRefusesBeforeAnyCallback() {
        for (var reward : List.of(xp(1), xp(-1),
                new AdvancementRewards(0, List.of(ResourceKey.create(Registries.LOOT_TABLE, id("test"))), List.of(), Optional.empty()),
                new AdvancementRewards(0, List.of(), List.of(), Optional.of(new CacheableFunction(id("test")))))) {
            var holder = advancement("unsafe", Map.of("log", InventoryChangeTrigger.TriggerInstance.hasItems(Items.OAK_LOG)), reward);
            assertThrows(SurvivalRefusal.class, () -> audit(holder));
        }
    }
    @Test void recursiveUnlockCannotHideConservedRewards() {
        var start = advancement("start", Map.of("log", InventoryChangeTrigger.TriggerInstance.hasItems(Items.OAK_LOG)), recipes("unexpected"));
        var unsafe = advancement("unsafe", Map.of("known", RecipeUnlockedTrigger.unlocked(id("unexpected"))), xp(10));
        assertThrows(SurvivalRefusal.class, () -> audit(start, unsafe));
    }
    @Test void nativeCraftedCriterionUsesOnlyTheFiveDirectCrafts() {
        var permitted = advancement("pickaxe", Map.of("craft", RecipeCraftedTrigger.TriggerInstance.craftedItem(id("wooden_pickaxe"))), AdvancementRewards.EMPTY);
        var refused = advancement("axe", Map.of("craft", RecipeCraftedTrigger.TriggerInstance.craftedItem(id("wooden_axe"))), xp(100));
        assertEquals(Set.of(permitted.id()), audit(permitted, refused).summary.reachableAdvancements());
    }
    @Test void wildcardAndMultiItemPredicatesConservativelyBroadenReachability() {
        var wildcard = InventoryChangeTrigger.TriggerInstance.hasItems(ItemPredicate.Builder.item().build());
        assertThrows(SurvivalRefusal.class, () -> audit(advancement("any", Map.of("item", wildcard), xp(1))));
        var multi = InventoryChangeTrigger.TriggerInstance.hasItems(Items.APPLE, Items.OAK_SAPLING);
        assertThrows(SurvivalRefusal.class, () -> audit(advancement("multi", Map.of("item", multi), xp(1))));
    }
    @Test void nativeComponentPredicatesRefuseEvenOutsideReachableRewards() {
        var predicate = ItemPredicate.Builder.item().of(Items.NETHERITE_HOE)
                .hasComponents(DataComponentPredicate.builder().expect(DataComponents.DAMAGE, 1).build()).build();
        var holder = advancement("custom", Map.of("item", InventoryChangeTrigger.TriggerInstance.hasItems(predicate)), AdvancementRewards.EMPTY);
        assertThrows(SurvivalRefusal.class, () -> audit(holder));
    }
    @Test void nativePlayerPredicatesRefuseWithoutRunningThem() {
        var instance = new InventoryChangeTrigger.TriggerInstance(Optional.of(ContextAwarePredicate.create()),
                InventoryChangeTrigger.TriggerInstance.Slots.ANY, List.of());
        var holder = advancement("player_predicate", Map.of("item", CriteriaTriggers.INVENTORY_CHANGED.createCriterion(instance)), AdvancementRewards.EMPTY);
        assertThrows(SurvivalRefusal.class, () -> audit(holder));
    }
    @Test void duplicateAndExplicitCapacityBoundsRefuse() {
        var holder = advancement("one", Map.of("item", InventoryChangeTrigger.TriggerInstance.hasItems(Items.OAK_LOG)), recipes("sixth"));
        assertThrows(SurvivalRefusal.class, () -> audit(holder, holder));
        var limits = new NativeFirstLoopMetadata.Limits(1, 1, 1, 1, 1, 5, 1);
        assertThrows(SurvivalRefusal.class, () -> NativeFirstLoopMetadata.auditLoaded(List.of(holder), limits));
    }
    @Test void xpZeroAdmissionRejectsEveryNormalizationOrLevelPath() {
        assertDoesNotThrow(() -> NativeFirstLoopMetadata.requireZeroXpNoOp(0, 0, 0, 7));
        assertDoesNotThrow(() -> NativeFirstLoopMetadata.requireZeroXpNoOp(0.5F, 17, 2, 11));
        for (float fraction : new float[]{Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -0.01F, 1, 2})
            assertThrows(SurvivalRefusal.class, () -> NativeFirstLoopMetadata.requireZeroXpNoOp(fraction, 0, 0, 7));
        assertThrows(SurvivalRefusal.class, () -> NativeFirstLoopMetadata.requireZeroXpNoOp(0, -1, 0, 7));
        assertThrows(SurvivalRefusal.class, () -> NativeFirstLoopMetadata.requireZeroXpNoOp(0, 0, -1, 7));
        assertThrows(SurvivalRefusal.class, () -> NativeFirstLoopMetadata.requireZeroXpNoOp(0, 0, 0, 0));
    }
    @Test void exactTwoByTwoAndThreeByThreePickupShapesRemainDistinct() {
        var inventory = NativeFirstLoopMetadata.MenuShape.INVENTORY_2X2;
        var table = NativeFirstLoopMetadata.MenuShape.TABLE_3X3;
        assertEquals(4, inventory.gridSlots); assertEquals(9, table.gridSlots);
        for (int slot : new int[]{0, 1, 4, 9, 36, 44}) assertTrue(inventory.admitsInitialPickupSlot(slot));
        for (int slot : new int[]{-999, -1, 5, 6, 7, 8, 45, 46}) assertFalse(inventory.admitsInitialPickupSlot(slot));
        for (int slot = 0; slot < 46; slot++) assertTrue(table.admitsInitialPickupSlot(slot));
        assertFalse(table.admitsInitialPickupSlot(-999)); assertFalse(table.admitsInitialPickupSlot(46));
    }
    private static NativeFirstLoopMetadata.Audit audit(AdvancementHolder... holders) {
        return NativeFirstLoopMetadata.auditLoaded(List.of(holders), LIMITS);
    }
    private static AdvancementHolder advancement(String name, Map<String, Criterion<?>> criteria, AdvancementRewards rewards) {
        return new AdvancementHolder(id(name), new Advancement(Optional.empty(), Optional.empty(), rewards,
                criteria, AdvancementRequirements.allOf(criteria.keySet()), false));
    }
    private static AdvancementRewards xp(int value) { return new AdvancementRewards(value, List.of(), List.of(), Optional.empty()); }
    private static AdvancementRewards recipes(String id) { return new AdvancementRewards(0, List.of(), List.of(id(id)), Optional.empty()); }
    private static ResourceLocation id(String path) { return ResourceLocation.withDefaultNamespace(path); }
}
