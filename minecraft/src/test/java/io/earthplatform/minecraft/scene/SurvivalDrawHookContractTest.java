package io.earthplatform.minecraft.scene;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Structural source-authentication checks only. No fake witness, GL context or scene is minted. */
class SurvivalDrawHookContractTest {
    private static final String SCENE="io/earthplatform/minecraft/scene/",SHADER="io/earthplatform/compat/dh/shader/";
    private static ClassNode node(byte[] bytes){var n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
    private static ClassNode own(String name)throws Exception{try(var in=SurvivalDrawHookContractTest.class.getClassLoader().getResourceAsStream(name+".class")){assertNotNull(in,name);return node(in.readAllBytes());}}
    private static ClassNode external(String property,String name)throws Exception{
        String file=System.getProperty(property);assertNotNull(file,property);
        try(var jar=new ZipFile(Path.of(file).toFile())){var entry=jar.getEntry(name+".class");assertNotNull(entry,name);try(var in=jar.getInputStream(entry)){return node(in.readAllBytes());}}
    }
    private static MethodNode method(ClassNode type,String name){return type.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static List<MethodInsnNode> calls(MethodNode method){var out=new ArrayList<MethodInsnNode>();for(var i:method.instructions)if(i instanceof MethodInsnNode c)out.add(c);return out;}
    private static List<String> names(MethodNode method){return calls(method).stream().map(c->c.name).toList();}
    private static void hasCall(MethodNode method,String name){assertTrue(names(method).contains(name),method.name+" requires "+name);}
    private static List<AnnotationNode> annotations(List<AnnotationNode> a,List<AnnotationNode> b){var out=new ArrayList<AnnotationNode>();if(a!=null)out.addAll(a);if(b!=null)out.addAll(b);return out;}
    private static Object value(AnnotationNode a,String name){for(int i=0;i<a.values.size();i+=2)if(a.values.get(i).equals(name))return a.values.get(i+1);return null;}

    @Test void drawCapabilitiesHavePrivateOriginsAndTypedOnlySubmission()throws Exception{
        for(String type:List.of(SCENE+"SurvivalSceneFrameJoin$NativeDraw",SHADER+"SurvivalDhPassWitness"))
            for(var m:own(type).methods)if(m.name.equals("<init>"))assertTrue((m.access&Opcodes.ACC_PRIVATE)!=0);
        var capture=method(own(SHADER+"SurvivalDhPassWitness"),"capture");assertEquals(0,capture.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_PROTECTED));
        assertEquals("(L"+SCENE+"SurvivalSceneFrameJoin$AcceptedFrame;)L"+SHADER+"SurvivalDhPassWitness;",capture.desc);
        assertEquals("(L"+SCENE+"SurvivalSceneFrameJoin$AcceptedFrame;)V",method(own(SCENE+"SurvivalNativeGpuBatch"),"draw").desc);
        assertEquals("(L"+SHADER+"SurvivalDhPassWitness;)V",method(own("io/earthplatform/minecraft/lod/SurvivalDhGpuPages$Completion"),"drawJoined").desc);
        for(var m:own(SHADER+"SurvivalDhHookAccess").methods){var result=Type.getReturnType(m.desc);assertNotEquals(Type.ARRAY,result.getSort());assertFalse(result.getClassName().startsWith("org.joml."));assertTrue((m.access&Opcodes.ACC_ABSTRACT)!=0);}
    }
    @Test void dhOriginReadsOnlyActualSingletonHeldProgramAndNeverLazyGetter()throws Exception{
        var witness=own(SHADER+"SurvivalDhPassWitness");var access=method(witness,"actualAccess");
        assertEquals("()L"+SHADER+"SurvivalDhHookAccess;",access.desc);
        assertTrue(java.util.Arrays.stream(access.instructions.toArray()).anyMatch(i->i instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.GETSTATIC
                &&f.owner.equals("com/seibel/distanthorizons/common/render/openGl/GlDhTerrainRenderer_neoforge")&&f.name.equals("INSTANCE")));
        hasCall(access,"earthplatform$heldTerrainProgram");
        assertFalse(witness.methods.stream().flatMap(m->calls(m).stream()).anyMatch(c->c.name.equals("getTerrainShaderProgram")));
        hasCall(method(witness,"requireCurrent"),"earthplatform$survivalDhInvocation");
        hasCall(method(witness,"requireCurrent"),"requireDrawResources");
        var hook=own(SHADER+"mixin/DhIrisTerrainPassMixin");var body=method(hook,"earth$submitOwnedPass");
        assertTrue(body.tryCatchBlocks.stream().anyMatch(b->b.type==null));hasCall(body,"requireSurvivalHookReservation");hasCall(body,"capture");
        for(var f:hook.fields)if(f.name.startsWith("earthplatform$survivalDh"))assertTrue((f.access&Opcodes.ACC_PRIVATE)!=0,f.name);
        var inject=annotations(body.visibleAnnotations,body.invisibleAnnotations).stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElseThrow();
        assertEquals(1,value(inject,"require"));var at=(AnnotationNode)((List<?>)value(inject,"at")).getFirst();assertEquals(1,value(at,"ordinal"));
        assertEquals("Lcom/seibel/distanthorizons/common/wrappers/minecraft/MinecraftGLWrapper;enableFaceCulling()V",value(at,"target"));
    }
    @Test void nativeAndDhPhysicalCallsHaveLastMomentTypedChecksWithoutWholeRosterReentry()throws Exception{
        var nativeType=own(SCENE+"SurvivalNativeGpuPage");
        var draw=nativeType.methods.stream().filter(m->m.name.equals("drawLayer")&&m.desc.contains("$NativeDraw;")).findFirst().orElseThrow();
        var sequence=names(draw);assertEquals(2,sequence.stream().filter(n->n.equals("beforeRange")).count());
        for(int i=0;i<sequence.size();i++)if(sequence.get(i).equals("drawRange"))assertEquals("beforeRange",sequence.get(i-1));
        var dh=method(own("io/earthplatform/minecraft/lod/SurvivalDhGpuPages$Completion"),"drawJoined");var far=names(dh);
        int physical=far.indexOf("draw");assertTrue(physical>1);assertEquals("multiplyExact",far.get(physical-1));assertEquals("beforeRange",far.get(physical-2));hasCall(dh,"restore");
        var live=method(own(SCENE+"SurvivalSceneFrameJoin$NativeDraw"),"requireCurrent");hasCall(live,"requireDrawResources");hasCall(live,"requireDrawConfiguration");
        assertFalse(names(live).contains("requireSameFrameResources"));assertFalse(names(live).contains("validateCandidate"));
        var settings=method(own("io/earthplatform/minecraft/lod/SurvivalDhGpuPages$Completion"),"requireDrawConfiguration");assertFalse(names(settings).contains("requireComplete"));
        hasCall(settings,"requireDhConfigurationOwner");hasCall(settings,"requireCurrentConfiguration");
    }
    @Test void restorationIsRetainedBeforeSetupAndPhysicalCleanupPrecedesUpstreamRetirement()throws Exception{
        var draw=method(own(SCENE+"SurvivalNativeGpuBatch"),"draw");var seq=names(draw);
        assertTrue(seq.indexOf("reserve")<seq.indexOf("captureState"));assertTrue(seq.indexOf("captureState")<seq.indexOf("setup"));
        int saved=-1,setup=-1;for(int i=0;i<draw.instructions.size();i++){var op=draw.instructions.get(i);if(op instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTFIELD&&f.name.equals("capturedRestore"))saved=i;if(op instanceof MethodInsnNode c&&c.name.equals("setup"))setup=i;}
        assertTrue(saved>=0&&saved<setup);hasCall(method(own(SCENE+"SurvivalNativeGpuBatch"),"restorePendingState"),"releaseAfterPhysicalDrain");
        var carrier=own(SHADER+"InstalledSurvivalShaderCarrier");hasCall(method(carrier,"invalidate"),"retire");hasCall(method(carrier,"invalidate"),"refuse");
        hasCall(method(carrier,"restoreNativeAndPhysical"),"restorePendingState");hasCall(method(carrier,"restoreNativeAndPhysical"),"restorePhysicalBindings");
        var join=own(SCENE+"SurvivalSceneFrameJoin");assertTrue(join.fields.stream().anyMatch(f->f.name.equals("completedMainWitnesses")));assertTrue(join.fields.stream().anyMatch(f->f.name.equals("completedMainDraws")));
        hasCall(method(own(SCENE+"SurvivalSceneFrameJoin$AcceptedFrame"),"completedDhPass"),"requireCompletionReady");
        hasCall(method(own(SHADER+"SurvivalDhPassWitness"),"requireCompletionReady"),"requireJoinedDrawComplete");
    }
    @Test void restorationFailureCannotBeSwallowedByTheOuterLegacyDiagnosticCatch()throws Exception{
        var carrier=own(SHADER+"InstalledSurvivalShaderCarrier");
        var contain=method(carrier,"contain");hasCall(contain,"invalidate");hasCall(contain,"append");hasCall(contain,"propagate");
        assertTrue(java.util.Arrays.stream(contain.instructions.toArray()).anyMatch(i->i instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTFIELD&&f.name.equals("restorationFailureEscapes")));
        assertTrue(java.util.Arrays.stream(contain.instructions.toArray()).anyMatch(i->i instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.GETFIELD&&f.name.equals("restorationFailureEscapes")));
        var terrain=method(carrier,"terrain");hasCall(terrain,"retire");hasCall(terrain,"contain");
        var far=method(carrier,"restoreFarAndPass");hasCall(far,"restorePendingState");hasCall(far,"retire");hasCall(far,"propagate");
        assertTrue(carrier.fields.stream().anyMatch(f->f.name.equals("pendingPass")&&(f.access&Opcodes.ACC_PRIVATE)!=0));
    }
    @Test void dhOriginsAndCompletionComeFromTheExactPhysicalOwner()throws Exception{
        var witness=own(SHADER+"SurvivalDhPassWitness");var page=method(witness,"page");
        assertEquals("(Lio/earthplatform/minecraft/lod/SurvivalDhGpuPages$Completion;Lio/earthplatform/minecraft/lod/DhGeographicLodData$TileKey;)V",page.desc);
        hasCall(page,"requireActiveTile");hasCall(page,"page");
        assertTrue(java.util.Arrays.stream(page.instructions.toArray()).anyMatch(i->i instanceof IntInsnNode n&&n.operand==2048),"Signed Y follows DhTerrainGeometry.PAGE_HEIGHT, never the quad/page-face cap");
        var offset=method(own(SHADER+"IrisShaderFarBackend"),"requireSurvivalPage");
        assertTrue(java.util.Arrays.stream(offset.instructions.toArray()).anyMatch(i->i instanceof IntInsnNode n&&n.operand==2048));
        hasCall(method(witness,"beforeRange"),"requireSurvivalPage");
        var physical=own("io/earthplatform/minecraft/lod/SurvivalDhGpuPages$Completion");
        hasCall(method(physical,"drawJoined"),"endPhysical");
        assertTrue(java.util.Arrays.stream(method(physical,"requireJoinedDrawComplete").instructions.toArray()).anyMatch(i->i instanceof FieldInsnNode f&&f.name.equals("completedDraw")));
        hasCall(method(witness,"retire"),"forgetJoinedDraw");
    }
    @Test void earlierAdmissionWrapsTheExactFirstSolidCallAndDelegatesOnceInFinally()throws Exception{
        var mixin=own("io/earthplatform/minecraft/mixin/NativeIrisTerrainLayerMixin");var wrapper=method(mixin,"earthplatform$survivalPrepareMain");
        var annotation=annotations(wrapper.visibleAnnotations,wrapper.invisibleAnnotations).stream().filter(a->a.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")).findFirst().orElseThrow();
        assertEquals(1,value(annotation,"require"));assertEquals(1,value(annotation,"allow"));
        var at=(AnnotationNode)((List<?>)value(annotation,"at")).getFirst();assertEquals(0,value(at,"ordinal"));
        assertEquals("Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",value(at,"target"));
        assertEquals(1,calls(wrapper).stream().filter(c->c.owner.equals("com/llamalad7/mixinextras/injector/wrapoperation/Operation")&&c.name.equals("call")).count());
        assertTrue(wrapper.tryCatchBlocks.stream().anyMatch(t->t.type==null));
        assertTrue(names(wrapper).indexOf("prepareActualMainInvocation")<names(wrapper).indexOf("call"));
        hasCall(wrapper,"getInstance");hasCall(wrapper,"solid");
        var join=own(SCENE+"SurvivalSceneFrameJoin");hasCall(method(join,"acceptMainFrame"),"requireMainPreparation");
        assertFalse(names(method(join,"acceptMainFrame")).contains("requireMainInvocation"));
        assertFalse(names(method(join,"captureRealMainFrame")).contains("getPhase"));
        hasCall(method(join,"requireMainInvocation"),"getPhase");
        var render=method(own("net/minecraft/client/renderer/LevelRenderer"),"renderLevel");
        var layers=calls(render).stream().filter(c->c.owner.equals("net/minecraft/client/renderer/LevelRenderer")&&c.name.equals("renderSectionLayer")).toList();assertEquals(7,layers.size());
        var first=layers.getFirst();var previous=new ArrayList<AbstractInsnNode>();for(var i=first.getPrevious();i!=null&&previous.size()<7;i=i.getPrevious())if(i.getOpcode()>=0)previous.addFirst(i);
        assertEquals(Opcodes.ALOAD,previous.get(0).getOpcode());assertEquals(0,((VarInsnNode)previous.get(0)).var);
        assertEquals("solid",((MethodInsnNode)previous.get(1)).name);
        for(int i=0;i<3;i++){assertEquals(Opcodes.DLOAD,previous.get(i+2).getOpcode());assertEquals(12+2*i,((VarInsnNode)previous.get(i+2)).var);}
        assertEquals(6,((VarInsnNode)previous.get(5)).var);assertEquals(7,((VarInsnNode)previous.get(6)).var);
    }
    @Test void pinnedDhHeadPrecedesNativeAnchorAndUsesForwardDepthState()throws Exception{
        var upstream=external("earthplatform.installedDhTestJar","com/seibel/distanthorizons/neoforge/mixins/client/MixinLevelRenderer");
        var head=method(upstream,"renderChunkLayer");var injection=annotations(head.visibleAnnotations,head.invisibleAnnotations).stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElseThrow();
        var at=(AnnotationNode)((List<?>)value(injection,"at")).getFirst();assertEquals("HEAD",value(at,"value"));
        hasCall(head,"solid");hasCall(head,"renderLods");
        var meta=external("earthplatform.installedDhTestJar","com/seibel/distanthorizons/common/render/openGl/GlDhMetaRenderer_neoforge");
        var setup=method(meta,"setGLState");hasCall(setup,"enableDepthTest");hasCall(setup,"enableDepthMask");
        assertTrue(java.util.Arrays.stream(setup.instructions.toArray()).anyMatch(i->i instanceof IntInsnNode n&&n.operand==513));
        var check=method(own(SHADER+"IrisShaderFarBackend"),"requireSurvivalPass");hasCall(check,"glIsEnabled");hasCall(check,"glGetBoolean");hasCall(check,"glGetInteger");
    }
    @Test void exactPinnedNativeFramebufferFieldsAndUpstreamBindingChoiceMatch()throws Exception{
        var upstream=external("earthplatform.installedIrisTestJar","net/irisshaders/iris/pipeline/programs/ExtendedShader");
        for(String name:List.of("writingToBeforeTranslucent","writingToAfterTranslucent")){
            var field=upstream.fields.stream().filter(f->f.name.equals(name)).findFirst().orElseThrow();assertEquals(Opcodes.ACC_PRIVATE|Opcodes.ACC_FINAL,field.access);
            assertEquals("Lnet/irisshaders/iris/gl/framebuffer/GlFramebuffer;",field.desc);
        }
        var bound=method(upstream,"bindFramebuffer");assertEquals(2,names(bound).stream().filter(n->n.equals("bind")).count());
        assertTrue(java.util.Arrays.stream(bound.instructions.toArray()).anyMatch(i->i instanceof FieldInsnNode f&&f.name.equals("isBeforeTranslucent")));
        var check=method(own(SCENE+"NativeIrisShaderStages"),"requireSurvivalBoundState");
        for(String name:List.of("earthplatform$beforeFramebuffer","earthplatform$afterFramebuffer","glIsFramebuffer","glIsProgram","glGetDoublev","check"))hasCall(check,name);
        hasCall(method(own(SCENE+"SurvivalSceneFrameJoin$NativeDraw"),"beforeRange"),"requireUploaded");
    }
    @Test void exactPinnedDhRendererInvokesItsAlreadyHeldProgram()throws Exception{
        var upstream=external("earthplatform.installedDhTestJar","com/seibel/distanthorizons/common/render/openGl/GlDhTerrainRenderer_neoforge");
        var field=upstream.fields.stream().filter(f->f.name.equals("terrainShaderProgram")).findFirst().orElseThrow();assertEquals(Opcodes.ACC_PRIVATE,field.access);
        assertEquals("Lcom/seibel/distanthorizons/common/render/openGl/terrain/GlDhTerrainShaderProgram_neoforge;",field.desc);
        var body=method(upstream,"render");assertEquals(List.of("getTerrainShaderProgram","tryInit","render"),names(body));
        assertEquals(2,java.util.Arrays.stream(body.instructions.toArray()).filter(i->i instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.GETFIELD&&f.name.equals("terrainShaderProgram")).count());
        var accessor=method(own(SHADER+"mixin/DhSurvivalTerrainRendererAccess"),"earthplatform$heldTerrainProgram");
        assertEquals("()"+field.desc,accessor.desc);assertTrue((accessor.access&Opcodes.ACC_ABSTRACT)!=0);
    }
}
