package io.earthplatform.minecraft.coregate;

/**
 * Identity of geometry already installed as one near/far pair. Candidate protocol state
 * never mutates this value. The host fence surrounds all access; this grants no render,
 * collision, readiness, or retained-presentation authority of its own.
 */
final class RollingPublishedPairIdentity {
    private String digest;

    String digest() { return digest; }

    /** Called only after both exact native and far installations have succeeded. */
    void publish(String exactDigest) {
        if (exactDigest == null || !exactDigest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact installed pair digest required");
        digest = exactDigest;
    }

    void revoke() { digest = null; }
}
