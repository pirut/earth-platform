package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Source-bounded direct geographic summaries. No distant vanilla chunks or one-metre far pre-generation. */
public final class DhGeographicLodData {
    public static final int CELL_SIZE=16,CELLS=64,RADIUS=512,TILE_SIZE=256,MAX_FACES=32768,MAX_TILES=64;
    /** Replacement pieces never alias base tiles, even at the same signed origin/page. */
    public record TileKey(long originX,long originZ,int pageY,String replacementIdentity,int piece) {
        public TileKey(long originX,long originZ,int pageY) { this(originX,originZ,pageY,"",0); }
        public TileKey {
            java.util.Objects.requireNonNull(replacementIdentity,"replacementIdentity");
            if (replacementIdentity.isEmpty()) {
                if (piece != 0) throw new IllegalArgumentException("Base tiles cannot have replacement pieces");
            } else if (!replacementIdentity.matches("[0-9a-f]{64}") || piece < 0 || piece >= MAX_FACES / 4096) {
                throw new IllegalArgumentException("Bounded canonical replacement identity and piece required");
            }
        }
        /** Preserve frozen base batch/cache serialization byte-for-byte. */
        @Override public String toString() {
            String base="TileKey[originX="+originX+", originZ="+originZ+", pageY="+pageY;
            return replacementIdentity.isEmpty() ? base+"]" : base+", replacementIdentity="+replacementIdentity+", piece="+piece+"]";
        }
    }
    public record Batch(Map<TileKey,List<DhTerrainGeometry.PageFace>> tiles,int faces,int clippedSourceFaces) {
        public Batch {var copy=new LinkedHashMap<TileKey,List<DhTerrainGeometry.PageFace>>();tiles.forEach((k,v)->copy.put(k,List.copyOf(v)));tiles=java.util.Collections.unmodifiableMap(copy);}
    }
    private record Cell(long x,long z,long top,int argb) { }
    private final List<DhTerrainGeometry.Face> sourceFaces;
    private final String identity;
    private final long originX,originZ;
    private final int knownCells,unknownCells;
    public DhGeographicLodData(SceneSurfaceSampler sampler,long centerX,long centerZ) {
        identity=sampler.identity();if(identity==null||identity.isBlank())throw new IllegalArgumentException("Source/appearance identity required");
        originX=Math.subtractExact(Math.multiplyExact(Math.floorDiv(centerX,CELL_SIZE),CELL_SIZE),RADIUS);
        originZ=Math.subtractExact(Math.multiplyExact(Math.floorDiv(centerZ,CELL_SIZE),CELL_SIZE),RADIUS);
        Math.addExact(originX,(long)CELLS*CELL_SIZE);Math.addExact(originZ,(long)CELLS*CELL_SIZE);
        Cell[][] cells=new Cell[CELLS][CELLS];int known=0,unknown=0;
        for(int z=0;z<CELLS;z++)for(int x=0;x<CELLS;x++) {
            long bx=originX+(long)x*CELL_SIZE,bz=originZ+(long)z*CELL_SIZE;
            try {
                long sampleX=bx+CELL_SIZE/2,sampleZ=bz+CELL_SIZE/2,y=sampler.surfaceY(sampleX,sampleZ);
                if(y < -11008||y>=8864)throw new IllegalArgumentException("Geographic source exceeds native Earth envelope");
                int color=sampler.argb(sampleX,sampleZ,y);if((color>>>24)!=255)throw new IllegalArgumentException("Opaque geographic far field only");
                cells[z][x]=new Cell(bx,bz,y+1,color);known++;
            }catch(MissingTerrainException absent){unknown++;}
        }
        if(known==0)throw new IllegalArgumentException("No known geographic far cells in the bounded view");
        if(!identity.equals(sampler.identity()))throw new IllegalStateException("Source identity changed during summary");
        ArrayList<DhTerrainGeometry.Face> faces=new ArrayList<>();
        for(int z=0;z<CELLS;z++)for(int x=0;x<CELLS;x++) {
            Cell c=cells[z][x];if(c==null)continue;
            faces.add(new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.UP,c.x,c.top,c.z,CELL_SIZE,CELL_SIZE,c.argb));
            if(x+1<CELLS&&cells[z][x+1]!=null)stepX(faces,c,cells[z][x+1]);
            if(z+1<CELLS&&cells[z+1][x]!=null)stepZ(faces,c,cells[z+1][x]);
        }
        if(faces.size()>MAX_FACES)throw new IllegalArgumentException("Source face budget exceeded");
        sourceFaces=List.copyOf(faces);knownCells=known;unknownCells=unknown;
    }
    private static void stepX(List<DhTerrainGeometry.Face> faces,Cell west,Cell east) {
        if(west.top==east.top)return;
        boolean positive=west.top>east.top;Cell high=positive?west:east;
        faces.add(new DhTerrainGeometry.Face(positive?DhTerrainGeometry.Direction.EAST:DhTerrainGeometry.Direction.WEST,
                east.x,Math.min(west.top,east.top),west.z,CELL_SIZE,Math.toIntExact(Math.abs(west.top-east.top)),shade(high.argb,0.72f)));
    }
    private static void stepZ(List<DhTerrainGeometry.Face> faces,Cell north,Cell south) {
        if(north.top==south.top)return;
        boolean positive=north.top>south.top;Cell high=positive?north:south;
        faces.add(new DhTerrainGeometry.Face(positive?DhTerrainGeometry.Direction.SOUTH:DhTerrainGeometry.Direction.NORTH,
                north.x,Math.min(north.top,south.top),south.z,CELL_SIZE,Math.toIntExact(Math.abs(north.top-south.top)),shade(high.argb,0.85f)));
    }
    public Batch clip(List<CubePos> actualReadyCubes) {
        if(actualReadyCubes.size()>256)throw new IllegalArgumentException("Ready cube clipping budget exceeded");
        Map<TileKey,List<DhTerrainGeometry.PageFace>> tiles=new LinkedHashMap<>();int count=0,changed=0;
        for(var source:sourceFaces) {
            var fragments=DhTerrainGeometry.clip(source,actualReadyCubes,4096);
            if(fragments.size()!=1||!fragments.getFirst().equals(source))changed++;
            for(var face:fragments) {
                long tileX=Math.floorDiv(face.x()-(face.direction()==DhTerrainGeometry.Direction.EAST?1:0),TILE_SIZE)*TILE_SIZE;
                long tileZ=Math.floorDiv(face.z()-(face.direction()==DhTerrainGeometry.Direction.SOUTH?1:0),TILE_SIZE)*TILE_SIZE;
                for(var page:DhTerrainGeometry.splitPages(face,tileX,tileZ)) {
                    if(++count>MAX_FACES)throw new IllegalArgumentException("Clipped far face budget exceeded");
                    var key=new TileKey(tileX,tileZ,page.pageY2048());
                    var list=tiles.computeIfAbsent(key,k->new ArrayList<>());
                    if(tiles.size()>MAX_TILES||list.size()>=4096)throw new IllegalArgumentException("Far tile owner or per-tile face budget exceeded");
                    list.add(page);
                }
            }
        }
        return new Batch(tiles,count,changed);
    }
    private static int shade(int color,float factor){return 0xFF000000|Math.round(((color>>>16)&255)*factor)<<16|Math.round(((color>>>8)&255)*factor)<<8|Math.round((color&255)*factor);}
    public String identity(){return identity;}
    public long originX(){return originX;}
    public long originZ(){return originZ;}
    public int knownCells(){return knownCells;}
    public int unknownCells(){return unknownCells;}
    public int sourceFaces(){return sourceFaces.size();}
}
