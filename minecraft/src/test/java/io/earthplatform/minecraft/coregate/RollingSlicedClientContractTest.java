package io.earthplatform.minecraft.coregate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent production-wiring regressions. Queue fault tests prove phase behavior; these
 * checks ensure the real client routes work through that queue and preserves ACK retirement.
 * They inspect bytecode without constructing a Minecraft client or pretending to test GL.
 */
class RollingSlicedClientContractTest {
    private static final String BASE = "io/earthplatform/minecraft/";
    private static final String SESSION = BASE + "coregate/RollingGateClient$Session";
    private static final String QUEUE = BASE + "coregate/RollingMeshWorkQueue";
    private static final String REPLICA = BASE + "coregate/RollingGateReplica";
    private static final String COLLISION = BASE + "coregate/CoreCollisionBinding";
    private static final String MESH = BASE + "scene/SceneMesh";
    private static final String FAR = BASE + "lod/DhPreparedLodView";

    @Test void actualRenderPathPumpsOneBoundedNativeOwnerSliceAndDoesNotBuildSynchronously() throws IOException {
        var code = read(SESSION);
        assertEquals(1, method(code, "updatePreparedPair").count(SESSION, "prepareCandidateWork"));
        for(String caller:List.of("render","shaderFrame")){
            var render=method(code,caller);
            assertEquals(1,render.count(SESSION,"updatePreparedPair"),caller);
            assertEquals(0,render.count(SESSION,"prepareCandidateWork"),caller);
            assertTrue(render.index(SESSION,"valid")<render.index(SESSION,"updatePreparedPair"),caller);
            assertTrue(render.index(BASE+"renderprobe/RenderPublicationGuard","invalidationRequested")<render.index(SESSION,"updatePreparedPair"),caller);
            assertEquals(1,render.monitors,caller+" retains the session owner fence");
        }
        assertEquals(Set.of("render","shaderFrame"),code.entrySet().stream().filter(e->e.getValue().count(SESSION,"updatePreparedPair")>0)
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("updatePreparedPair"),code.entrySet().stream().filter(e->e.getValue().count(SESSION,"prepareCandidateWork")>0)
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        assertRepeatedShaderPassCannotPumpAgain();
        var work = method(code, "prepareCandidateWork");
        assertEquals(0, work.count(QUEUE, "pumpSlice"),"Distance uses data proof slices and cannot pump undrawn SceneMeshes");
        assertEquals(1,work.count(SESSION,"prepareDistanceBody"));
        int bodyDispatch=work.eventIndex(new Call(SESSION,"prepareDistanceBody")),legacyPump=work.eventIndex(new Call(QUEUE,"pump"));
        assertTrue(bodyDispatch<legacyPump);
        assertTrue(work.events.subList(bodyDispatch,legacyPump).contains("return"),"Distance returns before all legacy model work");
        var body=method(code,"prepareDistanceBody");String binding=BASE+"coregate/RollingDistanceBodyBinding$Binding";
        assertEquals(1,body.count(binding,"step"));
        assertTrue(body.index(binding,"step")<body.index(SESSION,"prepareVisual"));
        assertTrue(body.index(SESSION,"prepareVisual")<body.index(binding,"join"));
        assertTrue(body.index(binding,"join")<body.index(SESSION,"validateCandidate"));
        assertTrue(body.index(SESSION,"validateCandidate")<body.index(SESSION,"prepareFarCoverage"));
        assertTrue(body.calls.stream().noneMatch(c->c.owner().equals(QUEUE)||c.owner().startsWith(MESH)));
        assertEquals(1, work.count(QUEUE, "pump"), "Other profiles preserve legacy pacing");
        assertEquals(1, work.count(QUEUE, "publishCompleted"));
        assertEquals(1, work.count(SESSION, "distanceMode"));
        assertTrue(work.reads.contains("nativeWorkPumpedFrame"));
        assertTrue(work.writes.contains("nativeWorkPumpedFrame"));
        for (var entry : code.entrySet()) {
            assertEquals(0, entry.getValue().count(MESH, "build"), entry.getKey());
            assertEquals(0, entry.getValue().count(MESH, "begin"), entry.getKey());
            assertEquals(0, entry.getValue().count(MESH + "$Build", "step"), entry.getKey());
            assertEquals(0, entry.getValue().count(MESH + "$Build", "finish"), entry.getKey());
        }
    }

    @Test void nativeDrainPrecedesPrivateFarPreparationAndThePreparedAcknowledgement() throws IOException {
        var code = read(SESSION);
        var work = method(code, "prepareCandidateWork");
        assertEquals(1, work.count(QUEUE, "isDrained"));
        assertTrue(work.index(QUEUE, "isDrained") < work.index(SESSION, "prepareFarCoverage"));
        assertTrue(work.index(SESSION, "validateCandidate") < work.index(SESSION, "prepareFarCoverage"));
        assertEquals(0, work.count(SESSION, "send"), "Native drain alone is not PREPARED");
        var validation = method(code, "validateCandidate");
        assertEquals(2, validation.count(QUEUE, "isDrained"),"Independent distance no-owner check and original legacy drain");
        String body=BASE+"coregate/RollingDistanceBodyBinding";
        assertEquals(1,validation.count(body+"$Binding","dependenciesCurrent"));
        assertEquals(1,validation.count(body+"$NativeJoined","requirePrepared"));
        assertTrue(validation.index(body+"$NativeJoined","requirePrepared")<validation.index(QUEUE,"isDrained"));
        assertTrue(validation.literals.contains("Distance body acquired forbidden SceneMesh owners"));
        assertEquals(1, validation.count(REPLICA, "candidateTargets"));
        assertEquals(1, validation.count(MESH, "isCurrent"));
        assertTrue(validation.literals.contains("Candidate queue drained before all targets were published"));
        assertTrue(validation.literals.contains("Edited central volume lost pinned native coverage"));
        var preparation = method(code, "prepareFarCoverage");
        assertTrue(preparation.index(SESSION, "validateCandidate") < preparation.index(FAR, "prepareCoverage"));
        assertTrue(preparation.index(SESSION,"validateCandidate")<preparation.index(FAR,"prepareCoverageDistance"));
        assertEquals(0,method(code,"prepareDistanceBody").count(SESSION,"send"),"Completed body equality and visual completion alone are not PREPARED");
        assertEquals(0, preparation.count(FAR, "commitPrepared"));
        var render = method(code, "updatePreparedPair");
        assertEquals(1, render.count(FAR, "pump"));
        assertTrue(render.index(SESSION, "prepareCandidateWork") < render.index(FAR, "pump"));
        assertTrue(render.index(FAR, "pump") < render.index(FAR, "isCurrent"));
        assertTrue(render.index(FAR, "isCurrent") < render.index(FAR, "isPrepared"));
        assertTrue(render.index(FAR, "isPrepared") < render.index(SESSION, "validateCandidate"));
        assertTrue(render.index(SESSION, "validateCandidate") < render.index(SESSION, "send"));
        for(String caller:List.of("render","shaderFrame")){
            assertEquals(1,method(code,caller).count(SESSION,"updatePreparedPair"));
            assertEquals(0,method(code,caller).count(FAR,"pump"));
            assertEquals(0,method(code,caller).count(FAR,"commitPrepared"));
        }
    }

    @Test void allowanceRechecksTicketAndCommitHidesThePairBeforeAnyFallibleSwap() throws IOException {
        var code = read(SESSION);
        var allowance = method(code, "allow");
        assertTrue(allowance.index(SESSION, "checkPair") < allowance.index(FAR, "isPrepared"));
        var install = method(code, "installPair");
        assertTrue(install.reads.contains("allowed"));
        assertTrue(install.reads.contains("preparedSent"));
        assertTrue(install.reads.contains("pairSent"));
        assertTrue(install.index(SESSION, "validateCandidate") < install.index(FAR, "isPrepared"));
        assertTrue(install.index(FAR, "isPrepared") < install.index(FAR, "commitPrepared"));
        assertTrue(install.eventIndex(new FieldWrite("visible", Opcodes.ICONST_0))
                < install.eventIndex(new Call(FAR, "commitPrepared")));
        assertTrue(install.index(FAR, "commitPrepared") < install.index(FAR, "coverageReady"));
        assertTrue(install.index(FAR, "coverageReady") < install.index(SESSION, "closeExcept"));
        assertTrue(install.index(SESSION, "closeExcept") < install.index(SESSION, "send"));
        for (var entry : code.entrySet()) {
            if (!entry.getKey().equals("installPair"))
                assertEquals(0, entry.getValue().count(FAR, "commitPrepared"), entry.getKey());
            assertEquals(0, entry.getValue().count(FAR, "replaceCoverage"), entry.getKey());
        }
    }

    @Test void retainedClosedSessionCannotAcceptLateAllowanceOrCommittedAcknowledgement() throws IOException {
        var code = read(SESSION);
        var check = method(code, "checkPair");
        assertTrue(check.reads.contains("closed"), "Cleanup failure can retain current after close");
        assertTrue(check.reads.contains("disposed"), "A disposed session cannot reopen collision");
        assertTrue(check.count(SESSION, "valid") > 0, "Connection and carrier identity must still match");
        assertTrue(check.throwCount > 0, "Closed sessions must reject the acknowledgement");
        assertEquals(1, method(code, "allow").count(SESSION, "checkPair"));
        assertEquals(1, method(code, "committed").count(SESSION, "checkPair"));
    }

    @Test void obsoleteEpochAndRetainedCleanupGuardsPrecedePayloadAdmission() throws IOException {
        var handle = method(read(BASE + "coregate/RollingGateClient"), "handle");
        var epoch = new FieldRead(BASE + "coregate/RollingGateClient", "lastEpoch");
        // Intentional protocol revision: the bounded provider/session descriptor replaces the analytic-only profile body.
        assertTrue(handle.eventIndex(epoch) < handle.eventIndex(new Call(BASE + "coregate/RollingSessionDescriptor", "read")));
        assertTrue(handle.eventIndex(epoch) < handle.eventIndex(new Call(BASE + "coregate/RollingGateWire", "readMetadata")));
        var snapshot = new Call(BASE + "network/EngineCubeSnapshot", "readFrame");
        assertTrue(handle.eventIndex(new FieldRead(SESSION, "closed")) < handle.eventIndex(snapshot));
        assertTrue(handle.eventIndex(new FieldRead(SESSION, "disposed")) < handle.eventIndex(snapshot));
        assertTrue(handle.eventIndex(new Call(SESSION, "valid")) < handle.eventIndex(snapshot));
        assertTrue(handle.eventIndex(new Call(BASE + "renderprobe/RenderPublicationGuard", "invalidationRequested"))
                < handle.eventIndex(snapshot));
        assertTrue(handle.count("net/minecraft/network/FriendlyByteBuf", "release") > 0,
                "Every ignored or failed envelope must release its opened byte buffer");
    }

    @Test void operationalErrorsReachTheSameFailClosedBoundaryAsRuntimeExceptions() throws IOException {
        var code = read(BASE + "coregate/RollingGateClient");
        for (var name : List.of("handle", "frame", "closeCurrent")) {
            var method = method(code, name);
            assertTrue(method.catchTypes.contains("java/lang/RuntimeException"), name);
            assertTrue(method.catchTypes.contains("java/lang/Error"), name);
        }
        assertTrue(method(code,"render").calls.stream().noneMatch(c->c.owner().startsWith("io/earthplatform/")),
                "The old AFTER_LEVEL callback cannot retain a second submission or operational boundary");
        // The installed API callback now owns the draw exception boundary. Reading its bytes does not load DH.
        String events="io/earthplatform/compat/dh/InstalledDhEvents",gate="io/earthplatform/compat/dh/DhCarrierRenderGate";
        var installed=read(events);
        for(var name:List.of("opaque","deferred")){
            var callback=method(installed,name);
            assertTrue(callback.catchTypes.contains("java/lang/RuntimeException"),name);
            assertTrue(callback.catchTypes.contains("java/lang/Error"),name);
            assertEquals(1,callback.count(gate,name));
            assertEquals(1,callback.count(events,"contain"));
        }
        var failure=method(installed,"contain");
        assertTrue(failure.index(gate,"invalidate")<failure.index("java/util/function/Consumer","accept"),
                "Authority is fenced before invoking fallible failure reporting");
        assertTrue(failure.catchTypes.contains("java/lang/RuntimeException"));
        assertTrue(failure.catchTypes.contains("java/lang/Error"));
    }

    @Test void onlyExactServerCommitRetiresReplicaSnapshotsOrPublishesCollision() throws IOException {
        var code = read(SESSION);
        for (var entry : code.entrySet()) {
            if (entry.getKey().equals("committed")) continue;
            assertEquals(0, entry.getValue().count(REPLICA, "commitStage"), entry.getKey());
            assertEquals(0, entry.getValue().count(COLLISION, "publish"), entry.getKey());
        }
        var committed = method(code, "committed");
        assertEquals(1, committed.count(REPLICA, "commitStage"));
        assertEquals(1, committed.count(COLLISION, "publish"));
        assertTrue(committed.index(SESSION, "checkPair") < committed.index(REPLICA, "commitStage"));
        assertTrue(committed.index(REPLICA, "commitStage") < committed.index(COLLISION, "publish"));
        assertTrue(committed.literals.contains("Commit precedes paired rendering or frozen-presentation retirement"));
    }

    @Test void cleanupRetainsReplicaUntilQueueAndAdoptedNativeOwnersHaveBeenClosed() throws IOException {
        var close = method(read(SESSION), "close");
        assertEquals(1, close.count(QUEUE, "close"));
        assertTrue(close.index(QUEUE, "close") < close.index(SESSION, "closeExcept"));
        assertTrue(close.index(SESSION, "closeExcept") < close.index(REPLICA, "close"));
        assertTrue(close.index(FAR, "close") < close.index(REPLICA, "close"));
        assertTrue(close.throwCount >= 2, "RuntimeException and Error cleanup failures must escape before retirement");
    }

    @Test void delayedScreenshotCannotRewriteAnotherPublicationsJsonReport() throws IOException {
        var code = read(SESSION);
        var screenshot = method(code, "afterHud");
        assertEquals(0, screenshot.count(SESSION, "report"));
        assertEquals(0, screenshot.count(SESSION, "write"));
        assertTrue(screenshot.reads.contains("pending"));
        assertTrue(screenshot.reads.contains("publication"));
        assertTrue(screenshot.reads.contains("drawn"));
        assertTrue(screenshot.writes.contains("capture"));
        assertTrue(method(code, "metadata").writes.contains("capture"));
        assertTrue(method(code, "beginEdit").writes.contains("capture"));
        assertEquals(2, screenshot.count(SESSION, "captureScreenshot"));
        assertEquals(1, method(code, "captureScreenshot").count("net/minecraft/client/Screenshot", "takeScreenshot"));
    }

    private static MethodCode method(Map<String, MethodCode> methods, String name) {
        var result = methods.get(name);
        assertNotNull(result, "Missing production method " + name);
        return result;
    }

    /** Multiple real main/shadow hooks in one client frame cannot run the shared preparation twice. */
    private static void assertRepeatedShaderPassCannotPumpAgain() throws IOException {
        var node=new ClassNode();try(var in=RollingSlicedClientContractTest.class.getClassLoader().getResourceAsStream(SESSION+".class")){
            assertNotNull(in);new ClassReader(in).accept(node,0);
        }
        var method=node.methods.stream().filter(m->m.name.equals("shaderFrame")).findFirst().orElseThrow();
        JumpInsnNode sameFrame=null;int update=-1;
        for(int i=0;i<method.instructions.size();i++){
            var instruction=method.instructions.get(i);
            if(instruction instanceof MethodInsnNode call&&call.owner.equals(SESSION)&&call.name.equals("updatePreparedPair")){assertEquals(-1,update);update=i;}
            if(instruction instanceof FieldInsnNode field&&field.owner.equals(BASE+"coregate/RollingGateClient")&&field.name.equals("actualRenderFrame")){
                var compare=nextOpcode(instruction);
                if(compare!=null&&compare.getOpcode()==Opcodes.LCMP){var branch=nextOpcode(compare);assertInstanceOf(JumpInsnNode.class,branch);assertEquals(Opcodes.IFEQ,branch.getOpcode());sameFrame=(JumpInsnNode)branch;}
            }
        }
        assertNotNull(sameFrame);assertTrue(update>=0);
        var visited=new HashSet<Integer>();var pending=new ArrayDeque<Integer>();pending.add(0);
        while(!pending.isEmpty()){
            int index=pending.removeFirst();if(index<0||index>=method.instructions.size()||!visited.add(index))continue;
            var instruction=method.instructions.get(index);int opcode=instruction.getOpcode();
            for(var handler:method.tryCatchBlocks)if(method.instructions.indexOf(handler.start)<=index&&index<method.instructions.indexOf(handler.end))pending.add(method.instructions.indexOf(handler.handler));
            if(instruction==sameFrame)pending.add(method.instructions.indexOf(sameFrame.label));
            else if(instruction instanceof JumpInsnNode branch){pending.add(method.instructions.indexOf(branch.label));if(opcode!=Opcodes.GOTO)pending.add(index+1);}
            else if(opcode!=Opcodes.ATHROW&&!(opcode>=Opcodes.IRETURN&&opcode<=Opcodes.RETURN))pending.add(index+1);
        }
        assertFalse(visited.contains(update),"A repeated native or DH stage must use the already pinned frame, not pump another phase");
        for(int index:visited)if(method.instructions.get(index) instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.PUTFIELD&&field.owner.equals(SESSION))
            assertFalse(Set.of("shaderPreparedFrame","shaderFrameDigest","shaderFrameHistorical").contains(field.name),"Same-frame presentation identity cannot change");
    }
    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction){
        do{instruction=instruction.getNext();}while(instruction!=null&&instruction.getOpcode()<0);return instruction;
    }

    private static Map<String, MethodCode> read(String className) throws IOException {
        var methods = new LinkedHashMap<String, MethodCode>();
        try (var stream = RollingSlicedClientContractTest.class.getClassLoader()
                .getResourceAsStream(className + ".class")) {
            assertNotNull(stream, className);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    var code = methods.computeIfAbsent(name, ignored -> new MethodCode());
                    return new MethodVisitor(Opcodes.ASM9) {
                        int previousOpcode = -1;
                        @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                              String desc, boolean iface) {
                            var call = new Call(owner, method);
                            code.calls.add(call); code.events.add(call); previousOpcode = opcode;
                        }
                        @Override public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                            if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC)
                                code.events.add(new FieldRead(owner, field));
                            if (owner.equals(SESSION)) {
                                if (opcode == Opcodes.GETFIELD) code.reads.add(field);
                                if (opcode == Opcodes.PUTFIELD) {
                                    code.writes.add(field); code.events.add(new FieldWrite(field, previousOpcode));
                                }
                            }
                            previousOpcode = opcode;
                        }
                        @Override public void visitVarInsn(int opcode, int variable) { previousOpcode = opcode; }
                        @Override public void visitTryCatchBlock(org.objectweb.asm.Label start,
                                org.objectweb.asm.Label end, org.objectweb.asm.Label handler, String type) {
                            if (type != null) code.catchTypes.add(type);
                        }
                        @Override public void visitLdcInsn(Object value) { code.literals.add(value); }
                        @Override public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) code.events.add("return");
                            if (opcode == Opcodes.ATHROW) code.throwCount++;
                            if (opcode == Opcodes.MONITORENTER) code.monitors++;
                            previousOpcode = opcode;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return methods;
    }

    private record Call(String owner, String name) {}
    private record FieldWrite(String field, int previousOpcode) {}
    private record FieldRead(String owner, String field) {}
    private static final class MethodCode {
        final List<Call> calls = new ArrayList<>();
        final List<Object> events = new ArrayList<>();
        final List<String> catchTypes = new ArrayList<>();
        int eventIndex(Object event) {
            int index = events.indexOf(event);
            assertTrue(index >= 0, "Missing production event " + event);
            return index;
        }
        final List<String> reads = new ArrayList<>(), writes = new ArrayList<>();
        final List<Object> literals = new ArrayList<>();
        int throwCount,monitors;
        long count(String owner, String name) { return calls.stream().filter(new Call(owner, name)::equals).count(); }
        int index(String owner, String name) {
            int index = calls.indexOf(new Call(owner, name));
            assertTrue(index >= 0, "Missing production call " + owner + "." + name);
            return index;
        }
    }
}
