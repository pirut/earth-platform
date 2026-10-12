package io.earthplatform.minecraft.survival.persistence;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.server.level.ServerPlayer;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.*;

/** AUTHORED, UNRUN. One step per REAL GameTest server tick after genuine player/Level registration.
 * This harness uses the real Port, coordinator/store and original native ack handler. The embedded
 * handler call is NOT a real remote client/network handshake proof. Caller owns fresh disposable
 * storage, executor, native registration, source cubes, transport preparation and failure retention.
 * Do not use the old detached NativeSurvivalTestFixture or insert entries into server maps/lists.
 */
public final class NativeHeldSessionProbe {
 public record Evidence(int nativeTeleportPackets,int nativeAcks,long firstGeneration,long finalGeneration,
   long outerSchedulerSuppressions,long carrierTickSuppressions,boolean ordinaryExact,boolean currentReturned){}
 private final NativeSessionBridge bridge;
 private final NativeSessionExecutionGate gate;
 private final SurvivalSessionTransitions transitions;
 private final ServerPlayer player;
 private final EmbeddedChannel channel;
 private final SurvivalSessionTransitions.OrdinaryState before;
 private final long firstGeneration;
 private int teleportPackets,acks,activeTicks;
 private long serverTick=-1;
 private boolean done;
 public NativeHeldSessionProbe(NativeSessionBridge bridge,NativeSessionExecutionGate gate,EmbeddedChannel channel){
  this.bridge=bridge;this.gate=gate;this.player=gate.player();this.channel=channel;before=bridge.captureOrdinary();
  NativeSurvivalCheckpoint prior=bridge.durable();firstGeneration=prior==null?0:prior.generation();
  transitions=new SurvivalSessionTransitions(bridge);transitions.enter();
 }
 /** Caller schedules once on each actual GameTest tick; this never drives the Level directly. */
 public void step(){
  if(done)throw new IllegalStateException("Probe completed");
  long tick=player.server.getTickCount();if(tick==serverTick)throw new IllegalStateException("One probe step per real server tick required");serverTick=tick;
  channel.runPendingTasks();
  for(Object packet;(packet=channel.readOutbound())!=null;){
   try{if(packet instanceof ClientboundPlayerPositionPacket position){
    teleportPackets++;
    if(teleportPackets>2)throw new AssertionError("Unexpected/replayed native teleport packet");
    player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(position.getId()));acks++;
   }}finally{ReferenceCountUtil.release(packet);}
  }
  if(transitions.state()==SurvivalSessionTransitions.State.ACTIVE){
   if(!NativeSessionExecutionGate.blocksNative(player))throw new AssertionError("Unqualified native Earth execution was enabled");
   // A real intervening held ACTIVE scheduler turn must happen before requesting return.
   if(++activeTicks>=2)transitions.leave();
  }else transitions.tick();
  if(transitions.state()==SurvivalSessionTransitions.State.FAILED || transitions.state()==SurvivalSessionTransitions.State.RECOVERY_REQUIRED)
   throw new AssertionError("Real native held session failed; preserve first failure",transitions.failure());
  if(transitions.checkpointFailure()!=null)throw new AssertionError("Real checkpoint failed; do not invent completion",transitions.checkpointFailure());
  if(transitions.state()==SurvivalSessionTransitions.State.RETURNED){
   NativeSessionAdmission.requireOrdinaryExact(player,bridge.binding(),before);
   if(bridge.durable()==null || bridge.durable().session().phase()!=SessionPhase.RETURNED || acks!=2 || teleportPackets!=2)
    throw new AssertionError("Missing clean native return evidence");
   done=true;
  }
 }
 public boolean done(){return done;}
 public Evidence evidence(){
  if(!done)throw new IllegalStateException("Native held session incomplete");
  var hold=gate.evidence();if(hold.carrierRoots()<4 || hold.schedulerRoots()<1)throw new AssertionError("Real outer scheduler/carrier hold coverage absent");
  return new Evidence(teleportPackets,acks,firstGeneration,bridge.durable().generation(),hold.schedulerRoots(),hold.carrierRoots(),true,true);
 }
}
