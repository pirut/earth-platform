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

    @Test void tightestPhysicalBaseOwnerSurvivesEveryAllowedYAndAdversarialPartialReadyMasks() {
        var sampler=new DhArenaSurfaceSampler(SOURCE);
        var data=new DhAdaptiveTerrainData(sampler,256,256,DhLodLayout.ARENA_4096);
        var refinement=new ArrayList<CubePos>(mask(new CubePos(8,9,8)));refinement.addAll(DEF.positions());
        var volume=List.of(DhArenaGeometry.volume(DEF));
        var original=data.clip(List.of(),refinement,volume,()->{});
        var tight=new DhGeographicLodData.TileKey(0,0,0);
        assertEquals(4056,original.tiles().get(tight).size());
        int cases=0;
        for(int cy=DEF.MIN_CENTER_Y;cy<=DEF.MAX_CENTER_Y;cy++) {
            var complete=mask(new CubePos(8,cy,8));var partial=new ArrayList<CubePos>();
            for(int i=0;i<complete.size();i++) if((i&1)==0) partial.add(complete.get(i));
            Collections.reverse(partial);partial.add(partial.getFirst());
            for(var ready:List.of(complete,partial)) {
                var actual=data.clip(ready,refinement,volume,()->{});
                assertEquals(original.sampledCells(),actual.sampledCells());
                assertTrue(actual.faces()<=original.faces());assertTrue(actual.tiles().size()<=original.tiles().size());
                actual.tiles().forEach((key,faces)->{
                    assertTrue(original.tiles().containsKey(key),"Clipping invented a physical owner");
                    var before=new HashSet<>(original.tiles().get(key));
                    assertEquals(faces.size(),new HashSet<>(faces).size(),"Duplicate visible faces");
                    assertTrue(before.containsAll(faces),"Every surviving face retains exact geometry, color and owner");
                    assertTrue(faces.size()<=original.tiles().get(key).size());assertTrue(faces.size()<=4096);
                });cases++;
            }
        }
        assertEquals(20,cases);
        System.out.println("INDEPENDENT_TIGHT_OWNER cases="+cases+" maxBasePage=4056 exactWholeFaceSubset=true");
    }

    @Test void accumulatedIsolatedEditsCrossTheRealNativeByteCapAndRecoverWithoutStaleAdmission() {
        var baseline=capture();var candidate=baseline;var cells=cavities(new CubePos(0,8,0));
        assertTrue(cells.size()>841);
        var admission=new DhArenaAdmission(SOURCE);
        assertEquals(0,admission.inspect(baseline,()->{}).arena().nativeFaces().get(new CubePos(0,8,0)));
        for(int i=0;i<840;i++) candidate=remove(candidate,cells.get(i));
        var accepted=candidate;String acceptedIdentity=accepted.contentIdentity();
        var before=admission.inspect(accepted,()->{});
        assertEquals(5040,before.arena().maxNativeFaces());assertEquals(1_048_320,before.arena().maxNativeVertexBytes());
        assertTrue(before.admitted());assertTrue(admission.assess(accepted).admitted());
        var last=cells.get(840);var rejected=remove(accepted,last);var after=admission.inspect(rejected,()->{});
        assertEquals(5046,after.arena().maxNativeFaces());assertEquals(1_049_568,after.arena().maxNativeVertexBytes());
        assertTrue(after.farFaces()<32768);assertTrue(after.farOwners()<64);
        assertFalse(after.admitted());assertFalse(admission.assess(rejected).admitted());
        var hidden=input(new Guard(),1,0,rejected);
        assertThrows(DhReplacementComposition.Declined.class,()->DhArenaComposition.build(0,0,DhLodLayout.ARENA_4096,
                mask(new CubePos(0,9,0)),hidden.data(),DhReplacementComposition.MAX_RESULT_BYTES,()->{}));
        candidate=rejected.compact().withMaterial(last.x,last.y,last.z,SOURCE.materialAt(last.x,last.y,last.z));
        assertEquals(acceptedIdentity,candidate.contentIdentity());assertTrue(admission.assess(candidate).admitted());
        assertEquals(acceptedIdentity,accepted.contentIdentity());assertEquals(ArenaTerrainSource.STONE,baseline.materialAt(cells.getFirst().x,cells.getFirst().y,cells.getFirst().z));
        System.out.println("INDEPENDENT_CUMULATIVE_NATIVE acceptedEdits=840 bytes=1048320 rejectedEdits=841 bytes=1049568 hiddenMaskStillRefuses=true recovery=true");
    }

    @Test void distributedEditsHitFarFaceReservationBeforeNativeOwnerOrMemoryLimits() {
        var baseline=capture();var admission=new DhArenaAdmission(SOURCE);var initial=admission.inspect(baseline,()->{});
        // Each selected hole is originally surrounded by six solid cells, lies inside
        // one native cube and has even XYZ parity. Holes never touch, so each adds exactly6 faces.
        int limit=(32768-DhArenaAdmission.MAX_BASE_FACES-initial.arena().sourceFaces())/6;
        assertEquals(2490,limit);
        var perCube=List.of(cavities(new CubePos(0,8,0)),cavities(new CubePos(0,8,1)),
                cavities(new CubePos(1,8,0)),cavities(new CubePos(1,8,1)));
        var candidate=baseline;
        for(int i=0;i<limit;i++) candidate=remove(candidate,perCube.get(i%4).get(i/4));
        var accepted=candidate;var before=admission.inspect(accepted,()->{});
        assertEquals(initial.arena().sourceFaces()+limit*6,before.arena().sourceFaces());
        assertEquals(32767,before.farFaces());assertTrue(before.admitted());
        var last=perCube.get(limit%4).get(limit/4);candidate=remove(candidate,last);
        var after=admission.inspect(candidate,()->{});
        assertEquals(32773,after.farFaces());assertEquals(before.arena().sourceFaces()+6,after.arena().sourceFaces());
        assertTrue(after.arena().maxNativeVertexBytes()<1_048_576);assertTrue(after.farOwners()<64);
        assertTrue(after.resultBytes()<5L*1024*1024);assertTrue(after.oldNewGpuBytes()<8L*1024*1024);
        assertFalse(after.admitted());assertFalse(admission.assess(candidate).admitted());
        var hidden=input(new Guard(),1,0,candidate);
        assertThrows(DhReplacementComposition.Declined.class,()->DhArenaComposition.build(0,0,DhLodLayout.ARENA_4096,
                mask(new CubePos(0,9,0)),hidden.data(),DhReplacementComposition.MAX_RESULT_BYTES,()->{}));
        assertTrue(admission.assess(accepted).admitted());
        System.out.println("INDEPENDENT_CUMULATIVE_FAR acceptedEdits="+limit+" faces=32767 rejectedEdits="+(limit+1)
                +" faces=32773 nativeBytes="+after.arena().maxNativeVertexBytes()+" owners="+after.farOwners()+" hiddenMaskStillRefuses=true");
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
