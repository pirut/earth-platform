package io.earthplatform.minecraft.survival.runtime;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.survival.persistence.SurvivalCheckpointStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class InstalledMarinSessionStoreIdentityTest {
 @TempDir Path root;
 private InstalledMarinSessionStoreIdentity.Spec spec(UUID player,long nativeSeed){
  return new InstalledMarinSessionStoreIdentity.Spec("marin-player-environment-v2",new WorldIdentity(42,"marin","v2","terrain","palette"),
   "source-with-seed-"+nativeSeed,"palette","packs",player,ResourceLocation.parse("earthplatform:survival_carrier"),nativeSeed);
 }
 private void initializeStore(InstalledMarinSessionStoreIdentity.Opened opened)throws IOException{
  try(var store=new SurvivalCheckpointStore(opened.checkpointDirectory(),"test-store",new SurvivalCheckpointStore.Limits(1,4096,4096,4096))){assertTrue(store.load().isEmpty());}
 }
 @Test void sameSavePlayerProfileReusesIdentityRatherThanSessionUuid()throws Exception{
  UUID player=UUID.randomUUID();var expected=spec(player,73);var first=InstalledMarinSessionStoreIdentity.open(root,expected);initializeStore(first);
  byte[] bytes=Files.readAllBytes(first.directory().resolve("IDENTITY"));var second=InstalledMarinSessionStoreIdentity.open(root,expected);
  assertTrue(first.newlyCreated());assertFalse(second.newlyCreated());assertEquals(first.binding(),second.binding());
  assertArrayEquals(bytes,Files.readAllBytes(second.directory().resolve("IDENTITY")));
  assertTrue(InstalledMarinSessionStoreIdentity.requiresStartupRecovery(root,expected.profile(),player));
 }
 @Test void changedSeedOrPolicyCannotOverwriteExistingAuthority()throws Exception{
  UUID player=UUID.randomUUID();var opened=InstalledMarinSessionStoreIdentity.open(root,spec(player,73));initializeStore(opened);
  byte[] before=Files.readAllBytes(opened.directory().resolve("IDENTITY"));
  assertThrows(IOException.class,()->InstalledMarinSessionStoreIdentity.open(root,spec(player,74)));
  assertArrayEquals(before,Files.readAllBytes(opened.directory().resolve("IDENTITY")));
 }
 @Test void missingCurrentNeverCreatesFreshEmptyAuthorityOverExistingIdentity()throws Exception{
  UUID player=UUID.randomUUID();var expected=spec(player,73);var opened=InstalledMarinSessionStoreIdentity.open(root,expected);
  assertThrows(IOException.class,()->InstalledMarinSessionStoreIdentity.open(root,expected));
  assertFalse(Files.exists(opened.checkpointDirectory().resolve("CURRENT")));
  assertTrue(InstalledMarinSessionStoreIdentity.requiresStartupRecovery(root,expected.profile(),player));
 }
 @Test void missingCorruptAndLinkedIdentityRefuseWithoutReplacement()throws Exception{
  UUID player=UUID.randomUUID();var expected=spec(player,73);var opened=InstalledMarinSessionStoreIdentity.open(root,expected);initializeStore(opened);
  Path file=opened.directory().resolve("IDENTITY");Files.write(file,new byte[]{1,2,3});
  assertThrows(IOException.class,()->InstalledMarinSessionStoreIdentity.open(root,expected));assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(file));
  Files.delete(file);assertThrows(IOException.class,()->InstalledMarinSessionStoreIdentity.open(root,expected));
  Path outside=root.resolve("outside");Files.writeString(outside,"not an identity");Files.createSymbolicLink(file,outside);
  assertThrows(IOException.class,()->InstalledMarinSessionStoreIdentity.open(root,expected));assertTrue(Files.isSymbolicLink(file));
 }
 @Test void unrelatedOrdinarySaveHasNoStartupFence()throws Exception{
  assertFalse(InstalledMarinSessionStoreIdentity.requiresStartupRecovery(root,"marin-player-environment-v2",UUID.randomUUID()));
 }
}
