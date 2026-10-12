package io.earthplatform.minecraft.coregate;

import io.earthplatform.compat.dh.shader.DhShaderPassGate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Adverse identity traces using the production committed holder, retained presentation and
 * strict pass gate. The fixture models protocol scheduling, not Minecraft/GL installation;
 * RollingPublishedPairWiringTest separately binds that model to compiled host sequencing.
 */
class RollingPublishedPairIdentityTest {
    private static final String OLD="a".repeat(64), NEXT="b".repeat(64), RETRY="c".repeat(64);
    private static final DhShaderPassGate.Pass NATIVE=DhShaderPassGate.Pass.MAIN_NATIVE_SOLID;
    private static final DhShaderPassGate.Pass FAR=DhShaderPassGate.Pass.MAIN_DH_OPAQUE;
    private record Pin(long serial,String digest,boolean historical) {}

    private static final class Trace implements AutoCloseable {
        final RollingPublishedPairIdentity published=new RollingPublishedPairIdentity();
        final Object level=new Object(),pipeline=new Object();
        final DhShaderPassGate gate=new DhShaderPassGate();
        final RollingEditPresentation<Object,Object> history=new RollingEditPresentation<>(new RollingEditPresentation.Driver<>() {
            public Object captureNative(){return new Object();}
            public Object captureFar(){return new Object();}
            public boolean nativeCurrent(Object ticket){return nativeHistoryCurrent;}
            public boolean farCurrent(Object ticket){return farHistoryCurrent;}
            public void closeNative(Object ticket){}
            public void closeFar(Object ticket){}
        });
        final DhShaderPassGate.Lease lease=gate.acquire(level,pipeline,"world",1,1,NATIVE.bit()|FAR.bit(),new DhShaderPassGate.Resources(){
            public boolean presentationCurrent(String digest,boolean historical){return Trace.this.current(digest,historical);}
            public void draw(DhShaderPassGate.Pass pass,Object context){draws++;}
            public void close(){}
        });
        String candidate="";long publication,frame;int installs,draws;
        boolean pending,prepared,allowed,pairSent,visible,revoked;
        boolean nativeCurrent=true,farCurrent=true,nativeHistoryCurrent=true,farHistoryCurrent=true;
        Pin pin;

        void metadata(){publication++;pending=true;prepared=allowed=pairSent=false;candidate="";}
        void ready(String digest){if(!pending||!candidate.isEmpty())throw new IllegalStateException();candidate=digest;prepared=true;}
        void allow(long id,long resources,String exact){
            if(revoked||!pending||id!=publication||resources!=1||candidate.isEmpty()||!candidate.equals(exact))throw new IllegalArgumentException("Stale allowance");
            if(!prepared||allowed||pairSent)throw new IllegalStateException();allowed=true;
        }
        void allow(){allow(publication,1,candidate);}
        void install(boolean nativeSucceeds,boolean farSucceeds){
            if(!allowed||!prepared||pairSent)throw new IllegalStateException();
            visible=false;history.invalidate();
            if(!farSucceeds)throw new IllegalStateException("far install failed");
            history.retire();
            if(!nativeSucceeds)throw new IllegalStateException("native install failed");
            published.publish(candidate);visible=true;pairSent=true;installs++;
        }
        void committed(){if(!pairSent)throw new IllegalStateException();pending=allowed=false;}
        void bootstrap(){metadata();ready(OLD);allow();install(true,true);committed();}
        RollingEditPresentation.Stamp edit(){
            var stamp=new RollingEditPresentation.Stamp(1,publication,1,publication,0,27,published.digest());
            history.capture(stamp);metadata();visible=false;return stamp;
        }
        // Candidate-only abandonment is an identity model contract. The real host closes on
        // stale protocol errors; it has no new rollback/abort path introduced by this fix.
        void abandonCandidate(){candidate="";pending=prepared=allowed=pairSent=false;}
        void replacementRetry(){candidate=RETRY;prepared=allowed=false;}
        Pin frame(long serial){
            frame=serial;
            if(pin==null||pin.serial()!=serial){boolean historical=history.frozen();pin=new Pin(serial,historical?history.stamp().manifestDigest():(visible?published.digest():null),historical);}
            assertTrue(gate.beginFrame(lease,serial,pipeline,1,pin.digest(),pin.historical()));return pin;
        }
        boolean current(String digest,boolean historical){
            if(revoked||pin==null||!java.util.Objects.equals(digest,pin.digest())||historical!=pin.historical())return false;
            if(historical)return history.frozen()&&digest.equals(history.stamp().manifestDigest())&&history.current();
            return visible&&!history.frozen()&&digest.equals(published.digest())&&nativeCurrent&&farCurrent;
        }
        DhShaderPassGate.Result submit(DhShaderPassGate.Pass pass){return gate.submit(lease,level,frame,pipeline,1,pass,null);}
        void revoke(){visible=false;published.revoke();history.invalidate();revoked=true;}
        public void close(){revoke();history.close();gate.close();}
    }

    @Test void initialLoadingRemainsNullThroughReadyAndAllowUntilRealInstallation(){
        try(var t=new Trace()){
            assertNull(t.frame(0).digest());assertEquals(DhShaderPassGate.Result.NO_PRESENTATION,t.submit(NATIVE));
            t.metadata();t.ready(OLD);t.allow();assertNull(t.published.digest());assertNull(t.frame(1).digest());
            assertEquals(DhShaderPassGate.Result.NO_PRESENTATION,t.submit(FAR));assertEquals(0,t.draws);
            t.install(true,true);assertEquals(OLD,t.frame(2).digest());assertEquals(DhShaderPassGate.Result.SUBMITTED,t.submit(NATIVE));
        }
    }
    @Test void oldGeometryKeepsExactIdentityDuringEmptyAndFutureNonemptyCandidateAndAllow(){
        try(var t=new Trace()){
            t.bootstrap();t.metadata();assertEquals("",t.candidate);assertEquals(OLD,t.frame(10).digest());
            assertEquals(DhShaderPassGate.Result.SUBMITTED,t.submit(NATIVE));
            t.ready(NEXT);assertEquals(NEXT,t.candidate);assertEquals(OLD,t.frame(11).digest());
            assertEquals(DhShaderPassGate.Result.SUBMITTED,t.submit(FAR));
            t.allow();assertEquals(OLD,t.published.digest());assertEquals(OLD,t.frame(12).digest());
            assertEquals(DhShaderPassGate.Result.SUBMITTED,t.submit(NATIVE));assertEquals(DhShaderPassGate.Result.SUBMITTED,t.submit(FAR));
            t.install(true,true);assertEquals(NEXT,t.frame(13).digest());assertEquals(2,t.installs);
            assertEquals(DhShaderPassGate.Result.SUBMITTED,t.submit(NATIVE));assertEquals(DhShaderPassGate.Result.SUBMITTED,t.submit(FAR));
            assertThrows(IllegalStateException.class,()->t.install(true,true));assertEquals(2,t.installs);
        }
    }
    @Test void candidateMovementBetweenNativeAndFarCannotRelabelAnAlreadyPinnedFrame(){
        try(var t=new Trace()){
            t.bootstrap();var pinned=t.frame(20);assertEquals(DhShaderPassGate.Result.SUBMITTED,t.submit(NATIVE));
            t.metadata();t.ready(NEXT);t.allow();assertSame(pinned,t.frame(20));assertEquals(OLD,pinned.digest());
            assertEquals(DhShaderPassGate.Result.SUBMITTED,t.submit(FAR));assertFalse(t.gate.counters().fenced());
        }
    }
    @Test void genuineInstallInsidePinnedFrameStillFencesRatherThanDrawingMismatchedGeometry(){
        try(var t=new Trace()){
            t.bootstrap();t.frame(30);assertEquals(DhShaderPassGate.Result.SUBMITTED,t.submit(NATIVE));
            t.metadata();t.ready(NEXT);t.allow();t.install(true,true);assertEquals(OLD,t.frame(30).digest());
            assertEquals(DhShaderPassGate.Result.FENCED,t.submit(FAR));assertEquals(1,t.draws);
        }
    }
    @Test void strictGateStillRejectsExplicitPairOrHistoricalRelabelWithinOneFrame(){
        for(boolean historical:new boolean[]{false,true})try(var t=new Trace()){
            t.bootstrap();t.frame(31);assertFalse(t.gate.beginFrame(t.lease,31,t.pipeline,1,historical?OLD:NEXT,historical));
            assertTrue(t.gate.counters().fenced());assertEquals(DhShaderPassGate.Result.FENCED,t.submit(FAR));
        }
    }
    @Test void candidateAbandonmentAndReplacementRetryNeverChangeCommittedIdentity(){
        try(var t=new Trace()){
            t.bootstrap();t.metadata();t.ready(NEXT);t.replacementRetry();assertEquals(RETRY,t.candidate);
            assertEquals(OLD,t.frame(40).digest());assertEquals(DhShaderPassGate.Result.SUBMITTED,t.submit(FAR));
            t.abandonCandidate();assertEquals(OLD,t.frame(41).digest());assertEquals(DhShaderPassGate.Result.SUBMITTED,t.submit(NATIVE));
            assertEquals(1,t.installs);
        }
    }
    @Test void staleAllowanceCannotPublishAndExistingFailClosedShutdownRevokesOldIdentity(){
        try(var t=new Trace()){
            t.bootstrap();t.metadata();t.ready(NEXT);
            assertThrows(IllegalArgumentException.class,()->t.allow(t.publication-1,1,NEXT));
            assertThrows(IllegalArgumentException.class,()->t.allow(t.publication,2,NEXT));
            assertThrows(IllegalArgumentException.class,()->t.allow(t.publication,1,OLD));
            assertEquals(OLD,t.published.digest());assertFalse(t.allowed);assertEquals(1,t.installs);
            // Session.accept catches the stale protocol error and closes; not an implicit rollback.
            t.revoke();assertNull(t.published.digest());assertNull(t.frame(42).digest());
            assertEquals(DhShaderPassGate.Result.NO_PRESENTATION,t.submit(FAR));
        }
    }
    @Test void partialNearOrFarInstallationNeverPublishesFutureDigest(){
        for(boolean farSucceeds:new boolean[]{false,true})try(var t=new Trace()){
            t.bootstrap();t.metadata();t.ready(NEXT);t.allow();
            assertThrows(IllegalStateException.class,()->t.install(false,farSucceeds));
            assertEquals(OLD,t.published.digest());assertFalse(t.visible);assertFalse(t.pairSent);assertEquals(1,t.installs);
            assertNull(t.frame(43).digest());assertEquals(DhShaderPassGate.Result.NO_PRESENTATION,t.submit(FAR));
        }
    }
    @Test void historicalEditKeepsImmutableOldStampDespiteCandidateAndCurrentAuthorityChanges(){
        try(var t=new Trace()){
            t.bootstrap();var old=t.edit();t.nativeCurrent=t.farCurrent=false;t.ready(NEXT);t.allow();
            var pin=t.frame(50);assertTrue(pin.historical());assertEquals(OLD,pin.digest());assertSame(old,t.history.stamp());
            assertEquals(DhShaderPassGate.Result.SUBMITTED,t.submit(NATIVE));assertEquals(DhShaderPassGate.Result.SUBMITTED,t.submit(FAR));
            t.nativeCurrent=t.farCurrent=true;t.install(true,true);assertEquals(NEXT,t.frame(51).digest());assertFalse(t.pin.historical());
            assertEquals(OLD,old.manifestDigest());assertEquals(1,old.publication());assertNull(t.history.stamp());
            assertFalse(t.current(OLD,true));
        }
    }
    @Test void unchangedDigestNeverSubstitutesForNativeFarOrHistoricalAuthority(){
        for(boolean historical:new boolean[]{false,true})for(boolean nativeSide:new boolean[]{false,true})try(var t=new Trace()){
            t.bootstrap();if(historical)t.edit();t.frame(60);
            if(historical){if(nativeSide)t.nativeHistoryCurrent=false;else t.farHistoryCurrent=false;}
            else if(nativeSide)t.nativeCurrent=false;else t.farCurrent=false;
            assertEquals(DhShaderPassGate.Result.FENCED,t.submit(FAR));assertEquals(0,t.draws);assertEquals(OLD,t.published.digest());
        }
    }
    @Test void invalidationAndReloadRevokeCurrentAndRetainedFramesWithoutLaunderingAuthority(){
        for(boolean historical:new boolean[]{false,true})try(var t=new Trace()){
            t.bootstrap();if(historical)t.edit();t.frame(70);t.revoke();
            assertNull(t.published.digest());assertFalse(t.history.frozen());assertEquals(DhShaderPassGate.Result.FENCED,t.submit(FAR));
            assertFalse(t.gate.beginFrame(t.lease,71,t.pipeline,2,OLD,historical));assertEquals(0,t.draws);
        }
        var t=new Trace();t.bootstrap();t.close();t.close();assertNull(t.published.digest());assertFalse(t.gate.counters().ownsCarrier());
    }
    @Test void publishedIdentityRequiresExactDigestAndRevocationIsIdempotent(){
        var identity=new RollingPublishedPairIdentity();assertNull(identity.digest());identity.publish(OLD);
        for(String malformed:new String[]{null,"","short","A".repeat(64),"a".repeat(63),"g".repeat(64)}){
            assertThrows(IllegalArgumentException.class,()->identity.publish(malformed));assertEquals(OLD,identity.digest());
        }
        identity.revoke();identity.revoke();assertNull(identity.digest());
    }
}
