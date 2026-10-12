package io.earthplatform.minecraft.survival.persistence;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/**
 * One checkpoint points to immutable payloads captured at one simulation boundary.
 * Payloads include cube state, inventory, drops and admitted scheduled effects; their
 * encoding and same-boundary capture are the caller's responsibility. This store never
 * invokes gameplay callbacks or acknowledges a simulation revision itself.
 *
 * An uncertain I/O result is NOT an acknowledgement. The owner keeps dirty pins and
 * retries the identical image, or explicitly recovers the last complete CURRENT record.
 * Older generations and incomplete attempts are retained, never silently adopted/deleted.
 */
public final class SurvivalCheckpointStore implements AutoCloseable {
    private static final int MAGIC = 0x45534350, VERSION = 1;
    public record Limits(int maxPayloads, int maxPayloadBytes, long maxTotalBytes, int maxManifestBytes) {
        public Limits { if (maxPayloads < 1 || maxPayloadBytes < 1 || maxTotalBytes < 1 || maxManifestBytes < 128) throw new IllegalArgumentException("Positive bounded checkpoint limits required"); }
    }
    public enum Phase { PAYLOADS_SYNCED, MANIFEST_SYNCED, CURRENT_REPLACED, DIRECTORY_SYNCED }
    @FunctionalInterface public interface FaultProbe { void after(Phase phase) throws IOException; }
    public static final class Image {
        private final long generation;
        private final SortedMap<String, byte[]> payloads;
        public Image(Limits limits, long generation, Map<String, byte[]> payloads) {
            Objects.requireNonNull(limits);
            if (Objects.requireNonNull(payloads).isEmpty() || payloads.size() > limits.maxPayloads) throw new IllegalArgumentException("Checkpoint payload count exceeds admission");
            long total = 0;
            for (var entry : payloads.entrySet()) { checkKey(entry.getKey()); byte[] value = Objects.requireNonNull(entry.getValue()); if (value.length > limits.maxPayloadBytes || (total = Math.addExact(total, value.length)) > limits.maxTotalBytes) throw new IllegalArgumentException("Checkpoint payload bytes exceed admission"); }
            if (generation < 1) throw new IllegalArgumentException("Positive checkpoint generation required");
            this.generation = generation;
            TreeMap<String, byte[]> copy = new TreeMap<>();
            Objects.requireNonNull(payloads).forEach((key, value) -> { checkKey(key); copy.put(key, Objects.requireNonNull(value).clone()); });
            this.payloads = Collections.unmodifiableSortedMap(copy);
        }
        private Image(long generation, SortedMap<String, byte[]> exclusivelyOwned) { this.generation = generation; this.payloads = Collections.unmodifiableSortedMap(exclusivelyOwned); }
        public long generation() { return generation; }
        /** Read-only scalar accounting; no byte-array alias or clone escapes. */
        public long payloadBytes(){long total=0;for(byte[] bytes:payloads.values())total=Math.addExact(total,bytes.length);return total;}
        public Set<String> names() { return payloads.keySet(); }
        public byte[] payload(String name) { byte[] bytes = payloads.get(name); if (bytes == null) throw new IllegalArgumentException("Missing checkpoint payload"); return bytes.clone(); }
    }
    public record Receipt(long generation, String manifestSha256, long payloadBytes, int payloadCount) {}
    private final Path directory;
    private final String worldIdentity;
    private final Limits limits;
    private final FaultProbe probe;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private boolean closed;

    public SurvivalCheckpointStore(Path directory, String worldIdentity, Limits limits) throws IOException {
        this(directory, worldIdentity, limits, phase -> {});
    }
    public SurvivalCheckpointStore(Path directory, String worldIdentity, Limits limits, FaultProbe probe) throws IOException {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.worldIdentity = Objects.requireNonNull(worldIdentity);
        if (worldIdentity.isBlank() || worldIdentity.length() > 1024) throw new IllegalArgumentException("Bounded world/source/palette identity required");
        this.limits = Objects.requireNonNull(limits); this.probe = Objects.requireNonNull(probe);
        if (Files.isSymbolicLink(this.directory)) throw new IOException("Checkpoint root must not be a symlink");
        Files.createDirectories(this.directory);
        Path ancestor = this.directory; while (ancestor != null) { syncDirectory(ancestor); ancestor = ancestor.getParent(); }
        Path writerPath = this.directory.resolve("writer.lock");
        if (Files.exists(writerPath, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(writerPath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Checkpoint lock must be an owned regular file");
        lockChannel = FileChannel.open(writerPath, CREATE, WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); if (acquired == null) throw new IOException("Survival checkpoint writer already owns this directory"); }
        catch (Throwable failure) { lockChannel.close(); throw failure; }
        lock = acquired;
        try { initializeEmptyAuthority(); } catch (Throwable failure) { try { lock.release(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); } try { lockChannel.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); } throw failure; }
    }
    void requireLimits(Limits exact){if(!limits.equals(exact))throw new IllegalArgumentException("Checkpoint store/owner limits differ");}
    public synchronized Optional<Image> load() throws IOException { ensureOpen(); Current current = current(); return current.generation == 0 ? Optional.empty() : Optional.of(readImage(current)); }
    public synchronized Receipt commit(long expectedPreviousGeneration, Image image) throws IOException {
        ensureOpen(); Objects.requireNonNull(image); validate(image);
        if (expectedPreviousGeneration < 0 || image.generation != Math.incrementExact(expectedPreviousGeneration)) throw new IllegalArgumentException("Consecutive checkpoint generation required");
        byte[] manifest = manifest(image); String digest = sha256(manifest); Current previous = current();
        long actual = previous.generation;
        if (actual == image.generation && previous.digest.equals(digest)) {
            // Retry after uncertain post-rename failure: revalidate bytes and re-establish barriers.
            Image validated = readImage(previous); syncGeneration(previous.directory, validated.names()); force(directory.resolve("CURRENT")); syncDirectory(directory);
            return receipt(image, digest);
        }
        if (actual != expectedPreviousGeneration) throw new IOException("Stale checkpoint parent: expected " + expectedPreviousGeneration + ", found " + actual);
        if (previous.generation != 0) readImage(previous); // Corrupt prior authority is never silently replaced.
        String generationName = "generation-" + image.generation + "-" + UUID.randomUUID();
        Path generation = directory.resolve(generationName); Files.createDirectory(generation);
        for (var entry : image.payloads.entrySet()) writeNew(generation.resolve(entry.getKey() + ".bin"), entry.getValue());
        syncDirectory(generation); probe.after(Phase.PAYLOADS_SYNCED);
        writeNew(generation.resolve("manifest.bin"), manifest); syncDirectory(generation); syncDirectory(directory); probe.after(Phase.MANIFEST_SYNCED);
        byte[] pointer = encodePointer(new Current(image.generation, generation, digest));
        Path temporary = directory.resolve("CURRENT-" + UUID.randomUUID() + ".tmp"); writeNew(temporary, pointer);
        // No non-atomic fallback: unsupported atomic replacement is an explicit save failure.
        Files.move(temporary, directory.resolve("CURRENT"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        probe.after(Phase.CURRENT_REPLACED); syncDirectory(directory); probe.after(Phase.DIRECTORY_SYNCED);
        return receipt(image, digest);
    }
    private Receipt receipt(Image image, String hash) { return new Receipt(image.generation, hash, image.payloads.values().stream().mapToLong(a -> a.length).sum(), image.payloads.size()); }
    private void validate(Image image) throws IOException {
        if (image.payloads.isEmpty() || image.payloads.size() > limits.maxPayloads) throw new IOException("Checkpoint payload count exceeds admission");
        long total = 0;
        for (byte[] value : image.payloads.values()) { if (value.length > limits.maxPayloadBytes) throw new IOException("Checkpoint payload exceeds admission"); total = Math.addExact(total, value.length); }
        if (total > limits.maxTotalBytes) throw new IOException("Checkpoint total exceeds admission");
    }
    private byte[] manifest(Image image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(worldIdentity); out.writeLong(image.generation); out.writeInt(image.payloads.size());
            for (var e : image.payloads.entrySet()) { out.writeUTF(e.getKey()); out.writeInt(e.getValue().length); out.writeUTF(sha256(e.getValue())); }
        }
        if (bytes.size() > limits.maxManifestBytes) throw new IOException("Checkpoint manifest exceeds admission");
        return bytes.toByteArray();
    }
    private Image readImage(Current current) throws IOException {
        byte[] manifest = boundedRead(current.directory.resolve("manifest.bin"), limits.maxManifestBytes);
        if (!sha256(manifest).equals(current.digest)) throw new IOException("Checkpoint manifest checksum mismatch");
        TreeMap<String, byte[]> payloads = new TreeMap<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(manifest))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION || !in.readUTF().equals(worldIdentity) || in.readLong() != current.generation) throw new IOException("Checkpoint identity/version mismatch");
            int count = in.readInt(); if (count < 1 || count > limits.maxPayloads) throw new IOException("Invalid checkpoint payload count");
            long total = 0; String previous = "";
            for (int i = 0; i < count; i++) {
                String name = in.readUTF(); try { checkKey(name); } catch (IllegalArgumentException e) { throw new IOException("Invalid checkpoint payload name", e); }
                if (name.compareTo(previous) <= 0) throw new IOException("Noncanonical checkpoint payload order"); previous = name;
                int length = in.readInt(); if (length < 0 || length > limits.maxPayloadBytes || (total = Math.addExact(total, length)) > limits.maxTotalBytes) throw new IOException("Checkpoint payload length exceeds admission");
                String digest = in.readUTF(); byte[] data = boundedRead(current.directory.resolve(name + ".bin"), length);
                if (data.length != length || !sha256(data).equals(digest)) throw new IOException("Checkpoint payload checksum mismatch: " + name);
                payloads.put(name, data);
            }
            if (in.available() != 0) throw new IOException("Trailing checkpoint manifest data");
        }
        return new Image(current.generation, payloads);
    }
    private record Current(long generation, Path directory, String digest) {}
    private Current current() throws IOException {
        Path path = directory.resolve("CURRENT"); if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing checkpoint authority; refusing empty-world fallback");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(boundedRead(path, 4096)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION || !in.readUTF().equals(worldIdentity)) throw new IOException("Checkpoint pointer identity/version mismatch");
            long generation = in.readLong(); String name = in.readUTF(), digest = in.readUTF();
            if (generation == 0 && name.isEmpty() && digest.isEmpty() && in.available() == 0) return new Current(0, null, "");
            if (generation < 1 || !name.matches("generation-" + generation + "-[a-f0-9-]{36}") || !digest.matches("[a-f0-9]{64}") || in.available() != 0) throw new IOException("Invalid checkpoint pointer");
            Path child = directory.resolve(name); if (Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Checkpoint generation is not an owned directory");
            return new Current(generation, child, digest);
        }
    }
    private byte[] encodePointer(Current current) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) { out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(worldIdentity); out.writeLong(current.generation); out.writeUTF(current.generation == 0 ? "" : current.directory.getFileName().toString()); out.writeUTF(current.digest); }
        return bytes.toByteArray();
    }
    private void initializeEmptyAuthority() throws IOException {
        if (Files.exists(directory.resolve("CURRENT"), LinkOption.NOFOLLOW_LINKS)) { current(); return; }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) { for (Path entry : entries) if (!entry.getFileName().toString().equals("writer.lock")) throw new IOException("Missing authority for existing checkpoint data; explicit recovery required"); }
        writeNew(directory.resolve("CURRENT"), encodePointer(new Current(0, null, ""))); syncDirectory(directory);
    }
    private void syncGeneration(Path generation, Set<String> names) throws IOException { force(generation.resolve("manifest.bin")); for (String name : names) force(generation.resolve(name + ".bin")); syncDirectory(generation); }
    private static void checkKey(String name) { if (name == null || !name.matches("[a-z][a-z0-9_-]{0,127}") || name.equals("manifest")) throw new IllegalArgumentException("Safe bounded checkpoint payload name required"); }
    private static byte[] boundedRead(Path path, int max) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid checkpoint file: " + path.getFileName());
        try (InputStream in = Files.newInputStream(path, READ, LinkOption.NOFOLLOW_LINKS)) {
            return readAtMost(in, max);
        }
    }
    static byte[] readAtMost(InputStream in, int max) throws IOException {
        if (max < 0) throw new IllegalArgumentException("Negative read bound");
        byte[] bytes = in.readNBytes(max);
        if (in.read() != -1) throw new IOException("Checkpoint file exceeds admitted length");
        return bytes;
    }
    private static void writeNew(Path path, byte[] bytes) throws IOException { try (FileChannel channel = FileChannel.open(path, CREATE_NEW, WRITE)) { java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true); } }
    private static void force(Path path) throws IOException { try (FileChannel channel = FileChannel.open(path, READ, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); } }
    private static void syncDirectory(Path path) throws IOException { try (FileChannel channel = FileChannel.open(path, READ, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); } }
    private static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); } }
    private void ensureOpen() throws IOException { if (closed || !lock.isValid() || !lockChannel.isOpen()) throw new IOException("Checkpoint store is closed"); }
    @Override public synchronized void close() throws IOException { if (closed) return; IOException failure = null; try { lock.release(); } catch (IOException e) { failure = e; } finally { try { lockChannel.close(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); } closed = true; } if (failure != null) throw failure; }
}
