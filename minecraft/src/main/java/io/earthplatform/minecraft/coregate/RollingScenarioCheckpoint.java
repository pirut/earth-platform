package io.earthplatform.minecraft.coregate;

/**
 * Immutable diagnostic request stamp, shared by the server's pending request and the
 * client's pending screenshot. It grants no gameplay, storage or publication authority.
 * Owners must retain this exact request until acknowledgement or failure; a newer current
 * publication cannot silently substitute for the pair requested by this checkpoint.
 */
public record RollingScenarioCheckpoint(long epoch, long publication, long resources,
        long coverage, String digest, String label) {
    public RollingScenarioCheckpoint {
        if (epoch <= 0 || publication <= 0 || resources <= 0 || coverage <= 0)
            throw new IllegalArgumentException("Positive exact checkpoint generations required");
        if (digest == null || !digest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact checkpoint pair SHA-256 required");
        if (label == null || !label.matches("[a-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Safe bounded nonempty checkpoint label required");
    }

    /** Compare every request component; neither currentness nor matching label alone suffices. */
    public boolean matches(long epoch, long publication, long resources, long coverage,
            String digest, String label) {
        return this.epoch == epoch && this.publication == publication && this.resources == resources
                && this.coverage == coverage && this.digest.equals(digest) && this.label.equals(label);
    }
}
