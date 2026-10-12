package io.earthplatform.minecraft.coregate;

import io.earthplatform.compat.PlayableProfile;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Model arithmetic tests plus compiled call-site entry-fence checks; not a live launch receipt. */
class PlayableAdmissionIndependentTest {
    @Test void profileRejectsAliasUnicodeEscapeInlineCommentAndExtraSettings(){
        for(String text:new String[]{"profile=finite-arena-shader-v2","profile=finite-arena-shader-v1 # enabled","profile=finite-arena-shader-v1\nshader=Photon","profile=\\u0066inite-arena-shader-v1","profile=finite-arena-shader-v1\nprofile=finite-arena-shader-v1","profile=finite-arena-shader-v1\u0000"})assertEquals(PlayableProfile.State.BLOCKED,PlayableProfile.parse(text.getBytes(StandardCharsets.UTF_8)).state(),text);
        assertTrue(PlayableProfile.parse("# restart required\r\nprofile=finite-arena-shader-v1\r\n".getBytes(StandardCharsets.UTF_8)).selected());
    }
    static int[] quad(Direction face){
        int axis=face.getAxis()==Direction.Axis.X?0:face.getAxis()==Direction.Axis.Y?1:2;float plane=face.getAxisDirection()==Direction.AxisDirection.POSITIVE?1:0;int[] vertices=new int[32];int[] order={0,1,3,2};
        for(int v=0;v<4;v++){int bit=0;for(int a=0;a<3;a++)vertices[v*8+a]=Float.floatToRawIntBits(a==axis?plane:(order[v]>>bit++)&1);}
        return vertices;
    }
    @Test void allSixDefaultUnitFacesPassExactGeometryCensus(){for(var face:Direction.values())assertDoesNotThrow(()->PlayableModelPreflight.requireUnitQuad(quad(face),face));}
    @Test void distortedMissingRepeatedNonfiniteAndWrongPlaneVerticesRefuse(){
        for(var face:Direction.values()){
            for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,.5f,1.001f,-.001f}){var v=quad(face);v[0]=Float.floatToRawIntBits(bad);assertThrows(IllegalStateException.class,()->PlayableModelPreflight.requireUnitQuad(v,face));}
            var repeated=quad(face);System.arraycopy(repeated,0,repeated,8,8);assertThrows(IllegalStateException.class,()->PlayableModelPreflight.requireUnitQuad(repeated,face));
            assertThrows(IllegalStateException.class,()->PlayableModelPreflight.requireUnitQuad(quad(face.getOpposite()),face));
        }
        for(int size:new int[]{0,28,31,33,64})assertThrows(IllegalStateException.class,()->PlayableModelPreflight.requireUnitQuad(new int[size],Direction.UP));
    }
    @Test void entryPayloadAllowsOnlyBoundedCanonicalChallengeReplies(){
        assertDoesNotThrow(()->new PlayableEntryPayload(Long.MAX_VALUE,PlayableEntryPayload.REQUEST,0,""));assertDoesNotThrow(()->new PlayableEntryPayload(1,PlayableEntryPayload.READY,0,""));
        for(int kind:new int[]{-1,3,255})assertThrows(IllegalArgumentException.class,()->new PlayableEntryPayload(1,kind,0,""));assertThrows(IllegalArgumentException.class,()->new PlayableEntryPayload(1,1,-1,""));
        assertThrows(IllegalArgumentException.class,()->new PlayableEntryPayload(1,2,0,"  "));assertThrows(IllegalArgumentException.class,()->new PlayableEntryPayload(1,2,0,null));
    }
    @Test void defaultResourcesAndExactModsAreRequiredBeforeInspectingModels()throws Exception{
        var m=method("io/earthplatform/minecraft/coregate/PlayableModelPreflight","require");assertBefore(m,"getSelectedIds","getBlockModel");assertBefore(m,"require","getBlockModel");assertTrue(strings(m).containsAll(List.of("vanilla","mod_resources")));
        // Complete exact inventory is checked by its independently artifact-backed tests.
        // This boundary must call that guard before any budget-dependent model admission.
        assertTrue(java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false).anyMatch(i->i instanceof org.objectweb.asm.tree.MethodInsnNode c&&c.owner.equals("io/earthplatform/compat/dh/InstalledPlayableInventory")&&c.name.equals("require")&&c.desc.equals("()V")));
        assertTrue(calls(m).contains("requireUnitQuad"));
    }
    @Test void clientModelAndShaderPreflightPrecedeReadyAndEntryConsumesReloadBoundChallenge()throws Exception{
        var m=method("io/earthplatform/minecraft/coregate/RollingGateClient","playableEntry");assertBefore(m,"requireInstalledDh","sendToServer");assertBefore(m,"lease","sendToServer");assertBefore(m,"require","sendToServer");assertTrue(fields(m).containsAll(List.of("playableApprovedChallenge","playableApprovedConnection","playableApprovedReload","SCENARIO_RELOAD_SEQUENCE")));
        var ctor=method("io/earthplatform/minecraft/coregate/RollingGateClient$Session","<init>");assertTrue(fields(ctor).containsAll(List.of("playableApprovedChallenge","playableApprovedConnection","playableApprovedReload","SCENARIO_RELOAD_SEQUENCE")));
        assertTrue(strings(ctor).stream().anyMatch(s->s.contains("exact current client preflight")));
    }
    @Test void hostPendingEntryBindsIdentityStateDeadlineAndExplicitCancel()throws Exception{
        var type=read("io/earthplatform/minecraft/coregate/RollingGateCommands$PendingPlayable");assertTrue(type.fields.stream().map(f->f.name).toList().containsAll(List.of("server","player","connection","dimension","mode","position","challenge","deadlineNanos")));
        var reply=method("io/earthplatform/minecraft/coregate/RollingGateCommands","playableEntryReply");for(String check:List.of("server","player","connection","challenge","deadlineNanos","dimension","mode","position","distanceToSqr","mayEnter"))assertBefore(reply,check,"open");
        var leave=method("io/earthplatform/minecraft/coregate/RollingGateCommands","leave");assertTrue(fields(leave).contains("pendingPlayable"));assertTrue(strings(leave).contains("Playable entry cancelled"));
    }
    @Test void earlyAdmissionChecksPinnedVersionsAndArtifactBytesWithoutLoadingRendererTargets()throws Exception{
        var m=method("io/earthplatform/compat/dh/EarlyPlayableAdmission","enabled");var calls=calls(m);assertEquals(4,calls.stream().filter("requireVersion"::equals).count());assertTrue(calls.containsAll(List.of("verifyInstalledDh","verifyBytes","verify")));assertTrue(strings(m).containsAll(List.of("oculus","embeddium","neoforge","distanthorizons","iris","sodium")));
        assertFalse(calls.contains("forName"));assertFalse(calls.contains("loadClass"));
    }
    @Test void profileSelectionIsAStaticRestartRequiredLoadRatherThanDynamicJvmFlags()throws Exception{
        var loaded=read("io/earthplatform/compat/PlayableProfile$Loaded");var value=loaded.fields.stream().filter(f->f.name.equals("VALUE")).findFirst().orElseThrow();assertTrue((value.access&Opcodes.ACC_FINAL)!=0);assertTrue((value.access&Opcodes.ACC_STATIC)!=0);var init=loaded.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElseThrow();assertTrue(calls(init).contains("read"));assertFalse(calls(method("io/earthplatform/compat/PlayableProfile","selection")).contains("getProperty"));
    }
    private static ClassNode read(String name)throws Exception{try(var in=PlayableAdmissionIndependentTest.class.getClassLoader().getResourceAsStream(name+".class")){assertNotNull(in,name);var node=new ClassNode();new ClassReader(in).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return node;}}
    private static MethodNode method(String owner,String name)throws Exception{return read(owner).methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static List<String> calls(MethodNode m){var out=new ArrayList<String>();for(var i:m.instructions)if(i instanceof MethodInsnNode call)out.add(call.name);return out;}
    private static List<String> fields(MethodNode m){var out=new ArrayList<String>();for(var i:m.instructions)if(i instanceof FieldInsnNode f)out.add(f.name);return out;}
    private static List<String> strings(MethodNode m){var out=new ArrayList<String>();for(var i:m.instructions)if(i instanceof LdcInsnNode l&&l.cst instanceof String s)out.add(s);return out;}
    private static void assertBefore(MethodNode m,String check,String action){var calls=calls(m);assertTrue(calls.indexOf(check)>=0,check);assertTrue(calls.indexOf(action)>calls.indexOf(check),check+" must precede "+action);}
}
