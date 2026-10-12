package io.earthplatform.minecraft.coregate;

import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pinned class-file checks only: proves exact HEAD targets/registration, not a running GUI mutation. */
class NativeDistanceMutationHookTest {
    private static ClassNode read(String name)throws Exception{try(var in=NativeDistanceMutationHookTest.class.getClassLoader().getResourceAsStream(name+".class")){assertNotNull(in,name);var node=new ClassNode();new ClassReader(in).accept(node,0);return node;}}
    private static Object value(AnnotationNode node,String name){for(int i=0;i<node.values.size();i+=2)if(node.values.get(i).equals(name))return node.values.get(i+1);return null;}
    private static MethodNode injection(ClassNode node,String target){
        var method=node.methods.stream().filter(m->m.visibleAnnotations!=null&&m.visibleAnnotations.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))).findFirst().orElseThrow();
        var annotation=method.visibleAnnotations.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElseThrow();
        assertEquals(List.of(target),value(annotation,"method"));assertEquals(1,value(annotation,"require"));assertEquals(1,value(annotation,"allow"));
        @SuppressWarnings("unchecked")var ats=(List<AnnotationNode>)value(annotation,"at");assertEquals(1,ats.size());assertEquals("HEAD",value(ats.getFirst(),"value"));return method;
    }
    @Test void exactOptionSetterExistsAndHeadHookChecksInstanceAndOldValue()throws Exception{
        var options=read("net/minecraft/client/OptionInstance");assertEquals(1,options.methods.stream().filter(m->m.name.equals("set")&&m.desc.equals("(Ljava/lang/Object;)V")).count());
        var hook=injection(read("io/earthplatform/minecraft/mixin/NativeRenderDistanceOptionMixin"),"set(Ljava/lang/Object;)V");
        var calls=new ArrayList<String>();int identities=0;for(var ins:hook.instructions){if(ins instanceof MethodInsnNode m)calls.add(m.owner+"."+m.name);if(ins.getOpcode()==Opcodes.IF_ACMPNE||ins.getOpcode()==Opcodes.IF_ACMPEQ)identities++;}
        assertTrue(identities>=1);assertTrue(calls.contains("net/minecraft/client/Options.renderDistance"));assertTrue(calls.contains("java/util/Objects.equals"));
        assertEquals(1,calls.stream().filter(c->c.equals("io/earthplatform/minecraft/coregate/RollingGateClient.beforeNativeDistanceMutation")).count());
    }
    @Test void exactServerSetterAndActualFieldAccessorExist()throws Exception{
        var options=read("net/minecraft/client/Options");assertEquals(1,options.methods.stream().filter(m->m.name.equals("setServerRenderDistance")&&m.desc.equals("(I)V")).count());
        var hook=read("io/earthplatform/minecraft/mixin/NativeServerRenderDistanceMixin");injection(hook,"setServerRenderDistance(I)V");
        assertTrue(hook.interfaces.contains("io/earthplatform/minecraft/coregate/NativeDistanceOptionsAccess"));
        var getter=hook.methods.stream().filter(m->m.name.equals("serverRenderDistance")&&m.desc.equals("()I")).findFirst().orElseThrow();
        assertTrue(java.util.stream.StreamSupport.stream(getter.instructions.spliterator(),false).anyMatch(i->i instanceof FieldInsnNode f&&f.name.equals("serverRenderDistance")&&f.getOpcode()==Opcodes.GETFIELD));
    }
    @Test void bothNarrowHooksAreRequiredClientMixins()throws Exception{
        var project=Path.of(System.getProperty("earthplatform.test.projectDirectory","minecraft"));
        var json=JsonParser.parseString(Files.readString(project.resolve("src/main/resources/earthplatform.mixins.json"))).getAsJsonObject();
        assertTrue(json.get("required").getAsBoolean());var clients=new HashSet<String>();json.getAsJsonArray("client").forEach(v->clients.add(v.getAsString()));
        assertTrue(clients.contains("NativeRenderDistanceOptionMixin"));assertTrue(clients.contains("NativeServerRenderDistanceMixin"));assertEquals(1,json.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
    }
}
