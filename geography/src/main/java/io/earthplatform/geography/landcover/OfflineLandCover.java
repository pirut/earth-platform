package io.earthplatform.geography.landcover;

import io.earthplatform.geography.GeoPoint;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Independent, bounded, immutable WorldCover pack. Queries perform no I/O and never change terrain. */
public final class OfflineLandCover {
    public static final String SAMPLING = "pixel-area-containing-cell-v1";
    public static final long MAX_PACK_BYTES = 64L * 1024 * 1024;
    private final List<LandCoverRaster> rasters;
    private final String identity;
    private OfflineLandCover(List<LandCoverRaster> rasters, String identity) {
        this.rasters = List.copyOf(rasters); this.identity = identity;
    }
    /** Identity includes every canonical manifest property, source attribution, sample policy and raster hash. */
    public String identity() { return identity; }
    public LandCoverSample sample(GeoPoint point) {
        Objects.requireNonNull(point);
        // Manifest order is explicit precedence, including a source no-data cell.
        for (var raster : rasters) if (raster.covers(point)) return raster.sample(point);
        return LandCoverSample.outsideCoverage();
    }
    public static OfflineLandCover load(Path manifest) throws IOException {
        Path realManifest = manifest.toRealPath(), root = realManifest.getParent();
        byte[] raw = boundedRead(realManifest, 64 * 1024);
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(raw));
        if (!"earth-landcover-pack-v1".equals(required(properties, "format"))
                || !SAMPLING.equals(required(properties, "sampling"))
                || !"esa-worldcover-2021-v200".equals(required(properties, "dataset.id"))
                || !"2021".equals(required(properties, "dataset.epoch"))
                || !"EPSG:4326".equals(required(properties, "dataset.crs"))
                || !"CC-BY-4.0".equals(required(properties, "dataset.license")))
            throw new IOException("Unsupported land-cover source/format/sampling contract");
        for (String key : List.of("dataset.source", "dataset.licenseUrl", "dataset.attribution", "dataset.citation", "dataset.modifications")) required(properties, key);
        try {
            int count = Integer.parseInt(required(properties, "tile.count"));
            if (count < 1 || count > 64) throw new IOException("Land-cover tile count outside bounded limit");
            var rasters = new ArrayList<LandCoverRaster>();
            var ids = new HashSet<String>();
            long total = 0;
            for (int i = 0; i < count; i++) {
                String prefix = "tile." + i + ".", id = required(properties, prefix + "id");
                if (!ids.add(id)) throw new IOException("Duplicate land-cover region identity");
                Path relative = Path.of(required(properties, prefix + "path"));
                if (relative.isAbsolute()) throw new IOException("Only local pack-relative paths are supported");
                Path path = root.resolve(relative).toRealPath();
                if (!path.startsWith(root)) throw new IOException("Land-cover tile path escapes pack directory");
                long size = Files.size(path);
                if (size > MAX_PACK_BYTES - total) throw new IOException("Land-cover pack exceeds 64 MiB cap");
                byte[] bytes = boundedRead(path, LandCoverRaster.HEADER_BYTES + LandCoverRaster.MAX_CELLS);
                total = Math.addExact(total, bytes.length);
                if (total > MAX_PACK_BYTES) throw new IOException("Land-cover pack grew beyond 64 MiB cap");
                String expectedSha = required(properties, prefix + "sha256");
                if (!expectedSha.matches("[0-9a-f]{64}") || !sha256(bytes).equals(expectedSha))
                    throw new IOException("Land-cover tile checksum mismatch: " + id);
                var raster = LandCoverRaster.decode(id, bytes);
                if (Double.compare(raster.stepLon(), 1.0 / 12000) != 0
                        || Double.compare(raster.stepLat(), 1.0 / 12000) != 0)
                    throw new IOException("WorldCover pack is not at native 1/12000-degree spacing");
                rasters.add(raster);
            }
            StringBuilder canonical = new StringBuilder();
            for (String key : new TreeSet<>(properties.stringPropertyNames())) {
                String value = properties.getProperty(key);
                canonical.append(key.length()).append(':').append(key).append(value.length()).append(':').append(value).append('\n');
            }
            return new OfflineLandCover(rasters, "landcover-" + sha256(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new IOException("Invalid land-cover manifest", e);
        }
    }
    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Missing land-cover property: " + key);
        return value;
    }
    private static byte[] boundedRead(Path path, int maximum) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > maximum) throw new IOException("Oversized/nonregular land-cover file");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("Land-cover file grew beyond cap");
            return bytes;
        }
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
