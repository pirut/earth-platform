package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.CubeSnapshot;
import java.util.ArrayList;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import io.earthplatform.minecraft.survival.persistence.NativeFoodSnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Original native controls execute once on exact detached GameMode state. A Host-private
 * reservation and retained Plan own the complete compound commit and bounded live installation.
 * Public mixin hooks confer no standalone start, destroy, receipt, apply, or replay authority.
 */
public final class NativeMiningControlPreparation {
    private static final ThreadLocal<Plan> ACTIVE=new ThreadLocal<>();
    private NativeMiningControlPreparation() {}
    private enum Phase { CAPTURE, PACKET, MODE_PACKET, MODE_TICK, ACK_PREFIX, COMPLETE }
    static final int RESERVATION_BYTES=NativeMiningControlJournal.RESERVATION_BYTES+96*1024;
    record Preview(NativeMiningControlState.Mode before,NativeMiningControlState.Mode after,
                   NativeMiningControlState.Prefix prefixBefore,NativeMiningControlState.Prefix prefixAfter,
                   List<NativeMiningControlJournal.Notice> notices,int wireBytes,boolean realPulse,int rawServerTick) {
        Preview {notices=List.copyOf(notices);}
    }
    /** One permanently retained Host owner; neither decoded evidence nor a caller can replace it. */
    static final class Owner {
        final NativeEffectDeliveryHost host;
        private final ServerPlayerGameMode ownedMode;
        private Plan retained;
        private boolean hasPulseTick;
        private int lastPulseTick;
        private long revision;
        private boolean lineageInvalidated;
        private NativeMiningControlState.Mode settled;
        private NativeMiningControlState.Prefix settledPrefix;
        private List<NativeMiningControlTranscript.Target> lineage=List.of();
        private int lineageSelected;
        private NativeStackSnapshot lineageTool;
        Owner(NativeEffectDeliveryHost host){this.host=java.util.Objects.requireNonNull(host);ownedMode=host.player().gameMode;}
        NativeEffectCodec.MiningControl preparePacket(NativeWholeAction action,ServerboundPlayerActionPacket packet,
                NativeEffectDeliveryHost.MiningReservation reservation) {
            return prepare(action,packet,null,reservation);
        }
        NativeEffectCodec.MiningControl preparePulse(NativeWholeAction action,NativeEffectDeliveryHost.MiningReservation reservation) {
            var origin=NativeMiningScheduledOrigin.requireCurrent(host.player());
            if(hasPulseTick&&lastPulseTick==origin.rawServerTick)throw refuse("Duplicate genuine server tick cannot advance native mining");
            hasPulseTick=true;lastPulseTick=origin.rawServerTick;
            return prepare(action,null,origin,reservation);
        }
        private NativeEffectCodec.MiningControl prepare(NativeWholeAction action,ServerboundPlayerActionPacket packet,
                NativeMiningScheduledOrigin.Origin origin,NativeEffectDeliveryHost.MiningReservation reservation) {
            if(retained!=null||ACTIVE.get()!=null)throw refuse("Reentrant/retained native mining preparation");
            reservation.require(this,action);
            var plan=new Plan(this,action,packet,origin,reservation);retained=plan;
            try {ACTIVE.set(plan);plan.capture();plan.run();return plan.effect;}
            catch(RuntimeException|Error failure){plan.poison(failure);throw failure;}
            finally{if(ACTIVE.get()==plan)ACTIVE.remove();}
        }
        void requireOpeningLineage() {
            if(lineageInvalidated)throw refuse("Native mining lineage was invalidated and cannot be revived by another input");
            var player=host.player();
            if(player.gameMode!=ownedMode||ownedMode==null||ownedMode.getClass()!=ServerPlayerGameMode.class)
                throw invalidate("Native mining GameMode instance was replaced");
            var mode=NativeMiningControlState.Mode.capture(ownedMode);
            var prefix=NativeMiningControlState.Prefix.capture(player);
            if(settled==null) {
                if(mode.destroying()||mode.delayed()||prefix.acknowledgement()!=-1)
                    throw invalidate("Native mining cannot adopt foreign preexisting progress or acknowledgement");
            } else if(!settled.equals(mode)||!settledPrefix.equals(prefix))
                throw invalidate("Native mining live mode/prefix changed outside the retained owner");
            if(mode.destroying()||mode.delayed()) {
                if(lineageTool==null||player.getInventory().selected!=lineageSelected
                        ||!lineageTool.matches(player.getMainHandItem(),player.registryAccess()))
                    throw invalidate("Native mining selected slot/tool lineage changed");
                for(var target:lineage) {
                    var stamp=host.actions().world().stamp(target.cell().cube());
                    if(stamp.incarnation()!=target.incarnation()||stamp.simulationRevision()!=target.simulationRevision()
                            ||host.actions().world().palette().id(host.actions().world().read(target.cell()))!=target.stateId()
)
                        throw invalidate("Native mining target/reach/permission lineage changed");
                }
            }
        }
        void requireBeforeCommit(NativeWholeAction action,List<NativeEffectCodec.Effect> effects) {
            var p=exact(action,NativeWholeAction.State.PREVALIDATING);p.requireLiveBefore();
            if(p.encoded!=null||p.effect==null||effects.size()!=(p.broken==null?2:p.broken.effectCount()+2)
                    ||!(effects.getFirst() instanceof NativeEffectCodec.RandomSeed seed)
                    ||!seed.world().equals(action.world().world())||!seed.ownerEpoch().equals(action.world().ownerEpoch())
                    ||!seed.actor().equals(p.player.getUUID())||seed.sequence()!=action.identity().sequence()
                    ||!p.effect.equals(effects.getLast())||action.world().usage().transactionWrites()!=(p.broken==null?0:1))
                throw p.refusal("Mining commit lacks its exact ordered seed/break/control transcript");
            if(p.broken!=null){p.statistics.requireIncrementFits(p.broken.minedStatistic());p.invocation.requireCompound(p.broken,action,effects);}
            p.encoded=effects.stream().map(NativeEffectCodec::encode).toList();
        }
        void requireBeforeApply(NativeWholeAction action,NativeSurvivalWorld.CommitReceipt receipt) {
            var p=exact(action,NativeWholeAction.State.APPLYING);p.requireLiveBefore();
            if(p.accepted!=null||p.encoded==null||!receipt.effects().equals(p.encoded)
                    ||!receipt.action().equals(action.identity())||!receipt.world().equals(action.world().world())
                    ||!receipt.ownerEpoch().equals(action.world().ownerEpoch())||receipt.commitSequence()!=p.commitBefore+1
                    ||receipt.commitSequence()!=action.world().commitSequence()||p.broken==null&&!receipt.changedCubes().isEmpty())
                throw p.refusal("Mining apply lacks its exact accepted compound receipt");
            p.delivery.accept(receipt);p.accepted=receipt; // Retained even if the very first write/send fails. No retry.
        }
        void applyBeforeBreak(NativeWholeAction action,NativeSurvivalWorld.CommitReceipt receipt) {
            var p=exact(action,NativeWholeAction.State.APPLYING);
            if(p.accepted!=receipt||p.attempted)throw p.refusal("Native mining live installation repeated or foreign");
            p.delivery.begin(receipt);p.attempted=true;p.requireLiveBefore();
            // Original resetLastActionTime precedes mode execution. No clock is read again here.
            NativeSessionMenuBoundary.write(ServerPlayer.class,p.player,"lastActionTime",long.class,p.preview.prefixAfter().lastActionTime());
            p.installMode();
            p.deliver(0,p.broken==null?p.preview.notices().size():p.destroyIndex);
        }
        void deliverAfterBreak(NativeWholeAction action,NativeSurvivalWorld.CommitReceipt receipt) {
            var p=exact(action,NativeWholeAction.State.APPLYING);
            if(p.accepted!=receipt||!p.attempted||p.outputsComplete||p.broken!=null&&!p.broken.readyForFinalVerification(receipt))
                throw p.refusal("Native mining final output lacks its exact completed break prefix");
            if(p.broken!=null)p.deliver(p.destroyIndex,p.preview.notices().size());
            // Native packet assignment follows mode output; native tick clears ack after its send.
            NativeSessionMenuBoundary.write(ServerGamePacketListenerImpl.class,p.receiver,"ackBlockChangesUpTo",int.class,p.preview.prefixAfter().acknowledgement());
            if(!p.preview.after().equals(NativeMiningControlState.Mode.capture(p.liveMode))
                    ||!p.preview.prefixAfter().equals(NativeMiningControlState.Prefix.capture(p.player)))
                throw p.refusal("Native mining installed fields differ from its accepted output");
            NativeBlockBreakAdmission.requireTrustedAudiences(p.player);p.outputsComplete=true;
        }
        void verifyAfterOutputs(NativeWholeAction action,NativeSurvivalWorld.CommitReceipt receipt,NativeFoodSnapshot expectedFood) {
            var p=exact(action,NativeWholeAction.State.APPLYING);
            if(p.accepted!=receipt||!p.outputsComplete||p.finished)throw p.refusal("Mining final verification precedes complete ordered output");
            var before=p.conserved;
            var expected=new NativeSessionMenuBoundary.ConservedScalars(expectedFood,before.pose(),before.healthBits(),before.absorptionBits(),
                    before.air(),before.fire(),before.frozen(),before.tickCount(),before.invulnerableTime(),before.hurtTime(),before.hurtDuration(),before.deathTime(),
                    before.xpBits(),before.xpTotal(),before.xpLevel(),before.noPhysics());
            if(!expected.equals(NativeSessionMenuBoundary.conservedScalars(p.player))
                    ||!p.preview.after().equals(NativeMiningControlState.Mode.capture(p.liveMode))
                    ||!p.preview.prefixAfter().equals(NativeMiningControlState.Prefix.capture(p.player))
                    ||p.selected!=p.player.getInventory().selected||!p.tool.matches(p.player.getMainHandItem(),p.player.registryAccess())
                    ||!p.inventory.equals(NativeInventorySnapshot.capture(p.player,8*1024)))
                throw p.refusal("Native mining trailing output changed complete conserved player state");
            p.statistics.requireAfter(p.broken==null?null:p.broken.minedStatistic());p.transport.require();p.finalVerified=true;
        }
        void finishDelivered(NativeWholeAction action,NativeSurvivalWorld.CommitReceipt receipt) {
            var p=exact(action,NativeWholeAction.State.APPLYING);
            if(p.accepted!=receipt||!p.outputsComplete||!p.finalVerified||p.finished||p.broken!=null&&!p.broken.finished(receipt))
                throw p.refusal("Native mining cannot finish before complete boundary/block verification");
            var targets=new ArrayList<NativeMiningControlTranscript.Target>(2);
            if(p.preview.after().destroying())p.addTarget(targets,p.preview.after().target());
            if(p.preview.after().delayed())p.addTarget(targets,p.preview.after().delayedTarget());
            settled=p.preview.after();settledPrefix=p.preview.prefixAfter();lineage=List.copyOf(targets);
            lineageSelected=p.selected;lineageTool=p.tool;revision=Math.incrementExact(revision);p.delivery.finish(receipt);p.finished=true;
            if(p.invocation!=null)p.invocation.detachFinished(p.broken,action,receipt);
        }
        NativeBlockBreakPlan breakPlan(){return retained==null?null:retained.broken;}
        NativeEffectCodec.MiningControl effect(){return retained==null?null:retained.effect;}
        int noticeCount(){return retained==null||retained.preview==null?0:retained.preview.notices().size();}
        void retire(NativeWholeAction action) {
            if(retained==null)return;
            if(retained.action!=action||ACTIVE.get()!=null||action.state()!=NativeWholeAction.State.ABORTED
                    &&(action.state()!=NativeWholeAction.State.APPLIED||!retained.finished))
                throw refuse("Native mining may retire only an exact aborted or fully applied plan");
            retained=null;
        }
        boolean settledForTransition() {
            if(lineageInvalidated)throw refuse("Invalidated native mining requires verified recovery before transition");
            if(retained!=null)throw refuse("Native mining save/leave readiness reentered its action");
            requireOpeningLineage();
            var mode=NativeMiningControlState.Mode.capture(host.player().gameMode);
            var prefix=NativeMiningControlState.Prefix.capture(host.player());
            return NativeMiningControlState.settledForCheckpoint(mode,prefix);
        }
        void requireSettledForTransition() {
            if(lineageInvalidated)throw refuse("Invalidated native mining requires verified recovery before transition");
            if(retained!=null)throw refuse("Native mining action must finish before save or leave");
            requireOpeningLineage();
            var mode=NativeMiningControlState.Mode.capture(host.player().gameMode);
            var prefix=NativeMiningControlState.Prefix.capture(host.player());
            if(!NativeMiningControlState.settledForCheckpoint(mode,prefix))
                throw refuse("Finish native mining and its acknowledgement before save or leave");
        }
        void refuseForeignLane(String operation) {
            if(lineageInvalidated)throw refuse("Invalidated native mining cannot enter "+operation);
            if(retained!=null)throw refuse("Foreign "+operation+" reentered native mining");
            if(settled!=null&&(settled.destroying()||settled.delayed()))
                throw refuse("Finish native mining before "+operation);
        }
        private Plan exact(NativeWholeAction action,NativeWholeAction.State state) {
            if(retained==null||retained.action!=action||action.state()!=state)throw refuse("Foreign native mining action/state");
            retained.require();return retained;
        }
        private SurvivalRefusal invalidate(String message) {lineageInvalidated=true;return refuse(message);}
        private SurvivalRefusal refuse(String message) {
            var failure=new SurvivalRefusal(message);if(retained!=null)retained.poison(failure);return failure;
        }
    }
    /** Unforgeable same-action capability, minted only at the original exact scratch destroy entry. */
    static final class DestroyInvocation {
        private Plan plan;
        private final CubeCell target;
        private NativeSurvivalWorld.CommitReceipt terminalReceipt;
        private NativeBlockBreakPlan bound;
        private DestroyInvocation(Plan plan,CubeCell target){this.plan=plan;this.target=target;}
        private Plan live() {
            if(plan==null)throw new SurvivalRefusal("Completed native destroy invocation cannot regain preparation authority");return plan;
        }
        void requirePreparation(NativeEffectDeliveryHost host,NativeWholeAction action,CubeCell requested) {
            var plan=live();plan.require();
            if(ACTIVE.get()!=plan||plan.invocation!=this||plan.owner.host!=host||plan.action!=action
                    ||!target.equals(requested)||bound!=null||plan.destroyEntries!=1
                    ||plan.phase!=Phase.MODE_PACKET&&plan.phase!=Phase.MODE_TICK)
                throw plan.refusal("Private native destroy invocation is foreign, repeated or outside its original branch");
        }
        void bind(NativeBlockBreakPlan exact) {var plan=live();if(bound!=null||exact==null)throw plan.refusal("Native destroy plan attached twice");bound=exact;}
        List<NativeEffectCodec.Effect> requireCompound(NativeBlockBreakPlan exact,NativeWholeAction action,List<NativeEffectCodec.Effect> effects) {
            var plan=live();plan.require();
            if(bound!=exact||plan.broken!=exact||action!=plan.action||plan.effect==null
                    ||action.state()!=NativeWholeAction.State.PREVALIDATING||effects.size()!=exact.effectCount()+2
                    ||!plan.effect.equals(effects.getLast())||plan.destroyIndex<0
                    ||!target.equals(plan.effect.journal().destroyed()))
                throw plan.refusal("Private destroy lacks its exact terminal control transcript");
            return effects.subList(0,effects.size()-1);
        }
        void requireAccepted(NativeBlockBreakPlan exact,NativeWholeAction action,NativeSurvivalWorld.CommitReceipt receipt) {
            var plan=live();plan.require();if(bound!=exact||plan.broken!=exact||plan.action!=action||plan.accepted!=receipt
                    ||action.state()!=NativeWholeAction.State.APPLYING)
                throw plan.refusal("Private destroy lost accepted mining ownership");
        }
        private void detachFinished(NativeBlockBreakPlan exact,NativeWholeAction action,NativeSurvivalWorld.CommitReceipt receipt) {
            requireAccepted(exact,action,receipt);var current=live();
            if(!current.finished||!exact.finished(receipt)||terminalReceipt!=null)
                throw current.refusal("Native destroy cannot release an unfinished or failed preparation graph");
            terminalReceipt=receipt; // Same receipt already charged/retained by the accepted break proof; no copy.
            plan=null; // BreakPlan -> invocation can no longer retain scratch/reservation/inventory/output graph.
        }
        boolean terminalFor(NativeBlockBreakPlan exact,NativeSurvivalWorld.CommitReceipt receipt) {
            return plan==null&&bound==exact&&terminalReceipt==receipt;
        }
    }
    private static final class Plan {
        final Owner owner;
        final NativeWholeAction action;
        final NativeEffectDeliveryHost.MiningReservation reservation;
        final long commitBefore;
        final ServerboundPlayerActionPacket packet;
        final NativeMiningScheduledOrigin.Origin origin;
        final ServerPlayer player;
        final ServerLevel level;
        final ServerGamePacketListenerImpl receiver;
        final ServerPlayerGameMode liveMode;
        final SurvivalLevelBinding exactBinding;
        final UUID session,epoch;
        final NativeMiningControlJournal journal=new NativeMiningControlJournal();
        PinnedNativeEventAdmission events;
        Phase phase=Phase.CAPTURE;
        NativeMiningControlState.Mode before;
        NativeMiningControlState.Prefix prefixBefore;
        NativeInventorySnapshot inventory;
        NativeFoodSnapshot food;
        io.earthplatform.minecraft.survival.persistence.NativePlayerPoseSnapshot pose;
        NativeStackSnapshot tool;
        NativeBlockBreakAdmission admission;
        NativeMiningStartAdmission startAdmission;
        NativeMiningClockAdmission clock;
        NativeMiningStatisticsImage statistics;
        NativeMiningTransportAdmission transport;
        NativeSessionMenuBoundary.ConservedScalars conserved;
        ServerPlayerGameMode scratch;
        GameType currentMode,previousMode;
        int ack,selected,nativeModeEntries,packetEntries,resetWrites,ackWrites,prefixEnds;
        long lastAction;
        final NativeMiningDeliveryLatch<NativeSurvivalWorld.CommitReceipt> delivery=new NativeMiningDeliveryLatch<>();
        Preview preview;
        NativeEffectCodec.MiningControl effect;
        List<io.earthplatform.minecraft.survival.world.NativeActionEffect> encoded;
        List<NativeMiningControlTranscript.Target> targetEvidence;
        List<Packet<?>> preparedOutput;
        DestroyInvocation invocation;
        NativeBlockBreakPlan broken;
        NativeSurvivalWorld.CommitReceipt accepted;
        int destroyEntries,destroyIndex=-1;
        boolean attempted,outputsComplete,finalVerified,finished;
        Plan(Owner owner,NativeWholeAction action,ServerboundPlayerActionPacket packet,NativeMiningScheduledOrigin.Origin origin,NativeEffectDeliveryHost.MiningReservation reservation) {
            this.owner=owner;this.action=java.util.Objects.requireNonNull(action);this.packet=packet;this.origin=origin;this.reservation=reservation;commitBefore=action.world().commitSequence();
            player=owner.host.player();level=owner.host.level();receiver=player.connection;liveMode=player.gameMode;
            session=owner.host.session();epoch=action.world().ownerEpoch();
            exactBinding=SurvivalLevelBinding.find(level);
            if(exactBinding==null||exactBinding.actions()!=owner.host.actions()||exactBinding.world()!=action.world())
                throw refusal("Mining lacks its exact open binding before preparation");
        }
        void capture() {
            require();reservation.require(owner,action);NativeMiningControlPins.requireAll();events=new PinnedNativeEventAdmission(NeoForge.EVENT_BUS);
            if((packet==null)==(origin==null))throw refusal("Mining preparation needs exactly one native input kind");
            if(packet!=null)NativeMiningControlJournal.requirePacket(packet);else origin.require(player);
            if(player.getClass()!=ServerPlayer.class||receiver==null||receiver.getClass()!=ServerGamePacketListenerImpl.class
                    ||receiver.player!=player||liveMode.getClass()!=ServerPlayerGameMode.class||level.getClass()!=ServerLevel.class
                    ||player.server.getClass()!=net.minecraft.gametest.framework.GameTestServer.class)
                throw refusal("Mining foundation requires exact registered native GameTest object graph");
            // This is deliberately still the all-empty grass/dirt proof profile. No tool admission is expanded.
            NativePlayerMoveSnapshotAdmission.require(player);
            admission=NativeBlockBreakAdmission.capture(player,Blocks.DIRT.defaultBlockState());
            NativeSessionMenuBoundary.requireSupportedStance(player);
            startAdmission=NativeMiningStartAdmission.capture(player);
            clock=NativeMiningClockAdmission.capture();
            statistics=new NativeMiningStatisticsImage(player);
            transport=new NativeMiningTransportAdmission(receiver);
            for(var target:owner.lineage) {
                if(!player.canInteractWithBlock(NativeMiningControlState.pos(target.cell()),1.0)
                        ||player.blockActionRestricted(level,NativeMiningControlState.pos(target.cell()),GameType.SURVIVAL)
                        ||!level.mayInteract(player,NativeMiningControlState.pos(target.cell())))
                    throw refusal("Native held/delayed target lost current reach or permission");
            }
            events.requireEmpty(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.class);
            currentMode=liveMode.getGameModeForPlayer();previousMode=liveMode.getPreviousGameModeForPlayer();
            if(currentMode!=GameType.SURVIVAL||NativeMiningControlState.read(liveMode,"player",ServerPlayer.class)!=player
                    ||NativeMiningControlState.read(liveMode,"level",ServerLevel.class)!=level)
                throw refusal("Mining live GameMode lost exact original player/level/modes");
            before=NativeMiningControlState.Mode.capture(liveMode);prefixBefore=NativeMiningControlState.Prefix.capture(player);
            ack=prefixBefore.acknowledgement();lastAction=prefixBefore.lastActionTime();
            selected=player.getInventory().selected;
            inventory=NativeInventorySnapshot.capture(player,8*1024);
            food=NativeFoodSnapshot.capture(player.getFoodData());pose=NativeSessionMenuBoundary.transientPose(player);
            conserved=NativeSessionMenuBoundary.conservedScalars(player);
            tool=NativeStackSnapshot.capture(player.getMainHandItem(),player.registryAccess());
            requireTarget(before.target());requireTarget(before.delayedTarget());
            if(packet!=null)requireTarget(NativeMiningControlState.cell(packet.getPos()));
            var evidence=new ArrayList<NativeMiningControlTranscript.Target>(3);
            if(before.destroying())addTarget(evidence,before.target());
            if(before.delayed())addTarget(evidence,before.delayedTarget());
            if(packet!=null)addTarget(evidence,NativeMiningControlState.cell(packet.getPos()));
            targetEvidence=List.copyOf(evidence);
            // Source-qualified constructor has only player/level assignments; no events or abilities changes.
            scratch=before.createScratch(player,level,currentMode,previousMode);
            requireLiveBefore();
        }
        void requireTarget(CubeCell target) {
            NativeMiningControlJournal.requireWireCell(target);
            // Inert constructor targets still need wire safety, but no read/reach is invented for them.
            boolean active=before!=null&&(before.destroying()&&target.equals(before.target())
                    ||before.delayed()&&target.equals(before.delayedTarget()))
                    ||packet!=null&&target.equals(NativeMiningControlState.cell(packet.getPos()));
            if(!active)return;
            var state=action.read(target);action.world().palette().id(state);
            if(!state.isAir())NativeBlockBreakAdmission.requireTarget(state);
        }
        Preview run() {
            require();
            if(packet!=null){phase=Phase.PACKET;receiver.handlePlayerAction(packet);
                if(packetEntries!=1||nativeModeEntries!=1||resetWrites!=1||ackWrites!=1)
                    throw refusal("Original mining handler prefix/mode/write census did not complete exactly once");
            }else{
                phase=Phase.MODE_TICK;scratch.tick();
                if(nativeModeEntries!=1)throw refusal("Original native GameMode.tick did not run exactly once");
                phase=Phase.ACK_PREFIX;receiver.tick();
                int expectedWrites=prefixBefore.acknowledgement()>-1?1:0;
                if(prefixEnds!=1||packetEntries!=0||resetWrites!=0||ackWrites!=expectedWrites)
                    throw refusal("Original mining acknowledgement prefix escaped its exact branch census");
            }
            requireLiveBefore();
            var after=NativeMiningControlState.Mode.capture(scratch);
            if(packet!=null&&after.ticks()!=before.ticks())throw refusal("Packet changed native mining tick count");
            if(origin!=null&&after.ticks()!=before.ticks()+1)throw refusal("Pulse did not preserve native int increment/wrap semantics");
            phase=Phase.COMPLETE;
            preview=new Preview(before,after,prefixBefore,new NativeMiningControlState.Prefix(ack,lastAction),
                    journal.seal(),journal.wireBytes(),origin!=null,origin==null?0:origin.rawServerTick);
            var input=packet==null?new NativeMiningControlTranscript.Input(3,null,-1,-1,origin.rawServerTick):
                new NativeMiningControlTranscript.Input(switch(packet.getAction()){case START_DESTROY_BLOCK->0;case STOP_DESTROY_BLOCK->1;case ABORT_DESTROY_BLOCK->2;default->throw refusal("Foreign mining action");},
                    NativeMiningControlState.cell(packet.getPos()),packet.getDirection().get3DDataValue(),packet.getSequence(),0);
            var transcript=new NativeMiningControlTranscript(session,owner.revision,input,before,after,prefixBefore,preview.prefixAfter(),
                    selected,tool,targetEvidence,preview.notices(),preview.wireBytes(),destroyIndex,invocation==null?null:invocation.target);
            effect=new NativeEffectCodec.MiningControl(action.world().world(),epoch,player.getUUID(),action.identity().sequence(),transcript);
            var encodedEffect=NativeEffectCodec.encode(effect);
            if(encodedEffect.payloadBytes()>NativeMiningControlTranscript.MAX_EFFECT_BYTES||!effect.equals(NativeEffectCodec.decode(encodedEffect,player.registryAccess())))
                throw refusal("Native mining control exceeds bounded exact codec image");
            var output=new ArrayList<Packet<?>>(preview.notices().size());
            for(var notice:preview.notices()) {
                if(notice instanceof NativeMiningControlJournal.BlockResync value)
                    output.add(new ClientboundBlockUpdatePacket(NativeMiningControlState.pos(value.target()),net.minecraft.world.level.block.Block.stateById(value.stateId())));
                else if(notice instanceof NativeMiningControlJournal.Ack value)output.add(new ClientboundBlockChangedAckPacket(value.sequence()));
                else output.add(null); // Native single-player crack recipient set is empty; never send actor a server crack.
            }
            preparedOutput=java.util.Collections.unmodifiableList(output);
            action.stage(effect);require();return preview;
        }
        void require() {
            delivery.requireHealthy();
            if(owner.retained!=this||!owner.host.actions().owns(action)||NativeWholeAction.current()!=action
                    ||action.state()!=NativeWholeAction.State.PREPARING&&action.state()!=NativeWholeAction.State.PREVALIDATING&&action.state()!=NativeWholeAction.State.APPLYING||!player.server.isSameThread()
                    ||player.serverLevel()!=level||player.connection!=receiver||player.gameMode!=liveMode
                    ||!owner.host.session().equals(session)||!action.world().ownerEpoch().equals(epoch)
                    ||!action.identity().actor().equals(player.getUUID())||owner.host.actions().world()!=action.world())
                throw refusal("Mining scratch escaped its privately retained exact action/host/session");
            if(SurvivalLevelBinding.find(level)!=exactBinding||exactBinding.isClosing()||exactBinding.isDetached())
                throw refusal("Mining scratch lost its cached exact cubic binding");
            if(action.state()==NativeWholeAction.State.APPLYING) {
                var pending=owner.host.actions().pendingDelivery();
                if(pending==null||encoded==null||!pending.receipt().effects().equals(encoded)
                        ||!pending.receipt().action().equals(action.identity())||pending.receipt().commitSequence()!=action.world().commitSequence()
                        ||accepted!=null&&pending.receipt()!=accepted)
                    throw refusal("Mining cached binding lacks its exact accepted pending delivery");
            } else if(exactBinding.actions()!=owner.host.actions()||exactBinding.world()!=action.world())
                throw refusal("Mining preparing binding identity changed");
            reservation.require(owner,action);
            if(origin!=null)origin.require(player);
        }
        void requireLiveBefore() {
            require();admission.revalidate();startAdmission.revalidate();clock.require();statistics.requireBefore();transport.require();
            events.requireEmpty(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.class);
            if(!conserved.equals(NativeSessionMenuBoundary.conservedScalars(player))
                    ||!before.equals(NativeMiningControlState.Mode.capture(liveMode))||!prefixBefore.equals(NativeMiningControlState.Prefix.capture(player))
                    ||liveMode.getGameModeForPlayer()!=currentMode||liveMode.getPreviousGameModeForPlayer()!=previousMode
                    ||!inventory.equals(NativeInventorySnapshot.capture(player,8*1024))||!food.equals(NativeFoodSnapshot.capture(player.getFoodData()))
                    ||!pose.equals(NativeSessionMenuBoundary.transientPose(player))||selected!=player.getInventory().selected
                    ||!tool.matches(player.getMainHandItem(),player.registryAccess()))
                throw refusal("Original scratch mining modified or lost a live native before-image");
        }
        void addTarget(List<NativeMiningControlTranscript.Target> targets,CubeCell target) {
            for(var present:targets)if(present.cell().equals(target))return;
            var stamp=action.state()==NativeWholeAction.State.PREPARING?reservation.snapshot(target.cube()).stamp():action.world().stamp(target.cube());
            targets.add(new NativeMiningControlTranscript.Target(target,stamp.incarnation(),stamp.simulationRevision(),
                    action.world().palette().id(action.world().read(target))));
        }
        void installMode() {
            var after=preview.after();
            NativeSessionMenuBoundary.write(ServerPlayerGameMode.class,liveMode,"isDestroyingBlock",boolean.class,after.destroying());
            NativeSessionMenuBoundary.write(ServerPlayerGameMode.class,liveMode,"destroyProgressStart",int.class,after.started());
            NativeSessionMenuBoundary.write(ServerPlayerGameMode.class,liveMode,"destroyPos",BlockPos.class,NativeMiningControlState.pos(after.target()));
            NativeSessionMenuBoundary.write(ServerPlayerGameMode.class,liveMode,"gameTicks",int.class,after.ticks());
            NativeSessionMenuBoundary.write(ServerPlayerGameMode.class,liveMode,"hasDelayedDestroy",boolean.class,after.delayed());
            NativeSessionMenuBoundary.write(ServerPlayerGameMode.class,liveMode,"delayedDestroyPos",BlockPos.class,NativeMiningControlState.pos(after.delayedTarget()));
            NativeSessionMenuBoundary.write(ServerPlayerGameMode.class,liveMode,"delayedTickStart",int.class,after.delayedStarted());
            NativeSessionMenuBoundary.write(ServerPlayerGameMode.class,liveMode,"lastSentState",int.class,after.lastSentStage());
        }
        void deliver(int from,int to) {
            for(int index=from;index<to;index++) {
                require();transport.require();NativeBlockBreakAdmission.requireTrustedAudiences(player);
                var packet=preparedOutput.get(index);
                if(packet!=null)receiver.send(packet); // Already materialized/codec-qualified; no native control callback replay.
                require();transport.require(); // A swallowed callback reentry cannot allow another output or break effect.
            }
        }
        void poison(Throwable failure) {
            delivery.poison(failure);
            if(action.state()==NativeWholeAction.State.PREPARING&&owner.host.actions().owns(action))
                try{action.poisonPreparation();}catch(RuntimeException|Error secondary){failure.addSuppressed(secondary);}
        }
        SurvivalRefusal refusal(String message){var result=new SurvivalRefusal(message);poison(result);return result;}
    }
    public static void rejectHeldIngress(String operation) {
        var plan=ACTIVE.get();if(plan!=null)throw plan.refusal("Foreign "+operation+" reentered native mining");
    }
    /** Exact original handler admission for the held-packet mixin; no unscoped packet is released. */
    public static boolean enterPacket(ServerGamePacketListenerImpl receiver,ServerPlayer player,ServerboundPlayerActionPacket packet) {
        var p=ACTIVE.get();if(p==null)return false;p.require();
        if(receiver!=p.receiver||player!=p.player||packet!=p.packet||p.phase!=Phase.PACKET||++p.packetEntries!=1)
            throw p.refusal("Foreign/repeated native mining packet entered preparation");return true;
    }
    public static void requireOriginalClock(ServerPlayer receiver) {
        var p=ACTIVE.get();if(p==null)return;p.require();
        if(receiver!=p.player||p.phase!=Phase.PACKET||p.packetEntries!=1)
            throw p.refusal("Native mining clock read escaped the original packet prefix");
        try{p.clock.require();}catch(RuntimeException|Error failure){p.poison(failure);throw failure;}
    }
    public static boolean captureLastActionTime(ServerPlayer receiver,long value) {
        var p=ACTIVE.get();if(p==null)return false;p.require();
        p.clock.require();
        if(receiver!=p.player||p.phase!=Phase.PACKET||p.packetEntries!=1||++p.resetWrites!=1)
            throw p.refusal("Native last-action-time write escaped original mining prefix");p.lastAction=value;return true;
    }
    public static boolean invokePacketMode(ServerPlayerGameMode receiver,BlockPos pos,ServerboundPlayerActionPacket.Action action,
            Direction face,int maxBuildHeight,int sequence) {
        var p=ACTIVE.get();if(p==null)return false;p.require();
        if(receiver!=p.liveMode||p.phase!=Phase.PACKET||p.packetEntries!=1||p.resetWrites!=1||p.nativeModeEntries!=0
                ||!pos.equals(p.packet.getPos())||action!=p.packet.getAction()||face!=p.packet.getDirection()||sequence!=p.packet.getSequence()
                ||maxBuildHeight!=p.level.getMaxBuildHeight())throw p.refusal("Native handler changed mining mode inputs");
        p.phase=Phase.MODE_PACKET;
        try{p.scratch.handleBlockBreakAction(pos,action,face,maxBuildHeight,sequence);}
        catch(RuntimeException|Error failure){p.poison(failure);throw failure;}finally{p.phase=Phase.PACKET;}
        return true;
    }
    public static boolean enterMode(ServerPlayerGameMode receiver,boolean tick) {
        var p=ACTIVE.get();if(p==null)return false;p.require();
        if(receiver!=p.scratch||receiver==p.liveMode||p.phase!=(tick?Phase.MODE_TICK:Phase.MODE_PACKET)||++p.nativeModeEntries!=1)
            throw p.refusal("Native mining mode escaped exact one-shot scratch invocation");return true;
    }
    public static boolean permitsAckPrefix(ServerGamePacketListenerImpl receiver) {
        var p=ACTIVE.get();if(p==null)return false;p.require();
        if(receiver!=p.receiver||p.phase!=Phase.ACK_PREFIX||p.origin==null||p.prefixEnds!=0)
            throw p.refusal("Unowned native connection prefix during mining preparation");return true;
    }
    public static boolean finishAckPrefix(ServerGamePacketListenerImpl receiver) {
        var p=ACTIVE.get();if(p==null)return false;
        if(!permitsAckPrefix(receiver)||++p.prefixEnds!=1)throw p.refusal("Native ack prefix ran twice");return true;
    }
    public static int readAcknowledgement(ServerGamePacketListenerImpl receiver,int ordinary) {
        var p=ACTIVE.get();if(p==null)return ordinary;
        if(!permitsAckPrefix(receiver))throw p.refusal("Foreign native ack read");return p.ack;
    }
    public static boolean writeAcknowledgement(ServerGamePacketListenerImpl receiver,int value) {
        var p=ACTIVE.get();if(p==null)return false;p.require();
        if(receiver!=p.receiver||p.phase!=Phase.PACKET&&p.phase!=Phase.ACK_PREFIX||++p.ackWrites!=1
                ||p.phase==Phase.PACKET&&(p.nativeModeEntries!=1||value!=p.packet.getSequence())
                ||p.phase==Phase.ACK_PREFIX&&(p.prefixBefore.acknowledgement()<0||value!=-1))
            throw p.refusal("Native ack write escaped exact original mining branch");p.ack=value;return true;
    }
    public static boolean captureCrack(ServerLevel level,int breakerId,BlockPos pos,int stage) {
        var p=ACTIVE.get();if(p==null)return false;p.require();
        if(level!=p.level||breakerId!=p.player.getId()||p.phase!=Phase.MODE_PACKET&&p.phase!=Phase.MODE_TICK)
            throw p.refusal("Native crack output escaped its exact mode scope");
        try{p.journal.crack(breakerId,NativeMiningControlState.cell(pos),stage);return true;}
        catch(RuntimeException|Error failure){p.poison(failure);throw failure;}
    }
    public static boolean captureOutput(Object receiver,Packet<?> packet,PacketSendListener listener) {
        var p=ACTIVE.get();if(p==null)return false;p.require();
        if(receiver!=p.receiver||listener!=null||p.phase!=Phase.MODE_PACKET&&p.phase!=Phase.MODE_TICK&&p.phase!=Phase.ACK_PREFIX)
            throw p.refusal("Native mining output escaped exact receiver/phase/null-callback closure");
        if(p.phase==Phase.ACK_PREFIX&&(packet==null||packet.getClass()!=net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket.class
                ||((net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket)packet).sequence()!=p.ack||p.ack<0)
                ||p.phase!=Phase.ACK_PREFIX&&(packet==null||packet.getClass()!=net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket.class))
            throw p.refusal("Native output packet differs from its source-qualified control branch");
        try{p.journal.packet(packet);return true;}catch(RuntimeException|Error failure){p.poison(failure);throw failure;}
    }
    public static boolean captureDestroy(ServerPlayerGameMode receiver,BlockPos pos) {
        var p=ACTIVE.get();if(p==null)return false;p.require();
        if(receiver!=p.scratch||p.phase!=Phase.MODE_PACKET&&p.phase!=Phase.MODE_TICK||++p.destroyEntries!=1)
            throw p.refusal("Native destroy escaped exact original scratch call");
        var target=NativeMiningControlState.cell(pos);
        p.destroyIndex=p.journal.count();p.invocation=new DestroyInvocation(p,target);
        try {
            CubeSnapshot before=p.reservation.snapshot(target.cube());
            p.broken=p.owner.host.prepareMiningBreak(p.action,target,before,p.invocation,p.reservation);
            return true;
        } catch(RuntimeException|Error failure){p.poison(failure);throw failure;}
    }
}
