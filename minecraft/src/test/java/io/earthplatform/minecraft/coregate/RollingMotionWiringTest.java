package io.earthplatform.minecraft.coregate;

import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pinned bytecode seams and host ordering; this does not claim transformed runtime acceptance. */
class RollingMotionWiringTest {
    static ClassNode cls(String name)throws Exception{try(var in=RollingMotionWiringTest.class.getResourceAsStream("/"+name+".class")){assertNotNull(in);var n=new ClassNode();new ClassReader(in).accept(n,0);return n;}}
    static ClassNode engine(String name)throws Exception{try(var jar=new ZipFile(System.getProperty("earthplatform.test.minecraftJar"));var in=jar.getInputStream(jar.getEntry(name+".class"))){var n=new ClassNode();new ClassReader(in).accept(n,0);return n;}}
    static Object value(AnnotationNode a,String key){for(int i=0;i<a.values.size();i+=2)if(a.values.get(i).equals(key))return a.values.get(i+1);return null;}
    static List<MethodInsnNode> calls(MethodNode m){var r=new ArrayList<MethodInsnNode>();for(var i:m.instructions)if(i instanceof MethodInsnNode c)r.add(c);return r;}
    static MethodNode method(ClassNode n,String name){return n.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    @Test void everyMovementInjectionHasExactlyOnePinnedTargetAndUniqueThreadHandoff()throws Exception{
        for(var e:Map.of("CoreCollisionServerMoveMixin","net/minecraft/server/network/ServerGamePacketListenerImpl","CoreCollisionMotionMixin","net/minecraft/world/entity/Entity").entrySet()){
            var mixin=cls("io/earthplatform/minecraft/mixin/"+e.getKey());var target=engine(e.getValue());int count=0;
            for(var m:mixin.methods)if(m.visibleAnnotations!=null)for(var a:m.visibleAnnotations)if(a.desc.endsWith("/Inject;")){
                for(var sig:(List<?>)value(a,"method")){var matches=target.methods.stream().filter(x->(x.name+x.desc).equals(sig)).toList();assertEquals(1,matches.size(),String.valueOf(sig));
                    var at=(AnnotationNode)((List<?>)value(a,"at")).getFirst();if("INVOKE".equals(value(at,"value"))){String call=(String)value(at,"target");assertEquals(1,calls(matches.getFirst()).stream().filter(x->("L"+x.owner+";"+x.name+x.desc).equals(call)).count());assertEquals("AFTER",((String[])value(at,"shift"))[1]);}count++;
                }
            }
            assertEquals(e.getKey().equals("CoreCollisionMotionMixin")?6:8,count);
        }
    }
    @Test void allModeGatePrecedesLegacySpectatorExemptionAndVanillaMovement()throws Exception{
        var listener=cls("io/earthplatform/minecraft/mixin/CoreCollisionServerMoveMixin");var packet=method(listener,"earthplatform$packetMotion");var calls=calls(packet);
        int gate=calls.stream().filter(x->x.name.equals("admitMotion")).mapToInt(packet.instructions::indexOf).findFirst().orElseThrow();
        int spectator=calls.stream().filter(x->x.name.equals("isSpectator")).mapToInt(packet.instructions::indexOf).findFirst().orElseThrow();assertTrue(gate<spectator);
        assertTrue(calls.stream().anyMatch(x->x.name.equals("correctionOutstanding")));assertTrue(calls.stream().anyMatch(x->x.name.equals("correctMotion")));
        var entity=cls("io/earthplatform/minecraft/mixin/CoreCollisionMotionMixin");assertTrue(calls(method(entity,"earthplatform$rawPosition")).stream().anyMatch(x->x.name.equals("admitPosition")));
        assertTrue(calls(method(entity,"earthplatform$body")).stream().anyMatch(x->x.name.equals("admitBody")));
    }

}
