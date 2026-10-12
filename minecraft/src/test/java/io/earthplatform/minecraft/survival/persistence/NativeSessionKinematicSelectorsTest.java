package io.earthplatform.minecraft.survival.persistence;
import java.util.*;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
/** Exact original-class targets plus compiled injection metadata; actual bridge transform is separate. */
final class NativeSessionKinematicSelectorsTest {
 @Test void allSixteenPinnedOverloadsAreExplicitlyRequired() throws Exception {
  String[] names={"move","moveTo","absMoveTo","setPos","setPosRaw","setDeltaMovement","push"};
  Set<String> wantedNames=Set.of(names),actual=new HashSet<>();var entity=new ClassNode();
  byte[] bytes=NativeSessionCallbackPins.verifyExactBinary(Entity.class.getName(),"6a5a5b7db211b144fe88f3079a0e1d93a0c31db069acab4ac593dce8d144e8cb");
  new ClassReader(bytes).accept(entity,0);
  for(var method:entity.methods)if(wantedNames.contains(method.name))actual.add(method.name+method.desc);
  assertEquals(16,actual.size());var mixin=new ClassNode();
  try(var input=getClass().getResourceAsStream("/io/earthplatform/minecraft/mixin/SurvivalSessionKinematicHoldMixin.class")){
   assertNotNull(input);new ClassReader(input).accept(mixin,0);
  }
  var method=mixin.methods.stream().filter(m->m.name.equals("earthplatform$holdKinematics")).findFirst().orElseThrow();
  var annotations=new ArrayList<AnnotationNode>();if(method.visibleAnnotations!=null)annotations.addAll(method.visibleAnnotations);if(method.invisibleAnnotations!=null)annotations.addAll(method.invisibleAnnotations);
  var inject=annotations.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElseThrow();
  var values=new HashMap<String,Object>();for(int i=0;i<inject.values.size();i+=2)values.put((String)inject.values.get(i),inject.values.get(i+1));
  assertEquals(16,values.get("require"));assertEquals(16,values.get("allow"));
  assertInstanceOf(List.class,values.get("method"));var selectors=(List<?>)values.get("method");assertEquals(16,selectors.size());assertEquals(actual,new HashSet<>(selectors));
  assertTrue(selectors.stream().allMatch(s->s instanceof String text&&text.contains("(")&&text.endsWith(")V")));
 }
 @Test void keepaliveInvokerTargetsTheExactNativeDeclaringClass() throws Exception {
  var common=new ClassNode();new ClassReader(NativeSessionCallbackPins.verifyExactBinary(
    "net.minecraft.server.network.ServerCommonPacketListenerImpl","2626df5c94f13f877364d5c13aba9d587f2261589f039ccd89b52341eeb0a1e0")).accept(common,0);
  var target=common.methods.stream().filter(m->m.name.equals("keepConnectionAlive")&&m.desc.equals("()V")).findFirst().orElseThrow();
  assertTrue((target.access&org.objectweb.asm.Opcodes.ACC_PROTECTED)!=0);
  var game=new ClassNode();new ClassReader(NativeSessionCallbackPins.verifyExactBinary(
    "net.minecraft.server.network.ServerGamePacketListenerImpl","0602f43318d5bf25d5c2f0468728dd26a61cac8e899e58a86dd5a81343417138")).accept(game,0);
  assertTrue(game.methods.stream().noneMatch(m->m.name.equals("keepConnectionAlive")));
  var bridge=new ClassNode();try(var input=getClass().getResourceAsStream("/io/earthplatform/minecraft/mixin/SurvivalSessionCommonAccess.class")){
   assertNotNull(input);new ClassReader(input).accept(bridge,0);
  }
  var method=bridge.methods.stream().filter(m->m.name.equals("earthplatform$keepSessionConnectionAlive")).findFirst().orElseThrow();assertEquals("()V",method.desc);
  var annotations=new ArrayList<AnnotationNode>();if(method.visibleAnnotations!=null)annotations.addAll(method.visibleAnnotations);if(method.invisibleAnnotations!=null)annotations.addAll(method.invisibleAnnotations);
  var invoker=annotations.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/gen/Invoker;")).findFirst().orElseThrow();
  assertEquals(List.of("value","keepConnectionAlive"),invoker.values);
 }

}
