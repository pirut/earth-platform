package io.earthplatform.compat.dh.shader;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import static org.junit.jupiter.api.Assertions.*;
class DhNativeDistanceDepthContractTest {
    private static float[] view(){return new Matrix4f().get(new float[16]);}
    private static float[] projection(float near,float aspect,float fov){return new Matrix4f().perspective((float)Math.toRadians(fov),aspect,near,6516.7f).get(new float[16]);}
    private static DhShaderDepthContract.Receipt verify(int selected,int effective,double radius,float near,float[] p){
        return DhShaderDepthContract.verifyDistance(selected,effective,radius,near,6516.7f,near,6516.7f,new double[]{0,1},p,p,view(),view());
    }
    @Test void allActualSliderDistancesRetainUploadedCornerProof(){
        for(int d=2;d<=32;d++){
            float near=d*2.01f;var p=projection(near,16f/9f,90);
            var receipt=verify(d,d,16*d,near,p);
            assertTrue(receipt.maximumNearCornerRadius()<16*d);assertEquals(0,receipt.projectionMaxError());
        }
    }
    @Test void twelveAndTwentyFourCannotUseOldSixteenMetreGuarantee(){
        for(int d:new int[]{12,24})assertThrows(IllegalStateException.class,()->verify(d,d,16,4,projection(4,1.7f,90)));
    }
    @Test void selectedEffectiveMismatchAndOutOfDomainAreRefused(){
        for(int[] d:new int[][]{{12,2},{24,12},{1,1},{33,33}})
            assertThrows(IllegalStateException.class,()->verify(d[0],d[1],192,4,projection(4,1.7f,90)));
    }
    @Test void radiusCannotComeFromUndrawnPrefetchOrMissFade(){
        var p=projection(4,1.7f,90);
        for(double radius:new double[]{167.999,193,Double.NaN,Double.POSITIVE_INFINITY})
            assertThrows(IllegalStateException.class,()->verify(12,12,radius,4,p));
        assertDoesNotThrow(()->verify(12,12,168,4,p));
    }
    @Test void scalarNearPassDoesNotHideWideNearCornerHole(){
        assertThrows(IllegalStateException.class,()->verify(12,12,192,60,projection(60,4,140)));
        assertThrows(IllegalStateException.class,()->verify(24,24,384,400,projection(400,1.7f,90)));
    }
    @Test void uniformMismatchAndHalfDepthStillFail(){
        var p=projection(24,1.7f,90);var changed=p.clone();changed[7]=.1f;
        assertThrows(IllegalStateException.class,()->DhShaderDepthContract.verifyDistance(12,12,192,24,6516.7f,24,6516.7f,new double[]{0,1},p,changed,view(),view()));
        assertThrows(IllegalStateException.class,()->DhShaderDepthContract.verifyDistance(12,12,192,24,6516.7f,24,6516.7f,new double[]{.5,1},p,p,view(),view()));
    }
    @Test void legacyExactSmallProfileStillRejectsNewNearRange(){
        var p=projection(24,1.7f,90);
        assertThrows(IllegalStateException.class,()->DhShaderDepthContract.verify(24,6516.7f,24,6516.7f,new double[]{0,1},p,p,view(),view()));
    }
}
