package io.earthplatform.minecraft.lod;

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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL33C.*;

/** Production lifecycle and shared ledger with fake GL only; no authenticated producer or real GL claim. */
class SurvivalDhGpuPagesTest {
    static final class Source implements SurvivalDhGpuPages.Source {
        final int[] lengths;final NativeDistanceCpuBudget.Reservation charge;
        boolean claimed,closed,failClose;int closes;
        Source(NativeDistanceCpuBudget cpu,int... quads){lengths=new int[quads.length];long bytes=256;for(int i=0;i<quads.length;i++){lengths[i]=quads[i]*64;bytes+=lengths[i];}
            charge=cpu.reserve(NativeDistanceCpuBudget.Pool.DH_RESULTS,bytes);}
        public void claim(){if(claimed||closed)throw new IllegalStateException("claim");claimed=true;}
        public int tileCount(){return lengths.length;}public int vertexBytes(int tile){return lengths[tile];}
        public DhGeographicLodData.TileKey tileKey(int tile){return new DhGeographicLodData.TileKey(Math.multiplyExact(tile,256L),0,0);}
        public void copyTo(int tile,ByteBuffer destination){if(!claimed||closed)throw new IllegalStateException("source");for(int i=0;i<lengths[tile];i++)destination.put((byte)(tile+i));destination.flip();}
        public boolean closeComplete(){return closed;}
        public void close(){closes++;if(failClose){failClose=false;throw new IllegalStateException("CPU close");}closed=true;charge.releaseAfterPhysicalDrain();}
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
    record Fixture(NativeDistanceCpuBudget cpu,SurvivalDhGpuLedger ledger,Source source,Ops gl,SurvivalDhGpuPages pages){}
    static Fixture fixture(int... quads){var cpu=new NativeDistanceCpuBudget(NativeDistanceCpuBudget.MAX_BYTES);var ledger=new SurvivalDhGpuLedger();var gl=new Ops();var source=new Source(cpu,quads);return new Fixture(cpu,ledger,source,gl,new SurvivalDhGpuPages(cpu,ledger,gl));}
    static void start(Fixture f){f.pages.startSource(f.source);}
    @Test void entireCandidateIsReservedBeforeGlAndExactCanonicalBytesAreUploaded(){
        var f=fixture(1,3);assertEquals(0,f.gl.calls);start(f);assertEquals(0,f.gl.calls);
        f.gl.onCreate=()->{assertEquals(2,f.ledger.evidence().owners());assertEquals(256+64+192,f.cpu.evidence().pools().get(NativeDistanceCpuBudget.Pool.DH_RESULTS).longValue());};
        assertFalse(f.pages.step(1,Long.MAX_VALUE).complete());assertFalse(f.source.closed);
        assertTrue(f.pages.step(1,Long.MAX_VALUE).complete());assertTrue(f.source.closed);
        assertEquals(0,f.cpu.evidence().pools().get(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD).longValue());
        assertArrayEquals(new int[]{0,1,2,2,3,0},f.gl.indexUploads.get(0));
        assertEquals(700,f.gl.vao);assertEquals(701,f.gl.array);assertEquals(702,f.gl.elements.get(700));
        assertEquals(2,f.pages.completion().tileCount());assertThrows(IllegalStateException.class,()->f.pages.completion().requireAuthenticated(null,(io.earthplatform.minecraft.survival.render.SurvivalCompleteOverlay.FrameLatch)null));
        assertThrows(IllegalStateException.class,()->f.pages.completion().requireAuthenticated(null,(io.earthplatform.minecraft.survival.render.SurvivalCompleteOverlay.PlanInput)null));
        f.pages.close();assertTrue(f.pages.closeComplete());assertEquals(0,f.ledger.evidence().bytes());assertEquals(0,f.cpu.evidence().bytes());
    }
    @Test void roundedReadbackMismatchRestoresBindingsAndCannotMintCompletion(){
        var f=fixture(1);start(f);f.gl.wrongSize=true;assertThrows(IllegalStateException.class,()->f.pages.step(1,Long.MAX_VALUE));
        assertEquals(700,f.gl.vao);assertEquals(701,f.gl.array);assertFalse(f.source.closed);assertThrows(IllegalStateException.class,f.pages::completion);
        f.pages.close();assertTrue(f.pages.closeComplete());assertEquals(0,f.cpu.evidence().bytes());
    }
    @Test void sharedLedgerRejectsWholeSecondGenerationBeforeAnyGl(){
        var f=fixture(4096);start(f);int[] full=new int[84];java.util.Arrays.fill(full,4096);
        var secondSource=new Source(f.cpu,full);var secondGl=new Ops();var second=new SurvivalDhGpuPages(f.cpu,f.ledger,secondGl);
        assertThrows(IllegalStateException.class,()->second.startSource(secondSource));assertEquals(0,secondGl.calls);assertEquals(1,f.ledger.evidence().owners());
        second.close();f.pages.close();assertEquals(0,f.cpu.evidence().bytes());
    }
    @Test void unknownInvokedAllocationRetainsBudgetWithoutInventingOrRetryingName(){
        for(int kind=0;kind<3;kind++){
            var f=fixture(1);start(f);f.gl.throwCreateKind=kind;assertThrows(IllegalStateException.class,()->f.pages.step(1,Long.MAX_VALUE));
            long charge=f.pages.chargedGpuBytes();assertThrows(IllegalStateException.class,f.pages::close);int deleted=f.gl.deletes;
            assertThrows(IllegalStateException.class,f.pages::close);assertEquals(deleted,f.gl.deletes);assertEquals(charge,f.pages.chargedGpuBytes());assertFalse(f.source.closed);assertFalse(f.pages.closeComplete());
        }
    }
    @Test void ambiguousDeletionConsumesNumericNameExactlyOnceAndRetainsCpu(){
        var f=fixture(1,1);start(f);f.pages.step(1,Long.MAX_VALUE);f.gl.failDelete=true;
        assertThrows(IllegalStateException.class,f.pages::close);int deleted=f.gl.deletes;
        assertThrows(IllegalStateException.class,f.pages::close);assertEquals(deleted,f.gl.deletes);assertEquals(1,f.ledger.evidence().owners());assertFalse(f.source.closed);
    }
    @Test void failedLivenessAfterDeletionNeverRetriesPotentiallyReusedName(){
        var f=fixture(1,1);start(f);f.pages.step(1,Long.MAX_VALUE);f.gl.failQuery=true;
        assertThrows(IllegalStateException.class,f.pages::close);int deleted=f.gl.deletes;assertThrows(IllegalStateException.class,f.pages::close);assertEquals(deleted,f.gl.deletes);assertFalse(f.source.closed);
    }
    @Test void failedRestorationRetriesBeforeCpuDrainAndNoFurtherUploads(){
        var f=fixture(1);start(f);f.gl.failPhase="restore.after";assertThrows(IllegalStateException.class,()->f.pages.step(1,Long.MAX_VALUE));
        assertFalse(f.source.closed);assertThrows(IllegalStateException.class,()->f.pages.step(1,Long.MAX_VALUE));f.pages.close();assertEquals(2,f.gl.restores);assertEquals(0,f.cpu.evidence().bytes());
    }
    @Test void ambiguousNativeFreeRetainsChargeAndDoesNotRetryAddress(){
        var f=fixture(1);start(f);f.gl.failFree=true;assertThrows(IllegalStateException.class,()->f.pages.step(1,Long.MAX_VALUE));
        assertThrows(IllegalStateException.class,f.pages::close);assertThrows(IllegalStateException.class,f.pages::close);assertEquals(1,f.gl.vertexFrees);assertFalse(f.source.closed);assertTrue(f.cpu.evidence().pools().get(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD).longValue()>0);
    }
    @Test void provenPreFreeFailureAndSourceCloseFailureRemainRetryable(){
        var f=fixture(1);start(f);f.gl.failBeforeFree=true;assertThrows(IllegalStateException.class,()->f.pages.step(1,Long.MAX_VALUE));
        f.source.failClose=true;assertThrows(IllegalStateException.class,f.pages::close);assertEquals(1,f.gl.vertexFrees);f.pages.close();assertEquals(1,f.gl.vertexFrees);assertEquals(0,f.cpu.evidence().bytes());
    }
    @Test void maximumPieceCannotWidenQuadLimitAndCountTimeSlicesRemainSeparate(){
        var f=fixture(4096,1);start(f);var step=f.pages.step(1,1);assertEquals(1,step.tiles());assertFalse(step.complete());assertEquals(4096*6,f.gl.indexUploads.get(0).length);
        f.pages.close();assertEquals(0,f.cpu.evidence().bytes());var rejected=fixture(4097);assertThrows(IllegalArgumentException.class,()->start(rejected));assertEquals(0,rejected.gl.calls);rejected.pages.close();
    }
}
