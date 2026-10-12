package io.earthplatform.minecraft.survival.interaction.host;
import static org.junit.jupiter.api.Assertions.*;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.data.registries.VanillaRegistries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
class NativePlayerMoveSnapshotAdmissionTest {
 @BeforeAll static void setup(){VanillaRegistries.createLookup();}
 @Test void firstLoopPrototypeCensusAndDamagePatchStayBounded(){
  for(var item:new net.minecraft.world.item.Item[]{Items.DIRT,Items.OAK_LOG,Items.OAK_PLANKS,Items.STICK,Items.CRAFTING_TABLE,Items.WOODEN_PICKAXE,Items.STONE_PICKAXE}){
   var stack=new ItemStack(item);assertDoesNotThrow(()->NativePlayerMoveSnapshotAdmission.requireComponents(stack));
   Object prototype=NativePlayerStepSnapshot.field(net.minecraft.world.item.Item.class,item,"components");
   var map=(java.util.Map<?,?>)NativePlayerStepSnapshot.field(prototype.getClass(),prototype,"map");
   assertEquals(item==Items.WOODEN_PICKAXE||item==Items.STONE_PICKAXE?9:6,map.size());
  }
  var pick=new ItemStack(Items.WOODEN_PICKAXE);pick.set(DataComponents.DAMAGE,1);
  assertDoesNotThrow(()->NativePlayerMoveSnapshotAdmission.requireComponents(pick));
 }
 @Test void foreignComponentPatchRefusesBeforeItsCollectionCallback() throws Exception {
  var stack=new ItemStack(Items.DIRT);
  Object components=NativePlayerStepSnapshot.field(ItemStack.class,stack,"components");
  var field=PatchedDataComponentMap.class.getDeclaredField("patch");assertTrue(field.trySetAccessible());
  Object original=field.get(components);
  var trap=new Reference2ObjectArrayMap<Object,Object>(){@Override public int size(){fail("Foreign collection callback was invoked");return 0;}};
  try{field.set(components,trap);assertThrows(SurvivalRefusal.class,()->NativePlayerMoveSnapshotAdmission.requireComponents(stack));}
  finally{field.set(components,original);}
 }
 @Test void removedOrExtraComponentRefusesBeforeCodec(){
  var stack=new ItemStack(Items.DIRT);stack.remove(DataComponents.LORE);
  assertThrows(SurvivalRefusal.class,()->NativePlayerMoveSnapshotAdmission.requireComponents(stack));
  assertThrows(SurvivalRefusal.class,()->NativePlayerMoveSnapshotAdmission.requireComponents(null));
 }
 @Test void auxiliaryAdvancementMapsRefuseForeignCallbacksBeforeLookup(){
  var trap=new java.util.HashMap<Object,Object>(){@Override public int size(){fail("Foreign criteria-map callback was invoked");return 0;}};
  assertThrows(SurvivalRefusal.class,()->NativePlayerStepNoAwardMetadata.requireImmutableMap(trap,1));
  assertThrows(SurvivalRefusal.class,()->NativePlayerStepNoAwardMetadata.requireImmutableMap(java.util.Map.of("a",1,"b",2),1));
  assertDoesNotThrow(()->NativePlayerStepNoAwardMetadata.requireImmutableMap(java.util.Map.of("a",1),1));
 }
 @Test void absentPairingOriginCannotAuthorizeSameClassSet(){
  assertThrows(SurvivalRefusal.class,()->NativePlayerMoveTracking.requirePairingOrigin(new Object(),java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>())));
 }
}
