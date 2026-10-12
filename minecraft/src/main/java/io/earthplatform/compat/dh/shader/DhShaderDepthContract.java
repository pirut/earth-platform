package io.earthplatform.compat.dh.shader;

/** Pure validation of the ordinary Iris/DH projection convention, never depth remapping. */
public final class DhShaderDepthContract {
    private DhShaderDepthContract(){}
    public record Receipt(float nearPlane,float farPlane,double depthMinimum,double depthMaximum,float projectionMaxError,float modelViewMaxError,double maximumNearCornerRadius){}
    public static Receipt verify(float eventNear,float eventFar,float irisNear,float irisFar,double[] range,
                                 float[] uploadedProjection,float[] irisProjection,float[] uploadedView,float[] irisView){
        if(!Float.isFinite(eventNear)||!Float.isFinite(eventFar)||eventNear<=0||eventNear>=8||eventFar<6000||eventFar>10000)
            throw new IllegalStateException("Unsupported DH clip range for the bounded four-kilometre shader fixture");
        if(!close(eventNear,irisNear)||!close(eventFar,irisFar))throw new IllegalStateException("DH event and Iris reconstruction clip planes differ");
        if(range==null||range.length!=2||range[0]!=0.0||range[1]!=1.0)throw new IllegalStateException("Shader depth must use the full native/DH attachment range, not diagnostic depth halves");
        float projection=matrix(uploadedProjection,irisProjection),view=matrix(uploadedView,irisView);
        return new Receipt(eventNear,eventFar,range[0],range[1],projection,view,nearCornerRadius(uploadedProjection));
    }
    /** Selected-distance path. The caller must obtain the radius from the current
     * authenticated native publication; this pure validator cannot mint coverage. */
    public static Receipt verifyDistance(int selectedChunks,int effectiveChunks,double guaranteedNativeRadius,
            float eventNear,float eventFar,float irisNear,float irisFar,double[] range,
            float[] uploadedProjection,float[] irisProjection,float[] uploadedView,float[] irisView){
        if(selectedChunks<2||selectedChunks>32||effectiveChunks!=selectedChunks)
            throw new IllegalStateException("Actual selected native distance must be fully effective before drawing");
        double selectedRadius=Math.multiplyExact(selectedChunks,16);
        double fadeEnd=selectedRadius-24.0;
        if(!Double.isFinite(guaranteedNativeRadius)||guaranteedNativeRadius<=0
                ||guaranteedNativeRadius<fadeEnd||guaranteedNativeRadius>selectedRadius)
            throw new IllegalStateException("Current native publication must cover the actual Photon fade sphere");
        if(!Float.isFinite(eventNear)||!Float.isFinite(eventFar)||eventNear<=0||eventNear>=guaranteedNativeRadius
                ||eventFar<6000||eventFar>10000)
            throw new IllegalStateException("Unsupported selected-distance DH clip range");
        if(!close(eventNear,irisNear)||!close(eventFar,irisFar))throw new IllegalStateException("DH event and Iris reconstruction clip planes differ");
        if(range==null||range.length!=2||range[0]!=0.0||range[1]!=1.0)throw new IllegalStateException("Shader depth must use the full native/DH attachment range");
        float projection=matrix(uploadedProjection,irisProjection),view=matrix(uploadedView,irisView);
        return new Receipt(eventNear,eventFar,range[0],range[1],projection,view,nearCornerRadius(uploadedProjection,guaranteedNativeRadius));
    }
    /** Unproject the actual uploaded projection, including its affine bob/aspect
     * terms. All four OpenGL near-plane corners must lie strictly inside the
     * guaranteed native sphere; a scalar near distance is insufficient. */
    static double nearCornerRadius(float[] projection){return nearCornerRadius(projection,16.0);}
    private static double nearCornerRadius(float[] projection,double radiusLimit){
        var inverse=new org.joml.Matrix4d(new org.joml.Matrix4f().set(projection));
        double determinant=inverse.determinant();
        if(!Double.isFinite(determinant)||determinant==0)throw new IllegalStateException("Singular shader projection");
        inverse.invert();double maximum=0;
        for(int x:new int[]{-1,1})for(int y:new int[]{-1,1}){
            var corner=inverse.transform(new org.joml.Vector4d(x,y,-1,1));
            if(!Double.isFinite(corner.w)||Math.abs(corner.w)<1e-12)throw new IllegalStateException("Unbounded shader near corner");
            double px=corner.x/corner.w,py=corner.y/corner.w,pz=corner.z/corner.w;
            double radius=Math.sqrt(px*px+py*py+pz*pz);
            if(!Double.isFinite(radius)||radius>=radiusLimit)throw new IllegalStateException("Shader near corner exceeds guaranteed native coverage: "+radius);
            maximum=Math.max(maximum,radius);
        }
        return maximum;
    }
    private static float matrix(float[] a,float[] b){
        if(a==null||b==null||a.length!=16||b.length!=16)throw new IllegalArgumentException("Exact four-by-four projection matrices required");
        float maximum=0;for(int i=0;i<16;i++){if(!close(a[i],b[i]))throw new IllegalStateException("Uploaded Iris DH matrix differs from its reconstruction contract at component "+i);maximum=Math.max(maximum,Math.abs(a[i]-b[i]));}return maximum;
    }
    private static boolean close(float a,float b){return Float.isFinite(a)&&Float.isFinite(b)&&Math.abs(a-b)<=2e-6f*Math.max(1.0f,Math.max(Math.abs(a),Math.abs(b)));}
}
