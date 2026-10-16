package io.earthplatform.minecraft.cubic;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.far.FarServer;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.earthplatform.minecraft.cubic.net.CubePayloads;
import io.earthplatform.minecraft.cubic.net.Throttle;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server cube authority: generates or loads cubes around players on worker threads, adopts them
 * on the server thread, streams them to watching players nearest first, random-ticks them and
 * saves edited cubes.
 */
public final class ServerCubeWorld extends CubeWorld {
    private static final int MAX_PENDING = 384, MAX_ADOPT_PER_TICK = 768, SEND_CUBES_PER_TICK = 160, LIGHT_PER_TICK = 16;
    private static final AtomicInteger THREADS = new AtomicInteger();

    private final ServerLevel server;
    private final CubeGenerator generator;
    private final CubeStorage storage;
    private final CubeCodec codec;
    private final ExecutorService workers;
    private final FarServer far;
    private final LongOpenHashSet pending = new LongOpenHashSet();
    /** Cubes whose generation failed (say, terrain data was unreachable), with the tick to try again. */
    private final Long2IntOpenHashMap retryAt = new Long2IntOpenHashMap();
    private int failures, lastFailureLog = Integer.MIN_VALUE / 2;
    private final ConcurrentLinkedQueue<Ready> ready = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Watcher> watchers = new HashMap<>();
    /** Players' cube positions and the reach around them, for workers deciding whether a request is still wanted. */
    private volatile int[] wantedAround = new int[0];
    private volatile int wantedH, wantedV;
    /** Requests workers dropped as no longer wanted, to take off {@code pending} on the server thread. */
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> droppedKeys = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.LongAdder generated = new java.util.concurrent.atomic.LongAdder(), dropped = new java.util.concurrent.atomic.LongAdder();
    private final Set<BlockPos> changed = new HashSet<>();
    private final LongOpenHashSet changedColumns = new LongOpenHashSet();
    /** Gameplay light per cube, computed on workers when first asked for; stamps count invalidations. */
    private final Long2ObjectOpenHashMap<Lit> light = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap lightStamps = new Long2IntOpenHashMap();
    private final LongLinkedOpenHashSet lightWanted = new LongLinkedOpenHashSet();
    private final LongOpenHashSet lightPending = new LongOpenHashSet();
    private final ConcurrentLinkedQueue<Lit> lit = new ConcurrentLinkedQueue<>();
    /** Freshly generated surface cubes still owed their first animals. */
    private final LongOpenHashSet unpopulated = new LongOpenHashSet();
    /** Entities that freshly generated cubes start with (chest minecarts), placed once their chunk ticks entities. */
    private final Long2ObjectOpenHashMap<List<CompoundTag>> unplacedEntities = new Long2ObjectOpenHashMap<>();
    private int ticks;
    private boolean closed;

    /**
     * Rock buried under the ground is out of sight from above it, and a player on the surface gets only the
     * cubes holding the ground, the air over it and, near them, the rock under it: buried cubes further out
     * are not made at all, and the client gets a plain rock stand-in so the ground shows no hole and needs
     * no faces under it. A player who goes underground gets everything around again, nearest first.
     * {@code -Dearthplatform.cubic.buried=true} makes every cube, as before.
     */
    private static final boolean ALL_BURIED = Boolean.getBoolean("earthplatform.cubic.buried");
    /** Columns across around a surface player whose buried cubes are made anyway: room to dig down and around at full speed. */
    private static final int NEAR = 3;
    /** Blocks of rock under a cube column's lowest ground (and its four neighbours') before its cubes count as buried. */
    private static final int COVER = 16;
    private static final int UNKNOWN_LOW = Integer.MAX_VALUE;
    /** Lowest ground of cube columns, worked out on the workers. */
    private final Long2IntOpenHashMap lows = new Long2IntOpenHashMap();
    private final LongOpenHashSet lowsPending = new LongOpenHashSet();
    private final ConcurrentLinkedQueue<long[]> lowsReady = new ConcurrentLinkedQueue<>();
    /** Saved buried cubes near players, such as a mine: they come back from the save, never as a stand-in that would fill them in. */
    private final LongOpenHashSet kept = new LongOpenHashSet();
    /** Buried cubes behind an open face of a cube in the ground band (see {@link #openBehind}): made for real. */
    private final LongOpenHashSet opened = new LongOpenHashSet();
    private final byte[] standIn;

    /**
     * A loaded or generated cube; a null section means generating it failed. {@code surface} marks cubes holding the
     * ground still owed their first animals; {@code edited} marks saved cubes changed by play; {@code kept} marks fresh
     * cubes to save as generated.
     */
    private record Ready(long key, int x, int y, int z, @Nullable LevelChunkSection section, List<CompoundTag> blockEntities,
                         List<CompoundTag> entities, boolean saved, boolean edited, boolean surface, boolean kept, @Nullable RuntimeException failure, int open) {}
    private record Lit(long key, int stamp, @Nullable byte[] values) {}

    private static final class Watcher {
        final ServerPlayer player;
        final LongOpenHashSet sent = new LongOpenHashSet();
        /** Buried cubes sent as plain rock stand-ins, not made yet. */
        final LongOpenHashSet stood = new LongOpenHashSet();
        /** Underground (in a mine or a deep cave): every cube around is made, as rock is all there is to see. */
        boolean deep;
        final LongOpenHashSet columns = new LongOpenHashSet();
        final List<CubePayloads.Change> changes = new ArrayList<>();
        Watcher(ServerPlayer player) { this.player = player; }
    }

    public ServerCubeWorld(ServerLevel level, CubeGenerator generator) {
        super(level);
        this.server = level;
        this.generator = generator;
        generator.attach(level);
        this.codec = new CubeCodec(level.registryAccess().registryOrThrow(Registries.BIOME));
        lows.defaultReturnValue(UNKNOWN_LOW);
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        this.standIn = CubeCodec.sectionBytes(new LevelChunkSection(
                new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
                new PalettedContainer<>(biomes.asHolderIdMap(), biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS), PalettedContainer.Strategy.SECTION_BIOMES)));
        this.storage = new CubeStorage(DimensionType.getStorageFolder(level.dimension(),
                level.getServer().getWorldPath(LevelResource.ROOT)).resolve("cubes"));
        this.far = new FarServer(generator, storage, codec, level.registryAccess().registryOrThrow(Registries.BIOME));
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
        this.workers = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "Earth cube worker " + THREADS.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    public CubeGenerator generator() { return generator; }
    public FarServer far() { return far; }

    /**
     * The generator's ground, which the server thread asks for (sending tops, lighting, spawning)
     * beside cubes not yet made: where its data is missing, as with a data server down, that is
     * unknown for now rather than a crash.
     */
    @Override protected int baselineTop(int x, int z) {
        try { return generator.skyTopY(x, z); }
        catch (io.earthplatform.geography.MissingTerrainException missing) { return UNKNOWN_TOP; }
    }

    @Override protected void topChanged(int x, int z, int oldTop, int top) {
        changedColumns.add(CubeKey.column(x >> 4, z >> 4));
        int low = Math.min(oldTop, top) >> 4, high = Math.max(oldTop, top) >> 4;
        if (high - low > 64) low = high - 64;
        for (int cy = low - 1; cy <= high + 1; cy++) {
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) staleLight(CubeKey.pack((x >> 4) + dx, cy, (z >> 4) + dz));
        }
    }

    @Override protected void onStateStored(Cube cube, BlockPos pos, BlockState old, BlockState state) {
        if (old.getLightEmission() != state.getLightEmission() || CubeLight.opacity(old) != CubeLight.opacity(state)) staleLightAround(cube.x, cube.y, cube.z);
    }

    /** Light is recomputed and every watcher holding the cube is sent it again whole. */
    @Override protected void onRewritten(Cube cube) {
        staleLightAround(cube.x, cube.y, cube.z);
        long column = CubeKey.column(cube.x, cube.z);
        for (Watcher watcher : watchers.values()) {
            if (watcher.sent.remove(cube.key)) watcher.columns.remove(column);
        }
    }

    @Override protected void forget(Cube cube) {
        super.forget(cube);
        light.remove(cube.key);
        lightStamps.remove(cube.key);
    }

    @Override public void blockChanged(BlockPos pos) {
        if (Cubic.TRACE) EarthPlatformMod.LOGGER.info("cubic: server change at {} (cube loaded: {})", pos, cubes.containsKey(CubeKey.ofBlock(pos)));
        if (cubes.containsKey(CubeKey.ofBlock(pos))) changed.add(pos.immutable());
    }

    // Loading ------------------------------------------------------------------------------------

    /**
     * Queues a cube for loading or generation; false when it is loaded, queued or waiting to retry.
     * Unless {@code always}, a worker skips it if no player still wants it when its turn comes, so
     * a player travelling fast does not leave a queue of cubes behind that hold up the ones ahead.
     */
    private boolean request(long key, boolean always) {
        if (closed || cubes.containsKey(key) || pending.contains(key)) return false;
        if (retryAt.containsKey(key)) {
            if (ticks < retryAt.get(key)) return false;
            retryAt.remove(key);
        }
        pending.add(key);
        int x = CubeKey.x(key), y = CubeKey.y(key), z = CubeKey.z(key);
        workers.execute(() -> {
            if (always || wanted(x, y, z)) { ready.add(produce(key, x, y, z)); generated.increment(); }
            else { droppedKeys.add(key); dropped.increment(); }
        });
        return true;
    }

    /** Workers: whether a cube is still near a player, as of the last tick. */
    private boolean wanted(int x, int y, int z) {
        int[] at = wantedAround;
        int h = wantedH, v = wantedV;
        for (int i = 0; i + 2 < at.length; i += 3) if (CubeOffsets.within(x - at[i], y - at[i + 1], z - at[i + 2], h, v)) return true;
        return false;
    }

    private Ready produce(long key, int x, int y, int z) {
        try {
            CompoundTag saved = storage.read(key);
            if (saved != null) {
                CubeCodec.Decoded decoded = codec.read(saved);
                return new Ready(key, x, y, z, decoded.section(), decoded.blockEntities(), CubeCodec.tags(saved, CubeCodec.ENTITIES), true,
                        !saved.getBoolean(CubeCodec.UNTOUCHED), saved.getBoolean(CubeCodec.UNPOPULATED), false, null, openFaces(decoded.section()));
            }
        } catch (IOException | RuntimeException failure) {
            EarthPlatformMod.LOGGER.error("Could not read saved cube {}; regenerating it", CubeKey.toString(key), failure);
        }
        try {
            List<CompoundTag> blockEntities = new ArrayList<>(), entities = new ArrayList<>();
            LevelChunkSection section = generator.generate(x, y, z, blockEntities, entities);
            int[] band = surfaceBand(x, z);
            boolean surface = band[0] + SAVED_BELOW == y;
            return new Ready(key, x, y, z, section, blockEntities, entities, false, false, surface, y >= band[0] && y <= band[1], null, openFaces(section));
        } catch (RuntimeException failure) {
            return new Ready(key, x, y, z, null, List.of(), List.of(), false, false, false, false, failure, 0);
        }
    }

    /** Synchronous load for places that must exist this tick, such as an arrival point. */
    @Nullable public Cube loadNow(int x, int y, int z) {
        long key = CubeKey.pack(x, y, z);
        Cube cube = cubes.get(key);
        if (cube != null) return cube;
        adoptReady(produce(key, x, y, z));
        return cubes.get(key);
    }

    /** Loads the cubes around a block synchronously, for checks such as a respawn bed. */
    public void loadAround(BlockPos pos) {
        int cx = pos.getX() >> 4, cy = pos.getY() >> 4, cz = pos.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) loadNow(cx + dx, cy + dy, cz + dz);
    }

    /** Asks the workers for the cubes around a block without waiting; true once they are all loaded. */
    public boolean readyAround(BlockPos pos) {
        int cx = pos.getX() >> 4, cy = pos.getY() >> 4, cz = pos.getZ() >> 4;
        boolean ready = true;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            long key = CubeKey.pack(cx + dx, cy + dy, cz + dz);
            if (cubes.containsKey(key)) continue;
            ready = false;
            request(key, true);
        }
        return ready;
    }

    /**
     * Cubes under the ground that are saved untouched with it. Below them, and above the treetops, untouched
     * cubes are rock and air remade from the generator when needed; edited ones are always saved.
     */
    private static final int SAVED_BELOW = 1;
    private int pregenerated;

    /** Any thread; reads map data. The lowest and highest cube Y of a cube column that are saved as generated. */
    private int[] surfaceBand(int cubeX, int cubeZ) {
        int x = (cubeX << 4) + 8, z = (cubeZ << 4) + 8;
        int ground = generator.surfaceY(x, z) >> 4;
        return new int[]{ground - SAVED_BELOW, Math.max(ground + 1, generator.skyTopY(x, z) >> 4)};
    }

    /**
     * Any thread; blocks on map data. Generates and saves the cubes of a column that a visit would
     * save ({@link #surfaceBand}), for pre-generation mods such as Chunky. Cubes already saved are
     * kept; the store happens on the server thread.
     */
    public void pregenerate(int cubeX, int cubeZ) {
        if (closed) return;
        int[] band = surfaceBand(cubeX, cubeZ);
        LongArrayList keys = new LongArrayList();
        List<CompoundTag> tags = new ArrayList<>();
        for (int y = band[0]; y <= band[1]; y++) {
            long key = CubeKey.pack(cubeX, y, cubeZ);
            try { if (storage.read(key) != null) continue; }
            catch (IOException failure) { continue; }
            List<CompoundTag> blockEntities = new ArrayList<>(), entities = new ArrayList<>();
            LevelChunkSection section = generator.generate(cubeX, y, cubeZ, blockEntities, entities);
            CompoundTag tag = codec.writeGenerated(cubeX, y, cubeZ, section, blockEntities);
            if (y == band[0] + SAVED_BELOW) tag.putBoolean(CubeCodec.UNPOPULATED, true);
            if (!entities.isEmpty()) {
                net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
                list.addAll(entities);
                tag.put(CubeCodec.ENTITIES, list);
            }
            keys.add(key);
            tags.add(tag);
        }
        if (tags.isEmpty()) return;
        server.getServer().execute(() -> {
            if (closed) return;
            for (int i = 0; i < tags.size(); i++) {
                long key = keys.getLong(i);
                // A cube loaded meanwhile is saved from its own state when it unloads.
                if (cubes.containsKey(key)) continue;
                try { storage.put(key, tags.get(i)); }
                catch (IOException failure) { EarthPlatformMod.LOGGER.error("Could not store cube {}", CubeKey.toString(key), failure); }
            }
            // Written as it goes, so a long run does not hold every region in memory until the next save.
            if ((pregenerated += tags.size()) >= 2048) {
                pregenerated = 0;
                storage.flushLater();
            }
        });
    }

    private void adoptReady(Ready result) {
        pending.remove(result.key);
        if (cubes.containsKey(result.key)) return;
        if (result.section == null) {
            // Leave a gap and try again shortly rather than stall: the data may come back.
            if (retryAt.size() > 8192) retryAt.clear();
            retryAt.put(result.key, ticks + 100);
            failures++;
            if (ticks - lastFailureLog > 200) {
                lastFailureLog = ticks;
                EarthPlatformMod.LOGGER.warn("Could not generate cube {} ({} failures so far); retrying", CubeKey.toString(result.key), failures, result.failure);
            }
            return;
        }
        Cube cube = new Cube(result.x, result.y, result.z, result.section);
        // Generated ground is saved, as vanilla saves every chunk it generates: a fresh cube in the surface band when it
        // unloads, and any cube once play changes it.
        cube.modified = true;
        cube.dirty = !result.saved && result.kept;
        cube.edited = result.edited;
        if (result.surface) unpopulated.add(result.key);
        if (!result.entities.isEmpty()) unplacedEntities.put(result.key, result.entities);
        for (CompoundTag tag : result.blockEntities) {
            BlockPos pos = BlockEntity.getPosFromTag(tag);
            BlockEntity entity = BlockEntity.loadStatic(pos, cube.get(pos.getX(), pos.getY(), pos.getZ()), tag, server.registryAccess());
            if (entity != null) cube.blockEntities.put(pos, entity);
        }
        adopt(cube);
        openBehind(cube, result.open);
        // Points of interest at cubic heights live only in memory: rebuild them from the blocks.
        server.getPoiManager().checkConsistencyWithBlocks(SectionPos.of(cube.x, cube.y, cube.z), cube.section);
        CubicPoiClaims.reclaim(server, SectionPos.of(cube.x, cube.y, cube.z));
        refreshTops(cube);
        staleLightAround(cube.x, cube.y, cube.z);
    }

    // Ticking ------------------------------------------------------------------------------------

    private int horizontalRadius() {
        return Math.max(2, Math.min(Cubic.MAX_HORIZONTAL_RADIUS, server.getServer().getPlayerList().getViewDistance()));
    }

    public void tick() {
        if (closed) return;
        long start = System.nanoTime();
        try { tickCubes(); }
        finally { Timings.SERVER_TICK.since(start); }
    }

    private void tickCubes() {
        ticks++;
        for (int i = 0; i < MAX_ADOPT_PER_TICK; i++) {
            Ready result = ready.poll();
            if (result == null) break;
            adoptReady(result);
        }
        for (Long key; (key = droppedKeys.poll()) != null; ) pending.remove(key.longValue());
        for (long[] low; (low = lowsReady.poll()) != null; ) {
            lowsPending.remove(low[0]);
            lows.put(low[0], (int) low[1]);
            for (int i = 2; i < low.length; i++) kept.add(low[i]);
        }
        far.nearWaiting(!pending.isEmpty());
        int h = horizontalRadius(), v = Cubic.VERTICAL_RADIUS;
        CubeOffsets offsets = CubeOffsets.of(h, v);
        syncWatchers();
        publishWanted(h + 2, v + 2);
        // Each player gets an equal share of the free generation slots, starting with a different
        // player each tick, so one player's backlog never starves another's.
        List<Watcher> order = new ArrayList<>(watchers.values());
        if (order.size() > 1) Collections.rotate(order, ticks % order.size());
        int share = Math.max(8, (MAX_PENDING - pending.size()) / Math.max(1, order.size()));
        for (Watcher watcher : order) {
            int cx = watcher.player.getBlockX() >> 4, cy = watcher.player.getBlockY() >> 4, cz = watcher.player.getBlockZ() >> 4;
            // A glider high above all terrain sees only far terrain and may be crossing a cube a tick: stream nothing.
            if (io.earthplatform.minecraft.cubic.jet.JetStream.aboveTerrain(watcher.player)) {
                if (ticks % 10 == 0) unsend(watcher, 1, 1, cx, cy, cz);
                continue;
            }
            watcher.deep = deep(watcher);
            requestAround(watcher, offsets, cx, cy, cz, share);
            send(watcher, offsets, cx, cy, cz);
            if (ticks % 10 == 0) unsend(watcher, h + 1, v + 1, cx, cy, cz);
        }
        flushChanges();
        far.tick(server.players());
        lightCubes();
        if (ticks % 20 == 0) populate();
        if (ticks % 40 == 0) unloadUnwatched(h + 2, v + 2);
        randomTicks(v);
    }

    /**
     * Whether the cube holding this block has been sent to the player. Clients hold still until the
     * cubes they stand in and on arrive, so a player waiting on one is not flying.
     */
    public boolean sentTo(ServerPlayer player, int x, int y, int z) {
        Watcher watcher = watchers.get(player.getUUID());
        return watcher != null && watcher.player == player && watcher.sent.contains(CubeKey.pack(x >> 4, y >> 4, z >> 4));
    }

    private void syncWatchers() {
        Set<UUID> present = new HashSet<>();
        for (ServerPlayer player : server.players()) {
            present.add(player.getUUID());
            Watcher watcher = watchers.get(player.getUUID());
            if (watcher == null || watcher.player != player) watchers.put(player.getUUID(), new Watcher(player));
        }
        watchers.keySet().retainAll(present);
    }

    /**
     * Shares where players are with the workers; the reach matches what {@link #unloadUnwatched}
     * keeps. Gliders above all terrain stream no cubes, so nothing queued is wanted for them.
     */
    private void publishWanted(int h, int v) {
        int[] at = new int[watchers.size() * 3];
        int i = 0;
        for (Watcher watcher : watchers.values()) {
            if (io.earthplatform.minecraft.cubic.jet.JetStream.aboveTerrain(watcher.player)) continue;
            at[i++] = watcher.player.getBlockX() >> 4; at[i++] = watcher.player.getBlockY() >> 4; at[i++] = watcher.player.getBlockZ() >> 4;
        }
        wantedH = h;
        wantedV = v;
        wantedAround = java.util.Arrays.copyOf(at, i);
    }

    private void requestAround(Watcher watcher, CubeOffsets offsets, int cx, int cy, int cz, int limit) {
        for (int i = 0; i < offsets.size() && limit > 0 && pending.size() < MAX_PENDING; i++) {
            int dx = offsets.dx(i), dz = offsets.dz(i);
            long key = CubeKey.pack(cx + dx, cy + offsets.dy(i), cz + dz);
            if (cubes.containsKey(key) || pending.contains(key)) continue;
            // Buried rock far from a surface player waits until someone goes underground; so does a cube
            // whose ground is not worked out yet, for a tick or two.
            if (!watcher.deep && !near(dx, dz, NEAR) && buried(cx + dx, cy + offsets.dy(i), cz + dz) != 0) continue;
            if (request(key, false)) limit--;
        }
    }

    /** Workers: bits 0-5 (down, up, north, south, west, east) for the faces of a cube with a block on them that light and sight pass. */
    private static int openFaces(LevelChunkSection section) {
        if (section.hasOnlyAir()) return 63;
        int open = 0;
        for (int a = 0; a < 16 && open != 63; a++) for (int b = 0; b < 16; b++) {
            if ((open & 1) == 0 && !section.getBlockState(a, 0, b).canOcclude()) open |= 1;
            if ((open & 2) == 0 && !section.getBlockState(a, 15, b).canOcclude()) open |= 2;
            if ((open & 4) == 0 && !section.getBlockState(a, b, 0).canOcclude()) open |= 4;
            if ((open & 8) == 0 && !section.getBlockState(a, b, 15).canOcclude()) open |= 8;
            if ((open & 16) == 0 && !section.getBlockState(0, a, b).canOcclude()) open |= 16;
            if ((open & 32) == 0 && !section.getBlockState(15, a, b).canOcclude()) open |= 32;
        }
        return open;
    }

    /**
     * Server thread. Opens the buried cubes right behind the open faces of a cube that is not buried itself, such as
     * the rock under a cave going down from the ground band, so a cave seen from above carries on another 16 blocks
     * before any stand-in's stone. Opened cubes don't open further: caves join up under most ground, and following
     * them would bring back nearly all the rock.
     */
    private void openBehind(Cube cube, int open) {
        if (open == 0 || buried(cube.x, cube.y, cube.z) != 0 || opened.contains(cube.key)) return;
        for (int face = 0; face < 6; face++) {
            if ((open & 1 << face) == 0) continue;
            int x = cube.x + (face == 4 ? -1 : face == 5 ? 1 : 0), y = cube.y + (face == 0 ? -1 : face == 1 ? 1 : 0), z = cube.z + (face == 2 ? -1 : face == 3 ? 1 : 0);
            if (buried(x, y, z) == 1) opened.add(CubeKey.pack(x, y, z));
        }
    }

    private static boolean near(int dx, int dz, int reach) { return dx * dx + dz * dz <= reach * reach + reach; }

    /**
     * Server thread. 1 when a cube lies under at least {@link #COVER} blocks of rock below the lowest ground of
     * its own and the four next cube columns, 0 when not, -1 while that ground is being worked out.
     */
    private int buried(int x, int y, int z) {
        if (ALL_BURIED) return 0;
        if (!kept.isEmpty() && kept.contains(CubeKey.pack(x, y, z)) || !opened.isEmpty() && opened.contains(CubeKey.pack(x, y, z))) return 0;
        int low = lowAround(x, z);
        if (low == UNKNOWN_LOW) return -1;
        return (y << 4) + 15 < low - COVER ? 1 : 0;
    }

    /** Server thread. Lowest ground of a cube column and the four next to it, or {@link #UNKNOWN_LOW} while asked for. */
    private int lowAround(int x, int z) {
        int low = lowest(x, z), known = 1;
        for (int side = 0; side < 4; side++) {
            int each = lowest(x + (side == 0 ? 1 : side == 1 ? -1 : 0), z + (side == 2 ? 1 : side == 3 ? -1 : 0));
            if (each == UNKNOWN_LOW) known = 0;
            else low = Math.min(low, each);
        }
        return known == 0 || low == UNKNOWN_LOW ? UNKNOWN_LOW : low;
    }

    private int lowest(int x, int z) {
        long column = CubeKey.column(x, z);
        int low = lows.get(column);
        if (low == UNKNOWN_LOW && !closed && lowsPending.add(column)) {
            workers.execute(() -> {
                LongArrayList result = new LongArrayList();
                result.add(column);
                // Ground that can't be had yet counts as nothing buried there: its cubes are all made, as before.
                try {
                    result.add(generator.lowestGround(x, z));
                    result.addAll(storage.savedIn(x, x, z, z));
                }
                catch (IOException | RuntimeException failure) { result.size(1); result.add(Integer.MIN_VALUE); }
                lowsReady.add(result.toLongArray());
            });
        }
        return low;
    }

    /**
     * Whether a player is underground: {@link #COVER} blocks under the generated ground where they stand, until back
     * up near it, so walking along at that depth does not switch back and forth.
     */
    private boolean deep(Watcher watcher) {
        if (ALL_BURIED) return true;
        int ground;
        try { ground = generator.surfaceY(watcher.player.getBlockX(), watcher.player.getBlockZ()); }
        catch (RuntimeException missing) { return watcher.deep; }
        return watcher.player.getBlockY() < ground - (watcher.deep ? COVER / 2 : COVER);
    }

    private void send(Watcher watcher, CubeOffsets offsets, int cx, int cy, int cz) {
        int budget = SEND_CUBES_PER_TICK, standIns = 4 * SEND_CUBES_PER_TICK, bytes = Throttle.allowance(watcher.player);
        for (int i = 0; i < offsets.size() && budget > 0 && bytes > 0; i++) {
            int dx = offsets.dx(i), dz = offsets.dz(i), x = cx + dx, y = cy + offsets.dy(i), z = cz + dz;
            long key = CubeKey.pack(x, y, z);
            if (watcher.sent.contains(key)) continue;
            Cube cube = cubes.get(key);
            if (cube == null) {
                // Buried rock not made yet goes as a stand-in, replaced by the real cube once it is made.
                if (standIns <= 0 || watcher.stood.contains(key) || near(dx, dz, NEAR) || buried(x, y, z) != 1) continue;
                CubePayloads.CubeData data = new CubePayloads.CubeData(x, y, z, standIn, List.of());
                PacketDistributor.sendToPlayer(watcher.player, data);
                watcher.stood.add(key);
                bytes -= data.size();
                standIns--;
                continue;
            }
            long column = CubeKey.column(x, z);
            if (watcher.columns.add(column)) {
                PacketDistributor.sendToPlayer(watcher.player, new CubePayloads.ColumnTops(x, z, topsFor(x, z).clone()));
            }
            CubePayloads.CubeData data = data(cube);
            PacketDistributor.sendToPlayer(watcher.player, data);
            watcher.sent.add(key);
            watcher.stood.remove(key);
            bytes -= data.size();
            budget--;
        }
    }

    private CubePayloads.CubeData data(Cube cube) {
        List<CompoundTag> entities = new ArrayList<>();
        for (BlockEntity entity : cube.blockEntities.values()) {
            CompoundTag tag = updateTag(entity);
            if (tag != null) entities.add(tag);
        }
        return new CubePayloads.CubeData(cube.x, cube.y, cube.z, CubeCodec.sectionBytes(cube.section), entities);
    }

    private CompoundTag updateTag(BlockEntity entity) {
        // Every block entity goes, as in vanilla chunk packets: chests, barrels and furnaces have no
        // update packet, and without their tag the client has no entity to draw them with.
        if (entity.isRemoved()) return null;
        CompoundTag tag = entity.getUpdateTag(server.registryAccess());
        BlockPos pos = entity.getBlockPos();
        tag.putInt("x", pos.getX()); tag.putInt("y", pos.getY()); tag.putInt("z", pos.getZ());
        return tag;
    }

    private void unsend(Watcher watcher, int h, int v, int cx, int cy, int cz) {
        LongArrayList dropped = new LongArrayList();
        for (LongIterator it = watcher.sent.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (!CubeOffsets.within(CubeKey.x(key) - cx, CubeKey.y(key) - cy, CubeKey.z(key) - cz, h, v)) { dropped.add(key); it.remove(); }
        }
        for (LongIterator it = watcher.stood.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (!CubeOffsets.within(CubeKey.x(key) - cx, CubeKey.y(key) - cy, CubeKey.z(key) - cz, h, v)) { dropped.add(key); it.remove(); }
        }
        for (LongIterator it = watcher.columns.iterator(); it.hasNext(); ) {
            long column = it.nextLong();
            int dx = CubeKey.columnX(column) - cx, dz = CubeKey.columnZ(column) - cz;
            if (dx * dx + dz * dz > (h + 1) * (h + 1)) it.remove();
        }
        if (!dropped.isEmpty()) PacketDistributor.sendToPlayer(watcher.player, new CubePayloads.CubeUnload(dropped.toLongArray()));
    }

    private void flushChanges() {
        if (!changed.isEmpty()) {
            for (BlockPos pos : changed) {
                Cube cube = cubes.get(CubeKey.ofBlock(pos));
                if (cube == null) continue;
                BlockState state = cube.get(pos.getX(), pos.getY(), pos.getZ());
                BlockEntity entity = state.hasBlockEntity() ? cube.blockEntities.get(pos) : null;
                CompoundTag tag = entity == null ? null : updateTag(entity);
                CubePayloads.Change change = new CubePayloads.Change(pos.getX(), pos.getY(), pos.getZ(), Block.getId(state), tag);
                for (Watcher watcher : watchers.values()) if (watcher.sent.contains(cube.key)) watcher.changes.add(change);
            }
            changed.clear();
            for (Watcher watcher : watchers.values()) {
                if (watcher.changes.isEmpty()) continue;
                if (Cubic.TRACE) EarthPlatformMod.LOGGER.info("cubic: sending {} block changes to {}", watcher.changes.size(), watcher.player.getScoreboardName());
                PacketDistributor.sendToPlayer(watcher.player, new CubePayloads.BlockChanges(List.copyOf(watcher.changes)));
                watcher.changes.clear();
            }
        }
        if (!changedColumns.isEmpty()) {
            for (LongIterator it = changedColumns.iterator(); it.hasNext(); ) {
                long column = it.nextLong();
                int[] values = tops.get(column);
                if (values == null) continue;
                CubePayloads.ColumnTops payload = new CubePayloads.ColumnTops(CubeKey.columnX(column), CubeKey.columnZ(column), values.clone());
                for (Watcher watcher : watchers.values()) if (watcher.columns.contains(column)) PacketDistributor.sendToPlayer(watcher.player, payload);
            }
            changedColumns.clear();
        }
    }

    private boolean watched(long key, int h, int v) { return watched(key, h, v, false); }

    /** {@code edited} cubes stay while near anyone, buried or not, and once saved are never stood in for. */
    private boolean watched(long key, int h, int v, boolean edited) {
        int x = CubeKey.x(key), y = CubeKey.y(key), z = CubeKey.z(key);
        for (Watcher watcher : watchers.values()) {
            int dx = x - (watcher.player.getBlockX() >> 4), dz = z - (watcher.player.getBlockZ() >> 4);
            if (!CubeOffsets.within(dx, y - (watcher.player.getBlockY() >> 4), dz, h, v)) continue;
            // Buried rock a surface player has walked away from goes, a little past where it would be made.
            if (watcher.deep || edited || near(dx, dz, NEAR + 2) || buried(x, y, z) != 1) return true;
        }
        return false;
    }

    private boolean columnWatched(long column, int h) {
        int x = CubeKey.columnX(column), z = CubeKey.columnZ(column);
        for (Watcher watcher : watchers.values()) {
            if (CubeOffsets.within(x - (watcher.player.getBlockX() >> 4), 0, z - (watcher.player.getBlockZ() >> 4), h, 0)) return true;
        }
        return false;
    }

    private void unloadUnwatched(int h, int v) {
        long start = System.nanoTime();
        try { unload(h, v); }
        finally { Timings.CUBE_UNLOAD.since(start); }
    }

    private void unload(int h, int v) {
        List<Cube> drop = new ArrayList<>();
        for (Cube cube : cubes.values()) if (!watched(cube.key, h, v, cube.edited)) drop.add(cube);
        if (drop.isEmpty()) return;
        for (Cube cube : drop) {
            store(cube);
            forget(cube);
            ((io.earthplatform.minecraft.cubic.CubicPoiStore) server.getPoiManager()).earthplatform$forget(SectionPos.asLong(cube.x, cube.y, cube.z));
        }
        // Keep sky tops near players only; far columns are rebuilt from the generator and cubes.
        tops.long2ObjectEntrySet().removeIf(entry -> !columnWatched(entry.getLongKey(), h * 3));
        lows.long2IntEntrySet().removeIf(entry -> !columnWatched(entry.getLongKey(), h * 3));
        kept.removeIf(key -> !lows.containsKey(CubeKey.column(CubeKey.x(key), CubeKey.z(key))) && !lowsPending.contains(CubeKey.column(CubeKey.x(key), CubeKey.z(key))));
        opened.removeIf(key -> !lows.containsKey(CubeKey.column(CubeKey.x(key), CubeKey.z(key))));
        long start = System.nanoTime();
        storage.flushLater();
        Timings.CUBE_FLUSH.since(start);
    }

    private void randomTicks(int v) {
        int speed = server.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        if (speed <= 0 || watchers.isEmpty()) return;
        RandomSource random = server.getRandom();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Cube cube : cubes.values()) {
            if (!cube.section.isRandomlyTicking() || !server.shouldTickBlocksAt(ChunkPos.asLong(cube.x, cube.z))) continue;
            if (!watchedVertically(cube.y, v)) continue;
            int x0 = cube.x << 4, y0 = cube.y << 4, z0 = cube.z << 4;
            for (int i = 0; i < speed; i++) {
                int bx = random.nextInt(16), by = random.nextInt(16), bz = random.nextInt(16);
                BlockState state = cube.section.getBlockState(bx, by, bz);
                pos.set(x0 + bx, y0 + by, z0 + bz);
                if (state.isRandomlyTicking()) state.randomTick(server, pos.immutable(), random);
                FluidState fluid = state.getFluidState();
                if (fluid.isRandomlyTicking()) fluid.randomTick(server, pos.immutable(), random);
            }
        }
    }

    private boolean watchedVertically(int cubeY, int v) {
        for (Watcher watcher : watchers.values()) if (Math.abs(cubeY - (watcher.player.getBlockY() >> 4)) <= v) return true;
        return false;
    }

    // Spawning -----------------------------------------------------------------------------------

    private static final int SPAWN_BAND = 64;

    /**
     * A natural spawn attempt in a carrier chunk: a random column, at a random height within the
     * band around the nearest player and no higher than just above the column's sky top, like
     * vanilla's pick below the surface. Null while that spot's cube or light is not ready.
     */
    @Nullable public BlockPos spawnCandidate(ChunkPos chunk, RandomSource random) {
        int x = chunk.getMinBlockX() + random.nextInt(16), z = chunk.getMinBlockZ() + random.nextInt(16);
        ServerPlayer nearest = null;
        double best = Double.MAX_VALUE;
        for (Watcher watcher : watchers.values()) {
            if (watcher.player.isSpectator()) continue;
            double dx = watcher.player.getX() - x, dz = watcher.player.getZ() - z, distance = dx * dx + dz * dz;
            if (distance < best) { best = distance; nearest = watcher.player; }
        }
        if (nearest == null) return null;
        int low = nearest.getBlockY() - SPAWN_BAND, high = Math.min(nearest.getBlockY() + SPAWN_BAND, skyTop(x, z) + 1);
        if (high < low) return null;
        BlockPos pos = new BlockPos(x, low + random.nextInt(high - low + 1), z);
        return cubes.containsKey(CubeKey.ofBlock(pos)) && lightReady(pos) ? pos : null;
    }

    /** Gives fresh surface cubes their first animals once the cube above them has loaded, as vanilla does at generation. */
    private void populate() {
        placeEntities();
        if (unpopulated.isEmpty()) return;
        for (LongIterator it = unpopulated.iterator(); it.hasNext(); ) {
            Cube cube = cubes.get(it.nextLong());
            if (cube == null) { it.remove(); continue; }
            ChunkPos chunk = new ChunkPos(cube.x, cube.z);
            if (!cubes.containsKey(CubeKey.pack(cube.x, cube.y + 1, cube.z)) || !server.isNaturalSpawningAllowed(chunk)) continue;
            it.remove();
            int x = chunk.getMiddleBlockX(), z = chunk.getMiddleBlockZ();
            NaturalSpawner.spawnMobsForChunkGeneration(server, server.getBiome(new BlockPos(x, skyTop(x, z) + 1, z)), chunk, server.getRandom());
            // Saved again, so a later visit does not populate it again.
            cube.markUnsaved();
        }
    }

    /** Adds the entities fresh cubes start with, once their chunk ticks entities, and keeps those cubes saved from then on. */
    private void placeEntities() {
        if (unplacedEntities.isEmpty()) return;
        for (long key : unplacedEntities.keySet().toLongArray()) {
            Cube cube = cubes.get(key);
            if (cube == null) { unplacedEntities.remove(key); continue; }
            if (!server.isNaturalSpawningAllowed(new ChunkPos(cube.x, cube.z))) continue;
            for (CompoundTag tag : unplacedEntities.remove(key)) {
                Entity entity = EntityType.loadEntityRecursive(tag, server, e -> e);
                if (entity != null) server.addFreshEntity(entity);
            }
            cube.markUnsaved();
        }
    }

    // Light --------------------------------------------------------------------------------------

    /** Lit cubes answer from their light; others answer from the sky-top model and get queued. */
    @Override public int getBrightness(LightLayer layer, BlockPos pos) {
        long key = CubeKey.ofBlock(pos);
        Lit values = light.get(key);
        if ((values == null || values.stamp != lightStamps.get(key)) && cubes.containsKey(key) && !lightPending.contains(key)) lightWanted.add(key);
        if (values == null) return super.getBrightness(layer, pos);
        int packed = values.values[(pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15)];
        return layer == LightLayer.SKY ? packed >> 4 & 15 : packed & 15;
    }

    /** True when the cube's light reflects its current neighbourhood; asks for it otherwise. */
    public boolean lightReady(BlockPos pos) {
        long key = CubeKey.ofBlock(pos);
        Lit values = light.get(key);
        if (values != null && values.stamp == lightStamps.get(key)) return true;
        if (cubes.containsKey(key) && !lightPending.contains(key)) lightWanted.add(key);
        return false;
    }

    private void staleLight(long key) {
        if (light.containsKey(key) || lightPending.contains(key)) lightStamps.addTo(key, 1);
    }

    private void staleLightAround(int cx, int cy, int cz) {
        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) staleLight(CubeKey.pack(cx + dx, cy + dy, cz + dz));
    }

    @SuppressWarnings("unchecked")
    private void lightCubes() {
        for (Lit result; (result = lit.poll()) != null; ) {
            lightPending.remove(result.key);
            if (result.values != null && cubes.containsKey(result.key)) light.put(result.key, result);
        }
        for (int budget = LIGHT_PER_TICK; budget > 0 && !lightWanted.isEmpty(); ) {
            long key = lightWanted.removeFirstLong();
            Cube cube = cubes.get(key);
            if (cube == null || lightPending.contains(key)) continue;
            PalettedContainer<BlockState>[] states = new PalettedContainer[27];
            for (int c = 0; c < 27; c++) {
                Cube near = cubes.get(CubeKey.pack(cube.x + c % 3 - 1, cube.y + c / 9 - 1, cube.z + c / 3 % 3 - 1));
                states[c] = near == null ? null : near.section.getStates().copy();
            }
            int[][] columns = new int[9][];
            for (int c = 0; c < 9; c++) columns[c] = topsFor(cube.x + c % 3 - 1, cube.z + c / 3 - 1).clone();
            int stamp = lightStamps.get(key), cy = cube.y;
            lightPending.add(key);
            workers.execute(() -> {
                byte[] values = null;
                try { values = CubeLight.centre(CubeLight.light(states, columns, cy)); }
                catch (RuntimeException failure) { EarthPlatformMod.LOGGER.error("Could not light cube {}", CubeKey.toString(key), failure); }
                finally { lit.add(new Lit(key, stamp, values)); }
            });
            budget--;
        }
    }

    // Saving -------------------------------------------------------------------------------------

    private void store(Cube cube) { store(cube, false); }

    /** {@code now} stores even an untouched cube on this thread, as when the world closes. */
    private void store(Cube cube, boolean now) {
        if (!cube.modified || !cube.dirty) return;
        if (!cube.edited && !now) { storeUntouched(cube); return; }
        try {
            CompoundTag tag = codec.write(cube, server.registryAccess());
            // Owed first animals or starting entities stay owed to the next visit.
            if (unpopulated.contains(cube.key)) tag.putBoolean(CubeCodec.UNPOPULATED, true);
            List<CompoundTag> owed = unplacedEntities.get(cube.key);
            if (owed != null && !owed.isEmpty()) {
                net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
                list.addAll(owed);
                tag.put(CubeCodec.ENTITIES, list);
            }
            storage.put(cube.key, tag);
            cube.dirty = false;
            // Edited rock now comes back from the save, not the generator, so it is never stood in for.
            if (buried(cube.x, cube.y, cube.z) != 0) kept.add(cube.key);
            // Saves that only record block entities or spawning leave far terrain as it was.
            if (cube.reshaped) {
                cube.reshaped = false;
                far.edited(cube.x, cube.z);
            }
        } catch (IOException | RuntimeException failure) {
            EarthPlatformMod.LOGGER.error("Could not store cube {}", CubeKey.toString(cube.key), failure);
        }
    }

    /**
     * Stores a cube saved just as generated, encoding it on a worker: a traveler leaves many behind,
     * and each would otherwise cost the server thread. Should it be edited or a newer copy loaded before
     * the store lands, that is saved in its turn; one lost (the server stopping) is generated again, the
     * same, when next asked for.
     */
    private void storeUntouched(Cube cube) {
        cube.dirty = false;
        long key = cube.key;
        int x = cube.x, y = cube.y, z = cube.z;
        LevelChunkSection section = new LevelChunkSection(cube.section.getStates().copy(), cube.section.getBiomes());
        List<CompoundTag> blockEntities = new ArrayList<>();
        for (BlockEntity entity : cube.blockEntities.values()) {
            if (!entity.isRemoved()) blockEntities.add(entity.saveWithFullMetadata(server.registryAccess()));
        }
        boolean owedAnimals = unpopulated.contains(key);
        List<CompoundTag> owed = unplacedEntities.get(key);
        List<CompoundTag> entities = owed == null ? List.of() : List.copyOf(owed);
        workers.execute(() -> {
            CompoundTag tag;
            try {
                tag = codec.writeGenerated(x, y, z, section, blockEntities);
                if (owedAnimals) tag.putBoolean(CubeCodec.UNPOPULATED, true);
                if (!entities.isEmpty()) {
                    net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
                    list.addAll(entities);
                    tag.put(CubeCodec.ENTITIES, list);
                }
            } catch (RuntimeException failure) {
                EarthPlatformMod.LOGGER.error("Could not store cube {}", CubeKey.toString(key), failure);
                return;
            }
            server.getServer().execute(() -> {
                // A copy loaded since, or this one edited since, is stored from its own state instead.
                Cube now = cubes.get(key);
                if (closed || cube.edited || now != null && now != cube) return;
                try { storage.put(key, tag); }
                catch (IOException failure) { EarthPlatformMod.LOGGER.error("Could not store cube {}", CubeKey.toString(key), failure); }
            });
        });
    }

    private void flushStorage() {
        try { storage.flush(); }
        catch (IOException failure) { EarthPlatformMod.LOGGER.error("Could not write cube regions", failure); }
    }

    public void save() {
        for (Cube cube : cubes.values()) store(cube);
        flushStorage();
    }

    public void close() {
        if (closed) return;
        for (Cube cube : cubes.values()) store(cube, true);
        try { storage.close(); }
        catch (IOException failure) { EarthPlatformMod.LOGGER.error("Could not write cube regions", failure); }
        closed = true;
        workers.shutdownNow();
        far.close();
        for (Cube cube : new ArrayList<>(cubes.values())) forget(cube);
    }

    public String describe() {
        int standing = 0;
        for (Watcher watcher : watchers.values()) standing += watcher.stood.size();
        return generator.id() + ", " + cubes.size() + " cubes loaded, " + standing + " buried cubes stood in for, " + opened.size() + " opened by caves, " + pending.size() + " pending (" + generated + " made, " + dropped + " dropped as no longer wanted), " + light.size() + " lit, "
                + watchers.size() + " watching, " + far.cached() + " far columns cached" + (failures > 0 ? ", " + failures + " generation failures" : "");
    }

    /** Iterates loaded cubes; exposed for diagnostics. */
    public Iterator<Cube> loaded() { return cubes.values().iterator(); }
}
