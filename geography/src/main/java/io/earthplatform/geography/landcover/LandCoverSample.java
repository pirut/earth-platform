package io.earthplatform.geography.landcover;

import java.util.Objects;

/** A classification with explicit missing-coverage/no-data status. No elevation or water-level claim. */
public record LandCoverSample(LandCoverClass classification, Status status, String regionId) {
    public enum Status { MAPPED, SOURCE_NO_DATA, OUTSIDE_COVERAGE }
    public LandCoverSample {
        Objects.requireNonNull(classification);
        Objects.requireNonNull(status);
        Objects.requireNonNull(regionId);
        if ((status == Status.MAPPED) == (classification == LandCoverClass.UNKNOWN))
            throw new IllegalArgumentException("Classification and coverage status disagree");
        if ((status == Status.OUTSIDE_COVERAGE) != regionId.isEmpty())
            throw new IllegalArgumentException("Only an outside-coverage result has no region identity");
    }
    public static LandCoverSample outsideCoverage() {
        return new LandCoverSample(LandCoverClass.UNKNOWN, Status.OUTSIDE_COVERAGE, "");
    }
}
