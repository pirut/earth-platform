package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import io.earthplatform.minecraft.cubic.CubeKey;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.system.MemoryUtil;

/**
 * Near cube meshes kept together by region, 8 cubes a side, so a layer is drawn with one call a buffer in place of one
 * a cube: every cube had its own vertex array and buffer, and a view of a few thousand cubes was that many binds,
 * uniform uploads and draws for each of the three opaque layers, and again for a shader pack's shadow map.
 *
 * <p>A region's layer holds its cubes' meshes in a few buffers of fixed size, each mesh a run of vertices (and, for the
 * sorted translucent layer, of indices) given out by {@link CubeRanges}. A buffer never grows and no mesh ever moves: a
 * full region gets another buffer. That keeps to calls every OpenGL 3.2 has, a Mac's among them (which silently lacks
 * copying between buffers). The vertices are placed in the region, not the cube ({@link #shift}), so one chunk offset,
 * the region's, serves every cube in it, and {@code glMultiDrawElementsBaseVertex} draws those in view.
 *
 * <p>The same triangles in the same order as cube by cube, except that the opaque layers go region by region (nearest
 * first), each region's cubes nearest first; translucent ones keep their exact order back to front, drawn in runs of
 * cubes that share a buffer. {@code -Dearthplatform.cubes.regions=false} gives every cube its own buffer as before,
 * for comparing.
 */
final class CubeRegions {
    static final boolean ON = !"false".equals(System.getProperty("earthplatform.cubes.regions"));
    /** A region is 1 &lt;&lt; SHIFT cubes a side. */
    static final int SHIFT = 3, MASK = (1 << SHIFT) - 1;
    /** Vertices the first, second and later buffers of a region's layer hold; a larger mesh gets one its own size. */
    private static final int[] CAPACITY = {8192, 32768, 131072};

    /** One mesh's place in a buffer. */
    static final class Slice {
        private final Arena arena;
        private final int firstVertex, vertexCount, firstIndex, indexCount;
        private boolean freed;

        private Slice(Arena arena, int firstVertex, int vertexCount, int firstIndex, int indexCount) {
            this.arena = arena; this.firstVertex = firstVertex; this.vertexCount = vertexCount;
            this.firstIndex = firstIndex; this.indexCount = indexCount;
        }
    }

    private static final class Region {
        final long key;
        /** Blocks. */
        final int x, y, z;
        @SuppressWarnings("unchecked") final List<Arena>[] layers = new List[CubeMesher.LAYERS.size()];
        int arenas;

        Region(long key) {
            this.key = key;
            x = CubeKey.x(key) << SHIFT + 4; y = CubeKey.y(key) << SHIFT + 4; z = CubeKey.z(key) << SHIFT + 4;
        }
    }

    /** One buffer of a region's layer: a vertex array, its vertices and, where meshes bring sorted indices, those. */
    private static final class Arena {
        final Region region;
        final int layer;
        final VertexFormat format;
        final boolean sorted;
        final CubeRanges vertexSpace;
        @Nullable final CubeRanges indexSpace;
        int array, vertices, indices;
        int live;
        /** Draws waiting: index count, base vertex and first index, three ints each. */
        int[] queue = new int[48];
        int queued;

        Arena(Region region, int layer, VertexFormat format, boolean sorted, int capacity) {
            this.region = region; this.layer = layer; this.format = format; this.sorted = sorted;
            vertexSpace = new CubeRanges(capacity);
            // Quads: six indices to four vertices.
            indexSpace = sorted ? new CubeRanges(capacity / 4 * 6) : null;
            array = GlStateManager._glGenVertexArrays();
            vertices = GlStateManager._glGenBuffers();
            GlStateManager._glBindVertexArray(array);
            GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, vertices);
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, (long) capacity * format.getVertexSize(), GL15C.GL_DYNAMIC_DRAW);
            format.setupBufferState();
            if (sorted) {
                indices = GlStateManager._glGenBuffers();
                GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indices);
                GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, (long) indexSpace.capacity() * Integer.BYTES, GL15C.GL_DYNAMIC_DRAW);
            }
        }

        void close() {
            RenderSystem.glDeleteBuffers(vertices);
            if (indices != 0) RenderSystem.glDeleteBuffers(indices);
            RenderSystem.glDeleteVertexArrays(array);
            array = vertices = indices = 0;
        }
    }

    private final Long2ObjectOpenHashMap<Region> regions = new Long2ObjectOpenHashMap<>();
    /** Buffers that held nothing when last looked at; still empty a look later, they go. */
    private final List<Arena> idle = new ArrayList<>();
    private int arenaCount;
    private long bytes, usedBytes;

    int regionCount() { return regions.size(); }
    int bufferCount() { return arenaCount; }
    /** Bytes of GPU memory the buffers take, used or not. */
    long bytes() { return bytes; }
    /** Bytes of them that hold meshes. */
    long usedBytes() { return usedBytes; }

    /**
     * Moves a mesh's vertices from their place in the cube to their place in its region; on the mesher's thread.
     * Whole blocks, so every coordinate stays as exact as it was.
     */
    static void shift(MeshData mesh, int cubeX, int cubeY, int cubeZ) {
        float dx = (cubeX & MASK) << 4, dy = (cubeY & MASK) << 4, dz = (cubeZ & MASK) << 4;
        if (dx == 0.0F && dy == 0.0F && dz == 0.0F) return;
        MeshData.DrawState state = mesh.drawState();
        ByteBuffer data = mesh.vertexBuffer();
        int stride = state.format().getVertexSize();
        long at = MemoryUtil.memAddress(data) + state.format().getOffset(VertexFormatElement.POSITION);
        for (int i = 0, count = state.vertexCount(); i < count; i++, at += stride) {
            MemoryUtil.memPutFloat(at, MemoryUtil.memGetFloat(at) + dx);
            MemoryUtil.memPutFloat(at + 4, MemoryUtil.memGetFloat(at + 4) + dy);
            MemoryUtil.memPutFloat(at + 8, MemoryUtil.memGetFloat(at + 8) + dz);
        }
    }

    /** Uploads a cube's mesh for a layer (and closes it); null when it holds nothing. Leaves no vertex array bound. */
    @Nullable Slice upload(long cube, int layer, MeshData mesh) {
        try (mesh) {
            RenderSystem.assertOnRenderThread();
            MeshData.DrawState state = mesh.drawState();
            if (state.mode() != VertexFormat.Mode.QUADS) throw new IllegalArgumentException("Cube buffers hold quads, not " + state.mode());
            ByteBuffer vertexData = mesh.vertexBuffer(), own = mesh.indexBuffer();
            int vertexCount = state.vertexCount(), indexCount = state.indexCount();
            if (vertexCount == 0 || indexCount == 0) return null;
            long key = CubeKey.pack(CubeKey.x(cube) >> SHIFT, CubeKey.y(cube) >> SHIFT, CubeKey.z(cube) >> SHIFT);
            Region region = regions.get(key);
            if (region == null) regions.put(key, region = new Region(key));
            BufferUploader.invalidate();
            Slice slice = place(region, layer, state.format(), own != null, vertexCount, indexCount);
            Arena arena = slice.arena;
            GlStateManager._glBindVertexArray(arena.array);
            GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, arena.vertices);
            GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER, (long) slice.firstVertex * arena.format.getVertexSize(), vertexData);
            if (own != null) {
                GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, arena.indices);
                long offset = (long) slice.firstIndex * Integer.BYTES;
                if (state.indexType() == VertexFormat.IndexType.INT) {
                    GL15C.glBufferSubData(GL15C.GL_ELEMENT_ARRAY_BUFFER, offset, own);
                } else {
                    // Every mesh's indices as ints, so a run of meshes draws in one call.
                    IntBuffer wide = MemoryUtil.memAllocInt(indexCount);
                    try {
                        long from = MemoryUtil.memAddress(own);
                        for (int i = 0; i < indexCount; i++) wide.put(i, MemoryUtil.memGetShort(from + i * 2L) & 0xFFFF);
                        GL15C.glBufferSubData(GL15C.GL_ELEMENT_ARRAY_BUFFER, offset, wide);
                    } finally {
                        MemoryUtil.memFree(wide);
                    }
                }
            } else {
                CubeBuffer.QuadIndices.ensure(indexCount);
            }
            GlStateManager._glBindVertexArray(0);
            BufferUploader.invalidate();
            return slice;
        }
    }

    /** Room for a mesh in one of the region's buffers for the layer, in a new buffer when the others are full. */
    private Slice place(Region region, int layer, VertexFormat format, boolean sorted, int vertexCount, int indexCount) {
        List<Arena> arenas = region.layers[layer];
        if (arenas == null) arenas = region.layers[layer] = new ArrayList<>(2);
        int same = 0;
        for (Arena arena : arenas) {
            // After a shader pack comes or goes, meshes in the old vertex format live on in their own buffers a while.
            if (arena.sorted != sorted || !arena.format.equals(format)) continue;
            same++;
            Slice slice = take(arena, vertexCount, indexCount);
            if (slice != null) { usedBytes += size(slice); return slice; }
        }
        Arena arena = new Arena(region, layer, format, sorted, Math.max(CAPACITY[Math.min(same, CAPACITY.length - 1)], (vertexCount + 3) / 4 * 4));
        arenas.add(arena);
        region.arenas++;
        arenaCount++;
        bytes += size(arena);
        Slice slice = take(arena, vertexCount, indexCount);
        if (slice != null) usedBytes += size(slice);
        if (slice == null) throw new IllegalStateException("A new cube buffer has no room for " + vertexCount + " vertices");
        return slice;
    }

    private static long size(Arena arena) {
        return (long) arena.vertexSpace.capacity() * arena.format.getVertexSize()
                + (arena.indexSpace == null ? 0L : (long) arena.indexSpace.capacity() * Integer.BYTES);
    }

    private static long size(Slice slice) {
        return (long) slice.vertexCount * slice.arena.format.getVertexSize() + (slice.arena.indexSpace == null ? 0L : (long) slice.indexCount * Integer.BYTES);
    }

    @Nullable private static Slice take(Arena arena, int vertexCount, int indexCount) {
        int firstVertex = arena.vertexSpace.take(vertexCount);
        if (firstVertex < 0) return null;
        int firstIndex = 0;
        if (arena.indexSpace != null) {
            firstIndex = arena.indexSpace.take(indexCount);
            if (firstIndex < 0) { arena.vertexSpace.give(firstVertex, vertexCount); return null; }
        }
        arena.live++;
        return new Slice(arena, firstVertex, vertexCount, firstIndex, indexCount);
    }

    /** A mesh is no longer drawn: its room is free again. */
    void free(@Nullable Slice slice) {
        if (slice == null || slice.freed) return;
        slice.freed = true;
        Arena arena = slice.arena;
        if (arena.array == 0) return;
        usedBytes -= size(slice);
        arena.vertexSpace.give(slice.firstVertex, slice.vertexCount);
        if (arena.indexSpace != null) arena.indexSpace.give(slice.firstIndex, slice.indexCount);
        arena.live--;
    }

    /**
     * Now and then: deletes the buffers that were empty at the last look and still are. Not at once, since a cube
     * meshed again frees its room a moment before taking it back.
     */
    void sweep() {
        for (Arena arena : idle) {
            if (arena.live != 0 || arena.array == 0) continue;
            Region region = arena.region;
            region.layers[arena.layer].remove(arena);
            bytes -= size(arena);
            arena.close();
            arenaCount--;
            if (--region.arenas == 0) regions.remove(region.key);
        }
        idle.clear();
        for (Region region : regions.values()) {
            for (List<Arena> arenas : region.layers) {
                if (arenas == null) continue;
                for (Arena arena : arenas) if (arena.live == 0) idle.add(arena);
            }
        }
    }

    // Drawing ------------------------------------------------------------------------------------

    @Nullable private Uniform offset;
    private Vec3 camera = Vec3.ZERO;
    private boolean ordered;
    /** Buffers with draws waiting, in the order first drawn from; in order, only ever the last one. */
    private final List<Arena> waiting = new ArrayList<>();
    /** The region the chunk offset is set for. */
    @Nullable private Region placed;
    private int calls;
    @Nullable private IntBuffer counts, bases;
    @Nullable private PointerBuffer offsets;

    /**
     * Starts drawing a layer with the shader bound, whose chunk offset is {@code offset}. {@code ordered} keeps the
     * meshes in exactly the order given, as translucent ones need; otherwise they are drawn buffer by buffer.
     */
    void begin(@Nullable Uniform offset, Vec3 camera, boolean ordered) {
        this.offset = offset;
        this.camera = camera;
        this.ordered = ordered;
        placed = null;
    }

    /** Draws a mesh, now or with others from its buffer by {@link #end} at the latest. */
    void draw(Slice slice) {
        Arena arena = slice.arena;
        if (arena.array == 0 || slice.freed) return;
        if (arena.queued == 0) {
            if (ordered) flush();
            waiting.add(arena);
        }
        int at = arena.queued * 3;
        if (at == arena.queue.length) arena.queue = java.util.Arrays.copyOf(arena.queue, at * 2);
        arena.queue[at] = slice.indexCount;
        arena.queue[at + 1] = slice.firstVertex;
        arena.queue[at + 2] = slice.firstIndex;
        arena.queued++;
    }

    /** Draws a mesh at once on condition of an occlusion query (see {@link CubeOcclusion}). */
    void drawOnCondition(Slice slice, int query) {
        Arena arena = slice.arena;
        if (arena.array == 0 || slice.freed) return;
        if (ordered) flush();
        bind(arena);
        CubeOcclusion.beginConditional(query);
        GL32C.glDrawElementsBaseVertex(GL11C.GL_TRIANGLES, slice.indexCount, GL11C.GL_UNSIGNED_INT, (long) slice.firstIndex * Integer.BYTES, slice.firstVertex);
        CubeOcclusion.endConditional();
        calls++;
    }

    private void bind(Arena arena) {
        Region region = arena.region;
        if (offset != null && region != placed) {
            offset.set((float) (region.x - camera.x), (float) (region.y - camera.y), (float) (region.z - camera.z));
            offset.upload();
            placed = region;
        }
        GlStateManager._glBindVertexArray(arena.array);
        // Bound again into the array before drawing, as CubeBuffer does, so nothing outside changes the indices used.
        GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, arena.sorted ? arena.indices : CubeBuffer.QuadIndices.name);
    }

    private void flush() {
        for (Arena arena : waiting) {
            int count = arena.queued;
            arena.queued = 0;
            if (count == 0 || arena.array == 0) continue;
            bind(arena);
            int[] queue = arena.queue;
            calls++;
            if (count == 1) {
                GL32C.glDrawElementsBaseVertex(GL11C.GL_TRIANGLES, queue[0], GL11C.GL_UNSIGNED_INT, (long) queue[2] * Integer.BYTES, queue[1]);
                continue;
            }
            if (counts == null || counts.capacity() < count) {
                int size = Math.max(256, Integer.highestOneBit(count - 1) << 1);
                MemoryUtil.memFree(counts);
                MemoryUtil.memFree(bases);
                MemoryUtil.memFree(offsets);
                counts = MemoryUtil.memAllocInt(size);
                bases = MemoryUtil.memAllocInt(size);
                offsets = MemoryUtil.memAllocPointer(size);
            }
            for (int i = 0; i < count; i++) {
                counts.put(i, queue[i * 3]);
                bases.put(i, queue[i * 3 + 1]);
                offsets.put(i, (long) queue[i * 3 + 2] * Integer.BYTES);
            }
            counts.limit(count);
            bases.limit(count);
            offsets.limit(count);
            GL32C.glMultiDrawElementsBaseVertex(GL11C.GL_TRIANGLES, counts, GL11C.GL_UNSIGNED_INT, offsets, bases);
            counts.limit(counts.capacity());
            bases.limit(bases.capacity());
            offsets.limit(offsets.capacity());
        }
        waiting.clear();
    }

    /** Draws what waits. The caller puts the chunk offset back and unbinds the vertex array. */
    void end() {
        flush();
        offset = null;
        placed = null;
    }

    /** Draw calls made since last asked. */
    int takeCalls() {
        int made = calls;
        calls = 0;
        return made;
    }

    void close() {
        RenderSystem.assertOnRenderThread();
        for (Region region : regions.values()) {
            for (List<Arena> arenas : region.layers) if (arenas != null) for (Arena arena : arenas) arena.close();
        }
        regions.clear();
        idle.clear();
        waiting.clear();
        arenaCount = 0;
        bytes = usedBytes = 0L;
        MemoryUtil.memFree(counts);
        MemoryUtil.memFree(bases);
        MemoryUtil.memFree(offsets);
        counts = bases = null;
        offsets = null;
    }
}
