package io.earthplatform.minecraft.survival.persistence;
import io.earthplatform.minecraft.survival.interaction.host.PinnedNativeEventAdmission;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.ChangeDimensionTrigger;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/** Read-only admission of the pinned changeDimension closure, never removes or pre-awards listeners. */
final class NativeSessionTransferAdmission {
 private NativeSessionTransferAdmission(){}
 static void require(ServerPlayer player,ServerLevel from,ServerLevel to){
  if(player.hasAttachments() || from.hasAttachments() || to.hasAttachments() || player.server.getLevel(from.dimension())!=from
    || player.server.getLevel(to.dimension())!=to || player.server.getPlayerList().getPlayer(player.getUUID())!=player
    || player.server.getPlayerList().getPlayerCount()!=1 || player.getCamera()!=player || player.isPassenger() || player.isVehicle()
    || !player.getActiveEffects().isEmpty() || from.dimension()==Level.NETHER || from.dimension()==Level.END
    || to.dimension()==Level.NETHER || to.dimension()==Level.END)
   throw new SurvivalRefusal("First native transfer needs exact registered, sole player, no attachments and ordinary/Earth Levels");
  final PinProfile profile=NativeSessionExecutionGate.requireInstalledTransfer(player,from,to)
    ? PinProfile.INSTALLED : PinProfile.DEVELOPMENT;
  for(var entry:Map.of(
    "net.minecraft.server.level.ServerPlayer",profile.expected("d48041f234ec0a2097d1947398b14eb66ef4fb17c9574517d818147ea7df74c2","ec7c90a8b6a771cfd53b5038f377a72314b9efbf2301ed1ce540723b7d19c6d2"),
    "net.minecraft.server.network.ServerGamePacketListenerImpl",profile.expected("0602f43318d5bf25d5c2f0468728dd26a61cac8e899e58a86dd5a81343417138","f8193992da15902eadb8a77681b1d9ee4ab3bed175ab7029d46061fa6486ba15"),
    "net.minecraft.server.network.ServerCommonPacketListenerImpl",profile.expected("2626df5c94f13f877364d5c13aba9d587f2261589f039ccd89b52341eeb0a1e0","91e3cff96e83eba5d65d536aad46af1b0d96c2c61e3d6c71043610e7814c7fdb"),
    "net.minecraft.server.level.ServerPlayerGameMode",profile.expected("b90be8fa9fedc2914a5b8f36082636f40901d7ade594b80c4d6a606838bc5c27","79cbb280a2e605bd5ff097e52bab487b685493a01ffc1b5ad28d1d80daa27a01"),
    "net.minecraft.world.entity.Entity",profile.expected("6a5a5b7db211b144fe88f3079a0e1d93a0c31db069acab4ac593dce8d144e8cb","4f65b7d5d4628e9f10a6a23e5a0f68814520d8019f4bc001924cd752c00eed84"),
    "net.minecraft.server.players.PlayerList",profile.expected("164f99fa12710bd1cf80901abc5e38bae32bfeb352bf67f88220e620b0ec6b24","c7ed33824878b591e2c8440d392b1a2fbecb152e51c0873ae02bf148e43de430"),
    "net.minecraft.advancements.critereon.ChangeDimensionTrigger",profile.expected("ae02cd66d1af005c46336e0855b3cdd2aea3669d42c36e1b1e0987f1063dc994","d49ec94af51f8e7ee5fc126ea21ed6fa82c64e448a953a14aadf904550db358f"),
    "net.minecraft.advancements.critereon.SimpleCriterionTrigger",profile.expected("e3c6eecbf8189c098dbd34994a4b7868b62cc20cc919fbd3882d296fe8ba90f2","a60b1010e6225217820a66879d8e414f044806e27cf52b0a6dd564ee9fe21ec7")).entrySet()) requireBinary(entry.getKey(),entry.getValue());
  var events=new PinnedNativeEventAdmission(NeoForge.EVENT_BUS);
  // Audited built-ins are no-ops for an exact ServerPlayer: it is neither ItemEntity nor Mob.
  events.requireDeliveryCallbacks();
  for(String name:List.of("net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent",
      "net.neoforged.neoforge.event.entity.EntityEvent$EnteringSection",
      "net.neoforged.neoforge.event.level.ChunkWatchEvent$Watch",
      "net.neoforged.neoforge.event.level.ChunkWatchEvent$UnWatch",
      "net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent",
      "net.neoforged.neoforge.event.tick.LevelTickEvent$Pre")) requireEmpty(events,name);
  NativeSessionCallbackAdmission.requireTransfer(player,from,to);
  requireNonmatchingDimensionCriteria(player,from,to);
 }
 private static void requireNonmatchingDimensionCriteria(ServerPlayer player,ServerLevel from,ServerLevel to){
  if(CriteriaTriggers.CHANGED_DIMENSION.getClass()!=ChangeDimensionTrigger.class)throw new SurvivalRefusal("Foreign dimension criterion trigger");
  try{
   Field field=SimpleCriterionTrigger.class.getDeclaredField("players");if(!field.trySetAccessible())throw new SurvivalRefusal("Dimension listener inspection unavailable");
   Object raw=field.get(CriteriaTriggers.CHANGED_DIMENSION);if(!(raw instanceof Map<?,?> map) || raw.getClass()!=IdentityHashMap.class)throw new SurvivalRefusal("Dimension listener map changed");
   Object found=map.get(player.getAdvancements());if(found==null)return;
   if(!(found instanceof Set<?> listeners) || found.getClass()!=HashSet.class || listeners.size()>64)throw new SurvivalRefusal("Dimension listener allowance exceeded");
   for(Object entry:listeners){
    if(!(entry instanceof CriterionTrigger.Listener<?> listener) || !(listener.trigger() instanceof ChangeDimensionTrigger.TriggerInstance instance)
      || instance.getClass()!=ChangeDimensionTrigger.TriggerInstance.class || instance.matches(from.dimension(),to.dimension()))
     throw new SurvivalRefusal("A matching dimension advancement effect is not admitted by the held transfer");
    var holder=player.server.getAdvancements().get(listener.advancement().id());
    if(holder!=listener.advancement() || holder.value().criteria().get(listener.criterion())==null
      || holder.value().criteria().get(listener.criterion()).trigger()!=CriteriaTriggers.CHANGED_DIMENSION
      || holder.value().criteria().get(listener.criterion()).triggerInstance()!=instance)
     throw new SurvivalRefusal("Dimension listener is not its exact loaded criterion");
   }
  }catch(ReflectiveOperationException failure){var refused=new SurvivalRefusal("Cannot inspect live dimension listeners");refused.initCause(failure);throw refused;}
 }
 @SuppressWarnings("unchecked") private static void requireEmpty(PinnedNativeEventAdmission events,String name){
  try{Class<?> type=Class.forName(name,false,NativeSessionTransferAdmission.class.getClassLoader());
   if(!Event.class.isAssignableFrom(type))throw new SurvivalRefusal("Unknown transfer callback class");events.requireEmpty((Class<? extends Event>)type);
  }catch(ClassNotFoundException failure){throw new SurvivalRefusal("Pinned transfer callback class unavailable: "+name);}
 }
 /** Only require() selects this value, after the exact registered gate/token check above. */
 private enum PinProfile {
  DEVELOPMENT,INSTALLED;
  private String expected(String development,String installed){return this==INSTALLED?installed:development;}
 }
 private static void requireBinary(String name,String expected){
  NativeSessionCallbackPins.verifyExactBinary(name,expected);
 }
}
