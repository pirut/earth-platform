package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.survival.render.*;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.*;

class SurvivalDhTerrainDataTest {
    @TempDir static Path source;
    @TempDir Path root;
    @BeforeAll static void setup()throws Exception{SurvivalDhTestSupport.setup(source);}
    static final SurvivalDhMaterialPalette.Material STONE=new SurvivalDhMaterialPalette.Material(0xff898989,2);
    static final SurvivalDhMaterialPalette.Material WOOD=new SurvivalDhMaterialPalette.Material(0xff6e5532,3);
    @Test void sparseAirTombstonePreservesBothExposedIntervalsWithoutVerticalScan(){
        var original=List.of(new SurvivalDhTerrainData.Run(MIN_Y,134,STONE));
        var result=SurvivalDhTerrainData.replace(original,-2048,-2032,List.of());
        assertEquals(List.of(new SurvivalDhTerrainData.Run(MIN_Y,-2048,STONE),new SurvivalDhTerrainData.Run(-2032,134,STONE)),result);
        assertEquals(2,result.size());
    }
    @Test void highBuildDoesNotExtrudeToSourceGroundAndMaterialBoundariesStayExact(){
        var original=List.of(new SurvivalDhTerrainData.Run(MIN_Y,134,STONE));
        var result=SurvivalDhTerrainData.replace(original,2048,2064,List.of(new SurvivalDhTerrainData.Run(2051,2053,WOOD)));
        assertEquals(2,result.size());assertEquals(134,result.get(0).maxY());assertEquals(2051,result.get(1).minY());
        var changed=SurvivalDhTerrainData.replace(original,128,144,List.of(new SurvivalDhTerrainData.Run(128,132,WOOD)));
        assertEquals(2,changed.size());assertEquals(STONE,changed.get(0).material());assertEquals(WOOD,changed.get(1).material());
    }
    @Test void richerMaterialWrapperDoesNotWeakenLegacyFaceGuard(){
        assertThrows(IllegalArgumentException.class,()->new DhTerrainGeometry.PageFace(Direction.UP,0,0,134,0,1,1,0xff72974a,13));
        var geometry=new DhTerrainGeometry.PageFace(Direction.UP,0,0,134,0,1,1,0xff72974a);
        assertEquals(13,new SurvivalDhTerrainData.PageFace(geometry,13).category());
        assertThrows(IllegalArgumentException.class,()->new SurvivalDhTerrainData.PageFace(geometry,0));
        assertThrows(IllegalArgumentException.class,()->new SurvivalDhTerrainData.PageFace(geometry,14));
    }
    @Test void exactMarin24PlanProducesBoundedComplementAndRetainsNoPlanAlias()throws Exception{
        try(var f=new SurvivalDhTestSupport(root);var plan=f.plan(24);
                var cover=SurvivalDhCoverage.capture(plan,SurvivalDhTestSupport.masks(plan),f.budget);
                var palette=new SurvivalDhMaterialPalette(SurvivalDhTestSupport.source.palette(),f.budget)) {
            long baseline=f.budget.evidence().bytes();
            var result=SurvivalDhTerrainData.build(SurvivalDhTestSupport.source,palette,cover,f.centerX(),f.centerZ(),SurvivalDhTerrainData.Limits.standard(),f.budget,()->{});
            System.out.println("SURVIVAL_DH_EXACT24_CENSUS "+result.census());
            assertEquals(cover.contentIdentity(),result.contentIdentity());assertEquals(cover.identity(),result.coverageIdentity());
            assertTrue(result.census().cells()<=DhNativeDistanceBudget.MAX_CELLS);assertTrue(result.census().outputFaces()>0);
            assertTrue(result.census().logicalWorkingBytes()<=DhNativeDistanceBudget.MAX_RESULT_BYTES);
            assertTrue(result.tileCount()<=DhNativeDistanceBudget.MAX_OWNERS);
            for(int tile=0;tile<result.tileCount();tile++)for(int index=0;index<result.faceCount(tile);index++) {
                var face=result.face(tile,index);var key=result.tileKey(tile);var g=face.geometry();
                assertTrue(List.of(1,2,3,5,13).contains(face.category()));
                long x=key.originX()+g.x()-(g.direction()==Direction.EAST?1:0);
                long z=key.originZ()+g.z()-(g.direction()==Direction.SOUTH?1:0);
                long y=(long)g.pageY2048()*2048+g.y()-(g.direction()==Direction.UP?1:0);
                assertFalse(cover.containsMember(CubePos.fromBlock(x,y,z)),"Far solid-side face remains inside native member");
            }
            result.close();result.close();assertThrows(SurvivalRefusal.class,result::tileCount);assertEquals(baseline,f.budget.evidence().bytes());
        }
    }
    @Test void unitOverrideOutsideSelectedNativeRegionExposesExactAirHole()throws Exception{
        try(var f=new SurvivalDhTestSupport(root)) {
            CubePos focus=SurvivalDhTestSupport.definition.anchor().center();CubePos hole=new CubePos(focus.x()+8,-128,focus.z());
            f.override(hole,Blocks.AIR.defaultBlockState());f.saveAndRelease(hole);
            assertEquals(io.earthplatform.minecraft.survival.world.NativeSurvivalWorld.Presence.UNKNOWN,f.world.presence(hole));
            try(var plan=f.plan(2);var cover=SurvivalDhCoverage.capture(plan,SurvivalDhTestSupport.masks(plan),f.budget);
                    var palette=new SurvivalDhMaterialPalette(SurvivalDhTestSupport.source.palette(),f.budget);
                    var result=SurvivalDhTerrainData.build(SurvivalDhTestSupport.source,palette,cover,f.centerX(),f.centerZ(),SurvivalDhTerrainData.Limits.standard(),f.budget,()->{})) {
                assertFalse(cover.containsMember(hole));assertEquals(4096,result.census().overrideReads());
                assertEquals(expectedBox(hole,true),boxFaces(result,hole),"Unloaded AIR cavity differs from exact native voxel face ownership");
            }
        }
    }
    @Test void highWoodBuildHasExactSixSidesAndNoExtrudedGroundWall()throws Exception{
        try(var f=new SurvivalDhTestSupport(root)) {
            CubePos focus=SurvivalDhTestSupport.definition.anchor().center();CubePos box=new CubePos(focus.x()+8,128,focus.z());
            f.override(box,Blocks.OAK_PLANKS.defaultBlockState());
            try(var plan=f.plan(2);var cover=SurvivalDhCoverage.capture(plan,SurvivalDhTestSupport.masks(plan),f.budget);
                    var palette=new SurvivalDhMaterialPalette(SurvivalDhTestSupport.source.palette(),f.budget);
                    var result=SurvivalDhTerrainData.build(SurvivalDhTestSupport.source,palette,cover,f.centerX(),f.centerZ(),SurvivalDhTerrainData.Limits.standard(),f.budget,()->{})) {
                assertEquals(expectedBox(box,false),boxFaces(result,box));
                for(int t=0;t<result.tileCount();t++)for(int i=0;i<result.faceCount(t);i++) {
                    var face=result.face(t,i);var g=face.geometry();long y=(long)g.pageY2048()*2048+g.y();
                    if(face.category()==3&&y>1024)assertTrue(y>=box.minBlockY()&&y<=box.minBlockY()+16);
                }
            }
        }
    }
    static java.util.Set<String> expectedBox(CubePos p,boolean hole) {
        var result=new java.util.HashSet<String>();long x=p.minBlockX(),y=p.minBlockY(),z=p.minBlockZ();
        for(int u=0;u<16;u++)for(int v=0;v<16;v++) {
            result.add(key(hole?Direction.UP:Direction.DOWN,x+u,y,z+v));
            result.add(key(hole?Direction.DOWN:Direction.UP,x+u,y+16,z+v));
            result.add(key(hole?Direction.EAST:Direction.WEST,x,y+v,z+u));
            result.add(key(hole?Direction.WEST:Direction.EAST,x+16,y+v,z+u));
            result.add(key(hole?Direction.SOUTH:Direction.NORTH,x+u,y+v,z));
            result.add(key(hole?Direction.NORTH:Direction.SOUTH,x+u,y+v,z+16));
        }
        assertEquals(1536,result.size());return result;
    }
    static java.util.Set<String> boxFaces(SurvivalDhTerrainData.Result result,CubePos p) {
        var faces=new java.util.HashSet<String>();long x0=p.minBlockX(),y0=p.minBlockY(),z0=p.minBlockZ();
        for(int t=0;t<result.tileCount();t++)for(int i=0;i<result.faceCount(t);i++) {
            var g=result.face(t,i).geometry();var k=result.tileKey(t);long x=k.originX()+g.x(),y=(long)g.pageY2048()*2048+g.y(),z=k.originZ()+g.z();
            if(x<x0||x>x0+16||z<z0||z>z0+16||y<y0||y>y0+16)continue;
            for(int u=0;u<g.width();u++)for(int v=0;v<g.height();v++) {
                long xx=x,yy=y,zz=z;
                switch(g.direction()) {case UP,DOWN->{xx+=u;zz+=v;}case NORTH,SOUTH->{xx+=u;yy+=v;}case EAST,WEST->{zz+=u;yy+=v;}}
                if(xx>=x0&&xx<=x0+16&&zz>=z0&&zz<=z0+16&&yy>=y0&&yy<=y0+16)
                    assertTrue(faces.add(key(g.direction(),xx,yy,zz)),"Duplicate emitted unit face in exact voxel oracle");
            }
        }
        return faces;
    }
    static String key(Direction d,long x,long y,long z){return d+":"+x+","+y+","+z;}
    @Test void cancellationAndCellRefusalDrainOnlyTheirOwnCharge()throws Exception{
        try(var f=new SurvivalDhTestSupport(root);var plan=f.plan(2);
                var cover=SurvivalDhCoverage.capture(plan,SurvivalDhTestSupport.masks(plan),f.budget);
                var palette=new SurvivalDhMaterialPalette(SurvivalDhTestSupport.source.palette(),f.budget)) {
            long baseline=f.budget.evidence().bytes();AtomicInteger checks=new AtomicInteger();
            assertThrows(CancellationException.class,()->SurvivalDhTerrainData.build(SurvivalDhTestSupport.source,palette,cover,f.centerX(),f.centerZ(),SurvivalDhTerrainData.Limits.standard(),f.budget,
                    ()->{if(checks.incrementAndGet()==100)throw new CancellationException("injected");}));
            assertEquals(baseline,f.budget.evidence().bytes());
            var tiny=new SurvivalDhTerrainData.Limits(1,131072,256,4096,20L<<20,4194304,262144);
            assertThrows(SurvivalRefusal.class,()->SurvivalDhTerrainData.build(SurvivalDhTestSupport.source,palette,cover,f.centerX(),f.centerZ(),tiny,f.budget,()->{}));
            assertEquals(baseline,f.budget.evidence().bytes());
            assertThrows(IllegalArgumentException.class,()->new SurvivalDhTerrainData.Limits(65537,131072,256,4096,20L<<20,4194304,262144));
        }
    }
    @Test void finiteSourceAndWrongRequestCentersRefuseWithoutReservation()throws Exception{
        try(var f=new SurvivalDhTestSupport(root);var plan=f.plan(2);
                var cover=SurvivalDhCoverage.capture(plan,SurvivalDhTestSupport.masks(plan),f.budget);
                var palette=new SurvivalDhMaterialPalette(SurvivalDhTestSupport.source.palette(),f.budget)) {
            long baseline=f.budget.evidence().bytes();
            assertThrows(RuntimeException.class,()->SurvivalDhTerrainData.build(SurvivalDhTestSupport.source,palette,cover,Long.MIN_VALUE,f.centerZ(),SurvivalDhTerrainData.Limits.standard(),f.budget,()->{}));
            assertThrows(SurvivalRefusal.class,()->SurvivalDhTerrainData.build(SurvivalDhTestSupport.source,palette,cover,f.centerX()+128,f.centerZ(),SurvivalDhTerrainData.Limits.standard(),f.budget,()->{}));
            assertEquals(baseline,f.budget.evidence().bytes());
        }
    }
}
