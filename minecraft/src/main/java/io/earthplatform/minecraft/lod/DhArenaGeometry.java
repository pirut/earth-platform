package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.*;
import java.util.*;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.*;

/** One exact34³ union. Faces belong to solid voxels; the immutable36³ halo supplies all six reads. */
public final class DhArenaGeometry {
    private DhArenaGeometry(){}
    public record Bucket(long x,long z,int page){}
    @FunctionalInterface public interface FaceVisitor { void accept(long x,long y,long z,Direction direction,int argb); }
    private static final Direction[] DIRECTIONS=Direction.values();
    private static final int[][] OFFSETS={{0,1,0},{0,-1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
    public record Census(int sourceFaces,int pieceOwners,int largestOwnerFaces,Map<Bucket,Integer> bucketFaces,
                         Map<CubePos,Integer> nativeFaces,int maxNativeFaces,long maxNativeVertexBytes){
        public Census{bucketFaces=Map.copyOf(bucketFaces);nativeFaces=Map.copyOf(nativeFaces);}
    }
    public static Volume volume(ArenaDefinition d){return new Volume(d.ownedMinX(),d.ownedMinY(),d.ownedMinZ(),d.ownedMaxXExclusive(),d.ownedMaxYExclusive(),d.ownedMaxZExclusive());}
    public static Bucket bucket(long x,long y,long z){return new Bucket(Math.floorDiv(x,32)*32,Math.floorDiv(z,32)*32,Math.toIntExact(Math.floorDiv(y,2048)));}
    public static void validate(ArenaTerrainSource source,ArenaCapture capture,Runnable check){
        Objects.requireNonNull(source);Objects.requireNonNull(capture);Objects.requireNonNull(check).run();var d=capture.definition();
        if(!d.identity().equals(source.definition().identity())||!d.identity().equals(ArenaDefinition.FIXED.identity())||capture.declaredBytes()!=ArenaCapture.PACKED_BYTES)
            throw new DhReplacementComposition.Declined("Exact bounded arena/source/capture required");
        for(long y=d.readMinY();y<d.readMaxYExclusive();y++)for(long z=d.readMinZ();z<d.readMaxZExclusive();z++)for(long x=d.readMinX();x<d.readMaxXExclusive();x++){
            check.run();int m=capture.materialAt(x,y,z);
            if(m<0||m>2||!d.editable(x,y,z)&&m!=source.materialAt(x,y,z))throw new DhReplacementComposition.Declined("Unsupported material or modified readonly arena halo");
        }
    }
    /** Complete source-face traversal, independent of native-ready clipping. No per-face allocation. */
    public static int visit(ArenaCapture capture,FaceVisitor visitor,Runnable check){
        var d=capture.definition();int faces=0;
        for(long y=d.ownedMinY();y<d.ownedMaxYExclusive();y++)for(long z=d.ownedMinZ();z<d.ownedMaxZExclusive();z++)for(long x=d.ownedMinX();x<d.ownedMaxXExclusive();x++){
            check.run();int color=capture.argbAt(x,y,z);if(color==0)continue;
            for(int i=0;i<6;i++){var a=OFFSETS[i];if(capture.materialAt(x+a[0],y+a[1],z+a[2])==ArenaCapture.AIR){faces=Math.incrementExact(faces);visitor.accept(x,y,z,DIRECTIONS[i],color);}}
        }return faces;
    }
    public static Census inspect(ArenaTerrainSource source,ArenaCapture capture,Runnable check){
        validate(source,capture,check);var buckets=new HashMap<Bucket,Integer>();int count=visit(capture,(x,y,z,d,c)->buckets.merge(bucket(x,y,z),1,Integer::sum),check);
        int pieces=0,largest=0;for(int n:buckets.values()){pieces+=Math.ceilDiv(n,DhAdaptiveTerrainData.MAX_PAGE_FACES);largest=Math.max(largest,Math.min(n,DhAdaptiveTerrainData.MAX_PAGE_FACES));}
        // Every cube that can acquire a face from an arena edit is included. The
        // remaining body cubes retain the independently proved baseline source.
        var nativeCounts=new TreeMap<CubePos,Integer>();int max=0;
        for(var cube:capture.definition().dependencies()){
            int n=0;
            for(long y=cube.minBlockY();y<cube.minBlockY()+16;y++)for(long z=cube.minBlockZ();z<cube.minBlockZ()+16;z++)for(long x=cube.minBlockX();x<cube.minBlockX()+16;x++){
                check.run();if(material(source,capture,x,y,z)==0)continue;
                for(var a:OFFSETS)if(material(source,capture,x+a[0],y+a[1],z+a[2])==0)n++;
            }
            nativeCounts.put(cube,n);max=Math.max(max,n);
        }
        return new Census(count,pieces,largest,buckets,nativeCounts,max,Math.multiplyExact((long)max,4L*52));
    }
    private static int material(ArenaTerrainSource source,ArenaCapture capture,long x,long y,long z){return capture.containsReadCell(x,y,z)?capture.materialAt(x,y,z):source.materialAt(x,y,z);}
    public static Face face(long x,long y,long z,Direction d,int c){return switch(d){
        case UP->new Face(d,x,y+1,z,1,1,c);case DOWN->new Face(d,x,y,z,1,1,c);
        case EAST->new Face(d,x+1,y,z,1,1,c);case SOUTH->new Face(d,x,y,z+1,1,1,c);
        case WEST,NORTH->new Face(d,x,y,z,1,1,c);};}
}
