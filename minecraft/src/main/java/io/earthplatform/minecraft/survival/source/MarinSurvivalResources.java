package io.earthplatform.minecraft.survival.source;

import io.earthplatform.geography.WorldManifest;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/** Setup-only, checksum-pinned bundled source. No sampling I/O, download or mutable-world authority. */
public final class MarinSurvivalResources {
    private record FileSpec(String resource, String target, int bytes, String sha256) {}
    private static final List<FileSpec> FILES = List.of(
        new FileSpec("proof_data/world-global.properties", "terrain/world-global.properties", 2247, "ba30ef9039668626d811739548b89ec82b8abab6e89822d0bf93cf2b10e4d663"),
        new FileSpec("proof_data/regions/challenger-deep/challenger-deep.egp", "terrain/regions/challenger-deep/challenger-deep.egp", 46124, "5c5e8c95f656d1f2b2fd39671e06ec0ffc751b91f6c8ded39c6bdc1d9051717c"),
        new FileSpec("proof_data/regions/everest/everest.egp", "terrain/regions/everest/everest.egp", 14444, "16b9f463cc39bbe192a0914ceadeb811e982dac6fce3ce55a1f04505d2f3dd04"),
        new FileSpec("proof_data/regions/san-francisco/san-francisco-east.egp", "terrain/regions/san-francisco/san-francisco-east.egp", 28844, "609dde26e4171090da445196754e304bb0b94267f3645527550dae3e12039445"),
        new FileSpec("proof_data/regions/san-francisco/san-francisco-west.egp", "terrain/regions/san-francisco/san-francisco-west.egp", 29804, "b6984a14952b7468cd544f2b4ec6ea0ff6d8e59308b1d530a2ce58569ef5c9bc"),
        new FileSpec("worldcover_proof_data/landcover.properties", "cover/landcover.properties", 956, "16e23d166b4fa8847931b7c628a9eae5691f85ce99ec5793bed9c27d3210c25a"),
        new FileSpec("worldcover_proof_data/marin-headlands/landcover.elc", "cover/marin-headlands/landcover.elc", 1048620, "82408a2d5707518f9d3deb69bc05645b1ea5674f935d3110653f830b88cfb27c"),
        new FileSpec("worldcover_proof_data/everest/landcover.elc", "cover/everest/landcover.elc", 1048620, "5276fb2adcf1c570e8328c126bc03a7c757cef2b8553f85a5a107b4a2608fdc7"));
    private MarinSurvivalResources() {}
    /** Caller owns the prepared source and its accounted memory for the entire reader lifetime. */
    public static MarinSurvivalDefinition installAndLoad(Path root) throws IOException {
        root = root.toAbsolutePath().normalize();
        plainDirectory(root);
        for (FileSpec spec : FILES) {
            byte[] bytes;
            try (var input = MarinSurvivalResources.class.getResourceAsStream("/earthplatform/" + spec.resource)) {
                if (input == null) throw new IOException("Missing bundled Marin source " + spec.resource);
                bytes = input.readNBytes(spec.bytes + 1);
            }
            if (bytes.length != spec.bytes || !WorldManifest.sha256(bytes).equals(spec.sha256))
                throw new IOException("Bundled Marin source checksum/length mismatch " + spec.resource);
            Path target = root.resolve(spec.target).normalize();
            if (!target.startsWith(root)) throw new IOException("Invalid source installation path");
            Path dir = root;
            for (Path component : root.relativize(target.getParent())) { dir = dir.resolve(component); plainDirectory(dir); }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source destination is not a plain file");
                try (var input = Files.newInputStream(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] existing = input.readNBytes(spec.bytes + 1);
                    if (existing.length == spec.bytes && WorldManifest.sha256(existing).equals(spec.sha256)) continue;
                }
                // Preserve a changed source as evidence; do not overwrite an unexpected user file.
                throw new IOException("Existing Marin source changed: " + spec.target);
            }
            Path temporary = Files.createTempFile(target.getParent(), ".marin-source-", ".tmp");
            try {
                Files.write(temporary, bytes);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
        }
        return MarinSurvivalDefinition.loadPaths(root.resolve("terrain/world-global.properties"),
                root.resolve("cover/landcover.properties"), root.resolve("cover/marin-headlands/landcover.elc"));
    }
    private static void plainDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source directory is not plain");
        } else Files.createDirectory(path);
    }
}
