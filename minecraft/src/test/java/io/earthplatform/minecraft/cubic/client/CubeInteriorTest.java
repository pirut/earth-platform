package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;
import io.earthplatform.minecraft.cubic.CubeLight;
import io.earthplatform.minecraft.cubic.gen.SurfacePacketFixtures;
import java.util.Arrays;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CubeInteriorTest {
    @BeforeAll static void bootstrap() { SurfacePacketFixtures.biomes(); }
    @SuppressWarnings("unchecked") private static PalettedContainer<BlockState>[] buried() {
        var states = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[27];
        for (int i : new int[] {13,4,22,10,16,12,14})
            states[i]=CubeSnapshotCacheTest.section((i%2==0?Blocks.STONE:Blocks.DEEPSLATE).defaultBlockState());
        return states;
    }
    private static int[][] tops(int y) {
        int[][] tops = new int[9][];
        for (int i=0;i<9;i++) {tops[i]=new int[256];Arrays.fill(tops[i],y);}
        return tops;
    }

    @Test void enclosedStoneProducesExactlyTheOriginalCentreLightingEvenWithLitCorners() {
        for(int cy : new int[] {-750,-10,0,150}) {
            var states=buried();int[][] tops=tops((cy<<4)+15);
            states[0]=CubeSnapshotCacheTest.section(Blocks.GLOWSTONE.defaultBlockState());
            assertTrue(CubeInterior.enclosedStone(states,tops,cy));
            assertArrayEquals(new byte[4096],CubeLight.centre(CubeLight.light(states,tops,cy,true)));
            assertArrayEquals(new byte[4096],CubeLight.centre(CubeLight.light(states,tops,cy,false)));
        }
    }

    @Test void caveEditsFluidsEmittersAndMissingNeighboursKeepTheFullPath() {
        for(int index:new int[] {13,4,22,10,16,12,14}) {
            var missing=buried();missing[index]=null;
            assertFalse(CubeInterior.enclosedStone(missing,tops(100),0));
            for(var block:new net.minecraft.world.level.block.Block[] {Blocks.AIR,Blocks.WATER,Blocks.GLASS,Blocks.TORCH,Blocks.GLOWSTONE}) {
                var states=buried();states[index].set(0,0,0,block.defaultBlockState());
                assertFalse(CubeInterior.enclosedStone(states,tops(100),0));
            }
        }
    }

    @Test void incompleteOrInconsistentSkyTopsCannotChangeTheLightingOracle() {
        var states=buried();
        assertFalse(CubeInterior.enclosedStone(states,null,0));
        var missing=tops(100);missing[4]=null;
        assertFalse(CubeInterior.enclosedStone(states,missing,0));
        var stale=tops(100);stale[4][5]=14;
        assertFalse(CubeInterior.enclosedStone(states,stale,0));
    }
}
