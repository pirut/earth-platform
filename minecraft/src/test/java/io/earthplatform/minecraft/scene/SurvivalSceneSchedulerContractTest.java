package io.earthplatform.minecraft.scene;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Structural regression checks only. No native models, GL, actual hooks or whole-scene runtime run. */
class SurvivalSceneSchedulerContractTest {
    private static final String SCENE="io/earthplatform/minecraft/scene/";
    private static final String RENDER="io/earthplatform/minecraft/survival/render/";
    private static ClassNode own(String path)throws Exception{
        try(var stream=SurvivalSceneSchedulerContractTest.class.getClassLoader().getResourceAsStream(path+".class")){
            assertNotNull(stream,path);var result=new ClassNode();new ClassReader(stream).accept(result,0);return result;
        }
    }
    private static MethodNode method(ClassNode type,String name){return type.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static List<MethodInsnNode> calls(MethodNode method){var result=new ArrayList<MethodInsnNode>();for(var insn:method.instructions)if(insn instanceof MethodInsnNode call)result.add(call);return result;}
    private static int callIndex(MethodNode method,String owner,String name){
        for(int i=0;i<method.instructions.size();i++)if(method.instructions.get(i) instanceof MethodInsnNode call
                &&call.owner.equals(owner)&&call.name.equals(name))return i;throw new AssertionError("Missing call "+owner+"."+name+" in "+method.name);
    }
    private static int writeIndex(MethodNode method,String field){
        for(int i=0;i<method.instructions.size();i++)if(method.instructions.get(i) instanceof FieldInsnNode write
                &&write.getOpcode()==Opcodes.PUTFIELD&&write.name.equals(field))return i;throw new AssertionError("Missing field write "+field+" in "+method.name);
    }
    @Test void schedulerStoresEveryFallibleOwnerBeforeItsStartAndJoinInitialization()throws Exception{
        var type=own(SCENE+"SurvivalSceneScheduler");var advance=method(type,"advance");var attach=method(type,"attach");
        assertTrue(writeIndex(advance,"inputs")<callIndex(advance,SCENE+"SurvivalSceneNativeInputs","start"));
        assertTrue(writeIndex(advance,"uploading")<callIndex(advance,SCENE+"SurvivalNativeGpuPage","start"));
        assertTrue(callIndex(advance,SCENE+"SurvivalSceneOwnerRoster","add")<callIndex(advance,SCENE+"SurvivalNativeGpuPage","start"));
        assertTrue(writeIndex(advance,"dhGpu")<callIndex(advance,"io/earthplatform/minecraft/lod/SurvivalDhGpuPages","start"));
        assertTrue(writeIndex(attach,"join")<callIndex(attach,SCENE+"SurvivalSceneFrameJoin","initialize"));
        assertTrue(writeIndex(attach,"carrier")<callIndex(attach,"io/earthplatform/compat/dh/shader/InstalledSurvivalShaderCarrier","start"));
        assertTrue(calls(advance).stream().anyMatch(c->c.name.equals("callerOwnsCleanup")));
    }
    @Test void onlyCompletePhysicalRosterCanReachTypedAttachment()throws Exception{
        var type=own(SCENE+"SurvivalSceneScheduler");
        var physical=method(type,"requireWholePhysicalGeneration");
        for(String expected:List.of("completion","requireAuthenticated","requireCurrentConfiguration"))
            assertTrue(calls(physical).stream().anyMatch(c->c.name.equals(expected)));
        var attach=method(type,"attach");
        assertTrue(callIndex(attach,SCENE+"SurvivalSceneScheduler","requireWholePhysicalGeneration")
                <callIndex(attach,SCENE+"SurvivalSceneFrameJoin","initialize"));
        assertFalse(calls(attach).stream().anyMatch(c->c.name.equals("beginActualFrame")||c.name.equals("prepareActualMainInvocation")));
    }
    @Test void sourceAssetsAndCallerDependencyMapsAreReservedBeforeAllocation()throws Exception{
        var assets=method(own("io/earthplatform/minecraft/survival/source/SurvivalSceneSourceAssets"),"load");
        assertTrue(callIndex(assets,"io/earthplatform/minecraft/visual/NativeDistanceCpuBudget","reserve")
                <callIndex(assets,"io/earthplatform/minecraft/survival/source/MarinSurvivalResources","installAndLoad"));
        var inputs=method(own(SCENE+"SurvivalSceneNativeInputs"),"start");
        int reserve=callIndex(inputs,"io/earthplatform/minecraft/visual/NativeDistanceCpuBudget","reserve");
        assertTrue(reserve>=0&&reserve<callIndex(inputs,RENDER+"SurvivalDistancePagePlan$Page","dependencies"));
        assertTrue(calls(inputs).stream().anyMatch(c->c.name.equals("requireSource")));
        var scheduler=method(own(SCENE+"SurvivalSceneScheduler"),"start");
        assertTrue(calls(scheduler).stream().anyMatch(c->c.name.equals("requireSource")));
    }
    @Test void retainedJoinShellNeverAttemptsCleanupInItsConstructorOrInitializerCatch()throws Exception{
        var type=own(SCENE+"SurvivalSceneFrameJoin");
        var constructor=method(type,"<init>");
        assertFalse(calls(constructor).stream().anyMatch(c->c.name.equals("retain")||c.name.equals("reserve")||c.name.equals("close")));
        var initialize=method(type,"initialize");
        assertFalse(calls(initialize).stream().anyMatch(c->c.name.equals("close")));
        assertTrue(initialize.instructions.iterator().hasNext());
        assertTrue(type.fields.stream().anyMatch(f->f.name.equals("initialized")));
    }
    @Test void contentPendingIsPrivateExactCasOutcomeAndNoExceptionIsRelabeledPending()throws Exception{
        var join=own(SCENE+"SurvivalSceneFrameJoin");var accept=method(join,"acceptMainFrame");
        int latch=callIndex(accept,RENDER+"SurvivalCompleteOverlay$CurrentLease","tryLatchCurrent");
        assertTrue(latch>=0&&latch<writeIndex(accept,"pendingContentFrame"));
        assertTrue(join.fields.stream().filter(f->f.name.equals("pendingContentFrame")).allMatch(f->(f.access&Opcodes.ACC_PRIVATE)!=0));
        var scheduler=own(SCENE+"SurvivalSceneScheduler");
        for(String name:List.of("advance","receiveAuthorization","haveCurrentAuthorization"))
            assertFalse(method(scheduler,name).tryCatchBlocks.stream().anyMatch(c->"io/earthplatform/minecraft/survival/world/SurvivalRefusal".equals(c.type)));
        var lease=method(own(RENDER+"SurvivalCompleteOverlay$CurrentLease"),"tryLatchCurrent");
        assertTrue(calls(lease).stream().anyMatch(c->c.name.equals("tryLatchBeforeRevocation")));
        assertTrue(calls(lease).stream().anyMatch(c->c.name.equals("close")));
    }
    @Test void bothCarrierPathsCheckActualPendingWitnessBeforeNativeOrDhDraw()throws Exception{
        var carrier=own("io/earthplatform/compat/dh/shader/InstalledSurvivalShaderCarrier");
        var nativeLayer=method(carrier,"nativeLayer");var terrain=method(carrier,"terrain");
        assertTrue(callIndex(nativeLayer,SCENE+"SurvivalSceneFrameJoin","skipPendingContentFrame")
                <callIndex(nativeLayer,SCENE+"SurvivalNativeGpuBatch","draw"));
        assertTrue(callIndex(terrain,SCENE+"SurvivalSceneFrameJoin","skipPendingContentFrame")
                <callIndex(terrain,"io/earthplatform/compat/dh/shader/SurvivalDhPassWitness","capture"));
        var end=method(own(SCENE+"SurvivalSceneFrameJoin"),"endActualFrame");
        assertTrue(writeIndex(end,"pendingContentFrame")>=0);
        assertTrue(calls(end).stream().anyMatch(c->c.name.equals("releaseAfterPhysicalDrain")));
        assertTrue(end.tryCatchBlocks.stream().anyMatch(c->c.type==null));
    }
    @Test void rebindAndFrameIngressRefusePendingCleanupAndCloseValidationBeforeSwap()throws Exception{
        var join=own(SCENE+"SurvivalSceneFrameJoin");var rebind=method(join,"rebindCurrent");
        assertTrue(callIndex(rebind,RENDER+"SurvivalCompleteOverlay$FrameLatch","close")<writeIndex(rebind,"current"));
        assertTrue(calls(rebind).stream().anyMatch(c->c.name.equals("requireSurvivalRebindQuiescent")));
        var begin=method(join,"beginActualFrame");
        for(String field:List.of("pendingRebind","rebindValidation","pendingContentFrame","proposal")) {
            boolean found=false;for(var insn:begin.instructions)if(insn instanceof FieldInsnNode read&&read.name.equals(field))found=true;
            assertTrue(found,field);
        }
    }
    @Test void frameProposalIsRetainedBeforeValidationAndJoinOnlyClosesAfterAliasDrain()throws Exception{
        var type=own(SCENE+"SurvivalSceneFrameJoin");
        var accept=method(type,"acceptMainFrame");
        assertTrue(writeIndex(accept,"proposal")<callIndex(accept,SCENE+"SurvivalSceneFrameJoin","validateCandidate"));
        assertTrue(calls(accept).stream().anyMatch(c->c.name.equals("addSuppressed")));
        var close=method(type,"close");
        assertTrue(writeIndex(close,"closing")<callIndex(close,SCENE+"SurvivalSceneFrameJoin","restorePhysicalBindings"));
        assertTrue(callIndex(close,SCENE+"SurvivalSceneResources","release")<writeIndex(close,"closed"));
        assertTrue(callIndex(close,RENDER+"SurvivalCompleteOverlay$PlanInput","close")<writeIndex(close,"closed"));
    }
    @Test void authenticatedStartHasNoPublicHistoricalLatchInputAndRetainsBeforeTryingCas()throws Exception{
        var compiler=own(RENDER+"SurvivalNativePageCompiler");
        for(var method:compiler.methods)if((method.access&Opcodes.ACC_PUBLIC)!=0)
            assertFalse(method.desc.contains("SurvivalCompleteOverlay$FrameLatch"));
        var shell=own(RENDER+"SurvivalNativePageCompiler$AuthenticatedStart");
        for(var method:shell.methods)if((method.access&Opcodes.ACC_PUBLIC)!=0)
            assertFalse(method.desc.contains("SurvivalCompleteOverlay$FrameLatch"));
        assertTrue(calls(method(shell,"tryBeginAuthenticated")).stream().anyMatch(c->c.name.equals("tryLatchCurrent")));
        assertTrue(shell.fields.stream().anyMatch(f->f.name.equals("pending")));
        var scheduler=method(own(SCENE+"SurvivalSceneScheduler"),"advance");
        assertTrue(writeIndex(scheduler,"authenticatedStart")<callIndex(scheduler,RENDER+"SurvivalNativePageCompiler$AuthenticatedStart","tryBeginAuthenticated"));
    }
    @Test void schedulerCleanupIsReentrancyGuardedAndRestoresCarrierBeforePhysicalDrain()throws Exception{
        var close=method(own(SCENE+"SurvivalSceneScheduler"),"close");
        assertTrue(close.tryCatchBlocks.stream().anyMatch(c->c.type==null));
        assertTrue(writeIndex(close,"operating")>=0);
        assertTrue(callIndex(close,"io/earthplatform/compat/dh/shader/InstalledSurvivalShaderCarrier","close")
                <callIndex(close,SCENE+"SurvivalSceneOwnerRoster","drain"));
    }
    @Test void completeAdvanceTimingIncludesFinallyAndRegistryCodecIsPrecharged()throws Exception{
        var advance=method(own(SCENE+"SurvivalSceneScheduler"),"advance");
        assertTrue(advance.tryCatchBlocks.stream().anyMatch(c->c.type==null));
        assertTrue(writeIndex(advance,"advanceNanos")>=0);
        assertTrue(writeIndex(advance,"advanceOvershootNanos")>=0);
        var inputs=method(own(SCENE+"SurvivalSceneNativeInputs"),"start");
        assertTrue(writeIndex(inputs,"codecCharge")<callIndex(inputs,"io/earthplatform/minecraft/enginebridge/NativeCubeCodec","<init>"));
        var close=method(own(SCENE+"SurvivalSceneScheduler"),"close");
        assertTrue(callIndex(close,"io/earthplatform/minecraft/visual/NativeDistanceCpuBudget$Reservation","releaseAfterPhysicalDrain")
                <callIndex(close,"java/util/List","remove"));
    }

}
