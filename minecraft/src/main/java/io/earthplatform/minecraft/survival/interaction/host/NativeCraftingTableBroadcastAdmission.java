package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Exact pinned native observer graph for one final resolved-menu broadcast, not a general listener permit. */
final class NativeCraftingTableBroadcastAdmission {
    private final ServerPlayer player;
    private final CraftingMenu menu;
    private final Inventory inventory;
    private final Object listener, synchronizer, listenerList, dataSlots, remoteDataSlots, quickcraftSlots;
    private final Object lastSlots, remoteSlots;
    private final List<Slot> slots;
    private NativeCraftingTableBroadcastAdmission(ServerPlayer player, CraftingMenu exactMenu) {
        this.player = player; menu = exactMenu; inventory = player.getInventory();
        NativeCraftingTableSnapshot.requireShape(player, menu, inventory);
        listener = read(ServerPlayer.class, player, "containerListener", ContainerListener.class);
        synchronizer = read(ServerPlayer.class, player, "containerSynchronizer", ContainerSynchronizer.class);
        requireNativeObserver(listener, "net.minecraft.server.level.ServerPlayer$2", player);
        requireNativeObserver(synchronizer, "net.minecraft.server.level.ServerPlayer$1", player);
        listenerList = read(AbstractContainerMenu.class, menu, "containerListeners", List.class);
        dataSlots = read(AbstractContainerMenu.class, menu, "dataSlots", List.class);
        remoteDataSlots = read(AbstractContainerMenu.class, menu, "remoteDataSlots", it.unimi.dsi.fastutil.ints.IntList.class);
        quickcraftSlots = read(AbstractContainerMenu.class, menu, "quickcraftSlots", java.util.Set.class);
        lastSlots = read(AbstractContainerMenu.class, menu, "lastSlots", NonNullList.class);
        remoteSlots = read(AbstractContainerMenu.class, menu, "remoteSlots", NonNullList.class);
        if (listenerList.getClass() != ArrayList.class || dataSlots.getClass() != ArrayList.class
                || remoteDataSlots.getClass() != it.unimi.dsi.fastutil.ints.IntArrayList.class
                || quickcraftSlots.getClass() != java.util.HashSet.class || menu.slots.getClass() != NonNullList.class
                || lastSlots.getClass() != NonNullList.class || remoteSlots.getClass() != NonNullList.class)
            throw refused("Native menu observer collections are not the pinned implementations");
        var retained = new ArrayList<Slot>(46);
        for (int i = 0; i < 46; i++) { Slot slot = menu.getSlot(i); requireNativeSlot(slot, i); retained.add(slot); }
        slots = List.copyOf(retained);
        if (read(AbstractContainerMenu.class, menu, "synchronizer", ContainerSynchronizer.class) != synchronizer
                || ((List<?>) listenerList).size() != 1 || ((List<?>) listenerList).getFirst() != listener)
            throw refused("One exact initialized native listener/synchronizer is required");
        require();
    }
    static NativeCraftingTableBroadcastAdmission capture(ServerPlayer player, CraftingMenu menu) { return new NativeCraftingTableBroadcastAdmission(player,menu); }
    void require() {
        if (!player.server.isSameThread() || player.containerMenu != menu || player.getInventory() != inventory)
            throw refused("Native broadcast player/menu identity changed");
        NativeCraftingTableSnapshot.requireShape(player, menu, inventory);
        if (read(ServerPlayer.class, player, "containerListener", ContainerListener.class) != listener
                || read(ServerPlayer.class, player, "containerSynchronizer", ContainerSynchronizer.class) != synchronizer
                || read(AbstractContainerMenu.class, menu, "synchronizer", ContainerSynchronizer.class) != synchronizer
                || read(AbstractContainerMenu.class, menu, "containerListeners", List.class) != listenerList
                || read(AbstractContainerMenu.class, menu, "dataSlots", List.class) != dataSlots
                || read(AbstractContainerMenu.class, menu, "remoteDataSlots", it.unimi.dsi.fastutil.ints.IntList.class) != remoteDataSlots
                || read(AbstractContainerMenu.class, menu, "quickcraftSlots", java.util.Set.class) != quickcraftSlots
                || read(AbstractContainerMenu.class, menu, "lastSlots", NonNullList.class) != lastSlots
                || read(AbstractContainerMenu.class, menu, "remoteSlots", NonNullList.class) != remoteSlots)
            throw refused("Native broadcast observer identity changed");
        List<?> listeners = (List<?>) listenerList;
        if (listeners.size() != 1 || listeners.getFirst() != listener
                || read(AbstractContainerMenu.class, menu, "suppressRemoteUpdates", boolean.class))
            throw refused("One exact initialized native listener/synchronizer is required");
        requireNativeObserver(listener, "net.minecraft.server.level.ServerPlayer$2", player);
        requireNativeObserver(synchronizer, "net.minecraft.server.level.ServerPlayer$1", player);
        for (int i = 0; i < 46; i++) {
            if (menu.getSlot(i) != slots.get(i)) throw refused("Native broadcast slot incarnation changed");
            requireNativeSlot(slots.get(i), i);
        }
    }
    /** Preflight every possible copy and packet, even unchanged slots; never count previews as ownership. */
    static int reservePayload(ServerPlayer player, NativeCraftingTableSnapshot.Data after, int byteLimit) {
        int used = 0;
        for (int i = 0; i < 47; i++) {
            NativeStackSnapshot image = i == 46 ? after.carried() : slotImage(after, i);
            ItemStack stack = image.materialize(player.registryAccess());
            NativeStackSnapshot copy = NativeStackSnapshot.capture(stack.copy(), player.registryAccess());
            if (!copy.equals(image)) throw refused("Native broadcast stack copy is not exact");
            var packet = new ClientboundContainerSetSlotPacket(i == 46 ? -1 : after.containerId(), after.stateId(), i == 46 ? -1 : i, stack);
            NativeCraftingTablePreparation.requireNativePacketRoundTrip(packet, image, player);
            used = checkedCharge(used, 64 + image.encodedBytes() * 2, byteLimit);
        }
        return used;
    }
    static NativeStackSnapshot slotImage(NativeCraftingTableSnapshot.Data data, int index) {
        if (index == 0) return data.result();
        if (index < 10) return data.grid().get(index - 1);
        return data.inventory().get(inventoryIndex(index));
    }
    static int inventoryIndex(int menuIndex) {
        if (menuIndex>=10 && menuIndex<=36) return menuIndex-1;
        if (menuIndex>=37 && menuIndex<=45) return menuIndex-37;
        throw refused("No native table inventory backing for menu index");
    }
    static int checkedCharge(int used, int amount, int limit) {
        if (used < 0 || amount < 0 || limit < 1 || used > limit || amount > limit - used)
            throw refused("Native menu delivery payload allowance exceeded");
        return used + amount;
    }
    private void requireNativeSlot(Slot slot, int index) {
        String type = index == 0 ? ResultSlot.class.getName() : Slot.class.getName();
        int backing = index == 0 ? 0 : index < 10 ? index - 1 : inventoryIndex(index);
        if (!slot.getClass().getName().equals(type) || slot.getClass().getClassLoader() != Slot.class.getClassLoader()
                || slot.index != index || slot.getContainerSlot() != backing)
            throw refused("Custom or remapped native broadcast slot is not admitted");
        try {
            if (slot.getClass().getMethod("getItem").getDeclaringClass() != Slot.class)
                throw refused("Custom native slot getItem callback is not admitted");
        } catch (ReflectiveOperationException failure) { throw refused("Pinned native Slot getter is unavailable"); }
    }
    private static void requireNativeObserver(Object observer, String name, ServerPlayer player) {
        if (observer == null || !observer.getClass().getName().equals(name)
                || observer.getClass().getClassLoader() != ServerPlayer.class.getClassLoader()
                || read(observer.getClass(), observer, "this$0", ServerPlayer.class) != player)
            throw refused("Native broadcast observer is not the exact real-player implementation");
    }
    private static <T> T read(Class<?> owner, Object object, String name, Class<T> type) {
        return NativeCraftingTableSnapshot.read(owner, object, name, type);
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
