package io.earthplatform.geography;

import io.earthplatform.core.WorldIdentity;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** A complete world pins projection, interpolation, generator and content-hashed raster data. */
public record WorldManifest(String id, long seed, String generatorVersion, EquirectangularProjection projection,
                            DatasetProvenance provenance, List<TileSpec> tiles, String fingerprint, String paletteVersion) {
    public WorldManifest { tiles = List.copyOf(tiles); if (tiles.isEmpty()) throw new IllegalArgumentException("Manifest has no tiles"); }
    public WorldIdentity worldIdentity() { return worldIdentity(paletteVersion); }
    /** Adapters using different runtime state IDs must supply a stable hash/version of their actual palette. */
    public WorldIdentity worldIdentity(String actualPaletteVersion) { return new WorldIdentity(seed, "earth-geography", generatorVersion, fingerprint, actualPaletteVersion); }
    public static WorldManifest load(Path file) throws IOException {
        if (Files.size(file) > 1_048_576) throw new IOException("Manifest exceeds 1 MiB");
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
        if (!EquirectangularProjection.ID.equals(required(p, "projection.id"))) throw new IOException("Unsupported projection");
        if (!"bilinear-v1".equals(required(p, "interpolation"))) throw new IOException("Unsupported interpolation");
        int count = Integer.parseInt(required(p, "tile.count"));
        if (count < 1 || count > 10000) throw new IOException("Invalid tile count");
        var tiles = new ArrayList<TileSpec>();
        var ids = new HashSet<String>();
        for (int i = 0; i < count; i++) {
            String k = "tile." + i + ".";
            var t = new TileSpec(required(p,k+"id"), required(p,k+"path"), required(p,k+"sha256"), number(p,k+"west"), number(p,k+"north"),
                number(p,k+"stepLon"), number(p,k+"stepLat"), Integer.parseInt(required(p,k+"width")), Integer.parseInt(required(p,k+"height")));
            if (!ids.add(t.id())) throw new IOException("Duplicate tile id " + t.id());
            tiles.add(t);
        }
        DatasetProvenance source = new DatasetProvenance(required(p,"dataset.id"),required(p,"dataset.release"),required(p,"dataset.datum"),number(p,"dataset.resolutionArcSeconds"),
            required(p,"dataset.license"),URI.create(required(p,"dataset.source")),required(p,"dataset.quality"),required(p,"dataset.attribution"));
        var projection = new EquirectangularProjection(new GeoPoint(number(p,"projection.originLat"),number(p,"projection.originLon")),number(p,"projection.standardParallel"));
        // Length-delimited sorted keys eliminate ambiguity from whitespace/newlines; comments/order are irrelevant.
        var canonical = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(canonical)) {
            for (String key : new TreeSet<>(p.stringPropertyNames())) {
                byte[] k = key.getBytes(StandardCharsets.UTF_8), v = p.getProperty(key).getBytes(StandardCharsets.UTF_8);
                out.writeInt(k.length); out.write(k); out.writeInt(v.length); out.write(v);
            }
        }
        return new WorldManifest(required(p,"world.id"),Long.parseLong(required(p,"world.seed")),required(p,"generator.version"),projection,source,tiles,sha256(canonical.toByteArray()),required(p,"palette.version"));
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key); if (value == null || value.isBlank()) throw new IOException("Missing manifest key " + key); return value;
    }
    private static double number(Properties p, String key) throws IOException { return Double.parseDouble(required(p,key)); }
}
