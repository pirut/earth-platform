package io.earthplatform.minecraft.survival.runtime;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import static org.junit.jupiter.api.Assertions.*;

/** Compiled component-shape regression; installed transformation/load order remains a real
 * client prerequisite. Reading these resource bytes does not initialize any mod registrar. */
class InstalledConsumerOriginHookBytecodeTest {
    private static ClassNode read(String name)throws Exception {
        try(var stream=InstalledConsumerOriginHookBytecodeTest.class.getResourceAsStream("/"+name+".class")){
            assertNotNull(stream);var node=new ClassNode();new ClassReader(stream).accept(node,0);return node;
        }
    }
    @Test void eachOfEightHooksDelegatesOnceAndPreservesNativeThrowPath()throws Exception {
        int hooks=0;
        for(String suffix:List.of("Earth","NeoForge")){
            var node=read("io/earthplatform/minecraft/mixin/InstalledConsumer"+suffix+"OriginsMixin");
            for(var method:node.methods){
                if(!method.name.startsWith("earthplatform$consumer"))continue;hooks++;
                int original=0,before=0,after=0,athrow=0;
                for(var instruction:method.instructions){
                    if(instruction.getOpcode()==Opcodes.ATHROW)athrow++;
                    if(instruction instanceof MethodInsnNode call){
                        if(call.owner.equals("com/llamalad7/mixinextras/injector/wrapoperation/Operation")&&call.name.equals("call"))original++;
                        if(call.owner.endsWith("/InstalledConsumerOrigins")&&call.name.equals("before"))before++;
                        if(call.owner.endsWith("/InstalledConsumerOrigins")&&call.name.equals("after"))after++;
                        assertNotEquals("accept",call.name);assertNotEquals("addListener",call.name);
                    }
                }
                assertEquals(1,original);assertEquals(1,before);assertTrue(after>=1);assertTrue(athrow>=1);
                assertTrue(method.tryCatchBlocks.stream().anyMatch(c->"java/lang/Throwable".equals(c.type)));
            }
        }
        assertEquals(8,hooks);
    }
    @Test void earlyCoreHasNoRegistrarOrEventClassDescriptorsOrResolutionCalls()throws Exception {
        for(String suffix:List.of("","$Owner","$Receipt")){
            var node=read("io/earthplatform/minecraft/survival/runtime/InstalledConsumerOrigins"+suffix);
            for(var field:node.fields)assertFalse(field.desc.contains("net/neoforged/"));
            for(var method:node.methods){
                assertFalse(method.desc.contains("net/neoforged/"));
                for(var instruction:method.instructions){
                    if(instruction instanceof TypeInsnNode type)assertFalse(type.desc.startsWith("net/neoforged/"));
                    if(instruction instanceof MethodInsnNode call){
                        assertFalse(call.owner.startsWith("net/neoforged/"));
                        assertFalse(call.name.equals("forName")||call.name.equals("loadClass"));
                    }
                }
            }
        }
    }
}
