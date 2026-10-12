package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeItemEntitySnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.stats.Stat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

/**
 * Calls the pinned native ItemEntity.playerTouch exactly once, with only its three mutating
 * player calls redirected to detached inventory/data effects. The entity itself is an exact
 * native detached copy with the NULL level callback; no native live state is rolled back.
 */
public final class NativeItemPickupPreparation {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private NativeItemPickupPreparation() {}
    static final class Result {
        private final NativeEffectCodec.ItemPickup effect;
        private final java.util.List<NativeStackSnapshot> inventoryAfter;
        private Result(NativeEffectCodec.ItemPickup effect, java.util.List<NativeStackSnapshot> inventoryAfter) {
            this.effect = effect; this.inventoryAfter = java.util.List.copyOf(inventoryAfter);
        }
        NativeEffectCodec.ItemPickup effect() { return effect; }
        java.util.List<NativeStackSnapshot> inventoryAfter() { return inventoryAfter; }
    }
    private static final class Scope {
        final NativeWholeAction action;
        final Player player;
        final ItemEntity copy;
        final Inventory inventory;
        int taken;
        Scope(NativeWholeAction action, Player player, ItemEntity copy) {
            this.action = action; this.player = player; this.copy = copy;
            this.inventory = new Inventory(player); inventory.selected = action.selectedSlot();
            for (int slot = 0; slot < 41; slot++) inventory.setItem(slot, action.stack(slot).copy());
        }
        void require(ItemEntity entity, Player player) {
            if (entity != copy || player != this.player || NativeWholeAction.current() != action
                    || action.state() != NativeWholeAction.State.PREPARING)
                throw new SurvivalRefusal("Native pickup callback escaped its exact detached action");
        }
    }

    static Result prepare(NativeEffectDeliveryHost host, NativeWholeAction action,
            ItemEntity source, NativeItemEntitySnapshot before) {
        if (ACTIVE.get() != null || NativeWholeAction.current() != action)
            throw new SurvivalRefusal("Native pickup preparation cannot reenter");
        var saved = before.tagCopy();
        if (saved.contains("Owner") || saved.contains("Thrower"))
            throw new SurvivalRefusal("Thrown/targeted native pickups require captured advancement semantics");
        ItemEntity copy = before.materialize(host.level());
        copy.setId(source.getId());
        host.requireDetachedEntity(copy);
        Scope scope = new Scope(action, host.player(), copy);
        ACTIVE.set(scope);
        try {
            copy.playerTouch(host.player());
            // Native Inventory.add may partially mutate both stacks and still return false.
            // Capture the actual transfer even when playerTouch never reaches take/awardStat.
            int transferred = copy.isRemoved() ? source.getItem().getCount()
                    : source.getItem().getCount() - copy.getItem().getCount();
            if (scope.taken != 0 && scope.taken != transferred)
                throw new SurvivalRefusal("Native pickup notification differs from transferred quantity");
            if (transferred == 0) {
                if (copy.isRemoved() || !before.equals(NativeItemEntitySnapshot.capture(copy)))
                    throw new SurvivalRefusal("Native no-pickup path unexpectedly changed detached entity state");
                for (int slot = 0; slot < 41; slot++)
                    if (!ItemStack.matches(action.stack(slot), scope.inventory.getItem(slot)))
                        throw new SurvivalRefusal("Native no-pickup path changed detached inventory");
                return new Result(null, java.util.List.of());
            }
            if (transferred < 0) throw new SurvivalRefusal("Native pickup increased its source stack unexpectedly");
            // Vanilla restores the local stack's picked count after discard for callbacks. A
            // removed clone is consumed, regardless of that transient count restoration.
            NativeItemEntitySnapshot after = copy.isRemoved() ? null : NativeItemEntitySnapshot.capture(copy);
            var inventoryAfter = new java.util.ArrayList<NativeStackSnapshot>(41);
            for (int slot = 0; slot < 41; slot++) {
                action.replaceStack(slot, scope.inventory.getItem(slot));
                inventoryAfter.add(NativeStackSnapshot.capture(scope.inventory.getItem(slot), host.level().registryAccess()));
            }
            return new Result(new NativeEffectCodec.ItemPickup(source.getUUID(), source.getId(), before, after, transferred, scope.taken), inventoryAfter);
        } finally { ACTIVE.remove(); }
    }

    /** The live-item HEAD hook must not route this exact detached native call a second time. */
    public static boolean isDetachedCall(ItemEntity entity, Player player) {
        Scope scope = ACTIVE.get();
        if (scope == null) return false;
        scope.require(entity, player); return true;
    }
    public static Inventory inventory(ItemEntity entity, Player player) {
        Scope scope = ACTIVE.get();
        if (scope == null) return player.getInventory();
        scope.require(entity, player); return scope.inventory;
    }
    public static void awardStat(ItemEntity entity, Player player, Stat<?> stat, int amount) {
        Scope scope = ACTIVE.get();
        if (scope == null) { player.awardStat(stat, amount); return; }
        scope.require(entity, player); scope.action.stage(statEffect(stat, amount));
    }
    private static <T> NativeEffectCodec.PlayerStat statEffect(Stat<T> stat, int amount) {
        return new NativeEffectCodec.PlayerStat(BuiltInRegistries.STAT_TYPE.getKey(stat.getType()),
                stat.getType().getRegistry().getKey(stat.getValue()), amount);
    }
    public static void take(ItemEntity entity, Player player, Entity takenEntity, int amount) {
        Scope scope = ACTIVE.get();
        if (scope == null) { player.take(takenEntity, amount); return; }
        scope.require(entity, player);
        if (takenEntity != entity || amount <= 0 || scope.taken != 0)
            throw new SurvivalRefusal("Unexpected native pickup notification identity/count");
        // The versioned pickup result retains quantity and ORIGINAL entity ID. No live menu
        // broadcast/advancement listener or collect packet may execute during preparation.
        scope.taken = amount;
    }
}
