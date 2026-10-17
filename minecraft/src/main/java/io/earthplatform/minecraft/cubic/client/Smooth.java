package io.earthplatform.minecraft.cubic.client;

import static io.earthplatform.minecraft.cubic.client.FarMesher.FIRST_WALL;
import static io.earthplatform.minecraft.cubic.client.FarMesher.GROUND;
import static io.earthplatform.minecraft.cubic.client.FarMesher.LOOK_MASK;
import static io.earthplatform.minecraft.cubic.client.FarMesher.POINTS;
import static io.earthplatform.minecraft.cubic.client.FarMesher.SMOOTH_TOP;
import static io.earthplatform.minecraft.cubic.client.FarMesher.SMOOTH_WALL;
import static io.earthplatform.minecraft.cubic.client.FarMesher.UNIT;
import static io.earthplatform.minecraft.cubic.client.FarMesher.WALL_DEPTH;
import static io.earthplatform.minecraft.cubic.client.FarMesher.groundRow;
import static io.earthplatform.minecraft.cubic.client.FarMesher.multiply;
import static io.earthplatform.minecraft.cubic.client.FarMesher.normalCode;
import static io.earthplatform.minecraft.cubic.client.FarMesher.side;
import static io.earthplatform.minecraft.cubic.client.FarMesher.top;

import io.earthplatform.minecraft.cubic.client.FarMesher.Job;
import io.earthplatform.minecraft.cubic.client.FarMesher.Side;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * A far column's ground as one smooth surface, the way distant land looks rather than as
 * stairs of voxels many pixels wide: a grid of points at the middle of every voxel column, at
 * the exact height of its top (so peaks keep their height) and in its colour, plus points on
 * the column's edges halfway to the next column's. Faces between the points slope and their
 * colours blend, so a hillside is a slope rather than lit treads and dark risers, and the
 * colours of forest, meadow and rock run into each other as a texture's would far off,
 * instead of a mosaic of voxel-sized squares. Neighbouring columns (diagonals included) work
 * out their shared edge points from the same voxel columns, so the surface has no seams;
 * walls hang from each edge for where the next column is drawn at another level.
 *
 * <p>Points are four ints each, after the column's faces (see {@link FarGpu}): height above
 * the column's base in 1/256 blocks, RGB, the ground's {@link FarMesher#normalCode} there, and on
 * textured levels, for points in the middle of a voxel column, one more than the id of that
 * voxel column's textured look (0 elsewhere), which the fragment shader reads to lay each voxel
 * column's own block texture over the slope.
 *
 * <p>{@link Builder} makes one per column that {@link FarMesher#smooth} picks, and
 * {@link FarGpu}'s shaders draw the faces and points it emits.
 */
final class Smooth {
    /** Voxel columns from -1 to 16 each way: this column's and the edge of each one around it. */
    private static final int CELLS = 18;
    final Builder builder;
    final Job job;
    final int voxel;
    /** Each voxel column's top in 1/256 blocks above this column's base, its colour, and whether it was known. */
    final int[] height = new int[CELLS * CELLS], rgb = new int[CELLS * CELLS];
    final BlockState[] state = new BlockState[CELLS * CELLS];
    @SuppressWarnings("unchecked") final Holder<Biome>[] biome = new Holder[CELLS * CELLS];
    final float[] pointHeight = new float[POINTS * POINTS];
    final int[] pointRgb = new int[POINTS * POINTS], pointNormal = new int[POINTS * POINTS];

    Smooth(Builder builder) {
        this.builder = builder;
        this.job = builder.job;
        this.voxel = builder.voxel;
        boolean[] known = new boolean[CELLS * CELLS];
        for (int cz = -1; cz <= 16; cz++) for (int cx = -1; cx <= 16; cx++) known[cell(cx, cz)] = read(cx, cz);
        // Columns not here yet: carry on level with the nearest voxel column of this one.
        for (int cz = -1; cz <= 16; cz++) for (int cx = -1; cx <= 16; cx++) {
            int at = cell(cx, cz);
            if (known[at]) continue;
            int from = cell(Mth.clamp(cx, 0, 15), Mth.clamp(cz, 0, 15));
            height[at] = height[from];
            rgb[at] = rgb[from];
        }
        for (int gz = 0; gz < POINTS; gz++) for (int gx = 0; gx < POINTS; gx++) point(gx, gz);
    }

    private static int cell(int cx, int cz) { return (cz + 1) * CELLS + cx + 1; }

    /** Reads one voxel column's top, here or in a column around; false when that column isn't here. */
    private boolean read(int cx, int cz) {
        LevelChunkSection[] sections;
        int bottom;
        char[] drops;
        boolean insideX = cx >= 0 && cx < 16, insideZ = cz >= 0 && cz < 16;
        if (insideX && insideZ) { sections = job.sections(); bottom = job.bottom(); drops = job.drops(); }
        else {
            Side side = insideX || insideZ ? (job.sides() == null ? null : job.sides()[side(cx, cz)])
                    : job.diagonals() == null ? null : job.diagonals()[(cz < 0 ? 0 : 2) + (cx < 0 ? 0 : 1)];
            if (side == null || side.sections() == null) return false;
            sections = side.sections(); bottom = side.bottom(); drops = side.drops();
        }
        // Textured levels draw trees as blocks on the ground; further out they are part of the surface.
        int x = cx & 15, z = cz & 15, row = job.textured() ? groundRow(sections, x, z) : top(sections, x, z, true);
        int at = cell(cx, cz);
        height[at] = (row + 1 + (bottom - job.bottom()) * 16) * voxel - drops[z * 16 + x];
        BlockState top = row < 0 ? GROUND : sections[row >> 4].getBlockState(x, row & 15, z);
        Holder<Biome> holder = row < 0 ? null : sections[row >> 4].getBiomes().get(x >> 2, (row & 15) >> 2, z >> 2);
        FarFaces.Face face = job.faces().face(top, Direction.UP);
        rgb[at] = multiply(face.rgb(), job.faces().tint(top, holder, face));
        state[at] = top;
        biome[at] = holder;
        return true;
    }

    /** Where grid point g lies across the column, in voxels: its edges, then the middle of each voxel column. */
    static float place(int g) { return Mth.clamp(g - 0.5F, 0.0F, 16.0F); }

    /** The voxel columns a grid point stands on along one axis: its own, or the two either side of an edge. */
    private static int first(int g) { return g == 0 ? -1 : g == POINTS - 1 ? 15 : g - 1; }
    private static int last(int g) { return g == 0 ? 0 : g == POINTS - 1 ? 16 : g - 1; }

    private void point(int gx, int gz) {
        float h = 0, r = 0, g = 0, b = 0, dx = 0, dz = 0;
        int n = 0;
        for (int cz = first(gz); cz <= last(gz); cz++) for (int cx = first(gx); cx <= last(gx); cx++) {
            int at = cell(cx, cz), colour = rgb[at];
            h += height[at];
            r += colour >> 16 & 255; g += colour >> 8 & 255; b += colour & 255;
            n++;
        }
        // The slope: straight across an edge between the two voxel columns there, else across the voxel column's neighbours.
        int xs = 0, zs = 0;
        for (int cz = first(gz); cz <= last(gz); cz++) { dx += slope(first(gx), last(gx), cz, true); xs++; }
        for (int cx = first(gx); cx <= last(gx); cx++) { dz += slope(first(gz), last(gz), cx, false); zs++; }
        int at = gz * POINTS + gx;
        pointHeight[at] = h / n;
        pointRgb[at] = Math.round(r / n) << 16 | Math.round(g / n) << 8 | Math.round(b / n);
        pointNormal[at] = normalCode(dx / xs, dz / zs);
    }

    /** Rise over run along x (or z) at voxel column(s) {@code from..to} of row {@code across}. */
    private float slope(int from, int to, int across, boolean alongX) {
        int low, high;
        if (from != to) { low = from; high = to; }
        else { low = Math.max(-1, from - 1); high = Math.min(16, from + 1); }
        int a = alongX ? height[cell(low, across)] : height[cell(across, low)];
        int c = alongX ? height[cell(high, across)] : height[cell(across, high)];
        return (c - a) / (float) ((high - low) * voxel);
    }

    /** Packs the ground's faces and walls into {@code groups} and returns the grid's points. */
    int[] emit(IntArrayList[] groups) {
        int quads = POINTS - 1;
        // Merge ids: pieces whose four points share a height, colour and slope, and the same look, merge into rectangles.
        long[] looks = new long[quads * quads];
        boolean[] flat = new boolean[quads * quads];
        for (int qz = 0; qz < quads; qz++) for (int qx = 0; qx < quads; qx++) {
            looks[qz * quads + qx] = look(qx, qz);
            int a = qz * POINTS + qx, b = a + 1, c = a + POINTS, d = c + 1;
            flat[qz * quads + qx] = pointHeight[a] == pointHeight[b] && pointHeight[a] == pointHeight[c] && pointHeight[a] == pointHeight[d]
                    && pointRgb[a] == pointRgb[b] && pointRgb[a] == pointRgb[c] && pointRgb[a] == pointRgb[d]
                    && pointNormal[a] == pointNormal[b] && pointNormal[a] == pointNormal[c] && pointNormal[a] == pointNormal[d];
        }
        boolean[] used = new boolean[quads * quads];
        for (int qz = 0; qz < quads; qz++) for (int qx = 0; qx < quads; qx++) {
            int at = qz * quads + qx;
            if (used[at]) continue;
            int width = 1, depth = 1;
            if (flat[at]) {
                while (qx + width < quads && width < 16 && mergeable(at, qz * quads + qx + width, flat, used, looks)) width++;
                rows:
                while (qz + depth < quads) {
                    for (int k = 0; k < width; k++) if (!mergeable(at, (qz + depth) * quads + qx + k, flat, used, looks)) break rows;
                    depth++;
                }
            }
            for (int t = 0; t < depth; t++) for (int k = 0; k < width; k++) used[(qz + t) * quads + qx + k] = true;
            pack(groups[Direction.UP.get3DDataValue()], SMOOTH_TOP, qx, qz, width, depth - 1, looks[at], 0);
        }
        // Walls along each edge, a voxel column wide, as deep as the voxel walls were.
        int depth = WALL_DEPTH * voxel / 16;
        for (int side = 0; side < 4; side++) {
            for (int along = 0; along < quads; along++) {
                int qx = side == 3 ? quads - 1 : side == 2 ? 0 : along, qz = side == 1 ? quads - 1 : side == 0 ? 0 : along;
                int x = side == 3 ? POINTS - 1 : side == 2 ? 0 : along, z = side == 1 ? POINTS - 1 : side == 0 ? 0 : along;
                pack(groups[FIRST_WALL + side], SMOOTH_WALL, x, z, 1, depth, look(qx, qz), side);
            }
        }
        int[] grid = new int[POINTS * POINTS * 4];
        for (int i = 0; i < POINTS * POINTS; i++) {
            grid[i * 4] = Math.round(pointHeight[i]);
            grid[i * 4 + 1] = pointRgb[i];
            grid[i * 4 + 2] = pointNormal[i];
        }
        if (job.textured()) {
            for (int cz = 0; cz < 16; cz++) for (int cx = 0; cx < 16; cx++) {
                int at = cell(cx, cz);
                grid[((cz + 1) * POINTS + cx + 1) * 4 + 3] = job.looks().id(job.faces(), state[at], Direction.UP, biome[at], true, job.shaderIds()) + 1;
            }
        }
        return grid;
    }

    private static boolean mergeable(int at, int other, boolean[] flat, boolean[] used, long[] looks) {
        return flat[other] && !used[other] && looks[other] == looks[at];
    }

    /** The look of the piece between points (qx, qz) and (qx + 1, qz + 1): that of the highest of this column's voxel columns under it. */
    private long look(int qx, int qz) {
        int best = -1;
        for (int cz = Math.max(0, qz - 1); cz <= Math.min(15, qz); cz++) for (int cx = Math.max(0, qx - 1); cx <= Math.min(15, qx); cx++) {
            int at = cell(cx, cz);
            if (best < 0 || height[at] > height[best]) best = at;
        }
        int cx = best % CELLS - 1, cz = best / CELLS - 1, own = cz * 16 + cx;
        float surface = builder.surface[own];
        int sky = Mth.clamp(15 - (int) (surface - height[best] / (float) UNIT), 0, 15);
        Holder<Biome> holder = biome[best];
        int id = job.looks().id(job.faces(), state[best], Direction.UP, holder, job.textured(), job.shaderIds());
        return (long) id << 8 | sky;
    }

    /** Packs a piece of ground (or a wall, with its side in the slope's bits) like {@link Builder#pack}. */
    private void pack(IntArrayList out, int kind, int x, int z, int width, int extent, long look, int side) {
        int slot = job.slot(), light = (int) (look & 0xFF);
        out.add(x | z << 5 | kind << 10 | (width - 1) << 13 | light << 17 | (slot & 0x7F) << 25);
        out.add(0);
        out.add(Math.min(extent, 0xFFFFF) | (slot >>> 7) << 20);
        out.add((int) (look >>> 8) & LOOK_MASK | side << 20);
    }
}
