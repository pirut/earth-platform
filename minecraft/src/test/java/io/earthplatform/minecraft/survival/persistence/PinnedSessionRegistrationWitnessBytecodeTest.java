package io.earthplatform.minecraft.survival.persistence;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
/** Authored original-classfile call-site checks; transformed/runtime witness remains a GameTest gate. */
final class PinnedSessionRegistrationWitnessBytecodeTest {
 @Test void neoForgeCapabilityPostIsExactFourteenthConsumerCall()throws IOException{
  assertSite("net/neoforged/neoforge/common/NeoForgeMod",13,"net/neoforged/neoforge/capabilities/CapabilityHooks","cleanCapabilityListenerReferencesOnTick","net/neoforged/neoforge/event/tick/LevelTickEvent$Post");
 }
 @Test void earthDimensionAndLoginBindTheirOriginalExactConsumers()throws IOException{
  assertSite("io/earthplatform/minecraft/EarthPlatformMod",3,"io/earthplatform/minecraft/EarthPlatformMod","playerChangedDimension","net/neoforged/neoforge/event/entity/player/PlayerEvent$PlayerChangedDimensionEvent");
  assertSite("io/earthplatform/minecraft/EarthPlatformMod",4,"io/earthplatform/minecraft/EarthPlatformMod","playerLoggedIn","net/neoforged/neoforge/event/entity/player/PlayerEvent$PlayerLoggedInEvent");
 }
 @Test void exactForeignClassResourcesAndUnchangedPinsAreRequired(){
  assertNotSame(NativeSessionCallbackPins.class.getModule(),net.neoforged.bus.EventBus.class.getModule());
  assertTrue(NativeSessionCallbackPins.verifiedBytes("net.neoforged.bus.EventBus").length>0);
  assertThrows(io.earthplatform.minecraft.survival.world.SurvivalRefusal.class,()->NativeSessionCallbackPins.verifyExactBinary("net.neoforged.bus.EventBus","0".repeat(64)));
  assertThrows(io.earthplatform.minecraft.survival.world.SurvivalRefusal.class,()->NativeSessionCallbackPins.verifyExactBinary("missing.ClosedProfileClass","0".repeat(64)));
  assertThrows(io.earthplatform.minecraft.survival.world.SurvivalRefusal.class,()->NativeSessionCallbackPins.verifiedBytes("java.lang.String"));
 }
 private static void assertSite(String host,int ordinal,String target,String method,String event)throws IOException{
  var node=new ClassNode();new ClassReader(NativeSessionCallbackPins.verifiedBytes(host.replace('/','.'))).accept(node,0);
  var constructors=node.methods.stream().filter(m->m.name.equals("<init>")).toList();assertEquals(1,constructors.size());
  int seen=0,matches=0;
  for(var insn:constructors.getFirst().instructions)if(insn instanceof MethodInsnNode call && call.owner.equals("net/neoforged/bus/api/IEventBus")&&call.name.equals("addListener")&&call.desc.equals("(Ljava/util/function/Consumer;)V")){
   if(seen++!=ordinal)continue;matches++;
   var previous=executableBefore(call);assertInstanceOf(InvokeDynamicInsnNode.class,previous);var indy=(InvokeDynamicInsnNode)previous;
   assertEquals("java/lang/invoke/LambdaMetafactory",indy.bsm.getOwner());assertEquals("metafactory",indy.bsm.getName());
   assertEquals("()Ljava/util/function/Consumer;",indy.desc);assertEquals(3,indy.bsmArgs.length);
   assertEquals(new Handle(Opcodes.H_INVOKESTATIC,target,method,"(L"+event+";)V",false),indy.bsmArgs[1]);
   assertEquals(Type.getMethodType("(L"+event+";)V"),indy.bsmArgs[2]);
   var receiver=executableBefore(indy);assertInstanceOf(FieldInsnNode.class,receiver);var field=(FieldInsnNode)receiver;
   assertEquals(Opcodes.GETSTATIC,field.getOpcode());assertEquals("net/neoforged/neoforge/common/NeoForge",field.owner);assertEquals("EVENT_BUS",field.name);
  }
  assertEquals(1,matches);
 }
 private static AbstractInsnNode executableBefore(AbstractInsnNode value){var previous=value.getPrevious();while(previous!=null&&previous.getOpcode()<0)previous=previous.getPrevious();return previous;}
}
