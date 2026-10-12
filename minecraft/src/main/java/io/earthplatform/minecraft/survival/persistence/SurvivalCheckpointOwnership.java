package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;

/** Logical ownership inside the existing installed12MiB CAPSULE reservation, not JVM heap size.
 * The on-disk4MiB limit remains independent. This profile can refuse an image below that limit
 * when simultaneous immutable graphs, codecs, Image clones and writer prior-read would not fit.
 */
final class SurvivalCheckpointOwnership {
    static final long CAPACITY=12L<<20;
    static final long CODEC_SCRATCH=2L<<20; // <=64 named states, per-cube maps,1MiB NBT accounter, stack codec and manifests.
    private final long graphs,externalGraphs,maximumEncoded,writerPeak;
    private boolean acknowledged,retired,writerRetired;
    SurvivalCheckpointOwnership(long previousGraph,long nextGraph,long priorEncodedBound,int formatBound){
        this(previousGraph,nextGraph,priorEncodedBound,formatBound,0);
    }
    SurvivalCheckpointOwnership(long previousGraph,long nextGraph,long priorEncodedBound,int formatBound,long pinnedCubes){
        this(previousGraph,nextGraph,priorEncodedBound,formatBound,pinnedCubes,0);
    }
    SurvivalCheckpointOwnership(long previousGraph,long nextGraph,long priorEncodedBound,int formatBound,long pinnedCubes,long externalGraphs){
        if(previousGraph<0||nextGraph<1||priorEncodedBound<0||formatBound<1||pinnedCubes<0||externalGraphs<0)
            throw new SurvivalRefusal("Invalid retained checkpoint ownership");
        graphs=Math.addExact(Math.addExact(previousGraph,nextGraph),pinnedCubes);
        this.externalGraphs=externalGraphs;
        long available=CAPACITY-graphs-externalGraphs-CODEC_SCRATCH;
        // ByteArrayOutputStream backing <=2N; toByteArray N; Image constructor clone N.
        // Store commit/retry separately owns current Image N and a revalidated prior Image.
        maximumEncoded=Math.min(formatBound,Math.min(available/4,available-2*priorEncodedBound));
        writerPeak=Math.max(4*maximumEncoded,maximumEncoded+2*priorEncodedBound);
        if(maximumEncoded<1)throw new SurvivalRefusal("Checkpoint old/new/writer graphs exhaust existing12MiB reservation");
    }
    int maximumEncoded(){if(retired)throw new SurvivalRefusal("Retired checkpoint image lease");return Math.toIntExact(maximumEncoded);}
    long charged(){return retired?0:writerRetired?graphs:Math.addExact(externalGraphs,Math.addExact(graphs,Math.addExact(CODEC_SCRATCH,writerPeak)));}
    /** Exact drained transition writer leaves its old/frozen graph lease alive. */
    void retireDrainedWriter(){requireRetirable();writerRetired=true;}
    void acknowledge(){if(retired||acknowledged)throw new SurvivalRefusal("Checkpoint ownership ack replay");acknowledged=true;}
    void requireRetirable(){if(retired||!acknowledged)throw new SurvivalRefusal("Unacknowledged checkpoint ownership cannot retire");}
    void retire(){retired=true;}
}
