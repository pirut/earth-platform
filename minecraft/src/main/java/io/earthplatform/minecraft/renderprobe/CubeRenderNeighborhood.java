package io.earthplatform.minecraft.renderprobe;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/** Immutable bounded native-state neighborhood. Fixed fixture illumination, never unknown-as-air. */
public final class CubeRenderNeighborhood implements BlockAndTintGetter {
    private final Map<CubePos,BlockState[]> cubes;
    public CubeRenderNeighborhood(List<EngineCubeSnapshot> snapshots,EnginePaletteContext palette) {
        if(snapshots.isEmpty()||snapshots.size()>2)throw new IllegalArgumentException("One or two snapshot neighborhoods required");
        Map<CubePos,BlockState[]> loaded=new HashMap<>();
        for(var snapshot:snapshots) {
            var nativeSection=snapshot.materialize(palette);BlockState[] states=new BlockState[CubePos.VOLUME];
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                var state=nativeSection.get(x,y,z);
                if(state!=Blocks.AIR.defaultBlockState()&&state!=Blocks.GOLD_BLOCK.defaultBlockState()&&state!=Blocks.DIAMOND_BLOCK.defaultBlockState())
                    throw new SparseFacadeException("render fixture permits only known air/gold/diamond default states");
                states[CubePos.index(x,y,z)]=state;
            }
            if(loaded.put(snapshot.position(),states)!=null)throw new IllegalArgumentException("Duplicate render cube");
        }
        cubes=Map.copyOf(loaded);
    }
    @Override public BlockState getBlockState(BlockPos pos) {
        var cube=CubePos.fromBlock(pos.getX(),pos.getY(),pos.getZ());var states=cubes.get(cube);
        if(states==null)throw new SparseFacadeException("unknown mesh dependency at "+pos+"; defer mesh, never invent air");
        return states[CubePos.index(pos.getX()&15,pos.getY()&15,pos.getZ()&15)];
    }
    @Override public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
    @Override public BlockEntity getBlockEntity(BlockPos pos){getBlockState(pos);return null;}
    @Override public int getHeight(){return 19872;}
    @Override public int getMinBuildHeight(){return -11008;}
    @Override public float getShade(Direction direction,boolean shade) {
        if(!shade)return 1;
        return switch(direction){case DOWN->0.5f;case UP->1f;case NORTH,SOUTH->0.8f;case WEST,EAST->0.6f;};
    }
    @Override public LevelLightEngine getLightEngine(){throw new SparseFacadeException("render fixture does not have a lighting engine");}
    @Override public int getBrightness(LightLayer layer,BlockPos pos){getBlockState(pos);return 15;}
    @Override public int getRawBrightness(BlockPos pos,int ambient){getBlockState(pos);return Math.max(0,15-ambient);}
    @Override public int getBlockTint(BlockPos pos,ColorResolver resolver){getBlockState(pos);return 0xFFFFFF;}
}
