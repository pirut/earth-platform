package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.minecraft.cubic.ChunkTickets.Ask;
import io.earthplatform.minecraft.cubic.ChunkTickets.Hold;
import io.earthplatform.minecraft.cubic.ChunkTickets.Kind;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import org.junit.jupiter.api.Test;

/** Chunk tickets in the Earth keep the cubes round the height they mean, loaded through the workers, within a fairly shared cap, and let go with the ticket. */
class ChunkTicketsTest {
    /** A ServerCubeWorld stand-in: loaded cubes, a worker queue, a ResidentCubes, the ground, and what operators were told. */
    private static final class World implements ChunkTickets.Host {
        final LongSet loaded = new LongLinkedOpenHashSet();
        final LongArrayList requested = new LongArrayList();
        final ResidentCubes resident = new ResidentCubes();
        final List<String> told = new ArrayList<>();
        final ArrayDeque<Runnable> io = new ArrayDeque<>();
        int groundY = 1180, groundAsks, keeps;
        boolean groundMissing;

        @Override public boolean loaded(long key) { return loaded.contains(key); }
        @Override public boolean request(long key) { return !loaded.contains(key) && !requested.contains(key) && requested.add(key); }
        @Override public void keep(Object holder, long[] keys) { keeps++; resident.keep(holder, keys); }
        @Override public void release(Object holder) { resident.release(holder); }
        @Override public int groundY(int x, int z) {
            groundAsks++;
            if (groundMissing) throw new IllegalStateException("map data not in yet");
            return groundY;
        }
        @Override public void tell(Component line) { told.add(line.getString()); }

        /** The workers deliver every cube asked for. */
        void deliver() { loaded.addAll(requested); requested.clear(); }
        void runIo() { while (!io.isEmpty()) io.poll().run(); }
        /** One unload pass with nobody near: what goes. */
        int pass() {
            var drop = ResidentCubes.unloadable(new LongArrayList(loaded), key -> false, resident);
            drop.forEach(loaded::remove);
            return drop.size();
        }
    }

    private static ChunkTickets tickets(World world) { return new ChunkTickets(world, world.io::add); }

    private static Ask forcedAt(int chunkX, int chunkZ, int y) { return forcedAt(chunkX, chunkZ, y, "forced:"); }

    private static Ask forcedAt(int chunkX, int chunkZ, int y, String owner) {
        return new Ask(new Hold(Kind.FORCED, ChunkPos.asLong(chunkX, chunkZ), y), owner, chunkX, chunkZ, ChunkTickets.FORCED_REACH, y, ChunkTickets.FORCED_V);
    }

    private static Ask console(int chunkX, int chunkZ) {
        return new Ask(new Hold(Kind.FORCED, ChunkPos.asLong(chunkX, chunkZ), ChunkTickets.GROUND), "forced:", chunkX, chunkZ,
                ChunkTickets.FORCED_REACH, ChunkTickets.GROUND, ChunkTickets.FORCED_V);
    }

    private static Ask portalAt(BlockPos at) {
        return new Ask(new Hold(Kind.PORTAL, ChunkPos.asLong(at.getX() >> 4, at.getZ() >> 4), at), at, at.getX() >> 4, at.getZ() >> 4,
                ChunkTickets.PORTAL_REACH, at.getY(), ChunkTickets.PORTAL_V);
    }

    private static final int BOX = 3 * 3 * (2 * Cubic.VERTICAL_RADIUS + 1);

    @Test void aForcedChunkKeepsItsRingRoundTheHeightItWasForcedFrom() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(forcedAt(-41_000, 2_900, 1200)), 8192);
        assertEquals(1, tickets.holds());
        assertEquals(BOX, tickets.heldCubes(), "the chunk, its ring, 6 cubes up and down");
        int cy = 1200 >> 4;
        assertTrue(world.resident.holds(CubeKey.pack(-41_000, cy, 2_900)));
        assertTrue(world.resident.holds(CubeKey.pack(-41_001, cy + Cubic.VERTICAL_RADIUS, 2_901)));
        assertFalse(world.resident.holds(CubeKey.pack(-41_000, cy + Cubic.VERTICAL_RADIUS + 1, 2_900)), "not the whole column");
        assertFalse(world.resident.holds(CubeKey.pack(-41_002, cy, 2_900)), "not past the ring");
        assertFalse(world.resident.holds(CubeKey.pack(-41_000, 4, 2_900)), "not the carrier band far below");
    }

    @Test void heldCubesComeFromTheWorkersAFewATickAndSurviveEveryUnloadPass() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(forcedAt(10, 10, 1200), forcedAt(12, 10, 1200)), 8192);
        int total = tickets.heldCubes(), ticks = 0;
        while (world.loaded.size() < total) {
            tickets.tick(++ticks, 384);
            assertTrue(world.requested.size() <= ChunkTickets.REQUESTS_PER_TICK, "never more than a few a tick");
            world.deliver();
            if (ticks % 2 == 0) assertEquals(0, world.pass(), "a held cube was dropped while the rest came in");
            assertTrue(ticks < 100);
        }
        for (int i = 0; i < 50; i++) assertEquals(0, world.pass());
        // With the workers full, nothing is asked.
        world.loaded.clear();
        tickets.tick(ChunkTickets.SWEEP, 0);
        assertTrue(world.requested.isEmpty());
        // A cube that went missing (its generation failed) is asked again at the next sweep.
        tickets.tick(ChunkTickets.SWEEP * 2, 384);
        assertEquals(ChunkTickets.REQUESTS_PER_TICK, world.requested.size());
    }

    @Test void removingTheTicketLetsGoOfItsCubes() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(forcedAt(5, 5, 1200), forcedAt(5, 5, 3000)), 8192);
        for (int i = 1; i <= 20; i++) { tickets.tick(i, 384); world.deliver(); }
        int held = world.loaded.size();
        assertEquals(2, world.resident.holders());
        tickets.reconcile(40, List.of(forcedAt(5, 5, 3000)), 8192); // one height removed
        assertEquals(1, world.resident.holders());
        assertEquals(held / 2, world.pass(), "the removed height's cubes go at the next pass");
        assertTrue(tickets.holdsColumn(CubeKey.column(5, 5)));
        tickets.reconcile(60, List.of(), 8192); // /forceload remove
        assertFalse(tickets.holdsColumn(CubeKey.column(5, 5)), "its columns are no longer kept");
        assertEquals(0, world.resident.holders());
        assertEquals(0, tickets.heldCubes());
        assertEquals(held / 2, world.pass());
        assertEquals(0, world.loaded.size());
    }

    @Test void aChunkForcedWithNoHeightKeepsItsGroundReadOnTheWorkers() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(console(3, 4)), 8192);
        assertEquals(0, tickets.holds(), "nothing until the ground is known");
        assertEquals(0, world.groundAsks, "the ground is not read here");
        assertNull(tickets.band(3, 4, ChunkTickets.GROUND));
        tickets.tick(21, 384);
        assertEquals(1, world.io.size(), "handed to the workers");
        world.runIo();
        assertEquals(1, world.groundAsks);
        tickets.reconcile(40, List.of(console(3, 4)), 8192);
        assertEquals(1, tickets.holds());
        assertTrue(world.resident.holds(CubeKey.pack(3, 1180 >> 4, 4)));
        assertArrayEquals(new int[]{((1180 >> 4) - 6) << 4, (((1180 >> 4) + 6) << 4) + 15}, tickets.band(3, 4, ChunkTickets.GROUND));
        tickets.reconcile(60, List.of(console(3, 4)), 8192);
        tickets.tick(61, 384);
        assertEquals(1, world.groundAsks, "read once");
    }

    @Test void aGroundWhoseMapDataIsMissingIsReadAgainLater() {
        World world = new World();
        world.groundMissing = true;
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(console(3, 4)), 8192);
        tickets.tick(21, 384);
        world.runIo();
        tickets.reconcile(40, List.of(console(3, 4)), 8192);
        tickets.tick(41, 384);
        world.runIo();
        assertEquals(1, world.groundAsks, "not read again at once");
        world.groundMissing = false;
        tickets.reconcile(40 + ChunkTickets.GROUND_RETRY + 1, List.of(console(3, 4)), 8192);
        tickets.tick(42 + ChunkTickets.GROUND_RETRY, 384);
        world.runIo();
        tickets.reconcile(60 + ChunkTickets.GROUND_RETRY, List.of(console(3, 4)), 8192);
        assertEquals(2, world.groundAsks);
        assertEquals(1, tickets.holds());
    }

    /** A big save: a thousand chunks forced from the console, none with a height. */
    private static List<Ask> thousandConsoleChunks() {
        List<Ask> asks = new ArrayList<>();
        for (int i = 0; i < 1000; i++) asks.add(console((i % 40) * 4, (i / 40) * 4));
        return asks;
    }

    @Test void groundReadsAreFewATickAndNoneForLoadersPastTheCap() {
        World world = new World();
        ChunkTickets none = tickets(world);
        none.reconcile(20, thousandConsoleChunks(), 0);
        for (int t = 21; t < 400; t++) none.tick(t, 384);
        assertEquals(0, none.groundReads(), "a cap of 0 reads nothing");
        assertTrue(world.io.isEmpty());

        World busy = new World();
        ChunkTickets tickets = tickets(busy);
        List<Ask> asks = thousandConsoleChunks();
        tickets.reconcile(20, asks, 8192);
        tickets.tick(21, 384);
        assertTrue(busy.io.size() <= ChunkTickets.GROUND_PER_TICK, "a few started a tick");
        for (int t = 22; t < 60; t++) tickets.tick(t, 384);
        assertTrue(busy.io.size() <= ChunkTickets.GROUND_IN_FLIGHT, "and only a few running at once: " + busy.io.size());
        // Let the reads and loads run to a steady state.
        for (int t = 60; t < 4000; t++) {
            busy.runIo();
            if (t % ChunkTickets.EVERY == 0) tickets.reconcile(t, asks, 8192);
            tickets.tick(t, 384);
        }
        int fits = 8192 / BOX;
        assertEquals(fits, tickets.holds(), "as many as fit");
        assertTrue(tickets.groundReads() <= fits + ChunkTickets.GROUND_IN_FLIGHT, "grounds read only for loaders that fit: " + tickets.groundReads());
        assertEquals(1000 - fits, tickets.leftOut());
        assertTrue(tickets.heldCubes() <= 8192);
    }

    @Test void unchangedTicketsCostNoPlanning() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        List<Ask> asks = List.of(forcedAt(0, 0, 1200), forcedAt(10, 0, 1200));
        tickets.reconcile(20, asks, 8192);
        int keeps = world.keeps, plans = tickets.plans();
        for (int t = 40; t < 2000; t += 20) tickets.reconcile(t, List.copyOf(asks), 8192);
        assertEquals(plans, tickets.plans(), "planned once");
        assertEquals(keeps, world.keeps);
        // One more chunk: planned again, and only the new hold is kept anew.
        tickets.reconcile(2000, List.of(forcedAt(0, 0, 1200), forcedAt(10, 0, 1200), forcedAt(20, 0, 1200)), 8192);
        assertEquals(plans + 1, tickets.plans());
        assertEquals(keeps + 1, world.keeps);
    }

    @Test void aNewChunkQueuesBehindTheHeldOnesWhateverItsCoordinates() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(forcedAt(10, 0, 1200), forcedAt(20, 0, 1200)), 2 * BOX);
        assertEquals(2, tickets.holds());
        // A chunk at a smaller coordinate, forced later by the same owner, waits; it doesn't evict.
        tickets.reconcile(40, List.of(forcedAt(-100, 0, 1200), forcedAt(10, 0, 1200), forcedAt(20, 0, 1200)), 2 * BOX);
        assertTrue(world.resident.holds(CubeKey.pack(10, 1200 >> 4, 0)));
        assertTrue(world.resident.holds(CubeKey.pack(20, 1200 >> 4, 0)));
        assertFalse(world.resident.holds(CubeKey.pack(-100, 1200 >> 4, 0)));
        assertEquals(1, tickets.leftOut());
        // One removed: the waiting one moves in.
        tickets.reconcile(60, List.of(forcedAt(-100, 0, 1200), forcedAt(20, 0, 1200)), 2 * BOX);
        assertTrue(world.resident.holds(CubeKey.pack(-100, 1200 >> 4, 0)));
        assertEquals(0, tickets.leftOut());
    }

    @Test void ownersShareTheCap() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        List<Ask> alex = List.of(forcedAt(0, 0, 1200, "forced:alex"), forcedAt(10, 0, 1200, "forced:alex"), forcedAt(20, 0, 1200, "forced:alex"));
        tickets.reconcile(20, alex, 3 * BOX);
        assertEquals(3, tickets.holds());
        // Sam forces a chunk: Sam gets a share; Alex keeps the first two and gives up the newest.
        List<Ask> both = new ArrayList<>(alex);
        both.add(forcedAt(100, 0, 1200, "forced:sam"));
        tickets.reconcile(40, both, 3 * BOX);
        assertTrue(world.resident.holds(CubeKey.pack(100, 1200 >> 4, 0)), "Sam's area");
        assertTrue(world.resident.holds(CubeKey.pack(0, 1200 >> 4, 0)));
        assertTrue(world.resident.holds(CubeKey.pack(10, 1200 >> 4, 0)));
        assertFalse(world.resident.holds(CubeKey.pack(20, 1200 >> 4, 0)), "Alex's newest gives way");
        assertEquals(1, tickets.leftOut());
    }

    @Test void lapsedPortalExitsGiveWayToLiveOnesAndThenGo() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        BlockPos old = new BlockPos(-658_370, 1210, -224_460), live = new BlockPos(1_000, 80, 1_000);
        tickets.reconcile(20, List.of(portalAt(old)), 8192);
        assertEquals(25 * 5, tickets.heldCubes(), "5x5 columns round the exit, two cubes up and down");
        tickets.reconcile(40, List.of(), 8192);
        assertEquals(1, tickets.holds(), "an item still on its way back finds its cubes");
        // Room for one exit: a live one beats the lapsed one.
        tickets.reconcile(60, List.of(portalAt(live)), 125);
        assertTrue(world.resident.holds(CubeKey.ofBlock(live)));
        assertFalse(world.resident.holds(CubeKey.ofBlock(old)));
        assertEquals(0, tickets.leftOut(), "a lapsed exit left out is not a loader left out");
        assertEquals(125, tickets.heldCubes(), "only what is held is counted");
        tickets.reconcile(60 + ChunkTickets.PORTAL_LINGER + 21, List.of(), 8192);
        tickets.reconcile(80 + ChunkTickets.PORTAL_LINGER + 21, List.of(), 8192);
        assertEquals(0, tickets.holds());
        assertEquals(0, world.resident.holders());
    }

    @Test void pastTheCapOperatorsAreToldOnceAndAgainWhenItFits() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        List<Ask> three = List.of(forcedAt(0, 0, 1200), forcedAt(10, 0, 1200), forcedAt(20, 0, 1200));
        tickets.reconcile(20, three, 250);
        assertEquals(2, tickets.holds(), "117 + 117 fit in 250; the third does not");
        assertEquals(1, tickets.leftOut());
        assertEquals(1, world.told.size());
        assertTrue(world.told.get(0).contains("chunkLoaderCubeLimit"), world.told.get(0));
        tickets.reconcile(40, three, 250);
        assertEquals(1, world.told.size(), "told once, not every pass");
        tickets.reconcile(60, three, 8192);
        assertEquals(3, tickets.holds());
        assertEquals(2, world.told.size(), "and told when it fits again");
        tickets.reconcile(80, three, 0);
        assertEquals(0, tickets.holds(), "0 keeps none");
        assertEquals(0, world.resident.holders());
    }

    @Test void overlappingLoadersCountSharedCubesOnce() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(forcedAt(0, 0, 1200), forcedAt(1, 0, 1200)), 4 * 3 * 13);
        assertEquals(2, tickets.holds(), "two side by side share their rings: 4 x 3 columns");
        assertEquals(4 * 3 * 13, tickets.heldCubes());
    }

    @Test void onlyCubesWithTheirGrowthReachLoadedRandomTick() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        int cy = 1200 >> 4;
        tickets.reconcile(20, List.of(forcedAt(0, 0, 1200)), 8192);
        long middle = CubeKey.pack(0, cy, 0);
        assertFalse(tickets.randomTicks(middle), "not before its neighbours are in");
        for (int t = 1; t < 20; t++) { tickets.tick(t, 384); world.deliver(); }
        assertTrue(tickets.randomTicks(middle));
        assertTrue(tickets.randomTicks(CubeKey.pack(0, cy - Cubic.VERTICAL_RADIUS + 1, 0)), "one above the bottom");
        assertFalse(tickets.randomTicks(CubeKey.pack(0, cy - Cubic.VERTICAL_RADIUS, 0)), "not the bottom layer");
        assertTrue(tickets.randomTicks(CubeKey.pack(0, cy + Cubic.VERTICAL_RADIUS - 2, 0)), "two below the top");
        assertFalse(tickets.randomTicks(CubeKey.pack(0, cy + Cubic.VERTICAL_RADIUS - 1, 0)), "a tree here would grow past the top");
        assertFalse(tickets.randomTicks(CubeKey.pack(1, cy, 0)), "nor the ring, whose outside isn't held");
        world.loaded.remove(CubeKey.pack(1, cy + 2, 1));
        assertFalse(tickets.randomTicks(CubeKey.pack(0, cy + 1, 0)), "a missing cube in reach stops it");
    }

    @Test void closingLetsGoOfEverything() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(forcedAt(0, 0, 1200), portalAt(new BlockPos(100, 64, 100))), 8192);
        assertEquals(2, world.resident.holders());
        tickets.close();
        assertEquals(0, world.resident.holders());
        tickets.reconcile(40, List.of(forcedAt(0, 0, 1200)), 8192);
        tickets.tick(41, 384);
        assertEquals(0, world.resident.holders(), "nothing after close");
        assertTrue(world.requested.isEmpty());
    }

    @Test void boxesStopAtTheTopAndBottomOfTheWorld() {
        long[] top = ChunkTickets.box(0, 0, 0, CubeWorld.MAX_Y >> 4, 6);
        assertEquals(7, top.length);
        assertTrue(new LongOpenHashSet(top).contains(CubeKey.pack(0, CubeWorld.MAX_Y >> 4, 0)));
        assertEquals(7, ChunkTickets.box(0, 0, 0, CubeWorld.MIN_Y >> 4, 6).length);
    }

    @Test void forcedHeightsKeepOneHeightAChunkUnlessForcedAgainFarAboveOrBelow() {
        ForcedHeights heights = new ForcedHeights();
        long chunk = ChunkPos.asLong(7, -3);
        heights.set(chunk, 1200, "alex");
        assertEquals(List.of(new ForcedHeights.Height(1200, "alex")), heights.of(chunk));
        assertFalse(heights.add(chunk, 1250, "alex"), "inside the height it keeps already");
        assertTrue(heights.add(chunk, 1200 + 16 * (Cubic.VERTICAL_RADIUS + 1), "sam"), "far enough above: a second height");
        assertTrue(heights.add(chunk, -40, "alex"));
        assertEquals(List.of(-40, 1200, 1312), heights.of(chunk).stream().map(ForcedHeights.Height::y).toList());
        assertEquals("sam", heights.of(chunk).get(2).owner());
        heights.set(chunk, null, ForcedHeights.SERVER); // forced anew from the console
        assertNull(heights.of(chunk));
        assertFalse(heights.add(chunk, 1200, "alex"), "a ground-band chunk stays one");
    }

    /** The repro: a chunk forced, then forced again for a second height before the first save. */
    @Test void forcingAChunkAgainNeverUnsavesTheFirstForce() {
        ForcedChunksSavedData earth = new ForcedChunksSavedData(), overworld = new ForcedChunksSavedData();
        for (ForcedChunksSavedData data : List.of(earth, overworld)) {
            data.getChunks().add(ChunkPos.asLong(7, -3));
            data.setDirty(ChunkTickets.forcedDirty(true, data.isDirty(), data == earth)); // forced at Y 1200
            data.setDirty(ChunkTickets.forcedDirty(false, data.isDirty(), data == earth)); // again at Y 2500: no change to the list
        }
        assertTrue(earth.isDirty(), "the Earth's forced chunks are still saved");
        assertFalse(overworld.isDirty(), "vanilla's own behaviour elsewhere is unchanged");
    }

    @Test void aChunkWithEarthHeightsMissingFromTheForcedListIsForcedAgainAtLoad() {
        ForcedHeights heights = new ForcedHeights();
        long kept = ChunkPos.asLong(7, -3), lost = ChunkPos.asLong(8, -3);
        heights.set(kept, 1200, "alex");
        heights.set(lost, 2500, "alex");
        LongOpenHashSet forced = new LongOpenHashSet(new long[]{kept});
        // The first look after a restart: the lost one is forced again, not dropped.
        assertArrayEquals(new long[]{lost}, ChunkTickets.reconcileForced(forced, heights, null));
        assertEquals(2, heights.size());
        // Later, a chunk unforced in play (a mod, /forceload remove all) forgets its heights.
        forced.add(lost);
        LongOpenHashSet seen = new LongOpenHashSet(forced);
        forced.remove(kept);
        assertEquals(0, ChunkTickets.reconcileForced(forced, heights, seen).length);
        assertNull(heights.of(kept));
        assertEquals(1, heights.size());
    }
}
