package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeReplacementCapture;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Face;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent global voxel and unit-face oracle. No source, host, authority guard or GL is needed after capture. */
class DhAdjacentWorkspaceCompositionTest {
    static final Runnable LIVE = () -> {};
    static final int STONE = 0xff808080, DIORITE = 0xffdedede;
    enum Pattern { FLAT, SHELL, BOUNDARIES, MIXED, CHECKER }
    record Unit(long x, long y, long z, Direction direction, int argb) { }
    record XZ(long x, long z) { }
    static WorldIdentity world(int y) {
        return new WorldIdentity(0,"earthplatform:analytic-native-cube","analytic-native-cube-v1/rolling-interior-v1",
                DhAnalyticFlatPolicy.expectedDataset(y),"unit-opaque-v1");
    }
    static DhAnalyticFlatPolicy policy(int y) { return new DhAnalyticFlatPolicy(world(y).datasetVersion(),world(y),y,STONE); }
    static DhReplacementInput.Data data(DhVoxelReplacementGeometry.Capture c, int anchor) {
        return DhReplacementInput.content(new DhReplacementInput.Certificate(world(anchor),10,1,2,c.editedCube(),1,
                "a".repeat(64),"d".repeat(64),DhReplacementInput.ANALYTIC_MATERIAL_POLICY),c);
    }
    static int material(int anchor, Pattern pattern, long x, long y, long z) {
        long floor = Math.floorDiv(anchor,16)*16L, ly=y-floor;
        if (x<0||x>=32||ly<0||ly>=16||z<0||z>=16) return y<=anchor?STONE:0;
        int flat = y<=anchor?STONE:0;
        return switch(pattern) {
            case FLAT -> flat;
            case CHECKER -> ((x+y+z)&1)==0?STONE:0;
            case SHELL -> {
                boolean box=x>=1&&x<=30&&ly>=1&&ly<=14&&z>=1&&z<=14;
                boolean shell=box&&(x==1||x==30||ly==1||ly==14||z==1||z==14);
                boolean opening=(x==15||x==16)&&ly==7&&z==1;
                yield shell&&!opening?((x+z)&1)==0?STONE:DIORITE:box?0:flat;
            }
            case BOUNDARIES -> {
                boolean carve=(x==0||x==15||x==16||x==31)&&(z==0||z==15||z==7);
                boolean bridge=x>=13&&x<=18&&ly==10&&z>=3&&z<=12;
                boolean corner=(x==0||x==31)&&(z==0||z==15)&&(ly==0||ly==15);
                yield bridge||corner?DIORITE:carve?0:flat;
            }
            case MIXED -> {
                boolean cut=(x==14||x==16||x%7==0)&&z%4==0;
                boolean upper=ly>=9&&ly<=12&&((x+3*z)%11==0);
                yield upper?((x+ly+z)&1)==0?STONE:DIORITE:cut?0:flat;
            }
        };
    }
    static List<DhReplacementInput.Data> fixture(int anchor,Pattern p) {
        return fixture(anchor,p,new AtomicInteger());
    }
    static List<DhReplacementInput.Data> fixture(int anchor,Pattern p,AtomicInteger calls) {
        var out=new ArrayList<DhReplacementInput.Data>();
        for(int cx:new int[]{0,1}) {
            var cube=new CubePos(cx,Math.floorDiv(anchor,16),0);
            out.add(data(DhVoxelReplacementGeometry.capture(cube,(x,y,z)->{
                calls.incrementAndGet();return material(anchor,p,x,y,z);
            },LIVE),anchor));
        }return List.copyOf(out);
    }
    static List<CubePos> near(int x,int y,int z) {
        var out=new ArrayList<CubePos>();
        for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) {
            var c=new CubePos(x+dx,y+dy,z+dz);
            if(c.minBlockY()>=-11008&&c.minBlockY()+16<=8864)out.add(c);
        }return out;
    }
    static long snap(int cube) { return Math.floorDiv(cube*16L+136,256)*256; }
    static Face worldFace(DhGeographicLodData.TileKey key,DhTerrainGeometry.PageFace f) {
        return new Face(f.direction(),key.originX()+f.x(),(long)f.pageY2048()*2048+f.y(),key.originZ()+f.z(),f.width(),f.height(),f.argb());
    }
    static Unit unit(Face f) { return new Unit(f.x(),f.y(),f.z(),f.direction(),f.argb()); }
    static long solidX(Unit u) { return u.x-(u.direction==Direction.EAST?1:0); }
    static long solidY(Unit u) { return u.y-(u.direction==Direction.UP?1:0); }
    static long solidZ(Unit u) { return u.z-(u.direction==Direction.SOUTH?1:0); }
    /** One global union loop, independent of capture traversal and per-region deduplication. */
    static Set<Unit> oracle(int anchor,Pattern pattern,List<CubePos> ready) {
        var out=new HashSet<Unit>();var mask=new HashSet<>(ready);
        long floor=Math.floorDiv(anchor,16)*16L;
        for(long y=Math.max(-11008,floor-1);y<Math.min(8864,floor+17);y++)
            for(long z=-1;z<17;z++)for(long x=-1;x<33;x++) {
                int c=material(anchor,pattern,x,y,z);
                if(c==0||mask.contains(CubePos.fromBlock(x,y,z)))continue;
                if(material(anchor,pattern,x-1,y,z)==0)out.add(new Unit(x,y,z,Direction.WEST,c));
                if(material(anchor,pattern,x+1,y,z)==0)out.add(new Unit(x+1,y,z,Direction.EAST,c));
                if(material(anchor,pattern,x,y-1,z)==0)out.add(new Unit(x,y,z,Direction.DOWN,c));
                if(material(anchor,pattern,x,y+1,z)==0)out.add(new Unit(x,y+1,z,Direction.UP,c));
                if(material(anchor,pattern,x,y,z-1)==0)out.add(new Unit(x,y,z,Direction.NORTH,c));
                if(material(anchor,pattern,x,y,z+1)==0)out.add(new Unit(x,y,z+1,Direction.SOUTH,c));
            }
        return out;
    }
    static void checkResult(int anchor,Pattern pattern,List<DhReplacementInput.Data> regions,
                            List<CubePos> ready,DhReplacementComposition.Result result) {
        var expected=oracle(anchor,pattern,ready);var actual=new HashSet<Unit>();var base=new ArrayList<Face>();
        int owners=0;
        for(var e:result.batch().tiles().entrySet()) {
            assertTrue(e.getValue().size()<=4096);
            if(!e.getKey().replacementIdentity().isEmpty())owners++;
            for(var page:e.getValue()) {
                var f=worldFace(e.getKey(),page);
                if(e.getKey().replacementIdentity().isEmpty()) {base.add(f);assertEquals(Direction.UP,f.direction());assertEquals(anchor+1,f.y());}
                else {
                    assertEquals(1,f.width());assertEquals(1,f.height());var u=unit(f);
                    assertTrue(actual.add(u),"Duplicate face: "+u);
                    assertEquals(regions.get(solidX(u)<17?0:1).identity(),e.getKey().replacementIdentity(),"Canonical solid owner: "+u);
                }
            }
        }
        assertEquals(expected,actual);assertEquals(expected.size(),result.replacementVisibleFaces());
        assertEquals(oracle(anchor,pattern,List.of()).size(),result.replacementSourceFaces());
        assertEquals(owners,result.replacementOwners());
        var removed=new HashSet<XZ>();
        for(long z=-1;z<17;z++)for(long x=-1;x<33;x++)removed.add(new XZ(x,z));
        for(var c:ready)if(anchor>=c.minBlockY()&&anchor<c.minBlockY()+16)
            for(long z=c.minBlockZ();z<c.minBlockZ()+16;z++)for(long x=c.minBlockX();x<c.minBlockX()+16;x++)removed.add(new XZ(x,z));
        assertEquals(8192L*8192-removed.size(),base.stream().mapToLong(f->(long)f.width()*f.height()).sum());
        for(int z=-2;z<=18;z++)for(int x=-2;x<=34;x++) {
            int n=0;for(var f:base)if(x>=f.x()&&x<f.x()+f.width()&&z>=f.z()&&z<f.z()+f.height())n++;
            assertEquals(removed.contains(new XZ(x,z))?0:1,n,"Base union at "+x+","+z);
        }
        assertEquals(0,result.batch().transitionFaces());assertTrue(result.batch().sampledCells()<=11587);
        assertTrue(result.batch().faces()<=32768);assertTrue(result.batch().tiles().size()<=64);
    }
    @Test void globalUnitFaceOracleCoversOverlapShellsCavitiesMaterialsCornersAndMasks() {
        for(int anchor:new int[]{128,-11000,8849})for(Pattern p:new Pattern[]{Pattern.FLAT,Pattern.SHELL,Pattern.BOUNDARIES,Pattern.MIXED}) {
            var calls=new AtomicInteger();var regions=fixture(anchor,p,calls);int captured=calls.get();
            var census=DhReplacementSetComposition.inspect(policy(anchor),regions,LIVE);
            assertTrue(census.fits());assertEquals(oracle(anchor,p,List.of()).size(),census.sourceFaces());
            assertEquals(census,DhReplacementSetComposition.inspectCaptures(policy(anchor),regions.stream().map(DhReplacementInput.Data::capture).toList(),LIVE));
            assertTrue(census.unionOwners()<=128);assertTrue(census.unionBytes()<=8L*1024*1024);
            int cy=Math.floorDiv(anchor,16);
            for(int[] at:new int[][]{{-1,cy,0},{0,cy,0},{1,cy,0},{2,cy,0},{31,cy,-32},{0,cy==553?cy-3:cy+3,0}}) {
                var ready=near(at[0],at[1],at[2]);
                var result=DhReplacementSetComposition.build(policy(anchor),snap(at[0]),snap(at[2]),DhLodLayout.LONG_4096,ready,regions,DhReplacementComposition.MAX_RESULT_BYTES,LIVE);
                checkResult(anchor,p,regions,ready,result);
            }
            assertEquals(captured,calls.get(),"Census/composition must not call a retained source");
        }
    }
    @Test void reverseOrderIsIdenticalAndOverlapHasOneOwner() {
        var r=fixture(128,Pattern.MIXED);var reverse=List.of(r.get(1),r.get(0));
        var a=DhReplacementSetComposition.build(policy(128),0,0,DhLodLayout.LONG_4096,List.of(),r,DhReplacementComposition.MAX_RESULT_BYTES,LIVE);
        var b=DhReplacementSetComposition.build(policy(128),0,0,DhLodLayout.LONG_4096,List.of(),reverse,DhReplacementComposition.MAX_RESULT_BYTES,LIVE);
        assertEquals(a,b);assertEquals(DhReplacementSetComposition.inspect(policy(128),r,LIVE),DhReplacementSetComposition.inspect(policy(128),reverse,LIVE));
        int duplicateNaiveFaces=0;for(var d:r)duplicateNaiveFaces+=DhVoxelReplacementGeometry.build(d.capture(),32768,5L*1024*1024,LIVE).faceCount();
        assertTrue(duplicateNaiveFaces>a.replacementSourceFaces(),"Fixture must expose naive overlapping-shell double publication");
        System.out.println("Overlap counterexample: naive per-region faces="+duplicateNaiveFaces+", canonical union="+a.replacementSourceFaces());
    }
    @Test void partialUnknownUnsupportedNonflatAndInconsistentSetsFailClosed() {
        var p=policy(128);var r=fixture(128,Pattern.FLAT);var captures=r.stream().map(DhReplacementInput.Data::capture).toList();
        for(var bad:List.of(List.<DhVoxelReplacementGeometry.Capture>of(),List.of(captures.getFirst()),List.of(captures.getFirst(),captures.getFirst())))
            assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.inspectCaptures(p,bad,LIVE));
        var unknown=new CubePos(1,8,0);
        assertThrows(IllegalArgumentException.class,()->DhVoxelReplacementGeometry.capture(unknown,(x,y,z)->x==16&&y==129&&z==0?0x01000000:material(128,Pattern.FLAT,x,y,z),LIVE));
        for(long[] cell:new long[][]{{15,129,7},{16,129,7},{14,129,7},{17,129,7}}) {
            var corrupt=DhVoxelReplacementGeometry.capture(unknown,(x,y,z)->x==cell[0]&&y==cell[1]&&z==cell[2]?DIORITE:material(128,Pattern.FLAT,x,y,z),LIVE);
            var fail=assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.inspectCaptures(p,List.of(captures.getFirst(),corrupt),LIVE));
            assertTrue(fail.getMessage().contains("overlap"));
            System.out.println("Overlap disagreement rejected at "+Arrays.toString(cell));
        }
        for(int badColor:new int[]{0,0xff00ff00}) {
            var corrupt=DhVoxelReplacementGeometry.capture(unknown,(x,y,z)->x==33&&y==128&&z==0?badColor:material(128,Pattern.FLAT,x,y,z),LIVE);
            assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.inspectCaptures(p,List.of(captures.getFirst(),corrupt),LIVE));
        }
        var cert=r.get(1).certificate();
        var mixed=DhReplacementInput.content(new DhReplacementInput.Certificate(cert.world(),cert.epoch(),cert.resourceGeneration(),cert.summaryGeneration(),cert.editedCube(),cert.durableRevision(),"b".repeat(64),cert.dependencyIdentity(),cert.materialPolicy()),captures.get(1));
        assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.inspect(p,List.of(r.getFirst(),mixed),LIVE));
    }
    @Test void compactPackingHasIdenticalUnitGeometryAndOneQuarterPayload() {
        for(int anchor:new int[]{128,-11000,8849}) {
            var raw=fixture(anchor,Pattern.BOUNDARIES);var packed=new ArrayList<DhReplacementInput.Data>();
            for(var r:raw) {
                var c=r.capture();var b=c.readBounds();var bytes=new byte[c.cellCount()];int i=0;
                for(long y=b.minY();y<b.maxYExclusive();y++)for(long z=b.minZ();z<b.maxZExclusive();z++)for(long x=b.minX();x<b.maxXExclusive();x++) {
                    int color=c.argbAt(x,y,z);bytes[i++]=(byte)(color==0?0:color==STONE?1:2);
                }
                packed.add(data(DhVoxelReplacementGeometry.captureCompact(new NativeReplacementCapture(c.editedCube(),bytes)),anchor));
            }
            var a=DhReplacementSetComposition.inspect(policy(anchor),raw,LIVE);var b=DhReplacementSetComposition.inspect(policy(anchor),packed,LIVE);
            assertEquals(a.sourceFaces(),b.sourceFaces());assertEquals(a.pieceOwners(),b.pieceOwners());assertEquals(a.captureBytes()/4,b.captureBytes());
            assertEquals(a.resultBytes()-a.captureBytes()+b.captureBytes(),b.resultBytes());assertTrue(b.captureBytes()<=16000);
            var result=DhReplacementSetComposition.build(policy(anchor),512,-512,DhLodLayout.LONG_4096,near(31,Math.floorDiv(anchor,16),-32),packed,DhReplacementComposition.MAX_RESULT_BYTES,LIVE);
            checkResult(anchor,Pattern.BOUNDARIES,packed,near(31,Math.floorDiv(anchor,16),-32),result);
        }
    }
    @Test void pinnedOfficialPackingPreservesCanonicalUnionFacesAndExistingByteCaps() throws Exception {
        String jar=System.getProperty("earthplatform.dhJar");
        assertNotNull(jar,"Pinned official artifact is mandatory for this proof");
        var runtime=DhOfficialRuntime.open(java.nio.file.Path.of(jar));
        long checkedFaces=0,peakResult=0,peakGpu=0;int checkedOwners=0;
        for(int anchor:new int[]{128,-11000,8849})for(Pattern pattern:new Pattern[]{Pattern.SHELL,Pattern.BOUNDARIES,Pattern.MIXED}) {
            var r=fixture(anchor,pattern);
            var result=DhReplacementSetComposition.build(policy(anchor),512,-512,DhLodLayout.LONG_4096,near(31,Math.floorDiv(anchor,16),-32),r,DhReplacementComposition.MAX_RESULT_BYTES,LIVE);
            var directions=EnumSet.noneOf(Direction.class);var colors=new HashSet<Integer>();
            long bytes=0;
            for(var e:result.batch().tiles().entrySet()) {
                var packed=runtime.buildRectangles(e.getValue());bytes+=packed.length;
                assertTrue(packed.length>0&&packed.length%64==0&&packed.length<=e.getValue().size()*64L);
                if(e.getKey().replacementIdentity().isEmpty())continue;
                checkedOwners++;checkedFaces+=e.getValue().size();
                for(var f:e.getValue()){directions.add(f.direction());colors.add(f.argb());}
                var expected=new HashSet<Unit>();for(var f:e.getValue())assertTrue(expected.add(unit(worldFace(e.getKey(),f))));
                var actual=new HashSet<Unit>();var b=java.nio.ByteBuffer.wrap(packed).order(java.nio.ByteOrder.nativeOrder());
                for(int q=0;q<packed.length;q+=64) {
                    // DH 3.3.3 putVertex stores RGBA at bytes8..11 and faceIndex at byte13.
                    int argb=(Byte.toUnsignedInt(b.get(q+11))<<24)|(Byte.toUnsignedInt(b.get(q+8))<<16)|(Byte.toUnsignedInt(b.get(q+9))<<8)|Byte.toUnsignedInt(b.get(q+10));
                    Direction direction=switch(Byte.toUnsignedInt(b.get(q+13))) {case 0->Direction.DOWN;case 1->Direction.UP;case 2->Direction.NORTH;case 3->Direction.SOUTH;case 4->Direction.WEST;case 5->Direction.EAST;default->throw new AssertionError("Invalid official face direction");};
                    int[] lo={4096,2048,4096},hi={0,0,0};
                    var corners=new HashSet<String>();
                    for(int v=0;v<4;v++) {
                        for(int attribute:new int[]{8,9,10,11,13})assertEquals(b.get(q+attribute),b.get(q+v*16+attribute),"Quad attributes must agree at every corner");
                        int[] pnt=new int[3];
                        for(int axis=0;axis<3;axis++){int value=Short.toUnsignedInt(b.getShort(q+v*16+axis*2));pnt[axis]=value;lo[axis]=Math.min(lo[axis],value);hi[axis]=Math.max(hi[axis],value);}
                        assertTrue(pnt[0]<=32&&pnt[2]<=32&&pnt[1]<=2048);
                        long y=e.getKey().pageY()*2048L+pnt[1];assertTrue(y>=-11008&&y<=8864);
                        corners.add(Arrays.toString(pnt));
                    }
                    assertEquals(4,corners.size());
                    var expectedCorners=new HashSet<String>();
                    for(int x:new int[]{lo[0],hi[0]})for(int y:new int[]{lo[1],hi[1]})for(int z:new int[]{lo[2],hi[2]})expectedCorners.add(Arrays.toString(new int[]{x,y,z}));
                    assertEquals(expectedCorners,corners,"Exact four official rectangle corners");
                    int normal=lo[0]==hi[0]?0:lo[1]==hi[1]?1:2;
                    assertEquals(lo[normal],hi[normal]);
                    assertEquals(switch(direction){case WEST,EAST->0;case UP,DOWN->1;case NORTH,SOUTH->2;},normal);
                    for(int axis=0;axis<3;axis++)if(axis!=normal)assertTrue(hi[axis]>lo[axis]);
                    for(int x=lo[0];x<Math.max(lo[0]+1,hi[0]);x++)for(int y=lo[1];y<Math.max(lo[1]+1,hi[1]);y++)for(int z=lo[2];z<Math.max(lo[2]+1,hi[2]);z++)
                        assertTrue(actual.add(new Unit(e.getKey().originX()+x,e.getKey().pageY()*2048L+y,e.getKey().originZ()+z,direction,argb)),"Official packing duplicated a union unit face");
                }
                assertEquals(expected,actual,"Official merged rectangles must preserve every canonical union unit face");
            }
            assertEquals(EnumSet.allOf(Direction.class),directions);assertEquals(Set.of(STONE,DIORITE),colors);
            long captures=r.stream().mapToLong(DhReplacementInput.Data::declaredBytes).sum();
            long declared=4096+captures+result.batch().faces()*64L+bytes;
            long oldPlusCandidate=32768L*88+bytes/64*88;
            assertTrue(declared<=5L*1024*1024);assertTrue(declared*4<=20L*1024*1024);assertTrue(oldPlusCandidate<=8L*1024*1024);
            assertTrue(result.batch().tiles().size()+64<=128);
            peakResult=Math.max(peakResult,declared);peakGpu=Math.max(peakGpu,oldPlusCandidate);
        }
        System.out.println("Adjacent official DH 3.3.3 packing: fixtures=9, owners="+checkedOwners+", exactUnitFaces="+checkedFaces+", peakResultBytes="+peakResult+", peakOldPlusCandidateGpuBytes="+peakGpu);
    }
    @Test void all1242YTemplatesAndEmptyInitialCentersAreExact() throws Exception {
        var validate=DhReplacementSetComposition.class.getDeclaredMethod("validateReady",List.class);validate.setAccessible(true);
        int endpoints=0;
        for(int cy=-688;cy<=553;cy++) {
            var ready=near(23,cy,-32);assertEquals(ready.stream().sorted().toList(),validate.invoke(null,ready));
            assertEquals(cy==-688||cy==553?18:27,ready.size());if(ready.size()==18)endpoints++;
        }assertEquals(2,endpoints);
        for(int anchor:new int[]{128,-11000,8849}) {
            var r=fixture(anchor,Pattern.FLAT);
            for(int x=-512;x<=512;x+=256)for(int z=-512;z<=512;z+=256) {
                var result=DhReplacementSetComposition.build(policy(anchor),x,z,DhLodLayout.LONG_4096,List.of(),r,DhReplacementComposition.MAX_RESULT_BYTES,LIVE);
                assertEquals(612,result.replacementSourceFaces());assertEquals(612,result.replacementVisibleFaces());
                assertEquals(oracle(anchor,Pattern.FLAT,List.of()).size(),result.replacementVisibleFaces());
            }
            var incomplete=new ArrayList<>(near(0,Math.floorDiv(anchor,16),0));incomplete.removeLast();
            assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.build(policy(anchor),0,0,DhLodLayout.LONG_4096,incomplete,r,DhReplacementComposition.MAX_RESULT_BYTES,LIVE));
        }
    }
    @Test void legalCheckerboardsRefusePooledBudgetBeforeNearHidingAndCancellationIsAtomic() {
        for(int anchor:new int[]{128,-11000,8849}) {
            var r=fixture(anchor,Pattern.CHECKER);var census=DhReplacementSetComposition.inspect(policy(anchor),r,LIVE);
            assertEquals(oracle(anchor,Pattern.CHECKER,List.of()).size(),census.sourceFaces());assertFalse(census.fits());
            var fail=assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.build(policy(anchor),0,0,DhLodLayout.LONG_4096,near(0,Math.floorDiv(anchor,16),0),r,DhReplacementComposition.MAX_RESULT_BYTES,LIVE));
            assertTrue(fail.getMessage().contains("full-envelope"));
            System.out.println("Legal adjacent checkerboard height="+anchor+" unionFaces="+census.sourceFaces()+" reservation="+census.faceUpperBound()+" owners="+census.ownerUpperBound());
        }
        var r=fixture(128,Pattern.MIXED);var captures=r.stream().map(DhReplacementInput.Data::capture).toList();
        for(int cancelAt:new int[]{1,9000,16500,20000,27000}) {
            var checks=new AtomicInteger();
            assertThrows(CancellationException.class,()->DhReplacementSetComposition.inspectCaptures(policy(128),captures,()->{if(checks.incrementAndGet()==cancelAt)throw new CancellationException();}));
        }
        for(int cancelAt:new int[]{1,17000,28000,40000}) {
            var checks=new AtomicInteger();
            assertThrows(CancellationException.class,()->DhReplacementSetComposition.build(policy(128),0,0,DhLodLayout.LONG_4096,List.of(),r,DhReplacementComposition.MAX_RESULT_BYTES,()->{if(checks.incrementAndGet()==cancelAt)throw new CancellationException();}));
        }
        long reserve=DhReplacementSetComposition.inspect(policy(128),r,LIVE).resultBytes();
        assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.build(policy(128),0,0,DhLodLayout.LONG_4096,List.of(),r,reserve-1,LIVE));
        assertDoesNotThrow(()->DhReplacementSetComposition.build(policy(128),0,0,DhLodLayout.LONG_4096,List.of(),r,reserve,LIVE));
    }
}
