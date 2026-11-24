package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntBinaryOperator;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for natural structure toggle ({@code generateStructures == false}) and surface slope/terrain
 * adaptation limits that keep surface structures off steep mountain walls.
 */
class VanillaStructuresTest {
    private static Holder<Biome> plains;
    private static RandomState randomState;

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var lookup = VanillaRegistries.createLookup();
        plains = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        NoiseGeneratorSettings noise = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        randomState = RandomState.create(noise, lookup.lookupOrThrow(Registries.NOISE), 1L);
    }

    // Issue #239: "No structures" world option ---------------------------------------------------

    @Test void shouldGenerateHonorsStructureManagerFlag() {
        StructureManager off = new StructureManager(null, new WorldOptions(1L, false, false), null);
        StructureManager on = new StructureManager(null, new WorldOptions(1L, true, false), null);
        assertFalse(VanillaStructures.shouldGenerate(off));
        assertTrue(VanillaStructures.shouldGenerate(on));
        assertTrue(VanillaStructures.shouldGenerate(null));
    }

    @Test void disabledStructuresProduceNoStartsLookupsOrClearing() {
        TestStructure surface = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.BEARD_THIN, 12);
        RegistryAccess registries = singleStructureRegistries("test_village", surface);
        VanillaStructures.Host failIfQueried = hostOf((x, z) -> { throw new AssertionError("ground queried when structures disabled"); });
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, false, failIfQueried, 1L);

        assertTrue(structures.startsAt(0, 0).isEmpty());
        assertNull(structures.startOf(surface, 0, 0));
        assertNull(structures.locate(s -> true, 8, 8, 4));
        assertFalse(structures.clears(8, 8));
        assertNull(structures.soleBiome(0, 6, 0));
        assertEquals("No structures here.", structures.describe(8, 100, 8));
        assertNotNull(structures.view(), "/place structure still needs a world view when natural generation is off");
        VanillaStructures manualOff = new VanillaStructures(null, registries, randomState, false, hostOf((x, z) -> 100), 1L);
        assertNotNull(manualOff.placed(surface, new ChunkPos(0, 0)), "/place structure still works when natural generation is off");

        IdMap<Holder<Biome>> biomeIds = new IdMap<>() {
            @Override public int getId(Holder<Biome> value) { return 0; }
            @Override public Holder<Biome> byId(int id) { return plains; }
            @Override public int size() { return 1; }
            @Override public java.util.Iterator<Holder<Biome>> iterator() { return List.of(plains).iterator(); }
        };
        LevelChunkSection section = new LevelChunkSection(
                new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
                new PalettedContainer<>(biomeIds, plains, PalettedContainer.Strategy.SECTION_BIOMES));
        List<CompoundTag> blockEntities = new ArrayList<>(), entities = new ArrayList<>();
        structures.place(section, 0, 6, 0, blockEntities, entities);
        assertTrue(section.hasOnlyAir());
        assertTrue(blockEntities.isEmpty());
        assertTrue(entities.isEmpty());
    }

    @Test void enabledStructuresGenerateOnFlatGroundAndRejectCliffWalls() {
        TestStructure surface = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.BEARD_THIN, 12);
        RegistryAccess registries = singleStructureRegistries("test_village", surface);

        // Flat terrain: starts normally.
        VanillaStructures flat = new VanillaStructures(null, registries, randomState, true, hostOf((x, z) -> 100), 1L);
        assertFalse(flat.startsAt(0, 0).isEmpty());
        assertNotNull(flat.startOf(surface, 0, 0));
        assertNotNull(flat.locate(s -> s == surface, 8, 8, 4));

        // Gentle 20-degree hill (slope ~0.36): still starts normally.
        VanillaStructures gentle = new VanillaStructures(null, registries, randomState, true,
                hostOf((x, z) -> 100 + (int) Math.round(0.36 * x)), 1L);
        assertFalse(gentle.startsAt(0, 0).isEmpty());

        // Steep 60-degree Karakoram wall (slope ~1.73): rejected.
        VanillaStructures wall = new VanillaStructures(null, registries, randomState, true,
                hostOf((x, z) -> 3000 + (int) Math.round(1.73 * x)), 1L);
        assertTrue(wall.startsAt(0, 0).isEmpty());
        assertNull(wall.startOf(surface, 0, 0));
        assertNull(wall.locate(s -> s == surface, 8, 8, 4));

        // Underground structures under the same 60-degree wall are still allowed.
        TestStructure underground = new TestStructure(plains, GenerationStep.Decoration.UNDERGROUND_STRUCTURES, TerrainAdjustment.NONE, 12);
        RegistryAccess undergroundReg = singleStructureRegistries("test_ruins", underground);
        VanillaStructures underWall = new VanillaStructures(null, undergroundReg, randomState, true,
                hostOf((x, z) -> 3000 + (int) Math.round(1.73 * x)), 1L);
        assertFalse(underWall.startsAt(0, 0).isEmpty());
    }

    // Legs over a void (YUNG's strongholds and zombie dungeons) ------------------------------

    @Test void layoutsAtVanillaDepthsPlaceDownToTheirFramesBottom() {
        // A stronghold whose pieces reach down to real Y 1,040, in a frame with vanilla's Y 0 at real Y 1,000.
        int low = VanillaStructures.lowestWrite(1040, -64, 1000);
        assertEquals(936, low, "down to the frame's bottom, where a leg over a void stops");
        assertTrue(VanillaStructures.placesInto(low, 1100, 936 >> 4), "the cube a leg ends in is placed into");
        assertFalse(VanillaStructures.placesInto(1040, 1100, 936 >> 4), "as it was: legs cut off at the pieces' reach");
        // Surface structures keep their reach, already some way under the ground.
        assertEquals(1040, VanillaStructures.lowestWrite(1040, io.earthplatform.minecraft.cubic.CubeWorld.MIN_Y, 0));
        assertFalse(VanillaStructures.placesInto(1040, 1100, 1200 >> 4), "nor above the top");
        // A frame at vanilla's depths where the local floor lies at vanilla's Y 64 is not shifted, but still ends at Y -64.
        int unshifted = VanillaStructures.lowestWrite(40, -64, 0);
        assertEquals(-64, unshifted, "legs over a void in an unshifted deep frame reach its bottom");
        assertTrue(VanillaStructures.placesInto(unshifted, 100, -64 >> 4));
        assertFalse(VanillaStructures.placesInto(40, 100, -64 >> 4), "as it was: cut off at the pieces' reach");
    }

    // Sets kept away from vanilla's stronghold rings (Dungeons and Taverns' shrines) -------------

    @Test void ringExclusionKeepsToTheStrongholdGridWithoutReadingFarBiomes() {
        TestStructure shrine = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE, 4);
        TestStructure stronghold = new TestStructure(plains, GenerationStep.Decoration.STRONGHOLDS, TerrainAdjustment.NONE, 4);
        RegistryAccess registries = ringExcludedRegistries(shrine, stronghold, 15);
        java.util.concurrent.atomic.AtomicInteger far = new java.util.concurrent.atomic.AtomicInteger();
        VanillaStructures.Host host = new VanillaStructures.Host() {
            final VanillaStructures.Host flat = hostOf((x, z) -> 100);
            @Override public int ground(int x, int z) { return 100; }
            @Override public int water(int x, int z) { return 62; }
            @Override public Holder<Biome> biome(int x, int y, int z) {
                // Vanilla's rings search biomes thousands of blocks out; on Earth each is a download.
                if (Math.abs(x) > 2048 || Math.abs(z) > 2048) far.incrementAndGet();
                return plains;
            }
            @Override public int floor(int x, int z) { return 100; }
            @Nullable @Override public LevelChunkSection cube(int cx, int cy, int cz) { return null; }
            @Override public BlockState fill(int x, int y, int z, int depth) { return Blocks.STONE.defaultBlockState(); }
            @Override public int seaLevel() { return 63; }
            @Override public Stream<Holder<Biome>> possibleBiomes() { return flat.possibleBiomes(); }
        };
        long seed = 1L;
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, true, host, seed);

        // Shrines may start in any chunk but within 15 chunks of a stronghold on this world's grid.
        RandomSpreadStructurePlacement grid = VanillaStructures.strongholdGrid();
        ChunkPos stronghold0 = grid.getPotentialStructureChunk(seed, 0, 0);
        StructureStart onStronghold = structures.startOf(shrine, stronghold0.x, stronghold0.z);
        assertEquals(0, far.get(), "biomes read far from the chunks asked about, for vanilla's rings");
        assertNull(onStronghold, "a shrine on a stronghold of this world's grid");
        assertNull(structures.startOf(shrine, stronghold0.x + 15, stronghold0.z - 15), "a shrine 15 chunks from one");
        ChunkPos clear = null;
        for (int cx = 0; cx < 256 && clear == null; cx++) {
            boolean near = false;
            for (int gz = -1; gz <= 1 && !near; gz++) for (int gx = -1; gx <= 5 && !near; gx++) {
                ChunkPos s = grid.getPotentialStructureChunk(seed, gx * grid.spacing(), gz * grid.spacing());
                near = Math.abs(s.x - cx) <= 15 && Math.abs(s.z) <= 15;
            }
            if (!near) clear = new ChunkPos(cx, 0);
        }
        assertNotNull(clear);
        assertNotNull(structures.startOf(shrine, clear.x, clear.z), "a shrine more than 15 chunks from every stronghold");
        assertFalse(structures.startsAt(clear.x, clear.z).isEmpty());
        assertEquals(0, far.get(), "biomes read far from the chunks asked about");
    }

    // Players' ticks ask which structure they stand in (YUNG's desert temples, every tick) -----

    @Test void serverThreadLookupsAnswerFromKnownStartsAndLayTheRestOutAside() throws InterruptedException {
        TestStructure surface = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.BEARD_THIN, 12);
        RegistryAccess registries = singleStructureRegistries("test_temple", surface);
        Thread server = Thread.currentThread();
        java.util.concurrent.atomic.AtomicInteger readOnServer = new java.util.concurrent.atomic.AtomicInteger();
        VanillaStructures.Host host = hostOf((x, z) -> {
            if (Thread.currentThread() == server) readOnServer.incrementAndGet();
            return 100;
        });
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, true, host, 1L);
        structures.serverThread(() -> Thread.currentThread() == server);

        // Nothing known yet: answered at once, with nothing laid out on the server thread.
        assertTrue(structures.startsAt(0, 0).isEmpty());
        assertNull(structures.startOf(surface, 0, 0));
        assertEquals(0, readOnServer.get(), "terrain read on the server thread for a lookup");

        // Laid out aside, then known: in the end every start another thread lays out there.
        int all = new VanillaStructures(null, registries, randomState, true, hostOf((x, z) -> 100), 1L).startsAt(0, 0).size();
        assertTrue(all > 0);
        List<StructureStart> found = List.of();
        for (long until = System.currentTimeMillis() + 20_000; found.size() < all && System.currentTimeMillis() < until; Thread.sleep(20))
            found = structures.startsAt(0, 0);
        assertEquals(all, found.size(), "starts laid out aside for the server thread");
        assertNotNull(structures.startOf(surface, 0, 0));
        assertEquals(0, readOnServer.get(), "terrain read on the server thread for a lookup");

        // Other threads still lay starts out themselves, the same ones, and only those of the structures asked about.
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        VanillaStructures elsewhere = new VanillaStructures(null, registries, randomState, true, hostOf((x, z) -> { reads.incrementAndGet(); return 100; }), 1L);
        assertTrue(elsewhere.startsAt(0, 0, structure -> structure != surface).isEmpty());
        assertEquals(0, reads.get(), "terrain read laying out structures nobody asked about");
        assertEquals(found.size(), elsewhere.startsAt(0, 0, structure -> structure == surface).size());
        assertEquals(found.size(), elsewhere.startsAt(0, 0).size());
    }

    @Test void serverThreadLookupsFindTheStructureFromAnyGeneratedColumnItReaches() {
        // A temple 40 blocks across from chunk 0, 0, its cubes generated: the start is laid out.
        TestStructure temple = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE, 40);
        RegistryAccess registries = singleStructureRegistries("test_desert_temple", temple);
        java.util.concurrent.atomic.AtomicBoolean asServer = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger readOnServer = new java.util.concurrent.atomic.AtomicInteger();
        Thread server = Thread.currentThread();
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, true, hostOf((x, z) -> {
            if (asServer.get() && Thread.currentThread() == server) readOnServer.incrementAndGet();
            return 100;
        }), 1L);
        structures.serverThread(() -> asServer.get() && Thread.currentThread() == server);
        // Its cubes generated: each cube lays out the starts reaching its own column, as its terrain asks.
        structures.clears(8, 8);
        structures.clears(2 * 16 + 8, 8);

        // The first lookup comes from a player two chunks from where it started, inside it (YUNG's Pharaoh dying there).
        asServer.set(true);
        List<StructureStart> found = structures.startsAt(2, 0, structure -> structure == temple);
        assertTrue(found.stream().anyMatch(start -> start.getChunkPos().equals(new ChunkPos(0, 0))), "the temple reaching the player's column");
        assertTrue(found.stream().allMatch(start -> start.getStructure() == temple));
        assertEquals(0, readOnServer.get(), "terrain read on the server thread for a lookup");
    }

    @Test void spawnOverrideLookupsInsideALaidOutStructureAnswerOnTheFirstCall() {
        // Mob spawns ask for every structure at a place, then each start by the chunk it started in (getMobsAt).
        TestStructure monument = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE, 40);
        RegistryAccess registries = singleStructureRegistries("test_monument", monument);
        java.util.concurrent.atomic.AtomicBoolean asServer = new java.util.concurrent.atomic.AtomicBoolean();
        Thread server = Thread.currentThread();
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, true, hostOf((x, z) -> 100), 1L);
        structures.serverThread(() -> asServer.get() && Thread.currentThread() == server);
        structures.clears(8, 8);
        structures.clears(16 + 8, 16 + 8); // the generated column the mob would spawn in

        asServer.set(true);
        assertFalse(structures.startsAt(1, 1).isEmpty(), "every structure at the place");
        StructureStart start = structures.startOf(monument, 0, 0);
        assertNotNull(start, "the start by its reference, on the first call");
        assertEquals(new ChunkPos(0, 0), start.getChunkPos());
    }

    @Test void closingTheWorldDropsQueuedLookups() throws InterruptedException {
        TestStructure surface = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE, 12);
        RegistryAccess registries = singleStructureRegistries("test_hut", surface);
        Thread server = Thread.currentThread();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger held = new java.util.concurrent.atomic.AtomicInteger();
        java.util.Set<Integer> laidOut = java.util.concurrent.ConcurrentHashMap.newKeySet();
        VanillaStructures.Host flat = hostOf((x, z) -> {
            if (Thread.currentThread() != server) laidOut.add(Math.floorDiv(x >> 4, 500)); // which of the far-apart places
            return 100;
        });
        VanillaStructures.Host host = new VanillaStructures.Host() {
            @Override public int ground(int x, int z) { return flat.ground(x, z); }
            @Override public int water(int x, int z) { return 62; }
            @Override public Holder<Biome> biome(int x, int y, int z) { return plains; }
            @Override public int floor(int x, int z) { return 100; }
            @Nullable @Override public LevelChunkSection cube(int cx, int cy, int cz) { return null; }
            @Override public BlockState fill(int x, int y, int z, int depth) { return Blocks.STONE.defaultBlockState(); }
            @Override public int seaLevel() {
                // Each lookup thread's first ask holds it, before it takes the start lock.
                if (Thread.currentThread() != server && held.getAndIncrement() < 2) {
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return 63;
            }
            @Override public Stream<Holder<Biome>> possibleBiomes() { return flat.possibleBiomes(); }
        };
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, true, host, 1L);
        structures.serverThread(() -> Thread.currentThread() == server);
        try {
            // Two lookups hold both lookup threads; a third waits in the queue.
            structures.startsAt(0, 0);
            structures.startsAt(1000, 0);
            for (long until = System.currentTimeMillis() + 20_000; held.get() < 2 && System.currentTimeMillis() < until; Thread.sleep(10)) {}
            assertTrue(held.get() >= 2, "both lookup threads busy");
            structures.startsAt(2000, 0);
            structures.close();
        } finally {
            release.countDown();
        }
        Thread.sleep(1000);
        // The two already running stop at the start lock, and the one queued never starts: a closed world lays nothing out.
        assertTrue(laidOut.isEmpty(), "laid out after the world closed: " + laidOut);
        structures.startsAt(3000, 0);
        Thread.sleep(200);
        assertFalse(laidOut.contains(6), "a lookup asked after the world closed was queued");
    }

    @Test void columnsLoadedFromTheSaveAreLaidOutAsideAndAnsweredOnTheFirstLookup() throws InterruptedException {
        // After a restart nothing is laid out: the temple's cubes come from the save, not from generation.
        TestStructure temple = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE, 40);
        RegistryAccess registries = singleStructureRegistries("test_saved_temple", temple);
        Thread server = Thread.currentThread();
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, true, hostOf((x, z) -> 100), 1L);
        structures.serverThread(() -> Thread.currentThread() == server);
        structures.warm(2, 0); // the storage path: a cube of chunk column 2, 0 read from the save
        for (long until = System.currentTimeMillis() + 20_000; !structures.laidOut(2, 0) && System.currentTimeMillis() < until; Thread.sleep(10)) {}
        assertTrue(structures.laidOut(2, 0), "the saved column laid out aside");

        // The first lookup there (YUNG's Pharaoh dying in a temple from the save) finds it.
        List<StructureStart> found = structures.startsAt(2, 0, structure -> structure == temple);
        assertTrue(found.stream().anyMatch(start -> start.getChunkPos().equals(new ChunkPos(0, 0))));
        assertFalse(structures.startsAt(2, 0).isEmpty(), "and every structure there, for mob spawns");
    }

    @Test void aPlayersOwnLookupIsNotCrowdedOutByMobSpawnProbes() throws InterruptedException {
        TestStructure hut = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE, 12);
        RegistryAccess registries = singleStructureRegistries("test_probe_hut", hut, 24, 8);
        Thread server = Thread.currentThread();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger held = new java.util.concurrent.atomic.AtomicInteger();
        VanillaStructures.Host flat = hostOf((x, z) -> 100);
        VanillaStructures.Host host = new VanillaStructures.Host() {
            @Override public int ground(int x, int z) { return 100; }
            @Override public int water(int x, int z) { return 62; }
            @Override public Holder<Biome> biome(int x, int y, int z) { return plains; }
            @Override public int floor(int x, int z) { return 100; }
            @Nullable @Override public LevelChunkSection cube(int cx, int cy, int cz) { return null; }
            @Override public BlockState fill(int x, int y, int z, int depth) { return Blocks.STONE.defaultBlockState(); }
            @Override public int seaLevel() {
                if (Thread.currentThread() != server && held.getAndIncrement() < 2) {
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return 63;
            }
            @Override public Stream<Holder<Biome>> possibleBiomes() { return flat.possibleBiomes(); }
        };
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, true, host, 1L);
        structures.serverThread(() -> Thread.currentThread() == server);
        // The player stands where a hut starts.
        ChunkPos player = new RandomSpreadStructurePlacement(24, 8, RandomSpreadType.LINEAR, 12345).getPotentialStructureChunk(1L, 0, 0);
        try {
            // Mob spawns probe every chunk round the players, far more than the queue takes.
            for (int i = 0; i < VanillaStructures.MOST_QUEUED + 100; i++) structures.startsAt(10_000 + i * 40, 10_000);
            for (long until = System.currentTimeMillis() + 20_000; held.get() < 2 && System.currentTimeMillis() < until; Thread.sleep(10)) {}
            // The player's own lookup, for the one structure it asks about, is still taken.
            assertTrue(structures.startsAt(player.x, player.z, structure -> structure == hut).isEmpty());
        } finally {
            release.countDown();
        }
        List<StructureStart> found = List.of();
        for (long until = System.currentTimeMillis() + 60_000; found.isEmpty() && System.currentTimeMillis() < until; Thread.sleep(20))
            found = structures.startsAt(player.x, player.z, structure -> structure == hut);
        assertFalse(found.isEmpty(), "the player's lookup was dropped behind mob spawn probes");
        structures.close();
    }

    /** A host whose lookup workers wait on {@code release} the first {@code holds} times they ask its sea level, before the start lock. */
    private static VanillaStructures.Host holdingHost(Thread server, java.util.concurrent.CountDownLatch release, java.util.concurrent.atomic.AtomicInteger held, int holds) {
        VanillaStructures.Host flat = hostOf((x, z) -> 100);
        return new VanillaStructures.Host() {
            @Override public int ground(int x, int z) { return 100; }
            @Override public int water(int x, int z) { return 62; }
            @Override public Holder<Biome> biome(int x, int y, int z) { return plains; }
            @Override public int floor(int x, int z) { return 100; }
            @Nullable @Override public LevelChunkSection cube(int cx, int cy, int cz) { return null; }
            @Override public BlockState fill(int x, int y, int z, int depth) { return Blocks.STONE.defaultBlockState(); }
            @Override public int seaLevel() {
                if (Thread.currentThread() != server && held.getAndIncrement() < holds) {
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return 63;
            }
            @Override public Stream<Holder<Biome>> possibleBiomes() { return flat.possibleBiomes(); }
        };
    }

    /** Another world's structure whose start takes as long as {@code release} holds it, inside the start lock (an ancient city). */
    private static Thread holdTheStartLock(java.util.concurrent.CountDownLatch release, java.util.concurrent.CountDownLatch holding) {
        TestStructure slow = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE, 12) {
            @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
                holding.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return super.findGenerationPoint(context);
            }
        };
        VanillaStructures other = new VanillaStructures(null, singleStructureRegistries("test_slow_city", slow), randomState, true, hostOf((x, z) -> 100), 1L);
        Thread worker = new Thread(() -> other.startsAt(0, 0), "Earth cube worker (slow start)");
        worker.start();
        return worker;
    }

    @Test void aSavedCubeIsHandedOutAtOnceWhileASlowStartHoldsTheStartLock() throws InterruptedException {
        // A returning player: a cube worker reads the temple's cube from the save while a cube worker lays out a slow start.
        TestStructure temple = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE, 40);
        RegistryAccess registries = singleStructureRegistries("test_returning_temple", temple);
        Thread server = Thread.currentThread();
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, true, hostOf((x, z) -> 100), 1L);
        structures.serverThread(() -> Thread.currentThread() == server);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1), holding = new java.util.concurrent.CountDownLatch(1);
        Thread slow = holdTheStartLock(release, holding);
        try {
            assertTrue(holding.await(20, java.util.concurrent.TimeUnit.SECONDS), "the slow start never took the lock");
            // The worker path: what ServerCubeWorld.produce calls for a saved cube, on a cube worker.
            Thread worker = new Thread(() -> structures.warm(2, 0), "Earth cube worker (test)");
            worker.start();
            worker.join(5_000);
            assertFalse(worker.isAlive(), "a cube worker handing out a saved cube waited on the start lock");
            // An arrival there waits, asking on the server thread, which does not wait either.
            long before = System.nanoTime();
            assertFalse(structures.ready(2, 0), "ready before its column was laid out");
            assertFalse(structures.ready(2, 0));
            assertTrue(structures.startsAt(2, 0, structure -> structure == temple).isEmpty());
            assertTrue(System.nanoTime() - before < 2_000_000_000L, "the server thread waited on the start lock");
        } finally {
            release.countDown();
        }
        slow.join(20_000);
        for (long until = System.currentTimeMillis() + 20_000; !structures.ready(2, 0) && System.currentTimeMillis() < until; Thread.sleep(10)) {}
        assertTrue(structures.ready(2, 0), "the saved column was never laid out");
        // Once the arrival is let through, the first lookup inside the temple (YUNG's Pharaoh dying there) finds it.
        List<StructureStart> found = structures.startsAt(2, 0, structure -> structure == temple);
        assertTrue(found.stream().anyMatch(start -> start.getChunkPos().equals(new ChunkPos(0, 0))));
        assertNotNull(structures.startOf(temple, 0, 0), "and the start by its reference, for mob spawns");
    }

    @Test void aSavedColumnWhoseLayoutFailsIsNotReadyAndIsLaidOutAgainAfterItsBackoff() throws InterruptedException {
        TestStructure temple = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE, 40);
        RegistryAccess registries = singleStructureRegistries("test_failing_temple", temple);
        Thread server = Thread.currentThread();
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(1);
        // The first layout fails as a mod's missing class does, out of the start's own code.
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, true, hostOf((x, z) -> {
            if (Thread.currentThread() != server && failures.getAndDecrement() > 0) throw new NoClassDefFoundError("a mod's missing class");
            return 100;
        }), 1L);
        structures.serverThread(() -> Thread.currentThread() == server);
        Thread worker = new Thread(() -> structures.warm(2, 0), "Earth cube worker (test)");
        worker.start();
        worker.join(5_000);
        assertFalse(worker.isAlive(), "a cube worker handing out a saved cube laid its column out");
        for (long until = System.currentTimeMillis() + 20_000; (structures.queued() > 0 || failures.get() > 0) && System.currentTimeMillis() < until; Thread.sleep(10)) {}
        assertEquals(0, structures.queued());

        // Failed: not ready, so an arrival is not let through as if its structures were known, and not asked again at once.
        assertFalse(structures.laidOut(2, 0));
        assertFalse(structures.ready(2, 0), "a failed column was ready");
        assertEquals(0, structures.queued(), "a failed column asked again inside its backoff");
        assertTrue(structures.startsAt(2, 0, structure -> structure == temple).isEmpty());

        // After the backoff, the arrival's ask lays it out, and lookups there are right.
        structures.retryAfter(0);
        for (long until = System.currentTimeMillis() + 20_000; !structures.ready(2, 0) && System.currentTimeMillis() < until; Thread.sleep(10)) {}
        assertTrue(structures.ready(2, 0), "a failed column was not laid out again");
        assertTrue(structures.startsAt(2, 0, structure -> structure == temple).stream().anyMatch(start -> start.getChunkPos().equals(new ChunkPos(0, 0))));
    }

    @Test void mobSpawnFollowUpsForAnEvictedStartKeepToTheProbesShare() throws InterruptedException {
        // getMobsAt asks getAllStructuresAt, then fillStartsForStructure by reference: a start no longer cached is laid out aside.
        TestStructure hut = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE, 12);
        RegistryAccess registries = singleStructureRegistries("test_follow_up_hut", hut); // a start in every chunk
        Thread server = Thread.currentThread();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger held = new java.util.concurrent.atomic.AtomicInteger();
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, true, holdingHost(server, release, held, 2), 1L);
        structures.serverThread(() -> Thread.currentThread() == server);
        try {
            for (int i = 0; i < VanillaStructures.MOST_PROBES + 50; i++) structures.startsAt(20_000 + i * 40, 20_000);
            for (long until = System.currentTimeMillis() + 20_000; held.get() < 2 && System.currentTimeMillis() < until; Thread.sleep(10)) {}
            assertEquals(VanillaStructures.MOST_PROBES, structures.queued());
            assertNull(structures.startOf(hut, 50_000, 50_000, true));
            assertEquals(VanillaStructures.MOST_PROBES, structures.queued(), "a mob spawn's follow-up went past the probes' share");
            // A player's own lookup by reference is still admitted.
            assertNull(structures.startOf(hut, 51_000, 51_000));
            assertEquals(VanillaStructures.MOST_PROBES + 1, structures.queued(), "a lookup was not admitted");
        } finally {
            structures.close();
            release.countDown();
        }
    }

    @Test void aWriteRacingARollIsNeverShadowedByTheRoller() throws InterruptedException {
        VanillaStructures.Generations<String> map = new VanillaStructures.Generations<>(2);
        map.put(1, "a");
        map.put(2, "b"); // the newer is full: the next write rolls
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        Thread[] writer = new Thread[1];
        map.whileWriting(() -> {
            if (Thread.currentThread() != writer[0]) return;
            inside.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        String[] kept = new String[2];
        // A fast-path writer puts the valid start, held once it has read the generations; another thread then rolls with a failed one.
        writer[0] = new Thread(() -> kept[0] = map.putIfAbsent(7, "valid"));
        writer[0].start();
        assertTrue(inside.await(10, java.util.concurrent.TimeUnit.SECONDS));
        Thread roller = new Thread(() -> kept[1] = map.putIfAbsent(7, "failed"));
        roller.start();
        Thread.sleep(200);
        release.countDown();
        writer[0].join(10_000);
        roller.join(10_000);
        map.whileWriting(null);
        assertEquals("valid", kept[0]);
        assertEquals("valid", kept[1], "the roller kept its own value over one already put");
        assertEquals("valid", map.get(7), "a valid start shadowed by the roller's failed one");
        // An entry in the older generation wins over a later putIfAbsent too, rather than being shadowed by it.
        map.put(8, "x"); map.put(9, "y"); // rolls: 7 is now in the older generation
        assertEquals("valid", map.putIfAbsent(7, "failed"));
        assertEquals("valid", map.get(7));
    }

    @Test void aDeepStartWhoseCopyCannotBeMadeIsAnsweredAsItStandsAndNotRequeued() throws InterruptedException {
        // A mineshaft, laid out in a frame below the real world; its copy at real heights throws as its pieces are saved
        // (here no level to save them with, as a modded piece that throws when it is saved).
        TestStructure shaft = new TestStructure(plains, GenerationStep.Decoration.UNDERGROUND_STRUCTURES, TerrainAdjustment.NONE, 12);
        RegistryAccess registries = singleStructureRegistries("mineshafts", shaft);
        Thread server = Thread.currentThread();
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, true, hostOf((x, z) -> 1000), 1L);
        structures.serverThread(() -> Thread.currentThread() == server);
        assertNull(structures.startOf(shaft, 0, 0), "not known yet: laid out aside");
        StructureStart found = null;
        for (long until = System.currentTimeMillis() + 20_000; found == null && System.currentTimeMillis() < until; Thread.sleep(10)) {
            if (structures.queued() == 0) found = structures.startOf(shaft, 0, 0);
        }
        assertNotNull(found, "a start whose copy failed was never answered");
        assertEquals(0, structures.queued(), "the failing copy was queued again");
        assertNotNull(structures.startOf(shaft, 0, 0));
        assertEquals(0, structures.queued(), "the failing copy was queued again");
    }

    @Test void aStartThatThrowsIsAFailureWithBackoffNotAMissingStart() throws InterruptedException {
        // The temple's start throws once (map data not in yet): the column is not ready, and the start is not cached as none.
        TestStructure temple = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE, 40);
        RegistryAccess registries = singleStructureRegistries("test_flaky_temple", temple);
        Thread server = Thread.currentThread();
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(1);
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, true, hostOf((x, z) -> {
            if (Thread.currentThread() != server && failures.getAndDecrement() > 0) throw new IllegalStateException("map data not in yet");
            return 100;
        }), 1L);
        structures.serverThread(() -> Thread.currentThread() == server);
        structures.startRetryAfter(0);
        structures.warm(2, 0);
        for (long until = System.currentTimeMillis() + 20_000; (structures.queued() > 0 || failures.get() > 0) && System.currentTimeMillis() < until; Thread.sleep(10)) {}
        assertFalse(structures.ready(2, 0), "an arrival let through with the temple's start taken for none");
        assertEquals(0, structures.queued(), "asked again inside its backoff");
        structures.retryAfter(0);
        for (long until = System.currentTimeMillis() + 20_000; !structures.ready(2, 0) && System.currentTimeMillis() < until; Thread.sleep(10)) {}
        assertTrue(structures.ready(2, 0));
        assertTrue(structures.startsAt(2, 0, structure -> structure == temple).stream().anyMatch(start -> start.getChunkPos().equals(new ChunkPos(0, 0))),
                "the start that threw once was left out for good");
    }

    @Test void aStartThatAlwaysThrowsIsLeftOutAfterItsTries() {
        TestStructure broken = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE, 12) {
            @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) { throw new IllegalStateException("another mod's bug"); }
        };
        VanillaStructures structures = new VanillaStructures(null, singleStructureRegistries("test_broken_hut", broken), randomState, true, hostOf((x, z) -> 100), 1L);
        structures.startRetryAfter(0); // each try in a window of its own
        // A cube generating there fails and is made again later; each start is tried a few times, then left out and the cube is made.
        assertThrows(VanillaStructures.StartFailed.class, () -> structures.clears(8, 8), "a start that threw taken for none at once");
        int tries = 1;
        for (; tries < 5000; tries++) {
            try { structures.clears(8, 8); break; }
            catch (VanillaStructures.StartFailed again) { /* made again later */ }
        }
        assertTrue(tries < 5000, "a start that always throws was never left out");
        assertTrue(tries >= VanillaStructures.MOST_START_FAILURES - 1, "left out before its tries: " + tries);
        assertFalse(structures.clears(8, 8));
        assertTrue(structures.startsAt(0, 0).isEmpty());
    }

    @Test void oneOutageCountsAsOneTryHoweverManyCallersAsk() {
        java.util.concurrent.atomic.AtomicInteger tried = new java.util.concurrent.atomic.AtomicInteger();
        ChunkPos hut = new RandomSpreadStructurePlacement(24, 8, RandomSpreadType.LINEAR, 12345).getPotentialStructureChunk(1L, 0, 0);
        TestStructure flaky = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE, 12) {
            @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
                if (context.chunkPos().equals(hut)) tried.incrementAndGet(); // the one start watched; others near it throw too
                throw new IllegalStateException("map data not in yet");
            }
        };
        VanillaStructures structures = new VanillaStructures(null, singleStructureRegistries("test_outage_hut", flaky, 24, 8), randomState, true, hostOf((x, z) -> 100), 1L);
        // Generation and lookups asking over and over during one outage: the start is tried once, and never left out.
        for (int i = 0; i < 4 * VanillaStructures.MOST_START_FAILURES; i++)
            assertThrows(VanillaStructures.StartFailed.class, () -> structures.clears((hut.x << 4) + 8, (hut.z << 4) + 8));
        assertEquals(1, tried.get(), "tried again inside its backoff");
        // Windows apart, it counts again, and after its tries it is left out.
        structures.startRetryAfter(0);
        int calls = 0;
        for (; calls < 60; calls++) {
            try { structures.clears((hut.x << 4) + 8, (hut.z << 4) + 8); break; }
            catch (VanillaStructures.StartFailed again) { /* asked again later */ }
        }
        assertEquals(VanillaStructures.MOST_START_FAILURES, tried.get(), "left out after a different number of tries");
        assertTrue(calls < 60);
    }

    @Test void aHitInTheOlderGenerationNeverWaitsOnAWriter() throws InterruptedException {
        VanillaStructures.Generations<String> map = new VanillaStructures.Generations<>(2);
        map.put(1, "a");
        map.put(2, "b");
        map.put(3, "x"); // rolls: 1 is in the older generation
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        Thread[] writer = new Thread[1];
        map.whileWriting(() -> {
            if (Thread.currentThread() != writer[0]) return;
            inside.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        writer[0] = new Thread(() -> map.put(5, "c"));
        writer[0].start();
        try {
            assertTrue(inside.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(map.writeLocked());
            // The server thread: a hit in the older generation, and a cache fill, while another thread writes.
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                assertEquals("a", map.get(1));
                map.offer(4, "d");
            }, "a reader waited on a writer");
        } finally {
            release.countDown();
        }
        writer[0].join(10_000);
        map.whileWriting(null);
        assertEquals("a", map.get(1), "still read after the write");
    }

    @Test void aSavedCubeThroughTheGeneratorGoesOutAtOnceAndItsArrivalWaitsForItsStructures() throws InterruptedException {
        // The real path: a Highlands world's generator with its own structure host. ServerCubeWorld's reader calls
        // generator.loaded for a saved cube; the arrival asks generator.structuresReady through ArrivalWait.
        MappedRegistry<Biome> biomeRegistry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).listElements()
                .forEach(h -> biomeRegistry.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        biomeRegistry.freeze();
        // As a level's registries are: the overworld tag, from which a generator's structure host takes the biomes structures may start in.
        biomeRegistry.bindTags(Map.of(net.minecraft.tags.BiomeTags.IS_OVERWORLD, biomeRegistry.holders().map(h -> (Holder<Biome>) h).toList()));
        HighlandsGenerator generator = new HighlandsGenerator(7, biomeRegistry);
        HolderSet<Biome> anywhere = HolderSet.direct(biomeRegistry.holders().map(h -> (Holder<Biome>) h).toList());
        java.util.concurrent.atomic.AtomicInteger laidOutOnServer = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] serverThread = {Thread.currentThread()}; // moved into the timed block's thread below
        Structure ruin = new TestStructure(anywhere, GenerationStep.Decoration.UNDERGROUND_STRUCTURES, TerrainAdjustment.NONE, 40) {
            @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
                if (Thread.currentThread() == serverThread[0]) laidOutOnServer.incrementAndGet();
                return super.findGenerationPoint(context);
            }
        };
        VanillaStructures structures = generator.attachStructures(singleStructureRegistries("test_highland_ruin", ruin), randomState);
        structures.serverThread(() -> Thread.currentThread() == serverThread[0]);
        int cx = 2, cz = 0, cy = generator.surfaceY((cx << 4) + 8, (cz << 4) + 8) >> 4;
        io.earthplatform.minecraft.cubic.ArrivalWait.Place place = new io.earthplatform.minecraft.cubic.ArrivalWait.Place() {
            @Override public boolean cubesReady() { return true; } // the saved cubes are in
            @Override public boolean structuresReady() {
                boolean ready = true;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) ready &= generator.structuresReady(cx + dx, cz + dz);
                return ready;
            }
        };
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1), holding = new java.util.concurrent.CountDownLatch(1);
        Thread slow = holdTheStartLock(release, holding);
        try {
            assertTrue(holding.await(20, java.util.concurrent.TimeUnit.SECONDS));
            Thread reader = new Thread(() -> generator.loaded(cx, cy, cz), "Earth cube reader (test)");
            reader.start();
            reader.join(5_000);
            assertFalse(reader.isAlive(), "a saved cube waited on the start lock");
            Thread caller = Thread.currentThread();
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
                serverThread[0] = Thread.currentThread();
                assertEquals(io.earthplatform.minecraft.cubic.ArrivalWait.Step.WAIT, io.earthplatform.minecraft.cubic.ArrivalWait.step(io.earthplatform.minecraft.cubic.ArrivalWait.Wait.NEW, place).step());
                assertEquals(io.earthplatform.minecraft.cubic.ArrivalWait.Step.WAIT, io.earthplatform.minecraft.cubic.ArrivalWait.step(new io.earthplatform.minecraft.cubic.ArrivalWait.Wait(io.earthplatform.minecraft.cubic.ArrivalWait.LIMIT - 1, 0), place).step());
                // Past the limit it goes without the structures rather than waiting on.
                assertEquals(io.earthplatform.minecraft.cubic.ArrivalWait.Step.GO, io.earthplatform.minecraft.cubic.ArrivalWait.step(new io.earthplatform.minecraft.cubic.ArrivalWait.Wait(io.earthplatform.minecraft.cubic.ArrivalWait.LIMIT, 0), place).step());
            }, "the server thread waited on the start lock");
            serverThread[0] = caller;
        } finally {
            release.countDown();
        }
        slow.join(20_000);
        io.earthplatform.minecraft.cubic.ArrivalWait.Step step = io.earthplatform.minecraft.cubic.ArrivalWait.Step.WAIT;
        for (long until = System.currentTimeMillis() + 60_000; step != io.earthplatform.minecraft.cubic.ArrivalWait.Step.GO && System.currentTimeMillis() < until; Thread.sleep(20))
            step = io.earthplatform.minecraft.cubic.ArrivalWait.step(new io.earthplatform.minecraft.cubic.ArrivalWait.Wait(1, 1), place).step();
        assertEquals(io.earthplatform.minecraft.cubic.ArrivalWait.Step.GO, step, "the arrival never went");
        // Once it goes, the first lookup there finds the ruin, answered on the server thread from the laid-out column.
        assertTrue(generator.structureStarts(cx, cz, structure -> structure == ruin).stream().anyMatch(start -> start.getChunkPos().equals(new ChunkPos(0, 0))));
        assertEquals(0, laidOutOnServer.get(), "laid out on the server thread");
    }

    @Test void probesAreAdmittedOnlyToTheirShareWhileTheWorkersAreHeld() throws InterruptedException {
        TestStructure hut = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE, 12);
        RegistryAccess registries = singleStructureRegistries("test_share_hut", hut); // a start in every chunk, so every job asks and is held
        Thread server = Thread.currentThread();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger held = new java.util.concurrent.atomic.AtomicInteger();
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, true, holdingHost(server, release, held, 2), 1L);
        structures.serverThread(() -> Thread.currentThread() == server);
        try {
            for (int i = 0; i < VanillaStructures.MOST_PROBES + 50; i++) structures.startsAt(20_000 + i * 40, 20_000);
            for (long until = System.currentTimeMillis() + 20_000; held.get() < 2 && System.currentTimeMillis() < until; Thread.sleep(10)) {}
            assertEquals(VanillaStructures.MOST_PROBES, structures.queued(), "probes fill only their share");
            // A typed probe (vanilla's fortress spawn check, getStructureAt) counts as one.
            structures.startsAt(40_000, 40_000, structure -> structure == hut, true);
            assertEquals(VanillaStructures.MOST_PROBES, structures.queued(), "a typed probe went past the probes' share");
            // A lookup for a structure is still admitted.
            structures.startsAt(41_000, 41_000, structure -> structure == hut);
            assertEquals(VanillaStructures.MOST_PROBES + 1, structures.queued(), "a lookup was not admitted");
        } finally {
            structures.close();
            release.countDown();
        }
    }

    // Big layouts make their base cubes ahead (ancient cities, YUNG's mineshafts) ---------------

    @Test void layoutsMakeAheadTheCubesTheirPiecesAndTerrainAdaptationRead() {
        // A piece 40 blocks across at Y 100..108 in chunk 0, 0, laid out 1,000 blocks below its frame.
        BoundingBox box = new BoundingBox(0, 100, 0, 40, 108, 40);
        int shift = 1000, floor = box.minY() + shift, top = box.maxY() + shift;
        List<StructurePiece> pieces = List.of(new BoxPiece(box));
        var adapted = VanillaStructures.reads(pieces, shift, TerrainAdjustment.BEARD_THIN, 0, 0);
        // Every block adapt reads: the fill down to its bottom, the cut up to its headroom, out to ADAPT sideways.
        for (int x = -VanillaStructures.ADAPT; x <= 40 + VanillaStructures.ADAPT; x++)
            for (int z = -VanillaStructures.ADAPT; z <= 40 + VanillaStructures.ADAPT; z++)
                for (int y = VanillaStructures.adaptFillBottom(floor); y <= top + VanillaStructures.ADAPT_HEADROOM; y++)
                    assertTrue(adapted.contains(net.minecraft.core.SectionPos.asLong(x >> 4, y >> 4, z >> 4)), "missing " + x + ", " + y + ", " + z);
        // Buried and encapsulated structures: wrapped 3 blocks round, so not the beard's reach.
        var buried = VanillaStructures.reads(pieces, shift, TerrainAdjustment.ENCAPSULATE, 0, 0);
        for (int x = -3; x <= 43; x++) for (int z = -3; z <= 43; z++) for (int y = floor - 3; y <= top + 3; y++)
            assertTrue(buried.contains(net.minecraft.core.SectionPos.asLong(x >> 4, y >> 4, z >> 4)), "missing " + x + ", " + y + ", " + z);
        assertTrue(buried.size() < adapted.size());
        // Without adaptation, the piece's own cubes and those a block round it, nothing far above or below.
        var plain = VanillaStructures.reads(pieces, shift, TerrainAdjustment.NONE, 0, 0);
        assertTrue(plain.contains(net.minecraft.core.SectionPos.asLong(0, floor >> 4, 0)));
        assertFalse(plain.contains(net.minecraft.core.SectionPos.asLong(0, (floor >> 4) - 2, 0)));
        assertTrue(plain.size() < adapted.size());
        // Only the chunks a layout places, within REACH of its start.
        BoundingBox wide = new BoundingBox(-400, 100, 0, 400, 101, 0);
        var clipped = VanillaStructures.reads(List.of(new BoxPiece(wide)), 0, TerrainAdjustment.NONE, 0, 0);
        assertFalse(clipped.contains(net.minecraft.core.SectionPos.asLong(-10, 6, 0)));
        assertTrue(clipped.contains(net.minecraft.core.SectionPos.asLong(-8, 6, 0)));
    }

    // Issue #216: surface slope / relief checks --------------------------------------------------

    @Test void footprintFitsAcceptsFlatAndGentleSlopes() {
        BoundingBox igloo = new BoundingBox(0, 100, 0, 6, 106, 7);
        BoundingBox house = new BoundingBox(0, 100, 0, 10, 108, 10);
        BoundingBox pyramid = new BoundingBox(0, 100, 0, 20, 112, 20);

        IntBinaryOperator flat = (x, z) -> 100;
        IntBinaryOperator gentle15 = (x, z) -> 100 + (int) Math.round(0.27 * x);
        IntBinaryOperator hill25 = (x, z) -> 100 + (int) Math.round(0.47 * x);
        IntBinaryOperator diag25 = (x, z) -> 100 + (int) Math.round(0.33 * x + 0.33 * z);
        IntBinaryOperator smallBump = (x, z) -> (x >= 4 && x <= 6 && z >= 4 && z <= 6) ? 103 : 100;

        for (BoundingBox box : List.of(igloo, house, pyramid)) {
            assertTrue(VanillaStructures.footprintFits(box, true, flat));
            assertTrue(VanillaStructures.footprintFits(box, true, gentle15));
            assertTrue(VanillaStructures.footprintFits(box, true, hill25));
            assertTrue(VanillaStructures.footprintFits(box, true, diag25));
            assertTrue(VanillaStructures.footprintFits(box, true, smallBump));
        }
    }

    @Test void footprintFitsRejectsWallsCliffsAndKnifeRidges() {
        BoundingBox igloo = new BoundingBox(0, 100, 0, 6, 106, 7);
        BoundingBox house = new BoundingBox(0, 100, 0, 10, 108, 10);
        BoundingBox outpost = new BoundingBox(0, 100, 0, 14, 118, 14);

        IntBinaryOperator slope35 = (x, z) -> 100 + (int) Math.round(0.70 * x);
        IntBinaryOperator wall45 = (x, z) -> 100 + x;
        IntBinaryOperator diagWall45 = (x, z) -> 100 + (int) Math.round(0.71 * x + 0.71 * z);
        IntBinaryOperator karakoram60 = (x, z) -> 3000 + (int) Math.round(1.73 * x);
        IntBinaryOperator cliffEdge = (x, z) -> x > 6 ? 118 : 100;
        IntBinaryOperator knifeRidge = (x, z) -> 110 - Math.abs(x - 5) * 2;

        for (BoundingBox box : List.of(igloo, house, outpost)) {
            assertFalse(VanillaStructures.footprintFits(box, true, slope35));
            assertFalse(VanillaStructures.footprintFits(box, true, wall45));
            assertFalse(VanillaStructures.footprintFits(box, true, diagWall45));
            assertFalse(VanillaStructures.footprintFits(box, true, karakoram60));
        }
        assertFalse(VanillaStructures.footprintFits(house, true, cliffEdge));
        assertFalse(VanillaStructures.footprintFits(house, true, knifeRidge));
    }

    @Test void nonRigidRoadAllowsLongGentleSlopeButRejectsSteepWall() {
        BoundingBox longRoad = new BoundingBox(0, 100, 0, 48, 101, 4);
        IntBinaryOperator gentleHill = (x, z) -> 100 + (int) Math.round(0.45 * x); // 22 blocks total relief, 0.45 slope
        IntBinaryOperator steepWall = (x, z) -> 100 + (int) Math.round(1.0 * x);

        assertTrue(VanillaStructures.footprintFits(longRoad, false, gentleHill));
        assertFalse(VanillaStructures.footprintFits(longRoad, false, steepWall));
    }

    // Issue #216: tightened terrain adaptation bounds --------------------------------------------

    @Test void adaptBoundsKeepCutsAndPedestalsRealistic() {
        int floor = 100, boxMaxY = 108;

        // Downhill fill is capped at ADAPT_FILL (16) blocks below floor rather than 64.
        assertEquals(floor - VanillaStructures.ADAPT_FILL, VanillaStructures.adaptFillBottom(floor));
        assertTrue(floor - VanillaStructures.adaptFillBottom(floor) <= 20);

        // Uphill BEARD_THIN cut clears gentle ground cleanly (ground + 4 when below headroom cap).
        assertEquals(107, VanillaStructures.adaptCutTop(TerrainAdjustment.BEARD_THIN, boxMaxY, 0, 103, 62));

        // Uphill BEARD_THIN cut against a tall cliff is capped at boxMaxY + ADAPT_HEADROOM (114), not ground + 24 (184).
        assertEquals(boxMaxY + VanillaStructures.ADAPT_HEADROOM,
                VanillaStructures.adaptCutTop(TerrainAdjustment.BEARD_THIN, boxMaxY, 4, 160, 62));

        // BEARD_BOX tapers inward from boxMaxY.
        assertEquals(boxMaxY - 3, VanillaStructures.adaptCutTop(TerrainAdjustment.BEARD_BOX, boxMaxY, 3, 160, 62));
    }

    // Test fixtures ------------------------------------------------------------------------------

    private static RegistryAccess singleStructureRegistries(String name, Structure structure) {
        return singleStructureRegistries(name, structure, 1, 0);
    }

    private static RegistryAccess singleStructureRegistries(String name, Structure structure, int spacing, int separation) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("minecraft", name);
        MappedRegistry<Structure> structures = new MappedRegistry<>(Registries.STRUCTURE, Lifecycle.stable());
        Holder.Reference<Structure> holder = structures.register(ResourceKey.create(Registries.STRUCTURE, id), structure, RegistrationInfo.BUILT_IN);
        structures.freeze();

        MappedRegistry<StructureSet> sets = new MappedRegistry<>(Registries.STRUCTURE_SET, Lifecycle.stable());
        StructureSet set = new StructureSet(
                List.of(StructureSet.entry(holder)),
                new RandomSpreadStructurePlacement(spacing, separation, RandomSpreadType.LINEAR, 12345));
        sets.register(ResourceKey.create(Registries.STRUCTURE_SET, id), set, RegistrationInfo.BUILT_IN);
        sets.freeze();

        return new RegistryAccess.ImmutableRegistryAccess(List.of(structures, sets)).freeze();
    }

    /** A shrine set in every chunk, kept {@code apart} chunks from a stronghold set on vanilla's concentric rings. */
    private static RegistryAccess ringExcludedRegistries(Structure shrine, Structure stronghold, int apart) {
        MappedRegistry<Structure> structures = new MappedRegistry<>(Registries.STRUCTURE, Lifecycle.stable());
        Holder.Reference<Structure> shrineHolder = structures.register(ResourceKey.create(Registries.STRUCTURE,
                ResourceLocation.fromNamespaceAndPath("test", "shrine")), shrine, RegistrationInfo.BUILT_IN);
        Holder.Reference<Structure> strongholdHolder = structures.register(ResourceKey.create(Registries.STRUCTURE,
                ResourceLocation.withDefaultNamespace("stronghold")), stronghold, RegistrationInfo.BUILT_IN);
        structures.freeze();

        MappedRegistry<StructureSet> sets = new MappedRegistry<>(Registries.STRUCTURE_SET, Lifecycle.stable());
        Holder.Reference<StructureSet> rings = sets.register(ResourceKey.create(Registries.STRUCTURE_SET, ResourceLocation.withDefaultNamespace("strongholds")),
                new StructureSet(List.of(StructureSet.entry(strongholdHolder)), new ConcentricRingsStructurePlacement(32, 3, 128, HolderSet.direct(plains))),
                RegistrationInfo.BUILT_IN);
        sets.register(ResourceKey.create(Registries.STRUCTURE_SET, ResourceLocation.fromNamespaceAndPath("test", "shrines")),
                new StructureSet(List.of(StructureSet.entry(shrineHolder)), new RandomSpreadStructurePlacement(net.minecraft.core.Vec3i.ZERO,
                        StructurePlacement.FrequencyReductionMethod.DEFAULT, 1f, 78409300,
                        Optional.of(new StructurePlacement.ExclusionZone(rings, apart)), 1, 0, RandomSpreadType.LINEAR)),
                RegistrationInfo.BUILT_IN);
        sets.freeze();
        return new RegistryAccess.ImmutableRegistryAccess(List.of(structures, sets)).freeze();
    }

    private static VanillaStructures.Host hostOf(IntBinaryOperator ground) {
        return new VanillaStructures.Host() {
            @Override public int ground(int x, int z) { return ground.applyAsInt(x, z); }
            @Override public int water(int x, int z) { return 62; }
            @Override public Holder<Biome> biome(int x, int y, int z) { return plains; }
            @Override public int floor(int x, int z) { return ground.applyAsInt(x, z); }
            @Nullable @Override public LevelChunkSection cube(int cx, int cy, int cz) { return null; }
            @Override public BlockState fill(int x, int y, int z, int depth) { return Blocks.STONE.defaultBlockState(); }
            @Override public int seaLevel() { return 63; }
            @Override public Stream<Holder<Biome>> possibleBiomes() { return Stream.of(plains); }
        };
    }

    private static class TestStructure extends Structure {
        private final int size;

        TestStructure(Holder<Biome> biome, GenerationStep.Decoration step, TerrainAdjustment adaptation, int size) {
            this(HolderSet.direct(biome), step, adaptation, size);
        }

        TestStructure(HolderSet<Biome> biomes, GenerationStep.Decoration step, TerrainAdjustment adaptation, int size) {
            super(new StructureSettings(biomes, Map.of(), step, adaptation));
            this.size = size;
        }

        @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
            int x = context.chunkPos().getMinBlockX(), z = context.chunkPos().getMinBlockZ();
            int y = context.chunkGenerator().getBaseHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,
                    context.heightAccessor(), context.randomState());
            BoundingBox box = new BoundingBox(x, y, z, x + size, y + 8, z + size);
            return Optional.of(new GenerationStub(new BlockPos(x, y, z), builder -> builder.addPiece(new BoxPiece(box))));
        }

        @Override public StructureType<?> type() {
            return () -> MapCodec.unit(this);
        }
    }

    private static final class BoxPiece extends StructurePiece {
        BoxPiece(BoundingBox box) {
            super(StructurePieceType.JIGSAW, 0, box);
        }

        @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {}

        @Override public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
                                          RandomSource random, BoundingBox box, ChunkPos chunk, BlockPos pos) {}
    }
}
