package io.earthplatform.minecraft.lod;

import io.earthplatform.minecraft.survival.render.SurvivalDhCoverage;
import io.earthplatform.minecraft.survival.render.SurvivalDhMaterialPalette;
import io.earthplatform.minecraft.survival.render.SurvivalDhTestSupport;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SurvivalDhResultTransferTest {
    @TempDir static Path source;
    @TempDir Path root;
    @BeforeAll static void setup()throws Exception{SurvivalDhTestSupport.setup(source);}
    @Test void exactBudgetExclusiveTransferRetainsCoverageAndReleasesPayloadOnce()throws Exception{
        try(var f=new SurvivalDhTestSupport(root);var plan=f.plan(24);
                var cover=SurvivalDhCoverage.capture(plan,SurvivalDhTestSupport.masks(plan),f.budget);
                var palette=new SurvivalDhMaterialPalette(SurvivalDhTestSupport.source.palette(),f.budget)){
            long baseline=f.budget.evidence().bytes();
            var result=SurvivalDhTerrainData.build(SurvivalDhTestSupport.source,palette,cover,f.centerX(),f.centerZ(),SurvivalDhTerrainData.Limits.standard(),f.budget,()->{});
            int count=result.tileCount();assertTrue(count>0);
            assertThrows(SurvivalRefusal.class,()->result.transferToCompiler(new NativeDistanceCpuBudget(NativeDistanceCpuBudget.MAX_BYTES)));
            assertEquals(count,result.tileCount());
            var owner=result.transferToCompiler(f.budget);assertEquals(count,owner.tileCount());
            assertThrows(SurvivalRefusal.class,result::tileCount);assertThrows(SurvivalRefusal.class,result::close);
            assertThrows(SurvivalRefusal.class,()->result.transferToCompiler(f.budget));
            try(var retained=owner.retainCoverage()){retained.requireSame(cover);assertTrue(owner.faceCount(0)>0);assertNotNull(owner.face(0,0));}
            owner.close();owner.close();assertTrue(owner.closeComplete());assertThrows(SurvivalRefusal.class,owner::tileCount);
            assertEquals(baseline,f.budget.evidence().bytes());
        }
    }
    @Test void exactDownExpansionCountMatchesEuclideanSquares(){
        assertEquals(5,SurvivalDhPageCompiler.downQuads(7,3));assertEquals(4096,SurvivalDhPageCompiler.downQuads(4096,1));
        assertEquals(1,SurvivalDhPageCompiler.downQuads(17,17));assertThrows(IllegalArgumentException.class,()->SurvivalDhPageCompiler.downQuads(0,1));
        assertEquals(4096+33024+4096*128L+4096*144L,SurvivalDhPageCompiler.copiedInputBytes(4096));
    }
}
