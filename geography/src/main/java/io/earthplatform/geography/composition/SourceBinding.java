package io.earthplatform.geography.composition;

import io.earthplatform.geography.*;
import java.util.Objects;

/** Trusted immutable source registration: exact evidence, data identity, provenance and safe coverage must describe its provider. */
public record SourceBinding(String id,String sourceFingerprint,DatasetProvenance provenance,DatumEvidence datumEvidence,
                            SafeCoverage coverage,int priority,ElevationProvider provider) {
    public SourceBinding {
        if(id==null||id.isBlank()||id.length()>256)throw new IllegalArgumentException("Stable source ID required");
        if(sourceFingerprint==null||!sourceFingerprint.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Pinned source fingerprint required");
        Objects.requireNonNull(provenance);Objects.requireNonNull(datumEvidence);Objects.requireNonNull(coverage);Objects.requireNonNull(provider);
    }
}
