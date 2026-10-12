package io.earthplatform.minecraft.renderprobe;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Owner-thread GPU publication guard layered over the still-live authoritative admission gate. */
public final class RenderPublicationGuard implements AutoCloseable {
    public static final int MAX_PRESENTED_MESHES = 28;
    public static final int MAX_PRESENTED_DEPENDENCIES = 152;

    public static final class Job {
        private final Object owner;
        private final EngineCubeSnapshot snapshot;
        private final CubeStreamAdmission.Header header;
        private final long resources;
        private boolean published;
        private Job(Object owner,EngineCubeSnapshot snapshot,long resources) {
            this.owner=owner;this.snapshot=snapshot;this.resources=resources;
            header=new CubeStreamAdmission.Header(snapshot.world(),snapshot.epoch(),snapshot.position(),snapshot.leaseGeneration(),snapshot.revision());
        }
        public EngineCubeSnapshot snapshot() { return snapshot; }
        public CubePos position() { return snapshot.position(); }
    }
    /** Opaque provenance for one physical published mesh; creating it allocates no GPU/cube storage. */
    public static final class PresentationSource implements AutoCloseable {
        private final RenderPublicationGuard guard;
        private final Job owner;
        private final List<Job> dependencies;
        private final BooleanSupplier usable;
        private boolean closed;
        private PresentationSource(RenderPublicationGuard guard, Job owner, List<Job> dependencies,
                                   BooleanSupplier usable) {
            this.guard=guard;this.owner=owner;this.dependencies=dependencies;this.usable=usable;
        }
        /** Revoke before freeing any native resource. This remains idempotent if native close needs retry. */
        @Override public void close() {
            guard.checkOwner();
            synchronized(guard.admission) { closed=true; }
        }
    }

    /** Per-mesh capability. Its constructor and exact source identity are deliberately private. */
    public static final class PresentationToken {
        private final PresentationLease lease;
        private final PresentationSource source;
        private PresentationToken(PresentationLease lease, PresentationSource source) {
            this.lease=lease;this.source=source;
        }
    }

    /** One bounded frozen batch. Never confers current revision, collider, edit, or readiness authority. */
    public static final class PresentationLease implements AutoCloseable {
        private final RenderPublicationGuard guard;
        private List<PresentationSource> sources;
        private List<Job> dependencies;
        private List<EngineCubeSnapshot> snapshots;
        private final IdentityHashMap<PresentationSource, PresentationToken> tokens=new IdentityHashMap<>();
        private boolean closed, invalidated;
        private PresentationLease(RenderPublicationGuard guard, List<PresentationSource> sources, List<Job> dependencies) {
            this.guard=guard;this.sources=List.copyOf(sources);this.dependencies=List.copyOf(dependencies);
            this.snapshots=dependencies.stream().map(Job::snapshot).toList();
            for(var source:sources)tokens.put(source,new PresentationToken(this,source));
        }
        public PresentationToken tokenFor(PresentationSource source) {
            guard.checkOwner();
            synchronized(guard.admission) {
                if(closed)throw new IllegalStateException("Presentation lease is closed");
                var token=tokens.get(Objects.requireNonNull(source));
                if(token==null)throw new IllegalArgumentException("Mesh does not belong to this presentation");
                return token;
            }
        }
        /** Existing immutable payload identities only; no cloned sections or additional cube arrays. */
        public List<EngineCubeSnapshot> snapshots(){guard.checkOwner();return snapshots;}
        public int meshCount(){guard.checkOwner();return sources.size();}
        public int dependencyCount(){guard.checkOwner();return dependencies.size();}
        public boolean isPresentable(){guard.checkOwner();synchronized(guard.admission){return guard.presentable(this);}}
        /** Current capability liveness only; deliberately does not assert current source revisions. */
        public boolean isCurrent(){return isPresentable();}
        @Override public void close() {
            guard.checkOwner();
            synchronized(guard.admission) {
                if(closed)return;
                closed=true;
                tokens.clear();sources=List.of();dependencies=List.of();snapshots=List.of();
                if(guard.presentation==this)guard.presentation=null;
            }
        }
    }

    private final Thread thread=Thread.currentThread();
    private final Object identity=new Object();
    private final CubeStreamAdmission admission;
    private long resources=1;
    private boolean closed,failed;
    private final java.util.concurrent.atomic.AtomicBoolean reloadRequested=new java.util.concurrent.atomic.AtomicBoolean();
    private long published,rejected;
    private PresentationLease presentation;
    private boolean capturingPresentation;
    public RenderPublicationGuard(CubeStreamAdmission admission) { this(admission,2); }
    public RenderPublicationGuard(CubeStreamAdmission admission,int ownerBudget) {
        this.admission=Objects.requireNonNull(admission);
        if(ownerBudget<1||ownerBudget>256||admission.maxLeases()>ownerBudget)
            throw new IllegalArgumentException("Renderer admission exceeds its explicit bounded owner budget");
    }
    public Job prepare(EngineCubeSnapshot snapshot) {
        checkOwner();Objects.requireNonNull(snapshot);
        synchronized(admission) {
            Job job=new Job(identity,snapshot,resources);
            if(!current(job))throw new IllegalStateException("Render preparation requires a current loaded lease/revision");
            return job;
        }
    }
    /** Revalidation and GPU installation share admission's retirement lock. Installer must be synchronous. */
    public boolean publish(Job job,Runnable installer) {
        checkOwner();Objects.requireNonNull(job);Objects.requireNonNull(installer);
        synchronized(admission) {
            if(!current(job)){rejected++;return false;}
            try {
                installer.run();
                // A callback must not reentrantly retire its own lease or resource generation.
                // Its candidate remains private until this second validation succeeds.
                if(!current(job))throw new IllegalStateException("GPU installer invalidated its own publication");
                job.published=true;published++;return true;
            }
            catch(RuntimeException|Error failure){failed=true;throw failure;}
        }
    }
    public boolean isCurrent(Job job) { checkOwner();synchronized(admission){return current(job);} }
    /** Revision-only rebinding eligibility. The old Job remains stale; replacement must be a fresh current Job. */
    public boolean isSameLiveLease(Job prior, Job replacement) {
        checkOwner(); Objects.requireNonNull(prior); Objects.requireNonNull(replacement);
        synchronized(admission) {
            if (closed || failed || reloadRequested.get() || prior.owner != identity
                    || prior.resources != resources || !current(replacement)) return false;
            var old = prior.snapshot; var next = replacement.snapshot;
            return old.world().equals(next.world()) && old.epoch() == next.epoch()
                    && old.position().equals(next.position()) && old.leaseGeneration() == next.leaseGeneration()
                    && admission.isCurrentLoadedLease(prior.header);
        }
    }
    /** Ownership, not presentability: invalidated frozen batches still retain their sources until close(). */
    public boolean isRetained(PresentationSource source) {
        checkOwner(); Objects.requireNonNull(source);
        synchronized(admission) {
            return source.guard == this && presentation != null && !presentation.closed
                    && presentation.tokens.containsKey(source);
        }
    }
    /** Linearizes all dependency checks and native GL submission against retirement/reload. */
    public boolean drawIfCurrent(java.util.Collection<Job> dependencies,java.util.function.BooleanSupplier draw) {
        checkOwner();Objects.requireNonNull(dependencies);Objects.requireNonNull(draw);
        synchronized(admission) {
            int count=0;
            for(var dependency:dependencies) {
                if(++count>256)throw new IllegalArgumentException("Draw dependency budget exceeded");
                if(!current(Objects.requireNonNull(dependency)))return false;
            }
            if(count==0)throw new IllegalArgumentException("A draw must carry its owner and dependencies");
            return draw.getAsBoolean();
        }
    }
    /**
     * Bind immutable provenance after successful publication. The callback checks only appearance/resource
     * liveness, never revision currentness; the guard owns all admission and exact-job validation.
     */
    public PresentationSource presentationSource(Job owner, Collection<Job> dependencies, BooleanSupplier usable) {
        checkOwner();Objects.requireNonNull(owner);Objects.requireNonNull(dependencies);Objects.requireNonNull(usable);
        synchronized(admission) {
            var captured=new ArrayList<Job>();
            var positions=new LinkedHashMap<CubePos,Job>();
            boolean containsOwner=false;
            for(var job:dependencies) {
                if(captured.size()>=256)throw new IllegalArgumentException("Mesh dependency budget exceeded");
                Objects.requireNonNull(job);
                if(positions.put(job.position(),job)!=null)throw new IllegalArgumentException("Duplicate mesh dependency coordinate");
                captured.add(job);containsOwner|=job==owner;
            }
            if(!containsOwner||!owner.published)throw new IllegalArgumentException("Presentation source requires its exact published owner");
            var result=new PresentationSource(this,owner,List.copyOf(captured),usable);
            if(!sourceCurrent(result))throw new IllegalStateException("Presentation source is not current and usable");
            return result;
        }
    }

    /** Capture the complete last-published batch BEFORE replacement, under the admission retirement lock. */
    public PresentationLease retainPresentation(Collection<PresentationSource> sources) {
        checkOwner();Objects.requireNonNull(sources);
        synchronized(admission) {
            if(presentation!=null||capturingPresentation)throw new IllegalStateException("Only one retained presentation is allowed");
            capturingPresentation=true;
            try {
                var captured=new ArrayList<PresentationSource>();
                var seen=new IdentityHashMap<PresentationSource,Boolean>();
                var dependencies=new LinkedHashMap<CubePos,Job>();
                for(var source:sources) {
                    if(captured.size()>=MAX_PRESENTED_MESHES)throw new IllegalArgumentException("Presentation mesh budget exceeded");
                    Objects.requireNonNull(source);
                    if(seen.put(source,Boolean.TRUE)!=null)throw new IllegalArgumentException("Duplicate presentation mesh");
                    if(!sourceCurrent(source))throw new IllegalStateException("Retained presentation requires the complete current batch");
                    captured.add(source);
                    for(var dependency:source.dependencies) {
                        var prior=dependencies.get(dependency.position());
                        if(prior!=null&&prior.snapshot!=dependency.snapshot)
                            throw new IllegalArgumentException("Presentation dependency must retain the exact immutable snapshot identity");
                        if(prior==null) {
                            if(dependencies.size()>=MAX_PRESENTED_DEPENDENCIES)
                                throw new IllegalArgumentException("Presentation dependency budget exceeded");
                            dependencies.put(dependency.position(),dependency);
                        }
                    }
                }
                if(captured.isEmpty())throw new IllegalArgumentException("An empty batch cannot retain a presentation");
                // Earlier checks/caller iteration may reenter and invalidate an earlier member.
                for(var source:captured)if(!sourceCurrent(source))throw new IllegalStateException("Presentation changed during capture");
                for(var dependency:dependencies.values())if(!current(dependency))throw new IllegalStateException("Presentation changed during capture");
                for(var source:captured)if(source.closed)throw new IllegalStateException("Presentation closed during capture");
                var result=new PresentationLease(this,captured,new ArrayList<>(dependencies.values()));
                presentation=result; // No fallible callbacks or state mutation precedes complete construction.
                return result;
            } finally { capturingPresentation=false; }
        }
    }

    public boolean isPresented(PresentationToken token, PresentationSource source) {
        checkOwner();Objects.requireNonNull(token);Objects.requireNonNull(source);
        synchronized(admission){return token.source==source&&source.guard==this&&presentable(token.lease);}
    }

    /** Native submission and complete GL restoration remain inside this same retirement/reload fence. */
    public boolean drawIfPresented(PresentationToken token, PresentationSource source, BooleanSupplier draw) {
        checkOwner();Objects.requireNonNull(token);Objects.requireNonNull(source);Objects.requireNonNull(draw);
        synchronized(admission) {
            if(!isPresented(token,source))return false;
            try {
                boolean submitted=draw.getAsBoolean();
                return isPresented(token,source)&&submitted;
            } catch(RuntimeException|Error failure) { failed=true;throw failure; }
        }
    }

    private boolean sourceCurrent(PresentationSource source) {
        if(source.guard!=this||source.closed||!source.owner.published)return false;
        for(var dependency:source.dependencies)if(!current(dependency))return false;
        if(!source.usable.getAsBoolean()||source.closed)return false;
        for(var dependency:source.dependencies)if(!current(dependency))return false;
        return true;
    }
    private boolean presentationMetadataLive(PresentationLease lease) {
        if(lease.guard!=this||lease!=presentation||lease.closed||lease.invalidated||closed||failed||reloadRequested.get())return false;
        for(var source:lease.sources)if(source.closed)return false;
        for(var dependency:lease.dependencies) {
            if(dependency.owner!=identity||dependency.resources!=resources||!admission.isCurrentLoadedLease(dependency.header))return false;
        }
        return true;
    }
    private boolean presentable(PresentationLease lease) {
        if(!presentationMetadataLive(lease)){lease.invalidated=true;return false;}
        try {
            for(var source:lease.sources)if(!source.usable.getAsBoolean()){lease.invalidated=true;return false;}
            if(!presentationMetadataLive(lease)){lease.invalidated=true;return false;}
            return true;
        } catch(RuntimeException|Error failure) { lease.invalidated=true;throw failure; }
    }

    private boolean current(Job job) {
        if(closed||failed||reloadRequested.get()||job.owner!=identity||job.resources!=resources)return false;
        return admission.isCurrentLoaded(job.header);
    }
    /** Prevent new publications at resource-reload start; does not touch GPU state off-thread. */
    public void requestInvalidation(){synchronized(admission){reloadRequested.set(true);}}
    /** Call before destroying/rebuilding GPU resources for a resource-pack reload. */
    public void invalidateResources() {
        checkOwner();
        synchronized(admission) {
            if(closed||failed)throw new IllegalStateException("Renderer is not open");
            try{resources=Math.incrementExact(resources);}catch(ArithmeticException exhausted){failed=true;throw exhausted;}
        }
    }
    public long published(){checkOwner();return published;}
    public long rejected(){checkOwner();return rejected;}
    public long resourceGeneration(){checkOwner();return resources;}
    public boolean invalidationRequested(){return reloadRequested.get();}
    private void checkOwner(){if(Thread.currentThread()!=thread)throw new IllegalStateException("GPU publication requires its render owner thread");}
    @Override public void close(){
        checkOwner();
        synchronized(admission){closed=true;if(presentation!=null)presentation.close();}
    }
}
