package io.earthplatform.geography;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, single-flight async tile cache. Each transfer is a whole small pinned data-pack tile, never a per-block API request. */
public final class CachedTileStore implements TileStore {
    private final Path sourceRoot, cacheRoot;
    private final Executor executor;
    private final long maxMemoryBytes, maxDiskBytes;
    private final int maxInFlight;
    private final boolean allowNetwork;
    private final LinkedHashMap<String,RasterTile> memory = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String,CompletableFuture<RasterTile>> inFlight = new HashMap<>();
    private long memoryBytes;
    private final AtomicLong sourceReads = new AtomicLong(), diskHits = new AtomicLong(), repairedCacheEntries = new AtomicLong();
    public CachedTileStore(Path sourceRoot, Path cacheRoot, Executor executor, long maxMemoryBytes, long maxDiskBytes, int maxInFlight, boolean allowNetwork) throws IOException {
        this.sourceRoot = sourceRoot.toRealPath();
        Files.createDirectories(cacheRoot); this.cacheRoot = cacheRoot.toRealPath();
        this.executor = Objects.requireNonNull(executor);
        if (maxMemoryBytes < 0 || maxDiskBytes < 0 || maxInFlight < 1) throw new IllegalArgumentException("Invalid cache budget");
        this.maxMemoryBytes = maxMemoryBytes; this.maxDiskBytes = maxDiskBytes; this.maxInFlight = maxInFlight; this.allowNetwork = allowNetwork;
        trimDisk();
    }
    @Override public synchronized CompletableFuture<RasterTile> load(TileSpec spec) {
        String key = spec.sha256();
        RasterTile hit = memory.get(key);
        if (hit != null) return compatible(hit,spec);
        var pending = inFlight.get(key);
        if (pending != null) return pending.thenCompose(tile -> compatible(tile,spec));
        if (inFlight.size() >= maxInFlight) return CompletableFuture.failedFuture(new RejectedExecutionException("Geography tile in-flight limit reached"));
        var future = new CompletableFuture<RasterTile>();
        inFlight.put(key, future);
        try {
            executor.execute(() -> {
                try {
                    RasterTile tile = read(spec);
                    synchronized (this) {
                        if (tile.payloadBytes() <= maxMemoryBytes) {
                            memory.put(key,tile); memoryBytes += tile.payloadBytes();
                            var iterator = memory.entrySet().iterator();
                            while (memoryBytes > maxMemoryBytes && iterator.hasNext()) { memoryBytes -= iterator.next().getValue().payloadBytes(); iterator.remove(); }
                        }
                        inFlight.remove(key);
                    }
                    future.complete(tile);
                } catch (Throwable failure) {
                    synchronized (this) { inFlight.remove(key); }
                    future.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException rejected) { inFlight.remove(key); future.completeExceptionally(rejected); }
        // Never return the shared future: caller cancellation cannot cancel another cube's tile request.
        return future.thenApply(tile -> tile);
    }
    private static CompletableFuture<RasterTile> compatible(RasterTile tile, TileSpec expected) {
        TileSpec actual = tile.spec();
        if (actual.width()!=expected.width() || actual.height()!=expected.height() || actual.west()!=expected.west() || actual.north()!=expected.north()
            || actual.stepLon()!=expected.stepLon() || actual.stepLat()!=expected.stepLat())
            return CompletableFuture.failedFuture(new IllegalArgumentException("Same tile hash used with conflicting registration"));
        return CompletableFuture.completedFuture(tile);
    }
    private RasterTile read(TileSpec spec) throws IOException {
        Path cached = cacheRoot.resolve(spec.sha256()+".egp");
        byte[] bytes = null;
        synchronized (this) {
            // The eviction lock also covers disk reads, so a concurrent insert cannot delete a file mid-read.
            if (Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
                bytes = readBounded(cached,spec);
                if (!WorldManifest.sha256(bytes).equals(spec.sha256())) {
                    // Only generated cache bytes are discarded. The replacement must still match the original pinned hash.
                    Files.delete(cached); bytes = null; repairedCacheEntries.incrementAndGet();
                } else {
                    diskHits.incrementAndGet();
                    Files.setLastModifiedTime(cached,java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                }
            }
        }
        if (bytes == null) {
            bytes = readSource(sourceRoot,spec,allowNetwork);
            verify(spec,bytes);
            sourceReads.incrementAndGet();
            if (spec.encodedBytes() <= maxDiskBytes) persist(cached,bytes);
        }
        return RasterTile.decode(spec,bytes);
    }
    private synchronized void persist(Path cached, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(cacheRoot,"incoming-",".tmp");
        try {
            Files.write(temporary,bytes);
            try { Files.move(temporary,cached,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary,cached,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        trimDisk();
    }
    private synchronized void trimDisk() throws IOException {
        var files = new ArrayList<Path>(); long size = 0;
        try (var listing = Files.list(cacheRoot)) {
            for (Path path : listing.filter(p -> p.getFileName().toString().matches("[0-9a-f]{64}\\.egp") && Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList()) {
                files.add(path); size += Files.size(path);
            }
        }
        files.sort(Comparator.comparingLong(p -> { try { return Files.getLastModifiedTime(p).toMillis(); } catch(IOException e) { return Long.MIN_VALUE; } }));
        for (Path path : files) { if (size <= maxDiskBytes) break; long n = Files.size(path); Files.delete(path); size -= n; }
    }
    static byte[] readSource(Path root, TileSpec spec, boolean allowNetwork) throws IOException {
        if (spec.path().startsWith("https://")) {
            if (!allowNetwork) throw new IOException("Network disabled for this data pack");
            URI uri = URI.create(spec.path());
            if (uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getHost()==null) throw new IOException("Invalid public tile URL");
            var connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent","EarthPlatform/0.1 (bounded pinned tile importer)");
            try {
                if (connection.getResponseCode()!=200) throw new IOException("Tile HTTP status " + connection.getResponseCode());
                if (connection.getContentLengthLong() > spec.encodedBytes()) throw new IOException("Tile exceeds pinned size");
                try (InputStream input = connection.getInputStream()) { return input.readNBytes(Math.toIntExact(spec.encodedBytes()+1)); }
            } finally { connection.disconnect(); }
        }
        Path relative = Path.of(spec.path());
        if (relative.isAbsolute()) throw new IOException("Pack paths must be relative or explicit HTTPS URLs");
        Path path = root.resolve(relative).normalize().toRealPath();
        if (!path.startsWith(root.toRealPath())) throw new IOException("Tile path escapes data pack");
        return readBounded(path,spec);
    }
    private static byte[] readBounded(Path path, TileSpec spec) throws IOException {
        if (Files.size(path)!=spec.encodedBytes()) throw new IOException("Tile file differs from pinned byte length");
        try (InputStream input = Files.newInputStream(path)) { return input.readNBytes(Math.toIntExact(spec.encodedBytes()+1)); }
    }
    static void verify(TileSpec spec,byte[] bytes) throws IOException {
        if (bytes.length!=spec.encodedBytes() || !WorldManifest.sha256(bytes).equals(spec.sha256())) throw new IOException("Tile SHA-256 mismatch: " + spec.id());
    }
    public synchronized CacheStats stats() { return new CacheStats(memory.size(),memoryBytes,inFlight.size(),sourceReads.get(),diskHits.get(),repairedCacheEntries.get()); }
    public record CacheStats(int memoryTiles,long memoryBytes,int inFlight,long sourceReads,long diskHits,long repairedCacheEntries) {}
}
