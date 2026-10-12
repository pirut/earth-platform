package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.NativeCollisionMotionFence;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic owner/packet trace tests. Packaged transformed-client acceptance is a separate gate. */
class RollingMotionGuardTest {
    static final CubePos C=new CubePos(0,8,0),N=C.offset(1,0,0);
    static final AABB ENVELOPE=new AABB(-512,-11008,-512,512,8864,512);
    static RollingMotionGuard.Pose pose(double x,RollingMotionGuard.Authority a){return pose(new Vec3(x,132,8),a,true,true);}
    static RollingMotionGuard.Pose pose(Vec3 f,RollingMotionGuard.Authority a,boolean spectator,boolean physics){return new RollingMotionGuard.Pose(new AABB(f.x-.3,f.y,f.z-.3,f.x+.3,f.y+1.8,f.z+.3),f,spectator,physics,a);}
    static RollingMotionGuard.Authority auth(long generation,long publication,long frame){return new RollingMotionGuard.Authority(7,generation,publication,"pair"+publication,frame,1,"source");}
    static RollingMotionGuard.Pair pair(long generation,long publication,CubePos center){return new RollingMotionGuard.Pair(7,publication,generation,center,1,"pair"+publication);}
    static RollingMotionGuard open(){
        var g=new RollingMotionGuard(7,ENVELOPE,pose(8,null),C);
        g.requireMetadata(C,C,1);g.beginCommit(pair(1,1,C),pose(8,null),p->true,0);
        var a=auth(1,1,1);g.committed(a,C,pose(8,a),p->true);return g;
    }
    static void accept(RollingMotionGuard g,Vec3 d){var before=g.accepted();assertTrue(g.admit(before,d,p->true,true));assertTrue(g.observe(pose(before.feet().add(d),g.authority(),true,true),p->true));}

    @Test void frozen030OrdinarySpectatorFlightReproducesIndefiniteWaitBody(){
        // Frozen packet hook excluded spectator. PREPARED only checked containment and returned.
        // Start N while feet8; eighteen ordinary4m spectator packets outrun it without a teleport.
        var body=pose(8,null).body();boolean spectator=true;CubePos actorGoal=N;boolean waiting=false;
        for(int i=0;i<18;i++){
            var delta=new Vec3(4,0,0);
            boolean oldPacketRejected=!spectator&&!NativeCollisionMotionFence.bounded(body,delta);
            assertFalse(oldPacketRejected);body=body.move(delta);
        }
        assertTrue(RollingMotionGuard.inside(body,ENVELOPE));
        for(int duplicatePrepared=0;duplicatePrepared<200;duplicatePrepared++){
            if(!RollingWindowAdmission.contains(body,N))waiting=true;
            assertTrue(waiting);assertEquals(N,actorGoal);
        }
        assertFalse(RollingWindowAdmission.contains(body,N));
    }
    @Test void sameTraceStopsBeforeEscapeAndHeldForwardResumesAfterExactCommit(){
        var g=open();assertTrue(g.reserve(N,2,p->true));int accepted=0,rejected=0;
        for(int i=0;i<18;i++){
            var old=g.accepted();var d=new Vec3(4,0,0);
            if(g.admit(old,d,p->true,true)){assertTrue(g.observe(pose(old.feet().add(d),g.authority(),true,true),p->true));accepted++;}
            else{rejected++;assertSame(old,g.accepted());}
            assertTrue(RollingWindowAdmission.contains(g.accepted().body(),C));assertTrue(RollingWindowAdmission.contains(g.accepted().body(),N));
        }
        assertEquals(9,accepted);assertEquals(9,rejected);assertEquals(48,g.latestIntent().x);
        g.beginCommit(pair(2,2,N),g.accepted(),p->true,10);
        assertFalse(g.admit(g.accepted(),new Vec3(4,0,0),p->true,true));
        var next=auth(2,2,2);g.committed(next,N,pose(g.accepted().feet(),next,true,true),p->true);
        accept(g,new Vec3(4,0,0));assertEquals(48,g.accepted().feet().x);assertEquals(0,g.recoveries());
    }
    @Test void reservationPrecedesMetadataAndActorReceivesOnlyOneAdjacentGoal(){
        var g=open();var distant=C.offset(20,0,0);var request=RollingCubePlan.nextStep(C,distant);
        assertTrue(g.reserve(request,2,p->true)); // actor is deterministically paused before metadata
        for(int i=0;i<9;i++)accept(g,new Vec3(4,0,0));
        assertFalse(g.admit(g.accepted(),new Vec3(4,0,0),p->true,true));
        g.requireMetadata(C,N,2);
        assertThrows(IllegalStateException.class,()->g.requireMetadata(C,N.offset(1,0,0),2));
        // Pinned actor consumes latestGoal==currentCenter after commit, so no unattended second stage.
        assertEquals(N,request);assertEquals(N,RollingCubePlan.nextStep(N,request));
    }
    @Test void reversalWhileHeldReplacesLatestIntentWithoutCancellingTheInFlightStep(){
        var g=open();assertTrue(g.reserve(N,2,p->true));for(int i=0;i<9;i++)accept(g,new Vec3(4,0,0));
        g.beginCommit(pair(2,2,N),g.accepted(),p->true,100);
        assertFalse(g.admit(g.accepted(),new Vec3(-4,0,0),p->true,true));
        assertEquals(40,g.latestIntent().x);assertEquals(N,g.reservation().candidate());
        var a=auth(2,2,2);g.committed(a,N,pose(g.accepted().feet(),a,true,true),p->true);
        accept(g,new Vec3(-4,0,0));assertEquals(40,g.accepted().feet().x);
    }
    @Test void exactAnchorRecoveryHappensOnceAndWrongAckCannotReleaseIt(){
        var g=open();var anchor=g.accepted();assertTrue(g.reserve(N,2,p->true));
        var escaped=pose(80,g.authority());var saved=g.recover(pair(2,2,N),escaped,p->p.authority().equals(g.authority()),100);
        assertEquals(anchor,saved);assertEquals(1,g.recoveries());assertEquals(N,g.reservation().candidate());
        g.correctionSent(41,100);assertFalse(g.acknowledge(40,saved,true));assertTrue(g.correctionPending());
        assertFalse(g.acknowledge(41,saved,false));assertTrue(g.acknowledge(41,saved,true));
        assertThrows(IllegalStateException.class,()->g.recover(pair(2,2,N),escaped,p->true,101));
        g.beginCommit(pair(2,2,N),saved,p->true,102);var a=auth(2,2,2);g.committed(a,N,pose(saved.feet(),a,true,true),p->true);
        accept(g,new Vec3(-4,0,0));assertEquals(1,g.recoveries());
    }
    @Test void staleFrameRevisionOwnerSourceOrVisualGenerationNeverMintsRecovery(){
        for(String stale:new String[]{"frame","revision","removed-owner","source"}){
            var g=open();assertTrue(g.reserve(N,2,p->true));
            assertThrows(IllegalStateException.class,()->g.recover(pair(2,2,N),pose(80,g.authority()),p->false,0),stale);
            assertEquals(0,g.recoveries());
        }
        var g=open();assertTrue(g.reserve(N,2,p->true));var exact=pair(2,2,N);g.recover(exact,pose(80,g.authority()),p->true,1);
        assertThrows(IllegalStateException.class,()->g.beginCommit(new RollingMotionGuard.Pair(7,2,2,N,2,"pair2"),g.accepted(),p->true,2));
        assertThrows(IllegalStateException.class,()->g.beginCommit(new RollingMotionGuard.Pair(8,2,2,N,1,"pair2"),g.accepted(),p->true,2));
    }
    @Test void missingAnchorModeChangeOccupiedWalkingAndCancelledCorrectionFailClosed(){
        var bootstrap=new RollingMotionGuard(7,ENVELOPE,pose(8,null),C);
        assertThrows(IllegalStateException.class,()->bootstrap.recover(pair(1,1,C),pose(80,null),p->true,0));
        var g=open();assertTrue(g.reserve(N,2,p->true));
        assertThrows(IllegalStateException.class,()->g.recover(pair(2,2,N),pose(new Vec3(80,132,8),g.authority(),false,false),p->true,0));
        assertThrows(IllegalStateException.class,()->g.recover(pair(2,2,N),pose(80,g.authority()),p->false,0));
        var saved=g.recover(pair(2,2,N),pose(80,g.authority()),p->true,100);
        g.correctionSent(1,100);assertFalse(g.acknowledge(1,pose(80,g.authority()),true));
        assertFalse(g.expired(99+RollingMotionGuard.CORRECTION_ACK_TIMEOUT_NANOS));assertTrue(g.expired(100+RollingMotionGuard.CORRECTION_ACK_TIMEOUT_NANOS));
        g.fail("cancelled/timeout");assertFalse(g.admit(saved,new Vec3(1,0,0),p->true,true));
    }
    @Test void allSixFacesSignedYAndLargeSignedCoordinatesAreSwept(){
        for(var center:new CubePos[]{C,new CubePos(-100,-600,-100),new CubePos(1000000,400,-1000000)}){
            var f=new Vec3(center.minBlockX()+8,center.minBlockY()+4,center.minBlockZ()+8);
            var env=new AABB(f.x-1000,f.y-1000,f.z-1000,f.x+1000,f.y+1000,f.z+1000);
            for(var d:new Vec3[]{new Vec3(4,0,0),new Vec3(-4,0,0),new Vec3(0,4,0),new Vec3(0,-4,0),new Vec3(0,0,4),new Vec3(0,0,-4)}){
                var g=new RollingMotionGuard(7,env,pose(f,null,true,true),center);g.beginCommit(pair(1,1,center),g.accepted(),p->true,0);var a=auth(1,1,1);g.committed(a,center,pose(f,a,true,true),p->true);
                boolean rejected=false;for(int i=0;i<30;i++){var old=g.accepted();if(!g.admit(old,d,p->true,true)){rejected=true;assertSame(old,g.accepted());break;}assertTrue(g.observe(pose(old.feet().add(d),a,true,true),p->true));assertTrue(RollingWindowAdmission.contains(g.accepted().body(),center));}assertTrue(rejected);
            }
        }
    }
    @Test void extremePacketsNeverBecomeUnboundedPrefetchIntentAndZeroLookIsAllowed(){
        var g=open();var original=g.latestIntent();
        for(var d:new Vec3[]{new Vec3(4.001,0,0),new Vec3(Double.NaN,0,0),new Vec3(0,Double.POSITIVE_INFINITY,0),new Vec3(0,0,Double.NEGATIVE_INFINITY)}){assertFalse(g.admit(g.accepted(),d,p->true,true));assertEquals(original,g.latestIntent());}
        assertTrue(g.admit(g.accepted(),Vec3.ZERO,p->true,true));
        assertFalse(RollingMotionGuard.bounded(new AABB(0,0,0,2,2,1),Vec3.ZERO));
    }
    @Test void diagonalSweepIsContainedAndExactBoundaryContactIsAccepted(){
        var g=open();for(int i=0;i<9;i++)accept(g,new Vec3(4,4,4));
        assertFalse(g.admit(g.accepted(),new Vec3(4,4,4),p->true,true));
        assertTrue(RollingWindowAdmission.contains(new AABB(47.4,132,8,48,133.8,8.6),C));
        assertFalse(RollingWindowAdmission.contains(new AABB(47.40001,132,8,48.00001,133.8,8.6),C));
    }
    @Test void publicationAndFrameMismatchCannotReleaseCommitHold(){
        var g=open();assertTrue(g.reserve(N,2,p->true));g.beginCommit(pair(2,2,N),g.accepted(),p->true,100);
        assertThrows(IllegalStateException.class,()->g.committed(auth(3,3,3),N,pose(8,auth(3,3,3)),p->true));
        assertThrows(IllegalStateException.class,()->g.committed(auth(2,2,2),N,pose(8,auth(2,2,2)),p->false));
        assertTrue(g.expired(100+RollingMotionGuard.COMMIT_TIMEOUT_NANOS));
        g.fail("reload after ALLOW");assertFalse(g.admit(g.accepted(),new Vec3(1,0,0),p->true,true));
    }
    @Test void heldBodyExpansionAndWrongAuthorityCannotEscapeTheCertificate(){
        var g=open();assertTrue(g.reserve(N,2,p->true));g.beginCommit(pair(2,2,N),g.accepted(),p->true,10);
        var old=g.accepted();var larger=new RollingMotionGuard.Pose(old.body().inflate(.01),old.feet(),true,true,g.authority());
        assertFalse(g.admitBody(larger,p->true));assertTrue(g.admitBody(old,p->true));
        var wrong=pose(8,new RollingMotionGuard.Authority(7,1,1,"pair1",99,1,"other-source"));
        assertFalse(g.admit(wrong,new Vec3(1,0,0),p->true,true));
    }
    @Test void sameGenerationEditRequiresFreshKnownBodyAndExactNewFrame(){
        var g=open();g.editHold(1,C);assertFalse(g.admit(g.accepted(),new Vec3(1,0,0),p->true,true));
        g.beginCommit(pair(1,2,C),g.accepted(),p->true,10);var fresh=auth(1,2,2);
        g.committed(fresh,C,pose(8,fresh),p->true);assertEquals(2,g.authority().frame());accept(g,new Vec3(1,0,0));
    }

    @Test void visualFrontierIntentAdvancesPreparationWithoutOverwritingBodyIntentAndRejectsStaleInput(){
        var g=open();var before=g.accepted();var bodyIntent=g.latestIntent();
        assertTrue(g.visualIntent(7,1,1,1,N));assertSame(before,g.accepted());assertEquals(N,g.visualGoal());assertEquals(bodyIntent,g.latestIntent());
        assertFalse(g.visualIntent(7,1,1,1,C));assertFalse(g.visualIntent(7,0,1,2,C));assertFalse(g.visualIntent(8,1,1,2,C));assertFalse(g.visualIntent(7,1,2,2,C));assertFalse(g.visualIntent(7,1,1,2,N.offset(1,0,0)));
        assertTrue(g.reserve(N,2,p->true));assertFalse(g.visualIntent(7,1,1,3,C.offset(-1,0,0)));assertEquals(N,g.reservation().candidate());
        assertTrue(g.admit(before,new Vec3(.2,0,0),p->true,true));assertEquals(8.2,g.latestIntent().x);assertEquals(N,g.visualGoal());
        g.beginCommit(pair(2,2,N),before,p->true,0);var next=auth(2,2,2);g.committed(next,N,pose(before.feet(),next,true,true),p->true);
        assertEquals(N,g.visualGoal());assertFalse(g.visualIntent(7,1,1,4,C));
        assertTrue(g.visualIntent(7,2,1,5,C));assertEquals(C,g.visualGoal());
    }

    @Test void correctionAfterAllowMustSettleBeforeAnAlreadyReceivedActorCommitCanPublish(){
        var g=open();assertTrue(g.reserve(N,2,p->true));g.beginCommit(pair(2,2,N),g.accepted(),p->true,1);
        assertFalse(g.admit(g.accepted(),new Vec3(1,0,0),p->true,true));g.correctionSent(5,2);
        boolean pairReady=true,actorCommitted=true;var next=auth(2,2,2);
        assertTrue(pairReady&&actorCommitted&&g.correctionPending());
        assertThrows(IllegalStateException.class,()->g.committed(next,N,pose(8,next),p->true));
        assertTrue(g.acknowledge(5,g.accepted(),true));
        g.committed(next,N,pose(8,next),p->true);assertEquals(RollingMotionGuard.State.OPEN,g.state());
    }

}
