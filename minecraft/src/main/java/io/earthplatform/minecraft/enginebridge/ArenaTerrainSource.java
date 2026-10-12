package io.earthplatform.minecraft.enginebridge;

import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The complete, immutable Monday arena source. No Minecraft, client, renderer or
 * GL state participates in generation. The far heightfield must use baseSurfaceY
 * and baseArgb; materialAt includes the genuine three-dimensional arena features.
 * There is deliberately no callback, seed-dependent algorithm or arbitrary layout.
 */
public final class ArenaTerrainSource {
    public static final int AIR = 0, STONE = 1, DIORITE = 2;
    public static final int MIN_SURFACE_Y = 144, MAX_SURFACE_Y = 208;
    public static final ArenaTerrainSource FIXED = new ArenaTerrainSource(ArenaDefinition.FIXED);
    private static final String POLICY = "earth-arena-terrain-v2-three-smooth-hills"
            + ":height=144+floor(max(64*west,52*north,44*east)*clearing)"
            + ":bump=(1-(dx/rx)^2-(dz/rz)^2)^2-inside-ellipse-else-zero"
            + ":west=(-144,48;176,176);north=(96,-160;192,192);east=(176,144;160,208)"
            + ":clearing=center(16,16),flat-through-radius28,smoothstep-to-radius112"
            + ":evaluation=bounded-long-squares,double-division,strictmath-sqrt,java-strict-fp"
            + ":outside-supports=144"
            + ":column=solid-through-height;stone-unless-height>=184-then-diorite"
            + ":chamber=xz[16,28),y[136,141);shaft=xz[20,24),y[141,160)"
            + ":arch=x[4,12),z[4,8),roof-y[151,153),supports-x[4,6)+[10,12),y[145,151)"
            + ":arena-border-top=diorite;features-only-in-editable-volume"
            + ":palette=air0,stone1:ff808080,diorite2:ffdedede;complete-all-long-xz";
    private final ArenaDefinition definition;
    private final String identity, canonicalIdentity;

    public ArenaTerrainSource(ArenaDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        if (definition != ArenaDefinition.FIXED)
            throw new IllegalArgumentException("Only the fixed proved arena source is supported");
        canonicalIdentity = POLICY + ":definition=" + definition.identity();
        try {
            identity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalIdentity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public ArenaDefinition definition() { return definition; }
    public String identity() { return identity; }
    /** Auditable full policy bytes; identity() stays bounded for existing save and wire fields. */
    public String canonicalIdentity() { return canonicalIdentity; }

    /** Surface Y is the highest solid block of the base heightfield, not its upper face. */
    public int baseSurfaceY(long x, long z) {
        // Reject the compact support rectangle before ANY subtraction. All remaining
        // long differences/squares are small and exact, including at long extrema.
        if (x <= -320 || x >= 336 || z <= -352 || z >= 352) return MIN_SURFACE_Y;
        double relief = Math.max(64 * bump(x, z, -144, 48, 176, 176),
                Math.max(52 * bump(x, z, 96, -160, 192, 192),
                        44 * bump(x, z, 176, 144, 160, 208)));
        if (relief == 0) return MIN_SURFACE_Y;
        long dx = x - 16, dz = z - 16, radiusSquared = dx * dx + dz * dz;
        if (radiusSquared <= 28L * 28) return MIN_SURFACE_Y;
        double blend = 1;
        if (radiusSquared < 112L * 112) {
            double t = (StrictMath.sqrt(radiusSquared) - 28) / 84;
            blend = t * t * (3 - 2 * t);
        }
        return MIN_SURFACE_Y + (int) StrictMath.floor(relief * blend);
    }

    private static double bump(long x, long z, int centerX, int centerZ, int radiusX, int radiusZ) {
        // The caller bounds x/z before subtraction; squared integer numerators are exact.
        long dx = x - centerX, dz = z - centerZ;
        if (dx <= -radiusX || dx >= radiusX || dz <= -radiusZ || dz >= radiusZ) return 0;
        double radiusSquared = (double) (dx * dx) / ((long) radiusX * radiusX)
                + (double) (dz * dz) / ((long) radiusZ * radiusZ);
        if (radiusSquared >= 1) return 0;
        double shoulder = 1 - radiusSquared;
        return shoulder * shoulder;
    }
    public int surfaceY(long x, long z) { return baseSurfaceY(x, z); }

    /** Every solid voxel in a base column has the same opaque material, including cliff faces. */
    public int baseMaterialAt(long x, long y, long z) {
        int surface = baseSurfaceY(x, z);
        return y > surface ? AIR : surface >= 184 ? DIORITE : STONE;
    }
    public int baseArgb(long x, long z) { return argb(baseMaterialAt(x, baseSurfaceY(x, z), z)); }
    public int surfaceArgb(long x, long z) { return baseArgb(x, z); }

    public int materialAt(long x, long y, long z) {
        if (definition.editable(x, y, z)) {
            boolean chamber = x >= 16 && x < 28 && z >= 16 && z < 28 && y >= 136 && y < 141;
            boolean shaft = x >= 20 && x < 24 && z >= 20 && z < 24 && y >= 141 && y < 160;
            if (chamber || shaft) return AIR;
            boolean archFootprint = x >= 4 && x < 12 && z >= 4 && z < 8;
            boolean roof = archFootprint && y >= 151 && y < 153;
            boolean support = archFootprint && (x < 6 || x >= 10) && y >= 145 && y < 151;
            boolean border = y == baseSurfaceY(x, z)
                    && (x == definition.minX() || x == definition.maxXExclusive() - 1
                        || z == definition.minZ() || z == definition.maxZExclusive() - 1);
            if (roof || support || border) return DIORITE;
        }
        return baseMaterialAt(x, y, z);
    }

    public int argbAt(long x, long y, long z) { return argb(materialAt(x, y, z)); }
    public static int argb(int material) {
        return switch (material) {
            case AIR -> 0;
            case STONE -> 0xff808080;
            case DIORITE -> 0xffdedede;
            default -> throw new IllegalArgumentException("Unsupported arena source material");
        };
    }

    /** The overflow-safe equation is defined for every native metre in every nonempty footprint. */
    public boolean knownFootprint(long minX, long minZ, long maxXExclusive, long maxZExclusive) {
        return minX < maxXExclusive && minZ < maxZExclusive;
    }
}
