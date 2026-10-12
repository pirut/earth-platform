package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.*;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Shared prepared alpine source for complete cubes, exact collision and stratified far summaries. */
public final class NativeAlpineCubeProvider implements CubeProvider {
    public static final String GENERATOR_ID="earthplatform:earth-alpine-cube";
    public static final String GENERATOR_VERSION="earth-alpine-stratified-fill-v1";
    public static final int MAX_CACHED_COLUMNS=16384;
    private final EarthAlpineDefinition definition;
    private final WorldIdentity identity;
    private final int[] states;
    private final int registrySize;
    private final Object sourceLock=new Object();
    private final LinkedHashMap<ColumnKey,EarthAlpineDefinition.Column> columns=new LinkedHashMap<>(256,.75f,true);
    private record ColumnKey(long x,long z) {}
    private final SceneSurfaceSampler sampler;

    /** May perform bounded file preparation; invoke only on the existing charged bootstrap worker. */
    public static NativeAlpineCubeProvider prepare(EnginePaletteContext palette){return new NativeAlpineCubeProvider(AlpineEarthResources.get(),palette);}
    /** Exact immutable source injection for the integrated source/native tests. */
    public NativeAlpineCubeProvider(EarthAlpineDefinition definition,EnginePaletteContext palette){
        this.definition=Objects.requireNonNull(definition);Objects.requireNonNull(palette);
        if(!palette.fingerprint().equals(EnginePaletteContext.create(palette.biomes()).fingerprint()))throw new IllegalArgumentException("Exact current native palette required");
        identity=new WorldIdentity(definition.sourceManifest().seed(),GENERATOR_ID,GENERATOR_VERSION,definition.identity(),NativeCubeCodec.paletteIdentity(palette));
        states=new int[EarthAlpineDefinition.Material.values().length];
        for(var material:EarthAlpineDefinition.Material.values())states[material.ordinal()]=stateId(nativeState(material));
        registrySize=Block.BLOCK_STATE_REGISTRY.size();
        sampler=new SceneSurfaceSampler(){
            public long surfaceY(long x,long z){return sample(x,z).surfaceY();}
            public int argb(long x,long z,long expectedY){return exactColumn(x,z,expectedY).argb();}
            public String identity(){return identity.datasetVersion();}
            public boolean supportsConcurrentReads(){return true;}
            public boolean supportsFootprintCoverage(){return true;}
            public boolean knownFootprint(long x,long z,long maxX,long maxZ){return definition.knownFootprint(x,z,maxX,maxZ);}
            public boolean supportsStratifiedSides(){return true;}
            public ColumnMaterials columnMaterials(long x,long z,long expectedY){var c=exactColumn(x,z,expectedY);return new ColumnMaterials(material(c.top()),material(c.subsurface()),material(c.deep()),3);}
        };
    }
    private static SceneSurfaceSampler.SurfaceMaterial material(EarthAlpineDefinition.Material m){return new SceneSurfaceSampler.SurfaceMaterial(m.argb(),m==EarthAlpineDefinition.Material.SNOW_BLOCK?8:2);}
    public static BlockState nativeState(EarthAlpineDefinition.Material m){return switch(m){
        case AIR->Blocks.AIR.defaultBlockState();case STONE->Blocks.STONE.defaultBlockState();case ANDESITE->Blocks.ANDESITE.defaultBlockState();
        case TUFF->Blocks.TUFF.defaultBlockState();case SNOW_BLOCK->Blocks.SNOW_BLOCK.defaultBlockState();};}
    public EarthAlpineDefinition definition(){return definition;}
    public WorldIdentity identity(){return identity;}
    public SceneSurfaceSampler surfaceSampler(){return sampler;}
    public String sourceUrl(){return definition.sourceManifest().provenance().source().toString();}
    public String verticalDatum(){return definition.sourceManifest().provenance().verticalDatum();}
    private EarthAlpineDefinition.Column exactColumn(long x,long z,long expectedY){var c=sample(x,z);if(c.surfaceY()!=expectedY)throw new IllegalArgumentException("Pinned Earth column changed");return c;}
    private EarthAlpineDefinition.Column sample(long x,long z){synchronized(sourceLock){return sampleLocked(x,z);}}
    private EarthAlpineDefinition.Column sampleLocked(long x,long z){
        var key=new ColumnKey(x,z);var column=columns.get(key);if(column!=null)return column;
        column=definition.column(x,z);columns.put(key,column);if(columns.size()>MAX_CACHED_COLUMNS)columns.remove(columns.keySet().iterator().next());return column;
    }
    @Override public CompletableFuture<ImmutableCube> load(CubePos position,CancellationToken cancellation){
        try{
            Objects.requireNonNull(position);Objects.requireNonNull(cancellation);cancellation.throwIfCancelled();
            if(position.minBlockY()<EarthAlpineDefinition.MIN_BLOCK_Y||position.minBlockY()+16>EarthAlpineDefinition.MAX_BLOCK_Y_EXCLUSIVE)throw new MissingTerrainException("Cube outside the alpine native Y envelope");
            if(Block.BLOCK_STATE_REGISTRY.size()!=registrySize)throw new IllegalStateException("Native palette changed after Earth preparation");
            for(var material:EarthAlpineDefinition.Material.values())if(states[material.ordinal()]!=stateId(nativeState(material)))throw new IllegalStateException("Native Earth state identity changed");
            int[] blocks=new int[CubePos.VOLUME];
            for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                cancellation.throwIfCancelled();EarthAlpineDefinition.Column column;
                synchronized(sourceLock){cancellation.throwIfCancelled();column=sampleLocked(position.minBlockX()+x,position.minBlockZ()+z);}
                cancellation.throwIfCancelled();
                for(int y=0;y<16;y++)blocks[CubePos.index(x,y,z)]=states[column.materialAt(position.minBlockY()+y).ordinal()];
            }
            cancellation.throwIfCancelled();return CompletableFuture.completedFuture(ImmutableCube.copyOf(blocks));
        }catch(RuntimeException failure){return CompletableFuture.failedFuture(failure);}
    }
    private static int stateId(BlockState state){int id=Block.BLOCK_STATE_REGISTRY.getId(state);if(NativeCubeCodec.registeredState(id)!=state)throw new IllegalStateException("Native registry state identity mismatch");return id;}
}
