package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.geography.earth.EarthAlpineDefinition.Material;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.nio.file.*;
import java.util.*;

/** Complete finite horizontal census, with an all-Y clipping proof and native unit-face census. */
public final class DhEarthAlpineGeometryCensus {
    public static final long NATIVE_BYTES_PER_QUAD=4L*52;
    public static final long NATIVE_BYTE_CAP=1_048_576;
    /** Test-only bounded primitive grid. No runtime cache, resource cap or terrain policy is changed. */
    public static final class CachedSampler implements SceneSurfaceSampler {
        final EarthAlpineDefinition source;
        final EarthAlpineDefinition.Envelope envelope;
        final int width,depth;
        final int[] columns;
        final BitSet checkedFootprints;
        long sourceReads,footprintReads;
        CachedSampler(EarthAlpineDefinition source) {
            this.source=source;envelope=source.envelope();
            width=Math.toIntExact(envelope.maxColumnXExclusive()-envelope.minColumnX());
            depth=Math.toIntExact(envelope.maxColumnZExclusive()-envelope.minColumnZ());
            if(width>4608||depth>4608)throw new IllegalArgumentException("Unbounded census grid");
            columns=new int[Math.multiplyExact(width,depth)];
            // Each dyadic size has a tightly packed disjoint grid in this bitset.
            checkedFootprints=new BitSet(Math.multiplyExact(width,depth)*2);
        }
        int packed(long x,long z) {
            if(!envelope.containsColumn(x,z))throw new IllegalArgumentException("Outside bounded census grid");
            int i=Math.toIntExact((z-envelope.minColumnZ())*width+x-envelope.minColumnX());
            int p=columns[i];
            if(p==0){var c=source.column(x,z);sourceReads++;p=(Math.toIntExact(c.surfaceY())+11009)|(c.top().ordinal()<<16)|(c.subsurface().ordinal()<<20);columns[i]=p;}
            return p;
        }
        public long surfaceY(long x,long z){return (packed(x,z)&65535)-11009;}
        public int argb(long x,long z,long y){return Material.values()[(packed(x,z)>>>16)&15].argb();}
        public String identity(){return source.identity();}
        public boolean supportsStratifiedSides(){return true;}
        public boolean supportsFootprintCoverage(){return true;}
        public boolean supportsConcurrentReads(){return false;}
        public ColumnMaterials columnMaterials(long x,long z,long y) {
            int p=packed(x,z);
            return new ColumnMaterials(material(Material.values()[(p>>>16)&15]),material(Material.values()[(p>>>20)&15]),material(Material.STONE),3);
        }
        private static SurfaceMaterial material(Material value){return new SurfaceMaterial(value.argb(),value==Material.SNOW_BLOCK?8:2);}
        public boolean knownFootprint(long x,long z,long endX,long endZ) {
            int size=Math.toIntExact(endX-x);
            if(endZ-z!=size||size<1||size>128||(size&(size-1))!=0||Math.floorMod(x,size)!=0||Math.floorMod(z,size)!=0)
                return source.knownFootprint(x,z,endX,endZ);
            if(!envelope.containsColumn(x,z)||!envelope.containsColumn(endX-1,endZ-1))return false;
            int offset=0;
            for(int s=1;s<size;s*=2)offset+=Math.multiplyExact(width/s,depth/s);
            int i=offset+Math.toIntExact((z-envelope.minColumnZ())/size*(width/size)+(x-envelope.minColumnX())/size);
            if(!checkedFootprints.get(i)){footprintReads++;if(!source.knownFootprint(x,z,endX,endZ))return false;checkedFootprints.set(i);}
            return true;
        }
    }
    public record NativeCounts(int horizontalCubeOwners,int totalNonemptyOwners,int maxQuads,CubePos worstOwner,
                               long totalQuads,int maxAdjacentDelta,long minHeight,long maxHeight,long unitColumns) {
        public long maxVertexBytes(){return maxQuads*NATIVE_BYTES_PER_QUAD;}
    }
    public record FarCounts(int horizontalProfiles,int uniqueFarCenters,int maxCells,int maxFaces,int maxOwners,
                            int maxPageFaces,long maxResultBytes,long maxOldNewGpuBytes,int maxResidentOwners,
                            int minSignedPage,int maxSignedPage,int maxSourceFaces,int maxTransitionFaces,
                            int maxRefinedCells,int maxMaterialBandSplits,int maxSignedPageSplits,String worstFaceProfile,String worstPageProfile) {}
    public static List<CubePos> refinement(EarthAlpineDefinition source,int cx,int cz) {
        // Y does not affect the production partition. Preserve full moving27 plus the permanent edit pin.
        int cy=source.anchor().center().y();
        var result=new ArrayList<CubePos>();
        for(int dz=-1;dz<=1;dz++)for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)result.add(new CubePos(cx+dx,cy+dy,cz+dz));
        result.add(CubePos.fromBlock(source.anchor().digTarget().x(),source.anchor().digTarget().y(),source.anchor().digTarget().z()));
        return List.copyOf(result);
    }
    public static NativeCounts nativeCensus(CachedSampler s) {
        var e=s.envelope;
        // Union of all permitted radius-two dependency windows, inclusive outer cubes.
        long x0=(long)(e.minCenterX()-2)*16,x1=(long)(e.maxCenterXExclusive()+2)*16;
        long z0=(long)(e.minCenterZ()-2)*16,z1=(long)(e.maxCenterZExclusive()+2)*16;
        int w=Math.toIntExact(x1-x0),d=Math.toIntExact(z1-z0);
        var counts=new TreeMap<CubePos,Integer>();
        long total=0,min=Long.MAX_VALUE,max=Long.MIN_VALUE;int maxDelta=0;
        for(long z=z0-1;z<=z1;z++)for(long x=x0-1;x<=x1;x++)s.surfaceY(x,z);
        for(long z=z0;z<z1;z++)for(long x=x0;x<x1;x++) {
            long h=s.surfaceY(x,z);min=Math.min(min,h);max=Math.max(max,h);
            increment(counts,x,h,z,1);total++;
            for(int[] delta:new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
                long other=s.surfaceY(x+delta[0],z+delta[1]);
                maxDelta=Math.max(maxDelta,Math.toIntExact(Math.abs(h-other)));
                // Every material is a full opaque cube. Internal material transitions have no face.
                // Each exposed unit side belongs to the higher column's solid cube.
                if(h>other)for(long y=other+1;y<=h;) {
                    long end=Math.min(h+1,Math.floorDiv(y,16)*16+16);
                    increment(counts,x,y,z,Math.toIntExact(end-y));total+=end-y;y=end;
                }
            }
        }
        int largest=0;CubePos worst=null;for(var entry:counts.entrySet())if(entry.getValue()>largest){largest=entry.getValue();worst=entry.getKey();}
        var nativeCsv=new StringBuilder("cubeX,cubeY,cubeZ,quads,vertexBytes52Stride\n");
        counts.forEach((cube,count)->nativeCsv.append(cube.x()).append(',').append(cube.y()).append(',').append(cube.z()).append(',').append(count).append(',').append(count*NATIVE_BYTES_PER_QUAD).append('\n'));
        evidence("native-nonempty-owners.csv",nativeCsv.toString());
        return new NativeCounts(w/16*(d/16),counts.size(),largest,worst,total,maxDelta,min,max,(long)w*d);
    }
    private static void increment(Map<CubePos,Integer> counts,long x,long y,long z,int amount){counts.merge(CubePos.fromBlock(x,y,z),amount,Integer::sum);}
    private record WallKey(DhTerrainGeometry.Direction direction,long x,long z,int width) {}
    public static FarCounts farCensus(CachedSampler s,boolean firstOnly) {
        var e=s.envelope;var centers=new HashSet<String>();
        int profiles=0,cells=0,faces=0,owners=0,page=0,minPage=Integer.MAX_VALUE,maxPage=Integer.MIN_VALUE,sourceFaces=0,transition=0,refined=0,bandSplits=0,pageSplits=0;
        String worstFaces="",worstPage="";
        var profileCsv=new StringBuilder("centerX,centerZ,farX,farZ,cells,sourceFaces,pageFaces,owners,maxPageFaces,materialBandSplits,signedPageSplits,refinedCells,transitionFaces\n");
        for(int cz=e.minCenterZ();cz<e.maxCenterZExclusive();cz++)for(int cx=e.minCenterX();cx<e.maxCenterXExclusive();cx++) {
            long fx=EarthAlpineDefinition.snapCubeCenter(cx),fz=EarthAlpineDefinition.snapCubeCenter(cz);
            centers.add(fx+","+fz);s.source.validateFarFootprint(fx,fz);
            String profile="center="+cx+","+cz+" far="+fx+","+fz;
            DhAdaptiveTerrainData.Batch batch;
            try {batch=new DhAdaptiveTerrainData(s,fx,fz,DhLodLayout.EARTH_ALPINE_2048).clip(List.of(),refinement(s.source,cx,cz));}
            catch(RuntimeException failure){System.out.println("REJECTED_PROFILE "+profile+" reason="+failure); diagnose(s,fx,fz,refinement(s.source,cx,cz));throw failure;}
            if(batch.unknownCells()!=0||batch.knownCells()!=batch.sampledCells())throw new AssertionError("Unknown source clipped: "+profile);
            profiles++;cells=Math.max(cells,batch.sampledCells());owners=Math.max(owners,batch.tiles().size());
            sourceFaces=Math.max(sourceFaces,batch.sourceFaces());transition=Math.max(transition,batch.transitionFaces());refined=Math.max(refined,batch.refinedCells());
            if(batch.faces()>faces){faces=batch.faces();worstFaces=profile;}
            var walls=new HashSet<WallKey>();
            for(var entry:batch.tiles().entrySet()) {
                for(var f:entry.getValue()) {
                    if(f.material()!=2&&f.material()!=8)throw new AssertionError("Unpinned DH material");
                    if(f.direction()!=DhTerrainGeometry.Direction.UP)walls.add(new WallKey(f.direction(),entry.getKey().originX()+f.x(),entry.getKey().originZ()+f.z(),f.width()));
                }
                minPage=Math.min(minPage,entry.getKey().pageY());maxPage=Math.max(maxPage,entry.getKey().pageY());
                if(entry.getValue().size()>page){page=entry.getValue().size();worstPage=profile+" owner="+entry.getKey();}
            }
            int bands=batch.sourceFaces()-batch.knownCells()-walls.size();
            if(bands<0)throw new AssertionError("Wall census mismatch");
            bandSplits=Math.max(bandSplits,bands);pageSplits=Math.max(pageSplits,batch.faces()-batch.sourceFaces());
            profileCsv.append(cx).append(',').append(cz).append(',').append(fx).append(',').append(fz).append(',')
                .append(batch.sampledCells()).append(',').append(batch.sourceFaces()).append(',').append(batch.faces()).append(',')
                .append(batch.tiles().size()).append(',').append(batch.tiles().values().stream().mapToInt(List::size).max().orElseThrow()).append(',')
                .append(bands).append(',').append(batch.faces()-batch.sourceFaces()).append(',').append(batch.refinedCells()).append(',').append(batch.transitionFaces()).append('\n');
            if(profiles%32==0||firstOnly)System.out.println("PROGRESS profiles="+profiles+" maxCells="+cells+" maxFaces="+faces+" maxOwners="+owners+" maxPage="+page+" sourceReads="+s.sourceReads);
            if(firstOnly)return new FarCounts(profiles,centers.size(),cells,faces,owners,page,4096L+128L*faces,176L*faces,2*owners,minPage,maxPage,sourceFaces,transition,refined,bandSplits,pageSplits,worstFaces,worstPage);
        }
        evidence("horizontal-profiles.csv",profileCsv.toString());
        return new FarCounts(profiles,centers.size(),cells,faces,owners,page,4096L+128L*faces,176L*faces,2*owners,minPage,maxPage,sourceFaces,transition,refined,bandSplits,pageSplits,worstFaces,worstPage);
    }
    private static void evidence(String name,String contents) {
        try {
            Path root=Path.of(System.getProperty("earth.alpine.evidence","review-evidence/alpine-geometry"));
            Files.createDirectories(root);Files.writeString(root.resolve(name),contents);
        } catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
    }
    /** Diagnostics use the actual private production partition and wall builder, keeping every guard intact.
     * Only aggregation is count-only, so a rejected owner can be reported without allocating it. */
    @SuppressWarnings({"unchecked","rawtypes"})
    public static void diagnose(CachedSampler s,long fx,long fz,List<CubePos> refinement) {
        try {
            var data=new DhAdaptiveTerrainData(s,fx,fz,DhLodLayout.EARTH_ALPINE_2048);
            var type=DhAdaptiveTerrainData.class;
            var partition=type.getDeclaredMethod("partitionLeaves",List.class,Runnable.class);partition.setAccessible(true);
            var leaves=(List<?>)partition.invoke(data,refinement,(Runnable)()->{});
            Class<?> leaf=Class.forName(type.getName()+"$Leaf"),cell=Class.forName(type.getName()+"$Cell"),edge=Class.forName(type.getName()+"$Edge"),sf=Class.forName(type.getName()+"$SourceFace");
            var x=leaf.getDeclaredMethod("x");x.setAccessible(true);var z=leaf.getDeclaredMethod("z");z.setAccessible(true);var size=leaf.getDeclaredMethod("size");size.setAccessible(true);
            var cellCtor=cell.getDeclaredConstructors()[0];cellCtor.setAccessible(true);var edgeCtor=edge.getDeclaredConstructors()[0];edgeCtor.setAccessible(true);
            var add=type.getDeclaredMethod("addEdge",Map.class,long.class,edge,boolean.class);add.setAccessible(true);
            var join=type.getDeclaredMethod("joinEdges",Map.class,boolean.class,List.class,Runnable.class);join.setAccessible(true);
            var faces=new ArrayList<Object>();var xs=new TreeMap<Long,Object>();var zs=new TreeMap<Long,Object>();
            var sfCtor=sf.getDeclaredConstructors()[0];sfCtor.setAccessible(true);
            for(Object l:leaves) {
                long lx=(long)x.invoke(l),lz=(long)z.invoke(l);int n=(int)size.invoke(l);long px=lx+n/2,pz=lz+n/2,h=s.surfaceY(px,pz);
                var material=s.columnMaterials(px,pz,h);Object c=cellCtor.newInstance(l,h+1,s.argb(px,pz,h),material);
                faces.add(sfCtor.newInstance(new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.UP,lx,h+1,lz,n,n,material.top().argb(),material.top().dhMaterial()),DhLodLayout.EARTH_ALPINE_2048.tileSizeForCell(n)));
                add.invoke(null,xs,lx,edgeCtor.newInstance(lz,lz+n,c),false);add.invoke(null,xs,lx+n,edgeCtor.newInstance(lz,lz+n,c),true);
                add.invoke(null,zs,lz,edgeCtor.newInstance(lx,lx+n,c),false);add.invoke(null,zs,lz+n,edgeCtor.newInstance(lx,lx+n,c),true);
            }
            join.invoke(data,xs,true,faces,(Runnable)()->{});join.invoke(data,zs,false,faces,(Runnable)()->{});
            var getFace=sf.getDeclaredMethod("face");getFace.setAccessible(true);var getTile=sf.getDeclaredMethod("tileSize");getTile.setAccessible(true);
            var owners=new LinkedHashMap<DhGeographicLodData.TileKey,Integer>();var byTile=new LinkedHashMap<DhGeographicLodData.TileKey,Map<Integer,Integer>>();int pageFaces=0;
            for(Object object:faces) {
                var f=(DhTerrainGeometry.Face)getFace.invoke(object);int tile=(int)getTile.invoke(object);
                long tx=Math.floorDiv(f.x()-(f.direction()==DhTerrainGeometry.Direction.EAST?1:0),tile)*tile;
                long tz=Math.floorDiv(f.z()-(f.direction()==DhTerrainGeometry.Direction.SOUTH?1:0),tile)*tile;
                for(var p:DhTerrainGeometry.splitPages(f,tx,tz)){var key=new DhGeographicLodData.TileKey(tx,tz,p.pageY2048());owners.merge(key,1,Integer::sum);byTile.computeIfAbsent(key,k->new TreeMap<>()).merge(tile,1,Integer::sum);pageFaces++;}
            }
            System.out.println("REJECTED_COUNTS cells="+leaves.size()+" sourceFaces="+faces.size()+" pageFaces="+pageFaces+" owners="+owners.size()+" largest="+owners.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow()+" byTile="+byTile.get(owners.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey()));
        } catch(ReflectiveOperationException reflection){throw new IllegalStateException("Diagnostic source signature changed",reflection);}
    }
    public static void main(String[] args)throws Exception {
        String mode=args.length==0?"all":args[0];
        var source=EarthAlpineDefinition.load(Path.of("geography"));var sampler=new CachedSampler(source);
        System.out.println("SOURCE identity="+source.identity()+" layout="+DhLodLayout.EARTH_ALPINE_2048.identity()+" envelope="+source.envelope());
        if(mode.equals("diagnose")){int cx=source.envelope().minCenterX()+1,cz=source.envelope().minCenterZ()+3;diagnose(sampler,EarthAlpineDefinition.snapCubeCenter(cx),EarthAlpineDefinition.snapCubeCenter(cz),refinement(source,cx,cz));return;}
        if(!mode.equals("first")&&!mode.equals("far"))System.out.println("NATIVE "+nativeCensus(sampler));
        if(!mode.equals("native"))System.out.println("FAR "+farCensus(sampler,mode.equals("first")));
        System.out.println("CACHE sourceReads="+sampler.sourceReads+" footprintReads="+sampler.footprintReads+" primitiveGridBytes="+(sampler.columns.length*4L));
    }
}
