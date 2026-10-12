package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageReady;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.lod.DhLodLayout;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Pure, common-side far-window policy. Only an authoritative native candidate selects a
 * center; cameras and free client coordinates are never inputs. The descriptor binds an immutable
 * layout while the native actor digest preserves its world/store and lease/revision identities. The outer
 * publication proof independently binds both of them to this exact far policy and center.
 */
public final class RollingFarPublication {
    public static final String POLICY_VERSION = "rolling-far-publication-v3";
    public record Center(long x, long z) {}

    private RollingFarPublication() {}

    /** Each profile moves on its descriptor-bound anchor-relative lattice. */
    public static Center center(RollingSessionDescriptor descriptor, CubePos candidateCenter) {
        Objects.requireNonNull(descriptor, "descriptor");
        descriptor.requireAllowedWindow(candidateCenter);
        var layout = descriptor.lodLayout();
        var result = new Center(snappedCoordinate(descriptor.fixedFarCenterX(), candidateCenter.x(), layout),
                snappedCoordinate(descriptor.fixedFarCenterZ(), candidateCenter.z(), layout));
        requireNativeFootprint(result.x(), layout); requireNativeFootprint(result.z(), layout);
        return result;
    }

    /** Checked at every arithmetic step, including negative-coordinate floor division. */
    static long snappedCoordinate(long fixedFarCenter, long candidateCubeCoordinate, DhLodLayout layout) {
        Objects.requireNonNull(layout, "layout");
        long anchor = alignedAnchor(fixedFarCenter, layout.centerAlignmentBlocks());
        int snap = layout.snapBlocks();
        long body = Math.addExact(Math.multiplyExact((long) CubePos.SIZE, candidateCubeCoordinate), CubePos.SIZE / 2);
        long relative = Math.addExact(Math.subtractExact(body, anchor), snap / 2);
        return Math.addExact(anchor, Math.multiplyExact((long) snap, Math.floorDiv(relative, snap)));
    }

    private static long alignedAnchor(long fixedFarCenter, int alignment) {
        return Math.multiplyExact((long) alignment, Math.floorDiv(fixedFarCenter, alignment));
    }

    private static void requireNativeFootprint(long center, DhLodLayout layout) {
        int radius = layout.radiusBlocks();
        long min = Math.multiplyExact((long) RollingCubePlan.NATIVE_XZ_MIN, CubePos.SIZE);
        long max = Math.multiplyExact(Math.addExact((long) RollingCubePlan.NATIVE_XZ_MAX, 1), CubePos.SIZE);
        if (Math.subtractExact(center, radius) < min || Math.addExact(center, radius) > max)
            throw new IllegalArgumentException("Moving far footprint exceeds signed native coordinate domain");
    }

    /** Exact ready convenience: independently derive the center and never consume a claimed one. */
    public static String digest(RollingSessionDescriptor descriptor, StageReady ready) {
        Objects.requireNonNull(ready, "ready");
        if (!descriptor.world().equals(ready.world())) throw new IllegalArgumentException("Pair source identity mismatch");
        return digest(descriptor, RollingGateWire.manifestDigest(ready), center(descriptor, ready.candidateCenter()));
    }

    /**
     * A canonical, versioned, length-delimited pair digest. This hashes a center, but does not
     * authorize that center: admission must derive it from the exact StageReady candidate.
     */
    public static String digest(RollingSessionDescriptor descriptor, String nativeManifestDigest, Center center) {
        Objects.requireNonNull(descriptor, "descriptor"); Objects.requireNonNull(center, "center");
        if (nativeManifestDigest == null || !nativeManifestDigest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Canonical native manifest SHA-256 required");
        try {
            var bytes = new ByteArrayOutputStream(256);
            try (var out = new DataOutputStream(bytes)) {
                string(out, POLICY_VERSION); string(out, descriptor.fingerprint()); string(out, nativeManifestDigest);
                out.writeLong(center.x()); out.writeLong(center.z());
                string(out, descriptor.lodLayout().id()); string(out, descriptor.lodLayout().identity());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8); out.writeInt(encoded.length); out.write(encoded);
    }
}
