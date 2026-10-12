package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.network.*;
import io.earthplatform.minecraft.scene.SceneCube;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import org.junit.jupiter.api.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;

/** Independent compact-wire and client-authority review; no source data, GL, or client launch. */
class RollingEarthReplacementIndependentTest {
    static final CubePos P=CubePos.fromBlock(EarthAlpineDefinition.ANCHOR_X,EarthAlpineDefinition.GROUND_Y,EarthAlpineDefinition.ANCHOR_Z);
    static final long EPOCH=707;
    static EnginePaletteContext palette;
    static WorldIdentity world;
    static final Block[] MATERIALS={Blocks.AIR,Blocks.STONE,Blocks.DIORITE,Blocks.ANDESITE,Blocks.TUFF,Blocks.SNOW_BLOCK};
    @BeforeAll static void setup(){
        var r=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        r.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(r.freeze());
        world=new WorldIdentity(EarthAlpineDefinition.SEED,NativeAlpineCubeProvider.GENERATOR_ID,NativeAlpineCubeProvider.GENERATOR_VERSION+"/"+RollingGateFixture.VERSION,EarthAlpineDefinition.DEFINITION_IDENTITY,NativeCubeCodec.paletteIdentity(palette));
    }
    static List<CubeVersion> versions(CubePos p,long lease,long revision){var out=new ArrayList<CubeVersion>();for(var q:CubeTickets.box(p,2,2,125).stream().sorted().toList())out.add(new CubeVersion(q,lease++,q.equals(P)?revision:0));return List.copyOf(out);}
    static ReplacementCertificate certificate(CubePos p,long generation,long resources,List<CubeVersion> versions,String material,String detail){
        var deps=CubeTickets.box(p,1,1,27);return new ReplacementCertificate(world,EPOCH,resources,p,"independent-store",world.datasetVersion(),palette.fingerprint(),material,detail,generation,versions.stream().filter(v->deps.contains(v.position())).toList());
    }
    static ReplacementCertificate certificate(long generation,long resources,List<CubeVersion> versions){return certificate(P,generation,resources,versions,EarthReplacementAdmission.MATERIAL_POLICY,EarthReplacementAdmission.DETAIL_POLICY);}
    static EarthReplacementCapture capture(CubePos p,boolean varied){try(var b=new EarthReplacementCapture.Builder(p)){for(int i=0;i<8000;i++)b.setNext(varied?i%6:0);return b.finish();}}
    static StageReady ready(List<CubeVersion> v,ReplacementCertificate c,EarthReplacementCapture cap){return new StageReady(world,EPOCH,1,P,v,c,true,null,null,List.of(),null,null,cap);}
    static FriendlyByteBuf buffer(){return new FriendlyByteBuf(Unpooled.buffer());}
    static StageReady decode(StageReady r){var b=buffer();try{RollingGateWire.writeReady(b,r);var value=RollingGateWire.readReady(b,world,EPOCH);assertFalse(b.isReadable());return value;}finally{b.release();}}
    static RollingReplacementAuthority authority(){return new RollingReplacementAuthority(world,EPOCH,1,P,world.datasetVersion(),palette.fingerprint(),EarthReplacementAdmission.MATERIAL_POLICY,EarthReplacementAdmission.DETAIL_POLICY);}

    @Test void revisionZeroRoundTripHasExact27DependenciesAndHighCoordinates(){
        var v=versions(P,1,0);var c=certificate(1,1,v);var cap=capture(P,true);var r=ready(v,c,cap);var decoded=decode(r);
        assertEquals(0,decoded.replacementCertificate().originalRevision());assertEquals(27,c.dependencies().size());assertEquals(c,decoded.replacementCertificate());
        assertNotSame(cap,decoded.earthReplacementCapture());assertEquals(cap.contentIdentity(),decoded.earthReplacementCapture().contentIdentity());
        assertEquals(RollingGateWire.manifestDigest(r),RollingGateWire.manifestDigest(decoded));assertTrue(P.minBlockX()>9_000_000);assertTrue(P.minBlockZ()< -3_000_000);
        for(int i=0;i<8000;i++)assertEquals(i%6,decoded.earthReplacementCapture().materialAt(cap.minX()+i%20,cap.minY()+i/400,cap.minZ()+i/20%20));
    }
    @Test void repeatedEqualWireContentCanonicalizesBeforeItCanMultiplyOwners(){
        var v=versions(P,1,0);var cap=capture(P,true);var r=ready(v,certificate(1,1,v),cap);
        for(int i=0;i<32;i++){var decoded=decode(r);var canonical=RollingEarthCaptureOwner.canonicalize(decoded,cap);assertSame(cap,canonical.earthReplacementCapture());assertSame(decoded.replacementCertificate(),canonical.replacementCertificate());assertEquals(RollingGateWire.manifestDigest(r),RollingGateWire.manifestDigest(canonical));}
    }
    @Test void changedContentChangesManifestAndRetainsDistinctOwner(){
        var v=versions(P,1,0);var old=capture(P,false);var c=certificate(1,1,v);var changed=old.withMaterial(P.minBlockX()+4,P.minBlockY()+4,P.minBlockZ()+4,5).compact();var r=ready(v,c,changed);var decoded=decode(r);var canonical=RollingEarthCaptureOwner.canonicalize(decoded,old);
        assertSame(decoded,canonical);assertNotSame(old,canonical.earthReplacementCapture());assertNotEquals(RollingGateWire.manifestDigest(ready(v,c,old)),RollingGateWire.manifestDigest(canonical));
    }
    @Test void unchangedContentDurableRevisionStillChangesManifestAndAuthority(){
        var old=capture(P,false);var v=versions(P,1,0);var r=ready(v,certificate(1,1,v),old);var nv=versions(P,1,1);var next=ready(nv,certificate(2,1,nv),old);var result=RollingEarthCaptureOwner.canonicalize(decode(next),old);
        assertSame(old,result.earthReplacementCapture());assertEquals(1,result.replacementCertificate().originalRevision());assertNotEquals(RollingGateWire.manifestDigest(r),RollingGateWire.manifestDigest(result));
    }
    static long payloadBytes(Object... roots)throws Exception{
        var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());var queue=new ArrayDeque<Object>();for(var root:roots)if(root!=null)queue.add(root);long bytes=0;
        while(!queue.isEmpty()){var value=queue.remove();if(!seen.add(value))continue;if(value instanceof byte[] data){bytes+=data.length;continue;}
            for(var field:value.getClass().getDeclaredFields())if(!java.lang.reflect.Modifier.isStatic(field.getModifiers())&&(field.getType()==byte[].class||field.getType()==EarthReplacementCapture.class)){field.setAccessible(true);var child=field.get(value);if(child!=null)queue.add(child);}}
        return bytes;
    }
    @Test void clientCompactPayloadPeakIs24kAndFarWrapperDoesNotDecodeOrCopy()throws Exception{
        var old=capture(P,false);var changed=old.withMaterial(P.minBlockX()+4,P.minBlockY()+4,P.minBlockZ()+4,5).compact();var wrapper=io.earthplatform.minecraft.lod.DhVoxelReplacementGeometry.captureEarth(changed);
        assertSame(changed,wrapper.earthCapture());assertEquals(8000,wrapper.declaredBytes());assertEquals(16000,payloadBytes(old,changed,wrapper));
        for(var field:wrapper.getClass().getDeclaredFields())if(field.getType()==int[].class){field.setAccessible(true);assertNull(field.get(wrapper),"Earth wrapper must never materialize decoded int[8000]");}
        var v=versions(P,1,1);var r=ready(v,certificate(2,1,v),changed);var incoming=decode(r);assertEquals(24000,payloadBytes(old,changed,wrapper,incoming.earthReplacementCapture()));
        var canonical=RollingEarthCaptureOwner.canonicalize(incoming,changed);assertEquals(16000,payloadBytes(old,changed,wrapper,canonical.earthReplacementCapture()));
        try(var builder=new EarthReplacementCapture.Builder(P)){assertEquals(24000,payloadBytes(old,changed,wrapper,builder));}
    }
    @Test void wireRejectsMalformedOversizedAndTruncatedCountsWithoutPartialOwner(){
        var c=certificate(1,1,versions(P,1,0));for(int count:new int[]{-1,0,7999,8001,Integer.MAX_VALUE}){var b=buffer();try{b.writeUtf("0".repeat(64));b.writeVarInt(count);assertThrows(RuntimeException.class,()->RollingEarthReplacementWire.read(b,c));}finally{b.release();}}
        for(int bytes:new int[]{0,1,3999,7999}){var b=buffer();try{b.writeUtf("0".repeat(64));b.writeVarInt(8000);b.writeZero(bytes);assertThrows(RuntimeException.class,()->RollingEarthReplacementWire.read(b,c));}finally{b.release();}}
    }
    @Test void wireRejectsForeignMaterialsAndForgedDigest(){
        var c=certificate(1,1,versions(P,1,0));var cap=capture(P,false);for(int material:new int[]{6,127,255}){var b=buffer();try{RollingEarthReplacementWire.write(b,c,cap);b.setByte(b.writerIndex()-1,material);assertThrows(RuntimeException.class,()->RollingEarthReplacementWire.read(b,c));}finally{b.release();}}
        var b=buffer();try{b.writeUtf("f".repeat(64));b.writeVarInt(8000);b.writeZero(8000);assertThrows(IllegalArgumentException.class,()->RollingEarthReplacementWire.read(b,c));}finally{b.release();}
    }
    @Test void wireRejectsForeignPolicyWrongCubeAndMissingCapture(){
        var v=versions(P,1,1);var cap=capture(P,false);var cert=certificate(1,1,v);
        assertThrows(IllegalArgumentException.class,()->RollingEarthReplacementWire.require(cert,capture(P.offset(1,0,0),false)));
        for(boolean material:new boolean[]{false,true}){var wrong=certificate(P,1,1,v,material?"foreign":EarthReplacementAdmission.MATERIAL_POLICY,material?EarthReplacementAdmission.DETAIL_POLICY:"foreign");assertThrows(IllegalArgumentException.class,()->RollingEarthReplacementWire.require(wrong,cap));}
        var elsewhere=P.offset(2,0,0);var ev=versions(elsewhere,1,1);var wrong=certificate(elsewhere,1,1,ev,EarthReplacementAdmission.MATERIAL_POLICY,EarthReplacementAdmission.DETAIL_POLICY);assertThrows(IllegalArgumentException.class,()->RollingEarthReplacementWire.require(wrong,capture(elsewhere,false)));
        assertThrows(IllegalArgumentException.class,()->new StageReady(world,EPOCH,1,P,v,cert,true));
    }
    @Test void bodyDecoderConsumesExactPayloadAndLeavesEnclosingSentinel(){var c=certificate(1,1,versions(P,1,0));var b=buffer();try{RollingEarthReplacementWire.write(b,c,capture(P,true));int end=b.writerIndex();b.writeByte(77);RollingEarthReplacementWire.read(b,c);assertEquals(end,b.readerIndex());assertEquals(77,b.readUnsignedByte());}finally{b.release();}}
    @Test void malformedDependencyVectorsCannotCreateEarthCertificate(){
        var good=certificate(1,1,versions(P,1,0));for(int mode=0;mode<4;mode++){var deps=new ArrayList<>(good.dependencies());if(mode==0)deps.removeLast();if(mode==1)Collections.swap(deps,0,1);if(mode==2)deps.set(1,deps.getFirst());if(mode==3){var x=deps.get(1);deps.set(1,new CubeVersion(x.position(),deps.getFirst().leaseGeneration(),x.revision()));}
            assertThrows(IllegalArgumentException.class,()->new ReplacementCertificate(world,EPOCH,1,P,good.storeIdentity(),good.providerIdentity(),good.paletteIdentity(),good.materialPolicy(),good.detailPolicy(),1,deps));}
    }
    static Map<CubePos,SceneCube> scenes(ReplacementCertificate c,EarthReplacementCapture capture,long leaseOffset){
        var cubes=new HashMap<CubePos,SceneCube>();for(var v:c.dependencies()){var p=v.position();var section=EngineCubeSection.uniform(palette,Blocks.AIR.defaultBlockState());
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){long ax=p.minBlockX()+x,ay=p.minBlockY()+y,az=p.minBlockZ()+z;if(capture.containsReadCell(ax,ay,az))section.set(x,y,z,MATERIALS[capture.materialAt(ax,ay,az)].defaultBlockState());}
            cubes.put(p,new SceneCube(EngineCubeSnapshot.capture(p,world,EPOCH,v.leaseGeneration()+leaseOffset,v.revision(),section),palette));}return cubes;
    }
    @Test void sixMaterialsMatchNativeSnapshotsInBoundedEightSteps(){
        var cap=capture(P,true);var c=certificate(1,1,versions(P,1,0));var cubes=scenes(c,cap,0);try(var validation=new RollingEarthCaptureValidation(c,cap)){
            for(int step=1;step<=8;step++){assertEquals(step==8,validation.step(cubes));assertEquals(Math.min(8000,step*1024),validation.completedReads());}assertSame(cap,validation.finish(cubes));
        }
    }
    @Test void everyDependencyAndLateRevisionMutationFencesValidation(){
        var cap=capture(P,false);var c=certificate(1,1,versions(P,1,0));var cubes=scenes(c,cap,0);
        for(var dep:c.dependencies()){var removed=cubes.remove(dep.position());assertThrows(IllegalStateException.class,()->RollingEarthCaptureValidation.verifyBindings(c,cubes));cubes.put(dep.position(),removed);}
        try(var validation=new RollingEarthCaptureValidation(c,cap)){assertFalse(validation.step(cubes));var dep=c.dependencies().getLast();cubes.put(dep.position(),new SceneCube(EngineCubeSnapshot.capture(dep.position(),world,EPOCH,dep.leaseGeneration(),1,EngineCubeSection.uniform(palette,Blocks.AIR.defaultBlockState())),palette));assertThrows(IllegalStateException.class,()->validation.step(cubes));}
    }
    @Test void wrongMaterialAndReacquiredLeaseCannotMasqueradeAsFreshCapture(){
        var cap=capture(P,false);var c=certificate(1,1,versions(P,1,0));var changed=cap.withMaterial(P.minBlockX()+4,P.minBlockY()+4,P.minBlockZ()+4,5).compact();var cubes=scenes(c,changed,0);
        try(var validation=new RollingEarthCaptureValidation(c,cap)){assertThrows(IllegalStateException.class,()->{while(!validation.step(cubes)){};});}
        assertThrows(IllegalStateException.class,()->RollingEarthCaptureValidation.verifyBindings(c,scenes(c,cap,1000)));
    }
    @Test void retainedRefusalAfterReacquisitionValidatesAllVoxelsButStillRejectsChangedDurability(){
        var cap=capture(P,true);var c=certificate(1,1,versions(P,1,0));var reacquired=scenes(c,cap,1000);
        try(var validation=new RollingEarthCaptureValidation(c,cap,false)){for(int step=0;step<8;step++)validation.step(reacquired);assertSame(cap,validation.finish(reacquired));}
        var dep=c.dependencies().getLast();reacquired.put(dep.position(),new SceneCube(EngineCubeSnapshot.capture(dep.position(),world,EPOCH,dep.leaseGeneration()+1000,1,EngineCubeSection.uniform(palette,Blocks.AIR.defaultBlockState())),palette));
        assertThrows(IllegalStateException.class,()->RollingEarthCaptureValidation.verifyBindings(c,reacquired,false));
        var blank=capture(P,false);var wrong=blank.withMaterial(P.minBlockX()+4,P.minBlockY()+4,P.minBlockZ()+4,5).compact();var different=scenes(c,wrong,1000);
        try(var validation=new RollingEarthCaptureValidation(c,blank,false)){assertThrows(IllegalStateException.class,()->{while(!validation.step(different)){};});}
    }
    @Test void refusalRestoresExactHistoricalCertificateOnlyThroughProposalThenCommit(){
        var v=versions(P,1,0);var cap=capture(P,false);var c=certificate(1,1,v);var r=ready(v,c,cap);var m=authority();m.propose(c);m.commit(c.identity());m.beginEdit();assertNull(m.committed());assertFalse(m.isCurrent(c.identity()));assertTrue(m.canPresent(c.identity()));
        assertThrows(IllegalStateException.class,()->m.commit(c.identity()));assertThrows(IllegalArgumentException.class,()->m.propose(c));m.reproposeRefusedEarth(c);assertNull(m.committed());assertTrue(m.isCurrent(c.identity()));m.commit(c.identity());assertSame(c,m.committed());assertEquals(1,m.retainedCertificates());
        var target=v.stream().filter(x->x.position().equals(P)).findFirst().orElseThrow();var request=new EditRequest(world,EPOCH,P,target.leaseGeneration(),0,P.minBlockX()+4,P.minBlockY()+4,P.minBlockZ()+4,1);
        var ack=new EditAck(request,EditStatus.NATIVE_BUDGET,0,"refused");var restored=RollingEditAcknowledgement.ready(r,P,v,ack);assertSame(c,restored.replacementCertificate());assertSame(cap,restored.earthReplacementCapture());assertEquals(RollingGateWire.manifestDigest(r),RollingGateWire.manifestDigest(restored));
    }
    @Test void changedAndCancelledGenerationsCannotReplayAndReloadClearsAllAuthority(){
        var c=certificate(1,1,versions(P,1,0));var next=certificate(2,1,versions(P,1,1));var m=authority();m.propose(c);m.commit(c.identity());m.beginEdit();assertThrows(IllegalArgumentException.class,()->m.reproposeRefusedEarth(next));m.propose(next);assertEquals(2,m.retainedCertificates());assertNull(m.committed());m.commit(next.identity());assertFalse(m.canPresent(c.identity()));assertThrows(IllegalArgumentException.class,()->m.propose(c));
        m.beginEdit();var cancelled=certificate(3,1,versions(P,1,2));m.propose(cancelled);m.cancelProposal();assertThrows(IllegalArgumentException.class,()->m.propose(cancelled));assertThrows(IllegalArgumentException.class,()->m.propose(certificate(4,2,versions(P,1,2))));m.close();m.close();assertFalse(m.isCurrent(next.identity()));assertFalse(m.canPresent(next.identity()));assertEquals(0,m.retainedCertificates());assertThrows(IllegalStateException.class,()->m.reproposeRefusedEarth(next));
    }
    @Test void uncertainDurabilityCannotRestoreHistoricalReady(){
        var v=versions(P,1,0);var r=ready(v,certificate(1,1,v),capture(P,false));var target=v.stream().filter(x->x.position().equals(P)).findFirst().orElseThrow();var request=new EditRequest(world,EPOCH,P,target.leaseGeneration(),0,P.minBlockX()+4,P.minBlockY()+4,P.minBlockZ()+4,1);
        for(var status:List.of(EditStatus.SAVE_FAILED,EditStatus.CLOSING))assertThrows(IllegalStateException.class,()->RollingEditAcknowledgement.ready(r,P,v,new EditAck(request,status,-1,"unknown")));
    }
    @Test void remoteNativePinMetadataIsRejectedButUnpinnedMovementRemainsCanonical(){
        CubePos current=P.offset(1,0,0),next=P.offset(2,0,0);for(boolean nextPin:new boolean[]{true,false}){var plan=RollingCubePlan.create(P,current,next,true,nextPin);var old=new ArrayList<CubeLease>();var candidate=new ArrayList<CubeLease>();var leases=new HashMap<CubePos,Long>();long n=1;for(var p:plan.committedOwners()){leases.put(p,n);old.add(new CubeLease(p,n++));}for(var p:plan.nextCommittedOwners()){Long lease=leases.get(p);candidate.add(new CubeLease(p,lease==null?n++:lease));}
            var metadata=new StageMetadata(world,EPOCH,2,false,P,current,next,old,candidate,plan.addedOwners(),plan.retiredOwners(),plan.candidateTargets(),true,nextPin);if(nextPin)assertThrows(IllegalArgumentException.class,()->RollingGateWire.validateMetadata(metadata));else assertDoesNotThrow(()->RollingGateWire.validateMetadata(metadata));}
    }
    static ClassNode client()throws Exception{try(var in=RollingEarthReplacementIndependentTest.class.getClassLoader().getResourceAsStream("io/earthplatform/minecraft/coregate/RollingGateClient$Session.class")){assertNotNull(in);var node=new ClassNode();new ClassReader(in).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return node;}}
    static List<String> calls(ClassNode type,String method){var out=new ArrayList<String>();for(var i:type.methods.stream().filter(m->m.name.equals(method)).findFirst().orElseThrow().instructions)if(i instanceof MethodInsnNode c)out.add(c.owner+"."+c.name);return out;}
    @Test void compiledClientCanonicalizesBeforeReplicaAndValidatesBeforeFarWrapping()throws Exception{
        var type=client();var ready=calls(type,"ready");int canonical=ready.indexOf("io/earthplatform/minecraft/coregate/RollingEarthCaptureOwner.canonicalize"),replica=ready.indexOf("io/earthplatform/minecraft/coregate/RollingGateReplica.acceptReady"),far=ready.indexOf("io/earthplatform/minecraft/coregate/RollingGateClient$Session.acceptEarthReplacement");assertTrue(canonical>=0);assertTrue(canonical<replica);assertTrue(replica<far);
        var prepare=calls(type,"prepareEarthReplacementInput");assertTrue(prepare.indexOf("io/earthplatform/minecraft/coregate/RollingEarthCaptureValidation.finish")<prepare.indexOf("io/earthplatform/minecraft/lod/DhVoxelReplacementGeometry.captureEarth"));assertTrue(prepare.indexOf("io/earthplatform/minecraft/lod/DhVoxelReplacementGeometry.captureEarth")<prepare.indexOf("io/earthplatform/minecraft/lod/DhReplacementInput.<init>"));
        assertTrue(calls(type,"acceptEarthReplacement").contains("io/earthplatform/minecraft/coregate/RollingReplacementAuthority.reproposeRefusedEarth"));
    }
}
