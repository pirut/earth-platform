package io.earthplatform.minecraft.survival.render;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.lod.DhTerrainGeometry;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Charged immutable clipping CONTENT, never evidence of completed meshes or a draw permit.
 * The future publisher must authenticate exactly these masks against live native completions,
 * current source/overlay and actual depth eligibility. Planning masks alone cannot do that.
 * Each selected cube owns its XYZ volume, including a correctly completed empty mesh. Ground
 * completeness uses every planned reason, never foliage opacity or a single sampled height.
 */
public final class SurvivalDhCoverage implements AutoCloseable {
    public enum Classification { FULL, BOUNDARY, UNCOVERED }
    private static final long HANDLE_BYTES = 256;
    private final Shared shared;
    private final NativeDistanceCpuBudget.Reservation handle;
    private boolean closed;
    private SurvivalDhCoverage(Shared shared, NativeDistanceCpuBudget.Reservation handle) {
        this.shared=shared; this.handle=handle;
    }
    private static final class Shared {
        SurvivalCompleteOverlay.PlanInput plan;
        int[][] rows;
        int[] fullPrefix,anyPrefix;
        final SurvivalDistancePagePlan.Bounds bounds;
        final int width,depth,stride;
        final String planIdentity,contentIdentity,sourceIdentity,identity;
        final NativeDistanceCpuBudget budget;
        final NativeDistanceCpuBudget.Reservation charge;
        int references;
        Shared(SurvivalCompleteOverlay.PlanInput plan,Map<CubePos,Long> masks,
                NativeDistanceCpuBudget budget,NativeDistanceCpuBudget.Reservation charge) {
            this.plan=plan;this.budget=budget;this.charge=charge;bounds=plan.bounds();
            width=Math.toIntExact(bounds.width()/16);depth=Math.toIntExact(bounds.depth()/16);stride=width+1;
            int slots=Math.multiplyExact(width,depth);
            int[] expected=new int[slots],selected=new int[slots];
            var canonical=new TreeMap<CubePos,Long>();
            for(int i=0;i<plan.pageCount();i++) {
                var page=plan.page(i);CubePos min=page.address().minimum();
                Long supplied=masks.get(min);long mask=supplied==null?0:supplied;
                if(mask==0&&supplied!=null||(mask&~page.memberMask())!=0)
                    throw new SurvivalRefusal("DH coverage includes an empty or unplanned member mask");
                if(mask!=0)canonical.put(min,mask);
                for(int bit=0;bit<64;bit++)if((page.memberMask()&(1L<<bit))!=0) {
                    CubePos p=member(min,bit);int slot=slot(p.minBlockX(),p.minBlockZ());expected[slot]++;
                    if((mask&(1L<<bit))!=0)selected[slot]++;
                }
            }
            if(canonical.size()!=masks.size())throw new SurvivalRefusal("DH coverage includes an unplanned page");
            rows=new int[slots][];
            for(int i=0;i<slots;i++)rows[i]=new int[selected[i]];
            int[] cursors=new int[slots];
            for(var entry:canonical.entrySet())for(int bit=0;bit<64;bit++)if((entry.getValue()&(1L<<bit))!=0) {
                CubePos p=member(entry.getKey(),bit);int slot=slot(p.minBlockX(),p.minBlockZ());rows[slot][cursors[slot]++]=p.y();
            }
            for(int[] column:rows)Arrays.sort(column);
            fullPrefix=new int[(width+1)*(depth+1)];anyPrefix=new int[fullPrefix.length];
            for(int z=0;z<depth;z++)for(int x=0;x<width;x++) {
                int slot=z*width+x,index=(z+1)*stride+x+1;
                fullPrefix[index]=expected[slot]>0&&selected[slot]==expected[slot]?1:0;
                anyPrefix[index]=selected[slot]>0?1:0;
            }
            prefix(fullPrefix);prefix(anyPrefix);
            planIdentity=plan.identity();
            try(var content=plan.retainContent()) {contentIdentity=content.contentIdentity();sourceIdentity=content.sourceIdentity();}
            identity=sha("survival-dh-xyz-coverage-content-039-v1\n"+planIdentity+"\n"+contentIdentity+"\n"+sourceIdentity+"\n"+canonical);
        }
        int slot(long x,long z) {
            if(!bounds.contains(x,z))throw new SurvivalRefusal("Coverage column outside exact selected footprint");
            return Math.toIntExact(Math.floorDiv(z-bounds.minZ(),16)*width+Math.floorDiv(x-bounds.minX(),16));
        }
        void prefix(int[] p) {
            for(int z=1;z<=depth;z++)for(int x=1;x<=width;x++) {
                int i=z*stride+x;p[i]=Math.addExact(p[i],Math.subtractExact(Math.addExact(p[i-1],p[i-stride]),p[i-stride-1]));
            }
        }
        int sum(int[] p,int x0,int z0,int x1,int z1) {
            return p[z1*stride+x1]-p[z0*stride+x1]-p[z1*stride+x0]+p[z0*stride+x0];
        }
        SurvivalDhCoverage open() {
            var lease=budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,HANDLE_BYTES);
            try {int next=Math.incrementExact(references);var result=new SurvivalDhCoverage(this,lease);references=next;return result;}
            catch(RuntimeException|Error e){lease.releaseAfterPhysicalDrain();throw e;}
        }
        void release() {
            if(--references!=0)return;
            rows=null;fullPrefix=null;anyPrefix=null;
            plan.close();plan=null;charge.releaseAfterPhysicalDrain();
        }
    }
    /** The supplied masks remain caller-attested content; this method does not authenticate them. */
    public static SurvivalDhCoverage capture(SurvivalCompleteOverlay.PlanInput input,
            Map<CubePos,Long> eligibleMasks,NativeDistanceCpuBudget budget) {
        Objects.requireNonNull(input);Objects.requireNonNull(eligibleMasks);Objects.requireNonNull(budget);
        var alias=input.retain();NativeDistanceCpuBudget.Reservation charge=null;SurvivalDhCoverage result=null;
        try {
            int pages=alias.pageCount();var bounds=alias.bounds();
            if(eligibleMasks.size()>pages||bounds.width()%16!=0||bounds.depth()%16!=0
                    ||bounds.width()>1040||bounds.depth()>1040)
                throw new SurvivalRefusal("DH coverage exceeds exact selected-distance domain");
            long slots=Math.multiplyExact(bounds.width()/16,bounds.depth()/16);
            long members=alias.census().members();
            long bytes=Math.addExact(8192,Math.addExact(Math.multiplyExact(slots,96),
                    Math.addExact(Math.multiplyExact(pages,256L),Math.multiplyExact(members,16))));
            charge=budget.reserve(NativeDistanceCpuBudget.Pool.COVERAGE,bytes);
            var copy=new TreeMap<CubePos,Long>();
            for(var entry:eligibleMasks.entrySet()) {
                if(copy.size()>=pages||copy.put(Objects.requireNonNull(entry.getKey()),Objects.requireNonNull(entry.getValue()))!=null)
                    throw new SurvivalRefusal("DH coverage input changed during bounded copy");
            }
            if(copy.size()!=eligibleMasks.size())throw new SurvivalRefusal("DH coverage input changed during bounded copy");
            result=new Shared(alias,copy,budget,charge).open();return result;
        }catch(RuntimeException|Error failure) {
            if(result!=null)result.close();else {alias.close();if(charge!=null)charge.releaseAfterPhysicalDrain();}
            throw failure;
        }
    }
    private Shared open() {if(closed||shared.references<1)throw new SurvivalRefusal("DH coverage handle is closed");return shared;}
    public SurvivalDhCoverage retain(){synchronized(shared){return open().open();}}
    public SurvivalCompleteOverlay.Content retainContent(){synchronized(shared){return open().plan.retainContent();}}
    public String identity(){synchronized(shared){return open().identity;}}
    public String planIdentity(){synchronized(shared){return open().planIdentity;}}
    public String contentIdentity(){synchronized(shared){return open().contentIdentity;}}
    public String sourceIdentity(){synchronized(shared){return open().sourceIdentity;}}
    public SurvivalDistancePagePlan.Bounds bounds(){synchronized(shared){return open().bounds;}}
    /** Identity assertion only; no new content, physical, camera or currentness authority. */
    public void requireSame(SurvivalDhCoverage exact) {
        Objects.requireNonNull(exact);
        if(identityOwner()!=exact.identityOwner())throw new SurvivalRefusal("Different exact DH coverage ownership");
    }
    private Shared identityOwner(){synchronized(shared){return open();}}
    public void requireExactPlan(SurvivalCompleteOverlay.FrameLatch frame) {
        synchronized(shared){Objects.requireNonNull(frame).requireExactPlan(open().plan);}
    }
    public void requireExactPlan(SurvivalCompleteOverlay.PlanInput plan) {
        synchronized(shared){open().plan.requireSame(Objects.requireNonNull(plan));}
    }
    public long selectedMask(int pageIndex) {
        synchronized(shared){
            Shared s=open();var page=s.plan.page(pageIndex);long result=0;
            for(int bit=0;bit<64;bit++)if((page.memberMask()&(1L<<bit))!=0){
                CubePos p=member(page.address().minimum(),bit);
                if(Arrays.binarySearch(s.rows[s.slot(p.minBlockX(),p.minBlockZ())],p.y())>=0)result|=1L<<bit;
            }
            return result;
        }
    }
    public Classification classification(long x0,long z0,long x1,long z1) {
        synchronized(shared) {
            Shared s=open();if(x0>=x1||z0>=z1)throw new IllegalArgumentException("Nonempty footprint required");
            long left=Math.max(x0,s.bounds.minX()),top=Math.max(z0,s.bounds.minZ());
            long right=Math.min(x1,s.bounds.maxXExclusive()),bottom=Math.min(z1,s.bounds.maxZExclusive());
            if(left>=right||top>=bottom)return Classification.UNCOVERED;
            int a=Math.toIntExact((left-s.bounds.minX())/16),b=Math.toIntExact((top-s.bounds.minZ())/16);
            int c=Math.toIntExact((right-s.bounds.minX()+15)/16),d=Math.toIntExact((bottom-s.bounds.minZ()+15)/16);
            if(left==x0&&top==z0&&right==x1&&bottom==z1&&s.sum(s.fullPrefix,a,b,c,d)==(c-a)*(d-b))return Classification.FULL;
            return s.sum(s.anyPrefix,a,b,c,d)==0?Classification.UNCOVERED:Classification.BOUNDARY;
        }
    }
    public boolean containsMember(CubePos p) {
        synchronized(shared){Shared s=open();return s.bounds.contains(p.minBlockX(),p.minBlockZ())
                &&Arrays.binarySearch(s.rows[s.slot(p.minBlockX(),p.minBlockZ())],p.y())>=0;}
    }
    /** Unit boundary refinement makes sparse signed-Y subtraction exact and bounded. */
    public List<DhTerrainGeometry.Face> clip(DhTerrainGeometry.Face face) {
        Objects.requireNonNull(face);
        synchronized(shared) {
            Shared s=open();var d=face.direction();
            long x=Math.subtractExact(face.x(),d==DhTerrainGeometry.Direction.EAST?1:0);
            long z=Math.subtractExact(face.z(),d==DhTerrainGeometry.Direction.SOUTH?1:0);
            long x1=Math.addExact(x,d==DhTerrainGeometry.Direction.WEST||d==DhTerrainGeometry.Direction.EAST?1:face.width());
            long z1=Math.addExact(z,d==DhTerrainGeometry.Direction.UP||d==DhTerrainGeometry.Direction.DOWN?face.height():
                    d==DhTerrainGeometry.Direction.WEST||d==DhTerrainGeometry.Direction.EAST?face.width():1);
            if(classification(x,z,x1,z1)==Classification.UNCOVERED)return List.of(face);
            if(face.width()!=1||((d==DhTerrainGeometry.Direction.UP||d==DhTerrainGeometry.Direction.DOWN)&&face.height()!=1))
                throw new SurvivalRefusal("Native-intersecting DH face was not unit refined");
            int[] rows=s.rows[s.slot(x,z)];
            if(d==DhTerrainGeometry.Direction.UP||d==DhTerrainGeometry.Direction.DOWN) {
                int y=Math.toIntExact(Math.floorDiv(face.y()-(d==DhTerrainGeometry.Direction.UP?1:0),16));
                return Arrays.binarySearch(rows,y)>=0?List.of():List.of(face);
            }
            var result=new ArrayList<DhTerrainGeometry.Face>();long cursor=face.y(),end=cursor+face.height();
            for(int row:rows) {
                long lo=(long)row*16,hi=lo+16;if(hi<=cursor)continue;if(lo>=end)break;
                if(lo>cursor)result.add(new DhTerrainGeometry.Face(d,face.x(),cursor,face.z(),1,Math.toIntExact(Math.min(end,lo)-cursor),face.argb()));
                cursor=Math.max(cursor,hi);if(cursor>=end)break;
            }
            if(cursor<end)result.add(new DhTerrainGeometry.Face(d,face.x(),cursor,face.z(),1,Math.toIntExact(end-cursor),face.argb()));
            return List.copyOf(result);
        }
    }
    @Override public void close(){synchronized(shared){if(closed)return;closed=true;shared.release();handle.releaseAfterPhysicalDrain();}}
    private static CubePos member(CubePos minimum,int bit){return minimum.offset(bit&3,bit>>>4,(bit>>>2)&3);}
    private static String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
