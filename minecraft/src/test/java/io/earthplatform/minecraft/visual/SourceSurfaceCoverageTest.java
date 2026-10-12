package io.earthplatform.minecraft.visual;

import io.earthplatform.core.CubePos;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.visual.EarthDistanceSurfacePlanTest.*;

class SourceSurfaceCoverageTest {
    @Test void completeColumnsClassifyFullAndCrossingExactXzPerimeterIsBoundary(){
        var p=plan(flatSource(2,0,-1),null,2,0);var c=SourceSurfaceCoverage.complete(p,p.pageIndex().keySet());var b=p.bounds();
        assertEquals(SourceSurfaceCoverage.Classification.FULL,c.classification(b.minX(),b.minZ(),b.maxXExclusive(),b.maxZExclusive()));
        assertEquals(SourceSurfaceCoverage.Classification.BOUNDARY,c.classification(b.minX()-1,0,b.minX()+1,1));
        assertEquals(SourceSurfaceCoverage.Classification.UNCOVERED,c.classification(b.minX()-2,0,b.minX(),1));
        assertEquals(List.of(new SourceSurfaceCoverage.SolidSpan(-64,0)),c.solidSpans(-1,-1));
        assertTrue(c.covers(-1,-1,-1));assertFalse(c.covers(-1,-65,-1));assertFalse(c.covers(-1,0,-1));assertFalse(c.covers(b.minX()-1,-1,0));
        assertThrows(UnsupportedOperationException.class,()->c.eligiblePages().clear());assertSame(p,c.plan());
    }
    @Test void restrictedSignedPagesDoNotExtrudeCoverageAndPartialCliffsRemainBoundary(){
        var p=plan(source(2,0,(x,z)->flat(x<0?-75:200)),null,2,0);
        Set<NativePageKey> top=new HashSet<>();for(var key:p.pageIndex().keySet())if(key.y()==3)top.add(key);
        var c=SourceSurfaceCoverage.restricted(p,top);
        assertEquals(SourceSurfaceCoverage.Classification.BOUNDARY,c.classification(0,0,1,1));
        assertEquals(SourceSurfaceCoverage.Classification.FULL,c.classification(1,0,2,1));
        assertEquals(SourceSurfaceCoverage.Classification.UNCOVERED,c.classification(-1,0,0,1));
        assertEquals(List.of(new SourceSurfaceCoverage.SolidSpan(192,201)),c.solidSpans(0,0));
        assertTrue(c.covers(0,192,0));assertFalse(c.covers(0,191,0));assertFalse(c.covers(0,201,0));
        Set<NativePageKey> low=new HashSet<>();for(var key:p.pageIndex().keySet())if(key.y()==-2)low.add(key);
        var below=SourceSurfaceCoverage.restricted(p,low);
        assertEquals(List.of(new SourceSurfaceCoverage.SolidSpan(-128,-74)),below.solidSpans(-1,0));
        assertEquals(List.of(new SourceSurfaceCoverage.SolidSpan(-128,-64)),below.solidSpans(0,0));
        assertEquals(SourceSurfaceCoverage.Classification.FULL,below.classification(-1,0,0,1));
        assertEquals(SourceSurfaceCoverage.Classification.BOUNDARY,below.classification(0,0,1,1));
        assertFalse(below.covers(0,-129,0));assertFalse(below.covers(0,-64,0));
    }
    @Test void buriedEditCaveMakesAdditionalRequiredPageAndExactHalfOpenGaps(){
        var s=flatSource(2,0,15);var edit=capture(s,new CubePos(0,-5,0)).withMaterial(5,-70,5,0);var p=plan(s,edit,2,0);
        var all=SourceSurfaceCoverage.complete(p,p.pageIndex().keySet());
        assertEquals(SourceSurfaceCoverage.Classification.FULL,all.classification(5,5,6,6));
        assertEquals(List.of(new SourceSurfaceCoverage.SolidSpan(-128,-70),new SourceSurfaceCoverage.SolidSpan(-69,-64),new SourceSurfaceCoverage.SolidSpan(0,16)),all.solidSpans(5,5));
        assertTrue(all.covers(5,-71,5));assertFalse(all.covers(5,-70,5));assertTrue(all.covers(5,-69,5));assertFalse(all.covers(5,-1,5));
        Set<NativePageKey> top=new HashSet<>();for(var key:p.pageIndex().keySet())if(key.y()==0)top.add(key);
        var partial=SourceSurfaceCoverage.restricted(p,top);
        assertEquals(SourceSurfaceCoverage.Classification.BOUNDARY,partial.classification(5,5,6,6));
        assertEquals(SourceSurfaceCoverage.Classification.BOUNDARY,partial.classification(4,5,5,6));
        assertEquals(SourceSurfaceCoverage.Classification.FULL,partial.classification(3,5,4,6));
    }
    @Test void buriedSolidInteriorAloneDoesNotTriggerFarBoundaryRefinement(){
        var p=plan(source(2,0,(x,z)->flat(x<0?-75:200)),null,2,0);Set<NativePageKey> selected=new HashSet<>();
        for(var key:p.pageIndex().keySet())if(key.y()==0)selected.add(key);
        var c=SourceSurfaceCoverage.restricted(p,selected);
        assertEquals(SourceSurfaceCoverage.Classification.BOUNDARY,c.classification(0,0,1,1));
        assertEquals(SourceSurfaceCoverage.Classification.UNCOVERED,c.classification(1,0,2,1));
        assertEquals(List.of(new SourceSurfaceCoverage.SolidSpan(0,64)),c.solidSpans(1,0));
        assertTrue(c.covers(1,32,0)); // occupancy is exact, but no native face is exposed at this column/page
    }
    @Test void emptyAndForeignPageSetsFailClosedAndCompletionRequiresExactDomain(){
        var p=plan(flatSource(2,0,15),null,2,0);var empty=SourceSurfaceCoverage.restricted(p,Set.of());
        assertEquals(SourceSurfaceCoverage.Classification.UNCOVERED,empty.classification(-10,-10,10,10));assertEquals(List.of(),empty.solidSpans(0,0));assertFalse(empty.covers(0,15,0));
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->SourceSurfaceCoverage.complete(p,Set.of()));
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->SourceSurfaceCoverage.restricted(p,Set.of(new NativePageKey(0,7,0))));
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->empty.classification(Long.MIN_VALUE,0,Long.MAX_VALUE,1));
    }
}
