package io.earthplatform.compat.dh.shader;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compiled integrated host contracts, independently inspected without initializing Minecraft. */
class IntegratedShaderHostIndependentTest {
    private static final String SESSION = "io/earthplatform/minecraft/coregate/RollingGateClient$Session";
    private static final String CLIENT = "io/earthplatform/minecraft/coregate/RollingGateClient";
    private static final String CARRIER = "io/earthplatform/compat/dh/shader/InstalledShaderCarrier";
    private static final String NATIVE = "io/earthplatform/minecraft/scene/NativeIrisShaderStages";
    private static ClassNode read(String name) throws IOException {
        try (var in = IntegratedShaderHostIndependentTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(in, name); var node = new ClassNode(); new ClassReader(in).accept(node, 0); return node;
        }
    }
    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static List<MethodInsnNode> calls(MethodNode method) {
        var calls = new ArrayList<MethodInsnNode>();
        for (var i : method.instructions) if (i instanceof MethodInsnNode call) calls.add(call); return calls;
    }
    private static List<MethodInsnNode> family(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name) || m.name.startsWith("lambda$" + name + "$"))
                .flatMap(m -> calls(m).stream()).toList();
    }
    private static int index(MethodNode method, String owner, String name) {
        var calls = calls(method).stream().filter(c -> c.owner.equals(owner) && c.name.equals(name)).toList();
        assertEquals(1, calls.size(), owner + "." + name); return method.instructions.indexOf(calls.getFirst());
    }
    private static Set<Integer> reachable(MethodNode method, int forcedBranch, boolean takeTarget) {
        var seen = new HashSet<Integer>(); var queue = new ArrayDeque<Integer>(); queue.add(0);
        while (!queue.isEmpty()) {
            int i = queue.removeFirst(); if (i < 0 || i >= method.instructions.size() || !seen.add(i)) continue;
            var instruction = method.instructions.get(i); int opcode = instruction.getOpcode();
            for (var handler : method.tryCatchBlocks)
                if (method.instructions.indexOf(handler.start) <= i && i < method.instructions.indexOf(handler.end))
                    queue.add(method.instructions.indexOf(handler.handler));
            if (i == forcedBranch) queue.add(takeTarget ? method.instructions.indexOf(((JumpInsnNode) instruction).label) : i + 1);
            else if (instruction instanceof JumpInsnNode branch) {
                queue.add(method.instructions.indexOf(branch.label)); if (opcode != Opcodes.GOTO) queue.add(i + 1);
            } else if (instruction instanceof TableSwitchInsnNode table) {
                queue.add(method.instructions.indexOf(table.dflt)); for (var label : table.labels) queue.add(method.instructions.indexOf(label));
            } else if (instruction instanceof LookupSwitchInsnNode lookup) {
                queue.add(method.instructions.indexOf(lookup.dflt)); for (var label : lookup.labels) queue.add(method.instructions.indexOf(label));
            } else if (opcode != Opcodes.ATHROW && !(opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)) queue.add(i + 1);
        }
        return seen;
    }
    private static AbstractInsnNode opcodeAfter(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    @Test void repeatedPassesInOneActualFrameCannotPrepareOrChangePinnedPresentation() throws Exception {
        var frame = method(read(SESSION), "shaderFrame"); int comparison = -1;
        for (var instruction : frame.instructions) if (instruction instanceof FieldInsnNode field
                && field.owner.equals(CLIENT) && field.name.equals("actualRenderFrame")) {
            var compare = opcodeAfter(instruction);
            if (compare != null && compare.getOpcode() == Opcodes.LCMP) {
                var branch = opcodeAfter(compare); assertInstanceOf(JumpInsnNode.class, branch);
                assertEquals(Opcodes.IFEQ, branch.getOpcode()); comparison = frame.instructions.indexOf(branch); break;
            }
        }
        assertTrue(comparison >= 0, "Actual render frame equality must guard all preparation");
        var sameFrame = reachable(frame, comparison, true);
        assertFalse(sameFrame.contains(index(frame, SESSION, "updatePreparedPair")));
        for (int i = 0; i < frame.instructions.size(); i++) if (frame.instructions.get(i) instanceof FieldInsnNode field
                && field.owner.equals(SESSION) && field.getOpcode() == Opcodes.PUTFIELD
                && Set.of("shaderFrameDigest", "shaderFrameHistorical", "shaderMainDrawMask", "drawn").contains(field.name))
            assertFalse(sameFrame.contains(i), "Repeated pass mutates frame pin/ledger: " + field.name);
        assertTrue(sameFrame.contains(index(frame, CARRIER + "$Frame", "<init>")));
    }

    @Test void nativeAndFarSubmissionFamiliesNeverUseEachOthersDrawPathOrDualDepthComposition() throws Exception {
        var session = read(SESSION); var nativeCalls = family(session, "shaderNative"); var farCalls = family(session, "shaderFar");
        assertTrue(nativeCalls.stream().anyMatch(c -> c.name.equals("drawLayer")));
        assertTrue(nativeCalls.stream().anyMatch(c -> c.name.equals("drawPresentedLayer")));
        assertFalse(nativeCalls.stream().anyMatch(c -> c.owner.endsWith("/DhPreparedLodView") && c.name.startsWith("draw")));
        assertTrue(farCalls.stream().anyMatch(c -> c.owner.endsWith("/DhPreparedLodView") && c.name.equals("drawPresented")));
        assertTrue(family(session, "shaderDrawCurrentFar").stream().anyMatch(c -> c.owner.endsWith("/DhPreparedLodView") && c.name.equals("draw")));
        assertFalse(farCalls.stream().anyMatch(c -> c.owner.endsWith("/SceneMesh") && c.name.startsWith("draw")));
        for (String name : List.of("shaderFrame", "shaderNative", "shaderFar", "shaderDrawCurrentFar"))
            for (var call : family(session, name)) {
                assertFalse(call.owner.contains("DepthComposition"), name + " enters diagnostic composition");
                assertFalse(Set.of("glDepthRange", "glClear", "setShaderFogStart", "setShaderFogEnd").contains(call.name), name + "." + call.name);
            }
    }

    @Test void shaderInitializationUsesNoPrivateDiagnosticTextureAndFencesPendingNativeRestoration() throws Exception {
        var initialize = method(read(SESSION), "initializeShader");
        assertTrue(calls(initialize).stream().anyMatch(c -> c.owner.equals(NATIVE) && c.name.equals("restorationPending")));
        for (var instruction : initialize.instructions) if (instruction instanceof TypeInsnNode type)
            assertNotEquals("net/minecraft/client/renderer/texture/DynamicTexture", type.desc);
        assertFalse(calls(initialize).stream().anyMatch(c -> Set.of("setPixelRGBA", "upload", "register").contains(c.name)));
        var close = method(read(SESSION), "close");
        int restoration = index(close, NATIVE, "restorePendingState");
        var disposal = new ArrayList<Integer>();
        for (int i = 0; i < close.instructions.size(); i++) if (close.instructions.get(i) instanceof FieldInsnNode field
                && field.owner.equals(SESSION) && field.name.equals("disposed") && field.getOpcode() == Opcodes.PUTFIELD) disposal.add(i);
        assertEquals(1, disposal.size()); assertTrue(restoration < disposal.getFirst(), "Restore before admitting final disposal");
    }

    @Test void bridgeAttestsPipelineAndForeignListenerPolicyBeforeHostPreparation() throws Exception {
        var prepare = method(read(CARRIER), "prepare");
        int lease = index(prepare, "io/earthplatform/compat/dh/InstalledIrisRuntime", "lease");
        int policy = index(prepare, "io/earthplatform/compat/dh/InstalledIrisEventPolicy", "requireCurrent");
        int host = index(prepare, CARRIER + "$Host", "frame");
        int frame = index(prepare, "io/earthplatform/compat/dh/shader/DhShaderPassGate", "beginFrame");
        assertTrue(lease < policy && policy < host && host < frame);
        assertTrue(index(prepare, "io/earthplatform/compat/dh/InstalledDhCarrierPolicy", "allows") < lease);
    }

    @Test void bootstrapExemptionRequiresNoPreviouslyAdmittedPipeline() throws Exception {
        var hook = method(read("io/earthplatform/compat/dh/shader/ShaderCarrierHooks"), "invalidateAdmitted");
        int admitted = index(hook, CARRIER, "pipelineAcquired"); var branch = opcodeAfter(hook.instructions.get(admitted));
        assertInstanceOf(JumpInsnNode.class, branch); assertEquals(Opcodes.IFEQ, branch.getOpcode());
        var unadmitted = reachable(hook, hook.instructions.indexOf(branch), true);
        assertFalse(unadmitted.contains(index(hook, CARRIER, "invalidate")));
    }

    @Test void nativeRestorationOwnershipIsRetainedUntilAllCleanupCategoriesSucceed() throws Exception {
        var state = read("io/earthplatform/minecraft/scene/NativeIrisState");
        var capture = method(state, "capture");
        index(capture, "io/earthplatform/minecraft/scene/NativeShaderCleanupOwner", "capture");
        var run = method(state, "run");
        int complete = index(run, "io/earthplatform/minecraft/scene/NativeIrisState$Cleanup", "finish");
        int writes = 0;
        for (int i = 0; i < run.instructions.size(); i++) if (run.instructions.get(i) instanceof FieldInsnNode field
                && field.name.equals("restored") && field.getOpcode() == Opcodes.PUTFIELD) {
            writes++; assertTrue(i > complete, "A failed restoration may not become permanently idempotent");
        }
        assertEquals(1, writes);
        var retry = method(read(NATIVE), "retryCleanup");
        assertTrue(index(retry, "com/mojang/blaze3d/systems/RenderSystem", "assertOnRenderThread")
                < index(retry, "io/earthplatform/minecraft/scene/NativeIrisState", "retryCleanup"));
    }

    @Test void lifecycleHostCallbackCannotRecursivelyReenterItsCarrierBarrier() throws Exception {
        ClassNode session = read(SESSION), implementation = null;
        for (var instruction : method(session, "initializeShader").instructions) if (instruction instanceof TypeInsnNode type
                && type.getOpcode() == Opcodes.NEW && type.desc.startsWith(SESSION + "$")) {
            var candidate = read(type.desc); if (candidate.interfaces.contains(CARRIER + "$Host")) implementation = candidate;
        }
        assertNotNull(implementation, "Discover the actual compiled shader Host implementation");
        record Work(ClassNode owner, MethodNode method) {}
        var queue = new ArrayDeque<Work>(); queue.add(new Work(implementation, method(implementation, "invalidate")));
        var visited = new HashSet<String>(); boolean inspectedSession = false;
        while (!queue.isEmpty()) {
            var work = queue.removeFirst(); if (!visited.add(work.owner().name + work.method().name + work.method().desc)) continue;
            for (var call : calls(work.method())) {
                assertFalse(call.owner.equals(CARRIER) && call.name.equals("invalidate"),
                        "Host lifecycle callback recurses into its already-running two-owner barrier through " + work.owner().name + "." + work.method().name);
                if (call.owner.equals(SESSION)) {
                    inspectedSession = true;
                    for (var next : session.methods) if (next.name.equals(call.name) && next.desc.equals(call.desc)) queue.add(new Work(session, next));
                }
            }
        }
        assertTrue(inspectedSession, "The actual session fencing path must be inspected");
        var invalidate = method(read(CARRIER), "invalidate");
        index(invalidate, "io/earthplatform/compat/dh/shader/ShaderLifecycleBarrier", "restore");
        assertTrue(invalidate.tryCatchBlocks.isEmpty(), "Lifecycle HEAD may not silently swallow required restoration failures");
    }

    @Test void backgroundResourceRevocationFencesCpuAuthorityWithoutEnteringTheGlBarrier() throws Exception {
        var invalidate = method(read(SESSION), "invalidate");
        int fence = index(invalidate, SESSION, "invalidateOwned");
        int check = index(invalidate, "net/minecraft/client/Minecraft", "isSameThread");
        var branch = opcodeAfter(invalidate.instructions.get(check));
        assertInstanceOf(JumpInsnNode.class, branch); assertEquals(Opcodes.IFEQ, branch.getOpcode());
        var background = reachable(invalidate, invalidate.instructions.indexOf(branch), true);
        assertTrue(background.contains(fence), "Background invalidation must immediately fence CPU and collision authority");
        assertFalse(background.contains(index(invalidate, CARRIER, "invalidate")), "Background prepare thread cannot restore GL state");
        assertTrue(fence < check);
    }

    @Test void retainedNativeStateRetriesBaseAndDerivedUniformsAndVerifiesGlBeforeCompletion() throws Exception {
        String owner = "io/earthplatform/minecraft/scene/NativeIrisState";
        var state = read(owner); var names = new HashSet<String>();
        for (var instruction : method(state, "<clinit>").instructions)
            if (instruction instanceof LdcInsnNode constant && constant.cst instanceof String name) names.add(name);
        assertTrue(names.containsAll(List.of("ModelViewMat", "ProjMat", "TextureMat", "ScreenSize", "ColorModulator",
                "Light0_Direction", "Light1_Direction", "GlintAlpha", "FogStart", "FogEnd", "FogColor", "FogShape",
                "LineWidth", "GameTime", "ChunkOffset", "ModelViewMatInverse", "ProjMatInverse", "NormalMat")),
                "Retained outer cleanup must repair all values whose stack-local restoration can fail");
        var uniform = read(owner + "$UniformValue"); var writes = family(uniform, "restore");
        for (String name : List.of("glUniform1iv", "glUniform2iv", "glUniform3iv", "glUniform4iv", "glUniform1fv",
                "glUniform2fv", "glUniform3fv", "glUniform4fv", "glUniformMatrix2fv", "glUniformMatrix3fv", "glUniformMatrix4fv"))
            assertTrue(writes.stream().anyMatch(c -> c.name.equals(name)), "Retry must repair native uniform type: " + name);
        boolean retainedRestore = false;
        for (var instruction : method(state, "run").instructions) if (instruction instanceof InvokeDynamicInsnNode dynamic)
            for (var argument : dynamic.bsmArgs) if (argument instanceof Handle handle
                    && handle.getOwner().equals(owner + "$UniformValue") && handle.getName().equals("restore")) retainedRestore = true;
        assertTrue(retainedRestore, "The pending native owner must retain each uniform restoration callback");
        assertTrue(family(state, "run").stream().anyMatch(c -> c.owner.equals(owner + "$GlErrors") && c.name.equals("check")));
        assertTrue(family(state, "verifyRestoredBindings").stream().anyMatch(c -> c.owner.equals(owner + "$UniformValue") && c.name.equals("verify")));
    }
}
