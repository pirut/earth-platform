package io.earthplatform.minecraft.coregate;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compiled host integration contracts supplement the pure scenario's own adversarial tests. */
class RollingArenaScenarioHostIndependentTest {
    private static final String ROOT="io/earthplatform/minecraft/coregate/";
    @Test void arenaScenarioNeedsAnExactCurrentClientWitnessBeforeEvidenceOrMovement()throws Exception{
        var ready=method(ROOT+"RollingGateCommands","arenaScenarioReady");assertTrue(calls(ready).contains("io/earthplatform/minecraft/coregate/RollingScenarioClientWitness.current"));assertTrue(fields(ready).containsAll(List.of("stopping","handshake","editPending","ray","collision","committedManifest","scenarioClientWitness")));
        var start=method(ROOT+"RollingGateCommands","arenaScenario");int current=call(start,ROOT+"RollingGateCommands","arenaScenarioReady");assertTrue(current>=0);assertTrue(current<call(start,"java/nio/file/Path","of"));assertTrue(current<call(start,ROOT+"RollingGateCommands","walk"));assertTrue(current<call(start,ROOT+"RollingGateCommands","scenarioPose"));
    }
    @Test void absentAndStaleWitnessesNeverBecomeCurrentEvenWhenReloadSequenceIsZero(){
        String digest="a".repeat(64);assertFalse(RollingScenarioClientWitness.current(null,1,1,1,digest));var witness=new RollingScenarioClientWitness(1,1,1,1,0,digest);assertTrue(RollingScenarioClientWitness.current(witness,1,1,1,digest));assertFalse(RollingScenarioClientWitness.current(witness,2,1,1,digest));assertFalse(RollingScenarioClientWitness.current(witness,1,2,1,digest));assertFalse(RollingScenarioClientWitness.current(witness,1,1,2,digest));assertFalse(RollingScenarioClientWitness.current(witness,1,1,1,"b".repeat(64)));
    }
    @Test void receiptVerifiesCurrentPairThenExactPendingCheckpointBeforeClearingIt()throws Exception{
        var m=method(ROOT+"RollingGateCommands","scenarioReceipt");int current=call(m,ROOT+"RollingScenarioClientWitness","current"),match=call(m,ROOT+"RollingScenarioCheckpoint","matches");assertTrue(current>=0&&match>current);assertTrue(field(m,Opcodes.PUTFIELD,"scenarioClientWitness")>current);assertTrue(field(m,Opcodes.PUTFIELD,"scenarioCapture")>match);assertTrue(field(m,Opcodes.PUTFIELD,"scenarioScreenshotAcks")>match);
        var checkpoint=new RollingScenarioCheckpoint(1,2,3,4,"a".repeat(64),"arena27-0-initial_ready");assertFalse(checkpoint.matches(1,2,3,5,"a".repeat(64),"arena27-0-initial_ready"));assertFalse(checkpoint.matches(1,2,3,4,"a".repeat(64),"arena27-1-initial_ready"));
    }
    @Test void screenshotCaptureRequiresExactVisibleCurrentPairAndUsesArenaTwentyFourCap()throws Exception{
        assertEquals(24,RollingArenaScenario.MAX_TRANSCRIPT);var handle=method(ROOT+"RollingGateClient","handle");int screenshots=field(handle,Opcodes.GETFIELD,"scenarioScreenshots");assertTrue(screenshots>=0);boolean cap=false;for(int i=screenshots;i<Math.min(screenshots+18,handle.instructions.size());i++)if(handle.instructions.get(i) instanceof IntInsnNode n&&n.operand==24)cap=true;assertTrue(cap,"Arena checkpoint path must use its finite 24 screenshot cap");
        var capture=method(ROOT+"RollingGateClient$Session","afterHud");int match=call(capture,ROOT+"RollingScenarioCheckpoint","matches"),screen=call(capture,"net/minecraft/client/Screenshot","takeScreenshot"),write=call(capture,"com/mojang/blaze3d/platform/NativeImage","writeToFile"),reply=call(capture,"net/neoforged/neoforge/network/PacketDistributor","sendToServer");assertTrue(match>=0&&screen>match&&write>screen&&reply>write);assertTrue(fields(capture).contains("visible"));assertTrue(call(capture,ROOT+"RollingGateClient$Session","publishedFarCoverageReady")<screen);
    }
    @Test void harnessStaysExplicitAndActorEditsStillUseActualDurabilityAdmission()throws Exception{
        assertEquals("SCRIPTED_HARNESS",RollingArenaScenario.INPUT_MODE);var edit=method(ROOT+"RollingGateCommands","arenaHarnessEdit");assertTrue(call(edit,"io/earthplatform/minecraft/enginebridge/RollingCubeActor","tryEdit")>=0);assertTrue(call(edit,"io/earthplatform/minecraft/enginebridge/RollingCubeActor","tryEdit")<call(edit,ROOT+"RollingPublicationHandshake","begin"));assertTrue(calls(edit).contains("io/earthplatform/minecraft/coregate/CoreCollisionBinding.fence"));
        var pump=method(ROOT+"RollingGateCommands","pumpArenaScenario");assertTrue(calls(pump).contains("io/earthplatform/minecraft/enginebridge/NativeRayEditPlanner.planArena"));var report=method(ROOT+"RollingGateCommands","writeArenaScenario");assertTrue(strings(report).stream().anyMatch(s->s.contains("No manual mouse/keyboard claim")));
    }
    private static MethodNode method(String owner,String name)throws Exception{try(var in=RollingArenaScenarioHostIndependentTest.class.getClassLoader().getResourceAsStream(owner+".class")){assertNotNull(in,owner);var c=new ClassNode();new ClassReader(in).accept(c,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return c.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}}
    private static int call(MethodNode m,String owner,String name){for(int i=0;i<m.instructions.size();i++)if(m.instructions.get(i) instanceof MethodInsnNode n&&n.owner.equals(owner)&&n.name.equals(name))return i;return -1;}
    private static int field(MethodNode m,int opcode,String name){for(int i=0;i<m.instructions.size();i++)if(m.instructions.get(i) instanceof FieldInsnNode n&&n.getOpcode()==opcode&&n.name.equals(name))return i;return -1;}
    private static List<String> calls(MethodNode m){var out=new ArrayList<String>();for(var i:m.instructions)if(i instanceof MethodInsnNode n)out.add(n.owner+"."+n.name);return out;}
    private static List<String> fields(MethodNode m){var out=new ArrayList<String>();for(var i:m.instructions)if(i instanceof FieldInsnNode n)out.add(n.name);return out;}
    private static List<String> strings(MethodNode m){var out=new ArrayList<String>();for(var i:m.instructions)if(i instanceof LdcInsnNode n&&n.cst instanceof String s)out.add(s);return out;}
}
