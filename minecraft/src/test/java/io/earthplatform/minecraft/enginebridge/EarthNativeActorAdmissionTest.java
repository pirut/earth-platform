package io.earthplatform.minecraft.enginebridge;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class EarthNativeActorAdmissionTest {
    private static final CubePos PIN=new CubePos(0,8,0);
    private static final long EPOCH=301;
    private static EnginePaletteContext palette;
    private static WorldIdentity world;
    private static int air,stone,diorite;
    private static CubeProvider source;
    @TempDir Path temporary;
    private final List<RollingCubeActor> actors=new ArrayList<>();
    private final Map<RollingCubeActor,ArrayDeque<Event>> queues=new IdentityHashMap<>();
    @BeforeAll static void setup(){
        var registry=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        registry.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(registry.freeze());
        world=new WorldIdentity(301,"earthplatform:earth-native-census-test","v1","native-census",NativeCubeCodec.paletteIdentity(palette));
        air=Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());stone=Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());diorite=Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState());
        source=(position,cancellation)->CompletableFuture.completedFuture(ImmutableCube.uniform(air));
    }
    @AfterEach void close()throws Exception{for(var actor:actors)actor.close();for(var actor:actors)actor.closed().get(15,TimeUnit.SECONDS);}
    private RollingCubeActor open(Path directory,boolean earth){
        var actor=earth?RollingCubeActor.openEarth(directory,world,source,palette,EPOCH,PIN):RollingCubeActor.open(directory,world,source,palette,EPOCH,PIN);
        actors.add(actor);queues.put(actor,new ArrayDeque<>());return actor;
    }
    private Event next(RollingCubeActor actor)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);var queue=queues.get(actor);
        while(queue.isEmpty()){
            queue.addAll(actor.pollEvents(MAX_EVENTS_PER_POLL));
            if(queue.isEmpty()){if(System.nanoTime()>end)fail("Timed out waiting for actor event");Thread.sleep(1);}
        }
        return queue.removeFirst();
    }
    private StageReady ready(RollingCubeActor actor)throws Exception{
        for(;;){var event=next(actor);if(event instanceof Failure failed)fail(failed.detail());if(event instanceof StageReady ready)return ready;}
    }
    private CubeVersion start(RollingCubeActor actor)throws Exception{
        var ready=ready(actor);assertTrue(ready.pinned());assertNull(ready.replacementCertificate());assertNull(ready.arenaCertificate());
        assertEquals(Admission.QUEUED,actor.commitPrepared(ready.generation()));
        assertInstanceOf(Committed.class,next(actor));
        return ready.versions().stream().filter(v->v.position().equals(PIN)).findFirst().orElseThrow();
    }
    private EditAck edit(RollingCubeActor actor,CubeVersion v,int index,int state)throws Exception{
        long x=index&15,y=128+(index>>>8),z=(index>>>4)&15;
        assertEquals(Admission.QUEUED,actor.tryEdit(new EditRequest(world,EPOCH,PIN,v.leaseGeneration(),v.revision(),x,y,z,state)));
        for(;;){var event=next(actor);if(event instanceof Failure failed)fail(failed.detail());if(event instanceof EditAck ack)return ack;}
    }
    private List<Integer> isolatedCells(){
        var result=new ArrayList<Integer>();
        for(int y=1;y<15;y++)for(int z=1;z<15;z++)for(int x=1;x<15;x++)if(((x+y+z)&1)==0)result.add(CubePos.index(x,y,z));
        return result;
    }
    private Path seed(Path directory,int count)throws Exception{
        var blocks=new HashMap<Integer,Integer>();var cells=isolatedCells();for(int i=0;i<count;i++)blocks.put(cells.get(i),stone);
        try(var store=new FileEditStore(directory,world)){store.save(new EditSnapshot(PIN,1,blocks));return store.primaryPath(PIN);}
    }
    @Test void checkerboardRefusalLeavesDurableRevisionAndBytesThenSequentialEditsSucceed()throws Exception{
        Path directory=temporary.resolve("earth"),primary=seed(directory,840);byte[] before=Files.readAllBytes(primary);var cells=isolatedCells();
        var actor=open(directory,true);var version=start(actor);assertEquals(1,version.revision());
        var refused=edit(actor,version,cells.get(840),diorite);
        assertEquals(EditStatus.NATIVE_BUDGET,refused.status());assertEquals(1,refused.revision());assertArrayEquals(before,Files.readAllBytes(primary));
        assertFalse(Files.exists(primary.resolveSibling(primary.getFileName()+".bak")));assertEquals(0,actor.diagnostics().admittedEdits());
        var removed=edit(actor,version,cells.get(0),air);assertEquals(EditStatus.APPLIED,removed.status());assertEquals(2,removed.revision());
        version=new CubeVersion(PIN,version.leaseGeneration(),removed.revision());
        var added=edit(actor,version,cells.get(840),diorite);assertEquals(EditStatus.APPLIED,added.status());assertEquals(3,added.revision());
        actor.close();assertTrue(actor.closed().get(15,TimeUnit.SECONDS).successful());
        try(var store=new FileEditStore(directory,world)){var saved=store.load(PIN).snapshot();assertEquals(3,saved.revision());assertEquals(air,saved.blocks().get(cells.get(0)));assertEquals(diorite,saved.blocks().get(cells.get(840)));}
        var reopened=open(directory,true);assertEquals(3,start(reopened).revision());
    }
    @Test void oversizedSavedPinIsRejectedBeforeReadyAndIsNotRewritten()throws Exception{
        Path directory=temporary.resolve("oversized"),primary=seed(directory,841);byte[] before=Files.readAllBytes(primary);
        var actor=open(directory,true);boolean failed=false;
        for(;;){var event=next(actor);assertFalse(event instanceof StageReady,"Over-cap saved pin must never become READY");if(event instanceof Failure){failed=true;break;}if(event instanceof Closed)break;}
        assertTrue(failed);assertFalse(actor.closed().get(15,TimeUnit.SECONDS).successful());assertArrayEquals(before,Files.readAllBytes(primary));
        try(var store=new FileEditStore(directory,world)){assertEquals(1,store.load(PIN).snapshot().revision());}
    }
    @Test void unsupportedEditAndExistingUnsupportedOverlayFailBeforeWrites()throws Exception{
        Path directory=temporary.resolve("unsupported");var actor=open(directory,true);var version=start(actor);
        int grass=Block.BLOCK_STATE_REGISTRY.getId(Blocks.GRASS_BLOCK.defaultBlockState());
        assertEquals(EditStatus.UNSUPPORTED_NATIVE,edit(actor,version,CubePos.index(4,4,4),grass).status());assertEquals(0,actor.diagnostics().admittedEdits());
        actor.close();actor.closed().get(15,TimeUnit.SECONDS);
        Path primary;try(var store=new FileEditStore(directory,world)){assertEquals(0,store.load(PIN).snapshot().revision());store.save(new EditSnapshot(PIN,1,Map.of(CubePos.index(4,4,4),grass)));primary=store.primaryPath(PIN);}
        byte[] before=Files.readAllBytes(primary);var reopened=open(directory,true);
        for(;;){var event=next(reopened);assertFalse(event instanceof StageReady);if(event instanceof Failure)break;}
        assertFalse(reopened.closed().get(15,TimeUnit.SECONDS).successful());assertArrayEquals(before,Files.readAllBytes(primary));
    }
    @Test void missingOrStaleDiagonalDependencyBlocksAdmissionWithoutChangingSave()throws Exception{
        for(boolean stale:new boolean[]{false,true}){
            Path directory=temporary.resolve(stale?"stale":"missing"),primary=seed(directory,1);byte[] before=Files.readAllBytes(primary);
            var actor=open(directory,true);var version=start(actor);
            var field=RollingCubeActor.class.getDeclaredField("currentVersions");field.setAccessible(true);
            @SuppressWarnings("unchecked") var old=(Map<CubePos,CubeVersion>)field.get(actor);
            var changed=new HashMap<>(old);var diagonal=PIN.offset(-1,-1,-1);var v=changed.get(diagonal);
            if(stale)changed.put(diagonal,new CubeVersion(diagonal,v.leaseGeneration(),v.revision()+1));else changed.remove(diagonal);
            field.set(actor,Map.copyOf(changed));
            assertEquals(EditStatus.NOT_READY,edit(actor,version,CubePos.index(4,4,4),diorite).status());
            assertArrayEquals(before,Files.readAllBytes(primary));assertEquals(0,actor.diagnostics().admittedEdits());
            actor.close();actor.closed().get(15,TimeUnit.SECONDS);
        }
    }
    @Test void legacyActorHasNoImplicitNewEarthCensus()throws Exception{
        Path directory=temporary.resolve("legacy");seed(directory,841);
        var actor=open(directory,false);assertEquals(1,start(actor).revision());
    }
}
