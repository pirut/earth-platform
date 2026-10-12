package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

/** Actual native removed/return/transferState preparation, with no live menu/player swapping. */
public final class NativeMenuClosePreparation {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private NativeMenuClosePreparation() {}
    public record Result(NativeSessionMenuBoundary.Data after, List<NativeInventoryMenuPreparation.SlotPacket> packets) {
        public Result { packets = List.copyOf(packets); }
    }
    private static final class Scope {
        final NativeSessionMenuOwner owner; final NativeWholeAction action; final NativeSessionMenuBoundary before;
        final ServerPlayer player; final NativeFirstLoopMetadata metadata; final NativeMenuBudget budget;
        final List<NativeInventoryMenuPreparation.SlotPacket> packets = new ArrayList<>();
        Inventory inventory; InventoryMenu inventoryMenu; AbstractContainerMenu menu;
        boolean closing;
        Scope(NativeSessionMenuOwner owner, NativeWholeAction action, NativeSessionMenuBoundary before, NativeFirstLoopMetadata metadata, NativeMenuBudget budget) {
            this.owner = owner; this.action = action; this.before = before; this.metadata = metadata; this.budget = budget; player = owner.host().player();
        }
        void require() {
            owner.requirePreparing(action);
            if (!closing || player.containerMenu != before.activeMenu() || inventory == player.getInventory()
                    || menu == player.containerMenu || inventoryMenu == player.inventoryMenu) throw refused("Native close callback escaped private detached owner");
        }
    }
    static Result prepare(NativeSessionMenuOwner owner, NativeWholeAction action, NativeSessionMenuBoundary before,
            NativeFirstLoopMetadata metadata, NativeMenuBudget budget) {
        boolean success = false;
        try {
            owner.requirePreparing(action); before.requireUnchanged(metadata, budget);
            if (ACTIVE.get() != null || NativeSessionMenuPreparation.active() || NativeInventoryMenuPreparation.active() || NativeCraftingTablePreparation.active())
                throw refused("Native close preparation cannot reenter another menu scope");
            var events = new PinnedNativeEventAdmission(NeoForge.EVENT_BUS); events.requireEmpty(PlayerContainerEvent.Close.class);
            var scope = new Scope(owner, action, before, metadata, budget);
            NativeMenuCloseCapacity.requireResolvable(owner, action, before.data(), budget);
            ACTIVE.set(scope);
            try {
                budget.reserve(256 * 1024);
                scope.inventory = new Inventory(scope.player); scope.inventoryMenu = new InventoryMenu(scope.inventory, true, scope.player);
                for (int i = 0; i < 41; i++) scope.inventory.setItem(i, before.data().inventory().get(i).materialize(scope.player.registryAccess()));
                scope.inventory.selected = before.data().selected();
                NativeSessionMenuBoundary.write(Inventory.class, scope.inventory, "timesChanged", int.class, before.data().inventoryChanges());
                hydrate(scope.inventoryMenu, before.data().inventoryMenu(), scope.player);
                if (before.table() == null) scope.menu = scope.inventoryMenu;
                else {
                    var state = action.read(before.table());
                    if (!state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) throw refused("Native close table disappeared");
                    var provider = state.getMenuProvider(scope.player.level(), io.earthplatform.minecraft.survival.interaction.NativeVanillaPreparation.nativePos(before.table()));
                    NativeTableMenuLifecycleAdmission.requireProvider(provider, scope.player, before.table());
                    scope.menu = provider.createMenu(before.data().active().containerId(), scope.inventory, scope.player);
                    if (scope.menu == null || scope.menu.getClass() != CraftingMenu.class) throw refused("Native close table provider changed");
                    hydrate(scope.menu, before.data().active(), scope.player);
                }
                scope.closing = true;
                scope.menu.removed(scope.player); // Genuine native cursor/grid reconciliation, exactly once in preparation.
                scope.inventoryMenu.transferState(scope.menu); // Exact native shared-slot cache transfer, not invented mapping.
                scope.closing = false;
                var images = new ArrayList<NativeStackSnapshot>(41);
                metadata.requireItems(scope.inventory.items); metadata.requireItems(scope.inventory.armor); metadata.requireItems(scope.inventory.offhand);
                for (int i = 0; i < 41; i++) images.add(budget.image(scope.inventory.getItem(i), scope.player));
                var after = new NativeSessionMenuBoundary.Data(images, scope.inventory.selected, scope.inventory.getTimesChanged(), before.data().containerCounter(),
                        NativeSessionMenuBoundary.captureMenu(scope.player, scope.inventoryMenu, metadata, budget), null);
                if (!after.durableResolved()) throw refused("Genuine native close did not resolve cursor/grid/result");
                before.requireUnchanged(metadata, budget); events.requireEmpty(PlayerContainerEvent.Close.class); metadata.revalidate(); success = true;
                return new Result(after, scope.packets);
            } finally { ACTIVE.remove(); }
        } finally { if (!success && action.state() == NativeWholeAction.State.PREPARING) action.poisonPreparation(); }
    }
    public static Inventory inventory(AbstractContainerMenu menu, Player player) {
        Scope scope = ACTIVE.get(); if (scope == null) return player.getInventory();
        scope.require();
        if (menu != scope.menu || player != scope.player) throw refused("Foreign native close inventory lookup");
        return scope.inventory;
    }
    public static void packet(Inventory inventory, ServerGamePacketListenerImpl connection, Packet<?> packet) {
        Scope scope = ACTIVE.get(); if (scope == null) { connection.send(packet); return; }
        scope.require();
        if (inventory != scope.inventory || connection != scope.player.connection || !(packet instanceof ClientboundContainerSetSlotPacket slot)
                || slot.getContainerId() != -2 || slot.getStateId() != 0 || slot.getSlot() < 0 || slot.getSlot() >= 41
                || scope.packets.size() >= 400) throw refused("Unexpected native close reconciliation packet");
        var image = scope.budget.image(slot.getItem(), scope.player); scope.budget.reserve(32768);
        NativeInventoryMenuPreparation.requireNativePacketRoundTrip(slot, image, scope.player);
        scope.packets.add(new NativeInventoryMenuPreparation.SlotPacket(-2, 0, slot.getSlot(), image));
    }
    public static ItemEntity drop(Inventory inventory, Player player, ItemStack stack, boolean includeThrower) {
        Scope scope = ACTIVE.get(); if (scope == null) return player.drop(stack, includeThrower);
        scope.require();
        if (inventory != scope.inventory || player != scope.player) throw refused("Foreign native close drop callback");
        throw refused("Native close would toss an item; retained session remains held");
    }
    private static void hydrate(AbstractContainerMenu target, NativeSessionMenuBoundary.MenuData data, ServerPlayer player) {
        Scope scope = ACTIVE.get();
        if (scope == null || scope.closing || target == player.containerMenu || target == player.inventoryMenu) throw refused("Native close hydration lacks private detached capability");
        replace(NativeSessionMenuBoundary.read(net.minecraft.world.inventory.TransientCraftingContainer.class, NativeSessionMenuPreparation.grid(target), "items", NonNullList.class), data.grid(), player);
        var result = NativeSessionMenuPreparation.result(target); result.setItem(0, data.result().materialize(player.registryAccess()));
        RecipeHolder<?> recipe = data.recipeUsed() == null ? null : player.server.getRecipeManager().byKey(data.recipeUsed()).orElseThrow(() -> refused("Native close recipe disappeared"));
        result.setRecipeUsed(recipe); target.setCarried(data.carried().materialize(player.registryAccess()));
        replace(NativeSessionMenuBoundary.read(AbstractContainerMenu.class, target, "lastSlots", NonNullList.class), data.lastSlots(), player);
        replace(NativeSessionMenuBoundary.read(AbstractContainerMenu.class, target, "remoteSlots", NonNullList.class), data.remoteSlots(), player);
        target.setRemoteCarried(data.remoteCarried().materialize(player.registryAccess()));
        NativeSessionMenuBoundary.write(AbstractContainerMenu.class, target, "stateId", int.class, data.stateId());
        NativeSessionMenuBoundary.write(AbstractContainerMenu.class, target, "quickcraftType", int.class, data.quickcraftType());
        if (!NativeSessionMenuBoundary.read(AbstractContainerMenu.class, target, "containerListeners", List.class).isEmpty()
                || NativeSessionMenuBoundary.read(AbstractContainerMenu.class, target, "synchronizer", net.minecraft.world.inventory.ContainerSynchronizer.class) != null)
            throw refused("Native close detached menu unexpectedly has observers");
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static void replace(List target, List<NativeStackSnapshot> data, ServerPlayer player) {
        if (target.size() != data.size()) throw refused("Native close hydration vector mismatch");
        for (int i = 0; i < data.size(); i++) target.set(i, data.get(i).materialize(player.registryAccess()));
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
