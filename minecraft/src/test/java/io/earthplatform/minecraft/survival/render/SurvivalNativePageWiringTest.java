package io.earthplatform.minecraft.survival.render;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Production call-graph checks only; live registry/model/GL equivalence still requires client qualification. */
class SurvivalNativePageWiringTest {
    private record Call(String method,String owner,String name) {}
    private static List<Call> calls(String type) throws IOException {
        var result=new ArrayList<Call>();
        try(var bytes=SurvivalNativePageWiringTest.class.getClassLoader().getResourceAsStream(type+".class")) {
            assertNotNull(bytes,type);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean itf) {
                            result.add(new Call(name,owner,method));
                        }
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        return result;
    }
    private static final String COMPILER="io/earthplatform/minecraft/survival/render/SurvivalNativePageCompiler";
    private static final String BRIDGE="io/earthplatform/minecraft/scene/SurvivalNativeCompilerBridge";
    @Test void pageCompilerUsesNativeModelLayerQueryAndNativeBatchedRenderer() throws Exception {
        var calls=calls(COMPILER+"$Build");
        for(String method:List.of("getBlockModel","getRenderTypes","renderBatched"))
            assertTrue(calls.stream().anyMatch(c->c.method.equals("compileBlock")&&c.name.equals(method)),method);
        assertFalse(calls.stream().anyMatch(c->c.owner.contains("NativeVisualCapsule")||c.owner.contains("NativeVisualCpuBuilder")
                ||c.owner.contains("NativeSurvivalWorld")||c.owner.contains("DistantHorizons")));
    }
    @Test void onlyThreeSeparateNativeTerrainLayersAreSupported() throws Exception {
        var calls=calls(COMPILER+"$Layer");
        for(String name:List.of("solid","cutoutMipped","cutout"))
            assertTrue(calls.stream().anyMatch(c->c.method.equals("of")&&c.name.equals(name)),name);
        assertFalse(calls.stream().anyMatch(c->c.name.equals("translucent")));
    }
    @Test void realBufferBuilderBulkDispatchAndCapturedFormatChecksArePreserved() throws Exception {
        var writer=calls(COMPILER+"$Build$Writer");
        assertEquals(2,writer.stream().filter(c->c.owner.equals("com/mojang/blaze3d/vertex/BufferBuilder")&&c.name.equals("putBulkData")).count());
        assertTrue(writer.stream().anyMatch(c->c.owner.equals(BRIDGE)&&c.name.equals("beginBlock")));
        assertTrue(writer.stream().anyMatch(c->c.owner.equals(BRIDGE)&&c.name.equals("endBlock")));
        var piece=calls(COMPILER+"$Build$Piece");
        assertTrue(piece.stream().anyMatch(c->c.method.equals("initialize")&&c.name.equals("earthplatform$format")));
        assertTrue(piece.stream().anyMatch(c->c.method.equals("initialize")&&c.name.equals("requireFormat")));
        assertTrue(piece.stream().anyMatch(c->c.method.equals("seal")&&c.name.equals("relocatePositions")));
    }
    @Test void bridgeUsesExactCaptureAndStickyTrackedHaloRatherThanCallerCallbacks() throws Exception {
        var calls=calls(BRIDGE);
        assertTrue(calls.stream().anyMatch(c->c.name.equals("tracking")));
        assertTrue(calls.stream().anyMatch(c->c.name.equals("requireHaloOwner")));
        assertTrue(calls.stream().anyMatch(c->c.owner.endsWith("/RenderPublicationGuard")&&c.name.equals("prepare")));
        assertTrue(calls.stream().anyMatch(c->c.owner.endsWith("/RenderPublicationGuard")&&c.name.equals("isCurrent")));
        assertTrue(calls.stream().anyMatch(c->c.owner.endsWith("SceneNativeShaderContract$Capture")&&c.name.equals("beginBlock")));
        assertFalse(calls.stream().anyMatch(c->c.name.equals("publish")||c.name.equals("presentationSource")));
    }
    @Test void nativeStorageHasAFixedAllocatorFenceAndCompilerNeverAllocatesGpuOwners() throws Exception {
        var storage=calls(COMPILER+"$Build$Storage");
        assertTrue(storage.stream().anyMatch(c->c.method.equals("reserve")&&c.name.equals("reserveVertex")));
        for(String type:List.of(COMPILER,COMPILER+"$Build",COMPILER+"$Build$Piece",COMPILER+"$CompiledPage")) {
            var calls=calls(type);
            assertFalse(calls.stream().anyMatch(c->c.owner.endsWith("/VertexBuffer")||c.owner.contains("NativeVisualGpu")
                    ||c.owner.startsWith("org/lwjgl/opengl/")),type);
        }
    }

    private static MethodNode method(String type,String name) throws IOException {
        try(var bytes=SurvivalNativePageWiringTest.class.getClassLoader().getResourceAsStream(type+".class")) {
            assertNotNull(bytes);var node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();
        }
    }
    private static boolean handlerClearsMember(MethodNode method,LabelNode label) {
        for(AbstractInsnNode at=label;at!=null;at=at.getNext()) {
            if(at instanceof MethodInsnNode call && call.owner.equals(BRIDGE)&&call.name.equals("leaveMember"))return true;
            if(at.getOpcode()==Opcodes.ATHROW||at.getOpcode()==Opcodes.RETURN)return false;
        }
        return false;
    }
    @Test void everyNativeCallbackAndPostcheckIsCoveredByTheMemberFinallyIncludingAirAndErrors() throws Exception {
        var method=method(COMPILER+"$Build","compileBlock");int covered=0;
        for(AbstractInsnNode instruction:method.instructions) {
            if(!(instruction instanceof MethodInsnNode call))continue;
            if(!List.of("enterMember","state","isAir","getBlockModel","getRenderTypes","renderBatched","requireCallbackCurrent").contains(call.name))continue;
            int index=method.instructions.indexOf(instruction);
            assertTrue(method.tryCatchBlocks.stream().anyMatch(t->t.type==null
                    &&method.instructions.indexOf(t.start)<=index&&index<method.instructions.indexOf(t.end)
                    &&handlerClearsMember(method,t.handler)),call.name+" must be covered by the member cleanup handler");
            covered++;
        }
        assertTrue(covered>=8);
        var calls=calls(COMPILER+"$Build");
        assertTrue(calls.stream().filter(c->c.method.equals("compileBlock")&&c.name.equals("leaveMember")).count()>=3,
                "Air return, ordinary return and exceptional handler must each clear the member");
        assertFalse(calls.stream().anyMatch(c->c.method.equals("step")&&c.name.equals("requireCallbackCurrent")),
                "Postcallback validation belongs inside the member lifetime, never after leaveMember");
        var leave=calls(BRIDGE).stream().filter(c->c.method.equals("leaveMember")).toList();
        assertTrue(leave.stream().anyMatch(c->c.name.equals("checkOwner")));
        assertFalse(leave.stream().anyMatch(c->c.name.equals("checkOpen")),"Poisoned callbacks must still clear their scope");
    }
    @Test void optInParityUsesUnchangedSceneMeshActualVboReadbackAndNoProductionPositionNormalizer() throws Exception {
        String probe="io/earthplatform/minecraft/scene/SurvivalNativePageParityProbe";
        var calls=calls(probe);
        assertTrue(calls.stream().anyMatch(c->c.owner.endsWith("/SceneMesh")&&c.name.equals("begin")));
        assertTrue(calls.stream().anyMatch(c->c.owner.endsWith("SceneMesh$Build")&&c.name.equals("finish")));
        assertTrue(calls.stream().anyMatch(c->c.name.equals("glGetBufferSubData")));
        assertTrue(calls.stream().anyMatch(c->c.name.equals("glGetBufferParameteri")));
        assertTrue(calls.stream().anyMatch(c->c.name.equals("verifyMemberScopes")));
        assertFalse(calls.stream().anyMatch(c->c.name.equals("relocatePositions")||c.owner.contains("NativeVisualCapsule")
                ||c.owner.contains("NativeVisualCpuBuilder")||c.name.equals("presentationSource")));
    }
}
