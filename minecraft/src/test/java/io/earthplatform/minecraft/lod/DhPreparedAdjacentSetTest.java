package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Matrix4f;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Complete adjacent-set authority/admission tests, independently of the geometry oracle. */
@Timeout(30)
class DhPreparedAdjacentSetTest {
    static final ReplacementWorkspaces ADJACENT=ReplacementWorkspaces.adjacent(new CubePos(0,8,0));
    record Cell(long x,long y,long z) { }
    static List<NativeReplacementCapture> captures(Map<Cell,Integer> edits,boolean checker){
        var out=new ArrayList<NativeReplacementCapture>();
        for(var p:ADJACENT.positions()){
            byte[] raw=new byte[8000];int i=0;
            for(long y=126;y<146;y++)for(long z=-2;z<18;z++)for(long x=p.minBlockX()-2;x<p.minBlockX()+18;x++){
                boolean editable=x>=0&&x<32&&y>=128&&y<144&&z>=0&&z<16;
                int value=checker&&editable?(((x+y+z)&1)==0?1:0):y<=128?1:0;
                raw[i++]=(byte)(int)edits.getOrDefault(new Cell(x,y,z),value);
            }
            out.add(new NativeReplacementCapture(p,raw));
        }
        return List.copyOf(out);
    }
    static List<NativeReplacementCapture> captures(){return captures(Map.of(),false);}
    static ReplacementSetCertificate certificate(List<NativeReplacementCapture> caps,long generation,long a,long b){
        var regions=new ArrayList<ReplacementSetCertificate.Region>();long lease=1;
        for(var capture:caps){
            var versions=new ArrayList<RollingCubeActor.CubeVersion>();
            for(var position:ADJACENT.dependencies(capture.position())){
                long revision=position.equals(ADJACENT.original())?a:position.equals(ADJACENT.second())?b:0;
                versions.add(new RollingCubeActor.CubeVersion(position,lease++,revision));
            }
            regions.add(new ReplacementSetCertificate.Region(capture.position(),versions,capture.contentIdentity()));
        }
        return new ReplacementSetCertificate(DhPreparedReplacementSetTest.WORLD,DhPreparedReplacementSetTest.EPOCH,
                DhPreparedReplacementSetTest.RESOURCE,"00000000-0000-0000-0000-000000000001","c".repeat(64),"provider","a".repeat(64),
                DhReplacementInput.ANALYTIC_MATERIAL_POLICY,"exact-owned18-read20-unit-faces-v1",generation,regions);
    }
    static DhAnalyticSetAdmission.Receipt assess(List<NativeReplacementCapture> captures){
        return DhAnalyticSetAdmission.assess(DhPreparedReplacementSetTest.POLICY,ADJACENT,captures,()->{});
    }
    static List<NativeReplacementCapture> overlay(List<NativeReplacementCapture> caps,long x,long y,long z,int material){
        return caps.stream().map(c->c.containsReadCell(x,y,z)?c.withReadMaterial(x,y,z,material):c).toList();
    }
    @Test void worstLegalTwoCubeCheckerboardStillRefusesBeforeAnySaveOrGpuWork(){
        var receipt=assess(captures(Map.of(),true));assertFalse(receipt.admitted());assertNotNull(receipt.census());
        assertTrue(receipt.census().faceUpperBound()>32768);assertEquals(2,receipt.census().regions().size());
        assertEquals(16000,receipt.census().captureBytes());
    }
    @Test void cancellationDuringOverlapValidationIsNotReportedAsCapacityOrSupportRefusal(){
        var count=new AtomicInteger();assertThrows(CancellationException.class,()->DhAnalyticSetAdmission.assess(
                DhPreparedReplacementSetTest.POLICY,ADJACENT,captures(),()->{if(count.incrementAndGet()==650)throw new CancellationException();}));
    }
}
