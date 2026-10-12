package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Pure far complement of exact source-certified native page coverage.
 * Interior cells are skipped before source reads; signed-Y boundary pages are
 * clipped only after unit perimeter refinement. This content carries no authority.
 */
public final class DhNativeDistanceTerrainData {
    public static final String POLICY = "native-distance-complement-v2:full-surface-interior-elision:unit-perimeter:dyadic-2cell-grading:signed-page-clip:source-strata:128m-fine-buckets:4096face-pieces";
    public record Limits(int maxCells,int maxFaces,int maxOwners,int maxPageFaces,long maxResultBytes) {
        public Limits {
            if(maxCells<=0||maxFaces<=0||maxOwners<=0||maxPageFaces<=0||maxPageFaces>4096||maxResultBytes<=0)
                throw new IllegalArgumentException("Explicit positive distance-complement limits required");
        }
    }
    public static final class BudgetExceeded extends IllegalArgumentException {
        public BudgetExceeded(String reason){super(reason);}
    }
    private static final int CELL_SIZE=16;
    private record SourceFace(DhTerrainGeometry.Face face, int tileSize) { }
    private record Footprint(long x0, long z0, long x1, long z1) { }
    private record Leaf(long x, long z, int size) { }
    private record Cell(Leaf leaf, long top, int argb, SceneSurfaceSampler.ColumnMaterials materials) { }
    private record Edge(long start, long end, Cell cell) { }
    private static final class EdgeLine {
        // Negative side means west/north of the plane; positive means east/south.
        final List<Edge> negative = new ArrayList<>(), positive = new ArrayList<>();
    }

    private final SceneSurfaceSampler sampler;
    private final io.earthplatform.minecraft.visual.SourceSurfaceCoverage coverage;
    private final Limits limits;
    private final DhLodLayout layout;
    private final String identity;
    private final boolean footprintCoverage, stratifiedSides;
    private final long originX, originZ;

    /** Pure content only. Runtime callers must retain the authenticated owner publication separately. */
    public DhNativeDistanceTerrainData(SceneSurfaceSampler sampler,long centerX,long centerZ,DhLodLayout layout,
            io.earthplatform.minecraft.visual.SourceSurfaceCoverage coverage,Limits limits) {
        this.coverage=Objects.requireNonNull(coverage);this.limits=Objects.requireNonNull(limits);
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.layout = Objects.requireNonNull(layout, "layout");
        identity = sampler.identity();footprintCoverage=sampler.supportsFootprintCoverage();stratifiedSides=sampler.supportsStratifiedSides();
        if (layout.requiresFootprintCoverage() && !footprintCoverage)
            throw new IllegalArgumentException("Long-distance summaries require complete footprint coverage");
        if (identity == null || identity.isBlank()) throw new IllegalArgumentException("Source/appearance identity required");
        originX = Math.subtractExact(layout.normalizeCenter(centerX), layout.radiusBlocks());
        originZ = Math.subtractExact(layout.normalizeCenter(centerZ), layout.radiusBlocks());
    }

    public DhAdaptiveTerrainData.Batch compose(DhTerrainGeometry.Volume ownedReplacement,
            DhTerrainGeometry.Volume replacementRead,Runnable cancellationCheck) {
        Objects.requireNonNull(cancellationCheck).run();
        var replacements=ownedReplacement==null?List.<DhTerrainGeometry.Volume>of():List.of(ownedReplacement);
        if(replacementRead!=null&&(replacementRead.maxXExclusive()-replacementRead.minX()>20
                ||replacementRead.maxYExclusive()-replacementRead.minY()>20||replacementRead.maxZExclusive()-replacementRead.minZ()>20))
            throw new BudgetExceeded("Only the exact bounded Earth read20 refinement is permitted");
        var leaves=partitionLeaves(replacementRead,cancellationCheck);
        requireIdentity();
        var cells = new ArrayList<Cell>(leaves.size());
        int unknown = 0, refined = 0, centerSamples=0, footprintRejected=0;
        for (Leaf leaf : leaves) {
            cancellationCheck.run();
            if (leaf.size < CELL_SIZE) refined++;
            long x = leaf.x + leaf.size / 2, z = leaf.z + leaf.size / 2;
            // size/2 is zero for a metre leaf: sample the exact native block.
            boolean queried=false;
            try {
                if(footprintCoverage&&!sampler.knownFootprint(leaf.x,leaf.z,leaf.x+leaf.size,leaf.z+leaf.size)){unknown++;footprintRejected++;continue;}
                queried=true;centerSamples++;
                long y = sampler.surfaceY(x, z);
                if (y < DhTerrainGeometry.MIN_Y || y >= DhTerrainGeometry.MAX_Y_EXCLUSIVE)
                    throw new IllegalArgumentException("Source exceeds native Earth envelope");
                int argb = sampler.argb(x, z, y);
                if ((argb >>> 24) != 255) throw new IllegalArgumentException("Opaque far surface required");
                var materials=stratifiedSides?sampler.columnMaterials(x,z,y):null;
                if(stratifiedSides&&(materials==null||materials.top().argb()!=argb))throw new IllegalStateException("Pinned top/side material contract changed");
                cells.add(new Cell(leaf, y + 1, argb,materials));
            } catch (MissingTerrainException absent) {
                unknown++;if(!queried)footprintRejected++;
            }
        }
        requireIdentity();
        if(unknown!=0) throw new IllegalArgumentException("Every distance-complement footprint must be source-supported");

        var faces = new ArrayList<SourceFace>();
        var xEdges = new TreeMap<Long, EdgeLine>();
        var zEdges = new TreeMap<Long, EdgeLine>();
        for (Cell cell : cells) {
            cancellationCheck.run();
            Leaf leaf = cell.leaf;
            append(faces, new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.UP,
                    leaf.x, cell.top, leaf.z, leaf.size, leaf.size, cell.argb,cell.materials==null?0:cell.materials.top().dhMaterial()), leaf.size);
            addEdge(xEdges, leaf.x, new Edge(leaf.z, leaf.z + leaf.size, cell), false);
            addEdge(xEdges, leaf.x + leaf.size, new Edge(leaf.z, leaf.z + leaf.size, cell), true);
            addEdge(zEdges, leaf.z, new Edge(leaf.x, leaf.x + leaf.size, cell), false);
            addEdge(zEdges, leaf.z + leaf.size, new Edge(leaf.x, leaf.x + leaf.size, cell), true);
        }
        int transitionFaces = joinEdges(xEdges, true, faces, cancellationCheck) + joinEdges(zEdges, false, faces, cancellationCheck);
        var tiles = new LinkedHashMap<DhGeographicLodData.TileKey, List<DhTerrainGeometry.PageFace>>();
        int count = 0, changed = 0;
        for (var summary : faces) {
            cancellationCheck.run();
            var source = summary.face;
            var fragments = new ArrayList<DhTerrainGeometry.Face>();
            for(var piece:DhTerrainGeometry.clip(source,List.of(),replacements,DhTerrainGeometry.MAX_PIECES))
                fragments.addAll(clipCoverage(piece));
            if (fragments.size() != 1 || !fragments.getFirst().equals(source)) changed++;
            for (var face : fragments) {
                long tileX = Math.floorDiv(face.x() - (face.direction() == DhTerrainGeometry.Direction.EAST ? 1 : 0), summary.tileSize) * summary.tileSize;
                long tileZ = Math.floorDiv(face.z() - (face.direction() == DhTerrainGeometry.Direction.SOUTH ? 1 : 0), summary.tileSize) * summary.tileSize;
                for (var page : DhTerrainGeometry.splitPages(face, tileX, tileZ)) {
                    if (++count > limits.maxFaces()) throw new BudgetExceeded("Distance-complement face limit exceeded");
                    long bytes=Math.addExact(4096L,Math.multiplyExact(128L,count));
                    if(bytes>limits.maxResultBytes())throw new BudgetExceeded("Distance-complement prepared bytes exceeded");
                    var key=pieceKey(tileX,tileZ,page.pageY2048(),tiles);
                    var list=tiles.computeIfAbsent(key,ignored->new ArrayList<>());
                    if(tiles.size()>limits.maxOwners())throw new BudgetExceeded("Distance-complement physical owner limit exceeded");
                    list.add(page);
                }
            }
        }
        return new DhAdaptiveTerrainData.Batch(tiles, count, changed, leaves.size(), cells.size(), unknown,
                refined, faces.size(), transitionFaces,centerSamples,footprintRejected,footprintCoverage?cells.size():0);
    }

    private List<Leaf> partitionLeaves(DhTerrainGeometry.Volume read,Runnable check) {
        var leaves=new ArrayList<Leaf>();
        long centerX=originX+layout.radiusBlocks(),centerZ=originZ+layout.radiusBlocks();
        int inner=0;
        for(var ring:layout.rings()) {
            int radius=ring.radiusBlocks(),size=ring.cellSize();
            for(long z=centerZ-radius;z<centerZ+radius;z+=size)for(long x=centerX-radius;x<centerX+radius;x+=size){
                check.run();
                if(inner>0&&x>=centerX-inner&&x<centerX+inner&&z>=centerZ-inner&&z<centerZ+inner)continue;
                partition(x,z,size,read,leaves,check);
            }
            inner=radius;
        }
        return leaves;
    }
    private void partition(long x,long z,int size,DhTerrainGeometry.Volume read,List<Leaf> leaves,Runnable check) {
        check.run();
        var expanded=coverage.classification(Math.subtractExact(x,1),Math.subtractExact(z,1),Math.addExact(x,size+1L),Math.addExact(z,size+1L));
        if(expanded==io.earthplatform.minecraft.visual.SourceSurfaceCoverage.Classification.FULL)return;
        boolean refine=read!=null&&x<read.maxXExclusive()&&x+size>read.minX()&&z<read.maxZExclusive()&&z+size>read.minZ();
        long halo=Math.multiplyExact(size,2L);
        refine|=coverage.classification(Math.subtractExact(x,halo),Math.subtractExact(z,halo),Math.addExact(x,size+halo),Math.addExact(z,size+halo))
                !=io.earthplatform.minecraft.visual.SourceSurfaceCoverage.Classification.UNCOVERED;
        if(size>1&&refine){int half=size/2;partition(x,z,half,read,leaves,check);partition(x+half,z,half,read,leaves,check);
            partition(x,z+half,half,read,leaves,check);partition(x+half,z+half,half,read,leaves,check);}
        else {if(leaves.size()==limits.maxCells())throw new BudgetExceeded("Distance-complement cell budget exceeded before sampling");leaves.add(new Leaf(x,z,size));}
    }
    private DhGeographicLodData.TileKey pieceKey(long x,long z,int y,Map<DhGeographicLodData.TileKey,List<DhTerrainGeometry.PageFace>> tiles){
        String identity=sha("distance-base-piece-content-key-v2\n"+POLICY+"\n"+this.identity+"\n"+layout.identity()+"\n"+x+","+y+","+z);
        // Existing exact TileKey piece domain remains unchanged; overflow is a real refusal.
        for(int piece=0;piece<8;piece++){
            var key=new DhGeographicLodData.TileKey(x,z,y,identity,piece);
            var faces=tiles.get(key);if(faces==null||faces.size()<limits.maxPageFaces())return key;
        }
        throw new BudgetExceeded("Distance-complement bucket exceeds eight physical pieces");
    }
    static String sha(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
    /** Unit perimeter refinement makes vertical ownership a bounded interval subtraction. */
    public List<DhTerrainGeometry.Face> clipCoverage(DhTerrainGeometry.Face face){
        var direction=face.direction();
        long x=face.x()-(direction==DhTerrainGeometry.Direction.EAST?1:0);
        long z=face.z()-(direction==DhTerrainGeometry.Direction.SOUTH?1:0);
        long x1=x+(direction==DhTerrainGeometry.Direction.WEST||direction==DhTerrainGeometry.Direction.EAST?1:face.width());
        long z1=z+(direction==DhTerrainGeometry.Direction.UP||direction==DhTerrainGeometry.Direction.DOWN?face.height():
                direction==DhTerrainGeometry.Direction.WEST||direction==DhTerrainGeometry.Direction.EAST?face.width():1);
        if(coverage.classification(x,z,x1,z1)==io.earthplatform.minecraft.visual.SourceSurfaceCoverage.Classification.UNCOVERED)return List.of(face);
        if(direction==DhTerrainGeometry.Direction.UP||direction==DhTerrainGeometry.Direction.DOWN){
            if(face.width()!=1||face.height()!=1)throw new IllegalStateException("Native boundary horizontal face was not unit refined");
            return coverage.covers(x,face.y()-(direction==DhTerrainGeometry.Direction.UP?1:0),z)?List.of():List.of(face);
        }
        if(face.width()!=1)throw new IllegalStateException("Native boundary side face was not unit refined");
        long cursor=face.y(),end=Math.addExact(cursor,face.height());var result=new ArrayList<DhTerrainGeometry.Face>();
        for(var span:coverage.solidSpans(x,z)){
            if(span.maxYExclusive()<=cursor)continue;if(span.minY()>=end)break;
            if(span.minY()>cursor)result.add(new DhTerrainGeometry.Face(direction,face.x(),cursor,face.z(),1,Math.toIntExact(Math.min(end,span.minY())-cursor),face.argb(),face.material()));
            cursor=Math.max(cursor,span.maxYExclusive());if(cursor>=end)break;
        }
        if(cursor<end)result.add(new DhTerrainGeometry.Face(direction,face.x(),cursor,face.z(),1,Math.toIntExact(end-cursor),face.argb(),face.material()));
        return List.copyOf(result);
    }
    private static void addEdge(Map<Long, EdgeLine> lines, long plane, Edge edge, boolean negativeSide) {
        EdgeLine line = lines.computeIfAbsent(plane, ignored -> new EdgeLine());
        (negativeSide ? line.negative : line.positive).add(edge);
    }

    /** Match opposite intervals, including every fine interval along a coarse edge. */
    private int joinEdges(Map<Long, EdgeLine> lines, boolean xAxis, List<SourceFace> faces, Runnable cancellationCheck) {
        int transitions = 0;
        for (var entry : lines.entrySet()) {
            cancellationCheck.run();
            EdgeLine line = entry.getValue();
            line.negative.sort(Comparator.comparingLong(Edge::start));
            line.positive.sort(Comparator.comparingLong(Edge::start));
            int i = 0, j = 0;
            while (i < line.negative.size() && j < line.positive.size()) {
                Edge a = line.negative.get(i), b = line.positive.get(j);
                long start = Math.max(a.start, b.start), end = Math.min(a.end, b.end);
                if (start < end && a.cell.top != b.cell.top) {
                    boolean positiveNormal = a.cell.top > b.cell.top;
                    Cell high = positiveNormal ? a.cell : b.cell;
                    var direction = xAxis
                            ? (positiveNormal ? DhTerrainGeometry.Direction.EAST : DhTerrainGeometry.Direction.WEST)
                            : (positiveNormal ? DhTerrainGeometry.Direction.SOUTH : DhTerrainGeometry.Direction.NORTH);
                    appendWall(faces,direction,xAxis?entry.getKey():start,xAxis?start:entry.getKey(),
                            Math.toIntExact(end-start),Math.min(a.cell.top,b.cell.top),high,
                            Math.min(a.cell.leaf.size,b.cell.leaf.size),xAxis?0.72f:0.85f);
                    if (a.cell.leaf.size != b.cell.leaf.size) transitions++;
                }
                if (a.end <= b.end) i++;
                if (b.end <= a.end) j++;
            }
        }
        return transitions;
    }

    private void appendWall(List<SourceFace> faces,DhTerrainGeometry.Direction direction,long x,long z,
            int width,long bottom,Cell high,int leafSize,float shade) {
        if(high.materials==null){
            append(faces,new DhTerrainGeometry.Face(direction,x,bottom,z,width,Math.toIntExact(high.top-bottom),shade(high.argb,shade)),leafSize);
            return;
        }
        // Each visible native voxel side uses the solid column's exact vertical palette:
        // top block at H, three subsurface blocks H-3..H-1, deep stone below H-3.
        long topBlock=high.top-1,deepEnd=topBlock-high.materials.subsurfaceDepth();
        long y=bottom;
        while(y<high.top){
            var material=y<deepEnd?high.materials.deep():y<topBlock?high.materials.subsurface():high.materials.top();
            long end=y<deepEnd?Math.min(high.top,deepEnd):y<topBlock?Math.min(high.top,topBlock):high.top;
            // Exact compact representation: erase only a boundary whose full material
            // (opaque colour AND pinned DH category) is identical on both sides.
            while(end<high.top){
                var next=end<topBlock?high.materials.subsurface():high.materials.top();
                if(!material.equals(next))break;
                end=end<topBlock?topBlock:high.top;
            }
            append(faces,new DhTerrainGeometry.Face(direction,x,y,z,width,Math.toIntExact(end-y),DhTerrainGeometry.stratifiedFaceArgb(direction,material.argb()),material.dhMaterial()),leafSize);
            y=end;
        }
    }

    private void append(List<SourceFace> faces, DhTerrainGeometry.Face face, int leafSize) {
        if (faces.size() == limits.maxFaces()) throw new BudgetExceeded("Source face budget exceeded");
        // Every leaf and ownership grid is globally dyadic. At a tier seam,
        // matched intervals inherit the finer side's grouping. A positive
        // normal belongs to the solid-side tile even at its endpoint.
        faces.add(new SourceFace(face, leafSize<16?128:layout.tileSizeForCell(leafSize)));
    }

    private static int shade(int color, float factor) {
        return 0xff000000 | Math.round(((color >>> 16) & 255) * factor) << 16
                | Math.round(((color >>> 8) & 255) * factor) << 8 | Math.round((color & 255) * factor);
    }

    private void requireIdentity() {
        if (!identity.equals(sampler.identity())||footprintCoverage!=sampler.supportsFootprintCoverage()||stratifiedSides!=sampler.supportsStratifiedSides()) throw new IllegalStateException("Source identity changed during adaptive summary");
    }

    public String identity() { return identity; }
    public DhLodLayout layout() { return layout; }
    public String layoutIdentity() { return layout.identity(); }
    public long originX() { return originX; }
    public long originZ() { return originZ; }
}
