package io.earthplatform.minecraft.coregate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

/** Independent production-wiring checks, not a substitute for physical VBO or GUI execution. */
class RollingNativeReuseContractTest {
    private static final String BASE = "io/earthplatform/minecraft/";
    private static final String SESSION = BASE + "coregate/RollingGateClient$Session";
    private static final String MESH = BASE + "scene/SceneMesh", REUSE = MESH + "$Reuse";
    private static final String PRESENTATION = BASE + "coregate/RollingEditPresentation";
    private static final String FAR = BASE + "lod/DhPreparedLodView";
    private static final String QUEUE = BASE + "coregate/RollingMeshWorkQueue";
    private static final String GUARD = BASE + "renderprobe/RenderPublicationGuard";
    private static final String COLLISION = BASE + "coregate/CoreCollisionBinding";

    @Test void readinessQueuesReuseWithoutReplayingOrMutatingAnOldBinding() throws IOException {
        var code = method(read(SESSION), "ready");
        assertEquals(1, code.count(MESH, "isCurrent"));
        assertEquals(0, code.count(MESH, "prepareReuse"));
        assertEquals(1, code.count("java/util/Deque", "addLast"));
        assertTrue(code.events.contains("read:nativeReuses"));
        assertTrue(code.events.contains("read:nativeReuseQueue"));
        assertEquals(0, code.count(REUSE, "commit"));
        assertEquals(0, code.count(MESH, "close"));
        assertEquals(0, code.count(PRESENTATION, "retire"));
        assertEquals(0, code.count(COLLISION, "publish"));
        assertTrue(code.strings.stream().anyMatch(s -> s.contains("Previous candidate owns resources")));
        assertTrue(code.strings.stream().anyMatch(s -> s.contains("Native classification exceeds target cap")));
    }

    @Test void oneReuseReplayReturnsBeforeAnyNativeCallbackPhase() throws IOException {
        var methods = read(SESSION);
        var prepare = method(methods, "prepareCandidateWork");
        int replay = prepare.at(SESSION, "prepareOneNativeReuse"), pump = prepare.at(QUEUE, "pump");
        assertEquals(1, prepare.count(SESSION, "prepareOneNativeReuse"));
        assertTrue(replay < pump);
        assertTrue(prepare.events.subList(replay, pump).contains("return"),
                "One footprint replay must return instead of also entering the native callback phase");
        var one = method(methods, "prepareOneNativeReuse");
        assertEquals(1, one.count(MESH, "prepareReuse"));
        assertEquals(0, one.count(QUEUE, "pump"));
        assertTrue(one.at(MESH, "prepareReuse") < one.at("java/util/Deque", "removeFirst"));
        assertTrue(one.at("java/util/Deque", "removeFirst") < one.at(SESSION, "startNativeBuilds"));
        assertEquals(0, one.count(REUSE, "commit"));
    }

    @Test void buildQueueStartsOnlyAfterClassificationAndKeepsLatePublicationResourceFence() throws IOException {
        var methods = read(SESSION);
        var start = method(methods, "startNativeBuilds");
        assertTrue(start.events.contains("read:nativeClassificationComplete"));
        assertTrue(start.events.contains("read:nativeReuseQueue"));
        assertTrue(start.at(QUEUE, "start") < start.at("java/util/List", "clear"));
        var fence = methods.values().stream().filter(code -> code.count(SESSION, "valid") == 1
                && code.events.contains("read:pending") && code.events.contains("read:digest")
                && code.count(GUARD, "resourceGeneration") == 1).findFirst().orElseThrow();
        assertTrue(fence.events.contains("read:publication"));
        assertEquals(1, fence.count(GUARD, "invalidationRequested"));
        assertEquals(1, fence.count("java/lang/String", "equals"));
        var validate = method(methods, "validateCandidate");
        assertTrue(validate.events.contains("read:nativeClassificationComplete"));
        assertTrue(validate.events.contains("read:nativeReuseQueue"));
        var close = method(methods, "close");
        assertTrue(close.events.contains("write:closingNativeReuseQueue"));
        assertEquals(1, close.count("java/util/Deque", "clear"));
    }

    @Test void candidateValidationUsesPrivateReuseProofAndNeverFrozenPresentationAuthority() throws IOException {
        var code = method(read(SESSION), "validateCandidate");
        assertEquals(2, code.count(QUEUE, "isDrained"),"Distance forbids queue ownership; legacy still requires its mesh drain");
        String body=BASE+"coregate/RollingDistanceBodyBinding";
        assertEquals(1,code.count(body+"$Binding","dependenciesCurrent"));
        assertEquals(1,code.count(body+"$NativeJoined","requirePrepared"));
        assertTrue(code.at(body+"$NativeJoined","requirePrepared")<code.at(QUEUE,"isDrained"));
        assertTrue(code.strings.contains("Distance body acquired forbidden SceneMesh owners"));
        assertEquals(1, code.count(REUSE, "isCurrent"));
        assertEquals(1, code.count(MESH, "isCurrent"));
        assertTrue(code.events.contains("read:nativeReuses"));
        assertEquals(0, code.count(REUSE, "commit"));
        assertFalse(code.calls.stream().anyMatch(call -> call.startsWith(PRESENTATION + ".")));
        assertFalse(code.calls.stream().anyMatch(call -> call.contains("isPresented")));
        assertValidationBranchesRemainSeparate();
    }

    @Test void rebindingFollowsExactFarCommitAndHistoricalRetirementBeforePairedReadiness() throws IOException {
        var code = method(read(SESSION), "installPair");
        assertTrue(code.at(SESSION, "validateCandidate") < code.at(FAR, "commitPrepared"));
        assertTrue(code.at(FAR, "commitPrepared") < code.at(FAR, "coverageReady"));
        assertEquals(2,code.count(PRESENTATION,"retire"));
        assertTrue(code.at(PRESENTATION,"retire")<code.at(FAR,"commitPrepared"));
        // The first retire is distance-only (its branch/polarity is checked by RollingFrozenClientContractTest).
        int legacyRetire=code.events.lastIndexOf(PRESENTATION+".retire");
        assertTrue(code.at(FAR, "coverageReady") < legacyRetire);
        assertTrue(legacyRetire < code.at(REUSE, "commit"));
        assertTrue(code.at(REUSE, "commit") < code.at(SESSION, "closeNativeReuses"));
        assertTrue(code.at(SESSION, "closeNativeReuses") < code.at(SESSION, "closeExcept"));
        assertTrue(code.at(SESSION, "closeExcept") < code.at(SESSION, "send"));
        assertEquals(0, code.count(COLLISION, "publish"));
    }

    @Test void sessionCleanupAttemptsPrivateBindingCleanupBeforePhysicalMeshCleanup() throws IOException {
        var code = method(read(SESSION), "close");
        assertTrue(code.at(GUARD, "close") < code.at(PRESENTATION, "close"));
        assertTrue(code.at(PRESENTATION, "close") < code.at(SESSION, "closeNativeReuses"));
        assertTrue(code.at(SESSION, "closeNativeReuses") < code.at(SESSION, "closeExcept"));
        assertTrue(code.at(SESSION, "closeExcept") < code.at(FAR, "close"));
        assertTrue(code.catchTypes.contains("java/lang/RuntimeException"));
        assertTrue(code.catchTypes.contains("java/lang/Error"));
        var cleanup = method(read(SESSION), "closeNativeReuses");
        assertTrue(cleanup.at(REUSE, "close") < cleanup.at("java/util/Iterator", "remove"),
                "A failed close must keep its cleanup owner reachable");
        assertTrue(cleanup.catchTypes.contains("java/lang/RuntimeException"));
        assertTrue(cleanup.catchTypes.contains("java/lang/Error"));
    }

    @Test void historicalDrawCannotInvokeRebindingOrCurrentNativeDraw() throws IOException {
        var historical = read(SESSION).values().stream()
                .filter(code -> code.count(FAR, "drawPresented") == 1).findFirst().orElseThrow();
        assertEquals(1, historical.count(MESH, "drawPresented"));
        assertEquals(1, historical.count(MESH, "presentationSource"));
        assertEquals(0, historical.count(MESH, "draw"));
        assertEquals(0, historical.count(MESH, "prepareReuse"));
        assertEquals(0, historical.count(REUSE, "commit"));
        assertEquals(0, historical.count(COLLISION, "publish"));
    }

    @Test void physicalOwnershipBudgetStillCountsActualMeshesInsteadOfBindings() throws IOException {
        var all = read(SESSION);
        var transferred = all.values().stream()
                .filter(code -> code.strings.contains("Native mesh generation cap exceeded"))
                .findFirst().orElseThrow();
        assertTrue(transferred.events.contains("read:owned"));
        assertTrue(transferred.events.contains("read:candidate"));
        assertFalse(transferred.events.contains("read:nativeReuses"));
        assertTrue(transferred.at("java/util/Set", "add") < transferred.at("java/util/Map", "put"));
        var close = method(all, "closeExcept");
        assertEquals(1, close.count(MESH, "close"));
        assertTrue(close.at(MESH, "close") < close.at("java/util/Iterator", "remove"));
    }

    @Test void reuseDiagnosticsDistinguishPreparedCommittedAndActualRebuildWork() throws IOException {
        var report = method(read(SESSION), "report");
        for (var expected : List.of("nativePrivateReuseBindings", "nativeStrictReused", "nativeFootprintPrepared",
                "nativeFootprintRejected", "nativeFootprintCommitted", "lastNativeFootprintPrepared",
                "lastNativeRebuildTargets", "nativeReusePreparationTiming", "nativeReuseCommitTiming")) {
            assertTrue(report.strings.contains(expected), "Missing evidence field " + expected);
        }
    }

    @Test void preparingReuseRetainsOriginalMeshBindingAndRequiresTheStrictPrimaryJob() throws IOException {
        var prepare = method(read(MESH), "prepareReuse");
        assertTrue(prepare.events.contains("read:job"));
        assertTrue(prepare.count(GUARD, "isCurrent") >= 2, "Primary and existing neighbor Jobs remain strict");
        assertEquals(1, prepare.count(GUARD, "isSameLiveLease"));
        assertEquals(1, prepare.count(BASE + "scene/SceneReadFootprint", "validate"));
        assertFalse(prepare.events.contains("write:binding"));
        assertEquals(0, prepare.count(GUARD, "presentationSource"));
        assertEquals(0, prepare.count(MESH, "close"));
        assertEquals(0, prepare.count(GUARD + "$PresentationSource", "close"));
        assertFalse(prepare.calls.stream().anyMatch(call -> call.startsWith("com/mojang/blaze3d/vertex/VertexBuffer.")));
    }

    @Test void preparedBindingValidationAndCommitNeverReplayTheFootprintAgain() throws IOException {
        var methods = read(REUSE);
        for (var name : List.of("isCurrent", "commit")) {
            var code = method(methods, name);
            assertEquals(0, code.count(BASE + "scene/SceneReadFootprint", "validate"), name);
            assertEquals(0, code.count(BASE + "scene/SceneNeighborhood", "replay"), name);
        }
        var current = method(methods, "isCurrent");
        assertTrue(current.events.contains("write:invalidated"), "Observed stale candidate cannot later regain authority");
        assertTrue(current.count(GUARD, "isCurrent") >= 2);
        var commit = method(methods, "commit");
        assertTrue(commit.at(REUSE, "isCurrent") < commit.at(GUARD, "isRetained"));
        assertTrue(commit.at(GUARD, "isRetained") < commit.at(GUARD, "drawIfCurrent"));
        var swap = methods.values().stream().filter(code -> code.events.contains("write:binding"))
                .findFirst().orElseThrow();
        assertTrue(swap.at(GUARD, "isRetained") < swap.at(GUARD, "presentationSource"));
        assertTrue(swap.at(GUARD, "presentationSource") < swap.events.indexOf("write:binding"));
        assertTrue(swap.events.indexOf("write:binding") < swap.at(GUARD + "$PresentationSource", "close"));
    }

    @Test void cancellingReuseClearsItsMetadataWithoutDestroyingSharedPhysicalMesh() throws IOException {
        var close = method(read(REUSE), "close");
        for (var field : List.of("closed", "expected", "dependencies", "pendingReuse"))
            assertTrue(close.events.contains("write:" + field));
        assertEquals(0, close.count(MESH, "close"));
        assertEquals(0, close.count(GUARD + "$PresentationSource", "close"));
        assertFalse(close.events.contains("write:binding"));
        var physicalClose = method(read(MESH), "close");
        assertTrue(physicalClose.at(REUSE, "close") < physicalClose.at(GUARD + "$PresentationSource", "close"));
        assertTrue(physicalClose.at(GUARD + "$PresentationSource", "close")
                < physicalClose.at("com/mojang/blaze3d/vertex/VertexBuffer", "close"));
    }

    @Test void observedAppearanceMismatchPermanentlyPoisonsThisPhysicalMesh() throws IOException {
        var usable = method(read(MESH), "presentationUsable");
        assertTrue(usable.events.contains("read:appearanceInvalidated"));
        assertTrue(usable.events.contains("write:appearanceInvalidated"));
        assertEquals(1, usable.count(BASE + "scene/SceneAppearanceSignature", "isCurrent"));
        assertTrue(usable.events.indexOf("read:appearanceInvalidated")
                < usable.at(BASE + "scene/SceneAppearanceSignature", "isCurrent"));
    }

    @Test void retainedInputAccountingIncludesWholeActiveNeighborhoodAndBothPrivateBindings() throws IOException {
        var report = method(read(SESSION), "reportRetainedNativeInputs");
        assertEquals(1, report.count("java/util/IdentityHashMap", "<init>"));
        assertEquals(1, report.count(MESH, "retainedSnapshots"));
        assertEquals(1, report.count(MESH + "$Build", "retainedSnapshots"));
        assertEquals(1, report.count(GUARD + "$PresentationLease", "snapshots"));
        assertEquals(1, report.count(MESH + "$Build", "footprintMetadataBytes"));
        assertTrue(report.events.contains("read:disposed"));
        assertTrue(report.events.contains("read:owned"));
        assertTrue(report.strings.contains("retainedNativeSnapshotSectionBytes"));
        var physical = method(read(MESH), "retainedSnapshots");
        assertTrue(physical.events.contains("read:binding"));
        assertTrue(physical.events.contains("read:pendingReuse"));
        assertTrue(physical.events.contains("read:expected"));
        assertEquals(1, physical.count("java/util/IdentityHashMap", "<init>"));
        var active = method(read(MESH + "$Build"), "retainedSnapshots");
        assertEquals(1, active.count(BASE + "scene/SceneNeighborhood", "retainedSnapshots"));
        assertEquals(1, active.count("java/util/IdentityHashMap", "<init>"));
        assertTrue(active.events.contains("read:cube"));
        assertTrue(active.events.contains("read:dependencies"));
    }

    @Test void inspectionAliasesAreRegisteredAfterAcquisitionAndReleasedOnlyAfterSuccessfulClose() throws IOException {
        var queue = read(SESSION + "$1");
        var begin = method(queue, "begin");
        assertTrue(begin.at(MESH, "begin") < begin.events.indexOf("write:inspectedNativeBuild"));
        var close = method(queue, "closeBuild");
        assertTrue(close.at(MESH + "$Build", "close") < close.events.indexOf("write:inspectedNativeBuild"));
        var presentation = read(SESSION + "$2");
        var capture = method(presentation, "captureNative");
        assertTrue(capture.at(GUARD, "retainPresentation") < capture.events.indexOf("write:inspectedNativePresentation"));
        var release = method(presentation, "closeNative");
        assertTrue(release.at(GUARD + "$PresentationLease", "close")
                < release.events.indexOf("write:inspectedNativePresentation"));
        var report = method(read(SESSION), "reportRetainedNativeInputs");
        assertFalse(report.calls.stream().anyMatch(call -> call.endsWith(".close")),
                "Inspection aliases must never become independent cleanup owners");
    }

    @Test void actualReadHaloFenceRunsBeforeCapturingAnyDependency() throws IOException {
        var observer = read(MESH + "$Build").values().stream()
                .filter(code -> code.count(BASE + "scene/SceneReadFootprint", "requireHaloOwner") == 1)
                .findFirst().orElseThrow();
        assertTrue(observer.at(BASE + "scene/SceneReadFootprint", "requireHaloOwner")
                < observer.at("java/util/Map", "computeIfAbsent"),
                "A known but farther stage-only owner must reject before its snapshot becomes retained");
        assertTrue(observer.at("java/util/Map", "computeIfAbsent") < observer.at(GUARD, "isCurrent"));
    }

    private static void assertValidationBranchesRemainSeparate()throws IOException{
        var node=new ClassNode();try(var in=RollingNativeReuseContractTest.class.getClassLoader().getResourceAsStream(SESSION+".class")){
            assertNotNull(in);new ClassReader(in).accept(node,0);
        }
        var method=node.methods.stream().filter(m->m.name.equals("validateCandidate")).findFirst().orElseThrow();
        JumpInsnNode mode=null;
        for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(SESSION)&&call.name.equals("distanceMode")){
            assertNull(mode);var next=instruction.getNext();while(next!=null&&next.getOpcode()<0)next=next.getNext();
            assertInstanceOf(JumpInsnNode.class,next);mode=(JumpInsnNode)next;assertEquals(Opcodes.IFEQ,mode.getOpcode());
        }
        assertNotNull(mode);
        var distance=reachableCalls(method,mode,true);var legacy=reachableCalls(method,mode,false);
        String body=BASE+"coregate/RollingDistanceBodyBinding";
        for(String call:List.of(body+"$Binding.dependenciesCurrent",body+"$NativeJoined.requirePrepared")){
            assertTrue(distance.contains(call),call);assertFalse(legacy.contains(call),call);
        }
        for(String call:List.of(REUSE+".isCurrent",MESH+".isCurrent",BASE+"coregate/RollingGateReplica.candidateTargets")){
            assertTrue(legacy.contains(call),call);assertFalse(distance.contains(call),call);
        }
        assertEquals(1,distance.stream().filter((QUEUE+".isDrained")::equals).count());
        assertEquals(1,legacy.stream().filter((QUEUE+".isDrained")::equals).count());
    }
    /** Follow the selected mode edge and every other possible edge, stopping at return/throw. */
    private static List<String> reachableCalls(MethodNode method,JumpInsnNode mode,boolean distance){
        var calls=new ArrayList<String>();var seen=new java.util.HashSet<Integer>();var pending=new java.util.ArrayDeque<Integer>();pending.add(0);
        while(!pending.isEmpty()){
            int index=pending.removeFirst();if(index<0||index>=method.instructions.size()||!seen.add(index))continue;
            var instruction=method.instructions.get(index);int op=instruction.getOpcode();
            if(instruction instanceof MethodInsnNode call)calls.add(call.owner+"."+call.name);
            if(instruction==mode)pending.add(distance?index+1:method.instructions.indexOf(mode.label));
            else if(instruction instanceof JumpInsnNode jump){pending.add(method.instructions.indexOf(jump.label));if(op!=Opcodes.GOTO)pending.add(index+1);}
            else if(op!=Opcodes.ATHROW&&!(op>=Opcodes.IRETURN&&op<=Opcodes.RETURN))pending.add(index+1);
        }
        return calls;
    }
    private static Code method(Map<String, Code> methods, String name) {
        var result = methods.get(name); assertNotNull(result, name); return result;
    }
    private static Map<String, Code> read(String name) throws IOException {
        var result = new LinkedHashMap<String, Code>();
        try (var input = RollingNativeReuseContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                           String signature, String[] exceptions) {
                    var code = new Code(); result.put(result.containsKey(method) ? method + descriptor : method, code);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                                              boolean isInterface) {
                            code.calls.add(owner + "." + name); code.events.add(owner + "." + name);
                        }
                        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            code.events.add((opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC ? "write:" : "read:") + name);
                        }
                        @Override public void visitLdcInsn(Object value) { if (value instanceof String s) code.strings.add(s); }
                        @Override public void visitInsn(int opcode) { if (opcode == Opcodes.RETURN) code.events.add("return"); }
                        @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle handle, Object... args) {
                            for (var arg : args) if (arg instanceof String s) code.strings.add(s);
                        }
                        @Override public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end,
                                                                org.objectweb.asm.Label handler, String type) {
                            if (type != null) code.catchTypes.add(type);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return result;
    }
    private static final class Code {
        final List<String> calls = new ArrayList<>(), events = new ArrayList<>(), strings = new ArrayList<>(), catchTypes = new ArrayList<>();
        long count(String owner, String name) { return calls.stream().filter((owner + "." + name)::equals).count(); }
        int at(String owner, String name) {
            int result = events.indexOf(owner + "." + name); assertTrue(result >= 0, "Missing " + owner + "." + name); return result;
        }
    }
}
