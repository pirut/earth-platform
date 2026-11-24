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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import org.junit.jupiter.api.Test;

/** Chunk tickets in the Earth keep the cubes round the height they mean, loaded through the workers, first come first served under the cap, and let go with the ticket. */
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
        /** Chunk columns whose ground read fails, as while their map data is missing. */
        final LongOpenHashSet failing = new LongOpenHashSet();

        @Override public boolean loaded(long key) { return loaded.contains(key); }
        @Override public boolean request(long key) { return !loaded.contains(key) && !requested.contains(key) && requested.add(key); }
        @Override public void keep(Object holder, long[] keys) { keeps++; resident.keep(holder, keys); }
        @Override public void release(Object holder) { resident.release(holder); }
        @Override public int groundY(int x, int z) {
            groundAsks++;
            if (groundMissing || failing.contains(ChunkPos.asLong(x >> 4, z >> 4))) throw new IllegalStateException("map data not in yet");
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

    /** A chunk forced at block Y {@code y}, {@code seq}th in the queue. */
    private static Ask forcedAt(int chunkX, int chunkZ, int y, long seq) {
        return new Ask(new Hold(Kind.FORCED, ChunkPos.asLong(chunkX, chunkZ), y), seq, chunkX, chunkZ, ChunkTickets.FORCED_REACH, y, ChunkTickets.FORCED_V);
    }

    private static Ask console(int chunkX, int chunkZ, long seq) { return forcedAt(chunkX, chunkZ, ChunkTickets.GROUND, seq); }

    private static Ask portalAt(BlockPos at) {
        return new Ask(new Hold(Kind.PORTAL, ChunkPos.asLong(at.getX() >> 4, at.getZ() >> 4), at), -1, at.getX() >> 4, at.getZ() >> 4,
                ChunkTickets.PORTAL_REACH, at.getY(), ChunkTickets.PORTAL_V);
    }

    private static boolean holdsChunk(World world, int chunkX, int chunkZ, int y) { return world.resident.holds(CubeKey.pack(chunkX, y >> 4, chunkZ)); }

    private static final int BOX = 3 * 3 * (2 * Cubic.VERTICAL_RADIUS + 1);

    @Test void aForcedChunkKeepsItsRingRoundTheHeightItWasForcedFrom() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(forcedAt(-41_000, 2_900, 1200, 0)), 8192);
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
        tickets.reconcile(20, List.of(forcedAt(10, 10, 1200, 0), forcedAt(12, 10, 1200, 1)), 8192);
        int total = tickets.heldCubes(), ticks = 0;
        while (world.loaded.size() < total) {
            tickets.tick(++ticks, 384);
            assertTrue(world.requested.size() <= ChunkTickets.REQUESTS_PER_TICK, "never more than a few a tick");
            world.deliver();
            if (ticks % 2 == 0) assertEquals(0, world.pass(), "a held cube was dropped while the rest came in");
            assertTrue(ticks < 100);
        }
        for (int i = 0; i < 50; i++) assertEquals(0, world.pass());
        world.loaded.clear();
        tickets.tick(ChunkTickets.SWEEP, 0);
        assertTrue(world.requested.isEmpty(), "with the workers full, nothing is asked");
        tickets.tick(ChunkTickets.SWEEP * 2, 384);
        assertEquals(ChunkTickets.REQUESTS_PER_TICK, world.requested.size(), "a cube gone missing is asked again at the next sweep");
    }

    @Test void removingTheTicketLetsGoOfItsCubes() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(forcedAt(5, 5, 1200, 0), forcedAt(5, 5, 3000, 1)), 8192);
        for (int i = 1; i <= 20; i++) { tickets.tick(i, 384); world.deliver(); }
        int held = world.loaded.size();
        assertEquals(2, world.resident.holders());
        tickets.reconcile(40, List.of(forcedAt(5, 5, 3000, 1)), 8192); // one height removed
        assertEquals(1, world.resident.holders());
        assertEquals(held / 2, world.pass(), "the removed height's cubes go at the next pass");
        assertTrue(tickets.holdsColumn(CubeKey.column(5, 5)));
        tickets.reconcile(60, List.of(), 8192); // /forceload remove
        assertFalse(tickets.holdsColumn(CubeKey.column(5, 5)));
        assertEquals(0, world.resident.holders());
        assertEquals(0, tickets.heldCubes());
        assertEquals(held / 2, world.pass());
    }

    @Test void aChunkForcedWithNoHeightKeepsItsGroundReadOnTheWorkers() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(console(3, 4, 0)), 8192);
        assertEquals(0, tickets.holds(), "nothing until the ground is known");
        assertEquals(0, world.groundAsks, "the ground is not read here");
        assertNull(tickets.band(3, 4, ChunkTickets.GROUND));
        tickets.tick(21, 384);
        assertEquals(1, world.io.size(), "handed to the workers");
        world.runIo();
        tickets.reconcile(40, List.of(console(3, 4, 0)), 8192);
        assertEquals(1, tickets.holds());
        assertTrue(holdsChunk(world, 3, 4, 1180));
        assertArrayEquals(new int[]{((1180 >> 4) - 6) << 4, (((1180 >> 4) + 6) << 4) + 15}, tickets.band(3, 4, ChunkTickets.GROUND));
        tickets.reconcile(60, List.of(console(3, 4, 0)), 8192);
        tickets.tick(61, 384);
        assertEquals(1, world.groundAsks, "read once");
    }

    @Test void aGroundWhoseMapDataIsMissingIsReadAgainLater() {
        World world = new World();
        world.groundMissing = true;
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(console(3, 4, 0)), 8192);
        tickets.tick(21, 384);
        world.runIo();
        tickets.reconcile(40, List.of(console(3, 4, 0)), 8192);
        tickets.tick(41, 384);
        world.runIo();
        assertEquals(1, world.groundAsks, "not read again at once");
        world.groundMissing = false;
        tickets.reconcile(40 + ChunkTickets.GROUND_RETRY + 1, List.of(console(3, 4, 0)), 8192);
        tickets.tick(42 + ChunkTickets.GROUND_RETRY, 384);
        world.runIo();
        tickets.reconcile(60 + ChunkTickets.GROUND_RETRY, List.of(console(3, 4, 0)), 8192);
        assertEquals(2, world.groundAsks);
        assertEquals(1, tickets.holds());
    }

    /** A big save: a thousand chunks forced from the console, none with a height, spread out. */
    private static List<Ask> thousandConsoleChunks() {
        List<Ask> asks = new ArrayList<>();
        for (int i = 0; i < 1000; i++) asks.add(console((i % 40) * 4, (i / 40) * 4, i));
        return asks;
    }

    @Test void groundsAreReadOnlyAtTheFrontOfTheQueueAFewATick() {
        World world = new World();
        ChunkTickets none = tickets(world);
        List<Ask> asks = thousandConsoleChunks();
        for (int t = 20; t < 600; t++) {
            world.runIo();
            if (t % ChunkTickets.EVERY == 0) none.reconcile(t, asks, 0);
            none.tick(t, 384);
        }
        assertTrue(none.groundReads() <= ChunkTickets.LOOKAHEAD, "a cap of 0: only the front of the queue, once: " + none.groundReads());
        assertEquals(0, none.holds());

        World busy = new World();
        ChunkTickets tickets = tickets(busy);
        tickets.reconcile(20, asks, 8192);
        tickets.tick(21, 384);
        assertTrue(busy.io.size() <= ChunkTickets.GROUND_PER_TICK, "a few started a tick");
        for (int t = 22; t < 60; t++) tickets.tick(t, 384);
        assertTrue(busy.io.size() <= ChunkTickets.GROUND_IN_FLIGHT, "and only a few running at once");
        for (int t = 60; t < 6000; t++) {
            busy.runIo();
            if (t % ChunkTickets.EVERY == 0) tickets.reconcile(t, asks, 8192);
            tickets.tick(t, 384);
        }
        int fits = 8192 / BOX;
        assertEquals(fits, tickets.holds(), "as many as fit");
        assertTrue(tickets.groundReads() <= fits + ChunkTickets.LOOKAHEAD, "grounds read only at the front: " + tickets.groundReads());
        assertEquals(1000 - fits, tickets.leftOut());
    }

    @Test void unchangedTicketsCostNoPlanning() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        List<Ask> asks = List.of(forcedAt(0, 0, 1200, 0), forcedAt(10, 0, 1200, 1));
        tickets.reconcile(20, asks, 8192);
        int keeps = world.keeps, plans = tickets.plans();
        for (int t = 40; t < 2000; t += 20) tickets.reconcile(t, List.copyOf(asks), 8192);
        assertEquals(plans, tickets.plans(), "planned once");
        assertEquals(keeps, world.keeps);
        tickets.reconcile(2000, List.of(forcedAt(0, 0, 1200, 0), forcedAt(10, 0, 1200, 1), forcedAt(20, 0, 1200, 2)), 8192);
        assertEquals(plans + 1, tickets.plans());
        assertEquals(keeps + 1, world.keeps, "only the new hold is made");
    }

    // Sol's three: cap 156 and cap 117, two chunks side by side.

    @Test void theAdjacentSecondChunkIsLetInForTheCubesItAdds() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(forcedAt(0, 0, 1200, 0)), 156);
        assertEquals(BOX, tickets.heldCubes());
        tickets.reconcile(40, List.of(forcedAt(0, 0, 1200, 0), forcedAt(1, 0, 1200, 1)), 156);
        assertEquals(2, tickets.holds(), "117 held, and the neighbour adds only its far column of 3 x 13 = 39: 156");
        assertEquals(156, tickets.heldCubes());
        assertEquals(0, tickets.leftOut());
    }

    @Test void aLoaderThatDoesNotFitWaitsAndNothingHeldIsPutOut() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(forcedAt(0, 0, 1200, 0)), 117);
        tickets.reconcile(40, List.of(forcedAt(0, 0, 1200, 0), forcedAt(1, 0, 1200, 1)), 117);
        assertEquals(1, tickets.holds());
        assertTrue(holdsChunk(world, -1, 0, 1200), "the first keeps its cubes");
        assertFalse(holdsChunk(world, 2, 0, 1200), "the second waits");
        assertEquals(1, tickets.leftOut());
        // At 156, a third side by side would add 39 more: it waits too, and both held stay.
        World wide = new World();
        ChunkTickets three = tickets(wide);
        three.reconcile(20, List.of(forcedAt(0, 0, 1200, 0), forcedAt(1, 0, 1200, 1), forcedAt(2, 0, 1200, 2)), 156);
        assertEquals(2, three.holds());
        assertEquals(156, three.heldCubes());
        assertEquals(1, three.leftOut());
        // The first removed: the waiting one moves in, the second keeps its cubes throughout.
        tickets.reconcile(60, List.of(forcedAt(1, 0, 1200, 1)), 117);
        assertTrue(holdsChunk(world, 2, 0, 1200));
        assertEquals(0, tickets.leftOut());
    }

    @Test void theOrderIsTheSameAfterARestart() {
        // The same saved places, read back in another order (a hash set's), and coordinates that sort the other way.
        List<Ask> saved = List.of(forcedAt(1, 0, 1200, 1), forcedAt(0, 0, 1200, 0));
        for (int cap : new int[]{117, 156}) {
            World before = new World(), after = new World();
            ChunkTickets first = tickets(before), restarted = tickets(after);
            first.reconcile(20, List.of(forcedAt(0, 0, 1200, 0)), cap);
            first.reconcile(40, List.of(forcedAt(0, 0, 1200, 0), forcedAt(1, 0, 1200, 1)), cap);
            restarted.reconcile(20, saved, cap);
            assertEquals(first.describeHolds(), restarted.describeHolds(), "cap " + cap);
            assertEquals(first.leftOut(), restarted.leftOut());
        }
        // And the places themselves are saved: the next keeps counting up.
        ForcedHeights heights = new ForcedHeights();
        heights.ensure(ChunkPos.asLong(0, 0));
        heights.set(ChunkPos.asLong(0, 0), 1200);
        heights.ensure(ChunkPos.asLong(1, 0));
        ForcedHeights loaded = ForcedHeights.load(heights.save(new CompoundTag(), null), null);
        assertEquals(heights.of(ChunkPos.asLong(0, 0)), loaded.of(ChunkPos.asLong(0, 0)));
        assertEquals(heights.of(ChunkPos.asLong(1, 0)), loaded.of(ChunkPos.asLong(1, 0)));
        assertEquals(2, loaded.nextSeq());
    }

    @Test void aNewChunkQueuesBehindTheHeldOnesWhateverItsCoordinates() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(forcedAt(10, 0, 1200, 0), forcedAt(20, 0, 1200, 1)), 2 * BOX);
        tickets.reconcile(40, List.of(forcedAt(-100, 0, 1200, 2), forcedAt(10, 0, 1200, 0), forcedAt(20, 0, 1200, 1)), 2 * BOX);
        assertTrue(holdsChunk(world, 10, 0, 1200));
        assertTrue(holdsChunk(world, 20, 0, 1200));
        assertFalse(holdsChunk(world, -100, 0, 1200));
        tickets.reconcile(60, List.of(forcedAt(-100, 0, 1200, 2), forcedAt(20, 0, 1200, 1)), 2 * BOX);
        assertTrue(holdsChunk(world, -100, 0, 1200), "one removed: the waiting one moves in");
    }

    @Test void lapsedPortalExitsGiveWayAndThenGo() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        BlockPos old = new BlockPos(-658_370, 1210, -224_460), live = new BlockPos(1_000, 80, 1_000);
        tickets.reconcile(20, List.of(portalAt(old)), 8192);
        assertEquals(25 * 5, tickets.heldCubes(), "5x5 columns round the exit, two cubes up and down");
        tickets.reconcile(40, List.of(), 8192);
        assertEquals(1, tickets.holds(), "an item still on its way back finds its cubes");
        tickets.reconcile(60, List.of(portalAt(live)), 125);
        assertTrue(world.resident.holds(CubeKey.ofBlock(live)), "a live exit takes the lapsed one's room");
        assertFalse(world.resident.holds(CubeKey.ofBlock(old)));
        assertEquals(125, tickets.heldCubes());
        // A live hold is never put out: a forced chunk now waits.
        tickets.reconcile(80, List.of(portalAt(live), forcedAt(0, 0, 1200, 0)), 125);
        assertTrue(world.resident.holds(CubeKey.ofBlock(live)));
        assertEquals(1, tickets.leftOut());
        tickets.reconcile(100, List.of(forcedAt(0, 0, 1200, 0)), 8192);
        tickets.reconcile(100 + ChunkTickets.PORTAL_LINGER + 1, List.of(forcedAt(0, 0, 1200, 0)), 8192);
        assertEquals(1, tickets.holds(), "the lapsed exit goes after its linger");
    }

    @Test void pastTheCapOperatorsAreToldOnceAndAgainWhenItFits() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        List<Ask> three = List.of(forcedAt(0, 0, 1200, 0), forcedAt(10, 0, 1200, 1), forcedAt(20, 0, 1200, 2));
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
        tickets.reconcile(80, three, 117);
        assertEquals(1, tickets.holds(), "a lower cap lets go of the newest first");
        assertTrue(holdsChunk(world, 0, 0, 1200));
        tickets.reconcile(100, three, 0);
        assertEquals(0, tickets.holds(), "0 keeps none");
        assertEquals(0, world.resident.holders());
    }

    @Test void onlyCubesWithTheirGrowthReachLoadedRandomTick() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        int cy = 1200 >> 4;
        tickets.reconcile(20, List.of(forcedAt(0, 0, 1200, 0)), 8192);
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

    @Test void weatherWaitsForAColumnsHeightWithNobodyNear() {
        assertFalse(ChunkTickets.weatherRuns(false, false), "a loader waiting for the cap: no cubes, no height, no weather");
        assertFalse(ChunkTickets.weatherRuns(false, false), "held, but its sky tops not read yet: no weather");
        assertTrue(ChunkTickets.weatherRuns(false, true), "held, its tops read off the server thread: snow and lightning as ever");
        assertTrue(ChunkTickets.weatherRuns(true, false), "a player near: as before");
    }

    @Test void closingLetsGoOfEverything() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(forcedAt(0, 0, 1200, 0), portalAt(new BlockPos(100, 64, 100))), 8192);
        assertEquals(2, world.resident.holders());
        tickets.close();
        assertEquals(0, world.resident.holders());
        tickets.reconcile(40, List.of(forcedAt(0, 0, 1200, 0)), 8192);
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
        heights.ensure(chunk); // forced: its ground band and its place
        long place = heights.of(chunk).get(0).seq();
        heights.set(chunk, 1200); // from Y 1200: the same place
        assertEquals(List.of(new ForcedHeights.Height(1200, place)), heights.of(chunk));
        assertFalse(heights.add(chunk, 1250), "inside the height it keeps already");
        assertTrue(heights.add(chunk, 1200 + 16 * (Cubic.VERTICAL_RADIUS + 1)), "far enough above: a second height, behind");
        assertTrue(heights.add(chunk, -40));
        assertEquals(List.of(-40, 1200, 1312), heights.of(chunk).stream().map(ForcedHeights.Height::y).toList());
        long ground = ChunkPos.asLong(8, -3);
        heights.ensure(ground);
        assertFalse(heights.add(ground, 1200), "a ground-band chunk stays one");
        // Chunks forced off while the mod was away lose their heights at load; the others keep theirs.
        assertEquals(1, heights.retain(new LongOpenHashSet(new long[]{chunk})));
        assertNull(heights.of(ground));
        assertEquals(3, heights.of(chunk).size());
    }

    // Sol's final check.

    @Test void aChunkRemovedAndForcedAgainBetweenTwoLooksIsQueuedOnceAndChargedOnce() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(forcedAt(0, 0, 1200, 0)), 117);
        assertEquals(117, tickets.heldCubes());
        // Removed and forced again at the same height before the next look: the same loader, a new place.
        tickets.reconcile(40, List.of(forcedAt(0, 0, 1200, 1)), 117);
        assertTrue(tickets.accountsAdd(), "queued or held once, and charged only for holds that exist");
        assertEquals(1, tickets.holds());
        assertEquals(117, tickets.heldCubes());
        assertEquals(1, world.resident.holders());
        for (int t = 60; t <= 200; t += 20) tickets.reconcile(t, List.of(forcedAt(0, 0, 1200, 1)), 117);
        assertTrue(tickets.accountsAdd());
        assertEquals(1, tickets.holds(), "never let in twice");
        tickets.reconcile(220, List.of(), 117);
        assertEquals(0, tickets.holds());
        assertEquals(0, tickets.heldCubes(), "nothing left charged once it is removed");
        assertEquals(0, world.resident.holders());
        assertTrue(tickets.accountsAdd());
        // And a re-add behind a waiting loader queues behind it, once.
        tickets.reconcile(240, List.of(forcedAt(0, 0, 1200, 2), forcedAt(5, 0, 1200, 3)), 117);
        tickets.reconcile(260, List.of(forcedAt(0, 0, 1200, 4), forcedAt(5, 0, 1200, 3)), 117);
        assertTrue(tickets.accountsAdd());
        assertEquals(1, tickets.holds());
        assertTrue(holdsChunk(world, 5, 0, 1200), "the waiting one was first now");
        assertEquals(117, tickets.heldCubes());
    }

    private static Ask entityAt(java.util.UUID id, int chunkX, int chunkZ, int y, long seq) {
        return new Ask(new Hold(Kind.MOD, ChunkPos.asLong(chunkX, chunkZ), id), seq, chunkX, chunkZ, ChunkTickets.FORCED_REACH, y, ChunkTickets.FORCED_V);
    }

    @Test void anEntityMovingInsideItsCubeKeepsItsHoldAndAnotherCubeIsHeldAtOnce() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        java.util.UUID turtle = new java.util.UUID(1, 2);
        tickets.reconcile(20, List.of(entityAt(turtle, 0, 0, 1200, 5)), 117);
        assertEquals(1, tickets.holds());
        // An older loader turns up and waits for room.
        tickets.reconcile(40, List.of(entityAt(turtle, 0, 0, 1200, 5), forcedAt(9, 9, 1200, 1)), 117);
        assertEquals(1, tickets.leftOut());
        int keeps = world.keeps;
        tickets.reconcile(60, List.of(entityAt(turtle, 0, 0, 1205, 5), forcedAt(9, 9, 1200, 1)), 117); // 1200 and 1205: cube 75 both
        assertEquals(keeps, world.keeps, "inside the same cube nothing changes");
        assertTrue(holdsChunk(world, 0, 0, 1200));
        assertFalse(holdsChunk(world, 9, 9, 1200), "the older waiting loader didn't take the room");
        tickets.reconcile(80, List.of(entityAt(turtle, 0, 0, 1300, 5), forcedAt(9, 9, 1200, 1)), 117); // into cube 81
        assertTrue(world.resident.holds(CubeKey.pack(0, 1300 >> 4, 0)), "held round its new cube at once");
        assertFalse(holdsChunk(world, 9, 9, 1200), "still in its place");
        assertEquals(117, tickets.heldCubes());
        assertTrue(tickets.accountsAdd());
    }

    @Test void groundReadsThatFailAtDifferentTimesAreEachTriedAgain() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        world.failing.add(ChunkPos.asLong(3, 4));
        world.failing.add(ChunkPos.asLong(30, 4));
        List<Ask> asks = List.of(console(3, 4, 0), console(30, 4, 1));
        tickets.reconcile(20, asks, 8192);
        tickets.tick(21, 384); // both reads start
        world.io.poll().run(); // the first fails by tick 40
        tickets.reconcile(40, asks, 8192);
        for (int t = 41; t < 100; t++) tickets.tick(t, 384);
        world.io.poll().run(); // the second fails by tick 100
        tickets.reconcile(100, asks, 8192);
        world.failing.clear(); // the map data comes in
        for (int t = 101; t <= 400; t++) {
            world.runIo();
            if (t % ChunkTickets.EVERY == 0) tickets.reconcile(t, asks, 8192);
            tickets.tick(t, 384);
        }
        assertEquals(2, tickets.holds(), "both let in once their ground is read again: retries due at 240 and 300");
        assertTrue(holdsChunk(world, 3, 4, 1180));
        assertTrue(holdsChunk(world, 30, 4, 1180));
    }

    /** Sol's round 1 repro: a chunk forced, then forced again for a second height before the first save. */
    @Test void forcingAChunkAgainNeverUnsavesTheFirstForce() {
        ForcedChunksSavedData earth = new ForcedChunksSavedData(), overworld = new ForcedChunksSavedData();
        for (ForcedChunksSavedData data : List.of(earth, overworld)) {
            data.getChunks().add(ChunkPos.asLong(7, -3));
            data.setDirty(ChunkTickets.forcedDirty(true, data.isDirty(), data == earth));
            data.setDirty(ChunkTickets.forcedDirty(false, data.isDirty(), data == earth));
        }
        assertTrue(earth.isDirty(), "the Earth's forced chunks are still saved");
        assertFalse(overworld.isDirty(), "vanilla's own behaviour elsewhere is unchanged");
    }
}
