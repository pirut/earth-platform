package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.advancements.critereon.RecipeCraftedTrigger;
import net.minecraft.advancements.critereon.RecipeUnlockedTrigger;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.StatAwardEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;

/**
 * Read-only admission of the exact loaded first-loop advancement/recipe metadata graph.
 * This is NOT an action owner, recipe-matcher admission, packet-budget admission, menu snapshot,
 * callback journal or permission to mutate inventory. The caller must separately own those seams.
 *
 * <p>The fixed universe includes real native oak-leaf apple/sapling drops. The five crafted IDs
 * are deliberately narrower than native recipe-book unlocks. Reachability ignores count/slot
 * restrictions and existing progress: any matching criterion might complete its advancement.
 * Empty player/component/subpredicates are required throughout the three invoked trigger families.
 * Original native callbacks may execute only under a separate committed metadata-delivery receipt.
 * No callback is invoked, suppressed, removed, replaced or synthesized by this class.
 *
 * <p>Capture and revalidation run on the exact server thread, under the pinned no-concurrent-reload
 * or mod-registration contract. Loaded manager and holder identities, complete relevant holder/tag
 * contents and actual trigger-listener registrations are rechecked; no packaged census grants
 * runtime authority. Native advancement/recipe-book changes remain normal native save metadata.
 */
public final class NativeFirstLoopMetadata {
    private static final Set<ResourceLocation> ITEMS = ids("oak_log", "oak_planks", "stick", "crafting_table",
            "wooden_pickaxe", "cobblestone", "stone_pickaxe", "dirt", "stone", "oak_leaves", "grass_block",
            "oak_sapling", "apple");
    private static final Set<ResourceLocation> CRAFTED = ids("oak_planks", "stick", "crafting_table",
            "wooden_pickaxe", "stone_pickaxe");

    public record Limits(int advancements, int criteria, int predicates, int holderEntries,
            int reachableAdvancements, int unlockRecipes, int audience) {
        public Limits {
            if (advancements < 1 || criteria < 1 || predicates < 1 || holderEntries < 1
                    || reachableAdvancements < 1 || unlockRecipes < 5 || audience < 1)
                throw new IllegalArgumentException("Explicit positive metadata bounds required");
        }
    }
    public record Summary(int loadedAdvancements, int relevantCriteria, int inspectedPredicates,
            int inspectedHolderEntries, Set<ResourceLocation> reachableAdvancements,
            Set<ResourceLocation> unlockRecipes) {
        public Summary {
            reachableAdvancements = Set.copyOf(reachableAdvancements);
            unlockRecipes = Set.copyOf(unlockRecipes);
        }
    }
    /** Same total native slot count, distinct meanings. Armor/offhand remain captured but refused. */
    public enum MenuShape {
        INVENTORY_2X2(2, 2, 4), TABLE_3X3(3, 3, 9);
        public final int width, height, gridSlots;
        MenuShape(int width, int height, int gridSlots) {
            this.width = width; this.height = height; this.gridSlots = gridSlots;
        }
        public boolean admitsInitialPickupSlot(int slot) {
            return slot >= 0 && slot < 46 && (this == TABLE_3X3 || slot < 5 || (slot >= 9 && slot < 45));
        }
        public static MenuShape of(AbstractContainerMenu menu) {
            if (menu.getClass() == InventoryMenu.class && menu.containerId == 0 && menu.slots.size() == 46)
                return INVENTORY_2X2;
            if (menu.getClass() == CraftingMenu.class && menu.containerId > 0 && menu.slots.size() == 46)
                return TABLE_3X3;
            throw refused("Only exact native 2x2 InventoryMenu or 3x3 CraftingMenu shape is admitted");
        }
    }

    private enum Kind { INVENTORY, CRAFTED, UNLOCKED }
    private record PredicateView(boolean anyItem, Set<ResourceLocation> items) {}
    private record CriterionView(Kind kind, ResourceLocation recipe, List<PredicateView> predicates) {
        boolean reachable(Set<ResourceLocation> unlocked) {
            if (kind == Kind.UNLOCKED) return unlocked.contains(recipe);
            if (kind == Kind.CRAFTED && !CRAFTED.contains(recipe)) return false;
            return predicates.stream().allMatch(p -> p.anyItem || p.items.stream().anyMatch(ITEMS::contains));
        }
    }
    private record Node(AdvancementHolder holder, Map<String, CriterionView> criteria) {}
    static final class Audit {
        final Map<ResourceLocation, Node> nodes;
        final Summary summary;
        Audit(Map<ResourceLocation, Node> nodes, Summary summary) { this.nodes = Map.copyOf(nodes); this.summary = summary; }
    }
    private final ServerPlayer player;
    private final MinecraftServer server;
    private final Object resources, advancements, recipes, registries;
    private final PlayerAdvancements progress;
    private final Object recipeBook;
    private final Limits limits;
    private final PinnedNativeEventAdmission events;
    private final Audit captured;
    private final Map<ResourceLocation, RecipeHolder<?>> rewardRecipes;

    private NativeFirstLoopMetadata(ServerPlayer player, Limits limits) {
        this.player = Objects.requireNonNull(player); this.server = player.server;
        this.limits = Objects.requireNonNull(limits);
        requirePlayer();
        resources = server.getResourceManager(); advancements = server.getAdvancements();
        recipes = server.getRecipeManager(); registries = server.reloadableRegistries();
        progress = player.getAdvancements(); recipeBook = player.getRecipeBook();
        if (progress.getClass() != PlayerAdvancements.class
                || recipeBook.getClass() != net.minecraft.stats.ServerRecipeBook.class)
            throw refused("Custom native progress/recipe-book implementation is not admitted");
        events = new PinnedNativeEventAdmission(NeoForge.EVENT_BUS);
        requireCallbacks();
        captured = auditLoaded(server.getAdvancements().getAllAdvancements(), limits);
        var recipeCopy = new HashMap<ResourceLocation, RecipeHolder<?>>();
        for (ResourceLocation id : captured.summary.unlockRecipes) {
            RecipeHolder<?> holder = server.getRecipeManager().byKey(id)
                    .orElseThrow(() -> refused("Native reward recipe is missing: " + id));
            requireMetadataRecipe(holder);
            recipeCopy.put(id, holder);
        }
        rewardRecipes = Map.copyOf(recipeCopy);
        requireLiveInventory();
        requireActualTriggerListeners();
    }
    public static NativeFirstLoopMetadata capture(ServerPlayer player, Limits limits) {
        return new NativeFirstLoopMetadata(player, limits);
    }
    public Summary summary() { return captured.summary; }
    /** A read-only admission cannot be reused for a different native player's callback graph. */
    public void requirePlayerIdentity(ServerPlayer expected) {
        if (expected != player) throw refused("Native metadata capability belongs to another player");
        requirePlayer();
    }
    public static Set<ResourceLocation> itemUniverse() { return ITEMS; }
    public static Set<ResourceLocation> craftedRecipes() { return CRAFTED; }

    /** Re-run before preparation, immediately before commit, and around native metadata delivery. */
    public void revalidate() {
        requirePlayer(); requireCallbacks();
        if (resources != server.getResourceManager() || advancements != server.getAdvancements()
                || recipes != server.getRecipeManager() || registries != server.reloadableRegistries()
                || progress != player.getAdvancements() || recipeBook != player.getRecipeBook())
            throw refused("Native metadata resource/player generation changed");
        Audit current = auditLoaded(server.getAdvancements().getAllAdvancements(), limits);
        if (!captured.summary.equals(current.summary) || !captured.nodes.keySet().equals(current.nodes.keySet()))
            throw refused("Native metadata closure changed");
        for (var entry : captured.nodes.entrySet()) {
            Node now = current.nodes.get(entry.getKey()), before = entry.getValue();
            if (now.holder != before.holder || !now.criteria.equals(before.criteria))
                throw refused("Native advancement instance or item-tag membership changed: " + entry.getKey());
        }
        for (var entry : rewardRecipes.entrySet())
            if (server.getRecipeManager().byKey(entry.getKey()).orElse(null) != entry.getValue())
                throw refused("Native reward recipe instance changed: " + entry.getKey());
        requireLiveInventory();
        requireActualTriggerListeners();
    }
    private void requireLiveInventory() {
        if (player.getInventory().getContainerSize() != 41) throw refused("Exact native inventory shape changed");
        var stacks = new ArrayList<ItemStack>(41);
        for (int slot = 0; slot < 41; slot++) stacks.add(player.getInventory().getItem(slot));
        requireItems(stacks);
    }
    /** The action owner must also pass its complete detached grid/result/cursor/output boundary. */
    public void requireItems(Iterable<ItemStack> stacks) {
        if (!server.isSameThread()) throw refused("Native item-universe audit requires the owner thread");
        int count = 0;
        for (ItemStack stack : stacks) {
            if (++count > 64) throw refused("Metadata item boundary exceeds native inventory/menu capacity");
            Objects.requireNonNull(stack);
            if (!stack.isEmpty() && !ITEMS.contains(BuiltInRegistries.ITEM.getKey(stack.getItem())))
                throw refused("Item is outside the closed first-loop metadata universe");
        }
    }
    public void requireCraftedRecipe(RecipeHolder<?> recipe) {
        requireCraftedId(recipe.id());
        if (rewardRecipes.get(recipe.id()) != recipe) throw refused("Crafted recipe is not the captured native instance");
    }
    static void requireCraftedId(ResourceLocation id) {
        if (!CRAFTED.contains(id)) throw refused("Native recipe result is outside the five first-loop crafts: " + id);
    }
    public void requireUnlockRecipe(RecipeHolder<?> recipe) {
        if (rewardRecipes.get(recipe.id()) != recipe) throw refused("Recipe unlock is outside the audited native closure");
    }
    private void requirePlayer() {
        if (!server.isSameThread() || player.getClass() != ServerPlayer.class || player.server != server
                || player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL || player.isCreative() || player.isSpectator()
                || player.getAbilities().instabuild || player.getAbilities().mayfly)
            throw refused("Exact server-thread native Survival player required for metadata admission");
        requireZeroXpNoOp(player.experienceProgress, player.totalExperience, player.experienceLevel,
                player.getXpNeededForNextLevel());
        if (server.getPlayerList().getPlayers().size() > limits.audience)
            throw refused("Native advancement announcement audience exceeds admission");
    }
    static void requireZeroXpNoOp(float fraction, int total, int level, int needed) {
        if (!Float.isFinite(fraction) || fraction < 0 || fraction >= 1 || total < 0 || level < 0 || needed <= 0)
            throw refused("Native giveExperiencePoints(0) could alter invalid XP state");
    }
    private void requireCallbacks() {
        events.requireEmpty(AdvancementEvent.AdvancementProgressEvent.class);
        events.requireEmpty(AdvancementEvent.AdvancementEarnEvent.class);
        events.requireEmpty(PlayerXpEvent.XpChange.class);
        events.requireEmpty(PlayerXpEvent.LevelChange.class);
        events.requireEmpty(PlayerEvent.ItemCraftedEvent.class);
        events.requireEmpty(StatAwardEvent.class);
        events.requireEmpty(PlayerEvent.NameFormat.class);
    }

    /** Conservative action-specific subset of the already admitted graph. Counts/player predicates
     * are over-approximated, so prior partial progress cannot hide a newly completed reward. */
    Summary scriptClosure(Set<ResourceLocation> ownedItems, Set<ResourceLocation> crafted,
            Set<ResourceLocation> initialUnlocks) {
        revalidate();
        if (!ITEMS.containsAll(ownedItems) || !CRAFTED.containsAll(crafted)
                || !captured.summary.unlockRecipes.containsAll(initialUnlocks))
            throw refused("Native script closure escaped the admitted item/recipe universe");
        var reached = new HashSet<ResourceLocation>(); var unlocked = new HashSet<>(initialUnlocks);
        boolean changed;
        do {
            changed = false;
            for (Node node : captured.nodes.values()) {
                if (reached.contains(node.holder.id())) continue;
                boolean possible = node.criteria.values().stream().anyMatch(c -> {
                    if (c.kind == Kind.UNLOCKED) return unlocked.contains(c.recipe);
                    if (c.kind == Kind.CRAFTED && !crafted.contains(c.recipe)) return false;
                    return c.predicates.stream().allMatch(p -> p.anyItem || p.items.stream().anyMatch(ownedItems::contains));
                });
                if (!possible) continue;
                reached.add(node.holder.id());
                if (!captured.summary.reachableAdvancements.contains(node.holder.id()))
                    throw refused("Native script advancement is outside the captured reward graph");
                changed |= unlocked.addAll(node.holder.value().rewards().recipes());
            }
        } while (changed);
        if (!captured.summary.unlockRecipes.containsAll(unlocked)) throw refused("Native script recipe closure grew beyond admission");
        return new Summary(captured.summary.loadedAdvancements, captured.summary.relevantCriteria,
                captured.summary.inspectedPredicates, captured.summary.inspectedHolderEntries, reached, unlocked);
    }

    /** Pure loaded-object audit: no trigger matching or reward/recipe callback is invoked. */
    static Audit auditLoaded(Collection<AdvancementHolder> loaded, Limits limits) {
        if (loaded.size() > limits.advancements) throw refused("Loaded advancement limit exceeded");
        var nodes = new LinkedHashMap<ResourceLocation, Node>();
        int allCriteria = 0, relevantCriteria = 0; int[] inspected = {0, 0};
        for (AdvancementHolder holder : loaded) {
            var criteria = new LinkedHashMap<String, CriterionView>();
            for (var entry : holder.value().criteria().entrySet()) {
                if (++allCriteria > limits.criteria) throw refused("Loaded advancement criterion limit exceeded");
                CriterionView view = inspectCriterion(entry.getValue(), limits, inspected);
                if (view != null) { criteria.put(entry.getKey(), view); relevantCriteria++; }
            }
            if (nodes.put(holder.id(), new Node(holder, Map.copyOf(criteria))) != null)
                throw refused("Duplicate loaded native advancement identity");
        }
        var reached = new HashSet<ResourceLocation>();
        var unlocked = new HashSet<>(CRAFTED);
        boolean changed;
        do {
            changed = false;
            for (Node node : nodes.values()) {
                if (reached.contains(node.holder.id()) || node.criteria.values().stream().noneMatch(c -> c.reachable(unlocked))) continue;
                reached.add(node.holder.id());
                if (reached.size() > limits.reachableAdvancements) throw refused("Reachable advancement limit exceeded");
                var rewards = node.holder.value().rewards();
                if (rewards.experience() != 0 || !rewards.loot().isEmpty() || rewards.function().isPresent())
                    throw refused("Reachable native advancement has conserved rewards: " + node.holder.id());
                if (rewards.recipes().size() > limits.unlockRecipes) throw refused("Native reward recipe list exceeds admission");
                for (ResourceLocation recipe : rewards.recipes()) {
                    changed |= unlocked.add(recipe);
                    if (unlocked.size() > limits.unlockRecipes) throw refused("Native recipe unlock closure limit exceeded");
                }
            }
        } while (changed);
        return new Audit(nodes, new Summary(nodes.size(), relevantCriteria, inspected[0], inspected[1], reached, unlocked));
    }
    private static CriterionView inspectCriterion(Criterion<?> criterion, Limits limits, int[] inspected) {
        if (criterion.trigger() == CriteriaTriggers.INVENTORY_CHANGED) {
            if (criterion.trigger().getClass() != InventoryChangeTrigger.class
                    || criterion.triggerInstance().getClass() != InventoryChangeTrigger.TriggerInstance.class)
                throw refused("Unrecognized native inventory criterion implementation");
            var instance = (InventoryChangeTrigger.TriggerInstance) criterion.triggerInstance();
            if (instance.player().isPresent()) throw refused("Inventory criterion player predicate is outside first-loop admission");
            return new CriterionView(Kind.INVENTORY, null, inspectPredicates(instance.items(), limits, inspected));
        }
        if (criterion.trigger() == CriteriaTriggers.RECIPE_CRAFTED) {
            if (criterion.trigger().getClass() != RecipeCraftedTrigger.class
                    || criterion.triggerInstance().getClass() != RecipeCraftedTrigger.TriggerInstance.class)
                throw refused("Unrecognized native crafted criterion implementation");
            var instance = (RecipeCraftedTrigger.TriggerInstance) criterion.triggerInstance();
            if (instance.player().isPresent()) throw refused("Crafted criterion player predicate is outside first-loop admission");
            return new CriterionView(Kind.CRAFTED, instance.recipeId(), inspectPredicates(instance.ingredients(), limits, inspected));
        }
        if (criterion.trigger() == CriteriaTriggers.RECIPE_UNLOCKED) {
            if (criterion.trigger().getClass() != RecipeUnlockedTrigger.class
                    || criterion.triggerInstance().getClass() != RecipeUnlockedTrigger.TriggerInstance.class)
                throw refused("Unrecognized native unlocked criterion implementation");
            var instance = (RecipeUnlockedTrigger.TriggerInstance) criterion.triggerInstance();
            if (instance.player().isPresent()) throw refused("Unlocked criterion player predicate is outside first-loop admission");
            return new CriterionView(Kind.UNLOCKED, instance.recipe(), List.of());
        }
        return null;
    }
    private static List<PredicateView> inspectPredicates(List<ItemPredicate> predicates, Limits limits, int[] inspected) {
        if (predicates.size() > limits.predicates - inspected[0]) throw refused("Native item predicate limit exceeded");
        var result = new ArrayList<PredicateView>(predicates.size());
        for (ItemPredicate predicate : predicates) {
            inspected[0]++;
            if (!predicate.components().alwaysMatches() || !predicate.subPredicates().isEmpty())
                throw refused("Native component/subpredicate behavior is outside first-loop admission");
            if (predicate.items().isEmpty()) { result.add(new PredicateView(true, Set.of())); continue; }
            HolderSet<Item> holders = predicate.items().get();
            if (holders.getClass() != HolderSet.Direct.class && holders.getClass() != HolderSet.Named.class)
                throw refused("Custom native item holder set is not admitted");
            if (holders.size() > limits.holderEntries - inspected[1]) throw refused("Native item holder-entry limit exceeded");
            var ids = new HashSet<ResourceLocation>();
            for (Holder<Item> holder : holders) {
                if (++inspected[1] > limits.holderEntries || holder.getClass() != Holder.Reference.class)
                    throw refused("Unrecognized or excessive native item holder entries");
                ResourceLocation id = holder.unwrapKey().orElseThrow(() -> refused("Unkeyed native item holder")).location();
                if (BuiltInRegistries.ITEM.get(id) != holder.value()) throw refused("Native item holder differs from registry identity");
                ids.add(id);
            }
            if (holders instanceof HolderSet.Named<Item> named) {
                // Native Named.contains consults Holder.Reference.is(tag), not contents().
                // Prove the two representations agree for every item that this session can own.
                for (ResourceLocation id : ITEMS) {
                    Item item = BuiltInRegistries.ITEM.get(id);
                    if (!BuiltInRegistries.ITEM.getKey(item).equals(id)
                            || item.builtInRegistryHolder().is(named.key()) != ids.contains(id))
                        throw refused("Native item-tag membership disagrees with its named holder set");
                }
            }
            result.add(new PredicateView(false, Set.copyOf(ids)));
        }
        return List.copyOf(result);
    }
    private static void requireMetadataRecipe(RecipeHolder<?> holder) {
        Class<?> type = holder.value().getClass();
        // addRecipes invokes only isSpecial(): no Ingredient/matches/assemble call is admitted here.
        if (type != ShapedRecipe.class && type != ShapelessRecipe.class
                && type != StonecutterRecipe.class && type != SmeltingRecipe.class)
            throw refused("Native metadata recipe implementation is outside the audited profile: " + holder.id());
        try {
            if (type.getMethod("isSpecial").getDeclaringClass() != Recipe.class)
                throw refused("Native reward recipe overrides isSpecial");
        } catch (ReflectiveOperationException failure) {
            throw refused("Cannot verify native reward recipe metadata method", failure);
        }
    }
    private void requireActualTriggerListeners() {
        int totalListeners = 0;
        for (SimpleCriterionTrigger<?> trigger : List.of(CriteriaTriggers.INVENTORY_CHANGED,
                CriteriaTriggers.RECIPE_CRAFTED, CriteriaTriggers.RECIPE_UNLOCKED)) {
            try {
                Field field = SimpleCriterionTrigger.class.getDeclaredField("players");
                if (field.getType() != Map.class || field.getModifiers() != (Modifier.PRIVATE | Modifier.FINAL)
                        || !field.trySetAccessible()) throw refused("Native criterion listener inspection unavailable");
                Object object = field.get(trigger);
                if (!(object instanceof Map<?, ?> map)) throw refused("Native criterion listener map unavailable");
                Object registered = map.get(progress);
                if (registered == null) continue;
                if (!(registered instanceof Set<?> listeners) || listeners.size() > limits.criteria - totalListeners)
                    throw refused("Native criterion listener bound exceeded");
                totalListeners += listeners.size();
                for (Object raw : listeners) {
                    if (!(raw instanceof CriterionTrigger.Listener<?> listener)) throw refused("Custom criterion listener is not admitted");
                    Node node = captured.nodes.get(listener.advancement().id());
                    if (node == null || node.holder != listener.advancement()) throw refused("Unloaded advancement listener is not admitted");
                    Criterion<?> criterion = node.holder.value().criteria().get(listener.criterion());
                    if (criterion == null || criterion.trigger() != trigger || criterion.triggerInstance() != listener.trigger())
                        throw refused("Native criterion listener differs from exact loaded criterion");
                }
            } catch (ReflectiveOperationException | SecurityException failure) {
                throw refused("Cannot verify native criterion listener registrations", failure);
            }
        }
    }
    private static Set<ResourceLocation> ids(String... names) {
        var result = new HashSet<ResourceLocation>();
        for (String name : names) result.add(ResourceLocation.withDefaultNamespace(name));
        return Set.copyOf(result);
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
    private static SurvivalRefusal refused(String message, Throwable cause) {
        SurvivalRefusal refusal = refused(message); refusal.initCause(cause); return refusal;
    }
}
