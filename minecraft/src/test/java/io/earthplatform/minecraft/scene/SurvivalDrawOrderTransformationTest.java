package io.earthplatform.minecraft.scene;

import io.earthplatform.minecraft.startupfixture.SurvivalDrawOrderHooks;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import static org.junit.jupiter.api.Assertions.*;

/** Actual MixinExtras transformation and execution on a synthetic target; DH/Sodium are mocks.
 * This does not qualify the installed LevelRenderer or actual simultaneous GL rendering. */
class SurvivalDrawOrderTransformationTest {
    private static final String TARGET="io.earthplatform.minecraft.startupfixture.SurvivalDrawOrderTarget";
    @Test void realCallsiteWrapperScopesMockDhHeadThenNativeAndRetiresOnBothExits()throws Exception{
        assertEquals("legacy-isolated",System.getProperty("earthplatform.test.partition"));
        var environment=MixinEnvironment.getCurrentEnvironment();assertEquals("0.8.7",environment.getVersion());
        var transformer=assertInstanceOf(IMixinTransformer.class,environment.getActiveTransformer());
        byte[] original;try(var input=getClass().getClassLoader().getResourceAsStream(TARGET.replace('.','/')+".class")){assertNotNull(input);original=input.readAllBytes();}
        byte[] transformed=transformer.transformClass(environment,TARGET,original);assertFalse(Arrays.equals(original,transformed));
        var node=new ClassNode();new ClassReader(transformed).accept(node,0);
        var layer=node.methods.stream().filter(m->m.name.equals("renderSectionLayer")).findFirst().orElseThrow();
        var calls=Arrays.stream(layer.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).map(m->m.name).toList();
        assertTrue(calls.stream().anyMatch(n->n.contains("fixture$dhHead")));assertTrue(calls.stream().anyMatch(n->n.contains("fixture$nativeAnchor")));
        assertTrue(node.methods.stream().anyMatch(m->m.name.contains("fixture$mainOrigin")&&!m.tryCatchBlocks.isEmpty()));
        assertTrue(node.methods.stream().anyMatch(m->m.name.contains("fixture$wholeFrame")&&!m.tryCatchBlocks.isEmpty()));
        Class<?> executable=new FixtureLoader(getClass().getClassLoader()).define(transformed);
        Object receiver=executable.getConstructor().newInstance();var scope=assertInstanceOf(SurvivalDrawOrderHooks.Scope.class,receiver);
        var render=executable.getMethod("renderLevel",Object.class,double.class,double.class,double.class,Object.class,Object.class);
        var direct=executable.getMethod("renderSectionLayer",Object.class,double.class,double.class,double.class,Object.class,Object.class);
        Object layerArg=new Object(),view=new Object(),projection=new Object();
        try{
            SurvivalDrawOrderHooks.RECEIVER=receiver;SurvivalDrawOrderHooks.LAYER=layerArg;SurvivalDrawOrderHooks.VIEW=view;SurvivalDrawOrderHooks.PROJECTION=projection;
            SurvivalDrawOrderHooks.X=-123.5;SurvivalDrawOrderHooks.Y=-0.0;SurvivalDrawOrderHooks.Z=987.25;
            Object[] args={layerArg,-123.5,-0.0,987.25,view,projection};
            for(int frame=0;frame<2;frame++){
                render.invoke(receiver,args);
                assertEquals(List.of("frame-begin","prepare","dh-head","managed-enter","sodium-draw","managed-exit","native-anchor","platform-hook","retire","level-tail","frame-retire"),List.copyOf(SurvivalDrawOrderHooks.EVENTS));
                assertNull(scope.fixture$frame());assertNull(SurvivalDrawOrderHooks.FRAME);assertNull(scope.fixture$preparation());assertNull(SurvivalDrawOrderHooks.TOKEN);SurvivalDrawOrderHooks.EVENTS.clear();
            }
            SurvivalDrawOrderHooks.FAIL_DRAW=true;
            var failed=assertThrows(InvocationTargetException.class,()->render.invoke(receiver,args));
            assertEquals("Synthetic draw failure",assertInstanceOf(IllegalStateException.class,failed.getCause()).getMessage());
            assertEquals(List.of("frame-begin","prepare","dh-head","managed-enter","sodium-draw","managed-exit","retire","frame-retire"),List.copyOf(SurvivalDrawOrderHooks.EVENTS));
            assertNull(scope.fixture$frame());assertNull(SurvivalDrawOrderHooks.FRAME);assertNull(scope.fixture$preparation());assertNull(SurvivalDrawOrderHooks.TOKEN);
            SurvivalDrawOrderHooks.FAIL_DRAW=false;SurvivalDrawOrderHooks.EVENTS.clear();
            var naked=assertThrows(InvocationTargetException.class,()->direct.invoke(receiver,args));
            assertEquals("No same-invocation fixture preparation",assertInstanceOf(IllegalStateException.class,naked.getCause()).getMessage());
            assertTrue(SurvivalDrawOrderHooks.EVENTS.isEmpty());assertNull(scope.fixture$frame());assertNull(SurvivalDrawOrderHooks.FRAME);assertNull(scope.fixture$preparation());
        }finally{SurvivalDrawOrderHooks.clear();}
    }
    private static final class FixtureLoader extends ClassLoader {
        FixtureLoader(ClassLoader parent){super(parent);}
        Class<?> define(byte[] bytes){return defineClass(null,bytes,0,bytes.length);}
    }
}
