package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.interaction.NativeVanillaPreparation;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.block.state.BlockState;

/** Actual placed native table use/open, retaining its exact menu across future packet actions. */
final class NativeMenuOpenPlan {
    private final NativeSessionMenuOwner owner;
    private final Object lineage;
    private final NativeWholeAction action;
    private final NativeSessionMenuBoundary before;
    private final NativeFirstLoopMetadata metadata;
    private final NativeCraftingRecipeAdmission recipes;
    private final NativeMenuBudget budget, finalBoundaryBudget;
    private final NativeTableMenuLifecycleAdmission lifecycle;
    private final NativeMenuMetadataDeliveryAdmission delivery;
    private final NativeEffectCodec.SessionMenuOpen effect;
    private final CubeCell table;
    private final BlockState state;
    private final ServerPlayer player;
    private final net.minecraft.world.inventory.InventoryMenu inventoryMenu;
    private final int interactionBefore, interactionAfter, reservedBytes;
    private NativeSurvivalWorld.CommitReceipt authorizedReceipt;
    private boolean attempted;
    static NativeMenuOpenPlan prepare(NativeSessionMenuOwner owner, Object lineage, NativeWholeAction action,
            NativeSessionMenuBoundary before, CubeCell table, NativeFirstLoopMetadata metadata,
            NativeCraftingRecipeAdmission recipes, NativeMenuBudget budget) {
        try { return new NativeMenuOpenPlan(owner,lineage,action,before,table,metadata,recipes,budget); }
        catch (RuntimeException | Error failure) {
            if (action.state() == NativeWholeAction.State.PREPARING) action.poisonPreparation(); throw failure;
        }
    }
    private NativeMenuOpenPlan(NativeSessionMenuOwner owner, Object lineage, NativeWholeAction action,
            NativeSessionMenuBoundary before, CubeCell table, NativeFirstLoopMetadata metadata,
            NativeCraftingRecipeAdmission recipes, NativeMenuBudget budget) {
        this.owner=owner; this.lineage=lineage; this.action=action; this.before=before; this.table=table;
        this.metadata=metadata; this.recipes=recipes; this.budget=budget;
        player=owner.host().player(); inventoryMenu=player.inventoryMenu;
        owner.requirePreparing(action); before.requireUnchanged(metadata,budget);
        if (!before.data().durableResolved()) throw refused("Native retained table open requires resolved inventory");
        NativeSessionMenuBoundary.requireSupportedStance(player); state=action.read(table);
        if (!state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) throw refused("Native open requires an actual placed crafting table");
        lifecycle=new NativeTableMenuLifecycleAdmission(player,table);
        // Source-qualified native provider/init projection. The empty result-slot click must be
        // a strict no-op; it cannot grant items, callbacks or a fabricated crafting outcome.
        budget.reserve(1024*1024);
        var transcript=NativeCraftingTablePreparation.prepareResolvedScript(action,player,table,metadata,recipes,
                List.of(new NativeInventoryMenuPreparation.Click(0,0,ClickType.PICKUP)),
                new NativeInventoryMenuPreparation.Limits(1,1,32768,1024*1024),
                new NativeInventoryMenuSnapshot.Budget(1024*1024));
        if (!transcript.before().equals(transcript.after()) || !transcript.notices().isEmpty())
            throw refused("Native empty-open projection unexpectedly produced click effects");
        var opened=transcript.before();
        var menu=new NativeSessionMenuBoundary.MenuData(opened.containerId(),opened.grid(),opened.result(),opened.carried(),opened.recipeUsed(),
                opened.removeCount(),opened.stateId(),opened.lastSlots(),opened.remoteSlots(),opened.remoteCarried(),opened.quickcraftType(),0,List.of(),false);
        var after=new NativeSessionMenuBoundary.Data(opened.inventory(),opened.selected(),opened.inventoryChanges(),
                opened.containerId(),before.data().inventoryMenu(),menu);
        int captureBytes=NativeSessionMenuBoundary.captureReservation(after);
        budget.reserve(captureBytes); finalBoundaryBudget=new NativeMenuBudget(captureBytes);
        effect=new NativeEffectCodec.SessionMenuOpen(action.world().world(),action.identity().ownerEpoch(),action.identity().actor(),action.identity().sequence(),
                new NativeMenuOpenJournal(owner.host().session(),owner.revision(),table,action.world().palette().id(state),before.data(),after));
        budget.reserve(2*io.earthplatform.minecraft.survival.world.NativeActionEffect.MAX_PAYLOAD_BYTES);
        if (!effect.equals(NativeEffectCodec.decode(NativeEffectCodec.encode(effect),player.registryAccess())))
            throw refused("Native table-open evidence changed during codec round trip");
        budget.reserve(1024*1024);
        int openBytes=NativeTableMenuLifecycleAdmission.reserveOpen(player,opened,256*1024);
        delivery=NativeMenuMetadataDeliveryAdmission.captureTable(player,metadata,recipes,transcript,256*1024-openBytes);
        reservedBytes=Math.addExact(openBytes,delivery.reservedBytes()); budget.reserve(reservedBytes);
        var stat=net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.INTERACT_WITH_CRAFTING_TABLE);
        interactionBefore=player.getStats().getValue(stat);
        interactionAfter=NativeEffectDeliveryHost.checkedAdd(interactionBefore,1,"Native table interaction statistic");
        requireBefore();
    }
    NativeEffectCodec.SessionMenuOpen effect() { return effect; }
    NativeSessionMenuBoundary.Data after() { return effect.journal().after(); }
    List<NativeStackSnapshot> inventoryAfter() { return after().inventory(); }
    int reservedDeliveryBytes() { return reservedBytes; }
    int reservedDeliveryOperations() { return 48+delivery.reservedOperations(); }
    void requireBeforeCommit(NativeWholeAction exact, NativeEffectCodec.SessionMenuOpen supplied) {
        owner.requirePlan(this,lineage,exact,NativeWholeAction.State.PREVALIDATING,effect.journal().revision());
        if (exact!=action || attempted || authorizedReceipt!=null || !effect.equals(supplied)) throw refused("Foreign retained native open evidence");
        requireBefore();
    }
    void requireBeforeApply(NativeWholeAction exact, NativeSurvivalWorld.CommitReceipt receipt) {
        owner.requirePlan(this,lineage,exact,NativeWholeAction.State.APPLYING,effect.journal().revision());
        if (exact!=action || attempted || authorizedReceipt!=null || !receipt.action().equals(action.identity())
                || !receipt.world().equals(action.world().world()) || !receipt.ownerEpoch().equals(action.world().ownerEpoch())
                || !receipt.changedCubes().isEmpty() || receipt.commitSequence()!=action.world().commitSequence()
                || !receipt.effects().contains(NativeEffectCodec.encode(effect)))
            throw refused("Native retained open lacks exact committed receipt");
        requireBefore(); authorizedReceipt=receipt;
    }
    private void requireBefore() {
        before.requireUnchanged(metadata,budget); before.requireConservedPlayer(); lifecycle.requireBefore();
        delivery.require(); metadata.revalidate(); recipes.revalidate();
        if (player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.INTERACT_WITH_CRAFTING_TABLE))!=interactionBefore)
            throw refused("Native table interaction statistic changed");
    }
    void apply(NativeSurvivalWorld.CommitReceipt receipt) {
        owner.requirePlan(this,lineage,action,NativeWholeAction.State.APPLYING,effect.journal().revision());
        if (attempted || receipt!=authorizedReceipt) throw refused("Native retained open cannot be replayed"); attempted=true;
        try {
            before.requireConservedPlayer(); lifecycle.requireBefore(); delivery.require();
            var pos=NativeVanillaPreparation.nativePos(table);
            var provider=state.getMenuProvider(player.level(),pos); NativeTableMenuLifecycleAdmission.requireProvider(provider,player,table);
            var result=state.useWithoutItem(player.level(),player,new net.minecraft.world.phys.BlockHitResult(
                    net.minecraft.world.phys.Vec3.atCenterOf(pos),net.minecraft.core.Direction.UP,pos,false));
            if (result!=net.minecraft.world.InteractionResult.CONSUME || player.containerMenu.getClass()!=CraftingMenu.class || player.inventoryMenu!=inventoryMenu)
                throw refused("Native placed table use did not retain exact crafting menu");
            var opened=(CraftingMenu)player.containerMenu;
            lifecycle.requireOpened(opened); before.requireConservedPlayer(); delivery.require(); metadata.revalidate(); recipes.revalidate();
            if (player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.INTERACT_WITH_CRAFTING_TABLE))!=interactionAfter)
                throw refused("Native table interaction statistic differed from one use");
            var observed=NativeSessionMenuBoundary.capture(player,action.world(),table,metadata,finalBoundaryBudget);
            before.requireInventoryTopology(observed);
            if (!observed.data().equals(after())) throw refused("Actual native table open differs from complete prepared ownership boundary");
            owner.opened(this,lineage,action,receipt,table,opened,observed);
        } catch (RuntimeException | Error failure) { owner.failed(this,lineage); throw failure; }
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
