package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.vertex.BufferBuilder;
import io.earthplatform.minecraft.survival.render.*;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import io.earthplatform.minecraft.visual.NativeVisualBudget;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only Source and no GL: verifies receiver lifetime, never actual producer geometry equivalence. */
class SurvivalAuthenticatedCompletionTest {
    @TempDir Path temporary;
    static final SceneNeighborhood.LightingPolicy OUTDOOR=SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE;
    private static class EmptySource implements SurvivalNativeGpuPage.Source {
        final SurvivalDistancePagePlan.Page page;
        SurvivalNativePageCompiler.AuthenticatedContent proof;
        boolean claimed,closed,failClose;
        EmptySource(SurvivalDistancePagePlan.Page page,SurvivalNativePageCompiler.AuthenticatedContent proof){this.page=page;this.proof=proof;}
        public void claim(){if(claimed||closed)throw new IllegalStateException();claimed=true;}
        public SurvivalNativePageCompiler.AuthenticatedContent takeAuthenticatedContent(){var result=proof;proof=null;return result;}
        public SurvivalDistancePagePlan.Page page(){return page;}
        public long completedMemberMask(){return page.memberMask();}
        public int pieceCount(){return 0;}
        public int rangeCount(int index){throw new AssertionError();}
        public int vertexBytes(int index){throw new AssertionError();}
        public int indexBytes(int index){throw new AssertionError();}
        public SurvivalNativePageCompiler.CompiledPage.PieceMetadata metadata(int index){throw new AssertionError();}
        public void withVertices(int index,SurvivalNativePageCompiler.CompiledPage.VertexUpload upload){throw new AssertionError();}
        public long retainedCpuBytes(){return 0;}
        public boolean closeComplete(){return closed;}
        public void close(){if(failClose){failClose=false;throw new IllegalStateException("retained CPU close failure");}closed=true;}
    }
    private static SurvivalNativeGpuPage gpu(NativeDistanceCpuBudget budget){
        return new SurvivalNativeGpuPage(new NativeVisualBudget(),budget,new SurvivalNativeGpuPage.Operations(){
            @Override void assertRenderThread(){}
        });
    }
    @Test void physicalCompletionRetainsPrivateProofAfterCpuDrainAndCloseRevokesIt() throws Exception {
        var f=new SurvivalAuthenticatedTestFixture(temporary,false);
        try(f;var gpu=gpu(f.budget)) {
            var proof=f.proof();var source=new EmptySource(f.page,proof);gpu.startSource(source);
            assertNull(source.proof);assertThrows(IllegalStateException.class,gpu::completion);
            assertTrue(gpu.step(1,Long.MAX_VALUE).complete());assertTrue(source.closed);
            var completion=gpu.completion();completion.requireAuthenticated(f.plan,f.capture,OUTDOOR,false);
            assertTrue(gpu.retainedCpuBytes()>=proof.retainedMetadataBytes());
            f.owner.requestInvalidation(); // Historical compile provenance alone cannot re-authorize current gameplay.
            completion.requireAuthenticated(f.plan,f.capture,OUTDOOR,false);
            assertThrows(IllegalStateException.class,()->gpu.startSource(source));
            gpu.close();assertThrows(IllegalStateException.class,()->completion.requireAuthenticated(f.plan,f.capture,OUTDOOR,false));
            assertThrows(IllegalStateException.class,proof::retainedMetadataBytes);
        }
        assertEquals(0,f.budget.evidence().bytes());
    }
    @Test void failedCpuDrainRetainsProofChargeAndRefusesCompletionUntilCleanup() throws Exception {
        var f=new SurvivalAuthenticatedTestFixture(temporary,false);
        try(f;var gpu=gpu(f.budget)) {
            var proof=f.proof();var source=new EmptySource(f.page,proof);source.failClose=true;
            gpu.startSource(source);long retained=f.budget.evidence().bytes();
            assertThrows(IllegalStateException.class,()->gpu.step(1,Long.MAX_VALUE));
            assertEquals(retained,f.budget.evidence().bytes());assertTrue(proof.retainedMetadataBytes()>0);
            assertThrows(IllegalStateException.class,gpu::completion);
            gpu.close();assertTrue(source.closed);assertThrows(IllegalStateException.class,proof::retainedMetadataBytes);
        }
        assertEquals(0,f.budget.evidence().bytes());
    }
    @Test void admissionFailureKeepsTransferredProofAndNumericPageEqualityCannotSubstitute() throws Exception {
        try(var f=new SurvivalAuthenticatedTestFixture(temporary,false)) {
            var proof=f.proof();var sameNumbers=new SurvivalDistancePagePlan.Page(f.page.address(),f.page.groundMask(),f.page.treeMask(),f.page.editExceptionMask());
            try(var gpu=gpu(f.budget)) {
                assertEquals(f.page,sameNumbers);assertNotSame(f.page,sameNumbers);
                assertThrows(IllegalStateException.class,()->gpu.startSource(new EmptySource(sameNumbers,proof)));
                assertTrue(proof.retainedMetadataBytes()>0);assertThrows(IllegalStateException.class,gpu::completion);
            }
            assertThrows(IllegalStateException.class,proof::retainedMetadataBytes);
            var second=f.proof();long used=f.budget.evidence().pools().get(NativeDistanceCpuBudget.Pool.OWNER_METADATA);
            var pressure=f.budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,NativeDistanceCpuBudget.Pool.OWNER_METADATA.limit()-used);
            try(var gpu=gpu(f.budget)) {
                assertThrows(IllegalStateException.class,()->gpu.startSource(new EmptySource(f.page,second)));
                assertTrue(second.retainedMetadataBytes()>0);
            }
            pressure.releaseAfterPhysicalDrain();assertThrows(IllegalStateException.class,second::retainedMetadataBytes);
        }
    }
    @Test void exactResourceObjectAndIrreversibleResourceRetirementRemainSeparateFromContent() throws Exception {
        var epoch=new AtomicLong(1);Object format=new Object();
        java.util.function.Supplier<SceneNativeShaderContract.Values> values=()->new SceneNativeShaderContract.Values(
                false,null,null,null,null,format,32,epoch.get(),0,false,false);
        var material=new SceneNativeShaderContract.MaterialContext(){
            public void begin(BufferBuilder b,BlockState s,int x,int y,int z){}public void end(BufferBuilder b){}
            public long metadataBytes(){return 1234;}
        };
        var resource=new SceneNativeShaderContract.Capture(values.get(),values,material);
        var equalValues=new SceneNativeShaderContract.Capture(values.get(),values,material);
        try(var f=new SurvivalAuthenticatedTestFixture(temporary,false);var gpu=gpu(f.budget)) {
            var proof=f.proof(resource);assertEquals(4096+1234,proof.retainedMetadataBytes());
            gpu.startSource(new EmptySource(f.page,proof));gpu.step(1,Long.MAX_VALUE);
            var receipt=gpu.completion();receipt.requireAuthenticated(f.plan,resource,OUTDOOR,false);
            assertThrows(IllegalStateException.class,()->receipt.requireAuthenticated(f.plan,equalValues,OUTDOOR,false));
            epoch.incrementAndGet();assertThrows(IllegalStateException.class,()->receipt.requireAuthenticated(f.plan,resource,OUTDOOR,false));
            epoch.set(1);assertThrows(IllegalStateException.class,()->receipt.requireAuthenticated(f.plan,resource,OUTDOOR,false));
            try(var current=f.lease.latch(f.plan)){assertEquals(f.plan.identity(),current.planIdentity());}
        }
    }
    private static final class OnePieceSource extends EmptySource {
        final SurvivalNativePageCompiler.CompiledPage.PieceMetadata piece;
        final java.nio.ByteBuffer bytes=java.nio.ByteBuffer.allocateDirect(128);
        OnePieceSource(SurvivalDistancePagePlan.Page page,SurvivalNativePageCompiler.AuthenticatedContent proof){
            super(page,proof);piece=new SurvivalNativePageCompiler.CompiledPage.PieceMetadata(SurvivalNativePageCompiler.Layer.SOLID,0,32,4,
                    java.util.List.of(new SurvivalPageGeometry.MemberRange(Long.numberOfTrailingZeros(page.memberMask()),0,4)));
        }
        public int pieceCount(){return 1;}public int rangeCount(int i){return 1;}public int vertexBytes(int i){return 128;}public int indexBytes(int i){return 24;}
        public SurvivalNativePageCompiler.CompiledPage.PieceMetadata metadata(int i){return piece;}
        public void withVertices(int i,SurvivalNativePageCompiler.CompiledPage.VertexUpload upload){upload.upload(bytes,null);}
    }
    private static final class Ops extends SurvivalNativeGpuPage.Operations {
        int name,array,index;boolean surviveDelete;
        final java.util.Map<Integer,Integer> sizes=new java.util.HashMap<>();final java.util.Set<Integer> live=new java.util.HashSet<>();
        @Override void assertRenderThread(){}
        @Override Bindings capture(){return new Bindings(0,0);}
        @Override void bindVertexArray(int id){}
        @Override void bindBuffer(int target,int id){if(target==org.lwjgl.opengl.GL33C.GL_ARRAY_BUFFER)array=id;else index=id;}
        @Override int create(int kind){int id=++name;live.add(id);return id;}
        @Override void allocate(int target,int size){sizes.put(target==org.lwjgl.opengl.GL33C.GL_ARRAY_BUFFER?array:index,size);}
        @Override int size(int target){return sizes.get(target==org.lwjgl.opengl.GL33C.GL_ARRAY_BUFFER?array:index);}
        @Override void upload(int target,java.nio.ByteBuffer bytes){}
        @Override void upload(int target,java.nio.IntBuffer bytes){}
        @Override void delete(int kind,int id){if(surviveDelete)surviveDelete=false;else live.remove(id);}
        @Override boolean alive(int kind,int id){return live.contains(id);}
        @Override void check(String phase,int vao,int vbo,int ibo){}
        @Override java.nio.IntBuffer allocateIndices(int count){return java.nio.ByteBuffer.allocateDirect(count*4).asIntBuffer();}
        @Override void freeIndices(java.nio.IntBuffer buffer){}
        @Override void setupFormat(com.mojang.blaze3d.vertex.VertexFormat format){}
    }
    @Test void failedGpuDeletionRetainsIndependentProofAfterCpuAlreadyDrained() throws Exception {
        var f=new SurvivalAuthenticatedTestFixture(temporary,false);var ops=new Ops();
        try(f;var gpu=new SurvivalNativeGpuPage(new NativeVisualBudget(),f.budget,ops)) {
            var proof=f.proof();var source=new OnePieceSource(f.page,proof);gpu.startSource(source);gpu.step(1,Long.MAX_VALUE);
            assertTrue(source.closed);long retained=f.budget.evidence().bytes();var receipt=gpu.completion();
            receipt.requireAuthenticated(f.plan,f.capture,OUTDOOR,false);ops.surviveDelete=true;
            assertThrows(IllegalStateException.class,gpu::close);assertFalse(gpu.closeComplete());
            assertEquals(retained,f.budget.evidence().bytes());assertTrue(proof.retainedMetadataBytes()>0);
            assertThrows(IllegalStateException.class,()->receipt.requireAuthenticated(f.plan,f.capture,OUTDOOR,false));
            gpu.close();assertTrue(gpu.closeComplete());assertThrows(IllegalStateException.class,proof::retainedMetadataBytes);
        }
        assertEquals(0,f.budget.evidence().bytes());
    }
    @Test void diagnosticCompletionNeverGainsContentAuthentication() throws Exception {
        try(var f=new SurvivalAuthenticatedTestFixture(temporary,false);var gpu=gpu(f.budget)) {
            gpu.startSource(new EmptySource(f.page,null));gpu.step(1,Long.MAX_VALUE);
            gpu.completion().requirePhysicalComplete();
            assertThrows(IllegalStateException.class,()->gpu.completion().requireAuthenticated(f.plan,f.capture,OUTDOOR,false));
        }
    }
}
