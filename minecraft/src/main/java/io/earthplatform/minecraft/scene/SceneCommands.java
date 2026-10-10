package io.earthplatform.minecraft.scene;

import io.earthplatform.minecraft.EarthPlatformMod;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Empty vanilla carrier plus the actual spectator camera. No native collision/edit/world-save claim. */
@EventBusSubscriber(modid=EarthPlatformMod.MOD_ID)
public final class SceneCommands {
    public static final String OPT_IN="earthplatform.experimental.sparseTerrainView";
    public static final ResourceKey<Level> CARRIER=ResourceKey.create(Registries.DIMENSION,ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID,"native_view_carrier"));
    public static final long START_X=9_674_928,START_Z=-3_112_479;
    private static final AtomicLong EPOCH=new AtomicLong();
    private static final Map<UUID,ReturnPoint> RETURNS=new HashMap<>();
    private record ReturnPoint(ResourceKey<Level> dimension,double x,double y,double z,float yaw,float pitch,GameType mode) {}
    private SceneCommands(){}
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("earth_scene").requires(source->source.hasPermission(2))
                .then(Commands.literal("enter").executes(context->enter(context.getSource().getPlayerOrException())))
                .then(Commands.literal("resume").executes(context->resume(context.getSource().getPlayerOrException())))
                .then(Commands.literal("leave").executes(context->leave(context.getSource().getPlayerOrException()))));
    }
    private static boolean allowed(ServerPlayer player) {
        if(Boolean.getBoolean(OPT_IN)&&player.server.isSingleplayer()&&!player.server.isDedicatedServer())return true;
        player.sendSystemMessage(Component.literal("Sparse terrain view requires the explicit integrated-client development opt-in."));return false;
    }
    private static int enter(ServerPlayer player) {
        if(!allowed(player))return 0;
        if(player.serverLevel().dimension().equals(CARRIER))return resume(player);
        var target=player.server.getLevel(CARRIER);
        if(target==null){player.sendSystemMessage(Component.literal("Create a fresh disposable test world with the sparse-view carrier resource."));return 0;}
        try {
            var source=new SceneTerrain();long ground=source.surfaceY(START_X,START_Z);
            if(!RETURNS.containsKey(player.getUUID())&&RETURNS.size()>=1)throw new IllegalStateException("One-player visual fixture only");
            var origin=new ReturnPoint(player.serverLevel().dimension(),player.getX(),player.getY(),player.getZ(),player.getYRot(),player.getXRot(),player.gameMode.getGameModeForPlayer());
            RETURNS.putIfAbsent(player.getUUID(),origin);
            player.setGameMode(GameType.SPECTATOR);resetPhysics(player);
            player.teleportTo(target,START_X+0.5,ground+36.0,START_Z+0.5,Set.of(),-35,22);
            if(player.serverLevel()!=target){player.setGameMode(origin.mode());throw new IllegalStateException("Another mod cancelled carrier travel");}
            PacketDistributor.sendToPlayer(player,new SceneStartPayload(nextEpoch(),source.identity()));
            player.sendSystemMessage(Component.literal("Sparse visual terrain view: real native camera coordinates in an EMPTY stock-height carrier. Spectator only; no cubic collision/edit/save yet. /earth_scene leave returns."));return 1;
        } catch(RuntimeException failure){EarthPlatformMod.LOGGER.error("Sparse visual scene entry failed",failure);player.sendSystemMessage(Component.literal("Sparse view unavailable: "+failure.getMessage()));return 0;}
    }
    private static int resume(ServerPlayer player) {
        if(!allowed(player))return 0;
        if(!player.serverLevel().dimension().equals(CARRIER)){player.sendSystemMessage(Component.literal("Use /earth_scene enter first."));return 0;}
        try {
            var source=new SceneTerrain();source.surfaceY((long)Math.floor(player.getX()),(long)Math.floor(player.getZ()));
            player.setGameMode(GameType.SPECTATOR);PacketDistributor.sendToPlayer(player,new SceneStartPayload(nextEpoch(),source.identity()));return 1;
        }catch(RuntimeException failure){player.sendSystemMessage(Component.literal("Cannot resume outside known terrain: "+failure.getMessage()));return 0;}
    }
    private static int leave(ServerPlayer player) {
        if(!allowed(player))return 0;
        var origin=RETURNS.get(player.getUUID());
        if(origin==null){player.sendSystemMessage(Component.literal("No return point is retained for this test session; use an operator teleport to leave the empty carrier."));return 0;}
        var target=player.server.getLevel(origin.dimension());
        if(target==null){player.sendSystemMessage(Component.literal("Original dimension is unavailable; return was not attempted."));return 0;}
        resetPhysics(player);player.teleportTo(target,origin.x(),origin.y(),origin.z(),Set.of(),origin.yaw(),origin.pitch());
        if(player.serverLevel()!=target){player.sendSystemMessage(Component.literal("Another mod cancelled return travel."));return 0;}
        player.setGameMode(origin.mode());RETURNS.remove(player.getUUID());return 1;
    }
    private static void resetPhysics(ServerPlayer player){player.setOnGround(false);player.resetFallDistance();player.setDeltaMovement(Vec3.ZERO);}
    private static long nextEpoch(){return EPOCH.updateAndGet(value->Math.incrementExact(value));}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){RETURNS.clear();}
    @EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,bus=EventBusSubscriber.Bus.MOD)
    public static final class Payloads {
        @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
            event.registrar("sparse-scene-1").playToClient(SceneStartPayload.TYPE,SceneStartPayload.CODEC,(payload,context)->SceneClientEvents.start(payload));
        }
    }
}
