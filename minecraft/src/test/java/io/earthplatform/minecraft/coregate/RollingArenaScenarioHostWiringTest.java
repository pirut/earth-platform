package io.earthplatform.minecraft.coregate;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compiled host wiring checks only. They neither launch Minecraft nor claim GUI acceptance. */
class RollingArenaScenarioHostWiringTest {
    private static final String HOST="io/earthplatform/minecraft/coregate/RollingGateCommands";
    private static final String SESSION=HOST+"$Session";
    private static final String RUNNER="io/earthplatform/minecraft/coregate/RollingArenaScenario";
    private static final String WITNESS="io/earthplatform/minecraft/coregate/RollingScenarioClientWitness";
    private static final String CHECKPOINT="io/earthplatform/minecraft/coregate/RollingScenarioCheckpoint";
    private static final String HANDSHAKE="io/earthplatform/minecraft/coregate/RollingPublicationHandshake";
    private static final String BRIDGE="io/earthplatform/minecraft/enginebridge/";
    private static ClassNode read(String name) throws IOException {
        try(var in=RollingArenaScenarioHostWiringTest.class.getClassLoader().getResourceAsStream(name+".class")){
            assertNotNull(in,"Compiled candidate class required: "+name);
            var node=new ClassNode();new ClassReader(in).accept(node,0);return node;
        }
    }
    private static MethodNode method(ClassNode type,String name){return type.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static List<AbstractInsnNode> instructions(MethodNode method){return java.util.stream.StreamSupport.stream(method.instructions.spliterator(),false).toList();}
    private static List<MethodInsnNode> calls(MethodNode method){return instructions(method).stream().filter(i->i instanceof MethodInsnNode).map(i->(MethodInsnNode)i).toList();}
    private static int call(MethodNode method,String owner,String name){
        var found=calls(method).stream().filter(c->c.owner.equals(owner)&&c.name.equals(name)).toList();
        assertEquals(1,found.size(),"One exact call required: "+owner+"."+name+" in "+method.name);
        return method.instructions.indexOf(found.getFirst());
    }
    private static int field(MethodNode method,String name,int opcode){
        var found=instructions(method).stream().filter(i->i instanceof FieldInsnNode f&&f.owner.equals(SESSION)&&f.name.equals(name)&&f.getOpcode()==opcode).toList();
        assertEquals(1,found.size(),"One session field access required: "+name+" in "+method.name);
        return method.instructions.indexOf(found.getFirst());
    }
    private static AbstractInsnNode nextOpcode(AbstractInsnNode node){
        for(var next=node.getNext();next!=null;next=next.getNext())if(next.getOpcode()>=0)return next;
        throw new AssertionError("Missing following opcode");
    }
    private static AbstractInsnNode previousOpcode(AbstractInsnNode node){
        for(var previous=node.getPrevious();previous!=null;previous=previous.getPrevious())if(previous.getOpcode()>=0)return previous;
        throw new AssertionError("Missing preceding opcode");
    }
    /** Normal plus exceptional control flow, with one actual boolean branch fixed. */
    private static Set<Integer> reachableWithResult(MethodNode method,int predicate,boolean result){
        var branch=nextOpcode(method.instructions.get(predicate));assertInstanceOf(JumpInsnNode.class,branch);
        assertTrue(branch.getOpcode()==Opcodes.IFEQ||branch.getOpcode()==Opcodes.IFNE);
        int condition=method.instructions.indexOf(branch);var seen=new HashSet<Integer>();var queue=new ArrayDeque<Integer>();queue.add(0);
        while(!queue.isEmpty()){
            int index=queue.removeFirst();if(index<0||index>=method.instructions.size()||!seen.add(index))continue;
            var instruction=method.instructions.get(index);int opcode=instruction.getOpcode();
            for(var handler:method.tryCatchBlocks)if(method.instructions.indexOf(handler.start)<=index&&index<method.instructions.indexOf(handler.end))queue.add(method.instructions.indexOf(handler.handler));
            if(index==condition){boolean jump=opcode==Opcodes.IFEQ?!result:result;queue.add(jump?method.instructions.indexOf(((JumpInsnNode)branch).label):index+1);}
            else if(instruction instanceof JumpInsnNode jump){queue.add(method.instructions.indexOf(jump.label));if(opcode!=Opcodes.GOTO)queue.add(index+1);}
            else if(instruction instanceof TableSwitchInsnNode table){queue.add(method.instructions.indexOf(table.dflt));for(var label:table.labels)queue.add(method.instructions.indexOf(label));}
            else if(instruction instanceof LookupSwitchInsnNode lookup){queue.add(method.instructions.indexOf(lookup.dflt));for(var label:lookup.labels)queue.add(method.instructions.indexOf(label));}
            else if(opcode!=Opcodes.ATHROW&&!(opcode>=Opcodes.IRETURN&&opcode<=Opcodes.RETURN))queue.add(index+1);
        }
        assertTrue(seen.contains(predicate),"The gate must be reachable");return seen;
    }
    private static ClassNode driver() throws IOException {
        var candidates=new java.util.ArrayList<ClassNode>();
        for(var child:read(HOST).innerClasses)if(child.name.startsWith(HOST+"$")){
            var type=read(child.name);if(type.interfaces.contains(RUNNER+"$Driver"))candidates.add(type);
        }
        assertEquals(1,candidates.size(),"One concrete arena driver required");return candidates.getFirst();
    }
    private static boolean invokes(AbstractInsnNode instruction,String owner,String name){return instruction instanceof MethodInsnNode m&&m.owner.equals(owner)&&m.name.equals(name);}
    private static boolean containsText(MethodNode method,String text){return instructions(method).stream().anyMatch(i->i instanceof LdcInsnNode s&&s.cst instanceof String value&&value.contains(text));}

    @Test void exactReadyAndDiagnosticRefusalDominateEvidenceAndMovement() throws Exception {
        var admission=method(read(HOST),"arenaScenario");
        for(String gate:List.of("arenaScenarioReady","arenaScenarioEnabled")){
            var refused=reachableWithResult(admission,call(admission,HOST,gate),false);int effects=0;
            for(int index=0;index<admission.instructions.size();index++){
                var instruction=admission.instructions.get(index);boolean effect=instruction.getOpcode()==Opcodes.PUTFIELD||instruction.getOpcode()==Opcodes.PUTSTATIC;
                if(instruction instanceof MethodInsnNode invoke)effect|=invoke.owner.equals("java/nio/file/Files")
                        ||invoke.owner.equals(HOST)&&Set.of("walk","scenarioPose","writeArenaScenario").contains(invoke.name)
                        ||invoke.owner.equals(RUNNER)&&invoke.name.equals("<init>");
                if(instruction instanceof TypeInsnNode type)effect|=type.getOpcode()==Opcodes.NEW&&type.desc.equals(RUNNER);
                if(effect){effects++;assertFalse(refused.contains(index),gate+" refusal reaches evidence/player/runner mutation "+index);}
            }
            assertTrue(effects>=8);assertTrue(refused.stream().anyMatch(i->admission.instructions.get(i).getOpcode()==Opcodes.IRETURN));
        }
        assertTrue(containsText(admission,"exact client READY"));
    }
    @Test void everyExternalModeUsesSamePreflightAndQaOnlyProfile() throws Exception {
        var host=read(HOST);var modes=new HashSet<String>();int count=0;
        for(var method:host.methods)for(var invoke:calls(method))if(invoke.owner.equals(HOST)&&invoke.name.equals("arenaScenario")){
            count++;for(var i:method.instructions)if(i instanceof FieldInsnNode f&&f.owner.equals(RUNNER+"$Mode")&&f.getOpcode()==Opcodes.GETSTATIC)modes.add(f.name);
        }
        assertEquals(3,count);assertEquals(Set.of("EXERCISE","AFTER_RELOAD","AFTER_RESTART"),modes);
        var enabled=method(host,"arenaScenarioEnabled");call(enabled,"io/earthplatform/compat/PlayableDiagnostics","enabled");
        call(enabled,"io/earthplatform/minecraft/coregate/RollingSessionDescriptor","arenaMode");
    }
    @Test void readinessUsesExactRetainedWitnessAndExistingCollisionAndPendingFences() throws Exception {
        var ready=method(read(HOST),"arenaScenarioReady");call(ready,WITNESS,"current");
        for(String method:List.of("pending","committedPublication","resourceGeneration","committedDigest"))call(ready,HANDSHAKE,method);
        call(ready,"io/earthplatform/minecraft/coregate/CoreCollisionBinding","ready");call(ready,BRIDGE+"RollingCubeActor$StageReady","arenaCertificate");
        var fields=new HashSet<String>();for(var i:ready.instructions)if(i instanceof FieldInsnNode f&&f.owner.equals(SESSION))fields.add(f.name);
        assertTrue(fields.containsAll(Set.of("stopping","editPending","ray","committedManifest","epoch","scenarioClientWitness")));
    }
    @Test void unreadyOrAckHeldPairCannotExecuteAnyArenaActionButClockStillRuns() throws Exception {
        var pump=method(read(HOST),"pumpArenaScenario");
        var refused=reachableWithResult(pump,call(pump,HOST,"arenaScenarioReady"),false);
        for(int index:refused){var i=pump.instructions.get(index);
            assertFalse(i instanceof MethodInsnNode m&&m.owner.equals(HOST)&&Set.of("arenaHarnessEdit","arenaTravelNext").contains(m.name));
            assertFalse(invokes(i,BRIDGE+"NativeRayEditPlanner","planArena"));
            assertFalse(i instanceof FieldInsnNode f&&f.owner.equals(SESSION)&&f.getOpcode()==Opcodes.PUTFIELD&&Set.of("scenarioCompletedAction","scenarioActionStarted","arenaOutcome").contains(f.name));
        }
        call(pump,RUNNER,"tick");assertTrue(containsText(pump,"Screenshot checkpoint acknowledgement deadline"));
        assertTrue(calls(pump).stream().anyMatch(c->c.owner.equals(RUNNER)&&c.name.equals("cancel")));
        assertTrue(instructions(pump).stream().anyMatch(i->i instanceof LdcInsnNode c&&Long.valueOf(RollingArenaScenario.STEP_NANOS).equals(c.cst)));
        var emptyCalls=calls(pump).stream().filter(c->c.owner.equals("java/lang/String")&&c.name.equals("isEmpty")).toList();
        assertTrue(emptyCalls.size()>=2);
        var held=reachableWithResult(pump,pump.instructions.indexOf(emptyCalls.get(1)),false);
        assertTrue(held.stream().map(pump.instructions::get).noneMatch(i->invokes(i,HOST,"arenaHarnessEdit")||invokes(i,HOST,"arenaTravelNext")||invokes(i,BRIDGE+"NativeRayEditPlanner","planArena")));
    }
    @Test void eachStartedActionCannotReenterActorEditOrInitialTravelOrRefusalPlanner() throws Exception {
        var pump=method(read(HOST),"pumpArenaScenario");var started=reachableWithResult(pump,field(pump,"scenarioActionStarted",Opcodes.GETFIELD),true);
        assertFalse(started.contains(call(pump,HOST,"arenaHarnessEdit")));
        assertFalse(started.contains(call(pump,BRIDGE+"NativeRayEditPlanner","planArena")));
        int mark=field(pump,"scenarioActionStarted",Opcodes.PUTFIELD);
        assertEquals(Opcodes.ICONST_1,previousOpcode(pump.instructions.get(mark)).getOpcode());
        assertTrue(mark<call(pump,HOST,"arenaHarnessEdit"));
        var edit=method(read(HOST),"arenaHarnessEdit");call(edit,BRIDGE+"RollingCubeActor","tryEdit");
        assertEquals(1,instructions(edit).stream().filter(i->i instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.NEW&&t.desc.equals(BRIDGE+"RollingCubeActor$EditRequest")).count());
        assertTrue(calls(edit).stream().noneMatch(m->Set.of("setBlock","setBlockAndUpdate","fill","forceRevision","resetRevision").contains(m.name)));
    }
    @Test void driverIssueOnlyQueuesOneActionAndNeverMutatesTerrainOrMovement() throws Exception {
        var issue=method(driver(),"issue");field(issue,"arenaAction",Opcodes.PUTFIELD);
        field(issue,"scenarioActionStarted",Opcodes.PUTFIELD);field(issue,"arenaOutcome",Opcodes.PUTFIELD);
        assertTrue(containsText(issue,"Unfinished arena action"));
        assertTrue(calls(issue).stream().noneMatch(m->m.owner.equals(BRIDGE+"RollingCubeActor")||m.owner.equals("java/nio/file/Files")
                ||m.owner.equals(HOST)&&Set.of("arenaHarnessEdit","arenaTravelNext","scenarioPose").contains(m.name)));
        assertEquals(1,calls(issue).stream().filter(m->m.name.equals("clear")).count());
    }
    @Test void editUsesActualCurrentLeaseRevisionAndOnlyTheRequestedTargetWithoutBaselineRepair() throws Exception {
        var host=read(HOST);var edit=method(host,"arenaHarnessEdit");
        call(edit,RUNNER+"$Action","target");call(edit,"io/earthplatform/core/CubePos","fromBlock");
        call(edit,BRIDGE+"ArenaDefinition","insideWindow");call(edit,"io/earthplatform/minecraft/coregate/RollingSessionDescriptor","permitsEdit");
        call(edit,"io/earthplatform/minecraft/network/EngineCubeSnapshot","leaseGeneration");
        assertEquals(2,calls(edit).stream().filter(m->m.owner.equals("io/earthplatform/minecraft/network/EngineCubeSnapshot")&&m.name.equals("revision")).count());
        call(edit,BRIDGE+"RollingCubeActor","requestCenter");call(edit,HANDSHAKE,"begin");call(edit,"io/earthplatform/minecraft/coregate/CoreCollisionBinding","fence");
        var admission=method(host,"arenaScenario");
        assertTrue(calls(admission).stream().noneMatch(m->m.owner.equals(BRIDGE+"RollingCubeActor")||Set.of("setBlock","fill","resetRevision","forceRevision").contains(m.name)));
        assertTrue(containsText(edit,"Harness fill intersects player"));
    }
    @Test void observationBindsAllEightDurableValuesCompleteCatalogueAndNativeFull64Residency() throws Exception {
        var observation=method(read(HOST),"observeArenaScenario");
        call(observation,WITNESS,"reloadSequence");call(observation,WITNESS,"coverage");call(observation,HOST,"arenaScenarioReady");
        call(observation,BRIDGE+"ArenaQueryAttestation","completeCatalogue");call(observation,BRIDGE+"ArenaQueryAttestation","snapshotGeneration");
        call(observation,BRIDGE+"ArenaQueryAttestation","snapshotRootIdentity");call(observation,BRIDGE+"ArenaQueryAttestation","identity");
        call(observation,BRIDGE+"ArenaCapture","contentIdentity");call(observation,BRIDGE+"ArenaCertificate","durableIdentity");
        call(observation,BRIDGE+"ArenaTerrainSource","identity");call(observation,BRIDGE+"RollingCubeActor","replacementCurrent");
        call(observation,BRIDGE+"ArenaDefinition","dependencies");call(observation,"java/util/stream/Stream","count");
        assertTrue(instructions(observation).stream().anyMatch(i->i instanceof FieldInsnNode f&&f.owner.equals(RUNNER)&&f.name.equals("ARENA_CUBES")));
        var handles=instructions(observation).stream().filter(i->i instanceof InvokeDynamicInsnNode).map(i->(InvokeDynamicInsnNode)i)
                .flatMap(i->java.util.Arrays.stream(i.bsmArgs)).filter(o->o instanceof Handle).map(o->(Handle)o).toList();
        assertTrue(handles.stream().anyMatch(h->h.getOwner().equals(BRIDGE+"ArenaCertificate")&&h.getName().equals("revision")));
        assertTrue(handles.stream().anyMatch(h->h.getOwner().equals("java/util/Map")&&h.getName().equals("containsKey")));
        assertTrue(instructions(observation).stream().anyMatch(i->i instanceof FieldInsnNode f&&f.owner.equals(HOST)&&f.name.equals("SCENARIO_JVM")));
    }
    @Test void checkpointRetainsExactPairAndOneBoundedOutstandingScreenshot() throws Exception {
        var checkpoint=method(driver(),"checkpoint");call(checkpoint,CHECKPOINT,"<init>");
        call(checkpoint,WITNESS,"coverage");call(checkpoint,HANDSHAKE,"committedPublication");
        call(checkpoint,HANDSHAKE,"resourceGeneration");call(checkpoint,HANDSHAKE,"committedDigest");
        call(checkpoint,"java/nio/file/Files","writeString");field(checkpoint,"scenarioCheckpoint",Opcodes.PUTFIELD);
        field(checkpoint,"scenarioCaptureStarted",Opcodes.PUTFIELD);
        assertTrue(containsText(checkpoint,"One screenshot checkpoint at a time"));assertTrue(containsText(checkpoint,"Arena checkpoint bound"));
        assertTrue(instructions(checkpoint).stream().anyMatch(i->i instanceof IntInsnNode n&&n.operand==RollingArenaScenario.MAX_TRANSCRIPT));
    }
    @Test void foreignWitnessOrWrongCheckpointCannotClearOrCountAnAcknowledgement() throws Exception {
        var receipt=method(read(HOST),"scenarioReceipt");
        var foreign=reachableWithResult(receipt,call(receipt,WITNESS,"current"),false);
        for(int index:foreign)assertFalse(receipt.instructions.get(index) instanceof FieldInsnNode f&&f.owner.equals(SESSION)&&f.getOpcode()==Opcodes.PUTFIELD);
        var wrong=reachableWithResult(receipt,call(receipt,CHECKPOINT,"matches"),false);
        for(int index:wrong)assertFalse(receipt.instructions.get(index) instanceof FieldInsnNode f&&f.owner.equals(SESSION)&&f.getOpcode()==Opcodes.PUTFIELD
                &&Set.of("scenarioCapture","scenarioCheckpoint","scenarioScreenshotAcks").contains(f.name));
        assertTrue(wrong.stream().map(receipt.instructions::get).anyMatch(i->invokes(i,RUNNER,"cancel")));
        assertTrue(calls(receipt).stream().anyMatch(i->i.owner.equals(HOST)&&i.name.equals("writeArenaScenario")));
    }
    @Test void savedExpectationHasBoundedInputAndRealExternalPhaseChecksBeforeMovement() throws Exception {
        var admission=method(read(HOST),"arenaScenario");int size=call(admission,"java/nio/file/Files","size");
        int read=call(admission,"java/nio/file/Files","readString"),directories=call(admission,"java/nio/file/Files","createDirectories");
        assertTrue(size<read&&read<directories&&directories<call(admission,HOST,"scenarioPose"));
        assertTrue(instructions(admission).stream().anyMatch(i->i instanceof LdcInsnNode n&&Long.valueOf(16384).equals(n.cst)));
        assertTrue(containsText(admission,"external-boundary-expectation.json"));assertTrue(containsText(admission,"WAITING_EXTERNAL_RELOAD"));
        assertTrue(containsText(admission,"WAITING_EXTERNAL_RESTART"));assertTrue(containsText(admission,"same JVM"));assertTrue(containsText(admission,"different JVM"));
        assertTrue(calls(admission).stream().anyMatch(c->c.owner.equals(HOST+"$ArenaScenarioRecovery")&&c.name.equals("expectation")));
    }
    @Test void recoveryExpectationIsPublishedOnlyForWaitingPhasesAfterExactScreenshotAck() throws Exception {
        var persist=method(read(HOST),"writeArenaScenario");
        var empty=calls(persist).stream().filter(c->c.owner.equals("java/lang/String")&&c.name.equals("isEmpty")).toList();assertEquals(3,empty.size());
        var held=reachableWithResult(persist,persist.instructions.indexOf(empty.get(1)),false);
        for(int index:held){var i=persist.instructions.get(index);
            assertFalse(i instanceof LdcInsnNode text&&"external-boundary-expectation.json".equals(text.cst),"Pending screenshot can publish recovery expectation");
            assertFalse(i instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.NEW&&t.desc.equals(HOST+"$ArenaScenarioRecovery"));
        }
        var phases=new HashSet<String>();for(var i:persist.instructions)if(i instanceof FieldInsnNode f&&f.owner.equals(RUNNER+"$Status"))phases.add(f.name);
        assertEquals(Set.of("RUNNING","WAITING_EXTERNAL_RELOAD","WAITING_EXTERNAL_RESTART"),phases);
        assertTrue(containsText(persist,"No manual mouse/keyboard claim"));assertTrue(containsText(persist,"Resource reload and process restart are external actions"));
        assertTrue(calls(persist).stream().anyMatch(c->c.owner.equals(RUNNER)&&c.name.equals("cancel")));
    }
}
