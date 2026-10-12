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

/**
 * Server-issued provenance of one exact durable 3-D replacement. The dependency leases describe
 * capture, not perpetual residency. Only the issuing authority can authorize its publication;
 * construction or decoding of an equal value is not an authorization token. No snapshots are held.
 */
public record ReplacementCertificate(WorldIdentity world, long epoch, long resourceGeneration,
        CubePos originalCube, String storeIdentity, String providerIdentity, String paletteIdentity,
        String materialPolicy, String detailPolicy, long generation,
        List<RollingCubeActor.CubeVersion> dependencies) {
    public static final int DEPENDENCIES = 27;
    public ReplacementCertificate {
        Objects.requireNonNull(world); Objects.requireNonNull(originalCube);
        if (epoch < 0 || resourceGeneration <= 0 || generation <= 0)
            throw new IllegalArgumentException("Invalid replacement authority generation");
        bounded(storeIdentity); bounded(providerIdentity); bounded(materialPolicy); bounded(detailPolicy);
        if (paletteIdentity == null || !paletteIdentity.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact palette fingerprint required");
        RollingCubePlan.stationary(originalCube, originalCube);
        dependencies = List.copyOf(Objects.requireNonNull(dependencies));
        List<CubePos> expected = CubeTickets.box(originalCube, 1, 1, DEPENDENCIES).stream().sorted().toList();
        if (dependencies.size() != DEPENDENCIES
                || !dependencies.stream().map(RollingCubeActor.CubeVersion::position).toList().equals(expected))
            throw new IllegalArgumentException("Complete canonical 27-cube halo required");
        if (dependencies.stream().map(RollingCubeActor.CubeVersion::leaseGeneration).distinct().count() != DEPENDENCIES)
            throw new IllegalArgumentException("Distinct capture leases required");
        if (dependencies.stream().filter(v -> v.position().equals(originalCube)).findFirst().orElseThrow().revision() <= 0
                && !(EarthReplacementAdmission.MATERIAL_POLICY.equals(materialPolicy) && EarthReplacementAdmission.DETAIL_POLICY.equals(detailPolicy)))
            throw new IllegalArgumentException("An acknowledged durable original-cube edit is required");
    }
    static void bounded(String value) {
        if (value == null || value.isBlank() || value.length() > 128)
            throw new IllegalArgumentException("Bounded nonempty replacement binding required");
    }
    /** Canonical length-framed identity binds every component, every revision, and capture lease. */
    public String identity() { return digest(true); }
    /** Durable dependency identity excludes publication generation and capture leases. */
    public String dependencyIdentity() { return digest(false); }
    private String digest(boolean capture) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF(capture ? "earth-replacement-certificate-v1" : "earth-replacement-dependencies-v1");
                out.writeLong(world.seed()); out.writeUTF(world.generatorId()); out.writeUTF(world.generatorVersion());
                out.writeUTF(world.datasetVersion()); out.writeUTF(world.blockPaletteVersion());
                out.writeLong(epoch); out.writeLong(resourceGeneration); writePosition(out, originalCube);
                out.writeUTF(storeIdentity); out.writeUTF(providerIdentity); out.writeUTF(paletteIdentity);
                out.writeUTF(materialPolicy); out.writeUTF(detailPolicy); if (capture) out.writeLong(generation);
                out.writeInt(dependencies.size());
                for (var value : dependencies) {
                    writePosition(out, value.position()); if (capture) out.writeLong(value.leaseGeneration()); out.writeLong(value.revision());
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void writePosition(DataOutputStream out, CubePos p) throws IOException {
        out.writeInt(p.x()); out.writeInt(p.y()); out.writeInt(p.z());
    }
    public long originalRevision() {
        return dependencies.stream().filter(v -> v.position().equals(originalCube)).findFirst().orElseThrow().revision();
    }
}
