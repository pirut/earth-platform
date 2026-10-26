package io.earthplatform.minecraft.cubic.far;

import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.geography.globe.NamedThreads;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubeCodec;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.CubeStorage;
import io.earthplatform.minecraft.cubic.Timings;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.earthplatform.minecraft.cubic.gen.FarColumn;
import io.earthplatform.minecraft.cubic.net.CubePayloads;
import io.earthplatform.minecraft.cubic.net.Throttle;
import io.earthplatform.minecraft.diagnostics.PerfFailures;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Makes far-terrain columns for players on low-priority workers, so they never hold up the cubes
 * players stand in. Columns come from the generator with every saved cube folded in, and are
 * remade and pushed again when a player's edits are saved, so builds show from afar.
 */
public final class FarServer {
    private static final int SEND_PER_TICK = 64, CACHE = 8192;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final CubeGenerator generator;
    private final CubeStorage storage;
    private final CubeCodec codec;
    private final IdMap<Holder<Biome>> biomeIds;
    private final Registry<Biome> biomes;
    /** Columns as the generator made them, kept on disk between sessions; null when off. */
    @Nullable private final FarStore store;
    /** What players built, for the coarse levels whose voxels cover several cubes. */
    private final FarBuilds builds;
    /** Summarizes saved builds off the server thread, in the order they were saved. */
    private final java.util.concurrent.ExecutorService summarizer = java.util.concurrent.Executors.newSingleThreadExecutor(
            NamedThreads.daemon("Earth far builds", Thread.MIN_PRIORITY));
    /** Coarse far columns made with a build showing, for diagnostics. */
    private final java.util.concurrent.atomic.AtomicIntegerArray builtColumns = new java.util.concurrent.atomic.AtomicIntegerArray(Far.MAX_LEVEL - Far.EDIT_LEVELS);
    /** Cube columns whose builds changed, so their coarse far columns are remade. */
    private final ConcurrentLinkedQueue<Long> rebuilt = new ConcurrentLinkedQueue<>();
    /** Cubes summarized since the world opened, which the reading of old saves leaves alone. */
    private final Set<Long> summarized = ConcurrentHashMap.newKeySet();
    /**
     * Far workers kept busy: two on four cores, and more on bigger machines, where two left most
     * cores idle while the far terrain took minutes to refine. They run at low priority behind the
     * cube workers. Set with {@code -Dearthplatform.far.threads}.
     */
    static final int WORKERS = Math.clamp(Integer.getInteger("earthplatform.far.threads", workersFor(Runtime.getRuntime().availableProcessors())), 2, 8);
    /**
     * Most far columns made at once while most of them wait on downloads, as over a new place; see
     * {@link FarWorkers}. Only {@link #WORKERS} of them use the processor, so the rest need no cores:
     * it was once the cores less two at most, when columns waiting could not be told from columns at
     * work and more at once made a four-core server answer late. Set with
     * {@code -Dearthplatform.far.mostThreads}.
     */
    static final int MOST_WORKERS = Math.clamp(Integer.getInteger("earthplatform.far.mostThreads", mostWorkersFor(WORKERS)), WORKERS, 64);

    /** Far workers kept at work on a machine with {@code cores} processors, when nothing sets it. */
    public static int workersFor(int cores) { return Math.clamp((cores - 4) / 2, 2, 8); }

    /** Far columns made at once with {@code workers} of them at work, when nothing sets it. */
    public static int mostWorkersFor(int workers) { return workers + 24; }

    private final FarWorkers workers = new FarWorkers("Earth far terrain", WORKERS, MOST_WORKERS);
    private final ConcurrentHashMap<Long, CubePayloads.FarColumnData> cache = new ConcurrentHashMap<>();
    /** What columns looked like, so an edited one remade the same is not sent again. */
    private final FarPrints prints = new FarPrints();
    private final ConcurrentLinkedQueue<Made> made = new ConcurrentLinkedQueue<>();
    /** Columns that could not be made, or were skipped as their player had moved away; players may ask for them again. */
    private final ConcurrentLinkedQueue<Long> failed = new ConcurrentLinkedQueue<>();
    /** Columns whose data could not be had, given back to be asked for again every few seconds. */
    private final ConcurrentLinkedQueue<Long> missing = new ConcurrentLinkedQueue<>();
    private final AtomicInteger failures = new AtomicInteger(), skipped = new AtomicInteger(), unchanged = new AtomicInteger();
    private volatile long lastFailureLog;
    private final Map<UUID, Session> sessions = new HashMap<>();
    /** Columns whose saved cubes changed and that players hold, to remake and push again. */
    private final LongOpenHashSet stale = new LongOpenHashSet();
    private int ticks;
    /** Set by {@link #close}; read by the far workers it interrupts. */
    private volatile boolean closed;

    /** A finished column for one player, or for everyone holding it when {@code player} is null. */
    private record Made(@Nullable UUID player, CubePayloads.FarColumnData column) {}

    private static final class Session {
        final LongOpenHashSet queued = new LongOpenHashSet();
        final LongOpenHashSet sent = new LongOpenHashSet();
        final ArrayDeque<CubePayloads.FarColumnData> outgoing = new ArrayDeque<>();
        /** Where the player stands, so workers can skip columns asked for before the player moved far away. */
        volatile int bx, bz;
    }

    public FarServer(CubeGenerator generator, CubeStorage storage, CubeCodec codec, Registry<Biome> biomes) {
        this.generator = generator;
        this.storage = storage;
        this.codec = codec;
        this.biomeIds = biomes.asHolderIdMap();
        this.biomes = biomes;
        this.builds = new FarBuilds(storage.directory().resolveSibling("builds"));
        this.store = FarStore.open(storage.directory().resolveSibling("far"), storeToken(biomes));
        if (!builds.scanned()) {
            Thread scan = new Thread(this::scanOldSaves, "Earth far builds scan");
            scan.setDaemon(true);
            scan.setPriority(Thread.MIN_PRIORITY);
            scan.start();
        }
    }

    /** Far columns a player asked for that have not been sent yet: its own terrain is still on its way. */
    public int waiting(UUID player) {
        Session session = sessions.get(player);
        return session == null ? 0 : session.queued.size();
    }

    /** Queues the columns a player asked for; ones outside that player's squares are ignored. */
    public void request(ServerPlayer player, long[] keys) {
        if (closed) return;
        UUID id = player.getUUID();
        Session session = sessions.get(id);
        if (session == null) {
            sessions.put(id, session = new Session());
            if (generator instanceof EarthGenerator earth) PacketDistributor.sendToPlayer(player, new CubePayloads.EarthScale(earth.grid().metresPerBlock()));
        }
        int bx = player.getBlockX(), bz = player.getBlockZ();
        session.bx = bx;
        session.bz = bz;
        for (long key : keys) {
            if (!Far.valid(key) || !Far.near(key, bx, bz) || !session.queued.add(key)) continue;
            CubePayloads.FarColumnData cached = cache.get(key);
            if (cached != null) session.outgoing.add(cached);
            else submit(id, session, key);
        }
    }

    /** Whether cubes near players are waiting to be made; far columns then run no more than {@link #WORKERS} at once. */
    public void nearWaiting(boolean waiting) { workers.hold(waiting); }

    private void submit(@Nullable UUID player, @Nullable Session session, long key) {
        workers.execute(() -> {
            // After a teleport the old place's columns can be thousands deep in the queue: skip them.
            if (session != null && !Far.near(key, session.bx, session.bz)) {
                skipped.incrementAndGet();
                failed.add(key);
                return;
            }
            long start = System.nanoTime();
            try {
                CubePayloads.FarColumnData column = make(key, player != null);
                // Most saved edits (grass spreading, a placed torch) change nothing at a voxel's size.
                if (player != null || !prints.remadeSame(key)) made.add(new Made(player, column));
                else unchanged.incrementAndGet();
                Timings.FAR_MAKE.since(start);
            }
            catch (RuntimeException | IOException failure) {
                // Closing the world interrupts columns still being made: nothing failed, so nothing to retry or report.
                if (closed) return;
                // Missing data is asked for again a few seconds on, not at once: with a data server down it fails at once again.
                if (failure instanceof MissingTerrainException) missing.add(key);
                else failed.add(key);
                failures.incrementAndGet();
                PerfFailures.record(PerfFailures.Stage.FAR_GENERATION,failure);
                long now = System.currentTimeMillis();
                if (now - lastFailureLog > 10_000) {
                    lastFailureLog = now;
                    EarthPlatformMod.LOGGER.warn("Could not make far column {} ({} failures so far)", key, failures.get(), failure);
                }
            }
        });
    }

    /** A saved cube changed: its columns at the levels that can show it get remade for everyone holding them. */
    public void edited(int cubeX, int cubeZ) {
        for (int level = Far.MIN_LEVEL; level <= Far.EDIT_LEVELS; level++) {
            long key = Far.key(level, cubeX >> level, cubeZ >> level);
            cache.remove(key);
            prints.edited(key);
            stale.add(key);
        }
    }

    private CubePayloads.FarColumnData make(long key, boolean useCache) throws IOException {
        if (useCache) {
            CubePayloads.FarColumnData cached = cache.get(key);
            if (cached != null) return cached;
        }
        int level = Far.level(key), columnX = Far.x(key), columnZ = Far.z(key);
        FarStore.Stored stored = store == null ? null : store.get(key);
        FarColumn column = null;
        List<byte[]> sections;
        int bottom;
        short[] drops;
        if (stored != null) {
            sections = stored.sections();
            bottom = stored.bottom();
            drops = stored.drops();
        } else {
            column = new FarColumn();
            generator.farColumn(level, columnX, columnZ, column);
            sections = bytes(column);
            bottom = column.bottom;
            drops = column.drops;
            if (store != null) store.put(key, bottom, sections, drops);
        }
        // Saved cubes and builds go on top of the generated column every time, so the store never holds them.
        int cubes = 1 << level, minX = columnX * cubes, minZ = columnZ * cubes;
        LongList saved = level <= Far.EDIT_LEVELS ? storage.savedIn(minX, minX + cubes - 1, minZ, minZ + cubes - 1) : null;
        var built = level <= Far.EDIT_LEVELS ? null : builds.in(minX, minX + cubes - 1, minZ, minZ + cubes - 1);
        if (saved != null && !saved.isEmpty() || built != null && !built.isEmpty()) {
            if (column == null) column = column(bottom, sections, drops);
            if (saved != null) foldSavedCubes(level, saved, column);
            else foldBuilds(level, columnX, columnZ, built, column);
            sections = bytes(column);
        }
        CubePayloads.FarColumnData data = new CubePayloads.FarColumnData(key, bottom, sections, drops);
        prints.made(key, FarPrints.of(bottom, sections, drops));
        if (cache.size() > CACHE) cache.clear();
        cache.put(key, data);
        return data;
    }

    private static List<byte[]> bytes(FarColumn column) {
        List<byte[]> sections = new ArrayList<>(column.sections.size());
        for (LevelChunkSection section : column.sections) sections.add(section == null ? new byte[0] : CubeCodec.sectionBytes(section));
        return sections;
    }

    /** A stored column as the generator would have filled it, for saved cubes and builds to fold into. */
    private FarColumn column(int bottom, List<byte[]> sections, short[] drops) {
        FarColumn column = new FarColumn();
        column.bottom = bottom;
        for (byte[] section : sections) column.sections.add(section.length == 0 ? null : CubeCodec.readSection(section, biomes));
        System.arraycopy(drops, 0, column.drops, 0, column.drops.length);
        return column;
    }

    /**
     * What a stored column was made by: this build of the mod, and the block and biome ids its
     * bytes hold, which other mods change. A store with another token is started afresh.
     */
    public static String storeToken(Registry<Biome> biomes) {
        long blocks = 1;
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) blocks = blocks * 31 + state.toString().hashCode();
        long biomeHash = 1;
        for (int id = 0; id < biomes.size(); id++) {
            Biome biome = biomes.byId(id);
            var name = biome == null ? null : biomes.getKey(biome);
            biomeHash = biomeHash * 31 + (name == null ? 0 : name.toString().hashCode());
        }
        return io.earthplatform.minecraft.diagnostics.BuildInfo.version() + " " + io.earthplatform.minecraft.diagnostics.BuildInfo.commit()
                + " blocks " + Block.BLOCK_STATE_REGISTRY.size() + ":" + Long.toHexString(blocks)
                + " biomes " + biomes.size() + ":" + Long.toHexString(biomeHash);
    }

    // Saved cubes --------------------------------------------------------------------------------

    /** Overwrites the voxels each saved cube covers with that cube's own blocks, downsampled. */
    private void foldSavedCubes(int level, LongList saved, FarColumn column) throws IOException {
        int perCube = 16 >> level, cubes = 1 << level;
        for (long key : saved) {
            CompoundTag tag = storage.read(key);
            // Cubes saved just as generated already match the far terrain made from the generator.
            if (tag == null || tag.getBoolean(CubeCodec.UNTOUCHED)) continue;
            LevelChunkSection cube = codec.read(tag).section();
            int voxelY = (CubeKey.y(key) * 16) >> level, sectionIndex = (voxelY >> 4) - column.bottom;
            // Edits below the column's ground are out of sight from afar.
            if (sectionIndex < 0 || sectionIndex >= Far.MAX_SECTIONS) continue;
            LevelChunkSection target = section(column, sectionIndex);
            int baseX = (CubeKey.x(key) & cubes - 1) * perCube, baseY = voxelY & 15, baseZ = (CubeKey.z(key) & cubes - 1) * perCube;
            for (int y = 0; y < perCube; y++) for (int z = 0; z < perCube; z++) for (int x = 0; x < perCube; x++) {
                BlockState state = downsample(cube, x << level, y << level, z << level, 1 << level);
                BlockState here = target.getBlockState(baseX + x, baseY + y, baseZ + z);
                // The generated voxels are lowered by their column's drop so the water meets its real
                // height, and a cube's own rows are not: swapping water and air between the two raised
                // the water over every saved cube into a lighter patch with a stepped outline. Edits that
                // matter from afar are to solid ground, so water and air keep the generated shape.
                if (here != state && !(waterOrAir(here) && waterOrAir(state))) target.setBlockState(baseX + x, baseY + y, baseZ + z, state, false);
            }
        }
        for (int i = 0; i < column.sections.size(); i++) {
            LevelChunkSection section = column.sections.get(i);
            if (section == null) continue;
            section.recalcBlockCounts();
            if (section.hasOnlyAir()) column.sections.set(i, null);
        }
    }

    /** Stands the summarized builds under a coarse column on its ground. */
    private void foldBuilds(int level, int columnX, int columnZ, it.unimi.dsi.fastutil.longs.Long2ObjectMap<FarBuilds.Summary> found, FarColumn column) {
        if (FarBuilds.fold(level, columnX, columnZ, found, column, this::section) > 0) builtColumns.incrementAndGet(level - Far.EDIT_LEVELS - 1);
        for (int i = 0; i < column.sections.size(); i++) {
            LevelChunkSection section = column.sections.get(i);
            if (section != null) section.recalcBlockCounts();
        }
    }

    // Builds -------------------------------------------------------------------------------------

    /**
     * An edited cube was saved: its build is summarized in the background, and the coarse columns
     * over it are remade if that changed. {@code states} must be the caller's own copy.
     */
    public void summarize(long cubeKey, PalettedContainer<BlockState> states) {
        if (closed) return;
        summarized.add(cubeKey);
        summarizer.execute(() -> {
            try {
                int x = CubeKey.x(cubeKey), y = CubeKey.y(cubeKey), z = CubeKey.z(cubeKey);
                if (builds.put(cubeKey, FarBuilds.summarize(states, x, y, z, generator::surfaceY))) rebuilt.add(cubeKey);
            } catch (RuntimeException failure) {
                EarthPlatformMod.LOGGER.warn("Could not summarize the build in cube {}", CubeKey.toString(cubeKey), failure);
            }
        });
    }

    /** Reads the builds in every edited cube saved before summaries were kept. */
    private void scanOldSaves() {
        long start = System.nanoTime();
        int[] counts = new int[2];
        try {
            storage.forEachSaved((cubeKey, tag) -> {
                if (closed) throw new java.util.concurrent.CancellationException();
                if (tag.getBoolean(CubeCodec.UNTOUCHED) || summarized.contains(cubeKey)) return;
                counts[0]++;
                FarBuilds.Summary summary = FarBuilds.summarize(codec.read(tag).section().getStates(),
                        CubeKey.x(cubeKey), CubeKey.y(cubeKey), CubeKey.z(cubeKey), generator::surfaceY);
                if (summary != null && builds.putIfAbsent(cubeKey, summary)) { counts[1]++; rebuilt.add(cubeKey); }
            });
            builds.flush();
            builds.markScanned();
            if (counts[0] > 0) EarthPlatformMod.LOGGER.info("Far terrain: read {} builds in {} edited cubes saved before, in {} ms",
                    counts[1], counts[0], (System.nanoTime() - start) / 1_000_000);
        } catch (java.util.concurrent.CancellationException stopped) {
            // The world closed first; the next opening reads them again.
        } catch (IOException | RuntimeException failure) {
            EarthPlatformMod.LOGGER.warn("Could not read the builds of older saves for far terrain", failure);
        }
    }

    /** Writes the build summaries that changed. */
    public void flush() { if (!closed) summarizer.execute(builds::flush); }

    private static boolean waterOrAir(BlockState state) { return state.isAir() || state.getBlock() == Blocks.WATER; }

    private LevelChunkSection section(FarColumn column, int index) {
        while (column.sections.size() <= index) column.sections.add(null);
        LevelChunkSection section = column.sections.get(index);
        if (section != null) return section;
        Holder<Biome> biome = null;
        for (LevelChunkSection other : column.sections) if (other != null) { biome = other.getBiomes().get(2, 2, 2); break; }
        if (biome == null) biome = biomeIds.byIdOrThrow(0);
        section = new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, AIR, PalettedContainer.Strategy.SECTION_STATES),
                new PalettedContainer<>(biomeIds, biome, PalettedContainer.Strategy.SECTION_BIOMES));
        column.sections.set(index, section);
        return section;
    }

    /**
     * The block standing for a size^3 box of a cube: the most common of the blocks seen from above
     * in each of its block columns once a quarter of it is solid, else water up to the highest
     * water in it, else air.
     */
    private static BlockState downsample(LevelChunkSection cube, int x0, int y0, int z0, int size) {
        int solid = 0, water = -1;
        Map<BlockState, Integer> seen = new IdentityHashMap<>();
        for (int z = z0; z < z0 + size; z++) for (int x = x0; x < x0 + size; x++) {
            boolean top = true;
            for (int y = y0 + size - 1; y >= y0; y--) {
                BlockState state = cube.getBlockState(x, y, z);
                if (state.isAir()) continue;
                // Plants and waterlogged blocks under water read as water from afar.
                boolean isWater = state.getFluidState().is(FluidTags.WATER);
                if (isWater) water = Math.max(water, y);
                else solid++;
                if (top && !isWater) { seen.merge(state, 1, Integer::sum); top = false; }
            }
        }
        if (solid * 4 < size * size * size) {
            BlockState filled = water < 0 ? null : Far.water(water - y0 + Far.WATER_SURFACE, size);
            return filled == null ? AIR : filled;
        }
        BlockState best = AIR;
        int most = 0;
        for (var entry : seen.entrySet()) if (entry.getValue() > most) { most = entry.getValue(); best = entry.getKey(); }
        return best;
    }

    // Sending ------------------------------------------------------------------------------------

    /** Sends finished columns, a few per player per tick, remakes edited ones and forgets players who left. */
    public void tick(Iterable<ServerPlayer> players) {
        ticks++;
        for (Long key; (key = failed.poll()) != null; ) for (Session session : sessions.values()) session.queued.remove(key.longValue());
        if (ticks % 100 == 0) for (Long key; (key = missing.poll()) != null; ) for (Session session : sessions.values()) session.queued.remove(key.longValue());
        for (Long cube; (cube = rebuilt.poll()) != null; ) {
            int cubeX = CubeKey.x(cube), cubeZ = CubeKey.z(cube);
            for (int level = Far.EDIT_LEVELS + 1; level <= Far.MAX_LEVEL; level++) {
                long key = Far.key(level, cubeX >> level, cubeZ >> level);
                cache.remove(key);
                prints.edited(key);
                stale.add(key);
            }
        }
        for (Made done; (done = made.poll()) != null; ) {
            if (done.player != null) {
                Session session = sessions.get(done.player);
                if (session != null) session.outgoing.add(done.column);
                continue;
            }
            for (Session session : sessions.values()) if (session.sent.contains(done.column.key())) session.outgoing.add(done.column);
        }
        if (!stale.isEmpty()) {
            for (LongIterator it = stale.iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                boolean held = false;
                for (Session session : sessions.values()) held |= session.sent.contains(key);
                if (held) submit(null, null, key);
                else prints.dropped(key);
            }
            stale.clear();
        }
        Set<UUID> present = new HashSet<>();
        for (ServerPlayer player : players) {
            present.add(player.getUUID());
            Session session = sessions.get(player.getUUID());
            if (session == null) continue;
            session.bx = player.getBlockX();
            session.bz = player.getBlockZ();
            int bytes = Throttle.allowance(player);
            for (int n = 0; n < SEND_PER_TICK && bytes > 0 && !session.outgoing.isEmpty(); n++) {
                CubePayloads.FarColumnData column = session.outgoing.poll();
                session.queued.remove(column.key());
                session.sent.add(column.key());
                PacketDistributor.sendToPlayer(player, column);
                bytes -= column.size();
            }
            if (ticks % 200 == 0) {
                int bx = player.getBlockX(), bz = player.getBlockZ();
                session.sent.removeIf(key -> !Far.near(key, bx, bz));
            }
        }
        sessions.keySet().retainAll(present);
    }

    public int cached() { return cache.size(); }
    /** Builds kept for the coarse levels, and how many coarse columns have shown one. */
    public String builds() {
        StringBuilder levels = new StringBuilder();
        for (int i = 0; i < builtColumns.length(); i++) levels.append(i == 0 ? "" : ", ").append("level ").append(Far.EDIT_LEVELS + 1 + i).append(": ").append(builtColumns.get(i));
        return builds.cubes() + " cubes with builds, shown in coarse far columns made (" + levels + ")";
    }
    /** Columns skipped because their player had moved far away before their turn came. */
    public int skipped() { return skipped.get(); }
    /** Edited columns remade the same as before, so not sent again. */
    public int unchanged() { return unchanged.get(); }

    public void close() {
        closed = true;
        workers.shutdownNow();
        // Summaries already asked for are written before the thread ends.
        summarizer.execute(builds::flush);
        summarizer.shutdown();
        try { summarizer.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        sessions.clear();
        // Columns still being made find it closed and keep nothing.
        if (store != null) store.close();
    }
}
