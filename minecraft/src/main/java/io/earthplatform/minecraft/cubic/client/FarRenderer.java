package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubeCodec;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.Timings;
import io.earthplatform.minecraft.cubic.far.Far;
import io.earthplatform.minecraft.cubic.net.CubePayloads;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

/**
 * Draws the voxel pyramid beyond the streamed cubes, the way Voxy does: columns of voxel
 * sections from the server, meshed on workers into 16-byte faces and drawn by the far terrain's
 * own shader, a multi-draw per buffer of faces (see {@link FarGpu}). With a shader pack the faces
 * go through the pack's Voxy fragment code, so the pack lights them as it lights Voxy's terrain.
 * Voxels whose cubes the client already draws are left out. Water keeps the block format and the
 * pack's own water shader.
 */
public final class FarRenderer {
    /** Distance to the nearest edge of the coarsest level's square. */
    private static final float REACH = Far.OUTER * (float) Far.width(Far.MAX_LEVEL);
    /** Fog fades far terrain out before the coarsest square's nearest edge; the depth range covers its corners. */
    public static final float FOG_START = 0.25F * REACH, FOG_END = 0.95F * REACH, DEPTH_FAR = 2.0F * REACH;
    /**
     * Levels drawn with block textures; coarser ones use each face's average colour, which is what
     * a texture blurs to at that distance anyway and stays steady when the camera moves.
     */
    private static final int TEXTURED_LEVELS = Integer.getInteger("earthplatform.far.textured", 2);
    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "block/far_white");
    private static final int PLAN_TICKS = 10, MASK_TICKS = 4, RETRY_TICKS = 200, KEEP_TICKS = 1200, MAX_COLUMNS = 8000,
            SETTLE_TICKS = 3, HIDDEN_SETTLE_TICKS = 40, UPLOADS_PER_FRAME = 8, MESHES_IN_FLIGHT = 128;
    /** Above Everest (Y 8,912), where the jet stream layer starts, and a speed only elytra and fast travel reach. */
    private static final double JET_Y = 9_200, FAST_BLOCKS_PER_SECOND = 200;
    private static final long UPLOAD_NANOS = 3_000_000L;
    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ExecutorService MESHER = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "Earth far mesher " + THREADS.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private final ClientCubeWorld world;
    private final Registry<Biome> biomes;
    private final FarFaces faces = new FarFaces();
    private final FarLooks looks = new FarLooks();
    @Nullable private FarGpu gpu;
    private boolean unsupported;
    private final Long2ObjectOpenHashMap<Column> columns = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap requested = new Long2IntOpenHashMap();
    private final ConcurrentLinkedQueue<FarMesher.Built> built = new ConcurrentLinkedQueue<>();
    private final List<Column> drawn = new ArrayList<>();
    private final LongOpenHashSet drawnKeys = new LongOpenHashSet();
    /** This frame's drawn columns in view, nearest first, for the water pass. */
    private List<Column> visible = List.of();
    private int waterFrame = -1;
    private int ticks, frame, uploadedFrame = -1, replaced;
    /** The finest level asked for and drawn; coarser while the player is high up or fast (see {@link #finest}). */
    private int finest = Far.MIN_LEVEL;
    /** Meshes handed to the workers and not yet taken back, so the nearest due columns go first. */
    private int meshing;
    /** How long the last frame took, so uploads keep pace however low the frame rate goes. */
    private long frameStart = System.nanoTime(), frameNanos = 16_000_000L;
    private boolean closed;

    private static final class Column {
        final long key;
        final int level, x0, z0, bottom;
        final LevelChunkSection[] sections;
        final short[] drops;
        final AABB box;
        @Nullable LongOpenHashSet drawnCubes;
        int lastWanted, version, due = -1, slot = -1;
        /** The slot of a replaced column's faces still drawn here, freed once this column's own faces are up. */
        int oldSlot = -1;
        boolean building, stale, meshed;
        /** Its mask changed while a finer level drew over it, so its remesh waits unless it is drawn. */
        boolean maskStale;
        @Nullable FarGpu.Allocation faces;
        @Nullable VertexBuffer water;
        /** Face and water bytes uploaded for this column, for the debug screen. */
        long bytes;
        double distance;

        Column(long key, int bottom, LevelChunkSection[] sections, short[] drops) {
            this.key = key;
            this.level = Far.level(key);
            this.bottom = bottom;
            this.sections = sections;
            this.drops = drops;
            int width = Far.width(level);
            x0 = Far.x(key) * width; z0 = Far.z(key) * width;
            // Drops lower a column by up to a voxel.
            box = new AABB(x0, (double) bottom * width - Far.voxel(level), z0, x0 + width,
                    (double) (bottom + Math.max(1, sections.length)) * width, z0 + width);
        }

        int baseY() { return bottom * Far.width(level); }

        /**
         * Keeps drawing the faces of the column this one replaces until its own are built, so a
         * column the server sends again (after an edit, or a repeated request) never leaves a gap
         * that a coarser level fills for a moment.
         */
        void takeOver(Column old, @Nullable FarGpu gpu) {
            drawnCubes = old.drawnCubes;
            faces = old.faces;
            water = old.water;
            bytes = old.bytes;
            meshed = true;
            // Meshes of the old column still in flight no longer match.
            version = old.version + 1;
            // The old faces use the old column's slot, or the one it kept from the column before it.
            oldSlot = old.oldSlot;
            if (old.bottom == bottom) slot = old.slot;
            else if (oldSlot < 0) oldSlot = old.slot;
            else if (old.slot >= 0 && gpu != null) gpu.freeSlot(old.slot);
        }

        long neighbour(int side) {
            int x = Far.x(key), z = Far.z(key);
            return switch (side) {
                case 0 -> Far.key(level, x, z - 1);
                case 1 -> Far.key(level, x, z + 1);
                case 2 -> Far.key(level, x - 1, z);
                default -> Far.key(level, x + 1, z);
            };
        }

        void close(@Nullable FarGpu gpu) {
            if (water != null) { water.close(); water = null; }
            if (gpu != null) {
                gpu.free(faces);
                if (slot >= 0) gpu.freeSlot(slot);
                freeOldSlots(gpu);
            }
            faces = null;
            slot = -1;
        }

        void freeOldSlots(FarGpu gpu) {
            if (oldSlot >= 0) gpu.freeSlot(oldSlot);
            oldSlot = -1;
        }
    }

    FarRenderer(ClientCubeWorld world) {
        this.world = world;
        this.biomes = world.client().registryAccess().registryOrThrow(Registries.BIOME);
        requested.defaultReturnValue(Integer.MIN_VALUE / 2);
    }

    public int columnCount() { return columns.size(); }
    /** GPU memory of every uploaded column's faces and water, in bytes. */
    public long vertexBytes() {
        long total = 0;
        for (Column column : columns.values()) total += column.bytes;
        return total;
    }

    /** The far terrain's GPU side, made on first use on the render thread; null where GL is too old. */
    @Nullable private FarGpu gpu() {
        if (gpu == null && !unsupported) {
            if (!FarGpu.supported()) {
                unsupported = true;
                EarthPlatformMod.LOGGER.warn("Far terrain needs OpenGL 3.3; it is off");
                return null;
            }
            gpu = new FarGpu();
            gpu.looks(looks);
        }
        return gpu;
    }
    public int drawnCount() { return drawn.size(); }
    /** Columns received again while already held, as when the server pushes an edit. */
    public int replacedCount() { return replaced; }

    // Columns ------------------------------------------------------------------------------------

    public void receive(CubePayloads.FarColumnData payload) {
        if (closed || !Far.valid(payload.key())) return;
        requested.remove(payload.key());
        LevelChunkSection[] sections = new LevelChunkSection[payload.sections().size()];
        for (int i = 0; i < sections.length; i++) {
            byte[] bytes = payload.sections().get(i);
            if (bytes.length > 0) sections[i] = CubeCodec.readSection(bytes, biomes);
        }
        Column column = new Column(payload.key(), payload.bottom(), sections, payload.drops());
        column.lastWanted = ticks;
        Column old = columns.put(column.key, column);
        if (old != null) {
            replaced++;
            if (old.meshed) column.takeOver(old, gpu);
            else { column.drawnCubes = old.drawnCubes; old.close(gpu); }
        }
        schedule(column);
        int level = column.level, x = Far.x(column.key), z = Far.z(column.key);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            Column side = columns.get(Far.key(level, x + direction.getStepX(), z + direction.getStepZ()));
            if (side != null) schedule(side);
        }
    }

    /** Rebuilds every column, as when a shader pack comes or goes and changes the vertex format and block ids. */
    void remeshAll() {
        for (Column column : columns.values()) schedule(column);
    }

    /** Meshes a column a few ticks from now, so neighbours arriving together cost one mesh each. */
    private void schedule(Column column) {
        if (column.due < 0) column.due = ticks + SETTLE_TICKS;
    }

    /** Asks for missing columns, forgets old ones, refreshes masks and starts due meshes. */
    public void tick() {
        ticks++;
        var player = Minecraft.getInstance().player;
        if (closed || player == null) return;
        finest = finest(player.getY(), player.getDeltaMovement().horizontalDistance() * 20.0, finest);
        if (ticks % MASK_TICKS == 0) mask();
        for (Column column : drawn) if (column.maskStale) column.due = Math.min(column.due, ticks + SETTLE_TICKS);
        submitDue(player.getBlockX(), player.getBlockZ());
        if (ticks % PLAN_TICKS != 0) return;
        LongArrayList missing = new LongArrayList();
        int bx = player.getBlockX(), bz = player.getBlockZ();
        Far.around(bx, bz, finest, key -> {
            Column column = columns.get(key);
            if (column != null) column.lastWanted = ticks;
            else if (ticks - requested.get(key) > RETRY_TICKS) missing.add(key);
        });
        // Ask for what a player would see change most first: the server makes columns in this order.
        missing.unstableSort((a, b) -> Long.compare(priority(a, bx, bz), priority(b, bx, bz)));
        for (int i = 0; i < missing.size(); i += CubePayloads.FarRequest.MAX_KEYS) {
            long[] keys = missing.subList(i, Math.min(missing.size(), i + CubePayloads.FarRequest.MAX_KEYS)).toLongArray();
            for (long key : keys) requested.put(key, ticks);
            PacketDistributor.sendToServer(new CubePayloads.FarRequest(keys));
        }
        requested.long2IntEntrySet().removeIf(entry -> ticks - entry.getIntValue() > RETRY_TICKS * 4);
        evict();
    }

    /**
     * The order to ask for a column in: by how far it is from a position in its own widths, so the
     * columns right around the player come first instead of last, when coarse voxels stood in
     * for them close up after every move.
     */
    private long priority(long key, int bx, int bz) {
        int level = Far.level(key), width = Far.width(level);
        long x0 = (long) Far.x(key) * width, z0 = (long) Far.z(key) * width;
        long dx = Math.max(0, Math.max(x0 - bx, bx - (x0 + width))), dz = Math.max(0, Math.max(z0 - bz, bz - (z0 + width)));
        // Hundredths of a width, and finer levels first among equals. Columns a finer level draws
        // over only stand in while it loads, so they come after every drawn one.
        long order = Math.max(dx, dz) * 100 / width * 8 + level;
        return Far.refined(level, Far.x(key), Far.z(key), bx, bz, finest) ? order + (1L << 32) : order;
    }

    /**
     * The finest level worth asking for. Each level is a ring 47 columns across, so crossing one
     * column width brings in 47 new ones: at the jet stream's 1,200 blocks a second the finest two
     * levels alone would need about 2,600 columns a second, several times what the server makes,
     * while from 9 km up they only cover the ground straight below. The thresholds differ going
     * up and coming down so the levels do not flicker at the edge.
     */
    static int finest(double y, double blocksPerSecond, int current) {
        boolean jet = y >= (current >= 4 ? JET_Y - 400 : JET_Y);
        boolean fast = blocksPerSecond >= (current >= 3 ? FAST_BLOCKS_PER_SECOND / 2 : FAST_BLOCKS_PER_SECOND);
        return jet ? 4 : fast ? 3 : Far.MIN_LEVEL;
    }

    private void evict() {
        List<Column> old = new ArrayList<>();
        for (Column column : columns.values()) if (column.lastWanted != ticks) old.add(column);
        old.sort(Comparator.comparingInt(column -> column.lastWanted));
        int excess = columns.size() - MAX_COLUMNS;
        for (Column column : old) {
            if (excess <= 0 && ticks - column.lastWanted <= KEEP_TICKS) break;
            columns.remove(column.key);
            column.close(gpu);
            excess--;
        }
    }

    /**
     * Hands each column the cubes the cube renderer already draws inside it (plus a voxel's margin),
     * and remeshes the columns whose set changed.
     */
    private void mask() {
        Long2ObjectOpenHashMap<LongOpenHashSet> sets = new Long2ObjectOpenHashMap<>();
        for (LongIterator it = world.renderer().compiledCubes().iterator(); it.hasNext(); ) {
            long cube = it.nextLong();
            int bx = CubeKey.x(cube) << 4, bz = CubeKey.z(cube) << 4;
            for (int level = Far.MIN_LEVEL; level <= Far.MAX_LEVEL; level++) {
                int v = Far.voxel(level);
                for (int x = Far.column(bx - v, level); x <= Far.column(bx + 15 + v, level); x++) {
                    for (int z = Far.column(bz - v, level); z <= Far.column(bz + 15 + v, level); z++) {
                        long key = Far.key(level, x, z);
                        if (columns.containsKey(key)) sets.computeIfAbsent(key, unused -> new LongOpenHashSet()).add(cube);
                    }
                }
            }
        }
        for (Column column : columns.values()) {
            LongOpenHashSet set = sets.get(column.key);
            if (Objects.equals(set, column.drawnCubes)) continue;
            column.drawnCubes = set;
            // Coarse columns under the finer ones near the player see every cube that compiles there;
            // remeshing each at once kept the far meshers busy with columns nobody sees, so hidden
            // ones gather their changes into one remesh a while later.
            if (drawnKeys.contains(column.key) || !column.meshed) schedule(column);
            else {
                column.maskStale = true;
                if (column.due < 0) column.due = ticks + HIDDEN_SETTLE_TICKS;
            }
        }
    }

    /**
     * Starts due meshes, the drawn columns nearest the player first, keeping only a short queue at
     * the workers: with every due column queued at once, in no order, a backlog (after a teleport
     * or a shader reload) left coarse stand-ins near the player until it had all drained.
     */
    private void submitDue(int bx, int bz) {
        List<Column> due = new ArrayList<>();
        for (Column column : columns.values()) {
            if (column.due < 0 || column.due > ticks) continue;
            if (column.building) submit(column);
            else due.add(column);
        }
        if (due.isEmpty()) return;
        due.sort(Comparator.comparingLong(column -> (drawnKeys.contains(column.key) ? 0 : 1L << 40) + priority(column.key, bx, bz)));
        for (Column column : due) {
            if (meshing >= MESHES_IN_FLIGHT) break;
            submit(column);
        }
    }

    private void submit(Column column) {
        column.due = -1;
        column.maskStale = false;
        FarGpu gpu = gpu();
        if (gpu == null) return;
        if (column.building) { column.stale = true; return; }
        column.building = true;
        column.stale = false;
        if (column.slot < 0) column.slot = gpu.slot(column.x0, column.baseY(), column.z0, column.level);
        FarMesher.Side[] sides = new FarMesher.Side[4];
        for (int i = 0; i < 4; i++) {
            Column side = columns.get(column.neighbour(i));
            if (side != null) sides[i] = new FarMesher.Side(side.sections, side.bottom, side.drops);
        }
        var level = world.client();
        float[] shade = new float[6];
        for (Direction direction : Direction.values()) shade[direction.get3DDataValue()] = level.getShade(direction, true);
        FarMesher.Job job = new FarMesher.Job(column.key, ++column.version, column.level, column.slot, column.x0, column.z0, column.bottom,
                column.sections, column.drops, sides, column.drawnCubes, column.level <= TEXTURED_LEVELS, looks, faces,
                ShaderBlocks.ids(), shade, white());
        meshing++;
        MESHER.execute(() -> {
            long start = System.nanoTime();
            try {
                built.add(FarMesher.build(job));
                Timings.FAR_MESH.since(start);
            }
            catch (Throwable failure) {
                EarthPlatformMod.LOGGER.error("Far column mesh failed", failure);
                built.add(new FarMesher.Built(job.key(), -job.version(), new int[0], new int[FarMesher.GROUPS], null));
            }
        });
    }

    /** Uploads finished meshes: a few every frame, and more while a small part of the last frame's time lasts. */
    private void upload(FarGpu gpu) {
        long deadline = System.nanoTime() + Math.max(UPLOAD_NANOS, frameNanos / 8);
        for (int n = 0; n < UPLOADS_PER_FRAME || System.nanoTime() < deadline; n++) {
            FarMesher.Built result = built.poll();
            if (result == null) return;
            meshing--;
            Column column = columns.get(result.key());
            if (closed || column == null || Math.abs(result.version()) != column.version) { result.free(); continue; }
            column.building = false;
            if (result.version() > 0) {
                column.meshed = true;
                column.bytes = result.bytes();
                gpu.free(column.faces);
                column.faces = gpu.upload(result.quads(), result.counts());
                column.water = upload(column.water, result.water());
                column.freeOldSlots(gpu);
            }
            if (column.stale) schedule(column);
        }
    }

    /**
     * The middle two texels of the plain white sprite (u0, v0, u1, v1) for untextured water. The
     * span is kept, not collapsed to a point, as shader packs derive surface tangents from it.
     */
    private static float[] white() {
        TextureAtlasSprite white = FarFaces.sprite(WHITE);
        float du = (white.getU1() - white.getU0()) / 16.0F, dv = (white.getV1() - white.getV0()) / 16.0F;
        float u = (white.getU0() + white.getU1()) / 2, v = (white.getV0() + white.getV1()) / 2;
        return new float[] {u - du, v - dv, u + du, v + dv};
    }

    /** Puts a built water mesh into a buffer, making or freeing the buffer as needed. */
    @Nullable private static VertexBuffer upload(@Nullable VertexBuffer buffer, @Nullable FarMesher.Part part) {
        if (part == null) {
            if (buffer != null) buffer.close();
            return null;
        }
        if (buffer == null) buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(part.mesh());
        part.memory().close();
        VertexBuffer.unbind();
        return buffer;
    }

    // Drawing ------------------------------------------------------------------------------------

    public void beginFrame() {
        frame++;
        long now = System.nanoTime();
        frameNanos = Math.min(now - frameStart, 1_000_000_000L);
        frameStart = now;
    }

    /** Draws the columns for the camera's squares, using a finer level only once all four of its columns are ready. */
    public void draw(Camera camera, Frustum frustum, Matrix4f modelView, Matrix4f projection) {
        FarGpu gpu = closed ? null : gpu();
        if (gpu == null) return;
        if (uploadedFrame != frame) {
            uploadedFrame = frame;
            long start = System.nanoTime();
            upload(gpu);
            Timings.FAR_UPLOAD.since(start);
        }
        long start = System.nanoTime();
        try { drawColumns(gpu, camera, frustum, modelView, projection); }
        finally { Timings.FAR_DRAW.since(start); }
    }

    private void drawColumns(FarGpu gpu, Camera camera, Frustum frustum, Matrix4f modelView, Matrix4f projection) {
        Vec3 position = camera.getPosition();
        int bx = (int) Math.floor(position.x), bz = (int) Math.floor(position.z);
        drawn.clear();
        int px = Far.column(bx, Far.MAX_LEVEL), pz = Far.column(bz, Far.MAX_LEVEL);
        for (int z = pz - Far.OUTER; z <= pz + Far.OUTER; z++) for (int x = px - Far.OUTER; x <= px + Far.OUTER; x++) {
            visit(Far.MAX_LEVEL, x, z, bx, bz);
        }
        drawnKeys.clear();
        for (Column column : drawn) drawnKeys.add(column.key);
        List<Column> visible = new ArrayList<>(drawn.size());
        for (Column column : drawn) {
            if (column.faces == null && column.water == null || !frustum.isVisible(column.box)) continue;
            double dx = column.box.getCenter().x - position.x, dz = column.box.getCenter().z - position.z;
            column.distance = dx * dx + dz * dz;
            visible.add(column);
        }
        visible.sort(Comparator.comparingDouble(column -> column.distance));
        this.visible = visible;
        if (visible.isEmpty()) return;
        boolean pack = ShaderBlocks.packInUse();
        RenderType type = RenderType.solid();
        type.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        // Applying the terrain shader binds a shader pack's terrain buffers, which the far terrain draws into too.
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, Minecraft.getInstance().getWindow());
        shader.apply();
        float[] shade = new float[6];
        for (Direction direction : Direction.values()) shade[direction.get3DDataValue()] = world.client().getShade(direction, true);
        if (gpu.begin(pack, pack ? ShaderBlocks.voxyPatch() : null, modelView, projection, position, shade)) {
            for (Column column : visible) {
                FarGpu.Allocation faces = column.faces;
                if (faces == null) continue;
                AABB box = column.box;
                // Only face groups turned toward the camera can show.
                if (position.y < box.maxY) gpu.add(faces, 0);
                if (position.y > box.minY) gpu.add(faces, 1);
                if (position.z < box.maxZ) gpu.add(faces, 2);
                if (position.z > box.minZ) gpu.add(faces, 3);
                if (position.x < box.maxX) gpu.add(faces, 4);
                if (position.x > box.minX) gpu.add(faces, 5);
                for (int side = 0; side < 4; side++) {
                    if (drawnKeys.contains(column.neighbour(side))) continue;
                    boolean facing = switch (side) {
                        case 0 -> position.z < box.minZ + 1;
                        case 1 -> position.z > box.maxZ - 1;
                        case 2 -> position.x < box.minX + 1;
                        default -> position.x > box.maxX - 1;
                    };
                    if (facing) gpu.add(faces, FarMesher.FIRST_WALL + side);
                }
            }
            gpu.end();
        }
        shader.clear();
        type.clearRenderState();
    }

    /** Draws the visible columns' water see-through, farthest first, before the near cubes' own water. */
    public void drawWater(Camera camera, Matrix4f modelView, Matrix4f projection) {
        if (closed || waterFrame == frame) return;
        waterFrame = frame;
        boolean any = false;
        for (Column column : visible) if (column.water != null) { any = true; break; }
        if (!any) return;
        Vec3 position = camera.getPosition();
        RenderType type = RenderType.translucent();
        type.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, Minecraft.getInstance().getWindow());
        shader.apply();
        Uniform offset = shader.CHUNK_OFFSET;
        for (int i = visible.size() - 1; i >= 0; i--) {
            Column column = visible.get(i);
            if (column.water == null) continue;
            if (offset != null) {
                offset.set((float) (column.x0 - position.x), (float) (column.baseY() - position.y), (float) (column.z0 - position.z));
                offset.upload();
            }
            column.water.bind();
            column.water.draw();
        }
        if (offset != null) offset.set(0.0F, 0.0F, 0.0F);
        shader.clear();
        VertexBuffer.unbind();
        type.clearRenderState();
    }

    /**
     * Draws a column, or its four children where a finer level replaces it. A replaced column only
     * stands in while its children load if it has a mesh itself: it is asked for last, so after a
     * goto it usually has none, and waiting for every child down to the finest level left the
     * ground around the player empty until the whole tree under it had arrived.
     */
    private void visit(int level, int x, int z, int bx, int bz) {
        Column column = columns.get(Far.key(level, x, z));
        boolean meshed = column != null && column.meshed;
        // Rising into the jet stream layer makes a coarser level the finest: until its columns
        // arrive, the finer ones already here keep standing in for them.
        boolean refined = Far.refined(level, x, z, bx, bz, finest) || !meshed && Far.refined(level, x, z, bx, bz);
        if (refined && (!meshed || childrenReady(level, x, z, bx, bz))) {
            for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) visit(level - 1, 2 * x + dx, 2 * z + dz, bx, bz);
            return;
        }
        if (meshed) drawn.add(column);
    }

    private boolean ready(int level, int x, int z, int bx, int bz) {
        Column column = columns.get(Far.key(level, x, z));
        if (column != null && column.meshed) return true;
        return Far.refined(level, x, z, bx, bz, finest) && childrenReady(level, x, z, bx, bz);
    }

    private boolean childrenReady(int level, int x, int z, int bx, int bz) {
        for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
            if (!ready(level - 1, 2 * x + dx, 2 * z + dz, bx, bz)) return false;
        }
        return true;
    }

    void close() {
        closed = true;
        for (Column column : columns.values()) column.close(gpu);
        columns.clear();
        FarMesher.Built result;
        while ((result = built.poll()) != null) result.free();
        if (gpu != null) { gpu.close(); gpu = null; }
    }
}
