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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
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
 *       ticket lapses, giving way to any loader that needs the room.</li>
 *   <li><b>NeoForge forced chunks</b> ({@code TicketController}, what chunk-loader blocks and
 *       entities use): the chunk and its ring, round the owner block's or entity's height.</li>
 * </ul>
 *
 * <p>The cap ({@code chunkLoaderCubeLimit}) is first come, first served. Each loader has a place in the
 * queue, given when it was first forced and saved with its heights (mods' loaders and portal exits take
 * theirs when first seen). Loaders are let in strictly in that order, each counting only the cubes it
 * adds to those already held; a held loader is never put out for another, and one that doesn't fit waits,
 * with everything behind it, and costs nothing while it waits. The tickets are read every {@link #EVERY}
 * ticks and only what changed is worked on. Missing cubes, and the ground of chunks forced with no height,
 * are asked of the workers a few a tick, never made here.
 */
public final class ChunkTickets {
    /** Ticks between looks at the tickets. */
    static final int EVERY = 20;
    /** Cubes asked of the workers a tick for holds, at most, beside players' own; and missing cubes looked at a tick. */
    static final int REQUESTS_PER_TICK = 32, SCANS_PER_TICK = 128;
    /** Ground reads started a tick, and running at once, for chunks forced with no height. */
    static final int GROUND_PER_TICK = 2, GROUND_IN_FLIGHT = 4;
    /** Loaders at the front of the queue whose ground is read ahead, while the first waits on its own. */
    static final int LOOKAHEAD = GROUND_IN_FLIGHT;
    /** Ticks between sweeps for held cubes that are not loaded (a generation that failed, say), to ask again. */
    static final int SWEEP = 200;
    /** Forced and mod chunks: their ring (vanilla block-ticks it), and cubes up and down: what a player gets. */
    static final int FORCED_REACH = 1, FORCED_V = Cubic.VERTICAL_RADIUS;
    /** Portal exits: the columns vanilla block-ticks round a portal ticket, and cubes up and down from the exit. */
    static final int PORTAL_REACH = 2, PORTAL_V = 2;
    /** Ticks a portal's hold outlasts its ticket: an item loop's trip back, so its cubes stay in between. */
    static final int PORTAL_LINGER = 600;
    /** A height in {@link Ask} meaning the column's ground. */
    static final int GROUND = ForcedHeights.GROUND;
    /** Ticks before a column whose ground could not be had is asked again. */
    static final int GROUND_RETRY = 200;
    /**
     * Cubes round a held cube that must be loaded for it to random-tick: across, below and above. A tree
     * grows up to 30 blocks up and several across; into a cube not loaded, it would read air and lose the
     * blocks it wrote there, leaving a stump.
     */
    static final int GROW_ACROSS = 1, GROW_DOWN = 1, GROW_UP = 2;
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
     * ground; {@code seq} is its place in the queue, or -1 for one given when it is first seen.
     */
    record Ask(Hold hold, long seq, int chunkX, int chunkZ, int reach, int y, int v) {}

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

    /** One loader as known here. */
    private static final class Entry {
        Ask ask;
        long seq;
        boolean held, lapsed, forced;
        int cubeY, seen;
        long[] keys, inner;
        Entry(Ask ask, long seq) { this.ask = ask; this.seq = seq; }
    }

    private final Host host;
    /** Where ground reads run: the cube workers, a fixed pool. */
    private final Executor pool;
    /** Places in the queue for loaders seen without one (mods' loaders, portal exits): the forced heights' own count. */
    private LongSupplier seqs;
    private long localSeq = 1L << 40;

    private final Map<Hold, Entry> known = new HashMap<>();
    /** Loaders not held yet, by their place in the queue; and held ones. */
    private final TreeMap<Long, Entry> waiting = new TreeMap<>(), held = new TreeMap<>();
    /** Held portal exits whose ticket lapsed, the longest lapsed first. */
    private final Set<Hold> lapsed = new LinkedHashSet<>();
    /** Holds per cube, per cube that may random-tick (see {@link #GROW_UP}), and held cubes per cube column. */
    private final Long2IntOpenHashMap cubeRefs = new Long2IntOpenHashMap(), interiorRefs = new Long2IntOpenHashMap(),
            columnRefs = new Long2IntOpenHashMap();

    /** Ground cube Y per chunk column, for forced chunks without a height; read off this thread. */
    private final Long2IntOpenHashMap ground = new Long2IntOpenHashMap();
    private final LongOpenHashSet asking = new LongOpenHashSet();
    private final LongLinkedOpenHashSet groundWanted = new LongLinkedOpenHashSet();
    private final Long2IntOpenHashMap groundFailedAt = new Long2IntOpenHashMap();
    private final ConcurrentLinkedQueue<long[]> grounds = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private int groundReads;

    /** Entity owners' last known block Y, for when they are not loaded. */
    private final Map<UUID, Integer> entityY = new HashMap<>();
    /** Held cubes not loaded yet, asked of the workers a few a tick. */
    private final LongLinkedOpenHashSet missing = new LongLinkedOpenHashSet();

    // What the last look saw, so an unchanged set of tickets costs next to nothing.
    private List<Ask> lastForced = List.of(), lastOthers = List.of();
    private int lastCap = -1, leftOut, plans, tick;
    /** New holds left for the next look, past {@link #NEW_PER_PASS}. */
    private boolean pending;
    private boolean closed, firstLook = true;
    /** The forced asks as last built, and the heights' version they were built from. */
    private List<Ask> forcedAsks = List.of();
    private int heightsVersion = -1;

    ChunkTickets(Host host, Executor pool) {
        this.host = host;
        this.pool = pool;
        this.seqs = () -> localSeq++;
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

    /** For tests: all asks in one list. */
    void reconcile(int tick, List<Ask> asks, int cap) { reconcile(tick, List.of(), asks, cap); }

    /**
     * Looks at the tickets' asks as they stand at {@code tick}: {@code forced} is the same list as last
     * time while the forced chunks are unchanged, {@code others} the portal and mod asks. When nothing
     * changed this costs next to nothing.
     */
    void reconcile(int tick, List<Ask> forced, List<Ask> others, int cap) {
        if (closed) return;
        this.tick = tick;
        boolean changed = drainGrounds() || cap != lastCap;
        // Each failed ground read is tried again on its own time: those due are let go of here, so the plan asks again.
        if (groundFailedAt.values().removeIf((int failedAt) -> tick - failedAt >= GROUND_RETRY)) changed = true;
        lastCap = cap;
        if (forced != lastForced) { changed |= diff(forced, true, cap); lastForced = forced; }
        if (!others.equals(lastOthers)) { changed |= diff(others, false, cap); lastOthers = List.copyOf(others); }
        for (Ask ask : others) if (ask.hold().kind() == Kind.PORTAL) known.get(ask.hold()).seen = tick;
        for (Iterator<Hold> it = lapsed.iterator(); it.hasNext(); ) {
            Entry entry = known.get(it.next());
            if (tick - entry.seen <= PORTAL_LINGER) continue;
            it.remove();
            drop(entry);
            known.remove(entry.ask.hold());
            changed = true;
        }
        if (changed || pending) plan(Math.max(0, cap));
    }

    /** Brings {@link #known} up to one list of asks, the forced ones or the portal and mod ones. True when anything changed. */
    private boolean diff(List<Ask> asks, boolean forced, int cap) {
        boolean changed = false;
        Set<Hold> now = new HashSet<>(2 * asks.size() + 2);
        for (Ask ask : asks) {
            now.add(ask.hold());
            Entry entry = known.get(ask.hold());
            if (entry == null) {
                entry = new Entry(ask, ask.seq() >= 0 ? ask.seq() : seqs.getAsLong());
                entry.forced = forced;
                known.put(ask.hold(), entry);
                waiting.put(entry.seq, entry);
                changed = true;
                continue;
            }
            if (entry.lapsed) { entry.lapsed = false; lapsed.remove(ask.hold()); changed = true; }
            if (entry.ask.equals(ask)) continue;
            changed = true;
            long seq = ask.seq() >= 0 ? ask.seq() : entry.seq;
            if (seq == entry.seq && entry.held) {
                // An owner that moved (a NeoForge entity): inside the same cube nothing changes; to another, it is held there at once.
                if (sameBox(entry, ask)) entry.ask = ask;
                else move(entry, ask, cap);
                continue;
            }
            // Forced again (a remove and a re-add between two looks): the old place is let go of, and it queues anew.
            unqueue(entry);
            drop(entry);
            entry.ask = ask;
            entry.seq = seq;
            waiting.put(seq, entry);
        }
        for (Iterator<Entry> it = known.values().iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (entry.forced != forced || entry.lapsed || now.contains(entry.ask.hold())) continue;
            if (entry.held && entry.ask.hold().kind() == Kind.PORTAL) {
                // A held exit whose ticket lapsed stays a while, giving way to any loader that needs its room.
                entry.lapsed = true;
                lapsed.add(entry.ask.hold());
            } else {
                it.remove();
                unqueue(entry);
                drop(entry);
            }
            changed = true;
        }
        return changed;
    }

    /**
     * Lets loaders in, strictly in their order, each counting only the cubes it adds to those held. One
     * that doesn't fit takes the room of lapsed portal exits, else waits with everything behind it; a held
     * loader is never put out for another. Nothing passes a loader still waiting on its ground, while the
     * ground of the next few is read ahead. A lower cap lets go of lapsed exits, then of the newest holds.
     */
    private void plan(int cap) {
        plans++;
        pending = false;
        while (cubeRefs.size() > cap && !held.isEmpty()) {
            if (lapsed.isEmpty()) {
                Entry newest = held.lastEntry().getValue();
                drop(newest);
                waiting.put(newest.seq, newest); // waits again in its place
            } else dropOldestLapsed();
        }
        int admitted = 0, ahead = 0;
        boolean full = false, onGround = false;
        for (Iterator<Entry> it = waiting.values().iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            Ask ask = entry.ask;
            int cubeY = cubeY(ask);
            if (cubeY == GROUND || onGround) {
                onGround = true;
                if (++ahead >= LOOKAHEAD) break;
                continue;
            }
            if (admitted >= NEW_PER_PASS) { pending = true; break; }
            long[] keys = box(ask.chunkX(), ask.chunkZ(), ask.reach(), cubeY, ask.v());
            while (cubeRefs.size() + fresh(keys) > cap && !lapsed.isEmpty()) dropOldestLapsed();
            if (cubeRefs.size() + fresh(keys) > cap) { full = true; break; }
            it.remove();
            entry.cubeY = cubeY;
            hold(entry, keys);
            admitted++;
        }
        int out = full ? waiting.size() : 0;
        if (out != leftOut) {
            if (out > 0) host.tell(Component.literal("Earth Platform: " + out + " chunk loaders here are past chunkLoaderCubeLimit (" + cap
                    + " cubes, in earthplatform-common.toml) and keep only their vanilla chunk until earlier ones are removed or the limit is raised. "
                    + cubeRefs.size() + " cubes are kept.").withStyle(ChatFormatting.GOLD));
            else if (leftOut > 0) host.tell(Component.literal("Earth Platform: every chunk loader's area fits under chunkLoaderCubeLimit again.").withStyle(ChatFormatting.GREEN));
            leftOut = out;
        }
    }

    /** Whether an ask keeps the same cubes as a held loader: it differs, if at all, only in its height within the same cube. */
    private boolean sameBox(Entry entry, Ask ask) {
        Ask had = entry.ask;
        return ask.y() != GROUND && Math.floorDiv(ask.y(), 16) == entry.cubeY && ask.chunkX() == had.chunkX() && ask.chunkZ() == had.chunkZ()
                && ask.reach() == had.reach() && ask.v() == had.v();
    }

    /** Takes a loader not held out of the queue. */
    private void unqueue(Entry entry) {
        if (waiting.get(entry.seq) == entry) waiting.remove(entry.seq);
    }

    /**
     * A held loader whose owner moved to another cube (a NeoForge entity): held round the new cube at once, in
     * its place, if that fits in the room its old cubes leave; else it keeps the old ones. Either way it never
     * lets go of its room for another loader.
     */
    private void move(Entry entry, Ask ask, int cap) {
        int cubeY = cubeY(ask);
        long[] keys = cubeY == GROUND ? null : box(ask.chunkX(), ask.chunkZ(), ask.reach(), cubeY, ask.v());
        long[] oldKeys = entry.keys;
        int oldY = entry.cubeY;
        Ask oldAsk = entry.ask;
        drop(entry);
        if (keys != null && cubeRefs.size() + fresh(keys) <= cap) {
            entry.ask = ask;
            entry.cubeY = cubeY;
            hold(entry, keys);
        } else {
            entry.cubeY = oldY;
            hold(entry, oldKeys); // with its old ask, so it is tried again at the next change
            entry.ask = oldAsk;
        }
    }

    /** For tests: whether the held cube counts are exactly those of the holds there are, and every loader is queued or held once. */
    boolean accountsAdd() {
        Long2IntOpenHashMap counted = new Long2IntOpenHashMap();
        for (Entry entry : held.values()) for (long key : entry.keys) counted.addTo(key, 1);
        if (!counted.equals(cubeRefs)) return false;
        Set<Entry> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Entry entry : waiting.values()) if (!seen.add(entry) || entry.held) return false;
        for (Entry entry : held.values()) if (!seen.add(entry) || !entry.held) return false;
        for (Entry entry : known.values()) if (!entry.lapsed && !seen.contains(entry)) return false;
        return true;
    }

    /** Lets go of the portal exit whose ticket lapsed longest ago. */
    private void dropOldestLapsed() {
        Iterator<Hold> oldest = lapsed.iterator();
        Entry exit = known.remove(oldest.next());
        oldest.remove();
        drop(exit);
    }

    /** The cubes of a box not held yet: what holding it would add. */
    private int fresh(long[] keys) {
        int fresh = 0;
        for (long key : keys) if (!cubeRefs.containsKey(key)) fresh++;
        return fresh;
    }

    private void hold(Entry entry, long[] keys) {
        if (entry.held) throw new IllegalStateException("held twice: " + entry.ask.hold());
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
        held.put(entry.seq, entry);
    }

    private void drop(Entry entry) {
        if (!entry.held) return;
        for (long key : entry.keys) {
            if (cubeRefs.addTo(key, -1) <= 1) { cubeRefs.remove(key); missing.remove(key); }
            long column = CubeKey.column(CubeKey.x(key), CubeKey.z(key));
            if (columnRefs.addTo(column, -1) <= 1) columnRefs.remove(column);
        }
        for (long key : entry.inner) if (interiorRefs.addTo(key, -1) <= 1) interiorRefs.remove(key);
        host.release(entry.ask.hold());
        held.remove(entry.seq);
        entry.held = false;
        entry.keys = null;
        entry.inner = null;
    }

    /** The cube Y an ask is kept round, or {@link #GROUND} while its ground is unknown, which is then read. */
    private int cubeY(Ask ask) {
        if (ask.y() != GROUND) return Math.floorDiv(ask.y(), 16);
        long column = ChunkPos.asLong(ask.chunkX(), ask.chunkZ());
        if (ground.containsKey(column)) return ground.get(column);
        if (asking.contains(column)) return GROUND;
        if (groundFailedAt.containsKey(column)) return GROUND; // waiting out its failure; let go of when due
        groundWanted.add(column);
        return GROUND;
    }

    /** Grounds read since the last look; true when any came in. */
    private boolean drainGrounds() {
        boolean any = false;
        for (long[] done; (done = grounds.poll()) != null; ) {
            any = true;
            asking.remove(done[0]);
            if (done[1] == Long.MIN_VALUE) groundFailedAt.put(done[0], tick);
            else {
                groundFailedAt.remove(done[0]);
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

    /**
     * Whether vanilla's weather (snow and ice, lightning) may run in a column: always with a player near; else
     * only once the column's sky tops are known from the read off the server thread. Until then, as in a column
     * whose loader waits for the cap, the weather is left out: working the height out would read the terrain
     * on the server thread.
     */
    static boolean weatherRuns(boolean playerNear, boolean topsKnown) { return playerNear || topsKnown; }

    /** Lets go of every hold, as the world closes. */
    void close() {
        closed = true;
        for (Entry entry : held.values()) host.release(entry.ask.hold());
        known.clear();
        waiting.clear();
        held.clear();
        lapsed.clear();
        cubeRefs.clear();
        interiorRefs.clear();
        columnRefs.clear();
        missing.clear();
        groundWanted.clear();
    }

    public int heldCubes() { return cubeRefs.size(); }
    /** Whether a hold keeps cubes in this cube column. */
    public boolean holdsColumn(long column) { return columnRefs.containsKey(column); }
    public int holds() { return held.size(); }
    public int leftOut() { return leftOut; }
    /** Ground reads started so far, and times the holds were planned. */
    public int groundReads() { return groundReads; }
    public int plans() { return plans; }

    /** For reports: each hold, in its order, as its kind, chunk and what it is kept round. */
    public List<String> describeHolds() {
        List<String> out = new ArrayList<>();
        for (Entry entry : held.values()) {
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

    /** A look at a level's tickets: the forced asks (the same list while they are unchanged), and the portal and mod asks. */
    record Asks(List<Ask> forced, List<Ask> others) {}

    /**
     * The asks of a level's tickets as they stand. The forced asks are built again only when the forced
     * chunks' heights changed (every forced chunk has them: {@link io.earthplatform.minecraft.cubic.mixin.CubicForcedChunksMixin}
     * keeps them up with {@code setChunkForced}). The first look after the world loads lets go of the heights of
     * chunks no longer forced (forced off while the mod was away) with one log line, and gives the forced chunks
     * that have none their ground band, in the order of their coordinates.
     */
    Asks gather(ServerLevel level) {
        ForcedHeights heights = ForcedHeights.of(level);
        seqs = heights::nextSeq;
        if (firstLook) {
            firstLook = false;
            LongSet forced = level.getForcedChunks();
            int gone = heights.retain(forced);
            if (gone > 0) EarthPlatformMod.LOGGER.info("Earth Platform: let go of the Earth heights of {} chunks no longer forced", gone);
            LongArrayList bare = new LongArrayList();
            for (LongIterator it = forced.iterator(); it.hasNext(); ) {
                long chunk = it.nextLong();
                if (!heights.has(chunk)) bare.add(chunk);
            }
            long[] sorted = bare.toLongArray();
            java.util.Arrays.sort(sorted); // once: ChunkPos packs z above x, so this is row by row
            for (long chunk : sorted) heights.ensure(chunk);
        }
        if (heights.version() != heightsVersion) {
            List<Ask> asks = new ArrayList<>(heights.size() + 4);
            for (Long2ObjectMap.Entry<List<ForcedHeights.Height>> entry : heights.all()) {
                long chunk = entry.getLongKey();
                int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
                for (ForcedHeights.Height height : entry.getValue())
                    asks.add(new Ask(new Hold(Kind.FORCED, chunk, height.y()), height.seq(), cx, cz, FORCED_REACH, height.y(), FORCED_V));
            }
            forcedAsks = asks;
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
                        others.add(new Ask(new Hold(Kind.PORTAL, chunk, at.immutable()), -1, cx, cz, PORTAL_REACH, at.getY(), PORTAL_V));
                    continue;
                }
                Object owner = Platform.INSTANCE.forcedTicketOwner(key);
                if (owner instanceof BlockPos at) {
                    others.add(new Ask(new Hold(Kind.MOD, chunk, at.immutable()), -1, cx, cz, FORCED_REACH, at.getY(), FORCED_V));
                } else if (owner instanceof UUID id) {
                    Entity entity = level.getEntity(id);
                    if (entity != null) entityY.put(id, entity.getBlockY());
                    Integer y = entityY.get(id);
                    others.add(new Ask(new Hold(Kind.MOD, chunk, id), -1, cx, cz, FORCED_REACH, y == null ? GROUND : y, FORCED_V));
                }
            }
        }
        if (entityY.size() > 1024) entityY.clear();
        return new Asks(forcedAsks, others);
    }

    // /forceload ----------------------------------------------------------------------------------

    /**
     * {@code /forceload add} or {@code remove} of one chunk in a cubic level, around {@code original}
     * (vanilla's {@code setChunkForced}, after which the chunk has its ground band, or none if removed):
     * records the height the command was run from. A chunk forced already counts as newly forced when
     * this adds a height to it, so the command says so.
     */
    public static boolean force(ServerLevel level, int chunkX, int chunkZ, boolean add, CommandSourceStack source, BooleanSupplier original) {
        if (!(CubicLevel.of(level) instanceof ServerCubeWorld)) return original.getAsBoolean();
        long chunk = ChunkPos.asLong(chunkX, chunkZ);
        boolean changed = original.getAsBoolean();
        if (!add) return changed;
        ForcedHeights heights = ForcedHeights.of(level);
        Integer y = sourceY(source);
        if (changed) { if (y != null) heights.set(chunk, y); return true; }
        return y != null && heights.add(chunk, y);
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
        if (kept == null) kept = List.of(new ForcedHeights.Height(GROUND, 0));
        StringBuilder text = new StringBuilder("Earth keeps ");
        for (int i = 0; i < kept.size(); i++) {
            int[] band = cubes.tickets().band(chunk.x, chunk.z, kept.get(i).y());
            if (i > 0) text.append(i == kept.size() - 1 ? " and " : ", ");
            if (kept.get(i).y() == GROUND) text.append(band == null ? "its ground band" : "its ground band, Y " + band[0] + " to " + band[1]);
            else text.append("Y ").append(band[0]).append(" to ").append(band[1]);
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
