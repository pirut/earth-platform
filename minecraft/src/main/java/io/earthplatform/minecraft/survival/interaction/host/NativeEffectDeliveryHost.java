package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.persistence.NativeItemEntitySnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeSessionExecutionGate;
import io.earthplatform.minecraft.survival.persistence.NativeSessionAdmission;
import io.earthplatform.minecraft.survival.persistence.SurvivalSessionTransitions;
import java.util.concurrent.Executor;
import io.earthplatform.minecraft.survival.persistence.NativeFoodSnapshot;
import io.earthplatform.minecraft.survival.persistence.NativePlayerPoseSnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint;
import io.earthplatform.minecraft.survival.persistence.SurvivalCheckpointCoordinator;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeActionEffect;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Bounded native delivery, not a replacement entity/tick engine. Actual ServerLevel.addFreshEntity,
 * native Inventory, native scheduled ticks, awardStat, playSeededSound and gameEvent own the resulting state.
 * Ordinary effect decisions and detached allocations precede the cube commit. The separately
 * admitted effect-only ItemTick command executes native dry physics once after acceptance under
 * its private sealed-read capability; it is never cloned, replayed or silently generalized. Native callbacks must
 * be absent except the two audited built-in ordinary-item join handlers; native game-event
 * listeners must also be absent in range.
 *
 * <p>Exact vanilla ItemEntity allocation uses native entity-local seed uniquifiers
 * and entity IDs, which may have gaps on abort. It never consumes Level.random, native random
 * sequences, loot RNG or the player's RNG. Conserved UUID/position/velocity/stack fields come only
 * from the prepared effect. This is not compatibility with arbitrary mods or async registration.
 *
 * <p>This internal slice installs pickup and one-item dry tick/landing ownership. General gameplay
 * wrappers, native entity save exclusion, merge/expiry cohort capture and scheduled-tick execution
 * remain required before enabling a live session. Pickup capture defers collect/menu cosmetics and
 * admits only sources without Owner/Thrower criteria. Consumed owners remain retained until their
 * exact bound-coordinator durable capture is acknowledged and explicitly retired.
 */
public final class NativeEffectDeliveryHost implements NativeWholeAction.EffectHost {
    // Discovery only. Exact binding -> Owner -> host owns the state; this does not own entities.
    private static final Map<ServerLevel, WeakReference<NativeEffectDeliveryHost>> ATTACHED = new WeakHashMap<>();
    public static NativeEffectDeliveryHost findAttached(ServerLevel level) {
        synchronized (ATTACHED) {
            WeakReference<NativeEffectDeliveryHost> reference = ATTACHED.get(level);
            NativeEffectDeliveryHost host = reference == null ? null : reference.get();
            return host != null && host.binding != null && SurvivalLevelBinding.find(level) == host.binding
                    && !host.binding.isDetached() ? host : null;
        }
    }

    public record Limits(int entityOwners, int scheduledTickOwners, int metadataEffects, int audience) {
        public Limits {
            if (entityOwners < 1 || entityOwners > 4096 || scheduledTickOwners < 1 || scheduledTickOwners > 4096
                    || metadataEffects < 1 || metadataEffects > 4096 || audience < 1 || audience > 64)
                throw new IllegalArgumentException("Explicit bounded native delivery limits required");
        }
    }
    public record Usage(int retainedEntityOwners, int pendingEntityOwners, int retainedTickOwners,
                        int pendingTickOwners, boolean failedDelivery) {}
    private static final class EntityOwner {
        final Entity entity;
        PickupConsumption consumed;
        EntityOwner(Entity entity) { this.entity = entity; }
    }
    private record RetirementCandidate(EntityOwner owner, PickupConsumption consumption) {}
    private static final class CheckpointCapture {
        final long commitSequence, generation;
        final NativeSurvivalCheckpoint.Session session;
        final Map<UUID, RetirementCandidate> consumed;
        NativeSurvivalCheckpoint.Boundary boundary;
        NativeSurvivalWorld.CheckpointTicket ticket;
        CheckpointCapture(long commitSequence, long generation, NativeSurvivalCheckpoint.Session session,
                Map<UUID, RetirementCandidate> consumed) {
            this.commitSequence = commitSequence; this.generation = generation; this.session = session;
            this.consumed = Map.copyOf(consumed);
        }
    }
    private record PickupConsumption(NativeSurvivalWorld.CommitReceipt receipt, NativeEffectCodec.ItemPickup effect) {}
    private static final class PickupPlan {
        final NativeWholeAction action;
        final ItemEntity source;
        final NativeItemPickupPreparation.Result result;
        PickupPlan(NativeWholeAction action, ItemEntity source, NativeItemPickupPreparation.Result result) {
            this.action = action; this.source = source; this.result = result;
        }
    }
    private record PreparedPickup(PickupPlan plan, ItemStack remainder) {}
    private record InventoryChange(int slot, ItemStack after) {}
    private record Statistic(Stat<?> stat, int before, int amount) {}
    private record PreparedSound(Holder<SoundEvent> sound, SoundSource source, NativeEffectCodec.Sound data, ServerPlayer excluded) {}
    private record PreparedEvent(Holder<GameEvent> event, BlockPos pos, Entity source, BlockState affectedState) {}

    /** Native object references are read-only inspection hooks, not authority to mutate or retry. */
    public static final class Capture {
        private final NativeEffectDeliveryHost host;
        private final long commitSequence, gameTime;
        private final List<ItemEntity> items;
        private final List<NativeEffectCodec.ScheduledBlockTick> ticks;
        private Capture(NativeEffectDeliveryHost host, long commitSequence, long gameTime,
                List<ItemEntity> items, List<NativeEffectCodec.ScheduledBlockTick> ticks) {
            this.host = host; this.commitSequence = commitSequence; this.gameTime = gameTime;
            this.items = List.copyOf(items); this.ticks = List.copyOf(ticks);
        }
        public UUID session() { return host.session; }
        public NativeWholeAction.Owner actions() { return host.actions; }
        public NativeSurvivalWorld world() { return host.world; }
        public ServerPlayer player() { return host.player; }
        public long commitSequence() { return commitSequence; }
        public long gameTime() { return gameTime; }
        public List<ItemEntity> items() { return items; }
        public List<NativeEffectCodec.ScheduledBlockTick> ticks() { return ticks; }
    }

    private final ServerLevel level;
    private final ServerPlayer player;
    private final UUID actor;
    private UUID session;
    private final NativeSurvivalWorld world;
    private final HolderLookup.Provider registries;
    private final Limits limits;
    private final NativeSurvivalCheckpoint.Limits checkpointLimits;
    private NativeInventorySnapshot ordinaryEscrow;
    private NativeFoodSnapshot ordinaryFoodEscrow;
    private NativePlayerPoseSnapshot ordinaryPoseEscrow;
    private int availableNativePayloadBytes;
    private final int requestedNativePayloadBytes;
    private final NativeSessionExecutionGate transitionGate;
    private NativeSurvivalCheckpoint.Boundary initialTransitionBoundary;
    private TransitionReceipt transitionReceipt;
    private SurvivalSessionTransitions.CheckpointRequest transitionRequest;
    private boolean ownedTransitionCapture;
    private final Object recipeSource, lootRegistrySource, connection;
    private final PinnedNativeEventAdmission events;
    private final NativeDeliveryReadAccess nativeState;
    private final NativeWholeAction.Owner actions;
    // These are retained references to the native owners, never a substitute simulation directory.
    private final Map<UUID, EntityOwner> entityOwners = new LinkedHashMap<>();
    private final NativeScheduledTickQueue scheduledTicks;
    private SurvivalCheckpointCoordinator checkpointCoordinator;
    private NativeSurvivalCheckpoint.Binding checkpointBinding;
    private CheckpointCapture checkpointCapture;
    private NativeWholeAction current;
    private NativeStackSnapshot[] inventoryBefore;
    private NativeFoodSnapshot foodBefore;
    private NativePlayerPoseSnapshot poseBefore;
    private int selectedBefore;
    private SurvivalLevelBinding binding;
    private Batch pending;
    private PickupPlan pickup;
    private ItemTickPlan itemTick;
    private final NativeSessionMenuOwner sessionMenuOwner = new NativeSessionMenuOwner(this);
    private NativeSessionMenuBoundary sessionMenuBefore;
    private NativeFirstLoopMetadata sessionMenuMetadata;
    private NativeMenuBudget sessionMenuBudget;
    private NativeMenuPacketPlan menuPacket;
    private NativeMenuClosePlan menuClose;
    private NativeMenuOpenPlan menuOpen;
    private SessionMenuBatch pendingMenu;
    private MenuOpening menuOpening;
    private PlayerStepPlan playerStep;
    private PlayerMovePermit movePermit;
    private NativePlayerStepBudget moveBudget;
    private NativeResolvedInventoryMenuPlan menuScript;
    private NativeResolvedCraftingTablePlan tableScript;
    private NativeDryPlacementPlan dryPlacement;
    private boolean preparingPlacement;
    private NativeSurvivalWorld.CommitReceipt lastApplied;

    // Narrow server proof authority. These fields never open the generic native execution gate.
    private BlockProofOpening blockProofOpening;
    private NativeBlockBreakPlan blockBreak;
    private NativeAcceptedBlockBreak lastAcceptedBreak;
    private AcceptedBreakLineage acceptedBreakLineage;
    private final List<BlockProofImageSlot> blockProofImageSlots = new ArrayList<>(2);
    // No runtime toggle is introduced. Parent may change this compile-time latch only after the
    // separate original Util.<clinit>/scheduler/mixin probes pass on the exact integrated sources.
    private static final boolean NATIVE_MINING_CONTROLS_QUALIFIED = false;
    // Retained logical payload: one decoded transcript <=670, its receipt encoding <=1024,
    // one later lastApplied control encoding <=1024, and <=1378 for owner/terminal vectors.
    // The mining break plan aliases receipt.effects after verification, never retains a second encoding.
    private static final int MINING_LINEAGE_BYTES = 4 * NativeMiningControlTranscript.MAX_EFFECT_BYTES;
    private NativeMiningControlPreparation.Owner miningOwner; // Constructed only after the private reservation debits its lineage slot.
    private MiningReservation miningReservation;
    private boolean miningLineageCharged;
    /** Private Host-minted reservation. Neither a byte count nor a decoded effect can acquire it. */
    final class MiningReservation {
        private NativeWholeAction action;
        private BlockProofImageSlot images;
        private boolean poisoned;
        private Map<CubePos,io.earthplatform.minecraft.survival.world.CubeSnapshot> snapshots;
        private boolean snapshotsSealed, prevalidationEntered;
        private MiningReservation(Map<CubePos,io.earthplatform.minecraft.survival.world.CubeSnapshot> snapshots) {
            this.snapshots=Map.copyOf(snapshots);
        }
        private void snapshotsInternal(Map<CubePos,io.earthplatform.minecraft.survival.world.CubeSnapshot> values) {
            if(miningReservation!=this||snapshotsSealed||action!=null||poisoned)throw new SurvivalRefusal("Mining snapshot reservation repeated");
            snapshots=Map.copyOf(values);snapshotsSealed=true;
        }
        private void enterPrevalidation() {
            if(prevalidationEntered||poisoned||action==null||current!=action||NativeWholeAction.current()!=action
                    ||!actions.owns(action)||action.state()!=NativeWholeAction.State.PREVALIDATING) {
                poisoned=true;poisonNestedOwnedPreparation();throw new SurvivalRefusal("Native mining prevalidation reentered or changed its action");
            }
            prevalidationEntered=true;
        }
        void require(NativeMiningControlPreparation.Owner exact,NativeWholeAction requested) {
            world.checkOwner();
            if(miningReservation!=this||exact!=miningOwner||poisoned||action==null||requested!=action
                    ||current!=action||!actions.owns(action)||NativeWholeAction.current()!=action)
                throw new SurvivalRefusal("Mining lacks its exact Host-debited reservation/action");
        }
        io.earthplatform.minecraft.survival.world.CubeSnapshot snapshot(CubePos cube) {
            require(miningOwner,action);var value=snapshots.get(cube);
            if(value==null)throw new SurvivalRefusal("Native mining target escaped its prepaid snapshot footprint");return value;
        }
    }
    private void refuseMiningReentry(String operation) {
        if(miningReservation!=null) {
            miningReservation.poisoned=true;poisonNestedOwnedPreparation();
            throw new SurvivalRefusal("Native mining cannot reenter "+operation+", even if refusal is caught");
        }
        if(miningOwner!=null)miningOwner.refuseForeignLane(operation);
    }
    /** The separate mining lane never opens generic requireActionOpen or a broad player tick. */
    public void requireNativeMiningGate(NativeSessionExecutionGate exact) {
        if(exact!=transitionGate||miningReservation==null||miningReservation.poisoned)
            throw new SurvivalRefusal("Native mining gate lacks its private Host opening");
        exact.requireClaim(this);checkTransitionIdentity();requireBinding();requireCheckpointCoordinator();
        var durable=checkpointCoordinator.durable().orElse(null);
        if(durable==null||durable.session().phase()!=NativeSurvivalCheckpoint.SessionPhase.ACTIVE
                ||!durable.session().id().equals(session)||checkpointCoordinator.state()!=SurvivalCheckpointCoordinator.State.IDLE
                ||checkpointCapture!=null||world.hasPendingCheckpoint()||movePermit!=null||blockProofOpening!=null
                ||sessionMenuOwner.retained()||pendingMenu!=null||menuOpening!=null||menuScript!=null||tableScript!=null
                ||pickup!=null||itemTick!=null||dryPlacement!=null||preparingPlacement||limits.audience()!=1)
            throw new SurvivalRefusal("Native mining requires the exact settled idle ACTIVE first profile");
        if(actions.fenced()&&(current==null||current.state()!=NativeWholeAction.State.APPLYING||pending==null
                ||!pending.nativeMining||actions.pendingDelivery()==null||actions.pendingDelivery().receipt().action()!=current.identity()))
            throw new SurvivalRefusal("Native mining is fenced outside its exact accepted delivery");
    }
    public static boolean dispatchNativeMiningPacket(net.minecraft.server.network.ServerGamePacketListenerImpl receiver,
            ServerPlayer source,net.minecraft.network.protocol.game.ServerboundPlayerActionPacket packet) {
        if(!NATIVE_MINING_CONTROLS_QUALIFIED)return false;
        if(source==null||receiver==null||source.connection!=receiver||receiver.player!=source)
            throw new SurvivalRefusal("Bound mining receiver/player identity changed before scheduling");
        var gate=NativeSessionExecutionGate.find(source);if(gate==null)return false;
        if(source.getClass()!=ServerPlayer.class||receiver.getClass()!=net.minecraft.server.network.ServerGamePacketListenerImpl.class)
            throw new SurvivalRefusal("Bound mining receiver/player implementation changed before scheduling");
        if(packet==null||packet.getClass()!=net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.class)
            throw new SurvivalRefusal("Bound mining packet class changed before scheduling");
        switch(packet.getAction()){case START_DESTROY_BLOCK,STOP_DESTROY_BLOCK,ABORT_DESTROY_BLOCK -> {} default -> {return false;}}
        // Preserve vanilla's Netty-to-owner handoff before any owner-only gate/native inspection.
        net.minecraft.network.protocol.PacketUtils.ensureRunningOnSameThread(packet,receiver,source.serverLevel());
        if(!NativeSessionExecutionGate.acceptsPlayerMove(source))return false;
        var host=gate.miningHost();if(host==null)return false;
        host.dispatchNativeMining(receiver,packet);return true;
    }
    public static boolean dispatchNativeMiningPulse(net.minecraft.server.network.ServerGamePacketListenerImpl receiver,ServerPlayer source) {
        if(!NATIVE_MINING_CONTROLS_QUALIFIED)return false;
        var gate=NativeSessionExecutionGate.find(source);if(gate==null||!NativeSessionExecutionGate.acceptsPlayerMove(source))return false;
        var host=gate.miningHost();if(host==null)return false;
        NativeMiningScheduledOrigin.requireCurrent(source);host.dispatchNativeMining(receiver,null);return true;
    }
    private void dispatchNativeMining(net.minecraft.server.network.ServerGamePacketListenerImpl receiver,
            net.minecraft.network.protocol.game.ServerboundPlayerActionPacket packet) {
        world.checkOwner();
        if(miningReservation!=null)refuseMiningReentry("mining control dispatch");
        if(movePermit!=null)throw movePermit.reject("Native mining overlaps MOVE authority");
        refuseActiveSaveReentry();refuseMenuOpeningReentry();refuseBlockProofReentry();
        if(actions.hasCurrentAction()||NativeWholeAction.hasCurrentOrOpeningAction()) {
            poisonNestedOwnedPreparation();throw new SurvivalRefusal("Native mining cannot overlap another action opening");
        }
        // Sticky private opening and full debit precede all callback-capable identity/census/capture work.
        var reservation=new MiningReservation(Map.of());miningReservation=reservation;
        miningLineageCharged=true;
        try {
            if(nativePayloadAllowance()<1||blockProofImageSlots.size()>=2)
                throw new SurvivalRefusal("Native mining shared reservation or retained drop-image slots exhausted");
            if(miningOwner==null)miningOwner=new NativeMiningControlPreparation.Owner(this);
            requireNoRetainedActiveSaveForAction();
            checkNativeIdentity();reapAborted();requireBinding();requireCheckpointCoordinator();
            if(receiver!=player.connection||receiver.player!=player||transitionGate==null||current!=null||pending!=null||actions.fenced())
                throw new SurvivalRefusal("Native mining requires exact idle registered receiver");
            if(packet!=null)NativeMiningControlJournal.requirePacket(packet);else NativeMiningScheduledOrigin.requireCurrent(player);
            NativeBlockBreakAdmission.requireTrustedAudiences(player);NativeBlockBreakAdmission.requireEmptyInventory(player);
            NativeInventorySnapshot.requireResolvedMenu(player);NativePlayerMoveSnapshotAdmission.require(player);
            io.earthplatform.minecraft.survival.source.MarinPlayerEnvironmentV2.requireInstalledPolicy(level,world.world());
            NativeSessionExecutionGate.requireNativeMiningOpen(player,this);
            miningOwner.requireOpeningLineage();
            var mode=NativeMiningControlState.Mode.capture(player.gameMode);
            var targets=new ArrayList<CubeCell>(3);
            if(mode.destroying())targets.add(mode.target());
            if(mode.delayed()&&!targets.contains(mode.delayedTarget()))targets.add(mode.delayedTarget());
            if(packet!=null&&!targets.contains(NativeMiningControlState.cell(packet.getPos())))targets.add(NativeMiningControlState.cell(packet.getPos()));
            var snapshots=new LinkedHashMap<CubePos,io.earthplatform.minecraft.survival.world.CubeSnapshot>();
            var footprints=new ArrayList<CubeCell>(targets);
            footprints.add(CubeCell.of((long)Math.floor(player.getX()),(long)Math.floor(player.getBoundingBox().minY)-1,(long)Math.floor(player.getZ())));
            for(var target:footprints) {
                NativeMiningControlJournal.requireWireCell(target);
                for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++) {
                    var cube=CubeCell.of(Math.addExact(target.x(),dx),Math.addExact(target.y(),dy),Math.addExact(target.z(),dz)).cube();
                    if(!snapshots.containsKey(cube)) {
                        if(snapshots.size()>=world.limits().transactionCubes()||world.presence(cube)!=NativeSurvivalWorld.Presence.RESIDENT)
                            throw new SurvivalRefusal("Native mining lacks complete resident target/read footprint");
                        snapshots.put(cube,world.snapshot(cube));
                    }
                }
            }
            reservation.snapshotsInternal(snapshots);
            var expected=new LinkedHashMap<CubePos,NativeSurvivalWorld.CubeStamp>();snapshots.forEach((cube,image)->expected.put(cube,image.stamp()));
            inventoryBefore=snapshotInventory();selectedBefore=player.getInventory().selected;
            foodBefore=NativeFoodSnapshot.capture(player.getFoodData());poseBefore=NativePlayerPoseSnapshot.capture(player);
            // A caught nested callback during the last scalar capture must fail before sequence consumption.
            if(miningReservation!=reservation||reservation.poisoned||reservation.action!=null
                    ||actions.hasCurrentAction()||NativeWholeAction.hasCurrentOrOpeningAction())
                throw new SurvivalRefusal("Native mining opening changed during final before-image capture");
            var identity=world.actionIdentity(actor,world.nextActionSequence(actor));
            NativeBlockBreakPlan completed=null;NativeSurvivalWorld.CommitReceipt receipt;
            try(var action=actions.begin(identity,expected,NativeWholeAction.inventoryOf(player))) {
                current=action;reservation.action=action;
                var images=new BlockProofImageSlot(action);blockProofImageSlots.add(images);reservation.images=images;
                if(packet==null)miningOwner.preparePulse(action,reservation);else miningOwner.preparePacket(action,packet,reservation);
                completed=blockBreak;receipt=action.commit();
            }
            if(completed!=null) {
                if(!completed.finished(receipt)||lastApplied!=receipt)throw new SurvivalRefusal("Native mining break delivery did not finish");
                var proof=new NativeAcceptedBlockBreak(receipt,completed.target(),completed.beforeStateId(),completed.observedDestroyProgress(),
                        completed.drops().stream().map(ItemEntity::getUUID).toList());
                acceptedBreakLineage=new AcceptedBreakLineage(proof,completed,world.stamp(completed.target().cube()));lastAcceptedBreak=proof;
            }
        } catch(RuntimeException|Error failure) {
            if(actions.fenced())NativeSessionExecutionGate.failNativeMining(player,this);
            throw failure;
        } finally {
            if(!actions.fenced()) {
                reapAborted();miningReservation=null;releaseUnownedBlockProofImages();
                if(miningOwner==null)miningLineageCharged=false;
                if(current==null){inventoryBefore=null;foodBefore=null;poseBefore=null;}
            }
        }
    }
    NativeBlockBreakPlan prepareMiningBreak(NativeWholeAction action,CubeCell target,
            io.earthplatform.minecraft.survival.world.CubeSnapshot before,NativeMiningControlPreparation.DestroyInvocation invocation,
            MiningReservation reservation) {
        reservation.require(miningOwner,action);invocation.requirePreparation(this,action,target);
        if(blockBreak!=null||reservation.snapshot(target.cube())!=before)throw new SurvivalRefusal("Native mining break request repeated or foreign");
        blockBreak=NativeBlockBreakPlan.prepareFromMining(this,action,target,before,invocation);return blockBreak;
    }
    /** Called BEFORE a transition hold can strand an unfinished delayed native destroy. */
    public boolean miningSettledBeforeHold(NativeSessionExecutionGate gate) {
        if(gate!=transitionGate)throw new SurvivalRefusal("Foreign mining transition gate");
        gate.requireClaim(this);if(!miningLineageCharged&&miningReservation==null)return true;
        if(miningReservation!=null)refuseMiningReentry("save/leave readiness");
        return miningOwner.settledForTransition();
    }
    public void requireMiningSettledBeforeHold(NativeSessionExecutionGate gate) {
        if(gate!=transitionGate)throw new SurvivalRefusal("Foreign mining transition gate");
        gate.requireClaim(this);if(!miningLineageCharged&&miningReservation==null)return;
        if(miningReservation!=null)refuseMiningReentry("save/leave hold");
        miningOwner.requireSettledForTransition();
    }

    private int nativePayloadAllowance() {
        int charged=transitionGate==null?0:NativeActiveSaveBudget.CHAIN_BYTES;
        charged=Math.addExact(charged,durableNativeCharge);
        if(activeSaveBudget!=null)charged=Math.addExact(charged,activeSaveBudget.charged());
        for(var slot:blockProofImageSlots)charged=Math.addExact(charged,Math.addExact(slot.cubeCharge,Math.addExact(slot.retainedCharge,slot.scratchCharge)));
        if(miningLineageCharged)charged=Math.addExact(charged,MINING_LINEAGE_BYTES);
        if(miningReservation!=null) {
            charged=Math.addExact(charged,NativeMiningControlPreparation.RESERVATION_BYTES);
            if(miningReservation.images==null)charged=Math.addExact(charged,NativeBlockBreakPlan.IMAGE_ALLOWANCE_BYTES+BlockProofImageSlot.CUBE_ALIAS_BYTES);
        }
        int remaining=availableNativePayloadBytes-charged;
        if(remaining<1)throw new SurvivalRefusal("Native boundary lacks its retained block-proof image allowance");
        return remaining;
    }
    /** Host-retained, two-slot maximum. It exposes no image or mutable budget to callers. */
    final class BlockProofImageSlot {
        private static final int CUBE_ALIAS_BYTES=64+16*1024;
        private int cubeCharge=CUBE_ALIAS_BYTES; // Prepaid until an independent persistence pin is installed.
        private SurvivalCheckpointCoordinator.DurableCubeLease durableCubeLease;
        private boolean cubeQualified;
        void retainBeforeCube(io.earthplatform.minecraft.survival.world.CubeSnapshot before){
            requireOwned();if(cubeQualified||current!=exactAction)throw new SurvivalRefusal("Repeated or foreign before-cube lease");
            requireCheckpointCoordinator();
            durableCubeLease=checkpointCoordinator.pinCurrentDurableCube(before).orElse(null);
            cubeCharge=durableCubeLease==null?64+before.blocks().payloadBytes():0;cubeQualified=true;
        }
        private void retireCubeLease(){
            if(durableCubeLease!=null){checkpointCoordinator.releaseDurableCubeLeases(List.of(durableCubeLease));durableCubeLease=null;}
        }
        private final NativeWholeAction exactAction;
        private int retainedCharge=NativeBlockBreakPlan.DROP_IMAGE_SLOT_BYTES;
        private int scratchCharge=NativeBlockBreakPlan.DROP_IMAGE_SLOT_BYTES;
        private NativeItemEntitySnapshot retained;
        private int projectedBoundaryBytes=-1;
        private NativeSurvivalWorld.CommitReceipt imageReceipt;
        private boolean captured;
        private BlockProofImageSlot(NativeWholeAction action) {exactAction=action;}
        private void requireOwned() {
            world.checkOwner();
            if(!blockProofImageSlots.contains(this))throw new SurvivalRefusal("Native break image slot was revoked");
        }
        NativeItemEntitySnapshot captureRetained(ItemEntity item) {
            requireOwned();
            if(captured||current!=exactAction||blockBreak==null)
                throw new SurvivalRefusal("Native break retained image is repeated or lacks its exact current plan");
            captured=true; // Failed capture keeps both worst-case charges while this owner remains retained.
            retained=NativeItemEntitySnapshot.capture(item,NativeBlockBreakPlan.DROP_IMAGE_MAX_BYTES);
            retainedCharge=Math.addExact(64,Math.addExact(retained.encodedBytes(),retained.stack().encodedBytes()));
            return retained;
        }
        void admitProjectedBoundary(NativeSurvivalCheckpoint.Boundary boundary) {
            requireOwned();
            if(current!=exactAction||!captured||scratchCharge!=0||projectedBoundaryBytes>=0||imageReceipt!=null)
                throw new SurvivalRefusal("Native break boundary image allowance was repeated or overtaken");
            for(var image:boundary.items())if(image.encodedBytes()>NativeBlockBreakPlan.DROP_IMAGE_MAX_BYTES)
                throw new SurvivalRefusal("Native break projected item exceeds its prepaid comparison image cap");
            int bytes=NativeSurvivalCheckpoint.checkedBoundaryBytes(boundary.inventory(),boundary.food(),boundary.pose(),
                    boundary.items(),boundary.scheduledTicks(),nativePayloadAllowance());
            if((long)bytes+NativeBlockBreakPlan.DROP_IMAGE_SLOT_BYTES>nativePayloadAllowance())
                throw new SurvivalRefusal("Native break projected boundary has no preaccepted comparison scratch headroom");
            projectedBoundaryBytes=bytes;
        }
        void acceptReceipt(NativeSurvivalWorld.CommitReceipt receipt) {
            requireOwned();
            if(current!=exactAction||projectedBoundaryBytes<0||imageReceipt!=null||!receipt.action().equals(exactAction.identity()))
                throw new SurvivalRefusal("Native break image allowance lacks its exact preadmitted receipt lineage");
            imageReceipt=receipt;
        }
        boolean hasReceipt(NativeSurvivalWorld.CommitReceipt receipt) {return imageReceipt==receipt&&projectedBoundaryBytes>=0;}
        boolean matches(ItemEntity item,NativeItemEntitySnapshot expected) {
            requireOwned();
            if(!captured||retained!=expected)throw new SurvivalRefusal("Native break comparison lost its exact retained image");
            return compare(item,expected);
        }
        boolean matchesProjected(ItemEntity item,NativeItemEntitySnapshot expected,NativeSurvivalWorld.CommitReceipt receipt) {
            requireOwned();
            if(current!=exactAction||imageReceipt!=receipt||pending==null||pending.nativeBlockBreak==null
                    ||pending.nativeBlockBreak.imageSlot()!=this||pending.projectedBoundary==null)
                throw new SurvivalRefusal("Native break boundary comparison lacks its exact pending receipt owner");
            boolean exact=false;
            for(var image:pending.projectedBoundary.items())if(image==expected){exact=true;break;}
            if(!exact)throw new SurvivalRefusal("Native break boundary image is outside its preaccepted projected roster");
            return compare(item,expected);
        }
        private boolean compare(ItemEntity item,NativeItemEntitySnapshot expected) {
            if(scratchCharge==0) {
                if((long)Math.max(projectedBoundaryBytes,0)+NativeBlockBreakPlan.DROP_IMAGE_SLOT_BYTES>nativePayloadAllowance())
                    throw new SurvivalRefusal("Native break comparison lacks bounded temporary image headroom");
                scratchCharge=NativeBlockBreakPlan.DROP_IMAGE_SLOT_BYTES;
            }
            NativeItemEntitySnapshot temporary=NativeItemEntitySnapshot.capture(item,NativeBlockBreakPlan.DROP_IMAGE_MAX_BYTES);
            boolean equal=expected.equals(temporary);
            temporary=null; // No temporary image escapes this method; release only after its comparison/alias drains.
            scratchCharge=0;
            return equal;
        }
    }
    private void reserveBlockProofImages(NativeWholeAction action,BlockProofOpening opening) {
        opening.requireCurrent();
        if(current!=action||!actions.owns(action)||opening.images!=null||blockProofImageSlots.size()>=2
                ||nativePayloadAllowance()<=NativeBlockBreakPlan.IMAGE_ALLOWANCE_BYTES+BlockProofImageSlot.CUBE_ALIAS_BYTES)
            throw new SurvivalRefusal("Native block proof lacks its bounded two-slot image admission");
        var slot=new BlockProofImageSlot(action);blockProofImageSlots.add(slot);opening.images=slot;
    }
    BlockProofImageSlot blockProofImagesFor(NativeWholeAction action) {
        world.checkOwner();
        if(miningReservation!=null) {
            miningReservation.require(miningOwner,action);
            if(miningReservation.images==null)throw new SurvivalRefusal("Mining lacks its prepaid drop-image slot");
            return miningReservation.images;
        }
        if(blockProofOpening==null||blockProofOpening.images==null||current!=action
                ||blockProofOpening.images.exactAction!=action)
            throw new SurvivalRefusal("Native block plan lacks its exact prepaid host image slot");
        blockProofOpening.requireCurrent();return blockProofOpening.images;
    }
    private void releaseUnownedBlockProofImages() {
        // Remove aliases first; failed accepted ownership keeps its conservative charge.
        for(var iterator=blockProofImageSlots.iterator();iterator.hasNext();){
            var slot=iterator.next();
            if((blockBreak==null||blockBreak.imageSlot()!=slot)
                &&(acceptedBreakLineage==null||acceptedBreakLineage.plan.imageSlot()!=slot)
                &&(pending==null||pending.nativeBlockBreak==null||pending.nativeBlockBreak.imageSlot()!=slot)
                &&(blockProofOpening==null||blockProofOpening.images!=slot)
                &&(miningReservation==null||miningReservation.images!=slot)) {slot.retireCubeLease();iterator.remove();}
        }
    }
    private void revokeAcceptedBreakLineage() {
        acceptedBreakLineage=null;lastAcceptedBreak=null;
        releaseUnownedBlockProofImages();
    }
    private final class AcceptedBreakLineage {
        final NativeAcceptedBlockBreak proof;
        final NativeBlockBreakPlan plan;
        final UUID exactSession = session;
        final NativeSurvivalWorld.CubeStamp stamp;
        final List<ItemEntity> drops;
        final List<NativeItemEntitySnapshot> images;
        NativeSurvivalWorld.CommitReceipt firstMove, secondMove;
        NativePlayerPoseSnapshot intermediatePose, landedPose;
        NativeFoodSnapshot landedFood;
        boolean moveInvalid;
        void noteDeliveredMove(PlayerStepPlan move,NativeSurvivalWorld.CommitReceipt receipt) {
            if(moveInvalid)return;
            long bytes=256;for(var effect:receipt.effects())bytes+=effect.payloadBytes();
            var before=move.before.pose();var after=move.after.pose();var target=proof.target();
            if(bytes>NativeActiveSaveBudget.CHAIN_BYTES/2 || receipt.effects().size()>2
                    || before.x()!=after.x() || before.z()!=after.z() || after.y()!=before.y()-0.5
                    || Math.floor(before.x())!=target.x() || Math.floor(before.z())!=target.z()
                    || !exactSession.equals(session) || !receipt.changedCubes().isEmpty()) {moveInvalid=true;return;}
            if(firstMove==null && lastApplied==proof.receipt() && before.y()==target.y()+1.0
                    && before.onGround() && !after.onGround()) {
                firstMove=receipt;intermediatePose=after;return;
            }
            if(firstMove!=null && secondMove==null && lastApplied==firstMove && before.equals(intermediatePose)
                    && after.y()==target.y() && after.onGround() && after.fallDistance()==0) {
                secondMove=receipt;landedPose=after;landedFood=move.after.food();return;
            }
            moveInvalid=true;
        }
        AcceptedBreakLineage(NativeAcceptedBlockBreak proof, NativeBlockBreakPlan plan, NativeSurvivalWorld.CubeStamp stamp) {
            this.proof=proof;this.plan=plan;this.stamp=stamp;this.drops=List.copyOf(plan.drops());this.images=List.copyOf(plan.dropImages());
        }
    }
    private final class BlockProofOpening {
        final CubeCell target;
        boolean poisoned;
        BlockProofImageSlot images;
        BlockProofOpening(CubeCell target) { this.target = target; }
        void requireCurrent() {
            if (blockProofOpening != this || poisoned) throw new SurvivalRefusal("Native block proof opening was replaced or reentered");
        }
    }
    private void refuseBlockProofReentry() {
        if (blockProofOpening != null) {
            blockProofOpening.poisoned = true; poisonNestedOwnedPreparation();
            throw new SurvivalRefusal("Native block proof cannot reenter another ingress, even if refusal is caught");
        }
    }
    /** Gate inspection only. The opaque private opening/plan is never returned to a caller. */
    public void requireServerBreakProofGate(NativeSessionExecutionGate exact) {
        if(movePermit!=null)throw movePermit.reject("Native block proof overlaps MOVE authority");
        world.checkOwner();
        if (exact == null || exact != transitionGate || blockProofOpening == null)
            throw new SurvivalRefusal("Native server break lacks its exact host-retained opening");
        blockProofOpening.requireCurrent(); NativeBlockBreakAdmission.requireTrustedAudiences(player); exact.requireClaim(this); checkTransitionIdentity(); requireBinding(); requireCheckpointCoordinator();
        var durable = checkpointCoordinator.durable().orElse(null);
        io.earthplatform.minecraft.survival.source.MarinPlayerEnvironmentV2.requireInstalledPolicy(level,world.world());
        if (durable == null || durable.session().phase() != NativeSurvivalCheckpoint.SessionPhase.ACTIVE || !durable.session().id().equals(session)
                || sessionMenuOwner.retained() || menuOpening != null || pendingMenu != null
                || menuPacket != null || menuOpen != null || menuClose != null || menuScript != null || tableScript != null
                || dryPlacement != null || preparingPlacement || pickup != null || itemTick != null
                || limits.audience() != 1 || level.players().size() != 1 || level.players().getFirst() != player)
            throw new SurvivalRefusal("Native server break is outside exact settled first registered Marin proof");
        if (actions.fenced() && (current == null || current.state() != NativeWholeAction.State.APPLYING
                || blockBreak == null || pending == null || pending.nativeBlockBreak != blockBreak
                || actions.pendingDelivery() == null || actions.pendingDelivery().receipt().action() != current.identity()))
            throw new SurvivalRefusal("Native break gate is fenced outside its exact accepted delivery");
    }
    /**
     * Instantaneous server semantic proof, explicitly NOT normal START/STOP mining controls.
     * The exact registered GameTest first profile and all-empty initial inventory are required.
     */
    public NativeAcceptedBlockBreak dispatchServerBreakProof(CubeCell target) {
        rejectDuringMove("block proof opening");
        world.checkOwner(); refuseMenuOpeningReentry(); refuseBlockProofReentry(); reapAborted();
        if (current != null || pending != null || NativeWholeAction.hasCurrentOrOpeningAction() || actions.fenced()) {
            poisonNestedOwnedPreparation(); throw new SurvivalRefusal("Native block proof is nested, busy or fenced");
        }
        var opening = new BlockProofOpening(Objects.requireNonNull(target)); blockProofOpening = opening;
        try {
            NativeBlockBreakAdmission.requireTrustedAudiences(player);
            checkNativeIdentity(); reapAborted(); requireBinding();
            NativeSessionExecutionGate.requireServerBreakProofOpen(player, this);
            NativeBlockBreakAdmission.requireEmptyInventory(player); NativeInventorySnapshot.requireResolvedMenu(player);
            // This first registered proof digs only the actual support cell. No caller teleport or setup write.
            CubeCell support = CubeCell.of((long)Math.floor(player.getX()),
                    Math.subtractExact((long)Math.floor(player.getBoundingBox().minY), 1), (long)Math.floor(player.getZ()));
            if (!target.equals(support) || !player.onGround()) throw new SurvivalRefusal("Initial server break proof must remove actual support under the grounded player");
            NativeBlockBreakPlan.requireMetadataCoordinates(target);
            var expected = new LinkedHashMap<CubePos, NativeSurvivalWorld.CubeStamp>();
            for (int dx=-1; dx<=1; dx++) for (int dy=-1; dy<=1; dy++) for (int dz=-1; dz<=1; dz++) {
                CubePos cube = CubeCell.of(Math.addExact(target.x(),dx),Math.addExact(target.y(),dy),Math.addExact(target.z(),dz)).cube();
                if (!expected.containsKey(cube)) {
                    if (expected.size() >= world.limits().transactionCubes() || world.presence(cube) != NativeSurvivalWorld.Presence.RESIDENT)
                        throw new SurvivalRefusal("Native support break neighborhood is unavailable or exceeds its stamped cube budget");
                    expected.put(cube, world.stamp(cube));
                }
            }
            var beforeCube = world.snapshot(target.cube());
            var identity = world.actionIdentity(actor, world.nextActionSequence(actor));
            NativeBlockBreakPlan completedPlan;
            NativeSurvivalWorld.CommitReceipt receipt;
            try (var action = beginServerBreakProof(identity, expected, opening)) {
                reserveBlockProofImages(action,opening);
                blockBreak = NativeBlockBreakPlan.prepare(this, action, target, beforeCube);
                opening.requireCurrent(); completedPlan = blockBreak;
                receipt = action.commit(); opening.requireCurrent();
            }
            reapAborted();
            if (!completedPlan.finished(receipt) || lastApplied != receipt || actions.fenced())
                throw new SurvivalRefusal("Native block proof did not finish its exact single accepted delivery");
            var proof = new NativeAcceptedBlockBreak(receipt,target,completedPlan.beforeStateId(),completedPlan.observedDestroyProgress(),
                    completedPlan.drops().stream().map(ItemEntity::getUUID).toList());
            acceptedBreakLineage = new AcceptedBreakLineage(proof,completedPlan,world.stamp(target.cube()));
            // Do not publish a partially allocated/failed proof. The original action owner retains its failure receipt.
            lastAcceptedBreak = proof;
            requireAcceptedBreakLineage(proof, target);
            return proof;
        } finally {
            if (blockProofOpening != opening) throw new SurvivalRefusal("Native break opening ownership changed");
            blockProofOpening = null; reapAborted(); releaseUnownedBlockProofImages();
        }
    }
    private NativeWholeAction beginServerBreakProof(NativeSurvivalWorld.ActionIdentity identity,
            Map<CubePos,NativeSurvivalWorld.CubeStamp> expected, BlockProofOpening opening) {
        requireNoRetainedActiveSaveForAction();
        opening.requireCurrent(); NativeSessionExecutionGate.requireServerBreakProofOpen(player,this);
        checkNativeIdentity(); requireBinding(); requireIdentity(identity); checkNativeRoster(); events.requireDeliveryCallbacks();
        if (current != null || pending != null || actions.fenced() || NativeWholeAction.hasCurrentOrOpeningAction())
            throw new SurvivalRefusal("Native break owner is busy or reentered");
        NativeBlockBreakAdmission.requireEmptyInventory(player); NativeInventorySnapshot.requireResolvedMenu(player);
        try {
            inventoryBefore=snapshotInventory(); selectedBefore=player.getInventory().selected;
            foodBefore=NativeFoodSnapshot.capture(player.getFoodData()); poseBefore=NativePlayerPoseSnapshot.capture(player);
            opening.requireCurrent(); current=actions.begin(identity,expected,NativeWholeAction.inventoryOf(player)); return current;
        } catch (RuntimeException | Error failure) { inventoryBefore=null;foodBefore=null;poseBefore=null;throw failure; }
    }
    /** Read-only identity/currentness check used before the movement owner opens its own receipt. */
    public void requireCurrentAcceptedBreak(NativeAcceptedBlockBreak proof,CubeCell target) {
        rejectDuringMove("accepted break proof check");
        world.checkOwner(); refuseMenuOpeningReentry(); refuseBlockProofReentry(); NativeBlockBreakAdmission.requireTrustedAudiences(player); checkNativeIdentity(); reapAborted(); requireBinding();
        if (current != null || pending != null || actions.hasCurrentAction() || actions.fenced() || world.hasActiveTransaction())
            throw new SurvivalRefusal("Native accepted support break requires an idle successful owner");
        requireAcceptedBreakLineage(proof,target);
    }
    private void requireAcceptedBreakLineage(NativeAcceptedBlockBreak proof,CubeCell target) {
        var lineage=acceptedBreakLineage;
        if (proof == null || proof != lastAcceptedBreak || lineage == null || lineage.proof != proof || !lineage.exactSession.equals(session)
                || !proof.target().equals(target) || proof.receipt() != lastApplied || !lineage.plan.finished(proof.receipt())
                || !proof.receipt().world().equals(world.world()) || !proof.receipt().ownerEpoch().equals(world.ownerEpoch())
                || !proof.receipt().action().actor().equals(actor) || proof.receipt().commitSequence() != world.commitSequence()
                || !world.stamp(target.cube()).equals(lineage.stamp) || !world.read(target).is(net.minecraft.world.level.block.Blocks.AIR))
            throw new SurvivalRefusal("Foreign, stale or superseded native accepted support break");
        for (int i=0; i<lineage.drops.size(); i++) {
            ItemEntity item=lineage.drops.get(i); EntityOwner owner=entityOwners.get(item.getUUID());
            if (owner == null || owner.entity != item || owner.consumed != null || item.isRemoved() || !item.isAddedToLevel()
                    || item.level() != level || level.getEntity(item.getUUID()) != item || level.getEntity(item.getId()) != item)
                throw new SurvivalRefusal("Accepted native break drop lineage was changed, relocated, consumed or replaced");
            lineage.plan.requireUnchangedDrop(item);
        }
    }

    public NativeEffectDeliveryHost(ServerLevel level, ServerPlayer player, NativeSurvivalWorld world,
            HolderLookup.Provider registries, UUID session, Limits limits, NativeSurvivalCheckpoint.Limits checkpointLimits,
            NativeInventorySnapshot ordinaryEscrow, NativeFoodSnapshot ordinaryFoodEscrow,
            NativePlayerPoseSnapshot ordinaryPoseEscrow, int availableNativePayloadBytes) {
        this(level, player, world, registries, session, limits, checkpointLimits, ordinaryEscrow,
                ordinaryFoodEscrow, ordinaryPoseEscrow, availableNativePayloadBytes, null, null);
    }
    /** Stages the SAME host/owner while the exact player is still held on its ordinary Level. */
    public static NativeEffectDeliveryHost stageHeld(ServerLevel level, ServerPlayer player, NativeSurvivalWorld world,
            HolderLookup.Provider registries, Limits limits, NativeSurvivalCheckpoint.Limits checkpointLimits,
            SurvivalSessionTransitions.OrdinaryState ordinary, int availableNativePayloadBytes,
            NativeSessionExecutionGate gate, NativeSurvivalCheckpoint.Boundary initialEarth) {
        Objects.requireNonNull(gate).requireHeld();
        if (gate.player() != player || player.serverLevel() == level) throw new SurvivalRefusal("Staging requires the exact held ordinary player");
        var host = new NativeEffectDeliveryHost(level, player, world, registries, UUID.randomUUID(), limits, checkpointLimits,
                ordinary.inventory(), ordinary.food(), ordinary.pose(), availableNativePayloadBytes, gate, initialEarth);
        gate.claim(host); gate.bindCarrier(host,level); return host;
    }
    private NativeEffectDeliveryHost(ServerLevel level, ServerPlayer player, NativeSurvivalWorld world,
            HolderLookup.Provider registries, UUID session, Limits limits, NativeSurvivalCheckpoint.Limits checkpointLimits,
            NativeInventorySnapshot ordinaryEscrow, NativeFoodSnapshot ordinaryFoodEscrow,
            NativePlayerPoseSnapshot ordinaryPoseEscrow, int availableNativePayloadBytes,
            NativeSessionExecutionGate transitionGate, NativeSurvivalCheckpoint.Boundary initialTransitionBoundary) {
        this.transitionGate = transitionGate; this.initialTransitionBoundary = initialTransitionBoundary;
        this.requestedNativePayloadBytes = availableNativePayloadBytes;
        this.level = Objects.requireNonNull(level); this.player = Objects.requireNonNull(player);
        this.world = Objects.requireNonNull(world); this.registries = Objects.requireNonNull(registries);
        this.session = Objects.requireNonNull(session); this.limits = Objects.requireNonNull(limits);
        this.checkpointLimits = Objects.requireNonNull(checkpointLimits);
        this.ordinaryEscrow = Objects.requireNonNull(ordinaryEscrow);
        this.ordinaryFoodEscrow = Objects.requireNonNull(ordinaryFoodEscrow);
        this.ordinaryPoseEscrow = Objects.requireNonNull(ordinaryPoseEscrow);
        // A fixed session reservation, never a caller-reset per-action budget. The session owner
        // also reserves its codec/cube/header overhead before supplying this native allowance.
        this.availableNativePayloadBytes = checkedBoundaryAllowance(checkpointLimits.payloadBytes(),
                Math.addExact(Math.addExact(ordinaryEscrow.encodedBytes(), ordinaryFoodEscrow.encodedBytes()), ordinaryPoseEscrow.encodedBytes()), availableNativePayloadBytes);
        if (limits.entityOwners() > checkpointLimits.items() || limits.scheduledTickOwners() > checkpointLimits.scheduledTicks()
                || world.limits().ownedCubes() > checkpointLimits.cubes() || world.limits().actors() > checkpointLimits.actors())
            throw new SurvivalRefusal("Native effect/world ownership exceeds the session checkpoint count limits");
        recipeSource = level.getRecipeManager(); lootRegistrySource = level.getServer().reloadableRegistries();
        connection = player.connection;
        this.actor = player.getUUID(); world.checkOwner();
        events = new PinnedNativeEventAdmission(NeoForge.EVENT_BUS);
        nativeState = new NativeDeliveryReadAccess();
        scheduledTicks = new NativeScheduledTickQueue(world, limits.scheduledTickOwners());
        if (transitionGate == null) checkNativeIdentity(); else checkTransitionIdentity();
        events.requireDeliveryCallbacks();
        actions = new NativeWholeAction.Owner(world, registries, this);
    }
    public NativeWholeAction.Owner actions() { world.checkOwner(); return actions; }
    public UUID session() { return session; }
    public ServerLevel level() { world.checkOwner(); return level; }
    public ServerPlayer player() { world.checkOwner(); return player; }
    public NativeSurvivalWorld.CommitReceipt lastAppliedReceipt() { world.checkOwner(); return lastApplied; }

    /** One-shot attachment permits ENTERING checkpoint capture before any player action. */
    public void attachBinding(SurvivalLevelBinding exactBinding) {
        rejectDuringMove("attachBinding");
        if (transitionGate == null) checkNativeIdentity(); else checkTransitionIdentity();
        world.checkOwner();
        if (binding != null || current != null || pending != null || actions.hasCurrentAction() || actions.fenced()
                || exactBinding == null || SurvivalLevelBinding.find(level) != exactBinding
                || exactBinding.level() != level || exactBinding.world() != world || exactBinding.actions() != actions)
            throw new SurvivalRefusal("Native effect binding cannot be foreign, replaced, or attached while busy");
        synchronized (ATTACHED) {
            if (findAttached(level) != null) throw new SurvivalRefusal("Native level already has an attached delivery host");
            binding = exactBinding; ATTACHED.put(level, new WeakReference<>(this));
        }
    }

    /**
     * The real ItemEntity.playerTouch wrapper enters here with the exact native tracked source.
     * Returns null for native delay/no-space/repeated consumed calls. An admitted no-op action
     * still consumes the shared core actor sequence. No caller-supplied outcome or authority.
     */
    public NativeSurvivalWorld.CommitReceipt dispatchPickup(ItemEntity source, Player touching) {
        rejectDuringMove("dispatchPickup");
        refuseBlockProofReentry();
        checkNativeIdentity(); reapAborted(); requireBinding();
        if (touching != player || actions.fenced() || current != null || pending != null || NativeWholeAction.current() != null)
            throw new SurvivalRefusal("Native pickup is foreign, nested, busy or fenced");
        EntityOwner owner = entityOwners.get(source.getUUID());
        if (owner == null || owner.entity != source) throw new SurvivalRefusal("Native pickup source is not owned by this exact session");
        if (owner.consumed != null) { requireConsumed(owner); return null; }
        requirePickupSource(source, null);
        events.requireDeliveryCallbacks(); events.requirePickupEventsEmpty();
        CubeCell cell = CubeCell.of(source.blockPosition().getX(), source.blockPosition().getY(), source.blockPosition().getZ());
        var identity = world.actionIdentity(actor, world.nextActionSequence(actor));
        var before = NativeItemEntitySnapshot.capture(source);
        try (var action = begin(identity, Map.of(cell.cube(), world.stamp(cell.cube())))) {
            action.read(cell);
            var result = NativeItemPickupPreparation.prepare(this, action, source, before);
            if (result.effect() == null) return null;
            pickup = new PickupPlan(action, source, result);
            action.stage(result.effect());
            return action.commit();
        } finally { reapAborted(); }
    }
    void requireDetachedEntity(ItemEntity copy) { nativeState.requireDetachedEntity(copy); }

    /** Accepts one bounded dry native item tick; never clones physics or edits pickup delay. */
    public NativeSurvivalWorld.CommitReceipt dispatchItemTick(ItemEntity source) {
        rejectDuringMove("dispatchItemTick");
        refuseBlockProofReentry();
        checkNativeIdentity(); reapAborted(); requireBinding();
        if (actions.fenced() || current != null || pending != null || NativeWholeAction.current() != null)
            throw new SurvivalRefusal("Native item tick is nested, busy or fenced");
        var before = NativeItemEntitySnapshot.capture(source);
        requireItemTickSource(source, before, source.tickCount);
        events.requireDeliveryCallbacks(); events.requireItemTickEventsEmpty(); NativeDryItemTickAdmission.requirePinnedBinaries();
        var box = NativeDryItemTickAdmission.bounds(source.getBoundingBox(), source.getDeltaMovement());
        if (box.cells() > world.limits().reads()) throw new SurvivalRefusal("Native item tick sealed reads exceed the action budget");
        var expected = new LinkedHashMap<CubePos, NativeSurvivalWorld.CubeStamp>();
        for (int x = box.x1(); x <= box.x2(); x++) for (int y = box.y1(); y <= box.y2(); y++) for (int z = box.z1(); z <= box.z2(); z++) {
            CubePos cube = CubeCell.of(x, y, z).cube();
            if (!expected.containsKey(cube)) {
                if (expected.size() >= world.limits().transactionCubes() || world.presence(cube) != NativeSurvivalWorld.Presence.RESIDENT)
                    throw new SurvivalRefusal("Native item tick read cube is unavailable or exceeds its budget");
                expected.put(cube, world.stamp(cube));
            }
        }
        var identity = world.actionIdentity(actor, world.nextActionSequence(actor));
        try (var action = begin(identity, expected)) {
            var sealed = new LinkedHashMap<CubeCell, BlockState>();
            var checked = new HashSet<net.minecraft.world.level.block.Block>();
            for (int x = box.x1(); x <= box.x2(); x++) for (int y = box.y1(); y <= box.y2(); y++) for (int z = box.z1(); z <= box.z2(); z++) {
                var cell = CubeCell.of(x, y, z); BlockState state = action.read(cell);
                if (checked.add(state.getBlock())) NativeDryItemTickAdmission.requireBlock(state);
                // Every concrete native state remains palette-admitted, including dry leaves.
                world.palette().id(state); sealed.put(cell, state);
            }
            if (source.mainSupportingBlockPos.isPresent() && !sealed.containsKey(CubeCell.of(
                    source.mainSupportingBlockPos.get().getX(), source.mainSupportingBlockPos.get().getY(), source.mainSupportingBlockPos.get().getZ())))
                throw new SurvivalRefusal("Native supporting block is outside the sealed tick view");
            // Refuse interpenetration before acceptance: the native escape branch consumes local RNG.
            if (!level.noCollision(source, source.getBoundingBox().deflate(1.0E-7)))
                throw new SurvivalRefusal("Native item begins inside a collision shape; escape behavior is outside the dry profile");
            int radius = GameEvent.HIT_GROUND.value().notificationRadius();
            if (radius < 0 || radius > 29) throw new SurvivalRefusal("Native ground event radius exceeds the sealed profile");
            nativeState.requireTickGameListeners(level, source.blockPosition(), radius + 3, limits.metadataEffects());
            requireTickDestinations(box);
            itemTick = new ItemTickPlan(action, source, new NativeEffectCodec.ItemTick(source.getUUID(), source.getId(), before, source.tickCount), box, sealed, radius + 3);
            action.stage(itemTick.effect);
            return action.commit();
        } finally { reapAborted(); }
    }

    /** Private-constructor permit binds the unchanged session execution fence to one dispatch. */
    public static final class PlayerMovePermit {
        private final NativeEffectDeliveryHost host;
        private final net.minecraft.server.network.ServerGamePacketListenerImpl receiver;
        private final net.minecraft.network.protocol.game.ServerboundMovePlayerPacket packet;
        private PlayerMovePermit(NativeEffectDeliveryHost host,net.minecraft.server.network.ServerGamePacketListenerImpl receiver,
                net.minecraft.network.protocol.game.ServerboundMovePlayerPacket packet){this.host=host;this.receiver=receiver;this.packet=packet;}
        private NativeSurvivalWorld.ActionIdentity openingIdentity;
        private boolean opening,beginGate,validated;
        private Throwable failure;
        private SurvivalRefusal reject(String message){var rejected=new SurvivalRefusal(message);if(failure==null)failure=rejected;if(host.playerStep!=null)host.playerStep.poison(rejected);return rejected;}
        private void requireHealthy(){if(failure instanceof RuntimeException exception)throw exception;if(failure instanceof Error exception)throw exception;}
        private void armBegin(NativeSurvivalWorld.ActionIdentity identity){requireHealthy();if(opening||openingIdentity!=null||host.current!=null)throw reject("Native MOVE begin reused");openingIdentity=identity;opening=true;}
        private void claimBegin(NativeSurvivalWorld.ActionIdentity identity){requireHealthy();if(!opening||openingIdentity!=identity||host.current!=null)throw reject("Nested generic action entered private MOVE opening");opening=false;beginGate=true;}
        public void requireActionEntry(NativeSessionExecutionGate gate){
            requireCurrent(gate);
            if(beginGate){beginGate=false;return;}
            if(!validated&&host.playerStep!=null&&host.current==host.playerStep.action
                &&NativeWholeAction.current()==host.current&&host.current.state()==NativeWholeAction.State.PREVALIDATING){validated=true;return;}
            throw reject("Native MOVE permit cannot open a generic action/menu/checkpoint lane");
        }
        public void requireCurrent(NativeSessionExecutionGate gate){
            requireHealthy();
            if(gate==null||host.transitionGate!=gate||host.movePermit!=this||host.moveBudget==null
                ||host.player.connection!=receiver||receiver.player!=host.player||packet==null)
                throw new SurvivalRefusal("Foreign or expired host-owned native MOVE permit");
            gate.requireClaim(host);host.checkNativeIdentity();host.requireBinding();
        }
    }
    private void rejectDuringMove(String operation){refuseMiningReentry(operation);refuseActiveSaveReentry();if(movePermit!=null)throw movePermit.reject("Nested "+operation+" during private native MOVE dispatch");}
    private NativeWholeAction beginMoveAction(NativeSurvivalWorld.ActionIdentity identity,Map<CubePos,NativeSurvivalWorld.CubeStamp> expected){
        movePermit.armBegin(identity);try{return begin(identity,expected);}finally{movePermit.opening=false;movePermit.beginGate=false;}
    }
    /** First gate is registered GameTestServer original packet-handler execution only. */
    public NativeSurvivalWorld.CommitReceipt dispatchPlayerStep(NativePlayerStepOperation operation,
            net.minecraft.server.network.ServerGamePacketListenerImpl receiver,
            net.minecraft.network.protocol.game.ServerboundMovePlayerPacket packet){
        // These roots have no admitted observer/output closure. Refuse before sequence/RNG/budget capture.
        rejectDuringMove("MOVE dispatch");requireNoRetainedActiveSaveForAction();
        refuseMenuOpeningReentry();
        refuseBlockProofReentry();
        if(hasRetainedMenuSession())throw new SurvivalRefusal("Close the retained native menu before MOVE admission");
        if(operation!=NativePlayerStepOperation.MOVE_PACKET)throw new SurvivalRefusal("Only original MOVE_PACKET is admitted; native tick roots remain held");
        if(player.server.getClass()!=net.minecraft.gametest.framework.GameTestServer.class)throw new SurvivalRefusal("First MOVE gate is the registered GameTestServer packet-handler fixture");
        NativePlayerMoveTracking.requireTrustedAudiences(player);
        checkNativeIdentity();reapAborted();requireBinding();requireCheckpointCoordinator();
        if(receiver!=player.connection||receiver.player!=player||packet==null||transitionGate==null
            ||actions.fenced()||current!=null||pending!=null||movePermit!=null||NativeWholeAction.hasCurrentOrOpeningAction())
            throw new SurvivalRefusal("Native MOVE requires exact idle registered session receiver");
        transitionGate.requirePlayerMoveAdmission(this,level);
        var durable=checkpointCoordinator.durable().orElseThrow(()->new SurvivalRefusal("Native MOVE requires durable ACTIVE session"));
        if(durable.session().phase()!=NativeSurvivalCheckpoint.SessionPhase.ACTIVE||!durable.session().id().equals(session)
            ||checkpointCoordinator.state()!=SurvivalCheckpointCoordinator.State.IDLE||checkpointCapture!=null||world.hasPendingCheckpoint())
            throw new SurvivalRefusal("Native MOVE requires settled ACTIVE checkpoint ownership");
        var delta=NativePlayerStepAdmission.requireInput(player,operation,packet);
        var box=NativePlayerStepAdmission.footprint(player.getBoundingBox(),player.getDeltaMovement(),delta);
        if(box.cells()>world.limits().reads())throw new SurvivalRefusal("MOVE sealed reads exceed action budget");
        io.earthplatform.minecraft.survival.source.MarinPlayerEnvironmentV2.requireInstalledPolicy(level,world.world());
        NativePlayerStepAdmission.requirePinnedBinaries();
        NativePlayerMoveSnapshotAdmission.require(player);
        var stepBudget=new NativePlayerStepBudget(Math.min(nativePayloadAllowance(),world.limits().effectBytes()));
        stepBudget.reserveScratch();
        stepBudget.reserve(NativePlayerStepBudget.ACTION_COPIES);
        moveBudget=stepBudget; // NativeWholeAction's own immutable/detached validation copies.
        movePermit=new PlayerMovePermit(this,receiver,packet);
        try{transitionGate.beginPlayerMove(movePermit);}catch(RuntimeException|Error failure){movePermit=null;moveBudget=null;throw failure;}
        try{
            var before=NativePlayerStepSnapshot.capture(player,moveBudget);
            NativePlayerStepAdmission.requireProfile(player,binding,operation,box,before);
            var owned=new ArrayList<Entity>();for(var owner:entityOwners.values())if(owner.consumed==null)owned.add(owner.entity);
            var tracking=new NativePlayerMoveTracking(player,owned,limits.entityOwners()+1,delta);
            var expected=new LinkedHashMap<CubePos,NativeSurvivalWorld.CubeStamp>();
            for(int x=box.x1();x<=box.x2();x++)for(int y=box.y1();y<=box.y2();y++)for(int z=box.z1();z<=box.z2();z++){
                var cube=CubeCell.of(x,y,z).cube();if(!expected.containsKey(cube)){
                    if(expected.size()>=world.limits().transactionCubes()||world.presence(cube)!=NativeSurvivalWorld.Presence.RESIDENT)
                        throw new SurvivalRefusal("MOVE lacks exact resident XYZ footprint");expected.put(cube,world.stamp(cube));
                }
            }
            movePermit.requireHealthy();
            var identity=world.actionIdentity(actor,world.nextActionSequence(actor));
            try(var action=beginMoveAction(identity,expected)){
                var sealed=new LinkedHashMap<CubeCell,BlockState>();var checked=new HashSet<BlockState>();
                for(int x=box.x1();x<=box.x2();x++)for(int y=box.y1();y<=box.y2();y++)for(int z=box.z1();z<=box.z2();z++){
                    var cell=CubeCell.of(x,y,z);var state=action.read(cell);if(checked.add(state))NativePlayerStepAdmission.requireBlock(state);
                    world.palette().id(state);sealed.put(cell,state);
                }
                if(player.mainSupportingBlockPos.isPresent()&&!box.contains(player.mainSupportingBlockPos.get()))
                    throw new SurvivalRefusal("MOVE support cache escaped sealed cells");
                int kind=packet instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos?0:
                    packet instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot?1:
                    packet instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot?2:3;
                var effect=new NativeEffectCodec.PlayerMove(world.world(),world.ownerEpoch(),actor,identity.sequence(),player.getId(),before.pose(),before.food(),
                    before.tickCount(),before.connection().tickCount(),before.connection().received(),before.connection().known(),kind,
                    packet.getX(player.getX()),packet.getY(player.getY()),packet.getZ(player.getZ()),packet.getYRot(player.getYRot()),packet.getXRot(player.getXRot()),packet.isOnGround());
                playerStep=new PlayerStepPlan(action,packet,before,moveBudget,effect,box,sealed,tracking);
                NativePlayerStepReadScope.prepare(playerStep);action.stage(effect);return action.commit();
            }
        }catch(RuntimeException|Error failure){
            if(actions.fenced()||playerStep!=null&&playerStep.ran)transitionGate.failPlayerMove(movePermit);
            throw failure;
        }finally{
            var permit=movePermit;transitionGate.endPlayerMove(permit);movePermit=null;
            if(!actions.fenced()){moveBudget=null;reapAborted();}
        }
    }

    /**
     * Commits one once-prepared resolved-to-resolved native2x2 PICKUP script. Callers provide
     * bounded input only, never a mutable menu, outcome, metadata callback or prepared plan.
     * This script-boundary entrypoint is not a claim of client packet/per-click qualification.
     */
    public NativeSurvivalWorld.CommitReceipt dispatchResolvedMenuScript(List<NativeInventoryMenuPreparation.Click> clicks,
            NativeFirstLoopMetadata.Limits metadataLimits, NativeCraftingRecipeAdmission.Limits recipeLimits,
            NativeInventoryMenuPreparation.Limits scriptLimits) {
        rejectDuringMove("dispatchResolvedMenuScript");
        refuseBlockProofReentry();
        checkNativeIdentity(); reapAborted(); requireBinding();
        if (actions.fenced() || current != null || pending != null || NativeWholeAction.current() != null) {
            poisonNestedOwnedPreparation();
            throw new SurvivalRefusal("Native menu script is nested, busy or fenced");
        }
        Objects.requireNonNull(clicks); Objects.requireNonNull(metadataLimits);
        Objects.requireNonNull(recipeLimits); Objects.requireNonNull(scriptLimits);
        if (clicks.isEmpty() || clicks.size() > scriptLimits.clicks()
                || clicks.size() > NativeEffectCodec.ResolvedMenuScript.MAX_CLICKS)
            throw new SurvivalRefusal("Native menu script exceeds its explicit click count");
        var input = List.copyOf(clicks);
        for (var click : input) NativeInventoryMenuPreparation.requireClick(click);
        var identity = world.actionIdentity(actor, world.nextActionSequence(actor));
        try (var action = begin(identity, Map.of())) {
            var metadata = NativeFirstLoopMetadata.capture(player, metadataLimits);
            var recipes = NativeCraftingRecipeAdmission.capture(player, metadata, recipeLimits);
            menuScript = NativeResolvedInventoryMenuPlan.prepare(this, action, metadata, recipes, input, scriptLimits);
            var after = menuScript.inventoryAfter();
            if (after.size() != 41) throw new SurvivalRefusal("Native menu plan lacks its complete final inventory");
            for (int slot = 0; slot < 41; slot++) action.replaceStack(slot, after.get(slot).materialize(registries));
            action.stage(menuScript.effect());
            return action.commit();
        } finally { reapAborted(); }
    }

    /** Internal resolved table action: native open/use, prepared clicks and native close, once. */
    public NativeSurvivalWorld.CommitReceipt dispatchResolvedTableScript(CubeCell table,
            List<NativeInventoryMenuPreparation.Click> clicks, NativeFirstLoopMetadata.Limits metadataLimits,
            NativeCraftingRecipeAdmission.Limits recipeLimits, NativeInventoryMenuPreparation.Limits scriptLimits) {
        rejectDuringMove("dispatchResolvedTableScript");
        checkNativeIdentity(); reapAborted(); requireBinding();
        if (actions.fenced() || current != null || pending != null || NativeWholeAction.current() != null) {
            poisonNestedOwnedPreparation();
            throw new SurvivalRefusal("Native table script is nested, busy or fenced");
        }
        Objects.requireNonNull(table); Objects.requireNonNull(clicks); Objects.requireNonNull(metadataLimits);
        Objects.requireNonNull(recipeLimits); Objects.requireNonNull(scriptLimits);
        if (clicks.isEmpty() || clicks.size() > scriptLimits.clicks()
                || clicks.size() > NativeEffectCodec.ResolvedMenuScript.MAX_CLICKS)
            throw new SurvivalRefusal("Native table script exceeds its explicit click count");
        var input = List.copyOf(clicks);
        for (var click : input) NativeCraftingTablePreparation.requireClick(click);
        var identity = world.actionIdentity(actor, world.nextActionSequence(actor));
        try (var action = begin(identity, Map.of(table.cube(), world.stamp(table.cube())))) {
            action.read(table);
            var metadata = NativeFirstLoopMetadata.capture(player, metadataLimits);
            var recipes = NativeCraftingRecipeAdmission.capture(player, metadata, recipeLimits);
            tableScript = NativeResolvedCraftingTablePlan.prepare(this, action, table, metadata, recipes, input, scriptLimits);
            var after = tableScript.inventoryAfter();
            if (after.size() != 41) throw new SurvivalRefusal("Native table plan lacks its complete final inventory");
            for (int slot = 0; slot < 41; slot++) action.replaceStack(slot, after.get(slot).materialize(registries));
            action.stage(tableScript.effect());
            return action.commit();
        } finally { reapAborted(); }
    }
    /** One narrow native placement preparation, retained privately until its accepted delivery. */
    public NativeDryPlacementPlan.Prepared dispatchDryTablePlacement(NativeWholeAction action,
            net.minecraft.world.phys.BlockHitResult hit) {
        rejectDuringMove("dispatchDryTablePlacement");
        refuseBlockProofReentry();
        checkNativeIdentity(); requireBinding();
        if (action == null || action != current || !actions.owns(action) || NativeWholeAction.current() != action
                || action.state() != NativeWholeAction.State.PREPARING || actions.fenced() || pending != null
                || preparingPlacement || dryPlacement != null || menuScript != null || tableScript != null
                || pickup != null || itemTick != null) {
            poisonNestedOwnedPreparation();
            throw new SurvivalRefusal("Native table placement is foreign, nested, busy or fenced");
        }
        preparingPlacement = true;
        try {
            dryPlacement = NativeDryPlacementPlan.prepare(this, action, Objects.requireNonNull(hit));
            return dryPlacement.prepared();
        } catch (RuntimeException | Error failure) {
            poisonNestedOwnedPreparation();
            throw failure;
        } finally { preparingPlacement = false; }
    }
    private void poisonNestedOwnedPreparation() {
        if (current != null && NativeWholeAction.current() == current && actions.owns(current)
                && current.state() == NativeWholeAction.State.PREPARING) current.poisonPreparation();
    }

    /** Exact retained native per-packet lane; ordinary begin/checkpoint admission stays resolved-only. */
    public NativeSurvivalWorld.CommitReceipt dispatchMenuPacket(
            net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet,
            NativeFirstLoopMetadata.Limits metadataLimits, NativeCraftingRecipeAdmission.Limits recipeLimits,
            NativeInventoryMenuPreparation.Limits menuLimits) {
        var opening = enterMenuOpening();
        try {
        checkNativeIdentity(); reapAborted(); requireBinding();
        NativeSessionExecutionGate.requireRetainedMenuActionOpen(player, this, false);
        requireMenuIdle();
        var budget = new NativeMenuBudget(menuLimits.menuSnapshotBytes()); sessionMenuBudget = budget;
        // Bound and copy all untrusted prediction data before callback-capable live capture.
        var input = NativeMenuPacketInput.capture(packet, player, budget);
        NativeSessionMenuBoundary.requireTrustedLiveCollections(player);
        sessionMenuMetadata = NativeFirstLoopMetadata.capture(player, metadataLimits);
        var recipes = NativeCraftingRecipeAdmission.capture(player, sessionMenuMetadata, recipeLimits);
        sessionMenuBefore = sessionMenuOwner.captureForAction(sessionMenuMetadata, budget);
        var identity = world.actionIdentity(actor, world.nextActionSequence(actor));
        Map<CubePos, NativeSurvivalWorld.CubeStamp> expected = sessionMenuOwner.expected(sessionMenuBefore.table());
        try (var action = beginRetainedMenu(identity, expected)) {
            menuPacket = sessionMenuOwner.preparePacket(action, sessionMenuBefore, input, sessionMenuMetadata, recipes, menuLimits, budget);
            for (int slot = 0; slot < 41; slot++) action.replaceStack(slot, menuPacket.inventoryAfter().get(slot).materialize(registries));
            action.stage(menuPacket.effect()); return action.commit();
        } finally { reapAborted(); }
        } finally { releaseMenuOpening(opening); }
    }
    /** Used by a genuine close packet or the host's explicit clean-save/leave drain before capture. */
    public NativeSurvivalWorld.CommitReceipt dispatchMenuClose(NativeFirstLoopMetadata.Limits metadataLimits, int menuBytes) {
        var opening = enterMenuOpening();
        try {
        checkNativeIdentity(); reapAborted(); requireBinding();
        NativeSessionExecutionGate.requireRetainedMenuActionOpen(player, this, true);
        requireMenuIdle();
        NativeSessionMenuBoundary.requireTrustedLiveCollections(player);
        sessionMenuMetadata = NativeFirstLoopMetadata.capture(player, metadataLimits);
        var budget = new NativeMenuBudget(menuBytes); sessionMenuBudget = budget;
        sessionMenuBefore = sessionMenuOwner.captureForAction(sessionMenuMetadata, budget);
        var identity = world.actionIdentity(actor, world.nextActionSequence(actor));
        Map<CubePos, NativeSurvivalWorld.CubeStamp> expected = sessionMenuOwner.expected(sessionMenuBefore.table());
        try (var action = beginRetainedMenu(identity, expected)) {
            menuClose = sessionMenuOwner.prepareClose(action, sessionMenuBefore, sessionMenuMetadata, budget);
            for (int slot = 0; slot < 41; slot++) action.replaceStack(slot, menuClose.inventoryAfter().get(slot).materialize(registries));
            action.stage(menuClose.effect()); return action.commit();
        } finally { reapAborted(); }
        } finally { releaseMenuOpening(opening); }
    }
    /** Called only from the parent's qualified placed-table use ingress; it retains the native open. */
    public NativeSurvivalWorld.CommitReceipt dispatchMenuOpen(CubeCell table,
            NativeFirstLoopMetadata.Limits metadataLimits, NativeCraftingRecipeAdmission.Limits recipeLimits, int menuBytes) {
        var opening = enterMenuOpening();
        try {
        checkNativeIdentity(); reapAborted(); requireBinding();
        NativeSessionExecutionGate.requireRetainedMenuActionOpen(player, this, false); requireMenuIdle();
        NativeSessionMenuBoundary.requireTrustedLiveCollections(player);
        sessionMenuMetadata = NativeFirstLoopMetadata.capture(player, metadataLimits);
        var recipes = NativeCraftingRecipeAdmission.capture(player, sessionMenuMetadata, recipeLimits);
        var budget = new NativeMenuBudget(menuBytes); sessionMenuBudget = budget;
        sessionMenuBefore = sessionMenuOwner.captureForAction(sessionMenuMetadata, budget);
        var identity = world.actionIdentity(actor, world.nextActionSequence(actor));
        try (var action = beginRetainedMenu(identity, sessionMenuOwner.expected(table))) {
            menuOpen = sessionMenuOwner.prepareOpen(action, sessionMenuBefore, table, sessionMenuMetadata, recipes, budget);
            action.stage(menuOpen.effect()); return action.commit();
        } finally { reapAborted(); }
        } finally { releaseMenuOpening(opening); }
    }
    public boolean hasRetainedMenuSession() { return sessionMenuOwner.retained(); }
    NativeSessionMenuOwner sessionMenuOwner() { return sessionMenuOwner; }
    private final class MenuOpening {
        NativeSessionMenuBoundary.ConservedScalars scalars;
        boolean poisoned;
        void requireCurrent() {
            if (menuOpening != this || poisoned || scalars == null || !scalars.equals(NativeSessionMenuBoundary.conservedScalars(player)))
                throw new SurvivalRefusal("Native menu opening was reentered or changed its conserved player boundary");
        }
    }
    private MenuOpening enterMenuOpening() {
        rejectDuringMove("retained menu opening");
        refuseBlockProofReentry();
        world.checkOwner();
        if (menuOpening != null) {
            menuOpening.poisoned = true; poisonNestedOwnedPreparation();
            throw new SurvivalRefusal("Native menu opening cannot be nested, even if its refusal is caught");
        }
        var opening = new MenuOpening(); menuOpening = opening;
        try { checkNativeIdentity(); opening.scalars = NativeSessionMenuBoundary.conservedScalars(player); opening.requireCurrent(); return opening; }
        catch (RuntimeException | Error failure) { menuOpening = null; throw failure; }
    }
    private void releaseMenuOpening(MenuOpening opening) {
        if (menuOpening != opening) throw new SurvivalRefusal("Foreign native menu opening release");
        // Never clear a failed accepted plan. A pre-action/capture refusal leaves the prior retained owner intact.
        if (current == null || current.state() == NativeWholeAction.State.ABORTED || current.state() == NativeWholeAction.State.APPLIED) {
            menuPacket = null; menuClose = null; menuOpen = null; sessionMenuBefore = null; sessionMenuMetadata = null; sessionMenuBudget = null;
        }
        menuOpening = null;
    }
    private void refuseMenuOpeningReentry() {
        refuseActiveSaveReentry();
        refuseBlockProofReentry();
        world.checkOwner();
        if (menuOpening != null) {
            menuOpening.poisoned = true; poisonNestedOwnedPreparation();
            throw new SurvivalRefusal("Action/checkpoint/transition cannot reenter a native menu opening");
        }
    }
    private void requireMenuOpening() {
        if (menuOpening == null) throw new SurvivalRefusal("Native menu preparation has no private opening token");
        menuOpening.requireCurrent();
    }
    private void requireMenuIdle() {
        requireMenuOpening();
        if (actions.fenced() || current != null || pending != null || pendingMenu != null || NativeWholeAction.current() != null) {
            poisonNestedOwnedPreparation(); throw new SurvivalRefusal("Native menu host is busy, nested or fenced");
        }
    }
    private NativeWholeAction beginRetainedMenu(NativeSurvivalWorld.ActionIdentity identity,
            Map<CubePos, NativeSurvivalWorld.CubeStamp> expected) {
        requireNoRetainedActiveSaveForAction();
        requireMenuIdle(); requireBinding(); requireIdentity(identity); checkNativeIdentity(); checkNativeRoster(); events.requireDeliveryCallbacks();
        sessionMenuBefore.requireUnchanged(sessionMenuMetadata, sessionMenuBudget);
        inventoryBefore = sessionMenuBefore.data().inventory().toArray(NativeStackSnapshot[]::new);
        selectedBefore = sessionMenuBefore.data().selected(); foodBefore = NativeFoodSnapshot.capture(player.getFoodData());
        // No NativePlayerPoseSnapshot.capture or durable boundary is fabricated while unresolved.
        poseBefore = null;
        try { current = actions.begin(identity, expected, NativeWholeAction.inventoryOf(player)); sessionMenuOwner.declareSupport(current); return current; }
        catch (RuntimeException | Error failure) {
            if (current != null && actions.owns(current)) {
                if (current.state() == NativeWholeAction.State.PREPARING) current.poisonPreparation();
                current.close(); reapAborted();
            }
            inventoryBefore = null; foodBefore = null; throw failure;
        }
    }
    private NativeWholeAction.PreparedEffects prepareRetainedMenu(NativeSurvivalWorld.ActionIdentity identity,
            List<NativeEffectCodec.Effect> effects) {
        checkNativeIdentity(); requireBinding(); requireIdentity(identity);
        NativeSessionExecutionGate.requireRetainedMenuActionOpen(player, this, menuClose != null);
        if (current == null || !actions.owns(current) || NativeWholeAction.current() != current
                || current.state() != NativeWholeAction.State.PREVALIDATING || pending != null || pendingMenu != null
                || (menuPacket == null ? 0 : 1) + (menuClose == null ? 0 : 1) + (menuOpen == null ? 0 : 1) != 1 || world.usage().transactionWrites() != 0)
            throw new SurvivalRefusal("Retained menu preparation lacks the exact host/action plan");
        requireMenuOpening(); validateInventory(); validateFood(); sessionMenuBefore.requireUnchanged(sessionMenuMetadata, sessionMenuBudget); checkNativeRoster(); events.requireDeliveryCallbacks();
        sessionMenuOwner.requireSupportDuring(current);
        var batch = new SessionMenuBatch(identity, effects);
        var projected = new ArrayList<NativeStackSnapshot>(List.of(inventoryBefore));
        var changed = new HashSet<Integer>(); int seeds = 0, markers = 0;
        for (var effect : effects) {
            if (effect instanceof NativeEffectCodec.RandomSeed seed) {
                if (++seeds != 1 || !seed.world().equals(world.world()) || !seed.ownerEpoch().equals(identity.ownerEpoch())
                        || !seed.actor().equals(actor) || seed.sequence() != identity.sequence()) throw new SurvivalRefusal("Foreign menu random identity");

            } else if (effect instanceof NativeEffectCodec.InventorySlot slot) {
                if (!changed.add(slot.slot()) || !slot.before().equals(inventoryBefore[slot.slot()]))
                    throw new SurvivalRefusal("Foreign or duplicate menu inventory before-image");
                ItemStack stack = slot.after().materialize(registries); validateStack(stack);
                batch.inventory.add(new InventoryChange(slot.slot(), stack)); projected.set(slot.slot(), slot.after());
            } else if (effect instanceof NativeEffectCodec.SessionMenuPacket packet) {
                if (menuPacket == null || ++markers != 1 || !menuPacket.effect().equals(packet)) throw new SurvivalRefusal("Unretained native menu packet evidence");
                menuPacket.requireBeforeCommit(current, packet);
            } else if (effect instanceof NativeEffectCodec.SessionMenuClose close) {
                if (menuClose == null || ++markers != 1 || !menuClose.effect().equals(close)) throw new SurvivalRefusal("Unretained native menu close evidence");
                menuClose.requireBeforeCommit(current, close);
            } else if (effect instanceof NativeEffectCodec.SessionMenuOpen open) {
                if (menuOpen == null || ++markers != 1 || !menuOpen.effect().equals(open)) throw new SurvivalRefusal("Unretained native menu open evidence");
                menuOpen.requireBeforeCommit(current, open);
            } else throw new SurvivalRefusal("A retained menu action cannot combine unrelated effects");
        }
        if (seeds != 1 || markers != 1 || !projected.equals(menuPacket != null ? menuPacket.inventoryAfter() : menuClose != null ? menuClose.inventoryAfter() : menuOpen.inventoryAfter()))
            throw new SurvivalRefusal("Menu journal lacks the complete exact shared41 inventory outcome");
        checkedMenuMetadataCount(0, menuPacket != null ? menuPacket.reservedDeliveryOperations() : menuClose != null ? menuClose.reservedDeliveryOperations() : menuOpen.reservedDeliveryOperations(), limits.metadataEffects());
        checkedMenuEffectBytes(batch.encoded, menuPacket != null ? menuPacket.reservedDeliveryBytes() : menuClose != null ? menuClose.reservedDeliveryBytes() : menuOpen.reservedDeliveryBytes(), world.limits().effectBytes());
        batch.requireOtherOwners(); pendingMenu = batch; return batch;
    }
    private final class SessionMenuBatch implements NativeWholeAction.PreparedEffects {
        final NativeSurvivalWorld.ActionIdentity identity;
        final long beforeCommit = world.commitSequence(), gameTime = level.getGameTime();
        final List<NativeActionEffect> encoded;
        final List<InventoryChange> inventory = new ArrayList<>();
        final List<NativeItemEntitySnapshot> itemImages = menuItemImages();
        final List<NativeEffectCodec.ScheduledBlockTick> tickImages = scheduledTicks.snapshot();
        final NativeMenuPacketPlan packetPlan = menuPacket;
        final NativeMenuClosePlan closePlan = menuClose;
        final NativeMenuOpenPlan openPlan = menuOpen;
        boolean attempted, failed;
        SessionMenuBatch(NativeSurvivalWorld.ActionIdentity identity, List<NativeEffectCodec.Effect> effects) {
            this.identity = identity; encoded = effects.stream().map(NativeEffectCodec::encode).toList();
        }
        void requireOtherOwners() {
            requireMenuOpening(); checkNativeIdentity(); checkNativeRoster(); events.requireDeliveryCallbacks(); validateFood(); sessionMenuBefore.requireConservedPlayer();
            sessionMenuOwner.requireSupportDuring(current);
            if (level.getGameTime() != gameTime || !tickImages.equals(scheduledTicks.snapshot()) || !itemImages.equals(menuItemImages()))
                throw new SurvivalRefusal("Retained native menu changed clock/item/tick ownership");
        }
        @Override public void apply(NativeSurvivalWorld.CommitReceipt receipt) {
            if (attempted || pendingMenu != this) throw new SurvivalRefusal("Native session menu batch cannot be replayed");
            attempted = true;
            try {
                requireBinding(); requireOtherOwners(); validateInventory();
                if (current == null || !actions.owns(current) || NativeWholeAction.current() != current
                        || current.state() != NativeWholeAction.State.APPLYING || !receipt.world().equals(world.world())
                        || !receipt.ownerEpoch().equals(world.ownerEpoch()) || !receipt.action().equals(identity)
                        || receipt.commitSequence() != beforeCommit + 1 || receipt.commitSequence() != world.commitSequence()
                        || !receipt.changedCubes().isEmpty() || !receipt.effects().equals(encoded)
                        || packetPlan != menuPacket || closePlan != menuClose || openPlan != menuOpen)
                    throw new SurvivalRefusal("Native retained menu delivery received a foreign receipt");
                if (packetPlan != null) packetPlan.requireBeforeApply(current, receipt); else if (closePlan != null) closePlan.requireBeforeApply(current, receipt); else openPlan.requireBeforeApply(current, receipt);
                for (var slot : inventory) player.getInventory().setItem(slot.slot(), slot.after());
                if (packetPlan != null) packetPlan.apply(receipt); else if (closePlan != null) closePlan.apply(receipt); else openPlan.apply(receipt);
                requireOtherOwners(); sessionMenuOwner.releaseClosedSupport(); lastApplied = receipt; pendingMenu = null;
            } catch (RuntimeException | Error failure) { failed = true; throw failure; }
        }
    }
    private List<NativeItemEntitySnapshot> menuItemImages() {
        var images = new ArrayList<NativeItemEntitySnapshot>(); int bytes = 0;
        for (var owner : entityOwners.values()) {
            if (owner.consumed != null) { requireConsumed(owner); continue; }
            if (!(owner.entity instanceof ItemEntity item) || item.isRemoved()) throw new SurvivalRefusal("Unadmitted menu item owner");
            int remaining = nativePayloadAllowance() - bytes - Integer.BYTES;
            if (remaining < 1) throw new SurvivalRefusal("Menu retained item snapshot allowance exhausted");
            var image = NativeItemEntitySnapshot.capture(item, Math.min(remaining, NativeItemEntitySnapshot.MAX_BYTES));
            bytes = Math.addExact(bytes, Integer.BYTES + image.encodedBytes()); images.add(image);
        }
        return List.copyOf(images);
    }

    /** Only this entrypoint establishes the exact live inventory source for this effect host. */
    public NativeWholeAction begin(NativeSurvivalWorld.ActionIdentity identity,
            Map<CubePos, NativeSurvivalWorld.CubeStamp> expected) {
        refuseMiningReentry("generic action begin");refuseMenuOpeningReentry();requireNoRetainedActiveSaveForAction();
        if(movePermit!=null)movePermit.claimBegin(identity);
        NativeSessionExecutionGate.requireActionOpen(player);
        if (sessionMenuOwner.retained()) throw new SurvivalRefusal("Close the retained native menu before another gameplay action");
        checkNativeIdentity(); reapAborted();
        if (actions.fenced() || current != null || pending != null) throw new SurvivalRefusal("Native delivery owner is busy or fenced");
        requireBinding();
        if (binding.world() != world || binding.actions() != actions)
            throw new SurvivalRefusal("Native delivery requires its exact attached cubic world/action binding");
        requireIdentity(identity); events.requireDeliveryCallbacks();
        NativeInventorySnapshot.requireResolvedMenu(player);
        checkNativeRoster();
        try {
            inventoryBefore = snapshotInventory(); selectedBefore = player.getInventory().selected;
            foodBefore = NativeFoodSnapshot.capture(player.getFoodData());
            poseBefore = NativePlayerPoseSnapshot.capture(player);
            current = actions.begin(identity, expected, NativeWholeAction.inventoryOf(player));
            return current;
        } catch (RuntimeException | Error failure) { inventoryBefore = null; foodBefore = null; poseBefore = null; throw failure; }
    }

    @Override public NativeWholeAction.PreparedEffects prepare(NativeSurvivalWorld.ActionIdentity identity,
            List<NativeEffectCodec.Effect> effects) {
        if(miningReservation!=null)miningReservation.enterPrevalidation();
        if (blockProofOpening != null && (blockBreak == null || current == null || current.state() != NativeWholeAction.State.PREVALIDATING)) {
            blockProofOpening.poisoned = true; poisonNestedOwnedPreparation();
            throw new SurvivalRefusal("Native block proof cannot enter a foreign preparation callback");
        }
        if (menuPacket != null || menuClose != null || menuOpen != null) return prepareRetainedMenu(identity, effects);
        if(miningReservation!=null)NativeSessionExecutionGate.requireNativeMiningOpen(player,this);
        else if (blockBreak != null) NativeSessionExecutionGate.requireServerBreakProofOpen(player, this);
        else NativeSessionExecutionGate.requireActionOpen(player);
        checkNativeIdentity(); requireIdentity(identity); requireBinding();
        if (current == null || !actions.owns(current) || NativeWholeAction.current() != current
                || current.state() != NativeWholeAction.State.PREVALIDATING || pending != null)
            throw new SurvivalRefusal("Native effects do not belong to the host's exact validating action");
        if(playerStep!=null&&(effects.size()!=2||!(effects.get(0) instanceof NativeEffectCodec.RandomSeed)
                ||!playerStep.effect.equals(effects.get(1))||world.usage().transactionWrites()!=0))
            throw playerStep.rejected("Native MOVE journal must be exactly one seed and its command, with zero writes");
        events.requireDeliveryCallbacks(); validateInventory(); validateFood(); validatePose(); checkNativeRoster();
        if (effects.size() > world.limits().effects()) throw new SurvivalRefusal("Native effect batch exceeds the action limit");
        boolean hasPlacementEffect = effects.stream().anyMatch(effect -> effect instanceof NativeEffectCodec.PlacementSound
                || effect instanceof NativeEffectCodec.BlockPlaceEvent);
        if (hasPlacementEffect && dryPlacement == null)
            throw new SurvivalRefusal("Native placement effects lack their exact private preparation plan");
        if (dryPlacement != null) dryPlacement.requireBeforeCommit(current, effects);
        if(miningReservation!=null)miningOwner.requireBeforeCommit(current,effects);
        if (blockBreak != null) blockBreak.requireBeforeCommit(current, effects);
        Batch batch = new Batch(identity, world.commitSequence(), effects);
        batch.placement = dryPlacement; batch.nativeBlockBreak = blockBreak;batch.nativeMining=miningReservation!=null;
        Set<Integer> changedSlots = new HashSet<>(); Set<UUID> newIds = new HashSet<>();
        Map<Stat<?>, Integer> amounts = new HashMap<>();
        int entityCount = 0, metadataCount = 0, seeds = 0;
        for (var effect : effects) {
            if (effect instanceof NativeEffectCodec.ItemSpawn || effect instanceof NativeEffectCodec.ExperienceSpawn) entityCount++;
            else if (effect instanceof NativeEffectCodec.ScheduledBlockTick) { /* Coalesced by native queue uniqueness below. */ }
            else if (effect instanceof NativeEffectCodec.ResolvedMenuScript script)
                metadataCount = checkedAdd(metadataCount, Math.max(1, script.transcript().notices().size()), "native menu metadata count");
            else if (effect instanceof NativeEffectCodec.ResolvedTableScript script)
                metadataCount = checkedAdd(metadataCount, Math.max(1, script.transcript().notices().size()), "native table metadata count");
            else if (effect instanceof NativeEffectCodec.MiningControl) {
                if(!batch.nativeMining||miningReservation==null||miningOwner==null)
                    throw new SurvivalRefusal("Public mining evidence has no privately reserved native plan");
                metadataCount=checkedAdd(metadataCount,miningOwner.noticeCount()+1,"native mining output count");
            }
            else if (effect instanceof NativeEffectCodec.BlockBreakMetadata) metadataCount = checkedAdd(metadataCount,2,"native break metadata count");
            else if (!(effect instanceof NativeEffectCodec.InventorySlot) && !(effect instanceof NativeEffectCodec.RandomSeed)) metadataCount++;
        }
        requireCapacity(entityOwners.size(), entityCount, limits.entityOwners(), "native entity owners");
        requireCapacity(0, metadataCount, limits.metadataEffects(), "native notification metadata");
        for (var effect : effects) {
            if (menuScript != null && !(effect instanceof NativeEffectCodec.RandomSeed
                    || effect instanceof NativeEffectCodec.InventorySlot || effect instanceof NativeEffectCodec.ResolvedMenuScript))
                throw new SurvivalRefusal("Native menu script cannot combine unrelated effects");
            if (tableScript != null && !(effect instanceof NativeEffectCodec.RandomSeed
                    || effect instanceof NativeEffectCodec.InventorySlot || effect instanceof NativeEffectCodec.ResolvedTableScript))
                throw new SurvivalRefusal("Native table script cannot combine unrelated effects");
            if(playerStep!=null&&!(effect instanceof NativeEffectCodec.RandomSeed||effect instanceof NativeEffectCodec.PlayerMove))
                throw new SurvivalRefusal("Native MOVE cannot combine unrelated effects");
            if (itemTick != null && !(effect instanceof NativeEffectCodec.RandomSeed || effect instanceof NativeEffectCodec.ItemTick))
                throw new SurvivalRefusal("Accepted native item tick cannot combine unrelated effects");
            if (pickup != null && !(effect instanceof NativeEffectCodec.RandomSeed || effect instanceof NativeEffectCodec.InventorySlot
                    || effect instanceof NativeEffectCodec.PlayerStat || effect instanceof NativeEffectCodec.ItemPickup))
                throw new SurvivalRefusal("Native pickup dispatcher cannot combine unrelated effects");
            if (effect instanceof NativeEffectCodec.RandomSeed seed) {
                if (++seeds != 1 || !seed.world().equals(world.world()) || !seed.ownerEpoch().equals(identity.ownerEpoch())
                        || !seed.actor().equals(actor) || seed.sequence() != identity.sequence())
                    throw new SurvivalRefusal("Foreign or duplicate native action random metadata");
            } else if (effect instanceof NativeEffectCodec.MiningControl control) {
                if(!batch.nativeMining||miningReservation==null||!control.equals(miningOwner.effect())
                        ||control!=effects.getLast())throw new SurvivalRefusal("Unretained or nonterminal native mining evidence");
            } else if (effect instanceof NativeEffectCodec.InventorySlot slot) {
                if (!changedSlots.add(slot.slot()) || !slot.before().equals(inventoryBefore[slot.slot()]))
                    throw new SurvivalRefusal("Native inventory before-image is foreign or duplicated");
                ItemStack after = slot.after().materialize(registries);
                validateStack(after); batch.inventory.add(new InventoryChange(slot.slot(), after));
            } else if (effect instanceof NativeEffectCodec.ResolvedMenuScript script) {
                if (menuScript == null || batch.resolvedMenu != null || !menuScript.effect().equals(script)
                        || !script.world().equals(world.world()) || !script.ownerEpoch().equals(identity.ownerEpoch())
                        || !script.actor().equals(actor) || script.sequence() != identity.sequence()
                        || pickup != null || itemTick != null || world.usage().transactionWrites() != 0)
                    throw new SurvivalRefusal("Native menu script lacks its exact effect-only single-use captured plan");
                menuScript.requireBeforeCommit(current, script);
                batch.resolvedMenu = menuScript;
            } else if (effect instanceof NativeEffectCodec.ResolvedTableScript script) {
                if (tableScript == null || batch.resolvedTable != null || !tableScript.effect().equals(script)
                        || !script.world().equals(world.world()) || !script.ownerEpoch().equals(identity.ownerEpoch())
                        || !script.actor().equals(actor) || script.sequence() != identity.sequence()
                        || menuScript != null || pickup != null || itemTick != null || world.usage().transactionWrites() != 0)
                    throw new SurvivalRefusal("Native table script lacks its exact effect-only single-use captured plan");
                tableScript.requireBeforeCommit(current, script);
                batch.resolvedTable = tableScript;
            } else if (effect instanceof NativeEffectCodec.ItemSpawn item) {
                requireNewEntity(item.id(), newIds, item.x(), item.y(), item.z());
                ItemStack stack = item.stack().materialize(registries); validateStack(stack); requireOrdinaryItem(stack);
                // This velocity-taking overload never calls Level.random. Native construction
                // callbacks have no effective listeners; allocation entropy is not gameplay RNG.
                ItemEntity entity = new ItemEntity(level, item.x(), item.y(), item.z(), stack,
                        item.velocityX(), item.velocityY(), item.velocityZ());
                entity.setUUID(item.id());
                CompoundTag data = new CompoundTag(); entity.addAdditionalSaveData(data);
                data.putShort("Age", (short) item.age()); data.putShort("PickupDelay", (short) item.pickupDelay());
                entity.readAdditionalSaveData(data);
                requirePreparedEntity(entity, item.id(), item.x(), item.y(), item.z());
                requireVelocity(entity, item.velocityX(), item.velocityY(), item.velocityZ());
                if (level.getEntity(entity.getId()) != null) throw new SurvivalRefusal("Native entity numeric ID collision");
                if (!item.stack().matches(entity.getItem(), registries)) throw new SurvivalRefusal("Native item construction changed full stack data");
                // Reuse the data-only durable profile before commit, including native load clamps,
                // full components/attachments and bounded NBT. No second constructor/loot call.
                NativeItemEntitySnapshot.capture(entity,blockBreak==null?NativeItemEntitySnapshot.MAX_BYTES:NativeBlockBreakPlan.DROP_IMAGE_MAX_BYTES);
                batch.entities.add(entity);
            } else if (effect instanceof NativeEffectCodec.ItemPickup item) {
                if (pickup == null || pickup.action != current || batch.pickup != null || !item.equals(pickup.result.effect()))
                    throw new SurvivalRefusal("Native pickup lacks its exact single-use captured result");
                events.requirePickupEventsEmpty(); requirePickupSource(pickup.source, item.before());
                ItemStack remainder = validatePickupResult(item, registries);
                batch.pickup = new PreparedPickup(pickup, remainder);
            } else if(effect instanceof NativeEffectCodec.PlayerMove move){
                if(playerStep==null||playerStep.action!=current||batch.nativePlayerStep!=null||!playerStep.effect.equals(move)
                        ||!move.world().equals(world.world())||!move.ownerEpoch().equals(world.ownerEpoch())||!move.actor().equals(actor)
                        ||move.sequence()!=identity.sequence())throw new SurvivalRefusal("MOVE command has no exact retained private plan");
                playerStep.requireBeforeCommit();batch.nativePlayerStep=playerStep;
            } else if (effect instanceof NativeEffectCodec.ItemTick tick) {
                if (itemTick == null || itemTick.action != current || batch.nativeItemTick != null || !itemTick.effect.equals(tick)
                        || world.usage().transactionWrites() != 0)
                    throw new SurvivalRefusal("Native item tick lacks its exact effect-only accepted plan");
                requireItemTickSource(itemTick.source, tick.before(), tick.tickCount());
                events.requireItemTickEventsEmpty(); nativeState.requireTickGameListeners(level, itemTick.source.blockPosition(), itemTick.listenerRadius, limits.metadataEffects());
                requireTickDestinations(itemTick.box); batch.nativeItemTick = itemTick;
            } else if (effect instanceof NativeEffectCodec.ExperienceSpawn) {
                throw new SurvivalRefusal("Native XP delivery requires its complete checkpoint codec before committing");
            } else if (effect instanceof NativeEffectCodec.ScheduledBlockTick tick) {
                requireCell(tick.cell()); batch.tickData.add(tick);
            } else if (effect instanceof NativeEffectCodec.PlayerStat stat) {
                Stat<?> nativeStat = resolveStat(stat);
                int amount = checkedAdd(amounts.getOrDefault(nativeStat, 0), stat.amount(), "native statistic amount");
                amounts.put(nativeStat, amount);
                checkedAdd(player.getStats().getValue(nativeStat), amount, "native statistic value");
                // Native scoreboard writes can create objectives/scores and notify dirty listeners.
                // Keep this first capability exact and callback-free; no objectives are admitted.
                if (!player.getScoreboard().getObjectives().isEmpty())
                    throw new SurvivalRefusal("Native statistic scoreboard objectives require captured delivery");
            } else if (effect instanceof NativeEffectCodec.PlayerExhaustion exhaustion) {
                batch.exhaustion.add(exhaustion.amount());
            } else if (effect instanceof NativeEffectCodec.Sound sound) {
                requirePosition(sound.x(), sound.y(), sound.z());
                requireSoundCoordinate(sound.x()); requireSoundCoordinate(sound.y()); requireSoundCoordinate(sound.z());
                Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.getHolder(
                        ResourceKey.create(Registries.SOUND_EVENT, sound.sound())).orElseThrow(() -> new SurvivalRefusal("Unknown native sound"));
                SoundSource category = null;
                for (SoundSource candidate : SoundSource.values()) if (candidate.getName().equals(sound.category())) category = candidate;
                if (category == null || !Float.isFinite(holder.value().getRange(sound.volume())))
                    throw new SurvivalRefusal("Unsupported native sound category/range");
                batch.sounds.add(new PreparedSound(holder, category, sound, null));
            } else if (effect instanceof NativeEffectCodec.GameEvent event) {
                BlockPos pos = requireCell(event.cell());
                Holder<GameEvent> holder = registries.lookupOrThrow(Registries.GAME_EVENT)
                        .getOrThrow(ResourceKey.create(Registries.GAME_EVENT, event.event()));
                Entity source = event.source() == null ? null : requireSource(event.source());
                nativeState.requireNoGameListeners(level, pos, holder.value().notificationRadius());
                batch.gameEvents.add(new PreparedEvent(holder, pos, source, null));
            } else if (effect instanceof NativeEffectCodec.PlacementSound sound) {
                if (!actor.equals(sound.excludedActor())) throw new SurvivalRefusal("Native placement sound excludes a foreign actor");
                requirePosition(sound.x(), sound.y(), sound.z());
                requireSoundCoordinate(sound.x()); requireSoundCoordinate(sound.y()); requireSoundCoordinate(sound.z());
                Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.getHolder(
                        ResourceKey.create(Registries.SOUND_EVENT, sound.sound())).orElseThrow(() -> new SurvivalRefusal("Unknown native placement sound"));
                SoundSource category = null;
                for (SoundSource candidate : SoundSource.values()) if (candidate.getName().equals(sound.category())) category = candidate;
                if (category == null || !Float.isFinite(holder.value().getRange(sound.volume())))
                    throw new SurvivalRefusal("Unsupported native placement sound category/range");
                batch.sounds.add(new PreparedSound(holder, category, new NativeEffectCodec.Sound(sound.sound(), sound.category(),
                        sound.x(), sound.y(), sound.z(), sound.volume(), sound.pitch(), sound.seed()), player));
            } else if (effect instanceof NativeEffectCodec.BlockBreakMetadata broken) {
                if (blockBreak == null || !blockBreak.metadata().equals(broken) || !broken.world().equals(world.world())
                        || !broken.actor().equals(actor)) throw new SurvivalRefusal("Native break metadata lacks its exact private plan");
                BlockPos pos = requireCell(broken.cell()); world.palette().state(broken.beforeStateId());
                NativeBlockBreakPlan.requireMetadataCoordinates(broken.cell());
                if (!world.read(broken.cell()).is(net.minecraft.world.level.block.Blocks.AIR))
                    throw new SurvivalRefusal("Native break final target is not AIR");
                nativeState.requireNoGameListeners(level,pos,GameEvent.BLOCK_DESTROY.value().notificationRadius());
                nativeState.requireTickGameListeners(level,pos,GameEvent.BLOCK_DESTROY.value().notificationRadius(),limits.metadataEffects());
            } else if (effect instanceof NativeEffectCodec.BlockPlaceEvent event) {
                if (!event.world().equals(world.world()) || !event.actor().equals(actor))
                    throw new SurvivalRefusal("Native placement event has foreign world/actor authority");
                BlockPos pos = requireCell(event.cell()); BlockState affected = world.palette().state(event.affectedStateId());
                if (world.read(event.cell()) != affected)
                    throw new SurvivalRefusal("Native placement event differs from the prepared final cubic state");
                nativeState.requireNoGameListeners(level, pos, GameEvent.BLOCK_PLACE.value().notificationRadius());
                batch.gameEvents.add(new PreparedEvent(GameEvent.BLOCK_PLACE, pos, player, affected));
            } else if (effect instanceof NativeEffectCodec.BlockNotification notification) {
                if (!notification.world().equals(world.world())) throw new SurvivalRefusal("Foreign native block notification world");
                requireCell(notification.cell()); world.palette().state(notification.beforeStateId());
                world.palette().state(notification.afterStateId());
                batch.notifications.add(notification);
            } else throw new SurvivalRefusal("Unsupported native effect kind");
        }
        // First admitted native mutation emits one game event. A multi-event batch needs a
        // union reservation of newly created section registries before it can be supported.
        requirePreparedGameEventCount(batch.gameEvents.size() + (batch.nativeBlockBreak == null ? 0 : 1));
        if (batch.nativeBlockBreak != null) batch.nativeBlockBreak.admitPreparedDrops(batch.entities);
        for (var event : batch.gameEvents)
            nativeState.requireTickGameListeners(level, event.pos(), event.event().value().notificationRadius(), limits.metadataEffects());
        Map<CubeCell, NativeEffectCodec.BlockNotification> lastNotification = new HashMap<>();
        for (var notification : batch.notifications) {
            var previous = lastNotification.put(notification.cell(), notification);
            if (previous != null && previous.afterStateId() != notification.beforeStateId())
                throw new SurvivalRefusal("Native block notification chain is discontinuous");
        }
        for (var notification : lastNotification.values())
            if (world.read(notification.cell()) != world.palette().state(notification.afterStateId()))
                throw new SurvivalRefusal("Final block notification differs from prepared cubic state");
        if (seeds != 1) throw new SurvivalRefusal("Native action seed metadata is missing");
        if (menuScript != null && batch.resolvedMenu == null)
            throw new SurvivalRefusal("Native menu captured transcript was omitted");
        if (tableScript != null && batch.resolvedTable == null)
            throw new SurvivalRefusal("Native table captured transcript was omitted");
        if(playerStep!=null&&batch.nativePlayerStep==null)throw new SurvivalRefusal("Captured native MOVE was omitted");
        if (itemTick != null && batch.nativeItemTick == null) throw new SurvivalRefusal("Captured native tick was omitted");
        if (pickup != null) {
            if (batch.pickup == null) throw new SurvivalRefusal("Native pickup captured result was omitted");
            ItemStack stack = pickup.result.effect().before().stack().materialize(registries);
            Stat<?> picked = net.minecraft.stats.Stats.ITEM_PICKED_UP.get(stack.getItem());
            int taken = pickup.result.effect().takeAmount();
            if (taken == 0 ? !amounts.isEmpty() : amounts.size() != 1 || amounts.getOrDefault(picked, 0) != taken)
                throw new SurvivalRefusal("Native pickup statistics differ from the captured native callback path");
        }
        amounts.forEach((stat, amount) -> batch.stats.add(new Statistic(stat, player.getStats().getValue(stat), amount)));
        batch.tickPlan = scheduledTicks.prepare(batch.tickData, level.getGameTime(), identity);
        batch.projectedBoundary = projectBoundary(batch);
        if(batch.nativeBlockBreak!=null)batch.nativeBlockBreak.imageSlot().admitProjectedBoundary(batch.projectedBoundary);
        events.requireDeliveryCallbacks(); validateInventory(); validateFood(); validatePose();
        if (batch.resolvedMenu != null) {
            checkedMenuMetadataCount(metadataCount, batch.resolvedMenu.reservedDeliveryOperations(), limits.metadataEffects());
            checkedMenuEffectBytes(batch.encoded, batch.resolvedMenu.reservedDeliveryBytes(), world.limits().effectBytes());
            batch.resolvedMenu.requireBeforeCommit(current, batch.resolvedMenu.effect());
        }
        if (batch.resolvedTable != null) {
            checkedMenuMetadataCount(metadataCount, batch.resolvedTable.reservedDeliveryOperations(), limits.metadataEffects());
            checkedMenuEffectBytes(batch.encoded, batch.resolvedTable.reservedDeliveryBytes(), world.limits().effectBytes());
            batch.resolvedTable.requireBeforeCommit(current, batch.resolvedTable.effect());
        }
        if(batch.nativePlayerStep!=null)moveBudget.reserveDelivery(2*NativePlayerStepBudget.SNAPSHOT+2*NativePlayerStepBudget.INVENTORY);
        // Ownership is installed only after complete allocation and validation. Aborted precommit
        // scopes can release this reservation; attempted or failed application never can.
        pending = batch; return batch;
    }

    private final class Batch implements NativeWholeAction.PreparedEffects {
        final NativeSurvivalWorld.ActionIdentity identity;
        final long beforeCommit;
        final List<NativeActionEffect> encoded;
        final List<InventoryChange> inventory = new ArrayList<>();
        final List<Entity> entities = new ArrayList<>();
        final List<NativeEffectCodec.ScheduledBlockTick> tickData = new ArrayList<>();
        NativeScheduledTickQueue.Prepared tickPlan;
        PreparedPickup pickup;
        ItemTickPlan nativeItemTick;
        PlayerStepPlan nativePlayerStep;
        NativeResolvedInventoryMenuPlan resolvedMenu;
        NativeResolvedCraftingTablePlan resolvedTable;
        NativeDryPlacementPlan placement;
        NativeBlockBreakPlan nativeBlockBreak;
        boolean nativeMining;
        final List<Statistic> stats = new ArrayList<>();
        final List<Float> exhaustion = new ArrayList<>();
        final List<PreparedSound> sounds = new ArrayList<>();
        final List<PreparedEvent> gameEvents = new ArrayList<>();
        final List<NativeEffectCodec.BlockNotification> notifications = new ArrayList<>();
        NativeSurvivalCheckpoint.Boundary projectedBoundary;
        boolean attempted, failed;
        Batch(NativeSurvivalWorld.ActionIdentity identity, long beforeCommit, List<NativeEffectCodec.Effect> effects) {
            this.identity = identity; this.beforeCommit = beforeCommit;
            encoded = effects.stream().map(NativeEffectCodec::encode).toList();
        }
        @Override public void apply(NativeSurvivalWorld.CommitReceipt receipt) {
            world.checkOwner();
            if (attempted || pending != this) throw new SurvivalRefusal("Native prepared batch cannot be repeated or replaced");
            attempted = true;
            try {
                checkNativeIdentity(); requireBinding(); events.requireDeliveryCallbacks();
                if (!receipt.world().equals(world.world()) || !receipt.ownerEpoch().equals(world.ownerEpoch())
                        || !receipt.action().equals(identity) || receipt.commitSequence() != Math.addExact(beforeCommit, 1)
                        || receipt.commitSequence() != world.commitSequence() || !receipt.effects().equals(encoded)
                        || current == null || !actions.owns(current) || NativeWholeAction.current() != current
                        || current.state() != NativeWholeAction.State.APPLYING)
                    throw new SurvivalRefusal("Native prepared batch received a foreign/stale receipt");
                validateInventory(); validateFood(); validatePose();
                NativeInventorySnapshot.requireResolvedMenu(player);
                if (projectedBoundary.gameTime() != level.getGameTime())
                    throw new SurvivalRefusal("Native game clock changed after preparation");
                for (var stat : stats) if (player.getStats().getValue(stat.stat()) != stat.before())
                    throw new SurvivalRefusal("Native statistic changed after preparation");
                for (var event : gameEvents) nativeState.requireTickGameListeners(level, event.pos(), event.event().value().notificationRadius(), limits.metadataEffects());
                if (pickup != null) {
                    events.requirePickupEventsEmpty();
                    requirePickupSource(pickup.plan().source, pickup.plan().result.effect().before());
                }
                if (resolvedMenu != null) {
                    if (resolvedMenu != menuScript || !receipt.changedCubes().isEmpty())
                        throw new SurvivalRefusal("Native menu receipt changed terrain or replaced its retained plan");
                    resolvedMenu.requireBeforeApply(current, receipt);
                }
                if (resolvedTable != null) {
                    if (resolvedTable != tableScript || !receipt.changedCubes().isEmpty())
                        throw new SurvivalRefusal("Native table receipt changed terrain or replaced its retained plan");
                    resolvedTable.requireBeforeApply(current, receipt);
                    resolvedTable.open(receipt);
                }
                if (placement != null) {
                    if (placement != dryPlacement) throw new SurvivalRefusal("Native placement lost its retained plan");
                    placement.requireBeforeApply(current, receipt);
                }
                if(nativeMining)miningOwner.requireBeforeApply(current,receipt);
                if (nativeBlockBreak != null) {
                    if (nativeBlockBreak != blockBreak) throw new SurvivalRefusal("Native break lost its privately retained plan");
                    if(nativeMining)NativeSessionExecutionGate.requireNativeMiningOpen(player,NativeEffectDeliveryHost.this);
                    else NativeSessionExecutionGate.requireServerBreakProofOpen(player, NativeEffectDeliveryHost.this);
                    nativeState.requireTickGameListeners(level,io.earthplatform.minecraft.survival.interaction.NativeVanillaPreparation.nativePos(nativeBlockBreak.target()),
                            GameEvent.BLOCK_DESTROY.value().notificationRadius(),limits.metadataEffects());
                    nativeBlockBreak.requireBeforeApply(current,receipt);
                    if(nativeMining)miningOwner.applyBeforeBreak(current,receipt);
                    nativeBlockBreak.emitDestructionMetadata(receipt);
                    for (var stat : stats) player.awardStat(stat.stat(),stat.amount());
                    for (float amount : exhaustion) player.causeFoodExhaustion(amount);
                }
                if(nativeMining&&nativeBlockBreak==null)miningOwner.applyBeforeBreak(current,receipt);
                for (var slot : inventory) player.getInventory().setItem(slot.slot(), slot.after());
                // Captured native menu plans own timesChanged; the narrow retained native placement
                // consumes the original held stack without calling Inventory.setChanged.
                if (!inventory.isEmpty() && resolvedMenu == null && resolvedTable == null && placement == null) player.getInventory().setChanged();
                if (resolvedMenu != null) {
                    resolvedMenu.apply(receipt);
                    requireResolvedMenuOutcome(projectedBoundary);
                }
                if (resolvedTable != null) {
                    // The private plan owns the brief CraftingMenu interval, closes it natively,
                    // then the generic complete-boundary checks become valid again.
                    resolvedTable.apply(receipt);
                    requireResolvedMenuOutcome(projectedBoundary);
                }
                for (var entity : entities) {
                    // Retain the exact native object before even attempting insertion. An uncertain
                    // insertion failure may already own native sections/tracking and cannot retry.
                    entityOwners.put(entity.getUUID(), new EntityOwner(entity));
                    if (!level.addFreshEntity(entity)) throw new SurvivalRefusal("Native entity insertion unexpectedly refused");
                }
                if (nativeBlockBreak != null) nativeBlockBreak.finishDropCallbacks(receipt);
                if (pickup != null) {
                    var effect = pickup.plan().result.effect(); ItemEntity source = pickup.plan().source;
                    if (effect.after() == null) {
                        source.discard();
                        if (!source.isRemoved() || source.getRemovalReason() != Entity.RemovalReason.DISCARDED
                                || level.getEntity(source.getUUID()) != null || level.getEntity(source.getId()) != null)
                            throw new SurvivalRefusal("Native consumed pickup is still tracked or changed removal reason");
                        // Retain source and the exact successful lifecycle receipt. A later failure
                        // retains this prefix too; no checkpoint may observe a fenced action.
                        entityOwners.get(source.getUUID()).consumed = new PickupConsumption(receipt, effect);
                    } else {
                        source.setItem(pickup.remainder());
                        if (!effect.after().equals(NativeItemEntitySnapshot.capture(source)))
                            throw new SurvivalRefusal("Native partial pickup differs from its prepared full state");
                    }
                }
                if (nativeItemTick != null) {
                    if (!receipt.changedCubes().isEmpty()) throw new SurvivalRefusal("Accepted native item tick changed cubic blocks");
                    nativeItemTick.receipt = receipt;
                    NativeItemTickReadScope.execute(nativeItemTick);
                    nativeItemTick.verifyAfter();
                    validateInventory(); validateFood(); validatePose(); checkNativeRoster();
                }
                if(nativePlayerStep!=null){
                    if(nativePlayerStep!=playerStep||!receipt.changedCubes().isEmpty())throw new SurvivalRefusal("Native MOVE lost retained plan or changed cubes");
                    nativePlayerStep.receipt=receipt;NativePlayerStepReadScope.execute(nativePlayerStep);nativePlayerStep.verifyAfter();
                    nativePlayerStep.requireConservedItems(projectedBoundary.items());
                    projectedBoundary=new NativeSurvivalCheckpoint.Boundary(projectedBoundary.gameTime(),projectedBoundary.inventory(),
                        nativePlayerStep.after.food(),nativePlayerStep.after.pose(),projectedBoundary.items(),projectedBoundary.scheduledTicks());
                    NativeSurvivalCheckpoint.checkedBoundaryBytes(projectedBoundary.inventory(),projectedBoundary.food(),projectedBoundary.pose(),
                        projectedBoundary.items(),projectedBoundary.scheduledTicks(),nativePayloadAllowance());
                    validateInventory();validateFood();checkNativeRoster();
                }
                tickPlan.apply(receipt);
                if (nativeBlockBreak == null) {
                    for (var stat : stats) player.awardStat(stat.stat(), stat.amount());
                    for (float amount : exhaustion) player.causeFoodExhaustion(amount);
                }
                if (!projectedBoundary.food().equals(NativeFoodSnapshot.capture(player.getFoodData())))
                    throw new SurvivalRefusal("Native food delivery differs from its prepared outcome");
                for (var sound : sounds) {
                    var data = sound.data();
                    level.playSeededSound(sound.excluded(), data.x(), data.y(), data.z(), sound.sound(), sound.source(),
                            data.volume(), data.pitch(), data.seed());
                }
                for (var event : gameEvents)
                    level.gameEvent(event.event(), Vec3.atCenterOf(event.pos()), GameEvent.Context.of(event.source(), event.affectedState()));
                // BlockNotification stays receipt/publication metadata. In particular, never call
                // sendBlockUpdated, markAndNotifyBlock, setBlock or neighbor callbacks here.
                if (placement != null) {
                    // Sound/game-event listeners are source-closed and admitted before commit.
                    // Verify every projected native owner after those original deliveries too.
                    placement.verifyAfter(receipt);
                    requireResolvedMenuOutcome(projectedBoundary);
                }
                if(nativeMining)miningOwner.deliverAfterBreak(current,receipt);
                if(nativePlayerStep==null)validatePose();
                else if(!nativePlayerStep.after.pose().equals(NativePlayerPoseSnapshot.capture(player)))throw new SurvivalRefusal("MOVE pose changed after verified native return");
                if(nativeMining) {
                    if(nativeBlockBreak==null)requireMiningControlOutcome(projectedBoundary,receipt);
                    miningOwner.verifyAfterOutputs(current,receipt,projectedBoundary.food());
                }
                if (nativeBlockBreak != null) {
                    requireBlockBreakOutcome(projectedBoundary,nativeBlockBreak,receipt);
                    nativeBlockBreak.verifyAfter(receipt);
                }
                if(nativeMining)miningOwner.finishDelivered(current,receipt);
                if(acceptedBreakLineage!=null) {
                    if(nativePlayerStep!=null)acceptedBreakLineage.noteDeliveredMove(nativePlayerStep,receipt);
                    else acceptedBreakLineage.moveInvalid=true;
                }
                lastApplied = receipt; pending = null;
            } catch (RuntimeException | Error failure) { failed = true; throw failure; }
        }
    }

    public Usage usage() {
        world.checkOwner();
        int pendingEntities = pending == null ? 0 : (int) pending.entities.stream().filter(entity -> !entityOwners.containsKey(entity.getUUID())).count();
        int pendingTicks = pending == null || pending.tickPlan == null ? 0 : pending.tickPlan.pendingTickCount();
        return new Usage(entityOwners.size(), pendingEntities, scheduledTicks.size(), pendingTicks, (pending != null && pending.failed) || (pendingMenu != null && pendingMenu.failed));
    }

    /** Full actual native queue and live item roster, suitable only at an idle whole-action boundary. */
    public Capture captureNativeState() {
        if(miningLineageCharged)miningOwner.requireSettledForTransition();
        rejectDuringMove("captureNativeState");
        refuseMenuOpeningReentry();
        checkNativeIdentity(); reapAborted(); requireBinding();
        if (sessionMenuOwner.retained() || pendingMenu != null || current != null || pending != null || actions.fenced() || actions.hasCurrentAction() || world.hasActiveTransaction())
            throw new SurvivalRefusal("Native checkpoint capture requires quiescent successful delivery");
        events.requireDeliveryCallbacks(); checkNativeRoster();
        var items = new ArrayList<ItemEntity>();
        for (var entry : entityOwners.entrySet()) {
            EntityOwner owner = entry.getValue(); Entity entity = owner.entity;
            if (entity.hasAttachments()) throw new SurvivalRefusal("Native entity attachments require separate checkpoint admission");
            if (owner.consumed != null) { requireConsumed(owner); continue; }
            if (entity.isRemoved())
                throw new SurvivalRefusal("Removed/unloaded native item requires a captured lifecycle receipt before checkpoint omission");
            if (entity instanceof ExperienceOrb) throw new SurvivalRefusal("Native XP checkpoint codec is not yet admitted");
            if (!(entity instanceof ItemEntity item) || item.getClass() != ItemEntity.class)
                throw new SurvivalRefusal("Unknown native entity checkpoint owner");
            items.add(item);
        }
        return new Capture(this, world.commitSequence(), level.getGameTime(), items, scheduledTicks.snapshot());
    }

    /** One exact coordinator owns durability for this host; replacement never grants retirement. */
    public void attachCheckpointCoordinator(SurvivalCheckpointCoordinator coordinator) {
        rejectDuringMove("attachCheckpointCoordinator");
        refuseMenuOpeningReentry();
        if (transitionGate == null) captureNativeState(); else { checkTransitionIdentity(); requireBinding(); requireTransitionIdle(); }
        Objects.requireNonNull(coordinator);
        var identity = coordinator.binding();
        if (checkpointCoordinator != null || coordinator.actions() != actions
                || coordinator.state() != SurvivalCheckpointCoordinator.State.IDLE || coordinator.pendingTicket().isPresent() || world.hasPendingCheckpoint()
                || !checkpointLimits.equals(coordinator.limits()) || !identity.world().equals(world.world())
                || !identity.player().equals(actor) || !identity.dimension().equals(level.dimension().location()))
            throw new SurvivalRefusal("Native checkpoint coordinator is foreign, busy or already attached");
        if(transitionGate!=null&&NativeSessionExecutionGate.holdsInstalledInspection(player))coordinator.requireGlobalPersistenceReservation();
        checkpointCoordinator = coordinator; checkpointBinding = identity;
    }

    /**
     * Freezes the complete exact host roster through its bound coordinator. The private candidate
     * token is retained even if encoding fails after the coordinator freezes its ticket.
     * Submission/retry/poll remain genuine coordinator/store operations.
     */
    public void captureCheckpoint(NativeSurvivalCheckpoint.SessionPhase phase) {
        rejectDuringMove("captureCheckpoint");
        refuseMenuOpeningReentry();
        if (transitionGate != null && !ownedTransitionCapture) throw new SurvivalRefusal("Staged host checkpoint capture requires its privately owned transition request");
        Capture source = captureNativeState(); requireCheckpointCoordinator();
        if (checkpointCapture != null || checkpointCoordinator.state() != SurvivalCheckpointCoordinator.State.IDLE
                || checkpointCoordinator.pendingTicket().isPresent() || world.hasPendingCheckpoint())
            throw new SurvivalRefusal("Finish the exact native checkpoint capture before replacing it");
        var candidates = new LinkedHashMap<UUID, RetirementCandidate>();
        Set<UUID> nativeIds = nativeState.knownUuids(level, limits.entityOwners() + limits.audience());
        for (var entry : entityOwners.entrySet()) if (entry.getValue().consumed != null) {
            requireConsumed(entry.getValue()); nativeState.requireRemovedEntityDetached(entry.getValue().entity);
            if (nativeIds.contains(entry.getKey())) throw new SurvivalRefusal("Consumed native item remains registered");
            candidates.put(entry.getKey(), new RetirementCandidate(entry.getValue(), entry.getValue().consumed));
        }
        var capturedSession = new NativeSurvivalCheckpoint.Session(session, Objects.requireNonNull(phase), ordinaryEscrow, ordinaryFoodEscrow, ordinaryPoseEscrow);
        var value = new CheckpointCapture(source.commitSequence(), Math.incrementExact(checkpointCoordinator.durableGeneration()), capturedSession, candidates);
        checkpointCapture = value;
        try {
            SurvivalCheckpointCoordinator.BoundedCapture capture=available -> {
                if (world.commitSequence() != source.commitSequence() || level.getGameTime() != source.gameTime())
                    throw new SurvivalRefusal("Native checkpoint capture clock/commit changed");
                value.boundary = NativeSurvivalCheckpoint.captureBoundary(world, checkpointBinding, checkpointLimits, player,
                        source.items(), source.ticks(), Math.min(available, nativePayloadAllowance()));
                return value.boundary;
            };
            if(ownedTransitionCapture)checkpointCoordinator.captureTransitionBounded(transitionPersistencePurpose,capturedSession,nativePayloadAllowance(),capture);
            else checkpointCoordinator.captureBounded(capturedSession,capture);
        } finally {
            var ticket = checkpointCoordinator.pendingTicket();
            if (ticket.isPresent()) {
                // Preserve all candidates on post-freeze encoding/submission failures for exact retry.
                value.ticket = ticket.get();
                if (value.boundary == null || value.ticket.commitSequence() != value.commitSequence
                        || !value.ticket.ownerEpoch().equals(world.ownerEpoch()) || !value.ticket.world().equals(world.world()))
                    throw new SurvivalRefusal("Native checkpoint did not retain its exact captured boundary/ticket");
            } else if (!world.hasPendingCheckpoint()) {
                checkpointCapture = null; // Failure before freeze granted no persistence or retirement.
            } else {
                // A world freeze without its coordinator token is uncertain. Keep the complete
                // captured boundary/candidates charged; no retry or owner release is invented.
                throw new SurvivalRefusal("Native checkpoint freeze has no exact coordinator ticket; capture retained");
            }
        }
    }

    /** Retires only captured successful pickup CPU owners after this coordinator's actual poll acknowledgement. */
    public int retireDurableConsumedOwners() {
        rejectDuringMove("retireDurableConsumedOwners");
        refuseBlockProofReentry();
        captureNativeState(); requireCheckpointCoordinator();
        var value = checkpointCapture;
        if (value == null || value.ticket == null || checkpointCoordinator.state() != SurvivalCheckpointCoordinator.State.IDLE
                || checkpointCoordinator.pendingTicket().isPresent() || world.hasPendingCheckpoint())
            throw new SurvivalRefusal("Exact native checkpoint has not been durably acknowledged");
        var durable = checkpointCoordinator.durable().orElseThrow(() -> new SurvivalRefusal("Native durable checkpoint is absent"));
        requireExactDurableCapture(durable, value.boundary, checkpointBinding, value.session,
                value.ticket.ownerEpoch(), value.generation, value.commitSequence);
        if (!value.ticket.ownerEpoch().equals(world.ownerEpoch()) || value.commitSequence > world.commitSequence()
                || !value.ticket.actorSequences().equals(durable.actorSequences()))
            throw new SurvivalRefusal("Native retirement capture does not belong to this current owner");
        Set<UUID> present = new HashSet<>();
        for (var item : durable.boundary().items()) present.add(item.id());
        Set<UUID> nativeIds = nativeState.knownUuids(level, limits.entityOwners() + limits.audience());
        // Validate the entire retained candidate set before retiring any prefix.
        for (var entry : value.consumed.entrySet()) {
            RetirementCandidate candidate = entry.getValue(); EntityOwner owner = candidate.owner();
            if (entityOwners.get(entry.getKey()) != owner || owner.consumed != candidate.consumption()
                    || owner.consumed.receipt().commitSequence() > value.commitSequence
                    || present.contains(entry.getKey()) || nativeIds.contains(entry.getKey()))
                throw new SurvivalRefusal("Native consumed owner is absent from the exact durable retirement proof");
            requireConsumed(owner); nativeState.requireRemovedEntityDetached(owner.entity);
        }
        for (var entry : value.consumed.entrySet()) entityOwners.remove(entry.getKey());
        checkpointCapture = null;
        return value.consumed.size();
    }
    /**
     * Recover from another caller overtaking the exact capture on the same coordinator.
     * This abandons only obsolete capture metadata: every native owner/receipt stays charged.
     * Failed, uncertain or same-generation captures cannot use this path.
     */
    public void abandonOvertakenCapture() {
        rejectDuringMove("abandonOvertakenCapture");
        refuseBlockProofReentry();
        captureNativeState(); requireCheckpointCoordinator();
        var value = checkpointCapture;
        if (value == null || value.ticket == null || checkpointCoordinator.state() != SurvivalCheckpointCoordinator.State.IDLE
                || checkpointCoordinator.pendingTicket().isPresent() || world.hasPendingCheckpoint())
            throw new SurvivalRefusal("Only a quiescent overtaken native capture can be abandoned");
        var durable = checkpointCoordinator.durable().orElseThrow(() -> new SurvivalRefusal("Native durable checkpoint is absent"));
        if (durable.boundary() == value.boundary || durable.generation() <= value.generation
                || durable.commitSequence() < value.commitSequence || !durable.captureOwner().equals(world.ownerEpoch())
                || !durable.binding().equals(checkpointBinding) || !durable.session().id().equals(session)
                || !durable.session().ordinaryEscrow().equals(ordinaryEscrow) || !durable.session().ordinaryFood().equals(ordinaryFoodEscrow)
                || !durable.session().ordinaryPose().equals(ordinaryPoseEscrow))
            throw new SurvivalRefusal("Native capture was not superseded by the same session's acknowledged successor");
        checkpointCapture = null;
    }
    private void requireCheckpointCoordinator() {
        if (checkpointCoordinator == null || checkpointCoordinator.actions() != actions
                || !checkpointBinding.equals(checkpointCoordinator.binding()) || !checkpointLimits.equals(checkpointCoordinator.limits()))
            throw new SurvivalRefusal("Native checkpoint coordinator has not been exactly attached");
    }
    /** Data guard only; the mutating retirement method obtains durable solely from its bound coordinator. */
    static void requireExactDurableCapture(NativeSurvivalCheckpoint durable, NativeSurvivalCheckpoint.Boundary boundary,
            NativeSurvivalCheckpoint.Binding binding, NativeSurvivalCheckpoint.Session session, UUID ownerEpoch,
            long generation, long commitSequence) {
        if (durable == null || boundary == null || durable.boundary() != boundary || !durable.binding().equals(binding)
                || !durable.session().equals(session) || !durable.captureOwner().equals(ownerEpoch)
                || durable.generation() != generation || durable.commitSequence() != commitSequence)
            throw new SurvivalRefusal("Native durable retirement requires the identical captured boundary and exact provenance");
    }

    /** Selection only; a native leaf whole-action dispatcher must own eventual execution/removal. */
    public NativeEffectCodec.ScheduledBlockTick nextDueTick(long gameTime) {
        world.checkOwner(); if (actions.fenced()) throw new SurvivalRefusal("Native delivery is fenced");
        return scheduledTicks.nextDue(gameTime);
    }

    /** Read-only order base; callers add bounded staged offsets without mutating the live clock. */
    public long nextAvailableScheduledOrder() {
        checkNativeIdentity(); requireBinding();
        if (actions.fenced() || current == null || !actions.owns(current) || NativeWholeAction.current() != current
                || current.state() != NativeWholeAction.State.PREPARING || pending != null)
            throw new SurvivalRefusal("Native tick order requires this host's exact preparing action");
        return scheduledTicks.nextAvailableScheduledOrder();
    }

    private NativeSurvivalCheckpoint.Boundary projectBoundary(Batch batch) {
        NativeInventorySnapshot.requireResolvedMenu(player);
        var slots = new ArrayList<NativeStackSnapshot>(List.of(inventoryBefore));
        for (var change : batch.inventory) slots.set(change.slot(), NativeStackSnapshot.capture(change.after(), registries));
        if (batch.resolvedMenu != null && !slots.equals(batch.resolvedMenu.inventoryAfter()))
            throw new SurvivalRefusal("Projected native menu inventory differs from its once-captured outcome");
        if (batch.resolvedTable != null && !slots.equals(batch.resolvedTable.inventoryAfter()))
            throw new SurvivalRefusal("Projected native table inventory differs from its once-captured outcome");
        if (batch.pickup != null && !slots.equals(batch.pickup.plan().result.inventoryAfter()))
            throw new SurvivalRefusal("Projected native pickup inventory differs from its one captured native outcome");
        var inventory = new NativeInventorySnapshot(slots, selectedBefore);
        var detachedFood = foodBefore.materialize();
        for (float amount : batch.exhaustion) detachedFood.addExhaustion(amount);
        var food = NativeFoodSnapshot.capture(detachedFood);
        var ticks = batch.tickPlan.projectedSnapshot();
        var items = new ArrayList<NativeItemEntitySnapshot>();
        int bytes = NativeSurvivalCheckpoint.checkedBoundaryBytes(inventory, food, poseBefore, items, ticks, nativePayloadAllowance());
        if (entityOwners.size() + batch.entities.size() > checkpointLimits.items() || ticks.size() > checkpointLimits.scheduledTicks())
            throw new SurvivalRefusal("Projected native boundary exceeds checkpoint count limits");
        for (var owner : entityOwners.values()) {
            if (owner.consumed != null) { requireConsumed(owner); continue; }
            if (batch.pickup != null && owner.entity == batch.pickup.plan().source) {
                var snapshot = batch.pickup.plan().result.effect().after();
                if (snapshot != null) {
                    bytes = Math.addExact(bytes, Math.addExact(Integer.BYTES, snapshot.encodedBytes())); items.add(snapshot);
                    if (bytes > nativePayloadAllowance()) throw new SurvivalRefusal("Projected pickup checkpoint bytes exhausted");
                }
                continue;
            }
            bytes = captureBoundaryItem(owner.entity, items, bytes);
        }
        for (var entity : batch.entities) bytes = captureBoundaryItem(entity, items, bytes);
        NativeSurvivalCheckpoint.checkedBoundaryBytes(inventory, food, poseBefore, items, ticks, nativePayloadAllowance());
        if (batch.nativeItemTick != null) {
            // Native dry movement retains item count/components. Reserve the maximum encoded
            // native entity result before acceptance rather than guessing its post-tick byte size.
            long worstCase = (long) bytes - batch.nativeItemTick.effect.before().encodedBytes() + NativeItemEntitySnapshot.MAX_BYTES;
            if (worstCase > nativePayloadAllowance())
                throw new SurvivalRefusal("Accepted native item tick lacks complete checkpoint payload headroom");
        }
        return new NativeSurvivalCheckpoint.Boundary(level.getGameTime(), inventory, food, poseBefore, items, ticks);
    }

    private int captureBoundaryItem(Entity entity, List<NativeItemEntitySnapshot> items, int bytes) {
        if (!(entity instanceof ItemEntity item) || entity.isRemoved())
            throw new SurvivalRefusal("Projected native entity boundary requires an admitted live item/lifecycle receipt");
        long remaining = (long) nativePayloadAllowance() - bytes - Integer.BYTES;
        if (remaining < 1) throw new SurvivalRefusal("Projected native checkpoint item bytes exhausted");
        int captureBytes=(int)Math.min(remaining,NativeItemEntitySnapshot.MAX_BYTES);
        if(moveBudget!=null){NativePlayerMoveSnapshotAdmission.requireComponents(item.getItem());moveBudget.reserve(NativePlayerStepBudget.PROJECTED_ITEM);captureBytes=Math.min(captureBytes,NativePlayerStepBudget.PROJECTED_ITEM);}
        var snapshot = NativeItemEntitySnapshot.capture(item,captureBytes);
        items.add(snapshot); return Math.addExact(bytes, Math.addExact(Integer.BYTES, snapshot.encodedBytes()));
    }
    private void requireConsumed(EntityOwner owner) {
        var effect = owner.consumed.effect(); var receipt = owner.consumed.receipt(); Entity entity = owner.entity;
        if (!entity.isRemoved() || entity.getRemovalReason() != Entity.RemovalReason.DISCARDED
                || !entity.getUUID().equals(effect.id()) || entity.getId() != effect.entityId() || effect.after() != null
                || !receipt.world().equals(world.world()) || !receipt.ownerEpoch().equals(world.ownerEpoch())
                || !receipt.action().actor().equals(actor) || receipt.commitSequence() > world.commitSequence()
                || entity.level() != level || level.getEntity(entity.getUUID()) != null || level.getEntity(entity.getId()) != null)
            throw new SurvivalRefusal("Native consumed-item receipt is foreign, stale or no longer exact");
    }
    private void requirePickupSource(ItemEntity source, NativeItemEntitySnapshot before) {
        EntityOwner owner = entityOwners.get(source.getUUID());
        if (owner == null || owner.entity != source || owner.consumed != null || source.getClass() != ItemEntity.class
                || source.level() != level || source.isRemoved() || !source.isAlive() || !source.isAddedToLevel()
                || source.isPassenger() || source.isVehicle() || source.hasAttachments() || player.isPassenger()
                || level.getEntity(source.getUUID()) != source || level.getEntity(source.getId()) != source
                || !player.getBoundingBox().inflate(1.0, 0.5, 1.0).intersects(source.getBoundingBox()))
            throw new SurvivalRefusal("Native pickup source identity/tracking/collision is unavailable");
        requireOrdinaryItem(source.getItem());
        if (before != null && !before.equals(NativeItemEntitySnapshot.capture(source)))
            throw new SurvivalRefusal("Native pickup full before-image changed before commit/delivery");
    }
    /** Shared data-only preflight: only native count transfer is admitted by this pickup profile. */
    static ItemStack validatePickupResult(NativeEffectCodec.ItemPickup pickup, HolderLookup.Provider registries) {
        ItemStack before = pickup.before().stack().materialize(registries);
        if (pickup.amount() > before.getCount()) throw new SurvivalRefusal("Native pickup quantity exceeds the source");
        var beforeTag = pickup.before().tagCopy();
        if (beforeTag.contains("Owner") || beforeTag.contains("Thrower"))
            throw new SurvivalRefusal("Thrown/targeted pickup criteria have not been captured");
        int remainder = before.getCount() - pickup.amount();
        if (pickup.after() == null) {
            if (remainder != 0) throw new SurvivalRefusal("Native full pickup lost a remainder");
            return ItemStack.EMPTY;
        }
        ItemStack after = pickup.after().stack().materialize(registries);
        if (remainder <= 0 || after.getCount() != remainder || !ItemStack.isSameItemSameComponents(before, after))
            throw new SurvivalRefusal("Native partial pickup changed components or lost quantity");
        var afterTag = pickup.after().tagCopy(); beforeTag.remove("Item"); afterTag.remove("Item");
        if (!beforeTag.equals(afterTag)) throw new SurvivalRefusal("Native pickup changed unrelated entity state");
        return after;
    }

    private void requireItemTickSource(ItemEntity source, NativeItemEntitySnapshot before, int tickCount) {
        EntityOwner owner = entityOwners.get(source.getUUID());
        if (owner == null || owner.entity != source || owner.consumed != null || source.getClass() != ItemEntity.class
                || source.level() != level || source.isRemoved() || !source.isAlive() || !source.isAddedToLevel()
                || source.isPassenger() || source.isVehicle() || source.hasAttachments()
                || level.getEntity(source.getUUID()) != source || level.getEntity(source.getId()) != source
                || source.tickCount != tickCount || !source.getUUID().equals(before.id())
                || !before.equals(NativeItemEntitySnapshot.capture(source)))
            throw new SurvivalRefusal("Native item tick source identity/full state changed");
        checkNativeRoster();
        int liveItems = 0;
        for (var retained : entityOwners.values()) {
            if (retained.consumed != null) { requireConsumed(retained); continue; }
            if (retained.entity.isRemoved() || retained.entity.getClass() != ItemEntity.class)
                throw new SurvivalRefusal("Native tick roster has an unadmitted lifecycle owner");
            liveItems++;
        }
        if (liveItems != 1) throw new SurvivalRefusal("Native peer merge/cohort ownership is not yet admitted");
        NativeDryItemTickAdmission.requireItem(source); nativeState.requireDryTickState(source);
        var tag = before.tagCopy(); int age = tag.getShort("Age"), lifespan = tag.getInt("Lifespan");
        if (age < 0 || lifespan <= age + 1 || lifespan > Short.MAX_VALUE - 1 || age >= Short.MAX_VALUE - 1
                || tag.contains("Owner") || tag.contains("Thrower") || source.getY() - 3 < (double) level.getMinBuildHeight() - 64
                || !level.getWorldBorder().isWithinBounds(source.getBoundingBox().inflate(3)))
            throw new SurvivalRefusal("Native item tick reaches expiry/criteria/void/border outside the admitted profile");
    }
    private void requireTickDestinations(NativeDryItemTickAdmission.ReadBounds box) {
        for (int x = box.x1() >> 4; x <= (box.x2() >> 4); x++) for (int z = box.z1() >> 4; z <= (box.z2() >> 4); z++) {
            BlockPos pos = new BlockPos(x << 4, box.y1(), z << 4);
            if (!level.isPositionEntityTicking(pos) || !level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(x, z)))
                throw new SurvivalRefusal("Native tick swept destination tracking/ticking column is unavailable");
        }
    }

    /** Constructor is host-private; this object is never a caller-issued tick/read authority. */
    final class ItemTickPlan {
        private final NativeWholeAction action;
        private final ItemEntity source;
        private final NativeEffectCodec.ItemTick effect;
        private final NativeDryItemTickAdmission.ReadBounds box;
        private final Map<CubeCell, BlockState> sealed;
        private final SurvivalLevelBinding exactBinding;
        private final int listenerRadius;
        private NativeSurvivalWorld.CommitReceipt receipt;
        private boolean entered, ran, closed;
        private ItemTickPlan(NativeWholeAction action, ItemEntity source, NativeEffectCodec.ItemTick effect,
                NativeDryItemTickAdmission.ReadBounds box, Map<CubeCell, BlockState> sealed, int listenerRadius) {
            this.action = action; this.source = source; this.effect = effect; this.box = box;
            this.sealed = Map.copyOf(sealed); this.listenerRadius = listenerRadius; exactBinding = binding;
        }
        void enter() {
            requireAuthority();
            if (entered || ran || closed) throw new SurvivalRefusal("Accepted native tick cannot be entered again");
            requireItemTickSource(source, effect.before(), effect.tickCount());
            events.requireDeliveryCallbacks(); events.requireItemTickEventsEmpty();
            nativeState.requireTickGameListeners(level, source.blockPosition(), listenerRadius, limits.metadataEffects()); requireTickDestinations(box);
            entered = true;
        }
        void runNative() {
            requireLiveScope();
            if (ran) throw new SurvivalRefusal("Accepted native tick cannot execute twice");
            ran = true; level.tickNonPassenger(source);
        }
        void close() { closed = true; }
        boolean permits(SurvivalLevelBinding requested) {
            NativeItemTickReadScope.requireSameBinding(exactBinding, requested);
            requireLiveScope(); return true;
        }
        boolean isExecuting(ServerLevel receiver, Entity requested) {
            if (receiver != level || requested != source || requested.level() != level) throw new SurvivalRefusal("Accepted native tick reached another entity");
            requireLiveScope(); return true;
        }
        BlockState read(BlockPos pos) {
            requireLiveScope(); BlockState state = sealed.get(CubeCell.of(pos.getX(), pos.getY(), pos.getZ()));
            if (state == null) throw new SurvivalRefusal("Native item tick read escaped its sealed full-coordinate view");
            return state;
        }
        boolean hasResidentBox(int x1, int y1, int z1, int x2, int y2, int z2) {
            requireLiveScope();
            if (x1 > x2 || y1 > y2 || z1 > z2 || !box.contains(x1, y1, z1) || !box.contains(x2, y2, z2))
                throw new SurvivalRefusal("Native item residency probe escaped its sealed full-coordinate view");
            return true;
        }
        private void requireLiveScope() {
            requireAuthority();
            if (!entered || closed) throw new SurvivalRefusal("Native accepted tick read capability is absent or revoked");
        }
        private void requireAuthority() {
            world.checkOwner();
            if (binding != exactBinding || SurvivalLevelBinding.find(level) != exactBinding || exactBinding.isClosing() || exactBinding.isDetached()
                    || current != action || NativeWholeAction.current() != action || !actions.owns(action)
                    || action.state() != NativeWholeAction.State.APPLYING || !actions.fenced()
                    || pending == null || pending.nativeItemTick != this || receipt == null
                    || actions.pendingDelivery() == null || actions.pendingDelivery().receipt() != receipt
                    || receipt.commitSequence() != world.commitSequence() || !receipt.action().equals(action.identity())
                    || !receipt.world().equals(world.world()) || !receipt.ownerEpoch().equals(world.ownerEpoch())
                    || !receipt.changedCubes().isEmpty() || source.level() != level || source.getClass() != ItemEntity.class
                    || !source.getUUID().equals(effect.id()) || source.getId() != effect.entityId())
                throw new SurvivalRefusal("Accepted native tick has no exact current owner/action/source/receipt");
        }
        private void verifyAfter() {
            if (!entered || !ran || !closed || source.isRemoved() || !source.isAlive() || !source.isAddedToLevel()
                    || level.getEntity(effect.id()) != source || level.getEntity(effect.entityId()) != source
                    || !source.getUUID().equals(effect.id()) || source.getId() != effect.entityId()
                    || source.tickCount != effect.tickCount() + 1)
                throw new SurvivalRefusal("Accepted native tick did not preserve its source and exact native tick count");
            var after = NativeItemEntitySnapshot.capture(source); var beforeTag = effect.before().tagCopy(); var afterTag = after.tagCopy();
            int delay = beforeTag.getShort("PickupDelay"); int expectedDelay = delay > 0 && delay != 32767 ? delay - 1 : delay;
            if (!after.stack().equals(effect.before().stack()) || afterTag.getShort("Age") != beforeTag.getShort("Age") + 1
                    || afterTag.getShort("PickupDelay") != expectedDelay || afterTag.getInt("Lifespan") != beforeTag.getInt("Lifespan")
                    || afterTag.getShort("Health") != beforeTag.getShort("Health"))
                throw new SurvivalRefusal("Accepted dry native tick changed conserved item/lifespan/health or native delay/age semantics");
        }
    }


    /** Exact privately retained MOVE command. Tick roots cannot construct this class. */
    final class PlayerStepPlan {
        private final NativeWholeAction action;
        private final net.minecraft.network.protocol.game.ServerboundMovePlayerPacket packet;
        private final net.minecraft.server.network.ServerGamePacketListenerImpl receiver;
        private final NativePlayerStepSnapshot before;
        private final NativePlayerStepBudget budget;
        private final NativeEffectCodec.PlayerMove effect;
        private final NativePlayerStepAdmission.Bounds box;
        private final Map<CubeCell,BlockState> sealed;
        private final SurvivalLevelBinding exactBinding;
        private final NativePlayerMoveTracking tracking;
        private NativePlayerStepNoAwardMetadata auxiliary;
        private NativeSurvivalWorld.CommitReceipt receipt;
        private NativePlayerStepSnapshot after;
        private boolean preparing,preflighting,entered,ran,closed;
        private int rootEntries,fallCalls;
        private Throwable firstFailure;
        private PlayerStepPlan(NativeWholeAction action,net.minecraft.network.protocol.game.ServerboundMovePlayerPacket packet,
                NativePlayerStepSnapshot before,NativePlayerStepBudget budget,NativeEffectCodec.PlayerMove effect,
                NativePlayerStepAdmission.Bounds box,Map<CubeCell,BlockState> sealed,NativePlayerMoveTracking tracking){
            this.action=action;this.packet=packet;this.receiver=player.connection;this.before=before;this.budget=budget;
            this.effect=effect;this.box=box;this.sealed=Map.copyOf(sealed);this.tracking=tracking;exactBinding=binding;
        }
        void beginPreparation(){if(preparing||entered||closed)throw rejected("Native move preparation reused");preparing=true;preflighting=true;requireAuthority();}
        void endPreparation(){preparing=false;preflighting=false;}
        void prepareNative(){
            requireLive();requireProfile();
            auxiliary=new NativePlayerStepNoAwardMetadata(player,box,new HashSet<>(sealed.values()),limits.metadataEffects());
            requireCollision();requireBeforeImage();
        }
        private void requireBeforeCommit(){
            if(current!=action||action.state()!=NativeWholeAction.State.PREVALIDATING||actions.fenced()||pending!=null)
                throw rejected("MOVE precommit lost preparing owner");
            requireBeforeImage();tracking.revalidate();auxiliary.revalidate();budget.requireRemaining(2*NativePlayerStepBudget.SNAPSHOT+3*NativePlayerStepBudget.INVENTORY);
        }
        private void requireBeforeImage(){if(!before.equals(NativePlayerStepSnapshot.capture(player,budget)))throw rejected("Native move full before-image changed");}
        private void requireProfile(){
            NativePlayerStepAdmission.requireProfile(player,exactBinding,NativePlayerStepOperation.MOVE_PACKET,box,before);
            NativePlayerStepAdmission.requireInput(player,NativePlayerStepOperation.MOVE_PACKET,packet);
            tracking.revalidate();NativePlayerStepAdmission.requireCallbacks();
            nativeState.requireTickGameListeners(level,player.blockPosition(),GameEvent.HIT_GROUND.value().notificationRadius()+3,limits.metadataEffects());
        }
        private void requireCollision(){
            var initial=player.getBoundingBox();var target=initial.move(0,effect.y()-before.pose().y(),0);
            if(!level.noCollision(player,initial.deflate(1e-7))||!level.noCollision(player,target.deflate(1e-7)))
                throw rejected("Native move starts or ends inside an unowned collision shape");
            if(packet.isOnGround()&&level.findSupportingBlock(player,target.move(0,-1e-6,0)).isEmpty())
                throw rejected("Native grounded target lacks actual cubic support");
        }
        void enter(){requireAuthority();if(entered||ran||closed)throw rejected("Native MOVE reused");entered=true;preflighting=true;requireBeforeImage();}
        void runNative(){
            requireLive();requireProfile();auxiliary.revalidate();requireCollision();requireHealthy();preflighting=false;ran=true;
            receiver.handleMovePlayer(packet);requireHealthy();
        }
        void close(){closed=true;preflighting=false;}
        void poison(Throwable failure){if(firstFailure==null)firstFailure=failure;}
        void requireHealthy(){if(firstFailure instanceof RuntimeException failure)throw failure;if(firstFailure instanceof Error failure)throw failure;}
        private SurvivalRefusal rejected(String message){var failure=new SurvivalRefusal(message);poison(failure);return failure;}
        private void requireAuthority(){
            requireHealthy();world.checkOwner();movePermit.requireCurrent(transitionGate);
            if(binding!=exactBinding||SurvivalLevelBinding.find(level)!=exactBinding||exactBinding.isClosing()||exactBinding.isDetached()
                ||player.level()!=level||player.connection!=receiver||receiver.player!=player||current!=action
                ||NativeWholeAction.current()!=action||!actions.owns(action))throw rejected("Foreign native MOVE receiver/owner/action");
            if(preparing){if(action.state()!=NativeWholeAction.State.PREPARING||actions.fenced()||pending!=null||receipt!=null)
                throw rejected("MOVE lacks exact preparing authority");}
            else if(action.state()!=NativeWholeAction.State.APPLYING||!actions.fenced()||pending==null||pending.nativePlayerStep!=this
                ||receipt==null||actions.pendingDelivery()==null||actions.pendingDelivery().receipt()!=receipt
                ||receipt.commitSequence()!=world.commitSequence()||!receipt.action().equals(action.identity())
                ||!receipt.world().equals(world.world())||!receipt.ownerEpoch().equals(world.ownerEpoch())||!receipt.changedCubes().isEmpty())
                throw rejected("MOVE lacks exact accepted delivery receipt");
        }
        private void requireLive(){requireAuthority();if(closed||!preparing&&!entered)throw rejected("Revoked native MOVE scope");}
        boolean permits(SurvivalLevelBinding requested){if(requested!=exactBinding)throw rejected("Foreign MOVE read receiver");requireLive();return true;}
        BlockState read(BlockPos pos){requireLive();var state=sealed.get(CubeCell.of(pos.getX(),pos.getY(),pos.getZ()));if(state==null)throw rejected("MOVE read escaped sealed XYZ cells");return state;}
        boolean hasResidentBox(int x1,int y1,int z1,int x2,int y2,int z2){requireLive();if(x1>x2||y1>y2||z1>z2||!box.contains(x1,y1,z1)||!box.contains(x2,y2,z2))throw rejected("MOVE residency escaped sealed XYZ cells");return true;}
        boolean enterMove(net.minecraft.server.network.ServerGamePacketListenerImpl requested,ServerPlayer source,
                net.minecraft.network.protocol.game.ServerboundMovePlayerPacket requestedPacket){
            requireLive();if(preparing||preflighting||!ran||source!=player||requested!=receiver||requestedPacket!=packet||rootEntries++!=0)
                throw rejected("Foreign, stale or repeated original MOVE root");return true;
        }
        boolean permitsKinematics(Entity source){requireLive();if(preparing||preflighting||!ran||rootEntries!=1||source!=player)throw rejected("Unowned native MOVE kinematic write");return true;}
        boolean residency(Entity source,boolean fall){
            permitsKinematics(source);var b=NativePlayerStepAdmission.box(source.getBoundingBox().inflate(1));
            hasResidentBox(b.x1(),b.y1(),b.z1(),b.x2(),b.y2(),b.z2());if(fall)fallCalls++;return false;
        }
        void rejectEffect(Entity source,String operation){requireLive();throw rejected("Native MOVE reached unowned effect before mutation: "+operation);}
        void rejectOutput(Object source){requireLive();throw rejected("Native MOVE attempted unadmitted outbound packet");}
        private void requireConservedItems(List<NativeItemEntitySnapshot> expected){
            requireAuthority();budget.requireScratch();int index=0;
            for(var owner:entityOwners.values()){
                if(owner.consumed!=null){requireConsumed(owner);continue;}
                if(owner.entity.getClass()!=ItemEntity.class||!tracking.ownsItem(owner.entity)||index>=expected.size())
                    throw rejected("Native MOVE changed projected item owner/order");
                var item=(ItemEntity)owner.entity;var baseline=expected.get(index++);
                if(!baseline.id().equals(item.getUUID()))throw rejected("Native MOVE changed projected native item identity");
                NativePlayerMoveSnapshotAdmission.requireComponents(item.getItem());
                // One prepaid8KiB scratch image at a time; never retained or installed as authority.
                var actual=NativeItemEntitySnapshot.capture(item,NativePlayerStepBudget.ITEM_POST_SCRATCH);
                if(!baseline.equals(actual))throw rejected("Native MOVE changed complete native item state");
            }
            if(index!=expected.size())throw rejected("Native MOVE omitted projected native item owner");
        }
        private void verifyAfter(){
            requireAuthority();if(!entered||!ran||!closed||rootEntries!=1||fallCalls!=1)throw rejected("Original MOVE/fall root was skipped or repeated");
            after=NativePlayerStepSnapshot.capture(player,budget);before.requireConserved(after);tracking.revalidate();auxiliary.revalidate();
            if(!before.food().equals(after.food())||!before.menu().equals(after.menu())||after.tickCount()!=before.tickCount()
                ||after.connection().tickCount()!=before.connection().tickCount()||after.connection().correction().isPresent()
                ||after.connection().awaitingTeleport()!=before.connection().awaitingTeleport()
                ||after.pose().x()!=effect.x()||after.pose().y()!=effect.y()||after.pose().z()!=effect.z()
                ||after.pose().onGround()!=effect.onGround()||after.connection().lastX()!=effect.x()||after.connection().lastY()!=effect.y()
                ||after.connection().lastZ()!=effect.z()||after.connection().received()!=before.connection().received()+1
                ||after.connection().known()!=before.connection().known()||packet.isOnGround()&&after.pose().fallDistance()!=0)
                throw rejected("Native MOVE final position, fall support, counters or conserved state differs");
            requireHealthy();
        }
    }


    // One private epoch starts before hold and survives capture, exact writer retry and release.
    // The output owner deliberately remains unqualified until the independent transport lane lands.
    private NativeSaveOutputReadiness saveOutput;
    /** Called only by the future source-qualified native transport constructor, while held. */
    void attachSaveOutputOwner(NativeSaveOutputReadiness exact){
        requireTransitionHealthy(transitionGate);transitionGate.requireHeld();requireTransitionIdle();
        if(saveOutput!=null||activeSaveCapture!=null||activeSaveBudget!=null)
            throw new SurvivalRefusal("Native save output owner is already bound or busy");
        Objects.requireNonNull(exact).requireBound(this,player);saveOutput=exact;
    }
    private boolean saveOutputSettled(){return saveOutput!=null&&saveOutput.settled(this,player);}
    private ActiveSaveCapture activeSaveCapture;
    private NativeActiveSaveBudget activeSaveBudget;
    private int durableNativeCharge;
    private Object transitionPersistencePurpose;
    private long activeSaveEpoch;
    private boolean activeSaveReading, activeSavePoisoned;
    private void requireNoRetainedActiveSaveForAction() {
        if(activeSaveCapture!=null || activeSaveBudget!=null || activeSavePoisoned)
            throw new SurvivalRefusal("Held or uncertain ACTIVE save still owns the action boundary");
    }
    private void refuseActiveSaveReentry() {
        if(activeSaveReading){activeSavePoisoned=true;throw new SurvivalRefusal("ACTIVE-save capture cannot reenter host ingress");}
    }
    private void beginActiveSaveRead(){
        refuseActiveSaveReentry();if(activeSavePoisoned)throw new SurvivalRefusal("Prior ACTIVE-save authority failed");activeSaveReading=true;
    }
    private void endActiveSaveRead(Throwable primary){
        boolean poisoned=activeSavePoisoned;activeSaveReading=false;
        if(poisoned){var refusal=new SurvivalRefusal("ACTIVE-save nested refusal was caught");
            if(primary!=null)primary.addSuppressed(refusal);else throw refusal;}
    }
    private void requireActiveSaveTransitionIdentity(NativeSessionExecutionGate gate){
        if(gate==null||gate!=transitionGate)throw new SurvivalRefusal("Foreign ACTIVE-save gate");
        gate.requireClaim(this);checkTransitionIdentity();requireBinding();requireCheckpointCoordinator();
        if(actions.fenced())throw new SurvivalRefusal("ACTIVE-save action authority is fenced");
    }
    /** False is temporary and leaves resolving native controls available; it never installs hold. */
    public boolean activeSaveReady(NativeSessionExecutionGate gate){
        if(!miningSettledBeforeHold(gate))return false;
        rejectDuringMove("ACTIVE-save readiness");refuseMenuOpeningReentry();
        beginActiveSaveRead();Throwable primary=null;
        try {
            requireActiveSaveTransitionIdentity(gate);gate.requirePlayerMoveAdmission(this,level);
            if(activeSaveCapture!=null||activeSaveBudget!=null||sessionMenuOwner.retained()||pendingMenu!=null
                    ||current!=null||pending!=null||actions.hasCurrentAction()||world.hasActiveTransaction()
                    ||player.containerMenu!=player.inventoryMenu||scheduledTicks.size()!=0) return false;
            NativeSessionMenuBoundary.requireTrustedLiveCollections(player);
            if(!player.inventoryMenu.getCarried().isEmpty()||!player.inventoryMenu.getCraftSlots().isEmpty()
                    ||!player.inventoryMenu.getSlot(0).getItem().isEmpty()||!settledSaveMenu())return false;
            var prior=checkpointCoordinator.durable().orElse(null);
            if(prior==null||prior.session().phase()!=NativeSurvivalCheckpoint.SessionPhase.ACTIVE
                    ||!prior.session().id().equals(session)||checkpointCoordinator.state()!=SurvivalCheckpointCoordinator.State.IDLE
                    ||checkpointCapture!=null||world.hasPendingCheckpoint()||world.ownedCount()>64||world.actorCount()>8
                    ||entityOwners.size()>16)return false;
            // Consumed-owner deinstantiation and return remain a separate qualification lane.
            for(var owner:entityOwners.values())if(owner.consumed!=null)return false;
            if(!nativeSaveMiningFieldsSettled()||!saveOutputSettled())return false; // Never infer completion from enqueue/receipt/empty queue.
            int required=NativeActiveSaveBudget.reservation(entityOwners.size(),world.ownedCount(),world.actorCount());
            if(nativePayloadAllowance()<=required)return false;
            var budget=new NativeActiveSaveBudget(nativePayloadAllowance(),entityOwners.size(),world.ownedCount(),world.actorCount());
            activeSaveBudget=budget; // Debit before retaining roster, native images, witnesses or cube aliases.
            var save=new ActiveSaveCapture(Math.incrementExact(activeSaveEpoch),prior,budget);
            activeSaveEpoch=save.epoch;activeSaveCapture=save;
            save.captureReadiness();return true;
        }catch(RuntimeException|Error failure){activeSavePoisoned=true;primary=failure;throw failure;}
        finally{endActiveSaveRead(primary);}
    }
    private boolean nativeSaveMiningFieldsSettled(){
        return NativeMiningControlState.settledForCheckpoint(NativeMiningControlState.Mode.capture(player.gameMode),
                NativeMiningControlState.Prefix.capture(player));
    }
    private boolean settledSaveMenu(){
        NativePlayerMoveSnapshotAdmission.require(player);
        var menu=player.inventoryMenu;
        if(NativeSessionMenuBoundary.read(net.minecraft.world.inventory.AbstractContainerMenu.class,menu,"quickcraftStatus",int.class)!=0
                ||NativeSessionMenuBoundary.read(net.minecraft.world.inventory.AbstractContainerMenu.class,menu,"suppressRemoteUpdates",boolean.class))return false;
        for(String name:List.of("lastSlots","remoteSlots")){
            var values=NativeSessionMenuBoundary.read(net.minecraft.world.inventory.AbstractContainerMenu.class,menu,name,net.minecraft.core.NonNullList.class);
            if(values.size()!=menu.slots.size())throw new SurvivalRefusal("Changed settled menu vector");
            for(int i=0;i<values.size();i++){
                ItemStack actual=(ItemStack)values.get(i),expected=menu.slots.get(i).getItem();
                if(actual.getCount()!=expected.getCount()||!ItemStack.isSameItemSameComponents(actual,expected))return false;
            }
        }
        return true;
    }
    private void requireReusableSaveProfile(){
        NativeBlockBreakAdmission.requireTrustedAudiences(player);checkNativeIdentity();requireBinding();
        NativePlayerStepAdmission.requirePinnedBinaries();NativePlayerMoveSnapshotAdmission.require(player);
        events.requireDeliveryCallbacks();checkNativeRoster();
        io.earthplatform.minecraft.survival.source.MarinPlayerEnvironmentV2.requireInstalledPolicy(level,world.world());
        if(!settledSaveMenu()||scheduledTicks.size()!=0||!miningSettledBeforeHold(transitionGate)||!nativeSaveMiningFieldsSettled()||!saveOutputSettled())
            throw new SurvivalRefusal("ACTIVE save lost settled menu/mining/ack/output ownership");
        for(var owner:entityOwners.values()){
            if(owner.consumed!=null||owner.entity.getClass()!=ItemEntity.class||owner.entity.isRemoved()
                    ||!owner.entity.isAlive()||!owner.entity.isAddedToLevel()||owner.entity.level()!=level
                    ||owner.entity.hasAttachments()||level.getEntity(owner.entity.getUUID())!=owner.entity
                    ||level.getEntity(owner.entity.getId())!=owner.entity)
                throw new SurvivalRefusal("ACTIVE save lost an exact live native item owner");
            NativePlayerMoveSnapshotAdmission.requireComponents(((ItemEntity)owner.entity).getItem());
        }
    }
    private static int activeSaveBoundaryCharge(NativeSurvivalCheckpoint.Boundary boundary){
        if(boundary.inventory().encodedBytes()>NativeActiveSaveBudget.INVENTORY_MAX||boundary.items().size()>16
                ||!boundary.scheduledTicks().isEmpty())throw new SurvivalRefusal("ACTIVE-save native image exceeds reusable profile");
        int bytes=1024+41*64+boundary.inventory().encodedBytes()+boundary.food().encodedBytes()+boundary.pose().encodedBytes();
        for(var image:boundary.items()){
            if(image.encodedBytes()>NativeActiveSaveBudget.ITEM_MAX)throw new SurvivalRefusal("ACTIVE-save item image exceeds prepaid slot");
            bytes=Math.addExact(bytes,64+image.encodedBytes()+image.stack().encodedBytes());
        }
        return bytes;
    }
    static long checkedActiveSaveCarrierTime(long captured,long lastObserved,long current,boolean installed,int scheduledOwners){
        if(scheduledOwners!=0)throw new SurvivalRefusal("ACTIVE-save temporal hold refuses scheduled owners: "+scheduledOwners);
        if(captured<0||lastObserved<captured||current<lastObserved||!installed&&(lastObserved!=captured||current!=captured))
            throw new SurvivalRefusal("ACTIVE-save carrier clock changed outside its temporal contract: captured="+captured
                    +" last="+lastObserved+" current="+current+" installed="+installed);
        return current;
    }
    private final class ActiveSaveCapture {
        final long epoch,commitSequence;
        final NativeSurvivalCheckpoint prior;
        final NativeActiveSaveBudget budget;
        final NativeSaveOutputReadiness outputOwner=saveOutput;
        final long outputLineage=outputOwner.lineage(NativeEffectDeliveryHost.this,player);
        final NativeSurvivalWorld.CommitReceipt receipt;
        final Map<UUID,Long> actors;
        final Map<io.earthplatform.core.CubePos,io.earthplatform.minecraft.survival.world.CubeSnapshot> cubes=new LinkedHashMap<>();
        final Map<io.earthplatform.core.CubePos,NativeSurvivalWorld.Presence> presence=new LinkedHashMap<>();
        final Map<UUID,EntityOwner> owners;
        final List<ItemEntity> items;
        NativeActiveSaveHoldWitness heldScalars;
        NativeSurvivalCheckpoint.Boundary readinessBoundary;
        SurvivalSessionTransitions.CheckpointRequest request;
        CheckpointCapture captured;
        TransitionReceipt acknowledged;
        long gameTime=-1,lastObservedCarrierTime;
        boolean captureInstalled,invalid,readyToRelease,released;
        ActiveSaveCapture(long epoch,NativeSurvivalCheckpoint prior,NativeActiveSaveBudget budget){
            this.epoch=epoch;this.prior=prior;this.budget=budget;commitSequence=world.commitSequence();receipt=lastApplied;
            actors=world.actionSequences();owners=new LinkedHashMap<>(entityOwners);
            items=owners.values().stream().map(owner->(ItemEntity)owner.entity).toList();
            for(var position:world.ownedPositions()){cubes.put(position,world.snapshot(position));presence.put(position,world.presence(position));}
            lastObservedCarrierTime=level.getGameTime();
        }
        void captureReadiness(){
            heldScalars=NativeActiveSaveHoldWitness.capture(player,items);
            requireReusableSaveProfile();requireOwners(false);
            var inventory=NativeInventorySnapshot.capture(player,NativeActiveSaveBudget.INVENTORY_MAX);
            var food=NativeFoodSnapshot.capture(player.getFoodData());var pose=NativePlayerPoseSnapshot.capture(player);
            var images=new ArrayList<NativeItemEntitySnapshot>();
            for(var item:items)images.add(NativeItemEntitySnapshot.capture(item,NativeActiveSaveBudget.ITEM_MAX));
            readinessBoundary=new NativeSurvivalCheckpoint.Boundary(lastObservedCarrierTime,inventory,food,pose,images,List.of());
            verifyBoundary(readinessBoundary);
        }
        private void requireOwners(boolean acknowledged){
            if(invalid||released||activeSaveCapture!=this||activeSaveBudget!=budget||epoch!=activeSaveEpoch||activeSavePoisoned
                    ||saveOutput!=outputOwner||outputOwner.lineage(NativeEffectDeliveryHost.this,player)!=outputLineage||world.commitSequence()!=commitSequence||lastApplied!=receipt||!actors.equals(world.actionSequences())
                    ||!owners.equals(entityOwners)||!cubes.keySet().equals(world.ownedPositions()))
                throw new SurvivalRefusal("ACTIVE-save complete action/actor/owner cut changed");
            for(var entry:cubes.entrySet()){
                var before=entry.getValue();var live=world.snapshot(entry.getKey());
                long durable=acknowledged?Math.incrementExact(before.durableRevision()):before.durableRevision();
                if(!live.stamp().equals(before.stamp())||live.blocks()!=before.blocks()||live.durableRevision()!=durable
                        ||live.durableSimulationRevision()!=(acknowledged?before.simulationRevision():before.durableSimulationRevision())
                        ||world.presence(entry.getKey())!=presence.get(entry.getKey()))
                    throw new SurvivalRefusal("ACTIVE-save exact cube payload/stamp/presence changed");
            }
            long now=level.getGameTime();
            if(now<lastObservedCarrierTime)throw new SurvivalRefusal("ACTIVE-save carrier clock went backwards");
            if(gameTime>=0)checkedActiveSaveCarrierTime(gameTime,lastObservedCarrierTime,now,captureInstalled,scheduledTicks.size());
            lastObservedCarrierTime=now;
        }
        private void requireExact(){
            try{
                requireOwners(acknowledged!=null||captured!=null&&checkpointCoordinator.durable().orElse(null)!=prior);
                if(request!=null&&(request!=transitionRequest||request.previous()!=prior||request.authority()!=NativeEffectDeliveryHost.this
                        ||!request.binding().equals(checkpointBinding)||!request.ownerEpoch().equals(world.ownerEpoch())
                        ||request.purpose()!=SurvivalSessionTransitions.CheckpointPurpose.ACTIVE_SAVE
                        ||request.kind()!=SurvivalSessionTransitions.CaptureKind.LIVE_EARTH||request.retainedBoundary()!=null
                        ||!request.session().equals(prior.session())||!request.session().id().equals(session)))
                    throw new SurvivalRefusal("ACTIVE-save private request/purpose changed");
            }catch(RuntimeException|Error failure){invalid=true;throw failure;}
        }
        void bind(SurvivalSessionTransitions.CheckpointRequest next){
            if(request!=null||next.previous()!=prior||!next.session().equals(prior.session()))
                throw new SurvivalRefusal("ACTIVE-save epoch was replaced or reused");
            requireExact();verifyBoundary(readinessBoundary);request=next;
        }
        NativeSurvivalCheckpoint.Boundary capture(int available){
            requireExact();budget.requireScratch();beginActiveSaveRead();Throwable primary=null;
            try{
                if(gameTime>=0)throw new SurvivalRefusal("ACTIVE-save image may only be captured once");
                verifyBoundary(readinessBoundary);gameTime=level.getGameTime();lastObservedCarrierTime=gameTime;
                var before=readinessBoundary;
                var boundary=new NativeSurvivalCheckpoint.Boundary(gameTime,before.inventory(),before.food(),before.pose(),before.items(),List.of());
                NativeSurvivalCheckpoint.checkedBoundaryBytes(boundary.inventory(),boundary.food(),boundary.pose(),boundary.items(),boundary.scheduledTicks(),available);
                verifyBoundary(boundary);requireExact();return boundary;
            }catch(RuntimeException|Error failure){invalid=true;primary=failure;throw failure;}
            finally{endActiveSaveRead(primary);}
        }
        private void verifyBoundary(NativeSurvivalCheckpoint.Boundary expected){
            requireExact();budget.requireScratch();heldScalars.requireUnchanged(player,items);requireReusableSaveProfile();
            if(expected==null||expected.items().size()!=items.size()||!expected.scheduledTicks().isEmpty()
                    ||!expected.inventory().equals(NativeInventorySnapshot.capture(player,NativeActiveSaveBudget.INVENTORY_MAX))
                    ||!expected.food().equals(NativeFoodSnapshot.capture(player.getFoodData()))
                    ||!expected.pose().equals(NativePlayerPoseSnapshot.capture(player)))
                throw new SurvivalRefusal("ACTIVE-save complete native player image changed");
            for(int i=0;i<items.size();i++)if(!expected.items().get(i).equals(NativeItemEntitySnapshot.capture(items.get(i),NativeActiveSaveBudget.ITEM_MAX)))
                throw new SurvivalRefusal("ACTIVE-save native item image changed");
            heldScalars.requireUnchanged(player,items);requireExact();
        }
        void installCapture(){
            requireExact();
            if(captureInstalled||captured==null||captured.ticket==null||captured.boundary==null||captured.boundary.gameTime()!=gameTime)
                throw new SurvivalRefusal("ACTIVE-save lacks exact frozen capture");
            heldScalars.requireUnchanged(player,items);captureInstalled=true;
        }
        void verifyPending(){
            beginActiveSaveRead();Throwable primary=null;
            try{requireExact();requireCapturedOwners();verifyBoundary(captured.boundary);}
            catch(RuntimeException|Error failure){invalid=true;primary=failure;throw failure;}finally{endActiveSaveRead(primary);}
        }
        private boolean sameSaveCube(io.earthplatform.minecraft.survival.world.CubeSnapshot a,
                io.earthplatform.minecraft.survival.world.CubeSnapshot b){
            return b!=null&&a.stamp().equals(b.stamp())&&a.position().equals(b.position())&&a.blocks()==b.blocks()
                    &&a.durableRevision()==b.durableRevision()&&a.durableSimulationRevision()==b.durableSimulationRevision();
        }
        private void requireCapturedOwners(){
            if(!captureInstalled||captured==null||captured.ticket==null||!captured.ticket.actorSequences().equals(actors)
                    ||!captured.ticket.cubes().keySet().equals(cubes.keySet())||captured.ticket.commitSequence()!=commitSequence)
                throw new SurvivalRefusal("ACTIVE-save lost exact frozen actor/cube ticket");
            for(var entry:captured.ticket.cubes().entrySet())if(!sameSaveCube(entry.getValue().snapshot(),cubes.get(entry.getKey())))
                throw new SurvivalRefusal("ACTIVE-save frozen cube cut changed");
        }
        void verifyHeld(NativeSurvivalCheckpoint durable){
            beginActiveSaveRead();Throwable primary=null;
            try{
                requireExact();requireCapturedOwners();
                if(durable.boundary()!=captured.boundary||durable.generation()!=Math.incrementExact(prior.generation())
                        ||!durable.session().equals(request.session())||!durable.captureOwner().equals(world.ownerEpoch())
                        ||durable.commitSequence()!=commitSequence||!durable.actorSequences().equals(actors))
                    throw new SurvivalRefusal("ACTIVE-save acknowledgement differs from immutable capture");
                for(var entry:captured.ticket.cubes().entrySet()){
                    var saved=durable.cubes().get(entry.getKey());var frozen=entry.getValue();
                    if(saved==null||saved.blocks()!=frozen.snapshot().blocks()||saved.simulationRevision()!=frozen.snapshot().simulationRevision()
                            ||saved.durableRevision()!=frozen.nextDurableRevision())throw new SurvivalRefusal("ACTIVE-save acknowledged cube changed");
                }
                verifyBoundary(captured.boundary);
            }catch(RuntimeException|Error failure){invalid=true;primary=failure;throw failure;}finally{endActiveSaveRead(primary);}
        }
    }
    public void requireActiveSaveHeldEpoch(NativeSessionExecutionGate gate){
        gate.requireHeld();if(activeSaveCapture==null){if(activeSaveBudget!=null||activeSavePoisoned)
            throw new SurvivalRefusal("Uncertain ACTIVE-save readiness is held");return;}
        requireActiveSaveTransitionIdentity(gate);beginActiveSaveRead();Throwable primary=null;
        try{activeSaveCapture.requireExact();activeSaveCapture.verifyBoundary(activeSaveCapture.readinessBoundary);}
        catch(RuntimeException|Error failure){activeSaveCapture.invalid=true;primary=failure;throw failure;}
        finally{endActiveSaveRead(primary);}
    }
    private void prepareActiveSave(SurvivalSessionTransitions.CheckpointRequest request){
        var save=activeSaveCapture;
        if(save==null||activeSaveBudget!=save.budget||request.purpose()!=SurvivalSessionTransitions.CheckpointPurpose.ACTIVE_SAVE)
            throw new SurvivalRefusal("ACTIVE-save hold has no exact successful readiness epoch");
        beginActiveSaveRead();Throwable primary=null;
        try{save.bind(request);}catch(RuntimeException|Error failure){save.invalid=true;primary=failure;throw failure;}
        finally{endActiveSaveRead(primary);}
    }
    private void captureActiveSave(SurvivalSessionTransitions.CheckpointRequest request){
        var save=activeSaveCapture;
        if(save==null||save.request!=request)throw new SurvivalRefusal("ACTIVE-save capture lost its epoch");
        var value=new CheckpointCapture(save.commitSequence,Math.incrementExact(request.previousGeneration()),request.session(),Map.of());
        checkpointCapture=value;save.captured=value;Throwable primary=null;
        try{
            checkpointCoordinator.captureHeldBounded(request.session(),NativeActiveSaveBudget.INVENTORY_MAX+4096+save.items.size()*(NativeActiveSaveBudget.ITEM_MAX+4),available->{
                value.boundary=save.capture(available);
                save.budget.finishCapture(activeSaveBoundaryCharge(value.boundary)+NativeActiveSaveHoldWitness.LOGICAL_BYTES
                        +save.items.size()*1024+save.cubes.size()*256+save.actors.size()*64);
                return value.boundary;
            });
        }catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally{
            try{value.ticket=checkpointCoordinator.pendingTicket().orElse(null);
                if(value.ticket==null)throw new SurvivalRefusal("ACTIVE save did not freeze an exact ticket; uncertain owner retained");
                save.installCapture();
            }catch(RuntimeException|Error failure){if(primary!=null)primary.addSuppressed(failure);else throw failure;}
        }
    }
    public TransitionReceipt requireSavedActiveEarthExact(NativeSessionExecutionGate gate,NativeSurvivalCheckpoint exact){
        rejectDuringMove("saved ACTIVE release proof");refuseMenuOpeningReentry();requireTransitionHealthy(gate);gate.requireHeld();requireTransitionIdle();
        var receipt=requireTransitionReceipt(gate,exact);var save=activeSaveCapture;
        if(save==null||save.released||save.acknowledged!=receipt||receipt.request!=save.request)
            throw new SurvivalRefusal("Saved ACTIVE has no exact current-epoch completion");
        save.verifyHeld(exact);save.readyToRelease=true;return receipt;
    }
    /** Every throwable validation precedes clearing aliases; native ingress is still held throughout. */
    public void consumeSavedActiveRelease(NativeSessionExecutionGate gate,NativeSurvivalCheckpoint exact,TransitionReceipt receipt){
        requireTransitionHealthy(gate);gate.requireHeld();var save=activeSaveCapture;
        if(save==null||save.released||!save.readyToRelease||save.acknowledged!=receipt||requireTransitionReceipt(gate,exact)!=receipt)
            throw new SurvivalRefusal("Saved ACTIVE release is stale, foreign or consumed");
        save.requireExact();save.budget.requireRetirable();
        int nextCharge=activeSaveBoundaryCharge(exact.boundary());
        var cubePins=new ArrayList<SurvivalCheckpointCoordinator.DurableCubeLease>(2);
        for(var slot:blockProofImageSlots)if(slot.durableCubeLease!=null)cubePins.add(slot.durableCubeLease);
        checkpointCoordinator.releaseHeldAcknowledgement(save.captured.ticket,exact,cubePins);
        // No callback-capable operation follows retirement. One small receipt retains CURRENT only.
        receipt.retireHistory();transitionRequest=null;checkpointCapture=null;
        acceptedBreakLineage=null;lastAcceptedBreak=null;lastApplied=null;
        blockProofImageSlots.clear();
        durableNativeCharge=nextCharge;save.readyToRelease=false;save.released=true;
        activeSaveCapture=null;activeSaveBudget=null;save.budget.retire();
    }
    public void retireTransitionReleaseHistory(NativeSessionExecutionGate gate,NativeSurvivalCheckpoint exact,TransitionReceipt receipt){
        requireTransitionHealthy(gate);gate.requireHeld();
        if(activeSaveCapture!=null||activeSaveBudget!=null||requireTransitionReceipt(gate,exact)!=receipt)
            throw new SurvivalRefusal("Transition release still owns an active save epoch");
        int nextCharge=activeSaveBoundaryCharge(exact.boundary());
        if(nextCharge>=availableNativePayloadBytes)throw new SurvivalRefusal("Durable native image exceeds shared allowance");
        checkpointCoordinator.releaseTransitionAcknowledgements(transitionPersistencePurpose,receipt.capture.ticket,exact);
        receipt.retireHistory();transitionRequest=null;initialTransitionBoundary=null;transitionPersistencePurpose=null;durableNativeCharge=nextCharge;
    }
    /** The current native durable image remains charged while subsequent actions allocate. */
    public int savedActiveNativePayloadHeadroom(NativeSessionExecutionGate gate,NativeSurvivalCheckpoint exact){
        requireTransitionHealthy(gate);refuseMenuOpeningReentry();
        if(activeSaveCapture!=null||checkpointCoordinator.durable().orElse(null)!=exact||transitionReceipt==null
                ||transitionReceipt.durable!=exact||!transitionReceipt.retired)
            throw new SurvivalRefusal("No exact released reusable ACTIVE save");
        return nativePayloadAllowance();
    }

    /** Private provenance survives native consumed-owner retirement and ordinary restoration. */
    public static final class TransitionReceipt {
        private final NativeEffectDeliveryHost host;
        private SurvivalSessionTransitions.CheckpointRequest request;
        private CheckpointCapture capture;
        private boolean retired;
        private final NativeSurvivalCheckpoint durable;
        private TransitionReceipt(NativeEffectDeliveryHost host, SurvivalSessionTransitions.CheckpointRequest request,
                CheckpointCapture capture, NativeSurvivalCheckpoint durable) {
            this.host=host; this.request=request; this.capture=capture; this.durable=durable;
        }
        private void retireHistory(){request=null;capture=null;retired=true;
        }
    }
    public void requireTransitionHealthy(NativeSessionExecutionGate gate) {
        rejectDuringMove("requireTransitionHealthy");
        if (gate == null || gate != transitionGate) throw new SurvivalRefusal("Foreign staged gate");
        gate.requireClaim(this); checkTransitionIdentity(); requireBinding(); requireCheckpointCoordinator();
        if (actions.fenced()) throw new SurvivalRefusal("Staged action authority is fenced");
    }
    public NativeSurvivalWorld transitionWorld(NativeSessionExecutionGate gate) { requireTransitionHealthy(gate); return world; }
    public NativeSurvivalCheckpoint transitionDurable(NativeSessionExecutionGate gate) { requireTransitionHealthy(gate); return checkpointCoordinator.durable().orElse(null); }
    public NativeSurvivalCheckpoint.Binding transitionBinding(NativeSessionExecutionGate gate) { requireTransitionHealthy(gate); return checkpointBinding; }
    public boolean transitionQuiescent(NativeSessionExecutionGate gate) {
        refuseMenuOpeningReentry();
        requireTransitionHealthy(gate); gate.requireHeld(); reapAborted();
        return !sessionMenuOwner.retained() && pendingMenu == null && current == null && pending == null && !actions.hasCurrentAction() && !world.hasActiveTransaction();
    }
    public NativeSurvivalCheckpoint.Boundary initialTransitionBoundary(NativeSessionExecutionGate gate) {
        refuseMenuOpeningReentry();
        requireTransitionHealthy(gate); gate.requireHeld(); requireTransitionIdle();
        if (checkpointCoordinator.durable().isPresent() || initialTransitionBoundary == null)
            throw new SurvivalRefusal("Initial Earth plan is only available before the first manifest");
        requireEmptyTransitionOwners(initialTransitionBoundary); return initialTransitionBoundary;
    }
    /** Read-only pre-hold refusal. No capture, reaping, menu close, gate write or owner removal. */
    public boolean returnReady(NativeSessionExecutionGate gate) {
        if (!miningSettledBeforeHold(gate)) return false; // Composes the separately reviewed mining owner first.
        rejectDuringMove("returnReady");
        refuseMenuOpeningReentry();
        requireTransitionHealthy(gate);
        gate.requirePlayerMoveAdmission(this, level); // Exact settled ACTIVE claimant; grants no MOVE.
        var durable = checkpointCoordinator.durable().orElse(null);
        if (durable == null || durable.session().phase() != NativeSurvivalCheckpoint.SessionPhase.ACTIVE
                || !durable.session().id().equals(session))
            throw new SurvivalRefusal("Return readiness lost the exact durable ACTIVE owner");
        if (sessionMenuOwner.retained() || pendingMenu != null || current != null || pending != null
                || actions.hasCurrentAction() || world.hasActiveTransaction()
                || checkpointCoordinator.state() != SurvivalCheckpointCoordinator.State.IDLE
                || checkpointCoordinator.pendingTicket().isPresent() || world.hasPendingCheckpoint()) return false;
        if(!saveOutputSettled())return false;
        events.requireDeliveryCallbacks(); checkNativeRoster();
        if (scheduledTicks.size() != 0) return false;
        for (EntityOwner owner : entityOwners.values()) {
            if (owner.consumed == null) return false;
            requireConsumed(owner); // A fully consumed detached owner may retire in the return capture.
        }
        return true;
    }
    public void requireEmptyReturnOwners(NativeSessionExecutionGate gate) {
        refuseMenuOpeningReentry();
        requireTransitionHealthy(gate); gate.requireHeld();
        Capture nativeCapture=captureNativeState();
        if (!nativeCapture.items().isEmpty() || !nativeCapture.ticks().isEmpty())
            throw new SurvivalRefusal("Live native item/tick deinstantiation and save exclusion are not admitted");
    }
    public TransitionReceipt requireTransitionReceipt(NativeSessionExecutionGate gate, NativeSurvivalCheckpoint exact) {
        requireTransitionHealthy(gate); gate.requireHeld();
        TransitionReceipt receipt=transitionReceipt;
        if(receipt==null || receipt.host!=this || receipt.durable!=exact || checkpointCoordinator.durable().orElse(null)!=exact
                || !receipt.retired && (receipt.capture==null || receipt.capture.ticket==null || receipt.request!=transitionRequest))
            throw new SurvivalRefusal("Exact acknowledged host transition provenance is unavailable");
        if(receipt.retired)return receipt;
        requireExactDurableCapture(exact,receipt.capture.boundary,checkpointBinding,receipt.request.session(),
                receipt.capture.ticket.ownerEpoch(),receipt.capture.generation,receipt.capture.commitSequence);
        if(!receipt.capture.ticket.actorSequences().equals(exact.actorSequences())) throw new SurvivalRefusal("Transition actor provenance changed");
        return receipt;
    }
    public SurvivalSessionTransitions.CheckpointOperation beginTransitionCheckpoint(NativeSessionExecutionGate gate,
            SurvivalSessionTransitions.CheckpointRequest request, Executor executor) {
        rejectDuringMove("beginTransitionCheckpoint");
        refuseMenuOpeningReentry();
        requireTransitionHealthy(gate); gate.requireHeld(); requireTransitionIdle();
        Objects.requireNonNull(request); Objects.requireNonNull(executor);
        NativeSurvivalCheckpoint prior=checkpointCoordinator.durable().orElse(null);
        if(request.authority()!=this || !request.binding().equals(checkpointBinding) || !request.ownerEpoch().equals(world.ownerEpoch())
                || request.previous()!=prior || checkpointCapture!=null || checkpointCoordinator.state()!=SurvivalCheckpointCoordinator.State.IDLE)
            throw new SurvivalRefusal("Foreign, overtaken or busy native transition checkpoint request");
        var phase=request.session().phase();
        boolean activeSave=request.purpose()==SurvivalSessionTransitions.CheckpointPurpose.ACTIVE_SAVE;
        if(activeSave && (phase!=NativeSurvivalCheckpoint.SessionPhase.ACTIVE || prior==null
                || prior.session().phase()!=NativeSurvivalCheckpoint.SessionPhase.ACTIVE
                || !prior.session().equals(request.session()) || request.kind()!=SurvivalSessionTransitions.CaptureKind.LIVE_EARTH
                || request.retainedBoundary()!=null || activeSaveCapture==null))
            throw new SurvivalRefusal("Same-phase ACTIVE-save request lacks its exact admitted purpose/prior");
        if(!activeSave && phase==NativeSurvivalCheckpoint.SessionPhase.ACTIVE && prior!=null
                && prior.session().phase()==NativeSurvivalCheckpoint.SessionPhase.ACTIVE)
            throw new SurvivalRefusal("ACTIVE-to-ACTIVE capture requires explicit private ACTIVE_SAVE purpose");
        if(phase==NativeSurvivalCheckpoint.SessionPhase.ENTERING) {
            NativeSessionAdmission.requireOrdinaryExact(player,checkpointBinding,request.session());
            if(prior==null) {
                if(request.kind()!=SurvivalSessionTransitions.CaptureKind.INITIAL_EARTH || request.retainedBoundary()!=initialTransitionBoundary)
                    throw new SurvivalRefusal("First ENTERING must use this staged initial plan");
            } else if(prior.session().phase()!=NativeSurvivalCheckpoint.SessionPhase.RETURNED
                    || request.kind()!=SurvivalSessionTransitions.CaptureKind.RETAINED_EARTH || request.retainedBoundary()!=prior.boundary())
                throw new SurvivalRefusal("Reentry requires exact prior RETURNED boundary");
            requireEmptyTransitionOwners(request.retainedBoundary());
            revokeAcceptedBreakLineage();
            session=request.session().id(); ordinaryEscrow=request.session().ordinaryEscrow();
            ordinaryFoodEscrow=request.session().ordinaryFood(); ordinaryPoseEscrow=request.session().ordinaryPose();
            availableNativePayloadBytes=checkedBoundaryAllowance(checkpointLimits.payloadBytes(),
                    Math.addExact(Math.addExact(ordinaryEscrow.encodedBytes(),ordinaryFoodEscrow.encodedBytes()),ordinaryPoseEscrow.encodedBytes()),requestedNativePayloadBytes);
        } else if(!request.session().id().equals(session) || !request.session().ordinaryEscrow().equals(ordinaryEscrow)
                || !request.session().ordinaryFood().equals(ordinaryFoodEscrow) || !request.session().ordinaryPose().equals(ordinaryPoseEscrow))
            throw new SurvivalRefusal("Transition changed its immutable ordinary escrow");
        if(phase==NativeSurvivalCheckpoint.SessionPhase.RETURNED) {
            if(prior==null || prior.session().phase()!=NativeSurvivalCheckpoint.SessionPhase.RETURNING
                    || request.kind()!=SurvivalSessionTransitions.CaptureKind.RETAINED_EARTH || request.retainedBoundary()!=prior.boundary())
                throw new SurvivalRefusal("RETURNED requires the identical final Earth boundary");
            requireTransitionReceipt(gate,prior); NativeSessionAdmission.requireOrdinaryExact(player,checkpointBinding,request.session());
            requireEmptyTransitionOwners(request.retainedBoundary());
        }
        if(!activeSave&&(phase==NativeSurvivalCheckpoint.SessionPhase.ENTERING||phase==NativeSurvivalCheckpoint.SessionPhase.RETURNING)){
            if(transitionPersistencePurpose!=null)throw new SurvivalRefusal("Previous normal transition purpose remains retained");
            transitionPersistencePurpose=new Object();
        }
        if(activeSave)prepareActiveSave(request);
        transitionRequest=request; transitionReceipt=null;
        Throwable setupFailure=null;
        try {
            if(activeSave) {
                captureActiveSave(request);
            } else if(request.kind()==SurvivalSessionTransitions.CaptureKind.LIVE_EARTH) {
                if(phase!=NativeSurvivalCheckpoint.SessionPhase.ACTIVE && phase!=NativeSurvivalCheckpoint.SessionPhase.RETURNING)
                    throw new SurvivalRefusal("Live Earth capture cannot name an ordinary phase");
                ownedTransitionCapture=true;
                try { captureCheckpoint(phase); } finally { ownedTransitionCapture=false; }
            } else {
                var value=new CheckpointCapture(world.commitSequence(),Math.incrementExact(checkpointCoordinator.durableGeneration()),request.session(),Map.of());
                checkpointCapture=value;
                try { checkpointCoordinator.captureTransitionBounded(transitionPersistencePurpose,request.session(),nativePayloadAllowance(),available->{
                    value.boundary=Objects.requireNonNull(request.retainedBoundary());
                    NativeSurvivalCheckpoint.checkedBoundaryBytes(value.boundary.inventory(),value.boundary.food(),value.boundary.pose(),
                            value.boundary.items(),value.boundary.scheduledTicks(),Math.min(available,nativePayloadAllowance()));
                    return value.boundary;
                }); } finally { value.ticket=checkpointCoordinator.pendingTicket().orElse(null);
                    if(value.ticket==null && !world.hasPendingCheckpoint())checkpointCapture=null;
                    else if(value.ticket==null)throw new SurvivalRefusal("Staged checkpoint froze without an exact coordinator ticket; hold retained");
                }
            }
            checkpointCoordinator.submit(executor);
        } catch(RuntimeException | Error failure) {
            if(checkpointCapture==null || checkpointCapture.ticket==null || checkpointCoordinator.state()!=SurvivalCheckpointCoordinator.State.FAILED)throw failure;
            setupFailure=failure;
        }
        final CheckpointCapture captured=checkpointCapture;
        final ActiveSaveCapture savedCapture=activeSave?activeSaveCapture:null;
        if(captured==null || captured.ticket==null)throw new SurvivalRefusal("Missing exact frozen host transition ticket");
        return new TransitionCheckpointOperation(gate,request,executor,captured,savedCapture,activeSave);
    }
    private final class TransitionCheckpointOperation implements SurvivalSessionTransitions.CheckpointOperation {
        private final NativeSessionExecutionGate gate;
        private final Executor executor;
        private final boolean activeSave;
        private SurvivalSessionTransitions.CheckpointRequest request;
        private CheckpointCapture captured;
        private ActiveSaveCapture savedCapture;
        private TransitionCheckpointOperation(NativeSessionExecutionGate gate,SurvivalSessionTransitions.CheckpointRequest request,
                Executor executor,CheckpointCapture captured,ActiveSaveCapture savedCapture,boolean activeSave){
            this.gate=gate;this.request=request;this.executor=executor;this.captured=captured;this.savedCapture=savedCapture;this.activeSave=activeSave;
        }
            boolean completed;
            private void check(){try {requireTransitionHealthy(gate);gate.requireHeld();requireTransitionIdle();
                if(completed || transitionRequest!=request || checkpointCapture!=captured
                        || checkpointCoordinator.pendingTicket().orElse(null)!=captured.ticket)
                    throw new SurvivalRefusal("Native transition operation was replaced or replayed");
                if(activeSave) {
                    if(savedCapture==null || activeSaveCapture!=savedCapture || savedCapture.request!=request || savedCapture.captured!=captured)
                        throw new SurvivalRefusal("ACTIVE-save poll lost private ledger ownership");
                    savedCapture.verifyPending(); // Includes pending/failed writer polls and exact retries.
                }
                } catch(RuntimeException|Error failure) {if(savedCapture!=null)savedCapture.invalid=true;throw failure;}
            }
            public SurvivalSessionTransitions.Result poll(){
                try {
                check();
                if(checkpointCoordinator.state()==SurvivalCheckpointCoordinator.State.FAILED)
                    return SurvivalSessionTransitions.Result.failed(checkpointCoordinator.failure());
                if(!checkpointCoordinator.poll())return checkpointCoordinator.state()==SurvivalCheckpointCoordinator.State.FAILED
                        ? SurvivalSessionTransitions.Result.failed(checkpointCoordinator.failure()):SurvivalSessionTransitions.Result.pending();
                NativeSurvivalCheckpoint durable=checkpointCoordinator.durable().orElseThrow();
                requireExactDurableCapture(durable,captured.boundary,checkpointBinding,request.session(),captured.ticket.ownerEpoch(),captured.generation,captured.commitSequence);
                if(!captured.ticket.actorSequences().equals(durable.actorSequences()) || world.commitSequence()!=captured.commitSequence)
                    throw new SurvivalRefusal("Held transition action ownership changed");
                if(activeSave) {
                    if(activeSaveCapture==null || activeSaveCapture.request!=request || activeSaveCapture.captured!=captured)
                        throw new SurvivalRefusal("ACTIVE-save ack lost private ledger ownership");
                    activeSaveCapture.verifyHeld(durable);checkpointCapture=null;
                } else if(request.kind()==SurvivalSessionTransitions.CaptureKind.LIVE_EARTH)retireDurableConsumedOwners();
                else {requireEmptyTransitionOwners(captured.boundary);checkpointCapture=null;}
                transitionReceipt=new TransitionReceipt(NativeEffectDeliveryHost.this,request,captured,durable);
                if(activeSave)activeSaveCapture.acknowledged=transitionReceipt;
                completed=true;request=null;captured=null;savedCapture=null;
                return SurvivalSessionTransitions.Result.succeeded();
                } catch(RuntimeException|Error failure) {if(savedCapture!=null)savedCapture.invalid=true;throw failure;}
            }
            public boolean retryReady(){check();return checkpointCoordinator.retryReady();}
            public void retryExact(){check();checkpointCoordinator.retry();checkpointCoordinator.submit(executor);}
    }
    private void requireEmptyTransitionOwners(NativeSurvivalCheckpoint.Boundary boundary) {
        if(boundary==null || !boundary.items().isEmpty() || !boundary.scheduledTicks().isEmpty()
                || !entityOwners.isEmpty() || scheduledTicks.size()!=0 || !boundary.pose().dimension().equals(level.dimension().location())
                || player.serverLevel()==level || !level.players().isEmpty()
                || !nativeState.knownUuids(level,limits.entityOwners()+limits.audience()).isEmpty())
            throw new SurvivalRefusal("Held first bridge only admits empty native item/tick ownership");
    }
    private void requireTransitionIdle(){refuseMenuOpeningReentry();reapAborted();if(sessionMenuOwner.retained() || pendingMenu!=null || current!=null || pending!=null || actions.hasCurrentAction() || actions.fenced() || world.hasActiveTransaction())
        throw new SurvivalRefusal("Native transition has not drained accepted whole actions");}
    private void checkTransitionIdentity(){
        world.checkOwner(); transitionGate.requireIdentity();
        if(player!=transitionGate.player() || level.getClass()!=ServerLevel.class || player.getClass()!=ServerPlayer.class
                || player.connection!=connection || registries!=level.registryAccess() || recipeSource!=level.getRecipeManager()
                || lootRegistrySource!=level.getServer().reloadableRegistries() || !actor.equals(player.getUUID())
                || !level.dimension().equals(SurvivalLevelBinding.DIMENSION)
                || (player.serverLevel()!=level && player.serverLevel()!=transitionGate.ordinaryLevel()))
            throw new SurvivalRefusal("Staged native transition identity changed");
    }

    private void checkNativeIdentity() {
        world.checkOwner();
        if (!player.server.isSameThread() || level.getClass() != ServerLevel.class || player.getClass() != ServerPlayer.class
                || player.serverLevel() != level || !actor.equals(player.getUUID()) || registries != level.registryAccess()
                || player.connection != connection || recipeSource != level.getRecipeManager()
                || lootRegistrySource != level.getServer().reloadableRegistries()
                || player.hasDisconnected() || player.isRemoved() || !player.isAlive()
                || player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL || player.hasInfiniteMaterials() || player.getAbilities().invulnerable
                || !level.dimension().equals(SurvivalLevelBinding.DIMENSION)
                || level.players().size() > limits.audience())
            throw new SurvivalRefusal("Native delivery player/world/registry/session profile changed or unavailable");
    }
    private void requireBinding() {
        if (binding == null || SurvivalLevelBinding.find(level) != binding || binding.isClosing() || binding.isDetached())
            throw new SurvivalRefusal("Native delivery cubic binding is unavailable");
    }
    private void requireIdentity(NativeSurvivalWorld.ActionIdentity identity) {
        if (!identity.ownerEpoch().equals(world.ownerEpoch()) || !identity.actor().equals(actor))
            throw new SurvivalRefusal("Native delivery belongs to another owner epoch/player");
    }
    private void reapAborted() {
        if (current == null || actions.hasCurrentAction()) return;
        if (current.state() == NativeWholeAction.State.ABORTED && (pending == null || !pending.attempted)) pending = null;
        if (current.state() == NativeWholeAction.State.ABORTED && sessionMenuBefore != null && (pendingMenu == null || !pendingMenu.attempted)) {
            sessionMenuOwner.abortPreparation(current); pendingMenu = null;
        }
        if (current.state() == NativeWholeAction.State.ABORTED || current.state() == NativeWholeAction.State.APPLIED) {
            if(miningReservation!=null&&miningOwner!=null)miningOwner.retire(current);
            menuPacket = null; menuClose = null; menuOpen = null; sessionMenuBefore = null; sessionMenuMetadata = null; sessionMenuBudget = null;
            current = null; playerStep=null; inventoryBefore = null; foodBefore = null; poseBefore = null; pickup = null; itemTick = null; menuScript = null; tableScript = null; dryPlacement = null; blockBreak = null;
        }
    }
    /** A metadata-only script may not change another conserved native owner while broadcasting. */
    /** Block-only complete boundary check, reusing its prepaid sequential image scratch. */
    private void requireBlockBreakOutcome(NativeSurvivalCheckpoint.Boundary expected,NativeBlockBreakPlan plan,
            NativeSurvivalWorld.CommitReceipt receipt) {
        if(plan!=blockBreak||pending==null||pending.nativeBlockBreak!=plan||pending.projectedBoundary!=expected
                ||!plan.imageSlot().hasReceipt(receipt))
            throw new SurvivalRefusal("Native break final boundary lacks its exact preaccepted private plan");
        checkNativeIdentity();requireBinding();events.requireDeliveryCallbacks();checkNativeRoster();
        NativeBlockBreakAdmission.requireEmptyInventory(player);NativeInventorySnapshot.requireResolvedMenu(player);
        if(level.getGameTime()!=expected.gameTime()||player.getInventory().selected!=expected.inventory().selected()
                ||!List.of(snapshotInventory()).equals(expected.inventory().slots())
                ||!NativeFoodSnapshot.capture(player.getFoodData()).equals(expected.food())
                ||!NativePlayerPoseSnapshot.capture(player).equals(expected.pose())
                ||!scheduledTicks.snapshot().equals(expected.scheduledTicks()))
            throw new SurvivalRefusal("Native break changed its exact conserved boundary");
        int index=0;
        for(var owner:entityOwners.values()) {
            if(owner.consumed!=null){requireConsumed(owner);continue;}
            if(!(owner.entity instanceof ItemEntity item)||item.isRemoved()||index>=expected.items().size()
                    ||!plan.imageSlot().matchesProjected(item,expected.items().get(index),receipt))
                throw new SurvivalRefusal("Native break changed its exact projected native item roster/image");
            index++;
        }
        if(index!=expected.items().size())throw new SurvivalRefusal("Native break projected item roster lost an owner");
    }
    private void requireMiningControlOutcome(NativeSurvivalCheckpoint.Boundary expected,NativeSurvivalWorld.CommitReceipt receipt) {
        if(miningReservation==null||pending==null||!pending.nativeMining||pending.nativeBlockBreak!=null||pending.projectedBoundary!=expected
                ||actions.pendingDelivery()==null||actions.pendingDelivery().receipt()!=receipt)
            throw new SurvivalRefusal("Mining final boundary lacks its exact accepted owner");
        miningReservation.require(miningOwner,current);checkNativeIdentity();requireBinding();events.requireDeliveryCallbacks();checkNativeRoster();
        NativeBlockBreakAdmission.requireEmptyInventory(player);NativeInventorySnapshot.requireResolvedMenu(player);
        if(level.getGameTime()!=expected.gameTime()||player.getInventory().selected!=expected.inventory().selected()
                ||!List.of(snapshotInventory()).equals(expected.inventory().slots())||!NativeFoodSnapshot.capture(player.getFoodData()).equals(expected.food())
                ||!NativePlayerPoseSnapshot.capture(player).equals(expected.pose())||!scheduledTicks.snapshot().equals(expected.scheduledTicks()))
            throw new SurvivalRefusal("Mining control changed complete native player/tick boundary");
        int index=0;
        for(var owner:entityOwners.values()) {
            if(owner.consumed!=null){requireConsumed(owner);continue;}
            if(owner.entity.getClass()!=ItemEntity.class||owner.entity.isRemoved()||index>=expected.items().size())
                throw new SurvivalRefusal("Mining control changed native item roster");
            var image=expected.items().get(index++);
            if(image.encodedBytes()>NativeBlockBreakPlan.DROP_IMAGE_MAX_BYTES)
                throw new SurvivalRefusal("Mining control item exceeds prepaid sequential scratch");
            // The current unused image slot remains fully charged; this one temporary never escapes.
            var actual=NativeItemEntitySnapshot.capture((ItemEntity)owner.entity,NativeBlockBreakPlan.DROP_IMAGE_MAX_BYTES);
            if(!image.equals(actual))throw new SurvivalRefusal("Mining output changed complete native item state");
        }
        if(index!=expected.items().size())throw new SurvivalRefusal("Mining control omitted a native item owner");
    }
    private void requireResolvedMenuOutcome(NativeSurvivalCheckpoint.Boundary expected) {
        checkNativeIdentity(); requireBinding(); events.requireDeliveryCallbacks(); checkNativeRoster();
        NativeInventorySnapshot.requireResolvedMenu(player);
        if (level.getGameTime() != expected.gameTime() || player.getInventory().selected != expected.inventory().selected()
                || !List.of(snapshotInventory()).equals(expected.inventory().slots())
                || !NativeFoodSnapshot.capture(player.getFoodData()).equals(expected.food())
                || !NativePlayerPoseSnapshot.capture(player).equals(expected.pose())
                || !scheduledTicks.snapshot().equals(expected.scheduledTicks()))
            throw new SurvivalRefusal("Native menu delivery changed its exact conserved boundary");
        var items = new ArrayList<NativeItemEntitySnapshot>();
        for (var owner : entityOwners.values()) {
            if (owner.consumed != null) { requireConsumed(owner); continue; }
            if (!(owner.entity instanceof ItemEntity item) || item.isRemoved())
                throw new SurvivalRefusal("Native menu delivery changed an owned entity lifecycle");
            items.add(NativeItemEntitySnapshot.capture(item));
        }
        if (!items.equals(expected.items()))
            throw new SurvivalRefusal("Native menu metadata changed conserved native item owners");
    }
    /** One-event first slice; multiple radii require a separately proved union reservation. */
    static void requirePreparedGameEventCount(int count) {
        if (count < 0 || count > 1)
            throw new SurvivalRefusal("Multiple native game events require a shared section-metadata reservation");
    }
    /** Captured notices and the native broadcast/reward closure share the existing metadata cap. */
    static int checkedMenuMetadataCount(int capturedNotices, int reservedDeliveryOperations, int maximum) {
        if (capturedNotices < 0 || reservedDeliveryOperations < 1 || maximum < 1
                || (long) capturedNotices + reservedDeliveryOperations > maximum)
            throw new SurvivalRefusal("Native menu notices and delivery closure exceed the metadata count cap");
        return capturedNotices + reservedDeliveryOperations;
    }
    /** Journal bytes and reserved native delivery closure share the action's existing byte cap. */
    static int checkedMenuEffectBytes(List<NativeActionEffect> effects, int reservedDeliveryBytes, int maximumBytes) {
        if (reservedDeliveryBytes < 1 || maximumBytes < 1)
            throw new SurvivalRefusal("Positive complete native menu delivery reservation required");
        long total = reservedDeliveryBytes;
        for (var effect : effects) total += effect.payloadBytes();
        if (total > maximumBytes) throw new SurvivalRefusal("Native menu journal and delivery exceed the whole action byte cap");
        return (int) total;
    }
    private NativeStackSnapshot[] snapshotInventory() {
        if (player.getInventory().getContainerSize() != 41 || player.getInventory().selected < 0 || player.getInventory().selected > 8)
            throw new SurvivalRefusal("Ordinary native inventory is unavailable");
        if(moveBudget!=null){NativePlayerMoveSnapshotAdmission.require(player);moveBudget.requireScratch();return NativeInventorySnapshot.capture(player,moveBudget.inventory()).slots().toArray(NativeStackSnapshot[]::new);}
        NativeStackSnapshot[] snapshots = new NativeStackSnapshot[41];
        for (int i = 0; i < snapshots.length; i++) snapshots[i] = NativeStackSnapshot.capture(player.getInventory().getItem(i), registries);
        return snapshots;
    }
    private void validatePose() {
        if (poseBefore == null || !poseBefore.equals(NativePlayerPoseSnapshot.capture(player)))
            throw new SurvivalRefusal("Native player pose/abilities changed during the admitted action");
    }
    private void validateFood() {
        if (foodBefore == null || !foodBefore.equals(NativeFoodSnapshot.capture(player.getFoodData())))
            throw new SurvivalRefusal("Native food before-image changed during action");
    }
    private void validateInventory() {
        if (inventoryBefore == null || player.getInventory().getContainerSize() != 41 || selectedBefore != player.getInventory().selected)
            throw new SurvivalRefusal("Native inventory selection changed");
        if(moveBudget!=null){
            if(!java.util.Arrays.equals(inventoryBefore,snapshotInventory()))throw new SurvivalRefusal("MOVE complete inventory before-image changed");return;
        }
        for (int i = 0; i < inventoryBefore.length; i++)
            if (!inventoryBefore[i].matches(player.getInventory().getItem(i), registries))
                throw new SurvivalRefusal("Full native inventory before-image changed at slot " + i);
    }
    private void checkNativeRoster() {
        Set<UUID> nativeIds = nativeState.knownUuids(level, limits.entityOwners() + limits.audience());
        if (nativeIds.size() > limits.entityOwners() + limits.audience()) throw new SurvivalRefusal("Native entity budget exhausted");
        for (UUID id : nativeIds)
            if (level.getPlayerByUUID(id) == null && !entityOwners.containsKey(id))
                throw new SurvivalRefusal("Unowned native entity exists in the bounded survival level");
        for (var entry : entityOwners.entrySet()) {
            EntityOwner owner = entry.getValue(); Entity entity = owner.entity;
            if (owner.consumed != null) { requireConsumed(owner); continue; }
            if (!entity.isRemoved() && (!nativeIds.contains(entry.getKey()) || level.getEntity(entry.getKey()) != entity
                    || entity.level() != level)) throw new SurvivalRefusal("Native entity tracking/ownership is unavailable");
        }
    }
    private void requireNewEntity(UUID id, Set<UUID> newIds, double x, double y, double z) {
        requirePosition(x, y, z);
        if (!newIds.add(id) || id.equals(actor) || entityOwners.containsKey(id) || nativeState.manager(level).isLoaded(id))
            throw new SurvivalRefusal("Duplicate native entity UUID or retained owner");
        BlockPos pos = BlockPos.containing(x, y, z);
        if (!level.isPositionEntityTicking(pos) || !level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(pos)))
            throw new SurvivalRefusal("Native entity tracking/ticking destination is unavailable");
    }
    private BlockPos requireCell(CubeCell cell) {
        BlockPos pos = exactNativePosition(cell);
        world.read(cell); // Joins the active declared/stamped transaction read set.
        return pos;
    }
    private void requirePosition(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || x < Integer.MIN_VALUE || x >= Integer.MAX_VALUE || y < Integer.MIN_VALUE || y >= Integer.MAX_VALUE
                || z < Integer.MIN_VALUE || z >= Integer.MAX_VALUE) throw new SurvivalRefusal("Native effect position exceeds exact native coordinates");
        requireCell(CubeCell.of((long) Math.floor(x), (long) Math.floor(y), (long) Math.floor(z)));
    }
    static BlockPos exactNativePosition(CubeCell cell) {
        // Native entity sections use a 22/20/22-bit key. Restrict before native packing; never alias.
        if (cell.cube().x() < -2097152 || cell.cube().x() > 2097151
                || cell.cube().y() < -524288 || cell.cube().y() > 524287
                || cell.cube().z() < -2097152 || cell.cube().z() > 2097151)
            throw new SurvivalRefusal("Native effect section is outside lossless native tracking bounds");
        return new BlockPos(Math.toIntExact(cell.x()), Math.toIntExact(cell.y()), Math.toIntExact(cell.z()));
    }
    static int checkedBoundaryAllowance(int checkpointBytes, int exactEscrowBytes, int requestedBytes) {
        if (checkpointBytes < 1 || exactEscrowBytes < 0 || requestedBytes < 1
                || (long) exactEscrowBytes + requestedBytes > checkpointBytes)
            throw new SurvivalRefusal("Native payload allowance does not reserve the exact ordinary inventory escrow");
        return requestedBytes;
    }
    static void requireCapacity(int retained, int pending, int limit, String label) {
        if (retained < 0 || pending < 0 || limit < 0 || retained > limit || pending > limit - retained)
            throw new SurvivalRefusal(label + " budget exhausted, including retained/pending owners");
    }
    static int checkedAdd(int before, int amount, String label) {
        if (before < 0 || amount < 0 || amount > Integer.MAX_VALUE - before) throw new SurvivalRefusal(label + " overflow");
        return before + amount;
    }
    static void requireSoundCoordinate(double value) {
        if (!Double.isFinite(value) || value * 8.0 < Integer.MIN_VALUE || value * 8.0 > Integer.MAX_VALUE)
            throw new SurvivalRefusal("Native sound packet coordinate would overflow");
    }
    private void validateStack(ItemStack stack) {
        if (!stack.isEmpty() && (stack.getCount() < 1 || stack.getCount() > stack.getMaxStackSize()
                || !stack.isItemEnabled(level.enabledFeatures()))) throw new SurvivalRefusal("Native stack is unavailable or overfull");
        NativeStackSnapshot.capture(stack, registries);
    }
    private static void requireOrdinaryItem(ItemStack stack) {
        PinnedNativeEventAdmission.requireOrdinaryItem(stack);
    }
    private static void requirePreparedEntity(Entity entity, UUID id, double x, double y, double z) {
        if (entity.hasAttachments() || entity.isRemoved() || entity.isAddedToLevel() || !entity.getUUID().equals(id) || entity.getId() <= 0
                || entity.getX() != x || entity.getY() != y || entity.getZ() != z)
            throw new SurvivalRefusal("Native detached entity did not preserve its prepared identity/position");
    }
    private static void requireVelocity(Entity entity, double x, double y, double z) {
        if (entity.getDeltaMovement().x != x || entity.getDeltaMovement().y != y || entity.getDeltaMovement().z != z)
            throw new SurvivalRefusal("Native detached entity velocity changed");
    }
    private Entity requireSource(UUID id) {
        EntityOwner owner = entityOwners.get(id);
        Entity entity = id.equals(actor) ? player : owner == null ? null : owner.entity;
        if (entity == null || entity.isRemoved() || entity.level() != level || level.getEntity(id) != entity)
            throw new SurvivalRefusal("Native game-event source is foreign or unavailable");
        return entity;
    }
    private static <T> Stat<T> resolve(StatType<T> type, NativeEffectCodec.PlayerStat effect) {
        T value = type.getRegistry().getOptional(effect.value()).orElseThrow(() -> new SurvivalRefusal("Unknown native statistic value"));
        return type.get(value);
    }
    private static Stat<?> resolveStat(NativeEffectCodec.PlayerStat effect) {
        var type = BuiltInRegistries.STAT_TYPE.getOptional(effect.category()).orElseThrow(() -> new SurvivalRefusal("Unknown native statistic category"));
        return resolve(type, effect);
    }
}
