package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.*;
import static io.earthplatform.minecraft.lod.DhArenaTestFixtures.*;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent adversaries; real composition/asynchronous lifecycle, fake GPU ownership only. */
@Timeout(60)
class DhArenaIndependentAdversarialTest {
    record Unit(long x,long y,long z,Direction direction,int color) {}
    record Boundary(long x,long y,long z,Direction direction,CubePos airSideCenter,CubePos solidSideCenter) {}

    @Test void everyDirectionClipsTheSolidOwnerRatherThanItsAirNeighbor() {
        var cases=List.of(
            new Boundary(15,150,15,Direction.EAST,new CubePos(2,9,0),new CubePos(-1,9,0)),
            new Boundary(16,150,15,Direction.WEST,new CubePos(-1,9,0),new CubePos(2,9,0)),
            new Boundary(15,143,15,Direction.UP,new CubePos(0,10,0),new CubePos(0,7,0)),
            new Boundary(15,144,15,Direction.DOWN,new CubePos(0,7,0),new CubePos(0,10,0)),
            new Boundary(15,150,15,Direction.SOUTH,new CubePos(0,9,2),new CubePos(0,9,-1)),
            new Boundary(15,150,16,Direction.NORTH,new CubePos(0,9,-1),new CubePos(0,9,2)));
        for(var b:cases) {
            var cap=capture((x,y,z)->DEF.editable(x,y,z)?(x==b.x&&y==b.y&&z==b.z?2:0):SOURCE.materialAt(x,y,z));
            var in=input(new Guard(),1,0,cap);
            var target=new Unit(b.x,b.y,b.z,b.direction,0xffdedede);
            assertTrue(oracle(cap).contains(target));
            for(var center:List.of(b.airSideCenter,b.solidSideCenter)) {
                var ready=mask(center);
                var batch=DhArenaComposition.build(center(center.x()),center(center.z()),DhLodLayout.ARENA_4096,ready,in.data(),5L*1024*1024,()->{});
                var actual=decode(batch,in.identity());
                var expected=oracle(cap);expected.removeIf(f->ready.contains(CubePos.fromBlock(f.x,f.y,f.z)));
                assertEquals(expected,actual,b.toString()+" / "+center);
                assertEquals(center.equals(b.airSideCenter),actual.contains(target),b.toString());
            }
        }
    }

    @Test void readonlyOwnedShellContributesFacesAndTheOuterReadOnlyHaloDoesNotOwnThem() {
        var cap=capture((x,y,z)->DEF.editable(x,y,z)?0:SOURCE.materialAt(x,y,z));
        var census=DhArenaGeometry.inspect(SOURCE,cap,()->{});
        var actual=oracle(cap);
        assertEquals(34*34*34,34*(DEF.ownedMaxYExclusive()-DEF.ownedMinY())*34);
        assertEquals(36*36*36,cap.cellCount());assertEquals(11664,cap.declaredBytes());
        assertEquals(actual.size(),census.sourceFaces());
        for(var f:actual) {
            assertTrue(f.x>=-1&&f.x<33&&f.y>=127&&f.y<161&&f.z>=-1&&f.z<33);
            assertFalse(DEF.editable(f.x,f.y,f.z));
        }
        assertTrue(actual.contains(new Unit(-1,130,5,Direction.EAST,0xff808080)));
        assertTrue(actual.contains(new Unit(32,130,5,Direction.WEST,0xff808080)));
        assertTrue(actual.contains(new Unit(5,127,5,Direction.UP,0xff808080)));
        assertTrue(actual.contains(new Unit(5,130,-1,Direction.SOUTH,0xff808080)));
        assertTrue(actual.contains(new Unit(5,130,32,Direction.NORTH,0xff808080)));
        assertEquals(64,census.nativeFaces().size());
        // This entire native cube is outside the compact read box except its last two columns.
        // It still has its complete 16x16 flat upper surface, not just a read-box intersection.
        assertEquals(256,census.nativeFaces().get(new CubePos(-1,9,-1)));
    }

    @Test void everyAdvertisedHardCapIsInclusiveAndOneMoreIsRejected() {
        int maxFaces=(int)(1_048_576L/(4*52));assertEquals(5041,maxFaces);
        var arena=new DhArenaGeometry.Census(22635,16,4096,Map.of(),Map.of(),maxFaces,maxFaces*4L*52);
        long cpu=5L*1024*1024,gpu=8L*1024*1024;
        assertTrue(new DhArenaAdmission.Census(arena,32768,64,cpu,gpu,128).admitted());
        assertFalse(new DhArenaAdmission.Census(arena,32769,64,cpu,gpu,128).admitted());
        assertFalse(new DhArenaAdmission.Census(arena,32768,65,cpu,gpu,128).admitted());
        assertFalse(new DhArenaAdmission.Census(arena,32768,64,cpu+1,gpu,128).admitted());
        assertFalse(new DhArenaAdmission.Census(arena,32768,64,cpu,gpu+1,128).admitted());
        assertFalse(new DhArenaAdmission.Census(arena,32768,64,cpu,gpu,129).admitted());
        var oversizedOwner=new DhArenaGeometry.Census(22635,16,4097,Map.of(),Map.of(),maxFaces,maxFaces*4L*52);
        assertFalse(new DhArenaAdmission.Census(oversizedOwner,32768,64,cpu,gpu,128).admitted());
        var oversizedNative=new DhArenaGeometry.Census(22635,16,4096,Map.of(),Map.of(),maxFaces+1,(maxFaces+1)*4L*52);
        assertFalse(new DhArenaAdmission.Census(oversizedNative,32768,64,cpu,gpu,128).admitted());
        var actual=new DhArenaAdmission(SOURCE).inspect(capture(),()->{});
        assertEquals(4096+11664+128L*actual.farFaces(),actual.resultBytes());
        assertEquals(88L*(32768+actual.farFaces()),actual.oldNewGpuBytes());
        assertEquals(64+actual.farOwners(),actual.oldNewOwners());
    }

    @Test void allSixtyFourWholeNativeCubesMatchAnIndependentOracleAfterScatteredEdits() {
        for(int salt=0;salt<4;salt++) {
            int seed=salt;
            var cap=capture((x,y,z)->{
                long h=(x*73856093L)^(y*19349663L)^(z*83492791L)^(seed*2654435761L);
                return DEF.editable(x,y,z)&&Math.floorMod(h,83)==0?(int)Math.floorMod(h,3):SOURCE.materialAt(x,y,z);
            });
            var census=DhArenaGeometry.inspect(SOURCE,cap,()->{});int maximum=0;long outsideCells=0;
            var expected=new TreeMap<CubePos,Integer>();
            for(int cx=-1;cx<=2;cx++)for(int cy=7;cy<=10;cy++)for(int cz=-1;cz<=2;cz++) {
                int count=0;
                for(long x=cx*16L;x<(cx+1)*16L;x++)for(long y=cy*16L;y<(cy+1)*16L;y++)for(long z=cz*16L;z<(cz+1)*16L;z++) {
                    if(!cap.containsReadCell(x,y,z))outsideCells++;
                    if(nativeMaterial(cap,x,y,z)==0)continue;
                    if(nativeMaterial(cap,x+1,y,z)==0)count++;
                    if(nativeMaterial(cap,x-1,y,z)==0)count++;
                    if(nativeMaterial(cap,x,y+1,z)==0)count++;
                    if(nativeMaterial(cap,x,y-1,z)==0)count++;
                    if(nativeMaterial(cap,x,y,z+1)==0)count++;
                    if(nativeMaterial(cap,x,y,z-1)==0)count++;
                }
                maximum=Math.max(maximum,count);expected.put(new CubePos(cx,cy,cz),count);
            }
            assertTrue(outsideCells>200_000);assertEquals(expected,census.nativeFaces());
            assertEquals(maximum,census.maxNativeFaces());assertEquals(maximum*4L*52,census.maxNativeVertexBytes());
        }
    }

    @Test void everyOuterHaloFaceAndCornerIsImmutableEvenWhereNoOwnedFaceReadsIt() {
        var points=List.of(new long[]{-2,140,5},new long[]{33,140,5},new long[]{5,126,5},new long[]{5,161,5},new long[]{5,140,-2},new long[]{5,140,33},new long[]{-2,126,-2},new long[]{33,161,33});
        for(var p:points) {
            var cap=capture((x,y,z)->x==p[0]&&y==p[1]&&z==p[2]?(SOURCE.materialAt(x,y,z)+1)%3:SOURCE.materialAt(x,y,z));
            var receipt=new DhArenaAdmission(SOURCE).assess(cap);assertFalse(receipt.admitted(),Arrays.toString(p));
            assertThrows(DhReplacementComposition.Declined.class,()->input(new Guard(),1,0,cap));
        }
    }

    @Test void aLocallySmallEditCanStillBeRejectedByItsWholeNativeMeshBeforeFarBudgets() {
        // A single 16-cube checkerboard is hidden by home readiness, but native generation
        // must nevertheless reserve every face before any edited state is persisted.
        var cap=capture((x,y,z)->x>=0&&x<16&&y>=128&&y<144&&z>=0&&z<16?(((x+y+z)&1)==0?1:0):SOURCE.materialAt(x,y,z));
        var census=new DhArenaAdmission(SOURCE).inspect(cap,()->{});
        assertTrue(census.farFaces()<=32768);assertTrue(census.farOwners()<=64);
        assertTrue(census.arena().maxNativeFaces()>5041);
        assertTrue(census.arena().maxNativeVertexBytes()>1_048_576);
        assertFalse(census.admitted());
        var in=input(new Guard(),1,0,cap);
        assertThrows(DhReplacementComposition.Declined.class,()->DhArenaComposition.build(0,0,DhLodLayout.ARENA_4096,mask(new CubePos(0,9,0)),in.data(),5L*1024*1024,()->{}));
    }

    @Test void queryResultDigestBindsEachOfTheEightIndependentRevisionsIncludingLastCube() {
        var cap=capture();var cert=certificate(cap,1,0);
        for(var position:DEF.positions()) {
            var changed=new ArrayList<>(cert.dependencies());
            for(int i=0;i<changed.size();i++) {
                var old=changed.get(i);
                if(old.position().equals(position))changed.set(i,new RollingCubeActor.CubeVersion(position,old.leaseGeneration(),1));
            }
            assertThrows(IllegalArgumentException.class,()->copyCertificate(cert,changed,cert.queryAttestation()),position.toString());
        }
        var q=cert.queryAttestation();
        assertThrows(IllegalArgumentException.class,()->new ArenaQueryAttestation(q.storeIdentity(),q.indexIdentity(),q.snapshotGeneration(),q.snapshotRootIdentity(),q.definitionIdentity(),q.layoutIdentity(),q.policyIdentity(),q.resultDigest(),7));
        var stale=capture().withMaterial(1,150,1,2);
        assertThrows(IllegalArgumentException.class,()->new DhArenaInput(SOURCE,cert,stale,new Guard()));
    }

    @Test void aSelfConsistentQueryForAForeignLayoutIsStillRejectedBeforeGuardAccess() {
        var cap=capture();var cert=certificate(cap,1,0);var q=cert.queryAttestation();
        var policy=new RollingCubeActor.ReplacementPolicy(cert.providerIdentity(),cert.materialPolicy(),cert.detailPolicy(),cert.resourceGeneration(),RollingEditPolicy.FULL_CUBE_V1);
        String foreign=ArenaQueryAttestation.policyIdentity(WORLD,cert.paletteIdentity(),policy,DEF,SOURCE.identity(),DhLodLayout.LONG_4096.identity());
        var entries=DEF.positions().stream().map(p->new ArenaReplacementIndex.Entry(p,0,foreign,cap.contentIdentity())).toList();
        var forged=new ArenaQueryAttestation(q.storeIdentity(),q.indexIdentity(),q.snapshotGeneration(),q.snapshotRootIdentity(),q.definitionIdentity(),DhLodLayout.LONG_4096.identity(),foreign,ArenaReplacementIndex.resultIdentity(entries),8);
        var wrong=copyCertificate(cert,cert.dependencies(),forged);var guard=new Guard();
        assertThrows(IllegalArgumentException.class,()->new DhArenaInput(SOURCE,wrong,cap,guard));assertEquals(0,guard.calls);
    }

    @Test void reentrantCurrentCancellationAndHistoricalReleaseCannotLeakAuthority() {
        try(var h=new DhPreparedArenaTest.Harness()) {
            var guard=new Guard();var old=input(guard,1,0);
            h.commit(1,DhPreparedArenaTest.AWAY,old);
            var retained=h.view.retainPresentation();guard.callback=()->h.view.releasePresentation(retained);
            assertFalse(h.view.drawPresented(retained,new Matrix4f(),0,145,0,RESOURCE));assertEquals(0,h.driver.draws);
            guard.callback=()->{};var nextGuard=new Guard();
            var ticket=h.prepare(2,DhPreparedArenaTest.HOME,input(nextGuard,2,0));
            nextGuard.callback=()->h.view.cancelPrepared(ticket);
            assertFalse(h.view.isPrepared(ticket));assertFalse(h.view.commitPrepared(ticket));h.view.pump();
            assertTrue(h.view.arenaReady(old.certificateIdentity()));
            assertFalse(h.view.diagnostics().get("invalidated").getAsBoolean());
        }
    }

    @Test void realQueuedArenaInputsAndLateCompletionRetainOnlyImmutableCpuDataAfterClose() throws Exception {
        var compiler=new IsolatedCompiler();var driver=new IsolatedDriver(compiler);var executor=Executors.newSingleThreadExecutor();
        var view=new DhPreparedLodView(new DhArenaSurfaceSampler(SOURCE),WORLD,EPOCH,RESOURCE,0,0,DhLodLayout.ARENA_4096,driver,executor);
        try {
            view.initialize();var guard=new Guard();var in=input(guard,1,0);
            var ticket=view.prepareCoverageArena(1,ready(new CubePos(-4,9,-4)),0,0,in);
            assertTrue(compiler.entered.await(10,TimeUnit.SECONDS));
            @SuppressWarnings("unchecked") var inputs=(Map<Object,Object>)field(view,"inputs");
            assertEquals(1,inputs.size());assertCpuGraph(inputs.values());assertCpuGraph(in.data());
            assertEquals(11664,countPackedBytes(in.data()));
            Object jobs=field(view,"jobs"),source=field(jobs,"source");assertCpuGraph(source);
            int guardCalls=guard.calls;view.close();assertTrue(inputs.isEmpty());
            assertFalse(view.commitPrepared(ticket));assertEquals(0,driver.uploads);
            compiler.release.countDown();executor.shutdown();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));
            assertEquals(guardCalls,guard.calls);assertFalse(view.isCurrent(ticket));
            assertEquals(0,view.diagnostics().get("residentPageTileOwners").getAsInt());
            assertEquals(0,view.diagnostics().get("summaryCachedItems").getAsInt());
        } finally {compiler.release.countDown();view.close();executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
    }

    private static ArenaCertificate copyCertificate(ArenaCertificate c,List<RollingCubeActor.CubeVersion> dependencies,ArenaQueryAttestation query) {
        return new ArenaCertificate(c.world(),c.epoch(),c.resourceGeneration(),c.storeIdentity(),c.indexIdentity(),c.providerIdentity(),c.paletteIdentity(),c.materialPolicy(),c.detailPolicy(),c.generation(),c.definition(),dependencies,c.captureIdentity(),query);
    }
    private static int nativeMaterial(ArenaCapture capture,long x,long y,long z) {
        return x>=-2&&x<34&&y>=126&&y<162&&z>=-2&&z<34?capture.materialAt(x,y,z):SOURCE.materialAt(x,y,z);
    }
    private static Set<Unit> oracle(ArenaCapture cap) {
        var faces=new HashSet<Unit>();
        for(long x=-1;x<33;x++)for(long y=127;y<161;y++)for(long z=-1;z<33;z++) {
            int material=cap.materialAt(x,y,z);if(material==0)continue;int color=material==1?0xff808080:0xffdedede;
            if(cap.materialAt(x+1,y,z)==0)faces.add(new Unit(x,y,z,Direction.EAST,color));
            if(cap.materialAt(x-1,y,z)==0)faces.add(new Unit(x,y,z,Direction.WEST,color));
            if(cap.materialAt(x,y+1,z)==0)faces.add(new Unit(x,y,z,Direction.UP,color));
            if(cap.materialAt(x,y-1,z)==0)faces.add(new Unit(x,y,z,Direction.DOWN,color));
            if(cap.materialAt(x,y,z+1)==0)faces.add(new Unit(x,y,z,Direction.SOUTH,color));
            if(cap.materialAt(x,y,z-1)==0)faces.add(new Unit(x,y,z,Direction.NORTH,color));
        }return faces;
    }
    private static Set<Unit> decode(DhReplacementComposition.Result result,String identity) {
        var out=new HashSet<Unit>();result.batch().tiles().forEach((key,faces)->{
            if(!identity.equals(key.replacementIdentity()))return;
            for(var f:faces) {
                assertEquals(1,f.width());assertEquals(1,f.height());long x=key.originX()+f.x(),y=f.pageY2048()*2048L+f.y(),z=key.originZ()+f.z();
                if(f.direction()==Direction.EAST)x--;if(f.direction()==Direction.UP)y--;if(f.direction()==Direction.SOUTH)z--;
                assertTrue(out.add(new Unit(x,y,z,f.direction(),f.argb())),"duplicated owned face");
            }
        });return out;
    }
    private static Object field(Object value,String name)throws Exception {var f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private static Set<Object> cpuGraph(Object root)throws Exception {
        Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());var queue=new ArrayDeque<Object>();queue.add(root);
        while(!queue.isEmpty()) {
            Object value=queue.remove();if(!seen.add(value))continue;Class<?> type=value.getClass();
            if(value instanceof Map<?,?> m){m.forEach((k,v)->{if(k!=null)queue.add(k);if(v!=null)queue.add(v);});continue;}
            if(value instanceof Iterable<?> all){for(Object item:all)if(item!=null)queue.add(item);continue;}
            if(value instanceof AtomicReference<?> ref){if(ref.get()!=null)queue.add(ref.get());continue;}
            if(type.isArray()){if(!type.componentType().isPrimitive())for(Object item:(Object[])value)if(item!=null)queue.add(item);continue;}
            if(!type.getName().startsWith("io.earthplatform."))continue;
            for(var f:type.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())&&!f.getType().isPrimitive()){f.setAccessible(true);Object item=f.get(value);if(item!=null)queue.add(item);}
        }return seen;
    }
    private static void assertCpuGraph(Object root)throws Exception {
        for(Object value:cpuGraph(root)) {
            assertFalse(value instanceof DhArenaInput,"worker retained authority wrapper");
            assertFalse(value instanceof DhArenaInput.AuthorityGuard,"worker retained authority guard");
            assertFalse(value instanceof DhPreparedLodView,"worker retained view");
            assertFalse(value instanceof DhGeographicLodView.Driver,"worker retained GPU driver");
            assertFalse(value instanceof RollingCubeActor,"worker retained actor");
            assertFalse(value.getClass().getName().endsWith("$Pending"),"worker retained pending owner");
        }
    }
    private static int countPackedBytes(Object root)throws Exception {int count=0;for(Object value:cpuGraph(root))if(value instanceof byte[] bytes)count+=bytes.length;return count;}
    private static final class IsolatedCompiler implements DhGeographicLodView.CpuCompiler {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicBoolean first=new AtomicBoolean(true);
        public DhPreparedVertices compile(List<PageFace> faces,Runnable cancellation) {
            if(first.compareAndSet(true,false)){entered.countDown();boolean done=false;while(!done)try{done=release.await(15,TimeUnit.SECONDS);if(!done)throw new AssertionError("gate timed out");}catch(InterruptedException ignored){}}
            return new DhPreparedVertices(new byte[faces.size()*64]);
        }
    }
    private static final class IsolatedDriver implements DhGeographicLodView.Driver {
        final Thread owner=Thread.currentThread();final IsolatedCompiler compiler;boolean deleted;int uploads;
        IsolatedDriver(IsolatedCompiler compiler){this.compiler=compiler;}
        public void checkRenderThread(){assertSame(owner,Thread.currentThread());}
        public String renderer(){return "independent-arena-fake";}public DhGeographicLodView.State captureState(){return ()->{};}
        public void initialize(){}public DhGeographicLodView.CpuCompiler cpuCompiler(){return compiler;}
        public DhGeographicLodView.Mesh upload(DhGeographicLodData.TileKey key,List<PageFace> faces){throw new AssertionError("owner CPU packing");}
        public DhGeographicLodView.Mesh uploadPrepared(DhGeographicLodData.TileKey key,List<PageFace> faces,DhPreparedVertices bytes){uploads++;throw new AssertionError("cancelled private candidate uploaded");}
        public DhGeographicLodView.DrawStats draw(Map<DhGeographicLodData.TileKey,DhGeographicLodView.Mesh> meshes,Matrix4f matrix,double x,double y,double z){throw new AssertionError("unexpected draw");}
        public void close(){deleted=true;}public boolean deleted(){return deleted;}
    }
}
