package io.earthplatform.minecraft.coregate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Production controller contracts: inspect compiled wiring without constructing a server Level. */
class RollingFarServerContractTest {
    private static final String BASE="io/earthplatform/minecraft/coregate/";
    private static final String COMMANDS=BASE+"RollingGateCommands",SESSION=COMMANDS+"$Session";
    private static final String FAR=BASE+"RollingFarPublication",HANDSHAKE=BASE+"RollingPublicationHandshake";
    private static final String READY="io/earthplatform/minecraft/enginebridge/RollingCubeActor$StageReady";
    private static final String ACTOR="io/earthplatform/minecraft/enginebridge/RollingCubeActor",METADATA=ACTOR+"$StageMetadata";

    @Test void serverReadyDerivesPairProofFromValidatedExactStageReadyBeforeHandshake() throws IOException {
        var m=method(read(COMMANDS),"publishReady");
        before(m,call(COMMANDS,"manifestMatches"),call(FAR,"center"));
        before(m,call(READY,"candidateCenter"),call(FAR,"center"));
        before(m,call(FAR,"center"),call(FAR,"digest"));
        before(m,call(FAR,"digest"),call(HANDSHAKE,"ready"));
        assertTrue(has(m,n->n instanceof MethodInsnNode c&&c.owner.equals(FAR)&&c.name.equals("digest")
                &&c.desc.equals("(L"+BASE+"RollingSessionDescriptor;L"+READY+";)Ljava/lang/String;")));
        assertTrue(has(m,field(SESSION,"pendingFarCenter",Opcodes.PUTFIELD)));
        assertFalse(has(m,call(BASE+"RollingGateWire","manifestDigest")),"A native-only digest must never arm the handshake");
        assertFalse(has(m,field(SESSION,"desiredFarCenter",Opcodes.GETFIELD)),"Live goals must not select this publication's center");
        assertFalse(has(m,field(SESSION,"latestGoal",Opcodes.GETFIELD)));
    }

    @Test void everyPublicationAdvancingPathRechecksIndependentAuthoritativePair() throws IOException {
        var c=read(COMMANDS);var control=method(c,"control");var grant=method(c,"tryGrant");var finish=method(c,"finishPublication");
        before(control,call(COMMANDS,"authoritativePairMatches"),call(HANDSHAKE,"prepared"));
        before(control,call(COMMANDS,"authoritativePairMatches"),call(HANDSHAKE,"pairReady"));
        before(grant,call(COMMANDS,"authoritativePairMatches"),call(BASE+"CoreCollisionBinding","fence"));
        before(grant,call(BASE+"CoreCollisionBinding","fence"),call(HANDSHAKE,"grant"));
        before(finish,call(COMMANDS,"authoritativePairMatches"),call(BASE+"CoreCollisionBinding","publish"));
        // Preserve the original actor-authorization-before-collision assertions across the
        // extracted production method. Independent CFG tests also prohibit a branch bypass;
        // real-actor integration tests execute the arena branch rather than just finding calls.
        before(finish,call(COMMANDS,"authoritativePairMatches"),call(COMMANDS,"authorizePublication"));
        before(finish,call(COMMANDS,"authorizePublication"),call(BASE+"CoreCollisionBinding","publish"));
        var authorization=method(c,"authorizePublication");
        before(authorization,call(HANDSHAKE,"state"),call(ACTOR,"authorizePinRetirement"));
        before(authorization,call(HANDSHAKE,"state"),call(ACTOR,"retainPinWithoutReplacement"));
        assertTrue(has(authorization,call(ACTOR,"authorizePinRetirement")));
        assertTrue(has(authorization,call(ACTOR,"retainPinWithoutReplacement")));
        var verify=method(c,"authoritativePairMatches");
        for(var call:List.of(call(COMMANDS,"manifestMatches"),call(READY,"candidateCenter"),call(FAR,"center"),call(FAR,"digest"),call(HANDSHAKE,"digest")))
            assertTrue(has(verify,call));
        assertTrue(has(verify,field(SESSION,"pendingManifest",Opcodes.GETFIELD)));
        assertTrue(has(verify,field(SESSION,"pendingFarCenter",Opcodes.GETFIELD)));
        assertFalse(has(verify,field(SESSION,"desiredFarCenter",Opcodes.GETFIELD)));
        assertFalse(has(verify,field(SESSION,"latestGoal",Opcodes.GETFIELD)));
    }

    @Test void publishedCenterChangesOnlyAfterCandidateCollisionInstallAndBeforeCommitReceipt() throws IOException {
        var c=read(COMMANDS);var finish=method(c,"finishPublication");
        before(finish,call(BASE+"CoreCollisionBinding","publish"),field(SESSION,"publishedFarCenter",Opcodes.PUTFIELD));
        before(finish,field(SESSION,"publishedFarCenter",Opcodes.PUTFIELD),call(HANDSHAKE,"committed"));
        before(finish,call(HANDSHAKE,"committed"),call(COMMANDS,"sendPair"));
        assertTrue(has(finish,field(SESSION,"pendingFarCenter",Opcodes.GETFIELD)));
        assertTrue(has(finish,field(SESSION,"pendingFarCenter",Opcodes.PUTFIELD)),"Completed pending center must clear");
        assertEquals(1,c.methods.stream().filter(m->has(m,field(SESSION,"publishedFarCenter",Opcodes.PUTFIELD))).count());
    }

    @Test void editAndRecenterBarriersHoldExactCandidateBeforeActorCommitAndNeverAcceptFreeWireCenters() throws IOException {
        var c=read(COMMANDS);var tick=method(c,"tick");var ray=method(c,"pumpRay");var events=method(c,"handleEvent");
        assertTrue(has(tick,call(HANDSHAKE,"pending")),"Both edits and recentering must hold actor goals throughout publication");
        assertTrue(has(tick,field(SESSION,"committedCenter",Opcodes.GETFIELD)));
        String motion=BASE+"RollingMotionGuard",descriptor=BASE+"RollingSessionDescriptor",plan="io/earthplatform/minecraft/enginebridge/RollingCubePlan";
        int actorRequest=index(tick,call(ACTOR,"requestCenter"));
        requireBoolean(tick,call(HANDSHAKE,"pending"),actorRequest,false);
        requireNull(tick,call(motion,"reservation"),actorRequest);
        requireBoolean(tick,call(motion,"reserve"),actorRequest,true);
        before(tick,call(plan,"nextStep"),call(descriptor,"requireAllowedTransition"));
        before(tick,call(descriptor,"requireAllowedTransition"),call(motion,"reserve"));
        assertFalse(reachable(tick,0,actorRequest,Set.of(index(tick,call(descriptor,"requireAllowedTransition")))),"No actor goal may bypass the bounded transition check");
        var stepStore=assertInstanceOf(VarInsnNode.class,nextOpcode(tick.instructions.get(index(tick,call(plan,"nextStep")))));
        assertEquals(Opcodes.ASTORE,stepStore.getOpcode());
        var requested=assertInstanceOf(VarInsnNode.class,previousOpcode(tick.instructions.get(actorRequest)));
        assertEquals(Opcodes.ALOAD,requested.getOpcode());assertEquals(stepStore.var,requested.var,"Actor receives only the exact reserved adjacent next step");
        assertFalse(has(tick,call(METADATA,"candidateCenter")),"Tick must not resubmit an in-flight stage goal");
        assertFalse(has(tick,call(METADATA,"currentCenter")),"Holding the old center would race a reverse stage after commit");
        assertFalse(has(events,call(ACTOR,"requestCenter")),"Metadata validates a prior reservation instead of minting another goal");
        before(events,call(motion,"requireMetadata"),call(descriptor,"requireAllowedTransition"));
        before(events,call(descriptor,"requireAllowedTransition"),call(HANDSHAKE,"begin"));
        before(events,call(motion,"requireMetadata"),call(COMMANDS,"send"));
        before(ray,field(SESSION,"committedCenter",Opcodes.GETFIELD),call(ACTOR,"tryEdit"));
        before(ray,call(ACTOR,"requestCenter"),call(ACTOR,"tryEdit"));
        assertTrue(has(events,field(SESSION,"committedCenter",Opcodes.GETFIELD)));
        assertFalse(read(BASE+"RollingGateControlPayload").fields.stream().anyMatch(f->f.name.toLowerCase().contains("center")),"The client cannot submit a free far coordinate");
        var pure=read(FAR);
        assertFalse(pure.methods.stream().anyMatch(m->has(m,n->n instanceof MethodInsnNode call&&(call.owner.contains("Camera")||call.owner.startsWith("net/minecraft/client/")))));
    }

    @Test void entryCameraHelpersFeedTeleportAtUnchangedDescriptorCoordinates() throws IOException {
        var matches=read(COMMANDS).methods.stream().filter(m->m.name.equals("open")
                &&m.desc.endsWith("L"+COMMANDS+"$PendingDistance;)I")).toList();
        assertEquals(1,matches.size(),"Exact owner-admission overload required");var open=matches.getFirst();
        before(open,call(BASE+"RollingSessionDescriptor","entry"),call(COMMANDS,"entryYaw"));
        before(open,call(COMMANDS,"entryYaw"),call(COMMANDS,"entryPitch"));
        before(open,call(COMMANDS,"entryPitch"),call("net/minecraft/server/level/ServerPlayer","teleportTo"));
        var instructions=code(open).stream().filter(n->n.getOpcode()>=0).toList();
        int teleport=-1;
        for(int i=0;i<instructions.size();i++)if(call("net/minecraft/server/level/ServerPlayer","teleportTo").test(instructions.get(i))){teleport=i;break;}
        assertTrue(teleport>=4);
        assertTrue(call(COMMANDS,"entryPitch").test(instructions.get(teleport-1)),"Pitch helper must feed the actual final teleport argument");
        assertTrue(call(COMMANDS,"entryYaw").test(instructions.get(teleport-3)),"Yaw helper must feed the actual penultimate teleport argument");
        assertEquals(3,code(open).stream().filter(call(BASE+"RollingSessionDescriptor","entry")).count(),"All three spawn coordinates remain descriptor-owned");
    }

    @Test void statusAndPhysicsExposeBoundedFarPolicyAndAllThreeCenters() throws IOException {
        var c=read(COMMANDS);
        for(String name:List.of("status","reportPhysics")) {
            var m=method(c,name);
            for(String field:List.of("desiredFarCenter","pendingFarCenter","publishedFarCenter"))
                assertTrue(has(m,field(SESSION,field,Opcodes.GETFIELD)),name+" lacks "+field);
        }
        for(String name:List.of("status","reportPhysics")) {
            var m=method(c,name);
            assertTrue(has(m,call(BASE+"RollingSessionDescriptor","lodLayout")),name+" must read immutable descriptor layout");
            for(String accessor:List.of("radiusBlocks","snapBlocks","centerAlignmentBlocks","id"))
                assertTrue(has(m,call("io/earthplatform/minecraft/lod/DhLodLayout",accessor)),name+" lacks actual "+accessor);
        }
        var physics=method(c,"reportPhysics");
        for(String key:List.of("farPolicyVersion","farRadius","farSnap","farLayout","farLayoutIdentity","farCenterAlignment","desiredFarCenter","pendingFarCenter","publishedFarCenter","pairDigest","publishedPairDigest"))
            assertTrue(has(physics,n->n instanceof LdcInsnNode ldc&&key.equals(ldc.cst)),"Missing physics key: "+key);
    }

    private static ClassNode read(String name) throws IOException {
        try(var input=RollingFarServerContractTest.class.getClassLoader().getResourceAsStream(name+".class")) {
            assertNotNull(input,name);var node=new ClassNode();new ClassReader(input).accept(node,0);return node;
        }
    }
    private static MethodNode method(ClassNode c,String name) {return c.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static List<AbstractInsnNode> code(MethodNode m) {var result=new ArrayList<AbstractInsnNode>();m.instructions.iterator().forEachRemaining(result::add);return result;}
    private static boolean has(MethodNode m,Predicate<AbstractInsnNode> p) {return code(m).stream().anyMatch(p);}
    private static Predicate<AbstractInsnNode> call(String owner,String name) {return n->n instanceof MethodInsnNode m&&m.owner.equals(owner)&&m.name.equals(name);}
    private static Predicate<AbstractInsnNode> field(String owner,String name,int opcode) {return n->n instanceof FieldInsnNode f&&f.owner.equals(owner)&&f.name.equals(name)&&f.getOpcode()==opcode;}
    private static int index(MethodNode m,Predicate<AbstractInsnNode> p){var nodes=code(m);for(int i=0;i<nodes.size();i++)if(p.test(nodes.get(i)))return i;fail("Missing production call in "+m.name);return -1;}
    private static AbstractInsnNode nextOpcode(AbstractInsnNode n){do{n=n.getNext();assertNotNull(n);}while(n.getOpcode()<0);return n;}
    private static AbstractInsnNode previousOpcode(AbstractInsnNode n){do{n=n.getPrevious();assertNotNull(n);}while(n.getOpcode()<0);return n;}
    private static void requireBoolean(MethodNode method,Predicate<AbstractInsnNode> check,int target,boolean required){
        int condition=index(method,check);assertFalse(reachable(method,0,target,Set.of(condition)),"Required owner guard cannot be skipped");
        var branch=assertInstanceOf(JumpInsnNode.class,nextOpcode(method.instructions.get(condition)));int op=branch.getOpcode();
        assertTrue(op==Opcodes.IFEQ||op==Opcodes.IFNE);int jump=method.instructions.indexOf(branch.label),fall=method.instructions.indexOf(branch)+1;
        int whenTrue=op==Opcodes.IFNE?jump:fall,whenFalse=op==Opcodes.IFEQ?jump:fall;
        assertTrue(reachable(method,required?whenTrue:whenFalse,target,Set.of()));assertFalse(reachable(method,required?whenFalse:whenTrue,target,Set.of()),"Refused owner guard cannot submit a goal");
    }
    private static void requireNull(MethodNode method,Predicate<AbstractInsnNode> check,int target){
        int condition=index(method,check);assertFalse(reachable(method,0,target,Set.of(condition)),"Reservation check cannot be skipped");
        var branch=assertInstanceOf(JumpInsnNode.class,nextOpcode(method.instructions.get(condition)));int op=branch.getOpcode();
        assertTrue(op==Opcodes.IFNULL||op==Opcodes.IFNONNULL);int jump=method.instructions.indexOf(branch.label),fall=method.instructions.indexOf(branch)+1;
        int whenNull=op==Opcodes.IFNULL?jump:fall,whenPresent=op==Opcodes.IFNULL?fall:jump;
        assertTrue(reachable(method,whenNull,target,Set.of()));assertFalse(reachable(method,whenPresent,target,Set.of()),"Existing reservation must block another actor goal");
    }
    private static boolean reachable(MethodNode method,int start,int target,Set<Integer> blocked){
        var nodes=code(method);var seen=new HashSet<Integer>();var work=new ArrayDeque<Integer>();work.add(start);
        while(!work.isEmpty()){int i=work.removeFirst();if(i<0||i>=nodes.size()||blocked.contains(i)||!seen.add(i))continue;if(i==target)return true;
            var node=nodes.get(i);int op=node.getOpcode();if(op==Opcodes.ATHROW||op>=Opcodes.IRETURN&&op<=Opcodes.RETURN)continue;
            if(node instanceof JumpInsnNode j){work.add(method.instructions.indexOf(j.label));if(op!=Opcodes.GOTO)work.add(i+1);}
            else if(node instanceof TableSwitchInsnNode t){work.add(method.instructions.indexOf(t.dflt));for(var label:t.labels)work.add(method.instructions.indexOf(label));}
            else if(node instanceof LookupSwitchInsnNode t){work.add(method.instructions.indexOf(t.dflt));for(var label:t.labels)work.add(method.instructions.indexOf(label));}
            else work.add(i+1);
        }return false;
    }
    private static void before(MethodNode m,Predicate<AbstractInsnNode> first,Predicate<AbstractInsnNode> second) {
        var code=code(m);int a=-1,b=-1;
        for(int i=0;i<code.size();i++){if(a<0&&first.test(code.get(i)))a=i;if(b<0&&second.test(code.get(i)))b=i;}
        assertTrue(a>=0&&b>a,m.name+": expected calls missing or in wrong order "+a+" / "+b);
    }
}
