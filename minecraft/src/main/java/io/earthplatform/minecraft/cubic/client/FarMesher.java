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
 * voxel's size, textured with the block's own sprite for that side (or its average colour on
 * coarse levels) and tinted for its biome. Voxels whose cube the client already draws are left
 * out, and count as air for their neighbours so the seam stays closed. Faces hidden only by the
 * next column go in a separate wall per side, drawn when that column is not (where a finer or
 * coarser level takes over), so level changes never open holes. Water goes in its own mesh, drawn
 * see-through like near water, over lake and sea floors that darken with depth.
 */
final class FarMesher {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState(), GROUND = Blocks.STONE.defaultBlockState();
    private static final Direction[] DIRECTIONS = Direction.values();

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
    }

    static Built build(Job job) {
        ByteBufferBuilder memory = new ByteBufferBuilder(65536);
        BufferBuilder builder = new BufferBuilder(memory, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        ByteBufferBuilder[] wallMemory = new ByteBufferBuilder[4];
        BufferBuilder[] walls = new BufferBuilder[4];
        ByteBufferBuilder waterMemory = null;
        BufferBuilder water = null;
        int v = 1 << job.level;
        float[] surface = surface(job, v);
        LevelChunkSection[] sections = job.sections;
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
                    BufferBuilder target = liquid ? null : builder;
                    if (drawn(job, nx, ny, nz)) {
                        // The cubes there are drawn up close; water carries on into theirs without a wall.
                        if (liquid && seeThrough(neighbour)) continue;
                    } else if (hides(state, neighbour, direction)) {
                        int side = side(nx, nz);
                        if (side < 0 || liquid) continue;
                        if (walls[side] == null) {
                            wallMemory[side] = new ByteBufferBuilder(8192);
                            walls[side] = new BufferBuilder(wallMemory[side], VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                        }
                        target = walls[side];
                    }
                    if (target == null) {
                        if (water == null) {
                            waterMemory = new ByteBufferBuilder(16384);
                            water = new BufferBuilder(waterMemory, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                        }
                        target = water;
                    }
                    if (biome == null) biome = section.getBiomes().get(x >> 2, y >> 2, z >> 2);
                    ShaderBlocks.begin(target, job.shaderIds, state, !state.getFluidState().isEmpty(), x, y, z);
                    face(target, job, state, biome, direction, x * v, vy * v, z * v, v, light, liquid);
                    ShaderBlocks.end(target, job.shaderIds);
                }
            }
        }
        Part[] wallParts = new Part[4];
        boolean anyWall = false;
        for (int side = 0; side < 4; side++) {
            if (walls[side] == null) continue;
            wallParts[side] = part(walls[side], wallMemory[side]);
            anyWall |= wallParts[side] != null;
        }
        return new Built(job.key, job.version, part(builder, memory), anyWall ? wallParts : null,
                water == null ? null : part(water, waterMemory));
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

    private static void face(BufferBuilder builder, Job job, BlockState state, Holder<Biome> biome, Direction direction,
                             float x, float y, float z, float v, int light, boolean seeThrough) {
        FarFaces.Face face = job.faces.face(state, direction);
        int tint = job.faces.tint(state, biome, face);
        int rgb = job.textured ? tint : multiply(face.rgb(), tint);
        // Flat faces have no texture to carry water's see-through alpha, so the vertices do.
        int alpha = seeThrough && !job.textured ? face.rgb() >>> 24 : 255;
        rgb = alpha << 24 | shade(rgb, job.shade[direction.get3DDataValue()]);
        float u0 = job.textured ? face.u0() : job.white[0], u1 = job.textured ? face.u1() : job.white[2];
        float v0 = job.textured ? face.v0() : job.white[1], v1 = job.textured ? face.v1() : job.white[3];
        float x1 = x + v, y1 = y + v * Far.fill(state), z1 = z + v;
        float nx = direction.getStepX(), ny = direction.getStepY(), nz = direction.getStepZ();
        switch (direction) {
            case UP -> quad(builder, rgb, light, nx, ny, nz, u0, v0, u1, v1, x, y1, z, x, y1, z1, x1, y1, z1, x1, y1, z);
            case DOWN -> quad(builder, rgb, light, nx, ny, nz, u0, v0, u1, v1, x, y, z1, x, y, z, x1, y, z, x1, y, z1);
            case NORTH -> quad(builder, rgb, light, nx, ny, nz, u0, v0, u1, v1, x1, y1, z, x1, y, z, x, y, z, x, y1, z);
            case SOUTH -> quad(builder, rgb, light, nx, ny, nz, u0, v0, u1, v1, x, y1, z1, x, y, z1, x1, y, z1, x1, y1, z1);
            case WEST -> quad(builder, rgb, light, nx, ny, nz, u0, v0, u1, v1, x, y1, z, x, y, z, x, y, z1, x, y1, z1);
            case EAST -> quad(builder, rgb, light, nx, ny, nz, u0, v0, u1, v1, x1, y1, z1, x1, y, z1, x1, y, z, x1, y1, z);
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
