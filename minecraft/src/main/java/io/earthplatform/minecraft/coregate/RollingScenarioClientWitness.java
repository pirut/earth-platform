package io.earthplatform.minecraft.coregate;

/**
 * Immutable diagnostic witness of the client's exact committed pair. Receipt presence is
 * significant: reload sequence zero is a known value, never a substitute for no receipt.
 * This witness grants no gameplay/publication authority and cannot acknowledge a screenshot.
 * The server must compare it with its current pair again each time readiness is required.
 */
record RollingScenarioClientWitness(long epoch, long publication, long resources,
        long coverage, long reloadSequence, String digest) {
    RollingScenarioClientWitness {
        if (epoch <= 0 || publication <= 0 || resources <= 0 || coverage <= 0 || reloadSequence < 0)
            throw new IllegalArgumentException("Exact positive pair/coverage and known reload sequence required");
        if (digest == null || !digest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact client pair SHA-256 required");
    }

    static boolean current(RollingScenarioClientWitness witness, long epoch, long publication,
            long resources, String digest) {
        return witness != null && witness.epoch == epoch && witness.publication == publication
                && witness.resources == resources && witness.digest.equals(digest);
    }
}
