package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.*;

/**
 * One server owner, one pending exact image, one durable generation chain. Async work sees only a
 * frozen store Image and its persistent parent generation. Completion must be polled on the owner
 * thread. Neither a future finishing nor an uncertain store write acknowledges cube durability.
 *
 * This supplies coherent checkpoint mechanics, not a player.dat transaction or entity-save hook.
 * Host entry/exit must hold actions, pickup, menus and entity/tick simulation through escrow phases.
 */
public final class SurvivalCheckpointCoordinator {
    @FunctionalInterface public interface Capture { Boundary capture(); }
    @FunctionalInterface public interface BoundedCapture { Boundary capture(int availableNativePayloadBytes); }
    public enum State { IDLE, FROZEN, WRITING, FAILED }
    private final NativeWholeAction.Owner actions;
    private final NativeSurvivalWorld world;
    private final SurvivalCheckpointStore store;
    private final SurvivalCheckpointStore.Limits storeLimits;
    private final SurvivalCheckpointCodec codec;
    private final io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Reservation memory;
    private NativeSurvivalCheckpoint durable;
    private Pending pending, heldAcknowledgement;
    private final ArrayList<Pending> transitionAcknowledgements=new ArrayList<>(2);
    private long retainedTransitionGraphs(){long bytes=0;for(var value:transitionAcknowledgements)bytes=Math.addExact(bytes,value.ownership.charged());return bytes;}
    private long durableEncodedBound;
    private final Set<DurableCubeLease> durableCubeLeases=Collections.newSetFromMap(new IdentityHashMap<>());
    /** Cube-only independent persistence lease. No prior checkpoint/request graph is retained. */
    public static final class DurableCubeLease {
        private final SurvivalCheckpointCoordinator owner;
        private io.earthplatform.core.ImmutableCube payload;
        private DurableCubeLease(SurvivalCheckpointCoordinator owner,io.earthplatform.core.ImmutableCube payload){this.owner=owner;this.payload=payload;}
    }
    private long pinnedCubeBytes(){long bytes=0;for(var lease:durableCubeLeases)bytes=Math.addExact(bytes,64L+lease.payload.payloadBytes());return bytes;}
    /** Only an exact already-paid CURRENT payload may transfer to this separate bounded lease. */
    public Optional<DurableCubeLease> pinCurrentDurableCube(io.earthplatform.minecraft.survival.world.CubeSnapshot before){
        world.checkOwner();memory.requireRetainedOwner(this);
        if(actions.fenced()||pending!=null||heldAcknowledgement!=null||!transitionAcknowledgements.isEmpty()||durableCubeLeases.size()>=2)
            throw new SurvivalRefusal("Durable cube alias pin exceeds existing two proof owners or overlaps a checkpoint");
        if(before==null||!before.world().equals(world.world())||!before.ownerEpoch().equals(world.ownerEpoch()))
            throw new SurvivalRefusal("Foreign durable cube alias source");
        var saved=durable==null?null:durable.cubes().get(before.position());
        if(saved==null||saved.blocks()!=before.blocks()||saved.simulationRevision()!=before.simulationRevision()
                ||saved.durableRevision()!=before.durableRevision())return Optional.empty();
        long bytes=64L+saved.blocks().payloadBytes();
        if(Math.addExact(graphBytes(durable),Math.addExact(pinnedCubeBytes(),bytes))>memory.bytes())
            throw new SurvivalRefusal("Durable cube pin exceeds existing persistence envelope");
        var lease=new DurableCubeLease(this,saved.blocks());durableCubeLeases.add(lease);return Optional.of(lease);
    }
    /** Capture/validate the whole bounded set before any retirement; no user Collection survives. */
    private DurableCubeLease[] prepareCubeLeaseRelease(Collection<DurableCubeLease> leases){
        world.checkOwner();memory.requireRetainedOwner(this);
        if(leases==null||leases.size()>2)throw new SurvivalRefusal("Invalid durable cube retirement set");
        var selected=leases.toArray(new DurableCubeLease[0]);
        if(selected.length>2)throw new SurvivalRefusal("Durable cube retirement set changed");
        for(int i=0;i<selected.length;i++){
            var lease=selected[i];
            if(lease==null||lease.owner!=this||lease.payload==null||!durableCubeLeases.contains(lease)
                    ||i==1&&lease==selected[0])throw new SurvivalRefusal("Foreign, repeated or already released durable cube lease");
        }
        return selected;
    }
    private void retireCubeLeaseFields(DurableCubeLease[] selected){
        for(int i=0;i<selected.length;i++){var lease=selected[i];durableCubeLeases.remove(lease);lease.payload=null;}
    }
    public void releaseDurableCubeLeases(Collection<DurableCubeLease> leases){
        var selected=prepareCubeLeaseRelease(leases);retireCubeLeaseFields(selected);
    }
    /** Full current durable graph, independently charged even when live world aliases its cubes. */
    public long retainedPersistenceBytes(){world.checkOwner();return pending!=null?pending.ownership.charged():
            heldAcknowledgement!=null?heldAcknowledgement.ownership.charged():!transitionAcknowledgements.isEmpty()?retainedTransitionGraphs():Math.addExact(graphBytes(durable),pinnedCubeBytes());}
    private static long graphBytes(NativeSurvivalCheckpoint value){
        if(value==null)return 0;
        long bytes=4096L+value.actorSequences().size()*64L+value.cubes().size()*512L;
        for(var cube:value.cubes().values())bytes=Math.addExact(bytes,64L+cube.blocks().payloadBytes());
        var boundary=value.boundary();
        bytes=Math.addExact(bytes,boundary.inventory().encodedBytes()+boundary.food().encodedBytes()+boundary.pose().encodedBytes()+4096L);
        for(var item:boundary.items())bytes=Math.addExact(bytes,512L+item.encodedBytes()+item.stack().encodedBytes());
        bytes=Math.addExact(bytes,value.session().ordinaryEscrow().encodedBytes()+value.session().ordinaryFood().encodedBytes()+value.session().ordinaryPose().encodedBytes());
        return Math.addExact(bytes,boundary.scheduledTicks().size()*2048L);
    }
    private long nextGraphBound(int nativeBytes){
        long bytes=8192L+world.actorCount()*128L+world.ownedCount()*768L+2L*nativeBytes;
        if(durable!=null)bytes=Math.addExact(bytes,graphBytes(durable)); // Conservative complete override union.
        for(var position:world.ownedPositions())bytes=Math.addExact(bytes,64L+world.snapshot(position).blocks().payloadBytes());
        return bytes;
    }
    private static final class Pending {
        final NativeSurvivalWorld.CheckpointTicket ticket;
        final Boundary boundary;
        final Session session;
        final Map<CubePos, Cube> priorCubes;
        final long previousGeneration;
        NativeSurvivalCheckpoint checkpoint;
        SurvivalCheckpointStore.Image image;
        WriteOwner write;
        final SurvivalCheckpointOwnership ownership;
        final boolean retainAcknowledgement,transitionRetention;
        final Object transitionPurpose;
        Throwable failure;
        Pending(NativeSurvivalWorld.CheckpointTicket ticket, Boundary boundary, Session session,
                Map<CubePos, Cube> priorCubes, long previousGeneration, SurvivalCheckpointOwnership ownership, boolean retainAcknowledgement,boolean transitionRetention,Object transitionPurpose) {
            this.ticket = ticket; this.boundary = boundary; this.session = session;
            this.priorCubes = priorCubes; this.previousGeneration = previousGeneration;
            this.ownership=ownership;this.retainAcknowledgement=retainAcknowledgement;this.transitionRetention=transitionRetention;this.transitionPurpose=transitionPurpose;
        }
    }
    /** The store remains caller-owned. Load/identity validation happens before accepting any capture. */
    public SurvivalCheckpointCoordinator(NativeWholeAction.Owner actions, SurvivalCheckpointStore store,
            SurvivalCheckpointStore.Limits storeLimits, SurvivalCheckpointCodec codec,
            io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Reservation memory) throws IOException {
        this.memory=Objects.requireNonNull(memory);
        if(memory.bytes()!=SurvivalCheckpointOwnership.CAPACITY||memory.pool()!=io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE)
            throw new SurvivalRefusal("Exact existing12MiB checkpoint CAPSULE reservation required");
        memory.claimRetainedOwner(this); // Before any constructor load/decode or retained graph allocation.
        this.actions = Objects.requireNonNull(actions); this.world = actions.world();
        this.store = Objects.requireNonNull(store); this.storeLimits = Objects.requireNonNull(storeLimits);
        this.codec = Objects.requireNonNull(codec);store.requireLimits(storeLimits); requireQuiescent();
        if (!codec.binding().world().equals(world.world()) || world.hasPendingCheckpoint())
            throw new SurvivalRefusal("Checkpoint coordinator requires its exact idle world owner");
        if(storeLimits.maxTotalBytes()>4L*1024*1024||storeLimits.maxManifestBytes()>64*1024
                ||2*storeLimits.maxTotalBytes()+SurvivalCheckpointOwnership.CODEC_SCRATCH>memory.bytes())
            throw new SurvivalRefusal("Checkpoint load peak exceeds retained memory profile");
        Optional<SurvivalCheckpointStore.Image> restored = store.load();
        if(restored.isPresent())requireDecodePeak(restored.orElseThrow().payloadBytes(),codec.limits(),memory.bytes());
        durable = restored.map(codec::decode).orElse(null);
        durableEncodedBound=restored.isPresent()?restored.orElseThrow().payloadBytes():0;
        if(graphBytes(durable)>SurvivalCheckpointOwnership.CAPACITY)
            throw new SurvivalRefusal("Loaded checkpoint graph exceeds existing persistence reservation");
    }
    /** Includes malformed input and bounded canonical re-encode before any decoder invocation. */
    static void requireDecodePeak(long bytes,Limits limits,long available){
        long wrappers=limits.cubes()*1024L+limits.actors()*128L+limits.items()*512L+limits.scheduledTicks()*2048L;
        // Item NBT and its independently encoded JSON stack can coexist; do not assume same size.
        long itemStacks=(long)limits.items()*io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot.MAX_BYTES;
        long peak=Math.addExact(SurvivalCheckpointOwnership.CODEC_SCRATCH,
                Math.addExact(12L*bytes,Math.addExact(wrappers,itemStacks)));
        if(bytes<1||peak>available)throw new SurvivalRefusal("Loaded checkpoint decode/canonicality peak exceeds retained reservation; explicit recovery required");
    }
    public void requireGlobalPersistenceReservation(){
        world.checkOwner();memory.requireRetainedOwner(this);
        if(!memory.ownedBy(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.global()))
            throw new SurvivalRefusal("Installed checkpoint requires its actual global CAPSULE owner");
    }
    public NativeWholeAction.Owner actions() { world.checkOwner(); return actions; }
    public Binding binding() { world.checkOwner(); return codec.binding(); }
    public Limits limits() { world.checkOwner(); return codec.limits(); }
    public Optional<NativeSurvivalCheckpoint> durable() { world.checkOwner(); return Optional.ofNullable(durable); }
    /** Complete verified durable overrides, including clean cubes no longer resident/owned. */
    public SortedMap<CubePos, Cube> completeDurableCubes() {
        world.checkOwner();
        return durable == null ? Collections.unmodifiableSortedMap(new TreeMap<>()) : durable.cubes();
    }
    public long durableGeneration() { world.checkOwner(); return durable == null ? 0 : durable.generation(); }
    public State state() {
        world.checkOwner();
        return pending == null ? State.IDLE : pending.failure != null ? State.FAILED : pending.write != null ? State.WRITING : State.FROZEN;
    }
    public Throwable failure() { world.checkOwner(); return pending == null ? null : pending.failure; }
    public Optional<NativeSurvivalWorld.CheckpointTicket> pendingTicket() {
        world.checkOwner(); return pending == null ? Optional.empty() : Optional.of(pending.ticket);
    }
    /** This callback must capture complete admitted host ownership; it must not plan, tick or mutate. */
    public void capture(Session session, Capture source) {
        Objects.requireNonNull(source); captureBounded(session, available -> source.capture());
    }
    /** Production native capture should pass this remaining allowance to captureBoundary. */
    public void captureBounded(Session session, BoundedCapture source) {captureBounded(session,source,false,false,null,codec.limits().payloadBytes());}
    /** Held native epoch retains old/frozen ownership through exact host release after ack. */
    public void captureHeldBounded(Session session,int maximumNativeBytes,BoundedCapture source){captureBounded(session,source,true,false,null,maximumNativeBytes);}
    /** Normal installed transition keeps both phase receipts charged until final Host release. */
    public void captureTransitionBounded(Object exactPurpose,Session session,int maximumNativeBytes,BoundedCapture source){captureBounded(session,source,true,true,Objects.requireNonNull(exactPurpose),maximumNativeBytes);}
    private void captureBounded(Session session, BoundedCapture source, boolean retainAcknowledgement,boolean transitionRetention,Object transitionPurpose,int maximumNativeBytes) {
        if(maximumNativeBytes<1||maximumNativeBytes>codec.limits().payloadBytes())throw new SurvivalRefusal("Invalid precharged native capture bound");
        requireQuiescent(); Objects.requireNonNull(session); Objects.requireNonNull(source);
        if (pending != null || heldAcknowledgement != null || world.hasPendingCheckpoint()
                ||!transitionRetention&&!transitionAcknowledgements.isEmpty()||transitionAcknowledgements.size()>=2) throw new SurvivalRefusal("Finish/retry the exact pending checkpoint first");
        if(transitionRetention){
            if(transitionAcknowledgements.isEmpty()){
                if(session.phase()!=SessionPhase.ENTERING&&session.phase()!=SessionPhase.RETURNING)
                    throw new SurvivalRefusal("Normal transition must retain its exact first phase");
            }else{
                var first=transitionAcknowledgements.get(0);
                SessionPhase next=first.session.phase()==SessionPhase.ENTERING?SessionPhase.ACTIVE:SessionPhase.RETURNED;
                if(first.transitionPurpose!=transitionPurpose||durable!=first.checkpoint||session.phase()!=next
                        ||!first.session.id().equals(session.id()))
                    throw new SurvivalRefusal("Normal transition changed its private purpose or exact phase handoff");
            }
        }
        validateSession(session);
        long sequence = world.commitSequence();
        Map<UUID,Long> beforeActors=world.actionSequences();
        Set<CubePos> positions = world.ownedPositions();
        if (positions.size() > world.limits().checkpointCubes()) throw new SurvivalRefusal("Complete owned capture exceeds frozen cube admission");
        Set<CubePos> all = new HashSet<>(positions);
        if (durable != null) all.addAll(durable.cubes().keySet());
        if (all.size() > codec.limits().cubes() || world.actorCount() > codec.limits().actors())
            throw new SurvivalRefusal("Complete durable checkpoint exceeds admission");
        // A loaded manifest is not permission to regenerate over its edited cubes.
        if (durable != null) for (CubePos position : positions) {
            Cube prior = durable.cubes().get(position);
            if (prior == null) continue;
            var live = world.snapshot(position);
            if (live.durableRevision() != prior.durableRevision() || live.simulationRevision() < prior.simulationRevision()
                    || live.simulationRevision() == prior.simulationRevision() && live.blocks()!=prior.blocks())
                throw new SurvivalRefusal("Owned cube has not recovered the exact durable checkpoint lineage");
        }
        // This reservation precedes retained cube wrappers, native capture, encoding and async ownership.
        var ownership=new SurvivalCheckpointOwnership(graphBytes(durable),nextGraphBound(maximumNativeBytes),
                durableEncodedBound,Math.min(codec.limits().payloadBytes(),storeLimits.maxPayloadBytes()),pinnedCubeBytes(),retainedTransitionGraphs());
        Map<CubePos, io.earthplatform.minecraft.survival.world.CubeSnapshot> beforeCubes = new HashMap<>();
        Map<CubePos, NativeSurvivalWorld.Presence> beforePresence = new HashMap<>();
        for (CubePos position : positions) { beforeCubes.put(position, world.snapshot(position)); beforePresence.put(position, world.presence(position)); }
        int remainingNativeBytes = Math.min(maximumNativeBytes,Math.subtractExact(codec.limits().payloadBytes(), Math.addExact(Math.addExact(session.ordinaryEscrow().encodedBytes(), NativeFoodSnapshot.ENCODED_BYTES), session.ordinaryPose().encodedBytes())));
        if (remainingNativeBytes < 1) throw new SurvivalRefusal("Ordinary escrow exhausts checkpoint payload admission");
        Boundary boundary = Objects.requireNonNull(source.capture(remainingNativeBytes));
        NativeSurvivalCheckpoint.checkedBoundaryBytes(boundary.inventory(), boundary.food(), boundary.pose(), boundary.items(), boundary.scheduledTicks(), remainingNativeBytes);
        requireQuiescent();
        if (world.commitSequence() != sequence || !beforeActors.equals(world.actionSequences()) || !world.ownedPositions().equals(positions))
            throw new SurvivalRefusal("Checkpoint source changed simulation/ownership during capture");
        for (CubePos position : positions)
            if (!sameSnapshot(beforeCubes.get(position),world.snapshot(position)) || beforePresence.get(position) != world.presence(position))
                throw new SurvivalRefusal("Checkpoint source replaced or changed exact cube ownership during capture");
        if (boundary.items().size() > codec.limits().items() || boundary.scheduledTicks().size() > codec.limits().scheduledTicks())
            throw new SurvivalRefusal("Native capture exceeds checkpoint admission");
        Map<CubePos, Cube> previous = durable == null ? Map.of() : durable.cubes();
        // After obtaining this token, even encoding/queue failures retain it and every frozen value.
        var ticket = world.freezeCheckpoint(positions);
        pending = new Pending(ticket, boundary, session, previous, durableGeneration(),ownership,retainAcknowledgement,transitionRetention,transitionPurpose);
        try { encodePending(); }
        catch (RuntimeException | Error failure) { fail(failure); throw failure; }
    }
    private void encodePending() {
        Pending value = pending;
        if (value.checkpoint == null) {
            Map<CubePos, Cube> cubes = new TreeMap<>(value.priorCubes);
            for (var frozen : value.ticket.cubes().entrySet()) {
                var snapshot = frozen.getValue().snapshot();
                cubes.put(frozen.getKey(), new Cube(snapshot.simulationRevision(), frozen.getValue().nextDurableRevision(), snapshot.blocks()));
            }
            value.checkpoint = new NativeSurvivalCheckpoint(codec.limits(), codec.binding(), value.ticket.ownerEpoch(),
                    Math.incrementExact(value.previousGeneration), value.ticket.commitSequence(), value.ticket.actorSequences(),
                    cubes, value.boundary, value.session);
        }
        if (value.image == null) value.image = codec.image(storeLimits, value.checkpoint,value.ownership.maximumEncoded());
    }
    public void submit(Executor ioExecutor) {
        requireQuiescent(); Objects.requireNonNull(ioExecutor);
        if (pending == null || pending.failure != null || pending.write != null || pending.image == null)
            throw new SurvivalRefusal("A frozen, nonfailed checkpoint is required for submission");
        // Register ownership BEFORE dispatch. An executor that queues then throws is uncertain;
        // its exact owner remains retained and cannot be retried until the runnable really drains.
        var writer=new WriteOwner(store,pending.previousGeneration,pending.image);pending.write=writer;
        try { ioExecutor.execute(writer); }
        catch (RuntimeException | Error failure) { fail(failure); throw failure; }
    }
    private static final class WriteOwner implements Runnable {
        private SurvivalCheckpointStore store;
        private SurvivalCheckpointStore.Image image;
        private final long previousGeneration;
        private final CompletableFuture<SurvivalCheckpointStore.Receipt> result=new CompletableFuture<>();
        private volatile boolean drained;
        private boolean started;
        WriteOwner(SurvivalCheckpointStore store,long previousGeneration,SurvivalCheckpointStore.Image image){
            this.store=store;this.previousGeneration=previousGeneration;this.image=image;
        }
        public void run(){
            synchronized(this){if(started)return;started=true;}
            SurvivalCheckpointStore.Receipt receipt=null;Throwable failure=null;
            try{receipt=store.commit(previousGeneration,image);}
            catch(Throwable thrown){failure=thrown;}
            finally{store=null;image=null;drained=true;}
            // No retained native/checkpoint/image alias survives this private completion point.
            if(failure==null)result.complete(receipt);else result.completeExceptionally(failure);
        }
    }
    /** True only after the actual store receipt has been consumed and this exact owner ticket acknowledged. */
    public boolean poll() {
        requireQuiescent();
        if (pending == null || pending.failure != null || pending.write == null || (!pending.write.drained || !pending.write.result.isDone())) return false;
        final SurvivalCheckpointStore.Receipt receipt;
        try { receipt = pending.write.result.join(); }
        catch (CompletionException | CancellationException failure) { fail(failure); return false; }
        if (receipt.generation() != pending.image.generation() || receipt.payloadCount() != pending.image.names().size() || receipt.payloadBytes()!=pending.image.payloadBytes()) {
            fail(new IllegalStateException("Store receipt does not describe the frozen checkpoint")); return false;
        }
        // A later simulation action is allowed while I/O runs. World ack only marks frozen revisions durable.
        world.acknowledgeCheckpoint(pending.ticket);
        durable = pending.checkpoint;durableEncodedBound=pending.image.payloadBytes();pending.ownership.acknowledge();
        if(pending.transitionRetention){
            // The exact writer has drained. Images/codecs may retire, old/frozen graphs may not.
            pending.image=null;pending.ownership.retireDrainedWriter();transitionAcknowledgements.add(pending);
        } else if(pending.retainAcknowledgement)heldAcknowledgement=pending;
        else {pending.ownership.requireRetirable();pending.ownership.retire();}
        pending=null;return true;
    }
    public boolean retryReady(){
        requireQuiescent();return pending!=null&&pending.failure!=null
                &&(pending.write==null||pending.write.drained&&pending.write.result.isDone());
    }
    public void retry() {
        requireQuiescent();
        if (pending == null || pending.failure == null || pending.write != null && (!pending.write.drained || !pending.write.result.isDone()))
            throw new SurvivalRefusal("Only the exact failed checkpoint can be retried");
        world.retryCheckpoint(pending.ticket); pending.failure = null; pending.write = null;
        try { encodePending(); }
        catch (RuntimeException | Error failure) { fail(failure); throw failure; }
    }
    /** Final normal transition release, after exact installed/restored/ordinary-save validation. */
    public void releaseTransitionAcknowledgements(Object exactPurpose,NativeSurvivalWorld.CheckpointTicket lastTicket,NativeSurvivalCheckpoint exact){
        requireQuiescent();int count=transitionAcknowledgements.size();
        if(pending!=null||heldAcknowledgement!=null||count!=2||durable!=exact||world.hasPendingCheckpoint()
                ||exactPurpose==null||transitionAcknowledgements.get(count-1).checkpoint!=exact
                ||transitionAcknowledgements.get(count-1).ticket!=lastTicket)
            throw new SurvivalRefusal("Normal transition lacks both exact acknowledged phase leases");
        for(int i=0;i<count;i++){
            var value=transitionAcknowledgements.get(i);
            if(!value.transitionRetention||value.transitionPurpose!=exactPurpose||value.ticket==null
                    ||!value.ticket.ownerEpoch().equals(world.ownerEpoch())||!value.ticket.world().equals(world.world())
                    ||value.checkpoint.generation()!=Math.incrementExact(value.previousGeneration)||value.write==null||!value.write.drained||!value.write.result.isDone()||value.image!=null
                    ||i>0&&value.previousGeneration!=transitionAcknowledgements.get(i-1).checkpoint.generation())
                throw new SurvivalRefusal("Normal transition graph/writer handoff changed");
            value.ownership.requireRetirable();
        }
        // Every throwing validation is complete. No callback or allocation in this retirement tail.
        for(int i=0;i<count;i++){var value=transitionAcknowledgements.get(i);value.write=null;value.ownership.retire();}
        transitionAcknowledgements.clear();
    }
    /** Does not release native images: Host separately owns those until its same held release. */
    public void releaseHeldAcknowledgement(NativeSurvivalWorld.CheckpointTicket ticket,NativeSurvivalCheckpoint exact){
        releaseHeldAcknowledgement(ticket,exact,List.of());
    }
    /** One validated ack+pin retirement; cleanup failure cannot retire an old graph prefix. */
    public void releaseHeldAcknowledgement(NativeSurvivalWorld.CheckpointTicket ticket,NativeSurvivalCheckpoint exact,
            Collection<DurableCubeLease> leases){
        var selected=prepareCubeLeaseRelease(leases);
        requireQuiescent();var value=heldAcknowledgement;
        if(value==null||pending!=null||value.ticket!=ticket||value.checkpoint!=exact||durable!=exact
                ||value.write==null||!value.write.drained||!value.write.result.isDone()||world.hasPendingCheckpoint())
            throw new SurvivalRefusal("Held acknowledgement still has a writer or foreign release owner");
        value.ownership.requireRetirable();
        // No allocation, Collection traversal, callbacks or throwing validation follows this point.
        retireCubeLeaseFields(selected);heldAcknowledgement=null;value.write=null;value.image=null;value.ownership.retire();
    }
    private void fail(Throwable failure) {
        if(pending.failure==null)pending.failure=failure;
        else if(pending.failure!=failure)pending.failure.addSuppressed(failure);
        if (world.checkpointState(pending.ticket) == NativeSurvivalWorld.CheckpointState.IN_FLIGHT)
            world.failCheckpoint(pending.ticket);
    }
    private static boolean sameSnapshot(io.earthplatform.minecraft.survival.world.CubeSnapshot a,
            io.earthplatform.minecraft.survival.world.CubeSnapshot b){
        return a.stamp().equals(b.stamp())&&a.position().equals(b.position())&&a.blocks()==b.blocks()
                &&a.durableRevision()==b.durableRevision()&&a.durableSimulationRevision()==b.durableSimulationRevision();
    }
    private void requireQuiescent() {
        memory.requireRetainedOwner(this);
        world.checkOwner();
        if (actions.world() != world || actions.hasCurrentAction() || actions.fenced() || world.hasActiveTransaction())
            throw new SurvivalRefusal("Coherent checkpoint requires the exact idle unfenced whole-action owner");
    }
    private void validateSession(Session next) {
        if (durable == null) {
            if (next.phase() != SessionPhase.ENTERING) throw new SurvivalRefusal("First checkpoint must durably escrow ENTERING before activation");
            return;
        }
        Session previous = durable.session();
        if (!previous.id().equals(next.id())) {
            if (previous.phase() != SessionPhase.RETURNED || next.phase() != SessionPhase.ENTERING)
                throw new SurvivalRefusal("Unfinished escrow session cannot be replaced");
            return;
        }
        if (!previous.ordinaryEscrow().equals(next.ordinaryEscrow()) || !previous.ordinaryFood().equals(next.ordinaryFood()) || !previous.ordinaryPose().equals(next.ordinaryPose())) throw new SurvivalRefusal("Ordinary escrow is immutable for its session");
        int change = next.phase().ordinal() - previous.phase().ordinal();
        if (change < 0 || change > 1) throw new SurvivalRefusal("Escrow transition skipped or reversed a durable phase");
    }
}
