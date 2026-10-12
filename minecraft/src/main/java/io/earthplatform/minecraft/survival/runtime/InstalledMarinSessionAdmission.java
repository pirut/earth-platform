package io.earthplatform.minecraft.survival.runtime;

import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.mixin.SurvivalSessionNativeAccess;
import io.earthplatform.minecraft.survival.host.SurvivalInstalledModIdentity;
import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.persistence.NativeFoodSnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot;
import io.earthplatform.minecraft.survival.persistence.NativePlayerPoseSnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeSessionExecutionGate;
import io.earthplatform.minecraft.survival.persistence.SurvivalSessionTransitions;
import io.earthplatform.minecraft.survival.source.SurvivalSceneSourceAssets;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import io.netty.channel.Channel;
import io.netty.channel.local.LocalChannel;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.server.IntegratedPlayerList;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.PrimaryLevelData;

/** Exact installed observation, independent of all GameTest gates. The token is private-minted
 * only after the audit-owned installed callback/lifecycle observer succeeds. It is never a broad
 * flag/server-class permission. This source does not register entry or change player state.
 */
public final class InstalledMarinSessionAdmission {
    public static final String FLAG = "earthplatform.installedMarinSession";
    private static final Map<ServerLevel,Token> RETAINED = new IdentityHashMap<>();
    private static final Map<Class<?>,String> VERIFIED = new IdentityHashMap<>();
    private InstalledMarinSessionAdmission() {}

    static Token prepare(ServerPlayer player, SurvivalSceneSourceAssets assets) throws IOException {
        requireLaunch(); Objects.requireNonNull(player); Objects.requireNonNull(assets);
        if (player.getClass() != ServerPlayer.class || player.server.getClass() != IntegratedServer.class || player.server.getPlayerList().getClass() != IntegratedPlayerList.class)
            throw refused("Installed session requires exact IntegratedServer and IntegratedPlayerList");
        requireBinary(ServerPlayer.class,"ec7c90a8b6a771cfd53b5038f377a72314b9efbf2301ed1ce540723b7d19c6d2");
        requireBinary(ServerGamePacketListenerImpl.class,"f8193992da15902eadb8a77681b1d9ee4ab3bed175ab7029d46061fa6486ba15");
        requireBinary(IntegratedServer.class,"26a16a6055bb148e1ffbe0d73bf74c38b710046a96f50600f3ae6b38a618bf5a");
        requireBinary(IntegratedPlayerList.class,"0aaf4d99d16b08f670b1a541207224a57ba65215b944c64818bf621332473641");
        requireBinary(Connection.class,"a01831a60461ab3b356c4706eb6e606026e272f7db1226a8681192c43ef64405");
        if (player.connection==null || player.connection.getClass()!=ServerGamePacketListenerImpl.class
                || player.connection.getConnection().getClass()!=Connection.class) throw refused("Exact original native listener/connection required");
        var artifacts = SurvivalInstalledModIdentity.captureInstalled();
        ServerLevel ordinary = player.server.overworld(), carrier = player.server.getLevel(SurvivalLevelBinding.DIMENSION);
        if (ordinary == null || carrier == null || ordinary.getClass()!=ServerLevel.class || carrier.getClass()!=ServerLevel.class || ordinary == carrier || player.serverLevel() != ordinary)
            throw refused("Installed entry requires existing ordinary player and startup-registered carrier");
        long seed = readNativeSeed(carrier);
        var token = new Token(player, ordinary, carrier, assets, artifacts, seed,
                InstalledSessionCallbackClosure.observe(player,ordinary,carrier,artifacts));
        token.requireCurrent(); token.captureOrdinary(); return token;
    }
    public static void requireLaunch() {
        if (!Boolean.getBoolean(FLAG) || Boolean.getBoolean("neoforge.gameTestServer")
                || Boolean.getBoolean("earthplatform.nativePlayerTickDiagnostic"))
            throw refused("Installed Marin session is default-off and separate from GameTest/tick diagnostics");
        for (String flag : new String[]{"earthplatform.experimental.authoritativeCoreGate", "earthplatform.experimental.rollingCubeGate",
                "earthplatform.experimental.fullHeightPlayerGate", "earthplatform.experimental.coreCollisionGate"})
            if (Boolean.getBoolean(flag)) throw refused("Competing legacy native profile: " + flag);
        if (io.earthplatform.compat.PlayableProfile.selection().state() != io.earthplatform.compat.PlayableProfile.State.DISABLED)
            throw refused("Competing legacy playable profile is enabled");
    }
    public static final class Token {
        private final Thread owner = Thread.currentThread();
        private final ServerPlayer player;
        private final IntegratedServer server;
        private final Object playerList, registries, reloadable, ordinaryGenerator, carrierGenerator;
        private final ServerGamePacketListenerImpl listener;
        private final Connection nativeConnection;
        private final Channel channel;
        private final ServerLevel ordinary, carrier;
        private final SurvivalSceneSourceAssets assets;
        private final SurvivalInstalledModIdentity.Capture artifacts;
        private final long nativeSeed;
        private final InstalledSessionCallbackClosure.Approval callbacks;
        private NativeSessionExecutionGate gate;
        private io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Reservation ordinarySaveCharge;
        void retainOrdinarySaveCharge(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Reservation charge) {
            requireCurrent();
            if(ordinarySaveCharge!=null || charge==null || charge.released() || charge.bytes()!=24L*1024*1024)
                throw refused("Exact retained installed ordinary-save reservation required");
            ordinarySaveCharge=charge;
        }
        public void requireOrdinarySave(NativeSessionExecutionGate exactGate,ServerPlayer exactPlayer) {
            requireCurrent();
            if(gate==null || exactGate!=gate || exactPlayer!=player || player.serverLevel()!=ordinary
                    || ordinarySaveCharge==null || ordinarySaveCharge.released())
                throw refused("Installed ordinary save requires original admitted held owner and retained budget");
            gate.requireHeld();callbacks.requireOrdinarySave();
        }
        private Token(ServerPlayer player, ServerLevel ordinary, ServerLevel carrier, SurvivalSceneSourceAssets assets,
                SurvivalInstalledModIdentity.Capture artifacts, long seed, InstalledSessionCallbackClosure.Approval callbacks) {
            this.player=player; server=(IntegratedServer)player.server; this.ordinary=ordinary; this.carrier=carrier;
            playerList=server.getPlayerList(); listener=player.connection; nativeConnection=listener.getConnection(); channel=nativeConnection.channel();
            registries=player.registryAccess(); reloadable=server.reloadableRegistries();
            ordinaryGenerator=ordinary.getChunkSource().getGenerator(); carrierGenerator=carrier.getChunkSource().getGenerator();
            this.assets=assets; this.artifacts=artifacts; nativeSeed=seed; this.callbacks=Objects.requireNonNull(callbacks);
        }
        public void requireCurrent() {
            requireIdentity(); callbacks.requireCurrent(); requireRegisteredPlayer();
            if (readNativeSeed(carrier) != nativeSeed) throw refused("Installed native biome seed changed");
        }
        /** Exact receiver/object identity pass. Collection-backed native census occurs only after
         * the audit-owned callback approval has established and rechecked its backing graphs. */
        public void requireIdentity() {
            requireLaunch();
            if (Thread.currentThread()!=owner || !server.isSameThread() || server.isPublished() || !server.isSingleplayer()
                    || player.server!=server || player.getClass()!=ServerPlayer.class || server.getPlayerList()!=playerList
                    || server.getSingleplayerProfile()==null || !server.getSingleplayerProfile().getId().equals(player.getUUID())
                    || player.connection!=listener || listener.getClass()!=ServerGamePacketListenerImpl.class || listener.player!=player
                    || listener.getConnection()!=nativeConnection || nativeConnection.getClass()!=Connection.class
                    || nativeConnection.getPacketListener()!=listener || nativeConnection.channel()!=channel
                    || channel==null || channel.getClass()!=LocalChannel.class || !channel.isActive() || !nativeConnection.isConnected()
                    || player.hasDisconnected() || player.isRemoved() || !player.isAlive()
                    || player.registryAccess()!=registries || server.reloadableRegistries()!=reloadable
                    || server.getLevel(ordinary.dimension())!=ordinary || server.getLevel(carrier.dimension())!=carrier
                    || ordinary.getClass()!=ServerLevel.class || carrier.getClass()!=ServerLevel.class
                    || ordinary.getChunkSource().getGenerator()!=ordinaryGenerator || carrier.getChunkSource().getGenerator()!=carrierGenerator
                    || player.serverLevel()!=ordinary && player.serverLevel()!=carrier
                    || player.hasAttachments() || ordinary.hasAttachments() || carrier.hasAttachments()
                    || assets.source().palette().context().biomes()!=server.registryAccess().registryOrThrow(Registries.BIOME)
                    || !assets.source().definition().identity().equals(MarinSurvivalDefinition.DEFINITION_IDENTITY))
                throw refused("Installed logged-in player/native connection/Level/registry/source identity changed");
        }
        private void requireRegisteredPlayer() {
            if (server.getPlayerList().getPlayerCount()!=1 || server.getPlayerList().getPlayer(player.getUUID())!=player
                    || player.serverLevel().getEntity(player.getUUID())!=player || !player.serverLevel().players().contains(player))
                throw refused("Installed native registration no longer names the original logged-in player");
        }
        public void claimGate(NativeSessionExecutionGate exactGate, ServerPlayer exactPlayer) {
            requireCurrent();
            if (gate!=null || exactGate==null || exactPlayer!=player || NativeSessionExecutionGate.find(player)!=null)
                throw refused("Installed admission token was replayed or names another player");
            synchronized (RETAINED) {
                if (RETAINED.containsKey(carrier)) throw refused("Installed carrier admission already owned");
                gate=exactGate; RETAINED.put(carrier,this);
            }
        }
        public void requireTransfer(ServerPlayer exactPlayer, ServerLevel from, ServerLevel to) {
            requireCurrent();
            if (gate==null || exactPlayer!=player || NativeSessionExecutionGate.find(player)!=gate
                    || !(from==ordinary && to==carrier || from==carrier && to==ordinary))
                throw refused("Installed transfer is outside its exact admitted owner pair");
            callbacks.requireTransfer(player,from,to);
        }
        void requireTransportPreparation() { requireCurrent(); callbacks.requireTransportPreparation(); }
        ServerPlayer player() { requireIdentity(); return player; }
        ServerLevel ordinary() { requireIdentity(); return ordinary; }
        ServerLevel carrier() { requireIdentity(); return carrier; }
        SurvivalSceneSourceAssets assets() { requireIdentity(); return assets; }
        SurvivalInstalledModIdentity.Capture artifacts() { return artifacts; }
        long nativeSeed() { requireIdentity(); return nativeSeed; }
        SurvivalSessionTransitions.OrdinaryState captureOrdinary() {
            requireCurrent();
            if (player.serverLevel()!=ordinary || !(listener instanceof SurvivalSessionNativeAccess access)
                    || access.earthplatform$awaitingPosition()!=null || player.noPhysics || player.isInvisible())
                throw refused("Ordinary entry pose or pending native teleport is unsupported");
            var inventory=NativeInventorySnapshot.capture(player,256*1024);
            var food=NativeFoodSnapshot.capture(player.getFoodData()); var pose=NativePlayerPoseSnapshot.capture(player);
            if (pose.velocityX()!=0 || pose.velocityY()!=0 || pose.velocityZ()!=0 || pose.abilities().flying()
                    || pose.gameMode()!=GameType.SURVIVAL && pose.gameMode()!=GameType.CREATIVE
                    || (long)inventory.encodedBytes()+food.encodedBytes()+pose.encodedBytes()>256*1024)
                throw refused("Entry requires supported stationary resolved ordinary escrow");
            return new SurvivalSessionTransitions.OrdinaryState(inventory,food,pose);
        }
    }
    /** Private source/gameplay compatibility witness; neither terrain nor gameplay digest is weakened. */
    public static final class SceneCompatibility {
        private final Token admission;
        private final io.earthplatform.minecraft.survival.world.NativeSurvivalWorld world;
        private final io.earthplatform.minecraft.survival.persistence.SurvivalCheckpointCoordinator checkpoints;
        private final io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.Binding binding;
        private final io.earthplatform.minecraft.survival.source.MarinVisualCubeSource source;
        private SceneCompatibility(Token admission, io.earthplatform.minecraft.survival.world.NativeSurvivalWorld world,
                io.earthplatform.minecraft.survival.persistence.SurvivalCheckpointCoordinator checkpoints) {
            this.admission=admission; this.world=world; this.checkpoints=checkpoints;
            binding=checkpoints.binding(); source=admission.assets.source();
            require(world,checkpoints.actions(),checkpoints,source);
        }
        public void require(io.earthplatform.minecraft.survival.world.NativeSurvivalWorld exactWorld,
                io.earthplatform.minecraft.survival.interaction.NativeWholeAction.Owner exactActions,
                io.earthplatform.minecraft.survival.persistence.SurvivalCheckpointCoordinator exactCheckpoints,
                io.earthplatform.minecraft.survival.source.MarinVisualCubeSource exactSource) {
            admission.requireCurrent(); world.checkOwner();
            if (exactWorld!=world || exactCheckpoints!=checkpoints || exactActions!=checkpoints.actions()
                    || exactActions.world()!=world || exactSource!=source || admission.assets.source()!=source
                    || !binding.equals(checkpoints.binding()) || !binding.world().equals(world.world())
                    || !binding.player().equals(admission.player.getUUID())
                    || !binding.dimension().equals(admission.carrier.dimension().location())
                    || !source.definition().identity().equals(MarinSurvivalDefinition.DEFINITION_IDENTITY)
                    || source.palette().context().biomes()!=admission.server.registryAccess().registryOrThrow(Registries.BIOME)
                    || !binding.registryIdentity().equals(source.palette().identity())
                    || !binding.sourceIdentity().equals(io.earthplatform.minecraft.survival.source.MarinPlayerEnvironmentV2.sourceIdentity(source.palette().identity(),admission.nativeSeed))
                    || !world.world().equals(io.earthplatform.minecraft.survival.source.MarinPlayerEnvironmentV2.worldIdentity(source.palette().identity(),admission.nativeSeed)))
                throw refused("Installed terrain/gameplay/seed/source compatibility witness changed");
        }
    }
    static SceneCompatibility sceneCompatibility(Token token,
            io.earthplatform.minecraft.survival.world.NativeSurvivalWorld world,
            io.earthplatform.minecraft.survival.persistence.SurvivalCheckpointCoordinator checkpoints) {
        token.requireCurrent();
        if (token.gate==null) throw refused("Installed scene compatibility requires the exact installed native gate");
        return new SceneCompatibility(token,world,checkpoints);
    }
    /** Available only after the exact installed token has claimed its gate; no class-only bypass. */
    public static long nativeBiomeSeed(ServerLevel exactCarrier) {
        final Token token; synchronized (RETAINED) { token=RETAINED.get(exactCarrier); }
        if (token==null) throw refused("Installed native seed policy lacks its privately admitted session owner");
        token.requireCurrent(); return token.nativeSeed;
    }
    private static long readNativeSeed(ServerLevel level) {
        if (level.getClass()!=ServerLevel.class || level.getServer().getClass()!=IntegratedServer.class || !level.getServer().isSameThread())
            throw refused("Actual integrated server owner required for biome seed");
        requireBinary(ServerLevel.class,"19b42e51848fd27df3683dacf694baa5126ee9f0665f12afdc65f82493a1e5a3");
        requireBinary(PrimaryLevelData.class,"b5eac29d33c7536e1dfd5a66bfbf59f95bf92f8a2ce9f9d86eeddcd1f0feb36e");
        requireBinary(WorldOptions.class,"ba99c2f7c0d1d64411441cbb79304c85882ea2e167d1ec2b0a706ae03267db3b");
        requireBinary(BiomeManager.class,"d618c2e8d162c5a161e2bcea641a40d26b349fd05cdf39d99c333f11acede548");
        var data=level.getServer().getWorldData();
        if (data.getClass()!=PrimaryLevelData.class || data.worldGenOptions().getClass()!=WorldOptions.class
                || level.getBiomeManager().getClass()!=BiomeManager.class) throw refused("Foreign native biome-seed owner");
        long seed=data.worldGenOptions().seed();
        if (level.getSeed()!=seed) throw refused("Carrier seed differs from actual saved world options");
        try {
            var zoom=BiomeManager.class.getDeclaredField("biomeZoomSeed");
            if (zoom.getType()!=long.class || !zoom.trySetAccessible() || zoom.getLong(level.getBiomeManager())!=BiomeManager.obfuscateSeed(seed))
                throw refused("Native biome zoom seed differs from actual saved world options");
        } catch (ReflectiveOperationException failure) { throw refused("Native biome seed inspection unavailable: "+failure); }
        return seed;
    }
    /** Installed original resource profile only; no development or observation fallback. */
    private static synchronized void requireBinary(Class<?> type, String expected) {
        if (expected.equals(VERIFIED.get(type))) return;
        try (var input=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
            if (input==null) throw refused("Missing pinned installed native resource: "+type.getName());
            byte[] bytes=input.readNBytes(2*1024*1024+1);
            if (bytes.length>2*1024*1024 || !expected.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
                throw refused("Installed original native binary differs: "+type.getName());
            VERIFIED.put(type,expected);
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) { throw refused("Cannot inspect installed native binary: "+type.getName()); }
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
