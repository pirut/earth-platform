package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.far.Far;
import io.earthplatform.minecraft.cubic.gen.FarSurfacePacket;
import io.earthplatform.minecraft.diagnostics.PerfFailures;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
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
    static final BlockState AIR = Blocks.AIR.defaultBlockState(), GROUND = Blocks.STONE.defaultBlockState();
    static final Direction[] DIRECTIONS = Direction.values();
    /**
     * How far below the next column's ground a wall reaches, in voxels: deep enough to close the
     * seam against a coarser or finer level's ground there, which differs by a few voxels at most.
     */
    static final int WALL_DEPTH = 8;
    /** How far below a column's own top it closes seams against cubes drawn up close, in voxels. */
    static final int SEAM_DEPTH = 16;
    /** Face groups: the column's own faces by direction (down, up, north, south, west, east), then its walls north, south, west, east. */
    static final int GROUPS = 10, FIRST_WALL = 6;
    /**
     * The order groups are stored in: west, north, up, south, east, so the three a camera can see
     * of a column (up and one of each pair of sides) mostly lie side by side and draw as one range.
     */
    static final int[] STORED = {0, 4, 2, 1, 3, 5, 6, 7, 8, 9};
    /** Heights inside the mesher are in 1/256 blocks, as drops are; faces store 1/16 blocks. */
    static final int UNIT = Far.DROP_UNIT;
    /** A face's fourth int: its look in the low 20 bits, its ground slope's {@link #normalCode} in the high 12. */
    static final int LOOK_MASK = (1 << 20) - 1;

    private FarMesher() {}

    /** A same-level column next to this one: north, south, west, east. */
    record Side(@Nullable LevelChunkSection[] sections, int bottom, char[] drops, @Nullable FarSurfacePacket packet) {
        Side(@Nullable LevelChunkSection[] sections, int bottom, char[] drops) { this(sections, bottom, drops, null); }
    }

    /** Everything a worker needs; sections are never changed after they arrive, so sharing them is safe. */
    record Job(long key, int version, int level, int slot, int x0, int z0, int bottom, LevelChunkSection[] sections, char[] drops,
               @Nullable Side[] sides, @Nullable Side[] diagonals, @Nullable LongOpenHashSet drawn, boolean textured, FarLooks looks,
               FarFaces faces, @Nullable Object2IntMap<BlockState> shaderIds, float[] shade, float[] white, @Nullable FarSurfacePacket packet) {
        Job(long key, int version, int level, int slot, int x0, int z0, int bottom, LevelChunkSection[] sections, char[] drops,
            @Nullable Side[] sides, @Nullable Side[] diagonals, @Nullable LongOpenHashSet drawn, boolean textured, FarLooks looks,
            FarFaces faces, @Nullable Object2IntMap<BlockState> shaderIds, float[] shade, float[] white) {
            this(key,version,level,slot,x0,z0,bottom,sections,drops,sides,diagonals,drawn,textured,looks,faces,shaderIds,shade,white,null);
        }
        int height() { return packet == null ? sections.length * 16 : packet.height(); }
        Job dense() {
            Side[] plainSides=denseSides(sides),plainDiagonals=denseSides(diagonals);
            if(packet==null&&plainSides==sides&&plainDiagonals==diagonals)return this;
            return new Job(key,version,level,slot,x0,z0,bottom,packet==null?sections:packet.materializeForMeshing().toArray(LevelChunkSection[]::new),drops,
                    plainSides,plainDiagonals,drawn,textured,looks,faces,shaderIds,shade,white);
        }
    }
    @Nullable private static Side[] denseSides(@Nullable Side[] sides) {
        if(sides==null)return null;
        Side[] dense=sides;
        for(int i=0;i<dense.length;i++) if(dense[i]!=null && dense[i].packet!=null) {
            if(dense==sides)dense=sides.clone();
            Side side=dense[i];dense[i]=new Side(side.packet.materializeForMeshing().toArray(LevelChunkSection[]::new),side.bottom,side.drops);
        }
        return dense;
    }
    static net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome(Job job,int x,int row,int z) {
        return job.packet == null ? job.sections[row>>4].getBiomes().get(x>>2,(row&15)>>2,z>>2) : job.packet.biome(x,z);
    }

    /** A finished water mesh and the memory holding it. */
    record Part(MeshData mesh, ByteBufferBuilder memory) {
        void free() { mesh.close(); memory.close(); }
        long bytes() { return mesh.vertexBuffer().remaining(); }
    }

    /**
     * The column's opaque faces, 4 ints each, grouped as {@link #GROUPS} with {@code counts}
     * faces per group in order, the points of its smooth ground (see {@link FarSmoothGround}) or null, and
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

    private static final boolean AUTO_RUNS=Boolean.parseBoolean(System.getProperty("earthplatform.far.runMesher","true"));
    private static final java.util.concurrent.atomic.LongAdder RUN_ATTEMPTS=new java.util.concurrent.atomic.LongAdder(),
            RUN_USED=new java.util.concurrent.atomic.LongAdder(),RUN_FALLBACK=new java.util.concurrent.atomic.LongAdder();
    record RunStats(long attempts,long used,long fallback) {}
    static RunStats runStats(){return new RunStats(RUN_ATTEMPTS.sum(),RUN_USED.sum(),RUN_FALLBACK.sum());}
    static boolean automaticRuns(){return AUTO_RUNS;}
    static Built buildDenseOracle(Job job){return new FarColumnMesh(job.dense()).build();}
    static Built buildRunPrototype(Job job) {
        if(job.packet==null&&job.level>=3&&!smooth(job)&&(job.drawn==null||job.drawn.isEmpty())) {
            RUN_ATTEMPTS.increment();
            try {
                var packet=FarSurfacePacket.fromDense(job.level,job.bottom,job.sections,job.drops);
                if(packet!=null)job=new Job(job.key,job.version,job.level,job.slot,job.x0,job.z0,job.bottom,job.sections,job.drops,
                        job.sides,job.diagonals,job.drawn,job.textured,job.looks,job.faces,job.shaderIds,job.shade,job.white,packet);
            }catch(RuntimeException failure){PerfFailures.record(PerfFailures.Stage.RUN_CONVERSION,failure);}
            if(job.packet==null)RUN_FALLBACK.increment();else RUN_USED.increment();
        }
        return buildPrepared(job);
    }
    static Built build(Job job) { return AUTO_RUNS&&job.packet==null?buildRunPrototype(job):buildPrepared(job); }
    private static Built buildPrepared(Job job) {
        // Bounded direct consumer: coverage masks, smooth terrain and edit overlays retain the dense oracle.
        if (job.packet != null && (smooth(job) || job.drawn != null && !job.drawn.isEmpty() || job.packet.editCount() != 0)) job=job.dense();
        return new FarColumnMesh(job).build();
    }

    /** Off with {@code -Dearthplatform.far.cacheCoverage=false} to compare the original cube lookup loop. */
    static final boolean CACHE_COVERAGE = Boolean.parseBoolean(System.getProperty("earthplatform.far.cacheCoverage", "true"));

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

    /** A visible part of a voxel's side, from and to in 1/256 blocks above the column's base. */
    static final class Piece {
        int from, to;
        final long look;
        final int group;
        boolean used;

        Piece(int from, int to, long look, int group) { this.from = from; this.to = to; this.look = look; this.group = group; }
    }

    /** Joins a voxel column's pieces that touch and look the same into one tall piece. */
    static void stack(List<Piece> list) {
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

    @Nullable static Piece match(@Nullable List<Piece> list, Piece piece) {
        if (list == null) return null;
        for (Piece other : list) {
            if (!other.used && other != piece && other.from == piece.from && other.to == piece.to && other.look == piece.look
                    && other.group == piece.group) return other;
        }
        return null;
    }

    record WaterLook(BlockState state, int argb, Direction direction) {}

    interface Rectangle {
        void accept(Direction direction, int x, int z, int width, int depth, int height, Object look);
    }

    /** The highest voxel row holding anything in each voxel column, or -1 where all is air. */
    static int[] tops(LevelChunkSection[] sections) {
        int[] tops = new int[256];
        for (int i = 0; i < 256; i++) tops[i] = top(sections, i & 15, i >> 4, false);
        return tops;
    }

    static int[] tops(Job job) {
        if(job.packet==null)return tops(job.sections);
        int[] tops=new int[256];
        for(int cell=0;cell<256;cell++)tops[cell]=job.packet.top(cell,state->!state.isAir());
        return tops;
    }
    private static int top(Side side,int x,int z,boolean solid) {
        return side.packet==null ? top(side.sections,x,z,solid) : side.packet.top(z*16+x,state->!state.isAir() && !(solid && seeThrough(state)));
    }
    static int groundRow(Job job,int x,int z) {
        return job.packet==null ? groundRow(job.sections,x,z) : job.packet.top(z*16+x,state->!state.isAir() && !seeThrough(state) && !foliage(state));
    }
    private static int groundRow(Side side,int x,int z) {
        return side.packet==null ? groundRow(side.sections,x,z) : side.packet.top(z*16+x,state->!state.isAir() && !seeThrough(state) && !foliage(state));
    }

    /**
     * For each side (north, south, west, east) and place along it, the highest solid voxel row of
     * the next column's facing edge, in this column's rows; walls only need to reach a little below it.
     */
    static int[][] sideTops(Job job) {
        int[][] tops = new int[4][16];
        for (int side = 0; side < 4; side++) {
            Side next = job.sides == null ? null : job.sides[side];
            for (int along = 0; along < 16; along++) {
                if (next == null || next.sections == null && next.packet == null) { tops[side][along] = Integer.MAX_VALUE; continue; }
                int x = side == 2 ? 15 : side == 3 ? 0 : along, z = side == 0 ? 15 : side == 1 ? 0 : along;
                tops[side][along] = top(next, x, z, true) + (next.bottom - job.bottom) * 16;
            }
        }
        return tops;
    }

    /** The highest voxel row in a voxel column holding anything (or, when {@code solid}, anything but water); below the sections is ground. */
    static int top(@Nullable LevelChunkSection[] sections, int x, int z, boolean solid) {
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
    static float[] surface(Job job, int v) {
        float[] surface = new float[256];
        java.util.Arrays.fill(surface, -1.0F);
        if(job.packet!=null) {
            for(int cell=0;cell<256;cell++) for(int run=job.packet.firstRun(cell);run<job.packet.afterLastRun(cell);run++) {
                BlockState state=job.packet.runState(run);
                if(seeThrough(state)) surface[cell]=Math.max(surface[cell],(job.packet.runEnd(run)-1+Far.fill(state))*v-job.drops[cell]/(float)UNIT);
            }
            return surface;
        }
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
    static int[] normals(Job job, int voxel) {
        int[] ground = new int[18 * 18];
        boolean[] known = new boolean[18 * 18];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int cell = z * 16 + x;
            ground[(z + 1) * 18 + x + 1] = (groundRow(job, x, z) + 1) * voxel - job.drops[cell];
            known[(z + 1) * 18 + x + 1] = true;
        }
        for (int side = 0; side < 4 && job.sides != null; side++) {
            Side next = job.sides[side];
            if (next == null || next.sections == null && next.packet == null) continue;
            for (int along = 0; along < 16; along++) {
                int x = side == 2 ? 15 : side == 3 ? 0 : along, z = side == 0 ? 15 : side == 1 ? 0 : along;
                int gx = side == 2 ? 0 : side == 3 ? 17 : along + 1, gz = side == 0 ? 0 : side == 1 ? 17 : along + 1;
                int row = groundRow(next, x, z) + (next.bottom - job.bottom) * 16;
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
            cover[cell] = at(job, cell & 15, groundRows[cell], cell >> 4);
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
    static int groundRow(LevelChunkSection[] sections, int x, int z) {
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
    static boolean foliage(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
    }

    /** Water, which is drawn see-through after the opaque terrain; lava and the rest are opaque. */
    static boolean seeThrough(BlockState state) {
        // Most voxels hold no fluid; asking the tag for those cost a third of meshing.
        var fluid = state.getFluidState();
        return !fluid.isEmpty() && fluid.is(FluidTags.WATER);
    }

    /** Which neighbouring column a voxel position outside this one lies in (north, south, west, east), or -1 inside. */
    static int side(int x, int z) {
        return z < 0 ? 0 : z > 15 ? 1 : x < 0 ? 2 : x > 15 ? 3 : -1;
    }

    /**
     * Whether a neighbouring voxel covers this voxel's face on its side. Water is see-through, so
     * it only covers water: above and below it, and beside it where it stands at least as high.
     */
    static boolean hides(BlockState state, BlockState neighbour, Direction direction) {
        if (neighbour.isAir()) return false;
        if (!seeThrough(neighbour)) return true;
        if (!seeThrough(state)) return false;
        return direction.getAxis() == Direction.Axis.Y || Far.fill(state) <= Far.fill(neighbour);
    }

    /** The voxel at a position relative to this column; below a column's sections is ground, above is air. */
    static BlockState at(Job job, int x, int vy, int z) {
        if (x >= 0 && x < 16 && z >= 0 && z < 16) return job.packet==null ? at(job.sections, vy, x, z) : packetAt(job.packet,vy,x,z);
        Side side = job.sides == null ? null : job.sides[side(x, z)];
        if (side == null || (x < 0 || x > 15) && (z < 0 || z > 15)) return AIR;
        int row=vy+(job.bottom-side.bottom)*16;
        return side.packet==null ? at(side.sections,row,x&15,z&15) : packetAt(side.packet,row,x&15,z&15);
    }

    private static BlockState packetAt(FarSurfacePacket packet,int row,int x,int z) {
        return row<0?GROUND:row>=packet.height()?AIR:packet.block(x,row,z);
    }

    static BlockState at(LevelChunkSection[] sections, int vy, int x, int z) {
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
    static boolean drawn(Job job, int x, int vy, int z) {
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
    static void quad(BufferBuilder builder, int argb, int light, float nx, float ny, float nz, float u0, float v0, float u1, float v1,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        builder.addVertex(ax, ay, az).setColor(argb).setUv(u0, v0).setLight(light).setNormal(nx, ny, nz);
        builder.addVertex(bx, by, bz).setColor(argb).setUv(u0, v1).setLight(light).setNormal(nx, ny, nz);
        builder.addVertex(cx, cy, cz).setColor(argb).setUv(u1, v1).setLight(light).setNormal(nx, ny, nz);
        builder.addVertex(dx, dy, dz).setColor(argb).setUv(u1, v0).setLight(light).setNormal(nx, ny, nz);
    }

    static int multiply(int a, int b) {
        return (a >> 16 & 255) * (b >> 16 & 255) / 255 << 16 | (a >> 8 & 255) * (b >> 8 & 255) / 255 << 8 | (a & 255) * (b & 255) / 255;
    }

    static int shade(int rgb, float shade) {
        int r = Math.min(255, (int) ((rgb >> 16 & 255) * shade)), g = Math.min(255, (int) ((rgb >> 8 & 255) * shade));
        int b = Math.min(255, (int) ((rgb & 255) * shade));
        return r << 16 | g << 8 | b;
    }
}
