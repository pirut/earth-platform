package io.earthplatform.minecraft.lod;

import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
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

/** Real Earth faces through the unchanged official CPU builder. Accounted mesh owners are CPU fakes, never GL. */
@Timeout(60)
class EarthRollingOfficialPackingTest {
    @BeforeAll static void registries(){EarthRollingFarBudgetTest.registries();}
    record Packed(DhAdaptiveTerrainData.Batch batch,Map<DhGeographicLodData.TileKey,DhPreparedVertices> vertices,
                  long bytes,int quads,String sha256,String thread){}
    static final class Mesh {
        final DhPreparedVertices vertices;boolean deleted;
        Mesh(DhPreparedVertices vertices){this.vertices=vertices;}
        long bytes(){return vertices.byteCount()+24L*vertices.quads();}
    }

    @Test void realSlopesPackDeterministicallyOffOwnerAndKeepCurrentPlusChangedPhysicalBytesBounded() throws Exception {
        String jar=System.getProperty("earthplatform.lodProbeJar",System.getenv("EARTH_DH_PROBE_JAR"));
        assumeTrue(jar!=null&&!jar.isBlank(),"Explicit pinned official artifact required");
        var runtime=DhOfficialRuntime.open(Path.of(jar));var source=EarthRollingFarBudgetTest.provider().surfaceSampler();
        var pin=EarthRollingFarBudgetTest.pin(source);Thread owner=Thread.currentThread();var workerCount=new AtomicInteger();
        var executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),task->{
            workerCount.incrementAndGet();var thread=new Thread(task,"earth-rolling-official-packing");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
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
        try(var cache=new DhIncrementalTileCache<>(ops,EarthRollingFarBudgetTest.GPU_CAP)){
            long generation=0;String firstHash=null;
            for(var offset:List.of(new int[]{0,0},new int[]{2,3},new int[]{0,16},new int[]{16,-16},new int[]{0,0})){
                var center=EarthRollingFarBudgetTest.center(source,pin,offset[0],offset[1]);
                var mask=RollingCubePlan.stationary(pin,center).committedTargets();
                Packed packed=executor.submit(()->{
                    assertNotSame(owner,Thread.currentThread());
                    SceneSurfaceSampler measured=new SceneSurfaceSampler(){
                        public long surfaceY(long x,long z){assertNotSame(owner,Thread.currentThread());return source.surfaceY(x,z);}
                        public int argb(long x,long z,long y){assertNotSame(owner,Thread.currentThread());return source.argb(x,z,y);}
                        public String identity(){return source.identity();}public boolean supportsConcurrentReads(){return source.supportsConcurrentReads();}};
                    var batch=new DhAdaptiveTerrainData(measured,EarthRollingFarBudgetTest.X,EarthRollingFarBudgetTest.Z).clip(mask);
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
                long declared=4096L+64L*packed.batch().faces()+packed.bytes();assertTrue(declared<=EarthRollingFarBudgetTest.RESULT_CAP);
                assertTrue(4L*declared<=EarthRollingFarBudgetTest.CACHE_CAP);assertEquals(64L*packed.quads(),packed.bytes());
                assertEquals("earth-rolling-official-packing",packed.thread());maxPreparedBytes=Math.max(maxPreparedBytes,declared);
                packing.set(packed);assertTrue(cache.stage(generation,packed.batch().tiles()));
                while(!cache.ready(generation)){
                    var progress=cache.pump(1_048_576,2,TimeUnit.SECONDS.toNanos(1));assertTrue(progress.uploadedOwners()<=2);
                    assertTrue(progress.uploadedBytes()<=1_048_576);assertTrue(progress.uploadedOwners()>0,"Hard resource caps must not deadlock an accepted bounded Earth batch");
                    assertTrue(cache.residentBytes()<=EarthRollingFarBudgetTest.GPU_CAP);assertTrue(cache.residentOwners()<=128);
                    maxGpuBytes=Math.max(maxGpuBytes,cache.residentBytes());maxResidentOwners=Math.max(maxResidentOwners,cache.residentOwners());
                }
                int reused=cache.lastStageReusedOwners();assertTrue(cache.commit(generation));
                assertEquals(packed.batch().tiles().size(),cache.currentResources().size());assertEquals(0,cache.retirementPending());
                assertEquals(cache.currentResources().size(),live.stream().filter(m->!m.deleted).count());
                if(generation==0)firstHash=packed.sha256();if(generation==4)assertEquals(firstHash,packed.sha256(),"LRU eviction and intervening masks cannot alter the pinned source's official bytes");
                System.out.println("EARTH_ROLLING_OFFICIAL generation="+generation+" center="+center+" targets="+mask.size()
                        +" faces="+packed.batch().faces()+" officialQuads="+packed.quads()+" packedBytes="+packed.bytes()+" declaredBytes="+declared
                        +" residentBytes="+cache.residentBytes()+" reused="+reused+" sha256="+packed.sha256()+" thread="+packed.thread()+" artifactSha256="+DhOfficialRuntime.ARTIFACT_SHA256);
                generation++;
            }
        }finally{
            executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));assertEquals(1,workerCount.get());
        }
        assertTrue(live.stream().allMatch(m->m.deleted));
        System.out.println("EARTH_ROLLING_OFFICIAL_MAX declaredBytes="+maxPreparedBytes+" residentBytes="+maxGpuBytes+" residentOwners="+maxResidentOwners+" postCloseOwners=0; no GL claim");
    }
}
