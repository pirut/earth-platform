package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.geography.globe.NamedThreads;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.Timings;
import io.earthplatform.minecraft.cubic.ViewOrder;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final int THREAD_COUNT = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2));
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREAD_COUNT,
            NamedThreads.numbered("Earth cube mesher", Thread.NORM_PRIORITY - 1));
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
    /**
     * Cubes being meshed when a shader pack came or went. Iris picks the vertex format when the
     * mesher starts a buffer, so these come back in the old one: drawn with the new one, their
     * vertices turned into huge dark slabs across the view. They are thrown away and meshed again.
     */
    private final LongOpenHashSet staleBuilds = new LongOpenHashSet();
    /** Cubes whose current contents have a mesh (or need none), so far terrain can leave them out. */
    private final LongOpenHashSet compiled = new LongOpenHashSet();
    private final OwnedResultQueue<CubeMesher.Built> built = new OwnedResultQueue<>(CubeMesher.Built::free);
    /**
     * Meshes of cubes the server has taken back, still drawn until the far terrain under them has
     * filled the hole it left for them, and the tick each must go by at the latest. Dropped at
     * once, a camera climbing out of the band of loaded cubes saw the whole ground beneath it vanish
     * until the far columns there had remeshed.
     */
    private final Long2ObjectOpenHashMap<Mesh> retiring = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap retireBy = new Long2IntOpenHashMap();
    private static final int RETIRE_TICKS = 100;
    private final List<Mesh> visible = new ArrayList<>();
    private int ticks, drawnFrame = -1, frame;
    /** The level passes (see {@link LevelPasses}) this renderer last culled for, drew the opaque layers in and drew the translucent ones in. */
    private int culledPass = -1, opaquePass = -1, translucentPass = -1;
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
    /** Cube meshes in the most recently rendered view, including retained transition meshes. */
    public int visibleMeshCount() { return visible.size(); }
    public int pendingCount() { return dirty.size() + building.size(); }

    /** Meshes in view (within 60 degrees of where the player looks) and behind, to see how the view fills in. */
    public int[] meshesByView(Vec3 camera, float yaw) {
        double lookX = ViewOrder.lookX(yaw), lookZ = ViewOrder.lookZ(yaw);
        int cx = (int) Math.floor(camera.x) >> 4, cz = (int) Math.floor(camera.z) >> 4;
        int[] counts = new int[2];
        for (long key : meshes.keySet()) counts[ViewOrder.later(CubeKey.x(key) - cx, CubeKey.z(key) - cz, lookX, lookZ, 1.5) > 1.0 ? 1 : 0]++;
        return counts;
    }
    /** Cubes whose current contents are drawn here; read only. */
    LongOpenHashSet compiledCubes() { return compiled; }

    /** Counts up whenever a cube is compiled again or stops being drawn, so far terrain re-masks only then. */
    int compiledVersion() { return compiledVersion; }

    private int compiledVersion;

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
        for (long key : building) { markDirty(key); staleBuilds.add(key); }
        // Retiring meshes are in the old format too, and nothing remeshes them.
        for (Mesh mesh : retiring.values()) mesh.close();
        retiring.clear();
        retireBy.clear();
    }

    void cubeArrived(long key) { markAround(CubeKey.x(key), CubeKey.y(key), CubeKey.z(key), false); }

    void drop(long key) {
        dirty.remove(key);
        dirtySince.remove(key);
        versions.remove(key);
        light.remove(key);
        compiled.remove(key);
        compiledVersion++;
        Mesh mesh = meshes.remove(key);
        if (mesh == null) return;
        Mesh older = retiring.put(key, mesh);
        if (older != null) older.close();
        retireBy.put(key, ticks + RETIRE_TICKS);
    }

    /** Stops drawing a dropped cube's mesh once far terrain covers its place, or once it has waited long enough. */
    private void retire() {
        if (retiring.isEmpty()) return;
        FarRenderer far = world.far();
        for (var it = retiring.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            long key = entry.getLongKey();
            if (ticks <= retireBy.get(key) && far != null && far.holeFor(key)) continue;
            entry.getValue().close();
            it.remove();
            retireBy.remove(key);
        }
    }

    /** A cube is drawn again: its old mesh, if still retiring, goes. */
    private void unretire(long key) {
        Mesh mesh = retiring.remove(key);
        if (mesh == null) return;
        mesh.close();
        retireBy.remove(key);
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
        if (old == null) { firstTops(cubeX, cubeZ); return; }
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

    /**
     * A column's tops came for the first time. Cubes beside it meshed before then, as those with a neighbour slow to
     * come are, took it for open sky at every height, and under water that lit the bed beside it in pale squares that
     * stayed: they are meshed again. Only cubes meshed or meshing, in the band the player is sent.
     */
    private void firstTops(int cubeX, int cubeZ) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        int cy = player.getBlockY() >> 4, reach = Cubic.VERTICAL_RADIUS + 1;
        for (int y = cy - reach; y <= cy + reach; y++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            long key = CubeKey.pack(cubeX + dx, y, cubeZ + dz);
            if (light.containsKey(key) || building.contains(key)) markDirty(key);
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

    public void tick() {
        ticks++;
        retire();
    }

    private boolean neighboursReady(long key) {
        int x = CubeKey.x(key), y = CubeKey.y(key), z = CubeKey.z(key);
        return world.cube(CubeKey.pack(x + 1, y, z)) != null && world.cube(CubeKey.pack(x - 1, y, z)) != null
                && world.cube(CubeKey.pack(x, y + 1, z)) != null && world.cube(CubeKey.pack(x, y - 1, z)) != null
                && world.cube(CubeKey.pack(x, y, z + 1)) != null && world.cube(CubeKey.pack(x, y, z - 1)) != null;
    }

    private void schedule(Vec3 camera, float yaw) {
        int limit = inFlight();
        if (dirty.isEmpty()) return;
        double lookX = ViewOrder.lookX(yaw), lookZ = ViewOrder.lookZ(yaw);
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
            long dx = CubeKey.x(key) - cx, dy = CubeKey.y(key) - cy, dz = CubeKey.z(key) - cz, distance = dx * dx + dy * dy * 2 + dz * dz;
            // Cubes out of view count as further off, so the view fills in first.
            if (ViewOrder.ON) {
                double later = ViewOrder.later(dx, dz, lookX, lookZ, 1.5);
                distance = (long) (distance * later * later);
            }
            // Distance in the high bits and the index below, so one primitive sort orders them.
            order.add(Math.min(distance, 1L << 40) << 22 | ready.size());
            ready.add(key);
        }
        int count = Math.min(ready.size(), limit - building.size());
        if (count <= 0) return;
        // Nearest first, counting the view; the picks can be hundreds.
        long[] sorted = order.toLongArray();
        if (count < sorted.length) Arrays.sort(sorted);
        for (int i = 0; i < count; i++) submit(ready.getLong((int) (sorted[i] & ((1 << 22) - 1))), camera);
    }

    private void compileEmpty(long key) {
        unretire(key);
        Mesh mesh = meshes.remove(key);
        if (mesh != null) mesh.close();
        light.remove(key);
        compiled.add(key);
        compiledVersion++;
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
            if (built.isClosed()) return;
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
    private void upload() { upload(false); }

    private void upload(boolean all) {
        long deadline = System.nanoTime() + frameNanos / 8;
        for (int n = 0; all || n < MIN_UPLOADS_PER_FRAME || System.nanoTime() < deadline; n++) {
            CubeMesher.Built result = built.poll();
            if (result == null) return;
            building.remove(result.key());
            boolean stale = staleBuilds.remove(result.key());
            if (stale || closed || world.cube(result.key()) == null || result.version() < 0) { result.free(); continue; }
            if (result.light() != null) light.put(result.key(), result.light());
            compiled.add(result.key());
            compiledVersion++;
            unretire(result.key());
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

    /**
     * Before the first layer: once a frame, uploads finished meshes and queues more; once each time
     * the level is drawn, culls for that pass's camera. A VR mod draws the level for each eye, and the
     * first eye's list left the other eye's edges empty and its nearest-first order wrong.
     */
    private void prepare(Camera camera, Frustum frustum) {
        Vec3 position = camera.getPosition();
        if (drawnFrame != frame) {
            drawnFrame = frame;
            long start = System.nanoTime();
            upload();
            Timings.CUBE_UPLOAD.since(start);
            schedule(position, camera.getYRot());
        }
        if (culledPass == LevelPasses.current()) return;
        culledPass = LevelPasses.current();
        visible.clear();
        for (Mesh mesh : meshes.values()) see(mesh, frustum, position);
        for (Mesh mesh : retiring.values()) see(mesh, frustum, position);
        visible.sort(Comparator.comparingDouble(mesh -> mesh.distance));
    }

    private void see(Mesh mesh, Frustum frustum, Vec3 position) {
        if (!frustum.isVisible(mesh.box)) return;
        double dx = mesh.x + 8 - position.x, dy = mesh.y + 8 - position.y, dz = mesh.z + 8 - position.z;
        mesh.distance = dx * dx + dy * dy + dz * dz;
        visible.add(mesh);
    }

    /** For a flawless frame: uploads every finished mesh and starts more; returns how many are still being meshed. */
    int settle(Vec3 camera, float yaw) {
        upload(true);
        schedule(camera, yaw);
        return building.size();
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
        opaquePass = LevelPasses.current();
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
        // Called at two stages so it draws whichever comes; once per pass, not once per frame, or a
        // second eye got no water or glass.
        if (translucentPass == LevelPasses.current()) return;
        translucentPass = LevelPasses.current();
        prepare(camera, frustum);
        draw(CubeMesher.LAYERS.indexOf(RenderType.translucent()), camera.getPosition(), modelView, projection, true);
        draw(CubeMesher.LAYERS.indexOf(RenderType.tripwire()), camera.getPosition(), modelView, projection, true);
    }

    /** Whether the opaque layers are drawn in this pass of the level already. */
    public boolean drewOpaqueThisPass() { return opaquePass == LevelPasses.current(); }

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
        for (Mesh mesh : meshes.values()) if (shadow.isVisible(mesh.box)) all.add(mesh);
        for (Mesh mesh : retiring.values()) if (shadow.isVisible(mesh.box)) all.add(mesh);
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
        built.close();
        for (Mesh mesh : meshes.values()) mesh.close();
        meshes.clear();
        for (Mesh mesh : retiring.values()) mesh.close();
        retiring.clear();
        retireBy.clear();
        light.clear();
        compiled.clear();
        compiledVersion++;
        dirty.clear();
    }
}
