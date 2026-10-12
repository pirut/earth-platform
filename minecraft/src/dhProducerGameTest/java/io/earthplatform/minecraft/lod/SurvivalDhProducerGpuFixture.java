package io.earthplatform.minecraft.lod;

import io.earthplatform.minecraft.survival.render.SurvivalCompleteOverlay;
import io.earthplatform.minecraft.survival.render.SurvivalDhCoverage;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.lwjgl.opengl.GL33C.*;
import static io.earthplatform.minecraft.survival.dhfixture.DhProducerFixtureAssertions.*;

/** Test-only bridge: genuine CompiledSet + production ownership, with explicitly fake GL operations. */
public final class SurvivalDhProducerGpuFixture {
    private SurvivalDhProducerGpuFixture(){}
    // The only case retains an unresolved physical owner through process exit; no cleanup is invented.
    private static SurvivalDhGpuPages retainedGpu;
    public static void uploadAndCheck(SurvivalDhPageCompiler.CompiledSet output,NativeDistanceCpuBudget cpu,
            SurvivalDhCoverage exact,SurvivalDhCoverage equalValuesDifferentOwner,SurvivalCompleteOverlay.FrameLatch frame,int tiles,Runnable deadline){
        if(retainedGpu!=null)throw new IllegalStateException("Prior fixture GPU ownership remains unresolved");
        var ledger=new SurvivalDhGpuLedger();var ops=new Ops();var gpu=new SurvivalDhGpuPages(cpu,ledger,ops);retainedGpu=gpu;
        gpu.start(output);assertThrows(SurvivalRefusal.class,output::tileCount);assertThrows(SurvivalRefusal.class,output::close);
        int slices=0;while(!gpu.step(4,Long.MAX_VALUE).complete()){deadline.run();assertTrue(++slices<=64);}
        deadline.run();var completion=gpu.completion();assertEquals(tiles,completion.tileCount());completion.requireAuthenticated(exact,frame);
        assertEquals("AS_GRASS",completion.historicalGrassSideMode());assertEquals("OFF",completion.historicalDebugMode());
        assertThrows(SurvivalRefusal.class,()->completion.requireAuthenticated(equalValuesDifferentOwner,frame));
        assertEquals(0,cpu.evidence().pools().get(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD).longValue());
        assertEquals(io.earthplatform.compat.dh.InstalledDhTerrainRuntime.SURVIVAL_WORKSPACE_BYTES,cpu.evidence().pools().get(NativeDistanceCpuBudget.Pool.DH_RESULTS).longValue());
        gpu.close();assertTrue(gpu.closeComplete());assertEquals(0,ledger.evidence().bytes());assertThrows(IllegalStateException.class,completion::requirePhysicalComplete);retainedGpu=null;
    }
    static final class Ops extends SurvivalDhGpuPages.Operations {
        int vao=700,array=701,next=1,calls,creates,deletes,restores,vertexFrees,indexFrees;
        final Map<Integer,Integer> elements=new HashMap<>(Map.of(700,702)),sizes=new HashMap<>();
        final Set<Integer> live=new HashSet<>();final List<int[]> indexUploads=new ArrayList<>();final List<byte[]> vertexUploads=new ArrayList<>();
        String failPhase;int failures=1;int throwCreateKind=-1;boolean failDelete,failQuery,wrongSize,failFree,failBeforeFree;
        Runnable onCreate=()->{};
        @Override void assertRenderThread(){}
        @Override Bindings capture(){calls++;return new Bindings(vao,array,elements.getOrDefault(vao,0));}
        @Override void invalidate(){calls++;}
        @Override void bindVertexArray(int id){calls++;vao=id;}
        @Override void bindBuffer(int target,int id){calls++;if(target==GL_ARRAY_BUFFER)array=id;else elements.put(vao,id);}
        @Override void restore(Bindings prior){restores++;invalidate();bindVertexArray(prior.vao());bindBuffer(GL_ARRAY_BUFFER,prior.array());assertEquals(prior.element(),elements.getOrDefault(vao,0));}
        @Override int create(int kind){calls++;creates++;onCreate.run();int id=next++;live.add(id);if(kind==throwCreateKind)throw new IllegalStateException("created without return");return id;}
        int bound(int target){return target==GL_ARRAY_BUFFER?array:elements.getOrDefault(vao,0);}
        @Override void allocate(int target,int bytes){calls++;sizes.put(bound(target),bytes);}
        @Override int size(int target){calls++;return sizes.get(bound(target))+(wrongSize?1:0);}
        @Override void upload(ByteBuffer data){calls++;byte[] copy=new byte[data.remaining()];data.duplicate().get(copy);vertexUploads.add(copy);}
        @Override void upload(IntBuffer data){calls++;int[] copy=new int[data.remaining()];data.duplicate().get(copy);indexUploads.add(copy);}
        @Override void enable(int index){}@Override void integerAttribute(int a,int b,int c,int d,long e){}@Override void attribute(int a,int b,int c,boolean d,int e,long f){}
        @Override boolean alive(int kind,int id){calls++;if(failQuery){failQuery=false;throw new IllegalStateException("query");}return live.contains(id);}
        @Override void delete(int kind,int id){calls++;deletes++;live.remove(id);if(failDelete){failDelete=false;live.add(id);throw new IllegalStateException("reused numeric name");}}
        @Override void check(String phase,int vao,int vbo,int ibo){calls++;if(phase.equals(failPhase)&&failures-->0)throw new IllegalStateException(phase);}
        @Override ByteBuffer allocateVertices(int bytes){return ByteBuffer.allocateDirect(bytes);}
        @Override IntBuffer allocateIndices(int count){return ByteBuffer.allocateDirect(count*4).order(ByteOrder.nativeOrder()).asIntBuffer();}
        @Override void beforeFreeVertices(){if(failBeforeFree){failBeforeFree=false;throw new IllegalStateException("before free");}}
        @Override void freeVertices(ByteBuffer bytes){vertexFrees++;if(failFree){failFree=false;throw new IllegalStateException("ambiguous free");}}
        @Override void freeIndices(IntBuffer bytes){indexFrees++;}
    }
}
