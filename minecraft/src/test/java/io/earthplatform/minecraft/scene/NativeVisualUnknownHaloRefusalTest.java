package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Field;
import java.util.Arrays;
import net.minecraft.ReportedException;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs in the normal headless FML test partition. The actual pinned renderBatched method wraps
 * a real Block.shouldRenderFace -> oracle View -> Input.state lookup. Only model selection is
 * controlled to reach that face lookup without a Minecraft window, atlas, or GL context.
 * This proves the native exception boundary, not installed Iris bytes or whole-page parity.
 */
class NativeVisualUnknownHaloRefusalTest {
    private static final BlockPos ORIGIN=BlockPos.ZERO;
    private static final class Fixture {
        final NativeVisualCpuBuilder.Input input=NativeVisualOracleFixtures.input(12);
        final BlockAndTintGetter view;
        Fixture()throws Exception{
            Field access=Unsafe.class.getDeclaredField("theUnsafe");access.setAccessible(true);
            var oracle=(NativeVisualPageOracle)((Unsafe)access.get(null)).allocateInstance(NativeVisualPageOracle.class);
            Field source=NativeVisualPageOracle.class.getDeclaredField("input");source.setAccessible(true);source.set(oracle,input);
            Class<?> viewType=Arrays.stream(NativeVisualPageOracle.class.getDeclaredClasses()).filter(t->t.getSimpleName().equals("View")).findFirst().orElseThrow();
            var constructor=viewType.getDeclaredConstructor(NativeVisualPageOracle.class);constructor.setAccessible(true);
            view=(BlockAndTintGetter)constructor.newInstance(oracle);
            var begin=viewType.getDeclaredMethod("begin",BlockPos.class);begin.setAccessible(true);begin.invoke(view,ORIGIN);
        }
        NativeVisualCpuBuilder.UnknownDependency observed()throws Exception{
            Field unknown=view.getClass().getDeclaredField("unknown");unknown.setAccessible(true);
            return(NativeVisualCpuBuilder.UnknownDependency)unknown.get(view);
        }
    }
    private static ReportedException throughPinnedDispatcher(Fixture fixture,Runnable faceLookup){
        // renderBatched itself is inherited unchanged. Its pinned catch and CrashReport are real.
        var dispatcher=new BlockRenderDispatcher(null,null,null){
            @Override public BakedModel getBlockModel(BlockState state){faceLookup.run();throw new AssertionError("Expected lookup to refuse");}
        };
        return assertThrows(ReportedException.class,()->dispatcher.renderBatched(Blocks.STONE.defaultBlockState(),
                ORIGIN,fixture.view,new PoseStack(),null,true,RandomSource.create(0),ModelData.EMPTY,null));
    }
    private static ReportedException throughPinnedFaceLookup(Fixture fixture){
        return throughPinnedDispatcher(fixture,()->Block.shouldRenderFace(Blocks.STONE.defaultBlockState(),
                fixture.view,ORIGIN,Direction.WEST,ORIGIN.west()));
    }
    @Test void actualPinnedRenderBatchedPreservesTheExactUnknownSeenByTheOracleGetter()throws Exception{
        var fixture=new Fixture();var failure=throughPinnedFaceLookup(fixture);var expected=fixture.observed();
        assertEquals(ReportedException.class,failure.getClass());assertNotNull(expected);
        assertSame(expected,failure.getCause());assertSame(expected,failure.getReport().getException());
        assertTrue(Arrays.stream(failure.getStackTrace()).anyMatch(e->e.getClassName().equals(BlockRenderDispatcher.class.getName())&&e.getMethodName().equals("renderBatched")));
        assertTrue(Arrays.stream(expected.getStackTrace()).anyMatch(e->e.getClassName().equals(Block.class.getName())&&e.getMethodName().equals("shouldRenderFace")));
        assertTrue(NativeVisualPageOracle.isExpectedUnknownHaloFailure(failure,expected,fixture.input));
        assertFalse(NativeVisualPageOracle.isExpectedUnknownHaloFailure(failure,expected,NativeVisualOracleFixtures.input(12)));
    }
    @Test void actualPinnedWrapperDoesNotAuthorizeAnUnrelatedSameMessageFailure()throws Exception{
        var fixture=new Fixture();
        var observed=assertThrows(NativeVisualCpuBuilder.UnknownDependency.class,()->fixture.view.getBlockState(ORIGIN.west()));
        var unrelated=new IllegalStateException(observed.getMessage());
        var failure=throughPinnedDispatcher(fixture,()->{throw unrelated;});
        assertSame(unrelated,failure.getCause());assertSame(observed,fixture.observed());
        assertFalse(NativeVisualPageOracle.isExpectedUnknownHaloFailure(failure,observed,fixture.input));
    }
    @Test void cleanupFailureSuppressedOnActualWrapperOrSentinelCannotPass()throws Exception{
        var outerFixture=new Fixture();var outer=throughPinnedFaceLookup(outerFixture);
        outer.addSuppressed(new IllegalStateException("oracle lease changed during cleanup"));
        assertFalse(NativeVisualPageOracle.isExpectedUnknownHaloFailure(outer,outerFixture.observed(),outerFixture.input));
        var innerFixture=new Fixture();var inner=throughPinnedFaceLookup(innerFixture);
        innerFixture.observed().addSuppressed(new IllegalStateException("native storage cleanup failed"));
        assertFalse(NativeVisualPageOracle.isExpectedUnknownHaloFailure(inner,innerFixture.observed(),innerFixture.input));
    }
}
