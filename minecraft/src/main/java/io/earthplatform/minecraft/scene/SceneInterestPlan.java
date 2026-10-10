package io.earthplatform.minecraft.scene;

import io.earthplatform.core.CubePos;
import java.util.*;

/** Surface-following 3D owners and explicit known neighbor halos, never a vertical column allocation. */
public record SceneInterestPlan(List<CubePos> owners,Set<CubePos> renderTargets,int centerX,int centerZ) {
    public static final int MAX_OWNERS=256;
    public static final int COLUMN_RADIUS=1;
    public static final double MAX_NEAR_DISTANCE=128;
    public SceneInterestPlan {
        Objects.requireNonNull(owners);Objects.requireNonNull(renderTargets);
        var boundedOwners=new LinkedHashSet<CubePos>();
        for(var owner:owners) {
            Objects.requireNonNull(owner);
            if(boundedOwners.size()>=MAX_OWNERS||!boundedOwners.add(owner))throw new IllegalArgumentException("Duplicate or over-budget scene owners");
            if(owner.minBlockY() < -11008||owner.minBlockY()+16>8864)throw new IllegalArgumentException("Owner outside native envelope");
        }
        var boundedTargets=new HashSet<CubePos>();
        for(var target:renderTargets) {
            Objects.requireNonNull(target);
            if(boundedTargets.size()>=MAX_OWNERS||!boundedOwners.contains(target)||!boundedTargets.add(target))
                throw new IllegalArgumentException("Render target lacks a unique bounded owner");
        }
        owners=List.copyOf(boundedOwners);renderTargets=Set.copyOf(boundedTargets);
    }
    public static SceneInterestPlan around(SceneSurfaceSampler source,double cameraX,double cameraY,double cameraZ) {
        if(!Double.isFinite(cameraX)||!Double.isFinite(cameraY)||!Double.isFinite(cameraZ))throw new IllegalArgumentException("Finite camera required");
        int centerX=CubePos.section((long)Math.floor(cameraX)),centerZ=CubePos.section((long)Math.floor(cameraZ));
        Set<CubePos> targets=new TreeSet<>(),owners=new TreeSet<>();
        for(int dz=-COLUMN_RADIUS;dz<=COLUMN_RADIUS;dz++)for(int dx=-COLUMN_RADIUS;dx<=COLUMN_RADIUS;dx++) {
            int cx=Math.addExact(centerX,dx),cz=Math.addExact(centerZ,dz);long min=Long.MAX_VALUE,max=Long.MIN_VALUE;
            for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                long height=source.surfaceY((long)cx*16+x,(long)cz*16+z);min=Math.min(min,height);max=Math.max(max,height);
            }
            if(min < -11000||max>8849)throw new IllegalArgumentException("Terrain plan outside supported native envelope");
            for(int cy=CubePos.section(min);cy<=CubePos.section(max);cy++) {
                var cube=new CubePos(cx,cy,cz);
                if(distance(cube,cameraX,cameraY,cameraZ)>MAX_NEAR_DISTANCE*MAX_NEAR_DISTANCE)continue;
                targets.add(cube);
                for(int hy=-1;hy<=1;hy++)for(int hz=-1;hz<=1;hz++)for(int hx=-1;hx<=1;hx++) {
                    var neighbor=cube.offset(hx,hy,hz);
                    if(neighbor.minBlockY()>=-11008&&neighbor.minBlockY()+16<=8864)owners.add(neighbor);
                    if(owners.size()>MAX_OWNERS)throw new IllegalArgumentException("Surface and known halo exceed the 256-owner near budget");
                }
            }
        }
        var ordered=new ArrayList<>(owners);
        ordered.sort(Comparator.comparingDouble((CubePos cube)->distance(cube,cameraX,cameraY,cameraZ)).thenComparing(Comparator.naturalOrder()));
        return new SceneInterestPlan(ordered,targets,centerX,centerZ);
    }
    private static double distance(CubePos cube,double x,double y,double z) {
        double dx=cube.minBlockX()+8.0-x,dy=cube.minBlockY()+8.0-y,dz=cube.minBlockZ()+8.0-z;
        return dx*dx+dy*dy+dz*dz;
    }
}
