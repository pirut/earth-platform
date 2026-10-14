package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.Timings;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

/**
 * Compiles client cubes into vanilla block-format vertex buffers off thread and draws them with
 * the vanilla terrain shaders, the way LevelRenderer draws its own sections.
 */
public final class CubeRenderer {
    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final int THREAD_COUNT = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2));
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREAD_COUNT, runnable -> {
                Thread thread = new Thread(runnable, "Earth cube mesher " + THREADS.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
    private static final int MIN_IN_FLIGHT = 48, MAX_IN_FLIGHT = 512, MIN_UPLOADS_PER_FRAME = 48, NEIGHBOUR_WAIT_TICKS = 20;
    /** About how long one mesh takes a worker, to keep the workers busy for a whole frame. */
    private static final long MESH_NANOS = 5_000_000L;

    private final ClientCubeWorld world;
    private final Long2ObjectOpenHashMap<Mesh> meshes = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<byte[]> light = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    private final Long2IntOpenHashMap dirtySince = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap versions = new Long2IntOpenHashMap();
    private final LongOpenHashSet building = new LongOpenHashSet();
    /** Cubes whose current contents have a mesh (or need none), so far terrain can leave them out. */
    private final LongOpenHashSet compiled = new LongOpenHashSet();
    /** Tick at which each cube was first compiled, so one held back too long shows anyway. */
    private final Long2IntOpenHashMap compiledAt = new Long2IntOpenHashMap();
    /**
     * The compiled cubes that are drawn: those with every cube above them compiled too, up to open
     * sky (an air cube) or the top of the cubes kept around the camera. While Earth data downloads,
     * cubes arrive in no particular order up a column, and a cube drawn before the ones above it
     * showed its unlit top and cave walls as a dark floor under the ground. Far terrain, which only
     * leaves out drawn cubes, went on standing in above it, and under a shader pack (where far
     * terrain gives way wherever near terrain is drawn at all, see FarShaders) it was torn to
     * shreds over that floor. Held back, the cubes stay hidden behind far terrain until their
     * column is whole.
     */
    private LongOpenHashSet shown = new LongOpenHashSet();
    private boolean shownStale = true;
    private int shownAt, shownCy = Integer.MIN_VALUE;
    /** Longest a compiled cube is held back for the cubes above it: they may never come. */
    private static final int HOLD_TICKS = 200;
    private final ConcurrentLinkedQueue<CubeMesher.Built> built = new ConcurrentLinkedQueue<>();
    private final List<Mesh> visible = new ArrayList<>();
    private int ticks, drawnFrame = -1, translucentFrame = -1, frame;
    /** How long the last frame took, so meshing and uploads keep pace however low the frame rate goes. */
    private long frameStart = System.nanoTime(), frameNanos = 16_000_000L;
    private boolean closed;

    private static final class Mesh {
        final long key;
        final int x, y, z;
        final AABB box;
        final CubeBuffer[] buffers = new CubeBuffer[CubeMesher.LAYERS.size()];
        double distance;
        Mesh(long key) {
            this.key = key; x = CubeKey.x(key) << 4; y = CubeKey.y(key) << 4; z = CubeKey.z(key) << 4;
            box = new AABB(x, y, z, x + 16, y + 16, z + 16);
        }
        void close() { for (int i = 0; i < buffers.length; i++) if (buffers[i] != null) { buffers[i].close(); buffers[i] = null; } }
        boolean empty() { for (CubeBuffer buffer : buffers) if (buffer != null) return false; return true; }
    }

    CubeRenderer(ClientCubeWorld world) { this.world = world; }

    public int meshCount() { return meshes.size(); }
    public int pendingCount() { return dirty.size() + building.size(); }
    /** Cubes whose current contents are drawn here; read only. */
    LongOpenHashSet compiledCubes() { return shown; }

    /** Works out {@link #shown} again when cubes were compiled or dropped, the camera changed cube level, or held cubes may be due. */
    private void refreshShown(int cy) {
        // At most every other tick while cubes stream in: it looks over every compiled cube.
        if (cy == shownCy && ticks - shownAt < (shownStale ? 2 : 20)) return;
        shownStale = false;
        shownCy = cy;
        shownAt = ticks;
        LongOpenHashSet next = new LongOpenHashSet(compiled.size() * 2);
        LongOpenHashSet held = new LongOpenHashSet();
        LongArrayList walked = new LongArrayList();
        int top = cy + io.earthplatform.minecraft.cubic.Cubic.VERTICAL_RADIUS;
        for (LongIterator it = compiled.iterator(); it.hasNext(); ) {
            long start = it.nextLong();
            if (next.contains(start) || held.contains(start)) continue;
            int x = CubeKey.x(start), z = CubeKey.z(start);
            walked.clear();
            boolean whole = false;
            for (int y = CubeKey.y(start); ; y++) {
                long key = CubeKey.pack(x, y, z);
                if (next.contains(key)) { whole = true; break; }
                if (held.contains(key) || !compiled.contains(key)) break;
                walked.add(key);
                Cube cube = world.cube(key);
                if (y >= top || cube != null && cube.isEmpty()) { whole = true; break; }
            }
            for (int i = 0; i < walked.size(); i++) {
                long key = walked.getLong(i);
                if (whole || ticks - compiledAt.get(key) >= HOLD_TICKS) next.add(key);
                else held.add(key);
            }
        }
        shown = next;
    }

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
        compiledAt.remove(key);
        shownStale = true;
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
        int limit = inFlight();
        if (dirty.isEmpty()) return;
        int cx = (int) Math.floor(camera.x) >> 4, cy = (int) Math.floor(camera.y) >> 4, cz = (int) Math.floor(camera.z) >> 4;
        LongArrayList ready = new LongArrayList(), order = new LongArrayList();
        for (LongIterator it = dirty.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (building.contains(key)) continue;
            Cube cube = world.cube(key);
            if (cube == null) { it.remove(); dirtySince.remove(key); continue; }
            // Air needs no worker, so it never waits its turn: high in the sky every cube is air,
            // and a per-frame cap on them all held back the ground cubes behind thousands of them.
            if (cube.isEmpty()) { it.remove(); dirtySince.remove(key); compileEmpty(key); continue; }
            if (!neighboursReady(key) && ticks - dirtySince.get(key) < NEIGHBOUR_WAIT_TICKS) continue;
            long dx = CubeKey.x(key) - cx, dy = CubeKey.y(key) - cy, dz = CubeKey.z(key) - cz;
            // Distance in the high bits and the index below, so one primitive sort orders them.
            order.add(Math.min(dx * dx + dy * dy * 2 + dz * dz, 1L << 40) << 22 | ready.size());
            ready.add(key);
        }
        int count = Math.min(ready.size(), limit - building.size());
        if (count <= 0) return;
        // Nearest first; the picks can be hundreds.
        long[] sorted = order.toLongArray();
        if (count < sorted.length) Arrays.sort(sorted);
        for (int i = 0; i < count; i++) submit(ready.getLong((int) (sorted[i] & ((1 << 22) - 1))), camera);
    }

    private void compileEmpty(long key) {
        Mesh mesh = meshes.remove(key);
        if (mesh != null) mesh.close();
        light.remove(key);
        if (compiled.add(key)) compiledAt.put(key, ticks);
        shownStale = true;
    }

    /**
     * Block entity model data for the cube, gathered on the client thread the way vanilla's chunk
     * compiler gets it. Together with the cube's block entities (handed to the mesher's view, as
     * vanilla's render region does), connected and multiblock models such as Immersive Engineering's
     * formed machines draw the right parts.
     */
    private static Map<BlockPos, ModelData> modelData(Cube cube) {
        if (cube.blockEntities().isEmpty()) return Map.of();
        Map<BlockPos, ModelData> data = new HashMap<>();
        for (BlockEntity entity : cube.blockEntities().values()) {
            ModelData model = entity.getModelData();
            if (model != ModelData.EMPTY) data.put(entity.getBlockPos().immutable(), model);
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    private void submit(long key, Vec3 camera) {
        Cube center = world.cube(key);
        dirty.remove(key);
        dirtySince.remove(key);
        if (center == null) return;
        int x = center.x, y = center.y, z = center.z;
        if (center.isEmpty()) { compileEmpty(key); return; }
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
                ShaderBlocks.ids(), modelData(center),
                center.blockEntities().isEmpty() ? Map.of() : new HashMap<>(center.blockEntities()));
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

    /** Uploads finished meshes: a few every frame, and more while an eighth of the last frame's time lasts. */
    private void upload() {
        long deadline = System.nanoTime() + frameNanos / 8;
        for (int n = 0; n < MIN_UPLOADS_PER_FRAME || System.nanoTime() < deadline; n++) {
            CubeMesher.Built result = built.poll();
            if (result == null) return;
            building.remove(result.key());
            if (closed || world.cube(result.key()) == null || result.version() < 0) { result.free(); continue; }
            if (result.light() != null) light.put(result.key(), result.light());
            if (compiled.add(result.key())) compiledAt.put(result.key(), ticks);
            shownStale = true;
            Mesh mesh = meshes.get(result.key());
            if (mesh == null) { mesh = new Mesh(result.key()); meshes.put(result.key(), mesh); }
            boolean[] present = new boolean[mesh.buffers.length];
            for (CubeMesher.Layer layer : result.layers()) {
                CubeBuffer buffer = mesh.buffers[layer.index()];
                if (buffer == null) buffer = mesh.buffers[layer.index()] = new CubeBuffer();
                buffer.upload(layer.mesh());
                layer.memory().close();
                present[layer.index()] = true;
            }
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
        refreshShown((int) Math.floor(position.y) >> 4);
        visible.clear();
        for (Mesh mesh : meshes.values()) {
            if (!shown.contains(mesh.key) || !frustum.isVisible(mesh.box)) continue;
            double dx = mesh.x + 8 - position.x, dy = mesh.y + 8 - position.y, dz = mesh.z + 8 - position.z;
            mesh.distance = dx * dx + dy * dy + dz * dz;
            visible.add(mesh);
        }
        visible.sort(Comparator.comparingDouble(mesh -> mesh.distance));
    }

    public void beginFrame() {
        frame++;
        long now = System.nanoTime();
        frameNanos = Math.min(now - frameStart, 1_000_000_000L);
        frameStart = now;
    }

    /**
     * Meshes started per frame: enough to keep the workers busy until the next frame. A fixed 12
     * (then 48) meant a frame rate of 1 under a shader pack built 12 cubes a second.
     */
    private int inFlight() {
        long perFrame = frameNanos * THREAD_COUNT / MESH_NANOS;
        return (int) Math.clamp(perFrame, MIN_IN_FLIGHT, MAX_IN_FLIGHT);
    }

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
     * Draws the opaque layers of every mesh inside a shader pack's shadow map into it, so near
     * terrain casts shadows; not limited to the view, since what lies outside it still shades what
     * is in it.
     */
    public void drawShadow(Camera camera, Matrix4f modelView, Matrix4f projection) {
        upload();
        Vec3 position = camera.getPosition();
        Frustum shadow = new Frustum(modelView, projection);
        shadow.prepare(position.x, position.y, position.z);
        List<Mesh> all = new ArrayList<>(meshes.size());
        refreshShown((int) Math.floor(position.y) >> 4);
        for (Mesh mesh : meshes.values()) if (shown.contains(mesh.key) && shadow.isVisible(mesh.box)) all.add(mesh);
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
            CubeBuffer buffer = mesh.buffers[layer];
            if (buffer == null) continue;
            if (offset != null) {
                offset.set((float) (mesh.x - camera.x), (float) (mesh.y - camera.y), (float) (mesh.z - camera.z));
                offset.upload();
            }
            buffer.draw();
        }
        if (offset != null) offset.set(0.0F, 0.0F, 0.0F);
        shader.clear();
        CubeBuffer.unbind();
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
        compiledAt.clear();
        shown.clear();
        dirty.clear();
        CubeMesher.Built result;
        while ((result = built.poll()) != null) result.free();
    }
}
