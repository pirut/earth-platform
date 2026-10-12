package io.earthplatform.minecraft.lod;

import com.google.gson.JsonObject;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission;
import io.earthplatform.minecraft.coregate.RollingDistanceBodyBinding;
import io.earthplatform.minecraft.scene.SceneReadyCube;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.joml.Matrix4f;

/**
 * Explicit immutable-layout, moving-center prepare/commit adapter for the immutable-base rolling experiment.
 * A ticket proves complete private far resources only. The controller must attest complete,
 * current native meshes (or the explicit distance body capability) for EXACTLY the supplied readiness list, obtain server ALLOW, then
 * revalidate those meshes and commit this ticket under its native/far/collision frame fence.
 * Neither admission nor CPU completion proves native readiness. No method except commitPrepared
 * publishes geometry. A new full-authority ticket may privately alias the current immutable
 * geometry only when the exact sorted XYZ mask is unchanged in this same final source/world/
 * session/resource/center context. It skips CPU regeneration, not server allowance or authority
 * validation, and adds no payload bytes, job-cache entry or GPU owners.
 * An optional single immutable voxel replacement has distinct server-certificate authority.
 * Its captured halo is CPU content, never a native-ready lease or collision authority.
 * All GL operations require the constructing thread. Invalidation/cancellation can be requested
 * from another thread and linearize under the same fence as owner operations.
 */
public final class DhPreparedLodView implements AutoCloseable {
    private static final long UPLOAD_BYTES=1_048_576, UPLOAD_NANOS=2_000_000, MAX_GPU_BYTES=8L*1024*1024;
    private static final Executor BACKGROUND=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2),r->{Thread t=new Thread(r,"earth-prepared-dh-summary");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Thread owner=Thread.currentThread();
    private final Object fence=new Object(), viewIdentity=new Object();
    private SceneSurfaceSampler sampler;
    private final String sourceIdentity;
    private final boolean footprintCoverage,stratifiedSides;
    private final DhLodLayout layout;
    private final DistanceConfig distanceConfig;
    private final io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Reservation distanceCpu;
    private io.earthplatform.minecraft.visual.NativeVisualPublication publishedVisual;
    // Owner-only capabilities: never referenced by Input, Geometry, or background work.
    private RollingDistanceBodyBinding.NativeJoined publishedBody,closingBody;
    private final WorldIdentity world;
    private final long epoch,resources,initialCenterX,initialCenterZ;
    private long publishedCenterX,publishedCenterZ,recenterCommits;
    private final DhGeographicLodView.Driver driver;
    private final java.util.concurrent.atomic.AtomicReference<DhGeographicLodView.CpuCompiler> compiler=new java.util.concurrent.atomic.AtomicReference<>();
    private RenderCpuProfiler profiler=new RenderCpuProfiler(false);
    private final DhIncrementalTileCache<DhGeographicLodData.TileKey,DhTerrainGeometry.PageFace,DhGeographicLodView.Mesh> cache;
    private final BoundedLodSummaryJobs<Geometry> jobs;
    private final GeometrySource cpuSource;
    private final ConcurrentMap<LodSummaryKey,Input> inputs=new ConcurrentHashMap<>();
    private Geometry activeUploadGeometry,geometry;
    private ReplacementAuthority publishedReplacement;
    private String replacementWorkspaceIdentity, replacementFlatPolicyIdentity, arenaDefinitionIdentity;
    private Boolean indexedReplacementSet;
    private CubePos originalReplacementCube;
    private long replacementDeclines;
    private String lastReplacementDecline="";
    private DhGeographicLodView.State pendingStateRestore;
    private Pending pending;
    private PresentationTicket presentation;
    private long presentationRetains,presentationReleases,presentationFrames,rejectedPresentationDraws;
    private long sameGeometryPreparations,sameGeometryCommits;
    private boolean firstFencePresentationRetained;
    private long firstFencePresentationGeneration=-1;
    private List<SceneReadyCube> coverage=List.of();
    private long coverageGeneration=-1,highestGeneration=-1,requestSerial;
    private boolean initializationStarted,initialized,invalidated,closed,deleted,busy;
    private String lastFailure="";
    private long uploadedBytes,uploadedOwners,reusedOwners,commits,rejected,drawCalls,vertices,lastDrawNanos,lastPumpNanos,maxPumpNanos,uploadOverruns;
    private int peakOwners;
    private long peakBytes;
    private long preClosePendingGeneration=-1,preCloseUploadedOwners,preClosePendingCenterX,preClosePendingCenterZ;
    private boolean preClosePendingCpu,preClosePendingStage,preClosePendingSameGeometry;
    private int preCloseResidentOwners;
    private boolean firstFenceCaptured,firstFencePendingCpu,firstFencePendingStage,firstFencePendingSameGeometry;
    private long firstFencePendingGeneration=-1,firstFencePendingCenterX,firstFencePendingCenterZ,firstFencePublishedCenterX,firstFencePublishedCenterZ;
    private int firstFenceRunning,firstFenceQueued,firstFenceResidentOwners,firstFencePublishedOwners;
    private int ownerResidentOwners,ownerPublishedOwners;

    /** Explicit additive distance profile. Legacy constructors retain every old cap. */
    public record DistanceConfig(String policy,DhNativeDistanceTerrainData.Limits geometry,long maxGpuBytes,
                                 int maxResidentOwners,long maxCachedBytes) {
        public DistanceConfig {
            Objects.requireNonNull(policy);Objects.requireNonNull(geometry);
            if(!policy.equals(DhNativeDistanceBudget.POLICY)||maxGpuBytes<=0||maxGpuBytes>DhNativeDistanceBudget.MAX_GPU_BYTES
                    ||maxResidentOwners<geometry.maxOwners()||maxResidentOwners>DhNativeDistanceBudget.MAX_RESIDENT_OWNERS
                    ||geometry.maxCells()>DhNativeDistanceBudget.MAX_CELLS||geometry.maxFaces()>DhNativeDistanceBudget.MAX_FACES
                    ||geometry.maxOwners()>DhNativeDistanceBudget.MAX_OWNERS||geometry.maxPageFaces()!=4096||geometry.maxResultBytes()>DhNativeDistanceBudget.MAX_RESULT_BYTES
                    ||maxCachedBytes!=0)throw new IllegalArgumentException("Bounded explicit native-distance far profile required");
        }
        public String identity(){return hash(policy+"\n"+geometry+"\n"+maxGpuBytes+"\n"+maxResidentOwners+"\n"+maxCachedBytes);}
    }
    /** Unforgeable identity, scoped to one exact view, request, source and coverage attestation. */
    public static final class CoverageTicket {
        private final Object view;
        private final long request,generation,epoch,resources,centerX,centerZ;
        private final String source,readyIdentity,layoutIdentity;
        private boolean cancelled,consumed;
        private String replacementIdentity="",declineReason="";
        private CoverageTicket(Object view,long request,long generation,long epoch,long resources,String source,String readyIdentity,Center center,DhLodLayout layout){
            centerX=center.x();centerZ=center.z();layoutIdentity=layout.identity();this.view=view;this.request=request;this.generation=generation;this.epoch=epoch;this.resources=resources;this.source=source;this.readyIdentity=readyIdentity;
        }
    }
    /**
     * One bounded visual-only reference to the last committed batch. It owns no extra GPU
     * resources and never attests current collision, readiness or authoritative edit state.
     * The caller must retain the matching old native presentation under its paired-frame fence.
     * close() is CPU-only, thread-safe and idempotent. Replacement/reload/close revoke the token.
     */
    public static final class PresentationTicket implements AutoCloseable {
        private final DhPreparedLodView owner;
        private final Object view;
        private final long epoch,resources,generation,centerX,centerZ;
        private final String source,layoutIdentity;
        private List<SceneReadyCube> exactReady;
        private ReplacementAuthority replacement;
        private io.earthplatform.minecraft.visual.NativeVisualPublication visual;
        private RollingDistanceBodyBinding.NativeJoined body;
        private RollingDistanceBodyBinding.History bodyHistory;
        private boolean released;
        private PresentationTicket(DhPreparedLodView owner,RollingDistanceBodyBinding.History history){
            this.owner=owner;body=owner.publishedBody;bodyHistory=history;visual=owner.publishedVisual;replacement=owner.publishedReplacement;view=owner.viewIdentity;epoch=owner.epoch;resources=owner.resources;
            layoutIdentity=owner.layout.identity();generation=owner.coverageGeneration;centerX=owner.publishedCenterX;centerZ=owner.publishedCenterZ;source=owner.sourceIdentity;exactReady=owner.coverage;
        }
        @Override public void close(){owner.releasePresentation(this);}
    }
    /** Canonical immutable job/ticket context; validate all footprint arithmetic before admission. */
    private record Center(long x,long z) {
        static Center normalized(long x,long z,DhLodLayout layout){
            return new Center(layout.normalizeCenter(x),layout.normalizeCenter(z));
        }
    }
    private record ReplacementPayload(DhReplacementInput.Data single,DhReplacementSetInput.Data set,DhArenaInput.Data arena) {
        ReplacementPayload(DhReplacementInput.Data single,DhReplacementSetInput.Data set){this(single,set,null);}
        String identity(){return arena!=null?arena.identity():set!=null?set.identity():single.identity();}
        long declaredBytes(){return arena!=null?arena.declaredBytes():set!=null?set.declaredBytes():single.declaredBytes();}
        List<DhTerrainGeometry.Volume> volumes(){return arena!=null?List.of(DhArenaGeometry.volume(arena.capture().definition())):set!=null?set.members().stream().map(v->v.capture().ownedVolume()).toList():List.of(single.capture().ownedVolume());}
    }
    private record ReplacementAuthority(DhReplacementInput single,DhReplacementSetInput set,DhArenaInput arena) {
        ReplacementAuthority(DhReplacementInput single,DhReplacementSetInput set){this(single,set,null);}
        ReplacementPayload data(){return arena!=null?new ReplacementPayload(null,null,arena.data()):set!=null?new ReplacementPayload(null,set.data()):new ReplacementPayload(single.data(),null);}
        String identity(){return arena!=null?arena.identity():set!=null?set.identity():single.identity();}
        boolean isCurrent(){return arena!=null?arena.isCurrent():set!=null?set.isCurrent():single.isCurrent();}
        boolean permitsRetainedPresentation(){return arena!=null?arena.permitsRetainedPresentation():set!=null?set.permitsRetainedPresentation():single.permitsRetainedPresentation();}
    }
    private record Input(List<CubePos> mask,Center center,DhLodLayout layout,ReplacementPayload replacement,
                         io.earthplatform.minecraft.visual.SourceSurfaceCoverage visualCoverage,DistanceConfig distanceConfig){}
    private record Geometry(DhAdaptiveTerrainData.Batch batch,Map<DhGeographicLodData.TileKey,DhPreparedVertices> vertices,
                            long cpuNanos,long builderNanos,long preparedBytes,String compilerThread,Center center,String layoutIdentity,
                            ReplacementPayload replacement,int replacementSourceFaces,int replacementVisibleFaces,int replacementOwners) implements BoundedLodSummaryJobs.Payload {
        public long declaredBytes(){return 4096L+batch.faces()*64L+preparedBytes+(replacement==null?0:replacement.declaredBytes());}
    }
    private final class Pending {
        final ReplacementAuthority replacement;
        final io.earthplatform.minecraft.visual.NativeVisualPublication visual;
        final RollingDistanceBodyBinding.NativeJoined body;
        final CoverageTicket ticket;final List<SceneReadyCube> ready;final Input input;final LodSummaryKey key;final LodJobStamp stamp;
        // This is a borrowed immutable alias to CURRENT committed content, never a cached
        // previous-world payload or a second byte allocation. Authority remains in ticket/ready.
        final Geometry reuseBase;final long reuseBaseGeneration;
        BoundedLodSummaryJobs<Geometry>.Lease lease;Geometry payload;boolean staged;
        Pending(long request,long generation,List<SceneReadyCube> ready,Center center,ReplacementAuthority replacement,io.earthplatform.minecraft.visual.NativeVisualPublication visual,RollingDistanceBodyBinding.NativeJoined body){
            this.body=body;this.replacement=replacement;this.visual=visual;this.ready=ready;input=new Input(ready.stream().map(SceneReadyCube::position).toList(),center,layout,replacement==null?null:replacement.data(),visual==null?null:visual.coverage(),distanceConfig);
            boolean same=(visual==null?publishedVisual==null:publishedVisual!=null&&visual.coverage().identity().equals(publishedVisual.coverage().identity()))&&geometry!=null&&Objects.equals(replacementIdentity(input.replacement()),replacementIdentity(geometry.replacement()))&&coverageGeneration>=0&&geometry.center().equals(center)&&geometry.layoutIdentity().equals(layout.identity())&&input.mask().equals(coverage.stream().map(SceneReadyCube::position).toList());
            reuseBase=same?geometry:null;reuseBaseGeneration=same?coverageGeneration:-1;
            String identity=hash("multiresolution-footprint-v1:"+layout.identity()+":"+footprintCoverage+":"+ready+(replacement==null?"":":replacement-v1:"+replacement.identity())+(visual==null?"":":native-distance:"+visual.coverage().identity()+":"+visual.stamp().identity()+":"+distanceConfig.identity()));
            ticket=new CoverageTicket(viewIdentity,request,generation,epoch,resources,sourceIdentity,identity,center,layout);ticket.replacementIdentity=replacementIdentity(input.replacement());
            // editRevision is immutable-base revision ZERO, never max(near cube revisions).
            // Every lease/revision/XYZ/world/epoch/resource attestation is in the variant digest.
            key=new LodSummaryKey(world,sourceIdentity,new LodSummaryKey.Volume(center.x()-layout.radiusBlocks(),layout.minNativeY(),center.z()-layout.radiusBlocks(),center.x()+layout.radiusBlocks(),layout.maxNativeYExclusive(),center.z()+layout.radiusBlocks()),Integer.numberOfTrailingZeros(layout.outerCellSize()),0,identity);
            stamp=new LodJobStamp(epoch,request,resources);
        }
    }

    /** The caller must retain this object before initialize(), including on initialization failure. */
    public DhPreparedLodView(SceneSurfaceSampler sampler,WorldIdentity world,long epoch,long resourceGeneration,long centerX,long centerZ){
        this(sampler,world,epoch,resourceGeneration,centerX,centerZ,DhLodLayout.FIXED_512);
    }
    /** The immutable common-side layout is part of every ticket, payload and content key. */
    public DhPreparedLodView(SceneSurfaceSampler sampler,WorldIdentity world,long epoch,long resourceGeneration,long centerX,long centerZ,DhLodLayout layout){
        this(sampler,world,epoch,resourceGeneration,centerX,centerZ,layout,
                new DhGeographicLodView.OpenGlDriver((layout==DhLodLayout.LONG_4096||layout==DhLodLayout.ARENA_4096)?16384:8192,(layout==DhLodLayout.LONG_4096||layout==DhLodLayout.ARENA_4096)?32768:8192),BACKGROUND);
    }
    /** Explicit already-installed runtime. No diagnostic loader fallback is reachable from this path. */
    public DhPreparedLodView(SceneSurfaceSampler sampler,WorldIdentity world,long epoch,long resourceGeneration,long centerX,long centerZ,
                             DhLodLayout layout,io.earthplatform.compat.dh.InstalledDhResolver.Runtime installedRuntime){
        this(sampler,world,epoch,resourceGeneration,centerX,centerZ,layout,
                new DhGeographicLodView.OpenGlDriver((layout==DhLodLayout.LONG_4096||layout==DhLodLayout.ARENA_4096)?16384:8192,(layout==DhLodLayout.LONG_4096||layout==DhLodLayout.ARENA_4096)?32768:8192,
                        Objects.requireNonNull(installedRuntime,"Resolved installed DH runtime required")),BACKGROUND);
    }
    /** Explicit borrowed Iris pass. Geometry/staging authority is identical to the installed stock path. */
    public DhPreparedLodView(SceneSurfaceSampler sampler,WorldIdentity world,long epoch,long resourceGeneration,long centerX,long centerZ,
                             DhLodLayout layout,io.earthplatform.compat.dh.InstalledDhResolver.Runtime installedRuntime,
                             io.earthplatform.compat.dh.shader.IrisShaderFarBackend shaderBackend){
        this(sampler,world,epoch,resourceGeneration,centerX,centerZ,layout,
                new DhGeographicLodView.OpenGlDriver((layout==DhLodLayout.LONG_4096||layout==DhLodLayout.ARENA_4096)?16384:8192,(layout==DhLodLayout.LONG_4096||layout==DhLodLayout.ARENA_4096)?32768:8192,
                        Objects.requireNonNull(installedRuntime),Objects.requireNonNull(shaderBackend)),BACKGROUND);
    }
    public DhPreparedLodView(SceneSurfaceSampler sampler,WorldIdentity world,long epoch,long resourceGeneration,long centerX,long centerZ,
            DhLodLayout layout,io.earthplatform.compat.dh.InstalledDhResolver.Runtime installedRuntime,
            io.earthplatform.compat.dh.shader.IrisShaderFarBackend shaderBackend,DistanceConfig distanceConfig){
        this(sampler,world,epoch,resourceGeneration,centerX,centerZ,layout,
                new DhGeographicLodView.OpenGlDriver(8192,8192,Objects.requireNonNull(installedRuntime),Objects.requireNonNull(shaderBackend),true),
                BACKGROUND,Objects.requireNonNull(distanceConfig));
    }
    DhPreparedLodView(SceneSurfaceSampler sampler,WorldIdentity world,long epoch,long resourceGeneration,long x,long z,DhGeographicLodView.Driver driver,Executor executor){
        this(sampler,world,epoch,resourceGeneration,x,z,DhLodLayout.FIXED_512,driver,executor);
    }
    DhPreparedLodView(SceneSurfaceSampler sampler,WorldIdentity world,long epoch,long resourceGeneration,long x,long z,DhLodLayout layout,DhGeographicLodView.Driver driver,Executor executor){
        this(sampler,world,epoch,resourceGeneration,x,z,layout,driver,executor,null);
    }
    DhPreparedLodView(SceneSurfaceSampler sampler,WorldIdentity world,long epoch,long resourceGeneration,long x,long z,DhLodLayout layout,DhGeographicLodView.Driver driver,Executor executor,DistanceConfig distanceConfig){
        this.distanceConfig=distanceConfig;
        require(distanceConfig==null||layout==DhLodLayout.EARTH_ALPINE_2048,"Native-distance profile requires exact Earth layout");
        this.layout=Objects.requireNonNull(layout,"immutable layout");
        this.sampler=Objects.requireNonNull(sampler);this.world=Objects.requireNonNull(world);this.driver=Objects.requireNonNull(driver);driver.checkRenderThread();
        require(sampler.supportsConcurrentReads(),"Explicit thread-safe immutable source required");
        sourceIdentity=Objects.requireNonNull(sampler.identity());footprintCoverage=sampler.supportsFootprintCoverage();stratifiedSides=sampler.supportsStratifiedSides();require(world.datasetVersion().equals(sourceIdentity),"Pinned shared source identity required");
        require(epoch>=0&&resourceGeneration>0,"Valid scene ownership required");this.epoch=epoch;resources=resourceGeneration;
        require(!layout.requiresFootprintCoverage()||footprintCoverage,"Long layout requires complete source footprint coverage");
        Center initial=Center.normalized(x,z,layout);
        initialCenterX=publishedCenterX=initial.x();initialCenterZ=publishedCenterZ=initial.z();
        cache=new DhIncrementalTileCache<>(new DhIncrementalTileCache.ResourceOps<>(){
            public long estimateBytes(List<DhTerrainGeometry.PageFace> faces){return distanceConfig==null?faces.size()*88L:DhNativeDistanceBudget.storage(faces.size()).chargedBytes();}
            public DhGeographicLodView.Mesh upload(DhGeographicLodData.TileKey key,List<DhTerrainGeometry.PageFace> faces){
                Geometry prepared=Objects.requireNonNull(activeUploadGeometry,"Only the retained prepared payload may upload");
                require(faces.equals(prepared.batch().tiles().get(key)),"Prepared bytes must match exact faces");
                return driver.uploadPrepared(key,faces,Objects.requireNonNull(prepared.vertices().get(key)));
            }
            public long bytes(DhGeographicLodView.Mesh mesh){return mesh.totalBytes();}
            public void close(DhGeographicLodView.Mesh mesh){mesh.close();}
            public boolean deleted(DhGeographicLodView.Mesh mesh){return mesh.deleted();}
        },distanceConfig==null?MAX_GPU_BYTES:distanceConfig.maxGpuBytes(),
          distanceConfig==null?64:distanceConfig.geometry().maxOwners(),distanceConfig==null?128:distanceConfig.maxResidentOwners(),System::nanoTime,distanceConfig!=null);
        cpuSource=new GeometrySource(sampler,inputs,owner,compiler);
        jobs=new BoundedLodSummaryJobs<>(cpuSource,Objects.requireNonNull(executor),distanceConfig==null
                ?new BoundedLodSummaryJobs.Limits(1,1,1,4,20L*1024*1024,5L*1024*1024)
                :new BoundedLodSummaryJobs.Limits(1,1,1,0,0,distanceConfig.geometry().maxResultBytes()),distanceConfig!=null);
        // No source/compiler work is admitted before this global all-generation reservation.
        distanceCpu=distanceConfig==null?null:io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.global().reserve(
                io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.DH_RESULTS,DhNativeDistanceBudget.MAX_CPU_RESERVATION_BYTES);
    }
    /** Cancellation-ignoring work cannot retain a View, authority guard, GL driver or host session. */
    private static final class GeometrySource implements BoundedLodSummaryJobs.Source<Geometry> {
        private SceneSurfaceSampler sampler;
        private final ConcurrentMap<LodSummaryKey,Input> inputs;
        private final Thread owner;
        private final java.util.concurrent.atomic.AtomicReference<DhGeographicLodView.CpuCompiler> compiler;
        GeometrySource(SceneSurfaceSampler sampler,ConcurrentMap<LodSummaryKey,Input> inputs,Thread owner,java.util.concurrent.atomic.AtomicReference<DhGeographicLodView.CpuCompiler> compiler){this.sampler=sampler;this.inputs=inputs;this.owner=owner;this.compiler=compiler;}
        void releaseSourceAfterDrain(){sampler=null;}
        public CompletionStage<Geometry> load(LodSummaryKey key,BoundedLodSummaryJobs.Cancellation cancel,long maxBytes){
            Input input=Objects.requireNonNull(inputs.get(key),"Retained bounded input required");
            cancel.throwIfCancelled();require(Thread.currentThread()!=owner,"Official CPU preparation must run off render owner");
            long start=System.nanoTime();
            var replacement=input.replacement();
            var composed=input.visualCoverage()!=null
                    ?DhNativeDistanceComposition.build(sampler,input.center().x(),input.center().z(),input.layout(),input.visualCoverage(),Objects.requireNonNull(replacement).single(),input.distanceConfig().geometry(),cancel::throwIfCancelled)
                    :replacement!=null&&replacement.arena()!=null
                    ?DhArenaComposition.build(input.center().x(),input.center().z(),input.layout(),input.mask(),replacement.arena(),maxBytes,cancel::throwIfCancelled)
                    :replacement!=null&&replacement.set()!=null
                    ?DhReplacementSetComposition.build(replacement.set().policy(),input.center().x(),input.center().z(),input.layout(),input.mask(),replacement.set().members(),maxBytes,cancel::throwIfCancelled)
                    :DhReplacementComposition.build(sampler,input.center().x(),input.center().z(),input.layout(),input.mask(),replacement==null?null:replacement.single(),maxBytes,cancel::throwIfCancelled);
            var batch=composed.batch();
            long cpu=System.nanoTime()-start;
            if(4096L+batch.faces()*128L+(input.replacement()==null?0:input.replacement().declaredBytes())>maxBytes)throw new IllegalArgumentException("Prepared geometry budget exceeded before packing");
            Map<DhGeographicLodData.TileKey,DhPreparedVertices> prepared=new LinkedHashMap<>();long bytes=0,built=System.nanoTime();
            for(var entry:batch.tiles().entrySet()){
                cancel.throwIfCancelled();var packed=Objects.requireNonNull(compiler.get(),"Initialized isolated CPU compiler").compile(entry.getValue(),cancel::throwIfCancelled);
                require(packed.quads()<=entry.getValue().size(),"Official quad budget");
                bytes=Math.addExact(bytes,packed.byteCount());prepared.put(entry.getKey(),packed);
            }
            cancel.throwIfCancelled();Geometry result=new Geometry(batch,Map.copyOf(prepared),cpu,System.nanoTime()-built,bytes,Thread.currentThread().getName(),input.center(),input.layout().identity(),input.replacement(),composed.replacementSourceFaces(),composed.replacementVisibleFaces(),composed.replacementOwners());
            require(result.declaredBytes()<=maxBytes,"Bounded prepared geometry payload");return CompletableFuture.completedFuture(result);
        }
    }
    /** Future cleanup holds immutable CPU input only, never Pending or its enclosing owner. */
    private static void retainInputCleanup(CompletableFuture<?> future,ConcurrentMap<LodSummaryKey,Input> inputs,LodSummaryKey key,Input input){
        future.whenComplete((result,error)->inputs.remove(key,input));
    }
    public void setCpuProfiler(RenderCpuProfiler profiler){checkOwner();synchronized(fence){require(!initializationStarted&&!closed,"Install profiler before initialize");this.profiler=Objects.requireNonNull(profiler);driver.setCpuProfiler(profiler);}}
    /** Creates driver resources only. It does not request, upload or publish an empty mask. */
    public void initialize(){checkOwner();driver.checkRenderThread();synchronized(fence){begin();try{
        require(!initializationStarted&&!closed&&!invalidated,"Prepared renderer initialization state");initializationStarted=true;
        guarded(()->{driver.initialize();compiler.set(Objects.requireNonNull(driver.cpuCompiler(),"Explicit CPU compiler required"));});
        require(!invalidated,"Initialization invalidated");initialized=true;
    }catch(RuntimeException|Error failure){invalidateInternal();try{closeInternal();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}finally{busy=false;}}}

    /**
     * Consumes monotonically increasing coverage generations. Repeating the current identical
     * pending request returns the same ticket; cancelled/committed generations cannot be reused.
     * The immutable list is caller attestation, not evidence derived from merely admitted cubes.
     */
    public CoverageTicket prepareCoverage(long generation,List<SceneReadyCube> exactReady){
        return prepareCoverage(generation,exactReady,initialCenterX,initialCenterZ);
    }
    /**
     * The host supplies the authoritative candidate center, never an uncommitted camera position.
     * Center is canonicalized to the immutable layout grid and captured by this immutable ticket/job.
     * Preparation retains the old published center; only explicit commit changes the exact pair.
     */
    public CoverageTicket prepareCoverage(long generation,List<SceneReadyCube> exactReady,long centerX,long centerZ){
        return prepareCoverage(generation,exactReady,centerX,centerZ,null);
    }
    /** Optional exact voxel summary; server ALLOW and native readiness remain mandatory. */
    public CoverageTicket prepareCoverage(long generation,List<SceneReadyCube> exactReady,long centerX,long centerZ,DhReplacementInput replacement){
        return prepareCoverageInternal(generation,exactReady,centerX,centerZ,replacement==null?null:new ReplacementAuthority(replacement,null));
    }
    /** Complete set authority and exact flat policy enter every private ticket, content key and commit. */
    public CoverageTicket prepareCoverageSet(long generation,List<SceneReadyCube> exactReady,long centerX,long centerZ,DhReplacementSetInput replacement){
        Objects.requireNonNull(replacement,"Complete canonical replacement set required");
        return prepareCoverageInternal(generation,exactReady,centerX,centerZ,new ReplacementAuthority(null,replacement));
    }
    /** One complete bounded arena certificate, never legacy flat/two-region authority. */
    public CoverageTicket prepareCoverageArena(long generation,List<SceneReadyCube> exactReady,long centerX,long centerZ,DhArenaInput arena){
        return prepareCoverageInternal(generation,exactReady,centerX,centerZ,new ReplacementAuthority(null,null,Objects.requireNonNull(arena)));
    }
    public CoverageTicket prepareCoverageDistance(long generation,List<SceneReadyCube> exactReady,long centerX,long centerZ,
            DhReplacementInput replacement,io.earthplatform.minecraft.visual.NativeVisualPublication publication,RollingDistanceBodyBinding.NativeJoined body){
        return prepareCoverageInternal(generation,exactReady,centerX,centerZ,new ReplacementAuthority(Objects.requireNonNull(replacement),null),Objects.requireNonNull(publication),Objects.requireNonNull(body));
    }
    private CoverageTicket prepareCoverageInternal(long generation,List<SceneReadyCube> exactReady,long centerX,long centerZ,ReplacementAuthority replacement){
        return prepareCoverageInternal(generation,exactReady,centerX,centerZ,replacement,null,null);
    }
    private CoverageTicket prepareCoverageInternal(long generation,List<SceneReadyCube> exactReady,long centerX,long centerZ,ReplacementAuthority replacement,
            io.earthplatform.minecraft.visual.NativeVisualPublication visual,RollingDistanceBodyBinding.NativeJoined body){checkOwner();driver.checkRenderThread();synchronized(fence){begin();try{
        require(initialized&&!closed&&!invalidated,"Prepared renderer unavailable");requireSource();
        Center center=Center.normalized(centerX,centerZ,layout);
        List<SceneReadyCube> ready=validateReady(exactReady);
        validateReplacement(replacement);validateVisual(visual,replacement);
        require(bodyPrepared(body,ready,visual,replacement),"Exact proved distance body is required");
        if(publishedReplacement!=null&&replacement!=null){
            var nextVolumes=replacement.data().volumes();
            for(var previous:publishedReplacement.data().volumes())
                if(!nextVolumes.contains(previous))require(covers(ready,previous),"Removing any replacement region requires complete actual native coverage");
        }
        if(replacement==null&&publishedReplacement!=null)require(coversAll(ready,publishedReplacement.data()),"Removing replacement requires complete actual native coverage");
        if(pending!=null&&!pending.ticket.cancelled&&pending.ticket.generation==generation&&pending.ready.equals(ready)&&pending.input.center().equals(center)&&Objects.equals(pending.replacement,replacement)&&pending.visual==visual&&pending.body==body&&current(pending.ticket))return pending.ticket;
        if(generation<0||generation<=highestGeneration){rejected++;throw new IllegalArgumentException("Coverage generation must strictly advance");}
        discardPending();
        Pending next=new Pending(Math.incrementExact(requestSerial),generation,ready,center,replacement,visual,body);requestSerial=next.ticket.request;highestGeneration=generation;
        pending=next;if(replacement!=null){
            if(replacement.arena()!=null){arenaDefinitionIdentity=replacement.arena().data().capture().definition().identity();}
            else if(replacement.set()!=null){indexedReplacementSet=replacement.set().data().queryAttestation()!=null;replacementWorkspaceIdentity=replacement.set().data().workspaceIdentity();replacementFlatPolicyIdentity=replacement.set().data().policy().identity();}
            else if(originalReplacementCube==null)originalReplacementCube=replacement.single().certificate().editedCube();
        }lastFailure="";if(next.reuseBase!=null)sameGeometryPreparations++;submit(next);return next.ticket;
    }finally{busy=false;}}}
    private List<SceneReadyCube> validateReady(List<SceneReadyCube> proposed){
        Objects.requireNonNull(proposed,"exactReady");if(proposed.size()>DhTerrainGeometry.MAX_READY_CUBES)throw new IllegalArgumentException("Native ready budget");
        var ready=new ArrayList<SceneReadyCube>(proposed.size());var positions=new HashSet<CubePos>();
        for(var cube:proposed){Objects.requireNonNull(cube,"ready cube");CubePos p=cube.position();
            if(!cube.world().equals(world)||cube.epoch()!=epoch||cube.resourceGeneration()!=resources||cube.leaseGeneration()<=0||cube.revision()<0||!positions.add(p)
                    ||p.minBlockY()<DhTerrainGeometry.MIN_Y||p.minBlockY()+CubePos.SIZE>DhTerrainGeometry.MAX_Y_EXCLUSIVE)
                throw new IllegalArgumentException("Exact native readiness identity or bounds mismatch");
            ready.add(cube);
        }
        ready.sort(Comparator.comparing(SceneReadyCube::position));return List.copyOf(ready);
    }
    private void submit(Pending p){if(p.reuseBase!=null||p.lease!=null||p.ticket.cancelled)return;inputs.put(p.key,p.input);
        try{p.lease=jobs.request(p.key,p.stamp);retainInputCleanup(p.lease.future(),inputs,p.key,p.input);}
        catch(RejectedExecutionException pressure){inputs.remove(p.key,p.input);lastFailure="Summary backpressure; old pair retained";}
    }
    /** Fixed radius; bounded CPU staging and GL uploads only, never publication. */
    public void pump(){checkOwner();driver.checkRenderThread();synchronized(fence){begin();try{
        if(closed||invalidated||!initialized)return;requireSource();
        Pending p=pending;if(p==null)return;if(p.ticket.cancelled||!replacementCurrent(p.replacement)||!visualCurrent(p.visual)||!bodyPrepared(p.body,p.ready,p.visual,p.replacement)){discardPending();return;}
        if(p.reuseBase!=null){
            require(reuseCurrent(p),"Committed geometry changed before reuse staging");
            if(!p.staged){p.payload=p.reuseBase;stagePayload(p,p.payload);}
            // Every candidate is an alias to an already-published owner. Never invoke a source,
            // compiler, upload callback or completed-result cache for this authority-only stage.
            require(current(p.ticket)&&reuseCurrent(p)&&cache.ready(p.ticket.request),"Exact reused geometry must be privately ready");
            return;
        }
        submit(p);if(p.lease==null)return;
        if(!p.staged&&p.lease.future().isDone()){
            try{p.lease.future().join();boolean staged=p.lease.publishIfCurrent(p.stamp,payload->{
                require(current(p.ticket),"Current ticket required for staging");p.payload=payload;
                stagePayload(p,payload);
            });if(!staged){discardPending();return;}}
            catch(CompletionException failure){Throwable cause=failure.getCause();if(cause instanceof DhReplacementComposition.Declined decline&&decline(p,decline.getMessage()))return;if(cause instanceof RejectedExecutionException){p.lease.close();p.lease=null;lastFailure="Summary executor pressure; old pair retained";return;}throw propagate(cause);}
            catch(CancellationException cancelled){discardPending();return;}
        }
        if(!p.staged||!current(p.ticket))return;
        long start=System.nanoTime();
        guarded(()->{activeUploadGeometry=p.payload;try{var progress=cache.pump(UPLOAD_BYTES,2,UPLOAD_NANOS);uploadedOwners+=progress.uploadedOwners();uploadedBytes+=progress.uploadedBytes();}finally{activeUploadGeometry=null;}});
        lastPumpNanos=System.nanoTime()-start;maxPumpNanos=Math.max(maxPumpNanos,lastPumpNanos);if(lastPumpNanos>UPLOAD_NANOS)uploadOverruns++;
        peakOwners=Math.max(peakOwners,cache.residentOwners()+driver.pendingOwners());peakBytes=Math.max(peakBytes,cache.residentBytes()+driver.pendingBytes());
        requireSource();if(p.ticket.cancelled)discardPending();
    }catch(DhReplacementComposition.Declined refusal){if(!decline(pending,refusal.getMessage())){fail(refusal);throw refusal;}}catch(RuntimeException|Error failure){fail(failure);throw failure;}finally{busy=false;}}}
    /** Staging itself owns no GL callbacks; a same-content transaction has zero new owners. */
    private void stagePayload(Pending p,Geometry payload){
        require(current(p.ticket)&&payload.center().equals(p.input.center())&&payload.layoutIdentity().equals(p.input.layout().identity()),"Current exact-center ticket required before private stage");
        preflightUnion(payload);
        long diff=profiler.start();try{require(cache.stage(p.ticket.request,payload.batch().tiles()),"Fresh stage required");p.staged=true;}finally{profiler.finish(RenderCpuProfiler.Stage.CPU_TILE_DIFF,diff);}
        require(current(p.ticket),"Private stage was invalidated");
    }
    private boolean reuseCurrent(Pending p){return p.reuseBase!=null&&geometry==p.reuseBase&&coverageGeneration==p.reuseBaseGeneration&&p.input.center().equals(geometry.center())&&geometry.layoutIdentity().equals(layout.identity())&&geometry.center().x()==publishedCenterX&&geometry.center().z()==publishedCenterZ;}
    private boolean payloadCurrent(Pending p){return p.reuseBase!=null?reuseCurrent(p):p.lease!=null&&p.lease.isCurrent(p.stamp);}
    /** Distinguishes a live pending/prepared ticket from a cancelled, stale or discarded request. */
    public boolean isCurrent(CoverageTicket ticket){checkOwner();synchronized(fence){return current(ticket)&&(pending.reuseBase!=null?reuseCurrent(pending):pending.lease==null||pending.lease.isCurrent(pending.stamp));}}
    /** A snapshot only; the caller must revalidate its actual near meshes at the frame fence. */
    public boolean isPrepared(CoverageTicket ticket){checkOwner();synchronized(fence){return current(ticket)&&pending.staged&&cache.ready(ticket.request)&&payloadCurrent(pending);}}
    /** The controller invokes this only after server ALLOW, under its native/far/collision fence. */
    public boolean commitPrepared(CoverageTicket ticket){checkOwner();driver.checkRenderThread();synchronized(fence){begin();try{
        if(!current(ticket)||!pending.staged||!cache.ready(ticket.request)||!payloadCurrent(pending))return false;
        Pending p=pending;int reused=cache.lastStageReusedOwners();
        boolean published;
        if(p.reuseBase!=null){publishPrepared(p,p.reuseBase,reused);sameGeometryCommits++;published=true;}
        else published=p.lease.publishIfCurrent(p.stamp,payload->publishPrepared(p,payload,reused));
        if(!published)return false;
        ticket.consumed=true;if(p.lease!=null)p.lease.close();pending=null;lastFailure="";return true;
    }catch(RuntimeException|Error failure){fail(failure);throw failure;}finally{busy=false;}}}
    private void publishPrepared(Pending p,Geometry payload,int reused){
        require(current(p.ticket)&&payloadCurrent(p)&&payload.center().equals(p.input.center())&&payload.layoutIdentity().equals(p.input.layout().identity()),"Current exact ticket, center and payload required at commit");
        guarded(()->{require(current(p.ticket)&&payloadCurrent(p),"Ticket cancelled before cache publication");revokePresentation();require(cache.commit(p.ticket.request),"Prepared cache commit required");});
        // Reentrant invalidation/cancellation during driver callbacks fails closed after publication.
        require(current(p.ticket)&&payloadCurrent(p),"Ticket invalidated during commit");
        if(coverageGeneration>=0&&(publishedCenterX!=p.input.center().x()||publishedCenterZ!=p.input.center().z()))recenterCommits++;
        geometry=payload;publishedReplacement=p.replacement;publishedVisual=p.visual;publishedBody=p.body;coverage=p.ready;coverageGeneration=p.ticket.generation;
        publishedCenterX=p.input.center().x();publishedCenterZ=p.input.center().z();commits++;reusedOwners+=reused;
    }
    /** Returns the single current visual-only token, or creates it after a committed batch exists. */
    public PresentationTicket retainPresentation(){
        require(distanceConfig==null,"Distance presentation requires the exact retained body history");
        return retainPresentationInternal(null);
    }
    /** The host acquires and owns the one native history token first; this view only aliases it. */
    public PresentationTicket retainPresentationDistance(RollingDistanceBodyBinding.History history){
        require(distanceConfig!=null,"Legacy presentation cannot accept distance history");
        return retainPresentationInternal(Objects.requireNonNull(history));
    }
    private PresentationTicket retainPresentationInternal(RollingDistanceBodyBinding.History history){checkOwner();synchronized(fence){begin();try{
        require(initialized&&!closed&&!invalidated&&coverageGeneration>=0,"Committed presentation required");requireSource();
        require(replacementCurrent(publishedReplacement)&&bodyPublishedCurrent(),"Current replacement/body required when capturing historical presentation");
        if(distanceConfig!=null)history.requirePresentable(publishedBody,coverage,publishedVisual,publishedReplacement.single());
        if(presentation!=null&&presentation.bodyHistory==history&&presentationCurrent(presentation))return presentation;
        revokePresentation();presentation=new PresentationTicket(this,history);presentationRetains++;return presentation;
    }finally{busy=false;}}}
    /** No source or GL callback is invoked while releasing; releasing a foreign token is a no-op. */
    public void releasePresentation(PresentationTicket ticket){synchronized(fence){
        if(ticket==null||ticket.owner!=this||ticket.released)return;
        ticket.released=true;if(presentation==ticket)presentation=null;
        if(distanceConfig!=null){ticket.visual=null;ticket.replacement=null;ticket.exactReady=List.of();ticket.body=null;ticket.bodyHistory=null;}
        presentationReleases++;
    }}
    private void revokePresentation(){if(presentation!=null)releasePresentation(presentation);}
    private boolean presentationIdentityCurrent(PresentationTicket ticket){
        return ticket!=null&&ticket.owner==this&&ticket.view==viewIdentity&&ticket==presentation&&!ticket.released
                &&initialized&&!closed&&!invalidated&&ticket.epoch==epoch&&ticket.resources==resources
                &&ticket.generation==coverageGeneration&&ticket.layoutIdentity.equals(layout.identity())&&ticket.centerX==publishedCenterX&&ticket.centerZ==publishedCenterZ&&ticket.source.equals(sourceIdentity)&&ticket.exactReady==coverage&&ticket.replacement==publishedReplacement&&ticket.visual==publishedVisual&&ticket.body==publishedBody;
    }
    private boolean presentationCurrent(PresentationTicket ticket){
        if(!presentationIdentityCurrent(ticket))return false;requireSource();
        boolean permitted;try{permitted=ticket.replacement==null||ticket.replacement.permitsRetainedPresentation();}catch(RuntimeException|Error failure){fail(failure);throw failure;}
        return permitted&&visualHistorical(ticket.visual)&&bodyHistorical(ticket)&&presentationIdentityCurrent(ticket);
    }
    public boolean isPresentationCurrent(PresentationTicket ticket){checkOwner();synchronized(fence){return presentationCurrent(ticket);}}
    /**
     * Draws only the exact retained old far batch. Caller fences motion/raycast/edit/collision and
     * draws its matching retained native batch in this same frame fence. A wrong/revoked token
     * returns false without GL submission. The resource argument must be the caller's CURRENT
     * generation; this view never infers external generation changes from an old token alone.
     */
    public boolean drawPresented(PresentationTicket ticket,Matrix4f matrix,double x,double y,double z,long resourceGeneration){
        checkOwner();driver.checkRenderThread();synchronized(fence){begin();try{
            if(resourceGeneration!=resources||!presentationCurrent(ticket)){rejectedPresentationDraws++;return false;}
            require(Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z),"Finite native camera");Objects.requireNonNull(matrix);
            if(ticket.visual!=null)ticket.visual.requireCamera(x,y,z);
            boolean[] submitted={false};
            guarded(()->{
                // State capture/source callbacks may reentrantly release or invalidate the token.
                if(!presentationCurrent(ticket))return;
                if(ticket.visual!=null)ticket.visual.requireCamera(x,y,z);
                var stats=driver.draw(cache.currentResources(),matrix,x,y,z);drawCalls+=stats.drawCalls();vertices+=stats.vertices();lastDrawNanos=stats.submissionNanos();submitted[0]=true;
            });
            boolean current=submitted[0]&&presentationCurrent(ticket);
            if(current)presentationFrames++;else rejectedPresentationDraws++;return current;
        }catch(RuntimeException|Error failure){fail(failure);throw failure;}finally{busy=false;}}
    }
    /** Cancellation is CPU-only; private GL owners remain retained until owner pump/close. */
    public void cancelPrepared(CoverageTicket ticket){synchronized(fence){
        if(ticket==null||ticket.view!=viewIdentity||ticket.consumed||pending==null||pending.ticket!=ticket)return;
        ticket.cancelled=true;if(pending.lease!=null)pending.lease.close();
    }}
    private boolean current(CoverageTicket ticket){
        if(ticket==null||ticket.view!=viewIdentity||ticket.cancelled||ticket.consumed||pending==null||pending.ticket!=ticket||closed||invalidated||!initialized)return false;
        requireSource();
        if(ticket.cancelled||ticket.consumed||pending==null||pending.ticket!=ticket||closed||invalidated)return false;
        Pending observed=pending;boolean permitted=replacementCurrent(observed.replacement)&&visualCurrent(observed.visual)&&bodyPrepared(observed.body,observed.ready,observed.visual,observed.replacement);
        if(!permitted||pending!=observed)return false;
        if(ticket.cancelled||ticket.consumed||pending==null||pending.ticket!=ticket||closed||invalidated)return false;
        return ticket.epoch==epoch&&ticket.resources==resources&&ticket.layoutIdentity.equals(layout.identity())&&ticket.source.equals(sourceIdentity)&&ticket.request==pending.stamp.lease()&&ticket.readyIdentity.equals(pending.key.variantId())&&ticket.centerX==pending.input.center().x()&&ticket.centerZ==pending.input.center().z()&&ticket.replacementIdentity.equals(replacementIdentity(pending.input.replacement()));
    }
    private void discardPending(){Pending p=pending;if(p==null)return;p.ticket.cancelled=true;if(p.lease!=null)p.lease.close();inputs.remove(p.key,p.input);
        guarded(cache::cancelStage);pending=null;
    }
    public boolean coverageReady(long generation){checkOwner();synchronized(fence){return hasPublishedGeometry()&&coverageGeneration==generation;}}
    /** Controller additionally proves the immutable common-side detail/extent policy. */
    public boolean coverageReady(long generation,long centerX,long centerZ,DhLodLayout expectedLayout){
        checkOwner();synchronized(fence){return layout==Objects.requireNonNull(expectedLayout)&&coverageReady(generation,centerX,centerZ);}
    }
    /** Exact center plus generation handshake for the controller's atomic native/far commit. */
    public boolean coverageReady(long generation,long centerX,long centerZ){checkOwner();synchronized(fence){
        Center center=Center.normalized(centerX,centerZ,layout);
        return hasPublishedGeometry()&&coverageGeneration==generation&&publishedCenterX==center.x()&&publishedCenterZ==center.z();
    }}
    public boolean hasPublishedGeometry(){checkOwner();synchronized(fence){if(!closed&&!invalidated)requireSource();return coverageGeneration>=0&&!closed&&!invalidated&&replacementCurrent(publishedReplacement)&&visualCurrent(publishedVisual)&&bodyPrepared(publishedBody,coverage,publishedVisual,publishedReplacement)&&!closed&&!invalidated;}}
    public void requestInvalidation(){synchronized(fence){invalidateInternal();}}
    private void invalidateInternal(){captureFirstFence();revokePresentation();invalidated=true;if(pending!=null)pending.ticket.cancelled=true;jobs.invalidateAll();}
    private void captureFirstFence(){
        if(firstFenceCaptured)return;firstFenceCaptured=true;
        firstFencePresentationRetained=presentationIdentityCurrent(presentation);firstFencePresentationGeneration=firstFencePresentationRetained?presentation.generation:-1;
        if(Thread.currentThread()==owner)updateOwnerSnapshot();
        var stats=jobs.stats();firstFenceRunning=stats.running();firstFenceQueued=stats.queued();
        firstFencePublishedCenterX=publishedCenterX;firstFencePublishedCenterZ=publishedCenterZ;
        if(pending!=null){firstFencePendingCenterX=pending.input.center().x();firstFencePendingCenterZ=pending.input.center().z();}
        firstFencePendingGeneration=pending==null?-1:pending.ticket.generation;firstFencePendingSameGeometry=pending!=null&&pending.reuseBase!=null;
        firstFencePendingCpu=pending!=null&&pending.lease!=null&&!pending.lease.future().isDone();
        firstFencePendingStage=pending!=null&&pending.staged;firstFenceResidentOwners=ownerResidentOwners;firstFencePublishedOwners=ownerPublishedOwners;
    }
    private void updateOwnerSnapshot(){ownerResidentOwners=cache.residentOwners()+driver.pendingOwners();ownerPublishedOwners=cache.currentResources().size();peakOwners=Math.max(peakOwners,ownerResidentOwners);peakBytes=Math.max(peakBytes,cache.residentBytes()+driver.pendingBytes());}
    private void requireSource(){try{if(!sourceIdentity.equals(sampler.identity())||footprintCoverage!=sampler.supportsFootprintCoverage()||stratifiedSides!=sampler.supportsStratifiedSides())throw new IllegalStateException("Immutable source identity changed");require(!invalidated&&!closed,"Source identity observation invalidated ownership");}catch(RuntimeException|Error failure){fail(failure);throw failure;}}
    public void draw(Matrix4f matrix,double x,double y,double z,long resourceGeneration){checkOwner();driver.checkRenderThread();synchronized(fence){begin();try{
        if(closed||invalidated||resourceGeneration!=resources||coverageGeneration<0)return;requireSource();
        if(!replacementCurrent(publishedReplacement)||!visualCurrent(publishedVisual)||!bodyPublishedCurrent()||closed||invalidated)return;
        require(Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z),"Finite native camera");Objects.requireNonNull(matrix);
        if(publishedVisual!=null)publishedVisual.requireCamera(x,y,z);
        guarded(()->{if(!replacementCurrent(publishedReplacement)||!visualCurrent(publishedVisual)||!bodyPublishedCurrent()||closed||invalidated)return;if(publishedVisual!=null)publishedVisual.requireCamera(x,y,z);var stats=driver.draw(cache.currentResources(),matrix,x,y,z);drawCalls+=stats.drawCalls();vertices+=stats.vertices();lastDrawNanos=stats.submissionNanos();});
        if(distanceConfig!=null)require(!closed&&!invalidated&&replacementCurrent(publishedReplacement)&&visualCurrent(publishedVisual)&&bodyPublishedCurrent(),"Distance body/visual authority changed during far draw");
    }catch(RuntimeException|Error failure){fail(failure);throw failure;}finally{busy=false;}}}
    private void guarded(Runnable operation){DhGeographicLodView.State state=null;Throwable failure=null;
        try{state=driver.captureState();operation.run();}catch(RuntimeException|Error error){failure=error;}
        if(state!=null)try{state.restore();}catch(RuntimeException|Error error){pendingStateRestore=state;failure=append(failure,error);}
        updateOwnerSnapshot();
        if(failure!=null){fail(failure);throw propagate(failure);}
    }
    private void fail(Throwable failure){lastFailure=failure.toString();invalidateInternal();}
    public JsonObject diagnostics(){checkOwner();synchronized(fence){var out=new JsonObject();var stats=jobs.stats();
        out.addProperty("nativeDistanceMode",distanceConfig!=null);
        out.addProperty("nativeDistanceFarPolicy",distanceConfig==null?"legacy":distanceConfig.identity());
        out.addProperty("maxFarGpuBytes",distanceConfig==null?MAX_GPU_BYTES:distanceConfig.maxGpuBytes());
        out.addProperty("maxFarCurrentOwners",distanceConfig==null?64:distanceConfig.geometry().maxOwners());
        out.addProperty("maxFarResidentOwners",distanceConfig==null?128:distanceConfig.maxResidentOwners());
        out.addProperty("farRoundedStorageBytesIncludingFailedUploads",cache.residentBytes()+driver.pendingBytes());
        out.addProperty("farFailedUploadRetainedBytes",driver.pendingBytes());
        out.addProperty("scope","Explicit immutable-base fixed-policy multiresolution moving-center prepared DH transactions; exact native-ready caller attestation");
        out.addProperty("preparedTransactions",true);out.addProperty("autoCommit",false);out.addProperty("incremental",true);out.addProperty("vertexPreparationMode","bounded-background-official-builder");
        out.addProperty("distanceBodyAliases",(publishedBody==null?0:1)+(closingBody==null||closingBody==publishedBody?0:1)+(pending==null||pending.body==null||pending.body==publishedBody||pending.body==closingBody?0:1));
        out.addProperty("distanceBodyHistoryAlias",presentation!=null&&presentation.bodyHistory!=null);
        out.addProperty("sameGeometryPreparations",sameGeometryPreparations);out.addProperty("sameGeometryCommits",sameGeometryCommits);out.addProperty("pendingSameGeometry",pending!=null&&pending.reuseBase!=null);out.addProperty("sameGeometryAddsPayloadBytes",false);out.addProperty("backgroundTimingScope","Retained geometry compilation; unchanged by same-geometry authority commits");out.addProperty("sameGeometryReusesAuthority",false);
        out.addProperty("presentationRetained",presentationIdentityCurrent(presentation));out.addProperty("presentationCoverageGeneration",presentation==null?-1:presentation.generation);out.addProperty("presentationReadyCubes",presentation==null?0:presentation.exactReady.size());out.addProperty("presentationRetains",presentationRetains);out.addProperty("presentationReleases",presentationReleases);out.addProperty("presentationFrames",presentationFrames);out.addProperty("rejectedPresentationDraws",rejectedPresentationDraws);out.addProperty("maxPresentationTickets",1);out.addProperty("presentationHasGameplayAuthority",false);out.addProperty("firstFencePresentationRetained",firstFencePresentationRetained);out.addProperty("firstFencePresentationGeneration",firstFencePresentationGeneration);
        out.addProperty("sourceAppearanceIdentity",sourceIdentity);out.addProperty("sourceEditRevision",0);out.addProperty("farSummarizesEdits",publishedReplacement!=null&&(publishedReplacement.single()!=null||publishedReplacement.set()!=null&&!publishedReplacement.set().certificate().editedRegions().isEmpty()||publishedReplacement.arena()!=null&&publishedReplacement.arena().certificate().definition().positions().stream().anyMatch(p->publishedReplacement.arena().certificate().revision(p)>0)));
        out.addProperty("replacementIdentity",publishedReplacement==null?"":publishedReplacement.identity());out.addProperty("replacementCertificateIdentity",publishedReplacement==null||publishedReplacement.single()==null?"":publishedReplacement.single().certificate().identity());out.addProperty("replacementDurableRevision",publishedReplacement==null||publishedReplacement.single()==null?-1:publishedReplacement.single().certificate().durableRevision());out.addProperty("replacementDeclines",replacementDeclines);out.addProperty("lastReplacementDecline",lastReplacementDecline);
        out.addProperty("replacementSetIdentity",publishedReplacement!=null&&publishedReplacement.set()!=null?publishedReplacement.set().certificateIdentity():"");
        out.addProperty("replacementSetWorkspaceIdentity",replacementWorkspaceIdentity==null?"":replacementWorkspaceIdentity);
        out.addProperty("replacementSetFlatPolicyIdentity",replacementFlatPolicyIdentity==null?"":replacementFlatPolicyIdentity);
        out.addProperty("replacementSetOwnershipPolicy",publishedReplacement!=null&&publishedReplacement.set()!=null?publishedReplacement.set().data().ownershipPolicy():"");
        arenaDiagnostics(out,"arena",publishedReplacement);
        arenaDiagnostics(out,"pendingArena",pending==null?null:pending.replacement);
        arenaDiagnostics(out,"presentationArena",presentation==null?null:presentation.replacement);
        queryDiagnostics(out,"replacementSetQuery",publishedReplacement);
        queryDiagnostics(out,"pendingReplacementSetQuery",pending==null?null:pending.replacement);
        queryDiagnostics(out,"presentationReplacementSetQuery",presentation==null?null:presentation.replacement);
        var setRegions=new com.google.gson.JsonArray();
        if(publishedReplacement!=null&&publishedReplacement.set()!=null)for(var member:publishedReplacement.set().data().members()){
            var entry=new com.google.gson.JsonObject();var c=member.certificate();
            entry.addProperty("x",c.editedCube().x());entry.addProperty("y",c.editedCube().y());entry.addProperty("z",c.editedCube().z());
            entry.addProperty("durableRevision",c.durableRevision());entry.addProperty("captureIdentity",member.capture().contentDigest());
            entry.addProperty("captureBytes",member.declaredBytes());setRegions.add(entry);
        }
        out.add("replacementSetRegionReceipts",setRegions);
        out.addProperty("replacementSetRegions",publishedReplacement!=null&&publishedReplacement.set()!=null?publishedReplacement.set().data().members().size():0);
        out.addProperty("replacementSetEditedRegions",publishedReplacement!=null&&publishedReplacement.set()!=null?publishedReplacement.set().certificate().editedRegions().size():0);
        if(pending!=null)out.addProperty("pendingReplacementIdentity",pending.ticket.replacementIdentity);out.addProperty("epoch",epoch);out.addProperty("resourceGeneration",resources);
        out.addProperty("radiusBlocks",layout.radiusBlocks());out.addProperty("lodLayout",layout.id());out.addProperty("lodLayoutIdentity",layout.identity());out.addProperty("outerCellSizeBlocks",layout.outerCellSize());out.addProperty("snapBlocks",layout.snapBlocks());out.addProperty("initialCenterX",initialCenterX);out.addProperty("initialCenterZ",initialCenterZ);out.addProperty("publishedCenterX",publishedCenterX);out.addProperty("publishedCenterZ",publishedCenterZ);out.addProperty("recenterCommits",recenterCommits);
        out.addProperty("requestedFootprintMinX",publishedCenterX-layout.radiusBlocks());out.addProperty("requestedFootprintMinZ",publishedCenterZ-layout.radiusBlocks());out.addProperty("requestedFootprintMaxXExclusive",publishedCenterX+layout.radiusBlocks());out.addProperty("requestedFootprintMaxZExclusive",publishedCenterZ+layout.radiusBlocks());out.addProperty("sampledCoverageIsContinuousCoverageProof",false);out.addProperty("fullFootprintCoverageRequired",footprintCoverage);out.addProperty("stratifiedColumnMaterials",stratifiedSides);out.addProperty("footprintCoveragePolicy",footprintCoverage?"complete-source-support-before-leaf-sampling-v1":"legacy-center-sampled-approximation");
        out.addProperty("centerAlignmentBlocks",layout.centerAlignmentBlocks());out.addProperty("centerAuthority","Immutable caller ticket; committed with exact XYZ mask");
        if(pending!=null){out.addProperty("pendingCenterX",pending.input.center().x());out.addProperty("pendingCenterZ",pending.input.center().z());}
        if(presentation!=null){out.addProperty("presentationCenterX",presentation.centerX);out.addProperty("presentationCenterZ",presentation.centerZ);}
        out.addProperty("coverageGeneration",coverageGeneration);out.addProperty("highestRequestedCoverageGeneration",highestGeneration);out.addProperty("nearReadyCubes",coverage.size());
        out.addProperty("coverageIdentity",hash("center-v1:"+publishedCenterX+":"+publishedCenterZ+":"+coverage));out.addProperty("nearReadyIdentity",hash(coverage.toString()));out.addProperty("pageTileOwners",cache.currentResources().size());out.addProperty("residentPageTileOwners",cache.residentOwners());out.addProperty("peakResidentPageTileOwners",peakOwners);
        out.addProperty("residentBufferBytes",cache.residentBytes());out.addProperty("peakBufferBytes",peakBytes);out.addProperty("uploadedBufferBytes",uploadedBytes);out.addProperty("uploadedOwners",uploadedOwners);out.addProperty("reusedTileOwners",reusedOwners);out.addProperty("retirementPending",cache.retirementPending());out.addProperty("commits",commits);
        out.addProperty("distanceCpuReservedBytes",distanceCpu==null||distanceCpu.released()?0:distanceCpu.bytes());out.addProperty("physicalSummaryWorkPending",jobs.physicalWorkPending());out.addProperty("physicalDrainComplete",physicalDrainComplete());
        out.addProperty("summaryRunning",stats.running());out.addProperty("summaryQueued",stats.queued());out.addProperty("summaryCachedItems",stats.cachedItems());out.addProperty("summaryCachedBytes",stats.cachedBytes());out.addProperty("summaryCacheHits",stats.cacheHits());out.addProperty("summaryCancelled",stats.cancelled());
        out.addProperty("maxPreparedResultBytes",distanceConfig==null?5L*1024*1024:distanceConfig.geometry().maxResultBytes());out.addProperty("maxPreparedCacheBytes",distanceConfig==null?20L*1024*1024:distanceConfig.maxCachedBytes());out.addProperty("pendingCoverageGeneration",pending==null?-1:pending.ticket.generation);out.addProperty("pendingGpuStage",pending!=null&&pending.staged);out.addProperty("pendingCancelled",pending!=null&&pending.ticket.cancelled);
        out.addProperty("lastCpuUploadNanos",lastPumpNanos);out.addProperty("maxCpuUploadNanos",maxPumpNanos);out.addProperty("uploadSoftBudgetOverruns",uploadOverruns);out.addProperty("drawCalls",drawCalls);out.addProperty("submittedVertices",vertices);out.addProperty("lastCpuSubmissionNanos",lastDrawNanos);out.addProperty("rejectedChanges",rejected);out.addProperty("lastFailure",lastFailure);
        out.addProperty("firstFencePendingSameGeometry",firstFencePendingSameGeometry);out.addProperty("firstFenceCaptured",firstFenceCaptured);out.addProperty("firstFencePendingCoverageGeneration",firstFencePendingGeneration);out.addProperty("firstFencePendingCpu",firstFencePendingCpu);out.addProperty("firstFencePendingGpuStage",firstFencePendingStage);out.addProperty("firstFenceSummaryRunning",firstFenceRunning);out.addProperty("firstFenceSummaryQueued",firstFenceQueued);out.addProperty("firstFenceResidentOwners",firstFenceResidentOwners);out.addProperty("firstFencePublishedOwners",firstFencePublishedOwners);out.addProperty("firstFencePrivateOrRetiredOwners",Math.max(0,firstFenceResidentOwners-firstFencePublishedOwners));
        out.addProperty("firstFencePublishedCenterX",firstFencePublishedCenterX);out.addProperty("firstFencePublishedCenterZ",firstFencePublishedCenterZ);
        if(firstFencePendingGeneration>=0){out.addProperty("firstFencePendingCenterX",firstFencePendingCenterX);out.addProperty("firstFencePendingCenterZ",firstFencePendingCenterZ);}
        if(preClosePendingGeneration>=0){out.addProperty("preClosePendingCenterX",preClosePendingCenterX);out.addProperty("preClosePendingCenterZ",preClosePendingCenterZ);}
        out.addProperty("preClosePendingSameGeometry",preClosePendingSameGeometry);out.addProperty("preClosePendingCoverageGeneration",preClosePendingGeneration);out.addProperty("preClosePendingCpu",preClosePendingCpu);out.addProperty("preClosePendingGpuStage",preClosePendingStage);out.addProperty("preCloseResidentOwners",preCloseResidentOwners);out.addProperty("preCloseUploadedOwners",preCloseUploadedOwners);
        if(geometry!=null){out.addProperty("replacementSourceFaces",geometry.replacementSourceFaces());out.addProperty("replacementVisibleFaces",geometry.replacementVisibleFaces());out.addProperty("replacementOwners",geometry.replacementOwners());var b=geometry.batch();out.addProperty("sampledCells",b.sampledCells());out.addProperty("centerSampledCells",b.centerSampledCells());out.addProperty("footprintRejectedCells",b.footprintRejectedCells());out.addProperty("fullySupportedCells",b.fullySupportedCells());out.addProperty("knownCells",b.knownCells());out.addProperty("unknownCells",b.unknownCells());out.addProperty("refinedCells",b.refinedCells());out.addProperty("transitionFaces",b.transitionFaces());out.addProperty("sourceFaces",b.sourceFaces());out.addProperty("publishedFaces",b.faces());out.addProperty("clippedSourceFaces",b.clippedSourceFaces());out.addProperty("lastBackgroundGeometryNanos",geometry.cpuNanos());out.addProperty("lastBackgroundBuilderNanos",geometry.builderNanos());out.addProperty("preparedVertexBytes",geometry.preparedBytes());out.addProperty("vertexCompilerThread",geometry.compilerThread());}
        out.addProperty("renderer",driver.renderer());out.addProperty("hardwareGpuPerformanceClaim",false);out.addProperty("installedDhRuntimeResolved",driver.installedRuntimeResolved());out.addProperty("closed",closed);out.addProperty("invalidated",invalidated);out.addProperty("ownedGlObjectsDeleted",deleted);out.addProperty("stateRestorationPending",pendingStateRestore!=null);out.addProperty("driverPartialOwners",driver.pendingOwners());return out;
    }}
    public void close(){checkOwner();driver.checkRenderThread();synchronized(fence){begin();try{closeInternal();}finally{busy=false;}}}
    private void closeInternal(){if(closed&&deleted){releaseDistanceCpuIfDrained();require(distanceCpu==null||distanceCpu.released(),"Prepared CPU work retained after close; retry physical drain");return;}
        if(!closed){if(pending!=null){preClosePendingCenterX=pending.input.center().x();preClosePendingCenterZ=pending.input.center().z();}preClosePendingGeneration=pending==null?-1:pending.ticket.generation;preClosePendingSameGeometry=pending!=null&&pending.reuseBase!=null;preClosePendingCpu=pending!=null&&pending.lease!=null&&!pending.lease.future().isDone();preClosePendingStage=pending!=null&&pending.staged;preCloseResidentOwners=cache.residentOwners()+driver.pendingOwners();preCloseUploadedOwners=uploadedOwners;}
        captureFirstFence();revokePresentation();closed=true;invalidated=true;jobs.close();if(pending!=null&&pending.body!=null)closingBody=pending.body;if(pending!=null){pending.ticket.cancelled=true;if(pending.lease!=null)pending.lease.close();}pending=null;inputs.clear();
        DhGeographicLodView.State state=null;Throwable failure=null;
        if(pendingStateRestore!=null){try{pendingStateRestore.restore();pendingStateRestore=null;}catch(RuntimeException|Error error){failure=error;}}
        if(pendingStateRestore==null)try{state=driver.captureState();}catch(RuntimeException|Error error){failure=append(failure,error);}
        try{cache.close();}catch(RuntimeException|Error error){failure=append(failure,error);}
        try{driver.close();require(driver.deleted(),"Prepared driver cleanup incomplete");}catch(RuntimeException|Error error){failure=append(failure,error);}
        if(state!=null)try{state.restore();}catch(RuntimeException|Error error){pendingStateRestore=state;failure=append(failure,error);}
        coverage=List.of();geometry=null;publishedReplacement=null;publishedVisual=null;activeUploadGeometry=null;updateOwnerSnapshot();deleted=cache.residentOwners()==0&&driver.deleted()&&pendingStateRestore==null&&failure==null;
        if(failure!=null){lastFailure=failure.toString();throw propagate(failure);}require(deleted,"Resources retained after close");
        releaseDistanceCpuIfDrained();require(distanceCpu==null||distanceCpu.released(),"Prepared CPU work retained after close; retry physical drain");
    }
    /** Caller must retain source/coverage owners until this is false before replacing a cancelled candidate. */
    public boolean physicalSummaryWorkPending(){checkOwner();synchronized(fence){return jobs.physicalWorkPending();}}
    /** No implicit release: the render owner retries close after the physical source completion. */
    public boolean physicalDrainComplete(){checkOwner();synchronized(fence){return closed&&deleted&&jobs.physicalDrainComplete()
            &&inputs.isEmpty()&&pending==null&&geometry==null&&activeUploadGeometry==null&&presentation==null
            &&publishedBody==null&&closingBody==null&&(distanceCpu==null||distanceCpu.released());}}
    private void releaseDistanceCpuIfDrained(){
        if(distanceCpu!=null&&!distanceCpu.released()&&closed&&deleted&&jobs.physicalDrainComplete()
                &&inputs.isEmpty()&&pending==null&&geometry==null&&activeUploadGeometry==null&&presentation==null){
            publishedBody=null;closingBody=null;
            cpuSource.releaseSourceAfterDrain();sampler=null;compiler.set(null);
            distanceCpu.releaseAfterPhysicalDrain();
        }
    }
    /** Exact committed server certificate handshake, distinct from native ready coverage. */
    public boolean replacementReady(String certificateIdentity){checkOwner();synchronized(fence){
        return hasPublishedGeometry()&&(certificateIdentity==null?publishedReplacement==null:publishedReplacement!=null&&publishedReplacement.single()!=null&&certificateIdentity.equals(publishedReplacement.single().certificate().identity()));
    }}
    /** Exact complete-set certificate handshake; never inferred from per-region readiness. */
    public boolean replacementSetReady(String certificateIdentity){checkOwner();synchronized(fence){
        return certificateIdentity!=null&&hasPublishedGeometry()&&publishedReplacement!=null&&publishedReplacement.set()!=null
                &&certificateIdentity.equals(publishedReplacement.set().certificateIdentity());
    }}
    public boolean arenaReady(String certificateIdentity){checkOwner();synchronized(fence){
        return certificateIdentity!=null&&hasPublishedGeometry()&&publishedReplacement!=null&&publishedReplacement.arena()!=null
                &&certificateIdentity.equals(publishedReplacement.arena().certificateIdentity());
    }}
    private static void arenaDiagnostics(JsonObject out,String name,ReplacementAuthority authority){
        var arena=authority==null?null:authority.arena();var value=new JsonObject();value.addProperty("present",arena!=null);
        if(arena!=null){var d=arena.data();var c=d.certificate();var q=c.queryAttestation();
            value.addProperty("identity",d.identity());value.addProperty("certificateIdentity",d.certificateIdentity());
            value.addProperty("definitionIdentity",c.definition().identity());value.addProperty("captureIdentity",d.capture().contentIdentity());value.addProperty("captureBytes",d.declaredBytes());
            value.addProperty("sourceIdentity",d.source().identity());value.addProperty("queryComplete",q.completeCatalogue());value.addProperty("queryResultCount",q.resultCount());
            value.addProperty("queryGeneration",q.snapshotGeneration());value.addProperty("queryRoot",q.snapshotRootIdentity());value.addProperty("queryResultDigest",q.resultDigest());
            var revisions=new com.google.gson.JsonArray();for(var p:c.definition().positions()){var r=new JsonObject();r.addProperty("x",p.x());r.addProperty("y",p.y());r.addProperty("z",p.z());r.addProperty("revision",c.revision(p));revisions.add(r);}value.add("cubeRevisions",revisions);
        }out.add(name,value);
    }
    public boolean isReplacementDeclined(CoverageTicket ticket){checkOwner();synchronized(fence){return ticket!=null&&ticket.view==viewIdentity&&!ticket.declineReason.isEmpty();}}
    public String replacementDeclineReason(CoverageTicket ticket){checkOwner();synchronized(fence){return isReplacementDeclined(ticket)?ticket.declineReason:"";}}
    private static void queryDiagnostics(JsonObject out,String name,ReplacementAuthority authority){
        var data=authority==null||authority.set()==null?null:authority.set().data();
        var query=data==null?null:data.queryAttestation();var item=new JsonObject();
        item.addProperty("present",query!=null);
        if(query!=null){
            item.addProperty("identity",data.queryIdentity());item.addProperty("complete",query.complete());
            item.addProperty("completeCatalogue",query.completeCatalogue());item.addProperty("storeIdentity",query.storeIdentity());
            item.addProperty("indexIdentity",query.indexIdentity());item.addProperty("snapshotGeneration",query.snapshotGeneration());
            item.addProperty("snapshotRootIdentity",query.snapshotRootIdentity());item.addProperty("minX",query.minX());
            item.addProperty("minY",query.minY());item.addProperty("minZ",query.minZ());item.addProperty("maxXExclusive",query.maxX());
            item.addProperty("maxYExclusive",query.maxY());item.addProperty("maxZExclusive",query.maxZ());
            item.addProperty("layoutIdentity",query.layoutIdentity());item.addProperty("policyIdentity",query.policyIdentity());
            item.addProperty("resultDigest",query.resultDigest());item.addProperty("resultCount",query.resultCount());
        }
        out.add(name,item);
    }
    /** Private prepared/commit checks do not require an already installed host pair. */
    private boolean bodyPrepared(RollingDistanceBodyBinding.NativeJoined body,List<SceneReadyCube> ready,
            io.earthplatform.minecraft.visual.NativeVisualPublication visual,ReplacementAuthority replacement){
        if(distanceConfig==null)return body==null;
        if(body==null||replacement==null||replacement.single()==null||!body.isPrepared())return false;
        body.requirePrepared(ready,visual,replacement.single());return true;
    }
    /** Actual submission additionally requires the exact binding installed by the host. */
    private boolean bodyPublishedCurrent(){
        return distanceConfig==null?publishedBody==null:publishedBody!=null&&publishedBody.isPublishedCurrent()
                &&bodyPrepared(publishedBody,coverage,publishedVisual,publishedReplacement);
    }
    private boolean bodyHistorical(PresentationTicket ticket){
        if(distanceConfig==null)return ticket.body==null&&ticket.bodyHistory==null;
        if(ticket.body==null||ticket.bodyHistory==null||!ticket.bodyHistory.isPresentable())return false;
        ticket.bodyHistory.requirePresentable(ticket.body,ticket.exactReady,ticket.visual,ticket.replacement.single());return true;
    }
    private boolean visualCurrent(io.earthplatform.minecraft.visual.NativeVisualPublication visual){
        return distanceConfig==null?visual==null:visual!=null&&visual.isCurrent();
    }
    private boolean visualHistorical(io.earthplatform.minecraft.visual.NativeVisualPublication visual){
        return distanceConfig==null?visual==null:visual!=null&&visual.permitsRetainedPresentation();
    }
    private void validateVisual(io.earthplatform.minecraft.visual.NativeVisualPublication visual,ReplacementAuthority replacement){
        if(distanceConfig==null){require(visual==null,"Legacy renderer cannot accept native-distance coverage");return;}
        require(visual!=null&&replacement!=null&&replacement.single()!=null,"Native-distance renderer cannot downgrade to legacy coverage");
        visual.requireCurrent();var stamp=visual.stamp();var cert=replacement.single().certificate();
        require(stamp.world().equals(world)&&stamp.sessionEpoch()==epoch&&stamp.resourceGeneration()==resources,"Native visual world/session/resource mismatch");
        require(stamp.durableRevision()==cert.durableRevision()&&stamp.captureIdentity().equals(replacement.single().data().capture().earthCapture().contentIdentity()),"Native/far edit authority differs");
        require(stamp.selectedDistanceChunks()==visual.plan().requestedChunks()&&stamp.selectedDistanceEffective(),"Native selected-distance coverage is incomplete");
        require(stamp.sourceIdentity().equals(EarthAlpineDefinition.SOURCE_IDENTITY),"Native visual source differs from exact Earth sampler");
    }
    private static String replacementIdentity(ReplacementPayload data){return data==null?"":data.identity();}
    private boolean replacementCurrent(ReplacementAuthority replacement){try{return replacement==null||replacement.isCurrent();}catch(RuntimeException|Error failure){fail(failure);throw failure;}}
    private void validateReplacement(ReplacementAuthority replacement){
        if(replacement==null){require(layout!=DhLodLayout.ARENA_4096&&layout!=DhLodLayout.EARTH_ALPINE_2048&&arenaDefinitionIdentity==null,"Arena/Earth authority cannot be omitted");return;}
        if(replacement.arena()!=null){
            require(layout==DhLodLayout.ARENA_4096&&originalReplacementCube==null&&replacementWorkspaceIdentity==null,"Legacy/set/arena modes cannot mix");
            var a=replacement.arena();var c=a.certificate();
            require(c.world().equals(world)&&c.epoch()==epoch&&c.resourceGeneration()==resources,"Arena world/session/resource identity mismatch");
            require(a.data().source().identity().equals(sourceIdentity)&&c.queryAttestation().layoutIdentity().equals(layout.identity()),"Exact arena source/layout required");
            require(arenaDefinitionIdentity==null||arenaDefinitionIdentity.equals(c.definition().identity()),"Immutable arena definition required");
        }else if(replacement.set()!=null){
            require(arenaDefinitionIdentity==null&&layout!=DhLodLayout.ARENA_4096,"Arena cannot downgrade to set authority");
            require(originalReplacementCube==null,"Legacy and set replacement authority cannot be mixed");
            var set=replacement.set();var c=set.certificate();var policy=set.data().policy();
            require(layout==DhLodLayout.LONG_4096&&policy.providerIdentity().equals(world)&&policy.sourceIdentity().equals(sourceIdentity),"Exact analytic source/layout policy required");
            require(c.world().equals(world)&&c.epoch()==epoch&&c.resourceGeneration()==resources,"Set world/session/resource identity mismatch");
            var query=set.data().queryAttestation();
            require(indexedReplacementSet==null||indexedReplacementSet==(query!=null),"Indexed and legacy set authority cannot be mixed");
            if(query!=null)require(query.completeCatalogue()&&query.layoutIdentity().equals(layout.identity()),"Complete catalogue query with exact renderer layout required");
            require(replacementWorkspaceIdentity==null||replacementWorkspaceIdentity.equals(replacement.set().data().workspaceIdentity()),"Immutable two-workspace policy required");
            require(replacementFlatPolicyIdentity==null||replacementFlatPolicyIdentity.equals(policy.identity()),"Immutable exact flat policy required");
        }else{
            require(arenaDefinitionIdentity==null&&layout!=DhLodLayout.ARENA_4096,"Arena cannot downgrade to legacy authority");
            require(replacementWorkspaceIdentity==null,"Set authority cannot downgrade to a legacy certificate");
            var certificate=replacement.single().certificate();
            boolean earth = replacement.single().data().capture().earthCapture()!=null;
            require(earth==(layout==DhLodLayout.EARTH_ALPINE_2048),"Exact Earth layout/capture mode required");
            if(earth){
                require(EarthReplacementAdmission.MATERIAL_POLICY.equals(certificate.materialPolicy()),"Exact Earth replacement material policy required");
                require(EarthAlpineDefinition.DEFINITION_IDENTITY.equals(sourceIdentity)&&sourceIdentity.equals(world.datasetVersion()),"Exact prepared Earth source required");
                require(certificate.editedCube().equals(CubePos.fromBlock(EarthAlpineDefinition.ANCHOR_X,EarthAlpineDefinition.GROUND_Y,EarthAlpineDefinition.ANCHOR_Z)),"Exact fixed Earth edit target required");
            }
            require(originalReplacementCube==null||originalReplacementCube.equals(certificate.editedCube()),"Only the original replacement region is supported");
            require(certificate.world().equals(world)&&certificate.epoch()==epoch&&certificate.resourceGeneration()==resources,"Replacement world/session/resource identity mismatch");
        }
        require(replacementCurrent(replacement)&&!closed&&!invalidated,"Current server replacement certificate required");
    }
    private static boolean coversAll(List<SceneReadyCube> ready,ReplacementPayload replacement){
        for(var volume:replacement.volumes())if(!covers(ready,volume))return false;
        return true;
    }
    /** Exact whole-cube union proves every voxel in the replacement volume is actually ready. */
    private static boolean covers(List<SceneReadyCube> ready,DhTerrainGeometry.Volume v){
        Set<CubePos> positions=new HashSet<>();for(var r:ready)positions.add(r.position());
        for(long x=Math.floorDiv(v.minX(),16);x<=Math.floorDiv(v.maxXExclusive()-1,16);x++)
            for(long y=Math.floorDiv(v.minY(),16);y<=Math.floorDiv(v.maxYExclusive()-1,16);y++)
                for(long z=Math.floorDiv(v.minZ(),16);z<=Math.floorDiv(v.maxZExclusive()-1,16);z++)
                    if(!positions.contains(new CubePos(Math.toIntExact(x),Math.toIntExact(y),Math.toIntExact(z))))return false;
        return true;
    }
    /** Only a typed, pre-upload geometry refusal with complete candidate native coverage can decline. */
    private boolean decline(Pending p,String reason){
        if(layout==DhLodLayout.EARTH_ALPINE_2048||p==null||p.replacement==null||!coversAll(p.ready,p.replacement.data())
                ||publishedReplacement!=null&&!coversAll(p.ready,publishedReplacement.data()))return false;
        p.ticket.declineReason=reason==null?"Replacement declined":reason.substring(0,Math.min(256,reason.length()));
        lastReplacementDecline=p.ticket.declineReason;replacementDeclines++;discardPending();return true;
    }
    /** Reject a whole transaction that can never fit, before any private upload or old retirement. */
    private void preflightUnion(Geometry payload){
        var union=distanceConfig==null?DhReplacementGpuBudget.assess(cache.currentPayloads(),payload.batch().tiles(),cache.residentOwners()+driver.pendingOwners(),cache.residentBytes())
                :DhReplacementGpuBudget.assessDistance(cache.currentPayloads(),payload.batch().tiles(),cache.residentOwners()+driver.pendingOwners(),cache.residentBytes()+driver.pendingBytes(),distanceConfig.geometry().maxOwners());
        boolean fits=distanceConfig==null?DhReplacementGpuBudget.fits(union):union.owners()<=distanceConfig.maxResidentOwners()&&union.bytes()<=distanceConfig.maxGpuBytes();
        if(!fits){
            if(payload.replacement()!=null)throw new DhReplacementComposition.Declined("Combined old/new GPU owner or byte budget exceeded before upload");
            throw new IllegalArgumentException("Combined old/new GPU budget exceeded before upload");
        }
    }
    private void begin(){require(!busy,"Reentrant prepared renderer operation");busy=true;}
    private void checkOwner(){require(Thread.currentThread()==owner,"Prepared LOD owner thread required");}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static void require(boolean valid,String message){if(!valid)throw new IllegalStateException(message);}
    private static Throwable append(Throwable first,Throwable next){if(first==null)return next;if(first!=next)first.addSuppressed(next);return first;}
    private static RuntimeException propagate(Throwable failure){if(failure instanceof Error e)throw e;if(failure instanceof RuntimeException e)return e;return new IllegalStateException(failure);}
}
