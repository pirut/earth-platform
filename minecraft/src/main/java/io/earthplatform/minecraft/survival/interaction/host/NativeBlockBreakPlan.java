package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.host.NativeDryMutationRouter;
import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.interaction.NativeVanillaPreparation;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.persistence.NativeItemEntitySnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.CubeSnapshot;
import io.earthplatform.minecraft.survival.world.NativeActionEffect;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

/** Private source-qualified target removal and full exact outcome. No caller effect shape is trusted. */
final class NativeBlockBreakPlan {
    static final int DROP_IMAGE_MAX_BYTES = 4096;
    static final int DROP_IMAGE_SLOT_BYTES = 64 + DROP_IMAGE_MAX_BYTES
            + io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot.MAX_BYTES;
    static final int IMAGE_ALLOWANCE_BYTES = 2 * DROP_IMAGE_SLOT_BYTES;
    // The host prepays one retained plus one scratch image before this private plan is constructed.
    // Charges shrink only after complete capture/comparison; failed capture keeps its conservative reservation.
    private final NativeEffectDeliveryHost.BlockProofImageSlot imageAllowance;
    private final NativeEffectDeliveryHost host;
    private final NativeMiningControlPreparation.DestroyInvocation miningInvocation;
    private final NativeWholeAction action;
    private final ServerPlayer player;
    private final UUID session;
    private final CubeCell target;
    private final CubeSnapshot beforeCube;
    private final BlockState before;
    private final int beforeStateId, inventoryChanges, minedBefore;
    private final long commitBefore;
    private final NativeBlockBreakAdmission admission;
    private final float progress;
    private final NativeEffectCodec.BlockBreakMetadata metadata;
    private final List<NativeEffectCodec.Effect> expected;
    private List<NativeActionEffect> encoded;
    private List<ItemEntity> preparedDrops;
    private List<NativeItemEntitySnapshot> dropImages;
    private NativeSurvivalWorld.CommitReceipt accepted;
    private boolean emitted, dropCallbacks, finished;

    private NativeBlockBreakPlan(NativeEffectDeliveryHost host, NativeWholeAction action,
            CubeCell target, CubeSnapshot beforeCube, NativeMiningControlPreparation.DestroyInvocation miningInvocation) {
        this.miningInvocation=miningInvocation;
        this.host = host; this.action = action; this.player = host.player(); this.session = host.session();
        imageAllowance = host.blockProofImagesFor(action);
        this.target = target; this.beforeCube = beforeCube;imageAllowance.retainBeforeCube(beforeCube); commitBefore = action.world().commitSequence();
        requireAction(NativeWholeAction.State.PREPARING); requireRouter();
        if (action.world().usage().transactionWrites() != 0 || action.world().usage().transactionEffects() != 1
                || !beforeCube.position().equals(target.cube()) || !beforeCube.world().equals(action.world().world())
                || !beforeCube.ownerEpoch().equals(action.world().ownerEpoch()))
            throw refused("Native break must begin from its exact untouched target cube");
        before = action.read(target); beforeStateId = action.world().palette().id(before);
        if (beforeCube.blocks().get(target.localIndex()) != beforeStateId)
            throw refused("Native break initial target differs from retained cube");
        NativeInventorySnapshot.requireResolvedMenu(player);
        inventoryChanges = player.getInventory().getTimesChanged();
        admission = NativeBlockBreakAdmission.capture(player, before);
        minedBefore = player.getStats().getValue(Stats.BLOCK_MINED.get(before.getBlock()));
        BlockPos pos = NativeVanillaPreparation.nativePos(target);
        requireMetadataCoordinates(target);
        // Exact bounded native shape neighborhood; only target mutation may survive preparation.
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
            NativeBlockBreakAdmission.requireNeighbor(action.read(CubeCell.of(
                    Math.addExact(target.x(), dx), Math.addExact(target.y(), dy), Math.addExact(target.z(), dz))));
        var nativePreparation = new NativeVanillaPreparation(action.world().palette(), admission);
        if (!nativePreparation.canReach(player, target) || player.blockActionRestricted(host.level(), pos, GameType.SURVIVAL))
            throw refused("Native break target is outside exact survival reach/permission");
        progress = nativePreparation.destroyProgress(player, host.level(), target);
        if (progress <= 0) throw refused("Native target cannot be destroyed in this profile");
        // The source-qualified empty event closure is called exactly once. No cancellation packet can be reached.
        if (CommonHooks.fireBlockBreak(host.level(), GameType.SURVIVAL, player, pos, before).isCanceled())
            throw refused("Native block-break event unexpectedly cancelled");
        var mining = nativePreparation.mining(player, target, action.random());
        if(mining.loot().size()!=1)throw refused("Original initial grass/dirt graph must produce exactly one native drop");
        if (mining.state() != before || !mining.harvestable() || mining.tool().awardsItemUsed()
                || mining.tool().durabilityChanged() || mining.tool().broken()
                || !mining.tool().before().isEmpty() || !mining.tool().after().isEmpty())
            throw refused("Initial bare-hand native break acquired unadmitted harvest/tool effects");
        var outcome = new ArrayList<NativeEffectCodec.Effect>();
        metadata = new NativeEffectCodec.BlockBreakMetadata(action.world().world(), target, player.getUUID(), beforeStateId, progress);
        outcome.add(metadata); action.stage(metadata);
        // Original native server removal selects AIR from the actual empty fluid and uses flags 3.
        if (!before.onDestroyedByPlayer(host.level(), pos, player, mining.harvestable(), before.getFluidState()))
            throw refused("Native onDestroyedByPlayer did not remove its target");
        before.getBlock().destroy(host.level(), pos, before); // Audited inherited native no-op, invoked once.
        if (!action.read(target).is(Blocks.AIR) || action.world().usage().transactionWrites() != 1
                || action.world().usage().transactionEffects() != 3)
            throw refused("Native removal needs additional writes, ticks or metadata outside this first proof");
        outcome.add(new NativeEffectCodec.BlockNotification(action.world().world(), target, beforeStateId,
                action.world().palette().id(Blocks.AIR.defaultBlockState()), 3, 512));
        var stat = new NativeEffectCodec.PlayerStat(BuiltInRegistries.STAT_TYPE.getKey(Stats.BLOCK_MINED),
                BuiltInRegistries.BLOCK.getKey(before.getBlock()), 1);
        var exhaustion = new NativeEffectCodec.PlayerExhaustion(0.005F); // Pinned inherited Block.playerDestroy.
        outcome.add(stat); action.stage(stat); outcome.add(exhaustion); action.stage(exhaustion);
        for (int i = 0; i < mining.loot().size(); i++) {
            var stack = mining.loot().get(i);
            PinnedNativeEventAdmission.requireOrdinaryItem(stack.materialize(host.level().registryAccess()));
            var random = action.random();
            // Exact Block.popResource and ItemEntity(Level,pos,stack) math, using the accepted detached stream.
            double x = target.x() + 0.5 + Mth.nextDouble(random, -0.25, 0.25);
            double y = target.y() + 0.5 + Mth.nextDouble(random, -0.25, 0.25) - EntityType.ITEM.getHeight() / 2.0;
            double z = target.z() + 0.5 + Mth.nextDouble(random, -0.25, 0.25);
            double vx = random.nextDouble() * 0.2 - 0.1, vz = random.nextDouble() * 0.2 - 0.1;
            UUID id = UUID.nameUUIDFromBytes(("native-server-break-v1:" + action.identity() + ":" + i).getBytes(StandardCharsets.UTF_8));
            var spawn = new NativeEffectCodec.ItemSpawn(id, stack, x, y, z, vx, 0.2, vz, 0, 10);
            outcome.add(spawn); action.stage(spawn);
        }
        expected = List.copyOf(outcome);
        requireLiveBefore();
    }
    static NativeBlockBreakPlan prepare(NativeEffectDeliveryHost host, NativeWholeAction action,
            CubeCell target, CubeSnapshot beforeCube) {
        try { return new NativeBlockBreakPlan(host, action, target, beforeCube, null); }
        catch (RuntimeException | Error failure) {
            if (action.state() == NativeWholeAction.State.PREPARING && host.actions().owns(action)) action.poisonPreparation();
            throw failure;
        }
    }
    static NativeBlockBreakPlan prepareFromMining(NativeEffectDeliveryHost host, NativeWholeAction action,
            CubeCell target, CubeSnapshot beforeCube, NativeMiningControlPreparation.DestroyInvocation invocation) {
        invocation.requirePreparation(host,action,target);
        try {
            var result=new NativeBlockBreakPlan(host,action,target,beforeCube,invocation);
            invocation.bind(result);return result;
        } catch(RuntimeException|Error failure) {
            if(action.state()==NativeWholeAction.State.PREPARING&&host.actions().owns(action))action.poisonPreparation();throw failure;
        }
    }
    net.minecraft.stats.Stat<?> minedStatistic(){return Stats.BLOCK_MINED.get(before.getBlock());}
    boolean readyForFinalVerification(NativeSurvivalWorld.CommitReceipt receipt){return accepted==receipt&&emitted&&dropCallbacks&&!finished;}
    int effectCount(){return expected.size();}
    boolean miningPreparationDetached(NativeSurvivalWorld.CommitReceipt receipt) {
        return finished(receipt)&&(miningInvocation==null||miningInvocation.terminalFor(this,receipt));
    }
    boolean ownsBeforeCheckpointCube(io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.Cube cube) {
        return cube!=null && cube.blocks()==beforeCube.blocks() && cube.simulationRevision()==beforeCube.simulationRevision()
                && cube.durableRevision()==beforeCube.durableRevision();
    }
    CubeCell target() { return target; }
    int beforeStateId() { return beforeStateId; }
    float observedDestroyProgress() { return progress; }
    NativeEffectCodec.BlockBreakMetadata metadata() { return metadata; }
    List<ItemEntity> drops() { return preparedDrops; }
    NativeEffectDeliveryHost.BlockProofImageSlot imageSlot() {return imageAllowance;}
    List<NativeItemEntitySnapshot> dropImages() { return dropImages; }
    boolean finished(NativeSurvivalWorld.CommitReceipt receipt) { return finished && accepted == receipt && imageAllowance.hasReceipt(receipt); }

    void requireBeforeCommit(NativeWholeAction exact, List<NativeEffectCodec.Effect> effects) {
        if (exact != action) throw refused("Foreign native break action");
        requireAction(NativeWholeAction.State.PREVALIDATING); requireLiveBefore(); admission.revalidate();
        var breakEffects=miningInvocation==null?effects:miningInvocation.requireCompound(this,action,effects);
        if (accepted != null || encoded != null || breakEffects.size() != expected.size() + 1
                || !(breakEffects.getFirst() instanceof NativeEffectCodec.RandomSeed seed)
                || !seed.world().equals(action.world().world()) || !seed.ownerEpoch().equals(action.world().ownerEpoch())
                || !seed.actor().equals(player.getUUID()) || seed.sequence() != action.identity().sequence()
                || !breakEffects.subList(1, breakEffects.size()).equals(expected)
                || action.world().usage().transactionWrites() != 1 || !action.world().read(target).is(Blocks.AIR))
            throw refused("Native break outcome differs from its privately prepared full effect image");
        encoded = effects.stream().map(NativeEffectCodec::encode).toList();
    }
    /** Host allocated real detached entities from this exact loot. The native empty drop event executes once precommit. */
    void admitPreparedDrops(List<Entity> allocated) {
        requireAction(NativeWholeAction.State.PREVALIDATING); admission.revalidate();
        if (preparedDrops != null || allocated.size() != 1)
            throw refused("Native break single-drop allocation was repeated or changed");
        Entity entity=allocated.getFirst();
        if(entity.getClass()!=ItemEntity.class||entity.isAddedToLevel())
            throw refused("Native break expected one detached ordinary item owner");
        var item=(ItemEntity)entity;var items=new ArrayList<ItemEntity>(1);items.add(item);
        var image=imageAllowance.captureRetained(item);
        var event = new BlockDropsEvent(host.level(), NativeVanillaPreparation.nativePos(target), before,
                null, items, player, ItemStack.EMPTY);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled() || event.getDroppedExperience() != 0 || event.getDrops() != items
                || items.size()!=1 || items.getFirst()!=item || !imageAllowance.matches(item,image))
            throw refused("Native drop event escaped its empty single-drop zero-experience closure");
        preparedDrops = List.of(item); dropImages = List.of(image);
    }
    void requireBeforeApply(NativeWholeAction exact, NativeSurvivalWorld.CommitReceipt receipt) {
        if (exact != action) throw refused("Foreign native break delivery action");
        requireAction(NativeWholeAction.State.APPLYING); requireLiveBefore(); admission.revalidate();
        if (accepted != null || encoded == null || preparedDrops == null || receipt.commitSequence() != Math.addExact(commitBefore, 1)
                || receipt.commitSequence() != action.world().commitSequence() || !receipt.effects().equals(encoded)
                || !receipt.action().equals(action.identity()) || !receipt.world().equals(action.world().world())
                || !receipt.ownerEpoch().equals(action.world().ownerEpoch()) || !receipt.changedCubes().keySet().equals(Set.of(target.cube())))
            throw refused("Native break lacks its exact accepted delivery receipt");
        var changed = receipt.changedCubes().get(target.cube());
        if (changed.incarnation() != beforeCube.incarnation() || changed.simulationRevision() != Math.addExact(beforeCube.simulationRevision(), 1)
                || !changed.blocks().equals(beforeCube.blocks().withEdits(Map.of(target.localIndex(), action.world().palette().id(Blocks.AIR.defaultBlockState())))))
            throw refused("Native break receipt changed anything beyond its exact target");
        if(miningInvocation!=null)miningInvocation.requireAccepted(this,action,receipt);
        imageAllowance.acceptReceipt(receipt);
        accepted = receipt; // Retained even when subsequent delivery fails. Never reset/retry.
    }
    /** Called before stat/exhaustion/drop application. Original native metadata callback runs once. */
    void emitDestructionMetadata(NativeSurvivalWorld.CommitReceipt receipt) {
        requireAction(NativeWholeAction.State.APPLYING);
        if (accepted != receipt || emitted || finished) throw refused("Native break metadata delivery repeated or foreign");
        emitted = true;
        if (before.getBlock().playerWillDestroy(host.level(), NativeVanillaPreparation.nativePos(target), before, player) != before)
            throw refused("Native playerWillDestroy changed its source-qualified state");
    }
    void finishDropCallbacks(NativeSurvivalWorld.CommitReceipt receipt) {
        requireAction(NativeWholeAction.State.APPLYING);
        if (accepted != receipt || !emitted || dropCallbacks || finished) throw refused("Native break drop callback repeated or foreign");
        dropCallbacks = true; // Attempt latch remains retained if the original callback fails.
        // Native CommonHooks.handleBlockDrops calls this after inserting its drops and with dropExperience=false.
        before.spawnAfterBreak(host.level(), NativeVanillaPreparation.nativePos(target), ItemStack.EMPTY, false);
    }
    void verifyAfter(NativeSurvivalWorld.CommitReceipt receipt) {
        requireAction(NativeWholeAction.State.APPLYING);
        if (accepted != receipt || !emitted || !dropCallbacks || finished) throw refused("Native break completion repeated or foreign");
        NativeBlockBreakAdmission.requireEmptyInventory(player); NativeInventorySnapshot.requireResolvedMenu(player);
        if (player.getInventory().getTimesChanged() != inventoryChanges
                || player.getStats().getValue(Stats.BLOCK_MINED.get(before.getBlock())) != Math.addExact(minedBefore, 1)
                || !imageAllowance.matches(preparedDrops.getFirst(),dropImages.getFirst()))
            throw refused("Native break final inventory/stat/drop image differs from its prepared native outcome");
        if(miningInvocation!=null)encoded=receipt.effects(); // Drop the separately encoded duplicate before its mining reservation drains.
        finished = true;
    }
    void requireUnchangedDrop(ItemEntity exact) {
        if(preparedDrops==null||preparedDrops.size()!=1||preparedDrops.getFirst()!=exact
                ||!imageAllowance.matches(exact,dropImages.getFirst()))
            throw refused("Native break retained drop identity/image changed");
    }
    private void requireLiveBefore() {
        NativeBlockBreakAdmission.requireEmptyInventory(player); NativeInventorySnapshot.requireResolvedMenu(player);
        if (host.player() != player || player.serverLevel() != host.level() || !session.equals(host.session())
                || player.getInventory().getTimesChanged() != inventoryChanges
                || player.getStats().getValue(Stats.BLOCK_MINED.get(before.getBlock())) != minedBefore)
            throw refused("Native break before-image/session changed");
    }
    private void requireAction(NativeWholeAction.State state) {
        if (NativeWholeAction.current() != action || !host.actions().owns(action) || action.state() != state)
            throw refused("Private native break action is foreign, stale or reentered");
    }
    private void requireRouter() {
        var binding = SurvivalLevelBinding.find(host.level());
        if (binding == null || binding.world() != action.world() || binding.actions() != host.actions()
                || NativeInventoryMenuSnapshot.read(SurvivalLevelBinding.class, binding, "mutations", SurvivalLevelBinding.MutationRouter.class).getClass()
                    != NativeDryMutationRouter.class)
            throw refused("Native break requires the exact admitted dry mutation router");
        ((NativeDryMutationRouter) NativeInventoryMenuSnapshot.read(SurvivalLevelBinding.class,binding,"mutations",
                SurvivalLevelBinding.MutationRouter.class)).requireOwnedBlockProofHost(host);
    }
    static void requireMetadataCoordinates(CubeCell target) {
        if (target.x() < -33554432 || target.x() > 33554431 || target.z() < -33554432 || target.z() > 33554431
                || target.y() < -2048 || target.y() > 2047)
            throw refused("Native 2001 event position exceeds the lossless native packet domain");
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
