package io.earthplatform.minecraft.coregate;

import java.io.InputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent protocol-state exercise and class-file wiring checks; no claim of GUI execution. */
class HostDistanceLifecycleIndependentTest {
    private static NativeDistanceClientTransition.Selection selection(int value) {
        return new NativeDistanceClientTransition.Selection(value,value,0);
    }

    @Test void randomizedHeadAndStableObservationNeverReviveAnOlderDrain() {
        Random random=new Random(0x031c1e17L);
        for(int initial=2;initial<=32;initial++) {
            var client=new NativeDistanceClientTransition(initial);
            int actual=initial;long expectedSerial=0;
            var obsolete=new ArrayList<NativeDistanceChangePayload>();
            for(int step=0;step<256;step++) {
                var head=client.mutation(selection(actual));
                assertEquals(++expectedSerial,head.serial());
                obsolete.add(head);
                assertFalse(client.permitsPreflight());
                // An entire A -> B -> A may happen without a tick observing B.
                if(random.nextBoolean()) {
                    actual=2+random.nextInt(31);
                    var secondHead=client.mutation(selection(actual));
                    assertEquals(++expectedSerial,secondHead.serial());obsolete.add(secondHead);
                }
                actual=2+random.nextInt(31);
                var update=client.observe(selection(actual));
                if(update!=null)assertEquals(++expectedSerial,update.serial());
                var latest=client.latest();
                assertEquals(actual,latest.selected());assertFalse(client.permitsPreflight());
                for(int n=0;n<4;n++) {
                    var old=obsolete.get(random.nextInt(obsolete.size()));
                    if(old.serial()!=latest.serial())assertFalse(client.drain(old.drain()));
                }
                assertNull(client.physicallyDrained());
                assertTrue(client.drain(latest.drain()));assertTrue(client.waitingForDrain());
                var receipt=client.physicallyDrained();
                assertTrue(latest.sameSelection(receipt));assertTrue(client.permitsPreflight());
                assertNull(client.physicallyDrained());assertNull(client.observe(selection(actual)));
            }
        }
    }

    @Test void joinedServerBarrierCannotUseClientAcknowledgementFromPriorTuple() {
        var client=new NativeDistanceClientTransition(31);
        var first=client.mutation(selection(12));var server=new NativeDistanceRestartGate(first);
        assertTrue(client.drain(first.drain()));var oldReceipt=client.physicallyDrained();
        assertTrue(server.clientDrained(oldReceipt));assertFalse(server.ready());
        server.serverDrained(true);assertTrue(server.ready());
        for(int selected:new int[]{24,12,32,2,24,12}) {
            var next=client.mutation(selection(selected));
            assertTrue(server.request(next));assertFalse(server.ready());assertFalse(client.permitsPreflight());
            assertFalse(server.clientDrained(oldReceipt));assertFalse(client.drain(first.drain()));
            assertTrue(client.drain(server.latest().drain()));var receipt=client.physicallyDrained();
            assertTrue(server.clientDrained(receipt));assertTrue(server.ready());
            oldReceipt=receipt;
        }
        var pending=client.mutation(new NativeDistanceClientTransition.Selection(24,12,12));
        assertTrue(server.request(pending));assertTrue(client.drain(pending.drain()));
        assertTrue(server.clientDrained(client.physicallyDrained()));assertFalse(server.ready());
        var caughtUp=client.observe(selection(24));assertNotNull(caughtUp);
        assertTrue(server.request(caughtUp));assertFalse(server.ready());
    }

    @Test void oldWireEpochCannotEnterCommittedOrUnrelatedActor() {
        for(long wire=1;wire<=32;wire++) {
            var newer=NativeDistanceChangePayload.request(wire,19,24,24,0);
            assertTrue(NativeDistanceRestartGate.openingEpoch(wire+1,wire,18,true,newer));
            assertFalse(NativeDistanceRestartGate.openingEpoch(wire+1,wire,19,true,newer));
            assertFalse(NativeDistanceRestartGate.openingEpoch(wire+1,wire,18,false,newer));
            assertFalse(NativeDistanceRestartGate.openingEpoch(wire+1,wire+2,18,true,newer));
            assertFalse(NativeDistanceRestartGate.openingEpoch(wire+1,wire,18,true,newer.drain()));
            assertFalse(NativeDistanceRestartGate.openingEpoch(wire+1,wire,18,true,newer.failed()));
        }
    }

    @Test void completedOracleIdentityIgnoresEpochButBindsEveryNamedDigest() {
        String a="a".repeat(64),b="b".repeat(64),c="c".repeat(64),d="d".repeat(64),e="e".repeat(64);
        var original=new io.earthplatform.minecraft.scene.NativeVisualCapsule.Identity(1,a,b,c,d);
        String identity=RollingGateClient.nativeOracleIdentity(original);
        assertEquals(identity,RollingGateClient.nativeOracleIdentity(new io.earthplatform.minecraft.scene.NativeVisualCapsule.Identity(99,a,b,c,d)));
        for(var changed:List.of(
                new io.earthplatform.minecraft.scene.NativeVisualCapsule.Identity(1,e,b,c,d),
                new io.earthplatform.minecraft.scene.NativeVisualCapsule.Identity(1,a,e,c,d),
                new io.earthplatform.minecraft.scene.NativeVisualCapsule.Identity(1,a,b,e,d),
                new io.earthplatform.minecraft.scene.NativeVisualCapsule.Identity(1,a,b,c,e)))
            assertNotEquals(identity,RollingGateClient.nativeOracleIdentity(changed));
    }

    @Test void resourceRefusalNamesSelectedSettingAndBoundedReasonWithoutClaimingSmallerCoverage() {
        for(int selected=2;selected<=32;selected++) {
            String text=RollingGateClient.nativeDistanceRefusal(selected,new IllegalStateException("CPU pool SOURCE exhausted\nnext\rline"));
            assertTrue(text.contains(selected+" chunks"));assertTrue(text.contains("CPU pool SOURCE exhausted"));
            assertTrue(text.contains("Coverage was not reduced"));assertFalse(text.contains("\n"));assertFalse(text.contains("\r"));
        }
        String longReason=RollingGateClient.nativeDistanceRefusal(24,new IllegalStateException("x".repeat(4096)));
        assertTrue(longReason.length()<550);
        assertTrue(RollingGateClient.nativeDistanceRefusal(12,new IllegalArgumentException()).contains("IllegalArgumentException"));
    }

    @Test void nonDistanceResourceReloadAndLogoutDiscardTinyCachedQualification() throws Exception {
        var client=read("io/earthplatform/minecraft/coregate/RollingGateClient");
        for(String name:List.of("closeReloadOwner","logout")) {
            var method=method(client,name);
            assertTrue(field(method,"RollingGateClient","nativeQualification",Opcodes.PUTSTATIC)>=0);
            assertTrue(field(method,"RollingGateClient","completedNativeOracleIdentity",Opcodes.PUTSTATIC)>=0);
        }
    }

    @Test void staleMetadataAndFailureAreCheckedBeforeAuthorityAdvances() throws Exception {
        var client=read("io/earthplatform/minecraft/coregate/RollingGateClient");
        var handle=method(client,"handle");
        int preflight=call(handle,"NativeDistanceClientTransition","permitsPreflight");
        assertTrue(preflight>=0&&preflight<call(handle,"RollingSessionDescriptor","read"));
        assertTrue(preflight<field(handle,"RollingGateClient","lastEpoch",Opcodes.PUTSTATIC));
        var change=method(client,"nativeDistanceChange");int exact=call(change,"NativeDistanceChangePayload","sameSelection");
        int fail=call(change,"NativeDistanceClientTransition","fail");assertTrue(exact>=0&&exact<fail);
        assertTrue(hasJumpBetween(change,exact,fail));
    }

    @Test void clientPhysicalAcknowledgementFollowsCloseAndRestorationChecks() throws Exception {
        var pump=method(read("io/earthplatform/minecraft/coregate/RollingGateClient"),"pumpDistanceTransition");
        int receipt=call(pump,"NativeDistanceClientTransition","physicallyDrained");
        assertTrue(lastCallBefore(pump,"RollingGateClient","closeCurrent",receipt)>=0);
        int restore=lastCallBefore(pump,"NativeIrisShaderStages","restorationPending",receipt);
        assertTrue(restore>=0&&restore<receipt);
        assertTrue(hasJumpBetween(pump,restore,receipt),"A pending restoration check must guard the physical receipt");
        var close=method(read("io/earthplatform/minecraft/coregate/RollingGateClient"),"closeCurrent");
        int physical=call(close,"RollingGateClient$Session","closeInstalledBridge");
        int release=field(close,"RollingGateClient","current",Opcodes.PUTSTATIC);
        assertTrue(physical>=0&&release>physical,"A thrown physical close must retain static session ownership");
        assertFalse(close.tryCatchBlocks.isEmpty());
    }

    @Test void suspendClosesNativeAndFarCallbackAdmissionBeforeLifecycleWork() throws Exception {
        var carrier=read("io/earthplatform/compat/dh/shader/InstalledShaderCarrier");
        var suspend=method(carrier,"suspend");
        assertTrue(field(suspend,"InstalledShaderCarrier","suspended",Opcodes.PUTFIELD)<call(suspend,"InstalledShaderCarrier","invalidate"));
        assertTrue(field(method(carrier,"owns"),"InstalledShaderCarrier","suspended",Opcodes.GETFIELD)>=0);
        for(String entry:List.of("nativeLayer","terrain")) {
            var method=method(carrier,entry);int own=call(method,"InstalledShaderCarrier","owns"),prepare=call(method,"InstalledShaderCarrier","prepare");
            assertTrue(own>=0&&own<prepare);assertTrue(hasJumpBetween(method,own,prepare));
        }
    }

    @Test void nativeCpuReleaseWaitsForExecutorTerminationAndPhysicalOwners() throws Exception {
        var stream=read("io/earthplatform/minecraft/scene/NativeVisualStream");
        var close=method(stream,"close");int release=call(close,"NativeDistanceCpuBudget$Reservation","releaseAfterPhysicalDrain");
        int terminated=call(close,"ExecutorService","isTerminated");
        assertTrue(terminated>=0&&terminated<release);assertTrue(hasJumpBetween(close,terminated,release));
        int clearSource=call(close,"AtomicReference","set");assertTrue(clearSource>=0&&clearSource<release);
        var complete=method(stream,"closeComplete");assertTrue(call(complete,"ExecutorService","isTerminated")>=0);
        assertTrue(field(complete,"NativeVisualStream","job",Opcodes.GETFIELD)>=0);
    }

    @Test void distantOriginalDependenciesRemainRevalidatableUntilFirstPairInstall() throws Exception {
        var session=read("io/earthplatform/minecraft/coregate/RollingGateClient$Session");
        var input=method(session,"prepareEarthReplacementInput");
        assertEquals(-1,call(input,"RollingEarthResumeBootstrap","close"),
                "An immutable input alone must not retire original27 before later candidate validation");
        var validate=method(session,"validateCandidate");
        assertTrue(call(validate,"RollingEarthResumeBootstrap","require")>=0,
                "Distant resume revalidation must use its authenticated original27 receipt");
        var install=method(session,"installPair");int release=call(install,"RollingEarthResumeBootstrap","close");
        assertTrue(release>field(install,"RollingGateClient$Session","publishedCertificate",Opcodes.PUTFIELD));
        assertTrue(release>field(install,"RollingGateClient$Session","replacementInput",Opcodes.PUTFIELD));
        assertTrue(call(method(session,"close"),"RollingEarthResumeBootstrap","close")>=0);
    }

    @Test void unstartedActorHasExplicitOriginalSnapshotReservationCleanup() throws Exception {
        var actor=read("io/earthplatform/minecraft/enginebridge/RollingCubeActor");
        var resume=method(actor,"resumeEarth");assertTrue(call(resume,"RollingCubeActor","closeUnstarted")>=0);
        var cleanup=method(actor,"closeUnstarted");
        assertTrue(call(cleanup,"Thread","getState")>=0);
        assertTrue(call(cleanup,"ThreadPoolExecutor","shutdown")>=0);
        assertTrue(call(cleanup,"NativeDistanceCpuBudget$Reservation","releaseAfterPhysicalDrain")>=0);
        var finish=method(actor,"finish");int release=call(finish,"NativeDistanceCpuBudget$Reservation","releaseAfterPhysicalDrain");
        assertTrue(release>=0&&release<call(finish,"ClosingFuture","publish"));
    }

    private static ClassNode read(String name)throws Exception {
        try(InputStream input=HostDistanceLifecycleIndependentTest.class.getClassLoader().getResourceAsStream(name+".class")) {
            assertNotNull(input,name);var node=new ClassNode();new ClassReader(input).accept(node,0);return node;
        }
    }
    private static MethodNode method(ClassNode node,String name) {
        return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow(()->new AssertionError(node.name+"."+name));
    }
    private static int call(MethodNode method,String ownerSuffix,String name) {
        int i=0;for(var ins:method.instructions) {if(ins instanceof MethodInsnNode m&&m.owner.endsWith(ownerSuffix)&&m.name.equals(name))return i;i++;}return -1;
    }
    private static int lastCallBefore(MethodNode method,String ownerSuffix,String name,int before) {
        int i=0,result=-1;for(var ins:method.instructions) {if(i>=before)break;if(ins instanceof MethodInsnNode m&&m.owner.endsWith(ownerSuffix)&&m.name.equals(name))result=i;i++;}return result;
    }
    private static int field(MethodNode method,String ownerSuffix,String name,int opcode) {
        int i=0;for(var ins:method.instructions) {if(ins instanceof FieldInsnNode f&&f.owner.endsWith(ownerSuffix)&&f.name.equals(name)&&f.getOpcode()==opcode)return i;i++;}return -1;
    }
    private static boolean hasJumpBetween(MethodNode method,int start,int end) {
        int i=0;for(var ins:method.instructions) {if(i>start&&i<end&&ins instanceof JumpInsnNode)return true;i++;}return false;
    }
}
