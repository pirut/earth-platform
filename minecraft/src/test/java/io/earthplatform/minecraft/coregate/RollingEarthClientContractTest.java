package io.earthplatform.minecraft.coregate;

import io.earthplatform.minecraft.enginebridge.BoundedProviderPreparation;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Production bytecode wiring plus real bounded-ticket cancellation tests. These checks do not
 * create a Minecraft client, synthesize GL readiness, or substitute for the playable GUI gate.
 */
class RollingEarthClientContractTest {
    private static final String BASE="io/earthplatform/minecraft/", CLIENT=BASE+"coregate/RollingGateClient";
    private static final String SESSION=CLIENT+"$Session", DESCRIPTOR=BASE+"coregate/RollingSessionDescriptor";
    private static final String PREPARATION=BASE+"coregate/RollingEarthPreparation", PREPARED=PREPARATION+"$Prepared";
    private static final String POOL=BASE+"enginebridge/BoundedProviderPreparation", TICKET=POOL+"$Ticket";
    private static final String PALETTE=BASE+"network/EnginePaletteContext", PROVIDER=BASE+"enginebridge/NativeEarthCubeProvider";
    private static final String TERRAIN=BASE+"scene/SceneTerrain", SAMPLER=BASE+"scene/SceneSurfaceSampler";
    private static final String FAR=BASE+"lod/DhPreparedLodView", GUARD=BASE+"renderprobe/RenderPublicationGuard";
    private static final String REPLICA=BASE+"coregate/RollingGateReplica", COLLISION=BASE+"coregate/CoreCollisionBinding";
    private static final String QUEUE=BASE+"coregate/RollingMeshWorkQueue";

    @Test void descriptorDecodeCannotPrepareTerrainOrReadFilesAndPrecedesReplicaAdmission() throws IOException {
        var descriptor=read(DESCRIPTOR);
        assertTrue(method(descriptor,"read").count(DESCRIPTOR,"analytic")>0);
        assertTrue(method(descriptor,"read").count(DESCRIPTOR,"earth")>0);
        for(var entry:descriptor.entrySet()) for(var call:entry.getValue().calls) {
            assertNotEquals(TERRAIN,call.owner(),entry.getKey());
            assertFalse(call.owner().startsWith("java/nio/file/"),entry.getKey()+": "+call);
            assertFalse(call.owner().startsWith("java/net/"),entry.getKey()+": "+call);
            assertFalse(call.owner().equals(PROVIDER)&&call.name().equals("prepare"),entry.getKey());
            assertFalse(call.owner().equals(BASE+"ProofTerrain")&&call.name().equals("get"),entry.getKey());
            assertNotEquals(POOL,call.owner(),entry.getKey());
        }
        var handle=method(read(CLIENT),"handle");
        assertTrue(handle.at(DESCRIPTOR,"read")<handle.at(SESSION,"metadata"));
        assertEquals(0,handle.count(PREPARATION,"prepare"));
    }

    @Test void asynchronousSourceSupplierCapturesOnlyImmutableSelectorAndPalette() throws IOException {
        var code=read(SESSION); var pump=method(code,"pumpSourcePreparation");
        var sourceFactories=pump.factories.stream().filter(f->Type.getReturnType(f.descriptor()).getInternalName().equals(POOL+"$Source")).toList();
        assertEquals(1,sourceFactories.size()); var source=sourceFactories.getFirst();
        assertArrayEquals(new Type[]{Type.BOOLEAN_TYPE,Type.getObjectType(PALETTE)},Type.getArgumentTypes(source.descriptor()),
                "Only the value selector and immutable palette may escape; never Session/player/Level");
        assertEquals(SESSION,source.implementation().getOwner());
        assertEquals(Opcodes.H_INVOKESTATIC,source.implementation().getTag());
        assertEquals("(ZL"+PALETTE+";L"+POOL+"$Cancellation;)L"+PREPARED+";",source.implementation().getDesc());
        var implementation=method(code,source.implementation().getName());
        assertTrue((implementation.access&Opcodes.ACC_STATIC)!=0,"No hidden Session receiver may escape to worker");
        assertTrue((implementation.access&Opcodes.ACC_SYNTHETIC)!=0);
        assertEquals(1,implementation.count(PREPARATION,"prepare"));
        String alpine=BASE+"coregate/RollingAlpinePreparation";
        assertEquals(1,implementation.count(alpine,"prepare"));
        assertTrue(implementation.fields.isEmpty(),"Worker may not recover mutable owners through any field");
        for(String owner:List.of(PREPARATION,alpine))
            assertTrue(implementation.calls.contains(new Call(owner,"prepare","(L"+PALETTE+";Ljava/lang/Runnable;)L"+PREPARED+";")));
        assertEquals(2,implementation.factories.size(),"Both branches bind cancellation, not an owner callback");
        for(var cancellation:implementation.factories) {
            assertEquals("(L"+POOL+"$Cancellation;)Ljava/lang/Runnable;",cancellation.descriptor());
            assertEquals(POOL+"$Cancellation",cancellation.implementation().getOwner());
            assertEquals("throwIfCancelled",cancellation.implementation().getName());
            assertEquals("()V",cancellation.implementation().getDesc());
        }
        assertTrue(pump.at(DESCRIPTOR,"alpineMode")<pump.events.indexOf(source),"Selector is read on the owning thread");
        assertEquals(1,pump.count(POOL,"submit"));
        for(var entry:code.entrySet()) {
            if(entry.getKey().equals(source.implementation().getName()))continue;
            assertEquals(0,entry.getValue().count(PREPARATION,"prepare"),entry.getKey());
            assertEquals(0,entry.getValue().count(alpine,"prepare"),entry.getKey());
        }
    }

    @Test void farSamplerRetainsOnlyGuardedSourceIdentityHeightAndAtomicCounters() throws IOException {
        var factory=method(read(CLIENT),"countedSampler");
        assertTrue((factory.access&Opcodes.ACC_STATIC)!=0,"Far worker adapter must have no Session receiver");
        var constructors=factory.calls.stream().filter(c->c.name().equals("<init>")&&c.owner().startsWith(CLIENT+"$")).toList();
        assertEquals(1,constructors.size());var constructor=constructors.getFirst();
        var expected=Map.of("L"+SAMPLER+";",1L,"Ljava/lang/String;",1L,"J",1L,"Ljava/util/concurrent/atomic/AtomicLong;",2L);
        var actual=java.util.Arrays.stream(Type.getArgumentTypes(constructor.descriptor()))
                .collect(java.util.stream.Collectors.groupingBy(Type::getDescriptor,java.util.stream.Collectors.counting()));
        assertEquals(expected,actual,"Constructor may capture no additional owner object");
        var fields=instanceFields(constructor.owner());assertEquals(5,fields.size());
        assertEquals(expected,fields.stream().collect(java.util.stream.Collectors.groupingBy(DeclaredField::descriptor,java.util.stream.Collectors.counting())));
        assertTrue(fields.stream().allMatch(f->(f.access()&Opcodes.ACC_FINAL)!=0),"Captured handles are immutable");
        for(var code:read(constructor.owner()).values())for(var field:code.fields)assertNotEquals(SESSION,field.owner());
        var coverage=method(read(SESSION),"prepareFarCoverage");
        assertEquals(1,coverage.count(CLIENT,"countedSampler"));
        assertTrue(coverage.at(CLIENT,"countedSampler")<coverage.at(FAR,"<init>"));
    }

    @Test void measuredSourceAndExplicitPolicyAreBoundBeforeThePreparedResultEscapes() throws IOException {
        var prepare=method(read(PREPARATION),"prepare");
        assertEquals(1,prepare.count(PROVIDER,"prepare"));
        assertTrue(prepare.calls.contains(new Call(PROVIDER,"prepare","(L"+PALETTE+";Z)L"+PROVIDER+";")));
        assertTrue(prepare.at(PROVIDER,"prepare")<prepare.at(SAMPLER,"surfaceY"));
        assertTrue(prepare.at(SAMPLER,"surfaceY")<prepare.at(DESCRIPTOR,"earth"));
        assertTrue(prepare.at(DESCRIPTOR,"earth")<prepare.at(PREPARED,"<init>"));
        assertTrue(prepare.count("java/lang/Runnable","run")>=3,"Cancellation is checked around blocking preparation and result handoff");
        var prepared=method(read(PREPARED),"<init>");
        assertTrue(prepared.count(DESCRIPTOR,"requirePreparedWorld")>0);
        assertTrue(prepared.count(SAMPLER,"identity")>0);
        assertTrue(prepared.count(SAMPLER,"supportsConcurrentReads")>0);
    }

    @Test void claimedWorldAndEntireDescriptorAreCheckedBeforeFarCoverageCanBePrepared() throws IOException {
        var code=read(SESSION);var pump=method(code,"pumpSourcePreparation");
        int claim=pump.at(TICKET,"takeReady");
        int world=pump.atAfter(DESCRIPTOR,"requirePreparedWorld",claim);
        int equal=pump.atAfter(DESCRIPTOR,"samePreparedSource",world);
        assertTrue(equal<pump.writeAfter(SESSION,"preparedSource",equal));
        assertTrue(equal<pump.writeAfter(SESSION,"sourceVerified",equal));
        assertEquals(0,pump.count(FAR,"prepareCoverage")); assertEquals(0,pump.count(COLLISION,"publish"));
        var far=method(code,"prepareFarCoverage");
        assertTrue(far.read(SESSION,"sourceVerified")<far.at(FAR,"<init>"));
        assertTrue(far.read(SESSION,"sourceVerified")<far.at(FAR,"prepareCoverage"));
        assertTrue(far.at(DESCRIPTOR,"fixedFarCenterX")<far.at(FAR,"<init>"));
        assertTrue(far.at(DESCRIPTOR,"fixedFarCenterZ")<far.at(FAR,"<init>"));
        var work=method(code,"prepareCandidateWork");
        assertTrue(work.read(SESSION,"sourceVerified")<work.at(SESSION,"prepareFarCoverage"));
        // Both render backends call the same preparation body; its source fence/order is unchanged.
        assertEquals(1,method(code,"render").count(SESSION,"updatePreparedPair"));
        assertEquals(1,method(code,"shaderFrame").count(SESSION,"updatePreparedPair"));
        var render=method(code,"updatePreparedPair");
        assertTrue(render.at(SESSION,"pumpSourcePreparation")<render.at(SESSION,"prepareCandidateWork"));
        assertTrue(render.at(SESSION,"pumpSourcePreparation")<render.at(FAR,"pump"));
    }

    @Test void preparedSourceNormalizationChangesOnlyValidatedAlpinePresentationPose() throws Exception {
        // Deliberately isolate descriptor comparison from raster/source admission. Production
        // admission separately canonicalizes the wire and verifies the actual prepared world.
        var pin=new io.earthplatform.core.CubePos(0,4,0);
        var originalWorld=new io.earthplatform.core.WorldIdentity(1,"review-source","v1","data-a","blocks");
        Object[] fields={originalWorld,RollingSessionDescriptor.EARTH,RollingSessionDescriptor.ALPINE_PROFILE_ID,
                "a".repeat(64),pin,pin,new net.minecraft.world.phys.Vec3(.5,65.01,.5),64,0L,0L,true,
                io.earthplatform.minecraft.lod.DhLodLayout.EARTH_ALPINE_2048,
                io.earthplatform.minecraft.enginebridge.RollingEditPolicy.INTERIOR_V1,
                new RollingSessionDescriptor.BodyEnvelope(-256,256,0,120,-256,256),
                new RollingSessionDescriptor.WindowEnvelope(-16,15,2,6,-16,15),false,false};
        var original=comparisonDescriptor(fields);
        var resumed=original.resumeAt(new net.minecraft.world.phys.Vec3(32.5,65.01,32.5));
        assertNotEquals(original,resumed);assertTrue(resumed.samePreparedSource(original));
        assertTrue(original.samePreparedSource(resumed));assertEquals(original.saveDirectory(),resumed.saveDirectory());
        assertFalse(resumed.samePreparedSource(null));
        Object[][] changes={
                {0,new io.earthplatform.core.WorldIdentity(1,"review-source","v1","data-b","blocks")},
                {1,RollingSessionDescriptor.ANALYTIC},{2,RollingSessionDescriptor.EARTH_PROFILE_ID},{3,"b".repeat(64)},
                {4,pin.offset(1,0,0)},{7,65},{8,64L},{9,64L},{10,false},
                {11,io.earthplatform.minecraft.lod.DhLodLayout.FIXED_512},
                {13,new RollingSessionDescriptor.BodyEnvelope(-256,255,0,120,-256,256)},
                {14,new RollingSessionDescriptor.WindowEnvelope(-16,14,2,6,-16,15)}};
        for(var change:changes){var altered=fields.clone();altered[(Integer)change[0]]=change[1];
            assertFalse(resumed.samePreparedSource(comparisonDescriptor(altered)),"Non-pose field changed: "+change[0]);}
        var code=method(read(DESCRIPTOR),"samePreparedSource");
        assertEquals(2,code.count(DESCRIPTOR,"equals"));assertEquals(1,code.count(DESCRIPTOR,"resumeAt"));
        assertEquals(2,code.count(DESCRIPTOR,"alpineMode"));
        assertEquals(List.of(new Field(Opcodes.GETFIELD,DESCRIPTOR,"entry")),code.fields);
    }

    private static RollingSessionDescriptor comparisonDescriptor(Object[] fields) throws Exception {
        var constructor=java.util.Arrays.stream(RollingSessionDescriptor.class.getDeclaredConstructors())
                .filter(c->c.getParameterCount()==17).findFirst().orElseThrow();
        constructor.setAccessible(true);return (RollingSessionDescriptor)constructor.newInstance(fields);
    }

    @Test void reloadAndCloseCancelTicketsAndEveryPumpRejectsRevokedOwnership() throws IOException {
        var code=read(SESSION); var pump=method(code,"pumpSourcePreparation");
        assertTrue(pump.read(SESSION,"closed")<pump.at(TICKET,"takeReady"));
        assertTrue(pump.at(GUARD,"invalidationRequested")<pump.at(TICKET,"takeReady"));
        assertEquals(1,method(code,"invalidate").count(SESSION,"invalidateOwned"));
        var invalidation=method(code,"invalidateOwned");
        // Installed callbacks can retain cleanup failures, so revoke gameplay/draw authority first.
        assertTrue(invalidation.at(GUARD,"requestInvalidation")<invalidation.at(SESSION,"cancelSourcePreparation"));
        assertTrue(invalidation.at(SESSION,"fenceCollision")<invalidation.at(SESSION,"cancelSourcePreparation"));
        assertEquals(1,invalidation.count(SESSION,"cancelSourcePreparation"));
        var close=method(code,"close");
        assertTrue(close.writeAfter(SESSION,"closed",-1)<close.at(SESSION,"cancelSourcePreparation"));
        assertTrue(close.at(SESSION,"cancelSourcePreparation")<close.at(QUEUE,"close"));
        assertTrue(close.at(SESSION,"cancelSourcePreparation")<close.at(REPLICA,"close"));
        assertEquals(1,method(code,"cancelSourcePreparation").count(TICKET,"close"));
        for(var entry:code.entrySet()) for(var call:entry.getValue().calls) {
            assertFalse(call.owner().equals("java/util/concurrent/CompletableFuture")&&(call.name().equals("join")||call.name().equals("get")),entry.getKey());
            assertFalse(call.owner().equals("java/util/concurrent/Future")&&call.name().equals("get"),entry.getKey());
            assertNotEquals(TERRAIN,call.owner(),entry.getKey());
        }
    }

    @Test void boundedWindowAndCollisionIdentityChecksPrecedeMutableAdmission() throws IOException {
        var metadata=method(read(REPLICA),"acceptMetadata");
        assertEquals(2,metadata.count(DESCRIPTOR,"requireAllowedWindow"));
        assertTrue(metadata.at(DESCRIPTOR,"requireAllowedWindow")<metadata.at("io/earthplatform/streaming/CubeStreamAdmission","replaceInterest"));
        var publish=method(read(COLLISION),"publish");
        assertTrue(publish.at(DESCRIPTOR,"requirePreparedWorld")<publish.at(BASE+"enginebridge/NativeCollisionView","<init>"));
        assertTrue(publish.at(BASE+"coregate/CorePlayerSafetyLease","epoch")<publish.at(BASE+"enginebridge/NativeCollisionView","<init>"));
    }

    @Test void cancellationIgnoringLateResultCannotBeClaimedOrFreeCapacityBeforePhysicalExit() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var published=new AtomicInteger();
        try(var pool=new BoundedProviderPreparation.Pool(1,"earth-client-reload-test")) {
            var stamp=new BoundedProviderPreparation.Stamp(42,2);
            var ticket=pool.submit(stamp,cancel->{started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return "late verified-looking source";});
            try {
                assertTrue(started.await(5,TimeUnit.SECONDS));ticket.close();
                assertFalse(ticket.physicalSettled());assertTrue(ticket.completionAcknowledged());
                assertEquals(1,pool.diagnostics().outstanding());assertEquals(1,pool.diagnostics().running());
                assertThrows(RejectedExecutionException.class,()->pool.submit(new BoundedProviderPreparation.Stamp(43,2),cancel->"new source"));
                ticket.takeReady(stamp).ifPresent(value->published.incrementAndGet());
                ticket.takeReady(new BoundedProviderPreparation.Stamp(43,2)).ifPresent(value->published.incrementAndGet());
            } finally { release.countDown(); }
            awaitSettlement(ticket);
            ticket.takeReady(stamp).ifPresent(value->published.incrementAndGet());
            assertEquals(0,published.get());assertEquals(BoundedProviderPreparation.State.CANCELLED,ticket.state());
            assertEquals(0,pool.diagnostics().outstanding());assertEquals(0,pool.diagnostics().running());
        }
    }

    @Test void readyButUnclaimedOldEpochRemainsChargedUntilCloseAndNeverTransfersToReplacement() throws Exception {
        try(var pool=new BoundedProviderPreparation.Pool(1,"earth-client-epoch-test")) {
            var old=new BoundedProviderPreparation.Stamp(7,2);var ticket=pool.submit(old,cancel->"source");
            awaitSettlement(ticket);
            assertEquals(BoundedProviderPreparation.State.READY,ticket.state());assertEquals(1,pool.diagnostics().completedUnclaimed());
            assertTrue(ticket.takeReady(new BoundedProviderPreparation.Stamp(8,2)).isEmpty());
            assertTrue(ticket.takeReady(new BoundedProviderPreparation.Stamp(7,3)).isEmpty());
            assertEquals(1,pool.diagnostics().outstanding());ticket.close();
            assertTrue(ticket.takeReady(old).isEmpty());assertEquals(0,pool.diagnostics().outstanding());
        }
    }

    private static void awaitSettlement(BoundedProviderPreparation.Ticket<?> ticket) {
        assertTimeoutPreemptively(Duration.ofSeconds(5),()->{while(!ticket.physicalSettled())Thread.sleep(1);});
    }
    private static Code method(Map<String,Code> methods,String name){var result=methods.get(name);assertNotNull(result,name);return result;}
    private static Map<String,Code> read(String type) throws IOException {
        var methods=new LinkedHashMap<String,Code>();
        try(var input=RollingEarthClientContractTest.class.getClassLoader().getResourceAsStream(type+".class")) {
            assertNotNull(input,type);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                    var code=methods.computeIfAbsent(name,ignored->new Code());code.access|=access;
                    return new MethodVisitor(Opcodes.ASM9){
                        @Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean iface){var call=new Call(owner,name,descriptor);code.calls.add(call);code.events.add(call);}
                        @Override public void visitFieldInsn(int opcode,String owner,String name,String descriptor){var field=new Field(opcode,owner,name);code.fields.add(field);code.events.add(field);}
                        @Override public void visitInvokeDynamicInsn(String name,String descriptor,Handle bootstrap,Object... args){
                            if(!bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory"))return;
                            for(var arg:args)if(arg instanceof Handle implementation){var factory=new Factory(descriptor,implementation);code.factories.add(factory);code.events.add(factory);}
                        }
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        return methods;
    }
    private static List<DeclaredField> instanceFields(String type) throws IOException {
        var result=new ArrayList<DeclaredField>();
        try(var input=RollingEarthClientContractTest.class.getClassLoader().getResourceAsStream(type+".class")) {
            assertNotNull(input,type);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public FieldVisitor visitField(int access,String name,String descriptor,String signature,Object value){
                    if((access&Opcodes.ACC_STATIC)==0)result.add(new DeclaredField(name,descriptor,access));return null;
                }
            },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        return result;
    }
    private record DeclaredField(String name,String descriptor,int access){}
    private record Call(String owner,String name,String descriptor){}
    private record Field(int opcode,String owner,String name){}
    private record Factory(String descriptor,Handle implementation){}
    private static final class Code {
        int access;final List<Object> events=new ArrayList<>();final List<Call> calls=new ArrayList<>();
        final List<Field> fields=new ArrayList<>();final List<Factory> factories=new ArrayList<>();
        long count(String owner,String name){return calls.stream().filter(c->c.owner().equals(owner)&&c.name().equals(name)).count();}
        int at(String owner,String name){return atAfter(owner,name,-1);}
        int atAfter(String owner,String name,int after){for(int i=after+1;i<events.size();i++)if(events.get(i) instanceof Call c&&c.owner().equals(owner)&&c.name().equals(name))return i;fail("Missing call "+owner+"."+name);return -1;}
        int read(String owner,String name){for(int i=0;i<events.size();i++)if(events.get(i) instanceof Field f&&(f.opcode()==Opcodes.GETFIELD||f.opcode()==Opcodes.GETSTATIC)&&f.owner().equals(owner)&&f.name().equals(name))return i;fail("Missing read "+owner+"."+name);return -1;}
        int writeAfter(String owner,String name,int after){for(int i=after+1;i<events.size();i++)if(events.get(i) instanceof Field f&&f.opcode()==Opcodes.PUTFIELD&&f.owner().equals(owner)&&f.name().equals(name))return i;fail("Missing write "+owner+"."+name);return -1;}
    }
}
