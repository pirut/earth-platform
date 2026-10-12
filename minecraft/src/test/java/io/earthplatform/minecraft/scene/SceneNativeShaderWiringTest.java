package io.earthplatform.minecraft.scene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Production bytecode plus exact pinned allocator contracts; these are not transformed-Iris/GUI proof. */
class SceneNativeShaderWiringTest {
    record Call(String method,String owner,String name,String descriptor){}
    record Field(String method,String owner,String name){}
    record Code(List<Call> calls,List<Field> fields){}
    private static Code read(String name)throws IOException{
        var calls=new ArrayList<Call>();var fields=new ArrayList<Field>();
        try(var in=SceneNativeShaderWiringTest.class.getClassLoader().getResourceAsStream(name+".class")){
            assertNotNull(in,name);new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[]e){return new MethodVisitor(Opcodes.ASM9){
                    @Override public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean iface){calls.add(new Call(n,owner,method,desc));}
                    @Override public void visitFieldInsn(int opcode,String owner,String field,String desc){fields.add(new Field(n,owner,field));}
                };}
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }return new Code(calls,fields);
    }
    private static Code mesh(String suffix)throws IOException{return read("io/earthplatform/minecraft/scene/SceneMesh"+suffix);}
    @Test void actualFormatAndStrideAreReadBeforeConstructingTheConsumer()throws Exception{
        var calls=mesh("$CpuPass").calls();
        int format=index(calls,"earthplatform$format"),stride=index(calls,"earthplatform$vertexSize"),configured=index(calls,"configure");
        assertTrue(format>=0&&stride>format&&configured>stride);
        assertTrue(index(calls,"requireFormat")<configured);
        assertTrue(calls.stream().anyMatch(c->c.owner().endsWith("$BoundedStorage")&&c.name().equals("<init>")));
    }
    @Test void nativeAllocatorReserveIsTheOnlyPhysicalCapacityAdmissionAndPrecedesSuperclass()throws Exception{
        var reserve=mesh("$BoundedStorage").calls().stream().filter(c->c.method().equals("reserve")).toList();
        assertEquals(2,reserve.stream().filter(c->c.name().equals("reserve")).count());
        var actualReserves=reserve.stream().filter(c->c.name().equals("reserve")).toList();
        assertTrue(actualReserves.getFirst().owner().endsWith("SceneNativeShaderContract$Budget"));
        assertEquals("com/mojang/blaze3d/vertex/ByteBufferBuilder",actualReserves.getLast().owner());
        assertFalse(mesh("$BoundedStorage").calls().stream().anyMatch(c->c.name().equals("resize")));
        String bufferBuilder="com/mojang/blaze3d/vertex/BufferBuilder";
        var upstream=read(bufferBuilder).calls();
        var nativeReserves=upstream.stream().filter(c->c.owner().equals("com/mojang/blaze3d/vertex/ByteBufferBuilder")&&c.name().equals("reserve")).toList();
        assertEquals(List.of("beginVertex","endLastVertex"),nativeReserves.stream().map(Call::method).toList());
        var node=readNode(bufferBuilder);
        for(String method:List.of("beginVertex","endLastVertex")) {
            var body=node.methods.stream().filter(m->m.name.equals(method)).findFirst().orElseThrow();
            var sites=java.util.Arrays.stream(body.instructions.toArray())
                    .filter(i->i instanceof MethodInsnNode call&&call.owner.equals("com/mojang/blaze3d/vertex/ByteBufferBuilder")&&call.name.equals("reserve"))
                    .map(i->(MethodInsnNode)i).toList();
            assertEquals(1,sites.size());var site=sites.getFirst();
            assertEquals(Opcodes.INVOKEVIRTUAL,site.getOpcode(),"Both paths must dispatch through the bounded storage override");
            assertEquals("(I)J",site.desc);assertFalse(site.itf);
            // The actual builder stride is the argument and the same owned buffer is the receiver.
            assertField(previousInstruction(site),Opcodes.GETFIELD,bufferBuilder,"vertexSize","I");
            assertLoadThis(previousInstruction(previousInstruction(site)));
            assertField(previousInstruction(previousInstruction(previousInstruction(site))),Opcodes.GETFIELD,bufferBuilder,
                    "buffer","Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;");
            assertLoadThis(previousInstruction(previousInstruction(previousInstruction(previousInstruction(site)))));
            if(method.equals("endLastVertex")) {
                var copies=java.util.Arrays.stream(body.instructions.toArray())
                        .filter(i->i instanceof MethodInsnNode call&&call.owner.equals("org/lwjgl/system/MemoryUtil")&&call.name.equals("memCopy"))
                        .map(i->(MethodInsnNode)i).toList();
                assertEquals(1,copies.size());var copy=copies.getFirst();
                assertTrue(body.instructions.indexOf(site)<body.instructions.indexOf(copy),"Reservation must precede the native line-vertex copy");
                var lines=modeField(body,"LINES");var strip=modeField(body,"LINE_STRIP");
                var lineBranch=assertInstanceOf(JumpInsnNode.class,nextInstruction(lines));
                var stripBranch=assertInstanceOf(JumpInsnNode.class,nextInstruction(strip));
                assertEquals(Opcodes.IF_ACMPEQ,lineBranch.getOpcode());assertEquals(Opcodes.IF_ACMPNE,stripBranch.getOpcode());
                assertField(previousInstruction(lines),Opcodes.GETFIELD,bufferBuilder,"mode","Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;");
                assertField(previousInstruction(strip),Opcodes.GETFIELD,bufferBuilder,"mode","Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;");
                assertTrue(body.instructions.indexOf(lineBranch)<body.instructions.indexOf(strip));
                assertTrue(body.instructions.indexOf(stripBranch)<body.instructions.indexOf(lineBranch.label));
                assertTrue(body.instructions.indexOf(lineBranch.label)<body.instructions.indexOf(site));
                assertTrue(body.instructions.indexOf(copy)<body.instructions.indexOf(stripBranch.label),"A non-line mode skips both allocation and copy");
            }
        }
        // Our constructor supplies QUADS, so the guarded line-duplication path is unreachable here.
        var initialize=readNode("io/earthplatform/minecraft/scene/SceneMesh$CpuPass").methods.stream()
                .filter(m->m.name.equals("initialize")).findFirst().orElseThrow();
        var constructors=java.util.Arrays.stream(initialize.instructions.toArray())
                .filter(i->i instanceof MethodInsnNode call&&call.owner.equals(bufferBuilder)&&call.name.equals("<init>"))
                .map(i->(MethodInsnNode)i).toList();
        assertEquals(1,constructors.size());var constructor=constructors.getFirst();
        assertEquals("(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;Lcom/mojang/blaze3d/vertex/VertexFormat;)V",constructor.desc);
        assertField(previousInstruction(constructor),Opcodes.GETSTATIC,"com/mojang/blaze3d/vertex/DefaultVertexFormat","BLOCK","Lcom/mojang/blaze3d/vertex/VertexFormat;");
        assertField(previousInstruction(previousInstruction(constructor)),Opcodes.GETSTATIC,"com/mojang/blaze3d/vertex/VertexFormat$Mode","QUADS","Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;");
    }
    @Test void bothBulkOverloadsPreflightThenPreserveActualNativeBulkDispatch()throws Exception{
        var calls=mesh("$BoundedConsumer").calls();
        assertEquals(2,calls.stream().filter(c->c.method().equals("putBulkData")&&c.name().equals("requireQuad")).count());
        assertEquals(2,calls.stream().filter(c->c.method().equals("putBulkData")&&c.name().equals("beginBulk")).count());
        assertEquals(2,calls.stream().filter(c->c.method().equals("putBulkData")&&c.owner().equals("com/mojang/blaze3d/vertex/VertexConsumer")&&c.name().equals("putBulkData")).count());
        assertTrue(calls.stream().anyMatch(c->c.method().equals("putBulkData")&&c.name().equals("endBulk")));
        assertFalse(calls.stream().anyMatch(c->c.name().equals("memPutInt")||c.name().equals("memCopy")));
    }
    @Test void finalizedDrawStateIsCheckedAgainstActualFormatAndBudgetBytes()throws Exception{
        var build=mesh("$Build");
        assertTrue(build.calls().stream().anyMatch(c->c.method().equals("finish")&&c.owner().endsWith("MeshData$DrawState")&&c.name().equals("format")));
        assertTrue(build.calls().stream().anyMatch(c->c.method().equals("finish")&&c.name().equals("multiplyExact")));
        assertTrue(build.fields().stream().anyMatch(f->f.method().equals("finish")&&f.owner().endsWith("SceneNativeShaderContract$Budget")&&f.name().equals("bytes")));
    }
    @Test void materialContextUsesRealBeginAndEndAroundNativeCallbacks()throws Exception{
        var build=mesh("$Build").calls();
        assertEquals(1,build.stream().filter(c->c.name().equals("beginBlock")).count());
        assertEquals(1,build.stream().filter(c->c.name().equals("endBlock")).count());
        var adapter=read("io/earthplatform/minecraft/scene/SceneNativeShaderContract$IrisAdapter$1").calls();
        assertTrue(adapter.stream().anyMatch(c->c.owner().equals("net/irisshaders/iris/vertices/BlockSensitiveBufferBuilder")&&c.name().equals("beginBlock")&&c.descriptor().equals("(IBBIII)V")));
        assertTrue(adapter.stream().anyMatch(c->c.name().equals("getOrDefault")));
    }
    @Test void defaultEntryPointDoesNotResolveIrisAndSelectedShaderFormatIsVerified()throws Exception{
        var defaults=read("io/earthplatform/minecraft/scene/SceneNativeShaderContract").calls();
        assertFalse(defaults.stream().filter(c->c.method().equals("defaultPath")).anyMatch(c->c.owner().startsWith("net/irisshaders")));
        var root=mesh("").calls();
        assertTrue(root.stream().anyMatch(c->c.method().equals("drawShaderValidated")&&c.name().equals("getVertexFormat")));
        assertTrue(root.stream().anyMatch(c->c.method().equals("presentationUsable")&&c.owner().endsWith("SceneNativeShaderContract$Capture")&&c.name().equals("isCurrent")));
        assertTrue(root.stream().anyMatch(c->c.method().equals("prepareReuse")&&c.name().equals("lightingPolicy")));
    }
    @Test void layerDrawHasDistinctCurrentAndHistoricalFencesAndNoDiagnosticSetup()throws Exception{
        var root=mesh("").calls();
        assertTrue(root.stream().anyMatch(c->c.method().startsWith("lambda$drawLayer$")&&c.name().equals("drawIfCurrent")));
        assertTrue(root.stream().anyMatch(c->c.method().startsWith("lambda$drawPresentedLayer$")&&c.name().equals("drawIfPresented")));
        var shaderCalls=root.stream().filter(c->c.method().equals("drawShaderValidated")||c.method().startsWith("lambda$drawShaderValidated$")).toList();
        for(String forbidden:List.of("setShaderFogStart","setShaderFogEnd","setShaderTexture","glClear","glDepthRange","drawValidated"))
            assertFalse(shaderCalls.stream().anyMatch(c->c.name().equals(forbidden)),forbidden);
        assertTrue(shaderCalls.stream().anyMatch(c->c.name().equals("captureState")));
        assertEquals(1,shaderCalls.stream().filter(c->c.name().equals("drawWithShader")).count());
    }
    private static ClassNode readNode(String name)throws IOException {
        var node=new ClassNode(Opcodes.ASM9);
        try(var in=SceneNativeShaderWiringTest.class.getClassLoader().getResourceAsStream(name+".class")) {
            assertNotNull(in,name);new ClassReader(in).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        return node;
    }
    private static AbstractInsnNode previousInstruction(AbstractInsnNode instruction) {
        do { instruction=instruction.getPrevious(); } while(instruction!=null&&instruction.getOpcode()<0);
        return assertNotNullInstruction(instruction);
    }
    private static AbstractInsnNode nextInstruction(AbstractInsnNode instruction) {
        do { instruction=instruction.getNext(); } while(instruction!=null&&instruction.getOpcode()<0);
        return assertNotNullInstruction(instruction);
    }
    private static AbstractInsnNode assertNotNullInstruction(AbstractInsnNode instruction) {
        assertNotNull(instruction,"Expected a real neighboring bytecode instruction");return instruction;
    }
    private static void assertField(AbstractInsnNode instruction,int opcode,String owner,String name,String descriptor) {
        var field=assertInstanceOf(FieldInsnNode.class,instruction);
        assertEquals(opcode,field.getOpcode());assertEquals(owner,field.owner);assertEquals(name,field.name);assertEquals(descriptor,field.desc);
    }
    private static void assertLoadThis(AbstractInsnNode instruction) {
        var load=assertInstanceOf(VarInsnNode.class,instruction);assertEquals(Opcodes.ALOAD,load.getOpcode());assertEquals(0,load.var);
    }
    private static FieldInsnNode modeField(MethodNode body,String name) {
        var fields=java.util.Arrays.stream(body.instructions.toArray())
                .filter(i->i instanceof FieldInsnNode field&&field.owner.equals("com/mojang/blaze3d/vertex/VertexFormat$Mode")&&field.name.equals(name))
                .map(i->(FieldInsnNode)i).toList();
        assertEquals(1,fields.size());assertEquals(Opcodes.GETSTATIC,fields.getFirst().getOpcode());return fields.getFirst();
    }
    private static int index(List<Call> calls,String name){for(int i=0;i<calls.size();i++)if(calls.get(i).name().equals(name))return i;return -1;}
}
