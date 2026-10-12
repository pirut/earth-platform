package io.earthplatform.minecraft.visual;

import io.earthplatform.minecraft.scene.NativeVisualGpuPiece;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.scene.NativeVisualCapsuleCapture;
import java.util.function.BooleanSupplier;
import java.util.*;
import org.joml.Matrix4f;

/** Owner-minted joined native completion and exact source clipping authority. */
public final class NativeVisualPublication implements AutoCloseable {
    public record CameraDomain(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        public CameraDomain {
            if(!Double.isFinite(minX)||!Double.isFinite(minY)||!Double.isFinite(minZ)||!Double.isFinite(maxX)||!Double.isFinite(maxY)||!Double.isFinite(maxZ)||minX>maxX||minY>maxY||minZ>maxZ)
                throw new IllegalArgumentException("Finite ordered native camera domain required");
            if(maxX-minX>128||maxY-minY>128||maxZ-minZ>128)throw new IllegalArgumentException("Native camera permit is locally bounded");
        }
        public boolean contains(double x,double y,double z){return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)&&x>=minX&&x<=maxX&&y>=minY&&y<=maxY&&z>=minZ&&z<=maxZ;}
    }
    private final Thread owner=Thread.currentThread();
    private final NativeVisualStamp stamp;
    private final EarthDistanceSurfacePlan plan;
    private final SourceSurfaceCoverage coverage;
    private final CameraDomain camera;
    private final List<NativeVisualGpuPiece.Completion> receipts;
    private final List<NativeVisualGpuPiece> drawnPieces;
    private final Map<NativePageKey,Long> drawnMemberMasks;
    private final EarthDistanceSurfacePlan.Bounds drawnBounds;
    private final BooleanSupplier ownerGuard;
    private final NativeVisualCapsuleCapture.Lease resourceLease;
    private final CheckedDepth depth;
    private final double guaranteedRadius;
    /** Actor-centered overlap permits remain continuous across adjacent 16 m windows. */
    public static CameraDomain cameraPermit(CubePos center) {
        Objects.requireNonNull(center);
        return new CameraDomain(center.minBlockX()-6.0,center.minBlockY()-6.0,center.minBlockZ()-6.0,
                center.minBlockX()+22.0,center.minBlockY()+22.0,center.minBlockZ()+22.0);
    }
    private boolean revoked;

    /** All receipts are private-constructor capabilities minted by successfully uploaded live owners. */
    public NativeVisualPublication(NativeVisualStamp stamp,EarthDistanceSurfacePlan plan,
            List<NativeVisualGpuPiece.Completion> completed,Matrix4f actualNativeProjection,CameraDomain camera,BooleanSupplier ownerGuard) {
        this.stamp=Objects.requireNonNull(stamp);this.plan=Objects.requireNonNull(plan);this.camera=Objects.requireNonNull(camera);this.ownerGuard=Objects.requireNonNull(ownerGuard);
        stamp.requireSelectedDistanceEffective();if(!ownerGuard.getAsBoolean())throw new IllegalStateException("Native retained-resource generation is stale");
        if(plan.guardRows()!=0||plan.requestedChunks()!=stamp.effectiveDistanceChunks())throw new IllegalArgumentException("Only the selected draw footprint can publish native coverage");
        if(!stamp.sourceIdentity().equals(plan.sourceIdentity())||!stamp.detailIdentity().equals(plan.source().detailIdentity())||!stamp.materialPolicyIdentity().equals(plan.source().materialPolicyIdentity())||!stamp.captureIdentity().equals(plan.captureIdentity()))throw new IllegalArgumentException("Native publication source/capture mismatch");
        receipts=List.copyOf(completed);if(receipts.size()>NativeVisualBudget.MAX_PIECES)throw new IllegalArgumentException("Native completion owner limit exceeded");
        var byPage=new TreeMap<NativePageKey,List<NativeVisualGpuPiece.Completion>>();
        Set<NativeVisualGpuPiece> physical=Collections.newSetFromMap(new IdentityHashMap<>());
        resourceLease=receipts.isEmpty()?null:receipts.getFirst().resourceLease();
        if(resourceLease!=null)resourceLease.requireCurrent();
        for(var receipt:receipts){receipt.requirePhysicalCurrent();if(receipt.resourceLease()!=resourceLease)throw new IllegalArgumentException("Mixed native resource owners");if(!physical.add(receipt.physicalOwner()))throw new IllegalArgumentException("Duplicate native physical owner");
            if(!receipt.capsuleIdentity().bytesDigest().equals(stamp.capsuleIdentity()))throw new IllegalArgumentException("Native capsule bytes differ from stamp");
            byPage.computeIfAbsent(receipt.key(),unused->new ArrayList<>()).add(receipt);
        }
        if(!byPage.keySet().equals(plan.pageIndex().keySet()))throw new IllegalArgumentException("Missing or extra actual native page completion");
        for(var page:plan.pages())validatePage(page,byPage.get(page.key()));
        depth=new CheckedDepth(actualNativeProjection);
        int radius=stamp.radiusBlocks();var center=plan.center();
        drawnBounds=new EarthDistanceSurfacePlan.Bounds(center.minBlockX()-radius,center.minBlockZ()-radius,
                center.minBlockX()+16L+radius,center.minBlockZ()+16L+radius);
        guaranteedRadius=Math.min(radius,Math.min(Math.min(camera.minX()-drawnBounds.minX(),drawnBounds.maxXExclusive()-camera.maxX()),Math.min(camera.minZ()-drawnBounds.minZ(),drawnBounds.maxZExclusive()-camera.maxZ())));
        if(guaranteedRadius<radius-6.0)throw new IllegalArgumentException("Completed native chunk footprint does not contain its bounded camera envelope");
        var eligible=new TreeMap<NativePageKey,Long>();var drawn=new ArrayList<NativeVisualGpuPiece>();
        for(var page:plan.pages()){
            var key=page.key();long mask=depthEligibleMemberMask(page,drawnBounds,camera,depth.nativeFar,guaranteedRadius);
            if(mask!=0){eligible.put(key,mask);for(var receipt:byPage.get(key)){
                boolean selected=false;for(var range:receipt.memberRanges())if((mask&(1L<<range.member()))!=0&&range.quads()!=0){selected=true;break;}
                if(selected)drawn.add(receipt.physicalOwner());
            }}
        }
        drawnMemberMasks=Collections.unmodifiableMap(eligible);
        coverage=SourceSurfaceCoverage.restrictedMembers(plan,drawnMemberMasks);drawnPieces=List.copyOf(drawn);
        requireCurrent();
    }
    /** Pure conservative geometry selection; only authenticated completion can publish its result. */
    public static long depthEligibleMemberMask(EarthDistanceSurfacePlan.Page page,EarthDistanceSurfacePlan.Bounds requested,
            CameraDomain camera,double nativeFar,double guaranteedRadius) {
        Objects.requireNonNull(page);Objects.requireNonNull(requested);Objects.requireNonNull(camera);
        if(!Double.isFinite(nativeFar)||!Double.isFinite(guaranteedRadius)||nativeFar<=0||guaranteedRadius<=0)throw new IllegalArgumentException("Finite positive native depth required");
        long mask=0;var key=page.key();
        for(int member=0;member<64;member++){
            if((page.memberMask()&(1L<<member))==0)continue;
            long x=key.minX()+(member&3)*16L,z=key.minZ()+((member>>>2)&3)*16L,y=key.minY()+(member>>>4)*16L;
            if(!requested.contains(x,z))continue;
            var p=page.faceBounds();var b=new EarthDistanceSurfacePlan.FaceBounds(Math.max(x,p.minX()),Math.max(y,p.minY()),Math.max(z,p.minZ()),Math.min(x+16,p.maxX()),Math.min(y+16,p.maxY()),Math.min(z+16,p.maxZ()));
            if(farthest(b,camera)<nativeFar-1)mask|=1L<<member;
            else if(nearest(b,camera)<=guaranteedRadius)throw new IllegalStateException("Selected native radius includes a surface outside the actual native depth envelope");
        }
        return mask;
    }
    private static void validatePage(EarthDistanceSurfacePlan.Page page,List<NativeVisualGpuPiece.Completion> parts){
        if(parts.size()!=page.pieces())throw new IllegalArgumentException("Native piece count differs from exact source census");
        parts.sort(Comparator.comparingInt(NativeVisualGpuPiece.Completion::ordinal));long quads=0,seenMembers=0;int member=-1,cursor=0;
        for(int i=0;i<parts.size();i++){
            var part=parts.get(i);
            if(part.ordinal()!=i||part.completesRequest()!=(i==parts.size()-1)||!part.contentIdentity().equals(page.identity()))throw new IllegalArgumentException("Native page pieces are stale, missing or out of order");
            quads=Math.addExact(quads,part.quads());
            for(var range:part.memberRanges()){
                if(range.member()!=member){if(member>=0&&cursor!=4096*6)throw new IllegalArgumentException("Incomplete native member before next member");if(range.member()<=member||range.firstFace()!=0)throw new IllegalArgumentException("Native member ordering mismatch");member=range.member();cursor=0;seenMembers|=1L<<member;}
                if(range.firstFace()!=cursor)throw new IllegalArgumentException("Native scan coverage has a gap or overlap");cursor=range.endFace();
            }
        }
        if(cursor!=4096*6||quads!=page.quads()||seenMembers!=page.memberMask())throw new IllegalArgumentException("Actual native completion disagrees with exact source face/member census");
    }
    private static double farthest(EarthDistanceSurfacePlan.FaceBounds b,CameraDomain c){return Math.sqrt(sq(Math.max(Math.abs(b.minX()-c.maxX()),Math.abs(b.maxX()-c.minX())))+sq(Math.max(Math.abs(b.minY()-c.maxY()),Math.abs(b.maxY()-c.minY())))+sq(Math.max(Math.abs(b.minZ()-c.maxZ()),Math.abs(b.maxZ()-c.minZ()))));}
    private static double nearest(EarthDistanceSurfacePlan.FaceBounds b,CameraDomain c){return Math.sqrt(sq(gap(b.minX(),b.maxX(),c.minX(),c.maxX()))+sq(gap(b.minY(),b.maxY(),c.minY(),c.maxY()))+sq(gap(b.minZ(),b.maxZ(),c.minZ(),c.maxZ())));}
    private static double gap(double a,double b,double c,double d){return Math.max(0,Math.max(a-d,c-b));}private static double sq(double x){return x*x;}
    public final class CheckedDepth {
        private final Matrix4f projection;
        private final double nativeNear,nativeFar;
        private CheckedDepth(Matrix4f actual){
            projection=new Matrix4f(Objects.requireNonNull(actual));float[] m=projection.get(new float[16]);for(float v:m)if(!Float.isFinite(v))throw new IllegalArgumentException("Finite actual native projection required");
            if(Math.abs(projection.m23()+1)>1e-5||Math.abs(projection.m33())>1e-5||Math.abs(projection.m02())>1e-7||Math.abs(projection.m12())>1e-7||Math.abs(projection.m03())>1e-7||Math.abs(projection.m13())>1e-7)throw new IllegalArgumentException("Pinned native perspective projection required");
            nativeNear=projection.m32()/(projection.m22()-1.0);nativeFar=projection.m32()/(projection.m22()+1.0);
            double expected=stamp.effectiveDistanceChunks()*64.0;
            if(Math.abs(nativeNear-.05)>1e-5||!Double.isFinite(nativeFar)||Math.abs(nativeFar-expected)>Math.max(.5,expected*.002))throw new IllegalArgumentException("Actual native far plane disagrees with selected Minecraft distance");
        }
        public int selectedDistanceChunks(){requireCurrent();return stamp.selectedDistanceChunks();}
        public int effectiveDistanceChunks(){requireCurrent();return stamp.effectiveDistanceChunks();}
        public double guaranteedNativeRadius(){requireCurrent();return guaranteedRadius;}
        public double nativeFar(){requireCurrent();return nativeFar;}
        public double nativeNear(){requireCurrent();return nativeNear;}
        public Matrix4f nativeProjection(){requireCurrent();return new Matrix4f(projection);}
        public void requireCurrent(){NativeVisualPublication.this.requireCurrent();}
    }
    public NativeVisualStamp stamp(){requireCurrent();return stamp;}
    public EarthDistanceSurfacePlan plan(){requireCurrent();return plan;}
    public SourceSurfaceCoverage coverage(){requireCurrent();return coverage;}
    public CheckedDepth checkedDepth(){requireCurrent();return depth;}
    public CameraDomain cameraDomain(){requireCurrent();return camera;}
    public List<NativeVisualGpuPiece> drawnPieces(){requireCurrent();return drawnPieces;}
    public Map<NativePageKey,Long> drawnMemberMasks(){requireCurrent();return drawnMemberMasks;}
    public EarthDistanceSurfacePlan.Bounds drawnBounds(){requireCurrent();return drawnBounds;}
    public void requireNativeProjection(Matrix4f actual){requireCurrent();new CheckedDepth(actual);if(Float.floatToIntBits(actual.m22())!=Float.floatToIntBits(depth.projection.m22())||Float.floatToIntBits(actual.m32())!=Float.floatToIntBits(depth.projection.m32()))throw new IllegalStateException("Native projection depth changed after publication");}
    public void requireCamera(double x,double y,double z){requireCurrent();if(!camera.contains(x,y,z))throw new IllegalStateException("Actual camera crossed the completed native visual frontier");}
    /** Retained presentation is independent of actor-current edit authority; its input checks that separately. */
    public boolean isCurrent(){checkOwner();if(revoked)return false;if(!ownerGuard.getAsBoolean()){revoked=true;return false;}if(resourceLease!=null)resourceLease.requireCurrent();for(var receipt:receipts)receipt.requirePhysicalCurrent();return true;}
    public boolean permitsRetainedPresentation(){return isCurrent();}
    public void requireCurrent(){if(!isCurrent())throw new IllegalStateException("Native visual publication revoked");}
    @Override public void close(){checkOwner();revoked=true;}
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Native publication requires its render owner");}
}
