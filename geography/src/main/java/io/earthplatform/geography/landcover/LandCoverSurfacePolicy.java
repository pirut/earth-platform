package io.earthplatform.geography.landcover;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Cosmetic interpretation of verified land-cover labels and DEM-derived slope.
 * This class changes materials only: it has no elevation, water-level, tree or world-edit API.
 * Coordinates are integer projected metres. Shelter is an explicit optional heuristic, not a measured climate layer.
 */
public final class LandCoverSurfacePolicy {
    public static final String VERSION = "worldcover-slope-appearance-v1";
    public static final LandCoverSurfacePolicy DEFAULT = new LandCoverSurfacePolicy(20221009L);
    public static final double GENTLE_SNOW_SLOPE = 0.65;
    public static final double EXPOSED_ROCK_SLOPE = 1.35;
    public static final double MAX_SHELTER_SLOPE_BIAS = 0.35;
    private static final double VEGETATION_ROCK_SLOPE = 1.10;
    private static final double BARE_ROCK_SLOPE = 0.90;
    private final long seed;
    private final String identity;

    public enum Appearance {
        UNKNOWN, UNRESOLVED_WATER, VEGETATED_GROUND, BARE_MINERAL,
        SNOW_ICE, STEEP_ROCK, BUILT_UP_HINT
    }
    public enum Basis {
        UNAVAILABLE_CLASSIFICATION, WATER_LABEL_WITHOUT_LEVEL,
        CLASS_BASED_COSMETIC_MAPPING, SLOPE_COSMETIC_MAPPING, PROCEDURAL_SNOW_ROCK_TRANSITION
    }
    /**
     * source is retained unchanged: appearance is never substituted for measured classification.
     * materialVariant 0..2 is a cosmetic palette hint, not geology, snow depth, ice type or tree species.
     */
    public record Selection(LandCoverSample source, double slopeRiseOverRun, double shelter,
                            Appearance appearance, Basis basis, String policyIdentity, int materialVariant) {
        public Selection {
            Objects.requireNonNull(source); Objects.requireNonNull(appearance);
            Objects.requireNonNull(basis); Objects.requireNonNull(policyIdentity);
            validate(slopeRiseOverRun, shelter);
            if (materialVariant < 0 || materialVariant > 2) throw new IllegalArgumentException("Invalid material variant");
            if (appearance == Appearance.SNOW_ICE && source.classification() != LandCoverClass.SNOW_AND_ICE)
                throw new IllegalArgumentException("Snow appearance requires a source snow/ice classification");
            if ((appearance == Appearance.UNKNOWN) != (source.classification() == LandCoverClass.UNKNOWN))
                throw new IllegalArgumentException("Unknown source classification must stay visibly unknown");
            if ((appearance == Appearance.UNRESOLVED_WATER) != (source.classification() == LandCoverClass.PERMANENT_WATER))
                throw new IllegalArgumentException("Water labels must stay unresolved without water-level data");
        }
    }

    public LandCoverSurfacePolicy(long seed) {
        this.seed = seed;
        // Include every fixed numerical rule as well as the semantic version and seed.
        String contract = VERSION + "\nseed=" + seed + "\ngentle=" + Double.toHexString(GENTLE_SNOW_SLOPE)
                + "\nexposed=" + Double.toHexString(EXPOSED_ROCK_SLOPE)
                + "\nshelter=" + Double.toHexString(MAX_SHELTER_SLOPE_BIAS)
                + "\nvegetation=" + Double.toHexString(VEGETATION_ROCK_SLOPE)
                + "\nbare=" + Double.toHexString(BARE_ROCK_SLOPE)
                + "\nnoise=smoothstep-value-24m-8m-0.65-0.35;variant-thresholds=0.30,0.72"
                + "\nunknown=explicit;water=unresolved;built=hint;no-height-or-placement";
        try {
            identity = "landcover-surface-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(contract.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public long seed() { return seed; }
    public String identity() { return identity; }

    /** Convenience for non-spatial inspection. Renderers should supply coordinates for coherent material patches. */
    public Selection evaluate(LandCoverSample source, double slopeRiseOverRun) {
        return evaluate(source, slopeRiseOverRun, 0, 0, 0);
    }
    /** No shelter evidence assumed. */
    public Selection evaluate(LandCoverSample source, double slopeRiseOverRun, long blockX, long blockZ) {
        return evaluate(source, slopeRiseOverRun, blockX, blockZ, 0);
    }
    /**
     * slopeRiseOverRun = sqrt((dh/dx)^2+(dh/dz)^2), where heights and horizontal distances are metres.
     * shelter 0..1 is optional caller-derived curvature/exposure evidence; 0 means no shelter assumption.
     * Gentle mapped snow remains snow; exposed steep mapped snow becomes rock; the middle band varies coherently.
     */
    public Selection evaluate(LandCoverSample source, double slopeRiseOverRun, long blockX, long blockZ, double shelter) {
        Objects.requireNonNull(source);
        validate(slopeRiseOverRun, shelter);
        if (slopeRiseOverRun == 0) slopeRiseOverRun = 0;
        if (shelter == 0) shelter = 0;
        if (source.classification() == LandCoverClass.UNKNOWN)
            return selection(source,slopeRiseOverRun,shelter,Appearance.UNKNOWN,Basis.UNAVAILABLE_CLASSIFICATION,0);
        if (source.classification() == LandCoverClass.PERMANENT_WATER)
            return selection(source,slopeRiseOverRun,shelter,Appearance.UNRESOLVED_WATER,Basis.WATER_LABEL_WITHOUT_LEVEL,0);
        double pattern = materialPattern(blockX, blockZ);
        int variant = pattern < 0.30 ? 1 : pattern > 0.72 ? 2 : 0;
        if (source.classification() == LandCoverClass.BUILT_UP)
            return selection(source,slopeRiseOverRun,shelter,Appearance.BUILT_UP_HINT,Basis.CLASS_BASED_COSMETIC_MAPPING,variant);
        if (source.classification() == LandCoverClass.SNOW_AND_ICE) {
            double effectiveSlope = Math.max(0, slopeRiseOverRun - shelter * MAX_SHELTER_SLOPE_BIAS);
            if (effectiveSlope <= GENTLE_SNOW_SLOPE)
                return selection(source,slopeRiseOverRun,shelter,Appearance.SNOW_ICE,Basis.SLOPE_COSMETIC_MAPPING,variant);
            if (effectiveSlope >= EXPOSED_ROCK_SLOPE)
                return selection(source,slopeRiseOverRun,shelter,Appearance.STEEP_ROCK,Basis.SLOPE_COSMETIC_MAPPING,variant);
            double snowFraction = (EXPOSED_ROCK_SLOPE - effectiveSlope) / (EXPOSED_ROCK_SLOPE - GENTLE_SNOW_SLOPE);
            Appearance appearance = pattern < snowFraction ? Appearance.SNOW_ICE : Appearance.STEEP_ROCK;
            return selection(source,slopeRiseOverRun,shelter,appearance,Basis.PROCEDURAL_SNOW_ROCK_TRANSITION,variant);
        }
        boolean bare = source.classification() == LandCoverClass.BARE_SPARSE_VEGETATION;
        if (slopeRiseOverRun >= (bare ? BARE_ROCK_SLOPE : VEGETATION_ROCK_SLOPE))
            return selection(source,slopeRiseOverRun,shelter,Appearance.STEEP_ROCK,Basis.SLOPE_COSMETIC_MAPPING,variant);
        return selection(source,slopeRiseOverRun,shelter,bare ? Appearance.BARE_MINERAL : Appearance.VEGETATED_GROUND,
                Basis.CLASS_BASED_COSMETIC_MAPPING,variant);
    }
    private Selection selection(LandCoverSample source, double slope, double shelter,
                                Appearance appearance, Basis basis, int variant) {
        return new Selection(source,slope,shelter,appearance,basis,identity,variant);
    }
    private static void validate(double slope, double shelter) {
        if (!Double.isFinite(slope) || slope < 0 || !Double.isFinite(shelter) || shelter < 0 || shelter > 1)
            throw new IllegalArgumentException("Slope must be finite/nonnegative and shelter must be in [0,1]");
    }
    /** Smooth correlated [0,1) field used only for material selection, never source or terrain displacement. */
    private double materialPattern(long x, long z) {
        return 0.65 * valueNoise(x,z,24,seed) + 0.35 * valueNoise(x,z,8,seed ^ 0x6a09e667f3bcc909L);
    }
    private static double valueNoise(long x, long z, int spacing, long seed) {
        long ix = Math.floorDiv(x,spacing), iz = Math.floorDiv(z,spacing);
        double tx = smooth((Math.floorMod(x,spacing)+0.5)/spacing);
        double tz = smooth((Math.floorMod(z,spacing)+0.5)/spacing);
        double a = hashUnit(ix,iz,seed), b = hashUnit(ix+1,iz,seed);
        double c = hashUnit(ix,iz+1,seed), d = hashUnit(ix+1,iz+1,seed);
        return (a+(b-a)*tx)*(1-tz)+(c+(d-c)*tx)*tz;
    }
    private static double smooth(double t) { return t*t*(3-2*t); }
    private static double hashUnit(long x, long z, long seed) {
        long v = seed ^ (x * 0x9e3779b97f4a7c15L) ^ Long.rotateLeft(z * 0xd1b54a32d192ed03L,29);
        v = (v ^ (v >>> 30)) * 0xbf58476d1ce4e5b9L;
        v = (v ^ (v >>> 27)) * 0x94d049bb133111ebL;
        v ^= v >>> 31;
        return (v >>> 11) * 0x1.0p-53;
    }
}
