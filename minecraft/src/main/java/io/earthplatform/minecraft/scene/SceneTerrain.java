package io.earthplatform.minecraft.scene;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.*;
import io.earthplatform.geography.landcover.LandCoverSurfacePolicy;
import io.earthplatform.geography.variety.AlpineSurfaceRelief;
import io.earthplatform.minecraft.NaturalWindowSurface;
import io.earthplatform.minecraft.ProofTerrain;
import io.earthplatform.minecraft.network.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Same pinned height/appearance source for sparse native cubes and DH summaries. No sampling-time I/O. */
public final class SceneTerrain implements SceneSurfaceSampler {
    public static final String NATURAL_RELIEF_OPT_IN="earthplatform.sceneNaturalRelief";
    public static final int MAX_CACHED_COLUMNS=16_384;
    public static final String PALETTE="scene-native-palette-v1;grass-tint-91bd59;unknown-magenta;unresolved-water-cyan;no-fluid";
    private final OfflineTerrain terrain;
    private final EquirectangularProjection projection=new EquirectangularProjection(new GeoPoint(0,0),0);
    private final NaturalWindowSurface surface;
    private final boolean naturalRelief;
    private final String identity;
    private final Map<Long,Column> cache=new LinkedHashMap<>(256,0.75f,true);
    public SceneTerrain() { this(Boolean.getBoolean(NATURAL_RELIEF_OPT_IN)); }
    /** Explicit immutable policy for asynchronous provider preparation; no ambient property reads. */
    public SceneTerrain(boolean naturalRelief) {
        terrain=ProofTerrain.get(ProofTerrain.COPERNICUS);surface=new NaturalWindowSurface(terrain,projection,AlpineSurfaceRelief.RIDGE_PREVIEW);
        this.naturalRelief=naturalRelief;
        identity=WorldManifest.sha256(("sparse-scene-v1\n"+terrain.manifest().fingerprint()+"\n"
                +ProofTerrain.GLOBAL_PROJECTION+"\n"+surface.identity()+"\nnaturalRelief="+naturalRelief+"\n"+PALETTE)
                .getBytes(StandardCharsets.UTF_8));
    }
    public record Column(NaturalWindowSurface.Column source,long surfaceY,BlockState top,BlockState subsurface,int argb) {}
    public Column sample(long x,long z) {
        int ix=Math.toIntExact(x),iz=Math.toIntExact(z);long key=((long)iz<<32)|(ix&0xffffffffL);
        Column cached=cache.get(key);if(cached!=null)return cached;
        var sampled=surface.sample(x,z);
        var used=naturalRelief?sampled:new NaturalWindowSurface.Column(sampled.sourceMetres(),0,sampled.sourceMetres(),sampled.appearance());
        long height=used.surfaceY();
        if(height < -11000||height>8849)throw new MissingTerrainException("Source height outside the verified native Earth envelope: "+height);
        BlockState top,subsurface;
        if(used.appearance().appearance()==LandCoverSurfacePolicy.Appearance.UNKNOWN) {
            top=Blocks.MAGENTA_TERRACOTTA.defaultBlockState();subsurface=Blocks.STONE.defaultBlockState();
        } else if(used.appearance().appearance()==LandCoverSurfacePolicy.Appearance.UNRESOLVED_WATER) {
            top=Blocks.CYAN_TERRACOTTA.defaultBlockState();subsurface=Blocks.STONE.defaultBlockState();
        } else {top=NaturalWindowSurface.map(3,used);subsurface=NaturalWindowSurface.map(2,used);}
        Column column=new Column(used,height,top,subsurface,color(top));cache.put(key,column);
        if(cache.size()>MAX_CACHED_COLUMNS)cache.remove(cache.keySet().iterator().next());
        return column;
    }
    @Override public boolean supportsFootprintCoverage(){return true;}
    @Override public boolean knownFootprint(long minX,long minZ,long maxXExclusive,long maxZExclusive){
        long width=Math.subtractExact(maxXExclusive,minX),depth=Math.subtractExact(maxZExclusive,minZ);
        if(width<=0||depth<=0||width>16||depth>16)throw new IllegalArgumentException("Bounded summary footprint required");
        if(minX<Integer.MIN_VALUE||minZ<Integer.MIN_VALUE||maxXExclusive>(long)Integer.MAX_VALUE+1||maxZExclusive>(long)Integer.MAX_VALUE+1)return false;
        // NaturalWindowSurface samples the source at ±8 projected metres for both slope and
        // shelter, even in the legacy relief mode. The entire continuous leaf plus that halo
        // must have finite bilinear support; a successful centre sample alone proves nothing.
        var northWest=projection.toGeographic(Math.subtractExact(minX,8),Math.subtractExact(minZ,8));
        var southEast=projection.toGeographic(Math.addExact(maxXExclusive,8),Math.addExact(maxZExclusive,8));
        return terrain.hasFiniteRectangleSupport(northWest,southEast);
    }
    @Override public long surfaceY(long x,long z){return sample(x,z).surfaceY();}
    @Override public int argb(long x,long z,long expectedY) {
        Column column=sample(x,z);if(column.surfaceY()!=expectedY)throw new IllegalArgumentException("Far sampler changed its pinned surface");
        return column.argb();
    }
    @Override public String identity(){return identity;}
    public boolean naturalRelief(){return naturalRelief;}
    public int cachedColumns(){return cache.size();}
    public String sourceUrl(){return terrain.manifest().provenance().source().toString();}
    public String datum(){return terrain.manifest().provenance().verticalDatum();}
    public WorldIdentity world(EnginePaletteContext palette) {
        String paletteIdentity=WorldManifest.sha256((PALETTE+"/"+palette.fingerprint()).getBytes(StandardCharsets.UTF_8));
        return new WorldIdentity(terrain.manifest().seed(),"earthplatform:sparse-native-view","scene-surface-fill-v1",identity,paletteIdentity);
    }
    public EngineCubeSnapshot snapshot(CubePos position,EnginePaletteContext palette,WorldIdentity world,long epoch,long lease) {
        if(position.minBlockY() < -11008||position.minBlockY()+16>8864)throw new MissingTerrainException("Cube outside bounded native Y envelope");
        var section=EngineCubeSection.uniform(palette,Blocks.AIR.defaultBlockState());
        for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            Column column=sample(position.minBlockX()+x,position.minBlockZ()+z);
            for(int y=0;y<16;y++) {
                long absolute=position.minBlockY()+y;
                if(absolute<=column.surfaceY())section.set(x,y,z,absolute==column.surfaceY()?column.top():
                        absolute>=column.surfaceY()-3?column.subsurface():Blocks.STONE.defaultBlockState());
            }
        }
        return EngineCubeSnapshot.capture(position,world,epoch,lease,0,section);
    }
    private static int color(BlockState state) {
        if(state.is(Blocks.SNOW_BLOCK))return 0xFFF1F5F6;
        if(state.is(Blocks.GRASS_BLOCK))return 0xFF79A44B;
        if(state.is(Blocks.DIRT))return 0xFF866044;
        if(state.is(Blocks.GRAVEL))return 0xFF8D8881;
        if(state.is(Blocks.ANDESITE))return 0xFF999A99;
        if(state.is(Blocks.TUFF))return 0xFF777A69;
        if(state.is(Blocks.MAGENTA_TERRACOTTA))return 0xFFAC4C9E;
        if(state.is(Blocks.CYAN_TERRACOTTA))return 0xFF4F999A;
        return 0xFF8A8A8A;
    }
}
