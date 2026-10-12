package io.earthplatform.minecraft.survival.render;

import io.earthplatform.core.CubePos;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Scalar geometry only. These matrices never mint production frame or draw authority. */
class SurvivalSceneDepthTest {
    private static final SurvivalDistancePagePlan.DistanceChoice DISTANCE=new SurvivalDistancePagePlan.DistanceChoice(2,2,0);
    private static final SurvivalDistancePagePlan.Bounds BOUNDS=new SurvivalDistancePagePlan.Bounds(-32,-32,48,48);
    private static Matrix4f projection(){return new Matrix4f().perspective((float)Math.toRadians(70),16f/9f,.05f,128f);}
    private static SurvivalDistancePagePlan.Page page(CubePos cube,long mask){return new SurvivalDistancePagePlan.Page(SurvivalVisualPage.containing(cube),mask,0,0);}
    @Test void actualSelectedDistanceAndRigidViewAreRequired(){
        var depth=SurvivalSceneDepth.inspect(DISTANCE,BOUNDS,projection(),new Matrix4f(),8,8,8);
        assertEquals(.05,depth.nativeNear(),1e-5);assertEquals(128,depth.nativeFar(),.5);assertEquals(32,depth.guaranteedRadius());
        assertThrows(IllegalArgumentException.class,()->SurvivalSceneDepth.inspect(new SurvivalDistancePagePlan.DistanceChoice(4,2,2),BOUNDS,projection(),new Matrix4f(),8,8,8));
        assertThrows(IllegalArgumentException.class,()->SurvivalSceneDepth.inspect(DISTANCE,BOUNDS,new Matrix4f(),new Matrix4f(),8,8,8));
        assertThrows(IllegalArgumentException.class,()->SurvivalSceneDepth.inspect(DISTANCE,BOUNDS,new Matrix4f().perspective(1,1,.05f,256),new Matrix4f(),8,8,8));
        assertThrows(IllegalArgumentException.class,()->SurvivalSceneDepth.inspect(DISTANCE,BOUNDS,projection(),new Matrix4f().scaling(2),8,8,8));
        assertThrows(IllegalArgumentException.class,()->SurvivalSceneDepth.inspect(DISTANCE,BOUNDS,projection(),new Matrix4f(),47,8,8));
    }
    @Test void signedYAndBit63UseTheCompleteCubeVolumeIncludingAir(){
        var p=page(new CubePos(-4,-4,-4),Long.MIN_VALUE);
        var depth=new SurvivalSceneDepth.Depth(.05,128,32);
        assertEquals(Long.MIN_VALUE,SurvivalSceneDepth.eligibleMask(p,new Matrix4f(),0,0,0,depth));
        assertEquals(0,SurvivalSceneDepth.eligibleMask(p,new Matrix4f(),0,200,0,depth));
    }
    @Test void fullCubeFarCornerAndActualViewTranslationMatter(){
        var p=page(new CubePos(0,0,0),1L);
        var depth=new SurvivalSceneDepth.Depth(.05,24,4);
        // A center/surface sample would fit, but the full cube corner is sqrt(3)*16 away.
        assertThrows(IllegalStateException.class,()->SurvivalSceneDepth.eligibleMask(p,new Matrix4f(),0,0,0,depth));
        var far=new SurvivalSceneDepth.Depth(.05,128,8);
        assertEquals(1L,SurvivalSceneDepth.eligibleMask(p,new Matrix4f(),-80,0,0,far));
        assertEquals(0L,SurvivalSceneDepth.eligibleMask(p,new Matrix4f().translation(64,0,0),-80,0,0,far));
    }
    @Test void rotationsKeepFullVolumeSelectionAndUnplannedBitsRemainClear(){
        var p=page(new CubePos(0,0,0),1L|Long.MIN_VALUE);
        var depth=new SurvivalSceneDepth.Depth(.05,128,32);
        long identity=SurvivalSceneDepth.eligibleMask(p,new Matrix4f(),8,8,8,depth);
        assertEquals(1L|Long.MIN_VALUE,identity);
        assertEquals(identity,SurvivalSceneDepth.eligibleMask(p,new Matrix4f().rotateY(.61f),8,8,8,depth));
    }
}
