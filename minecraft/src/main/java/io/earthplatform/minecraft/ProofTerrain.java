package io.earthplatform.minecraft;

import io.earthplatform.geography.OfflineTerrain;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.HashMap;
import java.util.Map;
import net.neoforged.fml.loading.FMLPaths;

/** Loads accepted checksum-verified proof packs; never fetches terrain during world generation. */
public final class ProofTerrain {
    public static final String NOAA = "noaa_etopo_2022";
    public static final String COPERNICUS = "copernicus_glo30_2021";
    public static final String MANIFEST_PROJECTION = "manifest";
    public static final String GLOBAL_PROJECTION = "global_equirectangular_v1";
    public static final String COPERNICUS_FINGERPRINT = "f03ea4512d29a26f3b41bed6477a137743fdb56f830abe9b4b47bd8225d1c436";
    private static final Map<String, OfflineTerrain> LOADED = new HashMap<>();
    public static OfflineTerrain get() { return get(NOAA); }
    public static void validateIdentifiers(String dataset, String projection) {
        if (!NOAA.equals(dataset) && !COPERNICUS.equals(dataset)) throw new IllegalArgumentException("Unknown proof dataset " + dataset);
        if (!MANIFEST_PROJECTION.equals(projection) && !GLOBAL_PROJECTION.equals(projection)) throw new IllegalArgumentException("Unknown horizontal projection " + projection);
    }
    public static String shortLabel(String dataset) {
        return switch (dataset) {
            case NOAA -> "NOAA 15\"";
            case COPERNICUS -> "Copernicus 1\" DSM";
            default -> throw new IllegalArgumentException("Unknown proof dataset " + dataset);
        };
    }
    public static String debugLabel(String dataset) {
        return switch (dataset) {
            case NOAA -> "NOAA ETOPO 2022 (15 arcsec)";
            case COPERNICUS -> "Copernicus DSM (1 arcsec)";
            default -> throw new IllegalArgumentException("Unknown proof dataset " + dataset);
        };
    }
    public static synchronized OfflineTerrain get(String dataset) {
        validateIdentifiers(dataset, MANIFEST_PROJECTION);
        if (LOADED.containsKey(dataset)) return LOADED.get(dataset);
        try {
            boolean noaa = NOAA.equals(dataset);
            String configured = System.getProperty(noaa ? "earthplatform.terrainManifest" : "earthplatform.copernicusManifest");
            String resourceRoot = noaa ? "/earthplatform/proof_data/" : "/earthplatform/copernicus_proof_data/";
            Path manifest;
            if (configured != null) manifest = Path.of(configured);
            else {
                Path root = FMLPaths.GAMEDIR.get().resolve(noaa ? "earthplatform-proof-data" : "earthplatform-copernicus-proof-data");
                Files.createDirectories(root);
                manifest = root.resolve("world.properties");
                copyResource(resourceRoot, "world.properties", manifest);
                Properties properties = new Properties();
                try (var input = Files.newInputStream(manifest)) { properties.load(input); }
                int count = Integer.parseInt(properties.getProperty("tile.count"));
                if (count < 1 || count > 32) throw new IOException("Invalid bundled proof tile count");
                for (int index = 0; index < count; index++) {
                    String relative = properties.getProperty("tile." + index + ".path");
                    Path destination = root.resolve(relative).normalize();
                    if (!destination.startsWith(root)) throw new IOException("Unsafe bundled tile path");
                    Files.createDirectories(destination.getParent());
                    copyResource(resourceRoot, relative, destination);
                }
            }
            OfflineTerrain loaded = OfflineTerrain.load(manifest);
            String expectedId = noaa ? "noaa-etopo-2022-surface-15s" : "copernicus-dem-glo30-2021";
            if (!expectedId.equals(loaded.manifest().provenance().id())) throw new IOException("Proof dataset identity mismatch");
            if (!noaa && !COPERNICUS_FINGERPRINT.equals(loaded.manifest().fingerprint())) throw new IOException("Unaccepted Copernicus manifest fingerprint");
            LOADED.put(dataset, loaded);
            return loaded;
        } catch (IOException e) { throw new IllegalStateException("Cannot load verified Earth proof pack " + dataset, e); }
    }
    private static void copyResource(String root, String relative, Path destination) throws IOException {
        try (var input = ProofTerrain.class.getResourceAsStream(root + relative)) {
            if (input == null) throw new IOException("Missing proof resource " + relative);
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
