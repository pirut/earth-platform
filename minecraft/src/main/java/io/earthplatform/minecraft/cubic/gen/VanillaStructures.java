package io.earthplatform.minecraft.cubic.gen;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import io.earthplatform.minecraft.cubic.CubeWorld;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.slf4j.Logger;

/**
 * Vanilla's structures in the cubic world, made by vanilla's own structure code: the same
 * placement grid, the same choice of structure and pieces, the same blocks, loot and mobs.
 *
 * <p>Vanilla code sees the world through a {@link Frame}, a chunk generator over the cubic
 * terrain. Surface structures see real heights. Structures vanilla puts at fixed depths
 * (mineshafts, strongholds, ancient cities, trial chambers) see a frame shifted so that vanilla's
 * Y 64 lies at the local floor, the same datum the rock layers hang from (see {@link Underground}).
 *
 * <p>Each structure is laid out once, on the terrain as generated, into an overlay of blocks,
 * block entities and entities bucketed by cube. A cube copies its part of every overlay
 * reaching it, so cubes come out the same whichever order they generate in.
 */
final class VanillaStructures {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** What vanilla code is shown of the world as generated. Real coordinates. */
    interface Host {
        int ground(int x, int z);
        int water(int x, int z);
        Holder<Biome> biome(int x, int y, int z);
        /** The local floor: vanilla's Y 64 for structures at fixed depths. */
        int floor(int x, int z);
        /** The base cube, before structures, or null where it is empty sky. */
        @Nullable LevelChunkSection cube(int cx, int cy, int cz);
        /** The block a column's ground would have {@code depth} blocks under its top, if its top stood at or above y. */
        BlockState fill(int x, int y, int z, int depth);
        /** Vanilla's sea level: one above the top water block of the sea. */
        int seaLevel();
        Stream<Holder<Biome>> possibleBiomes();
    }

    /** Structure sets placed at vanilla's fixed depths under the local floor. */
    private static final Set<String> DEEP = Set.of("minecraft:mineshafts", "minecraft:strongholds", "minecraft:ancient_cities", "minecraft:trial_chambers");
    /** How many chunks from its start chunk a structure reaches, as vanilla's structure references. */
    private static final int REACH = 8;
    /** How far terrain adaptation reaches from a piece, sideways and up or down. */
    private static final int ADAPT = 12, ADAPT_HEIGHT = 64;
    /** Strongholds sit on a spread grid rather than vanilla's rings around the origin, which a planet does not have. */
    private static final int STRONGHOLD_SPACING = 64, STRONGHOLD_SEPARATION = 32, STRONGHOLD_SALT = 0x53544E44;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState(), WATER = Blocks.WATER.defaultBlockState();

    private record Kind(int index, Holder<StructureSet> set, RandomSpreadStructurePlacement placement, boolean deep) {}
    /** A structure start; {@code reach} covers its pieces and terrain adaptation in real coordinates. */
    private record Start(int kind, int chunkX, int chunkZ, StructureStart start, Frame frame, BoundingBox reach) {}
    /** The part of an overlay in one cube: block indices and states, block entity and entity tags. */
    private record Patch(short[] at, BlockState[] states, List<CompoundTag> blockEntities, List<CompoundTag> entities) {}

    private final ServerLevel level;
    private final Host host;
    private final long seed;
    private final RandomState randomState;
    private final ChunkGeneratorStructureState state;
    private final List<Kind> kinds;
    /** Each structure's decoration step and index within it, from which vanilla seeds its placement. */
    private final Map<Structure, int[]> seeding = new HashMap<>();
    private final ConcurrentHashMap<Long, Optional<Start>> starts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, List<Start>> columns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<Long2ObjectOpenHashMap<Patch>>> overlays = new ConcurrentHashMap<>();

    VanillaStructures(ServerLevel level, Host host, long seed) {
        this.level = level;
        this.host = host;
        this.seed = seed;
        this.randomState = level.getChunkSource().randomState();
        Registry<StructureSet> sets = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
        this.state = ChunkGeneratorStructureState.createForFlat(randomState, seed, new FrameBiomes(0), sets.holders().map(h -> (Holder<StructureSet>) h));
        List<Kind> found = new ArrayList<>();
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            String id = set.unwrapKey().map(k -> k.location().toString()).orElse("");
            StructurePlacement placement = set.value().placement();
            if (placement instanceof ConcentricRingsStructurePlacement)
                placement = new RandomSpreadStructurePlacement(STRONGHOLD_SPACING, STRONGHOLD_SEPARATION, RandomSpreadType.TRIANGULAR, STRONGHOLD_SALT);
            if (placement instanceof RandomSpreadStructurePlacement spread) found.add(new Kind(found.size(), set, spread, DEEP.contains(id)));
            else LOGGER.info("Cubic world: no placement for structure set {}", id);
        }
        this.kinds = List.copyOf(found);
        Map<Integer, Integer> counts = new HashMap<>();
        for (Structure structure : level.registryAccess().registryOrThrow(Registries.STRUCTURE)) {
            int step = structure.step().ordinal();
            seeding.put(structure, new int[]{step, counts.merge(step, 1, Integer::sum) - 1});
        }
    }

    /** Copies into a cube every structure block, block entity and entity falling in it. */
    void place(LevelChunkSection section, int cx, int cy, int cz, List<CompoundTag> blockEntities, List<CompoundTag> entities) {
        int y0 = cy << 4;
        long key = SectionPos.asLong(cx, cy, cz);
        for (Start start : column(cx, cz)) {
            if (start.reach.maxY() < y0 || start.reach.minY() > y0 + 15) continue;
            Patch patch = overlay(start).get(key);
            if (patch == null) continue;
            for (int i = 0; i < patch.at.length; i++) {
                int at = patch.at[i];
                section.setBlockState(at & 15, at >> 8 & 15, at >> 4 & 15, patch.states[i], false);
            }
            if (!blockEntities.isEmpty()) {
                // Whatever the terrain had at a written position is gone.
                Set<Integer> written = new java.util.HashSet<>();
                for (short at : patch.at) written.add((int) at);
                blockEntities.removeIf(tag -> written.contains((tag.getInt("y") & 15) << 8 | (tag.getInt("z") & 15) << 4 | tag.getInt("x") & 15));
            }
            for (CompoundTag tag : patch.blockEntities) blockEntities.add(tag.copy());
            for (CompoundTag tag : patch.entities) entities.add(tag.copy());
        }
    }

    /**
     * The nearest start of a wanted structure within {@code radius} chunks of a block column, as
     * the centre of its pieces at real heights, or null. Slow where starts are not yet known.
     */
    @Nullable BlockPos locate(java.util.function.Predicate<Structure> wanted, int x, int z, int radius) {
        int cx = x >> 4, cz = z >> 4;
        // Nearest candidate first, so only the few closest starts are laid out (eyes of ender ask on the server thread)
        List<long[]> candidates = new ArrayList<>();
        for (Kind kind : kinds) {
            if (kind.set.value().structures().stream().noneMatch(e -> wanted.test(e.structure().value()))) continue;
            int spacing = kind.placement.spacing();
            for (int rz = Math.floorDiv(cz - radius, spacing); rz <= Math.floorDiv(cz + radius, spacing); rz++)
                for (int rx = Math.floorDiv(cx - radius, spacing); rx <= Math.floorDiv(cx + radius, spacing); rx++) {
                    ChunkPos chunk = kind.placement.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                    long d = (long) (chunk.x - cx) * (chunk.x - cx) + (long) (chunk.z - cz) * (chunk.z - cz);
                    if (d <= (long) radius * radius) candidates.add(new long[]{d, kind.index, chunk.x, chunk.z});
                }
        }
        candidates.sort(java.util.Comparator.comparingLong(c -> c[0]));
        for (long[] c : candidates) {
            Kind kind = kinds.get((int) c[1]);
            if (!kind.placement.isStructureChunk(state, (int) c[2], (int) c[3])) continue;
            Start start = start(kind, (int) c[2], (int) c[3]);
            if (start == null || !wanted.test(start.start.getStructure())) continue;
            BlockPos centre = start.start.getBoundingBox().getCenter().above(start.frame.shift);
            // Pieces that settle onto the ground as they are placed still sit at vanilla's Y 64 here
            int ground = host.ground(centre.getX(), centre.getZ());
            return start.frame.shift == 0 && centre.getY() < ground - 64 ? centre.atY(ground) : centre;
        }
        return null;
    }

    /** The structures reaching a block column, with how many blocks each lays and how many in the cube at y. For checking. */
    String describe(int x, int y, int z) {
        StringBuilder text = new StringBuilder();
        long cube = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        for (Start start : column(x >> 4, z >> 4)) {
            Long2ObjectOpenHashMap<Patch> overlay = overlay(start);
            int blocks = 0, entities = 0;
            for (Patch patch : overlay.values()) { blocks += patch.at.length; entities += patch.entities.size(); }
            Patch here = overlay.get(cube);
            text.append(level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(start.start.getStructure()))
                    .append(" box ").append(start.start.getBoundingBox().moved(0, start.frame.shift, 0))
                    .append(": ").append(blocks).append(" blocks, ").append(entities).append(" entities, ")
                    .append(here == null ? 0 : here.at.length).append(" here, by cube y in this column:");
            for (int cy = (start.reach.minY() >> 4); cy <= (start.reach.maxY() >> 4); cy++) {
                Patch p = overlay.get(SectionPos.asLong(x >> 4, cy, z >> 4));
                if (p != null) text.append(' ').append(cy).append('=').append(p.at.length);
            }
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = minX, maxY = maxX, minZ = minX, maxZ = maxX;
            for (long key : overlay.keySet()) {
                minX = Math.min(minX, SectionPos.x(key)); maxX = Math.max(maxX, SectionPos.x(key));
                minY = Math.min(minY, SectionPos.y(key)); maxY = Math.max(maxY, SectionPos.y(key));
                minZ = Math.min(minZ, SectionPos.z(key)); maxZ = Math.max(maxZ, SectionPos.z(key));
            }
            text.append(" cubes x ").append(minX).append("..").append(maxX).append(" y ").append(minY).append("..").append(maxY)
                    .append(" z ").append(minZ).append("..").append(maxZ).append(", ").append(start.start.getPieces().size()).append(" pieces: ");
            java.util.TreeMap<Integer, Integer> byY = new java.util.TreeMap<>();
            java.util.TreeMap<String, Integer> lowest = new java.util.TreeMap<>();
            final int bottom = minY;
            overlay.forEach((key, patch) -> {
                byY.merge(SectionPos.y(key), patch.at.length, Integer::sum);
                if (SectionPos.y(key) == bottom) for (BlockState state : patch.states) lowest.merge(state.getBlock().getDescriptionId(), 1, Integer::sum);
            });
            text.append(" by cube y ").append(byY).append(" lowest ").append(lowest).append(' ');
            text.append("; ");
        }
        return text.length() == 0 ? "No structures here." : text.toString();
    }

    // Placement: which structures start where -----------------------------------------------------

    private static long startKey(int kind, int chunkX, int chunkZ) {
        return (long) kind << 56 | ((long) chunkX & 0xFFFFFFFL) << 28 | (long) chunkZ & 0xFFFFFFFL;
    }

    /** The starts reaching a chunk column, in a fixed order. */
    private List<Start> column(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        List<Start> cached = columns.get(key);
        if (cached != null) return cached;
        List<Start> found = new ArrayList<>();
        int x0 = cx << 4, z0 = cz << 4;
        for (Kind kind : kinds) {
            int spacing = kind.placement.spacing();
            for (int rz = Math.floorDiv(cz - REACH, spacing); rz <= Math.floorDiv(cz + REACH, spacing); rz++)
                for (int rx = Math.floorDiv(cx - REACH, spacing); rx <= Math.floorDiv(cx + REACH, spacing); rx++) {
                    ChunkPos chunk = kind.placement.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                    if (Math.abs(chunk.x - cx) > REACH || Math.abs(chunk.z - cz) > REACH) continue;
                    if (!kind.placement.isStructureChunk(state, chunk.x, chunk.z)) continue;
                    Start start = start(kind, chunk.x, chunk.z);
                    if (start != null && start.reach.intersects(x0, z0, x0 + 15, z0 + 15)) found.add(start);
                }
        }
        List<Start> made = List.copyOf(found);
        if (columns.size() > 4096) columns.clear();
        columns.putIfAbsent(key, made);
        return made;
    }

    @Nullable private Start start(Kind kind, int chunkX, int chunkZ) {
        long key = startKey(kind.index, chunkX, chunkZ);
        Optional<Start> cached = starts.get(key);
        if (cached != null) return cached.orElse(null);
        Start made = null;
        try {
            made = makeStart(kind, chunkX, chunkZ);
        } catch (RuntimeException e) {
            LOGGER.warn("Cubic world: structure set {} failed to start in chunk {}, {}", kind.set.getRegisteredName(), chunkX, chunkZ, e);
        }
        if (starts.size() > 16384) starts.clear();
        starts.putIfAbsent(key, Optional.ofNullable(made));
        return made;
    }

    @Nullable private Start makeStart(Kind kind, int chunkX, int chunkZ) {
        Frame frame = new Frame(kind.deep ? host.floor((chunkX << 4) + 8, (chunkZ << 4) + 8) - 64 : 0, kind.deep);
        ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
        List<StructureSet.StructureSelectionEntry> entries = kind.set.value().structures();
        StructureStart made = StructureStart.INVALID_START;
        if (entries.size() == 1) made = generate(entries.get(0), frame, chunk);
        else {
            // Vanilla's weighted choice, retrying the others where the chosen one cannot start.
            List<StructureSet.StructureSelectionEntry> left = new ArrayList<>(entries);
            WorldgenRandom random = new WorldgenRandom(new net.minecraft.world.level.levelgen.LegacyRandomSource(0L));
            random.setLargeFeatureSeed(seed, chunkX, chunkZ);
            int total = 0;
            for (StructureSet.StructureSelectionEntry entry : left) total += entry.weight();
            while (!left.isEmpty()) {
                int roll = random.nextInt(total), k = 0;
                for (StructureSet.StructureSelectionEntry entry : left) {
                    roll -= entry.weight();
                    if (roll < 0) break;
                    k++;
                }
                StructureSet.StructureSelectionEntry entry = left.get(k);
                made = generate(entry, frame, chunk);
                if (made.isValid()) break;
                left.remove(k);
                total -= entry.weight();
            }
        }
        if (!made.isValid()) return null;
        BoundingBox box = made.getBoundingBox().moved(0, frame.shift, 0);
        if (made.getStructure().terrainAdaptation() != TerrainAdjustment.NONE) box = box.inflatedBy(ADAPT, ADAPT_HEIGHT, ADAPT);
        else box = box.inflatedBy(4, 4, 4); // features in pieces (trees) reach a little past them
        if (frame.shift == 0) {
            // Temples, huts, igloos, shipwrecks and ocean ruins drop onto the ground only as they are placed
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            for (int x : new int[]{box.minX(), box.getCenter().getX(), box.maxX()})
                for (int z : new int[]{box.minZ(), box.getCenter().getZ(), box.maxZ()}) {
                    int ground = host.ground(x, z);
                    low = Math.min(low, ground); high = Math.max(high, ground);
                }
            box = new BoundingBox(box.minX(), Math.min(box.minY(), low - 48), box.minZ(), box.maxX(), Math.max(box.maxY(), high + 32), box.maxZ());
        }
        return new Start(kind.index, chunkX, chunkZ, made, frame, box);
    }

    private static final Object STARTS = new Object();

    private StructureStart generate(StructureSet.StructureSelectionEntry entry, Frame frame, ChunkPos chunk) {
        Structure structure = entry.structure().value();
        // Vanilla lays starts out on one thread; some piece builders (strongholds) keep static state
        synchronized (STARTS) {
            return structure.generate(level.registryAccess(), frame, frame.getBiomeSource(), randomState, level.getStructureManager(),
                    seed, chunk, 0, frame.heights, structure.biomes()::contains);
        }
    }

    // Layout: a structure's blocks, once -------------------------------------------------------

    private Long2ObjectOpenHashMap<Patch> overlay(Start start) {
        long key = startKey(start.kind, start.chunkX, start.chunkZ);
        CompletableFuture<Long2ObjectOpenHashMap<Patch>> future = overlays.get(key);
        if (future == null) {
            CompletableFuture<Long2ObjectOpenHashMap<Patch>> mine = new CompletableFuture<>();
            if (overlays.size() > 256) overlays.values().removeIf(CompletableFuture::isDone);
            future = overlays.putIfAbsent(key, mine);
            if (future == null) {
                future = mine;
                try {
                    mine.complete(build(start));
                } catch (RuntimeException | LinkageError e) {
                    LOGGER.warn("Cubic world: structure {} failed to place at chunk {}, {}",
                            level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(start.start.getStructure()), start.chunkX, start.chunkZ, e);
                    mine.complete(new Long2ObjectOpenHashMap<>());
                }
            }
        }
        return future.join();
    }

    private Long2ObjectOpenHashMap<Patch> build(Start start) {
        Frame frame = start.frame;
        StructureLevel world = new StructureLevel(level, new Reader(), frame.shift, frame.minY, frame.height, frame.sea, (start.reach.minY() + start.reach.maxY()) >> 1, seed,
                RandomSource.create(startKey(start.kind, start.chunkX, start.chunkZ) ^ seed));
        adapt(world, start);
        BoundingBox box = start.start.getBoundingBox();
        int[] seeds = seeding.getOrDefault(start.start.getStructure(), new int[]{GenerationStep.Decoration.SURFACE_STRUCTURES.ordinal(), 0});
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        StructureManager structures = level.structureManager();
        for (int cx = Math.max(box.minX() >> 4, start.chunkX - REACH); cx <= Math.min(box.maxX() >> 4, start.chunkX + REACH); cx++)
            for (int cz = Math.max(box.minZ() >> 4, start.chunkZ - REACH); cz <= Math.min(box.maxZ() >> 4, start.chunkZ + REACH); cz++) {
                long decoration = random.setDecorationSeed(seed, cx << 4, cz << 4);
                random.setFeatureSeed(decoration, seeds[1], seeds[0]);
                BoundingBox chunkBox = new BoundingBox(cx << 4, frame.minY + 1, cz << 4, (cx << 4) + 15, frame.minY + frame.height - 1, (cz << 4) + 15);
                start.start.placeInChunk(world, structures, frame, random, chunkBox, new ChunkPos(cx, cz));
            }
        world.postProcessAll();
        return patches(world);
    }

    /** Buckets a laid-out structure by cube. */
    private static Long2ObjectOpenHashMap<Patch> patches(StructureLevel world) {
        Long2ObjectOpenHashMap<ShortArrayList> at = new Long2ObjectOpenHashMap<>();
        Long2ObjectOpenHashMap<List<BlockState>> states = new Long2ObjectOpenHashMap<>();
        Long2ObjectOpenHashMap<List<CompoundTag>> blockEntities = new Long2ObjectOpenHashMap<>(), entities = new Long2ObjectOpenHashMap<>();
        for (var entry : world.blocks.long2ObjectEntrySet()) {
            long pos = entry.getLongKey();
            BlockPos real = world.at(pos);
            int x = real.getX(), y = real.getY(), z = real.getZ();
            if (!CubeWorld.inRange(y)) continue;
            long cube = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
            at.computeIfAbsent(cube, k -> new ShortArrayList()).add((short) ((y & 15) << 8 | (z & 15) << 4 | x & 15));
            states.computeIfAbsent(cube, k -> new ArrayList<>()).add(entry.getValue());
        }
        for (CompoundTag tag : world.blockEntityTags())
            blockEntities.computeIfAbsent(SectionPos.asLong(tag.getInt("x") >> 4, tag.getInt("y") >> 4, tag.getInt("z") >> 4), k -> new ArrayList<>()).add(tag);
        for (CompoundTag tag : world.entities) {
            ListTag pos = tag.getList("Pos", Tag.TAG_DOUBLE);
            if (pos.size() != 3) continue;
            int x = (int) Math.floor(pos.getDouble(0)), y = (int) Math.floor(pos.getDouble(1)), z = (int) Math.floor(pos.getDouble(2));
            entities.computeIfAbsent(SectionPos.asLong(x >> 4, y >> 4, z >> 4), k -> new ArrayList<>()).add(tag);
        }
        Long2ObjectOpenHashMap<Patch> patches = new Long2ObjectOpenHashMap<>();
        for (long cube : at.keySet()) {
            patches.put(cube, new Patch(at.get(cube).toShortArray(), states.get(cube).toArray(BlockState[]::new),
                    blockEntities.getOrDefault(cube, List.of()), entities.getOrDefault(cube, List.of())));
        }
        for (long cube : entities.keySet())
            if (!patches.containsKey(cube)) patches.put(cube, new Patch(new short[0], new BlockState[0], List.of(), entities.get(cube)));
        return patches;
    }

    /**
     * Fits the terrain to a structure as vanilla's beardifier does, in a simpler shape: under each
     * rigid piece the ground is built up to its floor and over it the ground is cut away, both
     * sloping off at one block per block out to {@link #ADAPT}; buried structures are wrapped in rock.
     */
    private void adapt(StructureLevel world, Start start) {
        TerrainAdjustment how = start.start.getStructure().terrainAdaptation();
        if (how == TerrainAdjustment.NONE) return;
        int shift = start.frame.shift;
        List<BoundingBox> boxes = new ArrayList<>();
        IntArrayList floors = new IntArrayList();
        for (StructurePiece piece : start.start.getPieces()) {
            int delta = 0;
            if (piece instanceof PoolElementStructurePiece pool) {
                if (pool.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) continue;
                delta = pool.getGroundLevelDelta();
            }
            BoundingBox box = piece.getBoundingBox().moved(0, shift, 0);
            boxes.add(box);
            floors.add(box.minY() + delta);
        }
        if (boxes.isEmpty()) return;
        BoundingBox all = start.reach;
        for (int x = all.minX(); x <= all.maxX(); x++) for (int z = all.minZ(); z <= all.maxZ(); z++) {
            int best = -1, r = ADAPT + 1;
            for (int i = 0; i < boxes.size(); i++) {
                BoundingBox b = boxes.get(i);
                int d = Math.max(0, Math.max(Math.max(b.minX() - x, x - b.maxX()), Math.max(b.minZ() - z, z - b.maxZ())));
                if (d < r) { r = d; best = i; }
            }
            if (best < 0) continue;
            BoundingBox box = boxes.get(best);
            int floor = floors.getInt(best);
            switch (how) {
                case BEARD_THIN, BEARD_BOX -> {
                    int top = floor - 1 - r;
                    for (int y = top; y > top - ADAPT_HEIGHT; y--) {
                        BlockState was = world.realState(x, y, z);
                        if (was.blocksMotion() && !(was.getBlock() instanceof LeavesBlock)) break;
                        world.putReal(x, y, z, host.fill(x, y, z, top - y));
                    }
                    int from = floor + r, ground = host.ground(x, z);
                    int to = how == TerrainAdjustment.BEARD_BOX ? box.maxY() - r : Math.max(ground, host.water(x, z)) + 24;
                    for (int y = from; y <= Math.min(to, from + ADAPT_HEIGHT); y++)
                        if (!world.realState(x, y, z).isAir()) world.putReal(x, y, z, AIR);
                    if (how == TerrainAdjustment.BEARD_THIN && from <= ground && world.realState(x, from - 1, z).blocksMotion())
                        world.putReal(x, from - 1, z, host.fill(x, from - 1, z, 0));
                }
                case BURY, ENCAPSULATE -> {
                    if (r > 3) continue;
                    for (int y = box.minY() - 3 + r; y <= box.maxY() + 3 - r; y++)
                        if (!world.realState(x, y, z).blocksMotion()) world.putReal(x, y, z, host.fill(x, y, z, 64));
                }
                default -> {}
            }
        }
    }

    /** The base terrain for one layout, remembering the cube it last read. */
    private final class Reader implements StructureLevel.Terrain {
        private long lastKey = Long.MIN_VALUE;
        @Nullable private LevelChunkSection last;

        @Override public BlockState block(int x, int y, int z) {
            if (!CubeWorld.inRange(y)) return AIR;
            long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
            if (key != lastKey) { last = host.cube(x >> 4, y >> 4, z >> 4); lastKey = key; }
            return last == null ? AIR : last.getBlockState(x & 15, y & 15, z & 15);
        }
        @Override public int ground(int x, int z) { return host.ground(x, z); }
        @Override public int water(int x, int z) { return host.water(x, z); }
        @Override public Holder<Biome> biome(int x, int y, int z) { return host.biome(x, y, z); }
    }

    // Frames: vanilla's view of the world -------------------------------------------------------

    /** Biomes as vanilla sees them in a frame {@code shift} blocks below the real world. */
    private final class FrameBiomes extends BiomeSource {
        private final int shift;
        FrameBiomes(int shift) { this.shift = shift; }
        @Override protected MapCodec<? extends BiomeSource> codec() { throw new UnsupportedOperationException("cubic structure frame"); }
        @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return host.possibleBiomes(); }
        @Override public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
            return host.biome(QuartPos.toBlock(qx), QuartPos.toBlock(qy) + shift, QuartPos.toBlock(qz));
        }
    }

    /**
     * The chunk generator vanilla structure code asks about heights and biomes. Real heights for
     * surface structures; for deep ones, vanilla's -64..320 world with Y 64 at the local floor.
     */
    private final class Frame extends ChunkGenerator {
        final int shift, minY, height, sea;
        final LevelHeightAccessor heights;

        Frame(int shift, boolean deep) {
            super(new FrameBiomes(shift));
            this.shift = shift;
            this.minY = deep ? -64 : CubeWorld.MIN_Y;
            this.height = deep ? 384 : CubeWorld.MAX_Y - CubeWorld.MIN_Y + 1;
            this.sea = deep ? 63 : host.seaLevel();
            this.heights = LevelHeightAccessor.create(minY, height);
        }

        @Override public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heights, RandomState random) {
            int ground = host.ground(x, z);
            int top = switch (type) {
                case OCEAN_FLOOR, OCEAN_FLOOR_WG -> ground;
                default -> Math.max(ground, host.water(x, z));
            };
            return top + 1 - shift;
        }

        @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heights, RandomState random) {
            int ground = host.ground(x, z), water = host.water(x, z);
            int lo = Math.max(heights.getMinBuildHeight(), ground - shift - 128), hi = Math.min(heights.getMaxBuildHeight(), Math.max(ground, water) - shift + 32);
            BlockState[] states = new BlockState[Math.max(0, hi - lo)];
            for (int i = 0; i < states.length; i++) {
                int y = lo + i + shift;
                states[i] = y <= ground ? host.fill(x, y, z, ground - y) : y <= water ? WATER : AIR;
            }
            return new NoiseColumn(lo, states);
        }

        @Override public int getSeaLevel() { return sea; }
        @Override public int getMinY() { return minY; }
        @Override public int getGenDepth() { return height; }
        @Override protected MapCodec<? extends ChunkGenerator> codec() { throw new UnsupportedOperationException("cubic structure frame"); }
        @Override public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes, StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) {}
        @Override public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) {}
        @Override public void spawnOriginalMobs(WorldGenRegion region) {}
        @Override public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk) {
            return CompletableFuture.completedFuture(chunk);
        }
        @Override public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {}
    }
}
