package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/** Complete native2x2 before/after data. Hydration is confined to the private detached scope. */
public final class NativeInventoryMenuSnapshot {
    public record Data(List<NativeStackSnapshot> inventory, int selected, int inventoryChanges,
            List<NativeStackSnapshot> grid, NativeStackSnapshot result, NativeStackSnapshot carried,
            ResourceLocation recipeUsed, int removeCount, int stateId, List<NativeStackSnapshot> lastSlots,
            List<NativeStackSnapshot> remoteSlots, NativeStackSnapshot remoteCarried,
            boolean suppressRemoteUpdates, int quickcraftType) {
        public Data {
            inventory = List.copyOf(inventory); grid = List.copyOf(grid);
            lastSlots = List.copyOf(lastSlots); remoteSlots = List.copyOf(remoteSlots);
            if (inventory.size() != 41 || grid.size() != 4 || lastSlots.size() != 46 || remoteSlots.size() != 46
                    || selected < 0 || selected > 8 || removeCount != 0 || stateId < 0 || stateId > 32767)
                throw new IllegalArgumentException("Complete idle native2x2 menu data required");
        }
    }
    /** Shared retained diagnostic payload bound, charged before appending each immutable image. */
    static final class Budget {
        private final int limit;
        private int used;
        Budget(int limit) { if (limit < 1) throw new IllegalArgumentException("Positive native snapshot budget required"); this.limit = limit; }
        void charge(int bytes) {
            if (bytes < 0 || bytes > limit - used) throw refused("Cumulative native menu snapshot payload bound exceeded");
            used += bytes;
        }
    }
    private final Data data;
    private final RecipeHolder<?> recipe;
    private final InventoryMenu menu;
    private final Inventory inventory;
    private final Object[] listeners;
    private final Object synchronizer;
    private NativeInventoryMenuSnapshot(Data data, RecipeHolder<?> recipe, InventoryMenu menu,
            Inventory inventory, Object[] listeners, Object synchronizer) {
        this.data = data; this.recipe = recipe; this.menu = menu; this.inventory = inventory;
        this.listeners = listeners; this.synchronizer = synchronizer;
    }
    public Data data() { return data; }
    /** Read-only native reference observation; this grants no hydration or detached authority. */
    public static Data captureLive(ServerPlayer player) {
        if (player.containerMenu != player.inventoryMenu) throw refused("The actual native2x2 menu is not active");
        return capture(player, player.inventoryMenu, player.getInventory(), new Budget(4*1024*1024)).data;
    }
    static NativeInventoryMenuSnapshot capture(ServerPlayer player, InventoryMenu menu, Inventory inventory, Budget budget) {
        budget.charge(512);
        requireShape(player, menu, inventory);
        NativeInventoryMenuPreparation.requireSnapshotSource(player, menu, inventory);
        var result = (ResultContainer) menu.getSlot(0).container;
        RecipeHolder<?> recipe = result.getRecipeUsed();
        var last = stacks(read(AbstractContainerMenu.class, menu, "lastSlots", NonNullList.class), 46, player, budget);
        var remote = stacks(read(AbstractContainerMenu.class, menu, "remoteSlots", NonNullList.class), 46, player, budget);
        var listeners = read(AbstractContainerMenu.class, menu, "containerListeners", List.class);
        if (listeners.size() > 1) throw refused("Additional native menu observers are not admitted");
        var data = new Data(stacks(inventory.items, 36, player, budget, inventory.armor, inventory.offhand),
                inventory.selected, inventory.getTimesChanged(), stacks(menu.getCraftSlots().getItems(), 4, player, budget),
                image(result.getItem(0), player, budget), image(menu.getCarried(), player, budget), recipe == null ? null : recipe.id(),
                read(ResultSlot.class, menu.getSlot(0), "removeCount", int.class), menu.getStateId(), last, remote,
                image(read(AbstractContainerMenu.class, menu, "remoteCarried", ItemStack.class), player, budget),
                read(AbstractContainerMenu.class, menu, "suppressRemoteUpdates", boolean.class),
                read(AbstractContainerMenu.class, menu, "quickcraftType", int.class));
        return new NativeInventoryMenuSnapshot(data, recipe, menu, inventory, listeners.toArray(),
                readObject(AbstractContainerMenu.class, menu, "synchronizer"));
    }
    /** A retained before-image cannot authorize a different live menu/observer incarnation. */
    void requireUnchanged(ServerPlayer player) {
        if (player.inventoryMenu != menu || player.containerMenu != menu || player.getInventory() != inventory)
            throw refused("Live native inventory/menu identity changed during detached preparation");
        NativeInventoryMenuSnapshot now = capture(player, menu, inventory, new Budget(4*1024*1024));
        if (!data.equals(now.data) || recipe != now.recipe || synchronizer != now.synchronizer
                || listeners.length != now.listeners.length) throw refused("Live native menu before-image changed");
        for (int i = 0; i < listeners.length; i++) if (listeners[i] != now.listeners[i])
            throw refused("Live native menu listener identity changed");
    }
    void hydrateDetached(ServerPlayer player, InventoryMenu target, Inventory targetInventory) {
        NativeInventoryMenuPreparation.requireHydration(player, target, targetInventory);
        if (target == menu || target == player.inventoryMenu || targetInventory == inventory || targetInventory == player.getInventory())
            throw refused("Native snapshot hydration cannot target live inventory/menu state");
        for (int i = 0; i < 41; i++) targetInventory.setItem(i, data.inventory.get(i).materialize(player.registryAccess()));
        targetInventory.selected = data.selected;
        write(Inventory.class, targetInventory, "timesChanged", data.inventoryChanges);
        var grid = read(TransientCraftingContainer.class, target.getCraftSlots(), "items", NonNullList.class);
        replace(grid, data.grid, player);
        var result = (ResultContainer) target.getSlot(0).container;
        result.setItem(0, data.result.materialize(player.registryAccess())); result.setRecipeUsed(recipe);
        target.setCarried(data.carried.materialize(player.registryAccess()));
        replace(read(AbstractContainerMenu.class, target, "lastSlots", NonNullList.class), data.lastSlots, player);
        replace(read(AbstractContainerMenu.class, target, "remoteSlots", NonNullList.class), data.remoteSlots, player);
        target.setRemoteCarried(data.remoteCarried.materialize(player.registryAccess()));
        write(AbstractContainerMenu.class, target, "stateId", data.stateId);
        write(AbstractContainerMenu.class, target, "suppressRemoteUpdates", data.suppressRemoteUpdates);
        write(AbstractContainerMenu.class, target, "quickcraftType", data.quickcraftType);
        write(ResultSlot.class, target.getSlot(0), "removeCount", data.removeCount);
        if (!read(AbstractContainerMenu.class, target, "containerListeners", List.class).isEmpty()
                || readObject(AbstractContainerMenu.class, target, "synchronizer") != null)
            throw refused("Detached native menu unexpectedly owns live observers");
    }
    static ResultContainer result(InventoryMenu menu) { return (ResultContainer) menu.getSlot(0).container; }
    static void requireShape(ServerPlayer player, InventoryMenu menu, Inventory inventory) {
        if (!player.server.isSameThread() || menu.getClass() != InventoryMenu.class || !menu.active
                || inventory.getClass() != Inventory.class || inventory.player != player || inventory.getContainerSize() != 41
                || menu.slots.size() != 46 || menu.containerId != 0 || menu.getGridWidth() != 2 || menu.getGridHeight() != 2
                || menu.getCraftSlots().getClass() != TransientCraftingContainer.class
                || menu.getSlot(0).getClass() != ResultSlot.class || menu.getSlot(0).container.getClass() != ResultContainer.class)
            throw refused("Exact real-player native2x2 menu shape required");
        if (!read(AbstractContainerMenu.class, menu, "dataSlots", List.class).isEmpty()
                || !read(AbstractContainerMenu.class, menu, "remoteDataSlots", it.unimi.dsi.fastutil.ints.IntList.class).isEmpty()
                || read(AbstractContainerMenu.class, menu, "quickcraftStatus", int.class) != 0
                || !read(AbstractContainerMenu.class, menu, "quickcraftSlots", java.util.Set.class).isEmpty())
            throw refused("Native menu data/drag state is outside the2x2 click profile");
        for (int slot = 1; slot <= 4; slot++) if (menu.getSlot(slot).container != menu.getCraftSlots())
            throw refused("Native crafting grid backing identity changed");
        for (int slot = 5; slot < 46; slot++) if (menu.getSlot(slot).container != inventory)
            throw refused("Native inventory slot backing identity changed");
    }
    private static List<NativeStackSnapshot> stacks(List<?> values, int expected, ServerPlayer player, Budget budget, List<?>... tail) {
        if (values.size() != expected) throw refused("Native menu vector shape changed");
        var result = new ArrayList<NativeStackSnapshot>();
        for (Object value : values) {
            if (!(value instanceof ItemStack stack)) throw refused("Invalid native stack vector");
            result.add(image(stack, player, budget));
        }
        for (List<?> next : tail) for (Object value : next) {
            if (!(value instanceof ItemStack stack)) throw refused("Invalid native inventory tail");
            result.add(image(stack, player, budget));
        }
        return List.copyOf(result);
    }
    private static NativeStackSnapshot image(ItemStack stack, ServerPlayer player, Budget budget) {
        NativeStackSnapshot image = NativeStackSnapshot.capture(stack, player.registryAccess());
        budget.charge(Math.addExact(64, image.encodedBytes()));
        return image;
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static void replace(List target, List<NativeStackSnapshot> images, ServerPlayer player) {
        if (target.size() != images.size()) throw refused("Detached native vector shape differs from capture");
        for (int i = 0; i < images.size(); i++) target.set(i, images.get(i).materialize(player.registryAccess()));
    }
    @SuppressWarnings("unchecked") static <T> T read(Class<?> owner, Object target, String name, Class<T> type) {
        Field field = field(owner, name);
        if (field.getType() != type) throw refused("Pinned native menu field descriptor changed: " + name);
        try { return (T) field.get(target); }
        catch (IllegalAccessException failure) { throw refused("Native menu field read failed", failure); }
    }
    private static Object readObject(Class<?> owner, Object target, String name) {
        try { return field(owner, name).get(target); }
        catch (IllegalAccessException failure) { throw refused("Native menu observer read failed", failure); }
    }
    private static void write(Class<?> owner, Object target, String name, Object value) {
        try { field(owner, name).set(target, value); }
        catch (IllegalAccessException failure) { throw refused("Detached native menu hydration failed", failure); }
    }
    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            if (!field.trySetAccessible()) throw refused("Pinned native menu field is unavailable: " + name);
            return field;
        } catch (ReflectiveOperationException | SecurityException failure) { throw refused("Pinned native menu inspection failed", failure); }
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
    private static SurvivalRefusal refused(String message, Throwable cause) { var failure = refused(message); failure.initCause(cause); return failure; }
}
