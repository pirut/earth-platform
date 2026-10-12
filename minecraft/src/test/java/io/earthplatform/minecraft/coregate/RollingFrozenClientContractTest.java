package io.earthplatform.minecraft.coregate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Controller bytecode review: production wiring, not a substitute for native GL execution. */
class RollingFrozenClientContractTest {
    private static final String BASE="io/earthplatform/minecraft/", CLIENT=BASE+"coregate/RollingGateClient";
    private static final String SESSION=CLIENT+"$Session", PRESENTATION=BASE+"coregate/RollingEditPresentation";
    private static final String REPLICA=BASE+"coregate/RollingGateReplica", FAR=BASE+"lod/DhPreparedLodView";
    private static final String MESH=BASE+"scene/SceneMesh", GUARD=BASE+"renderprobe/RenderPublicationGuard";
    private static final String COLLISION=BASE+"coregate/CoreCollisionBinding";

    @Test void editCapturesBeforeReplicaBarrierOrPublicationMutationAndThenFencesGameplay() throws IOException {
        var code=method(read(SESSION),"beginEdit");
        assertEquals(1,code.count(SESSION,"publishedFarCoverageReady"));
        assertTrue(code.at(SESSION,"publishedFarCoverageReady")<code.at(PRESENTATION,"capture"));
        assertTrue(code.at(COLLISION,"ready")<code.at(PRESENTATION,"capture"));
        assertTrue(code.at(PRESENTATION,"capture")<code.at(REPLICA,"beginEdit"));
        assertTrue(code.at(PRESENTATION,"capture")<code.events.indexOf("write:publication"));
        assertTrue(code.at(REPLICA,"beginEdit")<code.at(SESSION,"fenceCollision"));
        // Boundary admission must use the immutable wire-bound descriptor; legacy defaults are tested independently.
        assertEquals(1,code.count(BASE+"coregate/RollingSessionDescriptor","permitsEdit"));
        assertFalse(code.calls.contains(BASE+"enginebridge/RollingCubePlan.isEditableInterior"));
        assertEquals(0,code.count(COLLISION,"publish"));
    }
    @Test void historicalSubmissionsUseOnlyExplicitCapabilitiesAndCurrentResourceArgument() throws IOException {
        var methods=read(SESSION);
        var historical=methods.values().stream().filter(c->c.count(FAR,"drawPresented")==1).findFirst().orElseThrow();
        assertEquals(1,historical.count(MESH,"drawPresented"));
        assertEquals(1,historical.count(GUARD,"resourceGeneration"));
        assertEquals(1,historical.count(GUARD+"$PresentationLease","tokenFor"));
        assertEquals(0,historical.count(MESH,"draw"));
        assertEquals(0,historical.count(FAR,"draw"));
        assertEquals(0,historical.count(SESSION,"send"));
        assertEquals(0,historical.count(COLLISION,"publish"));
        var render=method(methods,"render");
        assertTrue(render.at(PRESENTATION,"draw")<render.at(MESH,"isCurrent"));
        assertTrue(render.events.subList(render.at(PRESENTATION,"draw"),render.at(MESH,"isCurrent")).contains("return"),
                "Frozen branch must return before the current-authority draw path");
    }
    @Test void retirementPrecedesOldNativeDestructionAndPairedReadiness() throws IOException {
        var code=method(read(SESSION),"installPair");
        assertTrue(code.at(PRESENTATION,"invalidate")<code.at(FAR,"commitPrepared"));
        assertEquals(2,code.count(PRESENTATION,"retire"));
        assertTrue(code.at(PRESENTATION,"retire")<code.at(FAR,"commitPrepared"),"Distance history releases its old physical far owner before swapping");
        int legacyRetire=code.events.lastIndexOf(PRESENTATION+".retire");
        assertTrue(code.at(FAR,"coverageReady")<legacyRetire);
        assertTrue(legacyRetire<code.at(SESSION,"closeExcept"));
        assertDistanceOnlyEarlyRetirement();
        assertTrue(code.at(SESSION,"closeExcept")<code.at(SESSION,"send"));
    }
    @Test void exactCommitRequiresPresentationRetirementBeforeCurrentCollision() throws IOException {
        var code=method(read(SESSION),"committed");
        assertTrue(code.at(SESSION,"checkPair")<code.at(PRESENTATION,"retainsOwners"));
        assertTrue(code.at(PRESENTATION,"retainsOwners")<code.at(COLLISION,"publish"));
        assertEquals(0,code.count(PRESENTATION,"current"));
        assertEquals(0,code.count(PRESENTATION,"draw"));
    }
    @Test void cleanupAndReloadRevokeThePresentationBeforeAnyOldMeshCleanup() throws IOException {
        var methods=read(SESSION);var close=method(methods,"close");
        assertTrue(close.at(PRESENTATION,"invalidate")<close.at(GUARD,"close"));
        assertTrue(close.at(PRESENTATION,"close")<close.at(SESSION,"closeExcept"));
        assertTrue(close.at(SESSION,"closeExcept")<close.at(REPLICA,"close"));
        assertTrue(close.at(FAR,"close")<close.at(REPLICA,"close"));
        assertEquals(1,method(methods,"invalidate").count(SESSION,"invalidateOwned"));
        var invalidate=method(methods,"invalidateOwned");
        assertTrue(invalidate.at(PRESENTATION,"invalidate")<invalidate.at(GUARD,"requestInvalidation"));
        assertEquals(1,invalidate.count(SESSION,"fenceCollision"));
    }
    @Test void delayedReloadCompletionIsBoundToTheCapturedSessionNotTheLatestEpoch() throws IOException {
        var owner=method(read(CLIENT),"closeReloadOwner");
        assertTrue(owner.events.contains("read:current"));
        assertTrue(owner.jumps.contains(Opcodes.IFNULL)||owner.jumps.contains(Opcodes.IFNONNULL));
        assertTrue(owner.jumps.contains(Opcodes.IF_ACMPNE)||owner.jumps.contains(Opcodes.IF_ACMPEQ));
        assertEquals(1,owner.count(CLIENT,"closeCurrent"));
        var callbacks=read(CLIENT+"$Resources");
        assertEquals(0,callbacks.values().stream().mapToLong(c->c.count(CLIENT,"closeCurrent")).sum());
        assertEquals(1,callbacks.values().stream().mapToLong(c->c.count(CLIENT,"closeReloadOwner")).sum());
        assertTrue(callbacks.entrySet().stream().filter(e->e.getValue().count(CLIENT,"closeReloadOwner")==1)
                .allMatch(e->e.getValue().descriptor.contains("L"+SESSION+";")),"Callback must capture the original owner");
    }
    @Test void interactionFenceNeverConsultsHistoricalPresentationAsAuthority() throws IOException {
        var code=method(read(CLIENT),"input");
        for(String field:List.of("visible","pending","intentPending"))assertTrue(code.events.contains("read:"+field));
        assertEquals(1,code.count(COLLISION,"ready"));
        assertFalse(code.calls.stream().anyMatch(c->c.startsWith(PRESENTATION+".")));
    }
    @Test void bootstrapAndFrozenHudLabelsExplicitlyDiscloseFencedAuthority() throws IOException {
        var methods=read(SESSION);var label=method(methods,"presentationLabel");
        assertTrue(label.strings.stream().anyMatch(s->s.contains("VIEW WAITING")&&s.contains("collision and interactions fenced")));
        assertTrue(label.strings.stream().anyMatch(s->s.contains("FROZEN VIEW")&&s.contains("motion and interactions fenced")));
        assertTrue(method(methods,"preparationStatus").strings.stream().anyMatch(s->s.contains("bootstrap: no published pair")));
        assertTrue(label.calls.contains(COLLISION+".ready"));
    }
    /** The extra early retire is executed only by distance mode; legacy still retires after coverageReady. */
    private static void assertDistanceOnlyEarlyRetirement()throws IOException{
        var node=new ClassNode();try(var in=RollingFrozenClientContractTest.class.getClassLoader().getResourceAsStream(SESSION+".class")){
            assertNotNull(in);new ClassReader(in).accept(node,0);
        }
        var install=node.methods.stream().filter(m->m.name.equals("installPair")).findFirst().orElseThrow();
        var branch=distanceRetirementGuard(install);
        // Broken polarity would retire the legacy owner early and skip the distance release.
        install.instructions.set(branch,new JumpInsnNode(Opcodes.IFNE,branch.label));
        assertThrows(AssertionError.class,()->distanceRetirementGuard(install));
    }
    private static JumpInsnNode distanceRetirementGuard(MethodNode install){
        int firstRetire=-1,commit=-1;MethodInsnNode distance=null;
        for(int i=0;i<install.instructions.size();i++)if(install.instructions.get(i) instanceof MethodInsnNode call){
            if(call.owner.equals(SESSION)&&call.name.equals("distanceMode")&&firstRetire<0)distance=call;
            if(call.owner.equals(PRESENTATION)&&call.name.equals("retire")&&firstRetire<0)firstRetire=i;
            if(call.owner.equals(FAR)&&call.name.equals("commitPrepared")){commit=i;break;}
        }
        assertNotNull(distance);assertTrue(firstRetire>=0&&commit>firstRetire);
        var next=distance.getNext();while(next!=null&&next.getOpcode()<0)next=next.getNext();
        assertInstanceOf(JumpInsnNode.class,next);var branch=(JumpInsnNode)next;
        assertEquals(Opcodes.IFEQ,branch.getOpcode(),"Legacy false must skip the early retire");
        int target=install.instructions.indexOf(branch.label);
        assertTrue(install.instructions.indexOf(branch)<firstRetire&&firstRetire<target&&target<commit);
        // The true path falls straight through to retirement; no other conditional can skip it.
        for(var instruction=branch.getNext();instruction!=branch.label;instruction=instruction.getNext()){
            assertNotNull(instruction);assertFalse(instruction instanceof JumpInsnNode);
            assertFalse(instruction instanceof TableSwitchInsnNode||instruction instanceof LookupSwitchInsnNode);
            int op=instruction.getOpcode();assertFalse(op==Opcodes.ATHROW||(op>=Opcodes.IRETURN&&op<=Opcodes.RETURN));
        }
        return branch;
    }
    private static Code method(Map<String,Code> methods,String name){var c=methods.get(name);assertNotNull(c,name);return c;}
    private static Map<String,Code> read(String name)throws IOException{
        var result=new LinkedHashMap<String,Code>();
        try(var input=RollingFrozenClientContractTest.class.getClassLoader().getResourceAsStream(name+".class")){
            assertNotNull(input,name);new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9){
                public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions){
                    var c=new Code();c.descriptor=desc;result.put(method,c);return new MethodVisitor(Opcodes.ASM9){
                        public void visitMethodInsn(int opcode,String owner,String name,String desc,boolean iface){c.calls.add(owner+"."+name);c.events.add(owner+"."+name);}
                        public void visitFieldInsn(int opcode,String owner,String name,String desc){c.events.add((opcode==Opcodes.PUTFIELD||opcode==Opcodes.PUTSTATIC?"write:":"read:")+name);}
                        public void visitInsn(int opcode){if(opcode==Opcodes.RETURN)c.events.add("return");}
                        public void visitJumpInsn(int opcode,Label target){c.jumps.add(opcode);}
                        public void visitLdcInsn(Object value){if(value instanceof String s)c.strings.add(s);}
                        public void visitInvokeDynamicInsn(String name,String descriptor,Handle bootstrap,Object... args){for(var arg:args)if(arg instanceof String s)c.strings.add(s);}
                    };}
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }return result;
    }
    private static final class Code{
        String descriptor;final List<String> calls=new ArrayList<>(),events=new ArrayList<>(),strings=new ArrayList<>();final List<Integer> jumps=new ArrayList<>();
        long count(String owner,String name){return calls.stream().filter((owner+"."+name)::equals).count();}
        int at(String owner,String name){int at=events.indexOf(owner+"."+name);assertTrue(at>=0,"Missing "+owner+"."+name);return at;}
    }
}
