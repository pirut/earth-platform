package io.earthplatform.minecraft.lod;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DhNativeDistanceCacheTest {
 static final class Resource{boolean deleted;}
 static final class Ops implements DhIncrementalTileCache.ResourceOps<Integer,Integer,Resource>{
  boolean failClose;List<Resource> made=new ArrayList<>();public long estimateBytes(List<Integer> f){return DhNativeDistanceBudget.storage(f.size()).chargedBytes();}
  public Resource upload(Integer key,List<Integer> f){var r=new Resource();made.add(r);return r;}public long bytes(Resource r){return DhNativeDistanceBudget.storage(1).chargedBytes();}
  public void close(Resource r){if(failClose)throw new IllegalStateException("retained");r.deleted=true;}public boolean deleted(Resource r){return r.deleted;}
 }
 static Map<Integer,List<Integer>> faces(int n,int offset){var map=new LinkedHashMap<Integer,List<Integer>>();for(int i=0;i<n;i++)map.put(i+offset,List.of(i));return map;}
 @Test void explicitDistanceProfileAdmitsMoreThanLegacyOwnersAndChargesExactStores(){var ops=new Ops();var cache=new DhIncrementalTileCache<>(ops,32L<<20,256,512,System::nanoTime,true);assertTrue(cache.stage(1,faces(65,0)));cache.pump(32L<<20,256,Long.MAX_VALUE);assertTrue(cache.ready(1));assertEquals(65,cache.residentOwners());assertEquals(65*DhNativeDistanceBudget.storage(1).chargedBytes(),cache.residentBytes());assertTrue(cache.commit(1));cache.close();assertTrue(ops.made.stream().allMatch(r->r.deleted));}
 @Test void legacyConstructorStillRefusesSixtyFiveOwners(){var cache=new DhIncrementalTileCache<Integer,Integer,Resource>(new Ops(),32L<<20);assertThrows(IllegalArgumentException.class,()->cache.stage(1,faces(65,0)));cache.close();}
 @Test void failedCleanupRemainsFullyChargedUntilPhysicalSuccess(){var ops=new Ops();var cache=new DhIncrementalTileCache<>(ops,32L<<20,256,512,System::nanoTime,true);cache.stage(1,faces(2,0));cache.pump(32L<<20,256,Long.MAX_VALUE);cache.commit(1);ops.failClose=true;assertThrows(IllegalStateException.class,cache::close);assertEquals(2,cache.residentOwners());assertEquals(2*DhNativeDistanceBudget.storage(1).chargedBytes(),cache.residentBytes());ops.failClose=false;cache.close();assertEquals(0,cache.residentOwners());assertEquals(0,cache.residentBytes());}
 @Test void preflightCountsOnlyChangedPhysicalContentIncludingExistingFailures(){var current=faces(2,0);var candidate=new LinkedHashMap<>(current);candidate.put(2,List.of(1));long each=DhNativeDistanceBudget.storage(1).chargedBytes();var u=DhReplacementGpuBudget.assessDistance(current,candidate,3,3*each,256);assertEquals(4,u.owners());assertEquals(1,u.changedOwners());assertEquals(4*each,u.bytes());}
 @Test void reusedGpuOwnerUsesOnlyCurrentCpuFaceObjects() throws Exception{
  record Face(int value){};
  for(boolean distance:new boolean[]{false,true}){
   var ops=new DhIncrementalTileCache.ResourceOps<Integer,Face,Resource>(){
    public long estimateBytes(List<Face> f){return 1;}public Resource upload(Integer k,List<Face> f){return new Resource();}public long bytes(Resource r){return 1;}public void close(Resource r){r.deleted=true;}public boolean deleted(Resource r){return r.deleted;}};
   var cache=new DhIncrementalTileCache<>(ops,100,2,4,System::nanoTime,distance);var oldFace=new Face(1);var nextFace=new Face(1);assertNotSame(oldFace,nextFace);
   cache.stage(1,Map.of(1,List.of(oldFace)));cache.pump(100,4,Long.MAX_VALUE);cache.commit(1);var owner=cache.currentResources().get(1);
   cache.stage(2,Map.of(1,List.of(nextFace)));assertTrue(cache.ready(2));cache.commit(2);assertSame(owner,cache.currentResources().get(1));assertEquals(1,cache.residentOwners());
   var f=DhIncrementalTileCache.class.getDeclaredField("current");f.setAccessible(true);var tile=((Map<?,?>)f.get(cache)).get(1);var tf=tile.getClass().getDeclaredField("faces");tf.setAccessible(true);
   assertSame(distance?nextFace:oldFace,((List<?>)tf.get(tile)).getFirst());cache.close();
  }
 }
}
