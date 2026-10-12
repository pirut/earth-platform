package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Metadata-only, bounded two-region issuer. Runtime authority is never restored from a digest. */
final class NativeReplacementSetAuthority {
    private final WorldIdentity world;
    private final long epoch;
    private final ReplacementWorkspaces workspaces;
    private final String store, index, palette;
    private final RollingCubeActor.ReplacementPolicy policy;
    private final ReplacementRevisionLedger indexedLedger;
    private final Map<CubePos, Set<CubePos>> workspacesByDependency = new HashMap<>();
    private final Map<CubePos, RollingCubeActor.CubeVersion> observed = new HashMap<>();
    private final Map<CubePos, ReplacementSetCertificate.Region> captures = new HashMap<>();
    private final Set<CubePos> publishedRegions = new HashSet<>();
    private final Set<CubePos> uncertain = new HashSet<>();
    private ReplacementSetCertificate issued, committed, editBase;
    private CubePos editingPosition;
    private boolean bootstrapOpen = true;
    private boolean bootstrapSealed;
    private long nextGeneration = (java.util.UUID.randomUUID().getMostSignificantBits() & 0x3fffffffffffffffL) + 1;

    NativeReplacementSetAuthority(WorldIdentity world, long epoch, ReplacementWorkspaces workspaces,
            String store, String index, String palette, RollingCubeActor.ReplacementPolicy policy) {
        this(world, epoch, workspaces, store, index, palette, policy, null);
    }

    NativeReplacementSetAuthority(WorldIdentity world, long epoch, ReplacementWorkspaces workspaces,
            String store, String index, String palette, RollingCubeActor.ReplacementPolicy policy,
            ReplacementRevisionLedger indexedLedger) {
        this.indexedLedger = indexedLedger;
        if (indexedLedger != null && (!indexedLedger.indexed()
                || !store.equals(indexedLedger.identity().storeIdentity()) || !index.equals(indexedLedger.identity().indexIdentity())))
            throw new IllegalArgumentException("Exact indexed authority store required");
        this.world = Objects.requireNonNull(world); this.epoch = epoch; this.workspaces = Objects.requireNonNull(workspaces);
        this.store = Objects.requireNonNull(store); this.index = Objects.requireNonNull(index);
        this.palette = Objects.requireNonNull(palette); this.policy = Objects.requireNonNull(policy);
        for (CubePos workspace : workspaces.positions()) for (CubePos dependency : workspaces.dependencies(workspace))
            workspacesByDependency.computeIfAbsent(dependency, ignored -> new HashSet<>()).add(workspace);
    }

    /** Only READY snapshots loaded through the durable store barrier may be observed. */
    synchronized void observed(RollingCubeActor.CubeVersion version) {
        Set<CubePos> affected = workspacesByDependency.get(version.position());
        if (affected == null) return;
        boolean changed = affected.stream().map(captures::get).filter(Objects::nonNull)
                .anyMatch(capture -> revision(capture, version.position()) != version.revision());
        if (changed) {
            affected.forEach(this::invalidate);
            throw new IllegalStateException("Replacement region dependency revision changed before native reacquisition");
        }
        var previous = observed.put(version.position(), version);
        if (issued != null && affected.stream().anyMatch(workspace -> !publishedRegions.contains(workspace))
                && !version.equals(previous)) revoke();
    }

    /** Pure preflight must succeed before this call; every admitted write revokes affected authority. */
    synchronized void beforeEdit(CubePos position) {
        Set<CubePos> affected = workspacesByDependency.get(position);
        if (affected == null) return;
        editBase = committed; editingPosition = position;
        affected.forEach(this::invalidate); uncertain.add(position);
    }

    /** Called only after both the cube save and the two-region journal confirmation are durable. */
    synchronized void durableEdit(RollingCubeActor.CubeVersion version) {
        if (!workspacesByDependency.containsKey(version.position())) return;
        uncertain.remove(version.position()); observed(version);
    }

    /**
     * Actor-owned whole-set durability fence after the exact journal confirmation. Unchanged
     * dependencies retain their previously committed capture leases when no longer resident;
     * those are durable content witnesses only and never grant live residency or edit authority.
     * The sole edited cube advances everywhere, including regions whose read bytes did not change.
     */
    synchronized void completeEdit(RollingCubeActor.CubeVersion changed, List<NativeReplacementCapture> values,
            ReplacementRevisionLedger ledger) throws java.io.IOException {
        if (editBase == null || !changed.position().equals(editingPosition)
                || !uncertain.contains(editingPosition) || uncertain.size() != 1
                || changed.revision() != Math.addExact(editBase.region(editingPosition).revision(), 1)
                || !ledger.identity().storeIdentity().equals(store) || !ledger.identity().indexIdentity().equals(index)
                || !ledger.identity().workspaceIdentity().equals(workspaces.identity()))
            throw new IllegalStateException("Exact pending durable replacement transaction required");
        NativeReplacementCapture.verifySharedValues(values);
        var rebuilt = new java.util.ArrayList<ReplacementSetCertificate.Region>(ReplacementWorkspaces.COUNT);
        for (int i = 0; i < workspaces.positions().size(); i++) {
            CubePos workspace = workspaces.positions().get(i);
            NativeReplacementCapture value = values.get(i);
            if (!workspace.equals(value.position())) throw new IllegalArgumentException("Capture workspace order mismatch");
            var dependencies = editBase.region(workspace).dependencies().stream()
                    .map(version -> version.position().equals(changed.position()) ? changed : version).toList();
            for (var version : dependencies) ledger.verifyObserved(version.position(), version.revision());
            rebuilt.add(new ReplacementSetCertificate.Region(workspace, dependencies, value.contentIdentity()));
        }
        // Validate the complete candidate before mutating any authority slot.
        var checked = new ReplacementSetCertificate(world, epoch, policy.resourceGeneration(), store, index,
                policy.providerIdentity(), palette, policy.materialPolicy(), policy.detailPolicy(), nextGeneration, rebuilt,
                indexedLedger == null ? null : requireQuery());
        checked.verifyCaptures(values);
        for (var region : rebuilt) ledger.verifyCapture(region);
        captures.clear(); rebuilt.forEach(region -> captures.put(region.position(), region));
        uncertain.clear(); observed.put(changed.position(), changed);
        publishedRegions.addAll(workspaces.positions()); editBase = null; editingPosition = null;
        revoke();
    }

    synchronized void retire(CubePos position) { observed.remove(position); }

    /** Records a complete immutable capture after exact live bindings and durable ledger checks. */
    synchronized boolean captured(ReplacementSetCertificate.Region region) {
        if (!workspaces.contains(region.position())) throw new IllegalArgumentException("Unconfigured replacement region");
        if (region.dependencies().stream().anyMatch(value -> uncertain.contains(value.position()))) return false;
        for (var version : region.dependencies()) if (!version.equals(observed.get(version.position()))) return false;
        var previous = captures.get(region.position());
        if (previous != null && publishedRegions.contains(region.position())) {
            for (var version : region.dependencies()) if (revision(previous, version.position()) != version.revision()) {
                invalidate(region.position()); throw new IllegalStateException("Published region capture revisions changed");
            }
            if (!previous.captureIdentity().equals(region.captureIdentity())) {
                invalidate(region.position()); throw new IllegalStateException("Same durable region revisions changed voxel content");
            }
            return true; // A fresh capture lease cannot replace published durable content authority.
        }
        if (!region.equals(previous)) { captures.put(region.position(), region); revoke(); }
        return true;
    }

    /**
     * One-shot startup-only durability seal after both serial captures completed under the same
     * exclusive host store lock. The copied dependency leases are historical, never residency.
     * This does not authorize publication; the complete set still needs its fresh paired commit.
     */
    synchronized void sealBootstrap(ReplacementRevisionLedger ledger) throws java.io.IOException {
        if (!bootstrapOpen || bootstrapSealed || issued != null || committed != null)
            throw new IllegalStateException("Serial reconstruction seal is only available before initial set issuance");
        if (!uncertain.isEmpty() || captures.size() != ReplacementWorkspaces.COUNT
                || !ledger.identity().storeIdentity().equals(store) || !ledger.identity().indexIdentity().equals(index)
                || !ledger.identity().workspaceIdentity().equals(workspaces.identity()))
            throw new IllegalStateException("Complete matching durable region reconstruction required");
        for (var region : captures.values()) for (var version : region.dependencies())
            ledger.verifyObserved(version.position(), version.revision());
        for (var region : captures.values()) ledger.verifyCapture(region);
        if (indexedLedger != null) requireQuery();
        publishedRegions.addAll(workspaces.positions()); bootstrapSealed = true;
    }

    synchronized ReplacementSetCertificate prepare() {
        if (!uncertain.isEmpty() || captures.size() != ReplacementWorkspaces.COUNT) return null;
        ReplacementQueryAttestation query = indexedLedger == null ? null : indexedLedger.attestation();
        if (indexedLedger != null && query == null) { revoke(); return null; }
        if (committed != null) {
            if (Objects.equals(committed.queryAttestation(), query)) return committed;
            revoke(); return null;
        }
        for (var entry : captures.entrySet()) if (!publishedRegions.contains(entry.getKey()))
            for (var version : entry.getValue().dependencies()) if (!version.equals(observed.get(version.position()))) return null;
        List<ReplacementSetCertificate.Region> regions = workspaces.positions().stream().map(captures::get).toList();
        if (issued != null && issued.regions().equals(regions) && Objects.equals(issued.queryAttestation(), query)) return issued;
        issued = new ReplacementSetCertificate(world, epoch, policy.resourceGeneration(), store, index,
                policy.providerIdentity(), palette, policy.materialPolicy(), policy.detailPolicy(), nextGeneration, regions, query);
        bootstrapOpen = false;
        nextGeneration = Math.incrementExact(nextGeneration);
        return issued;
    }

    synchronized boolean canCommit(String identity) {
        if (identity == null || issued == null || !identity.equals(issued.identity()) || !uncertain.isEmpty()) return false;
        if (indexedLedger != null && !indexedLedger.queryCurrent(issued.queryAttestation())) { revoke(); return false; }
        for (var region : issued.regions()) {
            if (!region.equals(captures.get(region.position()))) return false;
            if (!publishedRegions.contains(region.position())) for (var version : region.dependencies())
                if (!version.equals(observed.get(version.position()))) return false;
        }
        return true;
    }

    synchronized boolean commit(String identity) {
        if (!canCommit(identity)) return false;
        committed = issued; publishedRegions.addAll(workspaces.positions()); editBase = null; editingPosition = null; return true;
    }
    synchronized boolean current(String identity) {
        if (committed == null || !committed.identity().equals(identity)) return false;
        if (indexedLedger != null && !indexedLedger.queryCurrent(committed.queryAttestation())) { revoke(); return false; }
        return true;
    }
    private ReplacementQueryAttestation requireQuery() {
        var query = indexedLedger.attestation();
        if (query == null) throw new IllegalStateException("Exact current COMPLETE indexed query required");
        return query;
    }
    synchronized ReplacementSetCertificate committed() { return committed; }
    synchronized ReplacementSetCertificate issued() { return issued; }
    synchronized int retainedRegionCount() { return captures.size(); }
    synchronized int observedDependencyCount() { return observed.size(); }
    synchronized void revoke() { issued = null; committed = null; }
    private void invalidate(CubePos workspace) { captures.remove(workspace); publishedRegions.remove(workspace); revoke(); }
    private static long revision(ReplacementSetCertificate.Region region, CubePos position) {
        return region.dependencies().stream().filter(value -> value.position().equals(position)).findFirst().orElseThrow().revision();
    }
}
