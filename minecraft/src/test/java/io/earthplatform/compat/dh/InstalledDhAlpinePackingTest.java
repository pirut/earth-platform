package io.earthplatform.compat.dh;
import io.earthplatform.minecraft.lod.DhTerrainGeometry;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Actual pinned builder proof: every face retains its own STONE/SNOW category. */
class InstalledDhAlpinePackingTest {
    @Test void explicitSnowAndStoneOverrideLegacyShaderDefaultWithoutChangingOtherPackedFields()throws Exception{
        var runtime=InstalledDhIndependentHelperTest.runtime();
        assertEquals(8,com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial.SNOW.index);
        for(int page:new int[]{-1,0,3,4})for(var direction:DhTerrainGeometry.Direction.values()){
            int y=page==4?500:1000;
            var snow=new DhTerrainGeometry.PageFace(direction,page,16,y,32,4,4,0xfff1f5f6,8);
            var stone=new DhTerrainGeometry.PageFace(direction,page,16,y,32,4,4,0xfff1f5f6,2);
            byte[] a=runtime.buildRectangles(List.of(snow),(byte)2,()->{}),b=runtime.buildRectangles(List.of(stone),(byte)0,()->{});
            assertEquals(64,a.length);assertEquals(64,b.length);
            for(int vertex=0;vertex<4;vertex++)for(int field=0;field<16;field++){
                if(field==12){assertEquals(8,Byte.toUnsignedInt(a[vertex*16+field]));assertEquals(2,Byte.toUnsignedInt(b[vertex*16+field]));}
                else assertEquals(a[vertex*16+field],b[vertex*16+field]);
            }
        }
    }
    @Test void mixedAdjacentFacesCannotMergeAcrossMaterialBoundary()throws Exception{
        var runtime=InstalledDhIndependentHelperTest.runtime();
        var snow=new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.UP,3,0,1000,0,4,4,0xfff1f5f6,8);
        var stone=new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.UP,3,4,1000,0,4,4,0xfff1f5f6,2);
        byte[] packed=runtime.buildRectangles(List.of(snow,stone),(byte)2,()->{});
        assertEquals(128,packed.length);int snowVertices=0,stoneVertices=0;
        for(int at=12;at<packed.length;at+=16){if(packed[at]==8)snowVertices++;if(packed[at]==2)stoneVertices++;}
        assertEquals(4,snowVertices);assertEquals(4,stoneVertices);
    }
    @Test void geographicOwnedOriginKeepsLargeSignedCoordinatesOutOfPackedShorts()throws Exception{
        var runtime=InstalledDhIndependentHelperTest.runtime();
        long originX=9_678_063L,originZ=-3_114_289L;
        for(var direction:DhTerrainGeometry.Direction.values()){
            var face=new DhTerrainGeometry.Face(direction,originX+6,7318,originZ+7,1,1,0xfff1f5f6,8);
            var pages=DhTerrainGeometry.splitPages(face,originX,originZ);
            assertEquals(1,pages.size());var page=pages.getFirst();
            assertEquals(6,page.x());assertEquals(7,page.z());assertEquals(3,page.pageY2048());assertEquals(1174,page.y());
            var bytes=runtime.buildRectangles(pages,(byte)2,()->{});
            assertEquals(64,bytes.length);
            var packed=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for(int vertex=0;vertex<4;vertex++){
                int at=vertex*16;int x=Short.toUnsignedInt(packed.getShort(at));int y=Short.toUnsignedInt(packed.getShort(at+2));int z=Short.toUnsignedInt(packed.getShort(at+4));
                assertTrue(x>=6&&x<=7);assertTrue(y>=1174&&y<=1175);assertTrue(z>=7&&z<=8);
                assertEquals(8,Byte.toUnsignedInt(bytes[at+12]));
            }
        }
    }
}
