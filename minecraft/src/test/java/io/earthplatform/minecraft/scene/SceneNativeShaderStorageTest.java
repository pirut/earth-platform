package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real fixed native allocations/reservations, no GPU/GL or Minecraft singleton. Run with the whole shader batch. */
class SceneNativeShaderStorageTest {
    @Test void realStorageNeverResizesAtTerrainBudgetBoundary() {
        var budget=new SceneNativeShaderContract.Budget();
        try(var storage=new SceneMesh.BoundedStorage(budget)){
            storage.configure(52);long first=storage.reserve(52),last=first;
            for(int i=1;i<20164;i++) last=storage.reserve(52);
            assertEquals(first+20163L*52,last);
            int count=budget.vertices,bytes=budget.bytes;
            assertThrows(IllegalStateException.class,()->storage.reserve(52));
            assertEquals(count,budget.vertices);assertEquals(bytes,budget.bytes);
        }
    }
    @Test void unexpectedStridesAndMissingConfigurationAreRejectedBeforeAccounting() {
        var budget=new SceneNativeShaderContract.Budget();
        try(var storage=new SceneMesh.BoundedStorage(budget)){
            assertThrows(IllegalStateException.class,()->storage.reserve(52));storage.configure(52);
            for(int stride:new int[]{-1,0,32,51,53,Integer.MAX_VALUE})assertThrows(IllegalStateException.class,()->storage.reserve(stride));
            assertEquals(0,budget.vertices);assertEquals(0,budget.bytes);
        }
    }
    @Test void allThreePassesShareOneGeometryBudgetWithoutIncreasingCpuCapacity() {
        var budget=new SceneNativeShaderContract.Budget();
        try(var a=new SceneMesh.BoundedStorage(budget);var b=new SceneMesh.BoundedStorage(budget);var c=new SceneMesh.BoundedStorage(budget)){
            for(var s:new SceneMesh.BoundedStorage[]{a,b,c}){s.configure(52);for(int i=0;i<6720;i++)s.reserve(52);}
            for(int i=0;i<4;i++)a.reserve(52);
            for(var s:new SceneMesh.BoundedStorage[]{a,b,c})assertThrows(IllegalStateException.class,()->s.reserve(52));
            assertEquals(1048528,budget.bytes);
        }
    }
    @Test void bulkScopeRejectsFifthVertexAndRetainsPartialExceptionAccounting() {
        var budget=new SceneNativeShaderContract.Budget();
        try(var storage=new SceneMesh.BoundedStorage(budget)){
            storage.configure(52);storage.beginBulk();
            assertThrows(IllegalStateException.class,storage::beginBulk);
            for(int i=0;i<4;i++)storage.reserve(52);
            assertThrows(IllegalStateException.class,()->storage.reserve(52));storage.endBulk(false);
            storage.beginBulk();storage.reserve(52);storage.endBulk(false);
            assertEquals(5,budget.vertices);assertEquals(260,budget.bytes);
            storage.beginBulk();storage.reserve(52);
            assertThrows(IllegalStateException.class,()->storage.endBulk(true));
        }
    }
    @Test void bothWrapperBulkShapesPreserveActualDelegateAndRejectMalformedInput() {
        var budget=new SceneNativeShaderContract.Budget();
        try(var storage=new SceneMesh.BoundedStorage(budget)){
            storage.configure(52);var target=new BulkDelegate(storage);var wrapper=new SceneMesh.BoundedConsumer(target,storage);
            var valid=new BakedQuad(new int[32],-1,Direction.UP,null,true);
            wrapper.putBulkData(null,valid,1,1,1,1,0,0);
            wrapper.putBulkData(null,valid,new float[4],1,1,1,1,new int[4],0,true);
            assertEquals(1,target.simple);assertEquals(1,target.lit);assertEquals(8,budget.vertices);
            var malformed=new BakedQuad(new int[40],-1,Direction.UP,null,true);
            assertThrows(IllegalArgumentException.class,()->wrapper.putBulkData(null,malformed,1,1,1,1,0,0));
            assertThrows(IllegalArgumentException.class,()->wrapper.putBulkData(null,valid,new float[3],1,1,1,1,new int[4],0,false));
            assertEquals(8,budget.vertices);
            target.vertices=5;
            assertThrows(IllegalStateException.class,()->wrapper.putBulkData(null,valid,1,1,1,1,0,0));
            assertEquals(12,budget.vertices,"The fifth physical reservation must be blocked");
        }
    }
    @Test void bulkPreflightAtByteLimitDoesNotCallNativeDelegate() {
        var budget=new SceneNativeShaderContract.Budget();
        try(var storage=new SceneMesh.BoundedStorage(budget)){
            storage.configure(52);for(int i=0;i<20164;i++)storage.reserve(52);
            var target=new BulkDelegate(storage);var wrapper=new SceneMesh.BoundedConsumer(target,storage);
            var quad=new BakedQuad(new int[32],-1,Direction.UP,null,true);
            assertThrows(IllegalStateException.class,()->wrapper.putBulkData(null,quad,1,1,1,1,0,0));
            assertEquals(0,target.simple);assertEquals(20164,budget.vertices);
        }
    }
    @Test void storageRejectsOffOwnerBeforeNativeReservation()throws Exception {
        var budget=new SceneNativeShaderContract.Budget();
        try(var storage=new SceneMesh.BoundedStorage(budget)){
            storage.configure(52);var failure=new AtomicReference<Throwable>();
            Thread worker=new Thread(()->{try{storage.reserve(52);}catch(Throwable e){failure.set(e);}});
            worker.start();worker.join();assertInstanceOf(IllegalStateException.class,failure.get());assertEquals(0,budget.vertices);
        }
    }
    /** Allocation/count arithmetic only. The installed parity host separately proves Sodium dispatch. */
    @Test void completeQuadReservationAccountsDestinationVerticesAndBytesForBothLayouts() {
        for(int stride:new int[]{32,52}) {
            var budget=new SceneNativeShaderContract.Budget();
            try(var storage=new SceneMesh.BoundedStorage(budget)) {
                storage.configure(stride);storage.beginBulk();long first=storage.reserve(4*stride);storage.endBulk(true);
                assertEquals(4,budget.vertices);assertEquals(4*stride,budget.bytes);
                assertEquals(1,budget.reservationCalls);assertEquals(1,budget.bulkQuadReservationCalls);
                storage.beginBulk();long next=storage.reserve(stride);
                assertEquals(first+4L*stride,next);
                for(int i=1;i<4;i++)storage.reserve(stride);
                storage.endBulk(true);
                assertEquals(8,budget.vertices);assertEquals(8*stride,budget.bytes);
                assertEquals(5,budget.reservationCalls);assertEquals(1,budget.bulkQuadReservationCalls);
            }
        }
    }
    @Test void batchedQuadCannotBypassScopeWholeVertexShapeOrPhysicalByteLimit() {
        for(int stride:new int[]{32,52}) {
            var budget=new SceneNativeShaderContract.Budget();
            try(var storage=new SceneMesh.BoundedStorage(budget)) {
                storage.configure(stride);
                assertThrows(IllegalStateException.class,()->storage.reserve(4*stride));
                storage.beginBulk();
                for(int bytes:new int[]{0,-stride,2*stride,3*stride,4*stride-1,4*stride+1,5*stride,Integer.MAX_VALUE})
                    assertThrows(IllegalStateException.class,()->storage.reserve(bytes));
                assertEquals(0,budget.vertices);assertEquals(0,budget.reservationCalls);
                storage.reserve(4*stride);
                assertThrows(IllegalStateException.class,()->storage.reserve(stride));storage.endBulk(true);
                int quads=SceneMesh.MAX_VERTEX_BYTES/(4*stride);
                for(int q=1;q<quads;q++){storage.beginBulk();storage.reserve(4*stride);storage.endBulk(true);}
                assertEquals(quads*4,budget.vertices);assertEquals(quads*4*stride,budget.bytes);
                assertEquals(quads,budget.reservationCalls);assertEquals(quads,budget.bulkQuadReservationCalls);
                assertThrows(IllegalStateException.class,storage::beginBulk);
                assertEquals(quads*4,budget.vertices);
            }
        }
    }
    @Test void partialGenericThenOverlargeBatchRefusesBeforeNativeAccountingAndStillDrains() {
        var budget=new SceneNativeShaderContract.Budget();
        try(var storage=new SceneMesh.BoundedStorage(budget)) {
            storage.configure(32);storage.beginBulk();storage.reserve(32);
            assertThrows(IllegalStateException.class,()->storage.reserve(128));
            assertEquals(1,budget.vertices);assertEquals(32,budget.bytes);assertEquals(1,budget.reservationCalls);
            storage.endBulk(false);storage.beginBulk();storage.reserve(128);storage.endBulk(true);
            assertEquals(5,budget.vertices);assertEquals(160,budget.bytes);assertEquals(1,budget.bulkQuadReservationCalls);
        }
    }
    @Test void wrapperBulkOverloadsAlsoAcceptOneWholeDestinationQuadReservation() {
        var budget=new SceneNativeShaderContract.Budget();
        try(var storage=new SceneMesh.BoundedStorage(budget)) {
            storage.configure(52);var target=new BulkDelegate(storage);target.batch=true;
            var wrapper=new SceneMesh.BoundedConsumer(target,storage);
            var quad=new BakedQuad(new int[32],-1,Direction.UP,null,true);
            wrapper.putBulkData(null,quad,1,1,1,1,0,0);
            wrapper.putBulkData(null,quad,new float[4],1,1,1,1,new int[4],0,true);
            assertEquals(8,budget.vertices);assertEquals(416,budget.bytes);assertEquals(2,budget.bulkQuadReservationCalls);
        }
    }
    private static final class BulkDelegate implements VertexConsumer {
        final SceneMesh.BoundedStorage storage;int simple,lit,vertices=4;boolean batch;
        BulkDelegate(SceneMesh.BoundedStorage storage){this.storage=storage;}
        void emit(){if(batch)storage.reserve(vertices*52);else for(int i=0;i<vertices;i++)storage.reserve(52);}
        @Override public void putBulkData(PoseStack.Pose p,BakedQuad q,float r,float g,float b,float a,int light,int overlay){simple++;emit();}
        @Override public void putBulkData(PoseStack.Pose p,BakedQuad q,float[] brightness,float r,float g,float b,float a,int[] light,int overlay,boolean color){lit++;emit();}
        public VertexConsumer addVertex(float x,float y,float z){storage.reserve(52);return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){return this;}
        public VertexConsumer setUv(float u,float v){return this;}
        public VertexConsumer setUv1(int u,int v){return this;}
        public VertexConsumer setUv2(int u,int v){return this;}
        public VertexConsumer setNormal(float x,float y,float z){return this;}
    }
}
