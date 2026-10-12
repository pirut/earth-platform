package io.earthplatform.minecraft.lod;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Explicit analytic capability supplied by the authoritative session descriptor.
 * This is never inferred by sampling, nor accepted from an arbitrary Earth sampler.
 * It certifies a constant opaque base plane outside the explicit owned replacement volumes,
 * with exact signed native Y. The generated world itself contains finite 3D fixture geometry.
 * Construction identifies content; the host must separately verify its authority.
 */
public record DhAnalyticFlatPolicy(String sourceIdentity, WorldIdentity providerIdentity,
                                   long surfaceY, int opaqueArgb) {
    public static final String VERSION = "dh-certified-analytic-flat-v1";
    public DhAnalyticFlatPolicy {
        Objects.requireNonNull(sourceIdentity, "source identity");
        Objects.requireNonNull(providerIdentity, "provider identity");
        if (sourceIdentity.isBlank() || sourceIdentity.length() > 4096)
            throw new IllegalArgumentException("Bounded analytic source identity required");
        if (!providerIdentity.generatorId().equals("earthplatform:analytic-native-cube")
                || !providerIdentity.generatorVersion().equals("analytic-native-cube-v1/rolling-interior-v1")
                || providerIdentity.seed() != 0)
            throw new IllegalArgumentException("Certified analytic native provider required");
        if (surfaceY != 128 && surfaceY != -11000 && surfaceY != 8849)
            throw new IllegalArgumentException("Two-region proof permits only base, abyss, and summit analytic planes");
        if (!sourceIdentity.equals(providerIdentity.datasetVersion()) || !sourceIdentity.equals(expectedDataset(surfaceY)))
            throw new IllegalArgumentException("Analytic source must match the exact bounded-collision fixture dataset");
        if (opaqueArgb != 0xff808080) throw new IllegalArgumentException("The certified gate plane is exactly opaque stone FF808080");
    }

    /**
     * Exact Config.boundedCollision fingerprint for the pinned native registry's air=0,
     * stone=1. These IDs and all three fingerprints are independently matched to saved
     * live-run WorldIdentity reports. No Minecraft registry initialization is required.
     */
    public static String expectedDataset(long height) {
        if (height != 128 && height != -11000 && height != 8849)
            throw new IllegalArgumentException("Unsupported analytic gate height");
        String config = "analytic-fixtures-v1\nfixture=BOUNDED_COLLISION\nanchorY=" + height
                + "\nriseX=0\nriseZ=0\nrun=1\nairState=0\nsolidState=1\n";
        try {
            return "analytic-fixtures-v1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(config.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    /** Canonical length-prefixed identity binds every plane and capability input. */
    public String identity() {
        String canonical = VERSION + "\n" + field(sourceIdentity) + providerIdentity.seed() + "\n"
                + field(providerIdentity.generatorId()) + field(providerIdentity.generatorVersion())
                + field(providerIdentity.datasetVersion()) + field(providerIdentity.blockPaletteVersion())
                + surfaceY + "\n" + Integer.toUnsignedString(opaqueArgb) + "\n"
                + "complete-footprint=true;base-plane-outside-explicit-owned-volumes=true;constant-height=true;constant-opaque-color=true;native-y=[-11008,8864)\n";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String field(String value) { return value.length() + ":" + value + "\n"; }

    /** No source object or provider callback is retained or invoked. */
    SceneSurfaceSampler constantSampler() {
        String identity = identity();
        long height = surfaceY;
        int color = opaqueArgb;
        return new SceneSurfaceSampler() {
            public String identity() { return identity; }
            public long surfaceY(long x, long z) { return height; }
            public int argb(long x, long z, long y) { return color; }
            public boolean supportsConcurrentReads() { return true; }
            public boolean supportsFootprintCoverage() { return true; }
            public boolean knownFootprint(long minX, long minZ, long maxX, long maxZ) {
                if (minX >= maxX || minZ >= maxZ) throw new IllegalArgumentException("Nonempty analytic footprint required");
                return true;
            }
        };
    }
}
