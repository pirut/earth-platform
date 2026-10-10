package io.earthplatform.minecraft;

import io.earthplatform.geography.landcover.OfflineLandCover;
import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;
import net.neoforged.fml.loading.FMLPaths;

/** Optional checksum-verified WorldCover proof pack; no sampling-time filesystem or network access. */
public final class ProofLandCover {
    private static OfflineLandCover loaded;
    private ProofLandCover() {}
    public static synchronized OfflineLandCover get() {
        if (loaded != null) return loaded;
        try {
            Path root = FMLPaths.GAMEDIR.get().resolve("earthplatform-worldcover-proof-data");
            Files.createDirectories(root);
            Path manifest = root.resolve("landcover.properties");
            copy("landcover.properties", manifest);
            var properties = new Properties();
            try (var input = Files.newInputStream(manifest)) { properties.load(input); }
            int count = Integer.parseInt(properties.getProperty("tile.count"));
            if (count < 1 || count > 16) throw new IOException("Invalid WorldCover tile count");
            for (int index = 0; index < count; index++) {
                String relative = properties.getProperty("tile." + index + ".path");
                Path path = root.resolve(relative).normalize();
                if (!path.startsWith(root)) throw new IOException("Unsafe WorldCover path");
                Files.createDirectories(path.getParent()); copy(relative, path);
            }
            loaded = OfflineLandCover.load(manifest);
            return loaded;
        } catch (IOException e) { throw new IllegalStateException("Cannot load verified land-cover proof pack", e); }
    }
    private static void copy(String relative, Path target) throws IOException {
        try (var input = ProofLandCover.class.getResourceAsStream("/earthplatform/worldcover_proof_data/" + relative)) {
            if (input == null) throw new IOException("Missing land-cover resource " + relative);
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
