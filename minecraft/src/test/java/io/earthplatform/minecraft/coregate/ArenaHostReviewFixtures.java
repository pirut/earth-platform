package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.lod.*;
import java.util.*;
import net.minecraft.world.phys.Vec3;

/** Independent value fixtures; no actor publication or catalogue implementation grants authority here. */
final class ArenaHostReviewFixtures {
    static final ArenaDefinition A = ArenaDefinition.FIXED;
    static final String STORE = "12345678-1234-1234-1234-123456789abc", INDEX = "a".repeat(64), PAL = "b".repeat(64);
    static final RollingSessionDescriptor D = descriptor();
    static final ArenaCapture AIR = emptyCapture();
    private ArenaHostReviewFixtures() {}
    private static RollingSessionDescriptor descriptor() {
        try {
            var c = RollingSessionDescriptor.class.getDeclaredConstructor(WorldIdentity.class,String.class,String.class,String.class,
                CubePos.class,CubePos.class,Vec3.class,int.class,long.class,long.class,boolean.class,DhLodLayout.class,RollingEditPolicy.class,
                RollingSessionDescriptor.BodyEnvelope.class,RollingSessionDescriptor.WindowEnvelope.class,boolean.class,boolean.class,boolean.class,boolean.class);
            c.setAccessible(true);
            var w = new WorldIdentity(27,NativeArenaCubeProvider.GENERATOR_ID,NativeArenaCubeProvider.GENERATOR_VERSION,"c".repeat(64),PAL);
            return c.newInstance(w,RollingSessionDescriptor.ARENA,RollingSessionDescriptor.ARENA_PROFILE_ID,PAL,A.origin(),A.origin(),new Vec3(8.5,137,12.5),136,0L,0L,false,DhLodLayout.ARENA_4096,RollingEditPolicy.FULL_CUBE_V1,
                new RollingSessionDescriptor.BodyEnvelope(-256,256,96,256,-256,256),new RollingSessionDescriptor.WindowEnvelope(-16,15,6,15,-16,15),false,false,false,true);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    static ArenaCapture emptyCapture() {
        var b = ArenaCapture.builder(A);
        for(long y=A.readMinY();y<A.readMaxYExclusive();y++) for(long z=A.readMinZ();z<A.readMaxZExclusive();z++) for(long x=A.readMinX();x<A.readMaxXExclusive();x++) b.set(x,y,z,ArenaCapture.AIR);
        return b.seal();
    }
    static List<RollingCubeActor.CubeVersion> dependencies(long lease,Map<CubePos,Long> revisions) {
        var values = new ArrayList<RollingCubeActor.CubeVersion>();
        for(var p:A.dependencies()) values.add(new RollingCubeActor.CubeVersion(p,lease++,revisions.getOrDefault(p,0L)));
        return values;
    }
    static ArenaCertificate certificate(long generation,long indexGeneration,long lease,Map<CubePos,Long> revisions,ArenaCapture capture) {
        return certificate(generation,indexGeneration,String.format("%064x",indexGeneration),lease,revisions,capture,RollingArenaQueryGuard.policy(D),D.lodLayout().identity(),1,1,STORE,INDEX);
    }
    static ArenaCertificate certificate(long generation,long indexGeneration,String root,long lease,Map<CubePos,Long> revisions,ArenaCapture capture,String policy,String layout,long epoch,long resources,String store,String index) {
        var deps=dependencies(lease,revisions);
        var entries=A.positions().stream().map(p->new ArenaReplacementIndex.Entry(p,revisions.getOrDefault(p,0L),policy,capture.contentIdentity())).toList();
        var query=new ArenaQueryAttestation(store,index,indexGeneration,root,A.identity(),layout,policy,ArenaReplacementIndex.resultIdentity(entries),8);
        return new ArenaCertificate(D.world(),epoch,resources,store,index,D.sourceFingerprint(),PAL,DhArenaInput.MATERIAL_POLICY,DhArenaInput.DETAIL_POLICY,generation,A,deps,capture.contentIdentity(),query);
    }
    static ArenaCertificate first() { return certificate(1,1,1,Map.of(),AIR); }
    static RollingCubeActor.StageReady ready(ArenaCertificate c,ArenaCapture capture) {
        var versions=new ArrayList<RollingCubeActor.CubeVersion>();long lease=65;
        for(var p:RollingCubePlan.stationary(A.origin(),A.origin(),false).committedOwners()) versions.add(new RollingCubeActor.CubeVersion(p,lease++,A.contains(p)?c.revision(p):0));
        return new RollingCubeActor.StageReady(D.world(),1,1,A.origin(),versions,null,false,null,null,List.of(),c,capture);
    }
    static ArenaCertificate replaceDependencies(ArenaCertificate c,List<RollingCubeActor.CubeVersion> deps) {
        return new ArenaCertificate(c.world(),c.epoch(),c.resourceGeneration(),c.storeIdentity(),c.indexIdentity(),c.providerIdentity(),c.paletteIdentity(),c.materialPolicy(),c.detailPolicy(),c.generation(),c.definition(),deps,c.captureIdentity(),c.queryAttestation());
    }
}
