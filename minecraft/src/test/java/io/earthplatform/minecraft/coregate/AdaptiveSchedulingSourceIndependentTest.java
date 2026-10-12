package io.earthplatform.minecraft.coregate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

/** Production bytecode safety gates plus exact unchanged geometry/corpus source pins. */
class AdaptiveSchedulingSourceIndependentTest {
    static final String SESSION = "io/earthplatform/minecraft/coregate/RollingGateClient$Session";
    static final String CAPSULE = "io/earthplatform/minecraft/coregate/NativeCapsuleWorkSlice";
    static final String CAPTURE = "io/earthplatform/minecraft/scene/NativeVisualCapsuleCapture";

    @Test void capsuleNeverSealsFromIncompleteDuplicateExpiredOrInvalidHostPaths() throws Exception {
        var method = observation();
        int seal = callIndex(method, CAPTURE, "seal");
        int complete = callIndex(method, CAPSULE + "$Stats", "complete");
        var incomplete = jumpAfter(method.instructions.get(complete));
        assertEquals(Opcodes.IFEQ, incomplete.getOpcode());
        assertSealUnreachable(method, incomplete, seal, "Incomplete installed corpus");
        var duplicate = fieldBranch(method, CAPSULE + "$StopReason", "DUPLICATE_FRAME", complete, seal);
        assertEquals(Opcodes.IF_ACMPEQ, duplicate.getOpcode());
        assertSealUnreachable(method, duplicate, seal, "Duplicate actual render frame");
        var current = method.instructions.toArray();
        JumpInsnNode elapsed = null;
        for (int i = complete; i < seal; i++) if (current[i] instanceof LdcInsnNode constant && Long.valueOf(3_000_000L).equals(constant.cst)) {
            var compare = next(current[i]);
            if (compare.getOpcode() == Opcodes.LCMP && next(compare) instanceof JumpInsnNode branch) elapsed = branch;
        }
        assertNotNull(elapsed, "Seal requires a newly observed owner elapsed comparison");
        assertEquals(Opcodes.IFGE, elapsed.getOpcode());
        assertSealUnreachable(method, elapsed, seal, "Exhausted shared capsule owner budget");
        for (String field : List.of("closed", "visualRecoveryQueued")) {
            var invalid = fieldBranch(method, SESSION, field, complete, seal);
            assertEquals(Opcodes.IFNE, invalid.getOpcode());
            assertSealUnreachable(method, invalid, seal, "Changed host " + field);
        }
        int lateValid = -1;
        for (int i = complete; i < seal; i++) if (current[i] instanceof MethodInsnNode call && call.owner.equals(SESSION) && call.name.equals("valid")) lateValid = i;
        assertTrue(lateValid > complete);
        var invalid = jumpAfter(current[lateValid]);
        assertEquals(Opcodes.IFEQ, invalid.getOpcode());
        assertSealUnreachable(method, invalid, seal, "Changed session/resource owner");
    }

    @Test void shadowOrOtherNativePassCannotReachTheCapsulePacker() throws Exception {
        var method = observation(); int pump = callIndex(method, CAPSULE, "pump");
        var mainOnly = fieldBranch(method, "io/earthplatform/compat/dh/shader/DhShaderPassGate$Pass", "MAIN_NATIVE_SOLID", 0, pump);
        assertTrue(mainOnly.getOpcode() == Opcodes.IF_ACMPNE || mainOnly.getOpcode() == Opcodes.IF_ACMPEQ);
        assertSealUnreachable(method, mainOnly, pump, "Shadow/other native callback", mainOnly.getOpcode() == Opcodes.IF_ACMPNE);
        int frameRead = -1;
        for (int i = 0; i < pump; i++) if (method.instructions.get(i) instanceof FieldInsnNode field && field.name.equals("actualRenderFrame")) frameRead = i;
        assertTrue(frameRead >= 0 && frameRead < pump, "Exact actual frame is supplied to deduplication helper");
    }

    @Test void capsuleHelperRetainsNoNativeRendererResourceOrNewCorpusCache() {
        for (var field : NativeCapsuleWorkSlice.class.getDeclaredFields()) {
            var type = field.getType();
            assertTrue(type.isPrimitive() || type == Thread.class || type == java.util.function.LongSupplier.class,
                    "Only bounded counters, constructing thread and time source belong in the helper: " + field);
        }
        assertEquals(86016, NativeCapsuleWorkSlice.MAX_CASES);
        assertEquals(672, NativeCapsuleWorkSlice.MAX_STEPS);
    }

    @Test void nativeGeometryInstalledComparatorAndResourceGuardsAreByteForByteUnchangedFrom035() throws Exception {
        String project = System.getProperty("earthplatform.test.projectDirectory");
        assertNotNull(project, "Verification must identify its actual candidate source directory");
        Path root = Path.of(project).toAbsolutePath().normalize().getParent();
        for (var entry : UNCHANGED.entrySet()) {
            byte[] bytes = Files.readAllBytes(root.resolve(entry.getKey()));
            assertEquals(entry.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), entry.getKey());
        }
    }

    private static MethodNode observation() throws Exception {
        try (var in = AdaptiveSchedulingSourceIndependentTest.class.getClassLoader().getResourceAsStream(SESSION + ".class")) {
            assertNotNull(in); var node = new ClassNode(); new ClassReader(in).accept(node, 0);
            return node.methods.stream().filter(m -> m.name.equals("observeVisualNative")).findFirst().orElseThrow();
        }
    }
    private static int callIndex(MethodNode method, String owner, String name) {
        int result = -1;
        for (int i = 0; i < method.instructions.size(); i++) if (method.instructions.get(i) instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
            assertEquals(-1, result, "One actual " + name + " call site"); result = i;
        }
        assertTrue(result >= 0, "Missing " + owner + "." + name); return result;
    }
    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    private static JumpInsnNode jumpAfter(AbstractInsnNode instruction) {
        return assertInstanceOf(JumpInsnNode.class, next(instruction));
    }
    private static JumpInsnNode fieldBranch(MethodNode method, String owner, String name, int from, int to) {
        for (int i = from; i < to; i++) if (method.instructions.get(i) instanceof FieldInsnNode field && field.owner.equals(owner) && field.name.equals(name))
            return jumpAfter(field);
        throw new AssertionError("Missing branch for " + owner + "." + name);
    }
    private static void assertSealUnreachable(MethodNode method, JumpInsnNode forced, int target, String condition) {
        assertSealUnreachable(method, forced, target, condition, true);
    }
    private static void assertSealUnreachable(MethodNode method, JumpInsnNode forced, int target, String condition, boolean takeBranch) {
        var pending = new ArrayDeque<Integer>(); var seen = new HashSet<Integer>(); pending.add(0);
        while (!pending.isEmpty()) {
            int index = pending.removeFirst(); if (index < 0 || index >= method.instructions.size() || !seen.add(index)) continue;
            var instruction = method.instructions.get(index); int opcode = instruction.getOpcode();
            for (var handler : method.tryCatchBlocks) if (method.instructions.indexOf(handler.start) <= index && index < method.instructions.indexOf(handler.end))
                pending.add(method.instructions.indexOf(handler.handler));
            if (instruction == forced) pending.add(takeBranch ? method.instructions.indexOf(forced.label) : index + 1);
            else if (instruction instanceof JumpInsnNode branch) {
                pending.add(method.instructions.indexOf(branch.label)); if (opcode != Opcodes.GOTO) pending.add(index + 1);
            } else if (opcode != Opcodes.ATHROW && !(opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)) pending.add(index + 1);
        }
        assertFalse(seen.contains(target), condition + " must make the target impossible in real host control flow");
    }
    private static final Map<String, String> UNCHANGED = Map.ofEntries(
            Map.entry("minecraft/src/main/java/io/earthplatform/minecraft/visual/NativeDistanceLimits.java", "b489c31d963903d98269d73b2ed5d0659b418ceb27a7605dce5cbc2680ff67bf"),
            Map.entry("minecraft/src/main/java/io/earthplatform/minecraft/scene/SceneMesh.java", "b57c1f805a027044c107c173491988ae884096f83c2eab02a01f51a97b8524a7"),
            Map.entry("minecraft/src/main/java/io/earthplatform/minecraft/scene/SceneBuildStepper.java", "fa2b13e4604311e7076df6d068c902a13f99be5f18e1c3dde9455bbf159a08a8"),
            Map.entry("minecraft/src/main/java/io/earthplatform/minecraft/scene/NativeVisualCapsule.java", "71b0511516a93d687e176163506ce1a3e7ef4d53c4e9689cd9869f35182aa111"),
            Map.entry("minecraft/src/main/java/io/earthplatform/minecraft/scene/NativeVisualCapsuleCapture.java", "29833ac48f48a0f400850564a47075db19d93e695b9f13f5ce68b45d166ba78a"),
            Map.entry("minecraft/src/main/java/io/earthplatform/minecraft/scene/NativeVisualPageOracle.java", "ddfb1003af83bcd39cd5e6cf7f8c76c501e276a4d6a03c0003906406d072a17c"),
            Map.entry("minecraft/src/main/java/io/earthplatform/minecraft/scene/NativeVisualCpuBuilder.java", "29291ee10b715fa2f4834e5f946d913951d043931671afb7f7b9fb1a48534392"),
            Map.entry("minecraft/src/main/java/io/earthplatform/minecraft/scene/NativeVisualGpuStorage.java", "6628ef2abe8e310a6f3f5d5f3a42d68b557e5688a1c80106ea5f8f889b5f071f"),
            Map.entry("minecraft/src/main/java/io/earthplatform/minecraft/scene/SceneReadFootprint.java", "5ea0f8d8445424d8b76b56ab4f357fc8bd94f1f39a720e7a27b2461ee108a539"),
            Map.entry("minecraft/src/main/java/io/earthplatform/minecraft/visual/NativeVisualPublication.java", "eb606a76836042b28794d8bfe11b35caeb8c889cfd819d73265c60425eda0a02"),
            Map.entry("minecraft/src/main/java/io/earthplatform/minecraft/coregate/RollingMotionGuard.java", "f6406aaf7950b5f886325f499f4c364c04b02909eb0ba2aa9e0b9d2d499a8887"),
            Map.entry("minecraft/src/main/java/io/earthplatform/minecraft/coregate/CoreCollisionBinding.java", "094858cb02165363a4a68e6892af19a2ae17312df4fb6ff062de7fb15d663cf4")
    );
}
