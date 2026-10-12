package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Native return-to-inventory capacity qualification. A native toss request always refuses precommit. */
public final class NativeMenuCloseCapacity {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private record Scope(NativeSessionMenuOwner owner, NativeWholeAction action, Inventory inventory, ServerPlayer player) {}
    private NativeMenuCloseCapacity() {}
    static void requireResolvable(NativeSessionMenuOwner owner, NativeWholeAction action,
            NativeSessionMenuBoundary.Data projected, NativeMenuBudget budget) {
        owner.requirePreparing(action);
        if (ACTIVE.get() != null) throw new SurvivalRefusal("Native close-capacity proof cannot reenter");
        ServerPlayer player = owner.host().player();
        if (!player.isAlive() || player.hasDisconnected() || projected.tableMenu() != null && !projected.inventoryMenu().resolved())
            throw new SurvivalRefusal("Native close cannot safely reconcile this player/inactive menu");
        budget.reserve(1024 * 1024);
        var inventory = new Inventory(player);
        for (int slot = 0; slot < 41; slot++) inventory.setItem(slot, projected.inventory().get(slot).materialize(player.registryAccess()));
        inventory.selected = projected.selected();
        ACTIVE.set(new Scope(owner, action, inventory, player));
        try {
            // Pinned AbstractContainerMenu.removed returns cursor before clearContainer visits
            // the grid in index order. The result slot is a preview, never an extra owned stack.
            inventory.placeItemBackInInventory(projected.active().carried().materialize(player.registryAccess()), false);
            for (var stack : projected.active().grid()) inventory.placeItemBackInInventory(stack.materialize(player.registryAccess()), false);
        } finally { ACTIVE.remove(); }
    }
    public static ItemEntity drop(Inventory inventory, Player player, ItemStack stack, boolean includeThrower) {
        Scope scope = ACTIVE.get();
        if (scope == null) return NativeMenuClosePreparation.drop(inventory, player, stack, includeThrower);
        scope.owner().requirePreparing(scope.action());
        if (inventory != scope.inventory() || player != scope.player()) throw new SurvivalRefusal("Foreign native close-capacity inventory callback");
        throw new SurvivalRefusal("Native menu needs free inventory capacity to close; this click would require an unadmitted native toss");
    }
}
