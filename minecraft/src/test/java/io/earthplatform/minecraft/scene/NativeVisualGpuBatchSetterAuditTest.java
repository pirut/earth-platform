package io.earthplatform.minecraft.scene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import static org.junit.jupiter.api.Assertions.*;

/** Independent compiled-code audit of the production restorers used by NativeVisualGpuBatch.
 * This is a setter/dispatch wiring proof only; NativeVisualGpuBatchActualGlTest owns real GL execution.
 */
class NativeVisualGpuBatchSetterAuditTest {
    private static final String SCENE = "io/earthplatform/minecraft/scene/";
    private static final String UNIFORM = "com/mojang/blaze3d/shaders/Uniform";

    @Test void productionVanillaSnapshotRestoresFloatArraysAndIntegerSafeVectorsOnly() throws IOException {
        var calls = uniformMutators(read(SCENE + "SceneMesh$ShaderUniforms"));
        assertEquals(List.of("restore:set([F)V", "restore:setSafe(IIII)V"), calls,
                "The actual production snapshot must never infer an integer overload for floating storage");
    }

    @Test void exactIrisRestoreUsesGpuTypeDispatchRatherThanUniformSetters() throws IOException {
        assertTrue(uniformMutators(read(SCENE + "NativeIrisState$UniformValue")).isEmpty(),
                "Exact host restoration must preserve independently captured CPU and GPU values");
    }

    @Test void exactIrisRestoreMapsEveryPinnedUniformTypeToItsMatchingGpuUpload() throws IOException {
        var node = read(SCENE + "NativeIrisState$UniformValue");
        List<Map<Integer, String>> actual = new ArrayList<>();
        for (var method : node.methods) for (var instruction : method.instructions) {
            Map<Integer, String> uploadCases = new LinkedHashMap<>();
            if (instruction instanceof TableSwitchInsnNode table) {
                for (int index = 0; index < table.labels.size(); index++) {
                    String upload = firstGpuUpload(table.labels.get(index));
                    if (upload != null) uploadCases.put(table.min + index, upload);
                }
            } else if (instruction instanceof LookupSwitchInsnNode lookup) {
                for (int index = 0; index < lookup.labels.size(); index++) {
                    String upload = firstGpuUpload(lookup.labels.get(index));
                    if (upload != null) uploadCases.put(lookup.keys.get(index), upload);
                }
            }
            if (!uploadCases.isEmpty()) actual.add(uploadCases);
        }
        assertEquals(List.of(
                Map.of(0, "glUniform1iv(I[I)V", 1, "glUniform2iv(I[I)V",
                        2, "glUniform3iv(I[I)V", 3, "glUniform4iv(I[I)V"),
                Map.of(4, "glUniform1fv(I[F)V", 5, "glUniform2fv(I[F)V",
                        6, "glUniform3fv(I[F)V", 7, "glUniform4fv(I[F)V",
                        8, "glUniformMatrix2fv(IZ[F)V", 9, "glUniformMatrix3fv(IZ[F)V",
                        10, "glUniformMatrix4fv(IZ[F)V")), actual,
                "Each bounded pinned type must select its own scalar/vector/matrix upload shape");
    }

    private static ClassNode read(String internalName) throws IOException {
        try (var input = NativeVisualGpuBatchSetterAuditTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(input, internalName);
            var result = new ClassNode();
            new ClassReader(input).accept(result, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return result;
        }
    }

    private static List<String> uniformMutators(ClassNode node) {
        List<String> result = new ArrayList<>();
        for (var method : node.methods) for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(UNIFORM)
                    && (call.name.equals("set") || call.name.equals("setSafe") || call.name.startsWith("setMat")))
                result.add(method.name + ":" + call.name + call.desc);
        return result;
    }

    private static String firstGpuUpload(LabelNode label) {
        for (AbstractInsnNode instruction = label.getNext(); instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && call.owner.startsWith("org/lwjgl/opengl/GL")
                    && call.name.startsWith("glUniform")) return call.name + call.desc;
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.GOTO || opcode == Opcodes.ATHROW || opcode == Opcodes.RETURN) return null;
        }
        return null;
    }
}
