package io.earthplatform.minecraft.scene;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.lod.DhAdaptiveTerrainData;
import io.earthplatform.minecraft.lod.DhTerrainGeometry;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class SceneAdaptiveSourceContractTest {
    @Test void actualInitialAndMovedNearMasksAndUnmaskedRefinedFallbackFitHardBudgets() {
        String old=System.getProperty(SceneTerrain.NATURAL_RELIEF_OPT_IN);
        try {
            System.setProperty(SceneTerrain.NATURAL_RELIEF_OPT_IN,"true");
            var terrain=new SceneTerrain();var data=new DhAdaptiveTerrainData(terrain,SceneCommands.START_X,SceneCommands.START_Z);
            for(double[] camera:new double[][]{{SceneCommands.START_X+0.5,7538.62,SceneCommands.START_Z+0.5},{9674962.895,7538.62,-3112429.490}}) {
                var plan=SceneInterestPlan.around(terrain,camera[0],camera[1],camera[2]);
                var ready=new ArrayList<>(new TreeSet<>(plan.renderTargets()));
                var masked=data.clip(ready);var fallback=data.clip(List.of(),ready);
                for(var batch:List.of(masked,fallback)) {
                    assertEquals(0,batch.unknownCells());assertEquals(batch.sampledCells(),batch.knownCells());
                    assertTrue(batch.sampledCells()<=DhAdaptiveTerrainData.MAX_CELLS);
                    assertTrue(batch.faces()<=DhAdaptiveTerrainData.MAX_FACES);
                    assertTrue(batch.tiles().size()<=DhAdaptiveTerrainData.MAX_TILES);
                    assertTrue(batch.refinedCells()>0);assertTrue(batch.transitionFaces()>0);
                    batch.tiles().values().forEach(faces->assertTrue(faces.size()<=DhAdaptiveTerrainData.MAX_PAGE_FACES));
                    assertExactMetreCollar(terrain,ready,batch);
                }
                assertEquals(masked.sampledCells(),fallback.sampledCells());assertTrue(fallback.faces()>=masked.faces());
                System.out.println("SCENE_ADAPTIVE_SOURCE cameraX="+camera[0]+" cameraZ="+camera[2]+" targets="+ready.size()
                        +" samples="+masked.sampledCells()+" maskedFaces="+masked.faces()+" fallbackFaces="+fallback.faces()
                        +" maskedOwners="+masked.tiles().size()+" fallbackOwners="+fallback.tiles().size());
            }
        } finally {if(old==null)System.clearProperty(SceneTerrain.NATURAL_RELIEF_OPT_IN);else System.setProperty(SceneTerrain.NATURAL_RELIEF_OPT_IN,old);}
    }
    private static void assertExactMetreCollar(SceneTerrain terrain,List<CubePos> ready,DhAdaptiveTerrainData.Batch batch) {
        Map<String,Long> tops=new HashMap<>();
        batch.tiles().forEach((tile,faces)->faces.forEach(face->{
            if(face.direction()==DhTerrainGeometry.Direction.UP&&face.width()==1&&face.height()==1)
                tops.put((tile.originX()+face.x())+":"+(tile.originZ()+face.z()),(long)face.pageY2048()*2048+face.y());
        }));
        long x0=ready.stream().mapToLong(CubePos::minBlockX).min().orElseThrow(),x1=ready.stream().mapToLong(CubePos::minBlockX).max().orElseThrow()+16;
        long z0=ready.stream().mapToLong(CubePos::minBlockZ).min().orElseThrow(),z1=ready.stream().mapToLong(CubePos::minBlockZ).max().orElseThrow()+16;
        for(long z=z0;z<z1;z++){assertTop(terrain,tops,x0-1,z);assertTop(terrain,tops,x1,z);}
        for(long x=x0;x<x1;x++){assertTop(terrain,tops,x,z0-1);assertTop(terrain,tops,x,z1);}
    }
    private static void assertTop(SceneTerrain terrain,Map<String,Long> tops,long x,long z) {
        assertEquals(terrain.surfaceY(x,z)+1,tops.get(x+":"+z),"Exact native-height collar at "+x+","+z);
    }
}
