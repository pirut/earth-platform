package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded, owner-thread DH page publication and native-mesh overlap policy.
 * This class contains no GL resources and never promotes missing data to air.
 */
public final class DhPageOwnership implements AutoCloseable {
    public static final int SCHEMA = 1;
    public static final int PAGE_HEIGHT = 2048;
    public static final int DETAIL = 0;
    public static final int MAX_SNAPSHOTS = 2;

    /** Every generation and coordinate-system input participates in page identity. */
    public record Namespace(WorldIdentity world, UUID saveSessionId, String dimension,
                            String projection, String projectionVersion, String verticalDatum,
                            int schema, int pageHeight) {
        public Namespace {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(saveSessionId, "saveSessionId");
            requireComponent(dimension, "dimension");
            requireComponent(projection, "projection");
            requireComponent(projectionVersion, "projectionVersion");
            requireComponent(verticalDatum, "verticalDatum");
            if (schema != SCHEMA || pageHeight != PAGE_HEIGHT) {
                throw new IllegalArgumentException("DH ownership requires schema 1 and page height 2048");
            }
        }

        public Namespace(WorldIdentity world, UUID saveSessionId, String dimension,
                         String projection, String projectionVersion, String verticalDatum) {
            this(world, saveSessionId, dimension, projection, projectionVersion, verticalDatum, SCHEMA, PAGE_HEIGHT);
        }

        private static void requireComponent(String component, String name) {
            Objects.requireNonNull(component, name);
            if (component.isBlank() || component.length() > 1024) {
                throw new IllegalArgumentException("Invalid DH namespace " + name);
            }
        }
    }

    /** Signed vertical page plus cube-sized X/Z tile. Full cube Y remains in Job.cube(). */
    public record PageKey(Namespace namespace, long pageY, int tileX, int tileZ, int detail) {
        public PageKey {
            Objects.requireNonNull(namespace, "namespace");
            if (detail != DETAIL) throw new IllegalArgumentException("Only detail zero is supported");
        }
    }

    /** Opaque publication capability; callers cannot substitute a key or ownership token. */
    public static final class Job {
        private final PageKey key;
        private final RenderPublicationGuard.Job publication;

        private Job(PageKey key, RenderPublicationGuard.Job publication) {
            this.key = key;
            this.publication = publication;
        }

        public PageKey key() { return key; }
        public EngineCubeSnapshot snapshot() { return publication.snapshot(); }
        public CubePos cube() { return publication.position(); }
    }

    private final Thread ownerThread = Thread.currentThread();
    private final CubeStreamAdmission admission;
    private final Namespace namespace;
    private final RenderPublicationGuard guard;
    // One successful publication per full cube coordinate, never one per flattened X/Z page.
    private final Map<CubePos, Job> publishedJobs = new HashMap<>();
    private long drawn, rejectedDraws;

    public DhPageOwnership(CubeStreamAdmission admission, Namespace namespace) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        if (!admission.world().equals(namespace.world())) {
            throw new IllegalArgumentException("DH namespace world does not match admission world");
        }
        guard = new RenderPublicationGuard(admission);
    }

    public Namespace namespace() { checkOwner(); return namespace; }

    public Job prepare(EngineCubeSnapshot snapshot) {
        checkOwner();
        Objects.requireNonNull(snapshot, "snapshot");
        synchronized (admission) {
            pruneStalePublications();
            var publication = guard.prepare(snapshot);
            var cube = snapshot.position();
            var key = new PageKey(namespace, Math.floorDiv(cube.minBlockY(), PAGE_HEIGHT),
                    cube.x(), cube.z(), DETAIL);
            return new Job(key, publication);
        }
    }

    /**
     * Revalidates and runs the synchronous installer under admission's retirement lock.
     * The installer must keep its candidate private until this method returns true.
     * Failed installation poisons this owner, as it does the underlying render guard.
     */
    public boolean publish(Job job, Runnable installer) {
        checkOwner();
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(installer, "installer");
        synchronized (admission) {
            pruneStalePublications();
            if (!guard.publish(job.publication, installer)) return false;
            publishedJobs.put(job.cube(), job);
            return true;
        }
    }

    /** Prepared jobs may be current before their GL candidate has been published. */
    public boolean isCurrent(Job job) {
        checkOwner();
        Objects.requireNonNull(job, "job");
        synchronized (admission) {
            pruneStalePublications();
            return guard.isCurrent(job.publication);
        }
    }

    /**
     * The caller must collect this bounded list immediately before each draw from actual,
     * successfully published native meshes whose native publication guard is still current.
     * Admitted snapshots or queued/in-progress mesh builds are not readiness evidence.
     * No near mask is retained here: native retirement or a failed replacement cannot leave
     * stale coverage behind. Known-air coverage likewise requires a published empty native mesh.
     */
    public boolean shouldDrawLod(Job job, Collection<EngineCubeSnapshot> currentPublishedNativeMeshes) {
        checkOwner();
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(currentPublishedNativeMeshes, "currentPublishedNativeMeshes");
        synchronized (admission) {
            // Bound traversal rather than trusting Collection.size(), and validate even for stale jobs.
            var ready = new ArrayList<EngineCubeSnapshot>(MAX_SNAPSHOTS);
            for (var nativeSnapshot : currentPublishedNativeMeshes) {
                if (ready.size() == MAX_SNAPSHOTS) {
                    throw new IllegalArgumentException("Native readiness exceeds two admitted snapshots");
                }
                ready.add(Objects.requireNonNull(nativeSnapshot, "native snapshot"));
            }
            pruneStalePublications();
            if (!guard.isCurrent(job.publication) || publishedJobs.get(job.cube()) != job) return false;
            for (var nativeSnapshot : ready) {
                if (sameOwnership(job.snapshot(), nativeSnapshot)) return false;
            }
            return true;
        }
    }

    /**
     * Atomically checks the full ready mask and submits draw commands while holding the
     * same retirement/reload monitor. This linearizes CPU submission, not GPU completion.
     * Other-thread invalidation cannot return until an already-entered callback returns.
     */
    public boolean drawIfCurrent(Job job, Collection<EngineCubeSnapshot> currentPublishedNativeMeshes, Runnable draw) {
        checkOwner();
        Objects.requireNonNull(draw,"draw");
        synchronized(admission) {
            if(!shouldDrawLod(job,currentPublishedNativeMeshes)){rejectedDraws++;return false;}
            draw.run();
            if(!guard.isCurrent(job.publication)||publishedJobs.get(job.cube())!=job)
                throw new IllegalStateException("Draw callback invalidated its own ownership");
            drawn++;return true;
        }
    }

    private static boolean sameOwnership(EngineCubeSnapshot first, EngineCubeSnapshot second) {
        return first.world().equals(second.world())
                && first.epoch() == second.epoch()
                && first.position().equals(second.position())
                && first.leaseGeneration() == second.leaseGeneration()
                && first.revision() == second.revision();
    }

    private void pruneStalePublications() {
        publishedJobs.values().removeIf(job -> !guard.isCurrent(job.publication));
    }

    /** Off-thread reload requests fence publication immediately, without touching GL resources. */
    public void requestInvalidation() { guard.requestInvalidation(); }

    /** Invalidates all prior resource jobs; a requested reload requires a fresh owner afterwards. */
    public void invalidateResources() {
        checkOwner();
        synchronized (admission) {
            guard.invalidateResources();
            publishedJobs.clear();
        }
    }

    public long resourceGeneration() { return guard.resourceGeneration(); }
    public long rejected() { return guard.rejected(); }
    public long published() { return guard.published(); }
    public long drawn(){checkOwner();return drawn;}
    public long rejectedDraws(){checkOwner();return rejectedDraws;}

    private void checkOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("DH publication requires its render owner thread");
        }
    }

    @Override public void close() {
        checkOwner();
        synchronized (admission) {
            guard.close();
            publishedJobs.clear();
        }
    }
}
