package io.earthplatform.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;

/**
 * Versioned, checksummed per-cube overlays with a pinned world manifest and exclusive writer lock.
 * Saves fsync a staged file, atomically replace a verified previous-generation backup, then atomically
 * replace the primary and fsync its directory. There is no unsafe non-atomic fallback.
 * Backup recovery is returned visibly; writes remain blocked until restoreBackup is explicitly called.
 */
public final class FileEditStore implements EditStore {
    private static final int MAGIC = 0x45415254, VERSION = 1, MANIFEST = 0, CUBE = 1;
    private static final int MAX_FILE_BYTES = 65536;
    private final Path directory, cubeDirectory;
    private final WorldIdentity identity;
    private final FileChannel lockChannel;
    private final FileLock writerLock;
    private boolean closed;
    private final DirectorySync directorySync;
    @FunctionalInterface interface DirectorySync { void force(Path directory) throws IOException; }

    public FileEditStore(Path directory, WorldIdentity identity) throws IOException {
        this(directory, identity, FileEditStore::forceDirectory);
    }
    FileEditStore(Path directory, WorldIdentity identity, DirectorySync directorySync) throws IOException {
        this.directorySync = Objects.requireNonNull(directorySync);
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.identity = Objects.requireNonNull(identity); this.cubeDirectory = this.directory.resolve("cubes");
        Files.createDirectories(this.directory);
        // A previous failed open may already have created these entries without syncing them.
        // Force the ancestor chain on every open rather than trusting mere path existence.
        for (Path ancestor = this.directory.getParent(); ancestor != null; ancestor = ancestor.getParent())
            directorySync.force(ancestor);
        FileChannel channel = FileChannel.open(this.directory.resolve("world.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            try { acquired = channel.tryLock(); }
            catch (OverlappingFileLockException error) { throw new IOException("World already open by another writer", error); }
            if (acquired == null) throw new IOException("World already open by another writer");
            Path manifest = this.directory.resolve("world.manifest");
            if (Files.exists(manifest)) decode(readBounded(manifest), MANIFEST, null);
            else {
                if (Files.isDirectory(cubeDirectory)) {
                    try (var files = Files.list(cubeDirectory)) {
                        if (files.findAny().isPresent()) throw new IOException("Missing manifest for existing cube data; refusing to adopt it");
                    }
                }
                atomicWrite(manifest, encode(MANIFEST, null));
            }
            Files.createDirectories(cubeDirectory); directorySync.force(this.directory);
        } catch (Throwable error) {
            if (acquired != null) acquired.release(); channel.close(); throw error;
        }
        lockChannel = channel; writerLock = acquired;
    }
    public WorldIdentity identity() { return identity; }
    public Path primaryPath(CubePos position) { return cubeDirectory.resolve(fileName(position)); }
    public Path backupPath(CubePos position) { return cubeDirectory.resolve(fileName(position) + ".bak"); }
    private static String fileName(CubePos position) { return position.x() + "_" + position.y() + "_" + position.z() + ".cube"; }
    @Override public synchronized LoadedEdits load(CubePos position) throws IOException {
        ensureOpen(); Objects.requireNonNull(position);
        Path primary = primaryPath(position), backup = backupPath(position);
        IOException problem = null;
        if (Files.exists(primary)) {
            try { return new LoadedEdits(decode(readBounded(primary), CUBE, position), Status.PRIMARY, ""); }
            catch (IOException error) { problem = error; }
        }
        if (Files.exists(backup)) {
            try {
                EditSnapshot recovered = decode(readBounded(backup), CUBE, position);
                String detail = problem == null ? "Primary file is missing" : problem.getMessage();
                return new LoadedEdits(recovered, Status.RECOVERED_BACKUP,
                        detail + "; recovered previous revision " + recovered.revision() + ". Explicit restore required before writing.");
            } catch (IOException error) {
                if (problem != null) error.addSuppressed(problem);
                throw new CorruptSaveException("No valid primary or backup for " + position, error);
            }
        }
        if (problem != null) throw new CorruptSaveException("Invalid primary and no backup for " + position, problem);
        return new LoadedEdits(EditSnapshot.empty(position), Status.EMPTY, "");
    }
    @Override public synchronized void save(EditSnapshot snapshot) throws IOException {
        ensureOpen(); Objects.requireNonNull(snapshot);
        LoadedEdits current = load(snapshot.position());
        if (current.status() == Status.RECOVERED_BACKUP) throw new CorruptSaveException(current.warning());
        if (snapshot.equals(current.snapshot())) {
            // Retry must re-establish durability if the previous rename succeeded but directory sync failed.
            if (current.status() == Status.PRIMARY) { forceFile(primaryPath(snapshot.position())); directorySync.force(cubeDirectory); }
            return;
        }
        if (snapshot.revision() != Math.incrementExact(current.snapshot().revision()))
            throw new IOException("Stale or skipped edit revision: expected " + (current.snapshot().revision() + 1));
        Path primary = primaryPath(snapshot.position());
        if (current.status() == Status.PRIMARY) atomicWrite(backupPath(snapshot.position()), readBounded(primary));
        atomicWrite(primary, encode(CUBE, snapshot));
    }
    /** Explicitly accept losing changes newer than the valid backup; retain the bad primary as evidence. */
    public synchronized LoadedEdits restoreBackup(CubePos position) throws IOException {
        ensureOpen();
        LoadedEdits recovered = load(position);
        if (recovered.status() != Status.RECOVERED_BACKUP) throw new IOException("Backup recovery is not required");
        Path primary = primaryPath(position);
        if (Files.exists(primary)) {
            Path quarantined = cubeDirectory.resolve(fileName(position) + ".corrupt-" + UUID.randomUUID());
            Files.copy(primary, quarantined); forceFile(quarantined); directorySync.force(cubeDirectory);
        }
        atomicWrite(primary, encode(CUBE, recovered.snapshot()));
        return load(position);
    }
    private byte[] encode(int kind, EditSnapshot snapshot) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(kind);
            out.writeLong(identity.seed()); out.writeUTF(identity.generatorId());
            out.writeUTF(identity.generatorVersion()); out.writeUTF(identity.datasetVersion()); out.writeUTF(identity.blockPaletteVersion());
            if (kind == CUBE) {
                out.writeInt(snapshot.position().x()); out.writeInt(snapshot.position().y()); out.writeInt(snapshot.position().z());
                out.writeLong(snapshot.revision()); out.writeInt(snapshot.blocks().size());
                for (var edit : snapshot.blocks().entrySet()) { out.writeShort(edit.getKey()); out.writeInt(edit.getValue()); }
            }
        }
        byte[] bytes = payload.toByteArray(); CRC32C crc = new CRC32C(); crc.update(bytes, 0, bytes.length);
        ByteBuffer full = ByteBuffer.allocate(bytes.length + Long.BYTES); full.put(bytes).putLong(crc.getValue());
        return full.array();
    }
    private EditSnapshot decode(byte[] bytes, int expectedKind, CubePos expectedPosition) throws IOException {
        if (bytes.length < 20) throw new CorruptSaveException("Truncated save");
        int end = bytes.length - Long.BYTES; CRC32C crc = new CRC32C(); crc.update(bytes, 0, end);
        if (crc.getValue() != ByteBuffer.wrap(bytes, end, Long.BYTES).getLong()) throw new CorruptSaveException("Checksum mismatch");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, end))) {
            if (in.readInt() != MAGIC) throw new CorruptSaveException("Invalid save magic");
            int version = in.readInt();
            if (version != VERSION) throw new CorruptSaveException("Unsupported save version " + version);
            if (in.readInt() != expectedKind) throw new CorruptSaveException("Wrong record kind");
            WorldIdentity saved = new WorldIdentity(in.readLong(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF());
            if (!identity.equals(saved)) throw new CorruptSaveException("World generation identity mismatch");
            EditSnapshot result = null;
            if (expectedKind == CUBE) {
                CubePos position = new CubePos(in.readInt(), in.readInt(), in.readInt());
                if (!position.equals(expectedPosition)) throw new CorruptSaveException("Cube coordinate mismatch");
                long revision = in.readLong(); int count = in.readInt();
                if (revision < 1 || count < 0 || count > CubePos.VOLUME) throw new CorruptSaveException("Invalid revision or edit count");
                Map<Integer, Integer> edits = new TreeMap<>(); int previous = -1;
                for (int i = 0; i < count; i++) {
                    int index = in.readUnsignedShort(), state = in.readInt();
                    if (index <= previous || index >= CubePos.VOLUME || state < 0) throw new CorruptSaveException("Invalid or duplicate edit");
                    edits.put(index, state); previous = index;
                }
                result = new EditSnapshot(position, revision, edits);
            }
            if (in.available() != 0) throw new CorruptSaveException("Trailing save data");
            return result;
        } catch (IllegalArgumentException error) { throw new CorruptSaveException("Invalid save content", error); }
    }
    private static byte[] readBounded(Path path) throws IOException {
        // Read at most one byte beyond the limit even if the file grows after the size check.
        try (InputStream in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new CorruptSaveException("Oversized save file");
            return bytes;
        }
    }
    private void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path staged = Files.createTempFile(target.getParent(), ".earth-write-", ".tmp");
        try {
            try (FileChannel out = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) out.write(buffer); out.force(true);
            }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            directorySync.force(target.getParent());
        } finally { Files.deleteIfExists(staged); }
    }
    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }
    private static void forceDirectory(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
        catch (IOException | UnsupportedOperationException error) {
            throw new IOException("Strict directory fsync failed for " + path + "; this filesystem may not support the required durability policy", error);
        }
    }
    private void ensureOpen() throws IOException { if (closed || !writerLock.isValid()) throw new IOException("Edit store is closed"); }
    @Override public synchronized void close() throws IOException {
        if (closed) return; closed = true;
        try { writerLock.release(); } finally { lockChannel.close(); }
    }
    public static final class CorruptSaveException extends IOException {
        private static final long serialVersionUID = 1L;
        public CorruptSaveException(String message) { super(message); }
        public CorruptSaveException(String message, Throwable cause) { super(message, cause); }
    }
}
