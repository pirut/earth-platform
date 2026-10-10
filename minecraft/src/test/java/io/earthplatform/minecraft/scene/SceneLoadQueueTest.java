package io.earthplatform.minecraft.scene;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneLoadQueueTest {
    private static final WorldIdentity WORLD=new WorldIdentity(1,"test","1","data","palette");
    private static void load(CubeStreamAdmission gate,CubeStreamAdmission.Lease lease,Set<CubePos> resident) {
        var token=gate.admit(new CubeStreamAdmission.Header(WORLD,1,lease.position(),lease.generation(),0));
        assertTrue(token.accepted());assertEquals(CubeStreamAdmission.Rejection.NONE,gate.commit(token.token(),()->resident.add(lease.position())));
    }
    @Test void replanningRequeuesRetainedUnfinishedOwnersRatherThanOnlyNewLeases() {
        var gate=new CubeStreamAdmission(WORLD,1,256);Set<CubePos> resident=new HashSet<>();
        var a=new CubePos(0,468,0);var b=a.offset(1,0,0);var c=b.offset(1,0,0);
        gate.replaceInterest(List.of(a,b));var initial=SceneLoadQueue.rebuild(List.of(a,b),gate.view(),resident);
        load(gate,initial.getFirst(),resident);long retainedGeneration=initial.get(1).generation();
        var change=gate.replaceInterest(List.of(a,b,c));assertEquals(1,change.requested().size());
        var rebuilt=SceneLoadQueue.rebuild(List.of(a,b,c),gate.view(),resident);
        assertEquals(List.of(b,c),rebuilt.stream().map(CubeStreamAdmission.Lease::position).toList());
        assertEquals(retainedGeneration,rebuilt.getFirst().generation());
        rebuilt.forEach(lease->load(gate,lease,resident));assertTrue(SceneLoadQueue.rebuild(List.of(a,b,c),gate.view(),resident).isEmpty());
    }
    @Test void rapidMovementBeforeGenerationFinishesAlwaysConvergesWithBoundedWork() {
        var gate=new CubeStreamAdmission(WORLD,1,256);Set<CubePos> resident=new HashSet<>();List<CubePos> desired=List.of();
        for(int step=0;step<200;step++) {
            List<CubePos> next=new ArrayList<>();for(int z=-2;z<=2;z++)for(int y=-2;y<=2;y++)for(int x=-2;x<=2;x++)next.add(new CubePos(step+x,468+y,z));
            var change=gate.replaceInterest(next);change.retired().forEach(old->resident.remove(old.lease().position()));
            var pending=SceneLoadQueue.rebuild(next,gate.view(),resident);assertTrue(pending.size()<=125);
            for(int i=0;i<Math.min(2,pending.size());i++)load(gate,pending.get(i),resident);
            desired=next;
        }
        SceneLoadQueue.rebuild(desired,gate.view(),resident).forEach(lease->load(gate,lease,resident));
        assertEquals(125,resident.size());assertEquals(125,gate.view().metrics().loadedLeases());
        assertTrue(SceneLoadQueue.rebuild(desired,gate.view(),resident).isEmpty());
    }
    @Test void missingLoadedSnapshotOrUnadmittedOwnerFailsClosed() {
        var gate=new CubeStreamAdmission(WORLD,1,2);var p=new CubePos(0,0,0);
        gate.replaceInterest(List.of(p));assertThrows(IllegalStateException.class,()->SceneLoadQueue.rebuild(List.of(p),gate.view(),Set.of(p)));
        var resident=new HashSet<CubePos>();load(gate,gate.view().leases().getFirst().lease(),resident);
        assertThrows(IllegalStateException.class,()->SceneLoadQueue.rebuild(List.of(p),gate.view(),Set.of()));
        assertThrows(IllegalStateException.class,()->SceneLoadQueue.rebuild(List.of(),gate.view(),resident));
        assertThrows(IllegalArgumentException.class,()->SceneLoadQueue.rebuild(List.of(p,p),gate.view(),resident));
    }
}
