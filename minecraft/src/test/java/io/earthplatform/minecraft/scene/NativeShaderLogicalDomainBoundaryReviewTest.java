package io.earthplatform.minecraft.scene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Static compiled-code scope fences. Does not claim to execute Iris capture, setup, draw, or restoration. */
class NativeShaderLogicalDomainBoundaryReviewTest {
    private static final String BASE = "io/earthplatform/minecraft/scene/";
    private static final String STATE = BASE + "NativeIrisState";
    private static final String RENDER = "com/mojang/blaze3d/systems/RenderSystem";

    private static ClassNode read(String name) throws IOException {
        try (var stream = NativeShaderLogicalDomainBoundaryReviewTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name); var node = new ClassNode(); new ClassReader(stream).accept(node, 0); return node;
        }
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<AbstractInsnNode> code(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (var instruction : method.instructions) if (instruction.getOpcode() >= 0) result.add(instruction);
        return result;
    }

    private static List<MethodInsnNode> textureCalls(ClassNode node, String name) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (var method : node.methods) for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(RENDER) && call.name.equals(name)) result.add(call);
        return result;
    }

    private static boolean snapshot(FieldInsnNode field) {
        return field.owner.equals(STATE) && field.name.equals("shaderTextures") && field.desc.equals("[I");
    }

    @Test void nativeSetupOwnsOnlyLogicalSlotsZeroAndTwoNotTheFull128AliasStore() throws Exception {
        var stages = read(BASE + "NativeIrisShaderStages");
        var invocation = read(BASE + "NativeIrisShaderStages$1");
        assertTrue(textureCalls(stages, "setShaderTexture").isEmpty());
        var writes = textureCalls(invocation, "setShaderTexture");
        assertEquals(2, writes.size());
        var instructions = code(method(invocation, "setup"));
        for (int i = 0; i < writes.size(); i++) {
            var call = writes.get(i); int position = instructions.indexOf(call);
            assertTrue(position >= 3); assertEquals("(II)V", call.desc);
            assertEquals(i == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_2, instructions.get(position - 3).getOpcode());
            assertEquals(Opcodes.ALOAD, instructions.get(position - 2).getOpcode());
            assertInstanceOf(FieldInsnNode.class, instructions.get(position - 1));
            var value = (FieldInsnNode) instructions.get(position - 1);
            assertEquals(i == 0 ? "val$atlas" : "val$lightmap", value.name);
        }
        assertEquals(128, NativeShaderTextureAliases.SLOT_COUNT, "Logical deletion coverage is a different domain");
    }

    @Test void nativeCaptureAndRestoreRemainBoundedByTheirOwnPrivateTwelveElementSnapshot() throws Exception {
        var state = read(STATE);
        var field = state.fields.stream().filter(value -> value.name.equals("shaderTextures")).findFirst().orElseThrow();
        assertEquals("[I", field.desc);
        assertTrue((field.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) == (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL));
        assertEquals(0, field.access & Opcodes.ACC_STATIC);
        var constructor = code(method(state, "<init>"));
        var assignments = constructor.stream().filter(instruction -> instruction instanceof FieldInsnNode value
                && snapshot(value) && value.getOpcode() == Opcodes.PUTFIELD).toList();
        assertEquals(1, assignments.size());
        int assignment = constructor.indexOf(assignments.getFirst());
        assertEquals(Opcodes.NEWARRAY, constructor.get(assignment - 1).getOpcode());
        assertEquals(Opcodes.T_INT, ((IntInsnNode) constructor.get(assignment - 1)).operand);
        assertEquals(12, ((IntInsnNode) constructor.get(assignment - 2)).operand);
        assertEquals(1, textureCalls(state, "getShaderTexture").size());
        assertEquals(1, textureCalls(state, "setShaderTexture").size());
        for (String owner : List.of("<init>", "run")) {
            var instructions = code(method(state, owner)); int snapshotBounds = 0;
            for (int index = 0; index + 2 < instructions.size(); index++)
                if (instructions.get(index) instanceof FieldInsnNode value && snapshot(value)
                        && value.getOpcode() == Opcodes.GETFIELD && instructions.get(index + 1).getOpcode() == Opcodes.ARRAYLENGTH) {
                    assertEquals(Opcodes.IF_ICMPGE, instructions.get(index + 2).getOpcode()); snapshotBounds++;
                }
            assertEquals(1, snapshotBounds, owner + " loop is bounded by the private snapshot, not raw128 or hardware capacity");
        }
        var restore = textureCalls(state, "setShaderTexture").getFirst();
        var restoreBody = state.methods.stream().filter(method -> code(method).contains(restore)).findFirst().orElseThrow();
        var restoreCode = code(restoreBody);
        assertEquals("(I)V", restoreBody.desc);
        assertEquals(List.of(Opcodes.ILOAD, Opcodes.ALOAD, Opcodes.GETFIELD, Opcodes.ILOAD,
                Opcodes.IALOAD, Opcodes.INVOKESTATIC, Opcodes.RETURN), restoreCode.stream().map(AbstractInsnNode::getOpcode).toList());
        assertTrue(snapshot((FieldInsnNode) restoreCode.get(2)));
        assertEquals(((VarInsnNode) restoreCode.getFirst()).var, ((VarInsnNode) restoreCode.get(3)).var);
        // Verify the exact restoration lambda is reached from run's bounded loop, with no alternate direct call.
        long restoreReferences = state.methods.stream().flatMap(method -> code(method).stream())
                .filter(instruction -> instruction instanceof InvokeDynamicInsnNode dynamic
                        && java.util.Arrays.stream(dynamic.bsmArgs).anyMatch(argument -> argument instanceof org.objectweb.asm.Handle handle
                        && handle.getOwner().equals(STATE) && handle.getName().equals(restoreBody.name))).count();
        assertEquals(1, restoreReferences);
        for (var body : state.methods) for (var instruction : body.instructions)
            if (instruction instanceof FieldInsnNode value && value.owner.equals(RENDER))
                assertNotEquals("shaderTextures", value.name, "Native restoration must not acquire direct whole-store ownership");
    }
}
