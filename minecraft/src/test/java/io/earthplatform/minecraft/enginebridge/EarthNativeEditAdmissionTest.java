package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EarthNativeEditAdmissionTest {
    private static final CubePos TARGET=new CubePos(0,8,0);
    private static int id(net.minecraft.world.level.block.Block block){return Block.BLOCK_STATE_REGISTRY.getId(block.defaultBlockState());}
    @Test void exactAlpinePaletteExcludesOtherVisuallySupportedStates(){
        assertEquals(6,EarthNativeEditAdmission.permittedStates().size());
        assertEquals(6,new HashSet<>(EarthNativeEditAdmission.permittedStates()).size());
        for(var block:new net.minecraft.world.level.block.Block[]{Blocks.AIR,Blocks.STONE,Blocks.ANDESITE,Blocks.TUFF,Blocks.SNOW_BLOCK,Blocks.DIORITE})
            assertTrue(EarthNativeEditAdmission.supported(block.defaultBlockState()));
        for(var block:new net.minecraft.world.level.block.Block[]{Blocks.GRASS_BLOCK,Blocks.GRAVEL,Blocks.PACKED_ICE,Blocks.WATER,Blocks.OAK_LEAVES,Blocks.CAVE_AIR})
            assertFalse(EarthNativeEditAdmission.supported(block.defaultBlockState()));
    }
    @Test void irisByteCapIsStricterThanLegacyVertexCount(){
        assertEquals(52,EarthNativeEditAdmission.IRIS_STRIDE);
        assertEquals(208,EarthNativeEditAdmission.BYTES_PER_FACE);
        assertEquals(5041,EarthNativeEditAdmission.MAX_FACES);
        assertEquals(io.earthplatform.minecraft.scene.SceneMesh.MAX_VERTEX_BYTES,EarthNativeEditAdmission.MAX_VERTEX_BYTES);
        assertEquals(io.earthplatform.minecraft.scene.SceneMesh.MAX_VERTICES,EarthNativeEditAdmission.MAX_VERTICES);
        assertTrue(EarthNativeEditAdmission.bytesForFaces(5041)<=1_048_576);
        assertTrue(EarthNativeEditAdmission.bytesForFaces(5042)>1_048_576);
        assertThrows(IllegalArgumentException.class,()->EarthNativeEditAdmission.bytesForFaces(-1));
        assertDoesNotThrow(()->EarthNativeEditAdmission.requireStride(52));
        for(int wrong:new int[]{0,32,48,56})assertThrows(IllegalStateException.class,()->EarthNativeEditAdmission.requireStride(wrong));
    }
    @Test void allSixNeighborsAndActualBoundaryOcclusionAreCounted(){
        var hidden=EarthNativeEditAdmission.assessCurrent(TARGET,(x,y,z)->id(Blocks.STONE));
        assertEquals(0,hidden.faces());assertTrue(hidden.admitted());
        var exposed=EarthNativeEditAdmission.assessCurrent(TARGET,(x,y,z)->x>=0&&x<16&&y>=128&&y<144&&z>=0&&z<16?id(Blocks.SNOW_BLOCK):id(Blocks.AIR));
        assertEquals(6*256,exposed.faces());assertEquals(6L*256*208,exposed.bytes());
        var hole=EarthNativeEditAdmission.assessEdit(TARGET,4,132,4,id(Blocks.AIR),(x,y,z)->id(Blocks.TUFF));
        assertEquals(6,hole.faces());
        var placed=EarthNativeEditAdmission.assessEdit(TARGET,4,132,4,id(Blocks.DIORITE),(x,y,z)->id(Blocks.AIR));
        assertEquals(6,placed.faces());
    }
    @Test void interiorCheckerboardExceedsCapWithoutRetainingOrMutatingSource(){
        AtomicInteger reads=new AtomicInteger();
        var receipt=EarthNativeEditAdmission.assessCurrent(TARGET,(x,y,z)->{
            reads.incrementAndGet();return x>0&&x<15&&y>128&&y<143&&z>0&&z<15&&((x+y+z)&1)==0?id(Blocks.STONE):id(Blocks.AIR);
        });
        assertEquals(1372*6,receipt.faces());assertFalse(receipt.admitted());
        assertTrue(reads.get()<=4096*7);assertEquals(EarthNativeEditAdmission.bytesForFaces(receipt.faces()),receipt.bytes());
    }
    @Test void exactThresholdAndVirtualEditLeaveSourceUntouched(){
        Set<String> cells=new HashSet<>();String next=null;
        outer:for(int y=129;y<143;y++)for(int z=1;z<15;z++)for(int x=1;x<15;x++)if(((x+y+z)&1)==0){
            if(cells.size()==840){next=x+","+y+","+z;break outer;}cells.add(x+","+y+","+z);
        }
        final Set<String> source=Set.copyOf(cells);
        EarthNativeEditAdmission.StateReader reader=(x,y,z)->source.contains(x+","+y+","+z)?id(Blocks.STONE):id(Blocks.AIR);
        assertEquals(5040,EarthNativeEditAdmission.assessCurrent(TARGET,reader).faces());
        String[] xyz=next.split(",");
        var receipt=EarthNativeEditAdmission.assessEdit(TARGET,Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2]),id(Blocks.DIORITE),reader);
        assertEquals(5046,receipt.faces());assertFalse(receipt.admitted());assertEquals(840,source.size());
        assertEquals(5040,EarthNativeEditAdmission.assessCurrent(TARGET,reader).faces());
    }
    @Test void unsupportedProposedCurrentOrNeighborStateFailsClosed(){
        assertThrows(IllegalArgumentException.class,()->EarthNativeEditAdmission.assessEdit(TARGET,4,132,4,id(Blocks.GRASS_BLOCK),(x,y,z)->id(Blocks.AIR)));
        assertThrows(IllegalArgumentException.class,()->EarthNativeEditAdmission.assessCurrent(TARGET,(x,y,z)->id(Blocks.PACKED_ICE)));
        assertThrows(IllegalArgumentException.class,()->EarthNativeEditAdmission.assessCurrent(TARGET,(x,y,z)->x==-1?id(Blocks.WATER):id(Blocks.STONE)));
        assertThrows(IllegalArgumentException.class,()->EarthNativeEditAdmission.assessEdit(TARGET,0,132,4,id(Blocks.AIR),(x,y,z)->id(Blocks.STONE)));
    }
}
