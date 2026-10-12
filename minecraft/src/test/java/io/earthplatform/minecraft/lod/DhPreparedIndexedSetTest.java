package io.earthplatform.minecraft.lod;

import io.earthplatform.minecraft.enginebridge.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Complete index authority uses existing two-region geometry and strict prepare/ALLOW semantics. */
@Timeout(30)
class DhPreparedIndexedSetTest {
    static final long MIN=ReplacementQueryAttestation.DOMAIN_MIN,MAX=ReplacementQueryAttestation.DOMAIN_MAX;
    static final String POLICY="b".repeat(64);
    static ReplacementSetCertificate base(){return DhPreparedAdjacentSetTest.certificate(DhPreparedAdjacentSetTest.captures(),1,0,0);}
    static ReplacementQueryAttestation query(long generation,String root,String layout,long min,long max){
        var c=base();var entries=c.regions().stream().map(r->new PagedReplacementIndex.Entry(r.position(),r.revision(),POLICY,r.captureIdentity())).toList();
        return new ReplacementQueryAttestation(c.storeIdentity(),c.indexIdentity(),generation,root,min,min,min,max,max,max,
                layout,POLICY,PagedReplacementIndex.resultIdentity(entries),2);
    }
    static ReplacementQueryAttestation query(long generation,String root){return query(generation,root,DhLodLayout.LONG_4096.identity(),MIN,MAX);}
    static ReplacementSetCertificate cert(ReplacementQueryAttestation q){var c=base();return new ReplacementSetCertificate(c.world(),c.epoch(),c.resourceGeneration(),c.storeIdentity(),c.indexIdentity(),c.providerIdentity(),c.paletteIdentity(),c.materialPolicy(),c.detailPolicy(),c.generation(),c.regions(),q);}
    static DhReplacementSetInput input(ReplacementQueryAttestation q,DhReplacementSetInput.AuthorityGuard guard){return new DhReplacementSetInput(DhPreparedReplacementSetTest.POLICY,cert(q),DhPreparedAdjacentSetTest.captures(),guard);}
    static DhReplacementSetInput input(ReplacementQueryAttestation q){return input(q,new DhPreparedReplacementSetTest.Guard());}
    @Test void exactCompleteQueryIsCachedAndReportedWithoutOwningIndexOrCursor(){
        var q=query(1,"d".repeat(64));var value=input(q);
        assertSame(value.data().queryIdentity(),value.data().queryIdentity());assertEquals(q.identity(),value.data().queryIdentity());assertSame(q,value.data().queryAttestation());
        assertEquals(16000,value.data().declaredBytes());
        for(var f:DhReplacementSetInput.Data.class.getDeclaredFields())assertFalse(f.getType().getName().contains("PagedReplacementIndex"));
        try(var h=new DhPreparedReplacementSetTest.Harness()){
            h.commit(1,10,value);var actual=h.view.diagnostics().getAsJsonObject("replacementSetQuery");
            assertTrue(actual.get("present").getAsBoolean());assertTrue(actual.get("completeCatalogue").getAsBoolean());
            assertEquals(q.identity(),actual.get("identity").getAsString());assertEquals(2,actual.get("resultCount").getAsInt());assertEquals(MIN,actual.get("minY").getAsLong());
        }
    }
    @Test void changedSnapshotWithIdenticalCaptureBytesCannotUseSameGeometryAuthorityFastPath(){
        var a=input(query(1,"d".repeat(64)));var b=input(query(2,"e".repeat(64)));
        assertNotEquals(a.identity(),b.identity());assertNotEquals(a.data().members().getFirst().identity(),b.data().members().getFirst().identity());
        try(var h=new DhPreparedReplacementSetTest.Harness()){
            h.commit(1,10,a);long reuse=h.n("sameGeometryPreparations");var ticket=h.prepare(2,10,b);
            assertTrue(h.view.replacementSetReady(a.certificateIdentity()));assertFalse(h.view.replacementSetReady(b.certificateIdentity()));
            assertEquals(b.data().queryIdentity(),h.view.diagnostics().getAsJsonObject("pendingReplacementSetQuery").get("identity").getAsString());
            assertEquals(reuse,h.n("sameGeometryPreparations"));assertTrue(h.view.commitPrepared(ticket));assertFalse(h.view.commitPrepared(ticket));
            assertTrue(h.view.replacementSetReady(b.certificateIdentity()));
        }
    }
    @Test void unchangedCompleteQueryAllowsFreshTicketWithoutNewPayloadOrUpload(){try(var h=new DhPreparedReplacementSetTest.Harness()){
        var a=input(query(1,"d".repeat(64)));h.commit(1,10,a);int uploads=h.driver.uploads;var t=h.prepare(2,10,a);
        assertTrue(h.view.coverageReady(1));assertTrue(h.view.commitPrepared(t));assertEquals(uploads,h.driver.uploads);assertEquals(1,h.n("sameGeometryCommits"));
    }}
    @Test void generationAloneRootAloneAndQueryPolicyAllBindCompleteAuthority(){
        var a=query(1,"d".repeat(64));var b=query(2,a.snapshotRootIdentity());var c=query(1,"e".repeat(64));
        assertNotEquals(input(a).identity(),input(b).identity());assertNotEquals(input(a).identity(),input(c).identity());
        var baseline=base();var alternate="f".repeat(64);var entries=baseline.regions().stream().map(r->new PagedReplacementIndex.Entry(r.position(),r.revision(),alternate,r.captureIdentity())).toList();
        var changed=new ReplacementQueryAttestation(a.storeIdentity(),a.indexIdentity(),a.snapshotGeneration(),a.snapshotRootIdentity(),MIN,MIN,MIN,MAX,MAX,MAX,a.layoutIdentity(),alternate,PagedReplacementIndex.resultIdentity(entries),2);
        assertNotEquals(input(a).identity(),input(changed).identity());
    }
    @Test void partialExtentAndWrongLayoutCannotReachRendererStaging(){try(var h=new DhPreparedReplacementSetTest.Harness()){
        var a=input(query(1,"d".repeat(64)));h.commit(1,10,a);int uploads=h.driver.uploads;
        for(var q:List.of(query(2,"e".repeat(64),DhLodLayout.LONG_4096.identity(),-1024,1024),query(2,"e".repeat(64),DhLodLayout.FIXED_512.identity(),MIN,MAX)))
            assertThrows(IllegalStateException.class,()->h.prepare(2,10,input(q)));
        assertEquals(uploads,h.driver.uploads);assertTrue(h.view.replacementSetReady(a.certificateIdentity()));assertEquals(1,h.n("highestRequestedCoverageGeneration"));
    }}
    @Test void indexedSetCannotDowngradeToLegacyAndLegacyCannotAcquireIndexedAuthorityMidView(){
        var indexed=input(query(1,"d".repeat(64)));var legacy=DhPreparedAdjacentSetTest.input(1,0,0,DhPreparedAdjacentSetTest.captures());
        for(boolean firstIndexed:new boolean[]{true,false})try(var h=new DhPreparedReplacementSetTest.Harness()){
            var first=firstIndexed?indexed:legacy;h.commit(1,10,first);assertThrows(IllegalStateException.class,()->h.prepare(2,10,firstIndexed?legacy:indexed));assertTrue(h.view.replacementSetReady(first.certificateIdentity()));
        }
    }
    @Test void indexRevocationSeparatesCurrentFromImmutableHistoricalPresentation(){try(var h=new DhPreparedReplacementSetTest.Harness()){
        var guard=new DhPreparedReplacementSetTest.Guard();var a=input(query(1,"d".repeat(64)),guard);h.commit(1,10,a);var shown=h.view.retainPresentation();guard.current=false;
        assertFalse(h.view.replacementSetReady(a.certificateIdentity()));assertTrue(h.view.drawPresented(shown,new Matrix4f(),0,129,0,DhPreparedReplacementSetTest.RESOURCE));
        assertEquals(a.data().queryIdentity(),h.view.diagnostics().getAsJsonObject("presentationReplacementSetQuery").get("identity").getAsString());
        var b=input(query(2,"e".repeat(64)));var t=h.prepare(2,10,b);assertTrue(h.view.isPresentationCurrent(shown));assertTrue(h.view.commitPrepared(t));assertFalse(h.view.isPresentationCurrent(shown));
    }}
    @Test void stalePreparedQueryRevocationCannotCommitOrRestoreAfterGuardAba(){try(var h=new DhPreparedReplacementSetTest.Harness()){
        var a=input(query(1,"d".repeat(64)));h.commit(1,10,a);var g=new DhPreparedReplacementSetTest.Guard();var b=input(query(2,"e".repeat(64)),g);var t=h.prepare(2,10,b);
        g.current=false;assertFalse(h.view.isPrepared(t));g.current=true;assertFalse(h.view.commitPrepared(t));h.view.pump();assertTrue(h.view.replacementSetReady(a.certificateIdentity()));
    }}
    @Test void cancelledLateSnapshotCannotReplaceTheCurrentCompleteQuery()throws Exception{try(var h=new DhPreparedReplacementSetTest.Harness()){
        var a=input(query(1,"d".repeat(64)));h.commit(1,10,a);var gates=new CountDownLatch[]{new CountDownLatch(1),new CountDownLatch(1)};h.driver.block.set(gates);
        var t=h.view.prepareCoverageSet(2,DhPreparedReplacementSetTest.ready(10),DhPreparedReplacementSetTest.center(10),0,input(query(2,"e".repeat(64))));
        assertTrue(gates[0].await(10,TimeUnit.SECONDS));h.view.cancelPrepared(t);gates[1].countDown();h.commit(3,10,a);assertFalse(h.view.commitPrepared(t));assertTrue(h.view.replacementSetReady(a.certificateIdentity()));
    }}
    @Test void resultDigestCountAndIndexMismatchCannotMintAnInput(){
        var q=query(1,"d".repeat(64));
        assertThrows(IllegalArgumentException.class,()->cert(new ReplacementQueryAttestation(q.storeIdentity(),q.indexIdentity(),1,q.snapshotRootIdentity(),MIN,MIN,MIN,MAX,MAX,MAX,q.layoutIdentity(),POLICY,"0".repeat(64),2)));
        assertThrows(IllegalArgumentException.class,()->new ReplacementQueryAttestation(q.storeIdentity(),q.indexIdentity(),1,q.snapshotRootIdentity(),MIN,MIN,MIN,MAX,MAX,MAX,q.layoutIdentity(),POLICY,q.resultDigest(),3));
        assertThrows(IllegalArgumentException.class,()->cert(new ReplacementQueryAttestation(q.storeIdentity(),"0".repeat(64),1,q.snapshotRootIdentity(),MIN,MIN,MIN,MAX,MAX,MAX,q.layoutIdentity(),POLICY,q.resultDigest(),2)));
    }
    @Test void resourceFenceRejectsAllIndexedDrawAndReleasesNoExtraIndexOwner(){try(var h=new DhPreparedReplacementSetTest.Harness()){
        var a=input(query(1,"d".repeat(64)));h.commit(1,10,a);var display=h.view.retainPresentation();h.view.requestInvalidation();
        assertFalse(h.view.replacementSetReady(a.certificateIdentity()));assertFalse(h.view.drawPresented(display,new Matrix4f(),0,129,0,DhPreparedReplacementSetTest.RESOURCE));h.view.close();assertEquals(0,h.n("residentPageTileOwners"));assertFalse(h.view.diagnostics().getAsJsonObject("replacementSetQuery").get("present").getAsBoolean());
    }}
    @Test void conservativeCompleteQueryRemainsExactAcrossFarRecenterAndCanceledTicket(){try(var h=new DhPreparedReplacementSetTest.Harness()){
        var q=query(3,"d".repeat(64));var value=input(q);h.commit(1,0,value);
        var ticket=h.prepare(2,20,value);assertTrue(h.view.coverageReady(1));
        assertEquals(q.identity(),h.view.diagnostics().getAsJsonObject("replacementSetQuery").get("identity").getAsString());
        assertEquals(q.identity(),h.view.diagnostics().getAsJsonObject("pendingReplacementSetQuery").get("identity").getAsString());
        assertTrue(h.view.commitPrepared(ticket));assertTrue(h.view.coverageReady(2));
        assertSame(q,value.data().queryAttestation());assertTrue(h.view.replacementSetReady(value.certificateIdentity()));
        var cancelled=h.prepare(3,21,value);h.view.cancelPrepared(cancelled);assertFalse(h.view.commitPrepared(cancelled));
        h.view.pump();assertTrue(h.view.coverageReady(2));assertTrue(h.view.replacementSetReady(value.certificateIdentity()));
        assertEquals(q.identity(),h.view.diagnostics().getAsJsonObject("replacementSetQuery").get("identity").getAsString());
    }}

}
