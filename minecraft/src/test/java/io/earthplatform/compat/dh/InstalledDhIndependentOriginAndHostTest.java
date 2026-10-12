package io.earthplatform.compat.dh;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pinned-origin tests and compiled host wiring checks; no claim that an actual game frame has run. */
class InstalledDhIndependentOriginAndHostTest {
    static Path suppliedJar(){return Path.of(java.util.Objects.requireNonNull(System.getProperty("earthplatform.installedDhTestJar")));}
    static Class<?> actualApi()throws Exception{return Class.forName("com.seibel.distanthorizons.api.DhApi");}
    static ClassNode read(String name)throws IOException{
        try(var in=InstalledDhIndependentOriginAndHostTest.class.getClassLoader().getResourceAsStream(name+".class")){
            assertNotNull(in,"Compiled candidate class required: "+name);var node=new ClassNode();new ClassReader(in).accept(node,0);return node;
        }
    }
    static MethodNode method(ClassNode node,String name){return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    static List<MethodInsnNode> calls(MethodNode method){return java.util.stream.StreamSupport.stream(method.instructions.spliterator(),false).filter(i->i instanceof MethodInsnNode).map(i->(MethodInsnNode)i).toList();}
    static int callIndex(MethodNode method,String owner,String name){for(var i:method.instructions)if(i instanceof MethodInsnNode m&&m.owner.equals(owner)&&m.name.equals(name))return method.instructions.indexOf(i);return -1;}

    @Test void actualPinnedClasspathOriginPassesAndForeignCodeSourceCannotBorrowItsHash()throws Exception{
        var api=actualApi();var good=new InstalledDhResolver.InstalledMod("3.3.3",suppliedJar(),api.getClassLoader());
        assertDoesNotThrow(()->InstalledDhResolver.verifyOrigin(good,api));
        assertThrows(IOException.class,()->InstalledDhResolver.verifyOrigin(good,getClass()));
        var foreignPath=new InstalledDhResolver.InstalledMod("3.3.3",Path.of("foreign.jar"),api.getClassLoader());
        assertThrows(IOException.class,()->InstalledDhResolver.verifyOrigin(foreignPath,api));
    }
    @Test void claimedSecureJarModuleCannotAcceptUnnamedClasspathApi()throws Exception{
        var api=actualApi();assertFalse(api.getModule().isNamed(),"This focused harness exercises plain-classpath identity only");
        var forged=new InstalledDhResolver.InstalledMod("3.3.3",suppliedJar(),api.getClassLoader(),"distanthorizons",suppliedJar().toUri());
        assertThrows(IOException.class,()->InstalledDhResolver.verifyOrigin(forged,api));
    }
    @Test void duplicateApiNameWithoutInstalledProtectionDomainIsRejected()throws Exception{
        byte[] definition;try(var in=getClass().getClassLoader().getResourceAsStream("com/seibel/distanthorizons/api/DhApi.class")){assertNotNull(in);definition=in.readAllBytes();}
        class DuplicateLoader extends ClassLoader {DuplicateLoader(){super(InstalledDhIndependentOriginAndHostTest.class.getClassLoader());}Class<?> duplicate(){return defineClass("com.seibel.distanthorizons.api.DhApi",definition,0,definition.length);}}
        var duplicate=new DuplicateLoader().duplicate();var installed=new InstalledDhResolver.InstalledMod("3.3.3",suppliedJar(),duplicate.getClassLoader());
        assertNotSame(actualApi(),duplicate);assertThrows(IOException.class,()->InstalledDhResolver.verifyOrigin(installed,duplicate));
    }
    @Test void productionResolverValidatesRealArtifactAndOriginThenWaitsForRealDelayedInitialization()throws Exception{
        var api=actualApi();var resolver=new InstalledDhResolver(()->Optional.of(new InstalledDhResolver.InstalledMod("3.3.3",suppliedJar(),api.getClassLoader())));
        var result=resolver.resolve();assertEquals(InstalledDhResolver.Status.WAITING_FOR_INIT,result.status(),result.explanation());assertNull(result.runtime());
    }
    @Test void oldAfterLevelHookContainsNoHostOrDriverSubmission()throws Exception{
        var render=method(read("io/earthplatform/minecraft/coregate/RollingGateClient"),"render");
        for(var call:calls(render))assertFalse(call.owner.startsWith("io/earthplatform/"),"AFTER_LEVEL may not call our render/session pipeline: "+call.owner+"."+call.name);
    }
    @Test void frameIdentityComesFromFramePreMethodAndIsNotIncrementedInsideSession()throws Exception{
        var host=read("io/earthplatform/minecraft/coregate/RollingGateClient");var frame=method(host,"frame");
        assertTrue(frame.desc.contains("RenderFrameEvent$Pre"));assertTrue(calls(frame).stream().anyMatch(c->c.owner.equals("java/lang/Math")&&c.name.equals("incrementExact")));
        int writes=0;for(var m:host.methods)for(var i:m.instructions)if(i instanceof FieldInsnNode f&&f.name.equals("actualRenderFrame")&&f.getOpcode()==Opcodes.PUTSTATIC){assertEquals("frame",m.name);writes++;}
        assertEquals(1,writes);
        var session=read("io/earthplatform/minecraft/coregate/RollingGateClient$Session");
        for(var m:session.methods)for(var i:m.instructions)if(i instanceof FieldInsnNode f)assertFalse(f.name.equals("actualRenderFrame")&&f.getOpcode()==Opcodes.PUTSTATIC);
    }
    @Test void carrierLeaseUsesBoundedFullDescriptorFingerprintInsteadOfVerboseWorldText()throws Exception{
        var session=read("io/earthplatform/minecraft/coregate/RollingGateClient$Session");var initialize=method(session,"initialize");
        int descriptor=callIndex(initialize,"io/earthplatform/minecraft/coregate/RollingSessionDescriptor","fingerprint");
        int acquire=callIndex(initialize,"io/earthplatform/compat/dh/DhCarrierRenderGate","acquire");
        assertTrue(descriptor>=0&&acquire>descriptor);
        assertFalse(calls(initialize).stream().anyMatch(c->c.owner.equals("io/earthplatform/core/WorldIdentity")&&c.name.equals("toString")));
    }
    @Test void sessionTeardownClosesEventsBeforeDroppingOrdinaryResourceOwnership()throws Exception{
        var node=read("io/earthplatform/minecraft/coregate/RollingGateClient$Session");var close=method(node,"closeInstalledBridge");
        int fence=callIndex(close,node.name,"invalidate");
        int unbind=callIndex(close,"io/earthplatform/compat/dh/InstalledDhEvents","close");
        int cleanup=callIndex(close,node.name,"close");assertTrue(fence>=0&&unbind>fence&&cleanup>unbind);
        var invalidate=method(node,"invalidate");int gate=callIndex(invalidate,"io/earthplatform/compat/dh/DhCarrierRenderGate","invalidate");
        int owned=callIndex(invalidate,node.name,"invalidateOwned");
        assertTrue(gate>=0&&owned>gate,"Default installed gate is invalidated before entering the shared CPU fence");
        var cpuFence=method(node,"invalidateOwned");
        int monitor=-1;for(var i:cpuFence.instructions)if(i.getOpcode()==Opcodes.MONITORENTER){monitor=cpuFence.instructions.indexOf(i);break;}
        assertTrue(monitor>=0,"The extracted CPU fence still owns the Session monitor");
        int guard=callIndex(cpuFence,"io/earthplatform/minecraft/renderprobe/RenderPublicationGuard","requestInvalidation");
        int collision=callIndex(cpuFence,node.name,"fenceCollision");
        int capture=callIndex(cpuFence,node.name,"closeReplacementCapture");
        int source=callIndex(cpuFence,node.name,"cancelSourcePreparation");
        assertTrue(guard>monitor&&collision>guard&&capture>collision&&source>collision,"Render/gameplay authority is revoked before fallible cleanup");
    }
    @Test void bootstrapRejectionSendsTheParsedEpochFenceAndNullCollisionStillNotifies()throws Exception{
        var host=read("io/earthplatform/minecraft/coregate/RollingGateClient");var handle=method(host,"handle");
        int resolve=callIndex(handle,host.name,"requireInstalledDh");assertTrue(resolve>=0);
        var protectedCall=handle.tryCatchBlocks.stream().filter(t->handle.instructions.indexOf(t.start)<=resolve
                &&resolve<handle.instructions.indexOf(t.end)&&"java/lang/RuntimeException".equals(t.type))
                .min(java.util.Comparator.comparingInt(t->handle.instructions.indexOf(t.end)-handle.instructions.indexOf(t.start))).orElseThrow();
        int sessionConstructor=callIndex(handle,host.name+"$Session","<init>");
        assertTrue(sessionConstructor>resolve&&sessionConstructor<handle.instructions.indexOf(protectedCall.end),
                "The same exact-epoch notification covers construction before current can retain an owner");
        assertTrue(handle.tryCatchBlocks.stream().anyMatch(t->t.start==protectedCall.start&&t.end==protectedCall.end
                &&t.handler==protectedCall.handler&&"java/lang/Error".equals(t.type)));
        var decodedLongs=new java.util.ArrayList<Integer>();
        for(var i:handle.instructions)if(i instanceof MethodInsnNode c&&c.name.equals("readLong")){
            var next=nextInstruction(i);if(next instanceof VarInsnNode v&&v.getOpcode()==Opcodes.LSTORE)decodedLongs.add(v.var);
            if(decodedLongs.size()==2)break;
        }
        assertEquals(2,decodedLongs.size());
        var caught=(VarInsnNode)nextInstruction(protectedCall.handler);assertEquals(Opcodes.ASTORE,caught.getOpcode());
        var body=new java.util.ArrayList<AbstractInsnNode>();
        for(var i=nextInstruction(caught);i!=null;i=nextInstruction(i)){body.add(i);if(i.getOpcode()==Opcodes.ATHROW)break;}
        var constructor=body.stream().filter(i->i instanceof MethodInsnNode c&&c.owner.equals("io/earthplatform/minecraft/coregate/RollingGateControlPayload")&&c.name.equals("<init>")).map(i->(MethodInsnNode)i).findFirst().orElseThrow();
        assertEquals("(JJJILjava/lang/String;Z)V",constructor.desc);
        var arguments=body.subList(0,body.indexOf(constructor));
        assertEquals(decodedLongs,arguments.stream().filter(i->i instanceof VarInsnNode v&&v.getOpcode()==Opcodes.LLOAD).map(i->((VarInsnNode)i).var).toList());
        assertTrue(arguments.stream().anyMatch(i->i.getOpcode()==Opcodes.ICONST_3),"Exact FENCED kind");
        assertTrue(body.stream().anyMatch(i->i instanceof MethodInsnNode c&&c.owner.equals("net/neoforged/neoforge/network/PacketDistributor")&&c.name.equals("sendToServer")));
        assertTrue(body.stream().anyMatch(i->i instanceof MethodInsnNode c&&c.name.equals("addSuppressed")));
        assertEquals(Opcodes.ATHROW,body.getLast().getOpcode());
        var rethrown=(VarInsnNode)body.get(body.size()-2);assertEquals(Opcodes.ALOAD,rethrown.getOpcode());assertEquals(caught.var,rethrown.var);
        var session=read("io/earthplatform/minecraft/coregate/RollingGateClient$Session");var fence=method(session,"fenceCollision");
        int send=callIndex(fence,session.name,"send");assertTrue(send>=0,"No collision object is needed to notify the server");
        int schedule=callIndex(fence,"net/minecraft/client/Minecraft","execute");assertTrue(schedule>send);
        assertTrue(callIndex(fence,"io/earthplatform/minecraft/renderprobe/RenderPublicationGuard","invalidationRequested")<send);
        assertTrue(callIndex(fence,"io/earthplatform/minecraft/coregate/RollingGateControlPayload","<init>")<send);
    }
    static AbstractInsnNode nextInstruction(AbstractInsnNode node){
        for(var next=node.getNext();next!=null;next=next.getNext())if(next.getOpcode()>=0)return next;
        return null;
    }
    @Test void productionGeographicDriverHasOnlyInstalledRuntimeBinding()throws Exception{
        var driver=read("io/earthplatform/minecraft/lod/DhGeographicLodView$OpenGlDriver");boolean opensInstalled=false;
        for(var m:driver.methods)for(var c:calls(m)){
            assertFalse(c.owner.contains("DhOfficialRuntime")||c.owner.contains("URLClassLoader"),c.owner+"."+c.name);
            opensInstalled|=c.owner.equals("io/earthplatform/compat/dh/InstalledDhTerrainRuntime")&&c.name.equals("open");
        }assertTrue(opensInstalled);
        var session=read("io/earthplatform/minecraft/coregate/RollingGateClient$Session");
        assertTrue(session.methods.stream().flatMap(m->calls(m).stream()).anyMatch(c->c.owner.equals("io/earthplatform/minecraft/lod/DhPreparedLodView")&&c.name.equals("<init>")&&c.desc.contains("InstalledDhResolver$Runtime")));
    }
    @Test void everyIsolatedOpenChecksHeadlessOnlyGuardBeforePathOrLoaderWork()throws Exception{
        var node=read("io/earthplatform/minecraft/lod/DhOfficialRuntime");int overloads=0;
        for(var m:node.methods)if(m.name.equals("open")){overloads++;var first=calls(m).getFirst();assertEquals(node.name,first.owner);assertEquals("requireDiagnosticTest",first.name);}
        assertEquals(2,overloads);var guard=method(node,"requireDiagnosticTest");var names=new java.util.HashSet<String>();
        for(var i:guard.instructions)if(i instanceof LdcInsnNode c&&c.cst instanceof String s)names.add(s);
        assertTrue(names.contains("earthplatform.test.isolatedDhRuntime"));assertTrue(names.contains("earthplatform.experimental.installedDhBackend"));
    }
}
