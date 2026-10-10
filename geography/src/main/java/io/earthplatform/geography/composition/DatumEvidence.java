package io.earthplatform.geography.composition;

import java.net.URI;
import java.util.Objects;

/** Curator-verified source metadata declaration. A hash pins evidence; it does not independently prove a provider truthful. */
public record DatumEvidence(VerticalDatum datum, URI source, String sha256) {
    public DatumEvidence {
        Objects.requireNonNull(datum, "Unknown vertical datum"); Objects.requireNonNull(source,"Missing datum evidence source");
        if (!source.isAbsolute() || source.getUserInfo()!=null || source.getFragment()!=null)
            throw new IllegalArgumentException("Absolute public evidence URI required");
        if (sha256==null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Pinned datum evidence SHA-256 required");
    }
}
