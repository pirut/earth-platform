package io.earthplatform.minecraft.survival.render;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.lod.DhTerrainGeometry;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SurvivalDhCoverageTest {
    @TempDir static Path source;
    @TempDir Path root;
    @BeforeAll static void setup()throws Exception{SurvivalDhTestSupport.setup(source);}
    @Test void completeMasksCoverOnlyExactSelectedRectangle()throws Exception{
        try(var f=new SurvivalDhTestSupport(root);var plan=f.plan(2);
                var cover=SurvivalDhCoverage.capture(plan,SurvivalDhTestSupport.masks(plan),f.budget)) {
            var b=plan.bounds();
            assertEquals(SurvivalDhCoverage.Classification.FULL,cover.classification(b.minX(),b.minZ(),b.maxXExclusive(),b.maxZExclusive()));
            assertEquals(SurvivalDhCoverage.Classification.BOUNDARY,cover.classification(b.minX()-1,b.minZ(),b.maxXExclusive(),b.maxZExclusive()));
            assertEquals(SurvivalDhCoverage.Classification.UNCOVERED,cover.classification(b.minX()-16,b.minZ(),b.minX(),b.maxZExclusive()));
            for(int i=0;i<plan.pageCount();i++)for(CubePos p:plan.page(i).address().members())
                assertEquals(plan.containsMember(p),cover.containsMember(p));
        }
    }
    @Test void oneMissingSignedMemberPreventsFullColumnOmission()throws Exception{
        try(var f=new SurvivalDhTestSupport(root);var plan=f.plan(2)) {
            var page=plan.page(0);int bit=Long.numberOfTrailingZeros(page.memberMask());
            CubePos p=page.address().minimum().offset(bit&3,bit>>>4,(bit>>>2)&3);
            var masks=new TreeMap<>(SurvivalDhTestSupport.masks(plan));long remaining=page.memberMask()&~(1L<<bit);
            if(remaining==0)masks.remove(page.address().minimum());else masks.put(page.address().minimum(),remaining);
            try(var cover=SurvivalDhCoverage.capture(plan,masks,f.budget)) {
                assertFalse(cover.containsMember(p));
                assertNotEquals(SurvivalDhCoverage.Classification.FULL,cover.classification(p.minBlockX(),p.minBlockZ(),p.minBlockX()+16,p.minBlockZ()+16));
                assertNotEquals(SurvivalDhCoverage.Classification.FULL,cover.classification(p.minBlockX()-1,p.minBlockZ()-1,p.minBlockX()+17,p.minBlockZ()+17));
            }
        }
    }
    @Test void emptyCoverageSuppressesNothingAndIsNotFull()throws Exception{
        try(var f=new SurvivalDhTestSupport(root);var plan=f.plan(2);var cover=SurvivalDhCoverage.capture(plan,Map.of(),f.budget)) {
            var b=plan.bounds();assertEquals(SurvivalDhCoverage.Classification.UNCOVERED,cover.classification(b.minX(),b.minZ(),b.maxXExclusive(),b.maxZExclusive()));
            var face=new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.UP,b.minX(),128,b.minZ(),16,16,0xff999999);
            assertEquals(java.util.List.of(face),cover.clip(face));
        }
    }
    @Test void bit63IsARealSignedMemberAndAirDoesNotEraseItsOwnership()throws Exception{
        try(var f=new SurvivalDhTestSupport(root)) {
            CubePos focus=SurvivalDhTestSupport.definition.anchor().center();
            CubePos p=SurvivalVisualPage.containing(focus).minimum().offset(3,3,3);
            f.override(p,Blocks.AIR.defaultBlockState());
            try(var plan=f.plan(4);var cover=SurvivalDhCoverage.capture(plan,Map.of(SurvivalVisualPage.containing(p).minimum(),Long.MIN_VALUE),f.budget)) {
                assertTrue(cover.containsMember(p));assertFalse(cover.containsMember(p.offset(0,-1,0)));
                for(var direction:DhTerrainGeometry.Direction.values()) {
                    long x=p.minBlockX()+7,y=p.minBlockY()+7,z=p.minBlockZ()+7;
                    if(direction==DhTerrainGeometry.Direction.UP)y=p.minBlockY()+16;
                    if(direction==DhTerrainGeometry.Direction.DOWN)y=p.minBlockY();
                    if(direction==DhTerrainGeometry.Direction.EAST)x=p.minBlockX()+16;
                    if(direction==DhTerrainGeometry.Direction.WEST)x=p.minBlockX();
                    if(direction==DhTerrainGeometry.Direction.SOUTH)z=p.minBlockZ()+16;
                    if(direction==DhTerrainGeometry.Direction.NORTH)z=p.minBlockZ();
                    assertTrue(cover.clip(new DhTerrainGeometry.Face(direction,x,y,z,1,1,0xff999999)).isEmpty(),direction.toString());
                }
                var vertical=new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.NORTH,p.minBlockX(),p.minBlockY()-5,p.minBlockZ(),1,26,0xff999999);
                var pieces=cover.clip(vertical);assertEquals(2,pieces.size());
                assertEquals(5,pieces.get(0).height());assertEquals(p.minBlockY()+16,pieces.get(1).y());assertEquals(5,pieces.get(1).height());
            }
        }
    }
    @Test void invalidMasksRefuseWithoutLeakingAndClosedAliasesCannotRead()throws Exception{
        try(var f=new SurvivalDhTestSupport(root);var plan=f.plan(2)) {
            long baseline=f.budget.evidence().bytes();var page=plan.page(0);var invalid=new TreeMap<CubePos,Long>();
            invalid.put(page.address().minimum(),0L);
            assertThrows(SurvivalRefusal.class,()->SurvivalDhCoverage.capture(plan,invalid,f.budget));
            assertEquals(baseline,f.budget.evidence().bytes());
            invalid.clear();invalid.put(page.address().minimum().offset(0,100,0),1L);
            assertThrows(SurvivalRefusal.class,()->SurvivalDhCoverage.capture(plan,invalid,f.budget));
            assertEquals(baseline,f.budget.evidence().bytes());
            var first=SurvivalDhCoverage.capture(plan,SurvivalDhTestSupport.masks(plan),f.budget);var alias=first.retain();
            String identity=alias.identity();first.close();
            assertThrows(SurvivalRefusal.class,first::identity);assertEquals(identity,alias.identity());
            assertTrue(f.budget.evidence().bytes()>baseline);alias.close();alias.close();assertEquals(baseline,f.budget.evidence().bytes());
        }
    }
    @Test void sourceGroundIsNotFullFromOnlyTreeReasonMasks()throws Exception{
        try(var f=new SurvivalDhTestSupport(root);var plan=f.plan(12)) {
            var masks=new TreeMap<CubePos,Long>();
            for(int i=0;i<plan.pageCount();i++) {
                var p=plan.page(i);long treeOnly=p.treeMask()&~p.groundMask()&~p.editExceptionMask();
                if(treeOnly!=0)masks.put(p.address().minimum(),treeOnly);
            }
            try(var cover=SurvivalDhCoverage.capture(plan,masks,f.budget)) {
                var b=plan.bounds();assertNotEquals(SurvivalDhCoverage.Classification.FULL,cover.classification(b.minX(),b.minZ(),b.maxXExclusive(),b.maxZExclusive()));
            }
        }
    }
}
