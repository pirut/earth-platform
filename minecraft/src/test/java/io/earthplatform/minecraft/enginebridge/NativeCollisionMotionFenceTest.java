package io.earthplatform.minecraft.enginebridge;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeCollisionMotionFenceTest {
    private final AABB body=new AABB(4.2,129,3.7,4.8,130.8,4.3);
    @Test void boundsVanillaPlayerAndFiniteWalkingDisplacement(){assertTrue(NativeCollisionMotionFence.bounded(body,new Vec3(0,-3.9,.2)));assertTrue(NativeCollisionMotionFence.bounded(body,new Vec3(4,4,4)));assertFalse(NativeCollisionMotionFence.bounded(body,new Vec3(4.001,0,0)));}
    @Test void rejectsOversizedBodiesAndNonfiniteMotion(){assertFalse(NativeCollisionMotionFence.bounded(new AABB(0,0,0,100000,2,1),Vec3.ZERO));for(double v:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})assertFalse(NativeCollisionMotionFence.bounded(body,new Vec3(0,v,0)));}
}
