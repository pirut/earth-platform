package io.earthplatform.minecraft.survival.source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MarinPlayerEnvironmentV2Test {
 @Test void identitySeparatesTerrainSeedNativeBiomeSeedAndPalette(){
  var zero=MarinPlayerEnvironmentV2.worldIdentity("palette-a",0L);
  assertEquals(io.earthplatform.geography.survival.MarinSurvivalDefinition.SEED,zero.seed());
  assertNotEquals(zero,MarinPlayerEnvironmentV2.worldIdentity("palette-a",20221009L));
  assertNotEquals(zero,MarinPlayerEnvironmentV2.worldIdentity("palette-b",0L));
  assertNotEquals(MarinPlayerEnvironmentV1.worldIdentity("palette-a"),zero);
  assertNotEquals(MarinPlayerEnvironmentV1.PROFILE,MarinPlayerEnvironmentV2.PROFILE);
  assertNotEquals(MarinPlayerEnvironmentV1.SAVE_NAMESPACE,MarinPlayerEnvironmentV2.SAVE_NAMESPACE);
 }
 @Test void sourcePolicyIsStableAndExplicitlyVersioned(){
  assertEquals(MarinPlayerEnvironmentV2.sourceIdentity("palette-a",0L),MarinPlayerEnvironmentV2.sourceIdentity("palette-a",0L));
  assertEquals("marin-player-environment-v2",MarinPlayerEnvironmentV2.VERSION);
  assertThrows(IllegalArgumentException.class,()->MarinPlayerEnvironmentV2.sourceIdentity("",0L));
  assertThrows(IllegalArgumentException.class,()->MarinPlayerEnvironmentV2.sourceIdentity("x".repeat(1025),0L));
 }
}
