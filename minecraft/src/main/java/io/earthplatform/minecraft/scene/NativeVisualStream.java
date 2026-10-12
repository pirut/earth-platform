package io.earthplatform.minecraft.scene;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.EarthReplacementCapture;
import io.earthplatform.minecraft.visual.*;
import java.util.*;
import java.util.concurrent.*;
import org.joml.Matrix4f;

/** One physical CPU writer, bounded output mailbox, lazy retained GPU pages and exact joined completion. */
public final class NativeVisualStream implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private NativeVisualCapsuleCapture.Sealed sealed;
    private final NativeVisualBudget gpuBudget;
    private EarthAlpineDefinition definition;
    private final List<NativeDistanceCpuBudget.Reservation> cpuReservations=new ArrayList<>();
    private final ExecutorService executor;
    private final Map<String,PageOwner> cache=new LinkedHashMap<>();
    private final Set<NativeVisualGpuPiece> physical=Collections.newSetFromMap(new IdentityHashMap<>());
    private Set<String> retainedPresentation=Set.of();
    private Job job;
    private Progress lastProgress;
    private final java.util.concurrent.atomic.AtomicReference<EarthDistanceSurfacePlan.Source> source=new java.util.concurrent.atomic.AtomicReference<>();
    private boolean closed,failed;
    private long uploadedPieces,completedRequests,reusedPages;
    private long pumpCalls,pumpOwnerWallNanos,pumpMaxOwnerWallNanos,successfulUploadWallNanos,maxSuccessfulUploadWallNanos;
    private long pumpNoRequestStops,pumpUploadLimitStops,pumpTimeLimitStops,pumpEmptyMailboxStops,pumpFailedStops;
    private long lastPumpWallNanos,lastPumpBudgetNanos;
    private int lastPumpUploadLimit,lastPumpUploads;
    private PumpStopReason lastPumpStop=PumpStopReason.NONE;
    private WorkerCounters lastWorkerCounters;
    public enum PumpStopReason { NONE, NO_REQUEST, UPLOAD_LIMIT, TIME_LIMIT, EMPTY_MAILBOX, FAILED }
    public enum WorkerStopReason { NOT_STARTED, RUNNING, COMPLETED, CANCELLED, FAILED }
    public enum WorkerPhase { IDLE, SOURCE, PLAN, HALO, MESH, OUTPUT_OFFER }
    /** Render-owner elapsed wall time, never CPU time. Stops preserve count, then time, then poll precedence.
     * Successful upload time covers gpu.upload() only, including its synchronous driver/staging work.
     * Rejected entry/ownership/argument checks are not admitted pump calls. No field credits useful progress.
     */
    public record PumpTiming(long calls,long ownerWallNanos,long maxOwnerWallNanos,
            long successfulUploadWallNanos,long maxSuccessfulUploadWallNanos,
            long noRequestStops,long uploadLimitStops,long timeLimitStops,long emptyMailboxStops,long failedStops,
            PumpStopReason lastStop,long lastWallNanos,int lastUploadLimit,long lastBudgetNanos,int lastUploads){}
    /** Per-request elapsed wall time. Completed phase totals include partial work on failure/cancellation.
     * Timed-offer time includes waiting AND queue/lock overhead; it is not an exact blocked-only or CPU timer.
     * Live snapshots are non-atomic observations across volatile counters; terminal stop publishes all totals.
     * outputsOffered counts both the one Plans output and Piece outputs, never completed/uploaded pieces.
     * Each false timed offer is an exact timeout. Counters include completed attempts; an in-flight attempt
     * is identified by OUTPUT_OFFER. workerWallNanos is finalized at worker exit, and is zero until then.
     */
    public record WorkerTiming(WorkerStopReason stop,WorkerPhase phase,long workerWallNanos,
            long sourceWallNanos,long planWallNanos,long haloWallNanos,long meshWallNanos,
            long outputOfferWallNanos,long maxOutputOfferWallNanos,long outputOfferAttempts,
            long outputOfferTimeouts,long outputsOffered){
        private static final WorkerTiming EMPTY=new WorkerTiming(WorkerStopReason.NOT_STARTED,WorkerPhase.IDLE,0,0,0,0,0,0,0,0,0,0);
    }
    /** A bounded scalar-only holder can outlive Job without retaining its capture, source, plans or queue.
     * One worker writes these volatile fields; snapshots acquire terminal stop before reading final totals.
     * Updates use saturating nonthrowing arithmetic, and ownership release never allocates a timing snapshot.
     */
    private static final class WorkerCounters {
        volatile WorkerStopReason stop=WorkerStopReason.NOT_STARTED;
        volatile WorkerPhase phase=WorkerPhase.IDLE;
        volatile long startedNanos,workerWallNanos,sourceWallNanos,planWallNanos,haloWallNanos,meshWallNanos;
        volatile long outputOfferWallNanos,maxOutputOfferWallNanos,outputOfferAttempts,outputOfferTimeouts,outputsOffered;
        WorkerTiming snapshot(){
            WorkerStopReason observed=stop;
            return new WorkerTiming(observed,phase,workerWallNanos,sourceWallNanos,planWallNanos,haloWallNanos,meshWallNanos,
                    outputOfferWallNanos,maxOutputOfferWallNanos,outputOfferAttempts,outputOfferTimeouts,outputsOffered);
        }
    }
    public record Evidence(int physicalPieces,long gpuBytes,long peakGpuBytes,int peakPhysicalPieces,int cachedPages,
                           boolean workerActive,int queuedPieces,long retainedCpuBytes,long uploadedPieces,long completedRequests,long reusedPages,boolean closed,boolean failed,PumpTiming pumpTiming,WorkerTiming workerTiming){}
    /** Immutable small evidence only; null until the first request, retained after its physical drain.
     * Counters are per request and credit completed work, never queue activity, polls or retries.
     * Worker counters are single-writer volatile longs; uploadedPieces belongs to the render owner.
     */
    public record Progress(NativeVisualStamp stamp,CubePos center,String captureIdentity,
            long sampledColumnsDone,long planTilesDone,long haloRowsDone,long faceScansDone,long producedPieces,long uploadedPieces,
            long sourceColumnsBound,long planTilesBound,long pagesBound,long haloRowsBound,long faceScansBound,long piecesBound,
            boolean workerExited){}
    private record WorkBounds(long pages,long haloRows,long faceScans,long pieces){
        private static final WorkBounds CONSERVATIVE=new WorkBounds(2*NativeDistanceLimits.PLAN_PAGES,
                2*NativeDistanceLimits.PLAN_PAGES*66,2*NativeDistanceLimits.PLAN_PAGES*64*4096*6,
                2*NativeDistanceLimits.PLAN_PIECES);
    }
    private static final class PageOwner {
        final EarthDistanceSurfacePlan.Page page;final List<NativeVisualGpuPiece> pieces=new ArrayList<>();boolean complete;
        PageOwner(EarthDistanceSurfacePlan.Page page){this.page=page;}
    }
    private sealed interface Output permits Plans,Piece{}
    private record Plans(EarthDistanceSurfacePlan draw,EarthDistanceSurfacePlan cache) implements Output{}
    private record Piece(NativeVisualCpuPiece value) implements Output{}
    private static final class Job {
        final NativeVisualStamp stamp;final CubePos center;final EarthReplacementCapture capture;
        final ArrayBlockingQueue<Output> output=new ArrayBlockingQueue<>(NativeDistanceLimits.CPU_MAILBOX_PIECES);
        final NativeVisualCpuBuilder.Cancellation cancel=new NativeVisualCpuBuilder.Cancellation();
        final WorkerCounters timing=new WorkerCounters();
        volatile boolean exited;volatile Throwable failure;volatile long workerBytes;
        volatile long sampledColumnsDone,planTilesDone,haloRowsDone,faceScansDone,producedPieces;
        long uploadedPieces; // Only the render owner publishes actual successful uploads.
        volatile long sourceColumnsBound=NativeDistanceLimits.SOURCE_COLUMNS;
        volatile long planTilesBound=2*(NativeDistanceLimits.PLAN_COLUMNS/(64*64));
        volatile WorkBounds bounds=WorkBounds.CONSERVATIVE;
        EarthDistanceSurfacePlan drawPlan,cachePlan;
        Progress progress(){
            // Acquiring exited first makes a terminal snapshot include every final worker write.
            boolean done=exited;WorkBounds b=bounds;
            return new Progress(stamp,center,stamp.captureIdentity(),sampledColumnsDone,planTilesDone,haloRowsDone,
                    faceScansDone,producedPieces,uploadedPieces,sourceColumnsBound,planTilesBound,b.pages,b.haloRows,b.faceScans,b.pieces,done);
        }
        Job(NativeVisualStamp stamp,CubePos center,EarthReplacementCapture capture){this.stamp=stamp;this.center=center;this.capture=capture;}
    }
    public NativeVisualStream(EarthAlpineDefinition definition,NativeVisualCapsuleCapture.Sealed sealed,NativeVisualBudget gpuBudget){
        this.definition=Objects.requireNonNull(definition);this.sealed=Objects.requireNonNull(sealed);this.gpuBudget=Objects.requireNonNull(gpuBudget);sealed.lease().requireCurrent();
        try{
            var b=NativeDistanceCpuBudget.global();
            cpuReservations.add(b.reserve(NativeDistanceCpuBudget.Pool.SOURCE,NativeDistanceLimits.SOURCE_BYTES));
            cpuReservations.add(b.reserve(NativeDistanceCpuBudget.Pool.COVERAGE,64L<<20));
            cpuReservations.add(b.reserve(NativeDistanceCpuBudget.Pool.PLAN,16L<<20));
            cpuReservations.add(b.reserve(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD,8L<<20));
            executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"earth-native-visual-031");t.setDaemon(true);return t;});
        }catch(RuntimeException|Error failure){for(var r:cpuReservations)r.releaseAfterPhysicalDrain();throw failure;}
    }
    public void request(NativeVisualStamp stamp,CubePos center,EarthReplacementCapture capture){
        checkOwner();requireOpen();Objects.requireNonNull(stamp);Objects.requireNonNull(center);Objects.requireNonNull(capture);
        if(job!=null){if(job.stamp.equals(stamp)&&job.center.equals(center)&&job.capture.contentIdentity().equals(capture.contentIdentity()))return;throw new IllegalStateException("Drain the prior physical native request before replacing it");}
        if(!stamp.captureIdentity().equals(capture.contentIdentity())||!stamp.capsuleIdentity().equals(sealed.capsule().identity().bytesDigest()))throw new IllegalArgumentException("Native request identity differs from immutable inputs");
        stamp.requireSelectedDistanceEffective();
        final Job next=new Job(stamp,center,capture);job=next;
        final Set<String> reused=new HashSet<>();for(var e:cache.entrySet())if(e.getValue().complete)reused.add(e.getKey());
        var work=new Worker(next,Set.copyOf(reused),definition,sealed.capsule(),source);
        executor.execute(work);
    }
    /** The executor retains only immutable source/palette inputs and its bounded output/cancellation owner. */
    private record Worker(Job work,Set<String> reused,EarthAlpineDefinition definition,NativeVisualCapsule capsule,
            java.util.concurrent.atomic.AtomicReference<EarthDistanceSurfacePlan.Source> sourceHolder) implements Runnable {
    @Override public void run(){
        WorkerCounters timing=work.timing;timing.startedNanos=System.nanoTime();timing.stop=WorkerStopReason.RUNNING;
        WorkerStopReason stop=WorkerStopReason.COMPLETED;
        try{
            checkCancelled(work);
            var source=sourceHolder.get();
            if(source==null){
                long sourceStart=System.nanoTime();timing.phase=WorkerPhase.SOURCE;
                try{source=EarthDistanceSurfacePlan.Source.prepare(definition,32,1,NativeDistanceLimits.sourceLimits(),done->{work.sampledColumnsDone=done;checkCancelled(work);});}
                finally{timing.sourceWallNanos=addTiming(timing.sourceWallNanos,elapsedSince(sourceStart));timing.phase=WorkerPhase.IDLE;}
                sourceHolder.set(source);work.sourceColumnsBound=source.sampledColumns();
            }else work.sourceColumnsBound=0; // Reusing completed source is no work for this request.
            checkCancelled(work);
            EarthDistanceSurfacePlan draw,cachePlan;long planStart=System.nanoTime();timing.phase=WorkerPhase.PLAN;
            try{
                draw=EarthDistanceSurfacePlan.build(source,work.capture,work.center,work.stamp.effectiveDistanceChunks(),0,NativeDistanceLimits.sourceLimits(),done->{work.planTilesDone=done;checkCancelled(work);});
                long drawTiles=draw.bounds().columns()/(64*64);
                cachePlan=EarthDistanceSurfacePlan.build(source,work.capture,work.center,work.stamp.effectiveDistanceChunks(),1,NativeDistanceLimits.sourceLimits(),done->{work.planTilesDone=drawTiles+done;checkCancelled(work);});
                work.planTilesBound=drawTiles+cachePlan.bounds().columns()/(64*64);
            }finally{timing.planWallNanos=addTiming(timing.planWallNanos,elapsedSince(planStart));timing.phase=WorkerPhase.IDLE;}
            // Draw pages first, then one retained cache row. The cache row never creates clipping authority.
            var pages=new ArrayList<EarthDistanceSurfacePlan.Page>(draw.pages());Set<String> seen=new HashSet<>();for(var p:pages)seen.add(p.identity());
            for(var p:cachePlan.pages())if(seen.add(p.identity()))pages.add(p);
            long pageCount=0,scans=0,pieces=0;
            for(var page:pages)if(!reused.contains(page.identity())){
                pageCount++;scans=Math.addExact(scans,(long)page.memberCount()*4096*6);pieces=Math.addExact(pieces,page.pieces());
            }
            work.bounds=new WorkBounds(pageCount,pageCount*66,scans,pieces);
            offer(work,new Plans(draw,cachePlan));
            for(var page:pages){
                checkCancelled(work);if(reused.contains(page.identity()))continue;
                NativeVisualCpuBuilder.Input input;long haloStart=System.nanoTime();timing.phase=WorkerPhase.HALO;
                try{
                    var key=page.key();byte[] halo=new byte[NativeVisualCpuBuilder.HALO_BYTES];work.workerBytes=2L*halo.length;
                    for(int y=-1;y<=64;y++){
                        checkCancelled(work);
                        for(int z=-1;z<=64;z++)for(int x=-1;x<=64;x++)halo[NativeVisualCpuBuilder.Input.haloIndex(x,y,z)]=(byte)cachePlan.materialAt(key.minX()+x,key.minY()+y,key.minZ()+z);
                        work.haloRowsDone++; // All 66x66 material lookups and stores have completed.
                    }
                    int[] members=new int[page.memberCount()];int n=0;for(int i=0;i<64;i++)if((page.memberMask()&(1L<<i))!=0)members[n++]=i;
                    input=new NativeVisualCpuBuilder.Input(Math.toIntExact(key.minX()),Math.toIntExact(key.minY()),Math.toIntExact(key.minZ()),halo,members,page.identity());halo=null;
                }finally{timing.haloWallNanos=addTiming(timing.haloWallNanos,elapsedSince(haloStart));timing.phase=WorkerPhase.IDLE;}
                long previousScans=work.faceScansDone;
                var builder=new NativeVisualCpuBuilder(input,capsule,work.cancel,done->work.faceScansDone=previousScans+done);
                work.workerBytes=input.retainedBytes()+NativeVisualCpuPiece.CAPACITY_BYTES;
                while(true){
                    NativeVisualCpuPiece piece;long meshStart=System.nanoTime();timing.phase=WorkerPhase.MESH;
                    try{piece=builder.nextPiece();}
                    finally{timing.meshWallNanos=addTiming(timing.meshWallNanos,elapsedSince(meshStart));timing.phase=WorkerPhase.IDLE;}
                    work.producedPieces++;offer(work,new Piece(piece));if(piece.completesRequest())break;
                }
                work.workerBytes=0;
            }
        }catch(CancellationException cancellation){if(!work.cancel.isCancelled()){work.failure=cancellation;stop=WorkerStopReason.FAILED;}else stop=WorkerStopReason.CANCELLED;}
        catch(RuntimeException|Error error){work.failure=error;stop=WorkerStopReason.FAILED;}
        finally{timing.workerWallNanos=elapsedSince(timing.startedNanos);timing.phase=WorkerPhase.IDLE;timing.stop=stop;work.workerBytes=0;work.exited=true;}
    }
    }
    private static void checkCancelled(Job work){if(work.cancel.isCancelled())throw new CancellationException("Native visual generation cancelled");}
    private static void offer(Job work,Output output){
        WorkerCounters timing=work.timing;
        try{while(true){
            checkCancelled(work);long offerStart=System.nanoTime();timing.phase=WorkerPhase.OUTPUT_OFFER;
            try{
                if(work.output.offer(output,50,TimeUnit.MILLISECONDS)){timing.outputsOffered=addTiming(timing.outputsOffered,1);return;}
                timing.outputOfferTimeouts=addTiming(timing.outputOfferTimeouts,1);
            }finally{
                long elapsed=elapsedSince(offerStart);timing.outputOfferWallNanos=addTiming(timing.outputOfferWallNanos,elapsed);
                timing.maxOutputOfferWallNanos=Math.max(timing.maxOutputOfferWallNanos,elapsed);
                timing.outputOfferAttempts=addTiming(timing.outputOfferAttempts,1);timing.phase=WorkerPhase.IDLE;
            }
        }}catch(InterruptedException error){Thread.currentThread().interrupt();throw new CancellationException("Native visual writer interrupted");}
    }
    /** Owner soft slice. One driver operation is not interruptible; timing is observational wall elapsed. */
    public int pump(int maxUploads,long maxNanos){
        checkOwner();requireOpen();if(maxUploads<1||maxNanos<1)throw new IllegalArgumentException();
        long start=System.nanoTime();int count=0;PumpStopReason stop=PumpStopReason.NONE;
        try{
            if(job==null){stop=PumpStopReason.NO_REQUEST;return 0;}
            while(true){
                if(count>=maxUploads){stop=PumpStopReason.UPLOAD_LIMIT;break;}
                if(System.nanoTime()-start>=maxNanos){stop=PumpStopReason.TIME_LIMIT;break;}
                Output output=job.output.poll();if(output==null){stop=PumpStopReason.EMPTY_MAILBOX;break;}
                if(output instanceof Plans plans){job.drawPlan=plans.draw;job.cachePlan=plans.cache;trimTo(plans.cache);for(var p:plans.cache.pages())if(cache.containsKey(p.identity()))reusedPages++;}
                else if(output instanceof Piece next){
                    if(job.cachePlan==null)throw new IllegalStateException("Native piece preceded its exact plan");
                    var cpu=next.value;var page=job.cachePlan.pageIndex().get(NativePageKey.containing(cpu.originX(),cpu.originY(),cpu.originZ()));
                    if(page==null||!page.identity().equals(cpu.requestStamp()))throw new IllegalStateException("Native worker emitted foreign page");
                    var gpu=new NativeVisualGpuPiece(cpu,sealed.lease(),gpuBudget);physical.add(gpu); // owner exists before allocation
                    PageOwner pageOwner=cache.computeIfAbsent(page.identity(),unused->new PageOwner(page));
                    if(pageOwner.complete||cpu.ordinal()!=pageOwner.pieces.size())throw new IllegalStateException("Duplicate/out-of-order native page piece");
                    pageOwner.pieces.add(gpu);long uploadStart=System.nanoTime();gpu.upload();
                    long uploadElapsed=elapsedSince(uploadStart);successfulUploadWallNanos=addTiming(successfulUploadWallNanos,uploadElapsed);maxSuccessfulUploadWallNanos=Math.max(maxSuccessfulUploadWallNanos,uploadElapsed);
                    uploadedPieces++;job.uploadedPieces++;count++;
                    if(cpu.completesRequest()){
                        long actual=pageOwner.pieces.stream().mapToLong(NativeVisualGpuPiece::quads).sum();
                        if(actual!=page.quads()||pageOwner.pieces.size()!=page.pieces())throw new IllegalStateException("Native actual mesh count differs from source census");
                        pageOwner.complete=true;
                    }
                }
            }
            if(job.failure!=null){failed=true;rethrow(job.failure);}
        }catch(RuntimeException|Error error){stop=PumpStopReason.FAILED;failed=true;if(job!=null)job.cancel.cancel();throw error;}
        finally{recordPump(stop,start,maxUploads,maxNanos,count);}
        return count;
    }
    private void recordPump(PumpStopReason stop,long start,int maxUploads,long maxNanos,int count){
        long elapsed=elapsedSince(start);pumpCalls=addTiming(pumpCalls,1);pumpOwnerWallNanos=addTiming(pumpOwnerWallNanos,elapsed);pumpMaxOwnerWallNanos=Math.max(pumpMaxOwnerWallNanos,elapsed);
        if(stop==PumpStopReason.NO_REQUEST)pumpNoRequestStops=addTiming(pumpNoRequestStops,1);
        else if(stop==PumpStopReason.UPLOAD_LIMIT)pumpUploadLimitStops=addTiming(pumpUploadLimitStops,1);
        else if(stop==PumpStopReason.TIME_LIMIT)pumpTimeLimitStops=addTiming(pumpTimeLimitStops,1);
        else if(stop==PumpStopReason.EMPTY_MAILBOX)pumpEmptyMailboxStops=addTiming(pumpEmptyMailboxStops,1);
        else if(stop==PumpStopReason.FAILED)pumpFailedStops=addTiming(pumpFailedStops,1);
        lastPumpStop=stop;lastPumpWallNanos=elapsed;lastPumpUploadLimit=maxUploads;lastPumpBudgetNanos=maxNanos;lastPumpUploads=count;
    }
    private PumpTiming pumpTiming(){return new PumpTiming(pumpCalls,pumpOwnerWallNanos,pumpMaxOwnerWallNanos,successfulUploadWallNanos,maxSuccessfulUploadWallNanos,
            pumpNoRequestStops,pumpUploadLimitStops,pumpTimeLimitStops,pumpEmptyMailboxStops,pumpFailedStops,lastPumpStop,lastPumpWallNanos,lastPumpUploadLimit,lastPumpBudgetNanos,lastPumpUploads);}
    private WorkerTiming workerTiming(){WorkerCounters counters=job==null?lastWorkerCounters:job.timing;return counters==null?WorkerTiming.EMPTY:counters.snapshot();}
    private static long elapsedSince(long start){return Math.max(0,System.nanoTime()-start);}
    private static long addTiming(long total,long delta){return total>=Long.MAX_VALUE-delta?Long.MAX_VALUE:total+delta;}

    private void trimTo(EarthDistanceSurfacePlan plan){
        Set<String> keep=new HashSet<>(retainedPresentation);for(var page:plan.pages())keep.add(page.identity());
        for(var iterator=cache.entrySet().iterator();iterator.hasNext();){var e=iterator.next();if(keep.contains(e.getKey()))continue;closePage(e.getValue());iterator.remove();}
    }
    private void closePage(PageOwner page){Throwable failure=null;for(var piece:page.pieces)try{piece.close();if(piece.closeComplete())physical.remove(piece);}catch(RuntimeException|Error error){failure=SceneBuildStepper.append(failure,error);}SceneBuildStepper.rethrow(failure);}
    public boolean drawReady(){checkOwner();requireOpen();if(job==null||job.drawPlan==null)return false;for(var p:job.drawPlan.pages()){var found=cache.get(p.identity());if(found==null||!found.complete)return false;}return true;}
    public NativeVisualPublication publication(Matrix4f actualProjection,NativeVisualPublication.CameraDomain camera,java.util.function.BooleanSupplier retainedGuard){
        checkOwner();if(!drawReady())throw new IllegalStateException("Exact selected native draw pages are not complete");
        var parts=new ArrayList<NativeVisualGpuPiece.Completion>();for(var p:job.drawPlan.pages())for(var piece:cache.get(p.identity()).pieces)parts.add(piece.completion());
        return new NativeVisualPublication(job.stamp,job.drawPlan,parts,actualProjection,camera,retainedGuard);
    }
    public void retainPresentation(NativeVisualPublication publication){checkOwner();publication.requireCurrent();Set<String> keep=new HashSet<>();for(var p:publication.plan().pages())keep.add(p.identity());retainedPresentation=Set.copyOf(keep);}
    /** Physical writer must exit before its inputs/queue can be released or another request starts. */
    public boolean finishRequest(){checkOwner();if(job==null)return true;if(!job.exited||!job.output.isEmpty())return false;if(job.failure!=null)rethrow(job.failure);if(!drawReady())throw new IllegalStateException("Native writer exited with incomplete draw pages");lastProgress=job.progress();lastWorkerCounters=job.timing;job=null;completedRequests++;return true;}
    public void cancelRequest(){checkOwner();if(job!=null)job.cancel.cancel();}
    public boolean drainCancelled(){checkOwner();if(job==null)return true;if(!job.cancel.isCancelled())throw new IllegalStateException("Native cancellation was not requested");if(!job.exited)return false;job.output.clear();for(var it=cache.entrySet().iterator();it.hasNext();){var e=it.next();if(e.getValue().complete)continue;closePage(e.getValue());it.remove();}lastProgress=job.progress();lastWorkerCounters=job.timing;job=null;return true;}
    public Progress progress(){checkOwner();return job==null?lastProgress:job.progress();}
    public EarthDistanceSurfacePlan drawPlan(){checkOwner();return job==null?null:job.drawPlan;}
    public Evidence evidence(){checkOwner();var b=gpuBudget.evidence();var sampled=source.get();long cpu=sampled==null?0:sampled.sourceBytes();cpu+=sealed==null?0:sealed.capsule().retainedBytes();if(job!=null){cpu+=job.workerBytes;for(var out:job.output)if(out instanceof Piece p)cpu+=p.value.retainedBytes();}for(var piece:physical)cpu+=piece.retainedCpuBytes();return new Evidence(physical.size(),b.bytes(),b.peakBytes(),b.peakPieces(),cache.size(),job!=null&&!job.exited,job==null?0:job.output.size(),cpu,uploadedPieces,completedRequests,reusedPages,closed,failed,pumpTiming(),workerTiming());}
    @Override public void close(){
        checkOwner();closed=true;if(job!=null)job.cancel.cancel();executor.shutdown();Throwable failure=null;
        for(var piece:new ArrayList<>(physical))try{piece.close();if(piece.closeComplete())physical.remove(piece);}catch(RuntimeException|Error error){failure=SceneBuildStepper.append(failure,error);}
        if(job==null||job.exited){if(job!=null){lastProgress=job.progress();lastWorkerCounters=job.timing;job.output.clear();}job=null;cache.clear();retainedPresentation=Set.of();source.set(null);if(physical.isEmpty()&&executor.isTerminated()){definition=null;sealed=null;for(var reservation:cpuReservations)reservation.releaseAfterPhysicalDrain();cpuReservations.clear();}}
        SceneBuildStepper.rethrow(failure);
    }
    public boolean closeComplete(){checkOwner();return closed&&physical.isEmpty()&&job==null&&executor.isTerminated()&&cpuReservations.isEmpty();}
    private void requireOpen(){if(closed||failed)throw new IllegalStateException("Native visual stream is fenced");sealed.lease().requireCurrent();}
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Native stream requires its render owner");}
    private static void rethrow(Throwable failure){if(failure instanceof RuntimeException r)throw r;if(failure instanceof Error e)throw e;throw new IllegalStateException(failure);}
}
