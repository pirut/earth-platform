package io.earthplatform.minecraft.cubic.far;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubeCodec;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.CubeStorage;
import io.earthplatform.minecraft.cubic.Timings;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.earthplatform.minecraft.cubic.gen.FarColumn;
import io.earthplatform.minecraft.cubic.net.CubePayloads;
import io.earthplatform.minecraft.cubic.net.Throttle;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final CubeGenerator generator;
    private final CubeStorage storage;
    private final CubeCodec codec;
    private final IdMap<Holder<Biome>> biomeIds;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "Earth far terrain " + THREADS.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final ConcurrentHashMap<Long, CubePayloads.FarColumnData> cache = new ConcurrentHashMap<>();
    /** What edited columns looked like before, so a remade one that came out the same is not sent again. */
    private final ConcurrentHashMap<Long, CubePayloads.FarColumnData> before = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Made> made = new ConcurrentLinkedQueue<>();
    /** Columns that could not be made, or were skipped as their player had moved away; players may ask for them again. */
    private final ConcurrentLinkedQueue<Long> failed = new ConcurrentLinkedQueue<>();
    private final AtomicInteger failures = new AtomicInteger(), skipped = new AtomicInteger(), unchanged = new AtomicInteger();
    private volatile long lastFailureLog;
    private final Map<UUID, Session> sessions = new HashMap<>();
    /** Columns whose saved cubes changed and that players hold, to remake and push again. */
    private final LongOpenHashSet stale = new LongOpenHashSet();
    private int ticks;
    private boolean closed;

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
    }

    /** Queues the columns a player asked for; ones outside that player's squares are ignored. */
    public void request(ServerPlayer player, long[] keys) {
        if (closed) return;
        UUID id = player.getUUID();
        Session session = sessions.computeIfAbsent(id, unused -> new Session());
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
                CubePayloads.FarColumnData old = player == null ? before.remove(key) : null;
                // Most saved edits (grass spreading, a placed torch) change nothing at a voxel's size.
                if (old == null || !same(old, column)) made.add(new Made(player, column));
                else unchanged.incrementAndGet();
                Timings.FAR_MAKE.since(start);
            }
            catch (RuntimeException | IOException failure) {
                failed.add(key);
                failures.incrementAndGet();
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
            CubePayloads.FarColumnData old = cache.remove(key);
            if (old != null && before.size() < CACHE) before.putIfAbsent(key, old);
            stale.add(key);
        }
    }

    private static boolean same(CubePayloads.FarColumnData a, CubePayloads.FarColumnData b) {
        if (a.bottom() != b.bottom() || !Arrays.equals(a.drops(), b.drops()) || a.sections().size() != b.sections().size()) return false;
        for (int i = 0; i < a.sections().size(); i++) if (!Arrays.equals(a.sections().get(i), b.sections().get(i))) return false;
        return true;
    }

    private CubePayloads.FarColumnData make(long key, boolean useCache) throws IOException {
        if (useCache) {
            CubePayloads.FarColumnData cached = cache.get(key);
            if (cached != null) return cached;
        }
        int level = Far.level(key);
        FarColumn column = new FarColumn();
        generator.farColumn(level, Far.x(key), Far.z(key), column);
        if (level <= Far.EDIT_LEVELS) foldSavedCubes(level, Far.x(key), Far.z(key), column);
        List<byte[]> sections = new ArrayList<>(column.sections.size());
        for (LevelChunkSection section : column.sections) sections.add(section == null ? new byte[0] : CubeCodec.sectionBytes(section));
        CubePayloads.FarColumnData data = new CubePayloads.FarColumnData(key, column.bottom, sections, column.drops);
        if (cache.size() > CACHE) cache.clear();
        cache.put(key, data);
        return data;
    }

    // Saved cubes --------------------------------------------------------------------------------

    /** Overwrites the voxels each saved cube covers with that cube's own blocks, downsampled. */
    private void foldSavedCubes(int level, int columnX, int columnZ, FarColumn column) throws IOException {
        int perCube = 16 >> level, cubes = 1 << level;
        int minX = columnX * cubes, minZ = columnZ * cubes;
        for (long key : storage.savedIn(minX, minX + cubes - 1, minZ, minZ + cubes - 1)) {
            CompoundTag tag = storage.read(key);
            if (tag == null) continue;
            LevelChunkSection cube = codec.read(tag).section();
            int voxelY = (CubeKey.y(key) * 16) >> level, sectionIndex = (voxelY >> 4) - column.bottom;
            // Edits below the column's ground are out of sight from afar.
            if (sectionIndex < 0 || sectionIndex >= Far.MAX_SECTIONS) continue;
            LevelChunkSection target = section(column, sectionIndex);
            int baseX = (CubeKey.x(key) - minX) * perCube, baseY = voxelY & 15, baseZ = (CubeKey.z(key) - minZ) * perCube;
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
                else before.remove(key);
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
    /** Columns skipped because their player had moved far away before their turn came. */
    public int skipped() { return skipped.get(); }
    /** Edited columns remade the same as before, so not sent again. */
    public int unchanged() { return unchanged.get(); }

    public void close() {
        closed = true;
        workers.shutdownNow();
        sessions.clear();
    }
}
