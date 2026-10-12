package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** All native inventory indices, including armor/offhand; no live stack aliases. */
public record NativeInventorySnapshot(List<NativeStackSnapshot> slots, int selected) {
    public static final int SLOTS = 41;
    public NativeInventorySnapshot {
        Objects.requireNonNull(slots);
        if (slots.size() != SLOTS || selected < 0 || selected > 8)
            throw new IllegalArgumentException("Complete 41-slot native inventory and selected hotbar slot required");
        slots = List.copyOf(slots);
    }
    /** The host closes menus and resolves cursor/crafting contents before this read-only boundary. */
    public static NativeInventorySnapshot capture(ServerPlayer player) { return capture(player, Integer.MAX_VALUE); }
    public static NativeInventorySnapshot capture(ServerPlayer player, int maximumBytes) {
        Objects.requireNonNull(player);
        if (!player.server.isSameThread()) throw new SurvivalRefusal("Inventory checkpoint requires the server thread");
        requireResolvedMenu(player);
        var inventory = player.getInventory();
        return capture(new NativeWholeAction.InventoryView() {
            public int size() { return inventory.getContainerSize(); }
            public ItemStack getItem(int slot) { return inventory.getItem(slot); }
            public int selectedSlot() { return inventory.selected; }
        }, player.registryAccess(), maximumBytes);
    }
    static NativeInventorySnapshot capture(NativeWholeAction.InventoryView inventory, HolderLookup.Provider registries) {
        return capture(inventory, registries, Integer.MAX_VALUE);
    }
    static NativeInventorySnapshot capture(NativeWholeAction.InventoryView inventory, HolderLookup.Provider registries, int maximumBytes) {
        if (maximumBytes < Integer.BYTES) throw new SurvivalRefusal("Native inventory payload budget exhausted");
        if (inventory.size() != SLOTS) throw new SurvivalRefusal("Complete native inventory required");
        List<NativeStackSnapshot> values = new ArrayList<>(SLOTS);
        long bytes = Integer.BYTES;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (maximumBytes - bytes <= Integer.BYTES) throw new SurvivalRefusal("Native inventory payload budget exhausted");
            // The component codec has its own 24 KiB per-stack allocation admission.
            var stack = NativeStackSnapshot.capture(inventory.getItem(slot), registries);
            bytes += Integer.BYTES + stack.encodedBytes();
            if (bytes > maximumBytes) throw new SurvivalRefusal("Cumulative native inventory payload budget exceeded");
            values.add(stack);
        }
        return new NativeInventorySnapshot(values, inventory.selectedSlot());
    }
    public static void requireResolvedMenu(ServerPlayer player) {
        if (player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()
                || !player.inventoryMenu.getCraftSlots().isEmpty() || !player.inventoryMenu.getSlot(0).getItem().isEmpty())
            throw new SurvivalRefusal("Close menus and resolve native cursor/crafting stacks before checkpointing");
    }
    public int encodedBytes() {
        int bytes = Integer.BYTES;
        for (var stack : slots) bytes = Math.addExact(bytes, Math.addExact(Integer.BYTES, stack.encodedBytes()));
        return bytes;
    }
    /** Predecode every stack before the host changes any live inventory slot. */
    public List<ItemStack> materialize(HolderLookup.Provider registries) {
        return slots.stream().map(stack -> stack.materialize(registries)).toList();
    }
}
