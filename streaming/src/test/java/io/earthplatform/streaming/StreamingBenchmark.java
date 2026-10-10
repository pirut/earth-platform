package io.earthplatform.streaming;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.streaming.CubeStreamAdmission.Admission;
import io.earthplatform.streaming.CubeStreamAdmission.Header;
import io.earthplatform.streaming.CubeStreamAdmission.Rejection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Synthetic metadata benchmark, not an engine/network/heap/FPS benchmark or rigorous JMH run. */
public final class StreamingBenchmark {
    private static final WorldIdentity WORLD = new WorldIdentity(42, "earth", "1", "terrain-1", "blocks-1");
    private static volatile long blackhole;
    private static final Runnable INSTALLER = () -> { }; // Intentionally excludes any actual installation work.

    private StreamingBenchmark() { }

    public static void main(String[] arguments) {
        System.out.println("Synthetic metadata-only benchmark: " + Instant.now());
        System.out.println("JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.runtime.version"));
        System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("Five in-process measured samples after one warmup; GC, scheduling and frequency are uncontrolled.");
        measure("admit + commit, 1024 leases, new header/token, empty installer", 100_000, 500_000,
                StreamingBenchmark::admitCommit);
        measure("reject retired lease, 1024 leases, prebuilt header", 100_000, 1_000_000,
                StreamingBenchmark::reject);
        measure("replace disjoint 256-lease interest, including returned metadata", 500, 2_000,
                StreamingBenchmark::replace);
        System.out.println("No wire parsing, payloads, Minecraft objects, renderer, transport, game tick or native allocation measured.");
        System.out.println("Checksum: " + blackhole);
    }

    private static long admitCommit(int count) {
        List<CubePos> positions = positions(1024, 0);
        CubeStreamAdmission gate = new CubeStreamAdmission(WORLD, 1, positions.size());
        gate.replaceInterest(positions);
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            int index = i & 1023;
            Admission admitted = gate.admit(new Header(WORLD, 1, positions.get(index), index + 1L, i));
            if (!admitted.accepted() || gate.commit(admitted.token(), INSTALLER) != Rejection.NONE) {
                throw new AssertionError("benchmark unexpectedly rejected valid snapshot");
            }
        }
        long elapsed = System.nanoTime() - start;
        blackhole ^= gate.view().metrics().commits();
        gate.close();
        return elapsed;
    }

    private static long reject(int count) {
        List<CubePos> positions = positions(1024, 0);
        CubeStreamAdmission gate = new CubeStreamAdmission(WORLD, 1, positions.size());
        gate.replaceInterest(positions);
        Header[] headers = new Header[1024];
        for (int i = 0; i < headers.length; i++) headers[i] = new Header(WORLD, 1, positions.get(i), Long.MAX_VALUE, 1);
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            if (gate.admit(headers[i & 1023]).rejection() != Rejection.RETIRED_LEASE) {
                throw new AssertionError("benchmark unexpectedly admitted stale lease");
            }
        }
        long elapsed = System.nanoTime() - start;
        blackhole ^= gate.view().metrics().admissionRejections();
        gate.close();
        return elapsed;
    }

    private static long replace(int count) {
        CubeStreamAdmission gate = new CubeStreamAdmission(WORLD, 1, 256);
        List<CubePos> first = positions(256, 0), second = positions(256, -100_000);
        gate.replaceInterest(second);
        long total = 0;
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            var changed = gate.replaceInterest((i & 1) == 0 ? first : second);
            total += changed.requested().size() + changed.retired().size();
        }
        long elapsed = System.nanoTime() - start;
        blackhole ^= total;
        gate.close();
        return elapsed;
    }

    private static List<CubePos> positions(int count, int offset) {
        List<CubePos> positions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) positions.add(new CubePos(offset + i, (i & 1) == 0 ? 553 : -688, -i));
        return List.copyOf(positions);
    }
    private static void measure(String label, int warmup, int count, Sample sample) {
        sample.run(warmup);
        double[] nanos = new double[5];
        for (int i = 0; i < nanos.length; i++) nanos[i] = (double) sample.run(count) / count;
        Arrays.sort(nanos);
        System.out.printf(Locale.ROOT, "%s: median %.1f ns/op; range %.1f..%.1f; %d operations/sample%n",
                label, nanos[2], nanos[0], nanos[4], count);
    }
    @FunctionalInterface private interface Sample { long run(int count); }
}
