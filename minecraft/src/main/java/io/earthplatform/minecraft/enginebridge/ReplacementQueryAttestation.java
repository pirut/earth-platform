package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.lod.DhAnalyticFlatPolicy;
import io.earthplatform.minecraft.lod.DhLodLayout;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable COMPLETE-query wire value. Constructing or decoding this value grants no authority:
 * the issuing actor must retain the exact live index completion and the host must mirror it.
 * The conservative complete catalogue covers the full signed cube domain, without native pins.
 */
public record ReplacementQueryAttestation(String storeIdentity, String indexIdentity,
        long snapshotGeneration, String snapshotRootIdentity,
        long minX, long minY, long minZ, long maxX, long maxY, long maxZ,
        String layoutIdentity, String policyIdentity, String resultDigest, int resultCount) {
    public static final String VERSION = "earth-paged-replacement-complete-query-v1";
    public static final long DOMAIN_MIN = (long) Integer.MIN_VALUE * 16;
    public static final long DOMAIN_MAX = ((long) Integer.MAX_VALUE + 1) * 16;

    public ReplacementQueryAttestation {
        try { if (!UUID.fromString(storeIdentity).toString().equals(storeIdentity)) throw new IllegalArgumentException(); }
        catch (RuntimeException malformed) { throw new IllegalArgumentException("Canonical query store UUID required", malformed); }
        digest(indexIdentity); digest(snapshotRootIdentity); digest(policyIdentity); digest(resultDigest);
        if (snapshotGeneration <= 0) throw new IllegalArgumentException("Positive committed index generation required");
        if (minX < DOMAIN_MIN || minY < DOMAIN_MIN || minZ < DOMAIN_MIN
                || maxX > DOMAIN_MAX || maxY > DOMAIN_MAX || maxZ > DOMAIN_MAX
                || minX >= maxX || minY >= maxY || minZ >= maxZ)
            throw new IllegalArgumentException("Exact nonempty cube-domain query extent required");
        if (layoutIdentity == null || layoutIdentity.isBlank() || layoutIdentity.length() > 1024)
            throw new IllegalArgumentException("Bounded exact far-layout identity required");
        if (resultCount != ReplacementWorkspaces.COUNT)
            throw new IllegalArgumentException("Exactly two COMPLETE indexed results required");
    }

    public boolean complete() { return true; }
    public boolean covers(long x0, long y0, long z0, long x1, long y1, long z1) {
        return x0 < x1 && y0 < y1 && z0 < z1 && minX <= x0 && minY <= y0 && minZ <= z0
                && maxX >= x1 && maxY >= y1 && maxZ >= z1;
    }
    public boolean completeCatalogue() {
        return minX == DOMAIN_MIN && minY == DOMAIN_MIN && minZ == DOMAIN_MIN
                && maxX == DOMAIN_MAX && maxY == DOMAIN_MAX && maxZ == DOMAIN_MAX;
    }
    public void verifyRegions(List<ReplacementSetCertificate.Region> regions) {
        Objects.requireNonNull(regions);
        if (regions.size() != resultCount) throw new IllegalArgumentException("Incomplete indexed replacement result");
        var entries = regions.stream().map(region -> new PagedReplacementIndex.Entry(region.position(),
                region.revision(), policyIdentity, region.captureIdentity())).toList();
        if (!resultDigest.equals(PagedReplacementIndex.resultIdentity(entries)))
            throw new IllegalArgumentException("Query result differs from exact replacement summaries");
        for (var region : regions) {
            var p = region.position();
            if (!(p.minBlockX() < maxX && p.minBlockX() + 16 > minX
                    && p.minBlockY() < maxY && p.minBlockY() + 16 > minY
                    && p.minBlockZ() < maxZ && p.minBlockZ() + 16 > minZ))
                throw new IllegalArgumentException("Replacement lies outside its exact query extent");
        }
    }
    public String identity() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) { write(out); }
            return hash(bytes.toByteArray());
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }
    /** Canonical public encoding, only appended when the opt-in certificate field is present. */
    public void write(DataOutputStream out) throws IOException {
        out.writeUTF(VERSION); out.writeUTF(storeIdentity); out.writeUTF(indexIdentity);
        out.writeLong(snapshotGeneration); out.writeUTF(snapshotRootIdentity);
        out.writeLong(minX); out.writeLong(minY); out.writeLong(minZ);
        out.writeLong(maxX); out.writeLong(maxY); out.writeLong(maxZ);
        out.writeUTF(layoutIdentity); out.writeUTF(policyIdentity); out.writeUTF(resultDigest); out.writeInt(resultCount);
    }
    /** Stable scope: restart epochs, leases, publication IDs and resource generations are excluded. */
    public static String policyIdentity(WorldIdentity world, String paletteIdentity,
            RollingCubeActor.ReplacementPolicy policy, DhAnalyticFlatPolicy flatPolicy, ReplacementWorkspaces workspaces) {
        Objects.requireNonNull(world); Objects.requireNonNull(policy); Objects.requireNonNull(flatPolicy); Objects.requireNonNull(workspaces);
        digest(paletteIdentity);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF("earth-indexed-adjacent-replacement-policy-v1");
                out.writeLong(world.seed()); out.writeUTF(world.generatorId()); out.writeUTF(world.generatorVersion());
                out.writeUTF(world.datasetVersion()); out.writeUTF(world.blockPaletteVersion());
                out.writeUTF(policy.providerIdentity()); out.writeUTF(paletteIdentity); out.writeUTF(policy.materialPolicy());
                out.writeUTF(policy.detailPolicy()); out.writeUTF(policy.editPolicy().name()); out.writeUTF(flatPolicy.identity());
                out.writeUTF(workspaces.identity()); out.writeUTF(DhLodLayout.LONG_4096.identity());
            }
            return hash(bytes.toByteArray());
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }
    private static void digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Exact SHA-256 identity required");
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
