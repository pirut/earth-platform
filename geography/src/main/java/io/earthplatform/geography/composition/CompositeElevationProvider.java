package io.earthplatform.geography.composition;

import io.earthplatform.core.CancellationToken;
import io.earthplatform.geography.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded batch planner. At most one provider call per selected source per batch; no point cache and no per-block network. */
public final class CompositeElevationProvider implements ElevationProvider {
    private final CompositionPlan plan;
    private final int maxBatchPoints,maxActiveBatches;
    private final AtomicInteger active=new AtomicInteger(),peakActive=new AtomicInteger();
    private final AtomicLong sourceCalls=new AtomicLong(),completed=new AtomicLong(),failed=new AtomicLong(),rejected=new AtomicLong(),peakPlannedPoints=new AtomicLong(),activePlannedPoints=new AtomicLong();
    public CompositeElevationProvider(CompositionPlan plan,int maxBatchPoints,int maxActiveBatches) {
        this.plan=Objects.requireNonNull(plan);
        if(maxBatchPoints<1||maxBatchPoints>65536||maxActiveBatches<1||maxActiveBatches>64)throw new IllegalArgumentException("Invalid composition admission bounds");
        this.maxBatchPoints=maxBatchPoints;this.maxActiveBatches=maxActiveBatches;
    }
    public CompositeElevationProvider(CompositionPlan plan){this(plan,4096,4);}
    public CompositionPlan plan(){return plan;}
    @Override public CompletableFuture<List<ElevationSample>> sample(List<GeoPoint> points,CancellationToken cancellation) {
        Objects.requireNonNull(points);Objects.requireNonNull(cancellation);
        if(points.size()>maxBatchPoints){rejected.incrementAndGet();return CompletableFuture.failedFuture(new RejectedExecutionException("Composition batch point limit exceeded"));}
        try{cancellation.throwIfCancelled();}catch(RuntimeException e){return CompletableFuture.failedFuture(e);}
        if(points.isEmpty())return CompletableFuture.completedFuture(List.of());
        int running;
        do {running=active.get();if(running>=maxActiveBatches){rejected.incrementAndGet();return CompletableFuture.failedFuture(new RejectedExecutionException("Composition active batch limit reached"));}}
        while(!active.compareAndSet(running,running+1));
        peakActive.accumulateAndGet(running+1,Math::max);
        List<GeoPoint> positions;
        List<Group> groups;
        try {
            var snapshot=new ArrayList<GeoPoint>(Math.min(points.size(),maxBatchPoints));
            for(GeoPoint point:points){if(snapshot.size()>=maxBatchPoints)throw new RejectedExecutionException("Composition batch changed beyond its limit");snapshot.add(Objects.requireNonNull(point));}
            positions=List.copyOf(snapshot);
            var selected=new LinkedHashMap<String,Group>();
            for(int i=0;i<positions.size();i++) {
                cancellation.throwIfCancelled();SourceBinding source=plan.select(positions.get(i));
                Group group=selected.computeIfAbsent(source.id(),id->new Group(source));group.points.add(positions.get(i));group.indices.add(i);
            }
            groups=List.copyOf(selected.values());
            peakPlannedPoints.accumulateAndGet(activePlannedPoints.addAndGet(positions.size()),Math::max);
        }catch(Throwable e){active.decrementAndGet();failed.incrementAndGet();return CompletableFuture.failedFuture(e);}
        // Resolve every point before dispatch; each scheduled source still participates in the final barrier if a later call throws.
        for(Group group:groups) {
            try {cancellation.throwIfCancelled();sourceCalls.incrementAndGet();group.future=Objects.requireNonNull(group.source.provider().sample(List.copyOf(group.points),cancellation),"Provider returned null future");}
            catch(Throwable e){group.future=CompletableFuture.failedFuture(e);}
        }
        CompletableFuture<List<ElevationSample>> privateWork=CompletableFuture.allOf(groups.stream().map(g->g.future).toArray(CompletableFuture[]::new)).thenApply(ignored->{
            cancellation.throwIfCancelled();ElevationSample[] result=new ElevationSample[positions.size()];
            for(Group group:groups) {
                List<ElevationSample> samples=Objects.requireNonNull(group.future.join(),"Provider returned null list");
                if(samples.size()!=group.indices.size())throw new IllegalStateException("Wrong source sample count for "+group.source.id());
                for(int i=0;i<samples.size();i++) {
                    cancellation.throwIfCancelled();ElevationSample sample=Objects.requireNonNull(samples.get(i),"Null source sample");
                    if(!sample.provenance().equals(group.source.provenance()))throw new IllegalStateException("Unexpected source provenance for "+group.source.id());
                    result[group.indices.get(i)]=sample;
                }
            }
            return List.of(result);
        });
        CompletableFuture<List<ElevationSample>> settlement=privateWork.whenComplete((ignored,error)->{activePlannedPoints.addAndGet(-positions.size());active.decrementAndGet();if(error==null)completed.incrementAndGet();else failed.incrementAndGet();});
        // Cancellation of this dependent view cannot release admission or cancel shared source work prematurely.
        return settlement.thenApply(result->result);
    }
    public Stats stats(){return new Stats(active.get(),peakActive.get(),sourceCalls.get(),completed.get(),failed.get(),rejected.get(),peakPlannedPoints.get(),maxBatchPoints,maxActiveBatches);}
    public record Stats(int activeBatches,int peakActiveBatches,long sourceCalls,long completedBatches,long failedBatches,long rejectedBatches,long peakPlannedPointReferences,int maxBatchPoints,int maxActiveBatches) {}
    private static final class Group {
        final SourceBinding source;final List<GeoPoint> points=new ArrayList<>();final List<Integer> indices=new ArrayList<>();CompletableFuture<List<ElevationSample>> future;
        Group(SourceBinding source){this.source=source;}
    }
}
