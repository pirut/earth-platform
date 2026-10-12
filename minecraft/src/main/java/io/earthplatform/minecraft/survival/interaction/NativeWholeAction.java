package io.earthplatform.minecraft.survival.interaction;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One non-reentrant server-owner-thread action, including detached full native inventory stacks.
 * Every callback runs once during preparation. A host validates/allocates all effect delivery
 * before the cube commit and applies that prepared batch once before publishing the receipt.
 *
 * <p>A postcommit apply failure is NOT rollback. The owner becomes permanently fenced and retains
 * the exact receipt/batch for diagnosis and a future separately proved recovery path. This class
 * intentionally provides no retry, discard, unfence or callback-replay escape hatch.
 */
public final class NativeWholeAction implements AutoCloseable {
    private static final ThreadLocal<NativeWholeAction> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> OPENING = new ThreadLocal<>();

    public interface InventoryView {
        int size();
        ItemStack getItem(int slot);
        int selectedSlot();
    }

    @FunctionalInterface public interface EffectHost {
        /**
         * Must reject unsupported effects, unavailable entity/tick capacity, registry mismatches
         * and every other predictable refusal here. Must not change the live world or inventory.
         */
        PreparedEffects prepare(NativeSurvivalWorld.ActionIdentity action, List<NativeEffectCodec.Effect> effects);
    }
    @FunctionalInterface public interface PreparedEffects {
        /** Apply only prevalidated data, exactly once. No native planning/callback reevaluation. */
        void apply(NativeSurvivalWorld.CommitReceipt receipt);
    }

    public static final class PendingDelivery {
        private final NativeSurvivalWorld.CommitReceipt receipt;
        private final PreparedEffects prepared;
        private Throwable failure;
        private PendingDelivery(NativeSurvivalWorld.CommitReceipt receipt, PreparedEffects prepared) {
            this.receipt = receipt; this.prepared = prepared;
        }
        public NativeSurvivalWorld.CommitReceipt receipt() { return receipt; }
        public Throwable failure() { return failure; }
        /** Diagnostic only: delivery was attempted, and must never be blindly invoked again. */
        public boolean hasRetainedPreparedBatch() { return prepared != null; }
    }

    /** Keep this owner with the host session; replacing it is not an authorized recovery action. */
    public static final class Owner {
        private final NativeSurvivalWorld world;
        private final HolderLookup.Provider registries;
        private final EffectHost host;
        private PendingDelivery pending;
        private NativeWholeAction current;
        public Owner(NativeSurvivalWorld world, HolderLookup.Provider registries, EffectHost host) {
            this.world = Objects.requireNonNull(world); this.registries = Objects.requireNonNull(registries);
            this.host = Objects.requireNonNull(host); world.checkOwner();
        }
        public boolean fenced() { world.checkOwner(); return pending != null; }
        public PendingDelivery pendingDelivery() { world.checkOwner(); return pending; }
        public NativeSurvivalWorld world() { world.checkOwner(); return world; }
        public boolean hasCurrentAction() { world.checkOwner(); return current != null; }
        public boolean owns(NativeWholeAction action) {
            world.checkOwner();
            return action != null && action.owner == this && current == action && !action.closed;
        }
        public NativeWholeAction begin(NativeSurvivalWorld.ActionIdentity identity,
                Map<CubePos, NativeSurvivalWorld.CubeStamp> expected, InventoryView inventory) {
            world.checkOwner();
            if (pending != null) throw new SurvivalRefusal("postcommit native delivery failed; session requires verified recovery");
            if (current != null || ACTIVE.get() != null || Boolean.TRUE.equals(OPENING.get()))
                throw new SurvivalRefusal("native whole actions cannot reenter");
            // Fence existing render authority and install OPENING before every caller inventory
            // method, including size/selectedSlot. Snapshot failure never restores an old lease.
            world.revokeRenderPublications();
            OPENING.set(true);
            try {
                Objects.requireNonNull(inventory);
                if (inventory.size() != 41 || inventory.selectedSlot() < 0 || inventory.selectedSlot() > 8)
                    throw new SurvivalRefusal("ordinary 41-slot native inventory required");
                NativeWholeAction action = new NativeWholeAction(this, inventory, identity, expected);
                current = action; ACTIVE.set(action); return action;
            } finally { OPENING.remove(); }
        }
    }

    public enum State { PREPARING, PREVALIDATING, APPLYING, APPLIED, ABORTED, DELIVERY_FAILED }
    private final Owner owner;
    private final InventoryView inventory;
    private final int selectedSlot;
    private final NativeStackSnapshot[] before = new NativeStackSnapshot[41];
    private final ItemStack[] detached = new ItemStack[41];
    private final List<NativeEffectCodec.Effect> effects = new ArrayList<>();
    private final NativeSurvivalWorld.ActionTransaction transaction;
    private final NativeActionRandom random;
    private State state = State.PREPARING;
    private boolean closed;

    private NativeWholeAction(Owner owner, InventoryView inventory, NativeSurvivalWorld.ActionIdentity identity,
            Map<CubePos, NativeSurvivalWorld.CubeStamp> expected) {
        this.owner = owner; this.inventory = inventory; this.selectedSlot = inventory.selectedSlot();
        // Snapshot/codec errors precede begin, so there is no abandoned active transaction.
        for (int slot = 0; slot < before.length; slot++) {
            ItemStack live = Objects.requireNonNull(inventory.getItem(slot));
            before[slot] = NativeStackSnapshot.capture(live, owner.registries);
            detached[slot] = live.copy();
        }
        transaction = owner.world.begin(identity, expected);
        try {
            random = new NativeActionRandom(owner.world.world(), identity);
            transaction.stage(NativeEffectCodec.encode(random.seedEffect()));
            effects.add(random.seedEffect());
        } catch (RuntimeException | Error failure) { transaction.close(); throw failure; }
    }

    public static NativeWholeAction current() { return ACTIVE.get(); }
    /** Owner-thread read-only barrier, including inventory capture before ACTIVE is installed. */
    public static boolean hasCurrentOrOpeningAction() {
        return ACTIVE.get() != null || Boolean.TRUE.equals(OPENING.get());
    }
    public NativeSurvivalWorld world() { return owner.world; }
    public NativeSurvivalWorld.ActionIdentity identity() { return transaction.identity(); }
    public State state() { owner.world.checkOwner(); return state; }
    public BlockState read(CubeCell cell) { requirePreparing(); return transaction.read(cell); }
    public BlockState set(CubeCell cell, BlockState replacement) { requirePreparing(); return transaction.set(cell, replacement); }
    public int selectedSlot() { return selectedSlot; }
    /** Returns the existing stream, never a fresh/reset stream. Only available while preparing. */
    public RandomSource random() { requirePreparing(); return random.source(); }
    /** Mutable detached stack. It never aliases native live inventory; only this scope may mutate it. */
    public ItemStack stack(int slot) { requirePreparing(); checkSlot(slot); return detached[slot]; }
    public void replaceStack(int slot, ItemStack replacement) {
        requirePreparing(); checkSlot(slot); detached[slot] = Objects.requireNonNull(replacement).copy();
    }
    public void stage(NativeEffectCodec.Effect effect) {
        requirePreparing();
        try {
            Objects.requireNonNull(effect);
            if (effect instanceof NativeEffectCodec.InventorySlot)
                throw new SurvivalRefusal("inventory effects are derived from detached slots, never supplied by callbacks");
            if (effect instanceof NativeEffectCodec.RandomSeed)
                throw new SurvivalRefusal("action random identity is derived once by the scope");
            transaction.stage(NativeEffectCodec.encode(effect)); effects.add(effect);
        } catch (RuntimeException | Error refused) {
            transaction.close(); state = State.ABORTED; throw refused;
        }
    }

    /**
     * Abort-only callback poison. Retain the current scope until close, so a swallowed callback
     * refusal can neither commit its partial plan nor open a nested replacement action.
     */
    public void poisonPreparation() {
        requirePreparing();
        transaction.close(); state = State.ABORTED;
    }

    /** Returns only after the single prepared effect application succeeded. Publishing comes next. */
    public NativeSurvivalWorld.CommitReceipt commit() {
        requirePreparing();
        try {
            for (int slot = 0; slot < before.length; slot++) {
                NativeStackSnapshot after = NativeStackSnapshot.capture(detached[slot], owner.registries);
                if (!before[slot].equals(after)) {
                    var effect = new NativeEffectCodec.InventorySlot(slot, before[slot], after);
                    transaction.stage(NativeEffectCodec.encode(effect)); effects.add(effect);
                }
            }
            // Round-trip every full stack/effect now, before committing even one block.
            List<NativeEffectCodec.Effect> admitted = effects.stream()
                    .map(effect -> NativeEffectCodec.decode(NativeEffectCodec.encode(effect), owner.registries)).toList();
            state = State.PREVALIDATING;
            PreparedEffects prepared = Objects.requireNonNull(owner.host.prepare(identity(), admitted), "prepared effect batch");
            revalidateInventory();
            NativeSurvivalWorld.CommitReceipt receipt = transaction.commit();
            state = State.APPLYING;
            // Install the fence before touching live native state, including for linkage/assertion
            // failures. Successful delivery is the only path that clears this pending record.
            owner.pending = new PendingDelivery(receipt, prepared);
            try {
                prepared.apply(receipt);
                state = State.APPLIED;
                owner.pending = null;
                return receipt;
            } catch (RuntimeException | Error failure) {
                state = State.DELIVERY_FAILED;
                owner.pending.failure = failure;
                var refused = new SurvivalRefusal("cube commit succeeded but native effect delivery failed; session fenced, receipt retained");
                refused.initCause(failure);
                throw refused;
            }
        } catch (RuntimeException | Error failure) {
            if (state == State.PREPARING || state == State.PREVALIDATING) { transaction.close(); state = State.ABORTED; }
            throw failure;
        }
    }

    private void revalidateInventory() {
        if (inventory.size() != 41 || inventory.selectedSlot() != selectedSlot)
            throw new SurvivalRefusal("native inventory selection changed during action");
        for (int slot = 0; slot < before.length; slot++)
            if (!before[slot].matches(inventory.getItem(slot), owner.registries))
                throw new SurvivalRefusal("native inventory changed during action at slot " + slot);
    }
    @Override public void close() {
        owner.world.checkOwner();
        if (closed) return;
        if (state == State.APPLYING || state == State.PREVALIDATING)
            throw new SurvivalRefusal("cannot close a validating or applying native action");
        if (state == State.PREPARING) { transaction.close(); state = State.ABORTED; }
        if (owner.current != this || ACTIVE.get() != this) throw new SurvivalRefusal("native action ownership mismatch");
        owner.current = null; ACTIVE.remove(); closed = true;
    }
    private void requirePreparing() {
        owner.world.checkOwner();
        if (closed || state != State.PREPARING || owner.current != this || ACTIVE.get() != this)
            throw new SurvivalRefusal("native action is not the current preparation scope");
    }
    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= 41) throw new IllegalArgumentException("Native inventory slot is out of bounds");
    }

    public static InventoryView inventoryOf(ServerPlayer player) {
        Objects.requireNonNull(player);
        if (!player.server.isSameThread()) throw new SurvivalRefusal("native player inventory must be captured on the server thread");
        return new InventoryView() {
            public int size() { return player.getInventory().getContainerSize(); }
            public ItemStack getItem(int slot) { return player.getInventory().getItem(slot); }
            public int selectedSlot() { return player.getInventory().selected; }
        };
    }
}
