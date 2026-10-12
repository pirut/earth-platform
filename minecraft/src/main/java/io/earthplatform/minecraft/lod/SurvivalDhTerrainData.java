package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.survival.render.SurvivalCompleteOverlay;
import io.earthplatform.minecraft.survival.render.SurvivalDhCoverage;
import io.earthplatform.minecraft.survival.render.SurvivalDhMaterialPalette;
import io.earthplatform.minecraft.survival.render.SurvivalDhMaterialPalette.Material;
import io.earthplatform.minecraft.survival.source.MarinVisualCubeSource;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.*;

/**
 * Pure revision/source-matched Marin complement. No owner capability, GL, installed helper,
 * history, resource lease or mutable registry enters this class's working input. Leaf columns
 * summarize the exact source at their representative centre; unit native/overlay boundaries
 * use exact metre columns. Leaves are explicitly opaque colored LOD proxies, never evidence
 * of opaque native ground. Unknown source refuses the whole result, without clipping extent.
 */
public final class SurvivalDhTerrainData {
    public static final String POLICY="survival-dh-solid-runs-039-v1;exact-overlay-air;"
            + "unit-native-and-overlay-collar;xyz-clip;full-source-support;no-skirts;no-bottom-cap";
    private static final int MAX_COLUMN_RUNS=512,MAX_PARTITION_VISITS=1_048_576;
    private static final long MAX_SPATIAL_CHECKS=16_777_216L,MAX_RUN_OPERATIONS=16_777_216L;
    public record Limits(int maxCells,int maxFaces,int maxOwners,int maxPageFaces,
                         long maxResultBytes,long maxOverrideReads,int maxRuns) {
        public Limits {
            if(maxCells<1||maxCells>DhNativeDistanceBudget.MAX_CELLS
                    ||maxFaces<1||maxFaces>DhNativeDistanceBudget.MAX_FACES
                    ||maxOwners<1||maxOwners>DhNativeDistanceBudget.MAX_OWNERS
                    ||maxPageFaces<1||maxPageFaces>DhNativeDistanceBudget.MAX_PAGE_FACES
                    ||maxResultBytes<262144||maxResultBytes>DhNativeDistanceBudget.MAX_RESULT_BYTES
                    ||maxOverrideReads<1||maxOverrideReads>4_194_304L||maxRuns<1||maxRuns>262144)
                throw new IllegalArgumentException("Explicit survival DH limits cannot exceed shared existing caps");
        }
        public static Limits standard(){return new Limits(65536,131072,256,4096,20L<<20,4_194_304,262144);}
    }
    /** Legacy coordinate validation is reused without widening its STONE/SNOW material guard. */
    public record PageFace(DhTerrainGeometry.PageFace geometry,int category) {
        public PageFace {
            Objects.requireNonNull(geometry);
            if(geometry.material()!=0)throw new IllegalArgumentException("Survival material is explicit, never legacy fallback");
            new Material(geometry.argb(),category);
            if(category==14)throw new IllegalArgumentException("AIR has no DH face");
        }
    }
    /** Private fixed-capacity primitive representation. Arrays never escape or get cloned. */
    static final class FaceSlabs {
        static final int FACES_PER_SLAB=256, FIELDS=9;
        static final long SLAB_BYTES=(long)FACES_PER_SLAB*FIELDS*Integer.BYTES+256;
        private static final Direction[] DIRECTIONS=Direction.values();
        private final int limit;
        private final int[][] slabs;
        private int size;
        private boolean frozen;
        FaceSlabs(int limit) {
            if(limit<1||limit>DhNativeDistanceBudget.MAX_PAGE_FACES)throw new IllegalArgumentException("Bounded face slab limit required");
            this.limit=limit;
            // At most16 references. The caller precharges its existing4096-byte tile metadata.
            slabs=new int[(limit+FACES_PER_SLAB-1)/FACES_PER_SLAB][];
        }
        int size(){return size;}
        void add(PageFace face,java.util.function.LongConsumer charge) {
            Objects.requireNonNull(face);Objects.requireNonNull(charge);
            if(frozen||size>=limit)throw new SurvivalRefusal("Frozen/full survival face slabs cannot mutate");
            var g=face.geometry();
            // Revalidation is cheap and prevents a future alternative producer bypassing invariants.
            new PageFace(new DhTerrainGeometry.PageFace(g.direction(),g.pageY2048(),g.x(),g.y(),g.z(),g.width(),g.height(),g.argb(),g.material()),face.category());
            int slab=size/FACES_PER_SLAB,at=(size%FACES_PER_SLAB)*FIELDS;
            if(slabs[slab]==null){charge.accept(SLAB_BYTES);slabs[slab]=new int[FACES_PER_SLAB*FIELDS];}
            int[] data=slabs[slab];
            data[at]=g.direction().ordinal();data[at+1]=g.pageY2048();data[at+2]=g.x();data[at+3]=g.y();data[at+4]=g.z();
            data[at+5]=g.width();data[at+6]=g.height();data[at+7]=g.argb();data[at+8]=face.category();size++;
        }
        void freeze(){if(frozen)throw new SurvivalRefusal("Face slabs already transferred");frozen=true;}
        PageFace get(int index) {
            if(!frozen)throw new SurvivalRefusal("Untransferred face slabs have no read capability");
            Objects.checkIndex(index,size);int[] data=slabs[index/FACES_PER_SLAB];int at=(index%FACES_PER_SLAB)*FIELDS;
            int direction=data[at];if(direction<0||direction>=DIRECTIONS.length)throw new SurvivalRefusal("Invalid retained face direction");
            return new PageFace(new DhTerrainGeometry.PageFace(DIRECTIONS[direction],data[at+1],data[at+2],data[at+3],data[at+4],
                    data[at+5],data[at+6],data[at+7]),data[at+8]);
        }
    }
    public record Census(int cells,int refinedCells,int runs,int sourceFaces,int outputFaces,
                         int owners,long overrideReads,long runOperations,long logicalWorkingBytes) {}
    /**
     * One bounded result lease. Only detached individual scalar faces/keys can escape. A compiler
     * copying a tile must charge its own buffer before copying. This is not a physical completion.
     */
    public static final class Result implements AutoCloseable {
        private List<DhGeographicLodData.TileKey> keys;
        private Map<DhGeographicLodData.TileKey,FaceSlabs> tiles;
        private final NativeDistanceCpuBudget.Reservation charge;
        private final NativeDistanceCpuBudget budget;
        private final String identity,sourceIdentity,contentIdentity,coverageIdentity;
        private final Census census;
        private SurvivalDhCoverage provenance;
        private CompilerOwner compilerOwner;
        private boolean closed,closing;
        private Result(Work w,NativeDistanceCpuBudget.Reservation charge,NativeDistanceCpuBudget budget) {
            this.budget=Objects.requireNonNull(budget);
            var copy=new LinkedHashMap<DhGeographicLodData.TileKey,FaceSlabs>();
            w.tiles.forEach((key,faces)->{ faces.freeze(); copy.put(key,faces); });
            tiles=Map.copyOf(copy);keys=List.copyOf(copy.keySet());this.charge=charge;
            sourceIdentity=w.source.identity();contentIdentity=w.content.contentIdentity();coverageIdentity=w.coverage.identity();
            identity=sha(POLICY+"\n"+SurvivalDhLayout.IDENTITY+"\n"+sourceIdentity+"\n"+contentIdentity
                    +"\n"+coverageIdentity+"\n"+w.palette.identity()+"\n"+w.cx+","+w.cz+"\n"+w.limits);
            census=new Census(w.cells.size(),w.refined,w.runs,w.sourceFaces,w.outputFaces,keys.size(),w.overrideReads,w.runOperations,w.bytes);
            // Transfer frozen private slabs without copying their payload or retaining a mutable Work alias.
            provenance=w.coverage.retain();
            w.tiles.clear();
        }
        private void open(){if(closed||closing||compilerOwner!=null)throw new SurvivalRefusal("Survival DH result is closed or exclusively transferred");}
        public synchronized int tileCount(){open();return keys.size();}
        public synchronized DhGeographicLodData.TileKey tileKey(int tile){open();return keys.get(tile);}
        public synchronized int faceCount(int tile){open();return tiles.get(keys.get(tile)).size();}
        public synchronized PageFace face(int tile,int face){open();return tiles.get(keys.get(tile)).get(face);}
        public synchronized String identity(){open();return identity;}
        public synchronized String sourceIdentity(){open();return sourceIdentity;}
        public synchronized String contentIdentity(){open();return contentIdentity;}
        public synchronized String coverageIdentity(){open();return coverageIdentity;}
        public synchronized Census census(){open();return census;}
        /** Single exclusive transfer. The caller loses scalar access and close authority. */
        synchronized CompilerOwner transferToCompiler(NativeDistanceCpuBudget expectedBudget){
            open();if(budget!=expectedBudget)throw new SurvivalRefusal("DH compiler must share the exact result CPU budget");compilerOwner=new CompilerOwner();return compilerOwner;
        }
        final class CompilerOwner implements AutoCloseable {
            private boolean drained;
            private CompilerOwner(){}
            private void requireOwned(){if(drained||closed||closing||compilerOwner!=this)throw new SurvivalRefusal("Absent exact DH compiler transfer");}
            public int tileCount(){synchronized(Result.this){requireOwned();return keys.size();}}
            public DhGeographicLodData.TileKey tileKey(int tile){synchronized(Result.this){requireOwned();return keys.get(tile);}}
            public int faceCount(int tile){synchronized(Result.this){requireOwned();return tiles.get(keys.get(tile)).size();}}
            public PageFace face(int tile,int face){synchronized(Result.this){requireOwned();return tiles.get(keys.get(tile)).get(face);}}
            public SurvivalDhCoverage retainCoverage(){synchronized(Result.this){requireOwned();return provenance.retain();}}
            public String identity(){synchronized(Result.this){requireOwned();return identity;}}
            public boolean closeComplete(){synchronized(Result.this){return drained;}}
            @Override public void close(){synchronized(Result.this){if(drained)return;if(closed||compilerOwner!=this)throw new SurvivalRefusal("Absent exact DH compiler close authority");drain();drained=true;}}
        }
        private void drain(){closing=true;tiles=null;keys=null;if(provenance!=null){provenance.close();provenance=null;}charge.releaseAfterPhysicalDrain();closed=true;}
        @Override public synchronized void close(){if(closed)return;if(compilerOwner!=null)throw new SurvivalRefusal("Survival DH result was exclusively transferred");drain();}
    }
    private SurvivalDhTerrainData() {}
    public static Result build(MarinVisualCubeSource source,SurvivalDhMaterialPalette palette,
            SurvivalDhCoverage input,long centerX,long centerZ,Limits limits,
            NativeDistanceCpuBudget budget,Runnable cancellationCheck) {
        Objects.requireNonNull(source);Objects.requireNonNull(palette);Objects.requireNonNull(input);
        Objects.requireNonNull(limits);Objects.requireNonNull(budget);Objects.requireNonNull(cancellationCheck).run();
        SurvivalDhLayout.validate(source.definition(),centerX,centerZ);
        try(var coverage=input.retain();var content=coverage.retainContent();var materials=palette.retain()) {
            if(!source.identity().equals(coverage.sourceIdentity())||!source.identity().equals(content.sourceIdentity())
                    ||!content.contentIdentity().equals(coverage.contentIdentity())
                    ||!materials.paletteIdentity().equals(source.palette().identity()))
                throw new SurvivalRefusal("Survival DH source, registry, overlay and coverage differ");
            var b=coverage.bounds();
            int focusX=Math.toIntExact(Math.floorDiv(b.minX()+b.maxXExclusive()-16,32));
            int focusZ=Math.toIntExact(Math.floorDiv(b.minZ()+b.maxZExclusive()-16,32));
            if(centerX!=MarinSurvivalDefinition.snapCubeCenter(focusX)||centerZ!=MarinSurvivalDefinition.snapCubeCenter(focusZ))
                throw new SurvivalRefusal("Survival DH centre differs from exact native plan request");
            // At most two full20MiB generations fit the unchanged40MiB DH_RESULTS pool.
            var charge=budget.reserve(NativeDistanceCpuBudget.Pool.DH_RESULTS,limits.maxResultBytes());
            try {
                Work work=new Work(source,materials,coverage,content,centerX,centerZ,limits,cancellationCheck);
                work.build();cancellationCheck.run();return new Result(work,charge,budget);
            }catch(RuntimeException|Error failure){charge.releaseAfterPhysicalDrain();throw failure;}
        }
    }
    private record ColumnKey(int x,int z) {}
    private record Bucket(long x,long z,int y) {}
    static record Run(long minY,long maxY,Material material) {
        Run {if(minY>=maxY||minY<MIN_Y||maxY>MAX_Y_EXCLUSIVE||material.air())throw new IllegalArgumentException("Bounded nonempty solid run required");}
    }
    private record Leaf(long x,long z,int size) {}
    private record Cell(Leaf leaf,List<Run> runs) {}
    private record Edge(long start,long end,Cell cell) {}
    private static final class EdgeLine {final List<Edge> negative=new ArrayList<>(),positive=new ArrayList<>();}
    private static final class Work {
        final MarinVisualCubeSource source;
        final SurvivalDhMaterialPalette palette;
        final SurvivalDhCoverage coverage;
        final SurvivalCompleteOverlay.Content content;
        final long cx,cz;
        final Limits limits;
        final Runnable check;
        final Map<ColumnKey,List<CubePos>> overrides=new java.util.HashMap<>();
        final Map<Bucket,String> keyIdentities=new java.util.HashMap<>();
        final List<Cell> cells=new ArrayList<>();
        final Map<DhGeographicLodData.TileKey,FaceSlabs> tiles=new LinkedHashMap<>();
        long bytes=262144,overrideReads,spatialChecks,runOperations;
        int visits,refined,runs,sourceFaces,outputFaces;
        Work(MarinVisualCubeSource source,SurvivalDhMaterialPalette palette,SurvivalDhCoverage coverage,
                SurvivalCompleteOverlay.Content content,long x,long z,Limits limits,Runnable check) {
            this.source=source;this.palette=palette;this.coverage=coverage;this.content=content;
            cx=x;cz=z;this.limits=limits;this.check=check;
        }
        void claim(long count){bytes=Math.addExact(bytes,count);if(bytes>limits.maxResultBytes())throw new SurvivalRefusal("Survival DH shared working/result byte allowance exceeded: bytes="+bytes+", cells="+cells.size()+", runs="+runs+", sourceFaces="+sourceFaces+", outputFaces="+outputFaces+", owners="+tiles.size());}
        void runStep(){if(++runOperations>MAX_RUN_OPERATIONS)throw new SurvivalRefusal("DH run-comparison work cap exceeded");if((runOperations&1023)==0)check.run();}
        void build() {
            int count=content.overrideCount();
            if(count>65536)throw new SurvivalRefusal("Complete overlay index exceeds bounded DH input");
            claim(Math.multiplyExact(count,160L));
            for(int i=0;i<count;i++) {
                check.run();CubePos p=content.overridePosition(i);source.validate(p);
                overrides.computeIfAbsent(new ColumnKey(p.x(),p.z()),unused->new ArrayList<>()).add(p);
            }
            for(var column:overrides.values())column.sort(Comparator.comparingInt(CubePos::y));
            int inner=0;
            for(var ring:SurvivalDhLayout.RINGS) {
                for(long z=cz-ring.radius();z<cz+ring.radius();z+=ring.cellSize())
                    for(long x=cx-ring.radius();x<cx+ring.radius();x+=ring.cellSize()) {
                        if(inner>0&&x>=cx-inner&&x<cx+inner&&z>=cz-inner&&z<cz+inner)continue;
                        partition(x,z,ring.cellSize());
                    }
                inner=ring.radius();
            }
            var xs=new TreeMap<Long,EdgeLine>();var zs=new TreeMap<Long,EdgeLine>();
            for(Cell cell:cells) {
                check.run();Leaf leaf=cell.leaf;
                for(int i=0;i<cell.runs.size();i++) {
                    Run run=cell.runs.get(i);
                    if(i==cell.runs.size()-1||cell.runs.get(i+1).minY>run.maxY)
                        emit(new Face(Direction.UP,leaf.x,run.maxY,leaf.z,leaf.size,leaf.size,run.material.argb()),run.material.category(),leaf.size);
                    if(run.minY>MIN_Y&&(i==0||cell.runs.get(i-1).maxY<run.minY))
                        emit(new Face(Direction.DOWN,leaf.x,run.minY,leaf.z,leaf.size,leaf.size,run.material.argb()),run.material.category(),leaf.size);
                }
                edge(xs,leaf.x,new Edge(leaf.z,leaf.z+leaf.size,cell),false);
                edge(xs,leaf.x+leaf.size,new Edge(leaf.z,leaf.z+leaf.size,cell),true);
                edge(zs,leaf.z,new Edge(leaf.x,leaf.x+leaf.size,cell),false);
                edge(zs,leaf.z+leaf.size,new Edge(leaf.x,leaf.x+leaf.size,cell),true);
            }
            join(xs,true);join(zs,false);
        }
        void partition(long x,long z,int size) {
            check.run();if(++visits>MAX_PARTITION_VISITS)throw new SurvivalRefusal("DH partition work bound exceeded");
            if(coverage.classification(x-1,z-1,x+size+1,z+size+1)==SurvivalDhCoverage.Classification.FULL)return;
            long halo=2L*size;
            boolean refine=coverage.classification(x-halo,z-halo,x+size+halo,z+size+halo)!=SurvivalDhCoverage.Classification.UNCOVERED
                    ||nearOverride(x-halo,z-halo,x+size+halo,z+size+halo);
            if(size>1&&refine) {
                int h=size/2;partition(x,z,h);partition(x+h,z,h);partition(x,z+h,h);partition(x+h,z+h,h);return;
            }
            if(cells.size()>=limits.maxCells())throw new SurvivalRefusal("Survival DH horizontal cell cap exceeded before sampling");
            claim(384); // cell/list/map/edge indexes and canonicalization; run/face payload separately below.
            if(!source.definition().knownFootprint(x,z,x+size,z+size))throw new SurvivalRefusal("Unavailable full Marin DH source footprint");
            long sampleX=x+size/2,sampleZ=z+size/2;
            var column=column(sampleX,sampleZ);
            if((long)runs+column.size()>limits.maxRuns())throw new SurvivalRefusal("Survival DH retained run cap exceeded");
            claim(64L*column.size());runs+=column.size();if(size<16)refined++;
            cells.add(new Cell(new Leaf(x,z,size),column));
        }
        boolean nearOverride(long x0,long z0,long x1,long z1) {
            if(overrides.isEmpty())return false;
            for(long z=Math.floorDiv(z0,16);z<=Math.floorDiv(z1-1,16);z++)
                for(long x=Math.floorDiv(x0,16);x<=Math.floorDiv(x1-1,16);x++) {
                    if(++spatialChecks>MAX_SPATIAL_CHECKS)throw new SurvivalRefusal("DH sparse-index work cap exceeded");
                    if((spatialChecks&1023)==0)check.run();
                    if(overrides.containsKey(new ColumnKey(Math.toIntExact(x),Math.toIntExact(z))))return true;
                }
            return false;
        }
        List<Run> column(long x,long z) {
            var definition=source.definition();var ground=definition.column(x,z);
            if(ground.surfaceY()<MIN_Y||ground.surfaceY()>=MAX_Y_EXCLUSIVE)throw new SurvivalRefusal("Marin source exceeds signed DH envelope");
            var result=new ArrayList<Run>();long sub=Math.max(MIN_Y,ground.surfaceY()-3);
            if(sub>MIN_Y)appendRun(result,new Run(MIN_Y,sub,SurvivalDhMaterialPalette.material(ground.deep())));
            if(sub<ground.surfaceY())appendRun(result,new Run(sub,ground.surfaceY(),SurvivalDhMaterialPalette.material(ground.subsurface())));
            appendRun(result,new Run(ground.surfaceY(),ground.surfaceY()+1,SurvivalDhMaterialPalette.material(ground.top())));
            var features=new TreeMap<Long,Material>();
            for(var tree:definition.visualTreesIntersecting(x,z,x+1,z+1)) {
                check.run();
                for(long y=Math.max(ground.surfaceY()+1,tree.groundY()+1);y<tree.topY()+2;y++) {
                    Material material=SurvivalDhMaterialPalette.material(tree.stateAt(x,y,z));
                    if(material.air())continue;
                    Material old=features.get(y);if(old==null||old.category()!=3)features.put(y,material);
                }
            }
            for(var feature:features.entrySet())appendRun(result,new Run(feature.getKey(),feature.getKey()+1,feature.getValue()));
            var patches=overrides.get(new ColumnKey(CubePos.section(x),CubePos.section(z)));
            if(patches!=null)for(CubePos p:patches) {
                check.run();var replacement=new ArrayList<Run>();
                for(int y=0;y<16;y++) {
                    if(++overrideReads>limits.maxOverrideReads())throw new SurvivalRefusal("DH exact override scalar-read cap exceeded");
                    int index=CubePos.index((int)Math.floorMod(x,16),y,(int)Math.floorMod(z,16));
                    Material material=palette.state(content.overrideStateId(p,index));
                    if(!material.air())appendRun(replacement,new Run(p.minBlockY()+y,p.minBlockY()+y+1,material));
                }
                result=replace(result,p.minBlockY(),p.minBlockY()+16,replacement,this::runStep);
            }
            return List.copyOf(result);
        }
        void join(Map<Long,EdgeLine> lines,boolean xAxis) {
            for(var entry:lines.entrySet()) {
                check.run();EdgeLine line=entry.getValue();
                line.negative.sort(Comparator.comparingLong(Edge::start));line.positive.sort(Comparator.comparingLong(Edge::start));
                int i=0,j=0;
                while(i<line.negative.size()&&j<line.positive.size()) {
                    Edge a=line.negative.get(i),b=line.positive.get(j);long lo=Math.max(a.start,b.start),hi=Math.min(a.end,b.end);
                    if(lo<hi) {
                        wall(a.cell,b.cell,xAxis?Direction.EAST:Direction.SOUTH,xAxis?entry.getKey():lo,xAxis?lo:entry.getKey(),Math.toIntExact(hi-lo));
                        wall(b.cell,a.cell,xAxis?Direction.WEST:Direction.NORTH,xAxis?entry.getKey():lo,xAxis?lo:entry.getKey(),Math.toIntExact(hi-lo));
                    }
                    if(a.end<=b.end)i++;if(b.end<=a.end)j++;
                }
            }
        }
        void wall(Cell solid,Cell neighbor,Direction direction,long x,long z,int width) {
            int leafSize=Math.min(solid.leaf.size,neighbor.leaf.size);
            for(Run run:solid.runs) {
                check.run();long cursor=run.minY;
                for(Run adjacent:neighbor.runs) {
                    runStep();if(adjacent.maxY<=cursor)continue;if(adjacent.minY>=run.maxY)break;
                    if(adjacent.minY>cursor)wallFace(direction,x,z,width,cursor,Math.min(adjacent.minY,run.maxY),run.material,leafSize);
                    cursor=Math.max(cursor,adjacent.maxY);if(cursor>=run.maxY)break;
                }
                if(cursor<run.maxY)wallFace(direction,x,z,width,cursor,run.maxY,run.material,leafSize);
            }
        }
        void wallFace(Direction direction,long x,long z,int width,long lo,long hi,Material material,int size) {
            emit(new Face(direction,x,lo,z,width,Math.toIntExact(hi-lo),stratifiedFaceArgb(direction,material.argb())),material.category(),size);
        }
        void emit(Face face,int category,int leafSize) {
            check.run();if(++sourceFaces>limits.maxFaces())throw new SurvivalRefusal("DH source-face cap exceeded");
            for(Face clipped:coverage.clip(face)) {
                int tileSize=leafSize<16?128:leafSize==16?256:leafSize<=64?1024:4096;
                long x=Math.floorDiv(clipped.x()-(clipped.direction()==Direction.EAST?1:0),tileSize)*tileSize;
                long z=Math.floorDiv(clipped.z()-(clipped.direction()==Direction.SOUTH?1:0),tileSize)*tileSize;
                for(var page:splitPages(clipped,x,z)) {
                    if(++outputFaces>limits.maxFaces())throw new SurvivalRefusal("DH output-face cap exceeded");
                    // Validate all scalar geometry/material fields before compact retention.
                    PageFace value=new PageFace(page,category);
                    DhGeographicLodData.TileKey key=key(x,z,page.pageY2048());
                    FaceSlabs bucket=tiles.get(key);
                    if(bucket==null) {
                        if(tiles.size()>=limits.maxOwners())throw new SurvivalRefusal("DH physical-piece count cap exceeded");
                        // Covers key/maps, fixed slab-reference vector, buffer object and transfer metadata.
                        claim(4096);bucket=new FaceSlabs(limits.maxPageFaces());tiles.put(key,bucket);
                    }
                    bucket.add(value,this::claim);
                }
            }
        }
        DhGeographicLodData.TileKey key(long x,long z,int y) {
            // Key alone is never cache proof. Exact face + explicit material equality remains mandatory.
            String identity=keyIdentities.computeIfAbsent(new Bucket(x,z,y),unused->sha(POLICY+"\n"+SurvivalDhLayout.IDENTITY+"\n"+source.identity()+"\n"+palette.identity()+"\n"+x+","+y+","+z));
            for(int piece=0;piece<8;piece++) {
                var key=new DhGeographicLodData.TileKey(x,z,y,identity,piece);var value=tiles.get(key);
                if(value==null||value.size()<limits.maxPageFaces())return key;
            }
            throw new SurvivalRefusal("DH signed bucket exceeds existing eight-piece domain");
        }
    }
    static ArrayList<Run> replace(List<Run> current,long lo,long hi,List<Run> replacement) {
        return replace(current,lo,hi,replacement,()->{});
    }
    private static ArrayList<Run> replace(List<Run> current,long lo,long hi,List<Run> replacement,Runnable workCheck) {
        if(lo<MIN_Y||hi>MAX_Y_EXCLUSIVE||lo>=hi)throw new IllegalArgumentException("Bounded replacement interval required");
        for(Run run:replacement)if(run.minY<lo||run.maxY>hi)throw new IllegalArgumentException("Replacement run lies outside its exact interval");
        var result=new ArrayList<Run>();
        for(Run run:current) {
            workCheck.run();if(run.maxY<=lo||run.minY>=hi)result.add(run);
            else {
                if(run.minY<lo)result.add(new Run(run.minY,lo,run.material));
                if(run.maxY>hi)result.add(new Run(hi,run.maxY,run.material));
            }
        }
        result.addAll(replacement);result.sort(Comparator.comparingLong(Run::minY));
        var merged=new ArrayList<Run>();for(Run run:result){workCheck.run();appendRun(merged,run);}return merged;
    }
    private static void appendRun(List<Run> runs,Run run) {
        if(!runs.isEmpty()) {
            Run previous=runs.getLast();if(previous.maxY>run.minY)throw new SurvivalRefusal("Overlapping DH solid runs");
            if(previous.maxY==run.minY&&previous.material.equals(run.material)) {
                runs.set(runs.size()-1,new Run(previous.minY,run.maxY,run.material));return;
            }
        }
        if(runs.size()>=MAX_COLUMN_RUNS)throw new SurvivalRefusal("DH per-column sparse-run cap exceeded");runs.add(run);
    }
    private static void edge(Map<Long,EdgeLine> lines,long plane,Edge edge,boolean negative) {
        EdgeLine line=lines.computeIfAbsent(plane,unused->new EdgeLine());(negative?line.negative:line.positive).add(edge);
    }
    private static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
