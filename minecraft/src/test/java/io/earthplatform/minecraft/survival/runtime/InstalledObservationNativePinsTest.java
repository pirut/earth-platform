package io.earthplatform.minecraft.survival.runtime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class InstalledObservationNativePinsTest {
 @Test void separateSevenClassOfficialProfile(){
  assertEquals("26a16a6055bb148e1ffbe0d73bf74c38b710046a96f50600f3ae6b38a618bf5a",InstalledObservationNativePins.expected("net.minecraft.client.server.IntegratedServer"));
  assertEquals("3f1b7a260a249ae836d9d71bf0a0fe7575b62aff48f2b8fac10dd06aaa9efd2f",InstalledObservationNativePins.expected("net.minecraft.server.MinecraftServer"));
  assertEquals("f8193992da15902eadb8a77681b1d9ee4ab3bed175ab7029d46061fa6486ba15",InstalledObservationNativePins.expected("net.minecraft.server.network.ServerGamePacketListenerImpl"));
  assertEquals("a45158b0d434f071f81a877d4d94a25bb4b1f47d4dca6e59505d0a764677c628",InstalledObservationNativePins.expected("net.minecraft.server.network.PlayerChunkSender"));
  assertEquals("ec7c90a8b6a771cfd53b5038f377a72314b9efbf2301ed1ce540723b7d19c6d2",InstalledObservationNativePins.expected("net.minecraft.server.level.ServerPlayer"));
  assertEquals("c7ed33824878b591e2c8440d392b1a2fbecb152e51c0873ae02bf148e43de430",InstalledObservationNativePins.expected("net.minecraft.server.players.PlayerList"));
  assertEquals("0aaf4d99d16b08f670b1a541207224a57ba65215b944c64818bf621332473641",InstalledObservationNativePins.expected("net.minecraft.client.server.IntegratedPlayerList"));
  assertEquals("official-installed-observation-21.1.256-v1",InstalledObservationNativePins.PROFILE);
 }
 @Test void foreignClassRefused(){assertThrows(IllegalArgumentException.class,()->InstalledObservationNativePins.expected("net.minecraft.world.level.Level"));}
 @Test void developmentHashIsNotAnAlternative(){assertNotEquals("8627bc6a77638dc7e2ca1dc8717c8b593e5bbea15f588051317a519eadbc7fb4",InstalledObservationNativePins.expected("net.minecraft.client.server.IntegratedServer"));}
 @Test void originalNamedIntegratedPlayerListHasSeparateArtifact(){assertEquals("a649bd3ce77f122d0f83ae1b37b4b303e662470f928b9bee5fbc76580805ffd4",InstalledObservationNativePins.NAMED_CLIENT_SHA256);assertNotEquals(InstalledObservationNativePins.NAMED_CLIENT_SHA256,InstalledObservationNativePins.PATCHED_CLIENT_SHA256);}
 @Test void foreignExceptionMessageIsNeverInvoked(){
  var hostile=new RuntimeException(){@Override public String getMessage(){throw new AssertionError("callback");}@Override public String toString(){throw new AssertionError("callback");}};
  String result=InstalledSessionCallbackProbe.describeFailure("OWNER",hostile);assertTrue(result.contains("stage=OWNER"));assertFalse(result.contains("reason="));
 }
 @Test void privateReasonIsBoundedAndPreserved() throws Exception {
  Class<?> type=Class.forName(InstalledSessionCallbackProbe.class.getName()+"$ProbeRefusal");var ctor=type.getDeclaredConstructor(String.class);ctor.setAccessible(true);
  Throwable refusal=(Throwable)ctor.newInstance("reason".repeat(1000));String result=InstalledSessionCallbackProbe.describeFailure("stage".repeat(1000),refusal);
  assertTrue(result.contains("reason=reason"));assertTrue(result.length()<900);
 }
}
