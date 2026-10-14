package io.earthplatform.minecraft.lod;

import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DhAnalyticSetAdmissionTest {
    @Test void exactFlatCompleteTwoCapturesFitWholeEnvelopeWithoutLiveRenderer(){
        var r=assess(DhPreparedReplacementSetTest.captures());assertTrue(r.admitted(),r.reason());
        assertEquals(16000,r.census().captureBytes());assertEquals(11595,r.census().baseBoundFaces());
        assertEquals(41,r.census().baseBoundOwners());assertTrue(r.census().unionBytes()<=8L*1024*1024);
        assertTrue(r.census().unionOwners()<=128);assertTrue(r.contentIdentity().matches("[0-9a-f]{64}"));
    }
    @Test void prospectiveInteriorChangeBindsBothPriorAndChangedRegionContent(){
        var base=DhPreparedReplacementSetTest.captures();var changed=List.of(base.getFirst(),base.getLast().withMaterial(68,130,4,2));
        var a=assess(base);var b=assess(changed);assertTrue(a.admitted());assertTrue(b.admitted());assertNotEquals(a.contentIdentity(),b.contentIdentity());
        assertEquals(base.getFirst().contentIdentity(),changed.getFirst().contentIdentity());assertEquals(0,base.getLast().materialAt(68,130,4));
    }
    @Test void doubleCheckerboardRefusedBeforeFaceOrVertexAllocation(){
        var c=DhPreparedReplacementSetTest.WORKSPACES.positions().stream().map(p->DhPreparedReplacementSetTest.capture(p,true)).toList();
        var r=assess(c);assertFalse(r.admitted());assertNotNull(r.census());assertTrue(r.census().faceUpperBound()>32768);
        assertEquals(2,r.census().regions().size());
    }
    @Test void changedHaloOutsideEditableCubeIsExplicitUnsupportedRefusal(){
        var base=DhPreparedReplacementSetTest.captures();byte[] b=base.getFirst().clonedBytes();b[0]=2;
        var r=assess(List.of(new NativeReplacementCapture(base.getFirst().position(),b),base.getLast()));
        assertFalse(r.admitted());assertTrue(r.reason().contains("halo"));assertNull(r.census());
    }
    @Test void unknownPaletteAndIncompleteOrReorderedSetCannotAdmit(){
        var c=DhPreparedReplacementSetTest.captures();byte[] unknown=c.getFirst().clonedBytes();unknown[0]=3;
        assertThrows(IllegalArgumentException.class,()->new NativeReplacementCapture(c.getFirst().position(),unknown));
        assertFalse(assess(List.of(c.getFirst())).admitted());assertFalse(assess(List.of(c.getLast(),c.getFirst())).admitted());
        assertFalse(assess(List.of(c.getFirst(),c.getFirst())).admitted());
    }
    @Test void cancellationPropagatesWithoutConvertingToCapacityRefusal(){
        var c=DhPreparedReplacementSetTest.captures();var n=new AtomicInteger();
        assertThrows(CancellationException.class,()->DhAnalyticSetAdmission.assess(DhPreparedReplacementSetTest.POLICY,DhPreparedReplacementSetTest.WORKSPACES,c,()->{if(n.incrementAndGet()==500)throw new CancellationException();}));
    }
    @Test void compactCaptureMatchesLegacyUnitFacesAtEverySignedEndpointAndRetainsNoIntArray() throws Exception {
        for(long height:new long[]{128,-11000,8849}){
            var p=new CubePos(0,Math.toIntExact(Math.floorDiv(height,16)),0);
            long minY=Math.max(p.minBlockY()-1,-11008)-1,maxY=Math.min(p.minBlockY()+17,8864)+1;
            byte[] raw=new byte[Math.toIntExact(400*(maxY-minY))];int cursor=0;
            for(long y=minY;y<maxY;y++)for(long z=-2;z<18;z++)for(long x=-2;x<18;x++)raw[cursor++]=(byte)(y<=height?1:0);
            var compact=new NativeReplacementCapture(p,raw);var a=DhVoxelReplacementGeometry.captureCompact(compact);
            var b=DhVoxelReplacementGeometry.capture(p,compact::argbAt,()->{});
            assertEquals(b.ownedVolume(),a.ownedVolume());assertEquals(b.readBounds(),a.readBounds());
            assertEquals(DhVoxelReplacementGeometry.build(b,32768,5L*1024*1024,()->{}).faces(),DhVoxelReplacementGeometry.build(a,32768,5L*1024*1024,()->{}).faces());
            assertEquals(raw.length,a.declaredBytes());assertEquals(raw.length*4L,b.declaredBytes());
            var field=DhVoxelReplacementGeometry.Capture.class.getDeclaredField("argb");field.setAccessible(true);assertNull(field.get(a));
            raw[0]=2;assertNotEquals(2,compact.materialAt(-2,minY,-2));
        }
    }
    @Test void nonanalyticSourceOrWrongConstantPolicyFailsClosed(){
        var w=DhPreparedReplacementSetTest.WORLD;
        assertThrows(IllegalArgumentException.class,()->new DhAnalyticFlatPolicy(w.datasetVersion(),new WorldIdentity(0,"earth","1",w.datasetVersion(),w.blockPaletteVersion()),128,0xff808080));
        assertThrows(IllegalArgumentException.class,()->new DhAnalyticFlatPolicy("other",w,128,0xff808080));
        assertThrows(IllegalArgumentException.class,()->new DhAnalyticFlatPolicy(w.datasetVersion(),w,129,0xff808080));
        assertThrows(IllegalArgumentException.class,()->new DhAnalyticFlatPolicy(w.datasetVersion(),w,128,0xff000000));
    }
    private static DhAnalyticSetAdmission.Receipt assess(List<NativeReplacementCapture> captures){return DhAnalyticSetAdmission.assess(DhPreparedReplacementSetTest.POLICY,DhPreparedReplacementSetTest.WORKSPACES,captures,()->{});}
}
