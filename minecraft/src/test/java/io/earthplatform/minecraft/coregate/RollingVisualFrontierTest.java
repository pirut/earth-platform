package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.visual.NativeVisualPublication;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executable production permit and motion-owner traces; GL host transaction tests are separate. */
class RollingVisualFrontierTest {
    private static final long EPOCH=7,RESOURCE=1;
    private static final CubePos START=new CubePos(0,458,0);
    private static AABB body(Vec3 eye){return new AABB(eye.x-.3,eye.y-1.62,eye.z-.3,eye.x+.3,eye.y+.18,eye.z+.3);}
    private static RollingVisualFrontier ready(CubePos center){
        var f=new RollingVisualFrontier(EPOCH);assertTrue(f.metadata(EPOCH,1,RESOURCE,center,center,1,0));
        f.committed(1,RESOURCE,1,center);return f;
    }
    private static RollingMotionGuard.Pose pose(Vec3 eye,RollingMotionGuard.Authority a){return new RollingMotionGuard.Pose(body(eye),new Vec3(eye.x,eye.y-1.62,eye.z),true,true,a);}
    private static RollingMotionGuard.Authority auth(long publication){return new RollingMotionGuard.Authority(EPOCH,publication,publication,"pair"+publication,publication,RESOURCE,"source");}
    private static RollingMotionGuard.Pair pair(long publication,CubePos center){return new RollingMotionGuard.Pair(EPOCH,publication,publication,center,RESOURCE,"pair"+publication);}
    private static RollingMotionGuard motion(CubePos center,Vec3 eye){
        var m=new RollingMotionGuard(EPOCH,new AABB(-512,-11008,-512,512,8864,512),pose(eye,null),center);m.enableVisualFrontier();
        m.beginCommit(pair(1,center),m.accepted(),p->true,0);m.committed(auth(1),center,pose(eye,auth(1)),p->true);return m;
    }
    @Test void frozen033SmallUpPacketCannotSelectBackwardsCandidateAfterExactCommit(){
        var previous=new CubePos(0,459,0);var center=previous.offset(0,1,0);var heldEye=new Vec3(8,7360.62,8);var m=motion(previous,heldEye);
        assertTrue(m.visualIntent(EPOCH,1,RESOURCE,1,center));
        // A small positive packet still names old feet cube459 while READY8 commits center460.
        assertTrue(m.admit(m.accepted(),new Vec3(0,.02,0),p->true,true));
        assertEquals(previous,RollingCubePlan.centerFromFeet(m.latestIntent().x,m.latestIntent().y,m.latestIntent().z));
        assertTrue(m.reserve(center,2,p->true));m.beginCommit(pair(2,center),m.accepted(),p->true,0);
        m.committed(auth(2),center,pose(heldEye,auth(2)),p->true);
        // Frozen033 tick read latestIntent here and immediately staged backwards459.
        assertEquals(previous,RollingCubePlan.nextStep(center,RollingCubePlan.centerFromFeet(m.latestIntent().x,m.latestIntent().y,m.latestIntent().z)));
        assertEquals(center,m.visualGoal(),"Exact visual commit consumes the request; old feet cannot schedule backwards");
        var finalEye=new Vec3(8,7376.917273,8);var eye=heldEye;
        while(finalEye.y-eye.y>0){var delta=new Vec3(0,Math.min(4,finalEye.y-eye.y),0);assertTrue(m.admit(m.accepted(),delta,p->true,true));eye=eye.add(delta);assertTrue(m.observe(pose(eye,m.authority()),p->true));}
        assertTrue(NativeVisualPublication.cameraPermit(center).contains(eye.x,eye.y,eye.z));
        assertFalse(NativeVisualPublication.cameraPermit(previous).contains(eye.x,eye.y,eye.z));
        assertFalse(m.reserve(previous,3,p->true),"Invalid candidate eye cannot reserve or reach metadata");
        assertEquals(0,m.recoveries());
    }
    @Test void sustainedUpAndForwardUseDirectAdjacentPublicationsWithZeroRecovery(){
        for(var direction:new Vec3[]{new Vec3(0,1,0),new Vec3(1,0,0),new Vec3(1,1,1)}){
            var center=START;var eye=new Vec3(8,START.minBlockY()+8,8);var f=ready(center);var m=motion(center,eye);long publication=1,sequence=0;int commits=0;
            for(int packet=0;packet<500;packet++){
                var delta=direction.scale(.5);
                if(f.admits(body(eye),delta)){
                    assertTrue(m.admit(m.accepted(),delta,p->true,true));eye=eye.add(delta);assertTrue(m.observe(pose(eye,m.authority()),p->true));
                }else{
                    var next=center.offset((int)direction.x,(int)direction.y,(int)direction.z);
                    assertTrue(f.request(EPOCH,publication,RESOURCE,++sequence,next,packet));
                    assertTrue(m.visualIntent(EPOCH,publication,RESOURCE,sequence,next));
                    // In-flight old feet packets cannot overwrite the pinned visual target.
                    m.admit(m.accepted(),delta,p->true,true);assertEquals(next,m.visualGoal());
                    assertTrue(m.reserve(next,publication+1,p->true));m.requireMetadata(center,next,publication+1);
                    assertTrue(f.metadata(EPOCH,publication+1,RESOURCE,center,next,publication+1,packet));
                    assertTrue(RollingVisualFrontier.cameraInside(next,eye));
                    assertTrue(f.candidateMatches(publication+1,RESOURCE,publication+1,next));
                    m.beginCommit(pair(publication+1,next),m.accepted(),p->true,packet);
                    publication++;m.committed(auth(publication),next,pose(eye,auth(publication)),p->true);f.committed(publication,RESOURCE,publication,next);
                    center=next;commits++;assertEquals(center,m.visualGoal());
                }
                assertTrue(RollingVisualFrontier.cameraInside(center,eye));assertEquals(0,m.recoveries());assertFalse(f.expired(packet));
            }
            assertTrue(commits>=10,"Long hold must make repeated ordinary adjacent progress");
        }
    }
    @Test void maximalBoundedFirstPersonPacketsAdvanceAcrossEverySubcubePhaseWithoutRecovery(){
        for(int phase=0;phase<16;phase++)for(var direction:new Vec3[]{new Vec3(1,0,0),new Vec3(-1,0,0),new Vec3(0,1,0),new Vec3(0,-1,0)}){
            var center=START;var eye=new Vec3(phase+.1,START.minBlockY()+phase+.1,8);var f=ready(center);var m=motion(center,eye);long publication=1,sequence=0;int commits=0;
            for(int packet=0;packet<80;packet++){
                var delta=direction.scale(4);
                if(f.admits(body(eye),delta)){assertTrue(m.admit(m.accepted(),delta,p->true,true));eye=eye.add(delta);assertTrue(m.observe(pose(eye,m.authority()),p->true));}
                else{
                    var next=center.offset((int)direction.x,(int)direction.y,(int)direction.z);
                    assertTrue(RollingVisualFrontier.cameraInside(next,eye));
                    assertTrue(f.request(EPOCH,publication,RESOURCE,++sequence,next,packet));assertTrue(m.visualIntent(EPOCH,publication,RESOURCE,sequence,next));
                    assertTrue(m.reserve(next,publication+1,p->true));assertTrue(f.metadata(EPOCH,publication+1,RESOURCE,center,next,publication+1,packet));
                    m.beginCommit(pair(publication+1,next),m.accepted(),p->true,packet);publication++;m.committed(auth(publication),next,pose(eye,auth(publication)),p->true);f.committed(publication,RESOURCE,publication,next);center=next;commits++;
                }
                assertEquals(0,m.recoveries());assertTrue(RollingVisualFrontier.cameraInside(center,eye));
            }
            assertTrue(commits>=10);
        }
    }
    @Test void firstRequestPinsOverlapBeforeMetadataAndReversalCannotReplaceIt(){
        var c=new CubePos(0,0,0);var f=ready(c);var n=c.offset(1,0,0);var eye=new Vec3(16,8,8);
        assertTrue(f.request(EPOCH,1,RESOURCE,1,n,100));
        assertFalse(f.request(EPOCH,1,RESOURCE,2,c.offset(-1,0,0),101));
        assertTrue(f.admits(body(eye),new Vec3(-1,0,0)));
        assertFalse(f.admits(body(eye),new Vec3(-2,0,0)),"Published-only guard would allow escaping candidate backwards");
        assertFalse(f.admits(body(eye),new Vec3(2,0,0)),"Current permit still binds forward travel");
        assertTrue(f.metadata(EPOCH,2,RESOURCE,c,n,2,105));f.committed(2,RESOURCE,2,n);
        assertTrue(f.request(EPOCH,2,RESOURCE,3,c,110));assertEquals(c,f.candidate());
    }
    @Test void releaseConsumesNoFollowOnGoalAndLateOldAuthorityCannotResurrectIt(){
        var c=new CubePos(0,0,0);var eye=new Vec3(16,8,8);var m=motion(c,eye);var n=c.offset(1,0,0);
        assertTrue(m.visualIntent(EPOCH,1,RESOURCE,1,n));assertTrue(m.reserve(n,2,p->true));m.beginCommit(pair(2,n),m.accepted(),p->true,0);
        assertFalse(m.admit(m.accepted(),new Vec3(-.01,0,0),p->true,true));
        m.committed(auth(2),n,pose(eye,auth(2)),p->true);assertEquals(n,m.visualGoal());
        assertTrue(m.admit(m.accepted(),Vec3.ZERO,p->true,true));assertEquals(n,m.visualGoal());
        assertFalse(m.visualIntent(EPOCH,1,RESOURCE,99,c));assertEquals(n,m.visualGoal());
    }
    @Test void authorityGenerationResourceAndCameraAbaAreRecheckedAtEveryBoundary(){
        var c=new CubePos(0,0,0);var f=ready(c);var n=c.offset(1,0,0);
        assertFalse(f.request(8,1,RESOURCE,1,n,0));assertFalse(f.request(EPOCH,2,RESOURCE,1,n,0));assertFalse(f.request(EPOCH,1,2,1,n,0));
        assertTrue(f.request(EPOCH,1,RESOURCE,2,n,0));
        assertFalse(f.metadata(EPOCH,2,RESOURCE,c,n,3,1));assertFalse(f.metadata(EPOCH,2,2,c,n,2,1));assertFalse(f.metadata(EPOCH,2,RESOURCE,c,c.offset(-1,0,0),2,1));
        assertTrue(f.metadata(EPOCH,2,RESOURCE,c,n,2,1));
        assertFalse(f.candidateMatches(2,RESOURCE,3,n));assertFalse(f.candidateMatches(2,2,2,n));
        assertTrue(RollingVisualFrontier.cameraInside(n,new Vec3(16,8,8)));assertFalse(RollingVisualFrontier.cameraInside(n,new Vec3(8,8,8)));assertTrue(RollingVisualFrontier.cameraInside(n,new Vec3(16,8,8)));
        assertThrows(IllegalStateException.class,()->f.committed(2,RESOURCE,3,n));f.committed(2,RESOURCE,2,n);
        assertFalse(f.metadata(EPOCH,2,RESOURCE,c,n,2,2));assertFalse(f.request(EPOCH,1,RESOURCE,3,c,2));
    }
    @Test void ignoredIntentAndPausedMetadataHaveOneAbsoluteDeadline(){
        var c=new CubePos(0,0,0);var f=ready(c);var n=c.offset(1,0,0);long now=100;
        assertTrue(f.request(EPOCH,1,RESOURCE,1,n,now));assertFalse(f.expired(now+RollingVisualFrontier.HANDOVER_TIMEOUT_NANOS-1));
        assertTrue(f.metadata(EPOCH,2,RESOURCE,c,n,2,now+RollingVisualFrontier.HANDOVER_TIMEOUT_NANOS-10));
        assertTrue(f.expired(now+RollingVisualFrontier.HANDOVER_TIMEOUT_NANOS));
        f.committed(2,RESOURCE,2,n);assertFalse(f.expired(Long.MAX_VALUE));
    }
    @Test void bootstrapRawCameraAcceptsUpperFeetFractionWithoutMovingOrWideningPermit(){
        var c=new CubePos(0,460,0);var eye=new Vec3(8,c.minBlockY()+15.9+1.62,8);var f=new RollingVisualFrontier(EPOCH);
        assertTrue(f.metadata(EPOCH,1,RESOURCE,c,c,1,0));assertTrue(f.candidateMatches(1,RESOURCE,1,c));
        assertTrue(RollingVisualFrontier.cameraInside(c,eye));assertFalse(RollingVisualFrontier.motionInside(c,body(eye),Vec3.ZERO));
        f.committed(1,RESOURCE,1,c);assertFalse(f.expired(Long.MAX_VALUE));
        assertEquals(c.minBlockY()+22,NativeVisualPublication.cameraPermit(c).maxY());
    }
    @Test void sameCenterPinRestoreWinsEitherArrivalOrderWithoutConsumingIntentBeforeCommit(){
        var c=new CubePos(0,0,0);var next=c.offset(1,0,0);var eye=new Vec3(16,8,8);
        for(boolean intentFirst:new boolean[]{true,false}){
            var f=ready(c);var m=motion(c,eye);
            if(intentFirst){assertTrue(f.request(EPOCH,1,RESOURCE,1,next,100));assertTrue(m.visualIntent(EPOCH,1,RESOURCE,1,next));}
            assertTrue(m.reserve(c,2,p->true));
            if(!intentFirst){assertTrue(f.request(EPOCH,1,RESOURCE,1,next,100));assertFalse(m.visualIntent(EPOCH,1,RESOURCE,1,next));}
            assertTrue(f.metadata(EPOCH,2,RESOURCE,c,c,2,101));
            assertEquals(next,f.candidate(),"Original request overlap is retained until exact same-center commit");
            assertFalse(f.admits(body(eye),new Vec3(-2,0,0)));
            assertTrue(f.candidateMatches(2,RESOURCE,2,c));assertFalse(f.candidateMatches(2,RESOURCE,2,next));
            m.beginCommit(pair(2,c),m.accepted(),p->true,102);m.committed(auth(2),c,pose(eye,auth(2)),p->true);f.committed(2,RESOURCE,2,c);
            assertEquals(c,m.visualGoal());assertFalse(f.awaiting());assertFalse(f.expired(Long.MAX_VALUE));
            assertFalse(m.visualIntent(EPOCH,1,RESOURCE,2,next));
            assertTrue(f.request(EPOCH,2,RESOURCE,2,next,103));assertTrue(m.visualIntent(EPOCH,2,RESOURCE,2,next));assertEquals(0,m.recoveries());
        }
    }
    @Test void sameCenterEditRebasesAnUnreservedRequestOnlyAtExactCommit(){
        var c=new CubePos(0,0,0);var next=c.offset(1,0,0);var f=ready(c);
        assertTrue(f.request(EPOCH,1,RESOURCE,1,next,100));assertTrue(f.edit(EPOCH,2,RESOURCE,1,c));
        assertEquals(next,f.candidate());assertTrue(f.candidateMatches(2,RESOURCE,1,c));
        assertThrows(IllegalStateException.class,()->f.committed(2,RESOURCE,2,c));f.committed(2,RESOURCE,1,c);
        assertFalse(f.awaiting());assertTrue(f.request(EPOCH,2,RESOURCE,2,next,101));
    }
    @Test void signedCoordinatesAndAllSixCandidateFacesKeepStrictRawBoundary(){
        for(var c:new CubePos[]{new CubePos(-30,-450,-20),new CubePos(1000000,400,-1000000)}){
            var d=NativeVisualPublication.cameraPermit(c);assertTrue(d.contains(d.minX(),d.minY(),d.minZ()));assertTrue(d.contains(d.maxX(),d.maxY(),d.maxZ()));
            for(var p:new Vec3[]{new Vec3(d.minX()-.001,d.minY(),d.minZ()),new Vec3(d.maxX()+.001,d.maxY(),d.maxZ()),new Vec3(d.minX(),d.minY()-.001,d.minZ()),new Vec3(d.maxX(),d.maxY()+.001,d.maxZ()),new Vec3(d.minX(),d.minY(),d.minZ()-.001),new Vec3(d.maxX(),d.maxY(),d.maxZ()+.001)})assertFalse(RollingVisualFrontier.cameraInside(c,p));
        }
    }
}
