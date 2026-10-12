package io.earthplatform.minecraft.survival.interaction.host;
import static org.junit.jupiter.api.Assertions.*;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import org.junit.jupiter.api.Test;
class NativePlayerMoveBudgetTest {
 @Test void capturesShareCumulativeAllowanceInsteadOfResettingPerSnapshot(){
  var b=new NativePlayerStepBudget(256*1024);
  b.reserveScratch();b.reserve(NativePlayerStepBudget.ACTION_COPIES);b.snapshot();b.inventory();b.snapshot();b.inventory();b.snapshot();
  b.requireRemaining(2*NativePlayerStepBudget.SNAPSHOT+3*NativePlayerStepBudget.INVENTORY);
  b.inventory();b.reserveDelivery(2*NativePlayerStepBudget.SNAPSHOT+2*NativePlayerStepBudget.INVENTORY);b.inventory();b.snapshot();b.snapshot();b.inventory();
  assertEquals(224*1024,b.used());assertThrows(SurvivalRefusal.class,b::inventory);
  b.reserve(4*NativePlayerStepBudget.PROJECTED_ITEM);assertEquals(256*1024,b.used());
  assertThrows(SurvivalRefusal.class,()->b.reserve(NativePlayerStepBudget.PROJECTED_ITEM));
  assertThrows(SurvivalRefusal.class,()->b.reserve(1));assertEquals(256*1024,b.used());
 }
 @Test void postcommitCapacityMustExistBeforeAcceptance(){
  var b=new NativePlayerStepBudget(128*1024);b.snapshot();
  assertDoesNotThrow(()->b.requireRemaining(4*NativePlayerStepBudget.SNAPSHOT));
  assertDoesNotThrow(()->b.requireRemaining(128*1024-b.used()));
  assertThrows(SurvivalRefusal.class,()->b.requireRemaining(128*1024-b.used()+1));
  assertThrows(SurvivalRefusal.class,()->b.reserve(Integer.MAX_VALUE));
  assertThrows(SurvivalRefusal.class,()->b.reserve(-1));
 }
 @Test void oneByteUnderDeliveryHeadroomRefusesBeforeAnyCapture(){
  int bytes=2*NativePlayerStepBudget.SNAPSHOT+2*NativePlayerStepBudget.INVENTORY;
  var shortBudget=new NativePlayerStepBudget(bytes-1);
  assertThrows(SurvivalRefusal.class,()->shortBudget.reserveDelivery(bytes));assertEquals(0,shortBudget.used());
  var exact=new NativePlayerStepBudget(bytes);exact.reserveDelivery(bytes);
  exact.snapshot();exact.snapshot();exact.inventory();exact.inventory();assertEquals(bytes,exact.used());
  assertThrows(SurvivalRefusal.class,exact::inventory);
 }
 @Test void oversizedMenuPayloadCannotResetItsPrepaidParentSnapshot(){
  var shared=new NativePlayerStepBudget(128*1024);shared.reserveScratch();int frame=shared.snapshot();int before=shared.used();
  var menu=new NativeInventoryMenuSnapshot.Budget(frame-NativePlayerStepBudget.INVENTORY-4096);
  menu.charge(frame-NativePlayerStepBudget.INVENTORY-4096);
  assertThrows(SurvivalRefusal.class,()->menu.charge(1));assertEquals(before,shared.used());
  shared.snapshot();assertEquals(before+NativePlayerStepBudget.SNAPSHOT,shared.used());
 }
 @Test void unownedTickRootsRefuseBeforeAnyNativeReceiverIsRead(){
  assertThrows(SurvivalRefusal.class,()->NativePlayerStepAdmission.requireInput(null,NativePlayerStepOperation.ENTITY_TICK,null));
  assertThrows(SurvivalRefusal.class,()->NativePlayerStepAdmission.requireInput(null,NativePlayerStepOperation.CONNECTION_TICK,null));
 }
 @Test void callersCannotMintHostMovePermits(){
  for(var constructor:NativeEffectDeliveryHost.PlayerMovePermit.class.getDeclaredConstructors())
   assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
 }
}
