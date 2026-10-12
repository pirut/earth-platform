package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.far.Far;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Builds one far column as block-format quads: every voxel face that touches air, as a box the
 * voxel's size, textured with the block's own sprite for that side and tinted for its biome. On
 * coarse levels, and on walls, faces take the sprite's average colour instead, and matching
 * neighbours merge into one quad. Voxels whose cube the client already draws are left
 * out, and count as air for their neighbours so the seam stays closed. Faces hidden only by the
 * next column go in a separate wall per side, drawn when that column is not (where a finer or
 * coarser level takes over), so level changes never open holes. Water goes in its own mesh, drawn
 * see-through like near water, over lake and sea floors that darken with depth.
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

    private FarMesher() {}

    /** A same-level column next to this one: north, south, west, east. */
    record Side(@Nullable LevelChunkSection[] sections, int bottom) {}

    /** Everything a worker needs; sections are never changed after they arrive, so sharing them is safe. */
    record Job(long key, int version, int level, int x0, int z0, int bottom, @Nullable LevelChunkSection[] sections,
               @Nullable Side[] sides, @Nullable LongOpenHashSet drawn, float[] shade, boolean textured,
               float[] white, FarFaces faces, @Nullable Object2IntMap<BlockState> shaderIds) {}

    /** A finished mesh and the memory holding it. */
    record Part(MeshData mesh, ByteBufferBuilder memory) {
        void free() { mesh.close(); memory.close(); }
        long bytes() { return mesh.vertexBuffer().remaining(); }
    }

    /**
     * The column's opaque mesh, its four walls (north, south, west, east) and its water, drawn
     * see-through after everything opaque; any may be null when empty.
     */
    record Built(long key, int version, @Nullable Part main, @Nullable Part[] walls, @Nullable Part water) {
        void free() {
            if (main != null) main.free();
            if (walls != null) for (Part wall : walls) if (wall != null) wall.free();
            if (water != null) water.free();
        }

        long bytes() {
            long total = (main == null ? 0 : main.bytes()) + (water == null ? 0 : water.bytes());
            if (walls != null) for (Part wall : walls) if (wall != null) total += wall.bytes();
            return total;
        }
    }

    static Built build(Job job) {
        Targets targets = new Targets();
        int v = 1 << job.level;
        float[] surface = surface(job, v);
        int[] tops = tops(job.sections);
        int[][] sideTops = sideTops(job);
        LevelChunkSection[] sections = job.sections;
        // Flat faces (every face on coarse levels, and walls on all) are gathered first and matching
        // neighbours merged into larger quads.
        Merger merger = new Merger(sections.length * 16);
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection section = sections[si];
            if (section == null || section.hasOnlyAir()) continue;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState state = section.getBlockState(x, y, z);
                if (state.isAir()) continue;
                int vy = si * 16 + y;
                if (drawn(job, x, vy, z)) continue;
                boolean liquid = seeThrough(state);
                // Under water, sky light fades a level per block of water above, as it does near by.
                int light = liquid ? LightTexture.FULL_SKY
                        : LightTexture.pack(0, Mth.clamp(15 - (int) (surface[z * 16 + x] - (vy + 1) * v), 0, 15));
                Holder<Biome> biome = null;
                for (Direction direction : DIRECTIONS) {
                    int nx = x + direction.getStepX(), ny = vy + direction.getStepY(), nz = z + direction.getStepZ();
                    BlockState neighbour = at(job, nx, ny, nz);
                    int wall = -1;
                    if (drawn(job, nx, ny, nz)) {
                        // The cubes there are drawn up close; water carries on into theirs without a wall.
                        if (liquid && seeThrough(neighbour) || vy < tops[z * 16 + x] - SEAM_DEPTH) continue;
                    } else if (hides(state, neighbour, direction)) {
                        wall = side(nx, nz);
                        if (wall < 0 || liquid || vy < sideTops[wall][wall < 2 ? x : z] - WALL_DEPTH) continue;
                    }
                    if (biome == null) biome = section.getBiomes().get(x >> 2, y >> 2, z >> 2);
                    int target = wall >= 0 ? wall : liquid ? Targets.WATER : Targets.MAIN;
                    boolean textured = job.textured && wall < 0;
                    int argb = colour(job, state, biome, direction, liquid, textured);
                    if (!textured) { merger.add(direction, x, vy, z, new Look(state, argb, light, target)); continue; }
                    BufferBuilder builder = targets.builder(target);
                    ShaderBlocks.begin(builder, job.shaderIds, state, !state.getFluidState().isEmpty(), x, y, z);
                    face(builder, job, state, argb, direction, x * v, vy * v, z * v, v, v, v, light, true);
                    ShaderBlocks.end(builder, job.shaderIds);
                }
            }
        }
        merger.emit(job, targets, v);
        return targets.built(job);
    }

    /** The column's meshes, each started when its first face arrives: the four walls, then the main mesh and the water. */
    private static final class Targets {
        static final int MAIN = 4, WATER = 5;
        private final ByteBufferBuilder[] memory = new ByteBufferBuilder[6];
        private final BufferBuilder[] builders = new BufferBuilder[6];

        BufferBuilder builder(int index) {
            if (builders[index] == null) {
                memory[index] = new ByteBufferBuilder(index == MAIN ? 65536 : 8192);
                builders[index] = new BufferBuilder(memory[index], VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            }
            return builders[index];
        }

        @Nullable private Part part(int index) { return builders[index] == null ? null : FarMesher.part(builders[index], memory[index]); }

        Built built(Job job) {
            Part[] walls = new Part[4];
            boolean anyWall = false;
            for (int side = 0; side < 4; side++) anyWall |= (walls[side] = part(side)) != null;
            return new Built(job.key, job.version, part(MAIN), anyWall ? walls : null, part(WATER));
        }
    }

    /** What a face needs to match a neighbour's to share one quad with it, including which mesh it goes in. */
    private record Look(BlockState state, int argb, int light, int target) {}

    /**
     * Collects a column's faces per direction and merges each plane's runs of matching faces into
     * rectangles, so flat ground and long cliffs cost a few quads instead of one per voxel.
     */
    private static final class Merger {
        private final int height;
        private final int[][] faces = new int[6][];
        private final java.util.List<Look> looks = new java.util.ArrayList<>();
        private final java.util.Map<Look, Integer> ids = new java.util.HashMap<>();

        Merger(int height) { this.height = height; }

        void add(Direction direction, int x, int vy, int z, Look look) {
            int[] plane = faces[direction.ordinal()];
            if (plane == null) plane = faces[direction.ordinal()] = new int[16 * 16 * height];
            Integer id = ids.get(look);
            if (id == null) { id = looks.size() + 1; looks.add(look); ids.put(look, id); }
            plane[(vy * 16 + z) * 16 + x] = id;
        }

        void emit(Job job, Targets targets, int v) {
            for (Direction direction : DIRECTIONS) {
                int[] plane = faces[direction.ordinal()];
                if (plane == null) continue;
                // Each slice is a plane facing this direction; u and w run across it.
                boolean vertical = direction.getAxis() == Direction.Axis.Y;
                int slices = vertical ? height : 16, across = vertical ? 16 : height;
                for (int slice = 0; slice < slices; slice++) {
                    for (int w = 0; w < across; w++) for (int u = 0; u < 16; u++) {
                        int id = plane[index(direction, slice, u, w)];
                        if (id == 0) continue;
                        Look look = looks.get(id - 1);
                        int width = 1;
                        while (u + width < 16 && plane[index(direction, slice, u + width, w)] == id) width++;
                        // Part-filled water only ever tops a stack, so it never merges upward.
                        int tall = 1;
                        boolean grow = vertical || Far.fill(look.state) >= 1.0F;
                        rows:
                        while (grow && w + tall < across) {
                            for (int k = 0; k < width; k++) if (plane[index(direction, slice, u + k, w + tall)] != id) break rows;
                            tall++;
                        }
                        for (int t = 0; t < tall; t++) for (int k = 0; k < width; k++) plane[index(direction, slice, u + k, w + t)] = 0;
                        rectangle(job, targets, v, direction, slice, u, w, width, tall, look);
                    }
                }
            }
        }

        private static int index(Direction direction, int slice, int u, int w) {
            return switch (direction.getAxis()) {
                case Y -> (slice * 16 + w) * 16 + u;
                case Z -> (w * 16 + slice) * 16 + u;
                case X -> (w * 16 + u) * 16 + slice;
            };
        }

        private static void rectangle(Job job, Targets targets, int v, Direction direction, int slice, int u, int w, int width, int tall, Look look) {
            int x, vy, z, sx, sy, sz;
            switch (direction.getAxis()) {
                case Y -> { x = u; vy = slice; z = w; sx = width; sy = 1; sz = tall; }
                case Z -> { x = u; vy = w; z = slice; sx = width; sy = tall; sz = 1; }
                default -> { x = slice; vy = w; z = u; sx = 1; sy = tall; sz = width; }
            }
            BufferBuilder builder = targets.builder(look.target);
            BlockState state = look.state;
            ShaderBlocks.begin(builder, job.shaderIds, state, !state.getFluidState().isEmpty(), x, vy & 15, z);
            face(builder, job, state, look.argb, direction, x * v, vy * v, z * v, sx * v, sy * v, sz * v, look.light, false);
            ShaderBlocks.end(builder, job.shaderIds);
        }
    }

    /** The highest voxel row holding anything in each voxel column, or -1 where all is air. */
    private static int[] tops(LevelChunkSection[] sections) {
        int[] tops = new int[256];
        java.util.Arrays.fill(tops, -1);
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

    /** Height of the water's surface above the column's base in each block column, or below zero where there is none. */
    private static float[] surface(Job job, int v) {
        float[] surface = new float[256];
        java.util.Arrays.fill(surface, -1.0F);
        LevelChunkSection[] sections = job.sections;
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection section = sections[si];
            if (section == null || section.hasOnlyAir()) continue;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState state = section.getBlockState(x, y, z);
                if (seeThrough(state)) surface[z * 16 + x] = Math.max(surface[z * 16 + x], (si * 16 + y + Far.fill(state)) * v);
            }
        }
        return surface;
    }

    /** Water, which is drawn see-through after the opaque terrain; lava and the rest are opaque. */
    private static boolean seeThrough(BlockState state) {
        return state.getFluidState().is(FluidTags.WATER);
    }

    @Nullable private static Part part(BufferBuilder builder, ByteBufferBuilder memory) {
        MeshData mesh = builder.build();
        if (mesh == null) { memory.close(); return null; }
        return new Part(mesh, memory);
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

    /** True when the cubes under a voxel are all drawn by the cube renderer. */
    private static boolean drawn(Job job, int x, int vy, int z) {
        LongOpenHashSet drawn = job.drawn;
        if (drawn == null) return false;
        int v = 1 << job.level;
        int bx = job.x0 + x * v, by = job.bottom * (Far.SIZE << job.level) + vy * v, bz = job.z0 + z * v;
        int cubes = Math.max(1, v >> 4);
        for (int cy = by >> 4; cy < (by >> 4) + cubes; cy++) for (int cz = bz >> 4; cz < (bz >> 4) + cubes; cz++)
            for (int cx = bx >> 4; cx < (bx >> 4) + cubes; cx++) if (!drawn.contains(CubeKey.pack(cx, cy, cz))) return false;
        return true;
    }

    /** A face's vertex colour: its biome tint, times its average colour when flat, shaded for its side. */
    private static int colour(Job job, BlockState state, Holder<Biome> biome, Direction direction, boolean seeThrough, boolean textured) {
        FarFaces.Face face = job.faces.face(state, direction);
        int tint = job.faces.tint(state, biome, face);
        int rgb = textured ? tint : multiply(face.rgb(), tint);
        // Flat faces have no texture to carry water's see-through alpha, so the vertices do.
        int alpha = seeThrough && !textured ? face.rgb() >>> 24 : 255;
        return alpha << 24 | shade(rgb, job.shade[direction.get3DDataValue()]);
    }

    /** One side of a box of blocks; a part-filled top voxel lowers the box's top by what it lacks. */
    private static void face(BufferBuilder builder, Job job, BlockState state, int argb, Direction direction,
                             float x, float y, float z, float sx, float sy, float sz, int light, boolean textured) {
        FarFaces.Face face = textured ? job.faces.face(state, direction) : null;
        float u0 = face != null ? face.u0() : job.white[0], u1 = face != null ? face.u1() : job.white[2];
        float v0 = face != null ? face.v0() : job.white[1], v1 = face != null ? face.v1() : job.white[3];
        float voxel = 1 << job.level;
        float x1 = x + sx, y1 = y + sy - voxel * (1.0F - Far.fill(state)), z1 = z + sz;
        float nx = direction.getStepX(), ny = direction.getStepY(), nz = direction.getStepZ();
        switch (direction) {
            case UP -> quad(builder, argb, light, nx, ny, nz, u0, v0, u1, v1, x, y1, z, x, y1, z1, x1, y1, z1, x1, y1, z);
            case DOWN -> quad(builder, argb, light, nx, ny, nz, u0, v0, u1, v1, x, y, z1, x, y, z, x1, y, z, x1, y, z1);
            case NORTH -> quad(builder, argb, light, nx, ny, nz, u0, v0, u1, v1, x1, y1, z, x1, y, z, x, y, z, x, y1, z);
            case SOUTH -> quad(builder, argb, light, nx, ny, nz, u0, v0, u1, v1, x, y1, z1, x, y, z1, x1, y, z1, x1, y1, z1);
            case WEST -> quad(builder, argb, light, nx, ny, nz, u0, v0, u1, v1, x, y1, z, x, y, z, x, y, z1, x, y1, z1);
            case EAST -> quad(builder, argb, light, nx, ny, nz, u0, v0, u1, v1, x1, y1, z1, x1, y, z1, x1, y, z, x1, y1, z);
        }
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
