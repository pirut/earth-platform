package io.earthplatform.minecraft.lod;

import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Moving real Earth faces through the unchanged official CPU builder. Physical owners are CPU fakes; never GL evidence. */
@Timeout(60)
class EarthMovingOfficialPackingTest {
    @BeforeAll static void registries(){EarthRollingFarBudgetTest.registries();}
    record Packed(DhAdaptiveTerrainData.Batch batch,Map<DhGeographicLodData.TileKey,DhPreparedVertices> vertices,
                  long bytes,int quads,String sha256,String thread){}
    static final class Mesh {
        final DhPreparedVertices vertices;boolean deleted;
        Mesh(DhPreparedVertices vertices){this.vertices=vertices;}
        long bytes(){return vertices.byteCount()+24L*vertices.quads();}
    }

    @Test void movingCentersPackActualOfficialBytesOffOwnerAndReuseAbsoluteOwnersOnReturn() throws Exception {
        String jar=System.getProperty("earthplatform.lodProbeJar",System.getenv("EARTH_DH_PROBE_JAR"));
        assumeTrue(jar!=null&&!jar.isBlank(),"Explicit pinned official artifact required");
        var runtime=DhOfficialRuntime.open(Path.of(jar));var source=EarthRollingFarBudgetTest.provider().surfaceSampler();
        var pin=EarthRollingFarBudgetTest.pin(source);Thread owner=Thread.currentThread();var workerCount=new AtomicInteger();
        var executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),task->{
            workerCount.incrementAndGet();var thread=new Thread(task,"earth-moving-official-packing");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
        var live=new ArrayList<Mesh>();var packing=new java.util.concurrent.atomic.AtomicReference<Packed>();
        var ops=new DhIncrementalTileCache.ResourceOps<DhGeographicLodData.TileKey,DhTerrainGeometry.PageFace,Mesh>(){
            public long estimateBytes(List<DhTerrainGeometry.PageFace> faces){return 88L*faces.size();}
            public Mesh upload(DhGeographicLodData.TileKey key,List<DhTerrainGeometry.PageFace> faces){
                assertSame(owner,Thread.currentThread());var payload=packing.get();assertEquals(faces,payload.batch().tiles().get(key));
                var mesh=new Mesh(Objects.requireNonNull(payload.vertices().get(key)));live.add(mesh);return mesh;}
            public long bytes(Mesh mesh){return mesh.bytes();}public void close(Mesh mesh){assertSame(owner,Thread.currentThread());mesh.deleted=true;}
            public boolean deleted(Mesh mesh){return mesh.deleted;}
        };
        long maxGpuBytes=0,maxPreparedBytes=0;int maxResidentOwners=0;
        try(var cache=new DhIncrementalTileCache<>(ops,EarthMovingFarCenterBudgetTest.GPU_CAP)){
            long generation=0;String firstHash=null;Packed previous=null;int totalReused=0,totalEqualByteOwners=0;
            var route=List.of(new int[]{0,0},new int[]{64,0},new int[]{-128,0},new int[]{-128,-128},new int[]{128,128},new int[]{112,112},new int[]{-112,112},new int[]{0,0});
            for(var offset:route){
                var visit=EarthMovingFarCenterBudgetTest.visit(source,pin,offset[0],offset[1]);
                var center=visit.center();var mask=visit.mask();
                Packed packed=executor.submit(()->{
                    assertNotSame(owner,Thread.currentThread());
                    SceneSurfaceSampler measured=new SceneSurfaceSampler(){
                        public long surfaceY(long x,long z){assertNotSame(owner,Thread.currentThread());return source.surfaceY(x,z);}
                        public int argb(long x,long z,long y){assertNotSame(owner,Thread.currentThread());return source.argb(x,z,y);}
                        public boolean supportsFootprintCoverage(){return source.supportsFootprintCoverage();}
                        public boolean knownFootprint(long x,long z,long x1,long z1){assertNotSame(owner,Thread.currentThread());return source.knownFootprint(x,z,x1,z1);}
                        public String identity(){return source.identity();}public boolean supportsConcurrentReads(){return source.supportsConcurrentReads();}};
                    var batch=new DhAdaptiveTerrainData(measured,center.x(),center.z()).clip(mask);
                    EarthMovingFarCenterBudgetTest.budgets(batch);EarthMovingFarCenterBudgetTest.assertExactNoReadyOverlap(batch,mask);
                    var output=new LinkedHashMap<DhGeographicLodData.TileKey,DhPreparedVertices>();long bytes=0;int quads=0;
                    var digest=MessageDigest.getInstance("SHA-256");
                    for(var entry:batch.tiles().entrySet()){
                        byte[] raw=runtime.buildRectangles(entry.getValue());var vertices=new DhPreparedVertices(raw);
                        assertTrue(vertices.quads()<=entry.getValue().size());assertTrue(vertices.byteCount()<=4096*64);
                        byte first=vertices.copyBytes()[0];raw[0]^=0x5a;assertEquals(first,vertices.copyBytes()[0],"Detached official result");
                        digest.update(entry.getKey().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update(vertices.copyBytes());
                        output.put(entry.getKey(),vertices);bytes+=vertices.byteCount();quads+=vertices.quads();
                    }
                    return new Packed(batch,Map.copyOf(output),bytes,quads,HexFormat.of().formatHex(digest.digest()),Thread.currentThread().getName());
                }).get(20,TimeUnit.SECONDS);
                long declared=4096L+64L*packed.batch().faces()+packed.bytes();assertTrue(declared<=EarthMovingFarCenterBudgetTest.RESULT_CAP);
                assertTrue(4L*declared<=EarthMovingFarCenterBudgetTest.CACHE_CAP);assertEquals(64L*packed.quads(),packed.bytes());
                assertEquals("earth-moving-official-packing",packed.thread());maxPreparedBytes=Math.max(maxPreparedBytes,declared);
                int equalByteOwners=0;
                if(previous!=null)for(var entry:packed.batch().tiles().entrySet()){
                    if(entry.getValue().equals(previous.batch().tiles().get(entry.getKey()))){
                        assertArrayEquals(previous.vertices().get(entry.getKey()).copyBytes(),packed.vertices().get(entry.getKey()).copyBytes(),
                                "Absolute overlap with unchanged face list must retain identical official bytes after center shift");
                        equalByteOwners++;
                    }
                }
                totalEqualByteOwners+=equalByteOwners;
                packing.set(packed);assertTrue(cache.stage(generation,packed.batch().tiles()));
                assertEquals(equalByteOwners,cache.lastStageReusedOwners(),"Exact face equality determines reuse, never a center-relative tile alias");
                while(!cache.ready(generation)){
                    var progress=cache.pump(1_048_576,2,TimeUnit.SECONDS.toNanos(1));assertTrue(progress.uploadedOwners()<=2);
                    assertTrue(progress.uploadedBytes()<=1_048_576);assertTrue(progress.uploadedOwners()>0,"Hard resource caps must not deadlock an accepted bounded Earth batch");
                    assertTrue(cache.residentBytes()<=EarthMovingFarCenterBudgetTest.GPU_CAP);assertTrue(cache.residentOwners()<=128);
                    maxGpuBytes=Math.max(maxGpuBytes,cache.residentBytes());maxResidentOwners=Math.max(maxResidentOwners,cache.residentOwners());
                }
                int reused=cache.lastStageReusedOwners();totalReused+=reused;assertTrue(cache.commit(generation));
                assertEquals(packed.batch().tiles().size(),cache.currentResources().size());assertEquals(0,cache.retirementPending());
                assertEquals(cache.currentResources().size(),live.stream().filter(m->!m.deleted).count());
                if(generation==0)firstHash=packed.sha256();if(generation==route.size()-1)assertEquals(firstHash,packed.sha256(),"LRU eviction and intervening masks cannot alter the pinned source's official bytes");
                System.out.println("EARTH_MOVING_OFFICIAL generation="+generation+" center="+center+" targets="+mask.size()
                        +" faces="+packed.batch().faces()+" officialQuads="+packed.quads()+" packedBytes="+packed.bytes()+" declaredBytes="+declared
                        +" residentBytes="+cache.residentBytes()+" reused="+reused+" sha256="+packed.sha256()+" thread="+packed.thread()+" artifactSha256="+DhOfficialRuntime.ARTIFACT_SHA256);
                previous=packed;generation++;
            }
            assertTrue(totalReused>0);assertEquals(totalEqualByteOwners,totalReused);
            System.out.println("EARTH_MOVING_OFFICIAL_REUSE exactFaceAndByteOwners="+totalEqualByteOwners+" routeCases="+route.size()+" returnHash="+firstHash);
        }finally{
            executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));assertEquals(1,workerCount.get());
        }
        assertTrue(live.stream().allMatch(m->m.deleted));
        System.out.println("EARTH_MOVING_OFFICIAL_MAX declaredBytes="+maxPreparedBytes+" residentBytes="+maxGpuBytes+" residentOwners="+maxResidentOwners+" postCloseOwners=0; no GL claim");
    }
}
