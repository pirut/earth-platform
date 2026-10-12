package io.earthplatform.minecraft.survival.runtime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class InstalledSessionControlProtocolTest {
 private final UUID session=UUID.fromString("11111111-2222-3333-4444-555555555555");
 private final UUID epoch=UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
 private String text(String action,String sequence){return "earthsurvival_control "+action+" "+session+" "+epoch+" "+sequence;}
 @Test void onlyBoundedTypedActionsAreParsed(){
  var status=InstalledSessionControlProtocol.parse(text("status","1"));assertNotNull(status);
  assertEquals(InstalledSessionControlProtocol.Action.STATUS,status.action());assertEquals(session,status.session());assertEquals(epoch,status.epoch());assertEquals(1,status.sequence());
  assertEquals(InstalledSessionControlProtocol.Action.RETURN,InstalledSessionControlProtocol.parse(text("return","2")).action());
  assertEquals(InstalledSessionControlProtocol.Action.RETRY_CHECKPOINT,InstalledSessionControlProtocol.parse(text("retry_checkpoint","3")).action());
  for(String action:new String[]{"save","retry","enter","op","RETURN","status;stop"})assertNull(InstalledSessionControlProtocol.parse(text(action,"1")));
 }
 @Test void rejectsOverflowReplayEncodingAndArbitraryCommands(){
  for(String sequence:new String[]{"0","-1","01","+1","9223372036854775808","1 stop",""})assertNull(InstalledSessionControlProtocol.parse(text("return",sequence)));
  assertNull(InstalledSessionControlProtocol.parse("op somebody"));assertNull(InstalledSessionControlProtocol.parse("/"+text("return","1")));
  assertNull(InstalledSessionControlProtocol.parse(text("return","1").replace(epoch.toString(),"a-b-c-d-e")));
  assertNull(InstalledSessionControlProtocol.parse(text("status","1").replace(" status ","  status ")));
  assertNull(InstalledSessionControlProtocol.parse(text("status","1")+" ".repeat(161)));
 }
}
