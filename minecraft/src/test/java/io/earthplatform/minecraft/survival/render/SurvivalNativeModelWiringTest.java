package io.earthplatform.minecraft.survival.render;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

/** Wiring evidence only. Loaded-model layer requests and actual native emission require the client fixture. */
class SurvivalNativeModelWiringTest {
    private record Call(String method, String owner, String name) {}
    private static List<Call> calls(String path) throws IOException {
        var calls = new ArrayList<Call>();
        try (var bytes = SurvivalNativeModelWiringTest.class.getClassLoader().getResourceAsStream(path + ".class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                        String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                            calls.add(new Call(method, owner, name));
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return calls;
    }
    @Test void survivalEntryUsesSceneMeshCorrectnessCompilerWithoutSixStateCapsule() throws Exception {
        var calls = calls("io/earthplatform/minecraft/survival/render/SurvivalNativeScene");
        assertEquals(2, calls.stream().filter(c -> c.method.equals("begin") && c.owner.endsWith("/SceneMesh")
                && c.name.equals("begin")).count());
        assertFalse(calls.stream().anyMatch(c -> c.owner.contains("NativeVisualCapsule") || c.owner.contains("NativeVisualCpuBuilder")));
        assertFalse(calls.stream().anyMatch(c -> c.owner.endsWith("/NativeSurvivalWorld")));
    }
    @Test void compilerQueriesActualBakedModelLayersAndRendersNativeBatchesSeparately() throws Exception {
        var calls = calls("io/earthplatform/minecraft/scene/SceneMesh$Build");
        assertTrue(calls.stream().anyMatch(c -> c.method.equals("compileBlock") && c.name.equals("getBlockModel")));
        assertTrue(calls.stream().anyMatch(c -> c.method.equals("compileBlock") && c.name.equals("getRenderTypes")));
        assertTrue(calls.stream().anyMatch(c -> c.method.startsWith("lambda$compileBlock$") && c.name.equals("renderBatched")));
        for (String layer : List.of("solid", "cutoutMipped", "cutout"))
            assertTrue(calls.stream().anyMatch(c -> c.method.equals("compileBlock") && c.name.equals(layer)), layer);
    }
    @Test void runtimeDiagnosticQueriesLoadedModelsAndDelegatesCompilationToTheSameSceneEntry() throws Exception {
        var calls = calls("io/earthplatform/minecraft/survival/render/SurvivalNativeModelFixture");
        assertTrue(calls.stream().anyMatch(c -> c.method.equals("inspectLoadedModelLayers") && c.name.equals("getBlockRenderer")));
        assertTrue(calls.stream().anyMatch(c -> c.method.equals("inspectLoadedModelLayers") && c.name.equals("getRenderTypes")));
        assertTrue(calls.stream().anyMatch(c -> c.method.equals("begin") && c.owner.endsWith("/SurvivalNativeScene") && c.name.equals("begin")));
        assertFalse(calls.stream().anyMatch(c -> c.owner.contains("NativeVisualCapsule") || c.owner.contains("NativeVisualCpuBuilder")));
    }
}
