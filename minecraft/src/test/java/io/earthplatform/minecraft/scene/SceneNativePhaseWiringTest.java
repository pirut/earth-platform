package io.earthplatform.minecraft.scene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

/** Bytecode contracts only: pure phase fault tests and actual GUI are separate evidence. */
class SceneNativePhaseWiringTest {
    record Call(String method,String owner,String name,String descriptor){}
    record Field(String method,String owner,String name,String descriptor){}
    record Info(List<Call> calls,List<Field> fields,List<String> signatures){}
    static Info read(String suffix) throws IOException {
        var calls=new ArrayList<Call>();var fields=new ArrayList<Field>();var signatures=new ArrayList<String>();
        String name="io/earthplatform/minecraft/scene/SceneMesh"+suffix;
        try(var stream=SceneNativePhaseWiringTest.class.getClassLoader().getResourceAsStream(name+".class")){
            assertNotNull(stream);new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions){
                    signatures.add(method+descriptor);return new MethodVisitor(Opcodes.ASM9){
                        @Override public void visitMethodInsn(int opcode,String owner,String called,String desc,boolean iface){calls.add(new Call(method,owner,called,desc));}
                        @Override public void visitFieldInsn(int opcode,String owner,String field,String desc){fields.add(new Field(method,owner,field,desc));}
                    };}
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        return new Info(calls,fields,signatures);
    }
    @Test void oldApiOverloadsDelegateToOptionalProbeWithoutRemovingPresentationRoute() throws Exception {
        var i=read("");String matrices="Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;DDDILio/earthplatform/minecraft/lod/RenderSubmissionProbe;)Z";
        assertTrue(i.signatures.contains("draw("+matrices));
        assertTrue(i.signatures.contains("drawPresented(Lio/earthplatform/minecraft/renderprobe/RenderPublicationGuard$PresentationToken;"+matrices));
        assertEquals(1,i.calls.stream().filter(c->c.method.equals("draw")&&c.name.equals("draw")&&c.descriptor.endsWith(matrices)).count());
        assertEquals(1,i.calls.stream().filter(c->c.method.equals("drawPresented")&&c.name.equals("drawPresented")&&c.descriptor.endsWith(matrices)).count());
    }
    @Test void currentAndHistoricalGuardsRemainDistinctAndBothBodiesReachSharedImplementation() throws Exception {
        var i=read("");assertEquals(1,i.calls.stream().filter(c->c.method.startsWith("lambda$draw$")&&c.name.equals("drawIfCurrent")).count());
        assertEquals(1,i.calls.stream().filter(c->c.method.startsWith("lambda$drawPresented$")&&c.name.equals("drawIfPresented")).count());
        assertEquals(1,i.calls.stream().filter(c->c.method.startsWith("lambda$drawPresented$")&&c.name.equals("isPresented")).count());
        assertEquals(2,i.calls.stream().filter(c->c.name.equals("drawValidated")).count());
        for(String phase:List.of("NATIVE_OWNER_CALL","NATIVE_VALIDATED_BODY")) {
            assertEquals(2,i.fields.stream().filter(f->(f.method.startsWith("lambda$draw$")||f.method.startsWith("lambda$drawPresented$"))&&f.name.equals(phase)).count());
            assertEquals(2,i.fields.stream().filter(f->(f.method.startsWith("lambda$drawLayer$")||f.method.startsWith("lambda$drawPresentedLayer$"))&&f.name.equals(phase)).count());
        }
        assertEquals(1,i.calls.stream().filter(c->c.method.startsWith("lambda$drawLayer$")&&c.name.equals("drawIfCurrent")).count());
        assertEquals(1,i.calls.stream().filter(c->c.method.startsWith("lambda$drawPresentedLayer$")&&c.name.equals("drawIfPresented")).count());
        assertEquals(1,i.calls.stream().filter(c->c.method.startsWith("lambda$drawPresentedLayer$")&&c.name.equals("isPresented")).count());
        assertEquals(2,i.calls.stream().filter(c->c.name.equals("drawShaderValidated")).count());
    }
    @Test void everyLeafFamilyIsExplicitAndNativeSubmissionIsNotReplacedWithRawGl() throws Exception {
        var i=read("");for(String name:List.of("NATIVE_VALIDITY_CHECK","NATIVE_STATE_CAPTURE","NATIVE_RENDER_SETUP","NATIVE_VBO_BIND","NATIVE_DRAW_WITH_SHADER","NATIVE_SHADER_RESTORE","NATIVE_STATE_RESTORE"))
            assertTrue(i.fields.stream().anyMatch(f->f.name.equals(name)),name);
        assertEquals(1,i.calls.stream().filter(c->c.method.startsWith("lambda$drawValidated$")&&c.name.equals("drawWithShader")).count());
        assertEquals(1,i.calls.stream().filter(c->c.method.startsWith("lambda$drawShaderValidated$")&&c.name.equals("drawWithShader")).count());
        assertFalse(i.calls.stream().anyMatch(c->Set.of("glFinish","glFlush","glReadPixels","glDrawElements","glDrawArrays").contains(c.name)));
    }
    @Test void nativeTimingUsesNumericOverloadAndPrecomputedIdentityRatherThanParsingLabels() throws Exception {
        var i=read("$NativeTiming");var start=i.calls.stream().filter(c->c.name.equals("startNativePhase")).toList();assertEquals(1,start.size());
        assertEquals("(Lio/earthplatform/minecraft/lod/RenderSubmissionProbe$Stage;Ljava/lang/String;JJJIJJ)J",start.getFirst().descriptor);
        assertFalse(i.calls.stream().anyMatch(c->Set.of("parseLong","parseInt","split","substring","format").contains(c.name)));
        assertFalse(i.calls.stream().anyMatch(c->c.owner.startsWith("org/lwjgl/")||c.owner.startsWith("com/mojang/blaze3d/")));
    }
    @Test void ownerLabelsAreOnlyBuiltDuringMeshAndPassConstruction() throws Exception {
        for(String suffix:List.of("","$Pass"))for(var c:read(suffix).calls)if(c.name.equals("drawDetail"))assertEquals("<init>",c.method);
        assertTrue(read("$Pass").fields.stream().anyMatch(f->f.name.equals("vertices")));
        assertTrue(read("$Pass").fields.stream().anyMatch(f->f.name.equals("layerId")));
    }
    @Test void shaderSetupAndBindAreSeparateAndCleanupUsesIndependentActions() throws Exception {
        var pass=read("$PassDraw");assertFalse(pass.calls.stream().anyMatch(c->c.method.equals("setup")&&c.name.equals("bind")));
        assertEquals(1,pass.calls.stream().filter(c->c.name.equals("cleanupAll")).count());
        assertEquals(1,pass.calls.stream().filter(c->c.name.equals("restore")&&c.owner.endsWith("$ShaderUniforms")).count());
        assertEquals(1,pass.calls.stream().filter(c->c.name.equals("setSampler")).count());
    }
    @Test void instrumentationDoesNotAddGlCallsToTheSharedRestorationImplementations() throws Exception {
        for(String suffix:List.of("$DrawState","$BufferBindings","$ShaderUniforms")){
            var i=read(suffix);assertFalse(i.calls.stream().anyMatch(c->c.owner.contains("RenderSubmissionProbe")||c.owner.contains("ThreadMXBean")));
            assertFalse(i.calls.stream().anyMatch(c->Set.of("glFinish","glReadPixels","glFenceSync","glClientWaitSync").contains(c.name)));
        }
    }
    @Test void precomputedLabelsRemainBoundedEvenAtSignedCoordinateExtremes() throws Exception {
        var method=SceneMesh.class.getDeclaredMethod("drawDetail",io.earthplatform.core.CubePos.class,String.class,int.class,boolean.class);method.setAccessible(true);
        for(int x:new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE})for(int y:new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE})for(int z:new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE})
            for(String layer:List.of("all","solid","cutout_mipped","cutout"))for(boolean first:List.of(false,true)){
                String label=(String)method.invoke(null,new io.earthplatform.core.CubePos(x,y,z),layer,SceneMesh.MAX_VERTICES,first);
                assertTrue(label.length()<=160,label);assertTrue(label.contains("layer="+layer));assertTrue(label.contains("firstUse="+first));
            }
    }

}
