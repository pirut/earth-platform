package io.earthplatform.compat.dh;

import io.earthplatform.minecraft.lod.DhTerrainGeometry;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual installed pinned helper bytes only. No headless result is a GL/shader visual claim. */
class InstalledDhAlpineIndependentPackingReviewTest {
    private static DhTerrainGeometry.PageFace face(int x,int material) {
        return new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.UP,-1,x,2048,16,4,4,0xff929292,material);
    }
    private static java.util.Map<Integer,Integer> categories(byte[] bytes) {
        assertEquals(0,bytes.length%64);var counts=new HashMap<Integer,Integer>();
        for(int offset=12;offset<bytes.length;offset+=16)counts.merge(Byte.toUnsignedInt(bytes[offset]),1,Integer::sum);
        return counts;
    }
    @Test void mixedExplicitCategoriesAndLegacyDefaultsRemainLocalToEachFaceAndBuild()throws Exception {
        var runtime=InstalledDhIndependentHelperTest.runtime();
        var input=List.of(face(0,0),face(16,2),face(32,8));
        assertEquals(java.util.Map.of(0,4,2,4,8,4),categories(runtime.buildRectangles(input,(byte)0,()->{})));
        assertEquals(java.util.Map.of(2,8,8,4),categories(runtime.buildRectangles(input,(byte)2,()->{})));
        assertEquals(java.util.Map.of(0,4),categories(runtime.buildRectangles(List.of(face(0,0)),(byte)0,()->{})));
        assertEquals(java.util.Map.of(8,4),categories(runtime.buildRectangles(List.of(face(0,8)),(byte)2,()->{})));
    }
    @Test void everyMixedMaterialCancellationAndTransparentFailureLeavesNoCategoryResidue()throws Exception {
        var runtime=InstalledDhIndependentHelperTest.runtime();
        var mixed=List.of(face(0,8),face(16,2),face(32,0));
        var count=new AtomicInteger();runtime.buildRectangles(mixed,(byte)2,count::incrementAndGet);
        int visits=count.get();assertTrue(visits>=7);
        byte[] expected=runtime.buildRectangles(List.of(face(48,0)),(byte)0,()->{});
        for(int stop=1;stop<=visits;stop++) {
            int at=stop;var seen=new AtomicInteger();var signal=new CancellationException("Independent material checkpoint "+at);
            assertSame(signal,assertThrows(CancellationException.class,()->runtime.buildRectangles(mixed,(byte)2,()->{if(seen.incrementAndGet()==at)throw signal;})));
            assertArrayEquals(expected,runtime.buildRectangles(List.of(face(48,0)),(byte)0,()->{}));
        }
        var transparent=new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.UP,0,0,1,0,1,1,0x7fffffff,8);
        assertThrows(IllegalArgumentException.class,()->runtime.buildRectangles(List.of(transparent),(byte)2,()->{}));
        assertArrayEquals(expected,runtime.buildRectangles(List.of(face(48,0)),(byte)0,()->{}));
    }
}
