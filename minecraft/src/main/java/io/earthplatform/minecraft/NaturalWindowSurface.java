package io.earthplatform.minecraft;

import io.earthplatform.geography.*;
import io.earthplatform.geography.landcover.*;
import io.earthplatform.geography.variety.NaturalSurfaceRelief;
import io.earthplatform.geography.variety.AlpineSurfaceRelief;
import java.nio.charset.StandardCharsets;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Source heights, measured classifications and explicitly heuristic rendering decisions stay separate. */
public final class NaturalWindowSurface {
    public static final String VERSION = "natural-window-surface-v1";
    private final OfflineTerrain terrain;
    private final EquirectangularProjection projection;
    private final OfflineLandCover cover;
    private final NaturalSurfaceRelief relief = NaturalSurfaceRelief.ridgePreview();
    private final String identity;
    private final AlpineSurfaceRelief alpine;
    public NaturalWindowSurface(OfflineTerrain terrain, EquirectangularProjection projection) {
        this(terrain, projection, NaturalSurfaceRelief.RIDGE_PREVIEW);
    }
    public NaturalWindowSurface(OfflineTerrain terrain, EquirectangularProjection projection, String policy) {
        alpine = AlpineSurfaceRelief.RIDGE_PREVIEW.equals(policy) ? AlpineSurfaceRelief.ridgePreview() : null;
        this.terrain = terrain; this.projection = projection; cover = ProofLandCover.get();
        identity = WorldManifest.sha256(((alpine == null ? VERSION : "alpine-window-surface-v2") + "\n" + (alpine == null ? relief.identity() : alpine.identity()) + "\n" + cover.identity() + "\n"
                + LandCoverSurfacePolicy.DEFAULT.identity() + "\nslope=source-central-difference-8-projected-m-ground-corrected"
                + (alpine == null ? "\nshelter=none" : "\nshelter=clamp((mean-cardinal-source8m-minus-centre)/2,0,1)") + ";palette=snow-top-stone-andesite-tuff-cap-v1").getBytes(StandardCharsets.UTF_8));
    }
    public String identity() { return identity; }
    public Column sample(long x, long z) {
        GeoPoint point = projection.blockCentre(x, z);
        double source = terrain.elevationMetres(point);
        double east = source(x + 8,z), west = source(x - 8,z), south = source(x,z + 8), north = source(x,z - 8);
        double gx = (east - west) / 16
                * StrictMath.cos(StrictMath.toRadians(projection.standardParallel())) / StrictMath.cos(StrictMath.toRadians(point.latitude()));
        double gz = (south - north) / 16;
        double slope = StrictMath.hypot(gx, gz);
        LandCoverSample measured = cover.sample(point);
        if (alpine != null) {
            double shelter = Math.clamp(((east + west + south + north) / 4 - source) / 2, 0, 1);
            var sample = alpine.sample(point, source, terrain.manifest().provenance().resolutionArcSeconds(), gx, gz, measured, shelter);
            return new Column(source, sample.proceduralDeltaMetres(), sample.generatedMetres(),
                    LandCoverSurfacePolicy.DEFAULT.evaluate(measured, slope, x, z, shelter));
        }
        var sample = relief.sample(point, source, terrain.manifest().provenance().resolutionArcSeconds(), slope, measured);
        return new Column(source, sample.proceduralDeltaMetres(), sample.generatedMetres(),
                LandCoverSurfacePolicy.DEFAULT.evaluate(measured, slope, x, z));
    }
    private double source(long x, long z) { return terrain.elevationMetres(projection.blockCentre(x, z)); }
    public record Column(double sourceMetres, double proceduralDeltaMetres, double generatedMetres,
                         LandCoverSurfacePolicy.Selection appearance) {
        public long surfaceY() { return (long) StrictMath.floor(generatedMetres); }
    }
    public static BlockState map(int id, Column column) {
        if (id == 0) return Blocks.AIR.defaultBlockState();
        if (id == 4) return Blocks.WATER.defaultBlockState();
        if (id == 1) return Blocks.STONE.defaultBlockState();
        if (id == 5) return Blocks.GRAVEL.defaultBlockState();
        if (id != 2 && id != 3) throw new IllegalArgumentException("Unknown material " + id);
        var selection = column.appearance();
        return switch (selection.appearance()) {
            case SNOW_ICE -> (id == 3 ? Blocks.SNOW_BLOCK : rock(selection.materialVariant())).defaultBlockState();
            case VEGETATED_GROUND -> (id == 3 ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState();
            case STEEP_ROCK, BARE_MINERAL -> rock(selection.materialVariant()).defaultBlockState();
            // Neither a water label nor unavailable classification invents grass, hydrology, buildings or snow.
            case UNKNOWN, UNRESOLVED_WATER, BUILT_UP_HINT -> Blocks.STONE.defaultBlockState();
        };
    }
    private static net.minecraft.world.level.block.Block rock(int variant) {
        return switch (variant) { case 1 -> Blocks.ANDESITE; case 2 -> Blocks.TUFF; default -> Blocks.STONE; };
    }
}
