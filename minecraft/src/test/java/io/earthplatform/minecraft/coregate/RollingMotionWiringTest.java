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
    @Test void reserveAndCommitHoldPrecedeActorRequestAndCollisionFence()throws Exception{
        var commands=cls("io/earthplatform/minecraft/coregate/RollingGateCommands");var tick=method(commands,"tick");var calls=calls(tick);
        int reserve=calls.stream().filter(x->x.name.equals("reserve")).mapToInt(tick.instructions::indexOf).findFirst().orElseThrow();
        int request=calls.stream().filter(x->x.name.equals("requestCenter")).mapToInt(tick.instructions::indexOf).findFirst().orElseThrow();assertTrue(reserve<request);
        var grant=method(commands,"tryGrant");int hold=calls(grant).stream().filter(x->x.name.equals("beginCommit")).mapToInt(grant.instructions::indexOf).findFirst().orElseThrow();
        int fence=calls(grant).stream().filter(x->x.name.equals("fence")).mapToInt(grant.instructions::indexOf).findFirst().orElseThrow();assertTrue(hold<fence);
        var event=method(commands,"handleEvent");assertTrue(calls(event).stream().anyMatch(x->x.name.equals("requireMetadata")));assertFalse(calls(event).stream().anyMatch(x->x.name.equals("requestCenter")));
    }
    @Test void exactTeleportAcknowledgementAndRawSweptCollisionReadsRemainBound()throws Exception{
        var mixin=cls("io/earthplatform/minecraft/mixin/CoreCollisionServerMoveMixin");var ack=method(mixin,"earthplatform$correctionAck");
        assertTrue(calls(ack).stream().anyMatch(x->x.name.equals("getId")));assertTrue(calls(ack).stream().anyMatch(x->x.name.equals("correctionAcknowledged")));
        var fields=new HashSet<String>();for(var i:ack.instructions)if(i instanceof FieldInsnNode f)fields.add(f.name);assertTrue(fields.containsAll(Set.of("awaitingTeleport","awaitingPositionFromClient")));
        var binding=cls("io/earthplatform/minecraft/coregate/CoreCollisionBinding");assertTrue(calls(method(binding,"admitPosition")).stream().anyMatch(x->x.name.equals("knownBox")));
        assertFalse(calls(method(binding,"fence")).stream().anyMatch(x->x.owner.endsWith("RollingMotionGuard")),"Collision invalidation cannot reopen a body hold");
    }
    @Test void pairAndActorCommitWaitForOutstandingCorrectionInsteadOfDroppingTheReceipt()throws Exception{
        var commands=cls("io/earthplatform/minecraft/coregate/RollingGateCommands");var commit=method(commands,"tryCommitReady");
        var c=calls(commit);int pending=c.stream().filter(x->x.name.equals("correctionPending")).mapToInt(commit.instructions::indexOf).findFirst().orElseThrow();
        int finish=c.stream().filter(x->x.name.equals("finishPublication")).mapToInt(commit.instructions::indexOf).findFirst().orElseThrow();
        int actor=c.stream().filter(x->x.name.equals("commitPrepared")).mapToInt(commit.instructions::indexOf).findFirst().orElseThrow();
        assertTrue(pending<finish&&pending<actor);assertTrue(calls(method(commands,"handleEvent")).stream().anyMatch(x->x.name.equals("tryCommitReady")));
        assertTrue(calls(method(commands,"tick")).stream().anyMatch(x->x.name.equals("tryCommitReady")));
    }

}
