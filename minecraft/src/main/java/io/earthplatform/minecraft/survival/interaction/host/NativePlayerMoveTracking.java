package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/** Read-only exact native tracking closure for a same-section vertical move and one registered audience. */
final class NativePlayerMoveTracking {
 private record Tracked(Object owner,Entity entity,Vec3 position,int tickCount,boolean seen){}
 private static final Class<?> TRACKED=NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.server.level.ChunkMap$TrackedEntity",ChunkMap.class);
 private static final Class<?> IDENTITY_SET=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()).getClass();
 private final ServerPlayer player;private final ServerLevel level;private final ChunkMap map;
 private final Object nativeMap,trackingView;private final SectionPos section;private final List<Tracked> admitted;
 private final int maximum;private final List<Entity> owned;
 NativePlayerMoveTracking(ServerPlayer player,List<Entity> owned,int maximum,Vec3 requested){
  this.player=player;this.level=player.serverLevel();
  if(player.getClass()!=ServerPlayer.class||level.getClass()!=ServerLevel.class||level.getChunkSource().getClass()!=net.minecraft.server.level.ServerChunkCache.class)
   throw refused("Exact native level/chunk source required before tracking callbacks");
  this.map=level.getChunkSource().chunkMap;this.maximum=maximum;this.owned=List.copyOf(owned);
  if(maximum<1||maximum>4096||owned.size()>maximum||requested.x!=0||requested.z!=0
    ||!SectionPos.of(player).equals(SectionPos.of(net.minecraft.core.BlockPos.containing(player.position().add(requested)))))
   throw refused("Native move must remain in exact managed XYZ section");
  section=player.getLastSectionPos();trackingView=player.getChunkTrackingView();nativeMap=field(ChunkMap.class,map,"entityMap");
  admitted=inspect();
 }
 void revalidate(){
  var now=inspect();if(now.size()!=admitted.size())throw refused("Native tracked roster changed");
  for(int i=0;i<now.size();i++){
   var a=admitted.get(i);var b=now.get(i);
   if(a.owner!=b.owner||a.entity!=b.entity||a.tickCount!=b.tickCount||a.seen!=b.seen
       ||a.entity!=player&&!a.position.equals(b.position))throw refused("Native tracked object, position or pairing changed");
  }
 }
 @SuppressWarnings("unchecked") private List<Tracked> inspect(){
  if(map.getClass()!=ChunkMap.class||level.getChunkSource().chunkMap!=map||field(ChunkMap.class,map,"entityMap")!=nativeMap
    ||nativeMap==null||nativeMap.getClass()!=it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap.class)
   throw refused("Foreign native entity tracking collection");
  var entities=(it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<?>)nativeMap;
  requirePlayerCollections();
  if(entities.size()>maximum
    ||entities.size()!=owned.size()+1
    ||player.serverLevel()!=level||!player.isAddedToLevel()||level.getEntity(player.getId())!=player||level.getEntity(player.getUUID())!=player
    ||!section.equals(SectionPos.of(player))||!section.equals(player.getLastSectionPos())||player.getChunkTrackingView()!=trackingView)
   throw refused("Exact registered player/managed section/tracked roster unavailable");
  Object pm=field(ChunkMap.class,map,"playerMap");
  if(pm==null||pm.getClass()!=PlayerMap.class)throw refused("Foreign native player tracking map");
  Object pmRaw=field(PlayerMap.class,pm,"players");
  if(pmRaw==null||pmRaw.getClass()!=it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap.class)
   throw refused("Foreign native player tracking collection");
  var players=(it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap<?>)pmRaw;
  if(players.size()!=1||!players.containsKey(player)||players.getBoolean(player))
   throw refused("Native move cannot promote an unregistered or ignored player");
  var result=new ArrayList<Tracked>();
  for(var entry:entities.int2ObjectEntrySet()){
   Object tracked=entry.getValue();
   if(tracked==null||tracked.getClass()!=TRACKED)throw refused("Foreign native tracked owner");Class<?> type=TRACKED;
   if(field(type,tracked,"this$0")!=map)throw refused("Custom tracked native owner");
   Entity entity=(Entity)field(type,tracked,"entity");
   if(entity!=player&&(entity==null||entity.getClass()!=ItemEntity.class||!ownedIdentity(entity)))throw refused("Foreign native tracked source before callbacks");
   if(entity.getId()!=entry.getIntKey()||entity.level()!=level||entity.isRemoved()||entity.isPassenger()||entity.isVehicle()
      ||entity!=player&&(entity.getClass()!=ItemEntity.class||!ownedIdentity(entity)))throw refused("Unowned native tracked source");
   Object raw=field(type,tracked,"seenBy");
   requirePairingOrigin(tracked,raw);
   Set<?> seen=(Set<?>)raw;if(seen.size()>1)throw refused("Unbounded native pairing audience");
   if(entity==player){if(!seen.isEmpty())throw refused("Single player's self-tracking pairing was not empty");}
   else{
    if(!seen.isEmpty()&&!seen.contains(player.connection))throw refused("Foreign native pairing audience");
    try{if(entity.getClass().getMethod("broadcastToPlayer",ServerPlayer.class).getDeclaringClass()!=Entity.class)throw refused("Custom tracking visibility callback");}
    catch(ReflectiveOperationException failure){throw refused("Cannot inspect tracking visibility callback");}
    int distance=(Integer)invoke(ChunkMap.class,map,"getPlayerViewDistance",new Class<?>[]{ServerPlayer.class},player);
    int effective=(Integer)invoke(type,tracked,"getEffectiveRange",new Class<?>[]{});
    double range=Math.min(effective,distance*16),dx=player.getX()-entity.getX(),dz=player.getZ()-entity.getZ();
    boolean expected=dx*dx+dz*dz<=range*range&&entity.broadcastToPlayer(player)
       &&(Boolean)invoke(ChunkMap.class,map,"isChunkTracked",new Class<?>[]{ServerPlayer.class,int.class,int.class},player,entity.chunkPosition().x,entity.chunkPosition().z);
    if(expected!=seen.contains(player.connection))throw refused("Native same-XZ move would change pairing membership");
   }
   result.add(new Tracked(tracked,entity,entity.position(),entity.tickCount,seen.contains(player.connection)));
  }
  result.sort(java.util.Comparator.comparingInt(t->t.entity.getId()));return List.copyOf(result);
 }
 static void requirePairingOrigin(Object tracked,Object set){
  if(tracked==null||tracked.getClass()!=TRACKED||set==null||set.getClass()!=IDENTITY_SET
     ||!(tracked instanceof NativeTrackedPairingOrigin origin)||origin.earthplatform$originalPairingSet()!=set)
   throw refused("Absent or substituted native constructor pairing-set witness");
 }
 boolean ownsItem(Entity entity){return ownedIdentity(entity);}
 private boolean ownedIdentity(Entity entity){for(Entity candidate:owned)if(candidate==entity)return true;return false;}
 static void requireTrustedAudiences(ServerPlayer player){
  if(player==null||player.getClass()!=ServerPlayer.class||player.serverLevel().getClass()!=ServerLevel.class)throw refused("Exact registered player/level required");
  ServerLevel level=player.serverLevel();
  // First gate is the exact server-side native packet-handler fixture. Client continuous motion is separate.
  if(player.server.getClass()!=net.minecraft.gametest.framework.GameTestServer.class)
   throw refused("First registered MOVE fixture requires exact GameTestServer range callback closure");
  var list=player.server.getPlayerList();
  if(list==null||list.getClass()!=NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.gametest.framework.GameTestServer$1",net.minecraft.gametest.framework.GameTestServer.class))
   throw refused("Foreign native registered player-list implementation");
  Object audience=field(net.minecraft.server.players.PlayerList.class,list,"players");
  Object byId=field(net.minecraft.server.players.PlayerList.class,list,"playersByUUID");
  Object levelAudience=field(ServerLevel.class,level,"players");
  if(audience==null||audience.getClass()!=ArrayList.class||byId==null||byId.getClass()!=java.util.HashMap.class
     ||levelAudience==null||levelAudience.getClass()!=ArrayList.class)throw refused("Foreign native registered audience collection");
  List<?> players=(List<?>)audience,local=(List<?>)levelAudience;Map<?,?> index=(Map<?,?>)byId;
  if(players.size()!=1||players.getFirst()!=player||local.size()!=1||local.getFirst()!=player
     ||index.size()!=1||index.get(player.getUUID())!=player)throw refused("Exact single registered native audience required");
 }
 private void requirePlayerCollections(){
  requireTrustedAudiences(player);
  if(trackingView!=net.minecraft.server.level.ChunkTrackingView.EMPTY
     &&trackingView.getClass()!=net.minecraft.server.level.ChunkTrackingView.Positioned.class)throw refused("Foreign native tracking-view callback");
  var sender=player.connection.chunkSender;
  if(sender==null||sender.getClass()!=net.minecraft.server.network.PlayerChunkSender.class)throw refused("Foreign native pending-chunk sender");
  Object pending=field(net.minecraft.server.network.PlayerChunkSender.class,sender,"pendingChunks");
  if(pending==null||pending.getClass()!=it.unimi.dsi.fastutil.longs.LongOpenHashSet.class
     ||((it.unimi.dsi.fastutil.longs.LongOpenHashSet)pending).size()>4096)throw refused("Unbounded or foreign native pending-chunk set");
 }
 private static Object field(Class<?> type,Object value,String name){return NativePlayerStepSnapshot.field(type,value,name);}
 private static Object invoke(Class<?> type,Object value,String name,Class<?>[] args,Object... values){
  try{Method m=type.getDeclaredMethod(name,args);if(!m.trySetAccessible())throw refused("Tracking query inaccessible");return m.invoke(value,values);}
  catch(ReflectiveOperationException|SecurityException failure){throw refused("Pinned native tracking query failed: "+name);}
 }
 private static SurvivalRefusal refused(String message){return new SurvivalRefusal(message);}
}
