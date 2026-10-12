package io.earthplatform.minecraft.visual;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission;
import io.earthplatform.minecraft.enginebridge.EarthReplacementCapture;
import io.earthplatform.minecraft.lod.*;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongBinaryOperator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent lattice and rectangle oracles; does not use the author tests' helpers. */
class DistanceGeometryIndependentTest {
    static final EarthDistanceSurfacePlan.Limits N = new EarthDistanceSurfacePlan.Limits(
            4_000_000, 128L<<20, 2_000_000, 4096, 8192, 8_000_000, 2L<<30, 64L<<20);
    static final DhNativeDistanceTerrainData.Limits F = new DhNativeDistanceTerrainData.Limits(
            65536, 131072, 256, 4096, 20L<<20);
    static final LongBinaryOperator FLAT=(x,z)->15;
    static final LongBinaryOperator CLIFF=(x,z)->x<0?-75:200;
    static final Runnable CHECK=()->{};
    static EarthAlpineDefinition.Envelope env(){return new EarthAlpineDefinition.Envelope(-3,4,-3,4,-16,16,-4096,4096,-4096,4096);}
    static EarthDistanceSurfacePlan.Sample sample(long h){return new EarthDistanceSurfacePlan.Sample(h,5,3,7,2,h-.125,.125);}
    static EarthDistanceSurfacePlan.Source source(int distance,LongBinaryOperator height){
        return EarthDistanceSurfacePlan.Source.prepareSampled(env(),-256,256,"independent-source","independent-detail","independent-palette",distance,0,N,(x,z)->sample(height.applyAsLong(x,z)));
    }
    static EarthDistanceSurfacePlan plan(EarthDistanceSurfacePlan.Source s,EarthReplacementCapture c,CubePos p,int r){return EarthDistanceSurfacePlan.build(s,c,p,r,0,N);}
    static EarthDistanceSurfacePlan plan(EarthDistanceSurfacePlan.Source s,EarthReplacementCapture c){return plan(s,c,new CubePos(0,0,0),2);}
    static EarthReplacementCapture capture(EarthDistanceSurfacePlan.Source s,CubePos p){
        try(var b=new EarthReplacementCapture.Builder(p)){
            for(long y=p.minBlockY()-2;y<p.minBlockY()+18;y++)for(long z=p.minBlockZ()-2;z<p.minBlockZ()+18;z++)for(long x=p.minBlockX()-2;x<p.minBlockX()+18;x++)b.setNext(s.materialAt(x,y,z));
            return b.finish();
        }
    }
    static SourceSurfaceCoverage coverage(EarthDistanceSurfacePlan p){return SourceSurfaceCoverage.complete(p,p.pageIndex().keySet());}
    static SourceSurfaceCoverage subset(EarthDistanceSurfacePlan p,long... pagesY){
        var keys=new TreeSet<NativePageKey>();for(var k:p.pageIndex().keySet())for(long y:pagesY)if(k.y()==y)keys.add(k);
        return SourceSurfaceCoverage.restricted(p,keys);
    }
    static class Sampler implements SceneSurfaceSampler {
        final LongBinaryOperator height;
        int reads,footprints;
        String id="independent-sampler";
        boolean supported=true,strata=true;
        Sampler(LongBinaryOperator height){this.height=height;}
        public long surfaceY(long x,long z){reads++;return height.applyAsLong(x,z);}
        public int argb(long x,long z,long y){return 0xfff1f5f6;}
        public String identity(){return id;}
        public boolean supportsFootprintCoverage(){return supported;}
        public boolean knownFootprint(long x,long z,long x1,long z1){footprints++;return true;}
        public boolean supportsStratifiedSides(){return strata;}
        public ColumnMaterials columnMaterials(long x,long z,long y){return new ColumnMaterials(
                new SurfaceMaterial(0xfff1f5f6,8),new SurfaceMaterial(0xff999a99,2),new SurfaceMaterial(0xff8a8a8a,2),3);}
    }
    static DhNativeDistanceTerrainData data(SourceSurfaceCoverage c,SceneSurfaceSampler s){return new DhNativeDistanceTerrainData(s,0,0,DhLodLayout.EARTH_ALPINE_2048,c,F);}
    record Unit(long x,long y,long z,EarthDistanceSurfacePlan.Direction d,int material){}
    static int oracleMaterial(EarthDistanceSurfacePlan.Source s,EarthReplacementCapture capture,long x,long y,long z){
        if(capture!=null){var p=capture.editedCube();if(x>=p.minBlockX()&&x<p.minBlockX()+16&&y>=p.minBlockY()&&y<p.minBlockY()+16&&z>=p.minBlockZ()&&z<p.minBlockZ()+16)return capture.materialAt(x,y,z);}
        return s.materialAt(x,y,z);
    }
    static Set<Unit> oracle(EarthDistanceSurfacePlan.Source s,EarthReplacementCapture c,long x0,long z0,long x1,long z1,long y0,long y1){
        Set<Unit> result=new HashSet<>();
        for(long z=z0;z<z1;z++)for(long x=x0;x<x1;x++)for(long y=y0;y<y1;y++){
            int m=oracleMaterial(s,c,x,y,z);if(m==0)continue;
            for(var d:EarthDistanceSurfacePlan.Direction.values())if(oracleMaterial(s,c,x+d.dx(),y+d.dy(),z+d.dz())==0)result.add(new Unit(x,y,z,d,m));
        }
        return result;
    }
    static Set<Unit> nativeFaces(EarthDistanceSurfacePlan p,Set<NativePageKey> keys){
        Set<Unit> result=new HashSet<>();for(var key:keys)p.forEachFace(key,(x,y,z,d,m)->assertTrue(result.add(new Unit(x,y,z,d,m)),"Duplicate native lattice face"));return result;
    }
    static List<DhTerrainGeometry.Face> unpack(DhAdaptiveTerrainData.Batch batch){
        var result=new ArrayList<DhTerrainGeometry.Face>();batch.tiles().forEach((k,list)->list.forEach(f->result.add(new DhTerrainGeometry.Face(f.direction(),k.originX()+f.x(),2048L*f.pageY2048()+f.y(),k.originZ()+f.z(),f.width(),f.height(),f.argb(),f.material()))));return result;
    }
    @Test void exactLatticeFacesMatchIndependentOracleAcrossSignedPagesCavesAndBoundaryEdits(){
        var s=source(2,(x,z)->x<0?-3:15);
        var c=capture(s,new CubePos(-1,0,-1)).withMaterial(-1,0,-1,2).compact()
                .withMaterial(-2,1,-1,2).compact().withMaterial(-1,0,-2,4).compact();
        var p=plan(s,c);var b=p.bounds();
        assertEquals(oracle(s,c,b.minX(),b.minZ(),b.maxXExclusive(),b.maxZExclusive(),-5,18),nativeFaces(p,p.pageIndex().keySet()));
        var buried=capture(s,new CubePos(0,-1,0)).withMaterial(0,-1,0,0).compact().withMaterial(1,-1,0,0).compact()
                .withMaterial(0,-2,0,0).compact().withMaterial(15,-16,15,0).compact();
        p=plan(s,buried);
        assertEquals(oracle(s,buried,b.minX(),b.minZ(),b.maxXExclusive(),b.maxZExclusive(),-18,18),nativeFaces(p,p.pageIndex().keySet()));
    }
    @Test void selectedDistanceFootprintsUseSignedFloorCeilingWithoutRadiusClamping(){
        var s=source(32,(x,z)->-1);
        for(int radius:new int[]{2,3,7,12,17,24,31,32})for(var c:List.of(new CubePos(-3,-15,3),new CubePos(0,0,0),new CubePos(3,15,-3))){
            var p=plan(s,null,c,radius);long x0=Math.floorDiv(16L*(c.x()-radius),64)*64,z0=Math.floorDiv(16L*(c.z()-radius),64)*64;
            long x1=Math.floorDiv(16L*(c.x()+radius+1)+63,64)*64,z1=Math.floorDiv(16L*(c.z()+radius+1)+63,64)*64;
            assertEquals(new EarthDistanceSurfacePlan.Bounds(x0,z0,x1,z1),p.bounds());assertEquals((x1-x0)*(z1-z0),p.quads());
            assertEquals(p.bounds().columns()/4096,p.pageCount());assertEquals(radius,p.requestedChunks());
        }
    }
    @Test void sourceSlabReadsExactlyOnceWithOneColumnHaloAndRejectsUnsupportedBeforeSampling(){
        var seen=new HashSet<String>();var s=EarthDistanceSurfacePlan.Source.prepareSampled(env(),-256,256,"s","d","m",2,0,N,(x,z)->{assertTrue(seen.add(x+":"+z));return sample(15);});
        assertEquals(s.sampledColumns(),seen.size());assertEquals(s.ownedBounds().expand(1),s.bounds());
        plan(s,null,new CubePos(-3,-15,3),2);plan(s,null,new CubePos(3,15,-3),2);assertEquals(s.sampledColumns(),seen.size());
        AtomicInteger calls=new AtomicInteger();var narrow=new EarthAlpineDefinition.Envelope(0,1,0,1,-2,2,-64,64,-64,64);
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->EarthDistanceSurfacePlan.Source.prepareSampled(narrow,-256,256,"s","d","m",2,0,N,(x,z)->{calls.incrementAndGet();return sample(15);}));assertEquals(0,calls.get());
        var cap=new EarthDistanceSurfacePlan.Limits(1,1,1,1,1,1,1,1);
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->EarthDistanceSurfacePlan.Source.prepareSampled(env(),-256,256,"s","d","m",2,0,cap,(x,z)->{calls.incrementAndGet();return sample(15);}));assertEquals(0,calls.get());
    }
    @Test void coverageContentIgnoresVerticalAndSameRoundedHorizontalFocusButBindsEveryChangedInput(){
        var s=source(3,FLAT);var p=plan(s,null);var c=coverage(p);
        var vertical=plan(s,null,new CubePos(0,15,0),2);var shifted=plan(s,null,new CubePos(1,-15,1),2);var radius=plan(s,null,new CubePos(0,0,0),3);
        assertEquals(p.bounds(),shifted.bounds());assertEquals(p.bounds(),radius.bounds());assertNotEquals(p.identity(),vertical.identity());assertNotEquals(p.identity(),shifted.identity());
        assertEquals(c.identity(),coverage(vertical).identity());assertEquals(c.identity(),coverage(shifted).identity());assertEquals(c.identity(),coverage(radius).identity());
        assertNotEquals(c.identity(),coverage(plan(s,null,new CubePos(2,0,0),2)).identity());
        assertNotEquals(c.identity(),coverage(plan(source(3,(x,z)->14),null)).identity());
        var changedReceipt=EarthDistanceSurfacePlan.Source.prepareSampled(env(),-256,256,"independent-source","independent-detail","independent-palette",3,0,N,(x,z)->new EarthDistanceSurfacePlan.Sample(15,5,3,7,1,14.875,.125));
        assertNotEquals(c.identity(),coverage(plan(changedReceipt,null)).identity());
        var edit=capture(s,new CubePos(0,0,0)).withMaterial(5,15,5,2);assertNotEquals(c.identity(),coverage(plan(s,edit)).identity());
        var fewer=new HashSet<>(p.pageIndex().keySet());fewer.remove(fewer.iterator().next());assertNotEquals(c.identity(),SourceSurfaceCoverage.restricted(p,fewer).identity());
        var reversed=new ArrayList<>(p.pageIndex().keySet());Collections.reverse(reversed);assertEquals(c.identity(),SourceSurfaceCoverage.restricted(p,new LinkedHashSet<>(reversed)).identity());
    }
    @Test void signedSubsetClassificationMatchesActualFaceMembershipForEveryColumnAndRectangle(){
        var s=source(2,CLIFF);var capture=capture(s,new CubePos(0,-1,0)).withMaterial(0,-1,0,0);var p=plan(s,capture);
        for(long[] chosen:new long[][]{{3},{-2,0,2},{-1,1},{}}){
            var c=subset(p,chosen);var selected=c.eligiblePages();var seen=new HashMap<String,int[]>();
            for(var f:nativeFaces(p,p.pageIndex().keySet())){var counts=seen.computeIfAbsent(f.x+":"+f.z,k->new int[2]);counts[0]++;if(selected.contains(NativePageKey.containing(f.x,f.y,f.z)))counts[1]++;}
            for(long z=-64;z<64;z++)for(long x=-64;x<64;x++){
                var counts=seen.get(x+":"+z);var expected=counts[1]==0?SourceSurfaceCoverage.Classification.UNCOVERED:counts[0]==counts[1]?SourceSurfaceCoverage.Classification.FULL:SourceSurfaceCoverage.Classification.BOUNDARY;
                assertEquals(expected,c.classification(x,z,x+1,z+1),"Column "+x+","+z);
            }
            var random=new Random(0x31cafe);for(int i=0;i<100;i++){
                int x0=random.nextInt(160)-80,z0=random.nextInt(160)-80,x1=x0+1+random.nextInt(30),z1=z0+1+random.nextInt(30);boolean any=false,full=true;
                for(int z=z0;z<z1;z++)for(int x=x0;x<x1;x++){var f=seen.get(x+":"+z);int all=f==null?1:f[0],hit=f==null?0:f[1];any|=hit>0;full&=all==hit;}
                assertEquals(full?SourceSurfaceCoverage.Classification.FULL:any?SourceSurfaceCoverage.Classification.BOUNDARY:SourceSurfaceCoverage.Classification.UNCOVERED,c.classification(x0,z0,x1,z1));
            }
        }
    }
    @Test void solidIntervalsExactlyMatchSelectedPageOccupancyAndCaveHoles(){
        var s=source(2,CLIFF);var edit=capture(s,new CubePos(0,-1,0)).withMaterial(0,-1,0,0).compact().withMaterial(0,-2,0,0).compact();var p=plan(s,edit);
        var c=subset(p,-2,-1,1,3);
        for(long x:new long[]{-1,0,1,15,63,64})for(long z:new long[]{-1,0,15}){
            var spans=c.solidSpans(x,z);long previous=Long.MIN_VALUE;for(var span:spans){assertTrue(previous<span.minY(),"Intervals must be sorted and maximally coalesced");previous=span.maxYExclusive();}
            for(long y=-200;y<220;y++){
                boolean expected=p.bounds().contains(x,z)&&c.eligiblePages().contains(NativePageKey.containing(x,y,z))&&oracleMaterial(s,edit,x,y,z)!=0;
                assertEquals(expected,c.covers(x,y,z));long yy=y;assertEquals(expected,spans.stream().anyMatch(span->span.contains(yy)));
            }
        }
        assertFalse(c.covers(0,-1,0));assertTrue(c.covers(0,-3,0));assertFalse(c.covers(0,0,0));
    }
    record Rect(long x0,long z0,long x1,long z1){}
    record Event(long z,int delta,int left,int right){}
    static void exactlyOneTopOwner(List<Rect> rects,long min,long max){
        var xs=new TreeSet<Long>();xs.add(min);xs.add(max);for(var r:rects){assertTrue(r.x0>=min&&r.z0>=min&&r.x1<=max&&r.z1<=max);xs.add(r.x0);xs.add(r.x1);}
        var x=new ArrayList<>(xs);var events=new ArrayList<Event>();for(var r:rects){events.add(new Event(r.z0,1,Collections.binarySearch(x,r.x0),Collections.binarySearch(x,r.x1)));events.add(new Event(r.z1,-1,Collections.binarySearch(x,r.x0),Collections.binarySearch(x,r.x1)));}
        events.sort(Comparator.comparingLong(Event::z));int[] counts=new int[x.size()-1];long last=min;
        for(int i=0;i<events.size();){long z=events.get(i).z;if(z>last)for(int n:counts)assertEquals(1,n,"Horizontal coverage hole or overlap in z slab "+last+":"+z);
            while(i<events.size()&&events.get(i).z==z){var e=events.get(i++);for(int k=e.left;k<e.right;k++)counts[k]+=e.delta;}last=z;}
        assertEquals(max,last);for(int n:counts)assertEquals(0,n);
    }
    @Test void full4096SquareHasNoHorizontalHoleOrOverlapAtOrdinaryAndMaximumDistances(){
        var s=source(32,FLAT);
        for(int radius:new int[]{2,12,24,32}){
            var p=plan(s,null,new CubePos(0,0,0),radius);var c=coverage(p);var sampler=new Sampler(FLAT);var b=data(c,sampler).compose(null,null,CHECK);
            var rects=new ArrayList<Rect>();var nb=p.bounds();rects.add(new Rect(nb.minX(),nb.minZ(),nb.maxXExclusive(),nb.maxZExclusive()));
            for(var f:unpack(b)){assertEquals(DhTerrainGeometry.Direction.UP,f.direction());assertEquals(16,f.y());rects.add(new Rect(f.x(),f.z(),f.x()+f.width(),f.z()+f.height()));}
            exactlyOneTopOwner(rects,-2048,2048);assertEquals(b.sampledCells(),sampler.reads);assertEquals(0,b.unknownCells());assertTrue(b.tiles().size()<=256);assertTrue(b.faces()<=131072);
        }
    }
    @Test void skippedInteriorIsNeverQueriedAndRetainedUnknownSupportFailsClosed(){
        var c=coverage(plan(source(2,FLAT),null));
        var skipSampler=new Sampler(FLAT){public long surfaceY(long x,long z){assertFalse(x>=-60&&x<60&&z>=-60&&z<60,"Source read escaped elision");return super.surfaceY(x,z);}public boolean knownFootprint(long x,long z,long x1,long z1){assertFalse(x>=-60&&x1<=60&&z>=-60&&z1<=60);return super.knownFootprint(x,z,x1,z1);}};
        data(c,skipSampler).compose(null,null,CHECK);
        var unknown=new Sampler(FLAT){public boolean knownFootprint(long x,long z,long x1,long z1){super.knownFootprint(x,z,x1,z1);return !(x<=64&&x1>64&&z<=0&&z1>0);}};
        assertThrows(IllegalArgumentException.class,()->data(c,unknown).compose(null,null,CHECK));
        var missing=new Sampler(FLAT){public long surfaceY(long x,long z){if(x==64&&z==0)throw new MissingTerrainException("independent retained boundary hole");return super.surfaceY(x,z);}};
        assertThrows(IllegalArgumentException.class,()->data(c,missing).compose(null,null,CHECK));
        var mutation=new Sampler(FLAT){public long surfaceY(long x,long z){id="changed";return super.surfaceY(x,z);}};
        assertThrows(IllegalStateException.class,()->data(c,mutation).compose(null,null,CHECK));
    }
    @Test void partialTallCliffFacesKeepExactlyOneOwnerAtEverySignedHeight(){
        var p=plan(source(2,CLIFF),null);
        for(long[] ys:new long[][]{{3},{0,2},{-2,1},{}}){
            var c=subset(p,ys);var b=data(c,new Sampler(CLIFF)).compose(null,null,CHECK);int[][] count=new int[136][275];
            for(var face:nativeFaces(p,c.eligiblePages()))if(face.d==EarthDistanceSurfacePlan.Direction.WEST&&face.x==0&&face.z>=-68&&face.z<68&&face.y>=-74&&face.y<=200)count[(int)face.z+68][(int)face.y+74]++;
            for(var f:unpack(b))if(f.direction()==DhTerrainGeometry.Direction.WEST&&f.x()==0){for(long z=Math.max(-68,f.z());z<Math.min(68,f.z()+f.width());z++)for(long y=Math.max(-74,f.y());y<Math.min(201,f.y()+f.height());y++)count[(int)z+68][(int)y+74]++;}
            for(int z=0;z<136;z++)for(int y=0;y<275;y++)assertEquals(1,count[z][y],"Cliff duplicate or hole at z="+(z-68)+", y="+(y-74));
        }
    }
    static Set<Unit> localUnits(List<DhTerrainGeometry.Face> faces,long x0,long z0,long x1,long z1,long y0,long y1){
        Set<Unit> result=new HashSet<>();for(var f:faces){
            var d=EarthDistanceSurfacePlan.Direction.valueOf(f.direction().name());
            for(int v=0;v<f.height();v++)for(int u=0;u<f.width();u++){
                long x=f.x()+(d==EarthDistanceSurfacePlan.Direction.WEST?0:d==EarthDistanceSurfacePlan.Direction.EAST?-1:u);
                long z=f.z()+(d==EarthDistanceSurfacePlan.Direction.NORTH?0:d==EarthDistanceSurfacePlan.Direction.SOUTH?-1:d==EarthDistanceSurfacePlan.Direction.UP||d==EarthDistanceSurfacePlan.Direction.DOWN?v:u);
                long y=f.y()+(d==EarthDistanceSurfacePlan.Direction.UP?-1:d==EarthDistanceSurfacePlan.Direction.DOWN?0:v);
                if(x<x0||x>=x1||z<z0||z>=z1||y<y0||y>=y1)continue;
                // Geometry membership ignores colour here; palette is checked separately.
                assertTrue(result.add(new Unit(x,y,z,d,0)),"Duplicate retained face at "+x+","+y+","+z+" "+d);
            }
        }return result;
    }
    @Test void owned18Read20AndNativeSignedSubsetsJoinWithoutCaveGapsOrDuplicates(){
        var s=source(2,FLAT);var edit=capture(s,new CubePos(0,-1,0)).withMaterial(0,-1,0,0).compact().withMaterial(0,-2,0,0).compact().withMaterial(15,-16,15,0).compact();
        var p=plan(s,edit);var replacement=DhVoxelReplacementGeometry.captureEarth(edit);var mesh=DhVoxelReplacementGeometry.build(replacement,8192,8192L*DhVoxelReplacementGeometry.BYTES_PER_FACE,CHECK);var r=replacement.readBounds();
        for(long[] ys:new long[][]{{0},{-1},{-1,0},{}}){
            var c=subset(p,ys);var composer=data(c,new Sampler(FLAT));var base=composer.compose(replacement.ownedVolume(),new DhTerrainGeometry.Volume(r.minX(),r.minY(),r.minZ(),r.maxXExclusive(),r.maxYExclusive(),r.maxZExclusive()),CHECK);var far=unpack(base);for(var f:mesh.faces())far.addAll(composer.clipCoverage(f));
            var actual=localUnits(far,-2,-2,18,18,-18,18);for(var f:nativeFaces(p,c.eligiblePages()))if(f.x>=-2&&f.x<18&&f.z>=-2&&f.z<18&&f.y>=-18&&f.y<18)assertTrue(actual.add(new Unit(f.x,f.y,f.z,f.d,0)),"Native/far overlap at "+f);
            var expected=new HashSet<Unit>();for(var f:oracle(s,edit,-2,-2,18,18,-18,18))expected.add(new Unit(f.x,f.y,f.z,f.d,0));assertEquals(expected,actual);
        }
    }
    @Test void exactSignedClipPreservesStrataAndSolidSideAtPositiveAndNegativeNormals(){
        var p=plan(source(2,CLIFF),null);var c=subset(p,0,2);var composer=data(c,new Sampler(CLIFF));
        for(var d:List.of(DhTerrainGeometry.Direction.WEST,DhTerrainGeometry.Direction.EAST,DhTerrainGeometry.Direction.NORTH,DhTerrainGeometry.Direction.SOUTH)){
            long x=d==DhTerrainGeometry.Direction.EAST?1:0,z=d==DhTerrainGeometry.Direction.SOUTH?1:0;
            var f=new DhTerrainGeometry.Face(d,x,-74,z,1,275,0xff123456,2);var pieces=composer.clipCoverage(f);int[] hit=new int[275];for(var a:pieces){assertEquals(f.argb(),a.argb());assertEquals(f.material(),a.material());assertEquals(f.direction(),a.direction());for(long y=a.y();y<a.y()+a.height();y++)hit[(int)y+74]++;}
            for(int i=0;i<hit.length;i++)assertEquals(c.covers(0,i-74,0)?0:1,hit[i]);
        }
        var top=subset(p,3);assertTrue(data(top,new Sampler(CLIFF)).clipCoverage(new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.UP,0,201,0,1,1,0xff123456,2)).isEmpty());
        assertEquals(1,data(top,new Sampler(CLIFF)).clipCoverage(new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.DOWN,0,201,0,1,1,0xff123456,2)).size());
    }
    @Test void eligibilityAndClipApiMisuseFailBeforeProducingAnApproximation(){
        var p=plan(source(2,FLAT),null);var c=coverage(p);assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->SourceSurfaceCoverage.complete(p,Set.of()));
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->SourceSurfaceCoverage.restricted(p,Set.of(new NativePageKey(100,100,100))));
        assertThrows(UnsupportedOperationException.class,()->c.eligiblePages().clear());assertThrows(UnsupportedOperationException.class,()->c.solidSpans(0,0).clear());
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->c.classification(0,0,0,1));assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->c.classification(Long.MIN_VALUE,0,Long.MAX_VALUE,1));
        var composer=data(c,new Sampler(FLAT));for(var f:List.of(new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.UP,0,16,0,2,2,0xff000000,2),new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.WEST,0,1,0,2,15,0xff000000,2)))assertThrows(IllegalStateException.class,()->composer.clipCoverage(f));
        assertThrows(DhNativeDistanceTerrainData.BudgetExceeded.class,()->composer.compose(null,new DhTerrainGeometry.Volume(0,0,0,21,1,1),CHECK));
        var noFootprint=new Sampler(FLAT);noFootprint.supported=false;assertThrows(IllegalArgumentException.class,()->data(c,noFootprint));
    }
    @Test void explicitFarBudgetsAndCancellationRefuseWithoutPartialPublication(){
        var c=coverage(plan(source(2,FLAT),null));var sampler=new Sampler(FLAT);
        var tinyCells=new DhNativeDistanceTerrainData.Limits(1,131072,256,4096,20L<<20);
        assertThrows(DhNativeDistanceTerrainData.BudgetExceeded.class,()->new DhNativeDistanceTerrainData(sampler,0,0,DhLodLayout.EARTH_ALPINE_2048,c,tinyCells).compose(null,null,CHECK));assertEquals(0,sampler.reads);
        for(var limit:List.of(new DhNativeDistanceTerrainData.Limits(65536,1,256,4096,20L<<20),new DhNativeDistanceTerrainData.Limits(65536,131072,1,4096,20L<<20),new DhNativeDistanceTerrainData.Limits(65536,131072,256,4096,4096)))assertThrows(DhNativeDistanceTerrainData.BudgetExceeded.class,()->new DhNativeDistanceTerrainData(new Sampler(FLAT),0,0,DhLodLayout.EARTH_ALPINE_2048,c,limit).compose(null,null,CHECK));
        var stopped=new IllegalStateException("independent cancellation");assertSame(stopped,assertThrows(IllegalStateException.class,()->data(c,new Sampler(FLAT)).compose(null,null,()->{throw stopped;})));
    }

    record Edge(long lo,long hi,int size){}
    static void verifyDyadicNeighbours(Map<Long,List<Edge>[]> lines){
        for(var line:lines.values()){
            line[0].sort(Comparator.comparingLong(Edge::lo));line[1].sort(Comparator.comparingLong(Edge::lo));int a=0,b=0;
            while(a<line[0].size()&&b<line[1].size()){
                var x=line[0].get(a);var y=line[1].get(b);
                if(Math.max(x.lo,y.lo)<Math.min(x.hi,y.hi))assertTrue(Math.max(x.size,y.size)<=2*Math.min(x.size,y.size),"Non-dyadic boundary jump "+x+" / "+y);
                if(x.hi<=y.hi)a++;if(y.hi<=x.hi)b++;
            }
        }
    }
    @SuppressWarnings("unchecked") static void edge(Map<Long,List<Edge>[]> lines,long plane,int side,long lo,long hi,int size){
        var line=lines.computeIfAbsent(plane,k->new List[]{new ArrayList<Edge>(),new ArrayList<Edge>()});line[side].add(new Edge(lo,hi,size));
    }
    @Test void everyNativePerimeterIsUnitRefinedAndOutgoingNeighboursAreAtMostTwoToOne(){
        var s=source(24,FLAT);var p=plan(s,null,new CubePos(0,0,0),24);var b=p.bounds();var faces=unpack(data(coverage(p),new Sampler(FLAT)).compose(null,null,CHECK));
        var xlines=new HashMap<Long,List<Edge>[]>();var zlines=new HashMap<Long,List<Edge>[]>();int perimeter=0;
        for(var f:faces){assertEquals(f.width(),f.height());int size=f.width();assertEquals(0,size&(size-1));
            boolean touches=(f.x()+size==b.minX()||f.x()==b.maxXExclusive())&&f.z()<b.maxZExclusive()&&f.z()+size>b.minZ()
                ||(f.z()+size==b.minZ()||f.z()==b.maxZExclusive())&&f.x()<b.maxXExclusive()&&f.x()+size>b.minX();
            if(touches){assertEquals(1,size);perimeter++;}
            edge(xlines,f.x(),1,f.z(),f.z()+size,size);edge(xlines,f.x()+size,0,f.z(),f.z()+size,size);
            edge(zlines,f.z(),1,f.x(),f.x()+size,size);edge(zlines,f.z()+size,0,f.x(),f.x()+size,size);
        }
        assertEquals(2*b.width()+2*b.depth(),perimeter);verifyDyadicNeighbours(xlines);verifyDyadicNeighbours(zlines);
    }
    static DhReplacementInput.Data replacement(EarthReplacementCapture capture){
        var world=new WorldIdentity(31,"independent","v1","synthetic-Earth-shape","six-material");
        return DhReplacementInput.content(new DhReplacementInput.Certificate(world,1,1,1,capture.editedCube(),0,"a".repeat(64),"b".repeat(64),EarthReplacementAdmission.MATERIAL_POLICY),DhVoxelReplacementGeometry.captureEarth(capture));
    }
    @Test void compositionEntryBindsLayoutCaptureCenterAndSourceCapabilities() throws Exception {
        long h=EarthAlpineDefinition.GROUND_Y;var pin=DhEarthReplacementAdmission.PIN;
        var definition=EarthAlpineDefinition.load(java.nio.file.Path.of(System.getProperty("earthplatform.test.projectDirectory", "minecraft")).resolve("../geography").normalize());
        var s=EarthDistanceSurfacePlan.Source.prepare(definition,2,0,N);
        var cap=capture(s,pin);var p=plan(s,cap,pin,2);var c=coverage(p);var input=replacement(cap);var sampler=new Sampler((x,z)->definition.column(x,z).surfaceY()){
            public int argb(long x,long z,long y){return definition.column(x,z).top().argb();}
            public ColumnMaterials columnMaterials(long x,long z,long y){var c=definition.column(x,z);return new ColumnMaterials(mat(c.top()),mat(c.subsurface()),mat(c.deep()),3);}
            SurfaceMaterial mat(EarthAlpineDefinition.Material m){return new SurfaceMaterial(m.argb(),m==EarthAlpineDefinition.Material.SNOW_BLOCK?8:2);}
            public boolean knownFootprint(long x,long z,long x1,long z1){return definition.knownFootprint(x,z,x1,z1);}
        };sampler.id=EarthAlpineDefinition.DEFINITION_IDENTITY;
        long cx=EarthAlpineDefinition.snapCubeCenter(pin.x()),cz=EarthAlpineDefinition.snapCubeCenter(pin.z());
        var result=DhNativeDistanceComposition.build(sampler,cx,cz,DhLodLayout.EARTH_ALPINE_2048,c,input,F,CHECK);
        assertEquals(0,result.batch().unknownCells());assertEquals(0,result.replacementVisibleFaces());
        var empty=SourceSurfaceCoverage.restricted(p,Set.of());var allFar=DhNativeDistanceComposition.build(sampler,cx,cz,DhLodLayout.EARTH_ALPINE_2048,empty,input,F,CHECK);
        assertTrue(allFar.replacementVisibleFaces()>0);assertTrue(allFar.batch().tiles().values().stream().allMatch(fs->fs.size()<=4096));
        assertThrows(IllegalArgumentException.class,()->DhNativeDistanceComposition.build(sampler,cx,cz,DhLodLayout.FIXED_512,c,input,F,CHECK));
        assertThrows(IllegalArgumentException.class,()->DhNativeDistanceComposition.build(sampler,cx+1,cz,DhLodLayout.EARTH_ALPINE_2048,c,input,F,CHECK));
        assertThrows(IllegalArgumentException.class,()->DhNativeDistanceComposition.build(sampler,cx+128,cz,DhLodLayout.EARTH_ALPINE_2048,c,input,F,CHECK));
        assertThrows(IllegalArgumentException.class,()->DhNativeDistanceComposition.build(sampler,cx+4096,cz,DhLodLayout.EARTH_ALPINE_2048,c,input,F,CHECK));
        assertThrows(IllegalArgumentException.class,()->DhNativeDistanceComposition.build(sampler,cx,cz,DhLodLayout.EARTH_ALPINE_2048,c,null,F,CHECK));
        var different=replacement(cap.withMaterial(pin.minBlockX()+5,h,pin.minBlockZ()+5,2));
        assertThrows(IllegalArgumentException.class,()->DhNativeDistanceComposition.build(sampler,cx,cz,DhLodLayout.EARTH_ALPINE_2048,c,different,F,CHECK));
        sampler.id="foreign";assertThrows(IllegalArgumentException.class,()->DhNativeDistanceComposition.build(sampler,cx,cz,DhLodLayout.EARTH_ALPINE_2048,c,input,F,CHECK));sampler.id=EarthAlpineDefinition.DEFINITION_IDENTITY;
        sampler.supported=false;assertThrows(IllegalArgumentException.class,()->DhNativeDistanceComposition.build(sampler,cx,cz,DhLodLayout.EARTH_ALPINE_2048,c,input,F,CHECK));sampler.supported=true;
        sampler.strata=false;assertThrows(IllegalArgumentException.class,()->DhNativeDistanceComposition.build(sampler,cx,cz,DhLodLayout.EARTH_ALPINE_2048,c,input,F,CHECK));
        var incompatible=new DhNativeDistanceTerrainData.Limits(65536,131072,256,2048,20L<<20);
        sampler.strata=true;assertThrows(IllegalArgumentException.class,()->DhNativeDistanceComposition.build(sampler,cx,cz,DhLodLayout.EARTH_ALPINE_2048,c,input,incompatible,CHECK));
        var admission=new DhNativeDistanceReplacementAdmission();assertTrue(admission.assess(cap).admitted());
        EarthReplacementCapture dense;try(var builder=new EarthReplacementCapture.Builder(pin)){
            for(long y=cap.minY();y<cap.maxYExclusive();y++)for(long z=cap.minZ();z<cap.maxZExclusive();z++)for(long x=cap.minX();x<cap.maxXExclusive();x++){
                boolean inside=CubePos.fromBlock(x,y,z).equals(pin);builder.setNext(inside?((x+y+z)&1)==0?1:0:cap.materialAt(x,y,z));
            }dense=builder.finish();
        }
        assertFalse(admission.assess(dense).admitted());var overBudget=replacement(dense);
        assertThrows(IllegalArgumentException.class,()->DhNativeDistanceComposition.build(sampler,cx,cz,DhLodLayout.EARTH_ALPINE_2048,c,overBudget,F,CHECK));
        // This pinned-source check exercises only pure CPU content and grants no runtime GPU permission.
    }
    @Test void unchangedOuterTilesKeepStableKeysWhileChangedBoundaryContentRemainsDifferent(){
        var s=source(3,FLAT);var a=plan(s,null,new CubePos(0,0,0),2);var b=plan(s,null,new CubePos(2,0,0),2);
        var ca=coverage(a);var cb=coverage(b);assertNotEquals(ca.identity(),cb.identity());
        var first=data(ca,new Sampler(FLAT)).compose(null,null,CHECK);var next=data(cb,new Sampler(FLAT)).compose(null,null,CHECK);
        int shared=0,changed=0;for(var entry:next.tiles().entrySet())if(first.tiles().containsKey(entry.getKey())){
            if(first.tiles().get(entry.getKey()).equals(entry.getValue()))shared++;else changed++;
        }
        assertTrue(shared>0,"Unchanged outer tile owners should be reusable across coverage changes");assertTrue(changed>0,"A stable key must still carry changed exact face content at the boundary");
        var otherSource=new Sampler(FLAT);otherSource.id="different-source";var other=data(ca,otherSource).compose(null,null,CHECK);
        assertTrue(Collections.disjoint(first.tiles().keySet(),other.tiles().keySet()),"Stable keys must bind source identity");
    }
}
