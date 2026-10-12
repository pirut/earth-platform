package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.visual.NativeVisualPublication;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Owner-thread camera handover. A requested adjacent candidate is immutable until exact commit. */
final class RollingVisualFrontier {
    static final long HANDOVER_TIMEOUT_NANOS=120_000_000_000L;
    static final double CAMERA_MARGIN=5, EYE_HEIGHT=1.62;
    private final long epoch;
    private long publication,resources,generation,lastSequence,pendingPublication,pendingGeneration,deadline;
    private CubePos center,candidate,stagedCenter;
    RollingVisualFrontier(long epoch){if(epoch<=0)throw new IllegalArgumentException("Positive frontier epoch required");this.epoch=epoch;}
    CubePos candidate(){return candidate;}
    boolean awaiting(){return candidate!=null;}
    boolean expired(long now){return deadline!=0&&now-deadline>=0;}
    boolean request(long requestEpoch,long committed,long resource,long sequence,CubePos target,long now){
        if(requestEpoch!=epoch||center==null||committed!=publication||resource!=resources||sequence<=lastSequence||target==null||target.equals(center)||!RollingCubePlan.adjacent(center,target)||candidate!=null)return false;
        lastSequence=sequence;candidate=target;pendingGeneration=generation+1;deadline=now+HANDOVER_TIMEOUT_NANOS;return true;
    }
    boolean metadata(long requestEpoch,long nextPublication,long resource,CubePos current,CubePos target,long nextGeneration,long now){
        if(requestEpoch!=epoch||resource<1||current==null||target==null||center==null&&!current.equals(target)||pendingPublication!=0||nextPublication<=publication||nextGeneration!=(center==null?1:generation+1))return false;
        if(center!=null&&(resource!=resources||!center.equals(current)||candidate==null&&!target.equals(center)||candidate!=null&&!candidate.equals(target)&&!target.equals(center)))return false;
        if(candidate==null)candidate=target;stagedCenter=target;pendingGeneration=nextGeneration;pendingPublication=nextPublication;if(deadline==0&&center!=null)deadline=now+HANDOVER_TIMEOUT_NANOS;return true;
    }
    /** A same-center edit may win the race before a visual request was reserved. Retain its overlap until commit. */
    boolean edit(long requestEpoch,long nextPublication,long resource,long actorGeneration,CubePos current){
        if(requestEpoch!=epoch||center==null||!center.equals(current)||resource!=resources||actorGeneration!=generation||nextPublication<=publication||pendingPublication!=0)return false;
        if(candidate==null)candidate=current;stagedCenter=current;pendingGeneration=generation;pendingPublication=nextPublication;return true;
    }
    void committed(long nextPublication,long resource,long nextGeneration,CubePos target){
        if(!candidateMatches(nextPublication,resource,nextGeneration,target))throw new IllegalStateException("Visual commit differs from exact pinned handover");
        publication=nextPublication;resources=resource;generation=nextGeneration;center=target;candidate=stagedCenter=null;pendingPublication=pendingGeneration=deadline=0;
    }
    boolean candidateMatches(long nextPublication,long resource,long nextGeneration,CubePos target){
        return resource>0&&(center==null||resources==resource)&&stagedCenter!=null&&stagedCenter.equals(target)&&nextGeneration==pendingGeneration&&nextPublication==pendingPublication&&nextPublication>publication;
    }
    boolean admits(AABB body,Vec3 delta){
        return center!=null&&motionInside(center,body,delta)&&(candidate==null||motionInside(candidate,body,delta));
    }
    /** Same fixed conservative eye/third-person envelope as the published guard; never expands a permit. */
    static boolean motionInside(CubePos target,AABB body,Vec3 delta){
        var d=NativeVisualPublication.cameraPermit(target);double x=(body.minX+body.maxX)*.5+delta.x,y=body.minY+EYE_HEIGHT+delta.y,z=(body.minZ+body.maxZ)*.5+delta.z;
        return d.contains(x-CAMERA_MARGIN,y-CAMERA_MARGIN,z-CAMERA_MARGIN)&&d.contains(x+CAMERA_MARGIN,y+CAMERA_MARGIN,z+CAMERA_MARGIN);
    }
    static boolean cameraInside(CubePos target,Vec3 actual){return actual!=null&&NativeVisualPublication.cameraPermit(target).contains(actual.x,actual.y,actual.z);}
    static boolean eyeInside(CubePos target,AABB body){return cameraInside(target,new Vec3((body.minX+body.maxX)*.5,body.minY+EYE_HEIGHT,(body.minZ+body.maxZ)*.5));}
}
