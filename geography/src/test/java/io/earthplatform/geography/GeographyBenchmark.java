package io.earthplatform.geography;

import io.earthplatform.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Reproducible bounded offline benchmark; reports actual elapsed time rather than promising game FPS. */
public final class GeographyBenchmark {
    public static void main(String[] args) throws Exception {
        Path path=Path.of(args.length==0?"geography/data/noaa-etopo2022-proof/world.properties":args[0]);
        WorldManifest manifest=WorldManifest.load(path);
        Path cache=Files.createTempDirectory("earth-geography-benchmark-");
        try(var io=Executors.newFixedThreadPool(2)) {
            var store=new CachedTileStore(path.toAbsolutePath().getParent(),cache,io,4*1024*1024,8*1024*1024,4,false);
            var elevation=new RasterElevationProvider(manifest,store);
            var provider=new GeographyCubeProvider(manifest.projection(),elevation,MaterialPalette.DEFAULT);
            long t=System.nanoTime();provider.load(new CubePos(0,0,0),new CancellationToken()).join();long cold=System.nanoTime()-t;
            long reads=store.stats().sourceReads();
            // 4x4 horizontal columns, 128 independently generated vertical cubes in each; 2,048 cubes.
            t=System.nanoTime();long uniforms=0;
            for(int x=0;x<4;x++)for(int z=0;z<4;z++)for(int y=-64;y<64;y++)if(provider.load(new CubePos(x,y,z),new CancellationToken()).join().isUniform())uniforms++;
            long warm=System.nanoTime()-t;
            if(store.stats().sourceReads()!=reads)throw new AssertionError("Warm sampling unexpectedly reread source");
            var second=new CachedTileStore(path.toAbsolutePath().getParent(),cache,io,0,8*1024*1024,4,false);
            t=System.nanoTime();second.load(manifest.tiles().getFirst()).join();long disk=System.nanoTime()-t;
            if(second.stats().diskHits()!=1||second.stats().sourceReads()!=0)throw new AssertionError("Persistent cache was not reused");
            t=System.nanoTime();OfflineTerrain offline=OfflineTerrain.load(path);long preload=System.nanoTime()-t;
            t=System.nanoTime();for(int i=0;i<10000;i++)offline.elevationMetres(i%64,i%73);long samples=System.nanoTime()-t;
            System.out.printf(Locale.ROOT,"{\"dataset\":\"%s\",\"networkEnabled\":false,\"coldCacheScope\":\"empty application tile cache; OS page cache not controlled\",\"coldFirstCubeMs\":%.3f,\"warm2048CubesMs\":%.3f,\"uniformCubes\":%d,\"sourceTileReads\":%d,\"warmSourceTileReads\":0,\"persistentCacheReadMs\":%.3f,\"persistentDiskHits\":%d,\"offlinePackPreloadMs\":%.3f,\"offline10000SamplesMs\":%.3f,\"columnHeightBatches\":%d,\"columnCacheBytes\":%d}%n",manifest.fingerprint(),cold/1e6,warm/1e6,uniforms,reads,disk/1e6,second.stats().diskHits(),preload/1e6,samples/1e6,provider.columnStats().elevationBatches(),provider.columnStats().payloadBytes());
        } finally {try(var files=Files.walk(cache)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
