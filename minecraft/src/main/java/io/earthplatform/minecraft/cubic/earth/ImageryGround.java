package io.earthplatform.minecraft.cubic.earth;

import io.earthplatform.geography.globe.GroundPalette;
import io.earthplatform.geography.globe.ImagerySource;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.globe.WorldCoverSource;
import io.github.pirut.chunks3.gen.SurfaceGenerator.Surface;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Makes the Earth's ground look like the place does from orbit: Sentinel-2 imagery picks the
 * vanilla block for bare rock, scree and sand by colour, so pale granite is pale and red dunes are
 * red, shows soil where grass and scrub look dry and bare, and thins woods that look open. It only
 * touches the generic rock, gravel, coarse dirt and sand picks and the grass of open land and
 * woods; badlands bands, calcite, snow, wetlands and every biome and tree kind keep the land cover
 * and climate rules. Pixels too dark to read (shade, dense forest) change nothing.
 */
final class ImageryGround {
    private static final BlockState STONE = Blocks.STONE.defaultBlockState(), ANDESITE = Blocks.ANDESITE.defaultBlockState(),
            GRAVEL = Blocks.GRAVEL.defaultBlockState(), COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState(),
            SAND = Blocks.SAND.defaultBlockState(), RED_SAND = Blocks.RED_SAND.defaultBlockState(), GRASS = Blocks.GRASS_BLOCK.defaultBlockState(),
            DIRT = Blocks.DIRT.defaultBlockState(), SANDSTONE = Blocks.SANDSTONE.defaultBlockState(),
            RED_SANDSTONE = Blocks.RED_SANDSTONE.defaultBlockState(), TERRACOTTA = Blocks.TERRACOTTA.defaultBlockState();
    private static final BlockState[] BLOCKS = new BlockState[GroundPalette.Ground.values().length];
    static {
        for (GroundPalette.Ground ground : GroundPalette.Ground.values())
            BLOCKS[ground.ordinal()] = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(ground.id())).defaultBlockState();
    }

    private final ImagerySource imagery;
    private final MercatorGrid grid;
    private final double blocksPerPixel;
    private final SimplexNoise warpX, warpZ, blend;

    ImageryGround(ImagerySource imagery, MercatorGrid grid, long seed) {
        this.imagery = imagery;
        this.grid = grid;
        this.blocksPerPixel = grid.blocksPerPixel(ImagerySource.MAX_ZOOM);
        LegacyRandomSource random = new LegacyRandomSource(seed ^ 0x494D41474552L);
        warpX = new SimplexNoise(random); warpZ = new SimplexNoise(random); blend = new SimplexNoise(random);
    }

    /** Imagery zoom for voxels of a size: about one pixel per voxel, full detail for anything smaller. */
    int zoom(int voxel) {
        int coarser = (int) Math.floor(Math.log(voxel / blocksPerPixel) / Math.log(2));
        return Math.clamp(ImagerySource.MAX_ZOOM - Math.max(0, coarser), 0, ImagerySource.MAX_ZOOM);
    }

    /**
     * The surface a column gets once imagery has had its say.
     *
     * @param code  the land cover class the surface came from
     * @param jitter a value in [0, 1) fixed for the column
     */
    Surface apply(Surface surface, int code, int x, int z, ClimateZone zone, double steep, int voxel, double jitter) {
        // Where two blocks share a colour, each holds broad stretches, hundreds of blocks across, that
        // meet in drifts a few blocks wide, rather than mottling the ground block by block.
        double roll = Math.clamp(0.5 + 0.9 * blend.getValue(x / 160.0, z / 160.0) + 0.25 * blend.getValue(x / 7.0 + 31.7, z / 7.0) + 0.1 * (jitter - 0.5), 0, 0.999);
        BlockState top = surface.top();
        boolean bare = top == STONE || top == ANDESITE || top == GRAVEL || top == COARSE_DIRT || top == SAND || top == RED_SAND;
        boolean open = top == GRASS && (code == WorldCoverSource.GRASSLAND || code == WorldCoverSource.CROPLAND || code == WorldCoverSource.SHRUBLAND);
        boolean wooded = top == GRASS && code == WorldCoverSource.TREE_COVER && surface.trees() > 0;
        if (!bare && !open && !wooded) return surface;
        GroundPalette.Reading reading = reading(x, z, voxel);
        if (reading == null || reading.shadow()) return surface;
        if (bare) {
            // Plants seen in the pixel belong to its neighbours; the rock under them says nothing.
            if (reading.green()) return surface;
            // Red sands and layered rock colours belong to dry lands, hot or cold (the Sahara, the Colorado Plateau).
            boolean arid = zone == ClimateZone.HOT_DESERT || zone == ClimateZone.HOT_STEPPE || zone == ClimateZone.TROPICAL_DRY
                    || zone == ClimateZone.COLD_DESERT || zone == ClimateZone.COLD_STEPPE;
            // Rock stays rock and loose ground loose, as land cover and slope chose; imagery picks which.
            boolean rock = top == STONE || top == ANDESITE;
            // Grey on sand is a road or a roof, not the ground; left alone it never draws lines across a desert.
            if (reading.grey() && (top == SAND || top == RED_SAND)) return surface;
            GroundPalette.Ground[] set = rock ? (arid ? GroundPalette.DESERT_ROCK : GroundPalette.ROCK) : arid ? GroundPalette.DESERT_LOOSE : GroundPalette.LOOSE;
            BlockState matched = BLOCKS[GroundPalette.match(reading, set, roll).ordinal()];
            return matched == top ? surface : with(surface, matched, surface.trees(), surface.grass(), surface.flowers());
        }
        double vegetation = reading.vegetation();
        if (wooded) {
            // Woods as thick as they look: half the usual trees where the canopy shows soil, more where it is lush.
            int trees = (int) Math.round(Math.min(100, surface.trees() * (0.5 + 0.7 * vegetation)));
            return trees == surface.trees() ? surface : with(surface, top, trees, surface.grass(), surface.flowers());
        }
        // Open land that looks dry and bare shows its soil, in patches as thick as the plants are thin.
        // Grey pixels there are roads, roofs and car parks, which stay grass rather than drawing lines.
        if (vegetation < 0.25 && reading.lightness() > 42 && !reading.grey() && roll > vegetation * 3) {
            BlockState soil = BLOCKS[GroundPalette.match(reading, GroundPalette.SOIL, roll).ordinal()];
            return with(surface, soil, 0, 0, 0);
        }
        return surface;
    }

    /**
     * What imagery shows at a column, read at a zoom fit for the voxel size and blended between
     * pixels, or null where there is none. Up close the read point wanders by about half a pixel,
     * so the pixel grid's straight edges curve.
     */
    GroundPalette.Reading reading(int x, int z, int voxel) {
        int zoom = zoom(voxel);
        double px = grid.pixelX(x + 0.5, zoom), py = grid.pixelY(z + 0.5, zoom);
        if (zoom == ImagerySource.MAX_ZOOM && voxel < blocksPerPixel) {
            double nx = x / (2.2 * blocksPerPixel), nz = z / (2.2 * blocksPerPixel);
            px += 0.6 * (warpX.getValue(nx, nz) + 0.5 * warpX.getValue(3 * nx, 3 * nz));
            py += 0.6 * (warpZ.getValue(nx, nz) + 0.5 * warpZ.getValue(3 * nx, 3 * nz));
        }
        double[] colour = new double[3];
        if (!imagery.bilinear(px, py, zoom, colour)) return null;
        return GroundPalette.read(colour);
    }

    private static Surface with(Surface s, BlockState top, int trees, int grass, int flowers) {
        return new Surface(s.biome(), top, under(top, s.under()), trees, s.spruce(), s.birch(), grass, flowers, s.ferns(), s.broadleaf());
    }

    /** The blocks under a top: sandstone under sand, dirt under soils, terracotta under terracotta, else what was there. */
    private static BlockState under(BlockState top, BlockState usual) {
        if (top == SAND) return SANDSTONE;
        if (top == RED_SAND) return RED_SANDSTONE;
        if (top == DIRT || top == COARSE_DIRT || top == Blocks.PACKED_MUD.defaultBlockState()) return DIRT;
        if (top.getBlock().getDescriptionId().endsWith("terracotta")) return TERRACOTTA;
        return usual == SANDSTONE || usual == RED_SANDSTONE || usual == DIRT ? STONE : usual;
    }
}
