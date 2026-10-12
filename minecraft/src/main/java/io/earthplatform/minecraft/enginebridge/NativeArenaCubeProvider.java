package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.*;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Native cube data delegates the exact immutable shared arena source, including its bounded cave. */
public final class NativeArenaCubeProvider implements CubeProvider {
    public static final String GENERATOR_ID="earthplatform:bounded-playable-arena";
    public static final String GENERATOR_VERSION="bounded-playable-arena-v1";
    private final ArenaTerrainSource source;
    private final WorldIdentity identity;
    private final int[] states;
    public NativeArenaCubeProvider(ArenaTerrainSource source,EnginePaletteContext palette){this(0L,source,palette);}
    public NativeArenaCubeProvider(long seed,EnginePaletteContext palette){this(seed,ArenaTerrainSource.FIXED,palette);}
    public NativeArenaCubeProvider(long seed,ArenaTerrainSource source,EnginePaletteContext palette){
        this.source=Objects.requireNonNull(source);Objects.requireNonNull(palette);
        if(!palette.fingerprint().equals(EnginePaletteContext.create(palette.biomes()).fingerprint()))throw new IllegalArgumentException("Exact current native palette required");
        identity=new WorldIdentity(seed,GENERATOR_ID,GENERATOR_VERSION,source.identity(),NativeCubeCodec.paletteIdentity(palette));
        states=new int[]{Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState()),Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState()),Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState())};
    }
    public ArenaTerrainSource source(){return source;} public WorldIdentity identity(){return identity;}
    @Override public CompletableFuture<ImmutableCube> load(CubePos position,CancellationToken cancellation){
        try{Objects.requireNonNull(position);Objects.requireNonNull(cancellation);cancellation.throwIfCancelled();int[] blocks=new int[CubePos.VOLUME];
            for(int y=0;y<16;y++)for(int z=0;z<16;z++){cancellation.throwIfCancelled();for(int x=0;x<16;x++){int material=source.materialAt(position.minBlockX()+x,position.minBlockY()+y,position.minBlockZ()+z);if(material<0||material>2)throw new IllegalStateException("Unsupported shared terrain material");blocks[CubePos.index(x,y,z)]=states[material];}}
            cancellation.throwIfCancelled();return CompletableFuture.completedFuture(ImmutableCube.copyOf(blocks));
        }catch(RuntimeException failure){return CompletableFuture.failedFuture(failure);}
    }
}
