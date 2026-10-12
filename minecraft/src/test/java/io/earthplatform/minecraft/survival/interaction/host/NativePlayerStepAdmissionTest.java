package io.earthplatform.minecraft.survival.interaction.host;

import static org.junit.jupiter.api.Assertions.*;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class NativePlayerStepAdmissionTest {
 @Test void sealedFootprintRetainsTrueYAndSweptLanding(){
  var low=NativePlayerStepAdmission.footprint(new AABB(10.2,135,10.2,10.8,136.8,10.8),new Vec3(0,-0.08,0),new Vec3(0,-1,0));
  assertTrue(low.contains(10,133,10));assertTrue(low.contains(10,138,10));
  assertFalse(low.contains(10,134+4096,10));
  var high=NativePlayerStepAdmission.footprint(new AABB(10.2,4231,10.2,10.8,4232.8,10.8),new Vec3(0,-0.08,0),new Vec3(0,-1,0));
  assertEquals(4096,high.y1()-low.y1());assertEquals(4096,high.y2()-low.y2());
  assertThrows(SurvivalRefusal.class,()->low.requireContains(high));
 }
 @Test void malformedOrUnboundedReadBoxesRefuse(){
  assertThrows(SurvivalRefusal.class,()->new NativePlayerStepAdmission.Bounds(0,10,0,0,9,0));
  assertThrows(SurvivalRefusal.class,()->new NativePlayerStepAdmission.Bounds(Integer.MIN_VALUE,0,0,Integer.MAX_VALUE,0,0));
  assertThrows(SurvivalRefusal.class,()->new NativePlayerStepAdmission.Bounds(0,0,0,64,64,64));
  assertThrows(SurvivalRefusal.class,()->NativePlayerStepAdmission.footprint(new AABB(0,0,0,0.6,1.8,0.6),new Vec3(0,Double.NaN,0),Vec3.ZERO));
 }
}
