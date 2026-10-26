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
import io.earthplatform.minecraft.diagnostics.BuildInfo;
import io.earthplatform.minecraft.diagnostics.PerfFailures;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
import net.minecraft.world.level.block.state.BlockState;
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
    /** Every column's water, in shared buffers (see {@link FarWaterBuffers}). */
    private final FarWaterBuffers waterBuffers = new FarWaterBuffers();
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
    /**
     * Each pick's number: a column drawn in it carries it (see {@link Column#drawnPick}), as a set
     * of drawn keys did, which every pick filled and every column's neighbours looked up.
     */
    private int pickSerial = 1;
    /**
     * Whether a pick finds a column's kids and sides through the links kept on it (see
     * {@link Column#kids}) rather than a hash lookup for each, which while the view moved was a
     * quarter of the render thread's time. Off with {@code -Dearthplatform.far.linkColumns=false},
     * for comparing; the pick is the same either way.
     */
    private static final boolean LINK_COLUMNS = !"false".equals(System.getProperty("earthplatform.far.linkColumns"));
    /** Off with {@code -Dearthplatform.far.cullTree=false}: every drawn column is tested against the view on its own. */
    private static final boolean CULL_TREE = !"false".equals(System.getProperty("earthplatform.far.cullTree"));
    private final FarCullTree cullTree = new FarCullTree();
    private static final boolean CACHE_PICK = !"false".equals(System.getProperty("earthplatform.far.cachePick"));
    private final FarPickState pick = new FarPickState();
    private final FarOrder order = new FarOrder();
    /**
     * Whether a view puts the columns in order starting from the last view's order (see {@link
     * FarOrder#sortFrom}); {@code -Dearthplatform.far.reuseOrder=false} sorts them afresh every
     * time, for comparing. The same order either way.
     */
    private static final boolean REUSE_ORDER = !"false".equals(System.getProperty("earthplatform.far.reuseOrder"));
    /** Uploads and fade stamps also invalidate a pick when no column has come or gone. */
    private int pickVersion;
    private boolean pickAnimating;
    /**
     * Whether a pick walks the pyramid again only when what the walk depends on has changed (see
     * {@link #walkDue}), not every frame the camera moves: flying, the walk was most of the render
     * thread's time, though what it found changed only every second or so. In between the camera
     * only looks again at the columns it found (see {@link #view}).
     * {@code -Dearthplatform.far.reuseWalk=false} walks whenever the view changes, as before;
     * the picture is the same either way.
     */
    private static final boolean REUSE_WALK = CACHE_PICK && !"false".equals(System.getProperty("earthplatform.far.reuseWalk"));
    /**
     * With {@code -Dearthplatform.far.checkWalk=true}, every frame that keeps the last walk walks
     * again anyway and logs where the two differ, which they never should.
     */
    private static final boolean CHECK_WALK = Boolean.getBoolean("earthplatform.far.checkWalk");
    /**
     * Whether a column meshed that the walk does not draw walks it again only when that changes what
     * it draws (see {@link #changesWalk}): a column meshed again, or the first of a parent's children
     * to come while the parent stands in for them, does not. Flying, most meshes were these, and
     * each walked the pyramid again. {@code -Dearthplatform.far.quietMeshes=false} walks again for
     * every one, as before; the picture is the same either way.
     */
    private static final boolean QUIET_MESHES = REUSE_WALK && !"false".equals(System.getProperty("earthplatform.far.quietMeshes"));
    /** What the last walk depended on (see {@link #walkDue}): the camera's column at {@link #WALK_LEVEL}, and the settings it saw. */
    private int walkX, walkZ, walkFinest, walkHole;
    /** The camera's block x and z the last walk saw, which the walk then saw everything from. */
    private int walkBx, walkBz;
    /**
     * The finest level whose column under the camera a walk asks for: only a level above the finest
     * one drawn, level 1 at the least, is ever split (see {@link Far#refined}).
     */
    private static final int WALK_LEVEL = Far.MIN_LEVEL + 1;
    private boolean walkBand, walked;
    /** The fade clock when the last walk ran, and how long after that the first fade it saw under way ends, or MAX_VALUE. */
    private int walkNow, walkLeft = Integer.MAX_VALUE;
    /**
     * Since the last walk: a column it draws was taken away or replaced, so it is walked again at
     * once; or a column was meshed that it does not draw yet, which it will walk for soon (see
     * {@link #WALK_EVERY}).
     */
    private boolean walkStale, walkBehind;
    /**
     * Hundredths of a second the walk waits after the last one for changes that only add to what it
     * draws: columns meshed, which then fade in that much later at most, and fades ending, whose
     * last step the shaders show anyway. Flying over new ground, meshes came up nearly every
     * frame, and so did the walk.
     */
    private static final int WALK_EVERY = 10;
    /** Counts up with every look at the walked columns from the camera (see {@link #view}), which the recorded passes are kept for. */
    private int viewSerial = 1;
    static final boolean FAST_WATER = !"false".equals(System.getProperty("earthplatform.far.fastWater"));
    /** Preserve water order while skipping dry columns and walls hidden by a drawn neighbor: places in {@link #shownDrawn}. */
    private final IntArrayList waterVisible = new IntArrayList(), waterSurfaces = new IntArrayList(), waterEdges = new IntArrayList();
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
        /** The section just above its box, so its box's top is this many widths up. */
        final int ceiling;
        @Nullable LongOpenHashSet drawnCubes;
        /** The cubes left out of the faces it draws now, and of the mesh being built (see {@link FarRenderer#holeFor}). */
        @Nullable LongOpenHashSet meshedCubes, buildingCubes;
        /**
         * The tick the plan last wanted it, while its place is crowded; otherwise the places keep it
         * (see {@link FarPlaces#lastWanted}), and this is only what they take in when it is placed.
         */
        int lastWanted;
        int version, due = -1, slot = -1;
        /** The slot of a replaced column's faces still drawn here, freed once this column's own faces are up. */
        int oldSlot = -1;
        boolean building, stale, meshed;
        /** Its mask changed while a finer level drew over it, so its remesh waits unless it is drawn. */
        boolean maskStale;
        /** Received since the last mask, which works its cubes out whole rather than from what changed (see {@link FarRenderer#maskChanges}). */
        boolean unmasked;
        @Nullable FarGpu.Allocation faces;
        @Nullable FarWaterBuffers.Mesh water;
        /** Water walls by side (north, south, west, east), drawn where that side's column is not. */
        @Nullable FarWaterBuffers.Mesh[] waterWalls = new FarWaterBuffers.Mesh[4];
        /** The sides it has water walls on, a bit each, which a view reads rather than reaching {@link #waterWalls}. */
        int wallSides;
        /** Face and water bytes uploaded for this column, for the debug screen. */
        long bytes;
        /** How this frame draws it: whole, fading out at its level's edge, or fading in over its children (see {@link FarGpu#NO_FADE}). */
        int fade;
        /** Whether its children were drawn in its place last frame, and when it last began to fade (see {@link FarGpu#now}), or 0. */
        boolean split;
        int stamp;
        /** Whether it is fading back in over its children, the camera having moved off (see {@link FarRenderer#visit}). */
        boolean merging;
        /** The last pick that drew it (see {@link FarRenderer#pickSerial}), and its place in {@link FarRenderer#drawn} then. */
        int drawnPick, drawnAt = -1;
        /**
         * The held columns a level finer under it (north-west, north-east, south-west, south-east)
         * and beside it (north, south, west, east), kept as columns come and go (see
         * {@link FarRenderer#link}), so a pick walks the pyramid without a hash lookup per step.
         */
        final Column[] kids = new Column[4], sides = new Column[4];
        /** Where its square and top are in {@link FarRenderer#tops}, or -1 when it holds only air. */
        int topIndex = -1;

        Column(long key, int bottom, LevelChunkSection[] sections, char[] drops) {
            this.key = key;
            this.level = Far.level(key);
            this.bottom = bottom;
            this.sections = sections;
            this.drops = drops;
            int width = Far.width(level);
            x0 = Far.x(key) * width; z0 = Far.z(key) * width;
            // Drops lower a column by up to a voxel.
            ceiling = bottom + Math.max(1, sections.length);
            box = new AABB(x0, (double) bottom * width - Far.voxel(level), z0, x0 + width, (double) ceiling * width, z0 + width);
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

        // Its box's bounds, worked out as the box has them from the column's own fields: the pick
        // reads them for every column it draws, and reaching the box, an object of its own, for them
        // cost a cache miss each.
        double minX() { return x0; }
        double maxX() { return x0 + Far.width(level); }
        double minY() { return (double) bottom * Far.width(level) - Far.voxel(level); }
        double maxY() { return (double) ceiling * Far.width(level); }
        double minZ() { return z0; }
        double maxZ() { return z0 + Far.width(level); }

        boolean hasWaterWalls() {
            return wallSides != 0;
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
            wallSides = old.wallSides;
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

        void close(@Nullable FarGpu gpu, FarWaterBuffers waterBuffers) {
            waterBuffers.free(water);
            water = null;
            for (int side = 0; side < 4; side++) { waterBuffers.free(waterWalls[side]); waterWalls[side] = null; }
            wallSides = 0;
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

    private long diagnosticReceivedColumns, diagnosticReceivedEstimatedBytes;
    record PerfState(int wanted,int settled,int wantedInView,int settledInView,int held,int visible,int requested,int meshing,long ownedBytes,int drawnRanges,int replacements,long receivedColumns,long receivedEstimatedBytes) {}
    PerfState perfState() { return new PerfState(wanted,settled,wantedInView,settledInView,columns.size(),shown,requested.size(),meshing,vertexBytes(),drawnRanges(),replaced,diagnosticReceivedColumns,diagnosticReceivedEstimatedBytes); }

    public int columnCount() { return columns.size(); }

    /** A held column's square of ground and the block Y just above its highest voxel. */
    public interface TopVisitor { void accept(int x0, int z0, int width, int top); }

    /** Visits every held column that holds anything, with how high its terrain reaches. */
    public void forEachTop(TopVisitor visitor) {
        int[] tops = this.tops;
        for (int at = 0, end = topCount * 4; at < end; at += 4) visitor.accept(tops[at], tops[at + 1], tops[at + 2], tops[at + 3]);
    }

    /** Counts up whenever a column {@link #forEachTop} visits comes or goes. */
    public int topsVersion() { return topsVersion; }

    /**
     * Every held column that holds anything, as its x0, z0, width and top, side by side: the cloud
     * deck looks them all over every frame (see EarthClouds), and going through the columns
     * themselves, scattered over the heap, took most of the render thread without a shader pack.
     */
    private int[] tops = new int[4 * 1024];
    private Column[] topColumns = new Column[1024];
    private int topCount, topsVersion;

    private void addTop(Column column) {
        if (column.top == Integer.MIN_VALUE) return;
        if (topCount == topColumns.length) {
            topColumns = java.util.Arrays.copyOf(topColumns, topCount * 2);
            tops = java.util.Arrays.copyOf(tops, topCount * 8);
        }
        int at = topCount * 4;
        tops[at] = column.x0;
        tops[at + 1] = column.z0;
        tops[at + 2] = Far.width(column.level);
        tops[at + 3] = column.top;
        topColumns[topCount] = column;
        column.topIndex = topCount++;
        topsVersion++;
    }

    private void removeTop(Column column) {
        int index = column.topIndex;
        if (index < 0) return;
        int last = --topCount;
        if (index != last) {
            System.arraycopy(tops, last * 4, tops, index * 4, 4);
            topColumns[index] = topColumns[last];
            topColumns[index].topIndex = index;
        }
        topColumns[last] = null;
        column.topIndex = -1;
        topsVersion++;
    }

    /** Held columns by place, for finding one, or that none is held, without a hash lookup (see {@link FarPlaces}). */
    private final FarPlaces<Column> places = new FarPlaces<>(column -> column.key, columns::get, column -> column.meshed,
            column -> column.lastWanted, (column, tick) -> column.lastWanted = tick);

    @Nullable private Column held(int level, int x, int z) { return places.get(level, x, z); }

    @Nullable private Column held(long key) { return places.get(key); }
    /** Ranges of faces the last draw of far terrain asked the GPU for (see {@link FarGpu#add}). */
    public int drawnRanges() { return drawCount.ranges(); }

    private final FarDrawCount drawCount = new FarDrawCount();
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
        if(BuildInfo.diagnostics()){diagnosticReceivedColumns++;diagnosticReceivedEstimatedBytes+=payload.size();}
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
        places.add(column);
        link(column);
        column.unmasked = true;
        unmasked.add(column);
        if (old != null) {
            replaced++;
            if (listed(old)) walkStale = true;
            column.drawnPick = old.drawnPick;
            dueColumns.remove(old);
            forget(old);
            if (old.meshed) {
                column.takeOver(old, gpu);
                places.meshed(column);
            }
            else { column.drawnCubes = old.drawnCubes; free(old); }
            if (column.drawnCubes != null) masked.add(column);
        }
        addTop(column);
        schedule(column);
        int level = column.level, x = Far.x(column.key), z = Far.z(column.key);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            Column side = held(level, x + direction.getStepX(), z + direction.getStepZ());
            if (side != null) schedule(side);
        }
        // Smooth ground meets its diagonal neighbours at its corners too.
        if (FarMesher.SMOOTH && level >= FarMesher.SMOOTH_FROM) {
            for (int corner = 0; corner < 4; corner++) {
                Column diagonal = held(column.diagonal(corner));
                if (diagonal != null) schedule(diagonal);
            }
        }
    }

    /** Lets go of the bookkeeping kept for a column on top of the map, as it leaves the map. */
    private void forget(Column column) {
        removeTop(column);
        staleMasks.remove(column);
        masked.remove(column);
    }

    /**
     * Takes a shader pack coming, going or remapping its blocks. Far faces only carry look ids, and
     * the looks take the new block ids in place, so only water, which is built in the pack's vertex
     * format, is meshed again. Rebuilding every column cost about a minute at a few frames a second.
     */
    void shadersChanged(@Nullable Object2IntMap<BlockState> ids) {
        looks.rebind(ids);
        for (Column column : columns.values()) {
            if (column.building || column.water != null || column.hasWaterWalls()) schedule(column);
        }
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
        // A rider's own velocity stays at nothing; its boat's is how fast it goes.
        finest = finest(player.getY() - ground(player.getBlockX(), player.getBlockZ(), player.getY()),
                player.getRootVehicle().getDeltaMovement().horizontalDistance() * 20.0, finest);
        if (ticks % MASK_TICKS == 0) mask();
        for (Column column : staleMasks) if (column.drawnPick == pickSerial && column.due >= 0) due(column, Math.min(column.due, ticks + SETTLE_TICKS));
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
        // Read once, so the plan and the requests it keeps agree on the columns wanted.
        int hole = Far.hole(), finest = this.finest;
        int[] counts = new int[5];
        Far.around(bx, bz, finest, hole, key -> {
            counts[4]++;
            int level = Far.level(key), x = Far.x(key), z = Far.z(key);
            // Noted and answered by the places alone, never reaching the columns themselves.
            boolean held = places.want(level, x, z, ticks);
            if (!Far.refined(level, x, z, bx, bz, finest)) {
                boolean meshed = held && places.meshed(level, x, z);
                counts[0]++;
                if (meshed) counts[1]++;
                if (inView(key, bx, bz)) {
                    counts[2]++;
                    if (meshed) counts[3]++;
                }
            }
            if (!held) missing.add(key);
        });
        wanted = counts[0];
        planSize = counts[4];
        settled = counts[1];
        wantedInView = counts[2];
        settledInView = counts[3];
        // A request the server dropped (its player had moved on) or one for a place left behind
        // frees its place for the columns wanted now; one still wanted is asked for again later.
        requested.long2IntEntrySet().removeIf(entry -> !Far.kept(entry.getLongKey(), bx, bz, finest, hole) || ticks - entry.getIntValue() > RETRY_TICKS);
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
            if (held(key) != null || requested.containsKey(key)) continue;
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
            Column column = held(level, Far.column(bx, level), Far.column(bz, level));
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

    /**
     * Whether the far terrain has open sea at a block column: the top of the finest column held there is water whose
     * top voxel holds the sea's top block Y {@code surface}, not land, ice or a lake above the sea. 1 for sea, 0 for
     * anything else, -1 while no column is held there.
     */
    public int sea(int bx, int bz, int surface) {
        for (int level = Far.MIN_LEVEL; level <= Far.MAX_LEVEL; level++) {
            Column column = held(level, Far.column(bx, level), Far.column(bz, level));
            if (column == null) continue;
            int x = (bx - column.x0) >> level, z = (bz - column.z0) >> level, voxel = Far.voxel(level);
            for (int si = column.sections.length - 1; si >= 0; si--) {
                LevelChunkSection section = column.sections[si];
                if (section == null || section.hasOnlyAir()) continue;
                for (int y = 15; y >= 0; y--) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state.isAir()) continue;
                    int bottom = column.baseY() + (si * 16 + y) * voxel;
                    return state.getBlock() == net.minecraft.world.level.block.Blocks.WATER && surface >= bottom && surface < bottom + voxel ? 1 : 0;
                }
            }
            return 0;
        }
        return -1;
    }

    private void evict() {
        // The plan alone can want more than MAX_COLUMNS (about 17,000 at the default detail), which
        // left no room for the columns just behind: every one went at once and came back by download.
        // A replay cannot ask for a column again, so there it keeps several times as many, and none for age alone.
        boolean replay = CubicClient.inReplay();
        int excess = columns.size() - Math.max(MAX_COLUMNS, planSize + SPARE_COLUMNS) * (replay ? 4 : 1);
        // With room to spare and no column left unwanted long enough to go, none would: going over
        // every column for the unwanted ones, each reached on the heap, twice a second even standing still.
        if (excess <= 0 && (replay || ticks - oldestUnwanted <= KEEP_TICKS)) return;
        List<Column> old = new ArrayList<>();
        for (Column column : columns.values()) if (places.lastWanted(column) != ticks) old.add(column);
        old.sort(Comparator.comparingInt(places::lastWanted));
        int gone = 0;
        for (Column column : old) {
            if (excess <= 0 && (replay || ticks - places.lastWanted(column) <= KEEP_TICKS)) break;
            columns.remove(column.key);
            places.remove(column);
            unlink(column);
            columnsVersion++;
            if (listed(column)) walkStale = true;
            dueColumns.remove(column);
            forget(column);
            free(column);
            excess--;
            gone++;
        }
        // Columns stop being wanted only from now on, so none of them will have been wanted last before this.
        oldestUnwanted = gone < old.size() ? places.lastWanted(old.get(gone)) : ticks;
        // Places a column left crowded may be free again: look them up directly once more.
        if (places.crowdedLeft()) places.placeAll(columns.values());
    }

    /**
     * No column held but no longer wanted was last wanted before this tick (see {@link #evict}): the
     * oldest left by the last look for them, or that look's tick if none was left.
     */
    private int oldestUnwanted;

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
        LongOpenHashSet compiled = world.renderer().compiledCubes(), sunk = sunk(compiled);
        if (MASK_CHANGES && handedOut != null) maskChanges(compiled, sunk);
        else maskWhole(compiled, sunk);
        for (Column column : unmasked) column.unmasked = false;
        unmasked.clear();
        if (CHECK_MASK) checkMask(compiled, sunk);
    }

    /** Every column's cubes worked out afresh (see {@link #mask}). */
    private void maskWhole(LongOpenHashSet compiled, LongOpenHashSet sunk) {
        Long2ObjectOpenHashMap<LongOpenHashSet> sets = cubesByColumn(compiled, sunk);
        // Only the columns with drawn cubes in them, now or before, can change: going over every
        // column held for them took as long as working out the sets.
        List<Column> touched = new ArrayList<>(masked);
        for (LongIterator it = sets.keySet().iterator(); it.hasNext(); ) {
            Column column = held(it.nextLong());
            if (column != null && column.drawnCubes == null) touched.add(column);
        }
        for (Column column : touched) {
            LongOpenHashSet set = sets.get(column.key);
            if (Objects.equals(set, column.drawnCubes)) continue;
            // A cube gone from under a drawn column leaves a hole until it remeshes: that comes first.
            hand(column, set, column.drawnCubes != null && (set == null || !set.containsAll(column.drawnCubes)));
        }
        if (!MASK_CHANGES) return;
        // What was handed out, for the next mask to start from.
        handedOut = new LongOpenHashSet(compiled.size());
        for (LongIterator it = compiled.iterator(); it.hasNext(); ) {
            long cube = it.nextLong();
            if (!sunk.contains(cube)) handedOut.add(cube);
        }
    }

    /** The drawn cubes not sunk, by the held columns they are inside (plus a voxel's margin). */
    private Long2ObjectOpenHashMap<LongOpenHashSet> cubesByColumn(LongOpenHashSet compiled, LongOpenHashSet sunk) {
        Long2ObjectOpenHashMap<LongOpenHashSet> sets = new Long2ObjectOpenHashMap<>();
        for (LongIterator it = compiled.iterator(); it.hasNext(); ) {
            long cube = it.nextLong();
            if (sunk.contains(cube)) continue;
            int bx = CubeKey.x(cube) << 4, bz = CubeKey.z(cube) << 4;
            for (int level = Far.MIN_LEVEL; level <= Far.MAX_LEVEL; level++) {
                int v = Far.voxel(level);
                for (int x = Far.column(bx - v, level); x <= Far.column(bx + 15 + v, level); x++) {
                    for (int z = Far.column(bz - v, level); z <= Far.column(bz + 15 + v, level); z++) {
                        if (places.has(level, x, z)) sets.computeIfAbsent(Far.key(level, x, z), unused -> new LongOpenHashSet()).add(cube);
                    }
                }
            }
        }
        return sets;
    }

    /**
     * The same as {@link #maskWhole}, from what changed since the last mask: the cubes drawn, gone
     * or sunk since change the columns they are inside, and the columns received since have their
     * cubes worked out whole. Every other column's cubes are as that mask handed them out. Flying,
     * cubes come and go only at the edges of what is loaded, but working every column's cubes out
     * afresh, the coarse ones over all of them included, took a sixth of the render thread's time.
     */
    private void maskChanges(LongOpenHashSet compiled, LongOpenHashSet sunk) {
        LongOpenHashSet earlier = handedOut;
        LongArrayList gone = new LongArrayList(), come = new LongArrayList();
        for (LongIterator it = earlier.iterator(); it.hasNext(); ) {
            long cube = it.nextLong();
            if (!compiled.contains(cube) || sunk.contains(cube)) gone.add(cube);
        }
        for (LongIterator it = compiled.iterator(); it.hasNext(); ) {
            long cube = it.nextLong();
            if (!sunk.contains(cube) && !earlier.contains(cube)) come.add(cube);
        }
        for (int i = 0; i < gone.size(); i++) earlier.remove(gone.getLong(i));
        for (int i = 0; i < come.size(); i++) earlier.add(come.getLong(i));
        Long2ObjectOpenHashMap<LongArrayList> added = new Long2ObjectOpenHashMap<>(), removed = new Long2ObjectOpenHashMap<>();
        for (int i = 0; i < come.size(); i++) byColumn(come.getLong(i), added);
        for (int i = 0; i < gone.size(); i++) byColumn(gone.getLong(i), removed);
        LongOpenHashSet changed = new LongOpenHashSet(added.keySet());
        changed.addAll(removed.keySet());
        for (LongIterator it = changed.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            Column column = held(key);
            if (column == null || column.unmasked) continue;
            // A new set rather than the old one changed: a mesh under way may be reading that.
            LongOpenHashSet set = column.drawnCubes == null ? new LongOpenHashSet() : column.drawnCubes.clone();
            LongArrayList in = added.get(key), out = removed.get(key);
            if (in != null) for (int i = 0; i < in.size(); i++) set.add(in.getLong(i));
            if (out != null) for (int i = 0; i < out.size(); i++) set.remove(out.getLong(i));
            hand(column, set.isEmpty() ? null : set, out != null);
        }
        if (unmasked.isEmpty()) return;
        // The columns received since: only those over the cubes' ground can have any.
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (LongIterator it = earlier.iterator(); it.hasNext(); ) {
            long cube = it.nextLong();
            int bx = CubeKey.x(cube) << 4, bz = CubeKey.z(cube) << 4;
            minX = Math.min(minX, bx); maxX = Math.max(maxX, bx);
            minZ = Math.min(minZ, bz); maxZ = Math.max(maxZ, bz);
        }
        for (Column column : unmasked) {
            if (columns.get(column.key) != column) continue;
            LongOpenHashSet set = null;
            int level = column.level, x = Far.x(column.key), z = Far.z(column.key), width = Far.width(level), v = Far.voxel(level);
            // A cube at block x bx is inside it when its x is between column(bx - v) and column(bx + 15 + v), as in cubesByColumn.
            if ((long) x * width - 15 - v <= maxX && (long) (x + 1) * width + v - 1 >= minX && (long) z * width - 15 - v <= maxZ && (long) (z + 1) * width + v - 1 >= minZ) {
                for (LongIterator it = earlier.iterator(); it.hasNext(); ) {
                    long cube = it.nextLong();
                    int bx = CubeKey.x(cube) << 4, bz = CubeKey.z(cube) << 4;
                    if (x < Far.column(bx - v, level) || x > Far.column(bx + 15 + v, level) || z < Far.column(bz - v, level) || z > Far.column(bz + 15 + v, level)) continue;
                    if (set == null) set = new LongOpenHashSet();
                    set.add(cube);
                }
            }
            if (Objects.equals(set, column.drawnCubes)) continue;
            hand(column, set, column.drawnCubes != null && (set == null || !set.containsAll(column.drawnCubes)));
        }
    }

    /** Notes a cube under every held column it is inside, as {@link #cubesByColumn} goes over them. */
    private void byColumn(long cube, Long2ObjectOpenHashMap<LongArrayList> lists) {
        int bx = CubeKey.x(cube) << 4, bz = CubeKey.z(cube) << 4;
        for (int level = Far.MIN_LEVEL; level <= Far.MAX_LEVEL; level++) {
            int v = Far.voxel(level);
            for (int x = Far.column(bx - v, level); x <= Far.column(bx + 15 + v, level); x++) {
                for (int z = Far.column(bz - v, level); z <= Far.column(bz + 15 + v, level); z++) {
                    if (places.has(level, x, z)) lists.computeIfAbsent(Far.key(level, x, z), unused -> new LongArrayList()).add(cube);
                }
            }
        }
    }

    /** Gives a column its new cubes, remeshing it as soon as it needs; {@code lost} when some it had are gone. */
    private void hand(Column column, @Nullable LongOpenHashSet set, boolean lost) {
        column.drawnCubes = set;
        if (set != null) masked.add(column);
        else masked.remove(column);
        if (lost && column.drawnPick == pickSerial) {
            due(column, ticks);
            return;
        }
        // Coarse columns under the finer ones near the player see every cube that compiles there;
        // remeshing each at once kept the far meshers busy with columns nobody sees, so hidden
        // ones gather their changes into one remesh a while later.
        if (column.drawnPick == pickSerial || !column.meshed) schedule(column);
        else {
            column.maskStale = true;
            staleMasks.add(column);
            if (column.due < 0) due(column, ticks + HIDDEN_SETTLE_TICKS);
        }
    }

    /** For {@link #CHECK_MASK}: works every column's cubes out afresh and logs where they differ from what a mask handed out. */
    private void checkMask(LongOpenHashSet compiled, LongOpenHashSet sunk) {
        Long2ObjectOpenHashMap<LongOpenHashSet> sets = cubesByColumn(compiled, sunk);
        int wrong = 0;
        StringBuilder differ = new StringBuilder();
        for (Column column : columns.values()) {
            LongOpenHashSet set = sets.get(column.key);
            if (Objects.equals(set, column.drawnCubes)) continue;
            if (wrong++ < 6) differ.append(" [L").append(column.level).append(' ').append(Far.x(column.key)).append(',').append(Far.z(column.key))
                    .append(" has ").append(column.drawnCubes == null ? 0 : column.drawnCubes.size()).append(" of ").append(set == null ? 0 : set.size()).append(']');
        }
        if (wrong > 0) EarthPlatformMod.LOGGER.warn("Far mask check: {} columns differ from a whole mask (after {} masks that matched):{}", wrong, masksMatched, differ);
        else if (++masksMatched % 100 == 1) EarthPlatformMod.LOGGER.info("Far mask check: {} masks matched", masksMatched);
    }

    private int masksMatched;

    /**
     * Starts due meshes, the drawn columns nearest the player first, keeping only a short queue at
     * the workers: with every due column queued at once, in no order, a backlog (after a teleport
     * or a shader reload) left coarse stand-ins near the player until it had all drained.
     */
    private void submitDue(int bx, int bz) { submitDue(bx, bz, false); }

    /** Starts the due meshes, or with {@code now} every waiting one, without the few ticks' wait for neighbours. */
    private void submitDue(int bx, int bz, boolean now) {
        List<Column> due = new ArrayList<>(), building = new ArrayList<>();
        for (Column column : dueColumns) {
            if (!now && column.due > ticks) continue;
            if (column.building) building.add(column);
            else due.add(column);
        }
        for (Column column : building) submit(column);
        // Only the first few can start. Ordering every waiting column each tick, as after a long
        // teleport with tens of thousands of them, took the render thread longer than the meshing.
        int free = MESHES_IN_FLIGHT - meshing;
        if (due.isEmpty() || free <= 0) return;
        int count = due.size();
        long[] order = new long[count];
        for (int i = 0; i < count; i++) {
            Column column = due.get(i);
            order[i] = (column.drawnPick == pickSerial ? 0 : 1L << 40) + priority(column.key, bx, bz);
        }
        for (int i : FarOrder.first(order, free)) submit(due.get(i));
    }

    /** Sets when a column is next meshed, keeping {@link #dueColumns} to the columns with a time. */
    private void due(Column column, int at) {
        column.due = at;
        dueColumns.add(column);
    }

    /** The columns waiting to be meshed: those with a {@code due} time, so a tick looks at them alone, not every column held. */
    private final java.util.Set<Column> dueColumns = new java.util.HashSet<>();
    /**
     * The columns whose remesh waits while they are hidden (see {@link Column#maskStale}), so a tick
     * looks at them alone for any drawn again; looking at every drawn column for them, tens of
     * thousands spread over the heap, took a few milliseconds a tick.
     */
    private final java.util.Set<Column> staleMasks = new java.util.HashSet<>();
    /** The columns with cubes drawn in them (see {@link Column#drawnCubes}), the only ones {@link #mask} can change. */
    private final java.util.Set<Column> masked = new java.util.HashSet<>();
    /** The columns received since the last mask (see {@link Column#unmasked}). */
    private final List<Column> unmasked = new ArrayList<>();
    /** The cubes the last mask handed out, drawn and not sunk, for the next one to start from (see {@link #maskChanges}); null before the first. */
    @Nullable private LongOpenHashSet handedOut;
    /**
     * Whether a mask works from what changed since the last one (see {@link #maskChanges});
     * {@code -Dearthplatform.far.maskChanges=false} works every column's cubes out afresh each time,
     * for comparing. The same cubes for every column either way.
     */
    private static final boolean MASK_CHANGES = !"false".equals(System.getProperty("earthplatform.far.maskChanges"));
    /** With {@code -Dearthplatform.far.checkMask=true}, every mask also works every column's cubes out afresh and logs any that differ, which none should. */
    private static final boolean CHECK_MASK = Boolean.getBoolean("earthplatform.far.checkMask");

    private void submit(Column column) {
        column.due = -1;
        dueColumns.remove(column);
        column.maskStale = false;
        staleMasks.remove(column);
        FarGpu gpu = gpu();
        if (gpu == null) return;
        if (column.building) { column.stale = true; return; }
        column.building = true;
        column.stale = false;
        column.buildingCubes = column.drawnCubes;
        if (column.slot < 0) column.slot = gpu.slot(column.x0, column.baseY(), column.z0, column.level);
        FarMesher.Side[] sides = new FarMesher.Side[4], diagonals = new FarMesher.Side[4];
        for (int i = 0; i < 4; i++) {
            Column side = held(column.neighbour(i));
            if (side != null) sides[i] = new FarMesher.Side(side.sections, side.bottom, side.drops);
            Column diagonal = held(column.diagonal(i));
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
                PerfFailures.record(PerfFailures.Stage.FAR_MESH,failure);
                EarthPlatformMod.LOGGER.error("Far column mesh failed", failure);
                built.add(new FarMesher.Built(job.key(), -job.version(), new int[0], new int[FarMesher.GROUPS], null, null, new FarMesher.Part[4]));
            }
        });
    }

    /** Uploads finished meshes: a few every frame, and more while a small part of the last frame's time lasts. */
    private void upload(FarGpu gpu) { upload(gpu, false); }

    private void upload(FarGpu gpu, boolean all) {
        long deadline = System.nanoTime() + Math.max(UPLOAD_NANOS, frameNanos / 8);
        for (int n = 0; all || n < UPLOADS_PER_FRAME || System.nanoTime() < deadline; n++) {
            FarMesher.Built result = built.poll();
            if (result == null) return;
            meshing--;
            Column column = held(result.key());
            if (closed || column == null || Math.abs(result.version()) != column.version) { result.free(); continue; }
            column.building = false;
            if (result.version() > 0) {
                pickVersion++;
                // A column the walk draws is drawn as before, from its new mesh; one it does not, it may draw now.
                boolean listed = listed(column), first = !column.meshed;
                // Which way the walk goes toward it is worked out before it counts as meshed, and again after.
                boolean ask = QUIET_MESHES && walked && !listed && !walkBehind && first;
                int before = ask ? walkPath(column) : 0;
                column.meshed = true;
                places.meshed(column);
                // Meshed again it changes nothing the walk sees: whether it is meshed is all the walk reads of its mesh.
                if (!listed && !walkBehind && (ask ? changesWalk(column, before) : !QUIET_MESHES || !walked)) walkBehind = true;
                vertexBytes += result.bytes() - column.bytes;
                column.bytes = result.bytes();
                gpu.free(column.faces);
                column.faces = gpu.upload(result.quads(), result.counts(), result.grid());
                column.meshedCubes = column.buildingCubes;
                column.water = waterBuffers.upload(column.water, result.water());
                column.wallSides = 0;
                for (int side = 0; side < 4; side++) {
                    column.waterWalls[side] = waterBuffers.upload(column.waterWalls[side], result.waterWalls()[side]);
                    if (column.waterWalls[side] != null) column.wallSides |= 1 << side;
                }
                column.freeOldSlots(gpu);
                if (listed) {
                    // What the view noted of its faces and water is worked out again, and the view looked at again.
                    drawnInfo[column.drawnAt] = -1;
                    drawnAny[column.drawnAt] = column.faces != null || column.water != null;
                    pick.invalidate();
                }
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

    // Drawing ------------------------------------------------------------------------------------

    /** For a flawless frame: starts every waiting mesh and uploads every finished one; returns how many are left. */
    int settle(int bx, int bz) {
        FarGpu gpu = closed ? null : gpu();
        if (gpu == null) return 0;
        submitDue(bx, bz, true);
        upload(gpu, true);
        return meshing + dueColumns.size();
    }

    public void beginFrame() {
        drawCount.begin();
        FarGpuTimers.frame();
        frame++;
        long now = System.nanoTime();
        frameNanos = Math.min(now - frameStart, 1_000_000_000L);
        frameStart = now;
    }

    /** Draws the columns for the camera's squares, using a finer level only once all four of its columns are ready. */
    public void draw(Camera camera, Frustum frustum, Matrix4f modelView, Matrix4f projection) {
        // Invalidate before fluid, closed/GPU-null, empty-view or failed-submit returns.
        drawCount.begin();
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
        FrustumAccess plain = frustum.getClass() == Frustum.class && frustum instanceof FrustumAccess view ? view : null;
        // The fade clock, read once for the frame: a walk is due, and is walked, by the same time.
        int now = FarGpu.now();
        if (REUSE_WALK) {
            boolean due = walkDue(bx, bz, band, now);
            if (due) walk(gpu, bx, bz, band, now);
            // A camera moving or turning within the walk's ground only sees the same columns from elsewhere.
            if (plain == null) pick.invalidate();
            if (plain == null || pick.changed(position.x, position.y, position.z, camera.rotation(), plain.earthplatform$matrix(),
                    plain.earthplatform$camX(), plain.earthplatform$camY(), plain.earthplatform$camZ(), pickSerial, 0, 0, 0, false)
                    || CHECK_WALK) {
                view(position, frustum);
            }
            // Only a walk with nothing waiting draws all a fresh one would.
            if (CHECK_WALK && !due && !walkBehind(now)) checkWalk(gpu, position, frustum, bx, bz, band, now);
        } else {
            boolean changed = true;
            if (CACHE_PICK && plain != null) {
                changed = pick.changed(position.x, position.y, position.z, camera.rotation(), plain.earthplatform$matrix(),
                        plain.earthplatform$camX(), plain.earthplatform$camY(), plain.earthplatform$camZ(),
                        columnsVersion, pickVersion, finest, Far.hole(), band);
            } else pick.invalidate();
            if (changed || pickAnimating) {
                walk(gpu, bx, bz, band, now);
                view(position, frustum);
            }
        }
        if (shown == 0) return;
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
            long setting = Float.floatToIntBits(reach) & 0xFFFFFFFFL;
            boolean again = REUSE_DRAWS && nearDraws.holds(viewSerial, setting);
            IntArrayList near = again ? NONE : nearReach(position, reach);
            // A pass drawn again was begun for columns it found, as finding them again would.
            if ((again || !near.isEmpty()) && gpu.begin(pack, ShaderBlocks.voxyPatch(), modelView, projection, position, shade, frame, true)) {
                long timer = FarGpuTimers.begin("near opaque");
                try { drawEach(gpu, nearDraws, again, near, position, setting); }
                finally { FarGpuTimers.end(timer, gpu.ranges, -1); }
            }
            FarLod.beginDraw(modelView, position.x, position.y, position.z);
        }
        if (own && gpu.depthPrepass && FarLod.prepassReady() && gpu.prepassReady(ShaderBlocks.voxyPatch())) {
            // Depth first, then shading only the faces it kept (see FarGpu#DEPTH_PREPASS).
            long setting = Float.floatToIntBits(FarLod.nearReach()) & 0xFFFFFFFFL | PREPASSED;
            // The shading pass draws again what the pre-pass drew, so without that it needs the columns.
            boolean again = REUSE_DRAWS && FarGpu.REPLAY && farDraws.holds(viewSerial, setting);
            IntArrayList far = again ? NONE : FarNearSkip.ENABLED ? beyondReach(position) : allShown();
            FarLod.beginPrepass();
            gpu.phase(FarGpu.PREPASS);
            if (gpu.begin(true, ShaderBlocks.voxyPatch(), modelView, farProjection, position, shade, frame, false)) {
                long timer = FarGpuTimers.begin("far depth prepass");
                try { drawEach(gpu, farDraws, again, far, position, setting); }
                finally { FarGpuTimers.end(timer, gpu.ranges, -1); }
            }
            FarLod.beginShade();
            // Ranges the pre-pass shows wholly hidden are left out of the shading pass (see FarHiddenCull).
            if (FarGpu.REPLAY && farDraws.holds(viewSerial, setting)) {
                long timer = FarGpuTimers.begin("far hidden cull");
                try { gpu.cullHidden(farDraws, FarLod.facesTexture(), FarLod.width(), FarLod.height(), modelView, farProjection); }
                finally { FarGpuTimers.end(timer); }
            }
            gpu.phase(FarGpu.SHADE);
            if (gpu.begin(true, ShaderBlocks.voxyPatch(), modelView, farProjection, position, shade, frame, false)) {
                long timer = FarGpuTimers.begin("far opaque");
                try { if (!FarGpu.REPLAY || !farDraws.holds(viewSerial, setting) || !gpu.play(farDraws)) drawEach(gpu, far, position); }
                finally { FarGpuTimers.end(timer, gpu.ranges, -1); }
                drawCount.submitted(gpu.ranges);
            }
            gpu.cullDone();
            gpu.phase(FarGpu.ONE_PASS);
            FarLod.endShade();
        } else if (gpu.begin(pack, pack ? ShaderBlocks.voxyPatch() : null, modelView, farProjection, position, shade, frame, false)) {
            long timer = FarGpuTimers.begin("far opaque");
            // Under a pack's own depth, columns wholly inside the game's blocks' reach draw no pixel there.
            boolean skipNear = own && FarNearSkip.ENABLED && gpu.dropsNearReach();
            long setting = (skipNear ? Float.floatToIntBits(FarLod.nearReach()) & 0xFFFFFFFFL | SKIPS_NEAR : 0) | (pack ? PACK : 0);
            boolean again = REUSE_DRAWS && farDraws.holds(viewSerial, setting);
            IntArrayList far = again ? NONE : skipNear ? beyondReach(position) : allShown();
            try { drawEach(gpu, farDraws, again, far, position, setting); }
            finally { FarGpuTimers.end(timer, gpu.ranges, -1); }
            drawCount.submitted(gpu.ranges);
        }
        if (own) FarLod.endDraw();
        long deepenTimer = own ? FarGpuTimers.begin("water deepen") : 0;
        if (own && FarLod.beginDeepen(modelView)) {
            waterBuffers.begin();
            for (int i = 0, count = FAST_WATER ? waterSurfaces.size() : shown; i < count; i++) {
                Column column = shownColumn(FAST_WATER ? waterSurfaces.getInt(i) : i);
                if (column.water == null || column.fade == FarGpu.FADE_IN) continue;
                FarLod.offset((float) (column.x0 - position.x), (float) (column.baseY() - position.y), (float) (column.z0 - position.z));
                waterBuffers.draw(column.water);
            }
            waterBuffers.end();
            FarLod.endDeepen();
        }
        FarGpuTimers.end(deepenTimer);
        shader.clear();
        type.clearRenderState();
    }

    /** The columns in view, in order, that reach inside the game's blocks' reach somewhere: their places in {@link #shownDrawn}. */
    private IntArrayList nearReach(Vec3 position, float reach) {
        near.clear();
        for (int i = 0; i < shown; i++) {
            int at = shownDrawn[i] * 6;
            double dx = Math.max(0.0, Math.max(drawnBoxes[at] - position.x, position.x - drawnBoxes[at + 3]));
            double dz = Math.max(0.0, Math.max(drawnBoxes[at + 2] - position.z, position.z - drawnBoxes[at + 5]));
            if (dx * dx + dz * dz < reach * reach) near.add(i);
        }
        return near;
    }

    /** The columns in view, in order, that reach past the game's blocks' reach somewhere (see {@link FarNearSkip}). */
    private IntArrayList beyondReach(Vec3 position) {
        double reach = FarLod.nearReach();
        beyond.clear();
        for (int i = 0; i < shown; i++) {
            int at = shownDrawn[i] * 6;
            if (!FarNearSkip.dropped(drawnBoxes[at], drawnBoxes[at + 1], drawnBoxes[at + 2], drawnBoxes[at + 3], drawnBoxes[at + 4], drawnBoxes[at + 5],
                    position.x, position.y, position.z, reach)) beyond.add(i);
        }
        return beyond;
    }

    /** Every column in view, in order. */
    private IntArrayList allShown() {
        if (all.size() != shown) {
            all.clear();
            for (int i = 0; i < shown; i++) all.add(i);
        }
        return all;
    }

    /** The lists of columns in view the passes draw, made once rather than every frame. */
    private final IntArrayList near = new IntArrayList(), beyond = new IntArrayList(), all = new IntArrayList();
    private static final IntArrayList NONE = new IntArrayList();

    /**
     * What the near pass and the far pass (the depth pre-pass, or the one pass) last queued, and the
     * pick and setting they were queued for. While the pick stands (the view and the columns as they
     * were) a pass draws its recording again rather than going over every column: standing still
     * that was most of the render thread's own time in far terrain, with every column's box and
     * faces spread over the heap. {@code -Dearthplatform.far.reuseDraws=false} queues them every frame.
     */
    private static final boolean REUSE_DRAWS = !"false".equals(System.getProperty("earthplatform.far.reuseDraws"));
    private final FarGpu.Recording nearDraws = new FarGpu.Recording(), farDraws = new FarGpu.Recording();
    /** A far pass's setting, besides the near reach: drawn after a pre-pass, leaving out what lies inside the reach, under a pack. */
    private static final long PREPASSED = 1L << 32, SKIPS_NEAR = 1L << 33, PACK = 1L << 34;

    /**
     * Draws a pass's columns, keeping what it queued, or with {@code again} what it queued for this
     * pick and setting before; the columns are then not needed.
     */
    private void drawEach(FarGpu gpu, FarGpu.Recording recording, boolean again, IntArrayList columns, Vec3 position, long setting) {
        if (again && gpu.play(recording)) return;
        gpu.record(recording);
        drawEach(gpu, columns, position);
        recording.drew(viewSerial, setting);
    }

    /**
     * Whether the walk must run again (see {@link #REUSE_WALK}). It depends on the camera only
     * through the column it is in at {@link #WALK_LEVEL}, as every coarser level's column follows
     * from that one, and otherwise on the finest level, the sharpness, the band, the columns held
     * and meshed, and the fades it saw under way: what it draws changes when the first of them ends.
     * A column it draws taken away walks it at once; a column meshed or a fade ended waits for
     * {@link #WALK_EVERY} to pass since the last walk. A column it draws meshed again only has
     * what the view noted of it worked out again (see {@link #upload}).
     */
    private boolean walkDue(int bx, int bz, boolean band, int now) {
        if (!walked || walkStale || Far.column(bx, WALK_LEVEL) != walkX || Far.column(bz, WALK_LEVEL) != walkZ || finest != walkFinest
                || Far.hole() != walkHole || band != walkBand) return true;
        return walkBehind(now) && (now - walkNow & 0x7FFFFFF) >= WALK_EVERY;
    }

    /** Whether something the kept walk does not draw yet has changed: a column meshed, or a fade it saw ended. */
    private boolean walkBehind(int now) {
        return walkBehind || walkLeft != Integer.MAX_VALUE && (now - walkNow & 0x7FFFFFF) >= walkLeft;
    }

    /**
     * How far a walk from the last walk's camera goes down toward a column (see {@link #visit}): a
     * bit for each level above the column whose column there it goes down through, from the top
     * until the first it stops at; all of them set when it reaches the column itself. -1 when the
     * walk starts nowhere over it. Worked out from the places' tags, as {@link #childrenReady} is.
     */
    private int walkPath(Column column) {
        int level = column.level, x = Far.x(column.key), z = Far.z(column.key), bx = walkBx, bz = walkBz;
        // The walk starts at the top level's columns around the camera's.
        int top = Far.MAX_LEVEL - level;
        if (Math.abs((x >> top) - Far.column(bx, Far.MAX_LEVEL)) > Far.OUTER || Math.abs((z >> top) - Far.column(bz, Far.MAX_LEVEL)) > Far.OUTER) return -1;
        int path = 0;
        for (int at = Far.MAX_LEVEL; at > level; at--) {
            int ax = x >> (at - level), az = z >> (at - level);
            boolean meshed = places.meshed(at, ax, az);
            boolean refined = Far.refined(at, ax, az, bx, bz, finest) || !meshed && Far.refined(at, ax, az, bx, bz);
            if (!refined || meshed && !childrenReady(at, ax, az, bx, bz)) break;
            path |= 1 << at;
        }
        return path;
    }

    /**
     * Whether a column the walk does not draw, just meshed for the first time, changes what a walk
     * would draw now; {@code before} is its {@link #walkPath} from just before it was meshed. The
     * walk reads nothing of it but whether it is meshed, where it reaches it; and above it, where a
     * column's children are all ready, which it may have just made so. Where the walk stops above
     * it, at a column that draws in its place, nothing under that column is looked at, unless that
     * column is fading back in over its children (see {@link #merge}), when whether they are all
     * meshed counts.
     */
    private boolean changesWalk(Column column, int before) {
        int after = walkPath(column);
        if (after != before) return true;
        if (after < 0) return false;
        int above = (1 << Far.MAX_LEVEL + 1) - (1 << column.level + 1);
        if (after == above) return true;
        // The level it stops at: the highest one above the column it does not go down through.
        int stop = 31 - Integer.numberOfLeadingZeros(above & ~after);
        if (stop != column.level + 1) return false;
        Column parent = held(stop, Far.x(column.key) >> 1, Far.z(column.key) >> 1);
        return parent != null && (parent.merging || parent.split);
    }

    /** Whether the kept walk draws this column (see {@link #drawn}), at its place in {@link #drawn}. */
    private boolean listed(Column column) {
        int at = column.drawnAt;
        return column.drawnPick == pickSerial && at >= 0 && at < drawn.size() && drawn.get(at) == column;
    }

    /** Walks the pyramid for the columns to draw and how (see {@link #visit}), with the fades it starts written to the GPU together. */
    private void walk(FarGpu gpu, int bx, int bz, boolean band, int now) {
        long start = System.nanoTime();
        gpu.beginStamps();
        try {
            walk(bx, bz, band, now);
        } finally {
            gpu.endStamps();
            Timings.FAR_WALK.since(start);
        }
    }

    /**
     * For {@link #CHECK_WALK}, on a frame that kept the last walk and has just looked at it again:
     * walks and looks afresh, and logs anything the two draw differently.
     */
    private void checkWalk(FarGpu gpu, Vec3 position, Frustum frustum, int bx, int bz, boolean band, int now) {
        List<Column> columns = shownColumns(), waters = new ArrayList<>(), edges = new ArrayList<>();
        int[] groups = java.util.Arrays.copyOf(shownGroups, shown), infos = new int[shown];
        for (int i = 0; i < shown; i++) infos[i] = drawnInfo[shownDrawn[i]];
        for (int i = 0; i < waterSurfaces.size(); i++) waters.add(shownColumn(waterSurfaces.getInt(i)));
        for (int i = 0; i < waterEdges.size(); i++) edges.add(shownColumn(waterEdges.getInt(i)));
        int left = walkLeft, since = now - walkNow & 0x7FFFFFF;
        walk(gpu, bx, bz, band, now);
        view(position, frustum);
        List<Column> fresh = shownColumns();
        boolean same = columns.equals(fresh) && java.util.Arrays.equals(groups, java.util.Arrays.copyOf(shownGroups, shown));
        for (int i = 0; same && i < shown; i++) {
            same = infos[i] == drawnInfo[shownDrawn[i]];
        }
        for (int i = 0; same && i < waterSurfaces.size(); i++) same = i < waters.size() && waters.get(i) == shownColumn(waterSurfaces.getInt(i));
        for (int i = 0; same && i < waterEdges.size(); i++) same = i < edges.size() && edges.get(i) == shownColumn(waterEdges.getInt(i));
        same &= waters.size() == waterSurfaces.size() && edges.size() == waterEdges.size();
        if (same) checksPassed++;
        else {
            EarthPlatformMod.LOGGER.warn("Far walk check: the kept walk drew {} columns in view where a fresh one draws {} (after {} frames that matched; fades left {}, since {})",
                    columns.size(), shown, checksPassed, left, since);
            java.util.Set<Column> kept = new java.util.HashSet<>(columns), walked = new java.util.HashSet<>(fresh);
            StringBuilder differ = new StringBuilder();
            int listed = 0;
            for (Column column : columns) if (!walked.contains(column) && listed++ < 6) differ.append(" kept-only ").append(describe(column));
            for (Column column : fresh) if (!kept.contains(column) && listed++ < 12) differ.append(" fresh-only ").append(describe(column));
            EarthPlatformMod.LOGGER.warn("Far walk check:{}", differ);
        }
        if (checksPassed % 1000 == 1) EarthPlatformMod.LOGGER.info("Far walk check: {} frames matched", checksPassed);
    }

    private int checksPassed;

    private String describe(Column column) {
        Column parent = column.level < Far.MAX_LEVEL ? held(column.level + 1, Far.x(column.key) >> 1, Far.z(column.key) >> 1) : null;
        return "[L" + column.level + " " + Far.x(column.key) + "," + Far.z(column.key) + " meshed " + column.meshed + " split " + column.split
                + " merging " + column.merging + " age " + age(column, pickNow) + " fade " + column.fade + " faces " + (column.faces != null)
                + " water " + (column.water != null) + (parent == null ? " no parent" : " parent split " + parent.split + " merging " + parent.merging
                + " age " + age(parent, pickNow) + " meshed " + parent.meshed + " drawn " + drawn(parent)) + "]";
    }

    private void walk(int bx, int bz, boolean band, int now) {
        drawn.clear();
        pickSerial++;
        pickAnimating = false;
        pickNow = now;
        walkLeft = Integer.MAX_VALUE;
        // The dithered ring between levels only blends where a pack's TAA moves the dither every frame
        // and averages it; without one it stays put, and where the two levels' outlines differ, as at
        // cliff edges, it left fixed specks of sky. There levels meet at a plain seam.
        bandFade = band;
        int px = Far.column(bx, Far.MAX_LEVEL), pz = Far.column(bz, Far.MAX_LEVEL);
        cullTree.clear(CULL_TREE);
        for (int z = pz - Far.OUTER; z <= pz + Far.OUTER; z++) for (int x = px - Far.OUTER; x <= px + Far.OUTER; x++) {
            int parent = cullTree.open();
            visit(held(Far.MAX_LEVEL, x, z), Far.MAX_LEVEL, x, z, bx, bz, false);
            cullTree.close(parent);
        }
        int count = drawn.size();
        if (drawnInfo.length < count) {
            int size = Math.max(count, drawnInfo.length * 2);
            drawnInfo = new int[size];
            drawnArena = new FarGpu.Arena[size];
            drawnStart = new int[size];
            drawnCounts = new int[size * FarMesher.GROUPS];
        }
        java.util.Arrays.fill(drawnInfo, 0, count, -1);
        walked = true;
        walkBx = bx;
        walkBz = bz;
        walkX = Far.column(bx, WALK_LEVEL);
        walkZ = Far.column(bz, WALK_LEVEL);
        walkFinest = finest;
        walkHole = Far.hole();
        walkBand = band;
        walkStale = false;
        walkBehind = false;
        walkNow = pickNow;
    }

    /**
     * Looks at the walked columns from the camera: which are in view, nearest first, which of their
     * faces turn to it, and which water shows. Only flat tables are read for most of them, never
     * the columns themselves, spread over the heap.
     */
    private void view(Vec3 position, Frustum frustum) {
        viewSerial++;
        boolean tree = CULL_TREE && frustum.getClass() == Frustum.class && frustum instanceof FrustumAccess;
        if (tree) {
            FrustumAccess view = (FrustumAccess) frustum;
            cullTree.classify(view.earthplatform$intersection(), view.earthplatform$camX(), view.earthplatform$camY(), view.earthplatform$camZ());
        }
        // The columns in view, from what addDrawn noted of each: only those are looked at again.
        FrustumAccess view = tree ? (FrustumAccess) frustum : null;
        int count = drawn.size(), kept = 0;
        order.clear(count);
        for (int i = 0; i < count; i++) {
            if (!drawnAny[i]) continue;
            int seen = tree ? cullTree.state(i) : FarCullTree.PARTLY;
            int at = i * 6;
            double minX = drawnBoxes[at], minY = drawnBoxes[at + 1], minZ = drawnBoxes[at + 2];
            double maxX = drawnBoxes[at + 3], maxY = drawnBoxes[at + 4], maxZ = drawnBoxes[at + 5];
            if (seen == FarCullTree.OUTSIDE || seen == FarCullTree.PARTLY && !(view != null
                    // As Frustum.isVisible tests a box: its corners as floats from the frustum's camera.
                    ? view.earthplatform$intersection().testAab((float) (minX - view.earthplatform$camX()), (float) (minY - view.earthplatform$camY()),
                            (float) (minZ - view.earthplatform$camZ()), (float) (maxX - view.earthplatform$camX()),
                            (float) (maxY - view.earthplatform$camY()), (float) (maxZ - view.earthplatform$camZ()))
                    : frustum.isVisible(drawn.get(i).box))) continue;
            // As AABB.getCenter() works it out, without making a Vec3 for each column.
            double dx = minX + 0.5 * (maxX - minX) - position.x, dz = minZ + 0.5 * (maxZ - minZ) - position.z;
            order.add(dx * dx + dz * dz, i);
            if (kept == keptDrawn.length) keptDrawn = java.util.Arrays.copyOf(keptDrawn, kept * 2);
            keptDrawn[kept++] = i;
        }
        // Nearest first, and in the walk's order where two are as near: from the last view's order
        // while the walk stands, which the camera moving a little changes only here and there.
        if (REUSE_ORDER) order.sortFrom(pickSerial, count);
        else order.sort();
        if (shownDrawn.length < kept) {
            int size = Math.max(kept, shownDrawn.length * 2);
            shownDrawn = new int[size];
            shownGroups = new int[size];
            shownDistance = new double[size];
        }
        for (int i = 0; i < kept; i++) {
            int at = order.index(i), index = keptDrawn[at];
            shownDrawn[i] = index;
            shownDistance[i] = order.value(at);
            shownGroups[i] = faceGroups(index, info(index), position);
        }
        shown = kept;
        if (FAST_WATER) pickWater();
    }

    /** The columns in view, nearest first; their places in {@link #drawn}, distances squared and face groups; and how many there are. */
    private int[] shownDrawn = new int[4096], shownGroups = new int[4096];
    private double[] shownDistance = new double[4096];
    private int shown;

    /**
     * A column in view by its place in {@link #shownDrawn}. Listing the columns themselves for each
     * view, every one a reference stored into a list the collector keeps track of, took as long as
     * all the rest of putting them in order while flying.
     */
    private Column shownColumn(int at) { return drawn.get(shownDrawn[at]); }

    /** The columns in view, nearest first, as a list (for {@link #checkWalk}). */
    private List<Column> shownColumns() {
        List<Column> columns = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) columns.add(shownColumn(i));
        return columns;
    }

    /**
     * The fade clock (see {@link FarGpu#now}) for the whole of a pick, read once: read for every
     * column visited, it cost as much as some of the pick's own work.
     */
    private int pickNow;

    /**
     * Each drawn column's box (six bounds) and whether it holds faces or water, by its place in
     * {@link #drawn}, noted while the pick has the column at hand: two drawn columns in three lie out
     * of view, and reaching each of them again only to find that took the render thread a few
     * milliseconds a frame while moving.
     */
    private double[] drawnBoxes = new double[6 * 4096];
    private boolean[] drawnAny = new boolean[4096];
    /** The drawn columns kept in view, by their place in {@link #drawn}, in their order there. */
    private int[] keptDrawn = new int[4096];

    /**
     * What a view needs of a drawn column besides its box (see {@link #info}), by its place in
     * {@link #drawn}, worked out the first time the column is in view after a walk and kept until
     * the next, as the columns in view change little from frame to frame; -1 until then. With its
     * faces' arena (null when it has none), their first face, and their counts per group in the
     * order they are stored (see {@link FarGpu#add(FarGpu.Arena, int, int[], int, int, float, float, float, float, float, float)}).
     */
    private int[] drawnInfo = new int[4096], drawnStart = new int[4096], drawnCounts = new int[4096 * FarMesher.GROUPS];
    private FarGpu.Arena[] drawnArena = new FarGpu.Arena[4096];
    /**
     * A drawn column's info: its fade in the low bits, then whether it has faces, smooth ones, and
     * water, the sides with no drawn column beside them (where its walls can show), and the sides
     * whose water walls are drawn.
     */
    private static final int FADES = 3, FACES = 1 << 2, SMOOTH = 1 << 3, WATER = 1 << 4, OPEN = 8, WATER_SIDES = 12;

    /** A drawn column's info (see {@link #FADES}), worked out from the column and its sides when not yet known since the walk. */
    private int info(int index) {
        int info = drawnInfo[index];
        if (info >= 0) return info;
        info = infoOf(index);
        drawnInfo[index] = info;
        return info;
    }

    private int infoOf(int index) {
        Column column = drawn.get(index);
        int info = column.fade;
        FarGpu.Allocation faces = column.faces;
        if (faces != null) {
            info |= FACES | (faces.smooth() ? SMOOTH : 0);
            drawnArena[index] = faces.arena();
            drawnStart[index] = faces.start();
            int[] counts = faces.counts();
            for (int k = 0; k < FarMesher.GROUPS; k++) drawnCounts[index * FarMesher.GROUPS + k] = counts[FarMesher.STORED[k]];
        } else drawnArena[index] = null;
        if (column.water != null) info |= WATER;
        for (int side = 0; side < 4; side++) {
            Column beside = beside(column, side);
            if (!drawn(beside)) info |= 1 << OPEN + side;
            // Water stays with the finer level where two levels fade into each other.
            if (column.fade != FarGpu.FADE_IN && (column.wallSides & 1 << side) != 0 && !waterDrawn(beside)) info |= 1 << WATER_SIDES + side;
        }
        return info;
    }

    /** Adds a column to this pick's drawn ones, in the view-culling node that is open (see {@link FarCullTree}). */
    private void addDrawn(Column column) {
        int index = drawn.size();
        double minX = column.minX(), minY = column.minY(), minZ = column.minZ(), maxX = column.maxX(), maxY = column.maxY(), maxZ = column.maxZ();
        cullTree.own(index, minX, minY, minZ, maxX, maxY, maxZ);
        if (index == drawnAny.length) {
            drawnAny = java.util.Arrays.copyOf(drawnAny, index * 2);
            drawnBoxes = java.util.Arrays.copyOf(drawnBoxes, index * 12);
        }
        int at = index * 6;
        drawnBoxes[at] = minX;
        drawnBoxes[at + 1] = minY;
        drawnBoxes[at + 2] = minZ;
        drawnBoxes[at + 3] = maxX;
        drawnBoxes[at + 4] = maxY;
        drawnBoxes[at + 5] = maxZ;
        drawnAny[index] = column.faces != null || column.water != null;
        drawn.add(column);
        column.drawnPick = pickSerial;
        column.drawnAt = index;
    }

    /** Water uses the same sorted pick and neighbor coverage in every pass. */
    private void pickWater() {
        waterVisible.clear();
        waterSurfaces.clear();
        waterEdges.clear();
        for (int i = 0; i < shown; i++) {
            int info = drawnInfo[shownDrawn[i]];
            if ((info & FADES) == FarGpu.FADE_IN) continue;
            boolean surface = (info & WATER) != 0, edge = (info >> WATER_SIDES & 15) != 0;
            if (surface) waterSurfaces.add(i);
            if (edge) waterEdges.add(i);
            if (surface || edge) waterVisible.add(i);
        }
    }

    /** The water sides of a column in view to draw walls on: those where no column of its water is drawn beside it. */
    private int waterSides(int at) { return drawnInfo[shownDrawn[at]] >> WATER_SIDES & 15; }

    /** Draws columns in view: whole ones first, then those fading out, then those fading in, each with its own program. */
    private void drawEach(FarGpu gpu, IntArrayList columns, Vec3 position) {
        int[] list = columns.elements();
        int count = columns.size(), fades = 0;
        for (int i = 0; i < count; i++) fades |= 1 << (drawnInfo[shownDrawn[list[i]]] & FADES);
        for (int fade = FarGpu.NO_FADE; fade <= FarGpu.FADE_IN; fade++) {
            if ((fades & 1 << fade) == 0) continue;
            if (fade != FarGpu.NO_FADE && !gpu.fade(fade) && fade == FarGpu.FADE_IN) break;
            for (int i = 0; i < count; i++) if ((drawnInfo[shownDrawn[list[i]]] & FADES) == fade) queue(gpu, list[i], position);
        }
        gpu.end();
    }

    /** Queues the faces of a column in view (by its place in {@link #shownDrawn}) that can face the camera, and its walls where its neighbours are not drawn. */
    private void queue(FarGpu gpu, int at, Vec3 position) {
        int index = shownDrawn[at];
        FarGpu.Arena arena = drawnArena[index];
        if (arena == null) return;
        int box = index * 6;
        gpu.add(arena, drawnStart[index], drawnCounts, index * FarMesher.GROUPS, shownGroups[at],
                (float) (drawnBoxes[box] - position.x), (float) (drawnBoxes[box + 1] - position.y), (float) (drawnBoxes[box + 2] - position.z),
                (float) (drawnBoxes[box + 3] - position.x), (float) (drawnBoxes[box + 4] - position.y), (float) (drawnBoxes[box + 5] - position.z));
    }

    /** The face groups of a drawn column that can show from where the camera is, from its box and info alone. */
    private int faceGroups(int index, int info, Vec3 position) {
        if ((info & FACES) == 0) return 0;
        int at = index * 6;
        double minX = drawnBoxes[at], minY = drawnBoxes[at + 1], minZ = drawnBoxes[at + 2];
        double maxX = drawnBoxes[at + 3], maxY = drawnBoxes[at + 4], maxZ = drawnBoxes[at + 5];
        // Only face groups turned toward the camera can show. Smooth ground's slopes, cliffs among them,
        // face sideways too, so it shows from below its column as well.
        int groups = 0;
        if (position.y < maxY) groups |= 1;
        if (position.y > minY || (info & SMOOTH) != 0) groups |= 1 << 1;
        if (position.z < maxZ) groups |= 1 << 2;
        if (position.z > minZ) groups |= 1 << 3;
        if (position.x < maxX) groups |= 1 << 4;
        if (position.x > minX) groups |= 1 << 5;
        // Walls only where no drawn column stands beside it.
        if ((info & 1 << OPEN) != 0 && position.z < minZ + 1) groups |= 1 << FarMesher.FIRST_WALL;
        if ((info & 1 << OPEN + 1) != 0 && position.z > maxZ - 1) groups |= 1 << FarMesher.FIRST_WALL + 1;
        if ((info & 1 << OPEN + 2) != 0 && position.x < minX + 1) groups |= 1 << FarMesher.FIRST_WALL + 2;
        if ((info & 1 << OPEN + 3) != 0 && position.x > maxX - 1) groups |= 1 << FarMesher.FIRST_WALL + 3;
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

    /** An Earth world's metres per block at the equator, or 0 before the server says or outside Earth worlds. */
    public double metresPerBlock() { return metresPerBlock; }

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
            for (int i = 0; i < shown && !any; i++) {
                Column column = shownColumn(i);
                any = column.fade != FarGpu.FADE_IN && (column.water != null || column.hasWaterWalls());
            }
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
        waterBuffers.begin();
        for (int pass = 0; pass < 2; pass++) {
            // Water walls go last: where the next level's water stands as high, its surface is
            // already in the depth buffer and hides them, so they only show across a step.
            IntArrayList water = pass == 0 ? waterSurfaces : waterEdges;
            for (int i = (FAST_WATER ? water.size() : shown) - 1; i >= 0; i--) {
                int at = FAST_WATER ? water.getInt(i) : i;
                Column column = shownColumn(at);
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
                    double distance = Math.sqrt(shownDistance[at] + square(column.box.getCenter().y - position.y));
                    float scale = (float) Math.max(0.8, 1.0 - distance * lift);
                    if (!FAST_WATER || scale != lastScale) {
                        view.set(nearer.set(modelView).scale(scale));
                        view.upload();
                        lastScale = scale;
                    }
                }
                if (pass == 0) {
                    waterBuffers.draw(column.water);
                    continue;
                }
                for (int side = 0; side < 4; side++) {
                    FarWaterBuffers.Mesh wall = column.waterWalls[side];
                    if (FAST_WATER ? (waterSides(at) & 1 << side) == 0 : wall == null || waterDrawn(beside(column, side))) continue;
                    waterBuffers.draw(wall);
                }
            }
        }
        FarGpuTimers.end(colourTimer);
        if (offset != null) offset.set(0.0F, 0.0F, 0.0F);
        if (view != null) view.set(modelView);
        if (own) FarLod.endWater(modelView, () -> {
            waterBuffers.begin();
            for (int i = 0, count = FAST_WATER ? waterVisible.size() : shown; i < count; i++) {
                int at = FAST_WATER ? waterVisible.getInt(i) : i;
                Column column = shownColumn(at);
                if (!FAST_WATER && (column.fade == FarGpu.FADE_IN || column.water == null && !column.hasWaterWalls())) continue;
                FarLod.offset((float) (column.x0 - position.x), (float) (column.baseY() - position.y), (float) (column.z0 - position.z));
                if (column.water != null) {
                    waterBuffers.draw(column.water);
                }
                for (int side = 0; side < 4; side++) {
                    FarWaterBuffers.Mesh wall = column.waterWalls[side];
                    if (FAST_WATER ? (waterSides(at) & 1 << side) == 0 : wall == null || waterDrawn(beside(column, side))) continue;
                    waterBuffers.draw(wall);
                }
            }
        });
        shader.clear();
        waterBuffers.end();
        type.clearRenderState();
    }

    /**
     * Draws a column, or its four children where a finer level replaces it. A replaced column only
     * stands in while its children load if it has a mesh itself: it is asked for last, so after a
     * goto it usually has none, and waiting for every child down to the finest level left the
     * ground around the player empty until the whole tree under it had arrived.
     */
    private void visit(@Nullable Column column, int level, int x, int z, int bx, int bz, boolean fading) {
        if (FADE && column != null) running(column.stamp);
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
                int now = pickNow;
                if (column.merging) {
                    // The camera has come back mid-merge: the children turn round and fade back in from where they got to.
                    int start = now - Math.max(0, FarShaders.FADE_TICKS - age(column, now)) & 0x7FFFFFF;
                    column.merging = false;
                    column.split = true;
                    stamp(column, start == 0 ? 1 : start);
                    for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                        Column child = kid(column, level, x, z, dx, dz);
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
                        Column child = kid(column, level, x, z, dx, dz);
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
            for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++)
                visit(kid(column, level, x, z, dx, dz), level - 1, 2 * x + dx, 2 * z + dz, bx, bz, band || arriving);
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
        int now = pickNow;
        // With a child already gone, the column would fade in over a hole: it is drawn whole instead.
        boolean whole = true;
        for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
            Column child = kid(column, level, x, z, dx, dz);
            if (child == null || !child.meshed) whole = false;
        }
        if (starting && whole) {
            // Mid-way through fading in over it, the children go no further and turn back from there.
            int left = column.merging ? 0 : Math.max(0, FarShaders.FADE_TICKS - age(column, now));
            int start = now - left & 0x7FFFFFF;
            column.merging = true;
            stamp(column, start == 0 ? 1 : start, true);
            for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                Column child = kid(column, level, x, z, dx, dz);
                if (child != null && child.meshed) stamp(child, column.stamp, true);
            }
        }
        if (whole && column.merging && age(column, now) < FarShaders.FADE_TICKS) {
            column.fade = FarGpu.FADE_IN;
            addDrawn(column);
            for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                Column child = kid(column, level, x, z, dx, dz);
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
            Column child = kid(column, level, x, z, dx, dz);
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
                    Column column = held(level, x, z);
                    if (column != null && column.drawnPick == pickSerial && column.meshedCubes != null && column.meshedCubes.contains(cube)) return true;
                }
            }
        }
        return false;
    }

    /** Hundredths of a second since a column last began to fade, on the clock {@link FarGpu#now} keeps. */
    private static int age(Column column, int now) {
        return column.stamp == 0 ? Integer.MAX_VALUE : now - column.stamp & 0x7FFFFFF;
    }

    /**
     * Notes a fade the walk sees, begun at a stamp (0 for none): while it runs, the walk needs
     * doing again when it ends (see {@link #walkDue}), as then what the walk draws changes.
     */
    private void running(int stamp) {
        if (stamp == 0) return;
        int left = FarShaders.FADE_TICKS - (pickNow - stamp & 0x7FFFFFF);
        if (left <= 0) return;
        if (CACHE_PICK) pickAnimating = true;
        if (left < walkLeft) walkLeft = left;
    }

    /** Starts a column's fade now, in the slots its faces are drawn with. */
    private void stamp(Column column, int now) { stamp(column, now, false); }

    /** Starts a column's fade, or a merge's (see {@link #merge}), at a time, or with 0 clears it. */
    private void stamp(Column column, int now, boolean merging) {
        pickVersion++;
        if (now != 0) {
            pickAnimating = true;
            running(now);
        }
        column.stamp = now;
        if (gpu == null) return;
        gpu.stamp(column.slot, now, merging);
        gpu.stamp(column.oldSlot, now, merging);
    }

    /** True when a column's water is drawn this frame: drawn, and not fading in over finer water. */
    /** Whether the last pick drew a column, which may be missing. */
    private boolean drawn(@Nullable Column column) { return column != null && column.drawnPick == pickSerial; }

    /** Whether the last pick drew a column's water: drawn, and not fading in over the finer level. */
    private boolean waterDrawn(@Nullable Column column) { return drawn(column) && column.fade != FarGpu.FADE_IN; }

    /**
     * Whether a level's column x, z can be drawn: meshed itself, or refined with all its children
     * ready. Answered from the places' tags (see {@link FarPlaces#meshed}), not the columns.
     */
    private boolean ready(int level, int x, int z, int bx, int bz) {
        if (places.meshed(level, x, z)) return true;
        return Far.refined(level, x, z, bx, bz, finest) && childrenReady(level, x, z, bx, bz);
    }

    private boolean childrenReady(int level, int x, int z, int bx, int bz) {
        for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
            if (!ready(level - 1, 2 * x + dx, 2 * z + dz, bx, bz)) return false;
        }
        return true;
    }

    /** A column's kid from its links when the column is held, or else from the map: kids can be held under a missing column. */
    @Nullable private Column kid(@Nullable Column column, int level, int x, int z, int dx, int dz) {
        return LINK_COLUMNS && column != null ? column.kids[dz * 2 + dx] : held(level - 1, 2 * x + dx, 2 * z + dz);
    }

    /** The held column beside one (north, south, west, east), or null. */
    @Nullable private Column beside(Column column, int side) {
        return LINK_COLUMNS ? column.sides[side] : columns.get(column.neighbour(side));
    }

    /** Links a column just put in the map with its parent, kids and sides (see {@link Column#kids}). */
    private void link(Column column) {
        int level = column.level, x = Far.x(column.key), z = Far.z(column.key);
        if (level > Far.MIN_LEVEL)
            for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) column.kids[dz * 2 + dx] = held(level - 1, 2 * x + dx, 2 * z + dz);
        if (level < Far.MAX_LEVEL) {
            Column parent = held(level + 1, x >> 1, z >> 1);
            if (parent != null) parent.kids[(z & 1) * 2 + (x & 1)] = column;
        }
        for (int side = 0; side < 4; side++) {
            Column beside = held(column.neighbour(side));
            column.sides[side] = beside;
            if (beside != null) beside.sides[side ^ 1] = column;
        }
    }

    /** Unlinks a column just taken out of the map. */
    private void unlink(Column column) {
        int level = column.level, x = Far.x(column.key), z = Far.z(column.key);
        if (level < Far.MAX_LEVEL) {
            Column parent = held(level + 1, x >> 1, z >> 1);
            int at = (z & 1) * 2 + (x & 1);
            if (parent != null && parent.kids[at] == column) parent.kids[at] = null;
        }
        for (int side = 0; side < 4; side++) {
            Column beside = column.sides[side];
            if (beside != null && beside.sides[side ^ 1] == column) beside.sides[side ^ 1] = null;
        }
    }

    /** Releases the bytes once; replacement columns transfer ownership without changing the total. */
    private void free(Column column) {
        vertexBytes -= column.bytes;
        column.bytes = 0;
        column.close(gpu, waterBuffers);
    }

    void close() {
        FarGpuTimers.close();
        closed = true;
        built.close();
        for (Column column : columns.values()) free(column);
        columns.clear();
        waterBuffers.close();
        places.clear();
        java.util.Arrays.fill(topColumns, null);
        topCount = 0;
        topsVersion++;
        staleMasks.clear();
        masked.clear();
        unmasked.clear();
        handedOut = null;
        nearDraws.close();
        farDraws.close();
        pick.invalidate();
        drawn.clear();
        shown = 0;
        all.clear();
        waterVisible.clear();
        waterSurfaces.clear();
        waterEdges.clear();
        dueColumns.clear();
        if (gpu != null) { gpu.close(); gpu = null; }
    }
}
