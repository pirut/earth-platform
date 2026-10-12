package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.util.Random;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent array oracle is test-only; production remains scalar and retains no census grid. */
class EarthNativeIndependentCensusTest {
    private static final CubePos TARGET=new CubePos(-5,457,-7);
    private static final int[][] DIRECTIONS={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
    private static int at(int x,int y,int z){return ((y+1)*18+(z+1))*18+x+1;}
    private static int oracle(int[] cells,int air) {
        int faces=0;
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)
            if(cells[at(x,y,z)]!=air)for(var d:DIRECTIONS)if(cells[at(x+d[0],y+d[1],z+d[2])]==air)faces++;
        return faces;
    }
    private static EarthNativeEditAdmission.StateReader reader(int[] cells) {
        return (x,y,z)->{
            int lx=Math.toIntExact(x-TARGET.minBlockX()),ly=Math.toIntExact(y-TARGET.minBlockY()),lz=Math.toIntExact(z-TARGET.minBlockZ());
            assertTrue(lx>=-1&&lx<=16&&ly>=-1&&ly<=16&&lz>=-1&&lz<=16);
            return cells[at(lx,ly,lz)];
        };
    }

    @Test void seededMixedOpaqueNeighborhoodsAgreeWithIndependentSixDirectionOracle() {
        int air=Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        int[] states=EarthNativeEditAdmission.permittedStates().stream().mapToInt(Block.BLOCK_STATE_REGISTRY::getId).toArray();
        var random=new Random(0x030EA471L);
        for(int trial=0;trial<24;trial++) {
            int[] cells=new int[18*18*18];
            for(int i=0;i<cells.length;i++)cells[i]=random.nextInt(4)==0?air:states[1+random.nextInt(states.length-1)];
            int expected=oracle(cells,air);var actual=EarthNativeEditAdmission.assessCurrent(TARGET,reader(cells));
            assertEquals(expected,actual.faces());assertEquals(expected*208L,actual.bytes());
            assertEquals(expected<=5041,actual.admitted());
        }
    }

    @Test void virtualPlacementAndRemovalMatchMutatedOracleWithoutChangingTheSource() {
        int air=Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        int stone=Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
        int diorite=Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState());
        var random=new Random(0x52B17EL);
        int[] source=new int[18*18*18];for(int i=0;i<source.length;i++)source[i]=random.nextBoolean()?stone:air;
        int[] original=source.clone();
        for(int i=0;i<24;i++) {
            int x=1+random.nextInt(14),y=1+random.nextInt(14),z=1+random.nextInt(14),state=(i&1)==0?air:diorite;
            int[] expected=source.clone();expected[at(x,y,z)]=state;
            var actual=EarthNativeEditAdmission.assessEdit(TARGET,TARGET.minBlockX()+x,TARGET.minBlockY()+y,TARGET.minBlockZ()+z,state,reader(source));
            assertEquals(oracle(expected,air),actual.faces());assertArrayEquals(original,source);
        }
    }

    @Test void differentlyColoredOpaqueMaterialsNeverCreateInternalFaces() {
        int[] states=EarthNativeEditAdmission.permittedStates().stream().filter(s->!s.isAir()).mapToInt(Block.BLOCK_STATE_REGISTRY::getId).toArray();
        var result=EarthNativeEditAdmission.assessCurrent(TARGET,(x,y,z)->states[Math.floorMod(x+y+z,states.length)]);
        assertEquals(0,result.faces());assertEquals(0,result.bytes());assertTrue(result.admitted());
    }

    @Test void missingSourceOrUnregisteredNativeStateCannotProduceAnAdmission() {
        int stone=Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
        var missing=new io.earthplatform.geography.MissingTerrainException("Independent missing dependency");
        assertSame(missing,assertThrows(io.earthplatform.geography.MissingTerrainException.class,
                ()->EarthNativeEditAdmission.assessCurrent(TARGET,(x,y,z)->{throw missing;})));
        assertThrows(IllegalArgumentException.class,()->EarthNativeEditAdmission.assessCurrent(TARGET,(x,y,z)->-1));
        assertSame(missing,assertThrows(io.earthplatform.geography.MissingTerrainException.class,
                ()->EarthNativeEditAdmission.assessEdit(TARGET,TARGET.minBlockX()+4,TARGET.minBlockY()+4,
                        TARGET.minBlockZ()+4,stone,(x,y,z)->{throw missing;})));
    }

    @Test void allSixImmutableShellPlanesRejectBeforeReadingOrChangingTheSource() {
        int stone=Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        EarthNativeEditAdmission.StateReader source=(x,y,z)->{reads.incrementAndGet();return stone;};
        for(int[] p:new int[][]{{0,4,4},{15,4,4},{4,0,4},{4,15,4},{4,4,0},{4,4,15}})
            assertThrows(IllegalArgumentException.class,()->EarthNativeEditAdmission.assessEdit(TARGET,
                    TARGET.minBlockX()+p[0],TARGET.minBlockY()+p[1],TARGET.minBlockZ()+p[2],stone,source));
        assertEquals(0,reads.get());
    }
}
