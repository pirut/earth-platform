package io.earthplatform.minecraft.cubic.client;

import static io.earthplatform.minecraft.cubic.client.FarMesher.COVER_FROM;
import static io.earthplatform.minecraft.cubic.client.FarMesher.DIRECTIONS;
import static io.earthplatform.minecraft.cubic.client.FarMesher.FIRST_WALL;
import static io.earthplatform.minecraft.cubic.client.FarMesher.GROUPS;
import static io.earthplatform.minecraft.cubic.client.FarMesher.LOOK_MASK;
import static io.earthplatform.minecraft.cubic.client.FarMesher.SEAM_DEPTH;
import static io.earthplatform.minecraft.cubic.client.FarMesher.STORED;
import static io.earthplatform.minecraft.cubic.client.FarMesher.TEXTURED_WATER_LEVELS;
import static io.earthplatform.minecraft.cubic.client.FarMesher.UNIT;
import static io.earthplatform.minecraft.cubic.client.FarMesher.WALL_DEPTH;
import static io.earthplatform.minecraft.cubic.client.FarMesher.at;
import static io.earthplatform.minecraft.cubic.client.FarMesher.evenCover;
import static io.earthplatform.minecraft.cubic.client.FarMesher.foliage;
import static io.earthplatform.minecraft.cubic.client.FarMesher.groundRow;
import static io.earthplatform.minecraft.cubic.client.FarMesher.hides;
import static io.earthplatform.minecraft.cubic.client.FarMesher.match;
import static io.earthplatform.minecraft.cubic.client.FarMesher.multiply;
import static io.earthplatform.minecraft.cubic.client.FarMesher.normals;
import static io.earthplatform.minecraft.cubic.client.FarMesher.quad;
import static io.earthplatform.minecraft.cubic.client.FarMesher.seeThrough;
import static io.earthplatform.minecraft.cubic.client.FarMesher.shade;
import static io.earthplatform.minecraft.cubic.client.FarMesher.side;
import static io.earthplatform.minecraft.cubic.client.FarMesher.sideTops;
import static io.earthplatform.minecraft.cubic.client.FarMesher.smooth;
import static io.earthplatform.minecraft.cubic.client.FarMesher.stack;
import static io.earthplatform.minecraft.cubic.client.FarMesher.surface;
import static io.earthplatform.minecraft.cubic.client.FarMesher.tops;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.minecraft.cubic.client.FarMesher.Built;
import io.earthplatform.minecraft.cubic.client.FarMesher.Job;
import io.earthplatform.minecraft.cubic.client.FarMesher.Part;
import io.earthplatform.minecraft.cubic.client.FarMesher.Piece;
import io.earthplatform.minecraft.cubic.client.FarMesher.Side;
import io.earthplatform.minecraft.cubic.client.FarMesher.WaterLook;
import io.earthplatform.minecraft.cubic.far.Far;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Meshes one far column for {@link FarMesher#build}: reads the column's voxels, collects their
 * visible faces (merged through {@link FarFaceMerger}, or as smooth ground through {@link FarSmoothGround}), packs
 * the opaque ones into face groups and draws water into its own block-format meshes.
 * {@link FarRenderer}'s far mesher threads make one per {@link FarMesher.Job} and drop it once
 * its {@link FarMesher.Built} is returned.
 */
final class FarColumnMesh {
    final Job job;
    final int v, voxel;
    /** Near-cube masks belong to this job only; its drawn snapshot never changes while it meshes. */
    @Nullable final FarCoverage coverage;
    final int[] tops;
    final int[][] sideTops;
    final float[] surface;
    /** Each voxel column's ground slope as a {@link FarMesher#normalCode}. */
    final int[] normals;
    /** Each voxel column's highest ground row (not water or trees), or -1. */
    final int[] groundRows = new int[256];
    /** What each voxel column's ground shows on top and on its step risers (see {@link FarMesher#evenCover}), or null for its own block. */
    @Nullable final BlockState[] cover;
    final FarFaceMerger opaqueFlat = new FarFaceMerger(), waterFlat = new FarFaceMerger();
    /** Side face pieces by direction (north, south, west, east) and voxel column. */
    @SuppressWarnings("unchecked") final List<Piece>[][] pieces = new List[4][256];
    @Nullable ByteBufferBuilder waterMemory;
    @Nullable BufferBuilder water;
    /** Water's own walls, north, south, west, east: water faces the next column's water hides, drawn when that column is not. */
    final ByteBufferBuilder[] wallMemory = new ByteBufferBuilder[4];
    final BufferBuilder[] waterWalls = new BufferBuilder[4];
    /** Water shows its texture only near by: drawn face by face, textured oceans would cost far more faces than merged flat ones. */
    final boolean texturedWater;

    FarColumnMesh(Job job) {
        this.job = job;
        texturedWater = job.textured() && job.level() <= TEXTURED_WATER_LEVELS;
        v = 1 << job.level();
        voxel = v * UNIT;
        coverage = FarMesher.CACHE_COVERAGE && job.drawn() != null && !job.drawn().isEmpty()
                ? new FarCoverage(job.level(), job.x0(), job.z0(), job.bottom(),
                job.height(), job.drops(), job.drawn()) : null;
        tops = tops(job);
        sideTops = sideTops(job);
        surface = surface(job, v);
        for (int cell = 0; cell < 256; cell++) groundRows[cell] = groundRow(job, cell & 15, cell >> 4);
        normals = normals(job, voxel);
        cover = job.level() >= COVER_FROM ? evenCover(job, groundRows) : null;
    }

    Built build() {
        // The ground is drawn as one smooth surface rather than voxels, so only water is meshed voxel by voxel,
        // and on the textured levels trees too, which stand on the ground as blocks do up close.
        boolean smooth = smooth(job), trees = smooth && job.textured();
        if(job.packet()!=null) {
            java.util.BitSet candidates=FarRunCandidates.make(job.packet());
            for(int bit=candidates.nextSetBit(0);bit>=0;bit=candidates.nextSetBit(bit+1)) {
                int cell=bit&255,row=bit>>>8,x=cell&15,z=cell>>4;
                BlockState state=job.packet().block(x,row,z);
                if(!state.isAir())voxel(state,x,row,z);
            }
        } else {
        LevelChunkSection[] sections = job.sections();
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection section = sections[si];
            if (section == null || section.hasOnlyAir()) continue;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState state = section.getBlockState(x, y, z);
                if (state.isAir() || smooth && !seeThrough(state) && !(trees && foliage(state))) continue;
                int vy = si * 16 + y;
                if (drawn(x, vy, z)) continue;
                voxel(state, x, vy, z);
            }
        }
        }
        IntArrayList[] groups = new IntArrayList[GROUPS];
        for (int g = 0; g < GROUPS; g++) groups[g] = new IntArrayList();
        int[] grid = null;
        if (smooth) {
            grid = new FarSmoothGround(this).emit(groups);
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
        for (int g : STORED) { groups[g].getElements(0, quads, at, groups[g].size()); at += groups[g].size(); }
        Part waterPart = part(water, waterMemory);
        Part[] wallParts = new Part[4];
        for (int wall = 0; wall < 4; wall++) wallParts[wall] = part(waterWalls[wall], wallMemory[wall]);
        return new Built(job.key(), job.version(), quads, counts, grid, waterPart, wallParts);
    }

    @Nullable private static Part part(@Nullable BufferBuilder builder, @Nullable ByteBufferBuilder memory) {
        if (builder == null) return null;
        MeshData mesh = builder.build();
        if (mesh == null) { memory.close(); return null; }
        return new Part(mesh, memory);
    }

    private void voxel(BlockState state, int x, int vy, int z) {
        int cell = z * 16 + x, drop = job.drops()[cell];
        boolean liquid = seeThrough(state);
        // Under water, sky light fades a level per block of water above, as it does near by.
        int sky = liquid ? 15 : Mth.clamp(15 - (int) (surface[cell] - ((vy + 1) * voxel - drop) / (float) UNIT), 0, 15);
        int bottom = vy * voxel - drop, top = bottom + Math.round(voxel * Far.fill(state));
        Holder<Biome> biome = null;
        for (Direction direction : DIRECTIONS) {
            if (direction.getAxis() == Direction.Axis.Y) {
                int ny = vy + direction.getStepY();
                BlockState neighbour = at(job, x, ny, z);
                if (drawn(x, ny, z)) {
                    if (liquid && seeThrough(neighbour) || vy < tops[cell] - SEAM_DEPTH) continue;
                } else if (hides(state, neighbour, direction)) continue;
                if (biome == null) biome = FarMesher.biome(job,x,vy,z);
                int height = direction == Direction.UP ? top : bottom;
                BlockState shown = direction == Direction.UP && vy == groundRows[cell] ? cover(cell, state) : state;
                if (!liquid) opaqueFlat.add(direction, x, vy, z, height, look(shown, biome, direction, sky, cell));
                else if (!texturedWater) waterFlat.add(direction, x, vy, z, height, waterLook(state, biome, direction));
                else waterFace(water(), state, biome, direction, x, z, height, 0);
                continue;
            }
            int nx = x + direction.getStepX(), nz = z + direction.getStepZ(), wall = side(nx, nz);
            int neighbourDrop = wall < 0 ? job.drops()[nz * 16 + nx] : sideDrop(wall, nx, nz, drop);
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
                if (drawn(nx, row, nz)) {
                    // The cubes there are drawn up close; water carries on into theirs without a wall.
                    if (liquid && seeThrough(neighbour) || vy < tops[cell] - SEAM_DEPTH) continue;
                    group = direction.get3DDataValue();
                } else if (hides(state, neighbour, direction)) {
                    if (wall < 0) continue;
                    if (liquid) {
                        // Where a coarser or finer level's water meets this, at a slightly different
                        // height, a wall of water closes the slit that showed the dark bed below.
                        if (vy < tops[cell] - WALL_DEPTH) continue;
                        if (biome == null) biome = FarMesher.biome(job,x,vy,z);
                        waterFace(waterWall(wall), state, biome, direction, x, z, from, to - from);
                        continue;
                    }
                    if (vy < sideTops[wall][wall < 2 ? x : z] - WALL_DEPTH) continue;
                    group = FIRST_WALL + wall;
                } else group = direction.get3DDataValue();
                if (biome == null) biome = FarMesher.biome(job,x,vy,z);
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

    private boolean drawn(int x, int vy, int z) {
        return coverage == null ? FarMesher.drawn(job, x, vy, z) : coverage.drawn(x, vy, z);
    }

    /**
     * The drop of a voxel column in the next column; unknown columns count as level with this
     * one. Both columns lower their rows the same way, so a drop compares directly with ours.
     */
    private int sideDrop(int wall, int nx, int nz, int own) {
        Side side = job.sides() == null ? null : job.sides()[wall];
        if (side == null || (nx < 0 || nx > 15) && (nz < 0 || nz > 15)) return own;
        return side.drops()[(nz & 15) * 16 + (nx & 15)];
    }

    /**
     * A face's look id, its light and its ground slope, which faces must share to merge.
     * Foliage and undersides keep their own face's normal.
     */
    private long look(BlockState state, Holder<Biome> biome, Direction direction, int sky, int cell) {
        int id = job.looks().id(job.faces(), state, direction, biome, job.textured());
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
    void pack(IntArrayList out, int x, int z, Direction direction, int width, int y256, int extent, long look) {
        int slot = job.slot(), light = (int) (look & 0xFF);
        out.add(x | z << 5 | direction.get3DDataValue() << 10 | (width - 1) << 13 | light << 17 | (slot & 0x7F) << 25);
        out.add(Math.floorDiv(y256 + 8, 16));
        out.add(Math.min(extent, 0xFFFFF) | (slot >>> 7) << 20);
        out.add((int) (look >>> 8) & LOOK_MASK | (int) (look >>> 40) << 20);
    }

    /** One water face drawn on its own, from {@code y} up by {@code height} (both in 1/256 blocks); up and down faces take no height. */
    private void waterFace(BufferBuilder builder, BlockState state, Holder<Biome> biome, Direction direction, int x, int z, int y, int height) {
        float unit = 1.0F / UNIT;
        ShaderBlocks.begin(builder, job.shaderIds(), state, true, x, 0, z);
        face(builder, state, colour(state, biome, direction), direction, x * v, y * unit, z * v, v, height * unit, v,
                LightTexture.FULL_SKY, texturedWater);
        ShaderBlocks.end(builder, job.shaderIds());
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
        FarFaces.Face face = job.faces().face(state, direction);
        int tint = job.faces().tint(state, biome, face);
        int rgb = texturedWater ? tint : multiply(face.rgb(), tint);
        // Flat faces have no texture to carry water's see-through alpha, so the vertices do.
        int alpha = texturedWater ? 255 : face.rgb() >>> 24;
        return alpha << 24 | shade(rgb, job.shade()[direction.get3DDataValue()]);
    }

    /** One side of a box of blocks, in blocks from the column's base. */
    void face(BufferBuilder builder, BlockState state, int argb, Direction direction,
              float x, float y, float z, float sx, float sy, float sz, int light, boolean textured) {
        FarFaces.Face face = textured ? job.faces().face(state, direction) : null;
        float u0 = face != null ? face.u0() : job.white()[0], u1 = face != null ? face.u1() : job.white()[2];
        float v0 = face != null ? face.v0() : job.white()[1], v1 = face != null ? face.v1() : job.white()[3];
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
