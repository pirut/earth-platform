package io.earthplatform.compat.dh;
import io.earthplatform.minecraft.lod.DhTerrainGeometry;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Executes the externally supplied pinned official builder, with no shader or GL emulation. */
class InstalledDhShaderPackingTest {
    @Test void opaqueStoneUsesActualMaterialByteWithoutChangingCoordinatesColourNormalsOrLight()throws Exception{
        var runtime=InstalledDhIndependentHelperTest.runtime();
        assertEquals(2,com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial.STONE.index);
        for(int page:new int[]{-6,-1,0,4})for(var direction:DhTerrainGeometry.Direction.values()){
            var face=new DhTerrainGeometry.PageFace(direction,page,16,page==-6?1288:page==4?608:1984,32,4,4,0xffd0d1d2);
            byte[] stock=runtime.buildRectangles(List.of(face),(byte)0,()->{}),stone=runtime.buildRectangles(List.of(face),(byte)2,()->{});
            assertEquals(64,stone.length);
            for(int vertex=0;vertex<4;vertex++){
                assertEquals(2,Byte.toUnsignedInt(stone[vertex*16+12]));assertEquals(0,stock[vertex*16+12]);
                int packedLight=Short.toUnsignedInt(java.nio.ByteBuffer.wrap(stone).order(java.nio.ByteOrder.nativeOrder()).getShort(vertex*16+6));
                assertEquals(15,packedLight&15);assertEquals(0,(packedLight>>4)&15);
                assertEquals(255,Byte.toUnsignedInt(stone[vertex*16+11]));
                for(int offset=0;offset<16;offset++)if(offset!=12)assertEquals(stock[vertex*16+offset],stone[vertex*16+offset],direction+" field "+offset);
            }
        }
    }
    @Test void unsupportedMaterialsRejectBeforeCallbacksAndCancelledBuildReleasesPool()throws Exception{
        var runtime=InstalledDhIndependentHelperTest.runtime();var face=InstalledDhIndependentHelperTest.top(0,11);var reads=new AtomicInteger();
        for(int material:new int[]{-1,1,3,14,15,127})assertThrows(IllegalArgumentException.class,()->runtime.buildRectangles(List.of(face),(byte)material,reads::incrementAndGet));
        assertEquals(0,reads.get());byte[] expected=runtime.buildRectangles(List.of(face),(byte)2,reads::incrementAndGet);int checkpoints=reads.get();
        for(int stop=1;stop<=checkpoints;stop++){int exact=stop;var seen=new AtomicInteger();var failure=new CancellationException("material checkpoint");
            assertSame(failure,assertThrows(CancellationException.class,()->runtime.buildRectangles(List.of(face),(byte)2,()->{if(seen.incrementAndGet()==exact)throw failure;})));
            assertArrayEquals(expected,runtime.buildRectangles(List.of(face),(byte)2,()->{}));
        }
    }
}
