package io.earthplatform.minecraft.cubic.earth;

import io.github.pirut.chunks3.Cube;
import io.github.pirut.chunks3.CubeOffsets;
import io.github.pirut.chunks3.Cubic;
import io.github.pirut.chunks3.CubicEvents;
import io.github.pirut.chunks3.CubeCodec;
import com.mojang.serialization.Lifecycle;
import io.earthplatform.geography.globe.BareEarth;
import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.CopernicusFill;
import io.earthplatform.geography.globe.CopernicusSource;
import io.earthplatform.geography.globe.Drainage;
import io.earthplatform.geography.globe.RemaElevation;
import io.earthplatform.geography.globe.RemaSource;
import io.earthplatform.geography.globe.RiverMap;
import io.earthplatform.geography.globe.SurfaceWaterSource;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.geography.globe.UsgsElevation;
import io.earthplatform.geography.globe.WorldCoverSource;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import io.github.pirut.chunks3.far.Far;
import io.github.pirut.chunks3.gen.FarColumn;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Measures the cubic Earth world's server-side costs without a game: generating the cubes around a
 * player, making far-terrain columns at each level, and encoding cubes for saving and sending.
 * Prints timings and a fingerprint of everything generated, so a speed-up can be checked to leave
 * the world exactly as it was.
 *
 * <p>Off unless asked for: {@code EARTH_BENCH=<cache dir> ./gradlew test --tests '*CubicBench'}, with
 * optional {@code EARTH_BENCH_THREADS} (default 2, as the server's cube workers on four cores),
 * {@code EARTH_BENCH_FAR} (far columns timed per level, default 150), {@code EARTH_BENCH_LEVEL} (the finest far level timed, default 1) and {@code EARTH_BENCH_PLACE}
 * (a place name prefix), {@code EARTH_BENCH_AT} ({@code name,latitude,longitude} for a place of one's own) and
 * {@code EARTH_BENCH_BARE=1} (towns on bare-earth ground) and {@code EARTH_BENCH_STREAMS=0} (no streams from the elevation). The cache dir has the game's {@code earthplatform/cache} layout; the first
 * run downloads what it needs. Results go to standard output.
 */
class CubicBench {
    private record Place(String name, double latitude, double longitude) {}

    private static final List<Place> PLACES = List.of(
            new Place("Yosemite Valley", 37.7240, -119.6365),
            new Place("Lake Tahoe", 38.9520, -120.1080),
            new Place("Amazon", -3.4653, -62.2159),
            new Place("Amazon tributaries", -3.3, -60.6),
            new Place("Tokyo", 35.6812, 139.7671),
            new Place("Mato Grosso", -11.8243, -55.5029));

    @Test
    @EnabledIfEnvironmentVariable(named = "EARTH_BENCH", matches = ".+")
    void bench() throws Exception {
        Path cache = Path.of(System.getenv("EARTH_BENCH"));
        int threads = Integer.parseInt(System.getenv().getOrDefault("EARTH_BENCH_THREADS", "2"));
        int farPerLevel = Integer.parseInt(System.getenv().getOrDefault("EARTH_BENCH_FAR", "150"));
        String only = System.getenv().getOrDefault("EARTH_BENCH_PLACE", "").toLowerCase(Locale.ROOT);
        boolean bare = "1".equals(System.getenv("EARTH_BENCH_BARE"));
        // EARTH_BENCH_BARE_CACHE=0 works out every bare-earth field afresh, as a first visit does.
        boolean bareCache = !"0".equals(System.getenv("EARTH_BENCH_BARE_CACHE"));
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(holder ->
                biomes.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        // The mod's own biomes, from its data pack.
        for (String name : List.of("glacial_lake")) {
            try (var in = CubicBench.class.getResourceAsStream("/data/earthplatform/worldgen/biome/" + name + ".json")) {
                var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in));
                Biome biome = Biome.DIRECT_CODEC.parse(net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, lookup), json).getOrThrow();
                biomes.register(net.minecraft.resources.ResourceKey.create(Registries.BIOME, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("earthplatform", name)),
                        biome, RegistrationInfo.BUILT_IN);
            }
        }
        // The same sources as a new world in the game: the Copernicus fill, 3DEP, REMA and traced rivers too.
        CopernicusSource copernicus = new CopernicusSource(CopernicusSource.AWS_COPERNICUS, cache.resolve(CopernicusSource.DATASET_ID), true, 12, 8, Duration.ofSeconds(30));
        TerrariumTileSource[] source = new TerrariumTileSource[1];
        TerrariumTileSource tiles = new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, cache.resolve(TerrariumTileSource.DATASET_ID),
                true, CubicSettings.tilesKept(), 16, Duration.ofSeconds(20), List.of(new CopernicusFill(copernicus, (z, x, y) -> source[0].cleaned(z, x, y), 512),
                        new UsgsElevation(UsgsElevation.AWS_3DEP, cache.resolve(UsgsElevation.DATASET_ID), true, CubicSettings.blocksKept(), 6, Duration.ofSeconds(30), UsgsElevation.Reading.valueOf(System.getenv().getOrDefault("EARTH_BENCH_3DEP", "GRADED"))),
                        new RemaElevation(new RemaSource(RemaSource.AWS_REMA, cache.resolve(RemaSource.DATASET_ID), true, CubicSettings.blocksKept(), 6, Duration.ofSeconds(30)))));
        source[0] = tiles;
        WorldCoverSource cover = new WorldCoverSource(WorldCoverSource.AWS_WORLDCOVER, cache.resolve(WorldCoverSource.DATASET_ID), true, 48, 8, Duration.ofSeconds(30));
        ClimateSource climate = new ClimateSource(ClimateSource.CHELSA, cache.resolve(ClimateSource.DATASET_ID), true, 320, 8, Duration.ofSeconds(30));
        SurfaceWaterSource water = new SurfaceWaterSource(SurfaceWaterSource.JRC_GSW, cache.resolve(SurfaceWaterSource.DATASET_ID), true,
                96, 4, Duration.ofSeconds(30));
        // EARTH_BENCH_STREAMS=0 leaves out the streams traced from the elevation's valleys, as worlds made before them.
        RiverMap rivers = "0".equals(System.getenv("EARTH_BENCH_STREAMS")) ? new RiverMap(water, 256)
                : new RiverMap(water, new Drainage(new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, cache.resolve(TerrariumTileSource.DATASET_ID), true, 64, 16, Duration.ofSeconds(20)),
                        new ClimateSource(ClimateSource.CHELSA, cache.resolve(ClimateSource.DATASET_ID), true, 32, 8, Duration.ofSeconds(30))::precipitation, 24), 256);
        BareEarth bareEarth = bare ? new BareEarth(tiles, cover, bareCache ? cache.resolve(BareEarth.ID) : null) : null;
        MessageDigest all = MessageDigest.getInstance("SHA-256");
        List<Place> places = PLACES;
        String at = System.getenv("EARTH_BENCH_AT");
        if (at != null) {
            String[] parts = at.split(",");
            places = List.of(new Place(parts[0], Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
        }
        for (Place place : places) {
            if (!only.isEmpty() && !place.name.toLowerCase(Locale.ROOT).startsWith(only)) continue;
            EarthSettings settings = EarthSettings.at(place.latitude, place.longitude);
            EarthGenerator warm = new EarthGenerator(1234L, biomes, settings, tiles, cover, climate, null, rivers, bareEarth);
            int[] home = warm.arrival();
            var fetched = io.earthplatform.geography.globe.Downloads.totals();
            long t0 = System.nanoTime();
            // As /cubes goto does: the data about the landing, then the rest while the cubes are made.
            boolean near = System.getenv("EARTH_BENCH_NEAR") != null;
            if (near) warm.prepareNear(home[0], home[1]); else warm.prepare(home[0], home[1]);
            System.out.printf(Locale.ROOT, "%n== %s (block %d, %d)%nprepare (data fetch): %.0f ms, %s%n", place.name, home[0], home[1], ms(t0),
                    io.earthplatform.geography.globe.Downloads.since(fetched));
            fetched = io.earthplatform.geography.globe.Downloads.totals();
            // The rest of a /cubes goto's wait: the search for a landing spot, as for a famous place.
            var landing = CubicEvents.class.getDeclaredMethod("landing", io.github.pirut.chunks3.gen.CubeGenerator.class, int[].class, int.class, boolean.class);
            landing.setAccessible(true);
            t0 = System.nanoTime();
            int[] spot = (int[]) landing.invoke(null, warm, home, 2048, true);
            System.out.printf(Locale.ROOT, "landing spot (%d, %d): %.0f ms, %s%n", spot[0], spot[1], ms(t0), io.earthplatform.geography.globe.Downloads.since(fetched));
            if (near) {
                long landed = System.nanoTime();
                var before = io.earthplatform.geography.globe.Downloads.totals();
                Thread.ofVirtual().start(() -> {
                    warm.prepare(home[0], home[1]);
                    System.out.printf(Locale.ROOT, "far data: %.0f ms after landing, %s%n", ms(landed), io.earthplatform.geography.globe.Downloads.since(before));
                });
            }

            // Cubes around a player standing on the ground, as the server loads them.
            EarthGenerator gen = new EarthGenerator(1234L, biomes, settings, tiles, cover, climate, null, rivers, bareEarth);
            int ground = gen.surfaceY(home[0], home[1]);
            int cx = home[0] >> 4, cy = (ground + 2) >> 4, cz = home[1] >> 4;
            CubeOffsets offsets = CubeOffsets.of(Cubic.MAX_HORIZONTAL_RADIUS, Cubic.VERTICAL_RADIUS);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<LevelChunkSection>> futures = new ArrayList<>();
            java.util.concurrent.atomic.AtomicLong cubeCpu = new java.util.concurrent.atomic.AtomicLong();
            long[] took = new long[offsets.size()];
            long gc0 = gcMillis();
            t0 = System.nanoTime();
            for (int i = 0; i < offsets.size(); i++) {
                int x = cx + offsets.dx(i), y = cy + offsets.dy(i), z = cz + offsets.dz(i);
                int index = i;
                futures.add(pool.submit(() -> {
                    long start = System.nanoTime(), cpu0 = CPU.getCurrentThreadCpuTime();
                    LevelChunkSection section = gen.generate(x, y, z);
                    took[index] = System.nanoTime() - start;
                    cubeCpu.addAndGet(CPU.getCurrentThreadCpuTime() - cpu0);
                    return section;
                }));
            }
            List<LevelChunkSection> cubes = new ArrayList<>(futures.size());
            for (Future<LevelChunkSection> future : futures) cubes.add(future.get());
            double wall = ms(t0);
            if (System.getenv("EARTH_BENCH_DEPTH") != null) depths(gen, offsets, cx, cy, cz, cubes, took);
            System.out.printf(Locale.ROOT, "cubes: %d in %.0f ms on %d threads = %.0f cubes/s (%.3f ms per cube per thread, cpu %.3f ms), gc %d ms%n",
                    cubes.size(), wall, threads, cubes.size() / wall * 1000, wall * threads / cubes.size(), cubeCpu.get() / 1e6 / cubes.size(), gcMillis() - gc0);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long bytes = 0, nonEmpty = 0;
            for (LevelChunkSection section : cubes) {
                byte[] data = CubeCodec.sectionBytes(section);
                fingerprint(digest, section);
                bytes += data.length;
                if (!section.hasOnlyAir()) nonEmpty++;
            }
            byte[] cubeHash = digest.digest();
            all.update(cubeHash);
            System.out.printf(Locale.ROOT, "cube network bytes: %d total, %.0f per cube, %d not empty; fingerprint %s%n",
                    bytes, bytes / (double) cubes.size(), nonEmpty, HexFormat.of().formatHex(cubeHash, 0, 8));

            // Saving and loading: NBT encode, compress, decode.
            CubeCodec codec = new CubeCodec(biomes);
            t0 = System.nanoTime();
            List<CompoundTag> tags = new ArrayList<>();
            for (int i = 0; i < 1000 && i < cubes.size(); i++) tags.add(codec.write(new Cube(0, i, 0, cubes.get(i)), lookup));
            double write = ms(t0);
            t0 = System.nanoTime();
            for (CompoundTag tag : tags) codec.read(tag);
            double read = ms(t0);
            CompoundTag region = new CompoundTag();
            for (int i = 0; i < tags.size(); i++) region.put(Integer.toString(i), tags.get(i));
            java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
            t0 = System.nanoTime();
            NbtIo.writeCompressed(region, compressed);
            double deflate = ms(t0);
            System.out.printf(Locale.ROOT, "codec per cube: write %.3f ms, read %.3f ms, compress %.3f ms, %.0f bytes compressed%n",
                    write / tags.size(), read / tags.size(), deflate / tags.size(), compressed.size() / (double) tags.size());

            // Far columns, nearest first at each level, as a client asks for them. EARTH_BENCH_FAR_WORKERS=1 makes them
            // as the server does, more at once while they wait on downloads.
            io.github.pirut.chunks3.far.FarWorkers farWorkers = "1".equals(System.getenv("EARTH_BENCH_FAR_WORKERS"))
                    ? new io.github.pirut.chunks3.far.FarWorkers("bench far", threads, 4 * threads) : null;
            MessageDigest farDigest = MessageDigest.getInstance("SHA-256");
            double farTotal = 0;
            long farCount = 0;
            for (int level = Integer.parseInt(System.getenv().getOrDefault("EARTH_BENCH_LEVEL", "" + Far.MIN_LEVEL)); level <= Integer.parseInt(System.getenv().getOrDefault("EARTH_BENCH_LEVEL_MAX", "" + Far.MAX_LEVEL)); level++) {
                int lv = level;
                LongArrayList keys = new LongArrayList();
                Far.around(home[0], home[1], key -> { if (Far.level(key) == lv) keys.add(key); });
                int px = Far.column(home[0], level), pz = Far.column(home[1], level);
                keys.sort((a, b) -> Long.compare(distance(a, px, pz), distance(b, px, pz)));
                int n = Math.min(farPerLevel, keys.size());
                List<Future<FarColumn>> made = new ArrayList<>();
                java.util.concurrent.atomic.AtomicLong farCpu = new java.util.concurrent.atomic.AtomicLong();
                t0 = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    long key = keys.getLong(i);
                    java.util.concurrent.Callable<FarColumn> job = () -> {
                        long cpu0 = CPU.getCurrentThreadCpuTime();
                        FarColumn column = new FarColumn();
                        gen.farColumn(Far.level(key), Far.x(key), Far.z(key), column);
                        farCpu.addAndGet(CPU.getCurrentThreadCpuTime() - cpu0);
                        return column;
                    };
                    if (farWorkers == null) made.add(pool.submit(job));
                    else {
                        java.util.concurrent.FutureTask<FarColumn> task = new java.util.concurrent.FutureTask<>(job);
                        farWorkers.execute(task);
                        made.add(task);
                    }
                }
                long farBytes = 0, sections = 0;
                MessageDigest levelDigest = MessageDigest.getInstance("SHA-256");
                for (Future<FarColumn> future : made) {
                    FarColumn column = future.get();
                    for (MessageDigest each : new MessageDigest[] {farDigest, levelDigest}) {
                        each.update(java.nio.ByteBuffer.allocate(4).putInt(column.bottom).array());
                        for (short drop : column.drops) each.update((byte) drop);
                    }
                    for (LevelChunkSection section : column.sections) {
                        if (section != null) {
                            fingerprint(farDigest, section);
                            fingerprint(levelDigest, section);
                            farBytes += CubeCodec.sectionBytes(section).length;
                        }
                        sections++;
                    }
                }
                double levelWall = ms(t0);
                farTotal += levelWall / n * keys.size() * threads;
                if (farWorkers != null) System.out.printf(Locale.ROOT, "far level %d: %.1f s, %d at once at the end%n", level, levelWall / 1000, farWorkers.allowed());
                farCount += keys.size();
                System.out.printf(Locale.ROOT, "far level %d: %d of %d columns, %.2f ms per column per thread (cpu %.2f ms), %.1f sections and %.0f bytes per column; fingerprint %s%n",
                        level, n, keys.size(), levelWall * threads / n, farCpu.get() / 1e6 / n, sections / (double) n, farBytes / (double) n,
                        HexFormat.of().formatHex(levelDigest.digest(), 0, 8));
            }
            byte[] farHash = farDigest.digest();
            all.update(farHash);
            System.out.printf(Locale.ROOT, "far: about %.0f s of one thread for all %d columns; fingerprint %s%n",
                    farTotal / 1000, farCount, HexFormat.of().formatHex(farHash, 0, 8));
            pool.shutdown();
            if (farWorkers != null) farWorkers.shutdownNow();
        }
        System.out.printf(Locale.ROOT, "%nelevation %s%nland cover %s%nclimate %s%nfingerprint of everything: %s%n",
                tiles.stats(), cover.stats(), climate.stats(), HexFormat.of().formatHex(all.digest(), 0, 8));
    }

    /** Blocks by registry id and biomes by name, so the print is the same in every run. */
    private static Biome standIn(Biome like) {
        return new Biome.BiomeBuilder().hasPrecipitation(true).temperature(0).downfall(0.5f).specialEffects(like.getSpecialEffects())
                .mobSpawnSettings(like.getMobSettings()).generationSettings(like.getGenerationSettings()).build();
    }

    private static void fingerprint(MessageDigest digest, LevelChunkSection section) {
        byte[] ids = new byte[4096 * 2];
        for (int i = 0; i < 4096; i++) {
            int id = net.minecraft.world.level.block.Block.getId(section.getBlockState(i & 15, i >> 8, i >> 4 & 15));
            ids[2 * i] = (byte) id; ids[2 * i + 1] = (byte) (id >> 8);
        }
        digest.update(ids);
        for (int i = 0; i < 64; i++) {
            digest.update(section.getBiomes().get(i & 3, i >> 4, i >> 2 & 3).unwrapKey().orElseThrow().location().toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static long distance(long key, int px, int pz) {
        long dx = Far.x(key) - px, dz = Far.z(key) - pz;
        return dx * dx + dz * dz;
    }

    /**
     * EARTH_BENCH_DEPTH=1: how the cubes around a player standing on the ground split between air above the ground,
     * the ground itself and rock buried under it (below the lowest ground of its own and the four next columns), with
     * generation time, faces a client would mesh (between solid and open blocks) and sent bytes for each.
     */
    private static void depths(EarthGenerator gen, CubeOffsets offsets, int cx, int cy, int cz, List<LevelChunkSection> cubes, long[] took) {
        String[] names = {"above ground", "ground band", "buried"};
        long[] count = new long[3], nanos = new long[3], faces = new long[3], bytes = new long[3];
        long[] nearCount = new long[3];
        java.util.Map<Long, Integer> lowest = new java.util.HashMap<>(), highest = new java.util.HashMap<>();
        for (int i = 0; i < cubes.size(); i++) {
            int x = cx + offsets.dx(i), y = cy + offsets.dy(i), z = cz + offsets.dz(i);
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            for (int[] d : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int bx = x + d[0], bz = z + d[1];
                long column = ((long) bx << 32) | (bz & 0xffffffffL);
                if (!lowest.containsKey(column)) {
                    int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                    for (int u = 0; u < 16; u++) for (int v = 0; v < 16; v++) {
                        int g = gen.surfaceY((bx << 4) + u, (bz << 4) + v);
                        lo = Math.min(lo, g);
                        hi = Math.max(hi, gen.skyTopY((bx << 4) + u, (bz << 4) + v));
                    }
                    lowest.put(column, lo);
                    highest.put(column, hi);
                }
                low = Math.min(low, lowest.get(column));
                if (d[0] == 0 && d[1] == 0) high = highest.get(column);
            }
            int kind = (y << 4) > high ? 0 : (y << 4) + 15 < low - 16 ? 2 : 1;
            LevelChunkSection section = cubes.get(i);
            count[kind]++;
            nanos[kind] += took[i];
            bytes[kind] += CubeCodec.sectionBytes(section).length;
            faces[kind] += openFaces(section);
            int dx = offsets.dx(i), dy = offsets.dy(i), dz = offsets.dz(i);
            if (dx * dx + dz * dz <= 2 * 2 + 2 && Math.abs(dy) <= Cubic.VERTICAL_RADIUS) nearCount[kind]++;
        }
        long allNanos = nanos[0] + nanos[1] + nanos[2], allFaces = faces[0] + faces[1] + faces[2], all = count[0] + count[1] + count[2];
        for (int k = 0; k < 3; k++) {
            System.out.printf(Locale.ROOT, "  %-12s %5d cubes (%2.0f%%), generation %6.0f ms (%2.0f%%), %.2f ms each, open faces %8d (%2.0f%%), %8d bytes; within 2 cubes across: %d%n",
                    names[k], count[k], 100.0 * count[k] / all, nanos[k] / 1e6, 100.0 * nanos[k] / allNanos, nanos[k] / 1e6 / Math.max(1, count[k]),
                    faces[k], 100.0 * faces[k] / Math.max(1, allFaces), bytes[k], nearCount[k]);
        }
    }

    /** Faces between a solid block and an open one inside a cube: about what a client would mesh. */
    private static long openFaces(LevelChunkSection section) {
        if (section.hasOnlyAir()) return 0;
        boolean[] solid = new boolean[4096];
        for (int i = 0; i < 4096; i++) solid[i] = section.getBlockState(i & 15, i >> 8, i >> 4 & 15).canOcclude();
        long faces = 0;
        for (int i = 0; i < 4096; i++) {
            int x = i & 15, z = i >> 4 & 15, y = i >> 8;
            if (x < 15 && solid[i] != solid[i + 1]) faces++;
            if (z < 15 && solid[i] != solid[i + 16]) faces++;
            if (y < 15 && solid[i] != solid[i + 256]) faces++;
        }
        return faces;
    }

    /** CPU time of the thread doing the work, which other processes on a shared machine leave alone. */
    private static final java.lang.management.ThreadMXBean CPU = java.lang.management.ManagementFactory.getThreadMXBean();

    private static double ms(long start) { return (System.nanoTime() - start) / 1e6; }

    private static long gcMillis() {
        long total = 0;
        for (var bean : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) total += Math.max(0, bean.getCollectionTime());
        return total;
    }
}
