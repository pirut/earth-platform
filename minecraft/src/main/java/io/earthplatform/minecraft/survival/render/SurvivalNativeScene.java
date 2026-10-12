package io.earthplatform.minecraft.survival.render;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.minecraft.scene.SceneCube;
import io.earthplatform.minecraft.scene.SceneMesh;
import io.earthplatform.minecraft.scene.SceneNativeShaderContract;
import io.earthplatform.minecraft.scene.SceneNeighborhood;
import io.earthplatform.minecraft.scene.SceneRenderEnvelope;
import io.earthplatform.minecraft.survival.world.CubeSnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalPalette;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One client/render-owner page admission lifetime. Only immutable server exports cross this boundary.
 * Every instance has a fresh private admission gate and publication guard. A process-unique positive
 * numeric transport epoch maps one-to-one to this lifetime and its full ownerEpoch/exportStream UUIDs.
 * UUIDs are never truncated, hashed or accepted through EngineCubeSnapshot metadata alone. Recreating
 * a scene never accepts queued raw engine snapshots from an earlier scene, even with identical leases.
 *
 * SceneMesh.Build remains the correctness compiler: actual native baked models/renderBatched and
 * separate solid/cutout_mipped/cutout passes. This owns no GPU buffers and confers no edit authority.
 * The caller owns and must close all builds/meshes, including failed cleanup owners. No historical
 * frame lease or selected-distance coverage is granted here. The final selected/effective distance
 * still needs complete native pages plus its exact DH complement, with a separate publication gate.
 */
public final class SurvivalNativeScene implements AutoCloseable {
    private static final AtomicLong TRANSPORT_LIFETIMES = new AtomicLong();
    public record Installed(SurvivalVisualSnapshot source, Map<CubePos, SceneCube> cubes,
            SceneNeighborhood neighborhood) {
        public Installed { cubes = Map.copyOf(cubes); }
        public SceneCube member(CubePos position) {
            if (!source.members().contains(position)) throw new SurvivalRefusal("Not an exported native mesh member");
            return cubes.get(position);
        }
    }
    private final Thread owner = Thread.currentThread();
    private final long transportEpoch;
    private final WorldIdentity world;
    private final UUID ownerEpoch, exportStream;
    private final SurvivalNativeStateAdapter adapter;
    private final CubeStreamAdmission admission;
    private final RenderPublicationGuard guard;
    private final boolean directionalShadingDisabled;
    private final Map<CubePos, CubeStreamAdmission.Lease> leases = new TreeMap<>();
    private Installed current;
    private boolean closed;

    public SurvivalNativeScene(SurvivalVisualSnapshot first, SurvivalPalette palette) {
        this(first, palette, false);
    }
    public SurvivalNativeScene(SurvivalVisualSnapshot first, SurvivalPalette palette, boolean directionalShadingDisabled) {
        Objects.requireNonNull(first, "first");
        world = first.world(); ownerEpoch = first.ownerEpoch(); exportStream = first.exportStream();
        transportEpoch = TRANSPORT_LIFETIMES.updateAndGet(value -> Math.incrementExact(value));
        this.directionalShadingDisabled = directionalShadingDisabled;
        adapter = new SurvivalNativeStateAdapter(world, palette);
        admission = new CubeStreamAdmission(world, transportEpoch, SurvivalVisualSnapshot.MAX_DEPENDENCIES);
        guard = new RenderPublicationGuard(admission, SurvivalVisualSnapshot.MAX_DEPENDENCIES);
        install(first);
    }
    public WorldIdentity world() { return world; }
    public UUID ownerEpoch() { return ownerEpoch; }
    public UUID exportStream() { return exportStream; }
    public Installed current() { checkOpen(); return current; }
    public RenderPublicationGuard guard() { checkOpen(); return guard; }

    /** Prevalidation failures preserve the old scene. A failure after admission changes closes this lifetime. */
    public Installed install(SurvivalVisualSnapshot next) {
        checkOpen(); Objects.requireNonNull(next, "next");
        if (!world.equals(next.world()) || !ownerEpoch.equals(next.ownerEpoch()) || !exportStream.equals(next.exportStream()))
            throw new SurvivalRefusal("Foreign survival world, owner epoch or export stream");
        if (current != null && (next.exportSequence() <= current.source.exportSequence()
                || next.commitSequence() < current.source.commitSequence()))
            throw new SurvivalRefusal("Replayed or out-of-order survival visual export");
        if (!SurvivalVisualSnapshot.dependencies(next.members()).equals(next.cubes().keySet()))
            throw new SurvivalRefusal("Visual export does not contain its complete exact dependency union");
        for (var entry : next.cubes().entrySet()) {
            CubeSnapshot cube = entry.getValue();
            if (!entry.getKey().equals(cube.position()) || !world.equals(cube.world()) || !ownerEpoch.equals(cube.ownerEpoch()))
                throw new SurvivalRefusal("Visual cube provenance does not match its export");
            adapter.validate(cube);
            CubeSnapshot previous = current == null ? null : current.source.cubes().get(entry.getKey());
            if (previous != null && (cube.incarnation() < previous.incarnation()
                    || cube.incarnation() == previous.incarnation() && cube.simulationRevision() < previous.simulationRevision()))
                throw new SurvivalRefusal("Visual cube stamp went backwards");
            if (previous != null && previous.stamp().equals(cube.stamp()) && !previous.blocks().equals(cube.blocks()))
                throw new SurvivalRefusal("Visual cube changed without a simulation revision");
        }
        try {
            // A new incarnation cannot inherit the old stream lease even if its revision repeats.
            var retained = new TreeSet<CubePos>();
            for (CubePos position : next.cubes().keySet()) {
                CubeSnapshot previous = current == null ? null : current.source.cubes().get(position);
                if (previous != null && previous.incarnation() == next.cubes().get(position).incarnation()) retained.add(position);
            }
            replaceInterest(retained);
            replaceInterest(next.cubes().keySet());
            var cubes = new TreeMap<CubePos, SceneCube>();
            for (var entry : next.cubes().entrySet()) {
                CubePos position = entry.getKey(); CubeSnapshot source = entry.getValue();
                CubeSnapshot previous = current == null ? null : current.source.cubes().get(position);
                if (previous != null && previous.stamp().equals(source.stamp())) {
                    cubes.put(position, current.cubes.get(position));
                    continue;
                }
                SceneCube cube = adapter.materialize(source, transportEpoch, leases.get(position).generation());
                var engine = cube.snapshot();
                var reservation = admission.admit(new CubeStreamAdmission.Header(world, transportEpoch, position,
                        engine.leaseGeneration(), engine.revision()));
                if (!reservation.accepted()) throw new SurvivalRefusal("Native visual admission refused: " + reservation.rejection());
                var applied = admission.commit(reservation.token(), () -> cubes.put(position, cube));
                if (applied != CubeStreamAdmission.Rejection.NONE)
                    throw new SurvivalRefusal("Native visual admission changed before installation: " + applied);
            }
            // Explicit outdoor diagnostic illumination and cosmetic tint, NOT propagated cubic lighting.
            var neighborhood = new SceneNeighborhood(cubes, SceneRenderEnvelope.NATIVE,
                    SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE, directionalShadingDisabled);
            current = new Installed(next, cubes, neighborhood);
            return current;
        } catch (RuntimeException | Error failure) {
            try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private void replaceInterest(java.util.Collection<CubePos> positions) {
        var changed = admission.replaceInterest(positions);
        for (var retired : changed.retired()) leases.remove(retired.lease().position());
        for (var requested : changed.requested()) leases.put(requested.position(), requested);
    }
    /** Conservative complete halo certificate for tests and page orchestration; mesh records actual reads itself. */
    public List<RenderPublicationGuard.Job> dependencyJobs(CubePos member) {
        checkOpen(); current.member(member);
        var jobs = new ArrayList<RenderPublicationGuard.Job>(27);
        for (CubePos position : SurvivalVisualSnapshot.dependencies(java.util.Set.of(member)))
            jobs.add(guard.prepare(current.cubes.get(position).snapshot()));
        return List.copyOf(jobs);
    }
    public SceneMesh.Build begin(CubePos member) {
        checkOpen(); return SceneMesh.begin(guard, current.member(member), current.neighborhood);
    }
    public SceneMesh.Build begin(CubePos member, SceneNativeShaderContract.Capture shader) {
        checkOpen(); return SceneMesh.begin(guard, current.member(member), current.neighborhood, shader);
    }
    /** Thread-safe immediate refusal before a resource reload touches native resources. Recreate scene afterward. */
    public void requestResourceInvalidation() { guard.requestInvalidation(); }
    /** Render-owner generation change; existing jobs can never become current again. */
    public void invalidateResources() { checkOpen(); guard.invalidateResources(); }
    @Override public void close() {
        checkOwner(); if (closed) return;
        closed = true; guard.close(); admission.close(); leases.clear(); current = null;
    }
    private void checkOpen() { checkOwner(); if (closed) throw new SurvivalRefusal("Native survival scene is closed"); }
    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new SurvivalRefusal("Native survival scene requires its client owner");
    }
}
