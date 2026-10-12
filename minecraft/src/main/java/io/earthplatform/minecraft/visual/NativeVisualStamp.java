package io.earthplatform.minecraft.visual;

import io.earthplatform.core.WorldIdentity;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Immutable content/request identity only. It is not a GPU-ready or collision capability. */
public record NativeVisualStamp(WorldIdentity world, UUID storeIdentity, long sessionEpoch,
        long resourceGeneration, long visualGeneration, long durableRevision,
        int selectedDistanceChunks, int effectiveDistanceChunks, int serverDistanceLimit,
        String sourceIdentity, String detailIdentity, String materialPolicyIdentity,
        String capsuleIdentity, String captureIdentity) {
    public static final String DOMAIN = "native-setting-visual-stamp-v1";
    public NativeVisualStamp {
        Objects.requireNonNull(world); Objects.requireNonNull(storeIdentity);
        if (sessionEpoch <= 0 || resourceGeneration <= 0 || visualGeneration <= 0 || durableRevision < 0)
            throw new IllegalArgumentException("Positive native visual generations and nonnegative durable revision required");
        if (selectedDistanceChunks < 2 || selectedDistanceChunks > 32 || effectiveDistanceChunks < 1
                || effectiveDistanceChunks > 32 || serverDistanceLimit < 0 || serverDistanceLimit > 32)
            throw new IllegalArgumentException("Native request exceeds the pinned Minecraft/Sodium option domain");
        int expected = serverDistanceLimit > 0 ? Math.min(selectedDistanceChunks, serverDistanceLimit) : selectedDistanceChunks;
        if (effectiveDistanceChunks != expected)
            throw new IllegalArgumentException("Effective native distance disagrees with actual selected/server values");
        requireHash(sourceIdentity); requireHash(detailIdentity); requireHash(materialPolicyIdentity);
        requireHash(capsuleIdentity); requireHash(captureIdentity);
    }
    public boolean selectedDistanceEffective() { return selectedDistanceChunks == effectiveDistanceChunks; }
    /** A single-player readiness claim may never hide a smaller effective setting. */
    public void requireSelectedDistanceEffective() {
        if (!selectedDistanceEffective()) throw new IllegalStateException("Selected native distance " + selectedDistanceChunks
                + " is not yet effective: " + effectiveDistanceChunks + " (server limit " + serverDistanceLimit + ")");
    }
    public int radiusBlocks() { return Math.multiplyExact(effectiveDistanceChunks, 16); }
    public String identity() {
        try {
            var bytes = new ByteArrayOutputStream(768);
            try (var out = new DataOutputStream(bytes)) {
                string(out, DOMAIN); out.writeLong(world.seed()); string(out, world.generatorId());
                string(out, world.generatorVersion()); string(out, world.datasetVersion()); string(out, world.blockPaletteVersion());
                out.writeLong(storeIdentity.getMostSignificantBits()); out.writeLong(storeIdentity.getLeastSignificantBits());
                out.writeLong(sessionEpoch); out.writeLong(resourceGeneration); out.writeLong(visualGeneration); out.writeLong(durableRevision);
                out.writeInt(selectedDistanceChunks); out.writeInt(effectiveDistanceChunks); out.writeInt(serverDistanceLimit);
                string(out, sourceIdentity); string(out, detailIdentity); string(out, materialPolicyIdentity);
                string(out, capsuleIdentity); string(out, captureIdentity);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException impossible) { throw new AssertionError(impossible); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Canonical SHA-256 native identity required");
    }
}
