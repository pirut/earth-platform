package io.earthplatform.minecraft.lod;

import io.earthplatform.minecraft.survival.render.SurvivalCompleteOverlay;
import io.earthplatform.minecraft.survival.render.SurvivalDhCoverage;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only bridge: genuine CompiledSet + production ownership, with explicitly fake GL operations. */
public final class SurvivalDhGpuTestHarness {
    private SurvivalDhGpuTestHarness(){}
    public static void uploadAndCheck(SurvivalDhPageCompiler.CompiledSet output,NativeDistanceCpuBudget cpu,
            SurvivalDhCoverage exact,SurvivalDhCoverage equalValuesDifferentOwner,SurvivalCompleteOverlay.FrameLatch frame,int tiles){
        var ledger=new SurvivalDhGpuLedger();var ops=new SurvivalDhGpuPagesTest.Ops();var gpu=new SurvivalDhGpuPages(cpu,ledger,ops);
        gpu.start(output);assertThrows(SurvivalRefusal.class,output::tileCount);assertThrows(SurvivalRefusal.class,output::close);
        int slices=0;while(!gpu.step(4,Long.MAX_VALUE).complete())assertTrue(++slices<=64);
        var completion=gpu.completion();assertEquals(tiles,completion.tileCount());completion.requireAuthenticated(exact,frame);
        assertEquals("AS_GRASS",completion.historicalGrassSideMode());assertEquals("OFF",completion.historicalDebugMode());
        assertThrows(SurvivalRefusal.class,()->completion.requireAuthenticated(equalValuesDifferentOwner,frame));
        assertEquals(0,cpu.evidence().pools().get(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD).longValue());
        assertEquals(io.earthplatform.compat.dh.InstalledDhTerrainRuntime.SURVIVAL_WORKSPACE_BYTES,cpu.evidence().pools().get(NativeDistanceCpuBudget.Pool.DH_RESULTS).longValue());
        gpu.close();assertTrue(gpu.closeComplete());assertEquals(0,ledger.evidence().bytes());assertThrows(IllegalStateException.class,completion::requirePhysicalComplete);
    }
}
