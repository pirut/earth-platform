package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Distinct bounded single-region and two-workspace indexes. Used only under FileEditStore's lock. */
final class ReplacementStoreIdentity {
    static final String FILE = "replacement-region-v1.index";
    static final String TWO_FILE = "replacement-regions-v2.index";
    static final String ADJACENT_FILE = "replacement-adjacent-regions-v1.index";
    static String setFile(ReplacementWorkspaces workspaces) { return workspaces.isAdjacent() ? ADJACENT_FILE : TWO_FILE; }
    static final int MAX_TWO_BYTES = 512;

    /** Durable portable binding for the exact pair; not a runtime publication or saved geometry. */
    record TwoWorkspaceIdentity(String storeIdentity, String workspaceIdentity, String indexIdentity) {}

    /**
     * Separate versioned format. An existing single-region index requires explicit migration;
     * opening it as a two-region store must never silently reinterpret its identity.
     * Called only while FileEditStore's exclusive directory lock is held.
     */
    static TwoWorkspaceIdentity openTwo(Path directory, ReplacementWorkspaces workspaces) throws IOException {
        return ReplacementRevisionLedger.open(directory, workspaces).identity();
    }

    static String open(Path directory, CubePos original) throws IOException {
        if (Files.exists(directory.resolve(PagedReplacementIndex.ROOT_FILE)) || Files.exists(directory.resolve(PagedReplacementIndex.INTENT_FILE)))
            throw new IOException("Paged replacement store cannot be opened through the legacy single-region path");
        if (Files.exists(directory.resolve(TWO_FILE)) || Files.exists(directory.resolve(ADJACENT_FILE)))
            throw new IOException("Two-workspace store cannot be opened through the legacy single-region path");
        Path target = directory.resolve(FILE);
        String suffix = "\n" + original.x() + " " + original.y() + " " + original.z() + "\n";
        if (!Files.exists(target)) {
            byte[] bytes = ("earth-replacement-store-v1\n" + UUID.randomUUID() + suffix).getBytes(StandardCharsets.UTF_8);
            Path staged = Files.createTempFile(directory, ".replacement-index-", ".tmp");
            try {
                try (FileChannel file = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) file.write(buffer);
                    file.force(true);
                }
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(staged); }
        }
        byte[] bytes;
        try (var in = Files.newInputStream(target)) { bytes = in.readNBytes(257); }
        if (bytes.length > 256) throw new IOException("Oversized replacement-region index");
        String value = new String(bytes, StandardCharsets.UTF_8);
        String prefix = "earth-replacement-store-v1\n";
        if (!value.startsWith(prefix) || !value.endsWith(suffix))
            throw new IOException("Replacement store is bound to a different original region");
        String id = value.substring(prefix.length(), value.length() - suffix.length());
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException malformed) { throw new IOException("Malformed replacement store identity", malformed); }
        // Reestablish a possibly uncertain previous creation before exposing any certificate.
        try (FileChannel file = FileChannel.open(target, StandardOpenOption.WRITE)) { file.force(true); }
        try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
        return id;
    }
    private ReplacementStoreIdentity() {}
}
