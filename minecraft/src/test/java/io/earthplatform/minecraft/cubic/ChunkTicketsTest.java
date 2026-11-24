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
import org.junit.jupiter.api.Test;

/** Chunk tickets in the Earth keep the cubes round the height they mean, loaded through the workers, within the cap, and let go with the ticket. */
class ChunkTicketsTest {
    /** A ServerCubeWorld stand-in: loaded cubes, a worker queue, a ResidentCubes, the ground, and what operators were told. */
    private static final class World implements ChunkTickets.Host {
        final LongSet loaded = new LongLinkedOpenHashSet();
        final LongArrayList requested = new LongArrayList();
        final ResidentCubes resident = new ResidentCubes();
        final List<String> told = new ArrayList<>();
        final ArrayDeque<Runnable> io = new ArrayDeque<>();
        int groundY = 1180;
        boolean groundMissing;
        int groundAsks;

        @Override public boolean loaded(long key) { return loaded.contains(key); }
        @Override public boolean request(long key) { return !loaded.contains(key) && !requested.contains(key) && requested.add(key); }
        @Override public void keep(Object holder, long[] keys) { resident.keep(holder, keys); }
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

    private static Ask forcedAt(int chunkX, int chunkZ, int y) {
        return new Ask(new Hold(Kind.FORCED, ChunkPos.asLong(chunkX, chunkZ), y), chunkX, chunkZ, ChunkTickets.FORCED_REACH, y, ChunkTickets.FORCED_V);
    }

    private static Ask portalAt(BlockPos at) {
        return new Ask(new Hold(Kind.PORTAL, ChunkPos.asLong(at.getX() >> 4, at.getZ() >> 4), at), at.getX() >> 4, at.getZ() >> 4,
                ChunkTickets.PORTAL_REACH, at.getY(), ChunkTickets.PORTAL_V);
    }

    @Test void aForcedChunkKeepsItsRingRoundTheHeightItWasForcedFrom() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(forcedAt(-41_000, 2_900, 1200)), 8192);
        assertEquals(1, tickets.holds());
        assertEquals(3 * 3 * (2 * Cubic.VERTICAL_RADIUS + 1), tickets.heldCubes(), "the chunk, its ring, 6 cubes up and down");
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
        int total = tickets.heldCubes();
        int ticks = 0;
        while (world.loaded.size() < total) {
            tickets.tick(384);
            assertTrue(world.requested.size() <= ChunkTickets.REQUESTS_PER_TICK, "never more than a few a tick");
            world.deliver();
            if (++ticks % 2 == 0) assertEquals(0, world.pass(), "a held cube was dropped while the rest came in");
            assertTrue(ticks < 100);
        }
        assertEquals(total, world.loaded.size());
        for (int i = 0; i < 50; i++) assertEquals(0, world.pass());
        // With the workers full, nothing is asked.
        world.loaded.clear();
        tickets.reconcile(40, List.of(forcedAt(10, 10, 1200)), 8192);
        tickets.tick(0);
        assertTrue(world.requested.isEmpty());
    }

    @Test void removingTheTicketLetsGoOfItsCubes() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(forcedAt(5, 5, 1200), forcedAt(5, 5, 3000)), 8192);
        for (int i = 0; i < 20; i++) { tickets.tick(384); world.deliver(); }
        int held = world.loaded.size();
        assertEquals(2, world.resident.holders());
        tickets.reconcile(40, List.of(forcedAt(5, 5, 3000)), 8192); // one height removed
        assertEquals(1, world.resident.holders());
        assertEquals(held / 2, world.pass(), "the removed height's cubes go at the next pass");
        tickets.reconcile(60, List.of(), 8192); // /forceload remove
        assertEquals(0, world.resident.holders());
        assertEquals(held / 2, world.pass());
        assertEquals(0, world.loaded.size());
    }

    @Test void aChunkForcedWithNoHeightKeepsItsGroundWorkedOutOffTheServerThread() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        Ask console = new Ask(new Hold(Kind.FORCED, ChunkPos.asLong(3, 4), ChunkTickets.GROUND), 3, 4, ChunkTickets.FORCED_REACH,
                ChunkTickets.GROUND, ChunkTickets.FORCED_V);
        tickets.reconcile(20, List.of(console), 8192);
        assertEquals(0, tickets.holds(), "nothing until the ground is known");
        assertEquals(0, world.groundAsks, "the ground is not read here");
        assertNull(tickets.band(3, 4, ChunkTickets.GROUND));
        world.runIo();
        assertEquals(1, world.groundAsks);
        tickets.reconcile(40, List.of(console), 8192);
        assertEquals(1, tickets.holds());
        assertTrue(world.resident.holds(CubeKey.pack(3, 1180 >> 4, 4)));
        assertArrayEquals(new int[]{((1180 >> 4) - 6) << 4, (((1180 >> 4) + 6) << 4) + 15}, tickets.band(3, 4, ChunkTickets.GROUND));
        tickets.reconcile(60, List.of(console), 8192);
        assertEquals(1, world.groundAsks, "asked once");
    }

    @Test void aGroundWhoseMapDataIsMissingIsAskedAgainLater() {
        World world = new World();
        world.groundMissing = true;
        ChunkTickets tickets = tickets(world);
        Ask console = new Ask(new Hold(Kind.FORCED, ChunkPos.asLong(3, 4), ChunkTickets.GROUND), 3, 4, 1, ChunkTickets.GROUND, 6);
        tickets.reconcile(20, List.of(console), 8192);
        world.runIo();
        tickets.reconcile(40, List.of(console), 8192);
        world.runIo();
        assertEquals(1, world.groundAsks, "not asked again at once");
        world.groundMissing = false;
        tickets.reconcile(20 + ChunkTickets.GROUND_RETRY + 1, List.of(console), 8192);
        world.runIo();
        tickets.reconcile(20 + ChunkTickets.GROUND_RETRY + 21, List.of(console), 8192);
        assertEquals(2, world.groundAsks);
        assertEquals(1, tickets.holds());
    }

    @Test void pastTheCapLaterLoadersAreLeftOutAndOperatorsAreToldOnce() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        List<Ask> three = List.of(forcedAt(0, 0, 1200), forcedAt(10, 0, 1200), forcedAt(20, 0, 1200));
        tickets.reconcile(20, three, 250);
        assertEquals(2, tickets.holds(), "117 + 117 fit in 250; the third does not");
        assertTrue(tickets.heldCubes() <= 250);
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

    @Test void aPortalExitStaysHeldALoopAfterItsTicketLapsesThenGoes() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        BlockPos exit = new BlockPos(-658_370, 1210, -224_460);
        tickets.reconcile(20, List.of(portalAt(exit)), 8192);
        assertEquals(25 * 5, tickets.heldCubes(), "5x5 columns round the exit, two cubes up and down");
        assertTrue(world.resident.holds(CubeKey.ofBlock(exit)));
        tickets.reconcile(20 + ChunkTickets.PORTAL_LINGER, List.of(), 8192);
        assertEquals(1, tickets.holds(), "an item still on its way back finds its cubes");
        tickets.reconcile(40 + ChunkTickets.PORTAL_LINGER, List.of(), 8192);
        assertEquals(0, tickets.holds());
        assertEquals(0, world.resident.holders());
    }

    @Test void closingLetsGoOfEverything() {
        World world = new World();
        ChunkTickets tickets = tickets(world);
        tickets.reconcile(20, List.of(forcedAt(0, 0, 1200), portalAt(new BlockPos(100, 64, 100))), 8192);
        assertEquals(2, world.resident.holders());
        tickets.close();
        assertEquals(0, world.resident.holders());
        tickets.reconcile(40, List.of(forcedAt(0, 0, 1200)), 8192);
        tickets.tick(384);
        assertEquals(0, world.resident.holders(), "nothing after close");
        assertTrue(world.requested.isEmpty());
    }

    @Test void boxesStopAtTheTopAndBottomOfTheWorld() {
        long[] top = ChunkTickets.box(0, 0, 0, CubeWorld.MAX_Y >> 4, 6);
        assertEquals(7, top.length);
        LongOpenHashSet keys = new LongOpenHashSet(top);
        assertTrue(keys.contains(CubeKey.pack(0, CubeWorld.MAX_Y >> 4, 0)));
        assertEquals(7, ChunkTickets.box(0, 0, 0, CubeWorld.MIN_Y >> 4, 6).length);
    }

    @Test void forcedHeightsKeepOneHeightAChunkUnlessForcedAgainFarAboveOrBelow() {
        ForcedHeights heights = new ForcedHeights();
        long chunk = ChunkPos.asLong(7, -3);
        heights.set(chunk, 1200);
        assertArrayEquals(new int[]{1200}, heights.of(chunk));
        assertFalse(heights.add(chunk, 1250), "inside the height it keeps already");
        assertTrue(heights.add(chunk, 1200 + 16 * (Cubic.VERTICAL_RADIUS + 1)), "far enough above: a second height");
        assertTrue(heights.add(chunk, -40));
        assertArrayEquals(new int[]{-40, 1200, 1312}, heights.of(chunk));
        heights.set(chunk, null); // forced anew from the console
        assertNull(heights.of(chunk));
        assertFalse(heights.add(chunk, 1200), "a ground-band chunk stays one");
        heights.set(chunk, 1200);
        heights.set(ChunkPos.asLong(8, -3), 64);
        heights.retain(new LongOpenHashSet(new long[]{chunk}));
        assertEquals(1, heights.size(), "removed chunks forget their heights");
        heights.clear(chunk);
        assertEquals(0, heights.size());
    }
}
