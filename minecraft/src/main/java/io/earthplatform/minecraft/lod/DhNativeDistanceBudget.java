package io.earthplatform.minecraft.lod;

/** Explicit031 far envelope. Legacy views continue using their unchanged prototype budgets. */
public final class DhNativeDistanceBudget {
    public static final String POLICY="earth-selected-native-distance-dh-32mib-v1";
    public static final int MAX_CELLS=65_536,MAX_FACES=131_072,MAX_OWNERS=256,MAX_RESIDENT_OWNERS=512,MAX_PAGE_FACES=4096;
    public static final long MAX_RESULT_BYTES=20L<<20,MAX_CACHED_BYTES=0,MAX_CPU_RESERVATION_BYTES=40L<<20,MAX_GPU_BYTES=32L<<20;
    public static final int ALLOCATION_QUANTUM=65_536,OWNER_ALLOWANCE=4096;
    private DhNativeDistanceBudget(){}
    public record Storage(int vertexBytes,int indexBytes,long chargedBytes){}
    public static Storage storage(int quads){
        if(quads<1||quads>MAX_PAGE_FACES)throw new IllegalArgumentException("Bounded nonempty DH piece required");
        int v=round(Math.multiplyExact(quads,64)),i=round(Math.multiplyExact(quads,24));
        return new Storage(v,i,(long)v+i+OWNER_ALLOWANCE);
    }
    private static int round(int n){return Math.toIntExact(((long)n+ALLOCATION_QUANTUM-1)/ALLOCATION_QUANTUM*ALLOCATION_QUANTUM);}
    public static DhPreparedLodView.DistanceConfig configuration(){
        return new DhPreparedLodView.DistanceConfig(POLICY,new DhNativeDistanceTerrainData.Limits(MAX_CELLS,MAX_FACES,MAX_OWNERS,MAX_PAGE_FACES,MAX_RESULT_BYTES),MAX_GPU_BYTES,MAX_RESIDENT_OWNERS,MAX_CACHED_BYTES);
    }
}
