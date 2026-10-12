package io.earthplatform.minecraft.survival.persistence;
import java.util.List;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.Event;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Component refusal tests on isolated actual native buses; not real registered transfer evidence. */
final class NativeSessionListenerCensusTest {
 public static class ParentEvent extends Event {}
 public static final class ChildEvent extends ParentEvent {}
 @Test void emptyEffectiveRosterIsReadOnly(){
  var bus=BusBuilder.builder().build();var census=new NativeSessionListenerCensus(bus);
  assertDoesNotThrow(()->census.require(ChildEvent.class.getName()));assertDoesNotThrow(()->census.require(ChildEvent.class.getName()));
 }
 @Test void additionalActualListenerIsRefusedWithoutInvocation(){
  var bus=BusBuilder.builder().build();bus.addListener(ChildEvent.class,event->{throw new AssertionError("Must never invoke callback to infer admission");});
  var census=new NativeSessionListenerCensus(bus);assertThrows(RuntimeException.class,()->census.require(ChildEvent.class.getName()));
 }
 @Test void inheritedActualListenerIsNotHiddenByLeafCensus(){
  var bus=BusBuilder.builder().build();bus.addListener(ParentEvent.class,event->{throw new AssertionError("Must not invoke inherited callback");});
  var census=new NativeSessionListenerCensus(bus);assertThrows(RuntimeException.class,()->census.require(ChildEvent.class.getName()));
 }
 @Test void lateRegistrationInvalidatesPreviouslyEmptyAdmission(){
  var bus=BusBuilder.builder().build();var census=new NativeSessionListenerCensus(bus);census.require(ChildEvent.class.getName());
  bus.addListener(ChildEvent.class,event->{throw new AssertionError("Must not invoke late callback");});
  assertThrows(RuntimeException.class,()->census.require(ChildEvent.class.getName()));
 }
 @Test void equalValuesNeverReplaceListenerIdentity(){
  assertFalse(NativeSessionListenerCensus.sameIdentity(List.of(new String("same")),List.of(new String("same"))));
  Object value=new Object();assertTrue(NativeSessionListenerCensus.sameIdentity(List.of(value),List.of(value)));
  assertFalse(NativeSessionListenerCensus.sameIdentity(List.of(value),List.of()));
 }
 @Test void publicWitnessCannotMintAConsumerOutsideNativeRegistration(){
  String a=System.getProperty("earthplatform.nativeSessionBridge"),b=System.getProperty("neoforge.gameTestServer");
  try{System.setProperty("earthplatform.nativeSessionBridge","true");System.setProperty("neoforge.gameTestServer","true");
   for(var site:NativeSessionCallbackWitness.Site.values())assertThrows(RuntimeException.class,()->NativeSessionCallbackWitness.observe(site,value->{}));
  }finally{if(a==null)System.clearProperty("earthplatform.nativeSessionBridge");else System.setProperty("earthplatform.nativeSessionBridge",a);if(b==null)System.clearProperty("neoforge.gameTestServer");else System.setProperty("neoforge.gameTestServer",b);}
 }
}
