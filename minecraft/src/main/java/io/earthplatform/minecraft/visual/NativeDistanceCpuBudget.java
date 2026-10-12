package io.earthplatform.minecraft.visual;

import java.util.EnumMap;
import java.util.Map;

/** Named031 accounted CPU reservations shared by every live/cancelled/cleanup-failed generation. */
public final class NativeDistanceCpuBudget {
    public static final String POLICY="native-setting-accounted-cpu-384mib-v1";
    public static final long MAX_BYTES=384L<<20;
    public enum Pool {
        SOURCE(96),COVERAGE(64),CAPSULE(16),PLAN(16),WORKER_UPLOAD(16),DH_RESULTS(40),SOURCE_ASSETS(64),OWNER_METADATA(72);
        final long limit;Pool(long mib){limit=mib<<20;}public long limit(){return limit;}
    }
    private static final NativeDistanceCpuBudget GLOBAL=new NativeDistanceCpuBudget(MAX_BYTES);
    public static NativeDistanceCpuBudget global(){return GLOBAL;}
    private final long limit;private final EnumMap<Pool,Long> used=new EnumMap<>(Pool.class);
    private long bytes,peakBytes,reservations;
    public record Evidence(long bytes,long peakBytes,long reservations,Map<Pool,Long> pools){}
    public NativeDistanceCpuBudget(long limit){if(limit<1||limit>MAX_BYTES)throw new IllegalArgumentException("Bounded CPU envelope required");this.limit=limit;for(var p:Pool.values())used.put(p,0L);}
    public synchronized Reservation reserve(Pool pool,long amount){
        if(pool==null||amount<=0)throw new IllegalArgumentException("Positive accounted CPU reservation required");
        long poolUsed=used.get(pool);if(amount>pool.limit-poolUsed||amount>limit-bytes)throw new IllegalStateException("Selected native distance exceeds "+POLICY+" pool="+pool+" requested="+amount);
        used.put(pool,poolUsed+amount);bytes+=amount;peakBytes=Math.max(peakBytes,bytes);reservations++;
        return new Reservation(pool,amount);
    }
    public synchronized Evidence evidence(){return new Evidence(bytes,peakBytes,reservations,Map.copyOf(used));}
    public final class Reservation {
        private final Pool pool;private final long amount;private boolean released;private Object retainedClaimant;
        private Reservation(Pool pool,long amount){this.pool=pool;this.amount=amount;}
        public long bytes(){return amount;}
        public Pool pool(){return pool;}
        public boolean ownedBy(NativeDistanceCpuBudget exact){return NativeDistanceCpuBudget.this==exact;}
        /** An unresolved retained graph cannot be spent twice or released by a generic drain. */
        public void claimRetainedOwner(Object exact){synchronized(NativeDistanceCpuBudget.this){
            if(exact==null||released||retainedClaimant!=null)throw new IllegalStateException("CPU reservation already claimed or released");
            retainedClaimant=exact;
        }}
        public void requireRetainedOwner(Object exact){synchronized(NativeDistanceCpuBudget.this){
            if(released||retainedClaimant!=exact||exact==null)throw new IllegalStateException("CPU retained reservation owner changed");
        }}
        public boolean released(){synchronized(NativeDistanceCpuBudget.this){return released;}}
        /** Call only after physical writers and every retained alias to these charged arrays have ended. */
        public void releaseAfterPhysicalDrain(){synchronized(NativeDistanceCpuBudget.this){if(released)return;if(retainedClaimant!=null)throw new IllegalStateException("Retained CPU owner has no qualified terminal release");long prior=used.get(pool);if(prior<amount||bytes<amount||reservations<1)throw new IllegalStateException("CPU owner accounting underflow");used.put(pool,prior-amount);bytes-=amount;reservations--;released=true;}}
    }
}
