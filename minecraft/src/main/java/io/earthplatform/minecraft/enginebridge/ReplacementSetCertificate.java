package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.core.WorldIdentity;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Complete canonical two-region authority value. Only its issuing actor can authorize publication. */
public record ReplacementSetCertificate(WorldIdentity world, long epoch, long resourceGeneration,
        String storeIdentity, String indexIdentity, String providerIdentity, String paletteIdentity,
        String materialPolicy, String detailPolicy, long generation, List<Region> regions,
        ReplacementQueryAttestation queryAttestation) {

    /** Legacy constructor preserves the exact v1 certificate identity bytes. */
    public ReplacementSetCertificate(WorldIdentity world, long epoch, long resourceGeneration,
            String storeIdentity, String indexIdentity, String providerIdentity, String paletteIdentity,
            String materialPolicy, String detailPolicy, long generation, List<Region> regions) {
        this(world, epoch, resourceGeneration, storeIdentity, indexIdentity, providerIdentity, paletteIdentity,
                materialPolicy, detailPolicy, generation, regions, null);
    }

    /** Includes clean revision-zero regions explicitly; absence must never be interpreted as clean. */
    public record Region(CubePos position, List<RollingCubeActor.CubeVersion> dependencies, String captureIdentity) {
        public Region {
            Objects.requireNonNull(position, "position");
            RollingCubePlan.validateWindowCenter(position);
            Objects.requireNonNull(dependencies, "dependencies");
            if (dependencies.size() != ReplacementCertificate.DEPENDENCIES)
                throw new IllegalArgumentException("Exactly27 replacement dependencies required");
            dependencies = List.copyOf(dependencies);
            List<CubePos> expected = CubeTickets.box(position, 1, 1, ReplacementCertificate.DEPENDENCIES).stream().sorted().toList();
            if (!dependencies.stream().map(RollingCubeActor.CubeVersion::position).toList().equals(expected))
                throw new IllegalArgumentException("Complete canonical27 dependency vector required");
            if (dependencies.stream().map(RollingCubeActor.CubeVersion::leaseGeneration).distinct().count() != expected.size())
                throw new IllegalArgumentException("Distinct capture leases required");
            digest(captureIdentity);
        }
        public long revision() {
            return dependencies.stream().filter(value -> value.position().equals(position)).findFirst().orElseThrow().revision();
        }
        public boolean edited() { return revision() > 0; }
        public void verifyCapture(NativeReplacementCapture capture) {
            if (capture == null || !position.equals(capture.position()) || !captureIdentity.equals(capture.contentIdentity()))
                throw new IllegalArgumentException("Replacement capture does not match the certified region");
        }
    }

    public ReplacementSetCertificate {
        Objects.requireNonNull(world, "world");
        ReplacementCertificate.bounded(world.generatorId()); ReplacementCertificate.bounded(world.generatorVersion());
        ReplacementCertificate.bounded(world.datasetVersion()); ReplacementCertificate.bounded(world.blockPaletteVersion());
        if (epoch < 0 || resourceGeneration <= 0 || generation <= 0)
            throw new IllegalArgumentException("Invalid replacement-set authority generation");
        try { if (!UUID.fromString(storeIdentity).toString().equals(storeIdentity)) throw new IllegalArgumentException(); }
        catch (RuntimeException malformed) { throw new IllegalArgumentException("Canonical replacement store UUID required", malformed); }
        digest(indexIdentity); digest(paletteIdentity);
        ReplacementCertificate.bounded(providerIdentity); ReplacementCertificate.bounded(materialPolicy);
        ReplacementCertificate.bounded(detailPolicy);
        Objects.requireNonNull(regions, "regions");
        if (regions.size() != ReplacementWorkspaces.COUNT) throw new IllegalArgumentException("Exactly two replacement regions required");
        regions = List.copyOf(regions);
        ReplacementWorkspaces.canonical(regions.stream().map(Region::position).toList());
        if (queryAttestation != null) {
            if (!storeIdentity.equals(queryAttestation.storeIdentity()) || !indexIdentity.equals(queryAttestation.indexIdentity()))
                throw new IllegalArgumentException("Query and replacement store/index identities differ");
            queryAttestation.verifyRegions(regions);
        }
        // Shared positions may carry historical serial-bootstrap leases, but durable values must agree.
        var versions = new java.util.HashMap<CubePos, Long>();
        var leases = new java.util.HashMap<Long, CubePos>();
        for (var region : regions) for (var version : region.dependencies()) {
            Long previous = versions.putIfAbsent(version.position(), version.revision());
            if (previous != null && previous.longValue() != version.revision())
                throw new IllegalArgumentException("Shared dependency durable revisions disagree");
            CubePos owner = leases.putIfAbsent(version.leaseGeneration(), version.position());
            if (owner != null && !owner.equals(version.position()))
                throw new IllegalArgumentException("A capture lease cannot identify different dependency positions");
        }
    }

    public ReplacementWorkspaces workspaces() {
        return ReplacementWorkspaces.canonical(regions.stream().map(Region::position).toList());
    }
    public Region region(CubePos position) {
        return regions.stream().filter(value -> value.position().equals(position)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown replacement region"));
    }
    public void verifyCaptures(List<NativeReplacementCapture> captures) {
        if (captures == null || captures.size() != ReplacementWorkspaces.COUNT)
            throw new IllegalArgumentException("Complete replacement capture set required");
        for (int i = 0; i < regions.size(); i++) regions.get(i).verifyCapture(captures.get(i));
        NativeReplacementCapture.verifySharedValues(captures);
    }
    public List<Region> editedRegions() { return regions.stream().filter(Region::edited).toList(); }
    /** Adapter for existing exact per-region geometry; clean regions remain explicit in this set. */
    public ReplacementCertificate asCertificate(CubePos position) {
        Region region = region(position);
        if (!region.edited()) throw new IllegalArgumentException("Clean region has no edited-volume replacement certificate");
        return new ReplacementCertificate(world, epoch, resourceGeneration, position, storeIdentity,
                providerIdentity, paletteIdentity, materialPolicy, detailPolicy, generation, region.dependencies());
    }
    public String identity() { return identity(true); }
    /** Stable durable content excludes restart epoch, resources, publication generation and capture leases. */
    public String durableIdentity() { return identity(false); }
    public String dependencyIdentity() { return durableIdentity(); }
    private String identity(boolean publication) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF(publication ? "earth-replacement-set-publication-v1" : "earth-replacement-set-durable-v1");
                out.writeLong(world.seed()); out.writeUTF(world.generatorId()); out.writeUTF(world.generatorVersion());
                out.writeUTF(world.datasetVersion()); out.writeUTF(world.blockPaletteVersion());
                if (publication) { out.writeLong(epoch); out.writeLong(resourceGeneration); out.writeLong(generation); }
                out.writeUTF(storeIdentity); out.writeUTF(indexIdentity); out.writeUTF(providerIdentity); out.writeUTF(paletteIdentity);
                out.writeUTF(materialPolicy); out.writeUTF(detailPolicy); out.writeInt(regions.size());
                for (Region region : regions) {
                    writePosition(out, region.position()); out.writeUTF(region.captureIdentity());
                    out.writeInt(region.dependencies().size());
                    for (var version : region.dependencies()) {
                        writePosition(out, version.position()); out.writeLong(version.revision());
                        if (publication) out.writeLong(version.leaseGeneration());
                    }
                }
                // No discriminator is written for legacy certificates: their bytes remain unchanged.
                if (queryAttestation != null) queryAttestation.write(out);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Exact SHA-256 identity required");
    }
    private static void writePosition(DataOutputStream out, CubePos position) throws IOException {
        out.writeInt(position.x()); out.writeInt(position.y()); out.writeInt(position.z());
    }
}
