package io.earthplatform.minecraft.survival.interaction.host;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.io.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Data/budget tests only. Real handler/state conservation is covered separately by transformed GameTests. */
class NativeMenuPacketJournalTest {
 private static HolderLookup.Provider registries;private static NativeStackSnapshot empty,log,preview;
 @BeforeAll static void setup(){registries=VanillaRegistries.createLookup();empty=NativeStackSnapshot.capture(ItemStack.EMPTY,registries);log=NativeStackSnapshot.capture(new ItemStack(Items.OAK_LOG),registries);preview=NativeStackSnapshot.capture(new ItemStack(Items.OAK_PRESSURE_PLATE),registries);}
 private static NativeSessionMenuBoundary.MenuData menu(boolean suppressed,NativeStackSnapshot carried,int state){return new NativeSessionMenuBoundary.MenuData(0,Collections.nCopies(4,empty),empty,carried,null,0,state,Collections.nCopies(46,empty),Collections.nCopies(46,empty),empty,-1,0,List.of(),suppressed);}
 private static NativeSessionMenuBoundary.Data data(NativeSessionMenuBoundary.MenuData menu){return new NativeSessionMenuBoundary.Data(Collections.nCopies(41,empty),0,0,0,menu,null);}
 private static NativeMenuPacketInput input(){return new NativeMenuPacketInput(0,7,36,0,List.of(new NativeMenuPacketInput.Prediction(0,preview)),log);}
 private static NativeMenuPacketJournal journal(){return new NativeMenuPacketJournal(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),5,input(),new NativeSessionMenuPreparation.Result(data(menu(false,empty,7)),data(menu(true,log,7)),data(menu(false,log,8)),List.of(),false,1,8));}
 @Test void predictionPreviewNeverBecomesAuthoritativeInventory(){var journal=journal();assertEquals(preview,journal.packet().changedSlots().getFirst().stack());assertTrue(journal.transcript().after().inventory().stream().allMatch(NativeStackSnapshot::isEmpty));assertEquals(log,journal.transcript().after().active().carried());}
 @Test void completeCanonicalPacketJournalRoundTrips()throws Exception{var bytes=new ByteArrayOutputStream();journal().write(new DataOutputStream(bytes));var input=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));assertEquals(journal(),NativeMenuPacketJournal.read(input,registries));assertEquals(0,input.available());}
 @Test void duplicatePredictionsAndInvalidSlotsRefuse(){assertThrows(IllegalArgumentException.class,()->new NativeMenuPacketInput(0,1,36,0,List.of(new NativeMenuPacketInput.Prediction(1,empty),new NativeMenuPacketInput.Prediction(1,log)),empty));for(int slot:new int[]{-999,-1,46,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->new NativeMenuPacketInput(0,1,slot,0,List.of(),empty));for(int slot:new int[]{5,8,45})assertThrows(SurvivalRefusal.class,()->new NativeMenuPacketInput(0,1,slot,0,List.of(),empty).requireShape(false));}
 @Test void syncCannotChangeAuthoritativeCursorOrInventory(){var original=journal();assertThrows(IllegalArgumentException.class,()->new NativeMenuPacketJournal(original.session(),original.revision(),original.packet(),new NativeSessionMenuPreparation.Result(original.transcript().before(),original.transcript().clicked(),data(menu(false,empty,8)),List.of(),false,1,8)));}
 @Test void unresolvedMenusAreNeverDurableBoundaries(){assertTrue(data(menu(false,empty,0)).durableResolved());assertFalse(data(menu(false,log,0)).durableResolved());assertFalse(data(menu(true,empty,0)).durableResolved());}
 @Test void scratchHeadroomIsReservedOnceWithinParentCeiling(){var budget=new NativeMenuBudget(2*1024*1024);budget.reserve(100);var scratch=budget.scratch();int used=budget.used();assertEquals(1024*1024+100,used);assertEquals(1024*1024,scratch.remaining());assertEquals(1024*1024,budget.scratch().remaining());assertEquals(used,budget.used());assertThrows(SurvivalRefusal.class,()->budget.reserve(1024*1024));assertThrows(IllegalArgumentException.class,()->new NativeMenuBudget(16*1024*1024+1));}
 @Test @SuppressWarnings({"rawtypes","unchecked"}) void oversizedTrustedPrototypeRefusesBeforeComponentLookup()throws Exception{
  var stack=new ItemStack(Items.OAK_LOG);var itemField=net.minecraft.world.item.Item.class.getDeclaredField("components");var stackField=ItemStack.class.getDeclaredField("components");assertTrue(itemField.trySetAccessible()&&stackField.trySetAccessible());
  Object originalItem=itemField.get(Items.OAK_LOG),originalStack=stackField.get(stack);
  var map=new it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap();
  for(var key:net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.stream().limit(17).toList())map.put(key,new Object());
  assertEquals(17,map.size());var type=Class.forName("net.minecraft.core.component.DataComponentMap$Builder$SimpleMap",false,ItemStack.class.getClassLoader());var constructor=type.getDeclaredConstructor(it.unimi.dsi.fastutil.objects.Reference2ObjectMap.class);assertTrue(constructor.trySetAccessible());
  var prototype=(net.minecraft.core.component.DataComponentMap)constructor.newInstance(map);
  try{itemField.set(Items.OAK_LOG,prototype);stackField.set(stack,new net.minecraft.core.component.PatchedDataComponentMap(prototype));assertThrows(SurvivalRefusal.class,()->NativeMenuBudget.requireComponents(stack));}
  finally{itemField.set(Items.OAK_LOG,originalItem);stackField.set(stack,originalStack);}
 }
 @Test void exactIntermediateHoePreviewDoesNotAdmitOwnedOrTakenItem(){
  var hoe=new ItemStack(Items.WOODEN_HOE);
  assertDoesNotThrow(()->NativeMenuBudget.requirePreviewComponents(hoe));
  var image=NativeStackSnapshot.capture(hoe,registries);assertEquals(image,NativeStackSnapshot.capture(image.materialize(registries),registries));
  assertThrows(SurvivalRefusal.class,()->NativeMenuBudget.requireComponents(hoe));
  assertThrows(SurvivalRefusal.class,()->PinnedNativeEventAdmission.requireOrdinaryItemType(Items.WOODEN_HOE));
  assertFalse(NativeFirstLoopMetadata.itemUniverse().contains(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(Items.WOODEN_HOE)));
  assertThrows(SurvivalRefusal.class,()->NativeMenuBudget.requirePreviewComponents(new ItemStack(Items.STONE_HOE)));
  assertThrows(SurvivalRefusal.class,()->NativeMenuBudget.requirePreviewComponents(new ItemStack(Items.WOODEN_SHOVEL)));
 }
 @Test void corruptPredictionCountRefusesBeforeAllocation()throws Exception{var outBytes=new ByteArrayOutputStream();var out=new DataOutputStream(outBytes);out.writeLong(1);out.writeLong(2);out.writeLong(0);for(int x:new int[]{0,1,36,0,Integer.MAX_VALUE})out.writeInt(x);assertThrows(IllegalArgumentException.class,()->NativeMenuPacketJournal.read(new DataInputStream(new ByteArrayInputStream(outBytes.toByteArray())),registries));}
}
