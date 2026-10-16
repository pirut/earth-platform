package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.far.Far;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Builds one far column's faces: every voxel face that touches air, as a box the voxel's size,
 * packed in 16 bytes each (see {@link FarGpu}) for the far terrain's own shader, as Voxy packs its
 * quads. Each voxel column's stack is lowered by its drop so the top lands at the real height, so
 * a side face may be partly hidden by a neighbour that sits lower or higher; only the visible
 * parts are kept. Matching faces merge: flat tops into rectangles, sides into runs. Voxels whose
 * cube the client already draws are left out, and count as air for their neighbours so the seam
 * stays closed. Faces hidden only by the next column go in a separate wall per side, drawn when
 * that column is not (where a finer or coarser level takes over), so level changes never open
 * holes. Water goes in its own block-format mesh, drawn see-through like near water.
 */
final class FarMesher {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState(), GROUND = Blocks.STONE.defaultBlockState();
    private static final Direction[] DIRECTIONS = Direction.values();
    /**
     * How far below the next column's ground a wall reaches, in voxels: deep enough to close the
     * seam against a coarser or finer level's ground there, which differs by a few voxels at most.
     */
    private static final int WALL_DEPTH = 8;
    /** How far below a column's own top it closes seams against cubes drawn up close, in voxels. */
    private static final int SEAM_DEPTH = 16;
    /** Face groups: the column's own faces by direction (down, up, north, south, west, east), then its walls north, south, west, east. */
    static final int GROUPS = 10, FIRST_WALL = 6;
    /** Heights inside the mesher are in 1/256 blocks, as drops are; faces store 1/16 blocks. */
    private static final int UNIT = Far.DROP_UNIT;
    /** A face's fourth int: its look in the low 20 bits, its ground slope's {@link #normalCode} in the high 12. */
    static final int LOOK_MASK = (1 << 20) - 1;

    private FarMesher() {}

    /** A same-level column next to this one: north, south, west, east. */
    record Side(@Nullable LevelChunkSection[] sections, int bottom, char[] drops) {}

    /** Everything a worker needs; sections are never changed after they arrive, so sharing them is safe. */
    record Job(long key, int version, int level, int slot, int x0, int z0, int bottom, LevelChunkSection[] sections, char[] drops,
               @Nullable Side[] sides, @Nullable Side[] diagonals, @Nullable LongOpenHashSet drawn, boolean textured, FarLooks looks,
               FarFaces faces, @Nullable Object2IntMap<BlockState> shaderIds, float[] shade, float[] white) {}

    /** A finished water mesh and the memory holding it. */
    record Part(MeshData mesh, ByteBufferBuilder memory) {
        void free() { mesh.close(); memory.close(); }
        long bytes() { return mesh.vertexBuffer().remaining(); }
    }

    /**
     * The column's opaque faces, 4 ints each, grouped as {@link #GROUPS} with {@code counts}
     * faces per group in order, the points of its smooth ground (see {@link Smooth}) or null, and
     * its water, drawn see-through after everything opaque.
     */
    record Built(long key, int version, int[] quads, int[] counts, @Nullable int[] grid, @Nullable Part water, @Nullable Part[] waterWalls) {
        void free() {
            if (water != null) water.free();
            for (Part wall : waterWalls) if (wall != null) wall.free();
        }
        long bytes() {
            long bytes = quads.length * 4L + (grid == null ? 0 : grid.length * 4L) + (water == null ? 0 : water.bytes());
            for (Part wall : waterWalls) if (wall != null) bytes += wall.bytes();
            return bytes;
        }
    }

    static Built build(Job job) {
        return new Builder(job).build();
    }

    private static final class Builder {
        final Job job;
        final int v, voxel;
        final int[] tops;
        final int[][] sideTops;
        final float[] surface;
        /** Each voxel column's ground slope as a {@link #normalCode}. */
        final int[] normals;
        /** Each voxel column's highest ground row (not water or trees), or -1. */
        final int[] groundRows = new int[256];
        /** What each voxel column's ground shows on top and on its step risers (see {@link #evenCover}), or null for its own block. */
        @Nullable final BlockState[] cover;
        final Flat opaqueFlat = new Flat(), waterFlat = new Flat();
        /** Side face pieces by direction (north, south, west, east) and voxel column. */
        @SuppressWarnings("unchecked") final List<Piece>[][] pieces = new List[4][256];
        @Nullable ByteBufferBuilder waterMemory;
        @Nullable BufferBuilder water;
        /** Water's own walls, north, south, west, east: water faces the next column's water hides, drawn when that column is not. */
        final ByteBufferBuilder[] wallMemory = new ByteBufferBuilder[4];
        final BufferBuilder[] waterWalls = new BufferBuilder[4];
        /** Water shows its texture only near by: drawn face by face, textured oceans would cost far more faces than merged flat ones. */
        final boolean texturedWater;

        Builder(Job job) {
            this.job = job;
            texturedWater = job.textured && job.level <= TEXTURED_WATER_LEVELS;
            v = 1 << job.level;
            voxel = v * UNIT;
            tops = tops(job.sections);
            sideTops = sideTops(job);
            surface = surface(job, v);
            for (int cell = 0; cell < 256; cell++) groundRows[cell] = groundRow(job.sections, cell & 15, cell >> 4);
            normals = normals(job, voxel);
            cover = job.level >= COVER_FROM ? evenCover(job, groundRows) : null;
        }

        Built build() {
            // The ground is drawn as one smooth surface rather than voxels, so only water is meshed voxel by voxel,
            // and on the textured levels trees too, which stand on the ground as blocks do up close.
            boolean smooth = smooth(job), trees = smooth && job.textured;
            LevelChunkSection[] sections = job.sections;
            for (int si = 0; si < sections.length; si++) {
                LevelChunkSection section = sections[si];
                if (section == null || section.hasOnlyAir()) continue;
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state.isAir() || smooth && !seeThrough(state) && !(trees && foliage(state))) continue;
                    int vy = si * 16 + y;
                    if (drawn(job, x, vy, z)) continue;
                    voxel(section, state, x, y, vy, z);
                }
            }
            IntArrayList[] groups = new IntArrayList[GROUPS];
            for (int g = 0; g < GROUPS; g++) groups[g] = new IntArrayList();
            int[] grid = null;
            if (smooth) {
                grid = new Smooth(this).emit(groups);
                if (trees) {
                    opaqueFlat.emitQuads(this, groups);
                    emitSides(groups);
                }
            } else {
                opaqueFlat.emitQuads(this, groups);
                emitSides(groups);
            }
            waterFlat.emitWater(this);
            int total = 0;
            int[] counts = new int[GROUPS];
            for (int g = 0; g < GROUPS; g++) { counts[g] = groups[g].size() / 4; total += groups[g].size(); }
            int[] quads = new int[total];
            int at = 0;
            for (IntArrayList group : groups) { group.getElements(0, quads, at, group.size()); at += group.size(); }
            Part waterPart = part(water, waterMemory);
            Part[] wallParts = new Part[4];
            for (int wall = 0; wall < 4; wall++) wallParts[wall] = part(waterWalls[wall], wallMemory[wall]);
            return new Built(job.key, job.version, quads, counts, grid, waterPart, wallParts);
        }

        @Nullable private static Part part(@Nullable BufferBuilder builder, @Nullable ByteBufferBuilder memory) {
            if (builder == null) return null;
            MeshData mesh = builder.build();
            if (mesh == null) { memory.close(); return null; }
            return new Part(mesh, memory);
        }

        private void voxel(LevelChunkSection section, BlockState state, int x, int y, int vy, int z) {
            int cell = z * 16 + x, drop = job.drops[cell];
            boolean liquid = seeThrough(state);
            // Under water, sky light fades a level per block of water above, as it does near by.
            int sky = liquid ? 15 : Mth.clamp(15 - (int) (surface[cell] - ((vy + 1) * voxel - drop) / (float) UNIT), 0, 15);
            int bottom = vy * voxel - drop, top = bottom + Math.round(voxel * Far.fill(state));
            Holder<Biome> biome = null;
            for (Direction direction : DIRECTIONS) {
                if (direction.getAxis() == Direction.Axis.Y) {
                    int ny = vy + direction.getStepY();
                    BlockState neighbour = at(job, x, ny, z);
                    if (drawn(job, x, ny, z)) {
                        if (liquid && seeThrough(neighbour) || vy < tops[cell] - SEAM_DEPTH) continue;
                    } else if (hides(state, neighbour, direction)) continue;
                    if (biome == null) biome = section.getBiomes().get(x >> 2, y >> 2, z >> 2);
                    int height = direction == Direction.UP ? top : bottom;
                    BlockState shown = direction == Direction.UP && vy == groundRows[cell] ? cover(cell, state) : state;
                    if (!liquid) opaqueFlat.add(direction, x, vy, z, height, look(shown, biome, direction, sky, cell));
                    else if (!texturedWater) waterFlat.add(direction, x, vy, z, height, waterLook(state, biome, direction));
                    else waterFace(water(), state, biome, direction, x, z, height, 0);
                    continue;
                }
                int nx = x + direction.getStepX(), nz = z + direction.getStepZ(), wall = side(nx, nz);
                int neighbourDrop = wall < 0 ? job.drops[nz * 16 + nx] : sideDrop(wall, nx, nz, drop);
                // The neighbour's rows sit delta lower than ours: our face meets two of them, split where they meet.
                int delta = neighbourDrop - drop, lowRow, highRow, split;
                if (delta >= 0) { lowRow = vy; highRow = vy + 1; split = bottom + voxel - delta; }
                else { lowRow = vy - 1; highRow = vy; split = bottom - delta; }
                for (int part = 0; part < 2; part++) {
                    int from = part == 0 ? bottom : Math.max(bottom, split), to = part == 0 ? Math.min(split, top) : top;
                    if (to <= from) continue;
                    int row = part == 0 ? lowRow : highRow;
                    BlockState neighbour = at(job, nx, row, nz);
                    int group;
                    if (drawn(job, nx, row, nz)) {
                        // The cubes there are drawn up close; water carries on into theirs without a wall.
                        if (liquid && seeThrough(neighbour) || vy < tops[cell] - SEAM_DEPTH) continue;
                        group = direction.get3DDataValue();
                    } else if (hides(state, neighbour, direction)) {
                        if (wall < 0) continue;
                        if (liquid) {
                            // Where a coarser or finer level's water meets this, at a slightly different
                            // height, a wall of water closes the slit that showed the dark bed below.
                            if (vy < tops[cell] - WALL_DEPTH) continue;
                            if (biome == null) biome = section.getBiomes().get(x >> 2, y >> 2, z >> 2);
                            waterFace(waterWall(wall), state, biome, direction, x, z, from, to - from);
                            continue;
                        }
                        if (vy < sideTops[wall][wall < 2 ? x : z] - WALL_DEPTH) continue;
                        group = FIRST_WALL + wall;
                    } else group = direction.get3DDataValue();
                    if (biome == null) biome = section.getBiomes().get(x >> 2, y >> 2, z >> 2);
                    if (liquid) waterFace(water(), state, biome, direction, x, z, from, to - from);
                    else {
                        int index = direction.get3DDataValue() - 2;
                        // The ground's own step risers look like the ground's top, so a gentle slope of
                        // voxel stairs reads as one surface rather than stripes of top and side texture.
                        List<Piece> list = pieces[index][cell];
                        if (list == null) list = pieces[index][cell] = new ArrayList<>(2);
                        boolean ground = vy == groundRows[cell];
                        list.add(new Piece(from, to, look(ground ? cover(cell, state) : state, biome, ground ? Direction.UP : direction, sky, cell), group));
                    }
                }
            }
        }

        private BlockState cover(int cell, BlockState own) {
            return cover == null || cover[cell] == null ? own : cover[cell];
        }

        /**
         * The drop of a voxel column in the next column; unknown columns count as level with this
         * one. Both columns lower their rows the same way, so a drop compares directly with ours.
         */
        private int sideDrop(int wall, int nx, int nz, int own) {
            Side side = job.sides == null ? null : job.sides[wall];
            if (side == null || (nx < 0 || nx > 15) && (nz < 0 || nz > 15)) return own;
            return side.drops[(nz & 15) * 16 + (nx & 15)];
        }

        /**
         * A face's look id, its light and its ground slope, which faces must share to merge.
         * Foliage and undersides keep their own face's normal.
         */
        private long look(BlockState state, Holder<Biome> biome, Direction direction, int sky, int cell) {
            int id = job.looks.id(job.faces, state, direction, biome, job.textured, job.shaderIds);
            int normal = direction == Direction.DOWN || foliage(state) ? 0 : normals[cell];
            return (long) normal << 40 | (long) id << 8 | sky;
        }

        private WaterLook waterLook(BlockState state, Holder<Biome> biome, Direction direction) {
            return new WaterLook(state, colour(state, biome, direction), direction);
        }

        /** Merges each voxel column's side pieces upward, then matching runs along the row, and packs them. */
        private void emitSides(IntArrayList[] groups) {
            for (int index = 0; index < 4; index++) {
                Direction direction = DIRECTIONS[index + 2];
                boolean alongX = direction.getAxis() == Direction.Axis.Z;
                List<Piece>[] cells = pieces[index];
                for (List<Piece> list : cells) if (list != null && list.size() > 1) stack(list);
                for (int plane = 0; plane < 16; plane++) {
                    for (int u = 0; u < 16; u++) {
                        List<Piece> list = cells[alongX ? plane * 16 + u : u * 16 + plane];
                        if (list == null) continue;
                        for (Piece piece : list) {
                            if (piece.used) continue;
                            int width = 1;
                            while (u + width < 16) {
                                Piece next = match(cells[alongX ? plane * 16 + u + width : (u + width) * 16 + plane], piece);
                                if (next == null) break;
                                next.used = true;
                                width++;
                            }
                            int x = alongX ? u : plane + (direction == Direction.EAST ? 1 : 0);
                            int z = alongX ? plane + (direction == Direction.SOUTH ? 1 : 0) : u;
                            int from = Math.floorDiv(piece.from + 8, 16), to = Math.floorDiv(piece.to + 8, 16);
                            if (to > from) pack(groups[piece.group], x, z, direction, width, piece.from, to - from, piece.look);
                        }
                    }
                }
            }
        }

        /** Packs one face: y in 1/256 blocks, extent in 1/16 blocks for side faces or depth - 1 for up and down faces. */
        private void pack(IntArrayList out, int x, int z, Direction direction, int width, int y256, int extent, long look) {
            int slot = job.slot, light = (int) (look & 0xFF);
            out.add(x | z << 5 | direction.get3DDataValue() << 10 | (width - 1) << 13 | light << 17 | (slot & 0x7F) << 25);
            out.add(Math.floorDiv(y256 + 8, 16));
            out.add(Math.min(extent, 0xFFFFF) | (slot >>> 7) << 20);
            out.add((int) (look >>> 8) & LOOK_MASK | (int) (look >>> 40) << 20);
        }

        /** One water face drawn on its own, from {@code y} up by {@code height} (both in 1/256 blocks); up and down faces take no height. */
        private void waterFace(BufferBuilder builder, BlockState state, Holder<Biome> biome, Direction direction, int x, int z, int y, int height) {
            float unit = 1.0F / UNIT;
            ShaderBlocks.begin(builder, job.shaderIds, state, true, x, 0, z);
            face(builder, state, colour(state, biome, direction), direction, x * v, y * unit, z * v, v, height * unit, v,
                    LightTexture.FULL_SKY, texturedWater);
            ShaderBlocks.end(builder, job.shaderIds);
        }

        BufferBuilder water() {
            if (water == null) {
                waterMemory = new ByteBufferBuilder(8192);
                water = new BufferBuilder(waterMemory, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            }
            return water;
        }

        BufferBuilder waterWall(int wall) {
            if (waterWalls[wall] == null) {
                wallMemory[wall] = new ByteBufferBuilder(1024);
                waterWalls[wall] = new BufferBuilder(wallMemory[wall], VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            }
            return waterWalls[wall];
        }

        /** A water face's vertex colour: its biome tint, times its average colour when flat, shaded for its side. */
        int colour(BlockState state, Holder<Biome> biome, Direction direction) {
            FarFaces.Face face = job.faces.face(state, direction);
            int tint = job.faces.tint(state, biome, face);
            int rgb = texturedWater ? tint : multiply(face.rgb(), tint);
            // Flat faces have no texture to carry water's see-through alpha, so the vertices do.
            int alpha = texturedWater ? 255 : face.rgb() >>> 24;
            return alpha << 24 | shade(rgb, job.shade[direction.get3DDataValue()]);
        }

        /** One side of a box of blocks, in blocks from the column's base. */
        void face(BufferBuilder builder, BlockState state, int argb, Direction direction,
                  float x, float y, float z, float sx, float sy, float sz, int light, boolean textured) {
            FarFaces.Face face = textured ? job.faces.face(state, direction) : null;
            float u0 = face != null ? face.u0() : job.white[0], u1 = face != null ? face.u1() : job.white[2];
            float v0 = face != null ? face.v0() : job.white[1], v1 = face != null ? face.v1() : job.white[3];
            if (face != null) {
                // Half a texel in from the sprite's edges: far off, a whole row of faces shares one
                // edge on screen, and sampling exactly there read the next sprite in the atlas, a
                // dark line across the sea.
                float du = (u1 - u0) / 32.0F, dv = (v1 - v0) / 32.0F;
                u0 += du; u1 -= du; v0 += dv; v1 -= dv;
            }
            float x1 = x + sx, y1 = y + sy, z1 = z + sz;
            float nx = direction.getStepX(), ny = direction.getStepY(), nz = direction.getStepZ();
            switch (direction) {
                case UP -> quad(builder, argb, light, nx, ny, nz, u0, v0, u1, v1, x, y, z, x, y, z1, x1, y, z1, x1, y, z);
                case DOWN -> quad(builder, argb, light, nx, ny, nz, u0, v0, u1, v1, x, y, z1, x, y, z, x1, y, z, x1, y, z1);
                case NORTH -> quad(builder, argb, light, nx, ny, nz, u0, v0, u1, v1, x1, y1, z, x1, y, z, x, y, z, x, y1, z);
                case SOUTH -> quad(builder, argb, light, nx, ny, nz, u0, v0, u1, v1, x, y1, z1, x, y, z1, x1, y, z1, x1, y1, z1);
                case WEST -> quad(builder, argb, light, nx, ny, nz, u0, v0, u1, v1, x, y1, z, x, y, z, x, y, z1, x, y1, z1);
                case EAST -> quad(builder, argb, light, nx, ny, nz, u0, v0, u1, v1, x1, y1, z1, x1, y, z1, x1, y, z, x1, y1, z);
            }
        }
    }

    /**
     * Whether far ground is drawn smooth: off by default, so far terrain stays blocks all the way out
     * as Minecraft looks; on with {@code -Dearthplatform.far.smooth=true}.
     * Every level is smooth, the textured ones too, or only from a later level with
     * {@code -Dearthplatform.far.smoothFrom}.
     */
    static final boolean SMOOTH = Boolean.getBoolean("earthplatform.far.smooth");
    static final int SMOOTH_FROM = Integer.getInteger("earthplatform.far.smoothFrom", 1);
    /** Grid points along each side of a column's smooth ground: its two edges and the middles of its 16 voxel columns. */
    static final int POINTS = 18;
    /** The levels whose water is textured; further out it is flat-coloured and merged. */
    static final int TEXTURED_WATER_LEVELS = 2;
    /** Face kinds past the six block directions: a piece of smooth ground, and a wall hanging from its edge. */
    static final int SMOOTH_TOP = 6, SMOOTH_WALL = 7;

    /** True when a column's ground is drawn as a smooth surface: any level with nothing of its own drawn up close. */
    static boolean smooth(Job job) {
        return SMOOTH && job.level >= SMOOTH_FROM && (job.drawn == null || job.drawn.isEmpty());
    }

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
     * the column's base in 1/256 blocks, RGB, the ground's {@link #normalCode} there, and on
     * textured levels, for points in the middle of a voxel column, one more than the id of that
     * voxel column's textured look (0 elsewhere), which the fragment shader reads to lay each voxel
     * column's own block texture over the slope.
     */
    private static final class Smooth {
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
            if (insideX && insideZ) { sections = job.sections; bottom = job.bottom; drops = job.drops; }
            else {
                Side side = insideX || insideZ ? (job.sides == null ? null : job.sides[side(cx, cz)])
                        : job.diagonals == null ? null : job.diagonals[(cz < 0 ? 0 : 2) + (cx < 0 ? 0 : 1)];
                if (side == null || side.sections == null) return false;
                sections = side.sections; bottom = side.bottom; drops = side.drops;
            }
            // Textured levels draw trees as blocks on the ground; further out they are part of the surface.
            int x = cx & 15, z = cz & 15, row = job.textured ? groundRow(sections, x, z) : top(sections, x, z, true);
            int at = cell(cx, cz);
            height[at] = (row + 1 + (bottom - job.bottom) * 16) * voxel - drops[z * 16 + x];
            BlockState top = row < 0 ? GROUND : sections[row >> 4].getBlockState(x, row & 15, z);
            Holder<Biome> holder = row < 0 ? null : sections[row >> 4].getBiomes().get(x >> 2, (row & 15) >> 2, z >> 2);
            FarFaces.Face face = job.faces.face(top, Direction.UP);
            rgb[at] = multiply(face.rgb(), job.faces.tint(top, holder, face));
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
            if (job.textured) {
                for (int cz = 0; cz < 16; cz++) for (int cx = 0; cx < 16; cx++) {
                    int at = cell(cx, cz);
                    grid[((cz + 1) * POINTS + cx + 1) * 4 + 3] = job.looks.id(job.faces, state[at], Direction.UP, biome[at], true, job.shaderIds) + 1;
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
            int id = job.looks.id(job.faces, state[best], Direction.UP, holder, job.textured, job.shaderIds);
            return (long) id << 8 | sky;
        }

        /** Packs a piece of ground (or a wall, with its side in the slope's bits) like {@link Builder#pack}. */
        private void pack(IntArrayList out, int kind, int x, int z, int width, int extent, long look, int side) {
            int slot = job.slot, light = (int) (look & 0xFF);
            out.add(x | z << 5 | kind << 10 | (width - 1) << 13 | light << 17 | (slot & 0x7F) << 25);
            out.add(0);
            out.add(Math.min(extent, 0xFFFFF) | (slot >>> 7) << 20);
            out.add((int) (look >>> 8) & LOOK_MASK | side << 20);
        }
    }

    /** A visible part of a voxel's side, from and to in 1/256 blocks above the column's base. */
    private static final class Piece {
        int from, to;
        final long look;
        final int group;
        boolean used;

        Piece(int from, int to, long look, int group) { this.from = from; this.to = to; this.look = look; this.group = group; }
    }

    /** Joins a voxel column's pieces that touch and look the same into one tall piece. */
    private static void stack(List<Piece> list) {
        list.sort((a, b) -> Integer.compare(a.from, b.from));
        List<Piece> merged = new ArrayList<>(list.size());
        for (Piece piece : list) {
            Piece last = merged.isEmpty() ? null : merged.getLast();
            if (last != null && last.to == piece.from && last.look == piece.look && last.group == piece.group) last.to = piece.to;
            else merged.add(piece);
        }
        list.clear();
        list.addAll(merged);
    }

    @Nullable private static Piece match(@Nullable List<Piece> list, Piece piece) {
        if (list == null) return null;
        for (Piece other : list) {
            if (!other.used && other != piece && other.from == piece.from && other.to == piece.to && other.look == piece.look
                    && other.group == piece.group) return other;
        }
        return null;
    }

    private record WaterLook(BlockState state, int argb, Direction direction) {}

    /**
     * Collects a column's up and down faces per voxel row and merges each row's matching faces
     * into rectangles, so flat ground and lakes cost a few faces instead of one per voxel.
     */
    private static final class Flat {
        /** Per direction (down, up) and voxel row, a 16 x 16 grid of merge ids, 0 for none. */
        private final Map<Integer, int[]>[] rows = newRows();
        private final List<Object> looks = new ArrayList<>();
        private final List<Integer> heights = new ArrayList<>();
        private final Map<Object, Integer> ids = new HashMap<>();

        @SuppressWarnings("unchecked") private static Map<Integer, int[]>[] newRows() { return new Map[] {new HashMap<>(), new HashMap<>()}; }

        void add(Direction direction, int x, int vy, int z, int height, Object look) {
            int[] grid = rows[direction.get3DDataValue()].computeIfAbsent(vy, unused -> new int[256]);
            List<Object> key = List.of(look, height);
            Integer id = ids.get(key);
            if (id == null) { id = looks.size() + 1; looks.add(look); heights.add(height); ids.put(key, id); }
            grid[z * 16 + x] = id;
        }

        /** Greedy rectangles: each run along x grows along z while the next rows match. */
        private void merge(Rectangle out) {
            for (int d = 0; d < 2; d++) {
                for (int[] grid : rows[d].values()) {
                    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        int id = grid[z * 16 + x];
                        if (id == 0) continue;
                        int width = 1;
                        while (x + width < 16 && grid[z * 16 + x + width] == id) width++;
                        int depth = 1;
                        rows:
                        while (z + depth < 16) {
                            for (int k = 0; k < width; k++) if (grid[(z + depth) * 16 + x + k] != id) break rows;
                            depth++;
                        }
                        for (int t = 0; t < depth; t++) for (int k = 0; k < width; k++) grid[(z + t) * 16 + x + k] = 0;
                        out.accept(DIRECTIONS[d], x, z, width, depth, heights.get(id - 1), looks.get(id - 1));
                    }
                }
            }
        }

        void emitQuads(Builder builder, IntArrayList[] groups) {
            merge((direction, x, z, width, depth, height, look) ->
                    builder.pack(groups[direction.get3DDataValue()], x, z, direction, width, height, depth - 1, (Long) look));
        }

        void emitWater(Builder builder) {
            float unit = 1.0F / UNIT;
            int v = builder.v;
            merge((direction, x, z, width, depth, height, look) -> {
                WaterLook water = (WaterLook) look;
                BufferBuilder out = builder.water();
                ShaderBlocks.begin(out, builder.job.shaderIds, water.state, true, x, 0, z);
                // Only flat-coloured water merges; textured levels draw each water face on its own.
                builder.face(out, water.state, water.argb, direction, x * v, height * unit, z * v, width * v, 0, depth * v,
                        LightTexture.FULL_SKY, false);
                ShaderBlocks.end(out, builder.job.shaderIds);
            });
        }
    }

    private interface Rectangle {
        void accept(Direction direction, int x, int z, int width, int depth, int height, Object look);
    }

    /** The highest voxel row holding anything in each voxel column, or -1 where all is air. */
    private static int[] tops(LevelChunkSection[] sections) {
        int[] tops = new int[256];
        for (int i = 0; i < 256; i++) tops[i] = top(sections, i & 15, i >> 4, false);
        return tops;
    }

    /**
     * For each side (north, south, west, east) and place along it, the highest solid voxel row of
     * the next column's facing edge, in this column's rows; walls only need to reach a little below it.
     */
    private static int[][] sideTops(Job job) {
        int[][] tops = new int[4][16];
        for (int side = 0; side < 4; side++) {
            Side next = job.sides == null ? null : job.sides[side];
            for (int along = 0; along < 16; along++) {
                if (next == null || next.sections == null) { tops[side][along] = Integer.MAX_VALUE; continue; }
                int x = side == 2 ? 15 : side == 3 ? 0 : along, z = side == 0 ? 15 : side == 1 ? 0 : along;
                tops[side][along] = top(next.sections, x, z, true) + (next.bottom - job.bottom) * 16;
            }
        }
        return tops;
    }

    /** The highest voxel row in a voxel column holding anything (or, when {@code solid}, anything but water); below the sections is ground. */
    private static int top(@Nullable LevelChunkSection[] sections, int x, int z, boolean solid) {
        for (int si = sections.length - 1; si >= 0; si--) {
            LevelChunkSection section = sections[si];
            if (section == null || section.hasOnlyAir()) continue;
            for (int y = 15; y >= 0; y--) {
                BlockState state = section.getBlockState(x, y, z);
                if (!state.isAir() && !(solid && seeThrough(state))) return si * 16 + y;
            }
        }
        return -1;
    }

    /** Height of the water's surface above the column's base in each voxel column, in blocks, or below zero where there is none. */
    private static float[] surface(Job job, int v) {
        float[] surface = new float[256];
        java.util.Arrays.fill(surface, -1.0F);
        LevelChunkSection[] sections = job.sections;
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection section = sections[si];
            if (section == null || section.hasOnlyAir()) continue;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState state = section.getBlockState(x, y, z);
                if (!seeThrough(state)) continue;
                int cell = z * 16 + x;
                float top = (si * 16 + y + Far.fill(state)) * v - job.drops[cell] / (float) UNIT;
                surface[cell] = Math.max(surface[cell], top);
            }
        }
        return surface;
    }

    /**
     * The ground's slope at each voxel column, from the ground heights either side of it, so a
     * stepped hillside is lit, with or without a shader pack, as the slope it stands for instead
     * of as stripes of lit tops and dark risers. Columns next door give the heights past the
     * edges, where they are known.
     */
    private static int[] normals(Job job, int voxel) {
        int[] ground = new int[18 * 18];
        boolean[] known = new boolean[18 * 18];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int cell = z * 16 + x;
            ground[(z + 1) * 18 + x + 1] = (groundRow(job.sections, x, z) + 1) * voxel - job.drops[cell];
            known[(z + 1) * 18 + x + 1] = true;
        }
        for (int side = 0; side < 4 && job.sides != null; side++) {
            Side next = job.sides[side];
            if (next == null || next.sections == null) continue;
            for (int along = 0; along < 16; along++) {
                int x = side == 2 ? 15 : side == 3 ? 0 : along, z = side == 0 ? 15 : side == 1 ? 0 : along;
                int gx = side == 2 ? 0 : side == 3 ? 17 : along + 1, gz = side == 0 ? 0 : side == 1 ? 17 : along + 1;
                int row = groundRow(next.sections, x, z) + (next.bottom - job.bottom) * 16;
                ground[gz * 18 + gx] = (row + 1) * voxel - next.drops[z * 16 + x];
                known[gz * 18 + gx] = true;
            }
        }
        int[] normals = new int[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int at = (z + 1) * 18 + x + 1;
            normals[z * 16 + x] = normalCode(slope(ground, known, at, 1, voxel), slope(ground, known, at, 18, voxel));
        }
        return normals;
    }

    /** Rise over run across a voxel column along one axis, from whichever neighbours are known. */
    private static float slope(int[] ground, boolean[] known, int at, int step, int voxel) {
        boolean before = known[at - step], after = known[at + step];
        if (before && after) return (ground[at + step] - ground[at - step]) / (2.0F * voxel);
        if (after) return (ground[at + step] - ground[at]) / (float) voxel;
        if (before) return (ground[at] - ground[at - step]) / (float) voxel;
        return 0.0F;
    }

    /**
     * An upward normal for ground with the given slopes, in 12 bits: x and z from -31 to 31 over
     * 31, each stored plus 32, so 0 never occurs and stands for "use the face's own normal".
     */
    static int normalCode(float slopeX, float slopeZ) {
        float length = (float) Math.sqrt(slopeX * slopeX + 1.0F + slopeZ * slopeZ);
        int nx = Math.round(-slopeX / length * 31.0F) + 32, nz = Math.round(-slopeZ / length * 31.0F) + 32;
        return nx | nz << 6;
    }

    /** The finest level whose ground cover is evened out (see {@link #evenCover}); finer ones sit by the real blocks and keep theirs. */
    static final int COVER_FROM = Integer.getInteger("earthplatform.far.coverFrom", 2);

    /**
     * Evens out what the ground shows from voxel to voxel. A far voxel takes the block found at
     * its spot, so where thin snow lies on rock, or grass on scree, neighbouring voxels flip
     * between the two and a mountainside reads as salt and pepper rather than snowfields and
     * rock. A voxel whose neighbours clearly agree on something else takes that on instead, twice
     * over, so lone specks go and patches round off, the way a snowfield or a meadow looks from
     * afar. Its shape and height stay as they are.
     */
    @Nullable static BlockState[] evenCover(Job job, int[] groundRows) {
        BlockState[] cover = new BlockState[256];
        boolean any = false;
        for (int cell = 0; cell < 256; cell++) {
            if (groundRows[cell] < 0) continue;
            cover[cell] = at(job.sections, groundRows[cell], cell & 15, cell >> 4);
            any = true;
        }
        if (!any) return null;
        for (int pass = 0; pass < 2; pass++) {
            BlockState[] next = cover.clone();
            for (int cell = 0; cell < 256; cell++) {
                BlockState own = cover[cell];
                if (own == null) continue;
                int x = cell & 15, z = cell >> 4, same = 0;
                BlockState best = null;
                int bestCount = 0;
                BlockState[] seen = new BlockState[8];
                int[] counts = new int[8];
                int kinds = 0;
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = x + dx, nz = z + dz;
                    if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
                    BlockState other = cover[nz * 16 + nx];
                    if (other == null) continue;
                    if (other == own) { same++; continue; }
                    int k = 0;
                    while (k < kinds && seen[k] != other) k++;
                    if (k == kinds) seen[kinds++] = other;
                    if (++counts[k] > bestCount) { bestCount = counts[k]; best = other; }
                }
                if (best != null && bestCount >= same + 2) next[cell] = best;
            }
            cover = next;
        }
        return cover;
    }

    /** The highest voxel row of ground in a voxel column: solid, not water and not foliage. */
    private static int groundRow(LevelChunkSection[] sections, int x, int z) {
        for (int si = sections.length - 1; si >= 0; si--) {
            LevelChunkSection section = sections[si];
            if (section == null || section.hasOnlyAir()) continue;
            for (int y = 15; y >= 0; y--) {
                BlockState state = section.getBlockState(x, y, z);
                if (!state.isAir() && !seeThrough(state) && !foliage(state)) return si * 16 + y;
            }
        }
        return -1;
    }

    /** Trees, which stand on the ground rather than shape it. */
    private static boolean foliage(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
    }

    /** Water, which is drawn see-through after the opaque terrain; lava and the rest are opaque. */
    private static boolean seeThrough(BlockState state) {
        // Most voxels hold no fluid; asking the tag for those cost a third of meshing.
        var fluid = state.getFluidState();
        return !fluid.isEmpty() && fluid.is(FluidTags.WATER);
    }

    /** Which neighbouring column a voxel position outside this one lies in (north, south, west, east), or -1 inside. */
    private static int side(int x, int z) {
        return z < 0 ? 0 : z > 15 ? 1 : x < 0 ? 2 : x > 15 ? 3 : -1;
    }

    /**
     * Whether a neighbouring voxel covers this voxel's face on its side. Water is see-through, so
     * it only covers water: above and below it, and beside it where it stands at least as high.
     */
    private static boolean hides(BlockState state, BlockState neighbour, Direction direction) {
        if (neighbour.isAir()) return false;
        if (!seeThrough(neighbour)) return true;
        if (!seeThrough(state)) return false;
        return direction.getAxis() == Direction.Axis.Y || Far.fill(state) <= Far.fill(neighbour);
    }

    /** The voxel at a position relative to this column; below a column's sections is ground, above is air. */
    private static BlockState at(Job job, int x, int vy, int z) {
        if (x >= 0 && x < 16 && z >= 0 && z < 16) return at(job.sections, vy, x, z);
        Side side = job.sides == null ? null : job.sides[side(x, z)];
        if (side == null || (x < 0 || x > 15) && (z < 0 || z > 15)) return AIR;
        return at(side.sections, vy + (job.bottom - side.bottom) * 16, x & 15, z & 15);
    }

    private static BlockState at(LevelChunkSection[] sections, int vy, int x, int z) {
        if (vy < 0) return GROUND;
        int si = vy >> 4;
        if (si >= sections.length || sections[si] == null) return AIR;
        return sections[si].getBlockState(x, vy & 15, z);
    }

    /**
     * True when the cubes under a voxel are all drawn by the cube renderer. A voxel is drawn lowered by its column's
     * drop, so it can reach into the cube below its own row: with only the air cube above the ground compiled, a far
     * water voxel lowered into a cube still loading would otherwise vanish and leave a hole down to the sky.
     */
    private static boolean drawn(Job job, int x, int vy, int z) {
        LongOpenHashSet drawn = job.drawn;
        if (drawn == null) return false;
        int v = 1 << job.level;
        int drop = job.drops[Mth.clamp(z, 0, 15) * 16 + Mth.clamp(x, 0, 15)];
        int bx = job.x0 + x * v, by = job.bottom * (Far.SIZE << job.level) + vy * v, bz = job.z0 + z * v;
        int low = by - Mth.positiveCeilDiv(drop, Far.DROP_UNIT), high = by + v - 1 - drop / Far.DROP_UNIT, cubes = Math.max(1, v >> 4);
        for (int cy = low >> 4; cy <= high >> 4; cy++) for (int cz = bz >> 4; cz < (bz >> 4) + cubes; cz++)
            for (int cx = bx >> 4; cx < (bx >> 4) + cubes; cx++) if (!drawn.contains(CubeKey.pack(cx, cy, cz))) return false;
        return true;
    }

    /** Corners in order top-left, bottom-left, bottom-right, top-right as seen from outside. */
    private static void quad(BufferBuilder builder, int argb, int light, float nx, float ny, float nz, float u0, float v0, float u1, float v1,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        builder.addVertex(ax, ay, az).setColor(argb).setUv(u0, v0).setLight(light).setNormal(nx, ny, nz);
        builder.addVertex(bx, by, bz).setColor(argb).setUv(u0, v1).setLight(light).setNormal(nx, ny, nz);
        builder.addVertex(cx, cy, cz).setColor(argb).setUv(u1, v1).setLight(light).setNormal(nx, ny, nz);
        builder.addVertex(dx, dy, dz).setColor(argb).setUv(u1, v0).setLight(light).setNormal(nx, ny, nz);
    }

    private static int multiply(int a, int b) {
        return (a >> 16 & 255) * (b >> 16 & 255) / 255 << 16 | (a >> 8 & 255) * (b >> 8 & 255) / 255 << 8 | (a & 255) * (b & 255) / 255;
    }

    private static int shade(int rgb, float shade) {
        int r = Math.min(255, (int) ((rgb >> 16 & 255) * shade)), g = Math.min(255, (int) ((rgb >> 8 & 255) * shade));
        int b = Math.min(255, (int) ((rgb & 255) * shade));
        return r << 16 | g << 8 | b;
    }
}
