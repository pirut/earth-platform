package io.earthplatform.minecraft.lod;

import java.util.List;
import java.util.Map;

/** Pure whole-transaction preflight, including current/private/failed-retirement physical owners. */
final class DhReplacementGpuBudget {
    static final long MAX_BYTES=8L*1024*1024;
    record Union(int owners,long bytes,int changedOwners) {}
    static <K,F> Union assess(Map<K,List<F>> current,Map<K,List<F>> candidate,int residentOwners,long residentBytes){
        return assessInternal(current,candidate,residentOwners,residentBytes,64,false);
    }
    static <K,F> Union assessDistance(Map<K,List<F>> current,Map<K,List<F>> candidate,int residentOwners,long residentBytes,int maxCandidateOwners){
        return assessInternal(current,candidate,residentOwners,residentBytes,maxCandidateOwners,true);
    }
    private static <K,F> Union assessInternal(Map<K,List<F>> current,Map<K,List<F>> candidate,int residentOwners,long residentBytes,int maxCandidateOwners,boolean rounded){
        if(maxCandidateOwners<1||maxCandidateOwners>256)throw new IllegalArgumentException("Explicit bounded native-distance owner domain required");
        if(residentOwners<0||residentBytes<0||candidate.size()>maxCandidateOwners)throw new IllegalArgumentException("Invalid pooled ownership inputs");
        int owners=residentOwners,changed=0;long bytes=residentBytes;
        for(var entry:candidate.entrySet()){
            if(entry.getValue().size()>4096)throw new IllegalArgumentException("Per-owner face cap");
            if(!entry.getValue().equals(current.get(entry.getKey()))){
                owners=Math.addExact(owners,1);changed++;bytes=Math.addExact(bytes,rounded?DhNativeDistanceBudget.storage(entry.getValue().size()).chargedBytes():entry.getValue().size()*88L);
            }
        }
        return new Union(owners,bytes,changed);
    }
    static boolean fits(Union union){return union.owners()<=128&&union.bytes()<=MAX_BYTES;}
    private DhReplacementGpuBudget(){}
}
