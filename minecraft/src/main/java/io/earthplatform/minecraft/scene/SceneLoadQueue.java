package io.earthplatform.minecraft.scene;

import io.earthplatform.core.CubePos;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.*;

/** Reconstructs bounded work from current ownership, including unfinished leases retained by a replan. */
public final class SceneLoadQueue {
    private SceneLoadQueue() {}
    public static List<CubeStreamAdmission.Lease> rebuild(List<CubePos> orderedOwners,
            CubeStreamAdmission.View view,Set<CubePos> residentOwners) {
        Objects.requireNonNull(orderedOwners);Objects.requireNonNull(view);Objects.requireNonNull(residentOwners);
        if(view.state()!=CubeStreamAdmission.State.OPEN||view.leases().size()>SceneInterestPlan.MAX_OWNERS
                ||residentOwners.size()>SceneInterestPlan.MAX_OWNERS)throw new IllegalStateException("Invalid bounded scene ownership");
        Map<CubePos,CubeStreamAdmission.LeaseView> current=new HashMap<>();
        for(var lease:view.leases())if(current.put(lease.lease().position(),lease)!=null)throw new IllegalStateException("Duplicate current lease");
        Set<CubePos> visited=new HashSet<>();List<CubeStreamAdmission.Lease> pending=new ArrayList<>();
        for(var position:orderedOwners) {
            if(visited.size()>=SceneInterestPlan.MAX_OWNERS||!visited.add(Objects.requireNonNull(position)))throw new IllegalArgumentException("Invalid desired owner list");
            var lease=current.get(position);if(lease==null)throw new IllegalStateException("Desired cube lacks a current lease");
            boolean loaded=residentOwners.contains(position);
            if(lease.lifecycle()==CubeStreamAdmission.Lifecycle.REQUESTED) {
                if(loaded)throw new IllegalStateException("Unadmitted resident owner");
                pending.add(lease.lease());
            } else if(!loaded)throw new IllegalStateException("Loaded lease lost its resident snapshot");
        }
        if(!visited.equals(current.keySet())||!visited.containsAll(residentOwners))throw new IllegalStateException("Scene interest and current owners disagree");
        return List.copyOf(pending);
    }
}
