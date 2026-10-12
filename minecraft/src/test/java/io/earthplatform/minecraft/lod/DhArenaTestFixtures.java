package io.earthplatform.minecraft.lod;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.scene.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
final class DhArenaTestFixtures {
 static final ArenaTerrainSource SOURCE=ArenaTerrainSource.FIXED;
 static final ArenaDefinition DEF=ArenaDefinition.FIXED;
 static final WorldIdentity WORLD=new WorldIdentity(0,NativeArenaCubeProvider.GENERATOR_ID,NativeArenaCubeProvider.GENERATOR_VERSION,SOURCE.identity(),"test-palette");
 static final long EPOCH=4,RESOURCE=2;
 @FunctionalInterface interface Material {int at(long x,long y,long z);}
 static ArenaCapture capture(){return capture(SOURCE::materialAt);}
 static ArenaCapture capture(Material m){var b=ArenaCapture.builder(DEF);for(long y=DEF.readMinY();y<DEF.readMaxYExclusive();y++)for(long z=DEF.readMinZ();z<DEF.readMaxZExclusive();z++)for(long x=DEF.readMinX();x<DEF.readMaxXExclusive();x++)b.set(x,y,z,m.at(x,y,z));return b.seal();}
 static final class Guard implements DhArenaInput.AuthorityGuard {
  final Thread owner=Thread.currentThread();boolean current=true,historical=true;int calls;Runnable callback=()->{};
  public boolean isCurrent(ArenaCertificate c){assertSame(owner,Thread.currentThread());calls++;callback.run();return current;}
  public boolean permitsRetainedPresentation(ArenaCertificate c){assertSame(owner,Thread.currentThread());calls++;callback.run();return historical;}
 }
 static ArenaCertificate certificate(ArenaCapture capture,long generation,long revision){return certificate(capture,generation,revision,EPOCH,RESOURCE,"c".repeat(64),"d".repeat(64),0);}
 static ArenaCertificate certificate(ArenaCapture capture,long generation,long revision,long epoch,long resource,String index,String root,long leaseOffset){
  String store="00000000-0000-0000-0000-000000000001",palette="a".repeat(64),provider="provider";
  var policy=new RollingCubeActor.ReplacementPolicy(provider,DhArenaInput.MATERIAL_POLICY,DhArenaInput.DETAIL_POLICY,resource,RollingEditPolicy.FULL_CUBE_V1);
  String policyId=ArenaQueryAttestation.policyIdentity(WORLD,palette,policy,DEF,SOURCE.identity(),DhLodLayout.ARENA_4096.identity());
  var deps=new ArrayList<RollingCubeActor.CubeVersion>();long lease=leaseOffset+1;for(var p:DEF.dependencies())deps.add(new RollingCubeActor.CubeVersion(p,lease++,p.equals(DEF.positions().getFirst())?revision:0));
  var entries=DEF.positions().stream().map(p->new ArenaReplacementIndex.Entry(p,p.equals(DEF.positions().getFirst())?revision:0,policyId,capture.contentIdentity())).toList();
  var query=new ArenaQueryAttestation(store,index,Math.max(1,generation),root,DEF.identity(),DhLodLayout.ARENA_4096.identity(),policyId,ArenaReplacementIndex.resultIdentity(entries),8);
  return new ArenaCertificate(WORLD,epoch,resource,store,index,provider,palette,DhArenaInput.MATERIAL_POLICY,DhArenaInput.DETAIL_POLICY,generation,DEF,deps,capture.contentIdentity(),query);
 }
 static DhArenaInput input(Guard guard,long gen,long rev){return input(guard,gen,rev,capture());}
 static DhArenaInput input(Guard guard,long gen,long rev,ArenaCapture c){return new DhArenaInput(SOURCE,certificate(c,gen,rev),c,guard);}
 static List<CubePos> mask(CubePos c){var r=new ArrayList<CubePos>();for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++)r.add(c.offset(x,y,z));return List.copyOf(r);}
 static List<SceneReadyCube> ready(CubePos c){return mask(c).stream().map(p->new SceneReadyCube(p,WORLD,EPOCH,1,0,RESOURCE)).toList();}
 static long center(int c){return 256L*Math.floorDiv(16L*c+136,256);}
}
