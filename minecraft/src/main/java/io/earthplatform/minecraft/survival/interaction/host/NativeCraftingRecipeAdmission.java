package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureElement;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.common.crafting.CompoundIngredient;
import net.neoforged.neoforge.common.crafting.DifferenceIngredient;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.extensions.IItemExtension;

/**
 * Pre-admission for the actual pinned native crafting search, not a replacement matcher.
 * Every candidate executes its original native matches method. Selected preview assembly is
 * limited to exact native shaped/shapeless output copying; actual taking is restricted separately
 * to the five first-loop recipes. The fixed input universe excludes filled maps, so the native
 * map-extending matcher cannot reach saved-map reads. Repair matching may succeed, but its
 * selected custom result is refused before assembly or any result-slot removal.
 */
public final class NativeCraftingRecipeAdmission {
    private static final Set<Class<?>> SPECIAL = Set.of(ArmorDyeRecipe.class, DecoratedPotRecipe.class,
            FireworkRocketRecipe.class, FireworkStarFadeRecipe.class, FireworkStarRecipe.class,
            MapCloningRecipe.class, MapExtendingRecipe.class, ShieldDecorationRecipe.class,
            SuspiciousStewRecipe.class, TippedArrowRecipe.class, BannerDuplicateRecipe.class,
            BookCloningRecipe.class, RepairItemRecipe.class, ShulkerBoxColoring.class);
    public record Limits(int recipes, int ingredientNodes, int depth, int alternatives, int snapshotBytes, int callbackVisits) {
        public Limits {
            if (recipes < 1 || ingredientNodes < 1 || depth < 1 || depth > 32 || alternatives < 1 || snapshotBytes < 1 || callbackVisits < 1 || callbackVisits > 1048576)
                throw new IllegalArgumentException("Explicit bounded native recipe search required");
        }
    }
    private record IngredientImage(Object customImplementation, List<Ingredient> children, List<String> tagMembers,
                                   List<NativeStackSnapshot> sourceStacks, List<NativeStackSnapshot> values, List<Integer> stackingIds, int traversalCost) {}
    private record RecipeImage(RecipeHolder<CraftingRecipe> holder, List<Ingredient> ingredients,
                               int width, int height, Object pattern, int ingredientCount, boolean matchFlag, NativeStackSnapshot preview) {}
    private record Image(List<RecipeImage> recipes, Map<Ingredient, IngredientImage> ingredients,
                         List<Ingredient> staticIngredients, int bytes) {}
    private final ServerPlayer player;
    private final RecipeManager manager;
    private final NativeFirstLoopMetadata metadata;
    private final Limits limits;
    private final Image before;

    private NativeCraftingRecipeAdmission(ServerPlayer player, NativeFirstLoopMetadata metadata, Limits limits) {
        this.player = player; this.manager = player.server.getRecipeManager(); this.metadata = metadata; this.limits = limits;
        metadata.requirePlayerIdentity(player); metadata.revalidate(); requireOwnedItemCallbacks(); before = inspect();
    }
    public static NativeCraftingRecipeAdmission capture(ServerPlayer player, NativeFirstLoopMetadata metadata, Limits limits) {
        return new NativeCraftingRecipeAdmission(player, metadata, limits);
    }
    void requirePlayerIdentity(ServerPlayer expected) {
        if (expected != player) throw refused("Native crafting capability belongs to another player");
        metadata.requirePlayerIdentity(expected);
    }
    public int recipeCount() { return before.recipes.size(); }
    public void revalidate() {
        metadata.revalidate();
        if (manager != player.server.getRecipeManager()) throw refused("Native crafting manager generation changed");
        Image now = inspect();
        if (before.recipes.size() != now.recipes.size() || before.ingredients.size() != now.ingredients.size()
                || before.bytes != now.bytes || !sameIdentities(before.staticIngredients, now.staticIngredients))
            throw refused("Native crafting search profile changed");
        for (int i = 0; i < before.recipes.size(); i++) {
            RecipeImage a = before.recipes.get(i), b = now.recipes.get(i);
            if (a.holder != b.holder || a.width != b.width || a.height != b.height || a.pattern != b.pattern
                    || a.ingredientCount != b.ingredientCount || a.matchFlag != b.matchFlag
                    || !sameIdentities(a.ingredients, b.ingredients) || !a.preview.equals(b.preview))
                throw refused("Native crafting candidate identity/data changed");
        }
        for (var entry : before.ingredients.entrySet()) {
            IngredientImage b = now.ingredients.get(entry.getKey()), a = entry.getValue();
            if (b == null || a.customImplementation != b.customImplementation || !sameIdentities(a.children, b.children) || !a.tagMembers.equals(b.tagMembers)
                    || a.traversalCost != b.traversalCost || !a.sourceStacks.equals(b.sourceStacks) || !a.values.equals(b.values) || !a.stackingIds.equals(b.stackingIds))
                throw refused("Native ingredient children/tag/cache generation changed");
        }
    }
    public void requireInput(List<ItemStack> items) { metadata.requireItems(items); }
    public void requirePreview(RecipeHolder<?> selected) {
        Class<?> type = selected.value().getClass();
        if ((type != ShapedRecipe.class && type != ShapelessRecipe.class)
                || manager.byKey(selected.id()).orElse(null) != selected)
            throw refused("Selected native special/custom preview assembly is not admitted: " + selected.id());
    }
    public void requireTake(RecipeHolder<?> selected) {
        metadata.requireCraftedRecipe(selected); requirePreview(selected);
    }
    public void requirePreviewStack(ItemStack stack) {
        requireFeatureMethods(stack);
        NativeStackSnapshot.capture(stack, player.registryAccess());
    }
    private Image inspect() {
        if (!player.server.isSameThread()) throw refused("Native recipe inspection requires the server thread");
        var candidates = manager.getAllRecipesFor(RecipeType.CRAFTING);
        if (candidates.size() > limits.recipes) throw refused("Native crafting candidate bound exceeded");
        var state = new Inspection(); var recipes = new ArrayList<RecipeImage>();
        for (RecipeHolder<CraftingRecipe> holder : candidates) {
            CraftingRecipe value = holder.value(); Class<?> type = value.getClass();
            if (type != ShapedRecipe.class && type != ShapelessRecipe.class && !SPECIAL.contains(type))
                throw refused("Custom native crafting matcher is not admitted: " + holder.id());
            List<Ingredient> ingredients = List.of(); int width = 0, height = 0, ingredientCount = 0;
            Object pattern = null; boolean matchFlag = false;
            NativeStackSnapshot preview = NativeStackSnapshot.capture(ItemStack.EMPTY, player.registryAccess());
            if (value instanceof ShapedRecipe shaped) {
                width = shaped.getWidth(); height = shaped.getHeight();
                if (width < 1 || height < 1 || width > 3 || height > 3) throw refused("Native shaped dimensions exceed first-loop search");
                requireNativeList(shaped.getIngredients());
                ingredients = List.copyOf(shaped.getIngredients()); pattern = shaped.pattern;
                if (ingredients.size() != width*height) throw refused("Native shaped ingredient vector differs from dimensions");
            } else if (type == ShapelessRecipe.class) {
                requireNativeList(value.getIngredients()); ingredients = List.copyOf(value.getIngredients());
            }
            if (ingredients.size() > 9) throw refused("Native ingredient grid bound exceeded");
            for (int index = 0; index < ingredients.size(); index++) {
                Ingredient ingredient = ingredients.get(index);
                try {
                    state.visit(ingredient, 0);
                    state.chargeMatcherVisits(ingredient, 2); // Both native shaped orientations.
                } catch (SurvivalRefusal failure) {
                    throw refused("Native ingredient admission for recipe " + holder.id() + " at index " + index + ": " + failure.getMessage(), failure);
                }
            }
            // Match caches must describe the same admitted native input graph, not stale data.
            if (pattern != null) {
                ingredientCount = NativeInventoryMenuSnapshot.read(ShapedRecipePattern.class, pattern, "ingredientCount", int.class);
                matchFlag = NativeInventoryMenuSnapshot.read(ShapedRecipePattern.class, pattern, "symmetrical", boolean.class);
                int actualCount = 0; for (Ingredient ingredient : ingredients) if (!ingredient.isEmpty()) actualCount++;
                if (ingredientCount != actualCount || matchFlag != net.minecraft.Util.isSymmetrical(width, height, ingredients))
                    throw refused("Native shaped match cache differs from its admitted ingredient graph");
            } else if (type == ShapelessRecipe.class) {
                ingredientCount = ingredients.size();
                matchFlag = NativeInventoryMenuSnapshot.read(ShapelessRecipe.class, value, "isSimple", boolean.class);
                if (!matchFlag || ingredients.stream().anyMatch(ingredient -> !ingredient.isSimple()))
                    throw refused("Native shapeless simple cache differs from its admitted ingredient graph");
            }
            if (type == ShapedRecipe.class || type == ShapelessRecipe.class) {
                ItemStack result = value.getResultItem(player.registryAccess());
                requireFeatureMethods(result); preview = state.stack(result);
            }
            recipes.add(new RecipeImage(holder, ingredients, width, height, pattern, ingredientCount, matchFlag, preview));
        }
        var staticIngredients = new ArrayList<Ingredient>();
        for (Class<?> type : SPECIAL.stream().sorted(java.util.Comparator.comparing(Class::getName)).toList()) {
            for (Field field : java.util.Arrays.stream(type.getDeclaredFields()).sorted(java.util.Comparator.comparing(Field::getName)).toList()) {
                if (field.getType() != Ingredient.class) continue;
                if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers()))
                    throw refused("Mutable special-recipe Ingredient field is not admitted");
                try {
                    if (!field.trySetAccessible()) throw refused("Special native Ingredient inspection unavailable");
                    Ingredient ingredient = (Ingredient) field.get(null);
                    staticIngredients.add(ingredient); state.visit(ingredient, 0);
                    state.chargeMatcherVisits(ingredient, 9); // Every possible grid slot for native special matchers.
                } catch (ReflectiveOperationException error) { throw refused("Cannot inspect native special Ingredient", error); }
            }
        }
        return new Image(List.copyOf(recipes), Collections.unmodifiableMap(state.done), List.copyOf(staticIngredients), state.bytes);
    }
    private final class Inspection {
        final Map<Ingredient, IngredientImage> done = new IdentityHashMap<>();
        final Set<Ingredient> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        int nodes, expanded, bytes, matcherVisits;
        void visit(Ingredient ingredient, int depth) {
            if (depth > limits.depth || ingredient == null || ingredient.getClass() != Ingredient.class)
                throw refused("Native Ingredient depth/type is outside admission");
            if (done.containsKey(ingredient)) return;
            if (!visiting.add(ingredient)) throw refused("Cyclic native compound Ingredient is not admitted");
            if (++nodes > limits.ingredientNodes) throw refused("Native Ingredient node bound exceeded");
            List<Ingredient> children = List.of();
            int nativeValueCount = 0;
            var tagMembers = new ArrayList<String>();
            var sourceStacks = new ArrayList<NativeStackSnapshot>();
            var custom = ingredient.getCustomIngredient();
            if (custom != null) {
                children = nativeCustomChildren(custom, limits.ingredientNodes - nodes);
                if (children.size() > limits.ingredientNodes - nodes) throw refused("Native custom Ingredient child bound exceeded");
                for (Ingredient child : children) visit(child, depth + 1);
                if (custom.getClass() == DifferenceIngredient.class) {
                    int alternatives = done.get(children.get(0)).values.size();
                    admitAlternatives(alternatives);
                    // Native Difference.getItems filters every base alternative through the
                    // complete subtracted predicate. Admit that work before native resolution.
                    chargeVisits(boundedFilterCost(alternatives, done.get(children.get(1)).traversalCost, limits.callbackVisits));
                } else {
                    int count = 0;
                    for (Ingredient child : children) count = Math.addExact(count, done.get(child).values.size());
                    admitAlternatives(count);
                }
            } else {
                var values = ingredient.getValues(); nativeValueCount = values.length;
                if (values.length > limits.ingredientNodes - nodes) throw refused("Native Ingredient value bound exceeded");
                for (Ingredient.Value value : values) {
                    if (value.getClass() == Ingredient.ItemValue.class) {
                        requireFeatureMethods(((Ingredient.ItemValue) value).item());
                        sourceStacks.add(stack(((Ingredient.ItemValue) value).item())); admitAlternatives(1);
                    } else if (value.getClass() == Ingredient.TagValue.class) {
                        var tag = ((Ingredient.TagValue) value).tag();
                        tagMembers.add("tag:" + tag.location());
                        int count = 0;
                        for (Holder<Item> member : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
                            if (++count > limits.alternatives || member.getClass() != Holder.Reference.class)
                                throw refused("Native Ingredient tag bound/type exceeded");
                            tagMembers.add(member.unwrapKey().orElseThrow(() -> refused("Unkeyed native ingredient holder")).location().toString());
                        }
                        tagMembers.add("end");
                        admitAlternatives(Math.max(1, count));
                    } else throw refused("Custom native Ingredient.Value callback is not admitted");
                }
            }
            int traversalCost = boundedTraversalCost(children.stream().map(child -> done.get(child).traversalCost).toList(), limits.callbackVisits);
            // Every callback in this complete subtree and its expanded recursive cost was
            // admitted before resolving native caches or invoking native recursive isSimple.
            // Repeated DAG children are charged repeatedly even when item alternatives deduplicate.
            ItemStack[] items = ingredient.getItems();
            if (items.length > limits.alternatives) throw refused("Native Ingredient cache bound exceeded");
            if (custom == null) traversalCost = boundedLeafCost(items.length, nativeValueCount, limits.callbackVisits);
            var stacks = new ArrayList<NativeStackSnapshot>();
            for (ItemStack item : items) stacks.add(stack(item));
            var ids = ingredient.getStackingIds();
            if (ids.getClass() != it.unimi.dsi.fastutil.ints.IntArrayList.class || ids.size() != items.length)
                throw refused("Native Ingredient stacking cache shape/type changed");
            int[] expectedIds = java.util.Arrays.stream(items).mapToInt(StackedContents::getStackingIndex).sorted().toArray();
            var stackingIds = new ArrayList<Integer>(expectedIds.length);
            for (int i = 0; i < expectedIds.length; i++) {
                if (ids.getInt(i) != expectedIds[i]) throw refused("Native Ingredient stacking cache differs from resolved native alternatives");
                stackingIds.add(expectedIds[i]);
            }
            done.put(ingredient, new IngredientImage(custom, children, List.copyOf(tagMembers), List.copyOf(sourceStacks),
                    List.copyOf(stacks), List.copyOf(stackingIds), traversalCost)); visiting.remove(ingredient);
        }
        void chargeMatcherVisits(Ingredient ingredient, int repeats) {
            chargeVisits((long) done.get(ingredient).traversalCost * repeats);
        }
        void chargeVisits(long cost) {
            if (cost < 0 || cost > limits.callbackVisits - matcherVisits)
                throw refused("Native aggregate crafting matcher traversal bound exceeded");
            matcherVisits += (int) cost;
        }
        void admitAlternatives(int count) {
            if (count > limits.alternatives - expanded) throw refused("Native expanded Ingredient bound exceeded");
            expanded += count;
        }
        NativeStackSnapshot stack(ItemStack stack) {
            NativeStackSnapshot image = NativeStackSnapshot.capture(stack, player.registryAccess());
            if (image.encodedBytes() > limits.snapshotBytes - bytes) throw refused("Native recipe snapshot byte bound exceeded");
            bytes += image.encodedBytes(); return image;
        }
    }
    /** Class/child inspection only. Never call an unadmitted custom implementation to inspect it. */
    static List<Ingredient> nativeCustomChildren(ICustomIngredient custom, int maxChildren) {
        if (custom.getClass() == CompoundIngredient.class) {
            var children = ((CompoundIngredient) custom).children(); requireTrustedList(children);
            if (children.isEmpty() || children.size() > maxChildren) throw refused("Native compound Ingredient child bound exceeded");
            for (Ingredient child : children) if (child == null) throw refused("Null native compound Ingredient child");
            return List.copyOf(children);
        }
        if (custom.getClass() == DifferenceIngredient.class) {
            if (maxChildren < 2) throw refused("Native difference Ingredient child bound exceeded");
            var difference = (DifferenceIngredient) custom;
            if (difference.base() == null || difference.subtracted() == null) throw refused("Null native difference Ingredient child");
            return List.of(difference.base(), difference.subtracted());
        }
        throw refused("Custom Ingredient callbacks are not admitted: " + custom.getClass().getName());
    }
    static int boundedFilterCost(int alternatives, int predicateCost, int limit) {
        if (alternatives < 0 || predicateCost < 1 || limit < 1) throw refused("Invalid native difference filter bounds");
        long cost = (long) alternatives * ((long) predicateCost + 1); // Predicate.negate wrapper plus its native test.

        if (cost > limit) throw refused("Native DifferenceIngredient filter traversal bound exceeded");
        return (int) cost;
    }
    static int boundedLeafCost(int resolvedAlternatives, int nativeValues, int limit) {
        if (resolvedAlternatives < 0 || nativeValues < 0 || limit < 1) throw refused("Invalid native leaf work bounds");
        int work = Math.max(resolvedAlternatives, nativeValues);
        if (work >= limit) throw refused("Native Ingredient leaf matching/equality work bound exceeded");
        return 1 + work; // Native test scans alternatives; native equals can scan the raw Value array.
    }
    static int boundedTraversalCost(List<Integer> childCosts, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Positive native callback traversal bound required");
        int result = 1;
        for (int child : childCosts) {
            if (child < 1 || child > limit - result)
                throw refused("Native recursive CompoundIngredient traversal bound exceeded");
            result += child;
        }
        return result;
    }
    private static void requireNativeList(NonNullList<?> list) {
        if (list.getClass() != NonNullList.class) throw refused("Custom native ingredient list is not admitted");
        requireTrustedList(NativeInventoryMenuSnapshot.read(NonNullList.class, list, "list", List.class));
    }
    static void requireTrustedList(List<?> list) {
        String name = list.getClass().getName();
        if (!(name.startsWith("java.util.ImmutableCollections$") || name.equals("java.util.ArrayList")
                || name.equals("java.util.Arrays$ArrayList") || name.equals("com.google.common.collect.RegularImmutableList")))
            throw refused("Custom compound child collection is not admitted");
    }
    private static void requireFeatureMethods(ItemStack stack) {
        if (stack.isEmpty()) return;
        Item item = stack.getItem(); Class<?> type = item.getClass();
        requireDeclaring(type, "isEnabled", FeatureElement.class, FeatureFlagSet.class);
        if (item instanceof BlockItem blockItem) {
            requireDeclaring(type, "requiredFeatures", BlockItem.class);
            requireDeclaring(type, "getBlock", BlockItem.class);
            requireDeclaring(blockItem.getBlock().getClass(), "requiredFeatures", BlockBehaviour.class);
        } else requireDeclaring(type, "requiredFeatures", Item.class);
    }
    static void requireOwnedItemCallbacks() {
        for (var id : NativeFirstLoopMetadata.itemUniverse()) {
            Item item = BuiltInRegistries.ITEM.get(id); Class<?> type = item.getClass();
            PinnedNativeEventAdmission.requireOrdinaryItemType(item);
            requireDeclaring(type, "overrideStackedOnOther", Item.class, ItemStack.class, Slot.class, ClickAction.class, Player.class);
            requireDeclaring(type, "overrideOtherStackedOnMe", Item.class, ItemStack.class, ItemStack.class, Slot.class, ClickAction.class, Player.class, SlotAccess.class);
            requireDeclaring(type, "onCraftedBy", Item.class, ItemStack.class, Level.class, Player.class);
            requireDeclaring(type, "onCraftedPostProcess", Item.class, ItemStack.class, Level.class);
            requireDeclaring(type, "isRepairable", Item.class, ItemStack.class);
            requireDeclaring(type, "isDamageable", IItemExtension.class, ItemStack.class);
            requireDeclaring(type, "hasCraftingRemainingItem", IItemExtension.class, ItemStack.class);
            requireDeclaring(type, "getCraftingRemainingItem", IItemExtension.class, ItemStack.class);
            requireDeclaring(type, "hasCraftingRemainingItem", Item.class);
            if (item.getCraftingRemainingItem() != null) throw refused("First-loop native item has an unexpected crafting remainder");
        }
    }
    private static void requireDeclaring(Class<?> type, String name, Class<?> expected, Class<?>... parameters) {
        try { if (type.getMethod(name, parameters).getDeclaringClass() != expected) throw refused("Native crafting callback override: " + type + "." + name); }
        catch (ReflectiveOperationException failure) { throw refused("Native crafting callback inspection unavailable", failure); }
    }
    private static boolean sameIdentities(List<?> a, List<?> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
    private static SurvivalRefusal refused(String message, Throwable cause) { var failure = refused(message); failure.initCause(cause); return failure; }
}
