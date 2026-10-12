package io.earthplatform.minecraft.survival.persistence;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
/** Pinned classfile tests only. These DO NOT exercise a transformed method, live hold or native save. */
class PinnedNativeSessionBridgeBytecodeTest {
 private ClassNode read(Class<?> type)throws Exception{try(var in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){
  assertNotNull(in);var node=new ClassNode();new ClassReader(in).accept(node,0);return node;}}
 private MethodNode method(Class<?> type,String name,String descriptor)throws Exception{return read(type).methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(descriptor)).findFirst().orElseThrow();}
 @Test void ackThreadHandoffAndTwoExitSitesAreExactlyPinned()throws Exception{
  var method=method(ServerGamePacketListenerImpl.class,"handleAcceptTeleportPacket","(Lnet/minecraft/network/protocol/game/ServerboundAcceptTeleportationPacket;)V");
  int handoffs=0,returns=0,clears=0;
  for(var i:method.instructions){if(i.getOpcode()==Opcodes.RETURN)returns++;
   if(i instanceof MethodInsnNode m && m.owner.equals("net/minecraft/network/protocol/PacketUtils")&&m.name.equals("ensureRunningOnSameThread")){
    assertEquals("(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",m.desc);handoffs++;}
   if(i instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTFIELD&&f.name.equals("awaitingPositionFromClient"))clears++;
  }
  assertEquals(1,handoffs);assertEquals(2,returns);assertEquals(1,clears);
 }
 @Test void outerSchedulerMutatesBeforePlayerTickSoInnerTickHoldAloneIsInsufficient()throws Exception{
  var m=method(ServerLevel.class,"tickNonPassenger","(Lnet/minecraft/world/entity/Entity;)V");int old=-1,count=-1,tick=-1;
  for(var i:m.instructions){if(i instanceof MethodInsnNode call&&call.name.equals("setOldPosAndRot"))old=m.instructions.indexOf(i);
   if(i instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTFIELD&&f.name.equals("tickCount"))count=m.instructions.indexOf(i);
   if(i instanceof MethodInsnNode call&&call.name.equals("tick")&&call.desc.equals("()V"))tick=m.instructions.indexOf(i);}
  assertTrue(old>=0&&old<count&&count<tick);
 }
 @Test void allSixteenKinematicRootsExist()throws Exception{
  Set<String> names=Set.of("move","moveTo","absMoveTo","setPos","setPosRaw","setDeltaMovement","push");
  var methods=read(Entity.class).methods.stream().filter(m->names.contains(m.name)).toList();assertEquals(16,methods.size());
  for(var m:methods)assertTrue(m.desc.endsWith(")V"));
 }
 @Test void nativeCrossDimensionEntryHasOriginalChangeDimensionCall()throws Exception{
  var m=method(ServerPlayer.class,"teleportTo","(Lnet/minecraft/server/level/ServerLevel;DDDFF)V");
  int nativeCalls=0;for(var i:m.instructions)if(i instanceof MethodInsnNode call&&call.name.equals("changeDimension"))nativeCalls++;
  assertEquals(1,nativeCalls);
 }
 @Test void ordinaryNettySchedulingDelegationIsOutsideFailureCatch()throws Exception{
  var method=method(NativeSessionExecutionGate.class,"runTeleportAck","(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/server/level/ServerPlayer;ILjava/lang/Runnable;)V");
  int directRuns=0;
  for(var i:method.instructions)if(i instanceof MethodInsnNode call&&call.owner.equals("java/lang/Runnable")&&call.name.equals("run")){
   directRuns++;int at=method.instructions.indexOf(i);
   for(var handler:method.tryCatchBlocks)assertFalse(method.instructions.indexOf(handler.start)<=at && at<method.instructions.indexOf(handler.end));
  }
  assertEquals(2,directRuns);
 }
 @Test void retainedFailedOrDisconnectedPlayerCannotVetoSuppressionBookkeeping()throws Exception{
  var owner=method(NativeSessionExecutionGate.class,"requireSuppressionOwner","()V");
  Set<String> forbidden=Set.of("requireIdentity","hasDisconnected","isRemoved","isAlive","isSameThread");
  for(var i:owner.instructions)if(i instanceof MethodInsnNode call)assertFalse(forbidden.contains(call.name));
  for(var i:owner.instructions)if(i instanceof FieldInsnNode field)assertFalse(Set.of("failed","transitionHeld","earthExecutionClosed").contains(field.name));
 }
 @Test void noPublicGateCanQualifyNativeEarthTicks(){
  for(var m:NativeSessionExecutionGate.class.getDeclaredMethods())assertFalse(Set.of("qualify","allowEarthTicks","unfenceCarrier").contains(m.getName()));
 }
}
