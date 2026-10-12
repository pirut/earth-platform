package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.core.WorldIdentity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Synchronized bounded authority. Durable dependencies survive intentional capture-lease retirement. */
final class NativeReplacementAuthority {
    private final WorldIdentity world;
    private final long epoch;
    private final CubePos original;
    private final String store, palette;
    private final RollingCubeActor.ReplacementPolicy policy;
    private final Set<CubePos> halo;
    private final Map<CubePos, RollingCubeActor.CubeVersion> observed = new HashMap<>();
    private final Map<CubePos, Long> uncertain = new HashMap<>();
    private ReplacementCertificate issued, committed;
    private long nextGeneration = (java.util.UUID.randomUUID().getMostSignificantBits() & 0x3fffffffffffffffL) + 1;
    NativeReplacementAuthority(WorldIdentity world, long epoch, CubePos original, String store,
                               String palette, RollingCubeActor.ReplacementPolicy policy) {
        this.world = world; this.epoch = epoch; this.original = original; this.store = store;
        this.palette = palette; this.policy = policy;
        halo = Set.copyOf(CubeTickets.box(original, 1, 1, 27));
    }
    /** Called only for host READY snapshots, whose load path reestablishes the durable barrier. */
    synchronized void observed(RollingCubeActor.CubeVersion version) {
        if (!halo.contains(version.position())) return;
        if (committed != null && revision(committed, version.position()) != version.revision()) {
            revoke();
            throw new IllegalStateException("A replacement dependency changed before native reacquisition");
        }
        var before = observed.put(version.position(), version);
        if (issued != null && committed == null && (before == null || !before.equals(version))) issued = null;
        Long poisonedLease = uncertain.get(version.position());
        if (poisonedLease != null && poisonedLease != version.leaseGeneration()) uncertain.remove(version.position());
    }
    /** Revocation happens before admitting any dependent write, including writes which later fail. */
    synchronized void beforeEdit(CubePos position, long lease) {
        if (!halo.contains(position)) return;
        revoke(); uncertain.put(position, lease);
    }
    synchronized void durableEdit(RollingCubeActor.CubeVersion version) {
        if (!halo.contains(version.position())) return;
        uncertain.remove(version.position()); observed(version);
    }
    synchronized void retire(CubePos position) { observed.remove(position); }
    /** Independent owner-observed stamp; caller-provided snapshot decoration cannot prove a lease. */
    synchronized boolean observes(RollingCubeActor.CubeVersion version) { return version.equals(observed.get(version.position())) && !uncertain.containsKey(version.position()); }
    synchronized ReplacementCertificate prepare(List<RollingCubeActor.CubeVersion> manifest) {
        if (committed != null) {
            for (var value : manifest) if (halo.contains(value.position())
                    && revision(committed, value.position()) != value.revision()) {
                revoke(); throw new IllegalStateException("Replacement manifest dependency revision changed");
            }
            return committed;
        }
        if (!uncertain.isEmpty()) return null;
        Map<CubePos, RollingCubeActor.CubeVersion> values = new HashMap<>();
        for (var value : manifest) if (halo.contains(value.position())) {
            if (values.put(value.position(), value) != null) throw new IllegalArgumentException("Duplicate dependency");
        }
        if (!values.keySet().equals(halo) || values.get(original).revision() == 0
                && !(EarthReplacementAdmission.MATERIAL_POLICY.equals(policy.materialPolicy()) && EarthReplacementAdmission.DETAIL_POLICY.equals(policy.detailPolicy()))) return null;
        List<RollingCubeActor.CubeVersion> vector = halo.stream().sorted().map(values::get).toList();
        for (var value : vector) if (!value.equals(observed.get(value.position()))) return null;
        if (issued != null && issued.dependencies().equals(vector)) return issued;
        issued = new ReplacementCertificate(world, epoch, policy.resourceGeneration(), original,
                store, policy.providerIdentity(), palette, policy.materialPolicy(), policy.detailPolicy(),
                nextGeneration, vector);
        nextGeneration = Math.incrementExact(nextGeneration);
        return issued;
    }
    synchronized boolean canCommit(String identity) {
        if (identity == null || issued == null || !issued.identity().equals(identity) || !uncertain.isEmpty()) return false;
        if (committed == issued) return true;
        for (var dependency : issued.dependencies())
            if (!dependency.equals(observed.get(dependency.position()))) return false;
        return true;
    }
    synchronized boolean commit(String identity) {
        if (!canCommit(identity)) return false;
        committed = issued; return true;
    }
    synchronized ReplacementCertificate committed() { return committed; }
    synchronized ReplacementCertificate issued() { return issued; }
    synchronized boolean current(String identity) { return committed != null && committed.identity().equals(identity); }
    synchronized void revoke() { issued = null; committed = null; }
    private static long revision(ReplacementCertificate certificate, CubePos position) {
        return certificate.dependencies().stream().filter(v -> v.position().equals(position)).findFirst().orElseThrow().revision();
    }
}
