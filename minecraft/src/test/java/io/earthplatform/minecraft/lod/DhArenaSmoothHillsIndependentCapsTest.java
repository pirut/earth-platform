package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.ArenaCapture;
import io.earthplatform.minecraft.enginebridge.ArenaTerrainSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static io.earthplatform.minecraft.lod.DhArenaTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Narrow independent final-029 adversaries; no duplicated whole-template census or GPU claims. */
@Timeout(60)
class DhArenaSmoothHillsIndependentCapsTest {
    private static final int[][] DELTAS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private record Cell(int x,int y,int z) {}

    @Test void bothLegacyIdentitiesAndEveryAdmittedGroupingRemainByteExact() {
        assertEquals("fixed-512-v1:square-radius=512:center-align=16:snap=64:rings=[Ring[radiusBlocks=512, cellSize=16, tileSize=256]]:fine=1:collar=1:fine-tile=32:native-y=[-11008,8864):full-xyz-clip=true:complete-footprint-required=false", DhLodLayout.FIXED_512.identity());
        assertEquals("long-4096-v1:square-radius=4096:center-align=128:snap=256:rings=[Ring[radiusBlocks=512, cellSize=16, tileSize=256], Ring[radiusBlocks=1024, cellSize=64, tileSize=1024], Ring[radiusBlocks=4096, cellSize=128, tileSize=4096]]:fine=1:collar=1:fine-tile=32:native-y=[-11008,8864):full-xyz-clip=true:complete-footprint-required=true", DhLodLayout.LONG_4096.identity());
        for (var legacy : List.of(DhLodLayout.FIXED_512, DhLodLayout.LONG_4096)) {
            for (int size=1;size<=legacy.outerCellSize();size*=2)
                assertEquals(size<16?32:size==16?256:size<=64?1024:4096,legacy.tileSizeForCell(size));
            for (int invalid : new int[] {-1,0,3,6,12,256,Integer.MAX_VALUE})
                assertThrows(IllegalArgumentException.class,()->legacy.tileSizeForCell(invalid));
        }
        assertEquals(512,DhLodLayout.ARENA_4096.tileSizeForCell(8));
        assertTrue(DhLodLayout.ARENA_4096.identity().endsWith(":fine-cell-below=8"));
    }

    @Test void completeLongDomainRejectsBeforeArithmeticAndIdentityBindsThePolicyBytes() throws Exception {
        assertEquals("643517cefb6fa527380f203c504d4c02912740cdbf1d066509c2e9d7eb71c2fa",SOURCE.identity());
        assertEquals(SOURCE.identity(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(SOURCE.canonicalIdentity().getBytes(StandardCharsets.UTF_8))));
        long[] outsideX={Long.MIN_VALUE,Long.MIN_VALUE+335,-4_294_967_297L,-321,-320,336,337,4_294_967_297L,Long.MAX_VALUE-336,Long.MAX_VALUE};
        long[] outsideZ={Long.MIN_VALUE,Long.MIN_VALUE+351,-4_294_967_297L,-353,-352,352,353,4_294_967_297L,Long.MAX_VALUE-352,Long.MAX_VALUE};
        long[] probes={Long.MIN_VALUE,-352,-320,-160,-144,0,16,48,96,144,176,336,352,Long.MAX_VALUE};
        for(long x:outsideX) for(long z:probes) assertFlat(x,z);
        for(long z:outsideZ) for(long x:probes) assertFlat(x,z);
        assertTrue(SOURCE.knownFootprint(Long.MIN_VALUE,Long.MIN_VALUE,Long.MAX_VALUE,Long.MAX_VALUE));
        assertFalse(SOURCE.knownFootprint(Long.MAX_VALUE,0,Long.MIN_VALUE,1));
    }
    private static void assertFlat(long x,long z) {
        assertEquals(144,SOURCE.baseSurfaceY(x,z));
        assertEquals(ArenaTerrainSource.STONE,SOURCE.materialAt(x,Long.MIN_VALUE,z));
        assertEquals(ArenaTerrainSource.STONE,SOURCE.materialAt(x,144,z));
        assertEquals(ArenaTerrainSource.AIR,SOURCE.materialAt(x,145,z));
        assertEquals(ArenaTerrainSource.AIR,SOURCE.materialAt(x,Long.MAX_VALUE,z));
    }
    private static ArenaCapture remove(ArenaCapture before,Cell c) {
        assertNotEquals(ArenaTerrainSource.AIR,before.materialAt(c.x,c.y,c.z));
        return before.compact().withMaterial(c.x,c.y,c.z,ArenaTerrainSource.AIR);
    }
    private static List<Cell> cavities(CubePos cube) {
        var cells=new ArrayList<Cell>();
        for(int y=(int)cube.minBlockY()+1;y<cube.minBlockY()+15;y++)
            for(int z=(int)cube.minBlockZ()+1;z<cube.minBlockZ()+15;z++)
                for(int x=(int)cube.minBlockX()+1;x<cube.minBlockX()+15;x++) {
                    if(((x+y+z)&1)!=0||SOURCE.materialAt(x,y,z)==0)continue;
                    boolean buried=true;for(var d:DELTAS)if(SOURCE.materialAt(x+d[0],y+d[1],z+d[2])==0)buried=false;
                    if(buried)cells.add(new Cell(x,y,z));
                }
        return cells;
    }
}
