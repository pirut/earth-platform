package io.earthplatform.minecraft.lod;

import io.earthplatform.compat.dh.InstalledDhTerrainRuntime;
import io.earthplatform.minecraft.survival.render.SurvivalDhCoverage;
import io.earthplatform.minecraft.survival.render.SurvivalCompleteOverlay;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Exclusive real Result -> installed-helper output ownership. No public byte/proof attachment. */
public final class SurvivalDhPageCompiler implements AutoCloseable {
    // Reconstructed face/geometry objects, list references, helper snapshot and up to4096
    // transient BufferQuad candidates discarded by successful official premerge in this call.
    // The official pooled/workspace graph is separately charged for process lifetime by runtime.
    static long copiedInputBytes(int faces){return Math.addExact(4096L+InstalledDhTerrainRuntime.SURVIVAL_INPUT_COPY_BYTES
            +InstalledDhTerrainRuntime.SURVIVAL_MAX_FACES*128L,Math.multiplyExact(faces,144L));}
    private final Thread owner=Thread.currentThread();
    private final InstalledDhTerrainRuntime runtime;
    private final NativeDistanceCpuBudget budget;
    private InstalledDhTerrainRuntime.ManagedSurvivalWorkspace helper;
    private InstalledDhTerrainRuntime.ManagedCall pendingCall;
    private boolean helperCallUncertain;
    private SurvivalDhTerrainData.Result.CompilerOwner input;
    private SurvivalDhCoverage coverage;
    private NativeDistanceCpuBudget.Reservation metadata,working,unadoptedOutput;
    private List<SurvivalDhTerrainData.PageFace> activeFaces;
    private List<Output> outputs=new ArrayList<>();
    private int tiles,next;
    private boolean started,failed,complete,transferred,closed,operating;
    private static final class Output {
        final DhGeographicLodData.TileKey key;
        byte[] bytes;
        final InstalledDhTerrainRuntime.AttestedSurvivalSettings settings;
        final NativeDistanceCpuBudget.Reservation charge;
        Output(DhGeographicLodData.TileKey key,byte[] bytes,InstalledDhTerrainRuntime.AttestedSurvivalSettings settings,NativeDistanceCpuBudget.Reservation charge){
            this.key=key;this.bytes=bytes;this.settings=settings;this.charge=charge;
        }
        void drain(){bytes=null;charge.releaseAfterPhysicalDrain();}
    }
    private InstalledDhTerrainRuntime.ObservedSurvivalSettings generationSettings;
    private InstalledDhTerrainRuntime.AttestedSurvivalSettings generationConfiguration;
    private InstalledDhTerrainRuntime.SurvivalCompilation pendingCompilation;
    private byte[] pendingBytes;
    public record Step(int tiles,int completedTiles,int totalTiles,long elapsedNanos,long budgetNanos,long overshootNanos,boolean complete){}
    /** Retain the wrapper before start; uncertain helper failures stay owned by this wrapper. */
    public SurvivalDhPageCompiler(InstalledDhTerrainRuntime runtime,NativeDistanceCpuBudget budget){
        this.runtime=Objects.requireNonNull(runtime);this.budget=Objects.requireNonNull(budget);
    }
    public void start(SurvivalDhTerrainData.Result result){
        check();if(started||closed)throw refused("DH compiler start is single-use");started=true;
        try{
            metadata=budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,8192+512L*DhNativeDistanceBudget.MAX_OWNERS);
            input=Objects.requireNonNull(result).transferToCompiler(budget);
            coverage=input.retainCoverage();tiles=input.tileCount();
            if(tiles<1||tiles>DhNativeDistanceBudget.MAX_OWNERS)throw refused("DH result tile count exceeds fixed limit");
            helper=runtime.managedSurvivalWorkspace(budget);
        }catch(RuntimeException|Error e){failed=true;throw e;}
    }
    /** Tiles/count are hard bounds; one official helper invocation is a noninterruptible soft slice. */
    public Step step(int maxTiles,long maxNanos,Runnable cancellation){
        check();if(maxTiles<1||maxNanos<1)throw new IllegalArgumentException("Positive bounded DH slice required");
        if(!started||closed||complete||failed||operating)throw refused("DH compiler is not resumable");
        Objects.requireNonNull(cancellation);operating=true;long begin=System.nanoTime();int initial=next;
        try{
            while(next<tiles){
                cancellation.run();compileTile(cancellation);next++;
                if(next-initial>=maxTiles||elapsed(begin)>=maxNanos)break;
            }
            if(next==tiles){input.close();if(!input.closeComplete())throw refused("DH scalar source did not drain");input=null;complete=true;}
            long duration=elapsed(begin);return new Step(next-initial,next,tiles,duration,maxNanos,Math.max(0,duration-maxNanos),complete);
        }catch(RuntimeException|Error e){failed=true;throw e;}finally{operating=false;}
    }
    private void compileTile(Runnable cancellation){
        int count=input.faceCount(next);if(count<1||count>4096)throw refused("DH tile has invalid face count");
        working=budget.reserve(NativeDistanceCpuBudget.Pool.DH_RESULTS,copiedInputBytes(count));
        // Charge precedes all scalar reconstruction and the helper's private copied input.
        activeFaces=new ArrayList<>(count);int maximumQuads=0;
        for(int i=0;i<count;i++){
            cancellation.run();var face=input.face(next,i);var g=face.geometry();
            maximumQuads=Math.addExact(maximumQuads,g.direction()==DhTerrainGeometry.Direction.DOWN?downQuads(g.width(),g.height()):1);
            if(maximumQuads>4096)throw refused("DH expanded tile exceeds unchanged4096-quad ceiling");activeFaces.add(face);
        }
        unadoptedOutput=budget.reserve(NativeDistanceCpuBudget.Pool.DH_RESULTS,256L+maximumQuads*64L);
        helperCallUncertain=true;
        pendingCall=helper.compile(activeFaces,generationSettings,cancellation);
        helperCallUncertain=!pendingCall.operationCompleted();
        pendingCompilation=pendingCall.compilation();
        pendingBytes=pendingCompilation.bytes();
        if(pendingBytes.length<64||pendingBytes.length>maximumQuads*64||pendingBytes.length%64!=0||helperCallUncertain)
            throw refused("Official DH output or physical cleanup differs from admission");
        var attested=Objects.requireNonNull(pendingCompilation.settings());
        var actual=new InstalledDhTerrainRuntime.ObservedSurvivalSettings(attested.grassSideMode(),attested.debugMode());
        if(generationSettings==null)generationSettings=actual;
        else if(!generationSettings.equals(actual))throw refused("One DH generation cannot mix actual grass/debug packing settings");
        if(generationConfiguration==null)generationConfiguration=attested;
        else generationConfiguration.requireSameConfiguration(attested);
        outputs.add(new Output(input.tileKey(next),pendingBytes,attested,unadoptedOutput));
        pendingBytes=null;pendingCompilation=null;pendingCall=null;unadoptedOutput=null;activeFaces=null;working.releaseAfterPhysicalDrain();working=null;
    }
    static int downQuads(int width,int height){
        if(width<1||height<1)throw new IllegalArgumentException("Positive rectangle required");int result=0;
        while(width>0&&height>0){if(width>=height){result=Math.addExact(result,width/height);width%=height;}else{result=Math.addExact(result,height/width);height%=width;}}
        return result;
    }
    public CompiledSet finish(){
        check();if(!complete||failed||closed||transferred||operating)throw refused("No exact complete DH output available");
        var proof=new Provenance(coverage,metadata,generationSettings,generationConfiguration);
        var result=new CompiledSet(outputs,proof,budget);transferred=true;outputs=null;coverage=null;metadata=null;return result;
    }
    public boolean closeComplete(){check();return closed;}
    @Override public void close(){
        check();if(closed)return;if(operating)throw refused("Cannot close a live DH compile");
        if(transferred)throw refused("DH compiler output was exclusively transferred");
        if(helperCallUncertain)throw refused("Uncertain exact official helper call retains transient ownership through process exit");
        activeFaces=null;pendingBytes=null;pendingCompilation=null;pendingCall=null;if(working!=null){working.releaseAfterPhysicalDrain();working=null;}
        if(unadoptedOutput!=null){unadoptedOutput.releaseAfterPhysicalDrain();unadoptedOutput=null;}
        if(outputs!=null){for(var output:outputs)output.drain();outputs.clear();outputs=null;}
        if(input!=null){input.close();input=null;}if(coverage!=null){coverage.close();coverage=null;}
        if(metadata!=null){metadata.releaseAfterPhysicalDrain();metadata=null;}closed=true;
    }
    private void check(){if(Thread.currentThread()!=owner)throw refused("DH compiler requires its exact worker owner");}
    private static long elapsed(long begin){return Math.max(0,System.nanoTime()-begin);}
    private static SurvivalRefusal refused(String message){return new SurvivalRefusal(message);}

    /** Privately minted, independently retained proof. It contains no scalar/result/output payload aliases. */
    static final class Provenance implements AutoCloseable {
        private SurvivalDhCoverage coverage;
        private NativeDistanceCpuBudget.Reservation metadata;
        private final InstalledDhTerrainRuntime.ObservedSurvivalSettings historical;
        private final InstalledDhTerrainRuntime.AttestedSurvivalSettings configuration;
        private boolean closed;
        private Provenance(SurvivalDhCoverage coverage,NativeDistanceCpuBudget.Reservation metadata,
                InstalledDhTerrainRuntime.ObservedSurvivalSettings historical,
                InstalledDhTerrainRuntime.AttestedSurvivalSettings configuration){
            this.coverage=Objects.requireNonNull(coverage);this.metadata=Objects.requireNonNull(metadata);this.historical=Objects.requireNonNull(historical);
            this.configuration=Objects.requireNonNull(configuration);
        }
        private void open(){if(closed)throw refused("DH provenance was retired");}
        void requireMatches(SurvivalDhCoverage exact,SurvivalCompleteOverlay.FrameLatch frame){open();coverage.requireSame(exact);coverage.requireExactPlan(frame);}
        void requireMatches(SurvivalDhCoverage exact,SurvivalCompleteOverlay.PlanInput plan){open();coverage.requireSame(exact);coverage.requireExactPlan(plan);}
        void requireCurrentConfiguration(){open();configuration.requireCurrentConfiguration();}
        String grassSideMode(){open();return historical.grassSideMode();}
        String debugMode(){open();return historical.debugMode();}
        @Override public void close(){if(closed)return;coverage.close();coverage=null;metadata.releaseAfterPhysicalDrain();metadata=null;closed=true;}
    }

    /** Public methods expose detached metadata only. Arrays are exclusively owned, never returned. */
    public static final class CompiledSet implements AutoCloseable {
        private List<Output> outputs;
        private Provenance provenance;
        private final NativeDistanceCpuBudget budget;
        private UploadOwner upload;
        private boolean closed;
        private CompiledSet(List<Output> outputs,Provenance provenance,NativeDistanceCpuBudget budget){this.outputs=outputs;this.provenance=provenance;this.budget=budget;}
        private void caller(){if(closed||upload!=null)throw refused("Compiled DH output is closed or transferred");}
        public synchronized int tileCount(){caller();return outputs.size();}
        public synchronized int vertexBytes(int tile){caller();return outputs.get(tile).bytes.length;}
        public synchronized DhGeographicLodData.TileKey tileKey(int tile){caller();return outputs.get(tile).key;}
        synchronized UploadOwner transferToUpload(NativeDistanceCpuBudget expectedBudget){caller();if(budget!=expectedBudget)throw refused("DH upload must share the exact compiler CPU budget");upload=new UploadOwner();return upload;}
        final class UploadOwner implements AutoCloseable {
            private boolean drained,proofTransferred;
            private UploadOwner(){}
            private void owned(){if(drained||closed||upload!=this)throw refused("Absent exact DH upload ownership");}
            int tileCount(){synchronized(CompiledSet.this){owned();return outputs.size();}}
            int vertexBytes(int tile){synchronized(CompiledSet.this){owned();return outputs.get(tile).bytes.length;}}
            DhGeographicLodData.TileKey tileKey(int tile){synchronized(CompiledSet.this){owned();return outputs.get(tile).key;}}
            Provenance takeProvenance(){synchronized(CompiledSet.this){owned();if(proofTransferred)throw refused("DH provenance already transferred");proofTransferred=true;return provenance;}}
            void copyTo(int tile,ByteBuffer destination){synchronized(CompiledSet.this){
                owned();byte[] bytes=outputs.get(tile).bytes;
                if(!destination.isDirect()||destination.position()!=0||destination.remaining()!=bytes.length)throw refused("Exact charged direct DH upload staging required");
                destination.put(bytes);destination.flip();
            }}
            boolean closeComplete(){synchronized(CompiledSet.this){return drained;}}
            @Override public void close(){synchronized(CompiledSet.this){if(drained)return;owned();drain(proofTransferred);drained=true;}}
        }
        private void drain(boolean proofTransferred){
            // Each record loses its sole byte[] alias BEFORE its reservation is released. A partial
            // drain therefore leaves every not-yet-drained payload fully charged and retryable.
            if(outputs!=null){for(var output:outputs)output.drain();outputs.clear();outputs=null;}
            if(!proofTransferred)provenance.close();provenance=null;closed=true;
        }
        @Override public synchronized void close(){if(closed)return;caller();drain(false);}
    }
}
