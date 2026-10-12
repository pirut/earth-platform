package io.earthplatform.minecraft.lod;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Objects;

/** Optional owner-thread attribution. Never a GPU timer or publication authority. */
public final class RenderSubmissionProbe {
    public enum Stage {
        FAR_DH,FAR_NATIVE,NEAR_DH,NEAR_NATIVE,LEGACY_DH,LEGACY_NATIVE,DEPTH_SETUP,DEPTH_RESTORE,
        NATIVE_OWNER_CALL,NATIVE_VALIDATED_BODY,NATIVE_VALIDITY_CHECK,NATIVE_STATE_CAPTURE,
        NATIVE_RENDER_SETUP,NATIVE_VBO_BIND,NATIVE_DRAW_WITH_SHADER,NATIVE_SHADER_RESTORE,NATIVE_STATE_RESTORE
    }
    interface Clock { long nanos(); long cpuNanos(); long utcMillis(); String cpuStatus(); }
    private static final Stage[] STAGES=Stage.values();
    private static final int ELAPSED_SAMPLES=3,FIRST_PHASE=Stage.NATIVE_OWNER_CALL.ordinal(),PHASES=9;
    private static final int STARTS=0,CALLS=1,INNER=2,OUTER=3,CPU=4,WALL_VALID=5,WALL_BAD=6,
            CPU_VALID=7,CPU_MISSING=8,CPU_BACKWARDS=9,FAILURES=10,FIELDS=11;
    private static final long SLOW_NANOS=50_000_000;
    public static final int MAX_NATIVE_DETAIL_CHARS=160;
    private final boolean enabled;
    private final Thread owner;
    private final Clock clock;
    private final String initialCpuStatus;
    private final Slot[] slots;
    private final Stats[] stats;
    /** One reusable accumulator, not a frame history or one array per depth range. */
    private final long[] batch;
    private long frame=-1,epoch=-1,publication=-1,resources=-1,displayedPublication=-1,presentedCoverage=-1,presentedRevision=-1,
            serial,cpuReadFailures,wallReadFailures,utcReadFailures;
    private boolean historical,saturated,batchSaturated;
    private int nativeParent=-1,nativeTop=-1;

    /** Only immutable value context is retained; no mesh, guard, session or callback references. */
    public record Sample(long frame,long epoch,long publication,long resourceGeneration,boolean historical,
                         int declaredOwners,long declaredVertices,long wallBeforeCpuStart,long wallStart,
                         long wallEnd,long wallAfterCpuEnd,long cpuStart,long cpuEnd,long innerElapsedNanos,
                         long outerElapsedNanos,long cpuDeltaNanos,long cpuExcessOverOuterNanos,long observedUtcMillis,
                         Stage stage,long token,Stage nativeDepthStage,long nativeParentToken,long parentOwnerToken,
                         String detail,long cubeX,long cubeY,long cubeZ,int layer,long attempt,
                         long displayedPublication,long presentedCoverageGeneration,long presentedRevision,int wallReadFailureMask,
                         NativeBatchTotals nativeBatch) {}
    public record PhaseTotals(Stage phase,long starts,long calls,long rawInnerElapsedNanos,long rawOuterElapsedNanos,
                              long totalValidThreadCpuNanos,long wallValidSamples,long wallAnomalies,long cpuAvailableSamples,
                              long cpuUnavailableSamples,long cpuBackwardsSamples,long measurementFailures) {}
    public record CpuResiduals(long parentMinusOwnersNanos,long ownersMinusBodiesNanos,long bodiesMinusLeavesNanos) {}
    /** Fixed nine-row snapshot copied only when its own native parent qualifies for retention. */
    public record NativeBatchTotals(PhaseTotals[] phases,long parentMinusOwnerOuterNanos,
                                   long ownerInnerMinusBodyOuterNanos,long bodyInnerMinusLeafOuterNanos,
                                   boolean completeWallCoverage,boolean completeCpuCoverage,boolean countersSaturated,
                                   CpuResiduals cpuResiduals) {}
    private static final class Slot {
        boolean active;long token,before,start,cpu,frame,epoch,publication,resources,displayedPublication,presentedCoverage,presentedRevision;
        boolean historical;int owners,wallFailureMask,enclosing=-1;long vertices,nativeParentToken,parentOwnerToken,cubeX,cubeY,cubeZ,attempt;int layer;
        Stage nativeDepthStage;String detail;
    }
    private static final class Stats {
        long calls,totalInner,totalOuter,totalCpu,maxInner,maxOuter,maxCpu,cpuAvailable,cpuUnavailable,cpuBackwards,
                wallAnomalies,cpuExcessCount,maxCpuExcess,slowCalls,measurementFailures;
        final Sample[] worstElapsed=new Sample[ELAPSED_SAMPLES];
        Sample worstCpuExcess;
    }
    public RenderSubmissionProbe(boolean enabled){this(enabled,enabled?new SystemClock():null);}
    RenderSubmissionProbe(boolean enabled,Clock clock){
        this.enabled=enabled;owner=enabled?Thread.currentThread():null;this.clock=enabled?Objects.requireNonNull(clock):null;
        initialCpuStatus=enabled?clock.cpuStatus():"DISABLED";
        slots=enabled?new Slot[STAGES.length]:null;stats=enabled?new Stats[STAGES.length]:null;
        batch=enabled?new long[PHASES*FIELDS]:null;
        if(enabled)for(int i=0;i<slots.length;i++){slots[i]=new Slot();stats[i]=new Stats();}
    }
    public void setContext(long frame,long epoch,long publication,long resourceGeneration,boolean historical){
        setContext(frame,epoch,publication,resourceGeneration,historical,-1,-1,-1);
    }
    public void setContext(long frame,long epoch,long publication,long resourceGeneration,boolean historical,
                           long displayedPublication,long presentedCoverageGeneration,long presentedRevision){
        if(!enabled)return;checkOwner();for(var slot:slots)if(slot.active)throw new IllegalStateException("Cannot change context inside a measured span");
        this.frame=frame;this.epoch=epoch;this.publication=publication;resources=resourceGeneration;this.historical=historical;
        this.displayedPublication=displayedPublication;presentedCoverage=presentedCoverageGeneration;this.presentedRevision=presentedRevision;
    }
    public long start(Stage stage){return start(stage,-1,-1);}
    public long start(Stage stage,int declaredOwners,long declaredVertices){
        if(!enabled)return 0;checkOwner();int index=Objects.requireNonNull(stage).ordinal();
        if(isPhase(index))throw new IllegalArgumentException("Native phases require startNativePhase");
        if(slots[index].active)throw new IllegalStateException("Stage is already active");
        if(declaredOwners< -1||declaredVertices< -1)throw new IllegalArgumentException("Unknown counts use exactly−1");
        if(isNative(stage)&&nativeParent!=-1)throw new IllegalStateException("Only one native parent batch may be active");
        long token=Math.incrementExact(serial);serial=token;
        if(isNative(stage)){Arrays.fill(batch,0);batchSaturated=false;nativeParent=index;}
        return begin(index,token,declaredOwners,declaredVertices,null,-1,Long.MIN_VALUE,Long.MIN_VALUE,Long.MIN_VALUE,-1,-1);
    }
    /** The caller supplies an immutable, precomputed mesh/layer/first-use label, never a per-frame label. */
    public long startNativePhase(Stage phase,String detail){
        return startNativePhase(phase,detail,Long.MIN_VALUE,Long.MIN_VALUE,Long.MIN_VALUE,-1,-1,-1);
    }
    public long startNativePhase(Stage phase,String detail,long cubeX,long cubeY,long cubeZ,int layer,long vertices,long attempt){
        if(!enabled)return 0;checkOwner();int index=Objects.requireNonNull(phase).ordinal();
        if(!isPhase(index))throw new IllegalArgumentException("A native phase family is required");
        Objects.requireNonNull(detail,"Native detail");
        if(detail.length()>MAX_NATIVE_DETAIL_CHARS)throw new IllegalArgumentException("Native detail exceeds fixed character bound");
        if(layer< -1||vertices< -1||attempt< -1)throw new IllegalArgumentException("Unknown native counts use exactly−1");
        if(nativeParent<0)throw new IllegalStateException("A native parent batch is required");
        if(slots[index].active)throw new IllegalStateException("Stage is already active");
        int enclosing=phase==Stage.NATIVE_OWNER_CALL?-1:phase==Stage.NATIVE_VALIDATED_BODY?FIRST_PHASE:FIRST_PHASE+1;
        if(nativeTop!=enclosing)throw new IllegalStateException("Native phases require owner, body, then disjoint leaf nesting");
        long token=Math.incrementExact(serial);serial=token;
        batchAdd(index,STARTS,1);nativeTop=index;
        return begin(index,token,-1,vertices,detail,enclosing,cubeX,cubeY,cubeZ,layer,attempt);
    }
    private long begin(int index,long token,int owners,long vertices,String detail,int enclosing,long cubeX,long cubeY,long cubeZ,int layer,long attempt){
        Slot s=slots[index];s.token=token;s.active=true;s.wallFailureMask=0;s.enclosing=enclosing;
        s.frame=frame;s.epoch=epoch;s.publication=publication;s.resources=resources;s.historical=historical;
        s.displayedPublication=displayedPublication;s.presentedCoverage=presentedCoverage;s.presentedRevision=presentedRevision;
        s.cubeX=cubeX;s.cubeY=cubeY;s.cubeZ=cubeZ;s.layer=layer;s.attempt=attempt;
        s.owners=owners;s.vertices=vertices;s.detail=detail;s.nativeDepthStage=null;s.nativeParentToken=0;s.parentOwnerToken=0;
        if(nativeParent>=0&&(index==nativeParent||isPhase(index))){
            s.nativeDepthStage=STAGES[nativeParent];s.nativeParentToken=slots[nativeParent].token;
            if(isPhase(index))s.parentOwnerToken=index==FIRST_PHASE?token:slots[FIRST_PHASE].token;
        }
        try{s.before=wall(s,1);s.cpu=cpu();s.start=wall(s,2);return token;}
        catch(RuntimeException|Error failure){
            s.active=false;s.detail=null;
            if(isPhase(index)){batchAdd(index,FAILURES,1);nativeTop=enclosing;}
            if(index==nativeParent){nativeParent=-1;nativeTop=-1;}
            stats[index].measurementFailures=add(stats[index].measurementFailures,1);throw failure;
        }
    }
    public void finish(Stage stage,long token){
        if(!enabled)return;checkOwner();int index=Objects.requireNonNull(stage).ordinal();Slot slot=slots[index];
        if(!slot.active||token!=slot.token)throw new IllegalStateException("Exact active timing token required");
        if((isPhase(index)&&nativeTop!=index)||(index==nativeParent&&nativeTop!=-1))
            throw new IllegalStateException("Finish native children before their enclosing span");
        // Clock faults are observations, not draw failures. Cleanup also runs if retaining a record fails.
        try{finishAccepted(index,slot);}catch(RuntimeException|Error failure){
            stats[index].measurementFailures=add(stats[index].measurementFailures,1);
            if(isPhase(index))batchAdd(index,FAILURES,1);throw failure;
        }finally{
            slot.active=false;slot.detail=null;
            if(isPhase(index))nativeTop=slot.enclosing;
            if(index==nativeParent){nativeParent=-1;nativeTop=-1;}
        }
    }
    private void finishAccepted(int index,Slot slot){
        long end=wall(slot,4),cpuEnd=cpu(),after=wall(slot,8);
        long inner=end-slot.start,outer=after-slot.before,delta=cpuEnd-slot.cpu;
        Stats s=stats[index];s.calls=add(s.calls,1);
        boolean bracketsPresent=slot.wallFailureMask==0,wallValid=bracketsPresent&&inner>=0&&outer>=inner;
        boolean cpuValid=slot.cpu>=0&&cpuEnd>=0&&delta>=0;
        if(!bracketsPresent)s.measurementFailures=add(s.measurementFailures,1);
        if(!wallValid)s.wallAnomalies=add(s.wallAnomalies,1);
        else{s.totalInner=add(s.totalInner,inner);s.totalOuter=add(s.totalOuter,outer);s.maxInner=Math.max(s.maxInner,inner);s.maxOuter=Math.max(s.maxOuter,outer);if(inner>=SLOW_NANOS)s.slowCalls=add(s.slowCalls,1);}
        if(slot.cpu<0||cpuEnd<0)s.cpuUnavailable=add(s.cpuUnavailable,1);
        else if(delta<0)s.cpuBackwards=add(s.cpuBackwards,1);
        else{s.cpuAvailable=add(s.cpuAvailable,1);s.totalCpu=add(s.totalCpu,delta);s.maxCpu=Math.max(s.maxCpu,delta);}
        if(isPhase(index)){
            batchAdd(index,CALLS,1);
            if(bracketsPresent){batchSignedAdd(index,INNER,inner);batchSignedAdd(index,OUTER,outer);}
            else batchAdd(index,FAILURES,1);
            batchAdd(index,wallValid?WALL_VALID:WALL_BAD,1);
            if(cpuValid){batchAdd(index,CPU_VALID,1);batchAdd(index,CPU,delta);}
            else batchAdd(index,slot.cpu<0||cpuEnd<0?CPU_MISSING:CPU_BACKWARDS,1);
        }
        long excess=wallValid&&cpuValid&&delta>outer?delta-outer:0;
        if(excess>0)s.cpuExcessCount=add(s.cpuExcessCount,1);
        boolean keepWall=wallValid&&(s.worstElapsed[ELAPSED_SAMPLES-1]==null||inner>s.worstElapsed[ELAPSED_SAMPLES-1].innerElapsedNanos());
        boolean keepExcess=excess>s.maxCpuExcess;
        if(keepWall||keepExcess){
            var snapshot=index==nativeParent?snapshot(inner,delta,wallValid,cpuValid):null;
            var sample=new Sample(slot.frame,slot.epoch,slot.publication,slot.resources,slot.historical,slot.owners,slot.vertices,
                    slot.before,slot.start,end,after,slot.cpu,cpuEnd,inner,outer,cpuValid?delta:-1,excess,utc(),
                    STAGES[index],slot.token,slot.nativeDepthStage,slot.nativeParentToken,slot.parentOwnerToken,slot.detail,
                    slot.cubeX,slot.cubeY,slot.cubeZ,slot.layer,slot.attempt,slot.displayedPublication,
                    slot.presentedCoverage,slot.presentedRevision,slot.wallFailureMask,snapshot);
            if(keepWall){int at=0;while(at<ELAPSED_SAMPLES&&s.worstElapsed[at]!=null&&s.worstElapsed[at].innerElapsedNanos()>=inner)at++;
                if(at<ELAPSED_SAMPLES){System.arraycopy(s.worstElapsed,at,s.worstElapsed,at+1,ELAPSED_SAMPLES-at-1);s.worstElapsed[at]=sample;}}
            if(keepExcess){s.maxCpuExcess=excess;s.worstCpuExcess=sample;}
        }
    }
    private NativeBatchTotals snapshot(long parentInner,long parentCpu,boolean parentWallValid,boolean parentCpuValid){
        var phases=new PhaseTotals[PHASES];boolean wallComplete=parentWallValid,cpuComplete=parentCpuValid;
        for(int i=0;i<PHASES;i++){
            int b=i*FIELDS;long calls=batch[b+CALLS];
            phases[i]=new PhaseTotals(STAGES[FIRST_PHASE+i],batch[b+STARTS],calls,batch[b+INNER],batch[b+OUTER],batch[b+CPU],
                    batch[b+WALL_VALID],batch[b+WALL_BAD],batch[b+CPU_VALID],batch[b+CPU_MISSING],batch[b+CPU_BACKWARDS],batch[b+FAILURES]);
            boolean complete=batch[b+STARTS]==calls&&batch[b+FAILURES]==0;
            wallComplete&=complete&&batch[b+WALL_VALID]==calls;cpuComplete&=complete&&batch[b+CPU_VALID]==calls;
        }
        long leafOuter=0,leafCpu=0;
        for(int i=2;i<PHASES;i++){leafOuter=signedAdd(leafOuter,batch[i*FIELDS+OUTER]);leafCpu=signedAdd(leafCpu,batch[i*FIELDS+CPU]);}
        long parentR=signedSubtract(parentInner,batch[OUTER]);
        long ownerR=signedSubtract(batch[INNER],batch[FIELDS+OUTER]);
        long bodyR=signedSubtract(batch[FIELDS+INNER],leafOuter);
        long parentCpuR=0,ownerCpuR=0,bodyCpuR=0;
        if(cpuComplete){parentCpuR=signedSubtract(parentCpu,batch[CPU]);ownerCpuR=signedSubtract(batch[CPU],batch[FIELDS+CPU]);bodyCpuR=signedSubtract(batch[FIELDS+CPU],leafCpu);}
        wallComplete&=!batchSaturated;cpuComplete&=wallComplete&&!batchSaturated;
        var residuals=cpuComplete?new CpuResiduals(parentCpuR,ownerCpuR,bodyCpuR):null;
        return new NativeBatchTotals(phases,parentR,ownerR,bodyR,wallComplete,cpuComplete,batchSaturated,residuals);
    }
    private long wall(Slot slot,int bit){try{return clock.nanos();}catch(RuntimeException|Error unavailable){wallReadFailures=add(wallReadFailures,1);slot.wallFailureMask|=bit;return 0;}}
    private long utc(){try{return clock.utcMillis();}catch(RuntimeException|Error unavailable){utcReadFailures=add(utcReadFailures,1);return -1;}}
    private long cpu(){try{return clock.cpuNanos();}catch(RuntimeException|Error unavailable){cpuReadFailures=add(cpuReadFailures,1);return -1;}}
    private static boolean isNative(Stage stage){return stage==Stage.FAR_NATIVE||stage==Stage.NEAR_NATIVE||stage==Stage.LEGACY_NATIVE;}
    private static boolean isPhase(int index){return index>=FIRST_PHASE;}
    private void batchAdd(int index,int field,long value){int at=(index-FIRST_PHASE)*FIELDS+field;if(batch[at]>Long.MAX_VALUE-value)batchSaturated=true;batch[at]=add(batch[at],value);}
    private void batchSignedAdd(int index,int field,long value){int at=(index-FIRST_PHASE)*FIELDS+field;batch[at]=signedAdd(batch[at],value);}
    private long signedAdd(long a,long b){long r=a+b;if(((a^r)&(b^r))<0){saturated=true;batchSaturated=true;return a<0?Long.MIN_VALUE:Long.MAX_VALUE;}return r;}
    private long signedSubtract(long a,long b){long r=a-b;if(((a^b)&(a^r))<0){saturated=true;batchSaturated=true;return a<0?Long.MIN_VALUE:Long.MAX_VALUE;}return r;}
    private long add(long a,long b){if(b<0)throw new IllegalArgumentException("Nonnegative accounting required");if(a>Long.MAX_VALUE-b){saturated=true;return Long.MAX_VALUE;}return a+b;}
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Submission probe owner thread required");}
    public JsonObject diagnostics(){
        if(enabled)checkOwner();var out=new JsonObject();out.addProperty("enabled",enabled);out.addProperty("initialThreadCpuStatus",initialCpuStatus);
        out.addProperty("globalCpuMonitoringChanged",false);out.addProperty("gpuWaitsAdded",false);out.addProperty("maxRetainedSamplesPerStage",4);out.addProperty("maxRetainedSamplesTotal",68);
        out.addProperty("nativePhaseFamilyCount",PHASES);out.addProperty("nativePhaseRawSlotCapacity",36);out.addProperty("maxNativeDetailChars",MAX_NATIVE_DETAIL_CHARS);
        out.addProperty("maxNativeBatchSnapshots",12);out.addProperty("maxPhaseRowsPerSnapshot",PHASES);out.addProperty("maxRetainedPhaseTotalRows",108);
        out.addProperty("declaredReusableNativeAccumulatorBytes",PHASES*FIELDS*Long.BYTES);
        out.addProperty("declaredMaxRetainedPhaseTotalLongBytes",108*FIELDS*Long.BYTES);
        out.addProperty("declaredMaxRetainedNativeResidualLongBytes",12*6*Long.BYTES);
        out.addProperty("declaredMaxProbeReferencedDetailUtf16Bytes",39*MAX_NATIVE_DETAIL_CHARS*Character.BYTES);
        out.addProperty("declaredByteBudgetScope","Logical primitive/code-unit payload only, not measured JVM heap; excludes object/array headers, references, alignment, and actual String encoding. Labels are caller-owned and may be shared.");
        out.addProperty("rawAnomalyRetentionLimit","Invalid wall spans are counted but do not displace worst-valid-elapsed or worst-CPU-excess raw slots. Their signed phase sums and counts are copied into a retained valid native parent; individual invalid child brackets may be absent. Missing bracket values do not enter phase wall sums.");
        out.addProperty("reusableNativeBatchLongs",PHASES*FIELDS);out.addProperty("maxRetainedPhaseTotalLongs",108*FIELDS);
        out.addProperty("worstElapsedCapacityPerStage",3);out.addProperty("worstCpuExcessCapacityPerStage",1);out.addProperty("slowThresholdNanos",SLOW_NANOS);
        out.addProperty("cpuReadFailures",cpuReadFailures);out.addProperty("wallReadFailures",wallReadFailures);out.addProperty("utcReadFailures",utcReadFailures);out.addProperty("countersSaturated",saturated);
        out.addProperty("scope","Owner-thread component wall/CPU spans; not GPU duration, FPS, or attribution of low-CPU elapsed time to driver versus scheduling. Counts are caller-declared cached context.");
        out.addProperty("brackets","Outer wall includes both thread-CPU clock reads; inner wall brackets operation only. Raw differences and CPU>outer discrepancies remain uncorrected. Missing wall readings have an explicit failure mask and do not enter elapsed totals.");
        out.addProperty("nativeResidualSemantics","Same-batch parent inner minus owner outer; owner inner sums minus body outer sums; body inner sums minus disjoint leaf outer sums. Signed residuals include uninstrumented work and measurement overhead, not pure loop, lock, driver, or scheduling time. Incomplete bracket sums and saturation are flagged. CPU residuals appear only with complete valid wall and CPU coverage.");
        out.addProperty("declaredAccounting","Fixed upper bounds, not measured heap: 17 active slots and stats with 17 three-reference elapsed arrays plus 17 CPU-excess references (68 raw slots); one 99-long native accumulator; at most 12 native-parent snapshot objects, 12 nine-reference arrays, 108 eleven-long phase rows, and 12 three-long CPU-residual objects. At most 36 retained and three simultaneously active detail-string references, each <=160 UTF-16 code units; caller precomputes labels. Snapshots contain totals, not extra raw samples. Disabled probe allocates none of these arrays or records; ordinary unretained spans allocate no labels, samples, snapshots, or arrays.");
        var stages=new JsonObject();int retained=0,snapshots=0;Gson gson=new Gson();
        if(enabled)for(var stage:STAGES){
            Stats s=stats[stage.ordinal()];var j=new JsonObject();j.addProperty("calls",s.calls);j.addProperty("totalInnerElapsedNanos",s.totalInner);j.addProperty("totalOuterElapsedNanos",s.totalOuter);j.addProperty("totalValidThreadCpuNanos",s.totalCpu);
            j.addProperty("maxInnerElapsedNanos",s.maxInner);j.addProperty("maxOuterElapsedNanos",s.maxOuter);j.addProperty("maxValidThreadCpuNanos",s.maxCpu);
            j.addProperty("cpuAvailableSamples",s.cpuAvailable);j.addProperty("cpuUnavailableSamples",s.cpuUnavailable);j.addProperty("cpuBackwardsSamples",s.cpuBackwards);j.addProperty("wallAnomalies",s.wallAnomalies);j.addProperty("measurementFailures",s.measurementFailures);
            j.addProperty("cpuExceedsOuterWallSamples",s.cpuExcessCount);j.addProperty("maxCpuExcessOverOuterWallNanos",s.maxCpuExcess);j.addProperty("slowCalls",s.slowCalls);j.addProperty("spanOpen",slots[stage.ordinal()].active);
            var samples=new JsonArray();for(var sample:s.worstElapsed)if(sample!=null){samples.add(gson.toJsonTree(sample));retained++;if(sample.nativeBatch()!=null)snapshots++;}j.add("worstElapsedSamples",samples);
            if(s.worstCpuExcess!=null){j.add("worstCpuExcessSample",gson.toJsonTree(s.worstCpuExcess));retained++;if(s.worstCpuExcess.nativeBatch()!=null)snapshots++;}stages.add(stage.name(),j);
        }
        out.add("stages",stages);out.addProperty("retainedSampleSlots",retained);out.addProperty("retainedNativeBatchSnapshotSlots",snapshots);out.addProperty("retainedNativePhaseTotalRows",snapshots*PHASES);return out;
    }
    private static final class SystemClock implements Clock {
        private final ThreadMXBean bean;private final String status;private final boolean readable;
        SystemClock(){ThreadMXBean found=null;boolean available=false;String label;
            try{found=ManagementFactory.getThreadMXBean();available=found.isCurrentThreadCpuTimeSupported()&&found.isThreadCpuTimeEnabled();label=available?"SUPPORTED_ALREADY_ENABLED":"UNSUPPORTED_OR_DISABLED";}
            catch(RuntimeException unavailable){label="UNAVAILABLE: "+unavailable.getClass().getSimpleName();}
            bean=found;readable=available;status=label;
        }
        public long nanos(){return System.nanoTime();}public long utcMillis(){return System.currentTimeMillis();}
        public long cpuNanos(){return readable?bean.getCurrentThreadCpuTime():-1;}public String cpuStatus(){return status;}
    }
}
