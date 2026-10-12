package io.earthplatform.minecraft.coregate;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compiled host routing contracts; actual transformed pipelines/GL are a separate gate. */
class RollingShaderHostWiringTest {
    private static ClassNode read(String suffix)throws Exception{
        try(var stream=RollingShaderHostWiringTest.class.getClassLoader().getResourceAsStream("io/earthplatform/minecraft/coregate/RollingGateClient"+suffix+".class")){
            assertNotNull(stream);var node=new ClassNode();new ClassReader(stream).accept(node,0);return node;
        }
    }
    private static MethodNode method(String suffix,String name)throws Exception{return read(suffix).methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static List<MethodInsnNode> calls(MethodNode method){return java.util.stream.StreamSupport.stream(method.instructions.spliterator(),false).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();}
    private static boolean calls(MethodNode method,String name){return calls(method).stream().anyMatch(c->c.name.equals(name));}
    @Test void shaderInitializationUsesBorrowedBridgeAndNoDiagnosticTexture()throws Exception{
        var m=method("$Session","initializeShader");assertTrue(calls(m,"start"));assertTrue(calls(m,"restorationPending"));
        assertTrue(calls(m).stream().anyMatch(c->c.owner.endsWith("InstalledShaderCarrier")&&c.name.equals("<init>")));
        assertFalse(calls(m).stream().anyMatch(c->c.owner.endsWith("DynamicTexture")||c.owner.endsWith("InstalledDhEvents")));
    }
    @Test void shaderFramePreparesBeforePinningOneExactDisplayedDigest()throws Exception{
        var m=method("$Session","shaderFrame");assertEquals(1,calls(m).stream().filter(c->c.name.equals("updatePreparedPair")).count());
        assertTrue(calls(m,"captureIris"));assertTrue(calls(m,"isCurrent"));assertTrue(calls(m,"manifestDigest"));
        int update=-1,frame=-1;for(int i=0;i<m.instructions.size();i++)if(m.instructions.get(i)instanceof MethodInsnNode c){if(c.name.equals("updatePreparedPair"))update=i;if(c.owner.endsWith("InstalledShaderCarrier$Frame")&&c.name.equals("<init>"))frame=i;}
        assertTrue(update>=0&&frame>update);assertTrue(java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false).anyMatch(i->i instanceof FieldInsnNode f&&f.name.equals("shaderPreparedFrame")&&f.getOpcode()==Opcodes.PUTFIELD));
    }
    @Test void shaderNativeUsesCurrentAndHistoricalLayerApisWithoutFarDraw()throws Exception{
        var methods=read("$Session").methods.stream().filter(m->m.name.equals("shaderNative")||m.name.startsWith("lambda$shaderNative$")).toList();
        var names=methods.stream().flatMap(m->calls(m).stream()).map(c->c.name).toList();
        assertTrue(names.contains("drawLayer"));assertTrue(names.contains("drawPresentedLayer"));assertTrue(names.contains("shaderPass"));
        assertFalse(methods.stream().flatMap(m->calls(m).stream()).anyMatch(c->c.owner.endsWith("DhPreparedLodView")||c.owner.endsWith("RollingDepthComposition")));
    }
    @Test void shaderFarUsesExistingExactFarCapabilitiesWithoutNativeOrDepthComposition()throws Exception{
        var methods=read("$Session").methods.stream().filter(m->m.name.equals("shaderFar")||m.name.equals("shaderDrawCurrentFar")||m.name.startsWith("lambda$shaderFar$")).toList();
        var c=methods.stream().flatMap(m->calls(m).stream()).toList();assertTrue(c.stream().anyMatch(i->i.name.equals("drawPresented")));assertTrue(c.stream().anyMatch(i->i.name.equals("draw")&&i.owner.endsWith("DhPreparedLodView")));
        assertFalse(c.stream().anyMatch(i->i.owner.endsWith("SceneMesh")||i.owner.endsWith("RollingDepthComposition")));
    }
    @Test void currentAndHistoricalAuthorityStayDistinct()throws Exception{
        var m=method("$Session","shaderPresentationCurrent");for(var name:List.of("isCurrent","frozen","current","publishedFarCoverageReady","publishedNativeCurrent"))assertTrue(calls(m,name),name);
        assertTrue(calls(m,"manifestDigest"));
        var current=method("$Session","publishedNativeCurrent");
        for(var name:List.of("distanceMode","isPublishedCurrent","requirePrepared","allMatch"))assertTrue(calls(current,name),name);
        assertTrue(calls(method("$Session","activePresentation"),"distanceMode"),"History must select the explicit body capability in distance mode");
    }
    @Test void nativeNeighborhoodHasExplicitNonEmissivePolicyAndLiveDirectionalCapture()throws Exception{
        var m=method("$Session","nativeNeighborhood");assertTrue(calls(m,"disableDirectionalShading"));assertTrue(calls(m,"isCurrent"));
        assertTrue(java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false).anyMatch(i->i instanceof FieldInsnNode f&&f.name.equals("SHADER_OUTDOOR_NON_EMISSIVE")));
    }
    @Test void actualMeshByteAccountingUsesCapturedPayload()throws Exception{
        var all=read("$Session").methods.stream().flatMap(m->calls(m).stream()).toList();
        // invokedynamic method-reference target is checked independently below.
        boolean exact=false;for(var m:read("$Session").methods)for(var i:m.instructions)if(i instanceof InvokeDynamicInsnNode d)for(var a:d.bsmArgs)if(a instanceof org.objectweb.asm.Handle h&&h.getOwner().endsWith("SceneMesh")&&h.getName().equals("bytes"))exact=true;
        assertTrue(exact);assertFalse(all.stream().anyMatch(c->c.name.equals("glFinish")||c.name.equals("glReadPixels")));
    }
    @Test void nativeRestorationPrecedesOwnedResourceDestruction()throws Exception{
        var m=method("$Session","close");int restore=-1,mesh=-1;for(int i=0;i<m.instructions.size();i++)if(m.instructions.get(i)instanceof MethodInsnNode c){if(c.name.equals("restorePendingState"))restore=i;if(c.name.equals("closeExcept"))mesh=i;}
        assertTrue(restore>=0&&mesh>restore);assertTrue(calls(method("$Session","closeInstalledBridge"),"close"));
    }
    @Test void mainScreenshotRequiresBothNativeAndFarStages()throws Exception{
        var m=method("$Session","shaderDrawComplete");assertTrue(java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false).anyMatch(i->i.getOpcode()==Opcodes.IAND));
        assertTrue(java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false).anyMatch(i->i instanceof FieldInsnNode f&&f.name.equals("shaderMainDrawMask")));
        assertTrue(java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false).anyMatch(i->i instanceof FieldInsnNode f&&f.name.equals("drawn")&&f.getOpcode()==Opcodes.PUTFIELD));
    }
    @Test void afterLevelRemainsNoSubmissionAndInstalledPathsKeepExclusiveModeChecks()throws Exception{
        assertFalse(calls(method("","render")).stream().anyMatch(c->Set.of("draw","render","shaderNative","shaderFar").contains(c.name)));
        assertTrue(calls(method("","frame"),"installedCurrent"));assertTrue(calls(method("","frame"),"supportedMode"));
    }
}
