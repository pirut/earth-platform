package io.earthplatform.geography.variety;

import io.earthplatform.core.*;
import io.earthplatform.geography.*;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.Locale;

/** Bounded offline CPU/allocation probe; not a native client FPS benchmark or cold OS-cache claim. */
public final class TerrainVarietyBenchmark {
    private TerrainVarietyBenchmark() {}
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args.length==0?"geography":args[0]);
        long start=System.nanoTime();
        var base=OfflineTerrain.load(root.resolve("data/copernicus-everest-proof/world.properties"));
        double setup=(System.nanoTime()-start)/1e6;
        var off=new VariedOfflineTerrain(base,MicrodetailProfile.disabled(),MaterialPalette.DEFAULT);
        var on=new VariedOfflineTerrain(base,MicrodetailProfile.everestExample(81),MaterialPalette.DEFAULT);
        int cubes=1024; long sink=0;
        for(int i=0;i<256;i++)sink+=on.cube(position(i),new CancellationToken()).get(0);
        var mx=ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean allocated=mx instanceof com.sun.management.ThreadMXBean m&&m.isThreadAllocatedMemorySupported()?m:null;
        if(allocated!=null&&!allocated.isThreadAllocatedMemoryEnabled())allocated.setThreadAllocatedMemoryEnabled(true);
        long tid=Thread.currentThread().threadId();
        long before=allocated==null?0:allocated.getThreadAllocatedBytes(tid);
        start=System.nanoTime();
        for(int i=0;i<cubes;i++)sink+=on.cube(position(i),new CancellationToken()).get(0);
        double enabledMs=(System.nanoTime()-start)/1e6;
        long bytes=allocated==null?-1:allocated.getThreadAllocatedBytes(tid)-before;
        start=System.nanoTime();
        for(int i=0;i<cubes;i++)sink+=off.cube(position(i),new CancellationToken()).get(0);
        double disabledMs=(System.nanoTime()-start)/1e6;
        double max=0,abs=0,square=0;int changed=0;
        for(int x=600;x<856;x++)for(int z=1100;z<1356;z++) {
            var sample=on.sample(x,z); double d=sample.proceduralDeltaMetres();max=Math.max(max,Math.abs(d));abs+=Math.abs(d);square+=d*d;
            if(StrictMath.floor(sample.sourceMetres())!=StrictMath.floor(sample.generatedMetres()))changed++;
        }
        long packBytes=base.manifest().tiles().stream().mapToLong(TileSpec::encodedBytes).sum();
        System.out.printf(Locale.ROOT,"""
            {
              "description": "Local accepted pack only; no network, no OS-cache flush, no Minecraft FPS claim",
              "packFingerprint": "%s",
              "profileFingerprint": "%s",
              "generatedDatasetFingerprint": "%s",
              "setupLocalReadMs": %.6f,
              "encodedPackBytes": %d,
              "cubes": %d,
              "enabledCubeTotalMs": %.6f,
              "disabledCubeTotalMs": %.6f,
              "enabledCurrentThreadAllocatedBytesPerCube": %.3f,
              "allocationMeaning": "Cumulative allocation churn including result cubes; not peak retained heap",
              "fixturePersistentQueryCacheEntries": 0,
              "networkRequests": 0,
              "comparisonColumns": 65536,
              "maximumAbsoluteDetailMetres": %.12f,
              "meanAbsoluteDetailMetres": %.12f,
              "rmsDetailMetres": %.12f,
              "changedSurfaceBlockColumns": %d,
              "sink": %d
            }
            """,base.manifest().fingerprint(),on.profile().fingerprint(),on.worldIdentity("geography-palette-v1").datasetVersion(),
            setup,packBytes,cubes,enabledMs,disabledMs,(double)bytes/cubes,max,abs/65536,StrictMath.sqrt(square/65536),changed,sink);
    }
    private static CubePos position(int i) { return CubePos.fromBlock(608+(i%16)*16,7824+((i/256)%4)*16,1104+((i/16)%16)*16); }
}
