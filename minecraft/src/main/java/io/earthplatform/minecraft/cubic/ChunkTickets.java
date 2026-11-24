package io.earthplatform.minecraft.cubic;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.platform.Platform;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

/**
 * Server thread. Chunk tickets in the Earth. A vanilla ticket keeps a carrier chunk loaded and
 * ticking, but the Earth's blocks live in cubes, which the unload pass drops once no player is near;
 * so a {@code /forceload}ed farm, a portal's far side or a mod's chunk loader kept an empty carrier.
 * This turns the tickets that keep chunks loaded on purpose into cube holds ({@link ResidentCubes}),
 * at the height each one means:
 *
 * <ul>
 *   <li><b>Forced chunks</b> ({@code /forceload}, and any {@code setChunkForced}): the chunk and the
 *       ring round it that vanilla block-ticks, {@link Cubic#VERTICAL_RADIUS} cubes up and down from
 *       the height the command was run from ({@link ForcedHeights}), or from the ground where there
 *       is none (the console, a mod).</li>
 *   <li><b>Portal tickets</b> (an entity through a Nether or End portal): the 5x5 columns vanilla
 *       block-ticks round the exit, two cubes up and down from it, kept a loop's length after the
 *       ticket lapses, below every live ticket.</li>
 *   <li><b>NeoForge forced chunks</b> ({@code TicketController}, what chunk-loader blocks and
 *       entities use): the chunk and its ring, round the owner block's or entity's height.</li>
 * </ul>
 *
 * <p>The tickets are read every {@link #EVERY} ticks, and only what changed is worked on. The cap ({@code chunkLoaderCubeLimit}) is shared out fairly: owners (the player who ran a
 * {@code /forceload}, a loader block or entity, a portal exit) take turns in the order they first came,
 * and each owner's areas in the order they came, so a hold keeps its cubes while new ones queue behind
 * it, an owner past its share gives way to one under it, and lapsed portal exits come last. Loaders
 * past the cap cost nothing: no ground read, no box. Missing cubes, and the ground of chunks forced with
 * no height, are asked of the workers a few a tick, never made here.
 */
public final class ChunkTickets {
    /** Ticks between looks at the tickets. */
    static final int EVERY = 20;
    /** Cubes asked of the workers a tick for holds, at most, beside players' own; and missing cubes looked at a tick. */
    static final int REQUESTS_PER_TICK = 32, SCANS_PER_TICK = 128;
    /** Ground reads started a tick, and running at once, for chunks forced with no height. */
    static final int GROUND_PER_TICK = 2, GROUND_IN_FLIGHT = 4;
    /** Ticks between sweeps for held cubes that are not loaded (a generation that failed, say), to ask again. */
    static final int SWEEP = 200;
    /** Forced and mod chunks: their ring (vanilla block-ticks it), and cubes up and down: what a player gets. */
    static final int FORCED_REACH = 1, FORCED_V = Cubic.VERTICAL_RADIUS;
    /** Portal exits: the columns vanilla block-ticks round a portal ticket, and cubes up and down from the exit. */
    static final int PORTAL_REACH = 2, PORTAL_V = 2;
    /** Ticks a portal's hold outlasts its ticket: an item loop's trip back, so its cubes stay in between. */
    static final int PORTAL_LINGER = 600;
    /** A height in {@link Ask} meaning the column's ground. */
    static final int GROUND = Integer.MIN_VALUE;
    /** Ticks before a column whose ground could not be had is asked again. */
    static final int GROUND_RETRY = 200;
    /**
     * Cubes round a held cube that must be loaded for it to random-tick: across, below and above. A tree
     * grows up to 30 blocks up and several across; into a cube not loaded, it would read air and lose the
     * blocks it wrote there, leaving a stump.
     */
    static final int GROW_ACROSS = 1, GROW_DOWN = 1, GROW_UP = 2;
    /** Asks in a row that don't fit after which planning stops: the rest would not fit either. */
    static final int MAX_MISSES = 64;
    /** New holds made a look at most; more wait for the next look. */
    static final int NEW_PER_PASS = 16;


    public enum Kind { FORCED, MOD, PORTAL }

    /** What holds a set of cubes: a kind, the chunk its ticket is on and what in it (a height, an owner, a portal). */
    record Hold(Kind kind, long chunk, Object at) {
        /** Mixed: a record's own hash of a packed chunk is x ^ z, so a forced area fell in a few buckets of every map keyed by holds. */
        @Override public int hashCode() { return (int) it.unimi.dsi.fastutil.HashCommon.mix(chunk * 31 + kind.ordinal()) ^ at.hashCode() * 0x9E3779B9; }
    }

    /**
     * A ticket's wish: the columns {@code reach} round a chunk, {@code v} cubes up and down from a block {@code y}, or the
     * ground; {@code owner} is who shares the cap with others.
     */
    record Ask(Hold hold, Object owner, int chunkX, int chunkZ, int reach, int y, int v) {}

    /** Where the cubes a hold needs come from and go. */
    interface Host {
        boolean loaded(long cubeKey);
        /** Queues a cube for the workers; false when it can't be queued now. */
        boolean request(long cubeKey);
        void keep(Object holder, long[] keys);
        void release(Object holder);
        /** Any thread but the server's: the ground's block Y at a block column; throws while its map data is missing. */
        int groundY(int x, int z);
        /** Tells operators (and the log) a line. */
        void tell(Component line);
    }

    private final Host host;
    /** Where ground reads run: the cube workers, a fixed pool. */
    private final Executor pool;

    /** One ask as known here: where it stands in the queue, whether it is held and with which cubes. */
    private static final class Entry {
        Ask ask;
        final int seq;
        boolean held, waiting, lapsed;
        /** From the forced list, or the portal and mod list. */
        boolean forced;
        int cubeY, seen;
        long[] keys, inner;
        Entry(Ask ask, int seq) { this.ask = ask; this.seq = seq; }
    }

    /** Every live ask, and lapsed portal exits still kept, in the order first seen: who came first keeps its place. */
    private final LinkedHashMap<Hold, Entry> known = new LinkedHashMap<>();
    /** Owners in the order first seen; they take turns in this order. */
    private final LinkedHashMap<Object, Integer> owners = new LinkedHashMap<>();
    private final Set<Hold> lapsed = new java.util.LinkedHashSet<>();
    /** Cubes held per owner (lapsed exits aside), and each owner's held areas by when they came. */
    private final Map<Object, Integer> ownerCubes = new HashMap<>();
    private final Map<Object, TreeMap<Integer, Entry>> heldByOwner = new HashMap<>();
    private int nextSeq, holds;
    /** Holds per cube, and per cube that may random-tick (see {@link #GROW_UP}). */
    private final Long2IntOpenHashMap cubeRefs = new Long2IntOpenHashMap(), interiorRefs = new Long2IntOpenHashMap();
    /** Held cubes per cube column. */
    private final Long2IntOpenHashMap columnRefs = new Long2IntOpenHashMap();

    /** Ground cube Y per chunk column, for forced chunks without a height; read off this thread. */
    private final Long2IntOpenHashMap ground = new Long2IntOpenHashMap();
    private final LongOpenHashSet asking = new LongOpenHashSet();
    private final LongLinkedOpenHashSet groundWanted = new LongLinkedOpenHashSet();
    private final Long2IntOpenHashMap groundFailedAt = new Long2IntOpenHashMap();
    private final ConcurrentLinkedQueue<long[]> grounds = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private int groundReads;
    /** The tick a failed ground read is due to be tried again, which calls for planning again. */
    private int retryDue = Integer.MAX_VALUE;

    /** Entity owners' last known block Y, for when they are not loaded. */
    private final Map<UUID, Integer> entityY = new HashMap<>();
    /** Held cubes not loaded yet, asked of the workers a few a tick. */
    private final LongLinkedOpenHashSet missing = new LongLinkedOpenHashSet();

    // What the last look saw, so an unchanged set of tickets costs next to nothing.
    private List<Ask> lastForced = List.of(), lastOthers = List.of();
    private int lastCap = -1, leftOut, plans, tick;
    /** New holds left for the next look, past {@link #NEW_PER_PASS}. */
    private boolean pending;

    private boolean closed;

    /** Forced chunks as last seen, to tell a chunk unforced since from one never seen; null before the first look. */
    @Nullable private LongOpenHashSet seenForced;
    /** The forced asks as last built, and what they were built from, so an unchanged save is not read again. */
    private List<Ask> forcedAsks = List.of();
    private long forcedSignature = Long.MIN_VALUE;
    private int heightsVersion = -1;

    ChunkTickets(Host host, Executor pool) {
        this.host = host;
        this.pool = pool;
    }

    // Planning ------------------------------------------------------------------------------------

    /** The cube keys of a box: the columns {@code reach} round a chunk, {@code v} up and down from cube {@code cubeY}, inside the world. */
    static long[] box(int chunkX, int chunkZ, int reach, int cubeY, int v) {
        int low = Math.max(CubeWorld.MIN_Y >> 4, cubeY - v), high = Math.min(CubeWorld.MAX_Y >> 4, cubeY + v);
        if (high < low) return new long[0];
        int side = 2 * reach + 1;
        long[] keys = new long[side * side * (high - low + 1)];
        int i = 0;
        for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++)
            for (int y = low; y <= high; y++) keys[i++] = CubeKey.pack(chunkX + dx, y, chunkZ + dz);
        return keys;
    }

    /** The cubes of a box that may random-tick: those with {@link #GROW_ACROSS}, {@link #GROW_DOWN} and {@link #GROW_UP} of the box round them. */
    static long[] interior(int chunkX, int chunkZ, int reach, int cubeY, int v) {
        int low = Math.max(CubeWorld.MIN_Y >> 4, cubeY - v) + GROW_DOWN, high = Math.min(CubeWorld.MAX_Y >> 4, cubeY + v) - GROW_UP;
        int across = reach - GROW_ACROSS;
        if (high < low || across < 0) return new long[0];
        int side = 2 * across + 1;
        long[] keys = new long[side * side * (high - low + 1)];
        int i = 0;
        for (int dx = -across; dx <= across; dx++) for (int dz = -across; dz <= across; dz++)
            for (int y = low; y <= high; y++) keys[i++] = CubeKey.pack(chunkX + dx, y, chunkZ + dz);
        return keys;
    }

    /** Cubes a box holds at most, before the world's top and bottom cut it. */
    static int nominal(Ask ask) {
        int side = 2 * ask.reach() + 1;
        return side * side * (2 * ask.v() + 1);
    }

    /** For tests: all asks in one list. */
    void reconcile(int tick, List<Ask> asks, int cap) { reconcile(tick, List.of(), asks, cap); }

    /**
     * Looks at the tickets' asks as they stand at {@code tick}: {@code forced} is the same list as last
     * time while the forced chunks are unchanged, {@code others} the portal and mod asks. When nothing
     * changed this costs next to nothing; otherwise only what changed is worked on: a removed ask lets go
     * at once, and asks not held yet are placed in what room there is, a few a look, taking room fairly
     * (see {@link #plan}).
     */
    void reconcile(int tick, List<Ask> forced, List<Ask> others, int cap) {
        if (closed) return;
        this.tick = tick;
        boolean changed = drainGrounds() || cap != lastCap;
        if (tick >= retryDue) { changed = true; retryDue = Integer.MAX_VALUE; }
        lastCap = cap;
        if (forced != lastForced) { changed |= diff(forced, Kind.FORCED); lastForced = forced; }
        if (!others.equals(lastOthers)) { changed |= diff(others, null); lastOthers = List.copyOf(others); }
        for (Ask ask : others) if (ask.hold().kind() == Kind.PORTAL) known.get(ask.hold()).seen = tick;
        for (Iterator<Hold> it = lapsed.iterator(); it.hasNext(); ) {
            Entry entry = known.get(it.next());
            if (tick - entry.seen <= PORTAL_LINGER) continue;
            it.remove();
            if (entry.held) drop(entry);
            known.remove(entry.ask.hold());
            changed = true;
        }
        if (changed || pending) plan(Math.max(0, cap));
    }

    /**
     * Brings {@link #known} up to one list of asks: the forced ones ({@code kind} FORCED), or the portal and
     * mod ones (null). True when anything changed.
     */
    private boolean diff(List<Ask> asks, @Nullable Kind kind) {
        boolean changed = false;
        Set<Hold> now = new HashSet<>(2 * asks.size() + 2);
        for (Ask ask : asks) {
            now.add(ask.hold());
            if (owners.putIfAbsent(ask.owner(), nextSeq) == null) nextSeq++;
            Entry entry = known.get(ask.hold());
            if (entry == null) {
                Entry fresh = new Entry(ask, nextSeq++);
                fresh.forced = kind == Kind.FORCED;
                known.put(ask.hold(), fresh);
                changed = true;
                continue;
            }
            if (!entry.ask.equals(ask)) {
                if (entry.held) drop(entry);
                entry.ask = ask;
                changed = true;
            }
            if (entry.lapsed) { entry.lapsed = false; lapsed.remove(ask.hold()); changed = true; }
        }
        for (Iterator<Entry> it = known.values().iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (entry.forced != (kind == Kind.FORCED) || entry.lapsed || now.contains(entry.ask.hold())) continue;
            if (entry.ask.hold().kind() == Kind.PORTAL) {
                // An exit whose ticket lapsed stays a while, giving way to every live loader.
                entry.lapsed = true;
                lapsed.add(entry.ask.hold());
            } else {
                if (entry.held) drop(entry);
                it.remove();
            }
            changed = true;
        }
        return changed;
    }

    /**
     * Shares the cap out. Holds keep their cubes; asks not held yet are placed in what is left, owners
     * taking turns in the order they came and each owner's areas in the order they came, at most
     * {@link #NEW_PER_PASS} a look. An ask that doesn't fit takes the room of a lapsed portal exit, else of
     * the newest area of the owner holding the most, while that owner would still hold at least as much
     * as this one's after; so a new owner gets a fair share, and nobody's share swings back and forth. A
     * lower cap lets go of lapsed exits, then of the newest areas of whoever holds the most.
     */
    private void plan(int cap) {
        plans++;
        pending = false;
        for (Entry victim; cubeRefs.size() > cap && (victim = victim(null, 0)) != null; ) drop(victim);
        Map<Object, ArrayDeque<Entry>> byOwner = new HashMap<>();
        for (Entry entry : known.values()) {
            if (entry.lapsed || entry.held) continue;
            byOwner.computeIfAbsent(entry.ask.owner(), owner -> new ArrayDeque<>()).add(entry);
        }
        List<ArrayDeque<Entry>> turns = new ArrayList<>(byOwner.size());
        for (Object owner : owners.keySet()) {
            ArrayDeque<Entry> turn = byOwner.get(owner);
            if (turn != null) turns.add(turn);
        }
        int[] room = {0, 0}; // reserved for grounds being read, new holds placed
        int misses = 0;
        while (!turns.isEmpty() && misses < MAX_MISSES) {
            for (Iterator<ArrayDeque<Entry>> it = turns.iterator(); it.hasNext() && misses < MAX_MISSES; ) {
                ArrayDeque<Entry> turn = it.next();
                Entry entry = turn.poll();
                if (turn.isEmpty()) it.remove();
                int placed = place(entry, cap, room);
                if (placed == 1) misses = 0;
                else if (placed == 0 || placed == -2) misses++; // no room: the rest would find none either
            }
        }
        int out = 0, live = 0;
        for (Entry entry : known.values()) {
            if (entry.lapsed) continue;
            live++;
            if (!entry.held && !entry.waiting) out++;
        }
        if (out != leftOut) {
            if (out > 0) host.tell(Component.literal("Earth Platform: " + out + " of " + live + " chunk loaders here are past chunkLoaderCubeLimit (" + cap
                    + " cubes, in earthplatform-common.toml) and keep only their vanilla chunk, until some are removed or the limit is raised. "
                    + cubeRefs.size() + " cubes are kept.").withStyle(ChatFormatting.GOLD));
            else if (leftOut > 0) host.tell(Component.literal("Earth Platform: every chunk loader's area fits under chunkLoaderCubeLimit again.").withStyle(ChatFormatting.GREEN));
            leftOut = out;
        }
    }

    /**
     * Who gives up room for an area of {@code size} cubes of {@code owner} (null: to bring the holds under a
     * lower cap): a lapsed portal exit, the longest lapsed first; else the newest area of the owner holding
     * the most, if after giving it up that owner still holds at least what {@code owner} would.
     */
    @Nullable private Entry victim(@Nullable Object owner, int size) {
        Entry stalest = null;
        for (Hold hold : lapsed) {
            Entry exit = known.get(hold);
            if (exit.held && (stalest == null || exit.seen < stalest.seen)) stalest = exit;
        }
        if (stalest != null) return stalest;
        Object richest = null;
        int most = -1;
        for (Map.Entry<Object, Integer> share : ownerCubes.entrySet()) {
            if (share.getKey().equals(owner) || share.getValue() <= most) continue;
            most = share.getValue();
            richest = share.getKey();
        }
        if (richest == null) return null;
        TreeMap<Integer, Entry> areas = heldByOwner.get(richest);
        Entry newest = areas.lastEntry().getValue();
        if (owner != null && most - newest.keys.length < ownerCubes.getOrDefault(owner, 0) + size) return null;
        return newest;
    }

    /**
     * Places an entry not held yet: 1 when its cubes fit (taking a fair share of room, see {@link #victim}),
     * 0 when they don't, -1 while its ground is read (keeping room for it meanwhile), -2 when there is no
     * room to read its ground, -3 when this look has placed enough.
     */
    private int place(Entry entry, int cap, int[] room) {
        Ask ask = entry.ask;
        int free = cap - cubeRefs.size() - room[0];
        int cubeY = cubeY(ask, free);
        entry.waiting = false;
        if (cubeY == GROUND) {
            if (free < nominal(ask) && victim(ask.owner(), nominal(ask)) == null) return -2;
            room[0] += nominal(ask);
            entry.waiting = true;
            return -1;
        }
        if (room[1] >= NEW_PER_PASS) { pending = true; entry.waiting = true; room[0] += nominal(ask); return -3; } // placed next look; keeps its room
        long[] keys = box(ask.chunkX(), ask.chunkZ(), ask.reach(), cubeY, ask.v());
        for (int swaps = 0; cubeRefs.size() + room[0] + countFresh(keys) > cap; swaps++) {
            Entry victim = swaps < NEW_PER_PASS ? victim(ask.owner(), keys.length) : null;
            if (victim == null) return 0;
            drop(victim);
        }
        room[1]++;
        entry.cubeY = cubeY;
        hold(entry, keys);
        return 1;
    }

    private int countFresh(long[] keys) {
        int fresh = 0;
        for (long key : keys) if (!cubeRefs.containsKey(key)) fresh++;
        return fresh;
    }

    private void hold(Entry entry, long[] keys) {
        Ask ask = entry.ask;
        host.keep(ask.hold(), keys);
        entry.held = true;
        entry.keys = keys;
        entry.inner = interior(ask.chunkX(), ask.chunkZ(), ask.reach(), entry.cubeY, ask.v());
        for (long key : keys) {
            if (cubeRefs.addTo(key, 1) == 0 && !host.loaded(key)) missing.add(key);
            columnRefs.addTo(CubeKey.column(CubeKey.x(key), CubeKey.z(key)), 1);
        }
        for (long key : entry.inner) interiorRefs.addTo(key, 1);
        if (!entry.lapsed) {
            ownerCubes.merge(ask.owner(), keys.length, Integer::sum);
            heldByOwner.computeIfAbsent(ask.owner(), owner -> new TreeMap<>()).put(entry.seq, entry);
        }
        holds++;
    }

    private void drop(Entry entry) {
        for (long key : entry.keys) {
            if (cubeRefs.addTo(key, -1) <= 1) { cubeRefs.remove(key); missing.remove(key); }
            long column = CubeKey.column(CubeKey.x(key), CubeKey.z(key));
            if (columnRefs.addTo(column, -1) <= 1) columnRefs.remove(column);
        }
        for (long key : entry.inner) if (interiorRefs.addTo(key, -1) <= 1) interiorRefs.remove(key);
        TreeMap<Integer, Entry> areas = heldByOwner.get(entry.ask.owner());
        if (areas != null && areas.remove(entry.seq) != null) {
            if (areas.isEmpty()) heldByOwner.remove(entry.ask.owner());
            if (ownerCubes.merge(entry.ask.owner(), -entry.keys.length, Integer::sum) <= 0) ownerCubes.remove(entry.ask.owner());
        }
        host.release(entry.ask.hold());
        entry.held = false;
        entry.keys = null;
        entry.inner = null;
        holds--;
    }

    /**
     * The cube Y an ask is kept round, or {@link #GROUND} while its ground is unknown. The ground is
     * asked for only when the ask could still fit in the {@code room} left under the cap.
     */
    private int cubeY(Ask ask, int room) {
        if (ask.y() != GROUND) return Math.floorDiv(ask.y(), 16);
        long column = ChunkPos.asLong(ask.chunkX(), ask.chunkZ());
        if (ground.containsKey(column)) return ground.get(column);
        if (room < nominal(ask) || asking.contains(column)) return GROUND;
        if (groundFailedAt.containsKey(column) && tick - groundFailedAt.get(column) < GROUND_RETRY) return GROUND;
        groundFailedAt.remove(column);
        groundWanted.add(column);
        return GROUND;
    }

    /** Grounds read since the last look; true when any came in. */
    private boolean drainGrounds() {
        boolean any = false;
        for (long[] done; (done = grounds.poll()) != null; ) {
            any = true;
            asking.remove(done[0]);
            if (done[1] == Long.MIN_VALUE) {
                groundFailedAt.put(done[0], tick);
                retryDue = Math.min(retryDue, tick + GROUND_RETRY);
            }
            else {
                if (ground.size() > 4096) ground.clear();
                ground.put(done[0], (int) done[1]);
            }
        }
        return any;
    }

    /**
     * Each tick: starts a few ground reads on the workers, and asks them for a few held cubes not loaded
     * yet; {@code room} is how many more cubes they may take now.
     */
    void tick(int now, int room) {
        if (closed) return;
        for (int started = 0; started < GROUND_PER_TICK && inFlight.get() < GROUND_IN_FLIGHT && !groundWanted.isEmpty(); ) {
            long column = groundWanted.removeFirstLong();
            if (ground.containsKey(column) || !asking.add(column)) continue;
            int x = (ChunkPos.getX(column) << 4) + 8, z = (ChunkPos.getZ(column) << 4) + 8;
            started++;
            inFlight.incrementAndGet();
            groundReads++;
            try {
                pool.execute(() -> {
                    long cubeY = Long.MIN_VALUE;
                    try { cubeY = Math.floorDiv(host.groundY(x, z), 16); }
                    catch (RuntimeException missingData) { /* asked again after GROUND_RETRY */ }
                    finally { inFlight.decrementAndGet(); grounds.add(new long[]{column, cubeY}); }
                });
            } catch (java.util.concurrent.RejectedExecutionException closing) {
                inFlight.decrementAndGet();
                asking.remove(column);
            }
        }
        if (now % SWEEP == 0) {
            for (LongIterator it = cubeRefs.keySet().iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                if (!host.loaded(key)) missing.add(key);
            }
        }
        int budget = Math.min(room, REQUESTS_PER_TICK);
        for (int scans = 0; budget > 0 && scans < SCANS_PER_TICK && !missing.isEmpty(); scans++) {
            long key = missing.removeFirstLong();
            if (host.loaded(key)) continue;
            if (host.request(key)) budget--;
            // A cube that can't be queued now (pending, or waiting out a failure) is looked at again at the next sweep.
        }
    }

    /**
     * Whether a cube that only a hold keeps loaded may random-tick: it is inside a hold's box, with the
     * cubes a growth reaches (see {@link #GROW_UP}) round it all loaded.
     */
    boolean randomTicks(long cubeKey) {
        if (!interiorRefs.containsKey(cubeKey)) return false;
        int x = CubeKey.x(cubeKey), y = CubeKey.y(cubeKey), z = CubeKey.z(cubeKey);
        for (int dx = -GROW_ACROSS; dx <= GROW_ACROSS; dx++) for (int dz = -GROW_ACROSS; dz <= GROW_ACROSS; dz++)
            for (int dy = -GROW_DOWN; dy <= GROW_UP; dy++) if (!host.loaded(CubeKey.pack(x + dx, y + dy, z + dz))) return false;
        return true;
    }

    /** Lets go of every hold, as the world closes. */
    void close() {
        closed = true;
        for (Entry entry : known.values()) if (entry.held) host.release(entry.ask.hold());
        known.clear();
        lapsed.clear();
        ownerCubes.clear();
        heldByOwner.clear();
        holds = 0;
        cubeRefs.clear();
        interiorRefs.clear();
        columnRefs.clear();
        missing.clear();
        groundWanted.clear();
    }

    public int heldCubes() { return cubeRefs.size(); }
    /** Whether a hold keeps cubes in this cube column. */
    public boolean holdsColumn(long column) { return columnRefs.containsKey(column); }
    public int holds() { return holds; }
    public int leftOut() { return leftOut; }
    /** Ground reads started so far, and times the holds were planned. */
    public int groundReads() { return groundReads; }
    public int plans() { return plans; }

    /** For reports: each hold, as its kind, chunk and what it is kept round. */
    public List<String> describeHolds() {
        List<String> out = new ArrayList<>();
        for (Entry entry : known.values()) {
            if (!entry.held) continue;
            Hold hold = entry.ask.hold();
            out.add(hold.kind() + " " + ChunkPos.getX(hold.chunk()) + "," + ChunkPos.getZ(hold.chunk()) + " " + (hold.at() instanceof Integer y && y == GROUND ? "ground" : hold.at()));
        }
        return out;
    }

    /** The cube Y range kept for a forced chunk's heights, for {@code /forceload query}; ground bands not worked out yet read as null. */
    @Nullable int[] band(int chunkX, int chunkZ, int y) {
        int cubeY;
        if (y == GROUND) {
            long column = ChunkPos.asLong(chunkX, chunkZ);
            if (!ground.containsKey(column)) return null;
            cubeY = ground.get(column);
        } else cubeY = Math.floorDiv(y, 16);
        return new int[]{Math.max(CubeWorld.MIN_Y, (cubeY - FORCED_V) << 4), Math.min(CubeWorld.MAX_Y, ((cubeY + FORCED_V) << 4) + 15)};
    }

    // Reading the tickets -------------------------------------------------------------------------

    /**
     * Forced chunks to force again. The first look after the world loads ({@code seenBefore} null)
     * returns any chunk that has Earth heights but is missing from vanilla's list, as when that list's
     * save was lost, so no kept spot quietly goes. Later looks forget the heights of chunks unforced
     * since the last look, whatever unforced them.
     */
    static long[] reconcileForced(LongSet forcedNow, ForcedHeights heights, @Nullable LongSet seenBefore) {
        if (seenBefore == null) {
            LongArrayList restore = new LongArrayList();
            for (long chunk : heights.chunks()) if (!forcedNow.contains(chunk)) restore.add(chunk);
            return restore.toLongArray();
        }
        LongOpenHashSet gone = new LongOpenHashSet();
        for (LongIterator it = seenBefore.iterator(); it.hasNext(); ) {
            long chunk = it.nextLong();
            if (!forcedNow.contains(chunk)) gone.add(chunk);
        }
        heights.forget(gone);
        return new long[0];
    }

    /** A look at a level's tickets: the forced asks (the same list while they are unchanged), and the portal and mod asks. */
    record Asks(List<Ask> forced, List<Ask> others) {}

    /**
     * The asks of a level's tickets as they stand. The forced asks are built again only when the forced
     * chunks or their heights changed, so a big save looked at every second costs next to nothing.
     */
    Asks gather(ServerLevel level) {
        ForcedHeights heights = ForcedHeights.of(level);
        LongSet forcedChunks = level.getForcedChunks();
        long signature = forcedChunks.size();
        for (LongIterator it = forcedChunks.iterator(); it.hasNext(); ) signature = signature * 31 + it.nextLong() * 0x9E3779B97F4A7C15L;
        if (signature != forcedSignature || heights.version() != heightsVersion || seenForced == null) {
            long[] restore = reconcileForced(forcedChunks, heights, seenForced);
            for (long chunk : restore) {
                EarthPlatformMod.LOGGER.warn("Earth Platform: chunk {}, {} kept Earth heights but was missing from the saved forced chunks; forcing it again",
                        ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
                level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), true);
            }
            forcedChunks = level.getForcedChunks();
            seenForced = new LongOpenHashSet(forcedChunks);
            List<Ask> asks = new ArrayList<>(forcedChunks.size());
            for (LongIterator it = forcedChunks.iterator(); it.hasNext(); ) {
                long chunk = it.nextLong();
                int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
                List<ForcedHeights.Height> kept = heights.of(chunk);
                if (kept == null) asks.add(new Ask(new Hold(Kind.FORCED, chunk, GROUND), "forced:" + ForcedHeights.SERVER, cx, cz, FORCED_REACH, GROUND, FORCED_V));
                else for (ForcedHeights.Height height : kept)
                    asks.add(new Ask(new Hold(Kind.FORCED, chunk, height.y()), "forced:" + height.owner(), cx, cz, FORCED_REACH, height.y(), FORCED_V));
            }
            // Chunks forced in the same breath queue in the order of their coordinates, so neighbours share their rings.
            asks.sort(Comparator.comparingInt(Ask::chunkZ).thenComparingInt(Ask::chunkX));
            forcedAsks = List.copyOf(asks);
            long again = forcedChunks.size();
            for (LongIterator it = forcedChunks.iterator(); it.hasNext(); ) again = again * 31 + it.nextLong() * 0x9E3779B97F4A7C15L;
            forcedSignature = again;
            heightsVersion = heights.version();
        }
        List<Ask> others = new ArrayList<>();
        var tickets = ((io.earthplatform.minecraft.cubic.mixin.DistanceManagerAccess) level.getChunkSource().chunkMap.getDistanceManager()).earthplatform$tickets();
        Set<Object> exits = new HashSet<>();
        for (Long2ObjectMap.Entry<SortedArraySet<Ticket<?>>> entry : tickets.long2ObjectEntrySet()) {
            for (Ticket<?> ticket : entry.getValue()) {
                TicketType<?> type = ticket.getType();
                boolean portal = type == TicketType.PORTAL;
                if (!portal && !type.toString().startsWith("neoforge:")) continue;
                long chunk = entry.getLongKey();
                int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
                Object key = ((io.earthplatform.minecraft.cubic.mixin.TicketAccess) (Object) ticket).earthplatform$key();
                if (portal) {
                    if (key instanceof BlockPos at && exits.add(at.immutable()))
                        others.add(new Ask(new Hold(Kind.PORTAL, chunk, at.immutable()), at.immutable(), cx, cz, PORTAL_REACH, at.getY(), PORTAL_V));
                    continue;
                }
                Object owner = Platform.INSTANCE.forcedTicketOwner(key);
                if (owner instanceof BlockPos at) {
                    BlockPos block = at.immutable();
                    others.add(new Ask(new Hold(Kind.MOD, chunk, block), block, cx, cz, FORCED_REACH, at.getY(), FORCED_V));
                } else if (owner instanceof UUID id) {
                    Entity entity = level.getEntity(id);
                    if (entity != null) entityY.put(id, entity.getBlockY());
                    Integer y = entityY.get(id);
                    others.add(new Ask(new Hold(Kind.MOD, chunk, id), id, cx, cz, FORCED_REACH, y == null ? GROUND : y, FORCED_V));
                }
            }
        }
        if (entityY.size() > 1024) entityY.clear();
        return new Asks(forcedAsks, others);
    }

    // /forceload ----------------------------------------------------------------------------------

    /**
     * {@code /forceload add} or {@code remove} of one chunk in a cubic level, around {@code original}
     * (vanilla's {@code setChunkForced}): records the height the command was run from, and who ran it.
     * A chunk forced already counts as newly forced when this adds a height to it, so the command says so.
     */
    public static boolean force(ServerLevel level, int chunkX, int chunkZ, boolean add, CommandSourceStack source, BooleanSupplier original) {
        if (!(CubicLevel.of(level) instanceof ServerCubeWorld)) return original.getAsBoolean();
        long chunk = ChunkPos.asLong(chunkX, chunkZ);
        ForcedHeights heights = ForcedHeights.of(level);
        boolean changed = original.getAsBoolean();
        if (!add) {
            heights.clear(chunk);
            return changed;
        }
        Integer y = sourceY(source);
        String owner = source.getEntity() instanceof Player player ? player.getUUID().toString() : ForcedHeights.SERVER;
        if (changed) { heights.set(chunk, y, owner); return true; }
        return y != null && heights.add(chunk, y, owner);
    }

    /**
     * Vanilla's {@code setChunkForced} sets the forced chunks' saved flag to whether this call changed
     * anything, so a call that changes nothing (a chunk forced again for a second height, a remove of one
     * not forced) clears the flag a change before it set, and that change is never saved; NeoForge's
     * loaders share the same data. In a cubic level the flag is only ever set here, never cleared.
     */
    public static boolean forcedDirty(boolean changed, boolean wasDirty, boolean cubic) {
        return changed || cubic && wasDirty;
    }

    /**
     * The block Y a command was run from, or null when it was run from somewhere with no height of
     * its own: the server console or RCON, not moved with {@code execute positioned}.
     */
    @Nullable static Integer sourceY(CommandSourceStack source) {
        Vec3 at = source.getPosition();
        // The console's and RCON's own names (the source itself is private outside NeoForge).
        boolean console = "Server".equals(source.getTextName()) || "Rcon".equals(source.getTextName());
        if (source.getEntity() == null && console) {
            MinecraftServer server = source.getServer();
            ServerLevel overworld = server.overworld();
            Vec3 spawn = Vec3.atLowerCornerOf(overworld.getSharedSpawnPos());
            double scale = DimensionType.getTeleportationScale(overworld.dimensionType(), source.getLevel().dimensionType());
            if (Math.abs(at.y - spawn.y) < 1e-6 && Math.abs(at.x - spawn.x * scale) < 1e-3 && Math.abs(at.z - spawn.z * scale) < 1e-3) return null;
        }
        int y = net.minecraft.util.Mth.floor(at.y);
        return CubeWorld.inRange(y) ? y : null;
    }

    /** After {@code /forceload add} in a cubic level: one line on the heights it keeps. */
    public static void tellAdded(CommandSourceStack source) {
        if (!(CubicLevel.of(source.getLevel()) instanceof ServerCubeWorld)) return;
        Integer y = sourceY(source);
        String text = y == null
                ? "Earth: those chunks keep their ground band loaded, " + FORCED_V * 16 + " blocks up and down (run it from a spot, or with /execute positioned, to choose a height)."
                : "Earth: those chunks keep Y " + Math.max(CubeWorld.MIN_Y, ((y >> 4) - FORCED_V) << 4) + " to "
                        + Math.min(CubeWorld.MAX_Y, (((y >> 4) + FORCED_V) << 4) + 15) + " loaded, round your height. Their cubes load over the next moments.";
        source.sendSuccess(() -> Component.literal(text), false);
    }

    /** After {@code /forceload query} of a forced chunk in a cubic level: the heights it keeps. */
    public static void tellBand(CommandSourceStack source, ChunkPos chunk) {
        ServerLevel level = source.getLevel();
        if (!(CubicLevel.of(level) instanceof ServerCubeWorld cubes) || !level.getForcedChunks().contains(chunk.toLong())) return;
        List<ForcedHeights.Height> kept = ForcedHeights.of(level).of(chunk.toLong());
        StringBuilder text = new StringBuilder("Earth keeps ");
        if (kept == null) {
            int[] band = cubes.tickets().band(chunk.x, chunk.z, GROUND);
            text.append(band == null ? "its ground band" : "its ground band, Y " + band[0] + " to " + band[1]);
        } else {
            for (int i = 0; i < kept.size(); i++) {
                int[] band = cubes.tickets().band(chunk.x, chunk.z, kept.get(i).y());
                if (i > 0) text.append(i == kept.size() - 1 ? " and " : ", ");
                text.append("Y ").append(band[0]).append(" to ").append(band[1]);
            }
        }
        text.append(" loaded in and round this chunk.");
        source.sendSuccess(() -> Component.literal(text.toString()), false);
    }

    /** Operators online, and the log, for {@link Host#tell}. */
    static void tellOperators(MinecraftServer server, Component line) {
        EarthPlatformMod.LOGGER.warn(line.getString());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(2) || server.isSingleplayerOwner(player.getGameProfile())) player.sendSystemMessage(line);
        }
    }
}
