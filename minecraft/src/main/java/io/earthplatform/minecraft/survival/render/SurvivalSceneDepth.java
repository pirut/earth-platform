package io.earthplatform.minecraft.survival.render;

import io.earthplatform.core.CubePos;
import java.util.Objects;
import org.joml.Matrix4f;

/** Pure conservative full-cube calculations. None of these scalar inputs or results grants authority. */
public final class SurvivalSceneDepth {
    private SurvivalSceneDepth() {}
    public record Depth(double nativeNear, double nativeFar, double guaranteedRadius) {}
    public static Depth inspect(SurvivalDistancePagePlan.DistanceChoice distance,
            SurvivalDistancePagePlan.Bounds bounds, Matrix4f projection, Matrix4f view,
            double x, double y, double z) {
        Objects.requireNonNull(distance);Objects.requireNonNull(bounds);Objects.requireNonNull(projection);Objects.requireNonNull(view);
        if(!distance.selectedDistanceEffective())throw new IllegalArgumentException("Selected native distance is not fully effective");
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!projection.isFinite()||!view.isFinite())
            throw new IllegalArgumentException("Finite actual native camera and matrices required");
        if(Math.abs(projection.m23()+1)>1e-5||Math.abs(projection.m33())>1e-5
                ||Math.abs(projection.m02())>1e-7||Math.abs(projection.m12())>1e-7
                ||Math.abs(projection.m03())>1e-7||Math.abs(projection.m13())>1e-7)
            throw new IllegalArgumentException("Pinned native perspective projection required");
        double near=projection.m32()/(projection.m22()-1.0),far=projection.m32()/(projection.m22()+1.0);
        double expected=distance.effectiveChunks()*64.0;
        if(Math.abs(near-.05)>1e-5||!Double.isFinite(far)||Math.abs(far-expected)>Math.max(.5,expected*.002))
            throw new IllegalArgumentException("Native clip planes disagree with the actual selected distance");
        // Native view may include bob/rotation/translation, but no projection or arbitrary scale.
        if(Math.abs(view.m03())>1e-7||Math.abs(view.m13())>1e-7||Math.abs(view.m23())>1e-7||Math.abs(view.m33()-1)>1e-7)
            throw new IllegalArgumentException("Affine native model-view required");
        for(int a=0;a<3;a++)for(int b=0;b<3;b++){
            double dot=0;for(int row=0;row<3;row++)dot+=(double)view.get(a,row)*view.get(b,row);
            if(Math.abs(dot-(a==b?1:0))>2e-4)throw new IllegalArgumentException("Rigid actual native model-view required");
        }
        double radius=distance.selectedChunks()*16.0;
        double guaranteed=Math.min(radius,Math.min(Math.min(x-bounds.minX(),bounds.maxXExclusive()-x),
                Math.min(z-bounds.minZ(),bounds.maxZExclusive()-z)));
        if(!Double.isFinite(guaranteed)||guaranteed<=0||guaranteed<radius-24.0)
            throw new IllegalArgumentException("Actual camera is outside the selected native fade footprint");
        return new Depth(near,far,guaranteed);
    }
    /** Full selected XYZ volumes, including completed AIR, determine the native/DH hole mask. */
    public static long eligibleMask(SurvivalDistancePagePlan.Page page, Matrix4f actualView,
            double cameraX, double cameraY, double cameraZ, Depth depth) {
        Objects.requireNonNull(page);Objects.requireNonNull(actualView);Objects.requireNonNull(depth);
        if(!actualView.isFinite()||!Double.isFinite(cameraX)||!Double.isFinite(cameraY)||!Double.isFinite(cameraZ)
                ||!Double.isFinite(depth.nativeFar)||!Double.isFinite(depth.guaranteedRadius)
                ||depth.nativeFar<=1||depth.guaranteedRadius<=0)
            throw new IllegalArgumentException("Finite positive native depth required");
        CubePos minimum=page.address().minimum();long mask=0;
        for(int bit=0;bit<64;bit++)if((page.memberMask()&(1L<<bit))!=0){
            CubePos cube=minimum.offset(bit&3,bit>>>4,(bit>>>2)&3);
            double x=cube.minBlockX()-cameraX,y=cube.minBlockY()-cameraY,z=cube.minBlockZ()-cameraZ;
            double farthest=0;
            for(int corner=0;corner<8;corner++){
                double px=x+((corner&1)==0?0:16),py=y+((corner&2)==0?0:16),pz=z+((corner&4)==0?0:16);
                double vx=actualView.m00()*px+actualView.m10()*py+actualView.m20()*pz+actualView.m30();
                double vy=actualView.m01()*px+actualView.m11()*py+actualView.m21()*pz+actualView.m31();
                double vz=actualView.m02()*px+actualView.m12()*py+actualView.m22()*pz+actualView.m32();
                farthest=Math.max(farthest,Math.sqrt(vx*vx+vy*vy+vz*vz));
            }
            if(farthest<depth.nativeFar-1.0)mask|=1L<<bit;
            else {
                double dx=gap(x,x+16),dy=gap(y,y+16),dz=gap(z,z+16);
                if(Math.sqrt(dx*dx+dy*dy+dz*dz)<=depth.guaranteedRadius)
                    throw new IllegalStateException("A selected native fade-sphere cube exceeds the actual depth envelope");
            }
        }
        return mask;
    }
    private static double gap(double minimum,double maximum){return Math.max(0,Math.max(minimum,-maximum));}
}
