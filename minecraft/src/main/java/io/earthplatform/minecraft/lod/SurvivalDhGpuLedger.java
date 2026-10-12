package io.earthplatform.minecraft.lod;

import java.util.IdentityHashMap;
import java.util.Objects;

/** One process ledger for every survival DH current/private/retiring/failed physical owner. */
public final class SurvivalDhGpuLedger {
    private static final SurvivalDhGpuLedger GLOBAL=new SurvivalDhGpuLedger();
    public static SurvivalDhGpuLedger global(){return GLOBAL;}
    // Package-only isolated lifecycle tests; production cannot create a parallel allowance.
    SurvivalDhGpuLedger(){}
    private final IdentityHashMap<Batch,Boolean> batches=new IdentityHashMap<>();
    private long bytes,peakBytes;
    private int owners,peakOwners;
    public record Evidence(long bytes,long peakBytes,int owners,int peakOwners,int generations){}
    public synchronized Evidence evidence(){return new Evidence(bytes,peakBytes,owners,peakOwners,batches.size());}
    synchronized Batch reserve(Object retainedOwner,int[] quads){
        Objects.requireNonNull(retainedOwner);Objects.requireNonNull(quads);
        if(quads.length<1||quads.length>DhNativeDistanceBudget.MAX_OWNERS)throw new IllegalArgumentException("Bounded complete DH candidate required");
        long total=0;for(int count:quads)total=Math.addExact(total,DhNativeDistanceBudget.storage(count).chargedBytes());
        if(quads.length>DhNativeDistanceBudget.MAX_RESIDENT_OWNERS-owners||total>DhNativeDistanceBudget.MAX_GPU_BYTES-bytes)
            throw new IllegalStateException("Complete survival DH candidate exceeds shared resident allowance");
        Batch batch=new Batch(retainedOwner,quads); // Caller precharges owner metadata before arrays/ledger insertion.
        batches.put(batch,Boolean.TRUE);bytes+=total;owners+=quads.length;
        peakBytes=Math.max(peakBytes,bytes);peakOwners=Math.max(peakOwners,owners);return batch;
    }
    final class Batch {
        // Strongly retain the physical wrapper, CPU owners and tombstones until every piece drains.
        private Object retainedOwner;
        private final long[] charges;
        private int remaining;
        Batch(Object retainedOwner,int[] quads){
            this.retainedOwner=retainedOwner;charges=new long[quads.length];remaining=quads.length;
            for(int i=0;i<quads.length;i++)charges[i]=DhNativeDistanceBudget.storage(quads[i]).chargedBytes();
        }
        long chargedBytes(){synchronized(SurvivalDhGpuLedger.this){long result=0;for(long charge:charges)result+=charge;return result;}}
        void releaseAfterPhysicalClose(int piece){synchronized(SurvivalDhGpuLedger.this){
            long charge=charges[piece];if(charge==0)return;
            if(!batches.containsKey(this)||bytes<charge||owners<1)throw new IllegalStateException("DH ledger underflow");
            charges[piece]=0;bytes-=charge;owners--;
            if(--remaining==0){batches.remove(this);retainedOwner=null;}
        }}
    }
}
