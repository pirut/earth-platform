package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.mixin.*;
import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost;
import io.earthplatform.minecraft.survival.interaction.host.NativeSessionMenuObservers;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.*;

/** Native teleport ONCE, original native ack handler, cache invalidation then actual collision reads.
 * Any mutation prefix failure is terminal and remains held. There is no broad rollback or replay.
 */
final class NativePreparedSessionChange implements SurvivalSessionTransitions.PreparedChange {
 private final NativeSessionBridge bridge;
 private final NativeEffectDeliveryHost host;
 private final NativeSessionExecutionGate gate;
 private final ServerPlayer player;
 private final ServerLevel from,to;
 private final NativeInventorySnapshot inventory;
 private final NativeFoodSnapshot food;
 private final NativePlayerPoseSnapshot pose;
 private final List<ItemStack> stacks;
 private final NativeSessionMenuObservers menu;
 private final Session ordinary;
 private final boolean entering;
 private final Map<BlockPos,net.minecraft.world.level.block.state.BlockState> targetBlocks;
 private NativeSurvivalCheckpoint permit;
 private boolean attempted,settled;
 private int teleportId;
 private Throwable failure;
 private NativePreparedSessionChange(NativeSessionBridge bridge,NativeEffectDeliveryHost host,NativeSessionExecutionGate gate,
   ServerLevel target,NativeInventorySnapshot inventory,NativeFoodSnapshot food,NativePlayerPoseSnapshot pose,Session ordinary,boolean entering){
  this.bridge=bridge;this.host=host;this.gate=gate;player=gate.player();from=player.serverLevel();to=Objects.requireNonNull(target);
  this.inventory=inventory;this.food=food;this.pose=pose;this.ordinary=ordinary;this.entering=entering;
  gate.requireHeld();NativeInventorySnapshot.requireResolvedMenu(player);
  if(pose.velocityX()!=0 || pose.velocityY()!=0 || pose.velocityZ()!=0 || !player.getDeltaMovement().equals(Vec3.ZERO))
   throw new SurvivalRefusal("First held native bridge requires zero velocity on both sides; motion-packet synchronization is unqualified");
  if(!to.dimension().location().equals(pose.dimension()) || to==from)throw new SurvivalRefusal("Exact cross-Level native target required");
  if(((SurvivalSessionNativeAccess)player.connection).earthplatform$awaitingPosition()!=null)
   throw new SurvivalRefusal("An earlier native teleport is still awaiting acknowledgement");
  NativeSessionTransferAdmission.require(player,from,to);
  stacks=inventory.materialize(player.registryAccess());food.materialize();menu=NativeSessionMenuObservers.capture(player);
  targetBlocks=admitCollisionEnvelope();
 }
 static NativePreparedSessionChange enter(NativeSessionBridge bridge,NativeEffectDeliveryHost host,NativeSessionExecutionGate gate,Boundary earth){
  var pose=earth.pose();
  if(pose.x()!=io.earthplatform.geography.survival.MarinSurvivalDefinition.ANCHOR_X+0.5D || pose.y()!=135D
      || pose.z()!=io.earthplatform.geography.survival.MarinSurvivalDefinition.ANCHOR_Z+0.5D)
   throw new SurvivalRefusal("First held bridge requires the genuine global Marin anchor at feet Y=135; no low-Y alias");
  var ground=host.level().getBlockState(BlockPos.containing(pose.x(),134D,pose.z()));
  if(!ground.is(Blocks.GRASS_BLOCK))throw new SurvivalRefusal("Genuine cubic Marin grass support at Y=134 is unavailable");
  return new NativePreparedSessionChange(bridge,host,gate,host.level(),earth.inventory(),earth.food(),pose,null,true);
 }
 static NativePreparedSessionChange leave(NativeSessionBridge bridge,NativeEffectDeliveryHost host,NativeSessionExecutionGate gate,Session session){
  return new NativePreparedSessionChange(bridge,host,gate,NativeSessionAdmission.requireReturnTarget(gate.player(),bridge.binding(),session),
    session.ordinaryEscrow(),session.ordinaryFood(),session.ordinaryPose(),session,false);
 }
 public void applyOnce(NativeSurvivalCheckpoint exact){
  if(attempted)throw new SurvivalRefusal("Native session install/restore may run only once");attempted=true;permit=exact;
  try{
   bridge.requirePermit(exact,entering?SessionPhase.ENTERING:SessionPhase.RETURNING);
   if(entering) NativeSessionAdmission.requireOrdinaryExact(player,bridge.binding(),exact.session());
   else {NativeSessionAdmission.requireSameReturnTarget(player,bridge.binding(),ordinary,to);host.requireEmptyReturnOwners(gate);}
   if(player.serverLevel()!=from)throw new SurvivalRefusal("Prepared native source Level changed");
   NativeSessionTransferAdmission.require(player,from,to);menu.require();requireEnvelopeUnchanged();
   if(entering)gate.enterEarth(host);
   gate.beginNativeMutation(host);
   try{
    // Exact actual native cross-dimension call: no setServerLevel/moveTo substitution.
    player.teleportTo(to,pose.x(),pose.y(),pose.z(),pose.yaw(),pose.pitch());
    if(player.serverLevel()!=to)throw new SurvivalRefusal("Native cross-dimension transfer did not install its target");
    var listener=(SurvivalSessionNativeAccess)player.connection;
    Vec3 expected=new Vec3(pose.x(),pose.y(),pose.z());
    if(!expected.equals(listener.earthplatform$awaitingPosition()))throw new SurvivalRefusal("Native teleport did not own the exact pending target");
    teleportId=listener.earthplatform$awaitingTeleport();gate.expectTeleport(this,teleportId);
    for(int slot=0;slot<NativeInventorySnapshot.SLOTS;slot++)player.getInventory().setItem(slot,stacks.get(slot));
    player.getInventory().selected=inventory.selected();player.getFoodData().readAdditionalSaveData(food.tagCopy());
    ((SurvivalSessionModeAccess)player.gameMode).earthplatform$restoreExactModes(pose.gameMode(),pose.previousGameMode());
    var a=player.getAbilities();var expectedAbilities=pose.abilities();
    a.invulnerable=expectedAbilities.invulnerable();a.flying=expectedAbilities.flying();a.mayfly=expectedAbilities.mayFly();
    a.instabuild=expectedAbilities.instantBuild();a.mayBuild=expectedAbilities.mayBuild();
    a.setFlyingSpeed(expectedAbilities.flyingSpeed());a.setWalkingSpeed(expectedAbilities.walkingSpeed());
    player.setDeltaMovement(pose.velocityX(),pose.velocityY(),pose.velocityZ());player.fallDistance=pose.fallDistance();
    invalidateAndReadCollision();
    player.connection.send(new ClientboundPlayerAbilitiesPacket(a));
    player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHealthPacket(player.getHealth(),food.foodLevel(),food.saturation()));
    player.connection.send(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,player));
    menu.require();player.inventoryMenu.sendAllDataToRemote();player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(inventory.selected()));menu.require();
   }finally{gate.endNativeMutation(host);}
  }catch(RuntimeException | Error cause){failure=cause;gate.fail();throw cause;}
 }
 public SurvivalSessionTransitions.Result pollSettled(){
  if(failure!=null)return SurvivalSessionTransitions.Result.failed(failure);
  if(!attempted || settled)throw new SurvivalRefusal("Native settling is absent or already consumed");
  try{
   bridge.requirePermit(permit,entering?SessionPhase.ENTERING:SessionPhase.RETURNING);
   if(player.serverLevel()!=to || player.connection==null)throw new SurvivalRefusal("Native transfer target changed while awaiting acknowledgement");
   var connection=(SurvivalSessionNativeAccess)player.connection;
   if(connection.earthplatform$awaitingTeleport()!=teleportId)throw new SurvivalRefusal("Native teleport acknowledgement was overtaken");
   if(connection.earthplatform$awaitingPosition()!=null)return SurvivalSessionTransitions.Result.pending();
   if(!gate.consumeTeleportAck(this,teleportId))throw new SurvivalRefusal("Pending position disappeared without the exact native ack handler");
   if(player.isChangingDimension() || connection.earthplatform$firstGoodX()!=pose.x() || connection.earthplatform$firstGoodY()!=pose.y()
       || connection.earthplatform$firstGoodZ()!=pose.z() || connection.earthplatform$lastGoodX()!=pose.x()
       || connection.earthplatform$lastGoodY()!=pose.y() || connection.earthplatform$lastGoodZ()!=pose.z())
    throw new SurvivalRefusal("Native connection movement baselines have not settled");
   requireEnvelopeUnchanged();invalidateAndReadCollision();menu.require();
   if(!inventory.equals(NativeInventorySnapshot.capture(player,inventory.encodedBytes()))
      || !food.equals(NativeFoodSnapshot.capture(player.getFoodData())) || !pose.equals(NativePlayerPoseSnapshot.capture(player)))
    throw new SurvivalRefusal("Native settled state differs from its exact durable install/restore image");
   settled=true;if(!entering)bridge.ordinaryRestored(ordinary,permit);
   return SurvivalSessionTransitions.Result.succeeded();
  }catch(RuntimeException | Error cause){failure=cause;gate.fail();return SurvivalSessionTransitions.Result.failed(cause);}
 }
 boolean isSettled(){return settled && failure==null;}
 private AABB targetBox(){return player.getBoundingBox().move(pose.x()-player.getX(),pose.y()-player.getY(),pose.z()-player.getZ());}
 private Map<BlockPos,net.minecraft.world.level.block.state.BlockState> admitCollisionEnvelope(){
  AABB box=targetBox().inflate(1.0);int x1=(int)Math.floor(box.minX),x2=(int)Math.floor(box.maxX);
  int y1=(int)Math.floor(box.minY),y2=(int)Math.floor(box.maxY),z1=(int)Math.floor(box.minZ),z2=(int)Math.floor(box.maxZ);
  if((long)(x2-x1+1)*(y2-y1+1)*(z2-z1+1)>4096)throw new SurvivalRefusal("Native transfer collision envelope exceeds bound");
  var binding=SurvivalLevelBinding.find(to);
  if(binding!=null && !binding.hasResidentBox(x1,y1,z1,x2,y2,z2))throw new SurvivalRefusal("Native target collision envelope is not fully cubic-resident");
  var result=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
  for(int x=x1;x<=x2;x++)for(int y=y1;y<=y2;y++)for(int z=z1;z<=z2;z++){
   BlockPos pos=new BlockPos(x,y,z);var state=to.getBlockState(pos);Class<?> type=state.getBlock().getClass();
   if(type!=Block.class && type!=AirBlock.class && type!=GrassBlock.class)
    throw new SurvivalRefusal("Unadmitted native collision block class: "+type.getName());
   result.put(pos,state);
  }
  if(!to.getEntities(player,box).isEmpty() || !to.noCollision(player,targetBox()))throw new SurvivalRefusal("Native transfer target is occupied");
  return Map.copyOf(result);
 }
 private void requireEnvelopeUnchanged(){for(var e:targetBlocks.entrySet())if(to.getBlockState(e.getKey())!=e.getValue())throw new SurvivalRefusal("Prepared native target terrain changed");}
 private void invalidateAndReadCollision(){
  // Native position setters do not clear these at equal block coordinates across Level changes.
  var cache=(SurvivalSessionEntityCacheAccess)player;cache.earthplatform$clearInBlockState(null);cache.earthplatform$setOnGroundNoBlocks(false);
  player.mainSupportingBlockPos=Optional.empty();player.horizontalCollision=false;player.verticalCollision=false;player.verticalCollisionBelow=false;
  player.setOnGround(pose.onGround());
  if(player.getInBlockState()!=to.getBlockState(player.blockPosition()) || !to.noCollision(player,player.getBoundingBox()))
   throw new SurvivalRefusal("Actual native in-block/collision read disagrees after transfer");
  if(pose.onGround() && player.mainSupportingBlockPos.isEmpty())throw new SurvivalRefusal("Grounded native transfer lacks actual support-block proof");
 }
}
