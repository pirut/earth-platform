package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import java.nio.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.lod.DhArenaTestFixtures.*;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.*;

/** Uses the separately supplied pinned official CPU builder; this is not installed-mod or GL evidence. */
@Timeout(60)
class DhArenaOfficialPackingTest {
 static DhOfficialRuntime runtime()throws Exception{String jar=System.getProperty("earthplatform.lodProbeJar",System.getenv("EARTH_DH_PROBE_JAR"));assertNotNull(jar,"Pinned external DH is mandatory for the arena aggregate");assertFalse(jar.isBlank());return DhOfficialRuntime.open(Path.of(jar));}
 @Test void fullFinalCompositionPacksWithoutLosingAreaAtHomeRetirementAndFarCorner()throws Exception{
  var runtime=runtime();var capture=capture().withMaterial(15,144,15,0);var input=input(new Guard(),2,1,capture);
  for(var center:List.of(new CubePos(0,9,0),new CubePos(-4,9,-4),new CubePos(15,15,15))){var r=DhArenaComposition.build(center(center.x()),center(center.z()),DhLodLayout.ARENA_4096,mask(center),input.data(),DhReplacementComposition.MAX_RESULT_BYTES,()->{});long bytes=0,quads=0;
   for(var tile:r.batch().tiles().entrySet()){byte[] packed=runtime.buildRectangles(tile.getValue());assertTrue(packed.length>0&&packed.length%64==0&&packed.length<=tile.getValue().size()*64);assertTrue(packed.length<=4096*64);long area=tile.getValue().stream().mapToLong(f->(long)f.width()*f.height()).sum();assertEquals(area,area(packed),"Official merging preserves complete final owner area "+tile.getKey());bytes+=packed.length;quads+=packed.length/64;}
   long result=4096+capture.declaredBytes()+64L*r.batch().faces()+bytes;assertTrue(result<=5L*1024*1024);assertTrue(4*result<=20L*1024*1024);assertTrue(bytes+24*quads<=8L*1024*1024);System.out.println("ARENA_OFFICIAL center="+center+" owners="+r.batch().tiles().size()+" faces="+r.batch().faces()+" quads="+quads+" vertexBytes="+bytes+" resultBytes="+result+" artifact="+DhOfficialRuntime.ARTIFACT_SHA256);
  }
 }
 @Test void tightestFixedBasePageCannotMergeWithEditedUnionOwner()throws Exception{
  var runtime=runtime();var capture=capture().withMaterial(15,144,15,0);var input=input(new Guard(),2,1,capture);
  var c=new CubePos(8,6,8);var r=DhArenaComposition.build(center(c.x()),center(c.z()),DhLodLayout.ARENA_4096,mask(c),input.data(),DhReplacementComposition.MAX_RESULT_BYTES,()->{});
  var base=r.batch().tiles().entrySet().stream().filter(e->e.getKey().originX()==0&&e.getKey().originZ()==0&&e.getKey().pageY()==0&&e.getKey().replacementIdentity().isEmpty()).findFirst().orElseThrow();
  var edited=r.batch().tiles().entrySet().stream().filter(e->e.getKey().originX()==0&&e.getKey().originZ()==0&&e.getKey().pageY()==0&&e.getKey().replacementIdentity().equals(input.identity())).findFirst().orElseThrow();
  assertEquals(4056,base.getValue().size());assertTrue(base.getValue().size()+edited.getValue().size()>4096);assertNotEquals(base.getKey(),edited.getKey());
  for(var tile:List.of(base,edited)){var packed=runtime.buildRectangles(tile.getValue());assertEquals(tile.getValue().stream().mapToLong(f->(long)f.width()*f.height()).sum(),area(packed));assertTrue(packed.length<=4096*64);}
  assertTrue(r.batch().tiles().values().stream().allMatch(f->f.size()<=4096));assertTrue(r.batch().tiles().size()<=64);
  System.out.println("ARENA_TIGHT_PAGE baseFaces="+base.getValue().size()+" separateEditedFaces="+edited.getValue().size()+" owners="+r.batch().tiles().size());
 }
 @Test void actualOfficialVerticesRoundTripSixDirectionsAndEveryXyzSeamCorner()throws Exception{var runtime=runtime();var directions=EnumSet.noneOf(Direction.class);for(long x:new long[]{-1,0,15,16,31,32})for(long y:new long[]{127,128,143,144,159,160})for(long z:new long[]{-1,0,15,16,31,32})for(var d:Direction.values()){var bucket=DhArenaGeometry.bucket(x,y,z);var page=DhTerrainGeometry.splitPages(DhArenaGeometry.face(x,y,z,d,0xffdedede),bucket.x(),bucket.z()).getFirst();var packed=runtime.buildRectangles(List.of(page));assertEquals(64,packed.length);assertEquals(corners(page),points(packed));directions.add(d);}assertEquals(EnumSet.allOf(Direction.class),directions);}
 static long area(byte[] bytes){var b=ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());long result=0;for(int at=0;at<bytes.length;at+=64){int[] min={65535,65535,65535},max={0,0,0};for(int v=0;v<4;v++)for(int a=0;a<3;a++){int value=Short.toUnsignedInt(b.getShort(at+v*16+a*2));min[a]=Math.min(min[a],value);max[a]=Math.max(max[a],value);}long x=max[0]-min[0],y=max[1]-min[1],z=max[2]-min[2];assertEquals(1,(x==0?1:0)+(y==0?1:0)+(z==0?1:0));result+=x*y+x*z+y*z;}return result;}
 static Set<String> points(byte[] bytes){var b=ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());var r=new HashSet<String>();for(int i=0;i<4;i++)r.add(Short.toUnsignedInt(b.getShort(i*16))+","+Short.toUnsignedInt(b.getShort(i*16+2))+","+Short.toUnsignedInt(b.getShort(i*16+4)));return r;}
 static Set<String> corners(PageFace f){var r=new HashSet<String>();for(int u:new int[]{0,f.width()})for(int v:new int[]{0,f.height()}){int x=f.x(),y=f.y(),z=f.z();switch(f.direction()){case UP,DOWN->{x+=u;z+=v;}case NORTH,SOUTH->{x+=u;y+=v;}case WEST,EAST->{z+=u;y+=v;}}r.add(x+","+y+","+z);}return r;}
}
