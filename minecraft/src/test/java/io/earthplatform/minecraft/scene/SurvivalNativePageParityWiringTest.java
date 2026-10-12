package io.earthplatform.minecraft.scene;

import io.earthplatform.compat.dh.InstalledIrisRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact shipped Iris bytecode plus compiled probe wiring. This does not claim a transformed client ran. */
class SurvivalNativePageParityWiringTest {
    private static final String PROBE="io/earthplatform/minecraft/scene/SurvivalNativePageParityProbe";
    private static final String BUFFER="com/mojang/blaze3d/vertex/VertexBuffer";
    private static final String HELPER="net/irisshaders/iris/helpers/VertexBufferHelper";
    private static final String MIXIN="net/irisshaders/iris/mixin/MixinVertexBuffer";
    private static ClassNode node(byte[] bytes){var node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
    private static ClassNode read(String name)throws IOException {
        try(var in=SurvivalNativePageParityWiringTest.class.getClassLoader().getResourceAsStream(name+".class")){assertNotNull(in,name);return node(in.readAllBytes());}
    }
    private static MethodNode method(ClassNode node,String name){return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static List<MethodInsnNode> calls(MethodNode method){var result=new ArrayList<MethodInsnNode>();for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call)result.add(call);return result;}
    private static int call(MethodNode method,String owner,String name){return method.instructions.indexOf(calls(method).stream().filter(c->c.owner.equals(owner)&&c.name.equals(name)).findFirst().orElseThrow());}
    private static boolean constant(MethodNode method,String text){for(var instruction:method.instructions)if(instruction instanceof LdcInsnNode ldc&&text.equals(ldc.cst))return true;return false;}

    @Test void exactInstalledIrisCacheAndFullscreenRestoreCanRebindADeletedNativeOwner()throws Exception {
        String supplied=System.getProperty("earthplatform.installedIrisTestJar");assertNotNull(supplied,"Pinned official Iris input is required");
        var path=Path.of(supplied);
        assertEquals(InstalledIrisRuntime.IRIS_SHA256,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        try(var jar=new ZipFile(path.toFile())) {
            var mixin=read(jar,MIXIN);assertTrue(mixin.interfaces.contains(HELPER));
            for(String name:List.of("current","saved")) {
                var field=mixin.fields.stream().filter(f->f.name.equals(name)).findFirst().orElseThrow();
                assertEquals("L"+BUFFER+";",field.desc);assertEquals(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC,field.access);
            }
            var save=method(mixin,"saveBinding");
            var saveFields=new ArrayList<FieldInsnNode>();for(var i:save.instructions)if(i instanceof FieldInsnNode f)saveFields.add(f);
            assertEquals(List.of("current","saved"),saveFields.stream().map(f->f.name).toList());
            assertEquals(List.of(Opcodes.GETSTATIC,Opcodes.PUTSTATIC),saveFields.stream().map(FieldInsnNode::getOpcode).toList());
            assertEquals(List.of("bind","unbind"),calls(method(mixin,"restoreBinding")).stream().filter(c->c.owner.equals(BUFFER)).map(c->c.name).toList());
            for(String hook:List.of("bindHelper","unbindHelper")) {
                var body=method(mixin,hook);
                assertTrue(java.util.Arrays.stream(body.instructions.toArray()).anyMatch(i->i instanceof FieldInsnNode f&&f.owner.equals(MIXIN)&&f.name.equals("current")&&f.getOpcode()==Opcodes.PUTSTATIC));
                var annotations=new ArrayList<AnnotationNode>();if(body.visibleAnnotations!=null)annotations.addAll(body.visibleAnnotations);if(body.invisibleAnnotations!=null)annotations.addAll(body.invisibleAnnotations);
                assertTrue(annotations.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")&&a.values.toString().contains(hook.equals("bindHelper")?"bind":"unbind")));
            }
            var fullscreen=read(jar,"net/irisshaders/iris/pathways/FullScreenQuadRenderer");var begin=method(fullscreen,"begin");
            assertTrue(call(begin,HELPER,"saveBinding")<call(begin,BUFFER,"bind"));
            assertTrue(calls(method(fullscreen,"end")).stream().anyMatch(c->c.owner.equals(HELPER)&&c.name.equals("restoreBinding")));
        }
        var nativeBuffer=read(BUFFER);
        assertTrue(calls(method(nativeBuffer,"bind")).stream().anyMatch(c->c.owner.equals("com/mojang/blaze3d/platform/GlStateManager")&&c.name.equals("_glBindVertexArray")));
        var close=method(nativeBuffer,"close");
        assertTrue(java.util.Arrays.stream(close.instructions.toArray()).anyMatch(i->i instanceof FieldInsnNode f&&f.name.equals("arrayObjectId")&&f.getOpcode()==Opcodes.PUTFIELD));
    }
    private static ClassNode read(ZipFile jar,String name)throws IOException {var entry=jar.getEntry(name+".class");assertNotNull(entry,name);try(var in=jar.getInputStream(entry)){return node(in.readAllBytes());}}

    @Test void referenceScopeStartsBeforeUploadAndClosesBeforeGpuChargeRelease()throws Exception {
        var probe=read(PROBE);var run=method(probe,"run");
        assertTrue(call(run,PROBE+"$ReferenceBindings","<init>")<call(run,"io/earthplatform/minecraft/scene/SceneMesh","begin"));
        assertTrue(call(run,"io/earthplatform/minecraft/scene/SceneMesh$Build","finish")<call(run,PROBE+"$ReferenceCleanup","restoreHost"));
        assertTrue(call(run,PROBE+"$ReferenceCleanup","restoreHost")<call(run,PROBE,"readReference"));
        var close=method(probe,"closeReference");
        assertTrue(call(close,PROBE+"$ReferenceCleanup","close")<call(close,"io/earthplatform/minecraft/visual/NativeVisualBudget$Reservation","releaseAfterPhysicalClose"));
        var cache=read(PROBE+"$IrisBufferCache");
        assertTrue(calls(method(cache,"captureIfPresent")).stream().anyMatch(c->c.name.equals("getInterfaces")));
        assertTrue(calls(method(cache,"captureIfPresent")).stream().anyMatch(c->c.name.equals("isLoaded")));
        assertTrue(calls(method(cache,"restore")).stream().anyMatch(c->c.owner.equals(BUFFER)&&c.name.equals("bind")));
        assertTrue(calls(method(cache,"restore")).stream().anyMatch(c->c.owner.equals(BUFFER)&&c.name.equals("unbind")));
        for(var m:cache.methods)assertFalse(calls(m).stream().anyMatch(c->c.name.equals("saveBinding")||(c.owner.equals("java/lang/reflect/Field")&&c.name.startsWith("set")&&!c.name.equals("setAccessible"))),"Cache fields are read-only");
        var bindings=method(read(PROBE+"$ReferenceBindings"),"restore");
        assertTrue(call(bindings,PROBE+"$IrisBufferCache","restore")<call(bindings,"io/earthplatform/minecraft/scene/SceneMesh$BufferBindings","restore"));
    }

    @Test void exactPinnedFramePostOccursAfterGameRendererInTheSameNativeFrame()throws Exception {
        var tick=method(read("net/minecraft/client/Minecraft"),"runTick");
        int before=call(tick,"net/neoforged/neoforge/client/ClientHooks","fireRenderFramePre");
        int render=call(tick,"net/minecraft/client/renderer/GameRenderer","render");
        int after=call(tick,"net/neoforged/neoforge/client/ClientHooks","fireRenderFramePost");
        assertTrue(before<render&&render<after);
        var hooks=read("net/neoforged/neoforge/client/ClientHooks");
        assertTrue(java.util.Arrays.stream(method(hooks,"fireRenderFramePre").instructions.toArray()).anyMatch(i->i instanceof TypeInsnNode n&&n.desc.equals("net/neoforged/neoforge/client/event/RenderFrameEvent$Pre")));
        assertTrue(java.util.Arrays.stream(method(hooks,"fireRenderFramePost").instructions.toArray()).anyMatch(i->i instanceof TypeInsnNode n&&n.desc.equals("net/neoforged/neoforge/client/event/RenderFrameEvent$Post")));
    }

    @Test void probeChecksCpuCleanupAndFrameIntervalsBeforeTheNextCase()throws Exception {
        var probe=read(PROBE);
        for(String phase:List.of("survival-parity.probe.entry","survival-parity.reference.cpu.slice","survival-parity.reference.upload.exit","survival-parity.page.cpu.entry","survival-parity.page.cpu.slice","survival-parity.page.cpu.exit","survival-parity.probe.exit"))assertTrue(constant(method(probe,"run"),phase),phase);
        assertTrue(constant(method(probe,"close"),"survival-parity.probe.close.exit"));
        var client=read("io/earthplatform/minecraft/scene/SurvivalNativePageParityClient");
        assertTrue(constant(method(client,"frame"),"survival-parity.frame.pre"));
        assertTrue(constant(method(client,"watchdog"),"survival-parity.frame.post"));
        assertTrue(constant(method(client,"runEligible"),"survival-parity.anchor.entry"));

        var session=read("io/earthplatform/minecraft/scene/SurvivalNativePageParityClient$Session");
        assertTrue(constant(method(session,"anchorExit"),"survival-parity.anchor.exit"));
        assertTrue(calls(method(session,"finishTerminal")).stream().anyMatch(c->c.name.equals("successAwaitingFrame")));
        assertTrue(calls(method(session,"completeFrame")).stream().anyMatch(c->c.name.equals("requireFence")));
        assertTrue(constant(method(session,"closeCase"),"survival-parity.case.close.exit"));
        assertTrue(constant(method(session,"closeCase"),"survival-parity.case.lease.release"));
    }
}
