package io.earthplatform.minecraft.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reads our actual compiled classes without loading Iris/DH. Structural tests are not real-hook or GL execution. */
class SurvivalFrameHookContractTest {
    private static final String WRAP="Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;";
    private static ClassNode node(String name)throws Exception{
        try(var stream=SurvivalFrameHookContractTest.class.getClassLoader().getResourceAsStream(name+".class")){
            assertNotNull(stream,name);var result=new ClassNode();new ClassReader(stream).accept(result,0);return result;
        }
    }
    private static MethodNode method(ClassNode node,String name){return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static List<MethodInsnNode> calls(MethodNode method){var result=new ArrayList<MethodInsnNode>();for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call)result.add(call);return result;}
    private static AnnotationNode annotation(MethodNode method,String descriptor){
        var all=new ArrayList<AnnotationNode>();if(method.visibleAnnotations!=null)all.addAll(method.visibleAnnotations);if(method.invisibleAnnotations!=null)all.addAll(method.invisibleAnnotations);
        return all.stream().filter(a->a.desc.equals(descriptor)).findFirst().orElseThrow();
    }
    private static Object value(AnnotationNode annotation,String key){for(int i=0;i<annotation.values.size();i+=2)if(annotation.values.get(i).equals(key))return annotation.values.get(i+1);return null;}
    @Test void witnessesHaveNoPublicConstructorsAndNoMatrixGetterAuthority()throws Exception{
        for(String nested:List.of("RealFrame","AcceptedFrame")){
            var type=node("io/earthplatform/minecraft/scene/SurvivalSceneFrameJoin$"+nested);
            for(var method:type.methods)if(method.name.equals("<init>"))assertTrue((method.access&Opcodes.ACC_PRIVATE)!=0);
        }
        var access=node("io/earthplatform/minecraft/scene/SurvivalRenderHookAccess");
        assertTrue((access.access&Opcodes.ACC_INTERFACE)!=0);
        for(var getter:access.methods){
            Type result=Type.getReturnType(getter.desc);assertNotEquals(Type.ARRAY,result.getSort());
            assertFalse(result.getClassName().startsWith("org.joml."));
            assertFalse(getter.name.startsWith("set"));assertTrue((getter.access&Opcodes.ACC_ABSTRACT)!=0);
        }
        var join=node("io/earthplatform/minecraft/scene/SurvivalSceneFrameJoin");
        assertEquals("()Lio/earthplatform/minecraft/scene/SurvivalSceneFrameJoin$RealFrame;",method(join,"captureRealMainFrame").desc);
        assertTrue((method(join,"captureRealMainFrame").access&Opcodes.ACC_PRIVATE)!=0);
        assertTrue(calls(method(join,"actualAccess")).stream().anyMatch(c->c.owner.equals("net/minecraft/client/Minecraft")&&c.name.equals("getInstance")));
    }
    @Test void actualFrameWrapperRetiresOnThrowableAndActualAnchorKeepsThePinnedInjection()throws Exception{
        var mixin=node("io/earthplatform/minecraft/mixin/NativeIrisTerrainLayerMixin");
        var frame=method(mixin,"earthplatform$survivalRenderFrame");
        assertEquals(List.of("renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"),value(annotation(frame,WRAP),"method"));
        assertTrue(frame.tryCatchBlocks.stream().anyMatch(t->t.type==null),"Frame cleanup must cover Throwable, not just RETURN");
        assertTrue(calls(frame).stream().anyMatch(c->c.name.equals("endActualFrame")));
        var anchor=method(mixin,"earthplatform$nativeTerrain");
        assertTrue(anchor.tryCatchBlocks.stream().anyMatch(t->t.type==null),"Invocation scalars/aliases must clear in finally");
        assertEquals(1,calls(anchor).stream().filter(c->c.name.equals("dispatch")).count());
        assertEquals(1,calls(anchor).stream().filter(c->c.name.equals("observeActualNativeAnchor")).count());
        for(var field:mixin.fields)if(field.name.startsWith("earthplatform$survival"))assertTrue((field.access&Opcodes.ACC_PRIVATE)!=0,field.name);
    }
    @Test void configWritesUseOnlyTheThreePinnedWrappersAndSynchronizedReaders()throws Exception{
        var specifications=Map.of(
                "SurvivalDhConfigBaseMixin",Map.of("earthplatform$survivalBaseMutation","set(Ljava/lang/Object;)V"),
                "SurvivalDhConfigEntryMixin",Map.of("earthplatform$survivalApiMutation","setApiValue(Ljava/lang/Object;Ljava/lang/String;)V",
                        "earthplatform$survivalVersionMutation","setMcVersionOverrideValue(Ljava/lang/Object;)V"));
        for(var entry:specifications.entrySet()){
            var mixin=node("io/earthplatform/compat/dh/shader/mixin/"+entry.getKey());
            for(var wrapper:entry.getValue().entrySet()){
                var method=method(mixin,wrapper.getKey());assertEquals(List.of(wrapper.getValue()),value(annotation(method,WRAP),"method"));
                assertTrue(method.tryCatchBlocks.stream().anyMatch(t->t.type==null));
                assertEquals(1,calls(method).stream().filter(c->c.owner.equals("com/llamalad7/mixinextras/injector/wrapoperation/Operation")&&c.name.equals("call")).count());
            }
            for(var method:mixin.methods)if(method.name.endsWith("Generation"))assertTrue((method.access&Opcodes.ACC_SYNCHRONIZED)!=0);
            for(var field:mixin.fields)if(field.name.startsWith("earthplatform$survival"))assertTrue((field.access&Opcodes.ACC_PRIVATE)!=0);
        }
    }
    @Test void reloadRevokesBeforeAliasCallbacksAndBothOriginalBranches()throws Exception{
        var mixin=node("io/earthplatform/minecraft/mixin/NativeShaderReloadScopeMixin");
        assertTrue(mixin.interfaces.contains("io/earthplatform/minecraft/scene/SurvivalSceneResources$ReloadAccess"));
        var calls=calls(method(mixin,"earthplatform$scopedTextureAliases"));
        assertEquals("invalidateBeforeMutation",calls.getFirst().name);
        assertEquals("io/earthplatform/minecraft/scene/SurvivalSceneResources",calls.getFirst().owner);
        assertEquals(2,calls.stream().filter(c->c.name.equals("createReload")).count());
        var frame=node("io/earthplatform/minecraft/mixin/NativeIrisTerrainLayerMixin");
        assertEquals("invalidateBeforeMutation",calls(method(frame,"earthplatform$survivalLevelChanged")).getFirst().name);
    }
}
