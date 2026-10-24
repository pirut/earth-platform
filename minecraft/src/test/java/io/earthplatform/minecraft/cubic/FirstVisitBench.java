package io.earthplatform.minecraft.cubic;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.geography.globe.BareEarth;
import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.CopernicusFill;
import io.earthplatform.geography.globe.CopernicusSource;
import io.earthplatform.geography.globe.Downloads;
import io.earthplatform.geography.globe.Drainage;
import io.earthplatform.geography.globe.ElevationLayer;
import io.earthplatform.geography.globe.GsiElevation;
import io.earthplatform.geography.globe.HrdemElevation;
import io.earthplatform.geography.globe.IgnElevation;
import io.earthplatform.geography.globe.RemaElevation;
import io.earthplatform.geography.globe.RemaSource;
import io.earthplatform.geography.globe.RiverMap;
import io.earthplatform.geography.globe.SurfaceWaterSource;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.geography.globe.UsgsElevation;
import io.earthplatform.geography.globe.WorldCoverSource;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import io.earthplatform.minecraft.cubic.far.Far;
import io.earthplatform.minecraft.cubic.far.FarServer;
import io.earthplatform.minecraft.cubic.far.FarWorkers;
import io.earthplatform.minecraft.cubic.gen.FarColumn;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A first visit to a place as a game makes it, without a game: a travel's data fetch and landing
 * search, the cubes about the landing that the arrival waits for, then the cubes around the player
 * and the far columns a client asks for, on workers like the server's, while the far levels' data
 * comes in behind them. Prints when the player lands, when the cubes are all made and how much of
 * the far terrain is made second by second, what was downloaded, and fingerprints of everything
 * made, so a change can be checked to leave the world as it was. Run it with an empty cache folder
 * for a first visit, and again with the same folder for a later one.
 *
 * <p>Off unless asked for: {@code EARTH_VISIT=<cache dir> ./gradlew test --tests '*FirstVisitBench' -i}, with
 * optional {@code EARTH_VISIT_AT} ({@code name,latitude,longitude}, Mount Shasta by default), {@code EARTH_VISIT_HOME}
 * ({@code latitude,longitude} of the world's home, the place itself by default), {@code EARTH_VISIT_SECONDS} (how long
 * to wait for the far terrain, 600 by default), {@code EARTH_VISIT_CORES} (size the workers as the game would on a
 * machine with that many cores) and {@code EARTH_VISIT_FAR_MOST} (most far columns made at once, as
 * {@code -Dearthplatform.far.mostThreads}).
 */
class FirstVisitBench {
    @Test
    @EnabledIfEnvironmentVariable(named = "EARTH_VISIT", matches = ".+")
    void visit() throws Exception {
        Path cache = Path.of(System.getenv("EARTH_VISIT"));
        String[] at = System.getenv().getOrDefault("EARTH_VISIT_AT", "Mount Shasta,41.4092,-122.1949").split(",");
        String name = at[0];
        double latitude = Double.parseDouble(at[1]), longitude = Double.parseDouble(at[2]);
        String[] home = System.getenv().getOrDefault("EARTH_VISIT_HOME", at[1] + "," + at[2]).split(",");
        int seconds = Integer.parseInt(System.getenv().getOrDefault("EARTH_VISIT_SECONDS", "600"));
        int cores = Integer.parseInt(System.getenv().getOrDefault("EARTH_VISIT_CORES", "" + Runtime.getRuntime().availableProcessors()));
        // As ServerCubeWorld and FarServer size theirs.
        int cubeThreads = Math.max(1, Math.min(4, cores - 2));
        int farBusy = FarServer.workersFor(cores);
        int farMost = Integer.parseInt(System.getenv().getOrDefault("EARTH_VISIT_FAR_MOST", "" + FarServer.mostWorkersFor(farBusy)));

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(holder -> biomes.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        for (String biome : List.of("glacial_lake")) {
            try (var in = FirstVisitBench.class.getResourceAsStream("/data/earthplatform/worldgen/biome/" + biome + ".json")) {
                var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in));
                Biome parsed = Biome.DIRECT_CODEC.parse(net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, lookup), json).getOrThrow();
                biomes.register(net.minecraft.resources.ResourceKey.create(Registries.BIOME, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("earthplatform", biome)),
                        parsed, RegistrationInfo.BUILT_IN);
            }
        }

        // The sources a new world reads, as CubicSettings makes them.
        Duration thirty = Duration.ofSeconds(30);
        CopernicusSource copernicus = new CopernicusSource(CopernicusSource.AWS_COPERNICUS, cache.resolve(CopernicusSource.DATASET_ID), true, 12, 8, thirty);
        TerrariumTileSource[] made = new TerrariumTileSource[1];
        List<ElevationLayer> layers = List.of(
                new CopernicusFill(copernicus, (z, x, y) -> made[0].cleaned(z, x, y), 512),
                new HrdemElevation(HrdemElevation.AWS_HRDEM, cache.resolve(HrdemElevation.DATASET_ID), true, CubicSettings.blocksKept(), 6, thirty),
                new UsgsElevation(UsgsElevation.AWS_3DEP, cache.resolve(UsgsElevation.DATASET_ID), true, CubicSettings.blocksKept(), 6, thirty, UsgsElevation.Reading.GRADED),
                new RemaElevation(new RemaSource(RemaSource.AWS_REMA, cache.resolve(RemaSource.DATASET_ID), true, CubicSettings.blocksKept(), 6, thirty)),
                new IgnElevation(IgnElevation.IGN_WMS, cache.resolve(IgnElevation.DATASET_ID), true, 64, 12, thirty),
                new GsiElevation(GsiElevation.GSI_TILES, cache.resolve(GsiElevation.DATASET_ID), true, 64, 16, thirty));
        Path tileCache = cache.resolve(TerrariumTileSource.DATASET_ID.toLowerCase(Locale.ROOT));
        TerrariumTileSource tiles = new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, tileCache, true, CubicSettings.tilesKept(), 16, Duration.ofSeconds(20), layers);
        made[0] = tiles;
        WorldCoverSource cover = new WorldCoverSource(WorldCoverSource.AWS_WORLDCOVER, cache.resolve(WorldCoverSource.DATASET_ID), true, 48, 8, thirty);
        ClimateSource climate = new ClimateSource(ClimateSource.CHELSA, cache.resolve(ClimateSource.DATASET_ID), true, 320, 8, thirty);
        SurfaceWaterSource water = new SurfaceWaterSource(SurfaceWaterSource.JRC_GSW, cache.resolve(SurfaceWaterSource.DATASET_ID), true, 96, 4, thirty);
        RiverMap rivers = new RiverMap(water, new Drainage(new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, tileCache, true, 64, 16, Duration.ofSeconds(20)),
                new ClimateSource(ClimateSource.CHELSA, cache.resolve(ClimateSource.DATASET_ID), true, 32, 8, thirty)::precipitation, 24), 256);
        BareEarth bareEarth = new BareEarth(tiles, cover, cache.resolve(BareEarth.ID));
        EarthSettings settings = EarthSettings.at(Double.parseDouble(home[0]), Double.parseDouble(home[1]));
        EarthGenerator earth = new EarthGenerator(1234L, biomes, settings, tiles, cover, climate, null, rivers, bareEarth);

        System.out.printf(Locale.ROOT, "%n== First visit to %s (%.4f, %.4f), world home %s, %s; %d cores: %d cube workers, far %d to %d%n",
                name, latitude, longitude, home[0] + "," + home[1], cache, cores, cubeThreads, farBusy, farMost);

        Tick tick = new Tick();
        long t0 = System.nanoTime();
        Map<String, long[]> before = Downloads.totals();
        // A famous place's travel, as the spawn picker's: the data about the landing, then the search for it.
        int[] centre = earth.block(latitude, longitude);
        earth.prepareNear(centre[0], centre[1]);
        double prepared = seconds(t0);
        String nearData = Downloads.since(before);
        var landing = CubicEvents.class.getDeclaredMethod("landing", io.earthplatform.minecraft.cubic.gen.CubeGenerator.class, int[].class, int.class, boolean.class);
        landing.setAccessible(true);
        Map<String, long[]> beforeLanding = Downloads.totals();
        int[] spot = (int[]) landing.invoke(null, earth, centre, 2048, true);
        double landed = seconds(t0);
        String landingData = Downloads.since(beforeLanding);
        tick.start();
        // The far levels' data comes in behind, on the game's IO pool.
        double[] farData = {-1};
        Map<String, long[]> beforeFar = Downloads.totals();
        String[] farDataText = {""};
        Thread prepare = Thread.ofPlatform().daemon().name("visit far data").start(() -> {
            earth.prepareFar(centre[0], centre[1]);
            farData[0] = seconds(t0);
            farDataText[0] = Downloads.since(beforeFar);
        });

        // The cubes about the landing, which the traveller waits for (readyAround), then all the cubes around them.
        ExecutorService cubeWorkers = Executors.newFixedThreadPool(cubeThreads, runnable -> { Thread t = new Thread(runnable, "visit cube worker"); t.setDaemon(true); return t; });
        int ground = Math.max(earth.surfaceY(spot[0], spot[1]), earth.waterY(spot[0], spot[1]));
        int cx = spot[0] >> 4, cy = ground >> 4, cz = spot[1] >> 4;
        Map<Long, LevelChunkSection> cubes = new ConcurrentHashMap<>();
        List<Future<?>> arrival = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            int x = cx + dx, y = cy + dy, z = cz + dz;
            arrival.add(cubeWorkers.submit(() -> cubes.put(CubeKey.pack(x, y, z), earth.generate(x, y, z))));
        }
        for (Future<?> cube : arrival) cube.get();
        double arrived = seconds(t0);
        System.out.printf(Locale.ROOT, "data about the landing %.1f s (%s)%nlanding search done at %.1f s (%s)%narrived at %.1f s%n",
                prepared, nearData, landed, landingData, arrived);

        // The player stands at the landing looking north. Cubes: buried ones beyond three columns are left, as the server leaves them.
        double yaw = 180, lookX = ViewOrder.lookX(yaw), lookZ = ViewOrder.lookZ(yaw);
        int h = Cubic.MAX_HORIZONTAL_RADIUS, v = Cubic.VERTICAL_RADIUS;
        CubeOffsets offsets = CubeOffsets.of(h, v, ViewOrder.sector(yaw));
        AtomicInteger nearPending = new AtomicInteger();
        double[] nearDone = {-1};
        Thread near = Thread.ofPlatform().daemon().name("visit near").start(() -> {
            try {
                // Lowest ground of every cube column, worked out on the workers as the server does.
                Map<Long, Integer> lows = new ConcurrentHashMap<>();
                List<Future<?>> lowJobs = new ArrayList<>();
                for (int dz = -h - 1; dz <= h + 1; dz++) for (int dx = -h - 1; dx <= h + 1; dx++) {
                    int x = cx + dx, z = cz + dz;
                    lowJobs.add(cubeWorkers.submit(() -> lows.put(CubeKey.column(x, z), earth.lowestGround(x, z))));
                }
                for (Future<?> job : lowJobs) job.get();
                List<Future<?>> jobs = new ArrayList<>();
                for (int i = 0; i < offsets.size(); i++) {
                    int dx = offsets.dx(i), dz = offsets.dz(i), x = cx + dx, y = cy + offsets.dy(i), z = cz + dz;
                    if (cubes.containsKey(CubeKey.pack(x, y, z))) continue;
                    if (dx * dx + dz * dz > 3 * 3 + 3) {
                        int low = lows.get(CubeKey.column(x, z));
                        for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) low = Math.min(low, lows.get(CubeKey.column(x + side[0], z + side[1])));
                        if ((y << 4) + 15 < low - 16) continue;
                    }
                    nearPending.incrementAndGet();
                    jobs.add(cubeWorkers.submit(() -> {
                        try { cubes.put(CubeKey.pack(x, y, z), earth.generate(x, y, z)); }
                        finally { nearPending.decrementAndGet(); }
                    }));
                }
                for (Future<?> job : jobs) job.get();
                nearDone[0] = seconds(t0);
            } catch (Exception failure) {
                failure.printStackTrace();
            }
        });

        // Far columns, asked for as a client asks: in its order, at most 192 at once, the plan made again every half second.
        FarWorkers farWorkers = new FarWorkers("visit far", farBusy, farMost);
        int bx = spot[0], bz = spot[1];
        LongOpenHashSet all = new LongOpenHashSet(), wanted = new LongOpenHashSet(), inView = new LongOpenHashSet();
        Far.around(bx, bz, Far.MIN_LEVEL, key -> {
            all.add(key);
            if (!Far.refined(Far.level(key), Far.x(key), Far.z(key), bx, bz)) {
                wanted.add(key);
                if (inView(key, bx, bz, lookX, lookZ)) inView.add(key);
            }
        });
        Map<Long, FarColumn> columns = new ConcurrentHashMap<>();
        LongOpenHashSet asked = new LongOpenHashSet();
        AtomicInteger inFlight = new AtomicInteger(), failures = new AtomicInteger();
        long[] levelNanos = new long[Far.MAX_LEVEL + 1];
        int[] levelCount = new int[Far.MAX_LEVEL + 1];
        double[] wantedAt = new double[101], inViewAt = new double[101];
        java.util.Arrays.fill(wantedAt, -1);
        java.util.Arrays.fill(inViewAt, -1);
        int nextPrint = 0;
        System.out.printf(Locale.ROOT, "far: %d columns kept, %d drawn at their own level, %d of them in view%n", all.size(), wanted.size(), inView.size());
        LongArrayList plan = new LongArrayList();
        int planned = 0;
        long lastPlan = 0;
        while (true) {
            long now = System.nanoTime();
            farWorkers.hold(nearPending.get() > 0);
            if (now - lastPlan > 500_000_000L || planned >= plan.size()) {
                lastPlan = now;
                plan.clear();
                Long2LongOpenHashMap order = new Long2LongOpenHashMap();
                for (long key : all) if (!columns.containsKey(key) && !asked.contains(key)) {
                    plan.add(key);
                    order.put(key, priority(key, bx, bz, lookX, lookZ, columns));
                }
                plan.unstableSort((a, b) -> Long.compare(order.get(a), order.get(b)));
                planned = 0;
            }
            while (planned < plan.size() && inFlight.get() < 192) {
                long key = plan.getLong(planned++);
                if (columns.containsKey(key) || !asked.add(key)) continue;
                inFlight.incrementAndGet();
                farWorkers.execute(() -> {
                    long start = System.nanoTime();
                    try {
                        FarColumn column = new FarColumn();
                        earth.farColumn(Far.level(key), Far.x(key), Far.z(key), column);
                        for (LevelChunkSection section : column.sections) if (section != null) CubeCodec.sectionBytes(section);
                        columns.put(key, column);
                        synchronized (levelNanos) { levelNanos[Far.level(key)] += System.nanoTime() - start; levelCount[Far.level(key)]++; }
                    } catch (RuntimeException failure) {
                        if (failures.incrementAndGet() <= 3) failure.printStackTrace();
                        synchronized (asked) { asked.remove(key); }
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            }
            int doneWanted = 0, doneInView = 0;
            for (long key : wanted) if (columns.containsKey(key)) { doneWanted++; if (inView.contains(key)) doneInView++; }
            double t = seconds(t0);
            int share = (int) (100L * doneWanted / wanted.size()), viewShare = (int) (100L * doneInView / Math.max(1, inView.size()));
            for (int p = 0; p <= share; p++) if (wantedAt[p] < 0) wantedAt[p] = t;
            for (int p = 0; p <= viewShare; p++) if (inViewAt[p] < 0) inViewAt[p] = t;
            if (t >= nextPrint) {
                System.out.printf(Locale.ROOT, "t=%4.0f s: far %5d of %d made, %3d%% of the drawn ones (%3d%% in view), %d working; near %s; %s%n", t, columns.size(), all.size(),
                        share, viewShare, inFlight.get(), nearDone[0] >= 0 ? "done" : nearPending.get() + " waiting", Downloads.since(before).replaceAll(":.*", ""));
                nextPrint += t < 60 ? 5 : 15;
            }
            if (columns.size() + failures.get() >= all.size() && inFlight.get() == 0 || t > seconds) break;
            Thread.sleep(50);
        }
        double farDone = seconds(t0);
        tick.stop();
        near.join(60_000);
        prepare.join(60_000);

        System.out.printf(Locale.ROOT, "%n-- %s summary%narrived %.1f s (data %.1f s, landing search %.1f s); cubes all made %.1f s; far data fetched %.1f s; far all made %.1f s (%d failures)%n",
                name, arrived, prepared, landed - prepared, nearDone[0], farData[0], farDone, failures.get());
        for (int p : new int[] {25, 50, 75, 90, 95, 99, 100})
            System.out.printf(Locale.ROOT, "far drawn %3d%%: %6.1f s, in view %6.1f s%n", p, wantedAt[p], inViewAt[p]);
        for (int level = Far.MIN_LEVEL; level <= Far.MAX_LEVEL; level++)
            System.out.printf(Locale.ROOT, "level %d: %5d columns, %.1f ms each (wall, waits included)%n", level, levelCount[level], levelNanos[level] / 1e6 / Math.max(1, levelCount[level]));
        System.out.println(tick);
        System.out.println("far data: " + farDataText[0]);
        System.out.println("all downloads: " + Downloads.since(before));

        // Fingerprints, in key order, so they compare between runs whatever order the workers made things in.
        MessageDigest cubeDigest = MessageDigest.getInstance("SHA-256");
        LongArrayList cubeKeys = new LongArrayList(cubes.keySet());
        cubeKeys.sort(Long::compare);
        for (long key : cubeKeys) fingerprint(cubeDigest, cubes.get(key));
        System.out.printf(Locale.ROOT, "cubes: %d, fingerprint %s%n", cubeKeys.size(), HexFormat.of().formatHex(cubeDigest.digest(), 0, 8));
        StringBuilder prints = new StringBuilder();
        for (int level = Far.MIN_LEVEL; level <= Far.MAX_LEVEL; level++) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            LongArrayList keys = new LongArrayList();
            for (long key : columns.keySet()) if (Far.level(key) == level) keys.add(key);
            keys.sort(Long::compare);
            for (long key : keys) {
                FarColumn column = columns.get(key);
                digest.update(java.nio.ByteBuffer.allocate(12).putLong(key).putInt(column.bottom).array());
                for (short drop : column.drops) digest.update((byte) drop);
                for (LevelChunkSection section : column.sections) if (section != null) fingerprint(digest, section);
            }
            prints.append(String.format(Locale.ROOT, " L%d %s", level, HexFormat.of().formatHex(digest.digest(), 0, 6)));
        }
        System.out.println("far fingerprints:" + prints);
        System.out.printf(Locale.ROOT, "elevation %s%nland cover %s%nclimate %s%n", tiles.stats(), cover.stats(), climate.stats());
        farWorkers.shutdownNow();
        cubeWorkers.shutdownNow();
    }

    private static double seconds(long start) { return (System.nanoTime() - start) / 1e9; }

    /**
     * A stand-in for the server's tick, at its priority: every 50 ms the same few milliseconds of
     * work, timed, from the landing until the far terrain is made, to show how much the workers
     * crowd the processor: how late each one starts and how much longer than on an idle machine it takes.
     */
    private static final class Tick {
        private final LongArrayList late = new LongArrayList(), took = new LongArrayList();
        private final long idle;
        private volatile boolean running = true;
        private Thread thread;
        private long sink;

        Tick() {
            long[] times = new long[21];
            for (int i = 0; i < times.length; i++) times[i] = work();
            java.util.Arrays.sort(times);
            idle = times[times.length / 2];
        }

        private long work() {
            long start = System.nanoTime(), hash = sink;
            for (int i = 0; i < 2_000_000; i++) hash = hash * 31 + (i ^ hash >>> 7);
            sink = hash;
            return System.nanoTime() - start;
        }

        void start() {
            thread = Thread.ofPlatform().daemon().name("visit tick").start(() -> {
                long next = System.nanoTime();
                while (running) {
                    next += 50_000_000L;
                    long wait = next - System.nanoTime();
                    if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
                    long started = System.nanoTime();
                    late.add(Math.max(0, started - next));
                    took.add(work());
                    if (System.nanoTime() - next > 50_000_000L) next = System.nanoTime();
                }
            });
        }

        void stop() throws InterruptedException { running = false; thread.join(); }

        @Override public String toString() {
            return String.format(Locale.ROOT, "tick stand-in (%d ticks): starts late p50 %.1f ms, p99 %.1f ms, most %.1f ms; %.1f ms of work took p50 %.1f ms, p99 %.1f ms, most %.1f ms",
                    late.size(), at(late, 50), at(late, 99), at(late, 100), idle / 1e6, at(took, 50), at(took, 99), at(took, 100));
        }

        private static double at(LongArrayList values, int percent) {
            if (values.isEmpty()) return 0;
            long[] sorted = values.toLongArray();
            java.util.Arrays.sort(sorted);
            return sorted[Math.min(sorted.length - 1, sorted.length * percent / 100)] / 1e6;
        }
    }

    private static boolean inView(long key, int bx, int bz, double lookX, double lookZ) {
        int width = Far.width(Far.level(key));
        double cx = (Far.x(key) + 0.5) * width - bx, cz = (Far.z(key) + 0.5) * width - bz, length = Math.sqrt(cx * cx + cz * cz);
        return length < width || cx * lookX + cz * lookZ >= 0.5 * length;
    }

    /** As FarRenderer.priority orders a client's requests. */
    private static long priority(long key, int bx, int bz, double lookX, double lookZ, Map<Long, FarColumn> held) {
        int level = Far.level(key), width = Far.width(level);
        long x0 = (long) Far.x(key) * width, z0 = (long) Far.z(key) * width;
        long dx = Math.max(0, Math.max(x0 - bx, bx - (x0 + width))), dz = Math.max(0, Math.max(z0 - bz, bz - (z0 + width)));
        double behind = ViewOrder.later(x0 + width / 2.0 - bx, z0 + width / 2.0 - bz, lookX, lookZ, width);
        double later = level == 7 ? 1.5 : level == 8 ? 2.0 : 1.0;
        long order = (long) (Math.max(dx, dz) * 100 * behind * later) / width * 8 + level;
        int x = Far.x(key), z = Far.z(key);
        if (!Far.refined(level, x, z, bx, bz)) return order;
        int rings = Math.max(Math.abs(x - Far.column(bx, level)), Math.abs(z - Far.column(bz, level)));
        if (rings >= Far.hole(level) - 1) return order;
        return level <= 6 && !childrenReady(level, x, z, bx, bz, held) ? order : order + (1L << 32);
    }

    private static boolean childrenReady(int level, int x, int z, int bx, int bz, Map<Long, FarColumn> held) {
        for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
            int kx = 2 * x + dx, kz = 2 * z + dz;
            if (held.containsKey(Far.key(level - 1, kx, kz))) continue;
            if (!(Far.refined(level - 1, kx, kz, bx, bz) && childrenReady(level - 1, kx, kz, bx, bz, held))) return false;
        }
        return true;
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
}
