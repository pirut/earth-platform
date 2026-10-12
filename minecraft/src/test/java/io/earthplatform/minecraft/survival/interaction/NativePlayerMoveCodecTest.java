package io.earthplatform.minecraft.survival.interaction;
import static org.junit.jupiter.api.Assertions.*;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.survival.persistence.NativeFoodSnapshot;
import io.earthplatform.minecraft.survival.persistence.NativePlayerPoseSnapshot;
import io.earthplatform.minecraft.survival.world.NativeActionEffect;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
class NativePlayerMoveCodecTest {
 private static HolderLookup.Provider registries;
 @BeforeAll static void setup(){registries=VanillaRegistries.createLookup();}
 private NativeEffectCodec.PlayerMove value(double y){
  var pose=new NativePlayerPoseSnapshot(ResourceLocation.parse("earthplatform:survival"),1.5,y,2.5,0,0,0,0,0,0,true,
    GameType.SURVIVAL,GameType.SURVIVAL,new NativePlayerPoseSnapshot.AbilityState(false,false,false,false,true,0.05F,0.1F));
  return new NativeEffectCodec.PlayerMove(new WorldIdentity(39,"move-fixture","v1","source","palette"),UUID.randomUUID(),UUID.randomUUID(),1,7,
    pose,new NativeFoodSnapshot(20,0,5,0),4,3,2,1,0,1.5,y-1,2.5,0,0,true);
 }
 @Test void commandRetainsFullXYZAndOriginalCounters(){
  for(double y:new double[]{135,4231,-2049}){var v=value(y);assertEquals(v,NativeEffectCodec.decode(NativeEffectCodec.encode(v),registries));}
 }
 @Test void truncationTrailingAndNoncanonicalBooleanRefuse(){
  var encoded=NativeEffectCodec.encode(value(135));var bytes=encoded.payload();
  for(int i=0;i<bytes.length;i++){int n=i;assertThrows(RuntimeException.class,()->NativeEffectCodec.decode(new NativeActionEffect(encoded.type(),Arrays.copyOf(bytes,n)),registries));}
  assertThrows(RuntimeException.class,()->NativeEffectCodec.decode(new NativeActionEffect(encoded.type(),Arrays.copyOf(bytes,bytes.length+1)),registries));
  var bad=bytes.clone();bad[bad.length-1]=2;
  assertThrows(RuntimeException.class,()->NativeEffectCodec.decode(new NativeActionEffect(encoded.type(),bad),registries));
 }
}
