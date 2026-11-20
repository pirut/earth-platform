package io.earthplatform.minecraft.cubic.earth;

import java.io.IOException;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What kind of trees make up each place's forest: how much of it is needleleaf (pine, spruce,
 * fir, larch) and how much of its broadleaf is evergreen (rainforest, laurel and holm oak) rather
 * than deciduous (oak, beech, maple, birch and the dry tropics' monsoon woods). WorldCover maps
 * only that trees stand somewhere; climate alone cannot tell the Black Forest's spruce from the
 * beech woods beside it, nor the pinewoods of the American South from its oaks.
 *
 * <p>Baked from the forest type layer of the Copernicus Global Land Service's 100 m land cover
 * (collection 3, 2019) to a grid of a 24th of a degree by {@code minecraft/scripts/forests.py}.
 * Each cell is 0 where no forest is mapped within about 20 km, else {@code 16 + 16n + e} with the
 * needleleaf share {@code n} out of 14 and the broadleaf's evergreen share {@code e} out of 15.
 */
public final class ForestTypes {
    /** The dataset pin of worlds whose forests follow this map. */
    public static final String ID = "cgls-lc100-forest-type-2019-v1";
    public static final String ATTRIBUTION = "Forest types: Copernicus Global Land Service, Land Cover 100 m collection 3, epoch 2019, "
            + "Buchhorn et al. 2020 (CC BY 4.0)";

    private static final Logger LOG = LoggerFactory.getLogger(ForestTypes.class);
    private static volatile ForestTypes instance;
    private static volatile boolean failed;

    private final EarthMaps.Grid grid;

    private ForestTypes(EarthMaps.Grid grid) { this.grid = grid; }

    /** The map, read once from the mod's resources; null if it could not be read. */
    @Nullable public static ForestTypes get() {
        ForestTypes map = instance;
        if (map != null || failed) return map;
        synchronized (ForestTypes.class) {
            if (instance == null && !failed) {
                try {
                    instance = new ForestTypes(EarthMaps.Grid.load("/earthplatform/forests.png"));
                } catch (IOException | RuntimeException e) {
                    failed = true;
                    LOG.warn("Forest type map unavailable; forests follow climate alone", e);
                }
            }
            return instance;
        }
    }

    /**
     * The forest's make-up at a place, blended between the four cells round it that have forest:
     * {@code out[0]} its needleleaf share and {@code out[1]} its broadleaf's evergreen share, each
     * 0 to 1. False, leaving {@code out} alone, where none of the four has forest.
     */
    public boolean at(double latitude, double longitude, double[] out) {
        int width = grid.width(), height = grid.height();
        double perDegree = width / 360.0, fx = (longitude + 180) * perDegree - 0.5, fy = (90 - latitude) * perDegree - 0.5;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        double tx = fx - x0, ty = fy - y0, weights = 0, needle = 0, evergreen = 0;
        for (int k = 0; k < 4; k++) {
            int dx = k & 1, dy = k >> 1;
            int code = grid.cell(Math.floorMod(x0 + dx, width), Math.min(height - 1, Math.max(0, y0 + dy)));
            if (code < 16) continue;
            double w = (dx == 0 ? 1 - tx : tx) * (dy == 0 ? 1 - ty : ty);
            needle += w * needle(code);
            evergreen += w * evergreen(code);
            weights += w;
        }
        if (weights <= 1e-9) return false;
        out[0] = needle / weights;
        out[1] = evergreen / weights;
        return true;
    }

    /** The needleleaf share a cell's code carries, 0 to 1. */
    static double needle(int code) { return ((code - 16) >> 4) / 14.0; }

    /** The evergreen share of the broadleaf trees a cell's code carries, 0 to 1. */
    static double evergreen(int code) { return ((code - 16) & 15) / 15.0; }
}
