package io.earthplatform.minecraft.coregate;
import com.mojang.serialization.Lifecycle;
import io.earthplatform.minecraft.enginebridge.NativeCubeActor;
import io.earthplatform.minecraft.network.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class CoreEndpointActorTest {
    static EnginePaletteContext palette;
    @TempDir Path temp;
    @BeforeAll static void registry(){var r=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());r.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);palette=EnginePaletteContext.create(r.freeze());}
    private List<NativeCubeActor.Event> initial(NativeCubeActor actor)throws Exception{var out=new ArrayList<NativeCubeActor.Event>();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(out.stream().filter(NativeCubeActor.Snapshot.class::isInstance).count()<125){assertTrue(System.nanoTime()<until,"bounded actor startup timeout");for(var e:actor.pollEvents(2)){if(e instanceof NativeCubeActor.Failure f)fail(f.detail());out.add(e);}Thread.sleep(1);}return out;}
    private void exercise(CoreGateProfile p)throws Exception{
        var provider=p.provider(palette);var dir=temp.resolve(p.saveDirectory());int diorite=Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState());
        var actor=NativeCubeActor.open(dir,provider.identity(),provider,palette,1,p.center());
        try{
            var events=initial(actor);var meta=(NativeCubeActor.ReadyMetadata)events.getFirst();assertEquals(p.center(),meta.center());
            try(var replica=new CoreGateReplica(meta.world(),1,palette,meta.leases().stream().map(NativeCubeActor.CubeLease::position).toList(),meta.leases().stream().map(NativeCubeActor.CubeLease::generation).toList(),meta.renderTargets(),p)){
                for(var e:events)if(e instanceof NativeCubeActor.Snapshot v)replica.accept(v.snapshot());
                var original=replica.cubes().get(p.target()).snapshot();var pos=p.commandEdit();replica.beginEdit();
                assertEquals(NativeCubeActor.Admission.QUEUED,actor.tryEdit(new NativeCubeActor.EditRequest(meta.world(),1,p.target(),original.leaseGeneration(),0,pos.getX(),pos.getY(),pos.getZ(),diorite)));
                boolean ack=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!ack){assertTrue(System.nanoTime()<until,"bounded durability timeout");for(var e:actor.pollEvents(2)){if(e instanceof NativeCubeActor.Failure f)fail(f.detail());if(e instanceof NativeCubeActor.Snapshot v)replica.accept(v.snapshot());if(e instanceof NativeCubeActor.EditAck a){assertTrue(a.applied());replica.acknowledge(a.status().name(),a.revision());ack=true;}}Thread.sleep(1);}
                assertEquals(1,replica.revision());assertFalse(replica.paused());
            }
        }finally{actor.close();assertTrue(actor.closed().get(10,TimeUnit.SECONDS).successful());}
        var reopened=NativeCubeActor.open(dir,provider.identity(),provider,palette,2,p.center());
        try{var events=initial(reopened);var saved=events.stream().filter(NativeCubeActor.Snapshot.class::isInstance).map(NativeCubeActor.Snapshot.class::cast).map(NativeCubeActor.Snapshot::snapshot).filter(s->s.position().equals(p.target())).findFirst().orElseThrow();assertEquals(1,saved.revision());var pos=p.commandEdit();assertTrue(saved.materialize(palette).get(pos.getX()&15,pos.getY()&15,pos.getZ()&15).is(Blocks.DIORITE));}
        finally{reopened.close();assertTrue(reopened.closed().get(10,TimeUnit.SECONDS).successful());}
    }
    @Test void abyssAuthoritativeGenerationNetworkDurabilityAndReopenRetainExactNativeY()throws Exception{exercise(CoreGateProfile.ABYSS);}
    @Test void summitAuthoritativeGenerationNetworkDurabilityAndReopenRetainExactNativeY()throws Exception{exercise(CoreGateProfile.SUMMIT);}
}
