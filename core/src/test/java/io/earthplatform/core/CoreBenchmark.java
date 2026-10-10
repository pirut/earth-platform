package io.earthplatform.core;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Synthetic core microbenchmarks; not Minecraft tick/FPS/network or real-geography performance. */
public final class CoreBenchmark {
    private static volatile long blackhole;
    private CoreBenchmark() {}
    public static void main(String[] args) throws Exception {
        int coordinates = 2_000_000, moves = 2_000, requests = 20_000, saves = 200;
        System.out.println("CORE SYNTHETIC BENCHMARK " + Instant.now());
        System.out.println("java=" + System.getProperty("java.version") + " os=" + System.getProperty("os.name")
                + " arch=" + System.getProperty("os.arch") + " jvm_visible_processors=" + Runtime.getRuntime().availableProcessors());
        coordinates(200_000); long start = System.nanoTime(); coordinates(coordinates); report("coordinate_roundtrips", coordinates, start);
        CubeTickets tickets = new CubeTickets(150, 2); int peak = 0;
        tickets.update("summit", new CubePos(0, 553, 0), 2, 1); tickets.update("trench", new CubePos(0, -688, 0), 2, 1);
        start = System.nanoTime();
        for (int i = 0; i < moves; i++) {
            tickets.update("summit", new CubePos(i, 553, 0), 2, 1);
            tickets.update("trench", new CubePos(-i, -688, 0), 2, 1); peak = Math.max(peak, tickets.size());
        }
        report("two_player_ticket_updates", 2L * moves, start); System.out.println("peak_resident_cubes=" + peak + " hard_budget=150");
        System.out.println("uniform_payload_bytes_per_cube=" + ImmutableCube.uniform(1).payloadBytes()
                + " dense_int_payload_bytes_per_cube=16384 (payload only; excludes object/map/array headers)");
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try (BoundedCubeLoader loader = new BoundedCubeLoader((p, cancellation) -> {
            cancellation.throwIfCancelled(); return CompletableFuture.completedFuture(ImmutableCube.uniform(p.y() < 0 ? 2 : 0));
        }, workers, 4, 64, 4)) {
            start = System.nanoTime();
            for (int base = 0; base < requests; base += 64) {
                List<BoundedCubeLoader.Lease> leases = new ArrayList<>();
                for (int i = base; i < Math.min(base + 64, requests); i++) leases.add(loader.request(new CubePos(i, i % 2 == 0 ? 553 : -688, 0)));
                for (var lease : leases) { blackhole += lease.future().get(10, TimeUnit.SECONDS).get(0); lease.close(); }
            }
            report("synthetic_async_cube_requests", requests, start); System.out.println("loader_final=" + loader.stats());
        } finally { workers.shutdownNow(); }
        Path directory = Files.createTempDirectory("earth-core-benchmark-");
        WorldIdentity id = new WorldIdentity(1, "benchmark", "1", "synthetic", "palette-v1");
        try (FileEditStore store = new FileEditStore(directory, id)) {
            EditSnapshot edits = EditSnapshot.empty(new CubePos(0, -688, 0)); start = System.nanoTime();
            for (int i = 0; i < saves; i++) { edits = edits.withBlock(i, i + 1); store.save(edits); }
            report("durable_overlay_saves_with_backup", saves, start);
            System.out.println("final_overlay_edits=" + edits.blocks().size() + " primary_file_bytes=" + Files.size(store.primaryPath(edits.position())));
            if (!store.load(edits.position()).snapshot().equals(edits)) throw new AssertionError("Benchmark persistence mismatch");
        }
        System.out.println("blackhole=" + blackhole);
        System.out.println("LIMITS: one uncalibrated run on shared Linux/cloud temporary storage; no JMH, confidence intervals, physical power-loss test, Minecraft engine, lighting, client, rendering, networking, or live Earth fetch.");
    }
    private static void coordinates(int count) {
        long sum = 0;
        for (int i = 0; i < count; i++) {
            long block = (long) i * 7919 - 8_000_000_000L;
            int section = CubePos.section(block), local = CubePos.local(block);
            if ((long) section * 16 + local != block) throw new AssertionError("Round-trip failed"); sum += section + local;
        }
        blackhole = sum;
    }
    private static void report(String name, long operations, long start) {
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf(Locale.ROOT, "%s operations=%d seconds=%.6f ops_per_second=%.0f%n", name, operations, seconds, operations / seconds);
    }
}
