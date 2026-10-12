package io.earthplatform.minecraft.lod;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DhNativeDistanceAllocationTest {
 static final class Owner implements DhGlUpload.Owner{int a,b,c;public int vao(){return a;}public int vbo(){return b;}public int ibo(){return c;}public void vao(int v){a=v;}public void vbo(int v){b=v;}public void ibo(int v){c=v;}}
 static class Gl implements DhGlUpload.Gl{
  List<String> calls=new ArrayList<>();int ids,vsize,isize,wrongTarget;String failPhase;RuntimeException failure=new IllegalStateException("injected phase");
  public int genVertexArray(){calls.add("vao");return ++ids;}public int genBuffer(){calls.add("buffer");return ++ids;}
  public void bindVertexArray(int i){calls.add("bind-vao");}public void bindBuffer(int t,int i){calls.add("bind-buffer");}
  public void vertexData(ByteBuffer b){calls.add("legacy-vertex");}public void indexData(IntBuffer b){calls.add("legacy-index");}
  public void allocateVertexBytes(int n){calls.add("allocate-vertex");vsize=n;}public void allocateIndexBytes(int n){calls.add("allocate-index");isize=n;}
  public void writeVertexData(ByteBuffer b){calls.add("write-vertex");}public void writeIndexData(IntBuffer b){calls.add("write-index");}
  public int bufferSize(int target){calls.add("size");return (target==0x8892?vsize:isize)+(wrongTarget==target?1:0);}
  public void enableAttribute(int i){calls.add("attribute");}public void integerAttribute(int a,int b,int c,int d,long e){calls.add("pointer");}
  public void attribute(int a,int b,int c,boolean d,int e,long f){calls.add("pointer");}
  public void check(String p,DhGlUpload.Owner o){calls.add(p);if(p.equals(failPhase))throw failure;}
  void run(Owner owner,int q){DhGlUpload.uploadRounded(this,owner,ByteBuffer.allocate(q*64),IntBuffer.allocate(q*6));}
 }
 @Test void exactRoundingAndOwnerAllowanceForEveryLegalPiece(){
  for(int q=1;q<=4096;q++){var s=DhNativeDistanceBudget.storage(q);assertEquals(0,s.vertexBytes()%65536);assertEquals(0,s.indexBytes()%65536);assertTrue(s.vertexBytes()>=q*64&&s.vertexBytes()<q*64+65536);assertTrue(s.indexBytes()>=q*24&&s.indexBytes()<q*24+65536);assertEquals((long)s.vertexBytes()+s.indexBytes()+4096,s.chargedBytes());}
  assertThrows(IllegalArgumentException.class,()->DhNativeDistanceBudget.storage(0));assertThrows(IllegalArgumentException.class,()->DhNativeDistanceBudget.storage(4097));
 }
 @Test void actualAllocationSizesAreReadBeforePayloadOrAttributes(){var gl=new Gl();var owner=new Owner();gl.run(owner,4096);
  assertEquals(262144,gl.vsize);assertEquals(131072,gl.isize);assertEquals(3,owner.ibo());
  assertTrue(gl.calls.indexOf("far.upload.vertex.size")<gl.calls.indexOf("write-vertex"));assertTrue(gl.calls.indexOf("far.upload.index.size")<gl.calls.indexOf("write-index"));
  assertFalse(gl.calls.contains("legacy-vertex"));assertFalse(gl.calls.contains("legacy-index"));
 }
 @Test void sizeMismatchStopsWithoutForgettingGeneratedNames(){for(int target:new int[]{0x8892,0x8893}){var gl=new Gl();gl.wrongTarget=target;var owner=new Owner();assertThrows(IllegalStateException.class,()->gl.run(owner,1));assertEquals(1,owner.vao());assertEquals(2,owner.vbo());assertEquals(target==0x8892?0:3,owner.ibo());assertFalse(gl.calls.contains("attribute"));}}
 @Test void everyRoundedCheckpointPreservesItsExactPrimary(){var good=new Gl();good.run(new Owner(),1);for(String phase:good.calls.stream().filter(s->s.startsWith("far.")).toList()){var gl=new Gl();gl.failPhase=phase;var owner=new Owner();assertSame(gl.failure,assertThrows(IllegalStateException.class,()->gl.run(owner,1)));assertEquals(phase,gl.calls.getLast());}}
 @Test void defaultUploadRetainsLegacyAllocationPath(){var gl=new Gl();DhGlUpload.upload(gl,new Owner(),ByteBuffer.allocate(64),IntBuffer.allocate(6));assertTrue(gl.calls.contains("legacy-vertex"));assertTrue(gl.calls.contains("legacy-index"));assertFalse(gl.calls.contains("allocate-vertex"));}
 @Test void profileIsNamedAndCannotGrowBeyondApprovedEnvelope(){var c=DhNativeDistanceBudget.configuration();assertEquals(32L<<20,c.maxGpuBytes());assertEquals(256,c.geometry().maxOwners());assertThrows(IllegalArgumentException.class,()->new DhPreparedLodView.DistanceConfig("unknown",c.geometry(),c.maxGpuBytes(),512,c.maxCachedBytes()));assertThrows(IllegalArgumentException.class,()->new DhPreparedLodView.DistanceConfig(c.policy(),c.geometry(),(32L<<20)+1,512,c.maxCachedBytes()));}
}
