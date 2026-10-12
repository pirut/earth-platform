package io.earthplatform.minecraft.survival.interaction;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.survival.world.*;
import io.earthplatform.minecraft.survival.interaction.host.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Data-only table/placement effects; private native lifecycle capability is tested separately. */
class NativeTableEffectCodecTest {
 static HolderLookup.Provider registries;
 static NativeStackSnapshot empty;
 static final WorldIdentity WORLD=new WorldIdentity(39,"marin","v1","source","palette");
 static final UUID OWNER=new UUID(1,2),ACTOR=new UUID(3,4);
 static final CubeCell TABLE=CubeCell.of(-13640057,134,-4213188);
 @BeforeAll static void init(){registries=VanillaRegistries.createLookup();empty=NativeStackSnapshot.capture(ItemStack.EMPTY,registries);}
 static List<NativeStackSnapshot> stacks(int n){return Collections.nCopies(n,empty);}
 static NativeInventoryMenuSnapshot.Data inventory(){return new NativeInventoryMenuSnapshot.Data(stacks(41),0,7,stacks(4),empty,empty,null,0,0,stacks(46),stacks(46),empty,false,-1);}
 static NativeCraftingTableSnapshot.Data table(int id){return new NativeCraftingTableSnapshot.Data(id,stacks(41),0,7,stacks(9),empty,empty,null,0,0,stacks(46),stacks(46),empty,false,-1);}
 static NativeCraftingTableSnapshot.Data change(NativeCraftingTableSnapshot.Data d,int selected,boolean suppressed,int drag,NativeStackSnapshot cursor){
  return new NativeCraftingTableSnapshot.Data(d.containerId(),d.inventory(),selected,d.inventoryChanges(),d.grid(),d.result(),cursor,d.recipeUsed(),d.removeCount(),d.stateId(),d.lastSlots(),d.remoteSlots(),d.remoteCarried(),suppressed,drag);
 }
 static NativeEffectCodec.ResolvedTableScript script(int counter,List<NativeInventoryMenuPreparation.Click> clicks,NativeCraftingTableSnapshot.Data before,NativeCraftingTableSnapshot.Data after,List<NativeCraftingTableSnapshot.Data> steps,List<NativeInventoryMenuPreparation.Notice> notices){
  return new NativeEffectCodec.ResolvedTableScript(WORLD,OWNER,ACTOR,9,TABLE,4,counter,clicks,new NativeCraftingTablePreparation.Result(inventory(),before,after,notices,steps));
 }
 static List<NativeInventoryMenuPreparation.Click> clicks(int n){return Collections.nCopies(n,new NativeInventoryMenuPreparation.Click(10,0,ClickType.PICKUP));}
 static NativeEffectCodec.ResolvedTableScript simple(int n){var d=table(1);return script(0,clicks(n),d,d,Collections.nCopies(n,d),List.of());}
 @Test void tableContainerCycleAndFullImagesRoundTrip(){for(int counter:new int[]{0,1,99,100}){var d=table(counter%100+1);var value=script(counter,clicks(1),d,d,List.of(d),List.of());assertEquals(value,NativeEffectCodec.decode(NativeEffectCodec.encode(value),registries));}}
 @Test void tableCoordinatesRetainSignedYWithoutPackedAliases(){var a=simple(1);var b=new NativeEffectCodec.ResolvedTableScript(a.world(),a.ownerEpoch(),a.actor(),a.sequence(),CubeCell.of(TABLE.x(),TABLE.y()+4096,TABLE.z()),a.tableStateId(),a.containerCounterBefore(),a.clicks(),a.transcript());assertNotEquals(NativeEffectCodec.encode(a),NativeEffectCodec.encode(b));assertEquals(b,NativeEffectCodec.decode(NativeEffectCodec.encode(b),registries));}
 @Test void wrongContainerCycleOrBeforeInventoryCannotBeEncoded(){var d=table(2);assertThrows(IllegalArgumentException.class,()->script(0,clicks(1),d,d,List.of(d),List.of()));var before=table(1);var changed=new NativeCraftingTableSnapshot.Data(1,stacks(41),0,8,stacks(9),empty,empty,null,0,0,stacks(46),stacks(46),empty,false,-1);assertThrows(IllegalArgumentException.class,()->script(0,clicks(1),changed,changed,List.of(changed),List.of()));for(int counter:new int[]{-1,101})assertThrows(IllegalArgumentException.class,()->script(counter,clicks(1),before,before,List.of(before),List.of()));}
 @Test void selectedDragSuppressionAndUnresolvedCursorFailClosed(){var d=table(1);var log=NativeStackSnapshot.capture(new ItemStack(Items.OAK_LOG),registries);for(var changed:List.of(change(d,1,false,-1,empty),change(d,0,true,-1,empty),change(d,0,false,0,empty),change(d,0,false,-1,log)))assertThrows(IllegalArgumentException.class,()->script(0,clicks(1),d,changed,List.of(changed),List.of()));}
 @Test void onlyExactTablePickupShapeIsAdmitted(){var d=table(1);for(int slot:new int[]{-999,-1,46})assertThrows(IllegalArgumentException.class,()->script(0,List.of(new NativeInventoryMenuPreparation.Click(slot,0,ClickType.PICKUP)),d,d,List.of(d),List.of()));for(var kind:ClickType.values())if(kind!=ClickType.PICKUP)assertThrows(IllegalArgumentException.class,()->script(0,List.of(new NativeInventoryMenuPreparation.Click(1,0,kind)),d,d,List.of(d),List.of()));for(int slot:new int[]{0,1,9,10,36,37,45})assertDoesNotThrow(()->script(0,List.of(new NativeInventoryMenuPreparation.Click(slot,1,ClickType.PICKUP)),d,d,List.of(d),List.of()));}
 @Test void tableNoticesUseNineIngredientsAndExactContainer(){var d=table(1);var id=ResourceLocation.parse("minecraft:wooden_pickaxe");var good=List.<NativeInventoryMenuPreparation.Notice>of(new NativeInventoryMenuPreparation.Crafted(id,stacks(9)),new NativeInventoryMenuPreparation.SlotPacket(1,1,0,empty));var value=script(0,clicks(1),d,d,List.of(d),good);assertEquals(value,NativeEffectCodec.decode(NativeEffectCodec.encode(value),registries));for(var bad:List.<NativeInventoryMenuPreparation.Notice>of(new NativeInventoryMenuPreparation.Crafted(id,stacks(10)),new NativeInventoryMenuPreparation.SlotPacket(0,1,0,empty),new NativeInventoryMenuPreparation.SlotPacket(1,1,1,empty)))assertThrows(IllegalArgumentException.class,()->script(0,clicks(1),d,d,List.of(d),List.of(bad)));}
 @Test void allTruncationsAndTrailingDataAreRejected(){var effect=NativeEffectCodec.encode(simple(1));var payload=effect.payload();for(int n=0;n<payload.length;n++){var truncated=new NativeActionEffect(effect.type(),Arrays.copyOf(payload,n));assertThrows(RuntimeException.class,()->NativeEffectCodec.decode(truncated,registries));}assertThrows(IllegalArgumentException.class,()->NativeEffectCodec.decode(new NativeActionEffect(effect.type(),Arrays.copyOf(payload,payload.length+1)),registries));}
 @Test void boundedTranscriptCannotGrowBeyondExistingEffectCap(){assertThrows(IllegalArgumentException.class,()->NativeEffectCodec.encode(simple(128)));assertThrows(IllegalArgumentException.class,()->simple(129));var value=simple(1);assertThrows(UnsupportedOperationException.class,()->value.clicks().clear());assertThrows(UnsupportedOperationException.class,()->value.transcript().steps().clear());assertThrows(UnsupportedOperationException.class,()->value.transcript().after().inventory().clear());}
 @Test void placementSoundKeepsActorExclusionAndOldSoundEncoding(){var id=ResourceLocation.parse("minecraft:block.wood.place");var placed=new NativeEffectCodec.PlacementSound(id,"blocks",-13640057.5,7314.5,-4213188.5,1,.8F,123,ACTOR);assertEquals(placed,NativeEffectCodec.decode(NativeEffectCodec.encode(placed),registries));var old=new NativeEffectCodec.Sound(id,"blocks",placed.x(),placed.y(),placed.z(),1,.8F,123);var raw=NativeEffectCodec.encode(old);try{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(1);out.writeUTF(id.toString());out.writeUTF("blocks");out.writeDouble(old.x());out.writeDouble(old.y());out.writeDouble(old.z());out.writeFloat(1);out.writeFloat(.8F);out.writeLong(123);}assertArrayEquals(bytes.toByteArray(),raw.payload());}catch(IOException impossible){throw new AssertionError(impossible);}assertThrows(NullPointerException.class,()->new NativeEffectCodec.PlacementSound(id,"blocks",0,0,0,1,1,1,null));}
 @Test void blockPlaceEventBindsWorldActorAffectedStateAndFullAddress(){for(long y:new long[]{-11000,-2049,2047,7314,8849}){var cell=CubeCell.of(-13640057,y,-4213188);var event=new NativeEffectCodec.BlockPlaceEvent(WORLD,cell,ACTOR,8);assertEquals(event,NativeEffectCodec.decode(NativeEffectCodec.encode(event),registries));}assertThrows(IllegalArgumentException.class,()->new NativeEffectCodec.BlockPlaceEvent(WORLD,TABLE,ACTOR,-1));assertThrows(NullPointerException.class,()->new NativeEffectCodec.BlockPlaceEvent(WORLD,TABLE,null,8));}
}
