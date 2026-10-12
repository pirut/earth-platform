package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.interaction.host.PinnedNativeEventAdmission;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.*;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import static io.earthplatform.minecraft.survival.persistence.NativeSessionListenerCensus.*;
import static io.earthplatform.minecraft.survival.persistence.NativeSessionCallbackWitness.Site.*;

/** Fixture-only source-reviewed callback closure. This is not arbitrary-mod sandboxing.
 * All original event dispatch remains enabled. A single exact minimal-stack profile is retained.
 */
public final class NativeSessionCallbackAdmission {
 private static final String NF="net.neoforged.neoforge.common.NeoForgeEventHandler";
 private static final String CORE="io.earthplatform.minecraft.coregate.CoreGateCommands";
 private static final String ROLL="io.earthplatform.minecraft.coregate.RollingGateCommands";
 private static final String PLAYER="net.neoforged.neoforge.event.entity.player.PlayerEvent$";
 private static final String TICK="net.neoforged.neoforge.event.tick.";
 private static NativeSessionCallbackAdmission retained;
 private final MinecraftServer server;
 private final ServerLevel ordinary,carrier;
 private final Object ordinarySource,carrierSource,ordinaryGenerator,carrierGenerator;
 private final NativeSessionListenerCensus census=new NativeSessionListenerCensus(NeoForge.EVENT_BUS);
 private NativeSessionCallbackAdmission(MinecraftServer server,ServerLevel ordinary,ServerLevel carrier){
  this.server=server;this.ordinary=ordinary;this.carrier=carrier;
  ordinarySource=ordinary.getChunkSource();carrierSource=carrier.getChunkSource();
  ordinaryGenerator=ordinary.getChunkSource().getGenerator();carrierGenerator=carrier.getChunkSource().getGenerator();
 }
 /** Invoke before any fixture native player construction/registration. Never replaces a profile. */
 public static NativeSessionCallbackAdmission prepare(MinecraftServer server,ServerLevel ordinary,ServerLevel carrier){
  if(retained!=null)throw new SurvivalRefusal("One registered held fixture per physical process required");
  var admission=new NativeSessionCallbackAdmission(server,ordinary,carrier);admission.require();retained=admission;return admission;
 }
 public static void requireTransfer(ServerPlayer player,ServerLevel from,ServerLevel to){
  if(NativeSessionExecutionGate.requireInstalledTransfer(player,from,to))return;
  var a=retained;if(a==null || player.server!=a.server || !(from==a.ordinary&&to==a.carrier || from==a.carrier&&to==a.ordinary))
   throw new SurvivalRefusal("Transfer callback admission lacks exact registered Level provenance");
  a.require();if(player.hasAttachments() || from.hasAttachments() || to.hasAttachments())throw new SurvivalRefusal("Native attachment callbacks are not admitted");
 }
 public List<String> report(){return census.rows().stream().map(Object::toString).toList();}
 public void require(){
  if(!Boolean.getBoolean("earthplatform.nativeSessionBridge") || !Boolean.getBoolean("neoforge.gameTestServer")
    || !(server instanceof GameTestServer) || !server.isSameThread() || server.isSingleplayer() || server.isPublished())
   throw new SurvivalRefusal("Callback profile requires unpublished actual GameTest server");
  Set<String> mods=new TreeSet<>();ModList.get().getMods().forEach(m->mods.add(m.getModId()));
  if(!mods.equals(Set.of("minecraft","neoforge","earthplatform")))throw new SurvivalRefusal("Registered held fixture requires exact minimal mod inventory: "+mods);
  if(server.getLevel(ordinary.dimension())!=ordinary || server.getLevel(carrier.dimension())!=carrier
    || ordinary.getClass()!=ServerLevel.class || carrier.getClass()!=ServerLevel.class
    || ordinary.getChunkSource()!=ordinarySource || carrier.getChunkSource()!=carrierSource
    || ordinary.getChunkSource().getGenerator()!=ordinaryGenerator || carrier.getChunkSource().getGenerator()!=carrierGenerator
    || ordinaryGenerator.getClass()!=FlatLevelSource.class || carrierGenerator.getClass()!=FlatLevelSource.class
    || ordinary.hasAttachments() || carrier.hasAttachments())throw new SurvivalRefusal("Native registered flat Level/generator/attachment profile changed");
  Set<String> actualLevels=new TreeSet<>();server.levelKeys().forEach(key->actualLevels.add(key.location().toString()));
  if(!actualLevels.equals(Set.of("minecraft:overworld","minecraft:the_nether","minecraft:the_end","earthplatform:survival_carrier")))
   throw new SurvivalRefusal("Native Level lookup/legacy carrier inventory changed: "+actualLevels);
  requireLegacyProfileDisabled();requireIdleOwners();requireEmptyCapabilityHolder(ordinary);requireEmptyCapabilityHolder(carrier);
  for(String name:List.of("net.neoforged.neoforge.common.UsernameCache","net.neoforged.neoforge.attachment.AttachmentSync","net.neoforged.neoforge.network.registration.NetworkRegistry"))NativeSessionCallbackPins.require(name);
  var delivery=new PinnedNativeEventAdmission(NeoForge.EVENT_BUS);delivery.requireDeliveryCallbacks();
  for(String suffix:List.of("LoadFromFile","SaveToFile","NameFormat","TabListNameFormat","PlayerChangeGameModeEvent"))census.require(PLAYER+suffix);
  for(String event:List.of("net.neoforged.neoforge.event.entity.EntityEvent$EnteringSection",
    "net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent",
    "net.neoforged.neoforge.event.entity.player.AdvancementEvent$AdvancementEarnEvent",
    "net.neoforged.neoforge.event.entity.player.AdvancementEvent$AdvancementProgressEvent",
    "net.neoforged.neoforge.event.level.ChunkWatchEvent$Watch",
    "net.neoforged.neoforge.event.level.ChunkWatchEvent$UnWatch",
    "net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent",
    TICK+"LevelTickEvent$Pre",TICK+"EntityTickEvent$Pre",TICK+"EntityTickEvent$Post",
    TICK+"PlayerTickEvent$Pre",TICK+"PlayerTickEvent$Post"))census.require(event);
  // The actual built-in stays registered. MinecraftServer.tickChildren invokes sendNextChunks
  // independently of connection.tick. This target census identifies AttachmentSync; it does not
  // fence that sender root or prove native pending/batch/ACK ownership. Installed admission must
  // establish those separately; finite fixture packet counts are evidence only for their runs.
  census.require("net.neoforged.neoforge.event.level.ChunkWatchEvent$Sent",Target.subscriber("net.neoforged.neoforge.attachment.AttachmentSync","onChunkSent"));
  census.require("net.neoforged.neoforge.event.OnDatapackSyncEvent",Target.subscriber(NF,"onDpSync"));
  census.require(PLAYER+"PlayerLoggedInEvent",Target.subscriber(NF,"playerLogin"),Target.subscriber(CORE,"login"),Target.subscriber(ROLL,"login"),Target.witnessed(EARTH_LOGIN));
  census.require(PLAYER+"PlayerLoggedOutEvent",Target.subscriber(CORE,"logout"),Target.subscriber(ROLL,"logout"));
  census.require(PLAYER+"PlayerChangedDimensionEvent",Target.witnessed(EARTH_DIMENSION));
  census.require(TICK+"ServerTickEvent$Pre",Target.subscriber(NF,"preServerTick"),Target.subscriber(ROLL,"motionInvariant"));
  census.require(TICK+"ServerTickEvent$Post",Target.subscriber(NF,"postServerTick"),Target.subscriber(CORE,"tick"),Target.subscriber(ROLL,"tick"));
  census.require(TICK+"LevelTickEvent$Post",Target.witnessed(CAPABILITY_POST));
 }
 private static void requireLegacyProfileDisabled(){
  for(String name:List.of("io.earthplatform.compat.PlayableProfile","io.earthplatform.compat.PlayableProfile$Loaded",
    "io.earthplatform.compat.PlayableProfile$Selection","io.earthplatform.compat.PlayableProfile$State",
    "io.earthplatform.compat.dh.EarlyPlayableAdmission","io.earthplatform.minecraft.coregate.RollingGateFixture",
    "io.earthplatform.minecraft.coregate.CoreGateFixture"))NativeSessionCallbackPins.require(name);
  for(String flag:List.of("earthplatform.experimental.authoritativeCoreGate","earthplatform.experimental.rollingCubeGate",
    "earthplatform.experimental.fullHeightPlayerGate","earthplatform.experimental.coreCollisionGate"))
   if(Boolean.getBoolean(flag))throw new SurvivalRefusal("Legacy native profile must remain disabled: "+flag);
  if(io.earthplatform.compat.PlayableProfile.selection().state()!=io.earthplatform.compat.PlayableProfile.State.DISABLED)
   throw new SurvivalRefusal("Installed playable profile is not the exact disabled fixture closure");
 }
 private void requireIdleOwners(){
  for(String name:List.of(CORE,ROLL)){
   NativeSessionCallbackPins.require(name);Class<?> owner=type(name);
   if(field(owner,null,"session")!=null)throw new SurvivalRefusal("Other Earth native owner is active");
   Object recovery=field(owner,null,"RECOVERY");
   NativeSessionCallbackPins.require(recovery.getClass().getName());
   if(field(recovery.getClass(),recovery,"current")!=null)throw new SurvivalRefusal("Other Earth recovery is pending");
  }
  for(String name:List.of("pendingOpen","pendingPlayable","pendingDistance"))if(field(type(ROLL),null,name)!=null)throw new SurvivalRefusal("Other Earth preparation is pending");
  Class<?> worker=type("net.neoforged.neoforge.common.WorldWorkerManager");NativeSessionCallbackPins.require(worker.getName());
  synchronized(worker){Object value=field(worker,null,"workers");if(value.getClass()!=ArrayList.class || !((List<?>)value).isEmpty())throw new SurvivalRefusal("Unaudited native world worker is active");}
 }
 private static void requireEmptyCapabilityHolder(ServerLevel level){
  NativeSessionCallbackPins.require("net.neoforged.neoforge.capabilities.CapabilityHooks");
  Object holder=field(ServerLevel.class,level,"capListenerHolder");
  if(holder.getClass()!=type("net.neoforged.neoforge.capabilities.CapabilityListenerHolder"))throw new SurvivalRefusal("Native capability listener holder changed");
  NativeSessionCallbackPins.require(holder.getClass().getName());
  Object map=field(holder.getClass(),holder,"byChunkThenBlock");
  if(map.getClass()!=it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap.class || !((Map<?,?>)map).isEmpty())
   throw new SurvivalRefusal("Native capability listener roster is not empty");
  if(field(holder.getClass(),holder,"queue").getClass()!=java.lang.ref.ReferenceQueue.class)throw new SurvivalRefusal("Native capability cleanup queue changed");
 }
}
