package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.lang.management.ManagementFactory;
import java.util.*;
import io.earthplatform.minecraft.cubic.CubeCodec;
import org.junit.jupiter.api.Test;

/** Optional single-thread synthetic component measurements; no FPS, geodata or streaming claim. */
class FarSurfacePacketBench {
    private static volatile Object sink;
    @Test void bench() throws Exception {
        assumeTrue("1".equals(System.getenv("EARTH_SURFACE_BENCH")));
        var g = new SurfacePacketFixtures.Country(0); var ids = SurfacePacketFixtures.biomes().asHolderIdMap();
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (bean.isThreadAllocatedMemorySupported()) bean.setThreadAllocatedMemoryEnabled(true);
        long tid = Thread.currentThread().threadId();
        // First-touch columns are separately disclosed, not presented as independent fresh JVMs.
        for (int level : new int[] {3, 5, 8}) {
            long start = System.nanoTime(); FarColumn dense = g.column(level, 0, 0, false); long denseCold = System.nanoTime() - start;
            start = System.nanoTime(); FarSurfacePacket packet = g.surfacePacket(level, 0, 0); long packetCold = System.nanoTime() - start;
            assertNotNull(packet);
            int legacyBytes = 0; for (var section : dense.sections) if (section != null) legacyBytes += CubeCodec.sectionBytes(section).length;
            byte[] encoded = packet.encode(ids);
            System.out.printf(Locale.ROOT, "SURFACE_COMPONENT first level=%d denseMs=%.4f directRunMs=%.4f legacyBytes=%d runBytes=%d rows=%d runs=%d%n", level, denseCold/1e6, packetCold/1e6, legacyBytes, encoded.length, packet.height(), packet.runCount());
            for (int warm = 0; warm < 100; warm++) { sink = g.column(level, warm%7, -warm%7, false); sink = g.surfacePacket(level, warm%7, -warm%7); }
            for (int round = 0; round < 4; round++) for (boolean runs : round%2==0 ? new boolean[]{false,true} : new boolean[]{true,false}) {
                long allocated = bean.getThreadAllocatedBytes(tid); start = System.nanoTime();
                int n = 160;
                for (int i = 0; i < n; i++) sink = runs ? g.surfacePacket(level, i%7, -i%7) : g.column(level, i%7, -i%7, false);
                long elapsed = System.nanoTime() - start, bytes = bean.getThreadAllocatedBytes(tid)-allocated;
                System.out.printf(Locale.ROOT, "SURFACE_COMPONENT warm level=%d round=%d directRuns=%s usPerColumn=%.3f allocatedBytesPerColumn=%.1f%n", level, round, runs, elapsed/1e3/n, bytes/(double)n);
            }
            for (String phase : new String[]{"runEncode","runDecode","materialize","editSnapshot"}) {
                long allocated = bean.getThreadAllocatedBytes(tid); start = System.nanoTime(); int n=160;
                for (int i=0;i<n;i++) sink=switch(phase) {
                    case "runEncode" -> packet.encode(ids);
                    case "runDecode" -> FarSurfacePacket.decode(encoded,ids);
                    case "materialize" -> packet.materialize(ids);
                    default -> packet.withEdits(0,i+1,List.of(new FarSurfacePacket.Edit(1,1,1,net.minecraft.world.level.block.Blocks.CAVE_AIR.defaultBlockState())));
                };
                long elapsed=System.nanoTime()-start,bytes=bean.getThreadAllocatedBytes(tid)-allocated;
                System.out.printf(Locale.ROOT,"SURFACE_COMPONENT operation level=%d phase=%s usPerColumn=%.3f allocatedBytesPerColumn=%.1f%n",level,phase,elapsed/1e3/n,bytes/(double)n);
            }
        }
    }
}
