package io.earthplatform.minecraft.visual;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.EarthReplacementCapture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Pure bounded source/geometry description. This class never grants draw or replacement authority. */
public final class EarthDistanceSurfacePlan {
    public static final int MIN_CHUNKS = 2, MAX_CHUNKS = 32, QUADS_PER_PIECE = 5041;
    public static final long VERTEX_BYTES_PER_QUAD = 208, INDEX_BYTES_PER_QUAD = 24;
    private static final String DOMAIN = "earth-distance-source-surfaces-031-v1";
    private static final String NO_CAPTURE = hash("no-edit-capture");
    private static final Direction[] DIRECTIONS = Direction.values();

    public static final class Refusal extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public Refusal(String message) { super(message); }
        public Refusal(String message, Throwable cause) { super(message, cause); }
    }
    /** All allocation/admission limits are explicit caller policy; no runtime default is implied. */
    public record Limits(long maxSourceColumns, long maxSourceBytes, long maxPlanColumns,
                         long maxPages, long maxPieces, long maxQuads, long maxGeometryBytes,
                         long maxCoverageBytes) {
        public Limits {
            if (maxSourceColumns <= 0 || maxSourceBytes <= 0 || maxPlanColumns <= 0 || maxPages <= 0
                    || maxPieces <= 0 || maxQuads <= 0 || maxGeometryBytes <= 0 || maxCoverageBytes <= 0)
                throw new Refusal("Every source/plan admission limit must be positive and explicit");
        }
    }
    /** Half-open block XZ coordinates; all arithmetic is checked before allocation or sampling. */
    public record Bounds(long minX, long minZ, long maxXExclusive, long maxZExclusive) {
        public Bounds {
            if (minX >= maxXExclusive || minZ >= maxZExclusive) throw new Refusal("Empty or inverted XZ bounds");
            try { Math.multiplyExact(Math.subtractExact(maxXExclusive, minX), Math.subtractExact(maxZExclusive, minZ)); }
            catch (ArithmeticException e) { throw new Refusal("XZ bounds overflow", e); }
        }
        public long width() { return Math.subtractExact(maxXExclusive, minX); }
        public long depth() { return Math.subtractExact(maxZExclusive, minZ); }
        public long columns() { return Math.multiplyExact(width(), depth()); }
        public boolean contains(long x, long z) { return x >= minX && x < maxXExclusive && z >= minZ && z < maxZExclusive; }
        public boolean contains(Bounds b) { return b.minX >= minX && b.minZ >= minZ && b.maxXExclusive <= maxXExclusive && b.maxZExclusive <= maxZExclusive; }
        public Bounds expand(long n) { return new Bounds(Math.subtractExact(minX,n), Math.subtractExact(minZ,n), Math.addExact(maxXExclusive,n), Math.addExact(maxZExclusive,n)); }
        public Bounds roundPages() { return new Bounds(floorPage(minX), floorPage(minZ), ceilPage(maxXExclusive), ceilPage(maxZExclusive)); }
    }
    public enum Direction {
        DOWN(0,-1,0), UP(0,1,0), NORTH(0,0,-1), SOUTH(0,0,1), WEST(-1,0,0), EAST(1,0,0);
        private final int dx,dy,dz;
        Direction(int dx,int dy,int dz) { this.dx=dx;this.dy=dy;this.dz=dz; }
        public int dx(){return dx;} public int dy(){return dy;} public int dz(){return dz;}
    }
    @FunctionalInterface public interface FaceConsumer { void accept(long x,long y,long z,Direction direction,int material); }
    @FunctionalInterface interface SolidSpanConsumer { void accept(long minY,long maxYExclusive); }
    @FunctionalInterface interface SpanConsumer { void accept(long x,long z,long minY,long maxYExclusive,Direction direction,int material); }
    /** Inclusive exact emitted-vertex extrema; a flat face can have an equal min/max on one axis. */
    public record FaceBounds(long minX,long minY,long minZ,long maxX,long maxY,long maxZ) {
        public FaceBounds { if(minX>maxX||minY>maxY||minZ>maxZ)throw new Refusal("Inverted emitted face bounds"); }
        public boolean containsVertex(long x,long y,long z){return x>=minX&&x<=maxX&&y>=minY&&y<=maxY&&z>=minZ&&z<=maxZ;}
    }
    /** A material is an EarthReplacementCapture palette ID, including DIORITE for edits. */
    public record Page(NativePageKey key, long quads, int pieces, String identity,FaceBounds faceBounds,long memberMask) {
        public Page {
            Objects.requireNonNull(key); Objects.requireNonNull(identity);Objects.requireNonNull(faceBounds);
            if(memberMask==0||faceBounds.minX<key.minX()||faceBounds.maxX>key.maxXExclusive()
                    ||faceBounds.minY<key.minY()||faceBounds.maxY>key.maxYExclusive()
                    ||faceBounds.minZ<key.minZ()||faceBounds.maxZ>key.maxZExclusive())throw new Refusal("Face bounds/member mask escape their signed page");
            if (quads <= 0 || pieces != pieceCount(quads)) throw new Refusal("Invalid native page piece census");
        }
        /** Bit (localCubeY*4+localCubeZ)*4+localCubeX owns at least one exposed native face. */
        public int memberCount(){return Long.bitCount(memberMask);}
        public boolean ownsMember(CubePos cube){
            if(!key.contains(cube.minBlockX(),cube.minBlockY(),cube.minBlockZ()))return false;
            int bit=(key.localY(cube.minBlockY())/16*4+key.localZ(cube.minBlockZ())/16)*4+key.localX(cube.minBlockX())/16;
            return (memberMask&(1L<<bit))!=0;
        }
        public long vertexBytes(){return Math.multiplyExact(quads,VERTEX_BYTES_PER_QUAD);}
        public long conservativeIndexBytes(){return Math.multiplyExact(quads,INDEX_BYTES_PER_QUAD);}
        public long geometryBytes(){return Math.addExact(vertexBytes(),conservativeIndexBytes());}
    }
    /** One transient sample; Source retains only primitive arrays, never Column/per-block objects. */
    public record Sample(long surfaceY, int top, int subsurface, int sourceClass, int receipt,
                         double sourceMetres, double proceduralDeltaMetres) {
        public Sample {
            if (top < 1 || top > 5 || subsurface < 1 || subsurface > 5 || sourceClass < 0 || sourceClass > 255
                    || receipt < 0 || receipt > 255 || !Double.isFinite(sourceMetres) || !Double.isFinite(proceduralDeltaMetres)
                    || Math.abs(proceduralDeltaMetres)>2 || Math.abs(sourceMetres)>0x1.0p52
                    || surfaceY != (long) StrictMath.floor(sourceMetres+proceduralDeltaMetres))
                throw new Refusal("Unsupported exact measured/detail/material source column");
        }
        int packed(){return top | subsurface<<3 | sourceClass<<6 | receipt<<14;}
    }
    @FunctionalInterface public interface ColumnSampler { Sample sample(long x,long z); }
    /** Optional observer of cumulative completed work within one prepare/build invocation. */
    @FunctionalInterface public interface CompletedWork { void completed(long count); }

    /** Shared immutable slab, sampled exactly once over every allowed center and its maximal view. */
    public static final class Source {
        private final EarthAlpineDefinition.Envelope envelope;
        private final Bounds ownedBounds, sampledBounds;
        private final long minY,maxY,sourceBytes;
        private final int maxChunks,guardRows,width;
        private final long[] heights;
        private final int[] labels;
        private final double[] sourceMetres,detailMetres;
        private final String sourceIdentity,detailIdentity,materialPolicyIdentity,identity,sampleIdentity;
        private Source(EarthAlpineDefinition.Envelope envelope,long minY,long maxY,String sourceIdentity,String detailIdentity,
                       String materialPolicyIdentity,int maxChunks,int guardRows,Limits limits,ColumnSampler sampler,CompletedWork progress) {
            this.envelope=Objects.requireNonNull(envelope);this.minY=minY;this.maxY=maxY;
            this.sourceIdentity=requireIdentity(sourceIdentity);this.detailIdentity=requireIdentity(detailIdentity);
            this.materialPolicyIdentity=requireIdentity(materialPolicyIdentity);this.maxChunks=maxChunks;this.guardRows=guardRows;
            validateDistance(maxChunks);if(guardRows<0)throw new Refusal("Negative native guard rows");
            if(envelope.minCenterX()>=envelope.maxCenterXExclusive() || envelope.minCenterZ()>=envelope.maxCenterZExclusive()
                    || envelope.minCenterY()>=envelope.maxCenterYExclusive() || minY>=maxY)
                throw new Refusal("Invalid source center or signed Y domain");
            // A slab is horizontal, not a dense Y volume. Check signed page arithmetic independently.
            new NativePageKey(0,Math.floorDiv(minY,64),0);new NativePageKey(0,Math.floorDiv(maxY-1,64),0);
            Bounds union=new Bounds(Math.multiplyExact((long)envelope.minCenterX()-maxChunks,16),
                    Math.multiplyExact((long)envelope.minCenterZ()-maxChunks,16),
                    Math.multiplyExact((long)envelope.maxCenterXExclusive()+maxChunks,16),
                    Math.multiplyExact((long)envelope.maxCenterZExclusive()+maxChunks,16));
            ownedBounds=union.roundPages().expand(Math.multiplyExact((long)guardRows,64));
            sampledBounds=ownedBounds.expand(1); // exactly one source-column neighbor halo
            Bounds supported=new Bounds(envelope.minColumnX(),envelope.minColumnZ(),envelope.maxColumnXExclusive(),envelope.maxColumnZExclusive());
            if(!supported.contains(sampledBounds))throw new Refusal("Requested distance/guard union escapes proved source support; no distance reduction");
            long columns=sampledBounds.columns();sourceBytes=Math.addExact(Math.multiplyExact(columns,28),512);
            if(columns>limits.maxSourceColumns || columns>Integer.MAX_VALUE-8L || sourceBytes>limits.maxSourceBytes)
                throw new Refusal("Requested source slab exceeds explicit column/byte admission; no sampling performed");
            width=Math.toIntExact(sampledBounds.width());int count=Math.toIntExact(columns);
            heights=new long[count];labels=new int[count];sourceMetres=new double[count];detailMetres=new double[count];
            MessageDigest digest=digest();byte[] scratch=new byte[32];
            for(long z=sampledBounds.minZ;z<sampledBounds.maxZExclusive;z++){
                for(long x=sampledBounds.minX;x<sampledBounds.maxXExclusive;x++){
                    Sample s=Objects.requireNonNull(sampler.sample(x,z),"Missing sampled source column");
                    if(s.surfaceY<minY || s.surfaceY>=maxY-1)throw new Refusal("Source surface/top-air neighbor escapes signed source Y support");
                    int i=index(x,z);heights[i]=s.surfaceY;labels[i]=s.packed();sourceMetres[i]=s.sourceMetres;detailMetres[i]=s.proceduralDeltaMetres;
                    putLong(scratch,0,s.surfaceY);putLong(scratch,8,s.packed());putLong(scratch,16,Double.doubleToLongBits(s.sourceMetres));
                    putLong(scratch,24,Double.doubleToLongBits(s.proceduralDeltaMetres));digest.update(scratch,0,32);
                }
                // A partially sampled, rejected or undigested row never advances the observer.
                if(progress!=null)progress.completed((z-sampledBounds.minZ+1)*width);
            }
            sampleIdentity=HexFormat.of().formatHex(digest.digest());
            // Dataset/policy identities make physical pages independent of focus and chosen distance.
            identity=hash(DOMAIN+"\n"+sourceIdentity+"\n"+detailIdentity+"\n"+materialPolicyIdentity+"\n"+minY+":"+maxY);
        }
        public static Source prepare(EarthAlpineDefinition definition,int maxChunks,int guardRows,Limits limits){
            return prepare(definition,maxChunks,guardRows,limits,null);
        }
        public static Source prepare(EarthAlpineDefinition definition,int maxChunks,int guardRows,Limits limits,CompletedWork progress){
            Objects.requireNonNull(definition);
            return prepareSampled(definition.envelope(),EarthAlpineDefinition.MIN_BLOCK_Y,EarthAlpineDefinition.MAX_BLOCK_Y_EXCLUSIVE,
                    definition.sourceIdentity(),definition.detailIdentity(),definition.materialPolicyIdentity(),maxChunks,guardRows,limits,(x,z)->{
                        var c=definition.column(x,z);
                        return new Sample(c.surfaceY(),material(c.top()),material(c.subsurface()),c.sourceClassification().classification().ordinal(),
                                c.materialReceipt().ordinal(),c.sourceMetres(),c.proceduralDeltaMetres());
                    },progress);
        }
        /** Pure source adapter for bounded deterministic datasets and tests; does not publish readiness. */
        public static Source prepareSampled(EarthAlpineDefinition.Envelope envelope,long minY,long maxY,String sourceIdentity,String detailIdentity,
                String materialPolicyIdentity,int maxChunks,int guardRows,Limits limits,ColumnSampler sampler){
            return prepareSampled(envelope,minY,maxY,sourceIdentity,detailIdentity,materialPolicyIdentity,maxChunks,guardRows,limits,sampler,null);
        }
        public static Source prepareSampled(EarthAlpineDefinition.Envelope envelope,long minY,long maxY,String sourceIdentity,String detailIdentity,
                String materialPolicyIdentity,int maxChunks,int guardRows,Limits limits,ColumnSampler sampler,CompletedWork progress){
            Objects.requireNonNull(limits);Objects.requireNonNull(sampler);
            try{return new Source(envelope,minY,maxY,sourceIdentity,detailIdentity,materialPolicyIdentity,maxChunks,guardRows,limits,sampler,progress);}
            catch(ArithmeticException e){throw new Refusal("Source bounds or allocation arithmetic overflow; no clamping",e);}
        }
        private int index(long x,long z){
            if(!sampledBounds.contains(x,z))throw new Refusal("Column outside prepared source slab");
            return Math.toIntExact((z-sampledBounds.minZ)*width+x-sampledBounds.minX);
        }
        private String horizontalPageIdentity(long minX,long minZ){
            MessageDigest d=digest();byte[] b=new byte[32];
            for(long z=minZ-1;z<minZ+65;z++)for(long x=minX-1;x<minX+65;x++){
                int i=index(x,z);putLong(b,0,heights[i]);putLong(b,8,labels[i]);putLong(b,16,Double.doubleToLongBits(sourceMetres[i]));
                putLong(b,24,Double.doubleToLongBits(detailMetres[i]));d.update(b);
            }
            return HexFormat.of().formatHex(d.digest());
        }
        public long surfaceY(long x,long z){return heights[index(x,z)];}
        public int materialAt(long x,long y,long z){
            if(y<minY||y>=maxY)throw new Refusal("Material query outside signed source support");
            int i=index(x,z);long h=heights[i];return y>h?0:y==h?labels[i]&7:y>=h-3?(labels[i]>>>3)&7:EarthReplacementCapture.STONE;
        }
        public int sourceClass(long x,long z){return (labels[index(x,z)]>>>6)&255;}
        public int materialReceipt(long x,long z){return (labels[index(x,z)]>>>14)&255;}
        public double sourceMetres(long x,long z){return sourceMetres[index(x,z)];}
        public double proceduralDeltaMetres(long x,long z){return detailMetres[index(x,z)];}
        public long minY(){return minY;}public long maxYExclusive(){return maxY;}
        public Bounds bounds(){return sampledBounds;}public Bounds ownedBounds(){return ownedBounds;}
        public long sampledColumns(){return heights.length;}public long sourceBytes(){return sourceBytes;}
        public int maxChunks(){return maxChunks;}public int guardRows(){return guardRows;}
        public String identity(){return identity;}public String sampleIdentity(){return sampleIdentity;}
        public String sourceIdentity(){return sourceIdentity;}public String detailIdentity(){return detailIdentity;}
        public String materialPolicyIdentity(){return materialPolicyIdentity;}
        public EarthAlpineDefinition.Envelope envelope(){return envelope;}
    }

    private final Source source;
    private final EarthReplacementCapture capture;
    private final CubePos center;
    private final Bounds bounds;
    private final int requestedChunks,guardRows;
    private final Limits limits;
    private final List<Page> pages;
    private final Map<NativePageKey,Page> pageIndex;
    private final long quads,pieces;
    private final String identity,captureIdentity;
    private final boolean changed;
    private EarthDistanceSurfacePlan(Source source,EarthReplacementCapture capture,CubePos center,int requestedChunks,int guardRows,Limits limits,CompletedWork progress){
        this.source=Objects.requireNonNull(source);this.capture=capture;this.center=Objects.requireNonNull(center);this.limits=Objects.requireNonNull(limits);
        validateDistance(requestedChunks);this.requestedChunks=requestedChunks;this.guardRows=guardRows;
        if(requestedChunks>source.maxChunks||guardRows<0||guardRows>source.guardRows)throw new Refusal("Distance/guard not included in prepared source union");
        if(!source.envelope.permitsCenter(center))throw new Refusal("Focus outside prepared signed cube-center domain");
        bounds=new Bounds(Math.multiplyExact((long)center.x()-requestedChunks,16),Math.multiplyExact((long)center.z()-requestedChunks,16),
                Math.multiplyExact((long)center.x()+requestedChunks+1,16),Math.multiplyExact((long)center.z()+requestedChunks+1,16)).roundPages().expand(Math.multiplyExact((long)guardRows,64));
        if(!source.ownedBounds.contains(bounds))throw new Refusal("Native ownership escapes prepared source slab");
        if(source.sampledColumns()>limits.maxSourceColumns||source.sourceBytes()>limits.maxSourceBytes||bounds.columns()>limits.maxPlanColumns)
            throw new Refusal("Native request exceeds explicit source/plan column limits");
        long coverage=coverageBytes(bounds);
        if(coverage>limits.maxCoverageBytes)throw new Refusal("Native coverage index exceeds explicit byte limit");
        captureIdentity=capture==null?NO_CAPTURE:capture.contentIdentity();changed=validateCapture();
        ArrayList<Page> built=new ArrayList<>();long total=0,pieceTotal=0,completedTiles=0;
        for(long px=bounds.minX;px<bounds.maxXExclusive;px+=64)for(long pz=bounds.minZ;pz<bounds.maxZExclusive;pz+=64){
            long low=Long.MAX_VALUE,high=Long.MIN_VALUE;
            for(long z=pz;z<pz+64;z++)for(long x=px;x<px+64;x++){
                long h=source.surfaceY(x,z);low=Math.min(low,sourceFaceLow(x,z));high=Math.max(high,h);
            }
            if(changed&&intersectsEditXZ(px,pz,px+64,pz+64)){
                low=Math.min(low,capture.editedCube().minBlockY()-1);high=Math.max(high,capture.editedCube().minBlockY()+16);
            }
            long firstY=Math.floorDiv(low,64),lastY=Math.floorDiv(high,64),slots=Math.addExact(Math.subtractExact(lastY,firstY),1);
            if(slots>limits.maxPages||slots>Integer.MAX_VALUE-8L)throw new Refusal("Signed cliff-page scratch exceeds explicit page limit");
            long[] counts=new long[(int)slots],members=new long[(int)slots];long[] subtotal={0};
            long[][] extrema=new long[6][(int)slots];for(int axis=0;axis<6;axis++)Arrays.fill(extrema[axis],axis<3?Long.MAX_VALUE:Long.MIN_VALUE);
            SpanConsumer counter=(x,z,y0,y1,d,m)->{
                for(long py=Math.floorDiv(y0,64);py<=Math.floorDiv(y1-1,64);py++){
                    long lo=Math.max(y0,Math.multiplyExact(py,64)),hi=Math.min(y1,Math.multiplyExact(py+1,64)),n=hi-lo;
                    int i=Math.toIntExact(py-firstY);counts[i]=Math.addExact(counts[i],n);subtotal[0]=Math.addExact(subtotal[0],n);
                    long vx0=d==Direction.EAST?x+1:x,vx1=d==Direction.WEST?x:x+1;
                    long vy0=d==Direction.UP?lo+1:lo,vy1=d==Direction.DOWN?hi-1:hi;
                    long vz0=d==Direction.SOUTH?z+1:z,vz1=d==Direction.NORTH?z:z+1;
                    extrema[0][i]=Math.min(extrema[0][i],vx0);extrema[1][i]=Math.min(extrema[1][i],vy0);extrema[2][i]=Math.min(extrema[2][i],vz0);
                    extrema[3][i]=Math.max(extrema[3][i],vx1);extrema[4][i]=Math.max(extrema[4][i],vy1);extrema[5][i]=Math.max(extrema[5][i],vz1);
                    int cx=Math.floorMod(x,64)/16,cz=Math.floorMod(z,64)/16;
                    for(long section=Math.floorDiv(lo,16);section<=Math.floorDiv(hi-1,16);section++){
                        int cy=Math.floorMod(section,4);members[i]|=1L<<((cy*4+cz)*4+cx);
                    }
                    if(subtotal[0]>limits.maxQuads||Math.multiplyExact(subtotal[0],232)>limits.maxGeometryBytes)throw new Refusal("Native geometry exceeds explicit quad/byte limits");
                }
            };
            for(long z=pz;z<pz+64;z++)for(long x=px;x<px+64;x++)forColumnSpans(x,z,counter);
            total=Math.addExact(total,subtotal[0]);
            if(total>limits.maxQuads||Math.multiplyExact(total,232)>limits.maxGeometryBytes)throw new Refusal("Requested native geometry exceeds explicit quad/byte limits; no radius reduction");
            String sourcePageIdentity=source.horizontalPageIdentity(px,pz);
            for(int i=0;i<counts.length;i++)if(counts[i]>0){
                if((long)built.size()+1>limits.maxPages)throw new Refusal("Native nonempty pages exceed explicit page limit");
                NativePageKey key=new NativePageKey(Math.floorDiv(px,64),firstY+i,Math.floorDiv(pz,64));int n=pieceCount(counts[i]);pieceTotal=Math.addExact(pieceTotal,n);
                if(pieceTotal>limits.maxPieces)throw new Refusal("Native bounded mesh pieces exceed explicit piece limit");
                FaceBounds faces=new FaceBounds(extrema[0][i],extrema[1][i],extrema[2][i],extrema[3][i],extrema[4][i],extrema[5][i]);
                String pageId=hash(DOMAIN+"\npage\n"+source.identity+"\n"+sourcePageIdentity+"\n"+key+"\n"+(editAffects(key)?captureIdentity:NO_CAPTURE)+"\n"+faces+"\n"+members[i]);
                built.add(new Page(key,counts[i],n,pageId,faces,members[i]));
            }
            // Count only the complete census, horizontal hash and all signed-page records.
            completedTiles++;if(progress!=null)progress.completed(completedTiles);
        }
        built.sort(Comparator.comparing(Page::key));pages=List.copyOf(built);TreeMap<NativePageKey,Page> index=new TreeMap<>();for(Page p:pages)index.put(p.key,p);
        pageIndex=Collections.unmodifiableMap(index);quads=total;pieces=pieceTotal;
        if(coverageBytes()>limits.maxCoverageBytes)throw new Refusal("Native completed-page coverage exceeds explicit byte limit");
        identity=hash(DOMAIN+"\nplan\n"+source.identity+"\n"+source.sampleIdentity+"\n"+captureIdentity+"\n"+center+"\n"+requestedChunks+"\n"+guardRows+"\n"+bounds);
    }
    public static EarthDistanceSurfacePlan build(Source source,EarthReplacementCapture capture,CubePos center,int requestedChunks,int guardRows,Limits limits){
        return build(source,capture,center,requestedChunks,guardRows,limits,null);
    }
    public static EarthDistanceSurfacePlan build(Source source,EarthReplacementCapture capture,CubePos center,int requestedChunks,int guardRows,Limits limits,CompletedWork progress){
        try{return new EarthDistanceSurfacePlan(source,capture,center,requestedChunks,guardRows,limits,progress);}
        catch(ArithmeticException e){throw new Refusal("Native plan arithmetic overflow; no distance reduction",e);}
    }
    private boolean validateCapture(){
        if(capture==null)return false;
        if(!source.sampledBounds.contains(new Bounds(capture.minX(),capture.minZ(),capture.maxXExclusive(),capture.maxZExclusive()))
                ||capture.minY()<source.minY||capture.maxYExclusive()>source.maxY)throw new Refusal("Exact edit capture escapes prepared source support");
        boolean different=false;
        for(long y=capture.minY();y<capture.maxYExclusive();y++)for(long z=capture.minZ();z<capture.maxZExclusive();z++)for(long x=capture.minX();x<capture.maxXExclusive();x++){
            int actual=capture.materialAt(x,y,z),expected=source.materialAt(x,y,z);
            if(actual!=expected){if(!inEditedCube(x,y,z))throw new Refusal("Read20 capture changes source outside its single edited cube");different=true;}
        }
        return different;
    }
    private boolean inEditedCube(long x,long y,long z){
        if(capture==null)return false;CubePos p=capture.editedCube();return x>=p.minBlockX()&&x<p.minBlockX()+16&&y>=p.minBlockY()&&y<p.minBlockY()+16&&z>=p.minBlockZ()&&z<p.minBlockZ()+16;
    }
    private boolean editColumn(long x,long z){return changed&&intersectsEditXZ(x,z,x+1,z+1);}
    private boolean intersectsEditXZ(long x0,long z0,long x1,long z1){
        CubePos p=capture.editedCube();return x0<p.minBlockX()+17&&x1>p.minBlockX()-1&&z0<p.minBlockZ()+17&&z1>p.minBlockZ()-1;
    }
    private boolean editAffects(NativePageKey key){return changed&&intersectsEditXZ(key.minX(),key.minZ(),key.maxXExclusive(),key.maxZExclusive())
            &&key.minY()<capture.editedCube().minBlockY()+17&&key.maxYExclusive()>capture.editedCube().minBlockY()-1;}
    public int materialAt(long x,long y,long z){return inEditedCube(x,y,z)?capture.materialAt(x,y,z):source.materialAt(x,y,z);}
    long sourceFaceLow(long x,long z){
        long h=source.surfaceY(x,z),n=Math.min(Math.min(source.surfaceY(x-1,z),source.surfaceY(x+1,z)),Math.min(source.surfaceY(x,z-1),source.surfaceY(x,z+1)));
        return Math.min(h,n+1);
    }
    /** All exposure is computed from unchanged source plus the exact edited16 cube, including cave ceilings. */
    void forColumnSpans(long x,long z,SpanConsumer out){
        if(!bounds.contains(x,z))throw new Refusal("Face column outside native ownership");
        long h=source.surfaceY(x,z);boolean edited=editColumn(x,z);
        baselineSpan(x,z,h,h+1,Direction.UP,edited,out);
        baselineSpan(x,z,source.surfaceY(x-1,z)+1,h+1,Direction.WEST,edited,out);
        baselineSpan(x,z,source.surfaceY(x+1,z)+1,h+1,Direction.EAST,edited,out);
        baselineSpan(x,z,source.surfaceY(x,z-1)+1,h+1,Direction.NORTH,edited,out);
        baselineSpan(x,z,source.surfaceY(x,z+1)+1,h+1,Direction.SOUTH,edited,out);
        if(edited){
            long y0=capture.editedCube().minBlockY()-1,y1=capture.editedCube().minBlockY()+17;
            for(long y=y0;y<y1;y++){
                int m=materialAt(x,y,z);if(m==0)continue;
                for(Direction d:DIRECTIONS)if(materialAt(x+d.dx,y+d.dy,z+d.dz)==0)out.accept(x,z,y,y+1,d,m);
            }
        }
    }
    private void baselineSpan(long x,long z,long lo,long hi,Direction d,boolean edited,SpanConsumer out){
        if(lo>=hi)return;
        if(edited){long cut0=capture.editedCube().minBlockY()-1,cut1=capture.editedCube().minBlockY()+17;
            emitSourceSpan(x,z,lo,Math.min(hi,cut0),d,out);emitSourceSpan(x,z,Math.max(lo,cut1),hi,d,out);
        }else emitSourceSpan(x,z,lo,hi,d,out);
    }
    private void emitSourceSpan(long x,long z,long lo,long hi,Direction d,SpanConsumer out){
        if(lo>=hi)return;long h=source.surfaceY(x,z);
        long deepEnd=Math.min(hi,h-3);if(lo<deepEnd)out.accept(x,z,lo,deepEnd,d,EarthReplacementCapture.STONE);
        long subStart=Math.max(lo,h-3),subEnd=Math.min(hi,h);if(subStart<subEnd)out.accept(x,z,subStart,subEnd,d,source.materialAt(x,h-1,z));
        if(lo<=h&&hi>h)out.accept(x,z,h,h+1,d,source.materialAt(x,h,z));
    }
    /** Deterministic z, x, span/direction, ascending y order; only faces owned by this signed page. */
    public void forEachFace(NativePageKey key,FaceConsumer consumer){
        Objects.requireNonNull(consumer);if(!pageIndex.containsKey(key))throw new Refusal("Page absent from this geometry plan");
        SpanConsumer emit=(x,z,lo,hi,d,m)->{long end=Math.min(hi,key.maxYExclusive());for(long y=Math.max(lo,key.minY());y<end;y++)consumer.accept(x,y,z,d,m);};
        for(long z=key.minZ();z<key.maxZExclusive();z++)for(long x=key.minX();x<key.maxXExclusive();x++)forColumnSpans(x,z,emit);
    }
    /** Sorted, non-overlapping exact solid intervals; only the edited column can split the heightfield. */
    void forColumnSolidSpans(long x,long z,SolidSpanConsumer out){
        if(!bounds.contains(x,z))throw new Refusal("Solid column outside native ownership");
        long high=source.surfaceY(x,z)+1;
        if(capture==null||!changed||x<capture.editedCube().minBlockX()||x>=capture.editedCube().minBlockX()+16
                ||z<capture.editedCube().minBlockZ()||z>=capture.editedCube().minBlockZ()+16){out.accept(source.minY,high);return;}
        long edit0=capture.editedCube().minBlockY(),edit1=edit0+16;
        if(source.minY<Math.min(high,edit0))out.accept(source.minY,Math.min(high,edit0));
        long start=Long.MIN_VALUE;
        for(long y=edit0;y<edit1;y++){
            if(capture.materialAt(x,y,z)!=0){if(start==Long.MIN_VALUE)start=y;}
            else if(start!=Long.MIN_VALUE){out.accept(start,y);start=Long.MIN_VALUE;}
        }
        if(start!=Long.MIN_VALUE)out.accept(start,edit1);
        if(edit1<high)out.accept(edit1,high);
    }
    static long coverageBytes(Bounds bounds){
        long stride=Math.addExact(bounds.width(),1),rows=Math.addExact(bounds.depth(),1);
        long entries=Math.multiplyExact(stride,rows);if(entries>Integer.MAX_VALUE-8L)throw new Refusal("Coverage index exceeds Java array range");
        // Two long summed-area arrays, plus a conservative per-page/key lookup allowance.
        return Math.addExact(Math.multiplyExact(entries,16),512);
    }
    public Source source(){return source;}public EarthReplacementCapture capture(){return capture;}public CubePos center(){return center;}
    public Bounds bounds(){return bounds;}public Limits limits(){return limits;}public int requestedChunks(){return requestedChunks;}public int guardRows(){return guardRows;}
    public List<Page> pages(){return pages;}public Map<NativePageKey,Page> pageIndex(){return pageIndex;}public int pageCount(){return pages.size();}
    public long coverageBytes(){return Math.addExact(coverageBytes(bounds),Math.multiplyExact((long)pages.size(),192));}
    public long quads(){return quads;}public long pieces(){return pieces;}public long vertexBytes(){return Math.multiplyExact(quads,208);}
    public long conservativeIndexBytes(){return Math.multiplyExact(quads,24);}public long geometryBytes(){return Math.multiplyExact(quads,232);}
    public String identity(){return identity;}public String sourceIdentity(){return source.sourceIdentity;}public String captureIdentity(){return captureIdentity;}
    private static int material(EarthAlpineDefinition.Material m){return switch(m){case AIR->0;case STONE->1;case ANDESITE->3;case TUFF->4;case SNOW_BLOCK->5;};}
    private static int pieceCount(long quads){return Math.toIntExact(Math.addExact(quads,QUADS_PER_PIECE-1)/QUADS_PER_PIECE);}
    private static void validateDistance(int chunks){if(chunks<MIN_CHUNKS||chunks>MAX_CHUNKS)throw new Refusal("Native chunks outside Minecraft's2..32 setting domain");}
    private static long floorPage(long x){return Math.multiplyExact(Math.floorDiv(x,64),64);}
    private static long ceilPage(long x){long q=Math.floorDiv(x,64);return Math.multiplyExact(Math.floorMod(x,64)==0?q:Math.addExact(q,1),64);}
    private static String requireIdentity(String s){if(s==null||s.isBlank())throw new Refusal("Source/procedural/material identity required");return s;}
    static String hash(String s){return HexFormat.of().formatHex(digest().digest(s.getBytes(StandardCharsets.UTF_8)));}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static void putLong(byte[] b,int offset,long n){for(int i=7;i>=0;i--){b[offset+i]=(byte)n;n>>>=8;}}
}
