package io.earthplatform.geography.variety;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.WorldManifest;
import io.earthplatform.geography.landcover.LandCoverClass;
import io.earthplatform.geography.landcover.LandCoverSample;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Opt-in cosmetic breakup of interpolated mountain terraces. Never a measured elevation product. */
public final class NaturalSurfaceRelief {
    public static final String VERSION = "source-aware-mountain-relief-v1";
    public static final String DISABLED = "none";
    public static final String RIDGE_PREVIEW = "copernicus-ridge-natural-v1";
    public static final double MAXIMUM_DISPLACEMENT_METRES = 1.5;
    private final MicrodetailProfile profile;
    private final String identity;

    public NaturalSurfaceRelief(MicrodetailProfile profile) {
        this.profile = Objects.requireNonNull(profile);
        identity = WorldManifest.sha256((VERSION + "\n" + profile.fingerprint()
                + "\nsource-snow70-or-bare60; slope-smoothstep-0.15-to-0.65; preserve-source-at-zero")
                .getBytes(StandardCharsets.UTF_8));
    }
    /** Only the reviewed ridge patch is enabled, with explicit coast and Everest summit protection. */
    public static NaturalSurfaceRelief ridgePreview() {
        return new NaturalSurfaceRelief(new MicrodetailProfile(0x4541525448524944L, MAXIMUM_DISPLACEMENT_METRES,
                List.of(new MicrodetailProfile.Region(new GeoPoint(27.959879063586857, 86.91136144313555), 512, 128)),
                List.of(new MicrodetailProfile.PreserveZone(new GeoPoint(27.9881, 86.925), 256, 128))));
    }
    public String identity() { return identity; }
    public MicrodetailProfile profile() { return profile; }

    public Sample sample(GeoPoint point, double sourceMetres, double sourceResolutionArcSeconds,
            double sourceSlopeRiseOverRun, LandCoverSample cover) {
        Objects.requireNonNull(point); Objects.requireNonNull(cover);
        if (!Double.isFinite(sourceMetres) || !Double.isFinite(sourceResolutionArcSeconds) || sourceResolutionArcSeconds <= 0
                || !Double.isFinite(sourceSlopeRiseOverRun) || sourceSlopeRiseOverRun < 0)
            throw new IllegalArgumentException("Finite elevation, positive resolution and finite nonnegative source slope required");
        boolean eligible = cover.status() == LandCoverSample.Status.MAPPED
                && (cover.classification() == LandCoverClass.SNOW_AND_ICE || cover.classification() == LandCoverClass.BARE_SPARSE_VEGETATION);
        double slopeWeight = smooth((sourceSlopeRiseOverRun - 0.15) / 0.5);
        double delta = !eligible || slopeWeight == 0 ? 0 : slopeWeight
                * SurfaceMicrodetail.displacement(point, sourceMetres, sourceResolutionArcSeconds, profile);
        return new Sample(sourceMetres, delta, delta == 0 ? sourceMetres : sourceMetres + delta, identity);
    }
    private static double smooth(double t) { t = Math.max(0, Math.min(1, t)); return t * t * t * (t * (t * 6 - 15) + 10); }
    public record Sample(double sourceMetres, double proceduralDeltaMetres, double generatedMetres, String policyIdentity) {}
}
