package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.Timings;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Compiles client cubes into vanilla block-format vertex buffers off thread and draws them with
 * the vanilla terrain shaders, the way LevelRenderer draws its own sections.
 */
public final class CubeRenderer {
    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(
            Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2)), runnable -> {
                Thread thread = new Thread(runnable, "Earth cube mesher " + THREADS.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
    private static final int MAX_IN_FLIGHT = 48, MAX_UPLOADS_PER_FRAME = 48, NEIGHBOUR_WAIT_TICKS = 20;

    private final ClientCubeWorld world;
    private final Long2ObjectOpenHashMap<Mesh> meshes = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<byte[]> light = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    private final Long2IntOpenHashMap dirtySince = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap versions = new Long2IntOpenHashMap();
    private final LongOpenHashSet building = new LongOpenHashSet();
    /** Cubes whose current contents have a mesh (or need none), so far terrain can leave them out. */
    private final LongOpenHashSet compiled = new LongOpenHashSet();
    private final ConcurrentLinkedQueue<CubeMesher.Built> built = new ConcurrentLinkedQueue<>();
    private final List<Mesh> visible = new ArrayList<>();
    private int ticks, drawnFrame = -1, translucentFrame = -1, frame;
    private boolean closed;

    private static final class Mesh {
        final long key;
        final int x, y, z;
        final AABB box;
        final VertexBuffer[] buffers = new VertexBuffer[CubeMesher.LAYERS.size()];
        double distance;
        Mesh(long key) {
            this.key = key; x = CubeKey.x(key) << 4; y = CubeKey.y(key) << 4; z = CubeKey.z(key) << 4;
            box = new AABB(x, y, z, x + 16, y + 16, z + 16);
        }
        void close() { for (int i = 0; i < buffers.length; i++) if (buffers[i] != null) { buffers[i].close(); buffers[i] = null; } }
        boolean empty() { for (VertexBuffer buffer : buffers) if (buffer != null) return false; return true; }
    }

    CubeRenderer(ClientCubeWorld world) { this.world = world; }

    public int meshCount() { return meshes.size(); }
    public int pendingCount() { return dirty.size() + building.size(); }
    /** Cubes whose current contents are drawn here; read only. */
    LongOpenHashSet compiledCubes() { return compiled; }

    // Dirty tracking -----------------------------------------------------------------------------

    private void markDirty(long key) {
        if (world.cube(key) == null) return;
        if (dirty.add(key)) dirtySince.put(key, ticks);
        versions.addTo(key, 1);
    }

    private void markAround(int cx, int cy, int cz, boolean all) {
        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            if (!all && Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 1) continue;
            markDirty(CubeKey.pack(cx + dx, cy + dy, cz + dz));
        }
    }

    /** Rebuilds every mesh, as when a shader pack comes or goes and changes the vertex format and block ids. */
    void remeshAll() {
        for (long key : meshes.keySet()) markDirty(key);
    }

    void cubeArrived(long key) { markAround(CubeKey.x(key), CubeKey.y(key), CubeKey.z(key), false); }

    void drop(long key) {
        dirty.remove(key);
        dirtySince.remove(key);
        versions.remove(key);
        light.remove(key);
        compiled.remove(key);
        Mesh mesh = meshes.remove(key);
        if (mesh != null) mesh.close();
    }

    void blockChanged(BlockPos pos, BlockState old, BlockState state) {
        int cx = pos.getX() >> 4, cy = pos.getY() >> 4, cz = pos.getZ() >> 4;
        boolean lighting = old.getLightEmission() != state.getLightEmission()
                || old.getLightBlock(world.client(), pos) != state.getLightBlock(world.client(), pos);
        if (lighting) { markAround(cx, cy, cz, true); return; }
        markDirty(CubeKey.pack(cx, cy, cz));
        int lx = pos.getX() & 15, ly = pos.getY() & 15, lz = pos.getZ() & 15;
        if (lx == 0) markDirty(CubeKey.pack(cx - 1, cy, cz)); else if (lx == 15) markDirty(CubeKey.pack(cx + 1, cy, cz));
        if (ly == 0) markDirty(CubeKey.pack(cx, cy - 1, cz)); else if (ly == 15) markDirty(CubeKey.pack(cx, cy + 1, cz));
        if (lz == 0) markDirty(CubeKey.pack(cx, cy, cz - 1)); else if (lz == 15) markDirty(CubeKey.pack(cx, cy, cz + 1));
    }

    void topsChanged(int cubeX, int cubeZ, int[] old, int[] now) {
        if (old == null) return;
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int i = 0; i < 256; i++) {
            if (old[i] == now[i]) continue;
            low = Math.min(low, Math.min(old[i], now[i]));
            high = Math.max(high, Math.max(old[i], now[i]));
        }
        if (low > high) return;
        for (int cy = (low >> 4) - 1; cy <= (high >> 4) + 1; cy++) {
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) markDirty(CubeKey.pack(cubeX + dx, cy, cubeZ + dz));
        }
    }

    /** Light the last compile computed for this position, or -1 when its cube is not lit yet. */
    int light(BlockPos pos, LightLayer layer) {
        byte[] values = light.get(CubeKey.ofBlock(pos));
        if (values == null) return -1;
        int packed = values[(pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15)];
        return layer == LightLayer.SKY ? packed >> 4 & 15 : packed & 15;
    }

    // Scheduling ---------------------------------------------------------------------------------

    public void tick() { ticks++; }

    private boolean neighboursReady(long key) {
        int x = CubeKey.x(key), y = CubeKey.y(key), z = CubeKey.z(key);
        return world.cube(CubeKey.pack(x + 1, y, z)) != null && world.cube(CubeKey.pack(x - 1, y, z)) != null
                && world.cube(CubeKey.pack(x, y + 1, z)) != null && world.cube(CubeKey.pack(x, y - 1, z)) != null
                && world.cube(CubeKey.pack(x, y, z + 1)) != null && world.cube(CubeKey.pack(x, y, z - 1)) != null;
    }

    private void schedule(Vec3 camera) {
        if (dirty.isEmpty() || building.size() >= MAX_IN_FLIGHT) return;
        int cx = (int) Math.floor(camera.x) >> 4, cy = (int) Math.floor(camera.y) >> 4, cz = (int) Math.floor(camera.z) >> 4;
        long[] picks = new long[MAX_IN_FLIGHT - building.size()];
        long[] scores = new long[picks.length];
        int count = 0;
        for (LongIterator it = dirty.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (building.contains(key)) continue;
            if (world.cube(key) == null) { it.remove(); dirtySince.remove(key); continue; }
            if (!neighboursReady(key) && ticks - dirtySince.get(key) < NEIGHBOUR_WAIT_TICKS) continue;
            long dx = CubeKey.x(key) - cx, dy = CubeKey.y(key) - cy, dz = CubeKey.z(key) - cz;
            long score = dx * dx + dy * dy * 2 + dz * dz;
            if (count < picks.length) { picks[count] = key; scores[count++] = score; continue; }
            int worst = 0;
            for (int i = 1; i < count; i++) if (scores[i] > scores[worst]) worst = i;
            if (score < scores[worst]) { picks[worst] = key; scores[worst] = score; }
        }
        for (int i = 0; i < count; i++) submit(picks[i], camera);
    }

    @SuppressWarnings("unchecked")
    private void submit(long key, Vec3 camera) {
        Cube center = world.cube(key);
        dirty.remove(key);
        dirtySince.remove(key);
        if (center == null) return;
        int x = center.x, y = center.y, z = center.z;
        if (center.isEmpty()) {
            Mesh mesh = meshes.remove(key);
            if (mesh != null) mesh.close();
            light.remove(key);
            compiled.add(key);
            return;
        }
        PalettedContainer<BlockState>[] states = new PalettedContainer[27];
        PalettedContainerRO<Holder<Biome>>[] biomes = new PalettedContainerRO[27];
        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            Cube cube = world.cube(CubeKey.pack(x + dx, y + dy, z + dz));
            int i = (dy + 1) * 9 + (dz + 1) * 3 + (dx + 1);
            if (cube == null) continue;
            states[i] = cube.section.getStates().copy();
            biomes[i] = cube.section.getBiomes();
        }
        int[][] tops = new int[9][];
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            int[] column = world.columnTops(x + dx, z + dz);
            tops[(dz + 1) * 3 + (dx + 1)] = column == null ? null : column.clone();
        }
        int version = versions.get(key);
        CubeMesher.Snapshot snapshot = new CubeMesher.Snapshot(key, version, x, y, z, states, biomes, tops,
                (float) camera.x, (float) camera.y, (float) camera.z, world.client().effects().constantAmbientLight(),
                ShaderBlocks.ids());
        building.add(key);
        POOL.execute(() -> {
            long start = System.nanoTime();
            try {
                built.add(CubeMesher.build(snapshot));
                Timings.CUBE_MESH.since(start);
            }
            catch (Throwable failure) {
                EarthPlatformMod.LOGGER.error("Cube mesh build failed for {}", CubeKey.toString(key), failure);
                built.add(new CubeMesher.Built(key, -1, List.of(), null));
            }
        });
    }

    private void upload() {
        for (int n = 0; n < MAX_UPLOADS_PER_FRAME; n++) {
            CubeMesher.Built result = built.poll();
            if (result == null) return;
            building.remove(result.key());
            if (closed || world.cube(result.key()) == null || result.version() < 0) { result.free(); continue; }
            if (result.light() != null) light.put(result.key(), result.light());
            compiled.add(result.key());
            Mesh mesh = meshes.get(result.key());
            if (mesh == null) { mesh = new Mesh(result.key()); meshes.put(result.key(), mesh); }
            boolean[] present = new boolean[mesh.buffers.length];
            for (CubeMesher.Layer layer : result.layers()) {
                VertexBuffer buffer = mesh.buffers[layer.index()];
                if (buffer == null) buffer = mesh.buffers[layer.index()] = new VertexBuffer(VertexBuffer.Usage.STATIC);
                buffer.bind();
                buffer.upload(layer.mesh());
                layer.memory().close();
                present[layer.index()] = true;
            }
            VertexBuffer.unbind();
            for (int i = 0; i < present.length; i++) {
                if (!present[i] && mesh.buffers[i] != null) { mesh.buffers[i].close(); mesh.buffers[i] = null; }
            }
            if (mesh.empty()) meshes.remove(result.key());
        }
    }

    // Drawing ------------------------------------------------------------------------------------

    /** Once per frame before the first layer: upload finished meshes, queue more and cull. */
    private void prepare(Camera camera, Frustum frustum) {
        if (drawnFrame == frame) return;
        drawnFrame = frame;
        Vec3 position = camera.getPosition();
        long start = System.nanoTime();
        upload();
        Timings.CUBE_UPLOAD.since(start);
        schedule(position);
        visible.clear();
        for (Mesh mesh : meshes.values()) {
            if (!frustum.isVisible(mesh.box)) continue;
            double dx = mesh.x + 8 - position.x, dy = mesh.y + 8 - position.y, dz = mesh.z + 8 - position.z;
            mesh.distance = dx * dx + dy * dy + dz * dz;
            visible.add(mesh);
        }
        visible.sort(Comparator.comparingDouble(mesh -> mesh.distance));
    }

    public void beginFrame() { frame++; }

    public void drawOpaque(Camera camera, Frustum frustum, Matrix4f modelView, Matrix4f projection) {
        prepare(camera, frustum);
        long start = System.nanoTime();
        for (int i = 0; i < CubeMesher.LAYERS.size(); i++) {
            RenderType type = CubeMesher.LAYERS.get(i);
            if (type == RenderType.translucent() || type == RenderType.tripwire()) continue;
            draw(i, camera.getPosition(), modelView, projection, false);
        }
        Timings.CUBE_DRAW.since(start);
    }

    public void drawTranslucent(Camera camera, Frustum frustum, Matrix4f modelView, Matrix4f projection) {
        if (translucentFrame == frame) return;
        translucentFrame = frame;
        prepare(camera, frustum);
        draw(CubeMesher.LAYERS.indexOf(RenderType.translucent()), camera.getPosition(), modelView, projection, true);
        draw(CubeMesher.LAYERS.indexOf(RenderType.tripwire()), camera.getPosition(), modelView, projection, true);
    }

    public boolean drewOpaqueThisFrame() { return drawnFrame == frame; }

    /**
     * Draws every mesh's opaque layers into a shader pack's shadow map, so near terrain casts
     * shadows; not limited to the view, since what lies outside it still shades what is in it.
     */
    public void drawShadow(Camera camera, Matrix4f modelView, Matrix4f projection) {
        upload();
        List<Mesh> all = new java.util.ArrayList<>(meshes.values());
        for (int i = 0; i < CubeMesher.LAYERS.size(); i++) {
            RenderType type = CubeMesher.LAYERS.get(i);
            if (type == RenderType.translucent() || type == RenderType.tripwire()) continue;
            draw(i, all, camera.getPosition(), modelView, projection, false);
        }
    }

    private void draw(int layer, Vec3 camera, Matrix4f modelView, Matrix4f projection, boolean backToFront) {
        draw(layer, visible, camera, modelView, projection, backToFront);
    }

    private void draw(int layer, List<Mesh> visible, Vec3 camera, Matrix4f modelView, Matrix4f projection, boolean backToFront) {
        RenderType type = CubeMesher.LAYERS.get(layer);
        boolean any = false;
        for (Mesh mesh : visible) if (mesh.buffers[layer] != null) { any = true; break; }
        if (!any) return;
        type.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, Minecraft.getInstance().getWindow());
        shader.apply();
        Uniform offset = shader.CHUNK_OFFSET;
        for (int n = 0; n < visible.size(); n++) {
            Mesh mesh = visible.get(backToFront ? visible.size() - 1 - n : n);
            VertexBuffer buffer = mesh.buffers[layer];
            if (buffer == null) continue;
            if (offset != null) {
                offset.set((float) (mesh.x - camera.x), (float) (mesh.y - camera.y), (float) (mesh.z - camera.z));
                offset.upload();
            }
            buffer.bind();
            buffer.draw();
        }
        if (offset != null) offset.set(0.0F, 0.0F, 0.0F);
        shader.clear();
        VertexBuffer.unbind();
        type.clearRenderState();
    }

    /** Block entities with their own renderers (chests, signs, beds...) near the camera. */
    public void drawBlockEntities(Camera camera, Frustum frustum, PoseStack pose, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockEntityRenderDispatcher dispatcher = minecraft.getBlockEntityRenderDispatcher();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        Vec3 position = camera.getPosition();
        boolean any = false;
        for (Cube cube : world.cubes()) {
            if (cube.blockEntities().isEmpty()) continue;
            for (BlockEntity entity : cube.blockEntities().values()) {
                if (dispatcher.getRenderer(entity) == null) continue;
                BlockPos pos = entity.getBlockPos();
                if (!frustum.isVisible(new AABB(pos).inflate(1.0))) continue;
                pose.pushPose();
                pose.translate(pos.getX() - position.x, pos.getY() - position.y, pos.getZ() - position.z);
                dispatcher.render(entity, partialTick, pose, buffers);
                pose.popPose();
                any = true;
            }
        }
        if (any) buffers.endBatch();
    }

    void close() {
        closed = true;
        for (Mesh mesh : meshes.values()) mesh.close();
        meshes.clear();
        light.clear();
        compiled.clear();
        dirty.clear();
        CubeMesher.Built result;
        while ((result = built.poll()) != null) result.free();
    }
}
