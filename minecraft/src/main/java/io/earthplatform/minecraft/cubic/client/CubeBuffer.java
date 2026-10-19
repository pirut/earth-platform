package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import javax.annotation.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.system.MemoryUtil;

/**
 * One layer of a near cube's mesh on the GPU, with its own vertex array, vertex buffer and index
 * buffer.
 *
 * <p>Vanilla's VertexBuffer leaves the quad indices to the game's shared sequential index buffer
 * and binds it into its vertex array only once, at upload. A later bind of another element buffer
 * while that array is current (a shader pack restoring "the last vertex buffer", or any code that
 * binds indices without its own array) silently replaced them, and the next uploads never put
 * them back. Under a shader pack on the Mac that drew every block face as one triangle of two and
 * stretched slanted sheets across the cube. Here the quad indices are our own and the index
 * buffer is bound again before every draw, so nothing outside can change which ones are used.
 */
final class CubeBuffer implements AutoCloseable {
    private int array, vertices, indices;
    @Nullable private VertexFormat format;
    private int indexCount, indexType;
    /** True when this layer has its own sorted indices (translucent), else the shared quad ones. */
    private boolean sorted;

    CubeBuffer() {
        RenderSystem.assertOnRenderThread();
        array = GlStateManager._glGenVertexArrays();
        vertices = GlStateManager._glGenBuffers();
        indices = GlStateManager._glGenBuffers();
    }

    /** Uploads a mesh (and closes it); leaves no vertex array bound. */
    void upload(MeshData mesh) {
        try (mesh) {
            if (array < 0) return;
            RenderSystem.assertOnRenderThread();
            MeshData.DrawState state = mesh.drawState();
            if (state.mode() != VertexFormat.Mode.QUADS) throw new IllegalArgumentException("Cube buffers hold quads, not " + state.mode());
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(array);
            GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, vertices);
            if (!state.format().equals(format)) {
                if (format != null) format.clearBufferState();
                state.format().setupBufferState();
                format = state.format();
            }
            ByteBuffer vertexData = mesh.vertexBuffer();
            if (vertexData != null) RenderSystem.glBufferData(GL15C.GL_ARRAY_BUFFER, vertexData, GL15C.GL_STATIC_DRAW);
            ByteBuffer own = mesh.indexBuffer();
            sorted = own != null;
            indexCount = state.indexCount();
            if (sorted) {
                GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indices);
                RenderSystem.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, own, GL15C.GL_STATIC_DRAW);
                indexType = state.indexType().asGLType;
            }
            else {
                QuadIndices.ensure(indexCount);
                indexType = GL11C.GL_UNSIGNED_INT;
            }
            GlStateManager._glBindVertexArray(0);
            BufferUploader.invalidate();
        }
    }

    /** Draws the mesh with the current shader; the caller unbinds the array afterwards. */
    void draw() {
        if (array < 0 || indexCount == 0) return;
        GlStateManager._glBindVertexArray(array);
        GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, sorted ? indices : QuadIndices.name);
        GlStateManager._drawElements(GL11C.GL_TRIANGLES, indexCount, indexType, 0L);
    }

    static void unbind() {
        GlStateManager._glBindVertexArray(0);
        BufferUploader.invalidate();
    }

    @Override
    public void close() {
        if (array < 0) return;
        RenderSystem.glDeleteBuffers(vertices);
        RenderSystem.glDeleteBuffers(indices);
        RenderSystem.glDeleteVertexArrays(array);
        array = vertices = indices = -1;
    }

    /** Indices 0 1 2 2 3 0 for every quad, as 32-bit ints, shared by every opaque layer. */
    private static final class QuadIndices {
        static int name;
        static int capacity;

        static void ensure(int count) {
            if (name == 0) name = GlStateManager._glGenBuffers();
            GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, name);
            if (count <= capacity) return;
            int quads = Math.max(1 << 14, Integer.highestOneBit(Math.max(1, count / 6 - 1)) << 1);
            IntBuffer data = MemoryUtil.memAllocInt(quads * 6);
            try {
                for (int quad = 0; quad < quads; quad++) {
                    int v = quad * 4;
                    data.put(v).put(v + 1).put(v + 2).put(v + 2).put(v + 3).put(v);
                }
                data.flip();
                GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, data, GL15C.GL_STATIC_DRAW);
            }
            finally {
                MemoryUtil.memFree(data);
            }
            capacity = quads * 6;
        }
    }
}
