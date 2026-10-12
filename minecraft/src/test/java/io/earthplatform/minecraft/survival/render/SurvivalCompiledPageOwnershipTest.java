package io.earthplatform.minecraft.survival.render;

import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.scene.SurvivalNativeCompilerBridge;
import io.earthplatform.minecraft.scene.SurvivalNativeGpuPage;
import io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler.CompiledPage;
import io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler.Layer;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import io.earthplatform.minecraft.visual.NativeVisualBudget;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Reflective test-only fixtures never add a production minter or pretend to contain compiled models. */
class SurvivalCompiledPageOwnershipTest {
    private static final class RenderOwner implements AutoCloseable {
        private final Field field; private final Object prior;
        RenderOwner() throws Exception {
            field = RenderSystem.class.getDeclaredField("renderThread"); field.setAccessible(true);
            prior = field.get(null); field.set(null, Thread.currentThread());
        }
        public void close() throws Exception { field.set(null, prior); }
    }
    private static CompiledPage fixture(NativeDistanceCpuBudget cpu) throws Exception {
        var type = Class.forName(SurvivalNativePageCompiler.class.getName() + "$Build$Piece");
        var constructor = type.getDeclaredConstructor(SurvivalNativePageCompiler.Build.class, Layer.class, int.class);
        constructor.setAccessible(true); var piece = constructor.newInstance(null, Layer.SOLID, 0);
        for (var field : new String[]{"stride", "vertices"}) {
            var target = type.getDeclaredField(field); target.setAccessible(true); target.set(piece, field.equals("stride") ? 32 : 4);
        }
        var inputType = Class.forName(SurvivalNativePageCompiler.class.getName() + "$InputOwnership");
        var inputConstructor = inputType.getDeclaredConstructor(NativeDistanceCpuBudget.class, long.class);
        inputConstructor.setAccessible(true);
        var constructorPage = CompiledPage.class.getDeclaredConstructor(SurvivalDistancePagePlan.Page.class, long.class,
                List.class, SurvivalNativeCompilerBridge.class, inputType, SurvivalNativePageCompiler.AuthenticatedContent.class);
        constructorPage.setAccessible(true);
        var page = new SurvivalDistancePagePlan.Page(new SurvivalVisualPage(new CubePos(0, 0, 0)), 1L, 0, 0);
        // This reflective diagnostic fixture deliberately carries no authenticated content proof.
        return constructorPage.newInstance(page, 1L, List.of(piece), null, inputConstructor.newInstance(cpu, 1024L), null);
    }
    @Test void actualCompiledPageTransferInvalidatesEveryCallerAndPreviouslyRetainedPieceAccessor() throws Exception {
        try (var render = new RenderOwner()) {
            var cpu = new NativeDistanceCpuBudget(NativeDistanceCpuBudget.MAX_BYTES); var page = fixture(cpu);
            var oldList = page.pieces(); var piece = oldList.getFirst(); var owner = page.transferToUpload();
            for (Runnable action : new Runnable[]{() -> page.page(), () -> page.completedMemberMask(), () -> page.pieces(),
                    () -> page.dependencyJobs(), () -> page.observedOwners(), () -> page.shaderCapture(),
                    () -> page.reservationCalls(), () -> page.bulkQuadReservationCalls(), page::requireCurrent,
                    () -> page.layerStats(), () -> page.retainedCpuBytes(), () -> page.closeComplete(), page::close,
                    () -> page.transferToUpload(), () -> piece.layer(), () -> piece.ordinal(), () -> piece.format(),
                    () -> piece.stride(), () -> piece.vertices(), () -> piece.vertexBytes(), () -> piece.indexBytes(),
                    () -> piece.memberRanges(), () -> piece.vertexBuffer()})
                assertThrows(IllegalStateException.class, action::run);
            assertEquals(1, owner.pieceCount()); assertEquals(1024, owner.retainedCpuBytes());
            owner.close(); assertTrue(owner.closeComplete()); assertEquals(0, owner.retainedCpuBytes());
            assertEquals(0, cpu.evidence().bytes()); owner.close();
            assertThrows(IllegalStateException.class, owner::pieceCount);
            assertThrows(IllegalStateException.class, page::close);
        }
    }
    @Test void diagnosticRawBufferPathMarksTheActualPageNontransferableEvenIfRetrievalFails() throws Exception {
        try (var render = new RenderOwner()) {
            var cpu = new NativeDistanceCpuBudget(NativeDistanceCpuBudget.MAX_BYTES); var page = fixture(cpu);
            // The test-only Piece has no MeshData. The public borrow fence must execute BEFORE access.
            assertThrows(NullPointerException.class, () -> page.pieces().getFirst().vertexBuffer());
            assertThrows(IllegalStateException.class, page::transferToUpload);
            assertEquals(1024, page.retainedCpuBytes()); page.close(); assertTrue(page.closeComplete());
            assertEquals(0, cpu.evidence().bytes());
        }
    }
    @Test void actualUploadOwnerBorrowFinallyReleasesItsGateAfterInputValidationThrows() throws Exception {
        try (var render = new RenderOwner()) {
            var cpu = new NativeDistanceCpuBudget(NativeDistanceCpuBudget.MAX_BYTES); var page = fixture(cpu);
            var owner = page.transferToUpload();
            // No fake bridge is supplied: the production input fence throws before any byte callback.
            assertThrows(NullPointerException.class, () -> owner.withVertices(0, (bytes, format) -> fail("No compiled model exists")));
            owner.close(); assertTrue(owner.closeComplete()); assertEquals(0, cpu.evidence().bytes());
        }
    }
    @Test void rejectedSecondPublicWrapperNeverClosesTheAlreadyTransferredOwner() throws Exception {
        try (var render = new RenderOwner()) {
            var cpu = new NativeDistanceCpuBudget(NativeDistanceCpuBudget.MAX_BYTES); var page = fixture(cpu);
            var owner = page.transferToUpload();
            var rejected = new SurvivalNativeGpuPage(new NativeVisualBudget(), cpu);
            assertThrows(IllegalStateException.class, () -> rejected.start(page));
            rejected.close(); assertTrue(rejected.closeComplete());
            assertFalse(owner.closeComplete()); assertEquals(1024, owner.retainedCpuBytes());
            owner.close(); assertEquals(0, cpu.evidence().bytes());
        }
    }
    @Test void compiledPageAndTransferCapabilityHaveOnlyPrivateConstructors() {
        for (var type : List.of(CompiledPage.class, CompiledPage.UploadOwner.class))
            for (var constructor : type.getDeclaredConstructors()) assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }
}
