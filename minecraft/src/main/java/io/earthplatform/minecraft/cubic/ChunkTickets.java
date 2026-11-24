package io.earthplatform.minecraft.cubic;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.platform.Platform;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
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
 *       ticket lapses, so a portal item loop doesn't reload its cubes every trip.</li>
 *   <li><b>NeoForge forced chunks</b> ({@code TicketController}, what chunk-loader blocks and
 *       entities use): the chunk and its ring, round the owner block's or entity's height.</li>
 * </ul>
 *
 * Holds are worked out again every {@link #EVERY} ticks from the tickets as they stand, so one lapses
 * with its ticket and none outlives the world. Missing cubes are asked of the workers a few a tick,
 * never made here. Past the cap ({@code chunkLoaderCubeLimit}) the last holds are left out and
 * operators are told.
 */
public final class ChunkTickets {
    /** Ticks between looks at the tickets. */
    static final int EVERY = 20;
    /** Cubes asked of the workers a tick for holds, at most, beside players' own. */
    static final int REQUESTS_PER_TICK = 32;
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

    public enum Kind { FORCED, MOD, PORTAL }

    /** What holds a set of cubes: a kind, the chunk its ticket is on and what in it (a height, an owner, a portal). */
    record Hold(Kind kind, long chunk, Object at) {}

    /** A ticket's wish: the columns {@code reach} round a chunk, {@code v} cubes up and down from a block {@code y}, or the ground. */
    record Ask(Hold hold, int chunkX, int chunkZ, int reach, int y, int v) {}

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
    private final Executor io;
    private final Map<Hold, long[]> held = new HashMap<>();
    /** Ground cube Y per chunk column, for forced chunks without a height; worked out off this thread. */
    private final Long2IntOpenHashMap ground = new Long2IntOpenHashMap();
    private final LongOpenHashSet asking = new LongOpenHashSet();
    private final Long2IntOpenHashMap groundFailedAt = new Long2IntOpenHashMap();
    private final ConcurrentLinkedQueue<long[]> grounds = new ConcurrentLinkedQueue<>();
    /** Portal asks still kept after their ticket went, and the tick each was last seen. */
    private final Map<Hold, Ask> portals = new LinkedHashMap<>();
    private final Map<Hold, Integer> portalSeen = new HashMap<>();
    /** Entity owners' last known block Y, for when they are not loaded. */
    private final Map<UUID, Integer> entityY = new HashMap<>();
    /** Held cubes not loaded yet, asked of the workers a few a tick. */
    private final LongLinkedOpenHashSet missing = new LongLinkedOpenHashSet();
    private int heldCubes, leftOut, cubesWanted;
    private boolean closed;

    ChunkTickets(Host host, Executor io) {
        this.host = host;
        this.io = io;
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

    /**
     * The asks that fit under the cap, in order, each with its cubes; an ask that would take the
     * total of distinct cubes past {@code cap} is left out whole, and later smaller ones may still fit.
     */
    static Map<Hold, long[]> fit(List<Map.Entry<Hold, long[]>> wants, int cap, LongOpenHashSet into) {
        Map<Hold, long[]> kept = new LinkedHashMap<>();
        for (Map.Entry<Hold, long[]> want : wants) {
            int fresh = 0;
            for (long key : want.getValue()) if (!into.contains(key)) fresh++;
            if (into.size() + fresh > cap) continue;
            for (long key : want.getValue()) into.add(key);
            kept.put(want.getKey(), want.getValue());
        }
        return kept;
    }

    /**
     * Works the holds out again from the tickets' asks as they stand at {@code tick}: releases what
     * no ask wants any more, keeps what they want up to {@code cap} cubes, and queues what is missing.
     */
    void reconcile(int tick, List<Ask> asks, int cap) {
        if (closed) return;
        drainGrounds();
        // Portals stay a while after their ticket lapses.
        List<Ask> all = new ArrayList<>(asks.size() + portals.size());
        for (Ask ask : asks) {
            if (ask.hold().kind() == Kind.PORTAL) { portals.put(ask.hold(), ask); portalSeen.put(ask.hold(), tick); }
            else all.add(ask);
        }
        for (Iterator<Map.Entry<Hold, Ask>> it = portals.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Hold, Ask> entry = it.next();
            if (tick - portalSeen.get(entry.getKey()) > PORTAL_LINGER) { it.remove(); portalSeen.remove(entry.getKey()); }
            else all.add(entry.getValue());
        }
        List<Map.Entry<Hold, long[]>> wants = new ArrayList<>(all.size());
        for (Ask ask : all) {
            int cubeY = cubeY(ask, tick);
            if (cubeY == GROUND) continue; // its ground is being worked out
            wants.add(Map.entry(ask.hold(), box(ask.chunkX(), ask.chunkZ(), ask.reach(), cubeY, ask.v())));
        }
        LongOpenHashSet cubes = new LongOpenHashSet();
        Map<Hold, long[]> kept = fit(wants, Math.max(0, cap), cubes);
        LongOpenHashSet everything = new LongOpenHashSet(cubes);
        for (Map.Entry<Hold, long[]> want : wants) if (!kept.containsKey(want.getKey())) for (long key : want.getValue()) everything.add(key);
        int wanted = everything.size();
        for (Iterator<Map.Entry<Hold, long[]>> it = held.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Hold, long[]> entry = it.next();
            if (!kept.containsKey(entry.getKey())) { host.release(entry.getKey()); it.remove(); }
        }
        for (Map.Entry<Hold, long[]> entry : kept.entrySet()) {
            host.keep(entry.getKey(), entry.getValue());
            held.put(entry.getKey(), entry.getValue());
        }
        missing.clear();
        for (LongIterator it = cubes.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (!host.loaded(key)) missing.add(key);
        }
        heldCubes = cubes.size();
        cubesWanted = wanted;
        int out = wants.size() - kept.size();
        if (out != leftOut) {
            if (out > 0) host.tell(Component.literal("Earth Platform: chunk loaders here want " + wanted + " cubes, over the limit of " + cap
                    + " (chunkLoaderCubeLimit in earthplatform-common.toml). " + out + " of " + wants.size()
                    + " loaded areas are not kept loaded until some are removed or the limit is raised.").withStyle(ChatFormatting.GOLD));
            else if (leftOut > 0) host.tell(Component.literal("Earth Platform: every chunk loader's area fits under chunkLoaderCubeLimit again.").withStyle(ChatFormatting.GREEN));
            leftOut = out;
        }
    }

    /** The cube Y an ask is kept round, or {@link #GROUND} while its ground is unknown (it is asked for). */
    private int cubeY(Ask ask, int tick) {
        if (ask.y() != GROUND) return Math.floorDiv(ask.y(), 16);
        long column = ChunkPos.asLong(ask.chunkX(), ask.chunkZ());
        if (ground.containsKey(column)) return ground.get(column);
        if (asking.contains(column)) return GROUND;
        if (groundFailedAt.containsKey(column) && tick - groundFailedAt.get(column) < GROUND_RETRY) return GROUND;
        groundFailedAt.remove(column);
        asking.add(column);
        int x = (ask.chunkX() << 4) + 8, z = (ask.chunkZ() << 4) + 8;
        try {
            io.execute(() -> {
                long ground;
                try { ground = Math.floorDiv(host.groundY(x, z), 16); }
                catch (RuntimeException missingData) { ground = Long.MIN_VALUE; }
                grounds.add(new long[]{column, ground, tick});
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            asking.remove(column);
        }
        return GROUND;
    }

    private void drainGrounds() {
        for (long[] done; (done = grounds.poll()) != null; ) {
            asking.remove(done[0]);
            if (done[1] == Long.MIN_VALUE) groundFailedAt.put(done[0], (int) done[2]);
            else {
                if (ground.size() > 4096) ground.clear();
                ground.put(done[0], (int) done[1]);
            }
        }
    }

    /** Asks the workers for a few held cubes not loaded yet; {@code room} is how many more they may take now. */
    void tick(int room) {
        if (closed || missing.isEmpty()) return;
        for (int budget = Math.min(room, REQUESTS_PER_TICK); budget > 0 && !missing.isEmpty(); ) {
            long key = missing.removeFirstLong();
            if (host.loaded(key)) continue;
            if (host.request(key)) budget--;
        }
    }

    /** Lets go of every hold, as the world closes. */
    void close() {
        closed = true;
        for (Hold hold : held.keySet()) host.release(hold);
        held.clear();
        portals.clear();
        portalSeen.clear();
        missing.clear();
    }

    public int heldCubes() { return heldCubes; }
    public int holds() { return held.size(); }
    public int leftOut() { return leftOut; }
    public int cubesWanted() { return cubesWanted; }

    /** For reports: each hold, as its kind, chunk and what it is kept round. */
    public List<String> describeHolds() {
        List<String> out = new ArrayList<>();
        for (Hold hold : held.keySet())
            out.add(hold.kind() + " " + ChunkPos.getX(hold.chunk()) + "," + ChunkPos.getZ(hold.chunk()) + " " + (hold.at() instanceof Integer y && y == GROUND ? "ground" : hold.at()));
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

    /** The asks of a level's tickets as they stand: forced chunks first, then mods' loaders, then portals. */
    List<Ask> gather(ServerLevel level) {
        List<Ask> forced = new ArrayList<>(), mods = new ArrayList<>(), portalsNow = new ArrayList<>();
        ForcedHeights heights = ForcedHeights.of(level);
        var forcedChunks = level.getForcedChunks();
        heights.retain(forcedChunks);
        long[] sorted = forcedChunks.toLongArray();
        java.util.Arrays.sort(sorted);
        for (long chunk : sorted) {
            int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
            int[] ys = heights.of(chunk);
            if (ys == null) forced.add(new Ask(new Hold(Kind.FORCED, chunk, GROUND), cx, cz, FORCED_REACH, GROUND, FORCED_V));
            else for (int y : ys) forced.add(new Ask(new Hold(Kind.FORCED, chunk, y), cx, cz, FORCED_REACH, y, FORCED_V));
        }
        var tickets = ((io.earthplatform.minecraft.cubic.mixin.DistanceManagerAccess) level.getChunkSource().chunkMap.getDistanceManager()).earthplatform$tickets();
        for (Long2ObjectMap.Entry<SortedArraySet<Ticket<?>>> entry : tickets.long2ObjectEntrySet()) {
            long chunk = entry.getLongKey();
            int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
            for (Ticket<?> ticket : entry.getValue()) {
                TicketType<?> type = ticket.getType();
                Object key = ((io.earthplatform.minecraft.cubic.mixin.TicketAccess) (Object) ticket).earthplatform$key();
                if (type == TicketType.PORTAL && key instanceof BlockPos at) {
                    portalsNow.add(new Ask(new Hold(Kind.PORTAL, chunk, at.immutable()), cx, cz, PORTAL_REACH, at.getY(), PORTAL_V));
                    continue;
                }
                String name = type.toString();
                if (!name.startsWith("neoforge:")) continue;
                Object owner = Platform.INSTANCE.forcedTicketOwner(key);
                if (owner instanceof BlockPos at) {
                    mods.add(new Ask(new Hold(Kind.MOD, chunk, at.immutable()), cx, cz, FORCED_REACH, at.getY(), FORCED_V));
                } else if (owner instanceof UUID id) {
                    Entity entity = level.getEntity(id);
                    if (entity != null) entityY.put(id, entity.getBlockY());
                    Integer y = entityY.get(id);
                    mods.add(new Ask(new Hold(Kind.MOD, chunk, id), cx, cz, FORCED_REACH, y == null ? GROUND : y, FORCED_V));
                }
            }
        }
        if (entityY.size() > 1024) entityY.clear();
        List<Ask> all = new ArrayList<>(forced.size() + mods.size() + portalsNow.size());
        all.addAll(forced);
        all.addAll(mods);
        all.addAll(dedupe(portalsNow));
        return all;
    }

    /** One portal ticket covers all the chunks round it; keep each exit once. */
    private static List<Ask> dedupe(List<Ask> asks) {
        Map<Object, Ask> one = new LinkedHashMap<>();
        for (Ask ask : asks) one.putIfAbsent(ask.hold().at(), ask);
        return new ArrayList<>(one.values());
    }

    // /forceload ----------------------------------------------------------------------------------

    /**
     * {@code /forceload add} or {@code remove} of one chunk in a cubic level, around {@code original}
     * (vanilla's {@code setChunkForced}): records the height the command was run from. A chunk forced
     * already counts as newly forced when this adds a height to it, so the command says so.
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
        if (changed) { heights.set(chunk, y); return true; }
        return y != null && heights.add(chunk, y);
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
        int[] ys = ForcedHeights.of(level).of(chunk.toLong());
        StringBuilder text = new StringBuilder("Earth keeps ");
        if (ys == null) {
            int[] band = cubes.tickets().band(chunk.x, chunk.z, GROUND);
            text.append(band == null ? "its ground band" : "its ground band, Y " + band[0] + " to " + band[1]);
        } else {
            for (int i = 0; i < ys.length; i++) {
                int[] band = cubes.tickets().band(chunk.x, chunk.z, ys[i]);
                if (i > 0) text.append(i == ys.length - 1 ? " and " : ", ");
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
