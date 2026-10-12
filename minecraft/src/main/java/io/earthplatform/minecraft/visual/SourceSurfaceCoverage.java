package io.earthplatform.minecraft.visual;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Read-only source clipping data. Only authenticated publication may authorize using it to replace DH. */
public final class SourceSurfaceCoverage {
    public enum Classification { FULL, BOUNDARY, UNCOVERED }
    public record SolidSpan(long minY,long maxYExclusive) {
        public SolidSpan { if(minY>=maxYExclusive)throw new IllegalArgumentException("Empty signed solid span"); }
        public boolean contains(long y){return y>=minY&&y<maxYExclusive;}
    }
    private final EarthDistanceSurfacePlan plan;
    private final Set<NativePageKey> eligiblePages;
    private final Map<NativePageKey,Long> eligibleMemberMasks;
    private final long coverageBytes;
    private final int width,depth,stride,pageWidth;
    private final long[][] pageYs,pageMemberMasks;
    private final long[] fullPrefix,anyPrefix;
    private final String identity;

    private SourceSurfaceCoverage(EarthDistanceSurfacePlan plan,Map<NativePageKey,Long> masks,boolean requireComplete,boolean exactMembers){
        this.plan=Objects.requireNonNull(plan);Objects.requireNonNull(masks);
        var canonical=new TreeMap<NativePageKey,Long>();
        for(var entry:masks.entrySet()){
            var key=Objects.requireNonNull(entry.getKey());long mask=Objects.requireNonNull(entry.getValue());
            var page=plan.pageIndex().get(key);
            if(page==null||mask==0||(exactMembers&&(mask&~page.memberMask())!=0))throw new EarthDistanceSurfacePlan.Refusal("Coverage member mask is absent, empty or outside exact source-face members");
            canonical.put(key,mask);
        }
        TreeSet<NativePageKey> selected=new TreeSet<>(canonical.keySet());
        if(!plan.pageIndex().keySet().containsAll(selected))throw new EarthDistanceSurfacePlan.Refusal("Coverage includes a page absent from its exact plan");
        if(requireComplete&&!selected.equals(plan.pageIndex().keySet()))throw new EarthDistanceSurfacePlan.Refusal("Complete coverage requires every planned nonempty signed page");
        this.eligiblePages=Collections.unmodifiableSet(selected);
        eligibleMemberMasks=Collections.unmodifiableMap(canonical);
        var b=plan.bounds();width=Math.toIntExact(b.width());depth=Math.toIntExact(b.depth());stride=Math.addExact(width,1);pageWidth=width/64;
        int slots=Math.multiplyExact(pageWidth,depth/64);
        coverageBytes=Math.addExact(plan.coverageBytes(),Math.addExact(Math.multiplyExact((long)slots,32),Math.multiplyExact((long)selected.size(),96)));
        if(coverageBytes>plan.limits().maxCoverageBytes())throw new EarthDistanceSurfacePlan.Refusal("Member coverage exceeds explicit allocation admission");
        pageYs=new long[slots][];pageMemberMasks=new long[slots][];int[] sizes=new int[slots];
        for(NativePageKey key:selected)sizes[pageIndex(key.minX(),key.minZ())]++;
        for(int i=0;i<slots;i++){pageYs[i]=new long[sizes[i]];pageMemberMasks[i]=new long[sizes[i]];}
        Arrays.fill(sizes,0);
        for(NativePageKey key:selected){int i=pageIndex(key.minX(),key.minZ());pageYs[i][sizes[i]++]=key.y();}
        for(long[] ys:pageYs)Arrays.sort(ys);
        for(NativePageKey key:selected){int i=pageIndex(key.minX(),key.minZ());pageMemberMasks[i][Arrays.binarySearch(pageYs[i],key.y())]=canonical.get(key);}
        int entries=Math.toIntExact(Math.multiplyExact((long)stride,(long)depth+1));fullPrefix=new long[entries];anyPrefix=new long[entries];
        FaceScan faces=new FaceScan();
        for(int iz=0;iz<depth;iz++)for(int ix=0;ix<width;ix++){
            long x=b.minX()+ix,z=b.minZ()+iz;int pi=pageIndex(x,z);long[] ys=pageYs[pi];
            if(ys.length==0)continue;
            faces.reset(ys,pageMemberMasks[pi],x,z);plan.forColumnSpans(x,z,faces);
            int i=(iz+1)*stride+ix+1;fullPrefix[i]=faces.full?1:0;anyPrefix[i]=faces.any?1:0;
        }
        prefix(fullPrefix);prefix(anyPrefix);
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(((exactMembers?"source-surface-coverage-members-031-v3\n":"source-surface-coverage-content-031-v2\n")+plan.source().identity()+"\n"+plan.captureIdentity()+"\n"+plan.bounds()+"\n").getBytes(StandardCharsets.UTF_8));
            for(NativePageKey key:selected)digest.update((key+"\n"+(exactMembers?Long.toUnsignedString(canonical.get(key),16)+"\n":"")+plan.pageIndex().get(key).identity()+"\n").getBytes(StandardCharsets.UTF_8));
            identity=HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    /** Every page must be both finished and depth-eligible in the one publication being assembled. */
    public static SourceSurfaceCoverage complete(EarthDistanceSurfacePlan plan,Set<NativePageKey> completedDepthEligiblePages){
        return new SourceSurfaceCoverage(plan,wholePages(completedDepthEligiblePages),true,false);
    }
    /** The complement of this exact signed subset remains DH, even if other pages exist in cache. */
    public static SourceSurfaceCoverage restricted(EarthDistanceSurfacePlan plan,Set<NativePageKey> completedDepthEligiblePages){
        return new SourceSurfaceCoverage(plan,wholePages(completedDepthEligiblePages),false,false);
    }
    /** Exact completed and depth-eligible16m source members inside64m physical pages. */
    public static SourceSurfaceCoverage restrictedMembers(EarthDistanceSurfacePlan plan,Map<NativePageKey,Long> completedDepthEligibleMembers){
        return new SourceSurfaceCoverage(plan,completedDepthEligibleMembers,false,true);
    }
    private static Map<NativePageKey,Long> wholePages(Set<NativePageKey> pages){
        Objects.requireNonNull(pages);var masks=new TreeMap<NativePageKey,Long>();for(var key:pages)masks.put(Objects.requireNonNull(key),-1L);return masks;
    }
    public Map<NativePageKey,Long> eligibleMemberMasks(){return eligibleMemberMasks;}
    public EarthDistanceSurfacePlan plan(){return plan;}
    public String identity(){return identity;}
    public Set<NativePageKey> eligiblePages(){return eligiblePages;}
    public EarthDistanceSurfacePlan.Bounds bounds(){return plan.bounds();}
    public long coverageBytes(){return coverageBytes;}

    /** FULL means all actual exposed faces for every requested column are in the selected pages. */
    public Classification classification(long minX,long minZ,long maxXExclusive,long maxZExclusive){
        var request=new EarthDistanceSurfacePlan.Bounds(minX,minZ,maxXExclusive,maxZExclusive);var b=plan.bounds();
        long x0=Math.max(minX,b.minX()),z0=Math.max(minZ,b.minZ()),x1=Math.min(maxXExclusive,b.maxXExclusive()),z1=Math.min(maxZExclusive,b.maxZExclusive());
        if(x0>=x1||z0>=z1)return Classification.UNCOVERED;
        long full=sum(fullPrefix,x0,z0,x1,z1),any=sum(anyPrefix,x0,z0,x1,z1);
        if(full==request.columns())return Classification.FULL;
        return any==0?Classification.UNCOVERED:Classification.BOUNDARY;
    }
    /** Exact occupied intervals clipped to selected signed16m members of64m pages. Never extrudes an XZ claim in Y. */
    public List<SolidSpan> solidSpans(long x,long z){
        if(!plan.bounds().contains(x,z))return List.of();int pi=pageIndex(x,z);long[] ys=pageYs[pi],masks=pageMemberMasks[pi];if(ys.length==0)return List.of();
        ArrayList<SolidSpan> spans=new ArrayList<>();
        plan.forColumnSolidSpans(x,z,(lo,hi)->{
            for(int i=0;i<ys.length;i++)for(int localY=0;localY<4;localY++){
                if((masks[i]&(1L<<memberBit(x,localY,z)))==0)continue;
                long p0=Math.addExact(Math.multiplyExact(ys[i],64),localY*16L),p1=Math.addExact(p0,16);
                long start=Math.max(lo,p0),end=Math.min(hi,p1);if(start>=end)continue;
                if(!spans.isEmpty()&&spans.getLast().maxYExclusive()==start){SolidSpan old=spans.removeLast();spans.add(new SolidSpan(old.minY(),end));}
                else spans.add(new SolidSpan(start,end));
            }
        });
        return List.copyOf(spans);
    }
    public boolean covers(long x,long y,long z){
        if(!plan.bounds().contains(x,z)||y<plan.source().minY()||y>=plan.source().maxYExclusive())return false;
        int pi=pageIndex(x,z);return selected(pageYs[pi],pageMemberMasks[pi],x,Math.floorDiv(y,16),z)&&plan.materialAt(x,y,z)!=0;
    }
    private int pageIndex(long x,long z){var b=plan.bounds();return Math.toIntExact(((z-b.minZ())/64)*pageWidth+(x-b.minX())/64);}
    private void prefix(long[] a){for(int z=1;z<=depth;z++)for(int x=1;x<=width;x++){int i=z*stride+x;a[i]=Math.addExact(a[i],Math.subtractExact(Math.addExact(a[i-1],a[i-stride]),a[i-stride-1]));}}
    private long sum(long[] a,long x0,long z0,long x1,long z1){var b=plan.bounds();int left=Math.toIntExact(x0-b.minX()),right=Math.toIntExact(x1-b.minX()),top=Math.toIntExact(z0-b.minZ()),bottom=Math.toIntExact(z1-b.minZ());
        return a[bottom*stride+right]-a[top*stride+right]-a[bottom*stride+left]+a[top*stride+left];}
    private static int memberBit(long x,int localY,long z){return (localY*4+(int)Math.floorMod(Math.floorDiv(z,16),4))*4+(int)Math.floorMod(Math.floorDiv(x,16),4);}
    private static boolean selected(long[] ys,long[] masks,long x,long cubeY,long z){
        int i=Arrays.binarySearch(ys,Math.floorDiv(cubeY,4));return i>=0&&(masks[i]&(1L<<memberBit(x,(int)Math.floorMod(cubeY,4),z)))!=0;
    }
    private static final class FaceScan implements EarthDistanceSurfacePlan.SpanConsumer {
        private long[] ys,masks;private long columnX,columnZ;private boolean full,any;
        void reset(long[] ys,long[] masks,long x,long z){this.ys=ys;this.masks=masks;columnX=x;columnZ=z;full=true;any=false;}
        @Override public void accept(long x,long z,long lo,long hi,EarthDistanceSurfacePlan.Direction d,int material){
            if(!full&&any)return;for(long cy=Math.floorDiv(lo,16);cy<=Math.floorDiv(hi-1,16);cy++){if(!selected(ys,masks,columnX,cy,columnZ))full=false;else any=true;}
        }
    }
}
