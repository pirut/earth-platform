package io.earthplatform.compat.dh;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual pinned classes on the existing test classpath. This does not simulate a full NeoForge mod startup. */
class InstalledDhUpstreamProofTest {
    static InstalledDhTerrainRuntime runtime()throws Exception{
        String path=System.getProperty("earthplatform.installedDhTestJar");assertNotNull(path,"Supply official test dependency explicitly");
        var api=Class.forName("com.seibel.distanthorizons.api.DhApi");
        var mod=new InstalledDhResolver.InstalledMod("3.3.3",Path.of(path),api.getClassLoader());
        return InstalledDhTerrainRuntime.open(new InstalledDhResolver.Runtime(mod,api));
    }
    @Test void actualAlreadyLoadedOfficialClassesPackEveryDirectionAndReadUnchangedShaders()throws Exception{
        var runtime=runtime();var faces=new ArrayList<InstalledDhTerrainRuntime.Face>();
        for(String direction:List.of("UP","DOWN","NORTH","SOUTH","WEST","EAST"))faces.add(new InstalledDhTerrainRuntime.Face(direction,4,1289,5,0xff808080));
        assertEquals(6*64,runtime.build(faces).length);assertTrue(runtime.shader(true).contains("#version"));assertTrue(runtime.shader(false).contains("#version"));
        var api=Class.forName("com.seibel.distanthorizons.api.DhApi");var builder=Class.forName("com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder");
        assertSame(api.getClassLoader(),builder.getClassLoader());assertSame(runtime,runtime());
    }
    @Test void adapterAndIndependentUpstreamThreadShareOnePoolButOwnTheirBuilderCheckouts()throws Exception{
        var runtime=runtime();var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{
            var adapter=executor.submit(()->{start.await();for(int i=0;i<100;i++)assertEquals(64,runtime.build(List.of(new InstalledDhTerrainRuntime.Face("UP",1,658,1,0xff808080))).length);return true;});
            var upstream=executor.submit(()->{start.await();
                var builderClass=Class.forName("com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder");
                var wrapper=Class.forName("com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper");
                var checkoutClass=Class.forName("com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout");
                var factory=builderClass.getMethod("getBuilder",boolean.class,wrapper);var pool=builderClass.getField("ARRAY_LIST_POOL").get(null);
                var checkout=pool.getClass().getMethod("checkoutByteBuffers",int.class);var opaque=builderClass.getMethod("makeOpaqueVertexBuffers",checkoutClass);
                var up=builderClass.getMethod("addQuadUp",short.class,short.class,short.class,short.class,int.class,short.class,byte.class,byte.class,byte.class);
                for(int i=0;i<100;i++)try(var builder=(AutoCloseable)factory.invoke(null,false,null);var buffers=(AutoCloseable)checkout.invoke(pool,1)){
                    up.invoke(builder,(short)2,(short)2048,(short)3,(short)1,0xff808080,(short)0,(byte)0,(byte)15,(byte)0);
                    @SuppressWarnings("unchecked") var list=(List<ByteBuffer>)opaque.invoke(builder,buffers);assertEquals(64,list.stream().mapToInt(ByteBuffer::remaining).sum());
                }return true;});
            start.countDown();assertTrue(adapter.get(20,TimeUnit.SECONDS));assertTrue(upstream.get(20,TimeUnit.SECONDS));
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void pinnedPublicCancellationDominatesOpaqueAndDeferredTerrainRendererCalls()throws Exception{
        var node=read("com/seibel/distanthorizons/core/api/internal/ClientApi.class");var method=node.methods.stream().filter(m->m.name.equals("renderLodLayer")).findFirst().orElseThrow();
        int verified=0;
        for(var instruction:method.instructions){
            if(!(instruction instanceof MethodInsnNode call)||!call.owner.equals("com/seibel/distanthorizons/core/render/renderer/LodRenderer")||!(call.name.equals("render")||call.name.equals("renderDeferred")))continue;
            int callIndex=method.instructions.indexOf(call);boolean branch=false,event=false;
            for(int i=Math.max(0,callIndex-14);i<callIndex;i++){
                var prior=method.instructions.get(i);
                if(prior instanceof JumpInsnNode jump&&jump.getOpcode()==Opcodes.IFNE&&method.instructions.indexOf(jump.label)>callIndex)branch=true;
                if(prior instanceof LdcInsnNode ldc&&ldc.cst instanceof Type type&&type.getClassName().endsWith(call.name.equals("render")?".DhApiBeforeRenderEvent":".DhApiBeforeDeferredRenderEvent"))event=true;
            }
            assertTrue(branch&&event,"Supported cancellation must skip "+call.name);verified++;
        }
        assertEquals(2,verified);
        // BeforeRenderPass is inside the skipped terrain-renderer subtree, not a second host draw trigger.
        var renderer=read("com/seibel/distanthorizons/common/render/openGl/terrain/GlDhTerrainShaderProgram_neoforge.class");
        assertTrue(renderer.methods.stream().flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
                .anyMatch(i->i instanceof LdcInsnNode l&&l.cst instanceof Type t&&t.getClassName().endsWith(".DhApiBeforeRenderPassEvent")));
    }
    @Test void shimBytecodeHasNoLoaderFallbackGlobalOverridesOrUpstreamPoolClosure()throws Exception{
        for(Class<?> type:List.of(InstalledDhResolver.class,InstalledDhApiProbe.class,InstalledDhTerrainRuntime.class,DhCarrierRenderGate.class,InstalledDhEvents.class)){
            var node=read(type.getName().replace('.','/')+".class");
            for(var method:node.methods)for(var i:method.instructions)if(i instanceof MethodInsnNode call){
                assertFalse(call.owner.equals("java/net/URLClassLoader"));
                assertFalse(call.owner.startsWith("com/seibel/")&&List.of("setValue","clearValue","clearRenderDataCache","setReadOnly","clear").contains(call.name),call.owner+"."+call.name);
            }
        }
    }
    @Test void officialEventMatricesPreserveAffineProjectionAndDoNotAliasMutableDhState(){
        var projection=new org.joml.Matrix4f().perspective(.9f,1.7f,.05f,2048).translate(.07f,-.04f,.01f).rotateZ(.11f);
        var view=new org.joml.Matrix4f().rotateYXZ(.4f,-.3f,.1f).translate(.2f,.1f,-.05f);
        var p=new com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam();
        p.mcProjectionMatrix.set(new com.seibel.distanthorizons.core.util.math.DhMat4f(projection));
        p.mcModelViewMatrix.set(new com.seibel.distanthorizons.core.util.math.DhMat4f(view));p.partialTicks=.75f;p.worldYOffset=-11008;
        var actual=InstalledDhFrame.capture(p);assertEquals(projection,actual.projection());assertEquals(view,actual.modelView());assertEquals(.75f,actual.partialTicks());
        actual.projection().zero();assertNotEquals(0,p.mcProjectionMatrix.m00);p.mcModelViewMatrix.m00=Float.NaN;
        assertThrows(IllegalArgumentException.class,()->InstalledDhFrame.capture(p));
    }
    @Test void actualPinnedApiCanBeInspectedBeforeItsDelayedRendererExists()throws Exception{
        var c=InstalledDhApiProbe.inspect(getClass().getClassLoader());assertEquals("3.3.3",c.version());assertEquals("7.2.0",c.apiVersion());
        assertSame(Class.forName("com.seibel.distanthorizons.api.DhApi"),c.apiClass());assertFalse(c.initialized());
    }
    static ClassNode read(String resource)throws Exception{try(var in=InstalledDhUpstreamProofTest.class.getClassLoader().getResourceAsStream(resource)){assertNotNull(in,resource);var node=new ClassNode();new ClassReader(in).accept(node,0);return node;}}
}
