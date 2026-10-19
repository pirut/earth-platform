package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.globe.NamedThreads;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubeCodec;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.Timings;
import io.earthplatform.minecraft.cubic.ViewOrder;
import io.earthplatform.minecraft.cubic.far.Far;
import io.earthplatform.minecraft.cubic.net.CubePayloads;
import io.earthplatform.minecraft.cubic.mixin.FrustumAccess;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final float REACH = Far.reach();
    /**
     * Where the haze over low ground ends, in meters: by default just inside the far view's edge,
     * so everything drawn stays clear and the haze only hides where the terrain stops. It no longer
     * follows the old 23 km plains haze. {@code -Dearthplatform.far.visibility} sets a real-world
     * visibility instead, in meters (about 20 to 50 km in humid air, 100 km and more in dry western
     * air), never past the edge. The levels beyond still show what rises above the haze (see
     * {@link FarShaders}), so distant mountains stand clear.
     */
    private static final float VISIBILITY = Math.min(0.95F * REACH,
            Math.max(5_000.0F, Float.parseFloat(System.getProperty("earthplatform.far.visibility", "1e9"))));
    /**
     * Haze distances in blocks where a block is a meter, as at an Earth world's home latitude; see
     * {@link #hazeScale}. At the edge the haze starts thin at 70% of the way out; a set visibility
     * starts it a quarter of the way out. The depth range covers the coarsest square's corners.
     */
    public static final float FOG_START = (VISIBILITY >= 0.95F * REACH ? 0.7F : 0.25F) * VISIBILITY, FOG_END = VISIBILITY,
            DEPTH_FAR = 2.0F * REACH;
    /** True when the haze sits at the far view's edge, which is in blocks, not ground meters. */
    public static final boolean HAZE_AT_EDGE = VISIBILITY >= 0.95F * REACH;
    /**
     * Levels drawn with block textures; coarser ones use each face's average colour, which is what
     * a texture blurs to at that distance anyway and stays steady when the camera moves.
     */
    private static final int TEXTURED_LEVELS = Integer.getInteger("earthplatform.far.textured", Far.MAX_LEVEL);
    /**
     * Whether a level fades into the next coarser one over the coarser level's outer ring of
     * middle columns (see {@link FarShaders#fade}), rather than meeting it at a seam.
     */
    private static final boolean FADE = !"false".equals(System.getProperty("earthplatform.far.fade"));
    /** Whether this frame's levels fade into each other over a ring, or meet at a seam (see {@link #drawColumns}). */
    private boolean bandFade;
    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "block/far_white");
    private static final int PLAN_TICKS = 10, MASK_TICKS = 4, RETRY_TICKS = 200, KEEP_TICKS = 1200, MAX_COLUMNS = 8000,
            SETTLE_TICKS = 3, HIDDEN_SETTLE_TICKS = 40, UPLOADS_PER_FRAME = 8, MESHES_IN_FLIGHT = 128;
    /** Speeds only elytra and the jet stream reach, in blocks a second. */
    private static final double FAST_BLOCKS_PER_SECOND = 200, JET_BLOCKS_PER_SECOND = 800;
    /** The finest level at jet stream speed: 64-block voxels, as before the far levels reached farther. */
    private static final int JET_LEVEL = 6;
    private static final long UPLOAD_NANOS = 3_000_000L;
    /** Experimental admission pacing; the established convergence policy remains the default. */
    private static final boolean STRICT_UPLOAD = Boolean.getBoolean("earthplatform.far.strictUpload");
    private static final long UPLOAD_BYTES = Math.max(1, Long.getLong("earthplatform.far.uploadBytes", 4L << 20));
    /**
     * Columns asked of the server and not yet back. Kept short so the server's queue always holds
     * what the player wants most now: with every missing column asked for at once, a walk or a turn
     * left thousands queued in the old order, and the view in front waited behind them.
     */
    private static final int IN_FLIGHT = Integer.getInteger("earthplatform.far.inFlight", 192);
    /** Far mesher threads: two, or a quarter of the cores on bigger machines, so meshing keeps up with the server's workers. */
    private static final int MESH_THREADS = Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 2, 4);
    private static final ExecutorService MESHER = Executors.newFixedThreadPool(MESH_THREADS,
            NamedThreads.numbered("Earth far mesher", Thread.NORM_PRIORITY - 1));

    /** An Earth world's metres per block at the equator, or 0 until the server says. */
    private volatile double metresPerBlock;
    private final ClientCubeWorld world;
    private final Registry<Biome> biomes;
    private final FarFaces faces = new FarFaces();
    private final FarLooks looks = new FarLooks();
    @Nullable private FarGpu gpu;
    private boolean unsupported;
    private final Long2ObjectOpenHashMap<Column> columns = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap requested = new Long2IntOpenHashMap();
    /** Missing columns in the order to ask for them, from the last plan, and how many of them have been looked at. */
    private final LongArrayList plan = new LongArrayList();
    private int planned;
    /** The player's horizontal look direction, so columns in view come before those behind. */
    private double lookX, lookZ = 1;
    /** Columns the view wants at their own level, and how many of them are drawn: how settled the far terrain is. */
    private int wanted, settled, planSize, wantedInView, settledInView;
    /** Columns kept beyond what the plan wants, for going back over ground just left. */
    private static final int SPARE_COLUMNS = 4000;
    private final OwnedResultQueue<FarMesher.Built> built = new OwnedResultQueue<>(FarMesher.Built::free);
    private final List<Column> drawn = new ArrayList<>();
    private final LongOpenHashSet drawnKeys = new LongOpenHashSet();
    /** Drawn columns fading in over the finer level they give way to; they keep out of the water passes. */
    private final LongOpenHashSet fadingIn = new LongOpenHashSet();
    /** This pass's drawn columns in view, nearest first, for the water pass. */
    private List<Column> visible = List.of();
    /** Off with {@code -Dearthplatform.far.cullTree=false}: every drawn column is tested against the view on its own. */
    private static final boolean CULL_TREE = !"false".equals(System.getProperty("earthplatform.far.cullTree"));
    private final FarCullTree cullTree = new FarCullTree();
    private static final boolean CACHE_PICK = !"false".equals(System.getProperty("earthplatform.far.cachePick"));
    private final FarPickState pick = new FarPickState();
    // Opt-in until matched client tests clear the stationary p95/p99 regression.
    private static final boolean CACHE_COMMANDS = Boolean.getBoolean("earthplatform.far.cacheCommands");
    private long selectionVersion;
    @Nullable private FarGpu.Commands fullCommands, nearCommands;
    private List<Column> nearVisible = List.of();
    private final FarOrder order = new FarOrder();
    /** Uploads and fade stamps also invalidate a pick when no column has come or gone. */
    private int pickVersion;
    private boolean pickAnimating;
    static final boolean FAST_WATER = !"false".equals(System.getProperty("earthplatform.far.fastWater"));
    /** Preserve water order while skipping dry columns and walls hidden by a drawn neighbor. */
    private final List<Column> waterVisible = new ArrayList<>(), waterSurfaces = new ArrayList<>(), waterEdges = new ArrayList<>();
    private final FarWaterBatch waterDeepen = new FarWaterBatch(), waterRestore = new FarWaterBatch();
    /** The level pass (see {@link LevelPasses}) whose water is drawn; a VR mod's second eye needs its own. */
    private int waterPass = -1;
    private int ticks, frame, uploadedFrame = -1, replaced;
    /** The finest level asked for and drawn; coarser while the player is high up or fast (see {@link #finest}). */
    private int finest = Far.MIN_LEVEL;
    /** Meshes handed to the workers and not yet taken back, so the nearest due columns go first. */
    private int meshing;
    /** How long the last frame took, so uploads keep pace however low the frame rate goes. */
    private long frameStart = System.nanoTime(), frameNanos = 16_000_000L;
    private boolean closed;

    /**
     * The last mesh version handed out, for every column alike. A column's versions once started
     * at 1 again whenever it was made anew (sent again before its first mesh was up, or evicted and
     * sent again), so a mesh still in flight for the column it replaced could match the new one and
     * be put up with the old column's slot, which had been freed and could be given to another
     * column: its faces were then drawn at that column's place and scale, stretched or squeezed.
     */
    private static int lastVersion;

    /** A mesh version no column has had; always positive, as failed meshes report theirs negated. */
    private static int nextVersion() {
        if (++lastVersion <= 0) lastVersion = 1;
        return lastVersion;
    }

    private static final class Column {
        final long key;
        final int level, x0, z0, bottom;
        final LevelChunkSection[] sections;
        final char[] drops;
        final AABB box;
        /** Block Y just above the column's highest voxel, or MIN_VALUE when it holds only air. */
        final int top;
        @Nullable LongOpenHashSet drawnCubes;
        /** The cubes left out of the faces it draws now, and of the mesh being built (see {@link FarRenderer#holeFor}). */
        @Nullable LongOpenHashSet meshedCubes, buildingCubes;
        int lastWanted, version, due = -1, slot = -1;
        /** The slot of a replaced column's faces still drawn here, freed once this column's own faces are up. */
        int oldSlot = -1;
        boolean building, stale, meshed;
        /** Its mask changed while a finer level drew over it, so its remesh waits unless it is drawn. */
        boolean maskStale;
        @Nullable FarGpu.Allocation faces;
        @Nullable CubeBuffer water;
        /** Water walls by side (north, south, west, east), drawn where that side's column is not. */
        @Nullable CubeBuffer[] waterWalls = new CubeBuffer[4];
        /** Face and water bytes uploaded for this column, for the debug screen. */
        long bytes;
        double distance;
        int groups;
        int waterSides;
        /** How this frame draws it: whole, fading out at its level's edge, or fading in over its children (see {@link FarGpu#NO_FADE}). */
        int fade;
        /** Whether its children were drawn in its place last frame, and when it last began to fade (see {@link FarGpu#now}), or 0. */
        boolean split;
        int stamp;
        /** Whether it is fading back in over its children, the camera having moved off (see {@link FarRenderer#visit}). */
        boolean merging;

        Column(long key, int bottom, LevelChunkSection[] sections, char[] drops) {
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
            top = top(bottom, sections, level);
        }

        private static int top(int bottom, LevelChunkSection[] sections, int level) {
            int width = Far.width(level), voxel = Far.voxel(level);
            for (int si = sections.length - 1; si >= 0; si--) {
                LevelChunkSection section = sections[si];
                if (section == null || section.hasOnlyAir()) continue;
                for (int y = 15; y >= 0; y--)
                    for (int i = 0; i < 256; i++)
                        if (!section.getBlockState(i & 15, y, i >> 4).isAir()) return (bottom + si) * width + (y + 1) * voxel;
            }
            return Integer.MIN_VALUE;
        }

        int baseY() { return bottom * Far.width(level); }

        boolean hasWaterWalls() {
            for (CubeBuffer wall : waterWalls) if (wall != null) return true;
            return false;
        }

        /**
         * Keeps drawing the faces of the column this one replaces until its own are built, so a
         * column the server sends again (after an edit, or a repeated request) never leaves a gap
         * that a coarser level fills for a moment.
         */
        void takeOver(Column old, @Nullable FarGpu gpu) {
            drawnCubes = old.drawnCubes;
            meshedCubes = old.meshedCubes;
            faces = old.faces;
            water = old.water;
            waterWalls = old.waterWalls;
            bytes = old.bytes;
            old.bytes = 0;
            meshed = true;
            split = old.split;
            stamp = old.stamp;
            merging = old.merging;
            // Meshes of the old column still in flight no longer match.
            version = nextVersion();
            // The old faces use the old column's slot, or the one it kept from the column before it.
            oldSlot = old.oldSlot;
            if (old.bottom == bottom) slot = old.slot;
            else if (oldSlot < 0) oldSlot = old.slot;
            else if (old.slot >= 0 && gpu != null) gpu.freeSlot(old.slot);
        }

        /** The same-level column at a corner: north-west, north-east, south-west, south-east. */
        long diagonal(int corner) {
            return Far.key(level, Far.x(key) + ((corner & 1) == 0 ? -1 : 1), Far.z(key) + (corner < 2 ? -1 : 1));
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
            for (int side = 0; side < 4; side++) if (waterWalls[side] != null) { waterWalls[side].close(); waterWalls[side] = null; }
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

    static boolean commandCacheEnabled() { return CACHE_COMMANDS; }
    private long diagnosticReceivedColumns, diagnosticReceivedEstimatedBytes;
    record PerfState(int wanted,int settled,int wantedInView,int settledInView,int held,int visible,int requested,int meshing,long ownedBytes,int drawnRanges,int replacements,long receivedColumns,long receivedEstimatedBytes) {}
    PerfState perfState() { return new PerfState(wanted,settled,wantedInView,settledInView,columns.size(),visible.size(),requested.size(),meshing,vertexBytes(),drawnRanges,replaced,diagnosticReceivedColumns,diagnosticReceivedEstimatedBytes); }

    public int columnCount() { return columns.size(); }

    /** A held column's square of ground and the block Y just above its highest voxel. */
    public interface TopVisitor { void accept(int x0, int z0, int width, int top); }

    /** Visits every held column that holds anything, with how high its terrain reaches. */
    public void forEachTop(TopVisitor visitor) {
        for (Column column : columns.values())
            if (column.top != Integer.MIN_VALUE) visitor.accept(column.x0, column.z0, Far.width(column.level), column.top);
    }
    /** Ranges of faces the last draw of far terrain asked the GPU for (see {@link FarGpu#add}). */
    public int drawnRanges() { return drawnRanges; }

    private int drawnRanges;
    /** Maintained on the render thread so the debug overlay never walks every column. */
    private long vertexBytes;
    private static final boolean TRACK_BYTES = !"false".equals(System.getProperty("earthplatform.far.trackBytes"));

    /** GPU memory of every uploaded column's faces and water, in bytes. */
    public long vertexBytes() {
        if (TRACK_BYTES) return vertexBytes;
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
    /** Share of the columns wanted at their own level that are meshed, in percent: 100 once the view has fully refined. */
    public int settledPercent() { return wanted == 0 ? 0 : (int) (100L * settled / wanted); }
    /** The same for the columns in view only, the ones within 60 degrees of where the player looks. */
    public int settledInViewPercent() { return wantedInView == 0 ? 0 : (int) (100L * settledInView / wantedInView); }

    private boolean inView(long key, int bx, int bz) {
        int width = Far.width(Far.level(key));
        double cx = (Far.x(key) + 0.5) * width - bx, cz = (Far.z(key) + 0.5) * width - bz, length = Math.sqrt(cx * cx + cz * cz);
        return length < width || cx * lookX + cz * lookZ >= 0.5 * length;
    }
    /** Columns received again while already held, as when the server pushes an edit. */
    public int replacedCount() { return replaced; }

    // Columns ------------------------------------------------------------------------------------

    public void receive(CubePayloads.FarColumnData payload) {
        if (closed || !Far.valid(payload.key())) return;
        if(io.earthplatform.minecraft.diagnostics.BuildInfo.diagnostics()){diagnosticReceivedColumns++;diagnosticReceivedEstimatedBytes+=payload.size();}
        requested.remove(payload.key());
        LevelChunkSection[] sections = new LevelChunkSection[payload.sections().size()];
        for (int i = 0; i < sections.length; i++) {
            byte[] bytes = payload.sections().get(i);
            if (bytes.length > 0) sections[i] = CubeCodec.readSection(bytes, biomes);
        }
        Column column = new Column(payload.key(), payload.bottom(), sections, unsigned(payload.drops()));
        column.lastWanted = ticks;
        Column old = columns.put(column.key, column);
        columnsVersion++;
        if (old != null) {
            replaced++;
            dueColumns.remove(old);
            if (old.meshed) column.takeOver(old, gpu);
            else { column.drawnCubes = old.drawnCubes; free(old); }
        }
        schedule(column);
        int level = column.level, x = Far.x(column.key), z = Far.z(column.key);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            Column side = columns.get(Far.key(level, x + direction.getStepX(), z + direction.getStepZ()));
            if (side != null) schedule(side);
        }
        // Smooth ground meets its diagonal neighbours at its corners too.
        if (FarMesher.SMOOTH && level >= FarMesher.SMOOTH_FROM) {
            for (int corner = 0; corner < 4; corner++) {
                Column diagonal = columns.get(column.diagonal(corner));
                if (diagonal != null) schedule(diagonal);
            }
        }
    }

    /** Rebuilds every column, as when a shader pack comes or goes and changes the vertex format and block ids. */
    void remeshAll() {
        for (Column column : columns.values()) schedule(column);
    }

    /** Meshes a column a few ticks from now, so neighbours arriving together cost one mesh each. */
    private void schedule(Column column) {
        if (column.due < 0) due(column, ticks + SETTLE_TICKS);
    }

    /** Asks for missing columns, forgets old ones, refreshes masks and starts due meshes. */
    public void tick() {
        ticks++;
        var player = Minecraft.getInstance().player;
        if (closed || player == null) return;
        finest = finest(player.getY() - ground(player.getBlockX(), player.getBlockZ(), player.getY()),
                player.getDeltaMovement().horizontalDistance() * 20.0, finest);
        if (ticks % MASK_TICKS == 0) mask();
        for (Column column : drawn) if (column.maskStale && column.due >= 0) due(column, Math.min(column.due, ticks + SETTLE_TICKS));
        double yaw = Math.toRadians(player.getYRot());
        lookX = -Math.sin(yaw);
        lookZ = Math.cos(yaw);
        int bx = player.getBlockX(), bz = player.getBlockZ();
        submitDue(bx, bz);
        if (ticks % PLAN_TICKS == 0) {
            plan(bx, bz);
            evict();
        }
        request();
    }

    /** Lists the missing columns, the ones a player would see change most first, and forgets requests no longer wanted. */
    private void plan(int bx, int bz) {
        LongArrayList missing = new LongArrayList();
        LongOpenHashSet all = new LongOpenHashSet(requested.size() * 2 + 16);
        int[] counts = new int[4];
        Far.around(bx, bz, finest, key -> {
            all.add(key);
            Column column = columns.get(key);
            if (!Far.refined(Far.level(key), Far.x(key), Far.z(key), bx, bz, finest)) {
                boolean meshed = column != null && column.meshed;
                counts[0]++;
                if (meshed) counts[1]++;
                if (inView(key, bx, bz)) {
                    counts[2]++;
                    if (meshed) counts[3]++;
                }
            }
            if (column != null) column.lastWanted = ticks;
            else missing.add(key);
        });
        wanted = counts[0];
        planSize = all.size();
        settled = counts[1];
        wantedInView = counts[2];
        settledInView = counts[3];
        // A request the server dropped (its player had moved on) or one for a place left behind
        // frees its place for the columns wanted now; one still wanted is asked for again later.
        requested.long2IntEntrySet().removeIf(entry -> !all.contains(entry.getLongKey()) || ticks - entry.getIntValue() > RETRY_TICKS);
        Long2LongOpenHashMap order = new Long2LongOpenHashMap(missing.size());
        for (int i = 0; i < missing.size(); i++) order.put(missing.getLong(i), priority(missing.getLong(i), bx, bz));
        missing.unstableSort((a, b) -> Long.compare(order.get(a), order.get(b)));
        plan.clear();
        plan.addAll(missing);
        planned = 0;
    }

    /** Tops the columns asked of the server back up to {@link #IN_FLIGHT}, in the plan's order; the server makes them in the order asked. */
    private void request() {
        if (planned >= plan.size() || requested.size() >= IN_FLIGHT) return;
        LongArrayList keys = new LongArrayList();
        while (planned < plan.size() && requested.size() < IN_FLIGHT && keys.size() < CubePayloads.FarRequest.MAX_KEYS) {
            long key = plan.getLong(planned++);
            if (columns.containsKey(key) || requested.containsKey(key)) continue;
            requested.put(key, ticks);
            keys.add(key);
        }
        if (!keys.isEmpty()) PacketDistributor.sendToServer(new CubePayloads.FarRequest(keys.toLongArray()));
    }

    /**
     * Levels whose middles stand in while the finer levels inside them load. The two coarsest
     * levels' columns cost four to thirteen times as much to make as the rest (at Yosemite, 33
     * and 97 ms against 4 to 15), and their middles only ever stand in for the 23 to 94 km the
     * next finer square already covers, so making them first spent the first minute after
     * arriving on ground nobody sees.
     */
    private static final int STAND_IN_LEVELS = 6;
    /** How much later the two coarsest levels' own rings come, past the haze, where only mountains show. */
    private static final double LEVEL_7_LATER = 1.5, LEVEL_8_LATER = 2.0;

    /**
     * The order to ask for a column in: by how far it is from a position in its own widths, so the
     * columns right around the player come first instead of last, when coarse voxels stood in
     * for them close up after every move. Columns out of view, and the two coarsest levels, wait.
     */
    private long priority(long key, int bx, int bz) {
        int level = Far.level(key), width = Far.width(level);
        long x0 = (long) Far.x(key) * width, z0 = (long) Far.z(key) * width;
        long dx = Math.max(0, Math.max(x0 - bx, bx - (x0 + width))), dz = Math.max(0, Math.max(z0 - bz, bz - (z0 + width)));
        // Hundredths of a width, and finer levels first among equals. Columns a finer level draws
        // over only stand in while it loads, so once their finer columns are all there they come
        // after every drawn one. Until then they are the only thing that can fill the view: after
        // a long teleport nothing is held, and putting them last left sky between the finer
        // columns as they arrived. The outermost two rows always keep their place: the finer
        // level's outer edge under them comes in last of its ring, and without them a band of
        // sky showed there.
        // Columns out of view wait: up to ViewOrder.BEHIND times as far for one right behind.
        double behind = ViewOrder.later(x0 + width / 2.0 - bx, z0 + width / 2.0 - bz, lookX, lookZ, width);
        double later = level == 7 ? LEVEL_7_LATER : level == 8 ? LEVEL_8_LATER : 1.0;
        long order = (long) (Math.max(dx, dz) * 100 * behind * later) / width * 8 + level;
        int x = Far.x(key), z = Far.z(key);
        if (!Far.refined(level, x, z, bx, bz, finest)) return order;
        int rings = Math.max(Math.abs(x - Far.column(bx, level)), Math.abs(z - Far.column(bz, level)));
        if (rings >= Far.hole(level) - 1) return order;
        return level <= STAND_IN_LEVELS && !childrenReady(level, x, z, bx, bz) ? order : order + (1L << 32);
    }

    /**
     * The finest level worth asking for. A level's hole is no wider than the player's height above
     * the ground is worth nothing more, as it only covers the ground straight below. Speed counts
     * too: each level is a ring 47 columns across, so crossing one column width brings in 47 new
     * ones, and at the jet stream's 1,500 blocks a second level 4 alone needs about 480 columns a
     * second, more than the server keeps up with. Coming back down needs a fifth less height and
     * half the speed, so the levels do not flicker at the edge.
     */
    static int finest(double height, double blocksPerSecond, int current) {
        int finest = Far.MIN_LEVEL;
        int hole = Far.hole();
        while (finest < Far.MAX_LEVEL && hole * Far.width(finest) * (finest < current ? 0.8 : 1.0) <= height) finest++;
        double slack = current > finest ? 0.5 : 1.0;
        if (blocksPerSecond >= JET_BLOCKS_PER_SECOND * slack) finest = Math.max(finest, JET_LEVEL);
        else if (blocksPerSecond >= FAST_BLOCKS_PER_SECOND * slack) finest = Math.max(finest, 3);
        return finest;
    }

    /** The ground's height under a block position from the finest column held there, or {@code fallback} when none is. */
    private double ground(int bx, int bz, double fallback) {
        for (int level = Far.MIN_LEVEL; level <= Far.MAX_LEVEL; level++) {
            Column column = columns.get(Far.key(level, Far.column(bx, level), Far.column(bz, level)));
            if (column == null) continue;
            int x = (bx - column.x0) >> level, z = (bz - column.z0) >> level;
            for (int si = column.sections.length - 1; si >= 0; si--) {
                LevelChunkSection section = column.sections[si];
                if (section == null || section.hasOnlyAir()) continue;
                for (int y = 15; y >= 0; y--) {
                    if (!section.getBlockState(x, y, z).isAir()) return column.baseY() + (si * 16 + y + 1) * (double) Far.voxel(level);
                }
            }
            return column.baseY();
        }
        return fallback;
    }

    private void evict() {
        List<Column> old = new ArrayList<>();
        for (Column column : columns.values()) if (column.lastWanted != ticks) old.add(column);
        old.sort(Comparator.comparingInt(column -> column.lastWanted));
        // The plan alone can want more than MAX_COLUMNS (about 17,000 at the default detail), which
        // left no room for the columns just behind: every one went at once and came back by download.
        int excess = columns.size() - Math.max(MAX_COLUMNS, planSize + SPARE_COLUMNS);
        for (Column column : old) {
            if (excess <= 0 && ticks - column.lastWanted <= KEEP_TICKS) break;
            columns.remove(column.key);
            columnsVersion++;
            dueColumns.remove(column);
            free(column);
            excess--;
        }
    }

    /** Counts up whenever a column comes or goes; with {@link CubeRenderer#compiledVersion}, what {@link #mask} last saw. */
    private int columnsVersion, maskedColumns = -1, maskedCompiled = -1;

    /** How often the sea under the drawn cubes is looked over again, and what it was last time. */
    private static final int SUNK_TICKS = 20;
    private int sunkAt = Integer.MIN_VALUE / 2;
    private LongOpenHashSet sunk = new LongOpenHashSet();

    /**
     * The cubes of nothing but water from the lowest drawn in each column up: the sea goes on
     * below them, past the cubes loaded. Far terrain still draws inside them, so its seabed shows
     * through the water as it does farther out, where the deep sea was a pale grey-blue of sky
     * seen through water with nothing under it.
     */
    private LongOpenHashSet sunk(LongOpenHashSet compiled) {
        if (ticks - sunkAt < SUNK_TICKS) return sunk;
        sunkAt = ticks;
        LongOpenHashSet sunk = new LongOpenHashSet();
        for (LongIterator it = compiled.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            int x = CubeKey.x(key), z = CubeKey.z(key);
            if (compiled.contains(CubeKey.pack(x, CubeKey.y(key) - 1, z))) continue;
            // The lowest drawn cube of its column: climb while the cubes are all water.
            for (int y = CubeKey.y(key); ; y++) {
                long up = CubeKey.pack(x, y, z);
                if (!compiled.contains(up) || !allWater(world.cube(up))) break;
                sunk.add(up);
            }
        }
        this.sunk = sunk;
        return sunk;
    }

    private static boolean allWater(@Nullable io.earthplatform.minecraft.cubic.Cube cube) {
        if (cube == null) return false;
        // A palette keeps states no longer used, so count the blocks themselves.
        int[] water = new int[1];
        cube.section.getStates().count((state, count) -> { if (state.getBlock() == net.minecraft.world.level.block.Blocks.WATER) water[0] += count; });
        return water[0] == 4096;
    }

    /**
     * Hands each column the cubes the cube renderer already draws inside it (plus a voxel's margin),
     * and remeshes the columns whose set changed.
     */
    private void mask() {
        // Standing still nothing changes, and working the sets out again cost a third of the
        // render thread's own time every fourth tick.
        int compiledVersion = world.renderer().compiledVersion();
        if (compiledVersion == maskedCompiled && columnsVersion == maskedColumns) return;
        maskedCompiled = compiledVersion;
        maskedColumns = columnsVersion;
        Long2ObjectOpenHashMap<LongOpenHashSet> sets = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet compiled = world.renderer().compiledCubes(), sunk = sunk(compiled);
        for (LongIterator it = compiled.iterator(); it.hasNext(); ) {
            long cube = it.nextLong();
            if (sunk.contains(cube)) continue;
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
            // A cube gone from under a drawn column leaves a hole until it remeshes: that comes first.
            boolean lost = column.drawnCubes != null && (set == null || !set.containsAll(column.drawnCubes));
            column.drawnCubes = set;
            if (lost && drawnKeys.contains(column.key)) {
                due(column, ticks);
                continue;
            }
            // Coarse columns under the finer ones near the player see every cube that compiles there;
            // remeshing each at once kept the far meshers busy with columns nobody sees, so hidden
            // ones gather their changes into one remesh a while later.
            if (drawnKeys.contains(column.key) || !column.meshed) schedule(column);
            else {
                column.maskStale = true;
                if (column.due < 0) due(column, ticks + HIDDEN_SETTLE_TICKS);
            }
        }
    }

    /**
     * Starts due meshes, the drawn columns nearest the player first, keeping only a short queue at
     * the workers: with every due column queued at once, in no order, a backlog (after a teleport
     * or a shader reload) left coarse stand-ins near the player until it had all drained.
     */
    private void submitDue(int bx, int bz) {
        List<Column> due = new ArrayList<>(), building = new ArrayList<>();
        for (Column column : dueColumns) {
            if (column.due > ticks) continue;
            if (column.building) building.add(column);
            else due.add(column);
        }
        for (Column column : building) submit(column);
        if (due.isEmpty()) return;
        Map<Column, Long> order = new HashMap<>(due.size() * 2);
        for (Column column : due) order.put(column, (drawnKeys.contains(column.key) ? 0 : 1L << 40) + priority(column.key, bx, bz));
        due.sort(Comparator.comparingLong(order::get));
        for (Column column : due) {
            if (meshing >= MESHES_IN_FLIGHT) break;
            submit(column);
        }
    }

    /** Sets when a column is next meshed, keeping {@link #dueColumns} to the columns with a time. */
    private void due(Column column, int at) {
        column.due = at;
        dueColumns.add(column);
    }

    /** The columns waiting to be meshed: those with a {@code due} time, so a tick looks at them alone, not every column held. */
    private final java.util.Set<Column> dueColumns = new java.util.HashSet<>();

    private void submit(Column column) {
        column.due = -1;
        dueColumns.remove(column);
        column.maskStale = false;
        FarGpu gpu = gpu();
        if (gpu == null) return;
        if (column.building) { column.stale = true; return; }
        column.building = true;
        column.stale = false;
        column.buildingCubes = column.drawnCubes;
        if (column.slot < 0) column.slot = gpu.slot(column.x0, column.baseY(), column.z0, column.level);
        FarMesher.Side[] sides = new FarMesher.Side[4], diagonals = new FarMesher.Side[4];
        for (int i = 0; i < 4; i++) {
            Column side = columns.get(column.neighbour(i));
            if (side != null) sides[i] = new FarMesher.Side(side.sections, side.bottom, side.drops);
            Column diagonal = columns.get(column.diagonal(i));
            if (diagonal != null) diagonals[i] = new FarMesher.Side(diagonal.sections, diagonal.bottom, diagonal.drops);
        }
        var level = world.client();
        float[] shade = new float[6];
        for (Direction direction : Direction.values()) shade[direction.get3DDataValue()] = level.getShade(direction, true);
        FarMesher.Job job = new FarMesher.Job(column.key, column.version = nextVersion(), column.level, column.slot, column.x0, column.z0, column.bottom,
                column.sections, column.drops, sides, diagonals, column.drawnCubes, column.level <= TEXTURED_LEVELS, looks, faces,
                ShaderBlocks.ids(), shade, white());
        meshing++;
        MESHER.execute(() -> {
            if (built.isClosed()) return;
            long start = System.nanoTime();
            try {
                built.add(FarMesher.build(job));
                Timings.FAR_MESH.since(start);
            }
            catch (Throwable failure) {
                io.earthplatform.minecraft.diagnostics.PerfFailures.record(io.earthplatform.minecraft.diagnostics.PerfFailures.Stage.FAR_MESH,failure);
                EarthPlatformMod.LOGGER.error("Far column mesh failed", failure);
                built.add(new FarMesher.Built(job.key(), -job.version(), new int[0], new int[FarMesher.GROUPS], null, null, new FarMesher.Part[4]));
            }
        });
    }

    /** Uploads finished meshes: a few every frame, and more while a small part of the last frame's time lasts. */
    private void upload(FarGpu gpu) {
        long now = System.nanoTime(), deadline = now + Math.max(UPLOAD_NANOS, frameNanos / 8);
        UploadBudget budget = STRICT_UPLOAD ? new UploadBudget(now, UPLOAD_NANOS, UPLOAD_BYTES) : null;
        for (int n = 0; STRICT_UPLOAD || n < UPLOADS_PER_FRAME || System.nanoTime() < deadline; n++) {
            if (budget != null) {
                FarMesher.Built next = built.peek();
                if (next == null) return;
                Column pending = columns.get(next.key());
                if (!closed && pending != null && next.version() > 0 && next.version() == pending.version
                        && !budget.tryAcquire(System.nanoTime(), next.bytes())) return;
            }
            FarMesher.Built result = built.poll();
            if (result == null) return;
            meshing--;
            Column column = columns.get(result.key());
            if (closed || column == null || Math.abs(result.version()) != column.version) { result.free(); continue; }
            column.building = false;
            if (result.version() > 0) {
                pickVersion++;
                column.meshed = true;
                vertexBytes += result.bytes() - column.bytes;
                column.bytes = result.bytes();
                gpu.free(column.faces);
                column.faces = gpu.upload(result.quads(), result.counts(), result.grid());
                column.meshedCubes = column.buildingCubes;
                column.water = upload(column.water, result.water());
                for (int side = 0; side < 4; side++) column.waterWalls[side] = upload(column.waterWalls[side], result.waterWalls()[side]);
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
    @Nullable private static CubeBuffer upload(@Nullable CubeBuffer buffer, @Nullable FarMesher.Part part) {
        if (part == null) {
            if (buffer != null) buffer.close();
            return null;
        }
        if (buffer == null) buffer = new CubeBuffer();
        buffer.upload(part.mesh(), FarWaterBatch.ENABLED);
        part.memory().close();
        return buffer;
    }

    // Drawing ------------------------------------------------------------------------------------

    public void beginFrame() {
        FarGpuTimers.frame();
        frame++;
        long now = System.nanoTime();
        frameNanos = Math.min(now - frameStart, 1_000_000_000L);
        frameStart = now;
    }

    /** Draws the columns for the camera's squares, using a finer level only once all four of its columns are ready. */
    public void draw(Camera camera, Frustum frustum, Matrix4f modelView, Matrix4f projection) {
        // Under water, lava or powder snow the near fog hides everything far terrain would draw;
        // drawn anyway, the sea's surface far above showed through it from the deep sea floor.
        if (camera.getFluidInCamera() != net.minecraft.world.level.material.FogType.NONE) return;
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
        // Spatial dither still runs in the shaders every frame; only timed arrivals need a new pick.
        boolean band = FADE && ShaderBlocks.packInUse() && gpu.jittered();
        boolean changed = true;
        if (CACHE_PICK && frustum.getClass() == Frustum.class && frustum instanceof FrustumAccess view) {
            changed = pick.changed(position.x, position.y, position.z, camera.rotation(), view.earthplatform$matrix(),
                    view.earthplatform$camX(), view.earthplatform$camY(), view.earthplatform$camZ(),
                    columnsVersion, pickVersion, finest, Far.hole(), band);
        } else pick.invalidate();
        if (changed || pickAnimating) pickColumns(position, frustum, bx, bz, band);
        List<Column> visible = this.visible;
        if (visible.isEmpty()) return;
        boolean pack = ShaderBlocks.packInUse();
        // Under a pack with Voxy support far terrain keeps its own, finer depth (see FarLod).
        boolean own = pack && FarLod.active();
        Matrix4f farProjection = own ? FarLod.project(projection) : projection;
        RenderType type = RenderType.solid();
        type.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        // Applying the terrain shader binds a shader pack's terrain buffers, which the far terrain draws into too.
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, Minecraft.getInstance().getWindow());
        shader.apply();
        float[] shade = new float[6];
        for (Direction direction : Direction.values()) shade[direction.get3DDataValue()] = world.client().getShade(direction, true);
        if (own) {
            // Far terrain inside the game's blocks' reach goes in the game's own depth, as without a pack's Voxy support.
            float reach = FarLod.nearReach();
            if (!CACHE_COMMANDS || nearCommands == null || !nearCommands.matches(selectionVersion, reach)) {
                List<Column> near = new ArrayList<>();
                for (Column column : visible) {
                    double dx = Math.max(0.0, Math.max(column.box.minX - position.x, position.x - column.box.maxX));
                    double dz = Math.max(0.0, Math.max(column.box.minZ - position.z, position.z - column.box.maxZ));
                    if (dx * dx + dz * dz < reach * reach) near.add(column);
                }
                nearVisible = near;
            }
            if (!nearVisible.isEmpty() && gpu.begin(pack, ShaderBlocks.voxyPatch(), modelView, projection, position, shade, frame, true)) {
                long timer = FarGpuTimers.begin("near opaque");
                try { drawEach(gpu, nearVisible, position, true, reach); }
                finally { FarGpuTimers.end(timer, gpu.ranges, -1); }
            }
            FarLod.beginDraw(modelView, position.x, position.y, position.z);
        }
        if (gpu.begin(pack, pack ? ShaderBlocks.voxyPatch() : null, modelView, farProjection, position, shade, frame, false)) {
            long timer = FarGpuTimers.begin("far opaque");
            try { drawEach(gpu, visible, position, false, 0); }
            finally { FarGpuTimers.end(timer, gpu.ranges, -1); }
            drawnRanges = gpu.ranges;
        }
        if (own) FarLod.endDraw();
        boolean batchedDeepen = false;
        if (own && FarWaterBatch.ENABLED && FarLod.batchWaterDepthReady()) {
            waterDeepen.begin();
            for (Column column : FAST_WATER ? waterSurfaces : visible) {
                if (column.water == null || column.fade == FarGpu.FADE_IN) continue;
                waterDeepen.add(column.water, (float) (column.x0 - position.x), (float) (column.baseY() - position.y), (float) (column.z0 - position.z));
            }
            batchedDeepen = waterDeepen.end();
        }
        long deepenTimer = own ? FarGpuTimers.begin("water deepen") : 0;
        if (own && FarLod.beginDeepen(modelView, batchedDeepen)) {
            if (batchedDeepen) waterDeepen.draw();
            else for (Column column : FAST_WATER ? waterSurfaces : visible) {
                if (column.water == null || column.fade == FarGpu.FADE_IN) continue;
                FarLod.offset((float) (column.x0 - position.x), (float) (column.baseY() - position.y), (float) (column.z0 - position.z));
                column.water.draw();
            }
            CubeBuffer.unbind();
            FarLod.endDeepen();
        }
        FarGpuTimers.end(deepenTimer);
        shader.clear();
        type.clearRenderState();
    }

    /** Rebuilds selection only when its inputs change; timed fades keep visiting on every frame. */
    private void pickColumns(Vec3 position, Frustum frustum, int bx, int bz, boolean band) {
        selectionVersion++;
        drawn.clear();
        pickAnimating = false;
        // The dithered ring between levels only blends where a pack's TAA moves the dither every frame
        // and averages it; without one it stays put, and where the two levels' outlines differ, as at
        // cliff edges, it left fixed specks of sky. There levels meet at a plain seam.
        bandFade = band;
        int px = Far.column(bx, Far.MAX_LEVEL), pz = Far.column(bz, Far.MAX_LEVEL);
        boolean tree = CULL_TREE && frustum.getClass() == Frustum.class && frustum instanceof FrustumAccess;
        cullTree.clear(tree);
        for (int z = pz - Far.OUTER; z <= pz + Far.OUTER; z++) for (int x = px - Far.OUTER; x <= px + Far.OUTER; x++) {
            int parent = cullTree.open();
            visit(Far.MAX_LEVEL, x, z, bx, bz, false);
            cullTree.close(parent);
        }
        if (tree) {
            FrustumAccess view = (FrustumAccess) frustum;
            cullTree.classify(view.earthplatform$intersection(), view.earthplatform$camX(), view.earthplatform$camY(), view.earthplatform$camZ());
        }
        drawnKeys.clear();
        fadingIn.clear();
        for (Column column : drawn) {
            drawnKeys.add(column.key);
            if (column.fade == FarGpu.FADE_IN) fadingIn.add(column.key);
        }
        List<Column> visible = new ArrayList<>(drawn.size());
        for (int i = 0; i < drawn.size(); i++) {
            Column column = drawn.get(i);
            if (column.faces == null && column.water == null) continue;
            int seen = tree ? cullTree.state(i) : FarCullTree.PARTLY;
            if (seen == FarCullTree.OUTSIDE || seen == FarCullTree.PARTLY && !frustum.isVisible(column.box)) continue;
            // As AABB.getCenter() works it out, without making a Vec3 for each column.
            AABB box = column.box;
            double dx = box.minX + 0.5 * (box.maxX - box.minX) - position.x, dz = box.minZ + 0.5 * (box.maxZ - box.minZ) - position.z;
            column.distance = dx * dx + dz * dz;
            visible.add(column);
        }
        if (CACHE_PICK) {
            order.clear(visible.size());
            for (Column column : visible) order.add(column.distance);
            order.sort();
            List<Column> sorted = new ArrayList<>(visible.size());
            for (int i = 0; i < visible.size(); i++) sorted.add(visible.get(order.index(i)));
            visible = sorted;
        } else visible.sort(Comparator.comparingDouble(column -> column.distance));
        this.visible = visible;
        if (CACHE_PICK) for (Column column : visible) column.groups = faceGroups(column, position);
        if (FAST_WATER) pickWater();
    }

    /** Adds a column to this pick's drawn ones, in the view-culling node that is open (see {@link FarCullTree}). */
    private void addDrawn(Column column) {
        cullTree.own(drawn.size(), column.box);
        drawn.add(column);
    }

    /** Water uses the same sorted pick and neighbor coverage in every pass. */
    private void pickWater() {
        waterVisible.clear();
        waterSurfaces.clear();
        waterEdges.clear();
        for (Column column : visible) {
            column.waterSides = 0;
            if (column.fade == FarGpu.FADE_IN) continue;
            for (int side = 0; side < 4; side++)
                if (column.waterWalls[side] != null && !waterDrawn(column.neighbour(side))) column.waterSides |= 1 << side;
            if (column.water != null) waterSurfaces.add(column);
            if (column.waterSides != 0) waterEdges.add(column);
            if (column.water != null || column.waterSides != 0) waterVisible.add(column);
        }
    }

    /** Draws columns: whole ones first, then those fading out, then those fading in, each with its own program. */
    private void drawEach(FarGpu gpu, List<Column> columns, Vec3 position, boolean near, float reach) {
        if (CACHE_COMMANDS) {
            FarGpu.Commands commands = near ? nearCommands : fullCommands;
            if (commands == null) {
                commands = gpu.commands();
                if (near) nearCommands = commands; else fullCommands = commands;
            }
            if (!commands.matches(selectionVersion, reach)) {
                commands.begin();
                for (Column column : columns)
                    commands.add(column.fade, column.faces, CACHE_PICK ? column.groups : faceGroups(column, position));
                commands.finish(selectionVersion, reach);
            }
            commands.draw();
            return;
        }
        boolean[] fades = new boolean[3];
        for (Column column : columns) fades[column.fade] = true;
        for (int fade = FarGpu.NO_FADE; fade <= FarGpu.FADE_IN; fade++) {
            if (!fades[fade]) continue;
            if (fade != FarGpu.NO_FADE && !gpu.fade(fade) && fade == FarGpu.FADE_IN) break;
            for (Column column : columns) if (column.fade == fade) queue(gpu, column, position);
        }
        gpu.end();
    }

    /** Queues a column's faces that can face the camera, and its walls where its neighbours are not drawn. */
    private void queue(FarGpu gpu, Column column, Vec3 position) {
        if (column.faces != null) gpu.add(column.faces, CACHE_PICK ? column.groups : faceGroups(column, position));
    }

    /** Facing and neighbor coverage stay fixed for the lifetime of the exact camera pick. */
    private int faceGroups(Column column, Vec3 position) {
        FarGpu.Allocation faces = column.faces;
        if (faces == null) return 0;
        AABB box = column.box;
        // Only face groups turned toward the camera can show. Smooth ground's slopes, cliffs among them,
        // face sideways too, so it shows from below its column as well.
        int groups = 0;
        if (position.y < box.maxY) groups |= 1;
        if (position.y > box.minY || faces.smooth()) groups |= 1 << 1;
        if (position.z < box.maxZ) groups |= 1 << 2;
        if (position.z > box.minZ) groups |= 1 << 3;
        if (position.x < box.maxX) groups |= 1 << 4;
        if (position.x > box.minX) groups |= 1 << 5;
        for (int side = 0; side < 4; side++) {
            if (drawnKeys.contains(column.neighbour(side))) continue;
            boolean facing = switch (side) {
                case 0 -> position.z < box.minZ + 1;
                case 1 -> position.z > box.maxZ - 1;
                case 2 -> position.x < box.minX + 1;
                default -> position.x > box.maxX - 1;
            };
            if (facing) groups |= 1 << FarMesher.FIRST_WALL + side;
        }
        return groups;
    }

    /**
     * Drops as sent, read unsigned: 256-block voxels drop up to 65,535 256ths of a block, past a
     * short's range, and read signed the coarsest columns stood up to 128 blocks over the ground.
     */
    private static char[] unsigned(short[] drops) {
        char[] out = new char[drops.length];
        for (int i = 0; i < drops.length; i++) out[i] = (char) drops[i];
        return out;
    }

    /** Sets an Earth world's metres per block at the equator, from the server. */
    public void scale(double metresPerBlock) { this.metresPerBlock = metresPerBlock; }

    /**
     * Blocks per ground metre at a block Z, relative to the world's home latitude, where a block
     * is a metre: the map stretches toward the poles, so haze that is 22 km deep at home spans
     * more blocks farther north. 1 outside Earth worlds.
     */
    public float hazeScale(double z) {
        // A world's scale makes a block a metre at home: metres per block there is sec(home latitude).
        double scale = metresPerBlock;
        return scale > 0 ? (float) (Math.cosh(z * scale / MercatorGrid.RADIUS) / scale) : 1.0F;
    }

    /** Draws the visible columns' water see-through, farthest first, before the near cubes' own water. */
    /**
     * How much nearer far water is drawn per block away, as a share of its distance, once divided by
     * the near plane's distance: two steps of a 24-bit depth buffer, which grow with distance squared
     * and shrink as the near plane moves out. Vanilla's near plane is 0.05 blocks; VR mods bring it
     * in to 0.02 so hands and controllers aren't clipped, and the steps grow to match.
     */
    private static final double WATER_LIFT = 2.0 / (1 << 24);

    private static double square(double value) { return value * value; }

    /** A perspective projection's near plane distance, whichever mod set it; vanilla's 0.05 if it can't be read. */
    static double nearPlane(Matrix4f projection) {
        double near = projection.m32() / (projection.m22() - 1.0);
        return near > 1.0E-4 && near < 16.0 ? near : 0.05;
    }

    public void drawWater(Camera camera, Matrix4f modelView, Matrix4f projection) {
        if (closed || waterPass == LevelPasses.current() || camera.getFluidInCamera() != net.minecraft.world.level.material.FogType.NONE) return;
        waterPass = LevelPasses.current();
        boolean any = FAST_WATER && !waterVisible.isEmpty();
        if (!FAST_WATER)
            for (Column column : visible) if (column.fade != FarGpu.FADE_IN && (column.water != null || column.hasWaterWalls())) { any = true; break; }
        if (!any) return;
        long start = System.nanoTime();
        try { drawWaterColumns(camera, modelView, projection); }
        finally { Timings.FAR_WATER.since(start); }
    }

    private void drawWaterColumns(Camera camera, Matrix4f modelView, Matrix4f projection) {
        Vec3 position = camera.getPosition();
        RenderType type = RenderType.translucent();
        type.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, Minecraft.getInstance().getWindow());
        shader.apply();
        // With far terrain in its own depth, far hills hide the water behind them this way instead.
        boolean own = ShaderBlocks.packInUse() && FarLod.active() && FarLod.beginWater();
        long colourTimer = FarGpuTimers.begin("far water colour");
        Uniform offset = shader.CHUNK_OFFSET, view = own ? null : shader.MODEL_VIEW_MATRIX;
        Matrix4f nearer = new Matrix4f();
        double lift = WATER_LIFT / nearPlane(projection);
        float lastX = Float.NaN, lastY = Float.NaN, lastZ = Float.NaN, lastScale = Float.NaN;
        for (int pass = 0; pass < 2; pass++) {
            // Water walls go last: where the next level's water stands as high, its surface is
            // already in the depth buffer and hides them, so they only show across a step.
            List<Column> water = FAST_WATER ? (pass == 0 ? waterSurfaces : waterEdges) : visible;
            for (int i = water.size() - 1; i >= 0; i--) {
                Column column = water.get(i);
                // Water stays with the finer level where two levels fade into each other.
                if (!FAST_WATER && (column.fade == FarGpu.FADE_IN || (pass == 0 ? column.water == null : !column.hasWaterWalls()))) continue;
                if (offset != null) {
                    float x = (float) (column.x0 - position.x), y = (float) (column.baseY() - position.y), z = (float) (column.z0 - position.z);
                    if (!FAST_WATER || x != lastX || y != lastY || z != lastZ) {
                        offset.set(x, y, z);
                        offset.upload();
                        lastX = x; lastY = y; lastZ = z;
                    }
                }
                if (view != null) {
                    // In the shared depth buffer a step of depth spans hundreds of blocks kilometres
                    // off, and a lake's bed a few blocks under its water showed through it in dark
                    // stripes. Drawn shrunk toward the camera the water covers the same pixels, at a
                    // depth nearer by a couple of those steps.
                    double distance = Math.sqrt(column.distance + square(column.box.getCenter().y - position.y));
                    float scale = (float) Math.max(0.8, 1.0 - distance * lift);
                    if (!FAST_WATER || scale != lastScale) {
                        view.set(nearer.set(modelView).scale(scale));
                        view.upload();
                        lastScale = scale;
                    }
                }
                if (pass == 0) {
                    column.water.draw();
                    continue;
                }
                for (int side = 0; side < 4; side++) {
                    CubeBuffer wall = column.waterWalls[side];
                    if (FAST_WATER ? (column.waterSides & 1 << side) == 0 : wall == null || waterDrawn(column.neighbour(side))) continue;
                    wall.draw();
                }
            }
        }
        FarGpuTimers.end(colourTimer);
        if (offset != null) offset.set(0.0F, 0.0F, 0.0F);
        if (view != null) view.set(modelView);
        boolean batchedRestore = false;
        if (own && FarWaterBatch.ENABLED && FarLod.batchWaterDepthReady()) {
            waterRestore.begin();
            for (Column column : FAST_WATER ? waterVisible : visible) {
                if (!FAST_WATER && (column.fade == FarGpu.FADE_IN || column.water == null && !column.hasWaterWalls())) continue;
                float x = (float) (column.x0 - position.x), y = (float) (column.baseY() - position.y), z = (float) (column.z0 - position.z);
                if (column.water != null) waterRestore.add(column.water, x, y, z);
                for (int side = 0; side < 4; side++) {
                    CubeBuffer wall = column.waterWalls[side];
                    if (FAST_WATER ? (column.waterSides & 1 << side) == 0 : wall == null || waterDrawn(column.neighbour(side))) continue;
                    waterRestore.add(wall, x, y, z);
                }
            }
            batchedRestore = waterRestore.end();
        }
        final boolean drawBatch = batchedRestore;
        if (own) FarLod.endWater(modelView, drawBatch, () -> {
            if (drawBatch) waterRestore.draw();
            else for (Column column : FAST_WATER ? waterVisible : visible) {
                if (!FAST_WATER && (column.fade == FarGpu.FADE_IN || column.water == null && !column.hasWaterWalls())) continue;
                FarLod.offset((float) (column.x0 - position.x), (float) (column.baseY() - position.y), (float) (column.z0 - position.z));
                if (column.water != null) {
                    column.water.draw();
                }
                for (int side = 0; side < 4; side++) {
                    CubeBuffer wall = column.waterWalls[side];
                    if (FAST_WATER ? (column.waterSides & 1 << side) == 0 : wall == null || waterDrawn(column.neighbour(side))) continue;
                    wall.draw();
                }
            }
        });
        shader.clear();
        CubeBuffer.unbind();
        type.clearRenderState();
    }

    /**
     * Draws a column, or its four children where a finer level replaces it. A replaced column only
     * stands in while its children load if it has a mesh itself: it is asked for last, so after a
     * goto it usually has none, and waiting for every child down to the finest level left the
     * ground around the player empty until the whole tree under it had arrived.
     */
    private void visit(int level, int x, int z, int bx, int bz, boolean fading) {
        Column column = columns.get(Far.key(level, x, z));
        if (CACHE_PICK && FADE && column != null && age(column, FarGpu.now()) < FarShaders.FADE_TICKS) pickAnimating = true;
        boolean meshed = column != null && column.meshed;
        // Rising into the jet stream layer makes a coarser level the finest: until its columns
        // arrive, the finer ones already here keep standing in for them.
        boolean refined = Far.refined(level, x, z, bx, bz, finest) || !meshed && Far.refined(level, x, z, bx, bz);
        if (refined && (!meshed || childrenReady(level, x, z, bx, bz))) {
            // The outer ring of a level's middle: drawn under its children too, fading in as they fade out.
            // The shader puts the ring at the shared hole, so where the coarsest level keeps a narrower
            // middle (an extreme sharpness) the two levels meet at a plain seam instead.
            boolean band = bandFade && meshed && Far.refined(level, x, z, bx, bz, finest)
                    && Math.max(Math.abs(x - Far.column(bx, level)), Math.abs(z - Far.column(bz, level))) == Far.hole(level)
                    && Far.hole(level) == Far.hole();
            boolean arriving = false;
            if (FADE && meshed) {
                int now = FarGpu.now();
                if (column.merging) {
                    // The camera has come back mid-merge: the children turn round and fade back in from where they got to.
                    int start = now - Math.max(0, FarShaders.FADE_TICKS - age(column, now)) & 0x7FFFFFF;
                    column.merging = false;
                    column.split = true;
                    stamp(column, start == 0 ? 1 : start);
                    for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                        Column child = columns.get(Far.key(level - 1, 2 * x + dx, 2 * z + dz));
                        if (child != null && child.meshed) stamp(child, column.stamp);
                    }
                }
                if (!column.split) {
                    // Its children have just all arrived: they fade in over it rather than pop in, once
                    // its own fade in over its parent is done, so levels sharpen one after another.
                    if (age(column, now) < FarShaders.FADE_TICKS) {
                        column.fade = fading ? FarGpu.FADE_OUT : FarGpu.NO_FADE;
                        addDrawn(column);
                        return;
                    }
                    column.split = true;
                    stamp(column, now);
                    for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                        Column child = columns.get(Far.key(level - 1, 2 * x + dx, 2 * z + dz));
                        if (child != null && child.meshed) stamp(child, now);
                    }
                }
                arriving = age(column, now) < FarShaders.FADE_TICKS;
            } else if (column != null) column.split = true;
            if (band || arriving) {
                column.fade = FarGpu.FADE_IN;
                addDrawn(column);
            }
            int parent = cullTree.open();
            for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) visit(level - 1, 2 * x + dx, 2 * z + dz, bx, bz, band || arriving);
            cullTree.close(parent);
            return;
        }
        boolean wasSplit = column != null && column.split;
        if (column != null) column.split = false;
        if (meshed) {
            if (FADE && (wasSplit || column.merging) && merge(column, level, x, z, wasSplit)) return;
            column.fade = fading ? FarGpu.FADE_OUT : FarGpu.NO_FADE;
            addDrawn(column);
        }
    }

    /**
     * Fades a column back in over its children once the camera has moved off and it no longer
     * needs them, the way they faded in over it, instead of swapping them out at once: flying along,
     * a level's edge moves over the land behind as well as ahead. Returns false once the fade is done.
     */
    private boolean merge(Column column, int level, int x, int z, boolean starting) {
        int now = FarGpu.now();
        // With a child already gone, the column would fade in over a hole: it is drawn whole instead.
        boolean whole = true;
        for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
            Column child = columns.get(Far.key(level - 1, 2 * x + dx, 2 * z + dz));
            if (child == null || !child.meshed) whole = false;
        }
        if (starting && whole) {
            // Mid-way through fading in over it, the children go no further and turn back from there.
            int left = column.merging ? 0 : Math.max(0, FarShaders.FADE_TICKS - age(column, now));
            int start = now - left & 0x7FFFFFF;
            column.merging = true;
            stamp(column, start == 0 ? 1 : start, true);
            for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                Column child = columns.get(Far.key(level - 1, 2 * x + dx, 2 * z + dz));
                if (child != null && child.meshed) stamp(child, column.stamp, true);
            }
        }
        if (whole && column.merging && age(column, now) < FarShaders.FADE_TICKS) {
            column.fade = FarGpu.FADE_IN;
            addDrawn(column);
            for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                Column child = columns.get(Far.key(level - 1, 2 * x + dx, 2 * z + dz));
                child.fade = FarGpu.FADE_OUT;
                addDrawn(child);
            }
            return true;
        }
        if (!column.merging) return false;
        // Done: no stale merge left on any of them for the next fade to start from.
        int stamp = column.stamp;
        column.merging = false;
        stamp(column, 0, false);
        for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
            Column child = columns.get(Far.key(level - 1, 2 * x + dx, 2 * z + dz));
            if (child != null && child.stamp == stamp) stamp(child, 0, false);
        }
        return false;
    }

    /**
     * Whether far terrain drawn now still leaves a hole for a cube, so the cube renderer keeps
     * drawing a cube it has dropped there (see {@link CubeRenderer#drop}).
     */
    boolean holeFor(long cube) {
        int bx = CubeKey.x(cube) << 4, bz = CubeKey.z(cube) << 4;
        for (int level = Far.MIN_LEVEL; level <= Far.MAX_LEVEL; level++) {
            int v = Far.voxel(level);
            for (int x = Far.column(bx - v, level); x <= Far.column(bx + 15 + v, level); x++) {
                for (int z = Far.column(bz - v, level); z <= Far.column(bz + 15 + v, level); z++) {
                    long key = Far.key(level, x, z);
                    if (!drawnKeys.contains(key)) continue;
                    Column column = columns.get(key);
                    if (column != null && column.meshedCubes != null && column.meshedCubes.contains(cube)) return true;
                }
            }
        }
        return false;
    }

    /** Hundredths of a second since a column last began to fade, on the clock {@link FarGpu#now} keeps. */
    private static int age(Column column, int now) {
        return column.stamp == 0 ? Integer.MAX_VALUE : now - column.stamp & 0x7FFFFFF;
    }

    /** Starts a column's fade now, in the slots its faces are drawn with. */
    private void stamp(Column column, int now) { stamp(column, now, false); }

    /** Starts a column's fade, or a merge's (see {@link #merge}), at a time, or with 0 clears it. */
    private void stamp(Column column, int now, boolean merging) {
        pickVersion++;
        if (now != 0) pickAnimating = true;
        column.stamp = now;
        if (gpu == null) return;
        gpu.stamp(column.slot, now, merging);
        gpu.stamp(column.oldSlot, now, merging);
    }

    /** True when a column's water is drawn this frame: drawn, and not fading in over finer water. */
    private boolean waterDrawn(long key) { return drawnKeys.contains(key) && !fadingIn.contains(key); }

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

    /** Releases the bytes once; replacement columns transfer ownership without changing the total. */
    private void free(Column column) {
        vertexBytes -= column.bytes;
        column.bytes = 0;
        column.close(gpu);
    }

    void close() {
        FarGpuTimers.close();
        closed = true;
        built.close();
        for (Column column : columns.values()) free(column);
        columns.clear();
        pick.invalidate();
        drawn.clear();
        drawnKeys.clear();
        fadingIn.clear();
        visible = List.of();
        waterVisible.clear();
        waterSurfaces.clear();
        waterEdges.clear();
        nearVisible = List.of();
        waterDeepen.close();
        waterRestore.close();
        dueColumns.clear();
        if (gpu != null) { gpu.close(); gpu = null; }
    }
}
