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
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/** Complete native3x3 before/after data. Hydration is confined to the private detached scope. */
public final class NativeCraftingTableSnapshot {
    public record Data(int containerId, List<NativeStackSnapshot> inventory, int selected, int inventoryChanges,
            List<NativeStackSnapshot> grid, NativeStackSnapshot result, NativeStackSnapshot carried,
            ResourceLocation recipeUsed, int removeCount, int stateId, List<NativeStackSnapshot> lastSlots,
            List<NativeStackSnapshot> remoteSlots, NativeStackSnapshot remoteCarried,
            boolean suppressRemoteUpdates, int quickcraftType) {
        public Data {
            inventory = List.copyOf(inventory); grid = List.copyOf(grid);
            lastSlots = List.copyOf(lastSlots); remoteSlots = List.copyOf(remoteSlots);
            if (containerId < 1 || containerId > 100 || inventory.size() != 41 || grid.size() != 9 || lastSlots.size() != 46 || remoteSlots.size() != 46
                    || selected < 0 || selected > 8 || removeCount != 0 || stateId < 0 || stateId > 32767 || quickcraftType < -1 || quickcraftType > 2)
                throw new IllegalArgumentException("Complete idle native3x3 menu data required");
        }
    }
    private final Data data;
    private final RecipeHolder<?> recipe;
    private final CraftingMenu menu;
    private final Inventory inventory;
    private final Object[] listeners;
    private final Object synchronizer;
    private NativeCraftingTableSnapshot(Data data, RecipeHolder<?> recipe, CraftingMenu menu,
            Inventory inventory, Object[] listeners, Object synchronizer) {
        this.data = data; this.recipe = recipe; this.menu = menu; this.inventory = inventory;
        this.listeners = listeners; this.synchronizer = synchronizer;
    }
    public Data data() { return data; }
    /** Read-only native reference observation; this grants no hydration or detached authority. */
    public static Data captureLive(ServerPlayer player) {
        if (!(player.containerMenu instanceof CraftingMenu menu)) throw refused("The actual native3x3 menu is not active");
        return capture(player, menu, player.getInventory(), new NativeInventoryMenuSnapshot.Budget(4*1024*1024)).data;
    }
    static NativeCraftingTableSnapshot capture(ServerPlayer player, CraftingMenu menu, Inventory inventory, NativeInventoryMenuSnapshot.Budget budget) {
        budget.charge(512);
        requireShape(player, menu, inventory);
        NativeCraftingTablePreparation.requireSnapshotSource(player, menu, inventory);
        var result = (ResultContainer) menu.getSlot(0).container;
        RecipeHolder<?> recipe = result.getRecipeUsed();
        var last = stacks(read(AbstractContainerMenu.class, menu, "lastSlots", NonNullList.class), 46, player, budget);
        var remote = stacks(read(AbstractContainerMenu.class, menu, "remoteSlots", NonNullList.class), 46, player, budget);
        var listeners = read(AbstractContainerMenu.class, menu, "containerListeners", List.class);
        if (listeners.size() > 1) throw refused("Additional native menu observers are not admitted");
        var data = new Data(menu.containerId, stacks(inventory.items, 36, player, budget, inventory.armor, inventory.offhand),
                inventory.selected, inventory.getTimesChanged(), stacks(grid(menu).getItems(), 9, player, budget),
                image(result.getItem(0), player, budget), image(menu.getCarried(), player, budget), recipe == null ? null : recipe.id(),
                read(ResultSlot.class, menu.getSlot(0), "removeCount", int.class), menu.getStateId(), last, remote,
                image(read(AbstractContainerMenu.class, menu, "remoteCarried", ItemStack.class), player, budget),
                read(AbstractContainerMenu.class, menu, "suppressRemoteUpdates", boolean.class),
                read(AbstractContainerMenu.class, menu, "quickcraftType", int.class));
        return new NativeCraftingTableSnapshot(data, recipe, menu, inventory, listeners.toArray(),
                readObject(AbstractContainerMenu.class, menu, "synchronizer"));
    }
    /** A retained before-image cannot authorize a different live menu/observer incarnation. */
    void requireUnchanged(ServerPlayer player) {
        if (player.containerMenu != menu || player.getInventory() != inventory)
            throw refused("Live native inventory/menu identity changed during detached preparation");
        NativeCraftingTableSnapshot now = capture(player, menu, inventory, new NativeInventoryMenuSnapshot.Budget(4*1024*1024));
        if (!data.equals(now.data) || recipe != now.recipe || synchronizer != now.synchronizer
                || listeners.length != now.listeners.length) throw refused("Live native menu before-image changed");
        for (int i = 0; i < listeners.length; i++) if (listeners[i] != now.listeners[i])
            throw refused("Live native menu listener identity changed");
    }
    static TransientCraftingContainer grid(CraftingMenu menu) { return (TransientCraftingContainer) menu.getSlot(1).container; }
    static ResultContainer result(CraftingMenu menu) { return (ResultContainer) menu.getSlot(0).container; }
    static void requireShape(ServerPlayer player, CraftingMenu menu, Inventory inventory) {
        if (!player.server.isSameThread() || menu.getClass() != CraftingMenu.class
                || inventory.getClass() != Inventory.class || inventory.player != player || inventory.getContainerSize() != 41
                || menu.slots.size() != 46 || (menu.containerId < 1 || menu.containerId > 100) || menu.getGridWidth() != 3 || menu.getGridHeight() != 3
                || grid(menu).getClass() != TransientCraftingContainer.class
                || menu.getSlot(0).getClass() != ResultSlot.class || menu.getSlot(0).container.getClass() != ResultContainer.class)
            throw refused("Exact real-player native3x3 menu shape required");
        if (!read(AbstractContainerMenu.class, menu, "dataSlots", List.class).isEmpty()
                || !read(AbstractContainerMenu.class, menu, "remoteDataSlots", it.unimi.dsi.fastutil.ints.IntList.class).isEmpty()
                || read(AbstractContainerMenu.class, menu, "quickcraftStatus", int.class) != 0
                || !read(AbstractContainerMenu.class, menu, "quickcraftSlots", java.util.Set.class).isEmpty())
            throw refused("Native menu data/drag state is outside the3x3 click profile");
        for (int slot = 1; slot <= 9; slot++) if (menu.getSlot(slot).container != grid(menu))
            throw refused("Native crafting grid backing identity changed");
        for (int slot = 10; slot < 46; slot++) if (menu.getSlot(slot).container != inventory)
            throw refused("Native inventory slot backing identity changed");
    }
    private static List<NativeStackSnapshot> stacks(List<?> values, int expected, ServerPlayer player, NativeInventoryMenuSnapshot.Budget budget, List<?>... tail) {
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
    private static NativeStackSnapshot image(ItemStack stack, ServerPlayer player, NativeInventoryMenuSnapshot.Budget budget) {
        NativeStackSnapshot image = NativeStackSnapshot.capture(stack, player.registryAccess());
        budget.charge(Math.addExact(64, image.encodedBytes()));
        return image;
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
