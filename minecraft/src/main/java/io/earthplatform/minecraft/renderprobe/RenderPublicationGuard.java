package io.earthplatform.minecraft.renderprobe;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.Objects;

/** Owner-thread GPU publication guard layered over the still-live authoritative admission gate. */
public final class RenderPublicationGuard implements AutoCloseable {
    public static final class Job {
        private final Object owner;
        private final EngineCubeSnapshot snapshot;
        private final CubeStreamAdmission.Header header;
        private final long resources;
        private Job(Object owner,EngineCubeSnapshot snapshot,long resources) {
            this.owner=owner;this.snapshot=snapshot;this.resources=resources;
            header=new CubeStreamAdmission.Header(snapshot.world(),snapshot.epoch(),snapshot.position(),snapshot.leaseGeneration(),snapshot.revision());
        }
        public EngineCubeSnapshot snapshot() { return snapshot; }
        public CubePos position() { return snapshot.position(); }
    }
    private final Thread thread=Thread.currentThread();
    private final Object identity=new Object();
    private final CubeStreamAdmission admission;
    private long resources=1;
    private boolean closed,failed;
    private final java.util.concurrent.atomic.AtomicBoolean reloadRequested=new java.util.concurrent.atomic.AtomicBoolean();
    private long published,rejected;
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
                published++;return true;
            }
            catch(RuntimeException|Error failure){failed=true;throw failure;}
        }
    }
    public boolean isCurrent(Job job) { checkOwner();synchronized(admission){return current(job);} }
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
    private boolean current(Job job) {
        if(closed||failed||reloadRequested.get()||job.owner!=identity||job.resources!=resources)return false;
        return admission.isCurrentLoaded(job.header);
    }
    /** Prevent new publications at resource-reload start; does not touch GPU state off-thread. */
    public void requestInvalidation(){synchronized(admission){reloadRequested.set(true);}}
    /** Call before destroying/rebuilding GPU resources for a resource-pack reload. */
    public void invalidateResources() {
        checkOwner();if(closed||failed)throw new IllegalStateException("Renderer is not open");
        try{resources=Math.incrementExact(resources);}catch(ArithmeticException exhausted){failed=true;throw exhausted;}
    }
    public long published(){checkOwner();return published;}
    public long rejected(){checkOwner();return rejected;}
    public long resourceGeneration(){checkOwner();return resources;}
    public boolean invalidationRequested(){return reloadRequested.get();}
    private void checkOwner(){if(Thread.currentThread()!=thread)throw new IllegalStateException("GPU publication requires its render owner thread");}
    @Override public void close(){checkOwner();closed=true;}
}
