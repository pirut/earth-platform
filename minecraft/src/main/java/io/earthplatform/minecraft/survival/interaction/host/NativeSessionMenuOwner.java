package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.CraftingMenu;

/** One host-retained menu owner; it never creates a second NativeWholeAction.Owner or checkpoint. */
final class NativeSessionMenuOwner {
    private final NativeEffectDeliveryHost host;
    private final Object lineage = new Object();
    private SurvivalLevelBinding boundBinding;
    private NativeWholeAction.Owner boundActions;
    private NativeSurvivalWorld boundWorld;
    private long revision;
    private NativeSessionMenuBoundary retained;
    private CubeCell table;
    private NativeMenuSupportedStance stance;
    private Object pending;
    private NativeWholeAction pendingAction;
    private boolean failed;
    NativeSessionMenuOwner(NativeEffectDeliveryHost host) { this.host = java.util.Objects.requireNonNull(host); }
    NativeEffectDeliveryHost host() { return host; }
    long revision() { requireHost(); return revision; }
    boolean retained() { requireHost(); return retained != null; }
    boolean failed() { requireHost(); return failed; }
    CubeCell table() { requireHost(); return table; }
    void requireHost() {
        if (!host.player().server.isSameThread() || host.sessionMenuOwner() != this
                || NativeEffectDeliveryHost.findAttached(host.level()) != host)
            throw refused("Native menu capability is not the exact attached host owner");
        var binding = SurvivalLevelBinding.find(host.level());
        if (boundBinding == null) {
            if (host.actions().fenced() || binding == null) throw refused("Native menu cannot initialize through a delivery fence");
            boundBinding = binding; boundActions = binding.actions(); boundWorld = binding.world();
        }
        // Never call generic binding.actions/world while APPLYING: those intentionally fence.
        if (binding != boundBinding || binding.isClosing() || binding.isDetached()
                || host.actions() != boundActions || host.actions().world() != boundWorld)
            throw refused("Native menu owner lost its retained exact binding/action lineage");
        if (failed) throw refused("Native menu delivery failed; retained session cannot be replayed or released");
    }
    void requirePreparing(NativeWholeAction action) {
        requireHost();
        if (host.actions().fenced()) throw refused("Native menu owner is delivery-fenced");
        if (action == null || NativeWholeAction.current() != action || !host.actions().owns(action)
                || action.state() != NativeWholeAction.State.PREPARING || action.world() != host.actions().world()
                || !action.identity().actor().equals(host.player().getUUID())) throw refused("Native menu preparation lacks exact host action lineage");
    }
    NativeSessionMenuBoundary captureForAction(NativeFirstLoopMetadata metadata, NativeMenuBudget budget) {
        requireHost();
        if (pending != null || host.actions().fenced() || host.actions().hasCurrentAction()) throw refused("Native menu action cannot overlap or reenter");
        if (retained == null) {
            NativeInventorySnapshot.requireResolvedMenu(host.player());
            stance = NativeMenuSupportedStance.capture(host.player(), host.actions().world(), budget);
        } else {
            budget.reserve(retained.retainedBytes());
            budget.reserve(stance.retainedBytes()); stance.requireIdle();
            retained.requireUnchanged(metadata, budget);
            return retained; // Transfer the existing complete before-image charge; do not duplicate it.
        }
        return NativeSessionMenuBoundary.capture(host.player(), host.actions().world(), table, metadata, budget);
    }
    java.util.Map<io.earthplatform.core.CubePos, NativeSurvivalWorld.CubeStamp> expected(CubeCell requestedTable) {
        requireHost(); if (stance == null) throw refused("Native menu lacks retained support evidence");
        return stance.expected(requestedTable);
    }
    void declareSupport(NativeWholeAction action) { requirePreparing(action); stance.declare(action); }
    void releaseClosedSupport() { if (retained == null && pending == null) stance = null; }
    void requireSupportDuring(NativeWholeAction action) { requireHost(); stance.requireDuring(action); }
    NativeMenuPacketPlan preparePacket(NativeWholeAction action, NativeSessionMenuBoundary before,
            NativeMenuPacketInput input, NativeFirstLoopMetadata metadata, NativeCraftingRecipeAdmission recipes,
            NativeInventoryMenuPreparation.Limits limits, NativeMenuBudget budget) {
        requirePreparing(action);
        if (pending != null) throw refused("Native menu already retains a preparing packet");
        var plan = NativeMenuPacketPlan.prepare(this, lineage, action, before, input, metadata, recipes, limits, budget);
        pending = plan; pendingAction = action; return plan;
    }
    NativeMenuClosePlan prepareClose(NativeWholeAction action, NativeSessionMenuBoundary before,
            NativeFirstLoopMetadata metadata, NativeMenuBudget budget) {
        requirePreparing(action);
        if (pending != null) throw refused("Native menu already retains a preparing close");
        var plan = NativeMenuClosePlan.prepare(this, lineage, action, before, metadata, budget);
        pending = plan; pendingAction = action; return plan;
    }
    void closed(Object plan, Object capability, NativeWholeAction action, NativeSurvivalWorld.CommitReceipt receipt,
            NativeSessionMenuBoundary resolved) {
        requirePlan(plan, capability, action, NativeWholeAction.State.APPLYING, revision);
        if (!resolved.data().durableResolved() || !receipt.action().equals(action.identity())
                || receipt.commitSequence() != action.world().commitSequence()) throw refused("Native menu close lacks resolved committed lineage");
        retained = null; table = null; revision = Math.addExact(revision, 1); pending = null; pendingAction = null;
    }
    void requirePlan(Object plan, Object capability, NativeWholeAction action, NativeWholeAction.State state, long expectedRevision) {
        requireHost();
        if (capability != lineage || pending != plan || pendingAction != action || expectedRevision != revision || NativeWholeAction.current() != action
                || !host.actions().owns(action) || action.state() != state) throw refused("Native menu receipt plan is foreign, stale or duplicated");
    }
    void applied(Object plan, Object capability, NativeWholeAction action, NativeSurvivalWorld.CommitReceipt receipt,
            NativeSessionMenuBoundary after) {
        requirePlan(plan, capability, action, NativeWholeAction.State.APPLYING, revision);
        if (!receipt.action().equals(action.identity()) || receipt.commitSequence() != action.world().commitSequence())
            throw refused("Native menu result has a foreign receipt");
        retained = after; revision = Math.addExact(revision, 1); pending = null; pendingAction = null;
    }
    void failed(Object plan, Object capability) {
        if (capability != lineage || pending != plan) throw refused("Foreign native menu failure fence");
        failed = true; // Deliberately retain the exact failed plan and before boundary. No retry/reset API.
    }
    void abortPreparation(NativeWholeAction action) {
        requireHost();
        if (action.state() != NativeWholeAction.State.ABORTED || pending != null && pendingAction != action)
            throw refused("Native menu abort cleanup has no exact aborted host action");
        pending = null; pendingAction = null;
    }
    NativeMenuOpenPlan prepareOpen(NativeWholeAction action, NativeSessionMenuBoundary before, CubeCell position,
            NativeFirstLoopMetadata metadata, NativeCraftingRecipeAdmission recipes, NativeMenuBudget budget) {
        requirePreparing(action);
        if (pending != null || table != null) throw refused("Native retained table open overlaps another menu lifecycle");
        var plan = NativeMenuOpenPlan.prepare(this, lineage, action, before, position, metadata, recipes, budget);
        pending = plan; pendingAction = action; return plan;
    }
    void opened(Object plan, Object capability, NativeWholeAction action, NativeSurvivalWorld.CommitReceipt receipt,
            CubeCell position, CraftingMenu exactMenu, NativeSessionMenuBoundary observed) {
        requirePlan(plan, capability, action, NativeWholeAction.State.APPLYING, revision);
        if (!receipt.action().equals(action.identity()) || receipt.commitSequence() != action.world().commitSequence()
                || host.player().containerMenu != exactMenu || observed.activeMenu() != exactMenu
                || observed.table() == null || !observed.table().equals(position))
            throw refused("Native table adoption lacks its exact privately retained committed open");
        table = position; retained = observed; revision = Math.addExact(revision, 1); pending = null; pendingAction = null;
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
