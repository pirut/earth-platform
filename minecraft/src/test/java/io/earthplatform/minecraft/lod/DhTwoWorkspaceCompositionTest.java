package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeReplacementCapture;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Face;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent cell/face oracle for the explicitly certified two-workspace flat path. No GL. */
class DhTwoWorkspaceCompositionTest {
    static final Runnable LIVE = () -> {};
    static WorldIdentity world(int anchor) { return new WorldIdentity(0,"earthplatform:analytic-native-cube","analytic-native-cube-v1/rolling-interior-v1",DhAnalyticFlatPolicy.expectedDataset(anchor),"unit-opaque-v1"); }
    static final WorldIdentity WORLD = world(128);
    static final DhReplacementInput.AuthorityGuard GUARD = new DhReplacementInput.AuthorityGuard() {
        public boolean isCurrent(DhReplacementInput.Certificate c) { return true; }
        public boolean permitsRetainedPresentation(DhReplacementInput.Certificate c) { return true; }
    };
    static final int STONE = 0xff808080, DIORITE = 0xffdedede;
    record Cell(long x,long y,long z) { }
    record Unit(long x,long y,long z,Direction direction,int argb) { }
    record XZ(long x,long z) { }
    static DhAnalyticFlatPolicy policy(int y) { return new DhAnalyticFlatPolicy(world(y).datasetVersion(),world(y),y,STONE); }
    static DhReplacementInput.Data data(DhVoxelReplacementGeometry.Capture capture) {
        int anchor = switch(capture.editedCube().y()) { case -688 -> -11000; case 553 -> 8849; default -> 128; };
        var cert = new DhReplacementInput.Certificate(world(anchor),10,1,2,capture.editedCube(),1,
                (capture.editedCube().x()==0?"a":"b").repeat(64),"d".repeat(64),DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
        return new DhReplacementInput(cert,capture,GUARD).data();
    }
    static int material(CubePos cube,int anchor,long x,long y,long z) {
        boolean inside=x>=cube.minBlockX()&&x<cube.minBlockX()+16&&y>=cube.minBlockY()&&y<cube.minBlockY()+16&&z>=0&&z<16;
        if(!inside)return y<=anchor?STONE:0;
        // Exact 3D excavation with open faces/edges/corners and isolated upper solids.
        if(y<=anchor&&((x-cube.minBlockX())%5==0||z%5==0))return 0;
        if(y>anchor&&((x+3*y+z)&7)==0)return DIORITE;
        return y<=anchor?STONE:0;
    }
    static List<DhReplacementInput.Data> fixture(int anchor) {
        var out=new ArrayList<DhReplacementInput.Data>();
        for(int x:new int[]{0,4}) {
            var cube=new CubePos(x,Math.floorDiv(anchor,16),0);
            out.add(data(DhVoxelReplacementGeometry.capture(cube,(a,b,c)->material(cube,anchor,a,b,c),LIVE)));
        }return List.copyOf(out);
    }
    static List<CubePos> near(int x,int y,int z) {
        var out=new ArrayList<CubePos>();for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) {
            var c=new CubePos(x+dx,y+dy,z+dz);if(c.minBlockY()>=-11008&&c.minBlockY()+16<=8864)out.add(c);
        }return out;
    }
    static Set<Unit> oracle(List<DhReplacementInput.Data> regions,int anchor,List<CubePos> ready) {
        var result=new HashSet<Unit>();
        for(var data:regions) {
            var cube=data.capture().editedCube();var v=data.capture().ownedVolume();
            for(long y=v.minY();y<v.maxYExclusive();y++)for(long z=v.minZ();z<v.maxZExclusive();z++)for(long x=v.minX();x<v.maxXExclusive();x++) {
                int color=material(cube,anchor,x,y,z);if(color==0||ready.contains(CubePos.fromBlock(x,y,z)))continue;
                if(material(cube,anchor,x-1,y,z)==0)result.add(new Unit(x,y,z,Direction.WEST,color));
                if(material(cube,anchor,x+1,y,z)==0)result.add(new Unit(x+1,y,z,Direction.EAST,color));
                if(material(cube,anchor,x,y-1,z)==0)result.add(new Unit(x,y,z,Direction.DOWN,color));
                if(material(cube,anchor,x,y+1,z)==0)result.add(new Unit(x,y+1,z,Direction.UP,color));
                if(material(cube,anchor,x,y,z-1)==0)result.add(new Unit(x,y,z,Direction.NORTH,color));
                if(material(cube,anchor,x,y,z+1)==0)result.add(new Unit(x,y,z+1,Direction.SOUTH,color));
            }
        }return result;
    }
    static Face world(DhGeographicLodData.TileKey key,DhTerrainGeometry.PageFace f) {
        return new Face(f.direction(),key.originX()+f.x(),(long)f.pageY2048()*2048+f.y(),key.originZ()+f.z(),f.width(),f.height(),f.argb());
    }
    @Test void independentVoxelAndFlatRectangleOracleAtBothNativeEndpointsAndEveryRegionVisibility() {
        for(int anchor:new int[]{128,-11000,8849})for(int[] center:new int[][]{{0,0},{2,0},{4,0},{31,-32}}) {
            var regions=fixture(anchor);int y=Math.floorDiv(anchor,16);var ready=near(center[0],y,center[1]);
            long cx=Math.floorDiv(center[0]*16L+136,256)*256,cz=Math.floorDiv(center[1]*16L+136,256)*256;
            var result=DhReplacementSetComposition.build(policy(anchor),cx,cz,DhLodLayout.LONG_4096,ready,regions,DhReplacementComposition.MAX_RESULT_BYTES,LIVE);
            var expected=oracle(regions,anchor,ready);var actual=new HashSet<Unit>();var base=new ArrayList<Face>();
            for(var entry:result.batch().tiles().entrySet())for(var page:entry.getValue()) {
                var f=world(entry.getKey(),page);
                if(entry.getKey().replacementIdentity().isEmpty()) { base.add(f); assertEquals(Direction.UP,f.direction());assertEquals(anchor+1,f.y()); }
                else {assertEquals(1,f.width());assertEquals(1,f.height());assertTrue(actual.add(new Unit(f.x(),f.y(),f.z(),f.direction(),f.argb())),"Duplicate replacement face");}
            }
            assertEquals(expected,actual);assertEquals(expected.size(),result.replacementVisibleFaces());
            assertEquals(oracle(regions,anchor,List.of()).size(),result.replacementSourceFaces());
            var removed=new HashSet<XZ>();
            for(var d:regions){var v=d.capture().ownedVolume();for(long z=v.minZ();z<v.maxZExclusive();z++)for(long x=v.minX();x<v.maxXExclusive();x++)removed.add(new XZ(x,z));}
            for(var p:ready)if(anchor>=p.minBlockY()&&anchor<p.minBlockY()+16)for(long z=p.minBlockZ();z<p.minBlockZ()+16;z++)for(long x=p.minBlockX();x<p.minBlockX()+16;x++)removed.add(new XZ(x,z));
            assertEquals(8192L*8192-removed.size(),base.stream().mapToLong(f->(long)f.width()*f.height()).sum());
            // Independent unit coverage around both collars: no base pinhole or overlap.
            for(int z=-2;z<=18;z++)for(int x=-2;x<=82;x++) {
                int count=0;for(var f:base)if(f.x()<=x&&x<f.x()+f.width()&&f.z()<=z&&z<f.z()+f.height())count++;
                assertEquals(removed.contains(new XZ(x,z))?0:1,count,"Base cut at "+x+","+z);
            }
            assertTrue(result.batch().sampledCells()<=DhReplacementSetComposition.MAX_ANALYTIC_LEAVES);
            assertEquals(0,result.batch().transitionFaces());
            assertTrue(result.batch().faces()<=32768);assertTrue(result.batch().tiles().size()<=64);
            assertTrue(result.batch().tiles().values().stream().allMatch(v->v.size()<=4096));
            var census=DhReplacementSetComposition.inspect(policy(anchor),regions,LIVE);
            assertTrue(census.fits());assertEquals(result.replacementSourceFaces(),census.sourceFaces());
            assertEquals(census,DhReplacementSetComposition.inspectCaptures(policy(anchor),regions.stream().map(DhReplacementInput.Data::capture).toList(),LIVE));
            assertTrue(census.unionBytes()<=8L*1024*1024);assertTrue(census.unionOwners()<=128);
            assertEquals((32768L+census.faceUpperBound())*88,census.unionBytes());
            assertEquals(64+census.ownerUpperBound(),census.unionOwners());
            assertTrue(census.unionBytes()>2L*census.faceUpperBound()*88,
                    "A smaller candidate must still reserve a larger previous publication");
            assertTrue(census.unionOwners()>2*census.ownerUpperBound());
            var reversed=new ArrayList<>(regions);Collections.reverse(reversed);
            assertEquals(result,DhReplacementSetComposition.build(policy(anchor),cx,cz,DhLodLayout.LONG_4096,ready,reversed,DhReplacementComposition.MAX_RESULT_BYTES,LIVE));
        }
    }
    @Test void unrestrictedCheckerboardArithmeticIsPreservedButUnsupportedHalosCannotEnterCompleteSetCensus() {
        var regions=new ArrayList<DhReplacementInput.Data>();
        for(int x:new int[]{0,4})regions.add(data(DhVoxelReplacementGeometry.capture(new CubePos(x,8,0),(a,b,c)->((a+b+c)&1)==0?STONE:0,LIVE)));
        int sourceFaces=0,pieces=0;
        for(var region:regions){
            var mesh=DhVoxelReplacementGeometry.build(region.capture(),32768,5L*1024*1024,LIVE);
            assertEquals(17496,mesh.faceCount());sourceFaces+=mesh.faceCount();
            var buckets=new HashMap<String,Integer>();
            for(var f:mesh.faces()){
                long x=f.x()-(f.direction()==Direction.EAST?1:0);
                long y=f.y()-(f.direction()==Direction.UP?1:0);
                long z=f.z()-(f.direction()==Direction.SOUTH?1:0);
                buckets.merge(Math.floorDiv(x,32)+":"+Math.floorDiv(z,32)+":"+Math.floorDiv(y,2048),1,Integer::sum);
            }
            int count=buckets.values().stream().mapToInt(n->Math.ceilDiv(n,4096)).sum();assertEquals(7,count);pieces+=count;
        }
        assertEquals(34992,sourceFaces);assertEquals(14,pieces);
        assertEquals(46587,sourceFaces+DhReplacementSetComposition.MAX_ANALYTIC_BASE_FACES);
        // Complete-set admission now proves support as well as capacity. The raw failing
        // counterexample remains recorded above; an invalid halo is never a legal candidate.
        assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.inspect(policy(128),regions,LIVE));
        assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.inspect(policy(128),List.of(regions.getFirst()),LIVE));
        assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.build(policy(128),0,0,DhLodLayout.LONG_4096,near(0,8,0),regions,DhReplacementComposition.MAX_RESULT_BYTES,LIVE));
    }
    @Test void legalEditableCheckerboardsWithFlatHalosStillFailBeforeAnyPublication() {
        var regions=new ArrayList<DhReplacementInput.Data>();
        for(int cx:new int[]{0,4}) {
            var cube=new CubePos(cx,8,0);
            regions.add(data(DhVoxelReplacementGeometry.capture(cube,(x,y,z)-> {
                boolean editable=x>=cube.minBlockX()&&x<cube.minBlockX()+16&&y>=128&&y<144&&z>=0&&z<16;
                return editable?(((x+y+z)&1)==0?STONE:0):(y<=128?STONE:0);
            },LIVE)));
        }
        var census=DhReplacementSetComposition.inspect(policy(128),regions,LIVE);
        assertTrue(census.sourceFaces()>32768-DhReplacementSetComposition.MAX_ANALYTIC_BASE_FACES);
        assertFalse(census.fits());
        var failure=assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.build(policy(128),0,0,DhLodLayout.LONG_4096,near(0,8,0),regions,DhReplacementComposition.MAX_RESULT_BYTES,LIVE));
        assertTrue(failure.getMessage().contains("full-envelope"));
        System.out.println("Legal two edited-cube checkerboards: sourceFaces="+census.sourceFaces()+", totalReservedFaces="+census.faceUpperBound());
    }
    @Test void rendererIndependentlyRejectsMissingCleanRegionAndNonflatCaptureHalo() {
        var p=policy(128);var good=fixture(128);
        assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.build(p,0,0,DhLodLayout.LONG_4096,List.of(),List.of(good.getFirst()),DhReplacementComposition.MAX_RESULT_BYTES,LIVE));
        var corrupt=data(DhVoxelReplacementGeometry.capture(new CubePos(4,8,0),(x,y,z)->x==62&&y==128&&z==0?0:(y<=128?STONE:0),LIVE));
        var bad=List.of(good.getFirst(),corrupt);
        assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.inspect(p,bad,LIVE),"Complete-set census must now validate support before admission");
        var failure=assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.build(p,0,0,DhLodLayout.LONG_4096,List.of(),bad,DhReplacementComposition.MAX_RESULT_BYTES,LIVE));
        assertTrue(failure.getMessage().contains("read halo"));
        var incomplete=new ArrayList<>(near(0,8,0));incomplete.removeLast();
        assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.build(p,0,0,DhLodLayout.LONG_4096,incomplete,good,DhReplacementComposition.MAX_RESULT_BYTES,LIVE));
        var duplicates=new ArrayList<>(near(0,8,0));duplicates.set(1,duplicates.getFirst());
        assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.build(p,0,0,DhLodLayout.LONG_4096,duplicates,good,DhReplacementComposition.MAX_RESULT_BYTES,LIVE));
    }
    @Test void compactCapturePreservesExactGeometryAndChargesOnlyItsBackingBytes() {
        for(int anchor:new int[]{128,-11000,8849}) {
            var unpacked=fixture(anchor);var compact=new ArrayList<DhReplacementInput.Data>();
            for(var region:unpacked) {
                var capture=region.capture();var b=capture.readBounds();byte[] values=new byte[capture.cellCount()];int i=0;
                for(long y=b.minY();y<b.maxYExclusive();y++)for(long z=b.minZ();z<b.maxZExclusive();z++)for(long x=b.minX();x<b.maxXExclusive();x++) {
                    int color=material(capture.editedCube(),anchor,x,y,z);values[i++]=(byte)(color==0?0:color==STONE?1:2);
                }
                var raw=new NativeReplacementCapture(capture.editedCube(),values);
                var packed=DhVoxelReplacementGeometry.captureCompact(raw);
                assertEquals(capture.declaredBytes()/4,packed.declaredBytes());
                assertEquals(capture.cellCount(),packed.cellCount());
                assertEquals(DhVoxelReplacementGeometry.build(capture,32768,5L*1024*1024,LIVE).faces(),
                        DhVoxelReplacementGeometry.build(packed,32768,5L*1024*1024,LIVE).faces());
                compact.add(data(packed));
            }
            var a=DhReplacementSetComposition.inspect(policy(anchor),unpacked,LIVE);
            var b=DhReplacementSetComposition.inspect(policy(anchor),compact,LIVE);
            assertEquals(a.sourceFaces(),b.sourceFaces());assertEquals(a.pieceOwners(),b.pieceOwners());
            assertEquals(a.captureBytes()/4,b.captureBytes());assertTrue(b.captureBytes()<=16000);
            assertEquals(a.resultBytes()-a.captureBytes()+b.captureBytes(),b.resultBytes());
            var ready=near(31,Math.floorDiv(anchor,16),-32);
            var result=DhReplacementSetComposition.build(policy(anchor),512,-512,DhLodLayout.LONG_4096,ready,compact,DhReplacementComposition.MAX_RESULT_BYTES,LIVE);
            assertEquals(oracle(unpacked,anchor,ready).size(),result.replacementVisibleFaces());
        }
    }
    static int cleanMaterial(int anchor,long x,long y,long z) {
        if(y<=anchor)return STONE;
        long dy=y-anchor;
        boolean roof=dy==4&&x>=2&&x<7&&z>=3&&z<10;
        boolean support=dy>=1&&dy<=3&&x==2&&z>=3&&z<5;
        boolean wall=dy>=1&&dy<=3&&x==9&&z>=1&&z<9;
        return roof||support||wall?STONE:0;
    }
    @Test void zeroRevisionOriginalFixtureStillPublishesItsRoofSupportAndWallAfterRetirement() {
        for(int anchor:new int[]{128,-11000,8849}) {
            var regions=new ArrayList<DhReplacementInput.Data>();
            for(int x:new int[]{0,4}) {
                var capture=DhVoxelReplacementGeometry.capture(new CubePos(x,Math.floorDiv(anchor,16),0),(a,b,c)->cleanMaterial(anchor,a,b,c),LIVE);
                var cert=new DhReplacementInput.Certificate(world(anchor),10,1,2,capture.editedCube(),0,(x==0?"a":"b").repeat(64),"d".repeat(64),DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
                regions.add(new DhReplacementInput(cert,capture,GUARD).data());
            }
            var result=DhReplacementSetComposition.build(policy(anchor),512,-512,DhLodLayout.LONG_4096,near(31,Math.floorDiv(anchor,16),-32),regions,DhReplacementComposition.MAX_RESULT_BYTES,LIVE);
            var units=new HashSet<Unit>();
            for(var e:result.batch().tiles().entrySet())if(!e.getKey().replacementIdentity().isEmpty())for(var p:e.getValue()) {
                var f=world(e.getKey(),p);units.add(new Unit(f.x(),f.y(),f.z(),f.direction(),f.argb()));
            }
            assertTrue(units.contains(new Unit(2,anchor+5,3,Direction.UP,STONE)),"Clean original roof disappeared");
            assertTrue(units.contains(new Unit(9,anchor+4,1,Direction.UP,STONE)),"Clean original wall disappeared");
            assertTrue(units.contains(new Unit(2,anchor+1,3,Direction.WEST,STONE)),"Clean original support disappeared");
            assertTrue(units.contains(new Unit(64,anchor+1,0,Direction.UP,STONE)),"Clean second workspace plane disappeared");
            assertEquals(0,result.batch().transitionFaces());
        }
    }
    @Test void all1242YWindowsUseExactWholeCubeEligibilityAndTheSameXZProjection() throws Exception {
        var validate=DhReplacementSetComposition.class.getDeclaredMethod("validateReady",List.class);
        validate.setAccessible(true);
        Set<XZ> expected=null;int windows=0,endpoints=0;
        for(int y=-688;y<=553;y++) {
            var ready=near(23,y,-32);
            assertEquals(ready.stream().sorted().toList(),validate.invoke(null,ready));
            assertEquals(y==-688||y==553?18:27,ready.size());
            if(ready.size()==18)endpoints++;
            var projection=new HashSet<XZ>();for(var cube:ready) {
                assertTrue(cube.minBlockY()>=-11008&&cube.minBlockY()+16<=8864);
                projection.add(new XZ(cube.x(),cube.z()));
            }
            if(expected==null)expected=projection;else assertEquals(expected,projection);
            windows++;
        }
        assertEquals(1242,windows);assertEquals(2,endpoints);assertEquals(9,expected.size());
        // Empty initialization is admitted at each of the 25 canonical far centers.
        var regions=fixture(128);
        for(int x=-512;x<=512;x+=256)for(int z=-512;z<=512;z+=256) {
            final int cx=x,cz=z;
            assertDoesNotThrow(()->DhReplacementSetComposition.build(policy(128),cx,cz,DhLodLayout.LONG_4096,List.of(),regions,DhReplacementComposition.MAX_RESULT_BYTES,LIVE));
        }
    }
    @Test void boundariesCapabilitiesAndCancellationAreExplicit() {
        var data=fixture(128);var p=policy(128);
        assertThrows(IllegalArgumentException.class,()->new DhAnalyticFlatPolicy("other",WORLD,128,STONE));
        assertThrows(IllegalArgumentException.class,()->new DhAnalyticFlatPolicy(p.sourceIdentity(),WORLD,128,DIORITE));
        assertNotEquals(p.identity(),policy(-11000).identity());
        assertThrows(IllegalArgumentException.class,()->new DhAnalyticFlatPolicy("earth",new WorldIdentity(0,"earth","v1","dem","palette"),128,STONE));
        assertThrows(IllegalArgumentException.class,()->new DhAnalyticFlatPolicy("bad-height",WORLD,2048,STONE));
        assertThrows(IllegalArgumentException.class,()->new DhAnalyticFlatPolicy("bad-alpha",WORLD,128,0));
        assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.inspect(p,List.of(data.getFirst(),data.getFirst()),LIVE));
        assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.inspect(p,List.of(data.getFirst(),data.get(1),data.getFirst()),LIVE));
        var invalid=data(DhVoxelReplacementGeometry.capture(new CubePos(2,8,0),(x,y,z)->0,LIVE));
        assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.inspect(p,List.of(invalid),LIVE));
        assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.build(p,768,0,DhLodLayout.LONG_4096,List.of(),data,DhReplacementComposition.MAX_RESULT_BYTES,LIVE));
        assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.build(p,0,0,DhLodLayout.LONG_4096,List.of(new CubePos(0,8,0),new CubePos(4,8,0)),data,DhReplacementComposition.MAX_RESULT_BYTES,LIVE));
        assertThrows(CancellationException.class,()->DhReplacementSetComposition.inspectCaptures(p,data.stream().map(DhReplacementInput.Data::capture).toList(),()->{throw new CancellationException();}));
        long reserve=DhReplacementSetComposition.inspect(p,data,LIVE).resultBytes();
        assertThrows(DhReplacementComposition.Declined.class,()->DhReplacementSetComposition.build(p,0,0,DhLodLayout.LONG_4096,List.of(),data,reserve-1,LIVE));
        assertDoesNotThrow(()->DhReplacementSetComposition.build(p,0,0,DhLodLayout.LONG_4096,List.of(),data,reserve,LIVE));
    }
}
