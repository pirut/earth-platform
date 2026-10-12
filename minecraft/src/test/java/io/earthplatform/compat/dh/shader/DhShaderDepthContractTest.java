package io.earthplatform.compat.dh.shader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DhShaderDepthContractTest {
    float[] identity(){return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
    @Test void exactFullRangeProjectionContract(){var a=identity();var r=DhShaderDepthContract.verify(3.67f,6516.697f,3.67f,6516.697f,new double[]{0,1},a,a,a,a);assertEquals(0,r.projectionMaxError());}
    @Test void diagnosticHalfRangeAndStockHighAltitudeClipAreRejected(){var a=identity();assertThrows(IllegalStateException.class,()->DhShaderDepthContract.verify(3,6516,3,6516,new double[]{.5,1},a,a,a,a));assertThrows(IllegalStateException.class,()->DhShaderDepthContract.verify(4318,6516,4318,6516,new double[]{0,1},a,a,a,a));}
    @Test void shortFarAndDifferentReconstructionAreRejected(){var a=identity();assertThrows(IllegalStateException.class,()->DhShaderDepthContract.verify(3,3620,3,3620,new double[]{0,1},a,a,a,a));assertThrows(IllegalStateException.class,()->DhShaderDepthContract.verify(3,6516,4,6516,new double[]{0,1},a,a,a,a));var changed=a.clone();changed[10]=.25f;assertThrows(IllegalStateException.class,()->DhShaderDepthContract.verify(3,6516,3,6516,new double[]{0,1},changed,a,a,a));}
    @Test void allMatrixComponentsAndNonfiniteValuesAreChecked(){for(int i=0;i<16;i++){var a=identity();var changed=a.clone();changed[i]=Float.NaN;assertThrows(IllegalStateException.class,()->DhShaderDepthContract.verify(3,6516,3,6516,new double[]{0,1},a,a,changed,a));}}

    float[] perspective(float fov,float aspect,float near){return new org.joml.Matrix4f().perspective((float)Math.toRadians(fov),aspect,near,6516).get(new float[16]);}
    @Test void actualNearCornersFitNormalWideAndBobbedProjection(){
        for(float aspect:new float[]{1,16f/9f,21f/9f}){
            var p=perspective(90,aspect,3.67f);var v=identity();
            var r=DhShaderDepthContract.verify(3.67f,6516,3.67f,6516,new double[]{0,1},p,p,v,v);
            assertTrue(r.maximumNearCornerRadius()>3.67&&r.maximumNearCornerRadius()<16);
        }
        var p=new org.joml.Matrix4f().set(perspective(90,16f/9f,3.67f)).translate(.15f,-.1f,.03f).rotateZ(.025f).get(new float[16]);
        assertTrue(DhShaderDepthContract.nearCornerRadius(p)<16);
    }
    @Test void highFovAndExtremeAspectRejectEvenWithSmallScalarNear(){
        for(var p:new float[][]{perspective(150,16f/9f,3.67f),perspective(90,8,3.67f),perspective(110,16f/9f,7.9f)}){
            var v=identity();assertThrows(IllegalStateException.class,()->DhShaderDepthContract.verify(3.67f,6516,3.67f,6516,new double[]{0,1},p,p,v,v));
        }
    }
    @Test void singularNonfiniteAndExactBoundaryCornersFailClosed(){
        assertThrows(IllegalStateException.class,()->DhShaderDepthContract.nearCornerRadius(new float[16]));
        var nonfinite=identity();nonfinite[0]=Float.NaN;assertThrows(IllegalStateException.class,()->DhShaderDepthContract.nearCornerRadius(nonfinite));
        // Orthographic near corners at exactly radius16 are refused too.
        var edge=new org.joml.Matrix4f().scaling(1f/16f,1,1).get(new float[16]);
        assertThrows(IllegalStateException.class,()->DhShaderDepthContract.nearCornerRadius(edge));
    }
}
