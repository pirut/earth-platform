package io.earthplatform.geography.composition;

import io.earthplatform.core.CancellationToken;
import io.earthplatform.geography.*;
import io.earthplatform.geography.ElevationProvider.ElevationSample;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Measured wrapper query/allocation behavior using explicit synthetic in-memory providers, not network or game FPS. */
public final class CompositionBenchmark {
    public static void main(String[] args)throws Exception {
        var high=SourceCompositionTest.source("high",1,0,SourceCompositionTest.PATCH,10,new AtomicInteger());
        var low=SourceCompositionTest.source("low",15,0,SourceCompositionTest.WORLD,1000,new AtomicInteger());
        var plan=SourceCompositionTest.plan(low,high);var provider=new CompositeElevationProvider(plan,256,4);
        List<GeoPoint> points=new ArrayList<>();for(int i=0;i<256;i++)points.add(i%2==0?SourceCompositionTest.HIGH:SourceCompositionTest.LOW);
        for(int i=0;i<100;i++)provider.sample(points,new CancellationToken()).join();
        var bean=ManagementFactory.getThreadMXBean();com.sun.management.ThreadMXBean allocations=bean instanceof com.sun.management.ThreadMXBean b?b:null;
        long allocatedBefore=allocations!=null&&allocations.isThreadAllocatedMemorySupported()?allocations.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1;
        long callsBefore=provider.stats().sourceCalls(),start=System.nanoTime();
        for(int i=0;i<1000;i++)provider.sample(points,new CancellationToken()).join();
        long elapsed=System.nanoTime()-start;
        long allocatedAfter=allocatedBefore<0?-1:allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long calls=provider.stats().sourceCalls()-callsBefore;if(calls!=2000)throw new AssertionError("Expected two grouped source calls per256-point batch");
        var highWait=new CompletableFuture<List<ElevationSample>>();var lowWait=new CompletableFuture<List<ElevationSample>>();
        var burst=new CompositeElevationProvider(SourceCompositionTest.plan(SourceCompositionTest.withProvider(high,(p,t)->highWait),SourceCompositionTest.withProvider(low,(p,t)->lowWait)),256,4);
        var active=new ArrayList<CompletableFuture<List<ElevationSample>>>();for(int i=0;i<4;i++)active.add(burst.sample(points,new CancellationToken()));
        for(int i=0;i<36;i++){var rejected=burst.sample(points,new CancellationToken());if(!rejected.isCompletedExceptionally())throw new AssertionError("Admission exceeded limit");}
        var peak=burst.stats();
        highWait.complete(Collections.nCopies(128,new ElevationSample(10,high.provenance())));lowWait.complete(Collections.nCopies(128,new ElevationSample(1000,low.provenance())));
        CompletableFuture.allOf(active.toArray(CompletableFuture[]::new)).join();
        String json=String.format(Locale.ROOT,"{\"scope\":\"synthetic synchronous providers; wrapper plus fixture allocations\",\"networkCalls\":0,\"measuredBatches\":1000,\"pointsPerBatch\":256,\"elapsedMs\":%.3f,\"groupedSourceCalls\":%d,\"currentThreadAllocatedBytes\":%d,\"allocatedBytesPerBatch\":%.1f,\"burstRequests\":40,\"burstAdmitted\":%d,\"burstRejected\":%d,\"peakPlannedInputPoints\":%d,\"burstSourceCalls\":%d,\"activeAfterDrain\":%d,\"retainedPointCacheEntries\":0,\"heapPeakMeasured\":false}%n",elapsed/1e6,calls,allocatedBefore<0?-1:allocatedAfter-allocatedBefore,allocatedBefore<0?-1:(allocatedAfter-allocatedBefore)/1000.0,peak.activeBatches(),peak.rejectedBatches(),peak.peakPlannedPointReferences(),peak.sourceCalls(),burst.stats().activeBatches());
        Path output=Path.of(args.length==0?"geography/composition-results":args[0]);Files.createDirectories(output);Files.writeString(output.resolve("benchmark.json"),json);System.out.print(json);
    }
}
