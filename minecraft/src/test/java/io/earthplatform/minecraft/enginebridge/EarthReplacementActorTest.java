package io.earthplatform.minecraft.enginebridge;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class EarthReplacementActorTest {
    private static final CubePos PIN=new CubePos(0,8,0);
    private static final String SOURCE="earth-six-material-test-source",LAYOUT=io.earthplatform.minecraft.lod.DhLodLayout.EARTH_ALPINE_2048.identity();
    private static final long EPOCH=303;
    private static final ReplacementPolicy POLICY=new ReplacementPolicy(SOURCE,EarthReplacementAdmission.MATERIAL_POLICY,EarthReplacementAdmission.DETAIL_POLICY,1);
    private static EnginePaletteContext palette;private static WorldIdentity world;private static int[] states;private static CubeProvider air;
    @TempDir Path temp;private final List<RollingCubeActor> actors=new ArrayList<>();private final Map<RollingCubeActor,ArrayDeque<Event>> events=new IdentityHashMap<>();
    @BeforeAll static void setup(){
        var registry=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());registry.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);palette=EnginePaletteContext.create(registry.freeze());
        world=new WorldIdentity(303,"earthplatform:earth-replacement-test","v1",SOURCE,NativeCubeCodec.paletteIdentity(palette));
        states=new int[]{id(Blocks.AIR),id(Blocks.STONE),id(Blocks.DIORITE),id(Blocks.ANDESITE),id(Blocks.TUFF),id(Blocks.SNOW_BLOCK)};
        air=(p,c)->CompletableFuture.completedFuture(ImmutableCube.uniform(states[0]));
    }
    private static int id(Block block){return Block.BLOCK_STATE_REGISTRY.getId(block.defaultBlockState());}
    @AfterEach void close()throws Exception{for(var a:actors)a.close();for(var a:actors)a.closed().get(20,TimeUnit.SECONDS);}
    private static EarthReplacementAdmission.Receipt receipt(EarthReplacementCapture c){return new EarthReplacementAdmission.Receipt(true,"Exact test layout admitted",c.contentIdentity(),SOURCE,LAYOUT);}
    private RollingCubeActor open(Path path,CubeProvider source,EarthReplacementAdmission admission){var a=RollingCubeActor.openEarth(path,world,source,palette,EPOCH,PIN,POLICY,SOURCE,LAYOUT,admission);actors.add(a);events.put(a,new ArrayDeque<>());return a;}
    private RollingCubeActor open(Path path){return open(path,air,EarthReplacementActorTest::receipt);}
    private Event next(RollingCubeActor a)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);var q=events.get(a);while(q.isEmpty()){q.addAll(a.pollEvents(2));if(q.isEmpty()){if(System.nanoTime()>deadline)fail("Timed out: "+a.diagnostics());Thread.sleep(1);}}return q.removeFirst();}
    private StageReady ready(RollingCubeActor a)throws Exception{for(;;){var e=next(a);if(e instanceof Failure f)fail(f.detail());if(e instanceof StageReady r)return r;}}
    private CubeVersion version(StageReady r){return r.versions().stream().filter(v->v.position().equals(PIN)).findFirst().orElseThrow();}
    private void commit(RollingCubeActor a,StageReady r)throws Exception{assertNotNull(r.earthReplacementCapture());assertEquals(Admission.QUEUED,a.commitPrepared(r.generation(),r.replacementCertificate().identity()));assertInstanceOf(Committed.class,next(a));}
    private StageReady start(RollingCubeActor a)throws Exception{var r=ready(a);commit(a,r);return r;}
    private StageReady move(RollingCubeActor a,int x)throws Exception{assertEquals(Admission.QUEUED,a.requestCenter(PIN.offset(x,0,0)));var r=ready(a);commit(a,r);assertTrue(a.diagnostics().residentCubes()<=213);return r;}
    private EditAck edit(RollingCubeActor a,CubeVersion v,int index,int state)throws Exception{assertEquals(Admission.QUEUED,a.tryEdit(new EditRequest(world,EPOCH,PIN,v.leaseGeneration(),v.revision(),index&15,128+(index>>>8),(index>>>4)&15,state)));for(;;){var e=next(a);if(e instanceof Failure f)fail(f.detail());if(e instanceof EditAck ack)return ack;}}
    private static List<Integer> isolated(){var cells=new ArrayList<Integer>();for(int y=1;y<15;y++)for(int z=1;z<15;z++)for(int x=1;x<15;x++)if(((x+y+z)&1)==0)cells.add(CubePos.index(x,y,z));return cells;}
    private Path seed(Path directory,int count)throws Exception{var edits=new HashMap<Integer,Integer>();var cells=isolated();for(int i=0;i<count;i++)edits.put(cells.get(i),states[1]);try(var store=new FileEditStore(directory,world)){store.save(new EditSnapshot(PIN,1,edits));return store.primaryPath(PIN);}}

    @Test void pristineRevisionZeroAuthorityCommitsRetiresAndRestoresWithoutSyntheticSave()throws Exception{
        Path path=temp.resolve("pristine");var a=open(path);var initial=ready(a);var cert=initial.replacementCertificate();assertNotNull(cert);assertEquals(0,cert.originalRevision());assertEquals(8000,initial.earthReplacementCapture().declaredBytes());
        assertEquals(Admission.NOT_READY,a.commitPrepared(initial.generation()));assertEquals(Admission.STALE_GENERATION,a.authorizePinRetirement(initial.generation(),cert.identity()));commit(a,initial);
        assertEquals(Admission.NOT_READY,a.retainPinWithoutReplacement(initial.generation()));assertEquals(Admission.NOT_READY,a.authorizePinRetirement(initial.generation(),"0".repeat(64)));assertEquals(Admission.QUEUED,a.authorizePinRetirement(initial.generation(),cert.identity()));
        StageReady far=null;for(int x=1;x<=10;x++){far=move(a,x);assertSame(initial.earthReplacementCapture(),far.earthReplacementCapture());assertEquals(cert,far.replacementCertificate());}
        assertFalse(far.pinned());assertEquals(Admission.NOT_READY,a.requestPinRestore());assertEquals(125,far.versions().size());assertFalse(far.versions().stream().anyMatch(v->v.position().equals(PIN)));
        StageReady back=null;for(int x=9;x>=0;x--)back=move(a,x);assertTrue(back.pinned());assertTrue(version(back).leaseGeneration()>version(initial).leaseGeneration());assertEquals(0,version(back).revision());
        a.close();assertTrue(a.closed().get(20,TimeUnit.SECONDS).successful());try(var store=new FileEditStore(path,world)){assertEquals(0,store.load(PIN).snapshot().revision());assertFalse(Files.exists(store.primaryPath(PIN)));}
    }
    @Test void repeatedAllSixMaterialEditsProduceImmutableCapturesAndFreshCommittedAuthority()throws Exception{
        Path path=temp.resolve("six");var a=open(path);var r=start(a);var v=version(r);var old=r.earthReplacementCapture();String oldIdentity=r.replacementCertificate().identity();
        int[] sequence={1,2,3,4,5,0,5,2,0};for(int material:sequence){var ack=edit(a,v,CubePos.index(4,5,4),states[material]);assertTrue(ack.applied(),ack.detail());assertNotNull(ack.replacementCertificate());assertEquals(v.revision()+1,ack.revision());assertEquals(material,ack.earthReplacementCapture().materialAt(4,133,4));assertNotEquals(oldIdentity,ack.replacementCertificate().identity());assertFalse(a.replacementCurrent(oldIdentity));assertTrue(EarthReplacementCaptureTest.arrayBytes(old,ack.earthReplacementCapture())<=16000);
            assertEquals(Admission.QUEUED,a.authorizePinRetirement(r.generation(),ack.replacementCertificate().identity()));v=new CubeVersion(PIN,v.leaseGeneration(),ack.revision());old=ack.earthReplacementCapture();oldIdentity=ack.replacementCertificate().identity();}
        assertEquals(16000,a.earthDiagnostics().peakCaptureArrayBytes());assertEquals(8000,a.earthDiagnostics().capturePayloadBytes());
        for(int x=1;x<=10;x++){var far=move(a,x);assertSame(old,far.earthReplacementCapture());assertEquals(oldIdentity,far.replacementCertificate().identity());}
        StageReady restored=null;for(int x=9;x>=0;x--)restored=move(a,x);assertTrue(restored.pinned());assertTrue(version(restored).leaseGeneration()>v.leaseGeneration());
        var fresh=edit(a,version(restored),CubePos.index(4,5,4),states[4]);assertTrue(fresh.applied());assertEquals(sequence.length+1,fresh.revision());assertEquals(4,fresh.earthReplacementCapture().materialAt(4,133,4));old=fresh.earthReplacementCapture();oldIdentity=fresh.replacementCertificate().identity();
        assertEquals(Admission.QUEUED,a.authorizePinRetirement(restored.generation(),oldIdentity));
        a.close();assertTrue(a.closed().get(20,TimeUnit.SECONDS).successful());var reopened=open(path);var saved=start(reopened);assertEquals(sequence.length+1,version(saved).revision());assertEquals(old.contentIdentity(),saved.earthReplacementCapture().contentIdentity());assertNotEquals(oldIdentity,saved.replacementCertificate().identity());
    }
    @Test void nativeCheckerboardRefusalKeepsDurableBytesAndRevision()throws Exception{
        Path directory=temp.resolve("native"),primary=seed(directory,840);byte[] before=Files.readAllBytes(primary);var a=open(directory);var r=start(a);var ack=edit(a,version(r),isolated().get(840),states[2]);assertEquals(EditStatus.NATIVE_BUDGET,ack.status());assertTrue(a.replacementCurrent(r.replacementCertificate().identity()));assertEquals(1,ack.revision());assertArrayEquals(before,Files.readAllBytes(primary));assertEquals(0,a.diagnostics().admittedEdits());assertFalse(Files.exists(primary.resolveSibling(primary.getFileName()+".bak")));
        var removed=edit(a,version(r),isolated().getFirst(),states[0]);assertTrue(removed.applied());assertEquals(2,removed.revision());
    }
    private static CubeProvider shell(){return (p,c)->{int[] blocks=new int[4096];Arrays.fill(blocks,states[0]);for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){long ax=p.minBlockX()+x,ay=p.minBlockY()+y-128,az=p.minBlockZ()+z;boolean within=ax>=-1&&ax<=16&&ay>=-1&&ay<=16&&az>=-1&&az<=16;boolean boundary=ax==-1||ax==16||ay==-1||ay==16||az==-1||az==16;if(within&&boundary&&((ax+ay+az)&1)==0)blocks[CubePos.index(x,y,z)]=states[1];}return CompletableFuture.completedFuture(ImmutableCube.copyOf(blocks));};}
    @Test void farCheckerboardRefusesBeforeSaveEvenWhenNativeBudgetFits()throws Exception{
        Path directory=temp.resolve("far"),primary=seed(directory,497);byte[] before=Files.readAllBytes(primary);var a=open(directory,shell(),EarthReplacementActorTest::receipt);var r=start(a);assertEquals(8190,EarthReplacementAdmission.faces(r.earthReplacementCapture()));
        var ack=edit(a,version(r),isolated().get(497),states[5]);assertEquals(EditStatus.REPLACEMENT_BUDGET,ack.status());assertTrue(a.replacementCurrent(r.replacementCertificate().identity()));assertEquals(1,ack.revision());assertArrayEquals(before,Files.readAllBytes(primary));assertEquals(0,a.diagnostics().admittedEdits());assertFalse(Files.exists(primary.resolveSibling(primary.getFileName()+".bak")));
    }
    @Test void initialSavedNativeAndFarOverbudgetFailBeforeReadyAndPreserveFiles()throws Exception{
        for(boolean far:new boolean[]{false,true}){Path dir=temp.resolve(far?"saved-far":"saved-native"),primary=seed(dir,far?498:841);byte[] before=Files.readAllBytes(primary);var a=open(dir,far?shell():air,EarthReplacementActorTest::receipt);for(;;){var e=next(a);assertFalse(e instanceof StageReady);if(e instanceof Failure)break;}assertFalse(a.closed().get(20,TimeUnit.SECONDS).successful());assertArrayEquals(before,Files.readAllBytes(primary));}
    }
    @Test void sourceLayoutAndContentReceiptMismatchCannotMutateDurableCube()throws Exception{
        for(int mismatch=0;mismatch<3;mismatch++){int type=mismatch;var bad=new AtomicBoolean();Path dir=temp.resolve("forged"+type),primary=seed(dir,1);byte[] before=Files.readAllBytes(primary);var a=open(dir,air,c->bad.get()?new EarthReplacementAdmission.Receipt(true,"forged",type==0?"wrong":c.contentIdentity(),type==1?"wrong":SOURCE,type==2?"wrong":LAYOUT):receipt(c));var r=start(a);bad.set(true);assertEquals(EditStatus.UNSUPPORTED_REPLACEMENT,edit(a,version(r),CubePos.index(4,4,4),states[4]).status());assertArrayEquals(before,Files.readAllBytes(primary));assertEquals(0,a.diagnostics().admittedEdits());a.close();a.closed().get(20,TimeUnit.SECONDS);}
    }
    @Test void missingStaleDiagonalAndLeaseDependenciesCannotAuthorizeEdits()throws Exception{
        for(int variant=0;variant<3;variant++){Path dir=temp.resolve("dependency"+variant),primary=seed(dir,1);byte[] before=Files.readAllBytes(primary);var a=open(dir);var r=start(a);var field=RollingCubeActor.class.getDeclaredField("currentVersions");field.setAccessible(true);@SuppressWarnings("unchecked")var old=(Map<CubePos,CubeVersion>)field.get(a);var changed=new HashMap<>(old);CubePos diagonal=PIN.offset(-1,-1,-1);var v=changed.get(diagonal);if(variant==0)changed.remove(diagonal);else changed.put(diagonal,new CubeVersion(diagonal,v.leaseGeneration()+(variant==2?1:0),v.revision()+(variant==1?1:0)));field.set(a,Map.copyOf(changed));
            assertEquals(EditStatus.NOT_READY,edit(a,version(r),CubePos.index(4,4,4),states[2]).status());assertArrayEquals(before,Files.readAllBytes(primary));a.close();a.closed().get(20,TimeUnit.SECONDS);}
    }
    @Test void failedSaveRevokesEarthAuthorityAndBlocksFurtherMovesOrEdits()throws Exception{
        Path path=temp.resolve("save-failure");var a=open(path);var r=start(a);var applied=edit(a,version(r),CubePos.index(4,4,4),states[2]);assertTrue(applied.applied());
        String identity=applied.replacementCertificate().identity();assertEquals(Admission.QUEUED,a.authorizePinRetirement(r.generation(),identity));
        Path primary=path.resolve("cubes/0_8_0.cube");Files.delete(primary);Files.createDirectory(primary);
        var v=new CubeVersion(PIN,version(r).leaseGeneration(),applied.revision());var failed=edit(a,v,CubePos.index(5,4,4),states[3]);assertEquals(EditStatus.SAVE_FAILED,failed.status());assertNull(failed.replacementCertificate());assertFalse(a.replacementCurrent(identity));assertEquals(Admission.NOT_READY,a.requestCenter(PIN.offset(1,0,0)));
        assertEquals(Admission.NOT_READY,a.tryEdit(new EditRequest(world,EPOCH,PIN,v.leaseGeneration(),v.revision(),4,132,4,states[0])));
    }
    @Test void earthCapabilityCannotBeSmuggledThroughLegacyOpen(){assertThrows(IllegalArgumentException.class,()->RollingCubeActor.open(temp,world,air,palette,EPOCH,PIN,POLICY));}
}
