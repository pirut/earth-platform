package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.minecraft.enginebridge.EarthReplacementCapture;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageReady;
import io.earthplatform.minecraft.lod.DhReplacementInput;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.minecraft.scene.SceneCube;
import io.earthplatform.minecraft.scene.SceneReadyCube;
import io.earthplatform.minecraft.visual.EarthDistanceSurfacePlan;
import io.earthplatform.minecraft.visual.NativePageKey;
import io.earthplatform.minecraft.visual.NativeVisualPublication;
import io.earthplatform.minecraft.visual.NativeVisualStamp;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Distance-only exact data authority. No model, GPU allocation, worker, or global proof cache. */
public final class RollingDistanceBodyBinding {
    private RollingDistanceBodyBinding() {}
    public static final int BODY_CUBES=125, TARGET_CUBES=27, MAX_COORDINATES=213, MAX_PAYLOADS=214;
    public static final int POLL_CELLS=64, MAX_COMPARISONS_PER_SLICE=262_144, MAX_COMPARISONS=512_000;
    public static final long SOFT_SLICE_NANOS=2_000_000L, MAX_METADATA_BYTES=1_048_576L;

    /** Logical metadata, within the existing session OWNER_METADATA reservation, not additive RAM. */
    public record Accounting(int coordinates,int payloadIdentities,int proofIdentities,long metadataBytes) {}
    public record ResourceGuardMetrics(long calls,long totalNanos,long maxNanos) {}
    public record Slice(int comparisonsThisSlice,long completedComparisons,int reusedSnapshots,boolean complete,
                        long elapsedNanos,long overshootNanos,long preGuardNanos,long postGuardNanos,boolean entryGuardExhausted) {}

    /** One bounded session owner. A failed cleanup remains here, revoked and charged, until retry. */
    static final class Owner implements AutoCloseable {
        private final Thread thread=Thread.currentThread();
        private final RollingGateReplica replica;
        private final RenderPublicationGuard guard;
        private final CubeStreamAdmission admission;
        private final BooleanSupplier liveSessionResources;
        private final LongSupplier clock;
        private final long resources;
        private Binding current,candidate;
        private History history;
        private long highestPublication,highestVisualGeneration;
        private long fullGuardCalls,fullGuardTotalNanos,fullGuardMaxNanos;
        private volatile boolean revoked;
        Owner(RollingGateReplica replica,RenderPublicationGuard guard,BooleanSupplier liveSessionResources) {
            this(replica,guard,liveSessionResources,System::nanoTime);
        }
        Owner(RollingGateReplica replica,RenderPublicationGuard guard,BooleanSupplier liveSessionResources,LongSupplier clock) {
            this.replica=Objects.requireNonNull(replica);this.guard=Objects.requireNonNull(guard);
            this.admission=replica.admission();this.liveSessionResources=Objects.requireNonNull(liveSessionResources);
            this.clock=Objects.requireNonNull(clock);resources=guard.resourceGeneration();
        }
        Binding begin(long publication,NativeVisualStamp stamp,EarthDistanceSurfacePlan plan,DhReplacementInput replacement) {
            check();require(live(),"Distance body session resources are stale");
            require(candidate==null,"Only one distance body candidate, including failed cleanup, may be retained");
            require(publication>highestPublication&&stamp.visualGeneration()>highestVisualGeneration,"Fresh body publication and visual request required");
            highestPublication=publication;highestVisualGeneration=stamp.visualGeneration();
            // Register before initialization, so a thrown validation still has a reachable cleanup owner.
            candidate=new Binding(this,publication);candidate.initialize(stamp,plan,replacement);
            accounting();return candidate;
        }
        Binding current(){check();return current;}
        Binding candidate(){check();return candidate;}
        void install(Binding next) {
            check();require(next!=null&&next==candidate,"Exact current body candidate required");
            next.requireJoined();
            require(history==null,"Retire the explicit historical body ticket before installing its replacement");
            Binding previous=current;
            if(previous!=null)previous.close(); // no old data alias survives replica retirement
            next.requireJoined();current=next;candidate=null;next.requireJoined();accounting();
        }
        void cancelCandidate(){check();if(candidate!=null){candidate.close();candidate=null;}}
        /** Off-thread reload fencing only flips the shared gate; no mutable owner graph is traversed. */
        void revoke(){revoked=true;}
        boolean aliasesReleased(){check();return current==null&&candidate==null&&history==null;}
        List<EngineCubeSnapshot> snapshots(){check();var out=new ArrayList<EngineCubeSnapshot>();addSnapshots(out,current);addSnapshots(out,candidate);return List.copyOf(out);}
        private static void addSnapshots(List<EngineCubeSnapshot> out,Binding b){if(b!=null&&b.snapshots!=null)for(var s:b.snapshots)if(s!=null&&!out.contains(s))out.add(s);}
        /** Includes replica payloads and optional separately charged resume-bootstrap snapshots. */
        Accounting accounting(){return accounting(List.of());}
        Accounting accounting(Collection<EngineCubeSnapshot> externallyRetained) {
            check();Objects.requireNonNull(externallyRetained);
            var payloads=Collections.newSetFromMap(new IdentityHashMap<EngineCubeSnapshot,Boolean>());
            var positions=new HashSet<CubePos>();var proofs=Collections.newSetFromMap(new IdentityHashMap<Proof,Boolean>());
            for(var cube:replica.cubes().values())account(payloads,positions,cube.snapshot());
            for(var snapshot:externallyRetained)account(payloads,positions,snapshot);
            int bindings=0;
            for(Binding binding:new Binding[]{current,candidate})if(binding!=null){bindings++;
                if(binding.snapshots!=null)for(var snapshot:binding.snapshots)if(snapshot!=null)account(payloads,positions,snapshot);
                if(binding.proofs!=null)for(var proof:binding.proofs)if(proof!=null)proofs.add(proof);
            }
            // Conservative logical object/reference accounting: two binding containers,125 snapshots,
            // headers/proof slots each; all distinct proof objects; exact27 records/list; one cursor,
            // joined/history wrappers; two bounded accounting tables and initialization peak scratch.
            // Payload bytes and SceneCube arrays remain in their existing separate CPU reservations.
            long bytes=4096L+bindings*(2048L+BODY_CUBES*(8L+8L+64L)+TARGET_CUBES*96L)
                    +proofs.size()*96L+MAX_PAYLOADS*256L+MAX_COORDINATES*96L+BODY_CUBES*64L;
            require(bytes<=MAX_METADATA_BYTES,"Distance body metadata sub-budget exceeded");
            return new Accounting(positions.size(),payloads.size(),proofs.size(),bytes);
        }
        private static void account(Set<EngineCubeSnapshot> payloads,Set<CubePos> positions,EngineCubeSnapshot snapshot) {
            Objects.requireNonNull(snapshot);payloads.add(snapshot);positions.add(snapshot.position());
            require(positions.size()<=MAX_COORDINATES&&payloads.size()<=MAX_PAYLOADS,"Distance body retained coordinate/payload ceiling exceeded");
        }
        private boolean cheapLive(){return !revoked&&!guard.invalidationRequested()&&guard.resourceGeneration()==resources;}
        private boolean live(){
            if(!cheapLive())return false;
            long started=System.nanoTime();fullGuardCalls=saturatedAdd(fullGuardCalls,1);
            try{
                // A dead capsule/registry/session generation cannot be resurrected by an equal future value.
                if(!liveSessionResources.getAsBoolean()){revoked=true;return false;}
                return cheapLive(); // callback may itself have revoked this owner
            }catch(RuntimeException|Error failure){revoked=true;throw failure;}
            finally{long elapsed=Math.max(0,System.nanoTime()-started);fullGuardTotalNanos=saturatedAdd(fullGuardTotalNanos,elapsed);fullGuardMaxNanos=Math.max(fullGuardMaxNanos,elapsed);}
        }
        ResourceGuardMetrics resourceGuardMetrics(){check();return new ResourceGuardMetrics(fullGuardCalls,fullGuardTotalNanos,fullGuardMaxNanos);}
        private void check(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Distance body render owner required");}
        @Override public void close(){
            check();revoke();Throwable failure=null;
            if(history!=null)history.close();
            if(candidate!=null)try{candidate.close();candidate=null;}catch(RuntimeException|Error e){failure=e;}
            if(current!=null)try{current.close();current=null;}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            if(failure instanceof RuntimeException e)throw e;if(failure instanceof Error e)throw e;
        }
    }

    /** Completed value fact intentionally holds no SceneCube, decoded array, map, callback or plan. */
    private static final class Proof {
        private final EngineCubeSnapshot snapshot;
        private final EarthDistanceSurfacePlan.Source source;
        private final EarthReplacementCapture capture; // null outside the exact single editable cube
        private Proof(EngineCubeSnapshot snapshot,EarthDistanceSurfacePlan.Source source,EarthReplacementCapture capture){this.snapshot=snapshot;this.source=source;this.capture=capture;}
        private boolean applies(EngineCubeSnapshot snapshot,EarthDistanceSurfacePlan.Source source,EarthReplacementCapture capture){return this.snapshot==snapshot&&this.source==source&&this.capture==capture;}
    }

    static final class Binding implements AutoCloseable {
        private final Owner owner;
        private final long publication;
        private StageReady ready;
        private NativeVisualStamp stamp;
        private EarthDistanceSurfacePlan plan;
        private DhReplacementInput replacement;
        private EngineCubeSnapshot[] snapshots;
        private CubeStreamAdmission.Header[] headers;
        private Proof[] proofs;
        private List<SceneReadyCube> exactReady=List.of();
        private SceneCube activeCube;
        private int cubeIndex,cellIndex,reused;
        private long comparisons,completedComparisons;
        private NativeJoined joined;
        private boolean revoked,currentRevoked,closed;
        // Package-private test seam models a close failure before source aliases can be released.
        private Runnable beforeAliasRelease=()->{};
        private Binding(Owner owner,long publication){this.owner=owner;this.publication=publication;}
        private void initialize(NativeVisualStamp stamp,EarthDistanceSurfacePlan plan,DhReplacementInput replacement) {
            this.stamp=Objects.requireNonNull(stamp);this.plan=Objects.requireNonNull(plan);this.replacement=Objects.requireNonNull(replacement);
            ready=Objects.requireNonNull(owner.replica.candidateReady(),"Complete StageReady required");
            stamp.requireSelectedDistanceEffective();
            require(stamp.resourceGeneration()==owner.resources&&stamp.sessionEpoch()==ready.epoch()&&stamp.world().equals(ready.world()),"Body request session identity mismatch");
            require(plan.center().equals(ready.candidateCenter())&&plan.guardRows()==0&&plan.requestedChunks()==stamp.effectiveDistanceChunks(),"Body requires the exact selected native draw plan");
            require(plan.capture()!=null&&plan.capture()==replacement.data().capture().earthCapture()
                    &&plan.capture()==ready.earthReplacementCapture(),"Exact admitted capture object required");
            var certificate=Objects.requireNonNull(ready.replacementCertificate(),"Distance body replacement certificate required");
            var request=replacement.certificate();
            require(certificate.identity().equals(request.identity())&&certificate.originalCube().equals(plan.capture().editedCube())
                    &&certificate.storeIdentity().equals(stamp.storeIdentity().toString())&&certificate.originalRevision()==stamp.durableRevision()
                    &&request.durableRevision()==stamp.durableRevision()&&request.resourceGeneration()==owner.resources
                    &&request.epoch()==stamp.sessionEpoch()&&request.world().equals(stamp.world()),"Body replacement/store/revision mismatch");
            require(stamp.sourceIdentity().equals(plan.sourceIdentity())&&stamp.detailIdentity().equals(plan.source().detailIdentity())
                    &&stamp.materialPolicyIdentity().equals(plan.source().materialPolicyIdentity())&&stamp.captureIdentity().equals(plan.captureIdentity()),"Body source/capture request mismatch");
            List<CubePos> positions=box(ready.candidateCenter(),2,BODY_CUBES),targets=box(ready.candidateCenter(),1,TARGET_CUBES);
            require(owner.replica.candidatePositions125().equals(positions)&&owner.replica.candidateTargets().equals(targets)
                    &&ready.versions().size()==BODY_CUBES,"Distance requires complete exact125 body and exact27 target sets");
            if(ready.pinned()){
                CubePos pin=owner.replica.candidateFocus();
                require(pin.equals(plan.capture().editedCube())&&targets.contains(pin)&&positions.containsAll(box(pin,1,TARGET_CUBES)),"Pinned original and its halo must be fully contained in distance body");
            }
            snapshots=new EngineCubeSnapshot[BODY_CUBES];headers=new CubeStreamAdmission.Header[BODY_CUBES];proofs=new Proof[BODY_CUBES];
            var targetReady=new ArrayList<SceneReadyCube>(TARGET_CUBES);
            for(int i=0;i<BODY_CUBES;i++){
                CubePos position=positions.get(i);var version=ready.versions().get(i);
                require(position.equals(version.position()),"Body StageReady is incomplete or noncanonical");
                EngineCubeSnapshot snapshot=Objects.requireNonNull(owner.replica.cubes().get(position),"Missing distance body cube").snapshot();
                require(snapshot.world().equals(ready.world())&&snapshot.epoch()==ready.epoch()&&snapshot.leaseGeneration()==version.leaseGeneration()&&snapshot.revision()==version.revision(),"Exact snapshot header disagrees with complete StageReady");
                snapshots[i]=snapshot;headers[i]=header(snapshot);
                if(targets.contains(position))targetReady.add(new SceneReadyCube(position,snapshot.world(),snapshot.epoch(),snapshot.leaseGeneration(),snapshot.revision(),owner.resources));
                Proof reusable=findReusable(snapshot);if(reusable!=null){proofs[i]=reusable;reused++;}
            }
            exactReady=List.copyOf(targetReady);requireDependencies();
        }
        private EarthReplacementCapture applicableCapture(EngineCubeSnapshot snapshot){return snapshot.position().equals(plan.capture().editedCube())?plan.capture():null;}
        private Proof findReusable(EngineCubeSnapshot snapshot){
            Binding prior=owner.current;if(prior==null||prior.closed||prior.proofs==null)return null;
            // Old revision authority can be fenced during an edit, while an immutable value fact is reusable.
            // Fresh headers, snapshot identity and live owner checks are always performed for this binding.
            for(var proof:prior.proofs)if(proof!=null&&proof.applies(snapshot,plan.source(),applicableCapture(snapshot)))return proof;
            return null;
        }
        Slice step(){
            owner.check();long start=owner.clock.getAsLong(),preStart=start;requireDependencies();
            long preEnd=owner.clock.getAsLong(),before=comparisons;long postStart;
            try{
                // Header checks themselves spend the same soft owner allowance. No unconditional first batch.
                if(preEnd-start<SOFT_SLICE_NANOS)while(cubeIndex<BODY_CUBES&&comparisons-before<MAX_COMPARISONS_PER_SLICE){
                    if(proofs[cubeIndex]!=null){cubeIndex++;continue;}
                    if(activeCube==null){activeCube=owner.replica.cubes().get(snapshots[cubeIndex].position());requireActive();}
                    int count=Math.min(POLL_CELLS,Math.min(4096-cellIndex,MAX_COMPARISONS_PER_SLICE-(int)(comparisons-before)));
                    var position=snapshots[cubeIndex].position();
                    for(int j=0;j<count;j++){
                        int x=cellIndex&15,z=cellIndex>>>4&15,y=cellIndex>>>8;
                        int actual=RollingEarthCaptureValidation.material(activeCube.state(x,y,z));
                        int expected=plan.materialAt(position.minBlockX()+x,position.minBlockY()+y,position.minBlockZ()+z);
                        comparisons++;cellIndex++;
                        if(actual!=expected)throw new IllegalStateException("Native body payload differs from exact prepared Source/capture at "+position+" cell "+(cellIndex-1));
                    }
                    require(owner.cheapLive(),"Distance body owner changed during comparison");requireActive();
                    if(cellIndex==4096){
                        proofs[cubeIndex]=new Proof(snapshots[cubeIndex],plan.source(),applicableCapture(snapshots[cubeIndex]));
                        activeCube=null;cellIndex=0;cubeIndex++;
                    }
                    if(owner.clock.getAsLong()-start>=SOFT_SLICE_NANOS)break;
                }
                postStart=owner.clock.getAsLong();requireDependencies();
                // Credit only complete newly proved snapshots after the whole post-slice guard succeeds.
                long complete=0;for(var proof:proofs)if(proof!=null)complete++;
                completedComparisons=(complete-reused)*4096L;
                require(comparisons<=MAX_COMPARISONS&&completedComparisons<=comparisons,"Body comparison bound violated");
            }catch(RuntimeException|Error failure){revoked=true;activeCube=null;throw failure;}
            long end=owner.clock.getAsLong(),elapsed=Math.max(0,end-start);
            return new Slice((int)(comparisons-before),completedComparisons,reused,complete(),elapsed,Math.max(0,elapsed-SOFT_SLICE_NANOS),Math.max(0,preEnd-preStart),Math.max(0,end-postStart),preEnd-start>=SOFT_SLICE_NANOS);
        }
        private void requireActive(){require(activeCube!=null&&activeCube==owner.replica.cubes().get(snapshots[cubeIndex].position())&&activeCube.snapshot()==snapshots[cubeIndex]&&owner.admission.isCurrentLoaded(headers[cubeIndex]),"Partial body cursor lost its exact SceneCube/admission identity");}
        boolean complete(){owner.check();if(closed||revoked||proofs==null)return false;for(var proof:proofs)if(proof==null)return false;return true;}
        boolean dependenciesCurrent(){owner.check();try{requireDependencies();return true;}catch(RuntimeException failure){return false;}}
        private void requireDependencies(){
            try{requireDependenciesRaw();}catch(RuntimeException|Error failure){currentRevoked=true;throw failure;}
        }
        private void requireDependenciesRaw(){
            owner.check();require(!closed&&!revoked&&!currentRevoked&&owner.live()&&(owner.current==this||owner.candidate==this),"Distance body binding revoked");
            require(ready!=null&&snapshots!=null&&!owner.replica.paused()&&!owner.replica.awaitingCommittedReady(),"Body lacks complete current actor readiness");
            if(owner.candidate==this||owner.replica.stagedMetadata()==null||owner.replica.stagedMetadata().generation()==ready.generation())require(owner.replica.candidateReady()==ready,"Body StageReady object changed");
            else require(owner.replica.currentActorGeneration()==ready.generation()&&owner.replica.currentCenter().equals(ready.candidateCenter()),"Published body actor identity changed");
            for(int i=0;i<BODY_CUBES;i++){
                var snapshot=snapshots[i];require(snapshot!=null,"Incomplete body snapshot binding");var cube=owner.replica.cubes().get(snapshot.position());
                require(cube!=null&&cube.snapshot()==snapshot&&owner.admission.isCurrentLoaded(headers[i]),"Body exact snapshot identity/header is no longer current");
            }
            if(activeCube!=null)requireActive();
            require(owner.cheapLive(),"Body resource ownership changed after header checks");
        }
        NativeJoined join(NativeVisualPublication visual){
            owner.check();requireDependencies();require(complete(),"Complete125 value equality required before native join");
            Objects.requireNonNull(visual);visual.requireCurrent();
            require(visual.stamp()==stamp&&visual.plan()==plan,"Exact native publication request/plan object required");
            require(visual.cameraDomain().equals(NativeVisualPublication.cameraPermit(ready.candidateCenter())),"Native publication has a different camera permit");
            for(var target:exactReady){
                var position=target.position();var key=NativePageKey.containing(position.minBlockX(),position.minBlockY(),position.minBlockZ());
                var page=plan.pageIndex().get(key);
                if(page!=null&&page.ownsMember(position)){
                    int member=(key.localY(position.minBlockY())/16*4+key.localZ(position.minBlockZ())/16)*4+key.localX(position.minBlockX())/16;
                    require((visual.drawnMemberMasks().getOrDefault(key,0L)&(1L<<member))!=0,"Exposed body target lacks actual drawable native member completion");
                }
            }
            if(joined!=null)require(joined.visual==visual,"Body is already joined to another native completion");else joined=new NativeJoined(this,visual);
            requireJoined();return joined;
        }
        NativeJoined joined(){owner.check();return joined;}
        long publication(){return publication;}
        long comparisons(){owner.check();return comparisons;}
        long completedComparisons(){owner.check();return completedComparisons;}
        int reusedSnapshots(){owner.check();return reused;}
        boolean isClosed(){owner.check();return closed;}
        List<EngineCubeSnapshot> snapshots(){owner.check();return snapshots==null?List.of():List.copyOf(Arrays.asList(snapshots));}
        void requireCommittedSnapshots(Map<CubePos,EngineCubeSnapshot> collisionSnapshots){
            requireJoined();require(owner.current==this&&owner.replica.stagedMetadata()==null&&owner.replica.currentActorGeneration()==ready.generation(),"Exact installed/committed actor required for collision");
            require(collisionSnapshots.size()==BODY_CUBES&&owner.replica.cubes().size()==BODY_CUBES,"Collision must use precisely the proved125 snapshot map");
            for(var snapshot:snapshots)require(collisionSnapshots.get(snapshot.position())==snapshot,"Collision snapshot differs from exact proved payload");
            requireJoined();
        }
        void requireCommittedSnapshots(){var actual=new LinkedHashMap<CubePos,EngineCubeSnapshot>();for(var entry:owner.replica.cubes().entrySet())actual.put(entry.getKey(),entry.getValue().snapshot());requireCommittedSnapshots(actual);}
        private void requireJoined(){requireDependencies();require(complete()&&joined!=null&&joined.visual!=null,"Body native join is incomplete");joined.visual.requireCurrent();require(joined.visual.stamp()==stamp&&joined.visual.plan()==plan,"Native join identity changed");requireDependencies();}
        void failCleanupForTest(Runnable action){owner.check();beforeAliasRelease=Objects.requireNonNull(action);}
        boolean partialAliasHeld(){owner.check();return activeCube!=null;}
        @Override public void close(){
            owner.check();if(closed)return;revoked=true;activeCube=null;beforeAliasRelease.run();
            if(joined!=null){joined.visual=null;joined.binding=null;}joined=null;
            ready=null;stamp=null;plan=null;replacement=null;snapshots=null;headers=null;proofs=null;exactReady=List.of();beforeAliasRelease=null;closed=true;
        }
    }

    /** Unforgeable exact125-plus-actual-native-completion facet shared with the DH owner. */
    public static final class NativeJoined {
        private Binding binding;
        private NativeVisualPublication visual;
        private NativeJoined(Binding binding,NativeVisualPublication visual){this.binding=binding;this.visual=visual;}
        public List<SceneReadyCube> exactReady(){requireCurrent();return binding.exactReady;}
        public void requirePrepared(List<SceneReadyCube> ready,NativeVisualPublication visual,DhReplacementInput replacement){
            requireCurrent();require(this.visual==visual&&binding.replacement==replacement&&binding.exactReady.equals(ready),"DH request differs from exact native-joined body authority");
        }
        public void requireCurrent(){require(binding!=null,"Native-joined body revoked");binding.requireJoined();}
        public boolean isPrepared(){try{requireCurrent();return true;}catch(RuntimeException failure){return false;}}
        public boolean isPublishedCurrent(){return isPrepared()&&binding.owner.current==binding;}
        public boolean permitsRetainedPresentation(){return binding!=null&&binding.owner.history!=null&&binding.owner.history.joined==this&&binding.owner.history.isPresentable();}
        public History retainPresentation(){
            require(isPublishedCurrent(),"Only the exact published current body may enter historical presentation");
            Owner owner=binding.owner;if(owner.history!=null){require(owner.history.joined==this&&owner.history.isPresentable(),"Only one body presentation ticket is retained");return owner.history;}
            owner.history=new History(owner,this);return owner.history;
        }
    }

    /** Presentation only: older revisions on the same loaded leases, never a current-ready capability. */
    public static final class History implements AutoCloseable {
        private Owner owner;
        private NativeJoined joined;
        private boolean revoked;
        private History(Owner owner,NativeJoined joined){this.owner=owner;this.joined=joined;}
        public NativeJoined joined(){require(isPresentable(),"Body historical presentation revoked");return joined;}
        public NativeVisualPublication visual(){require(isPresentable(),"Body historical presentation revoked");return joined.visual;}
        public boolean isPresentable(){
            Owner retainedOwner=owner;NativeJoined retainedJoined=joined;
            if(retainedOwner==null||revoked)return false;retainedOwner.check();
            try{
                // Any callback can revoke or close this token. Finish with only non-callback exact checks.
                if(!retainedOwner.live()||owner!=retainedOwner||joined!=retainedJoined||revoked
                        ||retainedOwner.history!=this||retainedJoined==null||retainedJoined.binding==null){revoked=true;return false;}
                Binding binding=retainedJoined.binding;
                if(binding.closed||binding.revoked||retainedOwner.current!=binding||binding.snapshots==null
                        ||retainedJoined.visual==null||!retainedJoined.visual.permitsRetainedPresentation()) {revoked=true;return false;}
                if(owner!=retainedOwner||joined!=retainedJoined||revoked||retainedOwner.history!=this
                        ||retainedJoined.binding!=binding||binding.closed||binding.revoked||binding.headers==null){revoked=true;return false;}
                for(var h:binding.headers)if(!retainedOwner.admission.isCurrentLoadedLease(h)){revoked=true;return false;}
                boolean valid=retainedOwner.cheapLive()&&owner==retainedOwner&&joined==retainedJoined
                        &&retainedOwner.history==this&&retainedOwner.current==binding&&!revoked&&!binding.closed&&!binding.revoked;
                if(!valid)revoked=true;return valid;
            }catch(RuntimeException failure){revoked=true;return false;}
        }
        public void requirePresentable(NativeJoined exactJoined,List<SceneReadyCube> ready,NativeVisualPublication visual,DhReplacementInput replacement){
            require(isPresentable(),"Body historical presentation revoked");
            require(joined==exactJoined&&joined.visual==visual&&joined.binding.replacement==replacement&&joined.binding.exactReady.equals(ready),"Historical DH request differs from exact retained body ticket");
        }
        @Override public void close(){if(owner==null)return;owner.check();revoked=true;if(owner.history==this)owner.history=null;joined=null;owner=null;}
    }
    private static List<CubePos> box(CubePos center,int radius,int size){return CubeTickets.box(center,radius,radius,size).stream().sorted().toList();}
    private static CubeStreamAdmission.Header header(EngineCubeSnapshot s){return new CubeStreamAdmission.Header(s.world(),s.epoch(),s.position(),s.leaseGeneration(),s.revision());}
    private static long saturatedAdd(long a,long b){return Long.MAX_VALUE-a<b?Long.MAX_VALUE:a+b;}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
