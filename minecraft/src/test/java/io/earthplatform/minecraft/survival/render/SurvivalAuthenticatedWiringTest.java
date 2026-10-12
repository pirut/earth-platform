package io.earthplatform.minecraft.survival.render;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Production linkage checks; runtime native model and installed GL parity remain separate qualifications. */
class SurvivalAuthenticatedWiringTest {
    private static final String C="io/earthplatform/minecraft/survival/render/SurvivalNativePageCompiler";
    private static final String G="io/earthplatform/minecraft/scene/SurvivalNativeGpuPage";
    private static MethodNode method(String type,String name) throws IOException {
        try(var bytes=SurvivalAuthenticatedWiringTest.class.getClassLoader().getResourceAsStream(type+".class")) {
            assertNotNull(bytes);var node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();
        }
    }
    private static int callIndex(MethodNode method,String name) {
        for(var at:method.instructions)if(at instanceof MethodInsnNode call&&call.name.equals(name))return method.instructions.indexOf(at);
        fail("Missing call "+name+" in "+method.name);return -1;
    }
    @Test void verificationReturnsBeforeNativeCompileAndDoesNotInvokeModels() throws Exception {
        var step=method(C+"$Build","step");int verify=callIndex(step,"verifySlice"),compile=callIndex(step,"compileBlock");
        assertTrue(verify<compile);
        var next=step.instructions.get(verify).getNext();while(next!=null&&next.getOpcode()<0)next=next.getNext();
        assertNotNull(next);assertEquals(Opcodes.ARETURN,next.getOpcode(),"Verification slice returns without falling into model compilation");
        for(String name:List.of("<init>","step","finish")) {
            var method=method(C+"$Verification",name);
            for(var at:method.instructions)if(at instanceof MethodInsnNode call)
                assertFalse(List.of("getBlockModel","renderBatched","compileBlock","upload").contains(call.name));
        }
        var constructor=method(C+"$Build","<init>");
        int copied=callIndex(constructor,"copyAuthenticated");
        int verifier=-1;
        for(var at:constructor.instructions)if(at instanceof MethodInsnNode call&&call.owner.equals(C+"$Verification")&&call.name.equals("<init>"))
            verifier=constructor.instructions.indexOf(at);
        assertTrue(verifier>copied,"Private charged copy must precede verification retention");
        // Actual source generation only occurs in the resumable verification dependency setup.
        for(var at:constructor.instructions)if(at instanceof MethodInsnNode call)
            assertFalse(List.of("generate","generateVisualCube","beginDependency","verifyCell").contains(call.name));
    }
    @Test void proofMovesThroughExclusiveProducerBeforePhysicalWorkAndCompletionRequiresIt() throws Exception {
        var start=method(G,"startSource");
        assertTrue(callIndex(start,"claim")<callIndex(start,"takeAuthenticatedContent"));
        assertTrue(callIndex(start,"takeAuthenticatedContent")<callIndex(start,"pieceCount"));
        var transfer=method(G+"$CompiledSource","takeAuthenticatedContent");
        assertTrue(callIndex(transfer,"takeAuthenticatedContent")>=0);
        var complete=method(G+"$Completion","requireAuthenticated");
        assertTrue(callIndex(complete,"requireComplete")<callIndex(complete,"requireMatches"));
        var owner=method(C+"$CompiledPage$UploadOwner","takeAuthenticatedContent");
        assertTrue(callIndex(owner,"check")>=0);
    }
    @Test void compilerFrameVerificationNeverReadsMutableSimulationOwners() throws Exception {
        for(String type:List.of(C+"$Verification",C+"$AuthenticatedContent",
                "io/earthplatform/minecraft/survival/render/SurvivalCompleteOverlay$DependencyCheck")) {
            try(var bytes=getClass().getClassLoader().getResourceAsStream(type+".class")) {
                assertNotNull(bytes);var node=new ClassNode();new ClassReader(bytes).accept(node,0);
                for(var method:node.methods)for(var at:method.instructions)if(at instanceof MethodInsnNode call)
                    assertFalse(call.owner.contains("NativeSurvivalWorld")||call.owner.contains("NativeWholeAction")||call.owner.contains("SurvivalCheckpointCoordinator"));
            }
        }
    }
}
