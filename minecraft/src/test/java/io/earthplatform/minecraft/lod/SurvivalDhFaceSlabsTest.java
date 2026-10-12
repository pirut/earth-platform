package io.earthplatform.minecraft.lod;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.*;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
class SurvivalDhFaceSlabsTest {
 static SurvivalDhTerrainData.PageFace face(Direction direction,int pageY,int ordinal){
  int y=direction==Direction.UP?2048:0;
  return new SurvivalDhTerrainData.PageFace(new PageFace(direction,pageY,ordinal%64,y,(ordinal/64)%64,1,1,0xff000000|ordinal),new int[]{1,2,3,5,13}[ordinal%5]);
 }
 @Test void everyScalarSignedPageEndpointAndInsertionOrderRoundTrips(){
  var slabs=new SurvivalDhTerrainData.FaceSlabs(4096);var values=new ArrayList<SurvivalDhTerrainData.PageFace>();var charge=new AtomicLong();
  for(int page:new int[]{-5,-2,-1,0,1,3})for(var direction:Direction.values())for(int i=0;i<9;i++){
   var f=face(direction,page,values.size());values.add(f);slabs.add(f,charge::addAndGet);
  }
  assertEquals(values.size(),slabs.size());assertThrows(SurvivalRefusal.class,()->slabs.get(0));slabs.freeze();
  for(int i=0;i<values.size();i++)assertEquals(values.get(i),slabs.get(i));
  assertEquals(2*SurvivalDhTerrainData.FaceSlabs.SLAB_BYTES,charge.get());
  assertThrows(SurvivalRefusal.class,()->slabs.add(values.getFirst(),charge::addAndGet));assertThrows(SurvivalRefusal.class,slabs::freeze);
 }
 @Test void wholeUnusedTailIsChargedBeforeAllocationAtEveryBoundary(){
  var slabs=new SurvivalDhTerrainData.FaceSlabs(4096);var charge=new AtomicLong();var f=face(Direction.UP,0,0);
  for(int i=0;i<4096;i++){slabs.add(f,charge::addAndGet);assertEquals(((i/256)+1)*SurvivalDhTerrainData.FaceSlabs.SLAB_BYTES,charge.get());}
  long total=charge.get();assertThrows(SurvivalRefusal.class,()->slabs.add(f,charge::addAndGet));assertEquals(total,charge.get());slabs.freeze();assertEquals(f,slabs.get(4095));
 }
 @Test void chargeFailureLeavesNoAppendedFaceOrPartiallyVisiblePayload(){
  var slabs=new SurvivalDhTerrainData.FaceSlabs(257);var charge=new AtomicLong();var f=face(Direction.UP,0,0);
  for(int i=0;i<256;i++)slabs.add(f,charge::addAndGet);
  var marker=new IllegalStateException("admission denied");assertSame(marker,assertThrows(IllegalStateException.class,()->slabs.add(f,n->{throw marker;})));assertEquals(256,slabs.size());
  slabs.freeze();assertEquals(f,slabs.get(255));assertThrows(IndexOutOfBoundsException.class,()->slabs.get(256));assertEquals(SurvivalDhTerrainData.FaceSlabs.SLAB_BYTES,charge.get());
 }
 @Test void limitsAndInvalidRecordFieldsRefuseBeforeSlabAdmission(){
  for(int n:new int[]{0,-1,4097,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->new SurvivalDhTerrainData.FaceSlabs(n));
  assertThrows(IllegalArgumentException.class,()->new SurvivalDhTerrainData.PageFace(new PageFace(Direction.UP,0,0,1,0,1,1,0),14));
  assertThrows(IllegalArgumentException.class,()->new PageFace(Direction.UP,0,0,0,0,1,1,0));
 }
 @Test void exactNativeVerticalEndpointsSurviveCompactStorage(){
  var values=List.of(new SurvivalDhTerrainData.PageFace(new PageFace(Direction.DOWN,-6,0,1280,0,1,1,0xff010203),2),
    new SurvivalDhTerrainData.PageFace(new PageFace(Direction.UP,4,0,672,0,1,1,0xff040506),13));
  var slabs=new SurvivalDhTerrainData.FaceSlabs(2);for(var f:values)slabs.add(f,n->{});slabs.freeze();
  assertEquals(values.get(0),slabs.get(0));assertEquals(values.get(1),slabs.get(1));
  assertEquals(MIN_Y,(long)slabs.get(0).geometry().pageY2048()*2048+slabs.get(0).geometry().y());
  assertEquals(MAX_Y_EXCLUSIVE,(long)slabs.get(1).geometry().pageY2048()*2048+slabs.get(1).geometry().y());
 }
}
