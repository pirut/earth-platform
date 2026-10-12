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
    static DhReplacementSetInput input(DhPreparedReplacementSetTest.Guard guard,long generation,long a,long b,List<NativeReplacementCapture> caps){
        return new DhReplacementSetInput(DhPreparedReplacementSetTest.POLICY,certificate(caps,generation,a,b),caps,guard);
    }
    static DhReplacementSetInput input(long generation,long a,long b,List<NativeReplacementCapture> caps){return input(new DhPreparedReplacementSetTest.Guard(),generation,a,b,caps);}
    static DhAnalyticSetAdmission.Receipt assess(List<NativeReplacementCapture> captures){
        return DhAnalyticSetAdmission.assess(DhPreparedReplacementSetTest.POLICY,ADJACENT,captures,()->{});
    }
    static List<NativeReplacementCapture> overlay(List<NativeReplacementCapture> caps,long x,long y,long z,int material){
        return caps.stream().map(c->c.containsReadCell(x,y,z)?c.withReadMaterial(x,y,z,material):c).toList();
    }
    @Test void completeAdjacentCleanSetIsCertifiedAndOneOwnerPolicyIsExplicit(){
        var caps=captures();var value=input(1,0,0,caps);var receipt=assess(caps);
        assertTrue(receipt.admitted(),receipt.reason());assertEquals(16000,receipt.census().captureBytes());
        assertEquals(ADJACENT.identity(),value.data().workspaceIdentity());
        assertEquals("lowest-canonical-solid-owner-union-v1",value.data().ownershipPolicy());
        assertTrue(value.data().members().stream().allMatch(m->m.certificate().identity().equals(value.certificateIdentity())));
        try(var h=new DhPreparedReplacementSetTest.Harness()){
            h.commit(1,10,value);assertTrue(h.view.replacementSetReady(value.certificateIdentity()));
            assertEquals(2,h.n("replacementSetRegions"));assertEquals(0,h.n("replacementSetEditedRegions"));
            assertEquals(value.data().ownershipPolicy(),h.view.diagnostics().get("replacementSetOwnershipPolicy").getAsString());
        }
    }
    @Test void seamEditRefreshesBothReadCapturesWhileOnlyEditedCubeRevisionChanges(){
        var old=captures();var next=overlay(old,15,130,4,2);
        assertNotEquals(old.getFirst().contentIdentity(),next.getFirst().contentIdentity());
        assertNotEquals(old.getLast().contentIdentity(),next.getLast().contentIdentity());
        assertEquals(0,old.getFirst().materialAt(15,130,4));assertEquals(0,old.getLast().materialAt(15,130,4));
        var value=input(2,1,0,next);assertTrue(assess(next).admitted());
        assertEquals(0,value.certificate().region(ADJACENT.second()).revision());
        try(var h=new DhPreparedReplacementSetTest.Harness()){
            var previous=input(1,0,0,old);h.commit(1,10,previous);var ticket=h.prepare(2,10,value);
            assertTrue(h.view.replacementSetReady(previous.certificateIdentity()));assertFalse(h.view.replacementSetReady(value.certificateIdentity()));
            assertTrue(h.view.commitPrepared(ticket));assertTrue(h.view.replacementSetReady(value.certificateIdentity()));
            assertFalse(h.view.commitPrepared(ticket));assertEquals(0,h.n("sameGeometryCommits"));
        }
    }
    @Test void everySeamReadCellUsesHalfOpenOverlapAndPreservesPriorArrays(){
        var base=captures();
        for(int x=13;x<=18;x++){
            var changed=overlay(base,x,130,4,2);
            assertEquals(x<18,!changed.getFirst().contentIdentity().equals(base.getFirst().contentIdentity()));
            assertEquals(x>=14,!changed.getLast().contentIdentity().equals(base.getLast().contentIdentity()));
            assertTrue(assess(changed).admitted(),"prospective seam x="+x);
            assertDoesNotThrow(()->input(2,1,1,changed));
        }
    }
    @Test void individuallyCertifiedButContradictoryOverlapCannotAcquireRendererAuthority(){
        var base=captures();var onlyA=List.of(base.getFirst().withMaterial(15,130,4,2),base.getLast());
        var receipt=assess(onlyA);assertFalse(receipt.admitted());assertNull(receipt.census());
        assertThrows(DhReplacementComposition.Declined.class,()->input(2,1,0,onlyA));
        try(var h=new DhPreparedReplacementSetTest.Harness()){
            var old=input(1,0,0,base);h.commit(1,10,old);int uploads=h.driver.uploads;
            assertThrows(DhReplacementComposition.Declined.class,()->input(2,1,0,onlyA));
            assertEquals(uploads,h.driver.uploads);assertTrue(h.view.replacementSetReady(old.certificateIdentity()));
        }
    }
    @Test void haloEditsOutsideBothActualCubesAreNotMistakenForLegitimateOverlap(){
        var invalid=overlay(captures(),-2,130,4,2);
        var receipt=assess(invalid);assertFalse(receipt.admitted());assertNull(receipt.census());
        assertThrows(DhReplacementComposition.Declined.class,()->input(2,1,0,invalid));
        var valid=overlay(captures(),16,130,4,2);assertTrue(assess(valid).admitted());
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
    @Test void adjacentAndDisjointWorkspaceModesCannotAliasOrSwitchWithinOneView(){
        var adjacent=input(1,0,0,captures());var disjoint=DhPreparedReplacementSetTest.input(new DhPreparedReplacementSetTest.Guard(),1,0,0);
        assertEquals(adjacent.data().policy().identity(),disjoint.data().policy().identity(),"The constant source facts are unchanged");
        assertNotEquals(adjacent.data().workspaceIdentity(),disjoint.data().workspaceIdentity());assertNotEquals(adjacent.identity(),disjoint.identity());
        assertFalse(DhAnalyticSetAdmission.assess(DhPreparedReplacementSetTest.POLICY,
                DhPreparedReplacementSetTest.WORKSPACES,captures(),()->{}).admitted());
        assertFalse(assess(DhPreparedReplacementSetTest.captures()).admitted());
        try(var h=new DhPreparedReplacementSetTest.Harness()){
            h.commit(1,10,adjacent);
            assertThrows(IllegalStateException.class,()->h.prepare(2,10,disjoint));
            assertTrue(h.view.replacementSetReady(adjacent.certificateIdentity()));
        }
    }
    @Test void historicalSetKeepsExactOldOverlapWhileCurrentIsRevokedAndFreshPairRequiresAllow(){
        try(var h=new DhPreparedReplacementSetTest.Harness()){
            var guard=new DhPreparedReplacementSetTest.Guard();var old=input(guard,1,0,0,captures());
            h.commit(1,10,old);var display=h.view.retainPresentation();guard.current=false;
            assertFalse(h.view.replacementSetReady(old.certificateIdentity()));
            assertTrue(h.view.drawPresented(display,new Matrix4f(),0,129,0,DhPreparedReplacementSetTest.RESOURCE));
            var next=input(2,1,0,overlay(captures(),15,130,4,2));var ticket=h.prepare(2,10,next);
            assertTrue(h.view.isPresentationCurrent(display));assertFalse(h.view.replacementSetReady(next.certificateIdentity()));
            assertTrue(h.view.commitPrepared(ticket));assertFalse(h.view.isPresentationCurrent(display));
            assertTrue(h.view.replacementSetReady(next.certificateIdentity()));
        }
    }
    @Test void canceledLateAdjacentPreparationCannotReplaceARevisionMatchedCurrentSet() throws Exception {
        try(var h=new DhPreparedReplacementSetTest.Harness()){
            var old=input(1,0,0,captures());h.commit(1,10,old);
            var gates=new CountDownLatch[]{new CountDownLatch(1),new CountDownLatch(1)};h.driver.block.set(gates);
            var next=input(2,1,0,overlay(captures(),15,130,4,2));
            var ticket=h.view.prepareCoverageSet(2,DhPreparedReplacementSetTest.ready(10),DhPreparedReplacementSetTest.center(10),0,next);
            assertTrue(gates[0].await(10,TimeUnit.SECONDS));h.view.cancelPrepared(ticket);gates[1].countDown();
            h.commit(3,10,old);assertFalse(h.view.commitPrepared(ticket));assertTrue(h.view.replacementSetReady(old.certificateIdentity()));
        }
    }
}
