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

    /** A loaded or generated cube; a null section means generating it failed. {@code surface} marks fresh cubes holding the ground. */
    private record Ready(long key, int x, int y, int z, @Nullable LevelChunkSection section, List<CompoundTag> blockEntities,
                         List<CompoundTag> entities, boolean saved, boolean surface, @Nullable RuntimeException failure) {}
    private record Lit(long key, int stamp, @Nullable byte[] values) {}

    private static final class Watcher {
        final ServerPlayer player;
        final LongOpenHashSet sent = new LongOpenHashSet();
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
                return new Ready(key, x, y, z, decoded.section(), decoded.blockEntities(), List.of(), true, false, null);
            }
        } catch (IOException | RuntimeException failure) {
            EarthPlatformMod.LOGGER.error("Could not read saved cube {}; regenerating it", CubeKey.toString(key), failure);
        }
        try {
            List<CompoundTag> blockEntities = new ArrayList<>(), entities = new ArrayList<>();
            LevelChunkSection section = generator.generate(x, y, z, blockEntities, entities);
            boolean surface = generator.surfaceY((x << 4) + 8, (z << 4) + 8) >> 4 == y;
            return new Ready(key, x, y, z, section, blockEntities, entities, false, surface, null);
        } catch (RuntimeException failure) {
            return new Ready(key, x, y, z, null, List.of(), List.of(), false, false, failure);
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
        cube.modified = result.saved;
        if (result.surface) unpopulated.add(result.key);
        if (!result.entities.isEmpty()) unplacedEntities.put(result.key, result.entities);
        for (CompoundTag tag : result.blockEntities) {
            BlockPos pos = BlockEntity.getPosFromTag(tag);
            BlockEntity entity = BlockEntity.loadStatic(pos, cube.get(pos.getX(), pos.getY(), pos.getZ()), tag, server.registryAccess());
            if (entity != null) cube.blockEntities.put(pos, entity);
        }
        adopt(cube);
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
            requestAround(offsets, cx, cy, cz, share);
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

    private void requestAround(CubeOffsets offsets, int cx, int cy, int cz, int limit) {
        for (int i = 0; i < offsets.size() && limit > 0 && pending.size() < MAX_PENDING; i++) {
            long key = CubeKey.pack(cx + offsets.dx(i), cy + offsets.dy(i), cz + offsets.dz(i));
            if (request(key, false)) limit--;
        }
    }

    private void send(Watcher watcher, CubeOffsets offsets, int cx, int cy, int cz) {
        int budget = SEND_CUBES_PER_TICK, bytes = Throttle.allowance(watcher.player);
        for (int i = 0; i < offsets.size() && budget > 0 && bytes > 0; i++) {
            int x = cx + offsets.dx(i), y = cy + offsets.dy(i), z = cz + offsets.dz(i);
            long key = CubeKey.pack(x, y, z);
            if (watcher.sent.contains(key)) continue;
            Cube cube = cubes.get(key);
            if (cube == null) continue;
            long column = CubeKey.column(x, z);
            if (watcher.columns.add(column)) {
                PacketDistributor.sendToPlayer(watcher.player, new CubePayloads.ColumnTops(x, z, topsFor(x, z).clone()));
            }
            CubePayloads.CubeData data = data(cube);
            PacketDistributor.sendToPlayer(watcher.player, data);
            watcher.sent.add(key);
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

    private boolean watched(long key, int h, int v) {
        int x = CubeKey.x(key), y = CubeKey.y(key), z = CubeKey.z(key);
        for (Watcher watcher : watchers.values()) {
            if (CubeOffsets.within(x - (watcher.player.getBlockX() >> 4), y - (watcher.player.getBlockY() >> 4),
                    z - (watcher.player.getBlockZ() >> 4), h, v)) return true;
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
        for (Cube cube : cubes.values()) if (!watched(cube.key, h, v)) drop.add(cube);
        if (drop.isEmpty()) return;
        for (Cube cube : drop) {
            store(cube);
            forget(cube);
            ((io.earthplatform.minecraft.cubic.CubicPoiStore) server.getPoiManager()).earthplatform$forget(SectionPos.asLong(cube.x, cube.y, cube.z));
        }
        // Keep sky tops near players only; far columns are rebuilt from the generator and cubes.
        tops.long2ObjectEntrySet().removeIf(entry -> !columnWatched(entry.getLongKey(), h * 3));
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
            // Saved from now on, so a later visit does not populate it again.
            cube.markModified();
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
            cube.markModified();
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

    private void store(Cube cube) {
        if (!cube.modified || !cube.dirty) return;
        try {
            storage.put(cube.key, codec.write(cube, server.registryAccess()));
            cube.dirty = false;
            // Saves that only record block entities or spawning leave far terrain as it was.
            if (cube.reshaped) {
                cube.reshaped = false;
                far.edited(cube.x, cube.z);
            }
        } catch (IOException | RuntimeException failure) {
            EarthPlatformMod.LOGGER.error("Could not store cube {}", CubeKey.toString(cube.key), failure);
        }
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
        for (Cube cube : cubes.values()) store(cube);
        try { storage.close(); }
        catch (IOException failure) { EarthPlatformMod.LOGGER.error("Could not write cube regions", failure); }
        closed = true;
        workers.shutdownNow();
        far.close();
        for (Cube cube : new ArrayList<>(cubes.values())) forget(cube);
    }

    public String describe() {
        return generator.id() + ", " + cubes.size() + " cubes loaded, " + pending.size() + " pending (" + generated + " made, " + dropped + " dropped as no longer wanted), " + light.size() + " lit, "
                + watchers.size() + " watching, " + far.cached() + " far columns cached" + (failures > 0 ? ", " + failures + " generation failures" : "");
    }

    /** Iterates loaded cubes; exposed for diagnostics. */
    public Iterator<Cube> loaded() { return cubes.values().iterator(); }
}
