package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded opaque cube summary. Unknown neighbors defer the whole mesh; they never become air. */
public final class DhSnapshotMesher {
    public record Summary(int pageY,int sourceBlocks,List<DhOfficialRuntime.Face> faces) {
        public Summary{faces=List.copyOf(faces);}
    }
    private DhSnapshotMesher(){ }
    public static Summary summarize(EngineCubeSnapshot snapshot,EnginePaletteContext palette) {
        var nativeSection=snapshot.materialize(palette);var cube=snapshot.position();
        int pageY=Math.toIntExact(Math.floorDiv(cube.minBlockY(),2048));
        int localY=Math.toIntExact(cube.minBlockY()-(long)pageY*2048);
        if(pageY < -6||pageY>4||cube.minBlockY() < -11008||cube.minBlockY()+16>8864)
            throw new IllegalArgumentException("Cube outside the native Earth fixture envelope");
        BlockState[] states=new BlockState[CubePos.VOLUME];int sourceBlocks=0;
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            var state=nativeSection.get(x,y,z);states[CubePos.index(x,y,z)]=state;
            if(state.isAir())continue;
            color(state);sourceBlocks++;
            if(x==0||x==15||y==0||y==15||z==0||z==15)
                throw new IllegalArgumentException("Unknown neighbor outside the admitted cube; defer boundary mesh");
        }
        List<DhOfficialRuntime.Face> faces=new ArrayList<>();
        for(int y=1;y<15;y++)for(int z=1;z<15;z++)for(int x=1;x<15;x++) {
            var state=states[CubePos.index(x,y,z)];if(state.isAir())continue;
            int color=color(state),py=localY+y;
            if(air(states,x,y+1,z))faces.add(new DhOfficialRuntime.Face("UP",x,py+1,z,color));
            if(air(states,x,y-1,z))faces.add(new DhOfficialRuntime.Face("DOWN",x,py,z,shade(color,0.5f)));
            if(air(states,x,y,z-1))faces.add(new DhOfficialRuntime.Face("NORTH",x,py,z,shade(color,0.8f)));
            if(air(states,x,y,z+1))faces.add(new DhOfficialRuntime.Face("SOUTH",x,py,z+1,shade(color,0.8f)));
            if(air(states,x-1,y,z))faces.add(new DhOfficialRuntime.Face("WEST",x,py,z,shade(color,0.6f)));
            if(air(states,x+1,y,z))faces.add(new DhOfficialRuntime.Face("EAST",x+1,py,z,shade(color,0.6f)));
            if(faces.size()>4096)throw new IllegalArgumentException("DH face budget exceeded");
        }
        return new Summary(pageY,sourceBlocks,faces);
    }
    private static boolean air(BlockState[] states,int x,int y,int z){return states[CubePos.index(x,y,z)].isAir();}
    private static int color(BlockState state) {
        if(state==Blocks.GOLD_BLOCK.defaultBlockState())return 0xFFF2C94C;
        if(state==Blocks.DIAMOND_BLOCK.defaultBlockState())return 0xFF52DCD3;
        if(state==Blocks.STONE.defaultBlockState())return 0xFF8A8A8A;
        if(state==Blocks.DIRT.defaultBlockState())return 0xFF896043;
        if(state==Blocks.GRASS_BLOCK.defaultBlockState())return 0xFF72A342;
        throw new IllegalArgumentException("Unsupported opaque native fixture state; no guessed LOD material");
    }
    private static int shade(int color,float factor) {
        return 0xFF000000|Math.round(((color>>>16)&255)*factor)<<16|Math.round(((color>>>8)&255)*factor)<<8|Math.round((color&255)*factor);
    }
}
