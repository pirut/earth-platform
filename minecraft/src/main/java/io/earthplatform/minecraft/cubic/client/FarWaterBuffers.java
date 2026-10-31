package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.system.MemoryUtil;

/**
 * Far water's meshes, every column's surface and walls, in one vertex buffer per vertex format,
 * which a pass binds once and then draws column after column from.
 *
 * <p>Each column's water had a vertex array, vertex buffer and index buffer of its own (a
 * {@link CubeBuffer}), and each of far water's passes bound them before every column's draw: over
 * lakes and rivers thousands of binds and draws a frame, about 5 ms of the render thread under
 * Photon. A mesh here is a run of quads in a shared buffer, drawn with a base vertex into the shared
 * quad indices: the same corners in the same triangles, in the same order and with the same
 * uniforms as before. {@code -Dearthplatform.far.sharedWater=false} gives each mesh a buffer of its
 * own again, bound before each of its draws. Everything here runs on the render thread.
 */
final class FarWaterBuffers {
    static final boolean SHARED = !"false".equals(System.getProperty("earthplatform.far.sharedWater"));
    /**
     * Far water's meshes are kept placed in a cube of {@link #REGION} blocks (their corners from
     * its corner) rather than in their own column, so every column in a cube is drawn from the
     * same place: a pass sets that once and draws the columns after one
     * another in a single multi-draw, where it set the place and drew each column on its own, three
     * passes a frame under a shader pack. The same triangles, drawn in the same order; their corners
     * are now rounded to about a 1,000th of a block. {@code -Dearthplatform.far.batchWater=false} places
     * each mesh in its column and draws them one by one, for comparing.
     */
    static final boolean BATCH = SHARED && !"false".equals(System.getProperty("earthplatform.far.batchWater"));
    /** The side of the cubes far water is placed in (see {@link #BATCH}): 16,384 blocks, so a corner is never more than that from its cube's. */
    static final int REGION = 1 << 14;

    /** The corner of the cube (see {@link #REGION}) a block coordinate lies in. */
    static int region(int block) { return block & -REGION; }
    /** A shared buffer's room at first, in quads; it doubles as it fills. */
    private static final int FIRST_QUADS = 1 << 14;

    /** One surface or wall: a run of quads in a buffer. */
    static final class Mesh {
        /** Null, with no range, for a mesh with no quads. */
        @Nullable private final Buffer buffer;
        @Nullable private final FarArenaRanges.Allocation range;
        private final int indexCount, baseVertex;

        private Mesh(@Nullable Buffer buffer, @Nullable FarArenaRanges.Allocation range, int indexCount) {
            this.buffer = buffer;
            this.range = range;
            this.indexCount = indexCount;
            baseVertex = range == null ? 0 : range.start() * 4;
        }
    }

    /** Vertex buffers by format (two only while a shader pack comes or goes), each with its vertex array. */
    private final List<Buffer> buffers = new ArrayList<>();
    /** The format of the last mesh put in; an emptied buffer of another format is let go. */
    @Nullable private VertexFormat latest;
    /** The buffer whose array the pass under way has bound, or null before its first draw. */
    @Nullable private Buffer bound;

    /**
     * Puts a built mesh in a buffer of its format, letting go of the one it replaces; null for none.
     * The mesh is built from its column's corner at x0, y0, z0, and is kept placed as {@link #BATCH} says.
     */
    @Nullable Mesh upload(@Nullable Mesh old, @Nullable FarMesher.Part part, int x0, int y0, int z0) {
        free(old);
        if (part == null) return null;
        try {
            return add(part.mesh(), BATCH ? x0 - region(x0) : 0, BATCH ? y0 - region(y0) : 0, BATCH ? z0 - region(z0) : 0);
        } finally {
            part.memory().close();
        }
    }

    private Mesh add(MeshData mesh, int dx, int dy, int dz) {
        try (mesh) {
            RenderSystem.assertOnRenderThread();
            MeshData.DrawState state = mesh.drawState();
            if (state.mode() != VertexFormat.Mode.QUADS) throw new IllegalArgumentException("Far water holds quads, not " + state.mode());
            // Drawn with the shared quad indices, in the order built, as CubeBuffer draws a mesh with no sorted indices of its own.
            if (mesh.indexBuffer() != null) throw new IllegalArgumentException("Far water's quads are not sorted");
            VertexFormat format = state.format();
            if (!format.equals(latest)) {
                latest = format;
                // A shader pack came or went: buffers of the old format are let go as they empty.
                for (Buffer buffer : List.copyOf(buffers)) letGoIfEmpty(buffer);
            }
            int quads = state.vertexCount() / 4;
            ByteBuffer vertices = mesh.vertexBuffer();
            // A builder given no quads gives no mesh, so not met in practice; drawn as nothing, as CubeBuffer drew it.
            if (quads == 0 || vertices == null) return new Mesh(null, null, 0);
            if ((dx | dy | dz) != 0) move(vertices, format, state.vertexCount(), dx, dy, dz);
            Buffer buffer = null;
            if (SHARED) for (Buffer candidate : buffers) if (candidate.format.equals(format)) { buffer = candidate; break; }
            if (buffer == null) {
                buffer = new Buffer(format, SHARED ? Math.max(FIRST_QUADS, quads) : quads);
                buffers.add(buffer);
            }
            return buffer.add(vertices, quads, state.indexCount());
        }
    }

    /** Moves every corner of a mesh's vertices by whole blocks, in place. */
    private static void move(ByteBuffer vertices, VertexFormat format, int count, int dx, int dy, int dz) {
        int stride = format.getVertexSize(), position = format.getOffset(VertexFormatElement.POSITION);
        long address = MemoryUtil.memAddress(vertices) + position;
        for (int i = 0; i < count; i++, address += stride) {
            MemoryUtil.memPutFloat(address, MemoryUtil.memGetFloat(address) + dx);
            MemoryUtil.memPutFloat(address + 4, MemoryUtil.memGetFloat(address + 4) + dy);
            MemoryUtil.memPutFloat(address + 8, MemoryUtil.memGetFloat(address + 8) + dz);
        }
    }

    /** Lets go of a mesh's quads. */
    void free(@Nullable Mesh mesh) {
        if (mesh == null || mesh.buffer == null) return;
        mesh.buffer.ranges.release(mesh.range);
        letGoIfEmpty(mesh.buffer);
    }

    /** Deletes a buffer holding nothing, unless it is the shared one meshes of the latest format go to. */
    private void letGoIfEmpty(Buffer buffer) {
        if (buffer.ranges.allocationCount() > 0 || SHARED && buffer.format.equals(latest)) return;
        buffers.remove(buffer);
        if (bound == buffer) bound = null;
        buffer.close();
    }

    /** Starts a pass: its first draw binds its buffer's array, whatever was bound before. */
    void begin() {
        bound = null;
        queued = 0;
    }

    /**
     * Draws a mesh with the program in use, binding its buffer's array if the pass's last draw used
     * another. With {@link #BATCH} the draw waits in a multi-draw with the meshes after it, until
     * {@link #flush}: call that before changing anything the draws use, as the place they are drawn from.
     */
    void draw(Mesh mesh) {
        if (mesh.indexCount == 0) return;
        if (mesh.buffer != bound) {
            flush();
            bound = mesh.buffer;
            GlStateManager._glBindVertexArray(bound.array);
            // Bound again into the array before drawing, as CubeBuffer does, so nothing outside changes the indices used.
            GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, CubeBuffer.QuadIndices.name);
        }
        if (!BATCH) {
            GL32C.glDrawElementsBaseVertex(GL11C.GL_TRIANGLES, mesh.indexCount, GL11C.GL_UNSIGNED_INT, 0L, mesh.baseVertex);
            return;
        }
        if (counts == null) {
            counts = MemoryUtil.memAllocInt(256);
            bases = MemoryUtil.memAllocInt(256);
            offsets = MemoryUtil.memCallocPointer(256);
        }
        if (queued == counts.capacity()) {
            int size = queued * 2;
            counts = MemoryUtil.memRealloc(counts, size);
            bases = MemoryUtil.memRealloc(bases, size);
            MemoryUtil.memFree(offsets);
            offsets = MemoryUtil.memCallocPointer(size);
        }
        counts.put(queued, mesh.indexCount);
        bases.put(queued, mesh.baseVertex);
        queued++;
    }

    /** Draws the meshes waiting since the last flush (see {@link #draw}), from the buffer bound. */
    void flush() {
        if (queued == 0) return;
        int count = queued;
        queued = 0;
        if (count == 1) {
            GL32C.glDrawElementsBaseVertex(GL11C.GL_TRIANGLES, counts.get(0), GL11C.GL_UNSIGNED_INT, 0L, bases.get(0));
            return;
        }
        counts.limit(count);
        bases.limit(count);
        offsets.limit(count);
        GL32C.glMultiDrawElementsBaseVertex(GL11C.GL_TRIANGLES, counts, GL11C.GL_UNSIGNED_INT, offsets, bases);
        counts.limit(counts.capacity());
        bases.limit(bases.capacity());
        offsets.limit(offsets.capacity());
    }

    /** Draws waiting in a multi-draw (see {@link #draw}): their index counts and base vertices, and every draw's index offset, 0. */
    @Nullable private java.nio.IntBuffer counts, bases;
    @Nullable private org.lwjgl.PointerBuffer offsets;
    private int queued;

    /** Ends a pass, drawing what waits and leaving no vertex array bound. */
    void end() {
        flush();
        bound = null;
        CubeBuffer.unbind();
    }

    void close() {
        for (Buffer buffer : buffers) buffer.close();
        buffers.clear();
        bound = null;
        latest = null;
        queued = 0;
        if (counts != null) {
            MemoryUtil.memFree(counts);
            MemoryUtil.memFree(bases);
            MemoryUtil.memFree(offsets);
            counts = bases = null;
            offsets = null;
        }
    }

    /** One vertex buffer of meshes of one format, and the vertex array reading it with the shared quad indices. */
    private static final class Buffer {
        final VertexFormat format;
        final FarArenaRanges ranges;
        private final int stride;
        private int vertices, array, quads;

        Buffer(VertexFormat format, int quads) {
            this.format = format;
            this.quads = quads;
            stride = format.getVertexSize();
            ranges = new FarArenaRanges(quads);
            vertices = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, vertices);
            GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, bytes(quads), GL15C.GL_DYNAMIC_DRAW);
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
            array = GlStateManager._glGenVertexArrays();
            point();
        }

        private long bytes(int quads) { return (long) quads * 4 * stride; }

        /** Points the array's attributes at the vertex buffer, as CubeBuffer sets up its own. */
        private void point() {
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(array);
            GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, vertices);
            format.setupBufferState();
            GlStateManager._glBindVertexArray(0);
            BufferUploader.invalidate();
        }

        Mesh add(ByteBuffer data, int quads, int indexCount) {
            FarArenaRanges.Allocation range = ranges.allocate(quads);
            if (range == null) {
                grow(quads);
                range = ranges.allocate(quads);
            }
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, vertices);
            GL15C.glBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, bytes(range.start()), data);
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
            // The shared quad indices reach far enough for this mesh, bound into this array alone.
            GlStateManager._glBindVertexArray(array);
            CubeBuffer.QuadIndices.ensure(indexCount);
            GlStateManager._glBindVertexArray(0);
            BufferUploader.invalidate();
            return new Mesh(this, range, indexCount);
        }

        /** Doubles the room, or more for a mesh larger than that, keeping every mesh where it is. */
        private void grow(int quadsWanted) {
            int grown = Math.max(quads * 2, quads + quadsWanted);
            int bigger = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, bigger);
            GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, bytes(grown), GL15C.GL_DYNAMIC_DRAW);
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, vertices);
            GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0, 0, bytes(quads));
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, 0);
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
            GL15C.glDeleteBuffers(vertices);
            vertices = bigger;
            ranges.grow(grown);
            quads = grown;
            point();
        }

        void close() {
            if (array < 0) return;
            RenderSystem.glDeleteBuffers(vertices);
            RenderSystem.glDeleteVertexArrays(array);
            array = vertices = -1;
        }
    }
}
