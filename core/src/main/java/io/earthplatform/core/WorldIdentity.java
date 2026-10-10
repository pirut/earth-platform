package io.earthplatform.core;

import java.util.Objects;

/** Pin all generation inputs per world; changing these requires an explicit migration. */
public record WorldIdentity(long seed, String generatorId, String generatorVersion, String datasetVersion, String blockPaletteVersion) {
    public WorldIdentity {
        validate(generatorId); validate(generatorVersion); validate(datasetVersion); validate(blockPaletteVersion);
    }
    private static void validate(String value) {
        Objects.requireNonNull(value);
        if (value.isBlank() || value.length() > 1024) throw new IllegalArgumentException("Invalid identity component");
    }
}
