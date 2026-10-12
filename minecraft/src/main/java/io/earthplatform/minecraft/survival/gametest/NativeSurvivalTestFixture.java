package io.earthplatform.minecraft.survival.gametest;

import com.mojang.authlib.GameProfile;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.survival.host.SurvivalLevelAccess;
import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.interaction.NativeVanillaPreparation;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.interaction.host.PinnedNativeEventAdmission;
import io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost;
import io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeFoodSnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint;
import io.earthplatform.minecraft.survival.persistence.SurvivalCheckpointCodec;
import io.earthplatform.minecraft.survival.persistence.SurvivalCheckpointCoordinator;
import io.earthplatform.minecraft.survival.persistence.SurvivalCheckpointStore;
import io.earthplatform.minecraft.survival.host.SurvivalDataPackIdentity;
import io.earthplatform.minecraft.survival.source.MarinNativeCubeSource;
import io.earthplatform.minecraft.survival.source.MarinSurvivalResources;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalPalette;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.NeoForgeEventHandler;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Explicit GameTest-only fixture. Native constructors, native registries and native Survival
 * abilities are used without subclassed players, Unsafe, low-Y translation or terrain mocks.
 * The separate level is not inserted into the server level map and is never autonomously ticked.
 * Its empty native generator is transport scaffolding; all bound terrain reads use cubic state.
 * Disposable files are retained for inspection. No listener socket or acceptance file is opened.
 */
final class NativeSurvivalTestFixture implements AutoCloseable {
    // One official, isolated GameTest process owns this finite roster until process termination.
    // Native close does not empty ticket/holder maps. Never drop these graphs or claim eviction.
    private static final java.util.List<NativeSurvivalTestFixture> PROCESS_RETAINED_FIXTURES = new java.util.ArrayList<>();
    private static final int MAX_PROCESS_FIXTURES = 32;
    final MinecraftServer server;
    final Path directory;
    LevelStorageSource.LevelStorageAccess storage;
    ServerLevel level;
    ServerPlayer player;
    EmbeddedChannel channel;
    SurvivalPalette palette;
    MarinSurvivalDefinition definition;
    MarinNativeCubeSource source;
    NativeSurvivalWorld world;
    NativeWholeAction.Owner actions;
    SurvivalLevelBinding binding;
    NativeVanillaPreparation preparation;
    NativeEffectDeliveryHost delivery;
    private final java.util.List<net.minecraft.world.level.chunk.LevelChunk> carriers = new java.util.ArrayList<>();
    private CubeCell forcedTarget;
    private final java.util.List<ChunkPos> forcedColumns = new java.util.ArrayList<>();
    private NativeInventorySnapshot escrow;
    private NativeFoodSnapshot foodEscrow;
    private io.earthplatform.minecraft.survival.persistence.NativePlayerPoseSnapshot poseEscrow;
    private NativeSurvivalCheckpoint.Binding checkpointBinding;
    private SurvivalCheckpointCodec checkpointCodec;
    private SurvivalCheckpointStore checkpointStore;
    private SurvivalCheckpointCoordinator checkpoints;
    private static final NativeSurvivalCheckpoint.Limits CHECKPOINT_LIMITS =
            new NativeSurvivalCheckpoint.Limits(64, 16, 16, 8, 4*1024*1024);
    private static final SurvivalCheckpointStore.Limits STORE_LIMITS =
            new SurvivalCheckpointStore.Limits(1, 4*1024*1024, 4*1024*1024, 4096);
    private static final int NATIVE_PAYLOAD_ALLOWANCE = 256*1024;
    private boolean playerEnvironment, returningEnvironmentPlayer;
    private long sequence;
    int lastRetiredOwners;
    private boolean shutdownAttempted;
    private NativeWholeAction.PendingDelivery expectedFencedClose;

    static NativeSurvivalTestFixture create(ServerLevel testLevel) throws IOException {
        return create(testLevel, false);
    }

    static NativeSurvivalTestFixture createPlayerEnvironment(ServerLevel testLevel) throws IOException {
        return create(testLevel, false, true);
    }

    static NativeSurvivalTestFixture createReturningPlayerEnvironment(ServerLevel testLevel) throws IOException {
        return create(testLevel, false, true, true);
    }

    static NativeSurvivalTestFixture createDeliveryUnbound(ServerLevel testLevel) throws IOException {
        return create(testLevel, true);
    }

    private static NativeSurvivalTestFixture create(ServerLevel testLevel, boolean deliveryMode) throws IOException {
        return create(testLevel, deliveryMode, false);
    }

    private static NativeSurvivalTestFixture create(ServerLevel testLevel, boolean deliveryMode, boolean playerEnvironment) throws IOException {
        return create(testLevel, deliveryMode, playerEnvironment, false);
    }

    private static NativeSurvivalTestFixture create(ServerLevel testLevel, boolean deliveryMode, boolean playerEnvironment, boolean returning) throws IOException {
        if (!(testLevel.getServer() instanceof GameTestServer) || !testLevel.getServer().isSameThread())
            throw new SurvivalRefusal("Native integration fixture requires the actual GameTestServer owner thread");
        var fixture = new NativeSurvivalTestFixture(testLevel.getServer());
        fixture.playerEnvironment=playerEnvironment;fixture.returningEnvironmentPlayer=returning;
        try { fixture.initialize(testLevel, deliveryMode); return fixture; }
        catch (RuntimeException | Error | IOException failure) {
            try { fixture.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private NativeSurvivalTestFixture(MinecraftServer server) throws IOException {
        require(server.isSameThread() && PROCESS_RETAINED_FIXTURES.size()<MAX_PROCESS_FIXTURES,
                "Disposable fixture process-retention allowance exhausted");
        require(PROCESS_RETAINED_FIXTURES.isEmpty() || PROCESS_RETAINED_FIXTURES.getFirst().server==server,
                "Disposable fixture roster cannot cross native server identities");
        this.server = server;
        directory = Files.createTempDirectory("earthplatform-native-survival-gametest-");
        PROCESS_RETAINED_FIXTURES.add(this);
    }

    private void initialize(ServerLevel testLevel, boolean deliveryMode) throws IOException {
        // This storage access is private to this one level and avoids touching the test world's
        // existing dimensions. GameTestServer's testflatworld3dims need not expose custom stems.
        storage = LevelStorageSource.createDefault(directory.resolve("saves")).createAccess(playerEnvironment
                ? io.earthplatform.minecraft.survival.source.MarinPlayerEnvironmentV1.SAVE_NAMESPACE : "fixture");
        var registries = server.registryAccess();
        var dimensionType = registries.registryOrThrow(Registries.DIMENSION_TYPE).getHolderOrThrow(
                ResourceKey.create(Registries.DIMENSION_TYPE, SurvivalLevelBinding.DIMENSION.location()));
        var flat = new FlatLevelGeneratorSettings(Optional.of(HolderSet.direct()),
                registries.registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS), List.of());
        flat.updateLayers();
        level = new ServerLevel(server, net.minecraft.Util.backgroundExecutor(), storage,
                new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData()),
                SurvivalLevelBinding.DIMENSION, new LevelStem(dimensionType, new FlatLevelSource(flat)),
                new ChunkProgressListener() {
                    public void updateSpawnPos(ChunkPos position) {}
                    public void onStatusChange(ChunkPos position, ChunkStatus status) {}
                    public void start() {}
                    public void stop() {}
                }, false, playerEnvironment ? net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(MarinSurvivalDefinition.SEED)
                        : MarinSurvivalDefinition.SEED, List.of(), false, null);
        // Construct on an ordinary native level before binding; native spawn selection is not
        // smuggled through the cubic terrain guard. Transfer still keeps exact absolute coords.
        var profile = new GameProfile(UUID.randomUUID(), "native-survival-test");
        if(returningEnvironmentPlayer) seedReturningAdvancementFile(profile.getId());
        var cookie = CommonListenerCookie.createInitial(profile, false);
        player = new ServerPlayer(server, testLevel, profile, cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        channel = new EmbeddedChannel(connection);
        new ServerGamePacketListenerImpl(server, connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        // Explicit ordinary-level before-image, captured before fixture transfer.
        escrow=NativeInventorySnapshot.capture(player, NATIVE_PAYLOAD_ALLOWANCE);
        foodEscrow=NativeFoodSnapshot.capture(player.getFoodData());
        poseEscrow=io.earthplatform.minecraft.survival.persistence.NativePlayerPoseSnapshot.capture(player);
        player.setServerLevel(level);
        requireSurvival();
        require(player.getInventory().isEmpty(), "Fixture must start without starter inventory");

        var context = EnginePaletteContext.create(registries.registryOrThrow(Registries.BIOME));
        palette = new SurvivalPalette(context);
        definition = MarinSurvivalResources.installAndLoad(directory.resolve("marin-source"));
        source = new MarinNativeCubeSource(definition, palette);
        var envelope = definition.envelope();
        world = new NativeSurvivalWorld(playerEnvironment
                ? io.earthplatform.minecraft.survival.source.MarinPlayerEnvironmentV1.worldIdentity(NativeCubeCodec.paletteIdentity(context))
                : new WorldIdentity(39, "native-survival-gametest", "first039", definition.identity(), NativeCubeCodec.paletteIdentity(context)), palette,
                new NativeSurvivalWorld.Bounds(
                        new CubePos(envelope.minCenterX(), envelope.minCenterY(), envelope.minCenterZ()),
                        new CubePos(envelope.maxCenterXExclusive()-1, envelope.maxCenterYExclusive()-1,
                                envelope.maxCenterZExclusive()-1)),
                new NativeSurvivalWorld.Limits(64, 64, 8, 64, 8192, 128, 128, 262144, 8, 64));
        if (!deliveryMode) bindPreparation();
        var events = new PinnedNativeEventAdmission(NeoForge.EVENT_BUS);
        preparation = new NativeVanillaPreparation(palette, callback -> {
            requireSurvival();
            // Verify effective inherited listeners before each query rather than assume a quiet
            // test bus or turn off registered gameplay callbacks.
            switch (callback) {
                case DESTROY_PROGRESS -> {
                    events.requireEmpty(PlayerEvent.BreakSpeed.class);
                    events.requireEmpty(PlayerEvent.HarvestCheck.class);
                }
                case HARVEST_CHECK -> events.requireEmpty(PlayerEvent.HarvestCheck.class);
                case LOOT_TABLE_AND_GLOBAL_MODIFIERS -> {
                    requireNativeLootProfile();
                }
                case RECIPE_MATCH_AND_ASSEMBLE, RECIPE_REMAINDERS -> { /* detached native data only */ }
                default -> throw new SurvivalRefusal("Callback is outside this preparation gate: " + callback);
            }
        });
    }

    /** Negative fixture: native load of prior progress, never a pre-award inside the tested tick. */
    private void seedReturningAdvancementFile(UUID playerId) throws IOException {
        var holder=server.getAdvancements().get(net.minecraft.resources.ResourceLocation.withDefaultNamespace("adventure/adventuring_time"));
        require(holder!=null&&holder.value().criteria().size()==53&&holder.value().rewards().experience()==500,
                "Returning-player fixture requires exact loaded native AdventuringTime shape");
        require(holder.value().criteria().containsKey("minecraft:forest"),"Native forest criterion missing");
        var criteria=new com.google.gson.JsonObject();
        for(String name:holder.value().criteria().keySet())if(!name.equals("minecraft:forest"))
            criteria.addProperty(name,"2026-10-10 12:00:00 +0000");
        var progress=new com.google.gson.JsonObject();progress.add("criteria",criteria);progress.addProperty("done",false);
        var data=new com.google.gson.JsonObject();data.add(holder.id().toString(),progress);
        data.addProperty("DataVersion",net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        String json=new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(data);
        Files.writeString(directory.resolve("returning-player-native-advancements-seed.json"),json);
        Path path=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(playerId+".json");
        Files.createDirectories(path.getParent());
        Files.writeString(path,json,java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    private void bindPreparation() {
        // A deliberately refusing host proves that these first tests cannot accidentally claim
        // delivery, inventory mutation or a committed gameplay action.
        actions = new NativeWholeAction.Owner(world, level.registryAccess(), (identity, effects) -> {
            throw new SurvivalRefusal("Preparation-only GameTest has no native effect delivery capability");
        });
        binding = new SurvivalLevelBinding(level, world, actions, (action, cell, state, flags, depth) -> {
            throw new SurvivalRefusal("Preparation-only GameTest has no native mutation callback capability");
        });
        ((SurvivalLevelAccess) level).earthplatform$bindSurvival(binding);
    }

    CubeCell nearestNaturalLog() {
        long x=MarinSurvivalDefinition.ANCHOR_X, z=MarinSurvivalDefinition.ANCHOR_Z;
        var tree = definition.treesIntersecting(x-32,z-32,x+32,z+32).stream()
                .min(java.util.Comparator.comparingLong(value -> Math.abs(value.x()-x)+Math.abs(value.z()-z))).orElseThrow();
        return CubeCell.of(tree.x(), tree.groundY()+1, tree.z());
    }

    void beginNativeCarrierLoad(CubeCell target) {
        require(binding==null && forcedTarget==null, "Carrier loading must precede binding exactly once");
        forcedTarget=target; admitNeighborhood(target); standNear(target);
        forceNativeColumn(target.cube().x(),target.cube().z());
    }

    /** Complete finite dry-item sweep coverage, fixed before any action/RNG/native item exists. */
    void beginNativeCarrierLoadForDryItemTicks(CubeCell target) {
        beginNativeCarrierLoad(target);
        for (int dx=-1;dx<=1;dx++) for (int dz=-1;dz<=1;dz++) if (dx!=0 || dz!=0)
            forceNativeColumn(Math.addExact(target.cube().x(),dx),Math.addExact(target.cube().z(),dz));
        require(forcedColumns.size()==9, "Native dry-item tick fixture requires exactly nine forced carrier columns");
    }
    private void forceNativeColumn(int x,int z) {
        require(binding==null && forcedColumns.size()<9, "Native forced carrier plan exceeds its prebind bound");
        var column=new ChunkPos(x,z);
        require(!forcedColumns.contains(column) && level.setChunkForced(x,z,true), "Native forced carrier ticket was not created");
        forcedColumns.add(column);
    }
    private int readyNativeCarriers() {
        int ready=0;
        for (var column : forcedColumns) {
            var pos=new net.minecraft.core.BlockPos(column.getMinBlockX(),Math.toIntExact(forcedTarget.y()),column.getMinBlockZ());
            if (level.isPositionEntityTicking(pos) && level.areEntitiesLoaded(column.toLong())) ready++;
        }
        return ready;
    }

    void awaitNativeCarrierLoad() {
        require(binding==null && forcedTarget!=null, "Native load waiting is only allowed before binding");
        final long start=System.nanoTime(), deadline=start+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        final long[] nextTick={start}, nextReport={start+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)};
        final boolean[] expired={false};
        var pos=NativeVanillaPreparation.nativePos(forcedTarget);
        // GameTestServer ticks run faster than wall time. Native managedBlock services the
        // actual server queue while this condition also drives this separate level's native
        // chunk queue. No rescheduled Runnable key, synthetic loaded state or enlarged tick cap.
        server.managedBlock(() -> {
            long now=System.nanoTime();
            if (now>=deadline) { expired[0]=true; return true; }
            for (int i=0; i<256 && level.getChunkSource().pollTask(); i++) { }
            if (now>=nextTick[0]) {
                level.tick(() -> true);
                nextTick[0]=now+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50);
            }
            require(level.getChunkSource().getLoadedChunksCount()<=1089,
                    "Native forced-ticket holder footprint exceeded admission");
            int readyCount=readyNativeCarriers();
            boolean ready=readyCount==forcedColumns.size();
            if (!ready && now>=nextReport[0]) {
                var holder=level.getChunkSource().chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(pos));
                io.earthplatform.minecraft.EarthPlatformMod.LOGGER.info(
                        "Native survival fixture carrier load: elapsedMs={}, holders={}, tasks={}, entityFutureDone={}, entitiesLoaded={}, ready={}/{}",
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(now-start),level.getChunkSource().getLoadedChunksCount(),
                        level.getChunkSource().getPendingTasksCount(),holder!=null && holder.getEntityTickingChunkFuture().isDone(),
                        level.areEntitiesLoaded(ChunkPos.asLong(pos)),readyCount,forcedColumns.size());
                nextReport[0]=now+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            }
            return ready;
        });
        require(!expired[0] && readyNativeCarriers()==forcedColumns.size(),
                "Real native entity-ticking carrier load exceeded its 30-second wall deadline");
    }

    void bindNativeDelivery() throws IOException { bindNativeDelivery(32); }

    // Only the separately labeled resolved-crafting fixture uses the approved256 metadata bound.
    void bindNativeDeliveryForEarnedMenu() throws IOException { bindNativeDelivery(256); }

    void bindNativeDeliveryForEarnedTable() throws IOException { bindNativeDelivery(256,true); }

    private void bindNativeDelivery(int metadataOperations) throws IOException { bindNativeDelivery(metadataOperations,false); }
    private void bindNativeDelivery(int metadataOperations, boolean nativeTablePlacement) throws IOException {
        require(metadataOperations == 32 || metadataOperations == 256, "Unapproved fixture metadata allowance");
        require(binding==null && forcedTarget!=null, "Delivery binding is single-use after carrier load");
        require((forcedColumns.size()==1 || forcedColumns.size()==9) && readyNativeCarriers()==forcedColumns.size(),
                "Every declared native carrier must really be entity-ticking before binding");
        int nativeHolders=0;
        for (int x=forcedTarget.cube().x()-16; x<=forcedTarget.cube().x()+16; x++)
            for (int z=forcedTarget.cube().z()-16; z<=forcedTarget.cube().z()+16; z++) {
                if (level.getChunkSource().chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x,z))!=null) nativeHolders++;
                var carrier=level.getChunkSource().getChunkNow(x,z);
                if (carrier!=null) carriers.add(carrier);
            }
        require(nativeHolders==level.getChunkSource().getLoadedChunksCount(), "Native carrier footprint escaped its exact bound");
        require(!carriers.isEmpty() && carriers.size()<=64, "Unexpected native full-carrier count");
        if (forcedColumns.size()==9) System.out.printf("EARTH_NATIVE_DRY_TICK_CARRIERS forced=%d ready=%d holders=%d full=%d maxHolders=1089 maxFull=64%n",
                forcedColumns.size(),readyNativeCarriers(),nativeHolders,carriers.size());
        delivery=new NativeEffectDeliveryHost(level,player,world,level.registryAccess(),UUID.randomUUID(),
                new NativeEffectDeliveryHost.Limits(16,16,metadataOperations,1), CHECKPOINT_LIMITS, escrow, foodEscrow, poseEscrow, NATIVE_PAYLOAD_ALLOWANCE);
        actions=delivery.actions();
        SurvivalLevelBinding.MutationRouter router;
        if (nativeTablePlacement) router=new io.earthplatform.minecraft.survival.host.NativeDryMutationRouter(
                level,actions,new PinnedNativeEventAdmission(NeoForge.EVENT_BUS),()->0L,4096);
        else router=(action,cell,state,flags,depth)->{
            throw new SurvivalRefusal("Component delivery gate has no packet/native mutation-callback dispatcher");
        };
        binding=new SurvivalLevelBinding(level,world,actions,router);
        ((SurvivalLevelAccess) level).earthplatform$bindSurvival(binding);
        for (var carrier:carriers) binding.admitCarrier(carrier);
        // Exact fixed native holder lineage is required by the production serializer guard.
        // This adds metadata ownership only, without more cubic/full-carrier owners or save bypass.
        binding.admitCarrierSerialization(forcedTarget.cube().x(),forcedTarget.cube().z(),
                new io.earthplatform.minecraft.survival.host.SurvivalCarrierSerializationRoster.PhysicalAdmission(
                        nativeHolders,carriers.size(),
                        io.earthplatform.minecraft.survival.host.SurvivalCarrierSerializationRoster.chargedMetadataBytes(nativeHolders)));
        delivery.attachBinding(binding);
        // Reserve 3.75 MiB for this fixture's bounded 64-cube palettes, actor metadata,
        // binding/header and exact escrow. The complete native boundary has its own 256 KiB cap.
        var data=SurvivalDataPackIdentity.capture(server.getResourceManager(),palette.identity(),
                "official-neoforge-gametest-1.21.1-21.1.256",new SurvivalDataPackIdentity.Limits(16,20000,30000,4194304,134217728));
        for (String domain : java.util.List.of("recipe","loot_table","advancement","tags"))
            require(data.domainPaths().getOrDefault(domain,0)>0, "Actual native resource census is empty for "+domain);
        require(data.paths()>4000 && data.layers()>=data.paths() && data.bytes()>1000000,
                "Actual loaded native resource census is implausibly incomplete");
        com.mojang.logging.LogUtils.getLogger().info("SURVIVAL_NATIVE_RESOURCE_CENSUS paths={} layers={} bytes={} domains={} digest={}",
                data.paths(),data.layers(),data.bytes(),data.domainPaths(),data.digest());
        checkpointBinding=new NativeSurvivalCheckpoint.Binding(UUID.randomUUID(),playerEnvironment
                ? io.earthplatform.minecraft.survival.source.MarinPlayerEnvironmentV1.PROFILE : "native-delivery-gametest",
                world.world(),playerEnvironment ? io.earthplatform.minecraft.survival.source.MarinPlayerEnvironmentV1.IDENTITY : definition.identity(),palette.identity(),data.digest(),player.getUUID(),level.dimension().location());
        checkpointCodec=new SurvivalCheckpointCodec(checkpointBinding,CHECKPOINT_LIMITS,level.registryAccess(),palette,world.bounds());
        checkpointStore=new SurvivalCheckpointStore(directory.resolve("checkpoint"),checkpointCodec.storageIdentity(),STORE_LIMITS);
        checkpoints=new SurvivalCheckpointCoordinator(actions,checkpointStore,STORE_LIMITS,checkpointCodec,new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
        delivery.attachCheckpointCoordinator(checkpoints);
    }

    record NativeSaveFiles(Path playerData, Path advancements, Path statistics) {}

    /** File-content evidence for this fresh fixture UUID, not a production save receipt or restart. */
    NativeSaveFiles saveAndVerifyNativePlayerFiles() throws IOException {
        require(server.getPlayerList().getPlayer(player.getUUID()) == null,
                "This isolated fixture must explicitly disclose absence from the main player list");
        requireSurvival(); NativeInventorySnapshot.requireResolvedMenu(player);
        var events = new PinnedNativeEventAdmission(NeoForge.EVENT_BUS);
        events.requireEmpty(PlayerEvent.SaveToFile.class);
        var expected = player.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        var nativeStore = new net.minecraft.world.level.storage.PlayerDataStorage(storage,
                net.minecraft.util.datafix.DataFixers.getDataFixer());
        Path playerFile = storage.getLevelPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR)
                .resolve(player.getStringUUID() + ".dat");
        require(!Files.exists(playerFile), "Native file proof requires a fresh unique UUID destination");
        nativeStore.save(player); // Void return is deliberately not treated as success.
        require(Files.isRegularFile(playerFile) && Files.size(playerFile) > 0 && Files.size(playerFile) <= 4*1024*1024,
                "Native PlayerDataStorage did not produce its bounded fresh UUID file");
        var actual = net.minecraft.nbt.NbtIo.readCompressed(playerFile, net.minecraft.nbt.NbtAccounter.create(4*1024*1024));
        require(actual.equals(expected), "Actual native player UUID.dat differs from exact emitted inventory/food/pose/book NBT");
        Path advancementFile = testOnlyReadField(net.minecraft.server.PlayerAdvancements.class, player.getAdvancements(),
                "playerSavePath", Path.class);
        java.io.File statsFile = testOnlyReadField(net.minecraft.stats.ServerStatsCounter.class, player.getStats(),
                "file", java.io.File.class);
        require(advancementFile.getFileName().toString().equals(player.getStringUUID() + ".json")
                && statsFile.getName().equals(player.getStringUUID() + ".json"), "Native metadata file identity changed");
        require(!Files.exists(advancementFile) && !Files.exists(statsFile.toPath()), "Native metadata proof requires fresh UUID files");
        var expectedStatistics = nativeStatisticImage();
        player.getAdvancements().save(); player.getStats().save();
        var advancements = boundedNativeJson(advancementFile);
        var statistics = boundedNativeJson(statsFile.toPath());
        var actualCriteria = new java.util.TreeMap<String, java.util.Set<String>>();
        for (var entry : advancements.entrySet()) if (!entry.getKey().equals("DataVersion")) {
            var saved = entry.getValue().getAsJsonObject();
            if (saved.has("criteria") && !saved.getAsJsonObject("criteria").keySet().isEmpty())
                actualCriteria.put(entry.getKey(), java.util.Set.copyOf(saved.getAsJsonObject("criteria").keySet()));
        }
        require(actualCriteria.equals(nativeAdvancementState()), "Native saved advancement criterion graph differs");
        for (var entry : actualCriteria.entrySet()) {
            var holder = server.getAdvancements().get(net.minecraft.resources.ResourceLocation.parse(entry.getKey()));
            require(holder != null && advancements.getAsJsonObject(entry.getKey()).get("done").getAsBoolean()
                    == player.getAdvancements().getOrStartProgress(holder).isDone(), "Native saved advancement completion differs");
        }
        require(statistics.keySet().equals(java.util.Set.of("stats","DataVersion"))
                && statistics.getAsJsonObject("stats").equals(expectedStatistics)
                && statistics.get("DataVersion").getAsInt()==net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion(),
                "Complete native saved statistic graph differs from the captured exact counter");
        require(nativeStatisticImage().equals(expectedStatistics), "Native save changed live statistics");
        require(player.saveWithoutId(new net.minecraft.nbt.CompoundTag()).equals(expected), "Native save unexpectedly changed live player data");
        System.out.printf("EARTH_NATIVE_EARNED_SAVE playerListed=false metadataOperations=256 player=%s advancement=%s stats=%s%n",
                playerFile, advancementFile, statsFile);
        return new NativeSaveFiles(playerFile, advancementFile, statsFile.toPath());
    }
    private com.google.gson.JsonObject nativeStatisticImage() {
        var counter = testOnlyReadField(net.minecraft.stats.StatsCounter.class, player.getStats(),
                "stats", it.unimi.dsi.fastutil.objects.Object2IntMap.class);
        require(counter.getClass()==it.unimi.dsi.fastutil.objects.Object2IntMaps.SynchronizedMap.class,
                "Foreign native statistic wrapper before traversal");
        var backing=testOnlyReadField(it.unimi.dsi.fastutil.objects.Object2IntMaps.SynchronizedMap.class,counter,
                "map",it.unimi.dsi.fastutil.objects.Object2IntMap.class);
        require(backing.getClass()==it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap.class
                && testOnlyReadField(it.unimi.dsi.fastutil.objects.Object2IntFunctions.SynchronizedFunction.class,counter,
                "function",it.unimi.dsi.fastutil.objects.Object2IntFunction.class)==backing
                && testOnlyReadField(it.unimi.dsi.fastutil.objects.Object2IntFunctions.SynchronizedFunction.class,counter,"sync",Object.class)==counter,
                "Foreign native statistic backing/function/monitor before traversal");
        require(backing.size()<=256,"Native fixture statistic graph exceeded its bound");
        var result=new com.google.gson.JsonObject();int count=0;
        for(Object object:backing.object2IntEntrySet()) {
            require(++count<=256,"Native statistic graph grew during capture");
            var entry=(it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<?>)object;
            require(entry.getKey()!=null && entry.getKey().getClass()==net.minecraft.stats.Stat.class,"Native statistic map has foreign key");
            var stat=(net.minecraft.stats.Stat<?>)entry.getKey();
            var typeKey=net.minecraft.core.registries.BuiltInRegistries.STAT_TYPE.getKey(stat.getType());
            require(stat.getType().getClass()==net.minecraft.stats.StatType.class && typeKey!=null
                    && net.minecraft.core.registries.BuiltInRegistries.STAT_TYPE.get(typeKey)==stat.getType(),"Foreign native statistic type/registry");
            String category=typeKey.toString();
            var values=result.getAsJsonObject(category);if(values==null){values=new com.google.gson.JsonObject();result.add(category,values);}
            String key=nativeStatisticKey(stat);require(!values.has(key),"Duplicate native serialized statistic key");
            values.addProperty(key,entry.getIntValue());
        }
        require(count==counter.size(),"Native statistic membership changed during capture");return result;
    }
    private static <T> String nativeStatisticKey(net.minecraft.stats.Stat<T> stat) {
        var registry=stat.getType().getRegistry();var key=registry.getKey(stat.getValue());
        require(key!=null && registry.get(key)==stat.getValue(),"Foreign native statistic value registry");return key.toString();
    }
    Map<String, java.util.Set<String>> nativeAdvancementState() {
        Map<?, ?> progress = testOnlyReadField(net.minecraft.server.PlayerAdvancements.class,
                player.getAdvancements(), "progress", Map.class);
        require(progress.size() <= 4096, "Native fixture advancement progress exceeded its bound");
        var result = new java.util.TreeMap<String, java.util.Set<String>>();
        for (var entry : progress.entrySet()) {
            require(entry.getKey() instanceof net.minecraft.advancements.AdvancementHolder
                    && entry.getValue() instanceof net.minecraft.advancements.AdvancementProgress, "Unexpected native advancement map entry");
            var holder = (net.minecraft.advancements.AdvancementHolder) entry.getKey();
            var value = (net.minecraft.advancements.AdvancementProgress) entry.getValue();
            var completed = new java.util.TreeSet<String>(); value.getCompletedCriteria().forEach(completed::add);
            if (!completed.isEmpty()) result.put(holder.id().toString(), java.util.Set.copyOf(completed));
        }
        return Map.copyOf(result);
    }
    private static com.google.gson.JsonObject boundedNativeJson(Path path) throws IOException {
        require(Files.isRegularFile(path) && Files.size(path) > 0 && Files.size(path) <= 4*1024*1024,
                "Native metadata save did not produce its bounded fresh UUID file");
        return com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
    private static <T> T testOnlyReadField(Class<?> owner, Object target, String name, Class<T> type) {
        try {
            var field = owner.getDeclaredField(name);
            require(field.getType() == type && field.trySetAccessible(), "Pinned native save file field is unavailable");
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException failure) { throw new SurvivalRefusal("Pinned native save path read failed: " + failure); }
    }

    NativeSurvivalCheckpoint checkpoint(NativeSurvivalCheckpoint.SessionPhase phase) throws IOException {
        delivery.captureCheckpoint(phase);
        checkpoints.submit(Runnable::run);
        require(checkpoints.poll(), "Native fixture checkpoint did not obtain a verified durable receipt");
        lastRetiredOwners = delivery.retireDurableConsumedOwners();
        require(!world.hasUncheckpointedActions() && world.dirtyPositions().isEmpty(), "Checkpoint left unacknowledged cubic changes");
        return readDurableCheckpoint();
    }

    // Actual coordinator access only for bounded fixture failure/acknowledgement scheduling.
    SurvivalCheckpointCoordinator checkpointCoordinator() { return checkpoints; }
    NativeSurvivalCheckpoint readDurableCheckpoint() throws IOException {
        var fromDisk=checkpointCodec.decode(checkpointStore.load().orElseThrow());
        require(java.util.Arrays.equals(checkpointCodec.encode(fromDisk),checkpointCodec.encode(checkpoints.durable().orElseThrow())),
                "Reopened checkpoint payload differs from the exact acknowledged image");
        return fromDisk;
    }

    /** Exact pinned read-only inspection; never replaces or clears any native modifiers. */
    private static void requireNativeLootProfile() {
        try {
            var method = NeoForgeEventHandler.class.getDeclaredMethod("getLootModifierManager");
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != net.neoforged.neoforge.common.loot.LootModifierManager.class
                    || !method.trySetAccessible())
                throw new SurvivalRefusal("Pinned native loot modifier inspection is unavailable");
            var manager = (net.neoforged.neoforge.common.loot.LootModifierManager) method.invoke(null);
            require(manager != null && manager.getAllLootMods().isEmpty(),
                    "Integration reference needs the unmodified native loot profile");
        } catch (ReflectiveOperationException | SecurityException failure) {
            var refusal = new SurvivalRefusal("Pinned native loot modifier inspection is unavailable");
            refusal.initCause(failure); throw refusal;
        }
    }

    void requireSurvival() {
        require(player.getClass() == ServerPlayer.class, "Expected exact native ServerPlayer class");
        require(player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "Player is not Survival");
        require(!player.isCreative() && !player.isSpectator() && !player.hasInfiniteMaterials()
                && !player.getAbilities().instabuild && !player.getAbilities().mayfly,
                "Native Survival abilities were overridden");
    }

    void admit(CubePos cube) {
        if (world.presence(cube) == NativeSurvivalWorld.Presence.UNKNOWN)
            world.admitGenerated(cube, source.generate(cube, new CancellationToken()));
    }

    void admitNeighborhood(CubeCell cell) {
        for (int x=-1; x<=1; x++) for (int y=-1; y<=1; y++) for (int z=-1; z<=1; z++)
            admit(cell.cube().offset(x, y, z));
    }

    NativeWholeAction beginNextDeclared() {
        Map<CubePos,NativeSurvivalWorld.CubeStamp> expected=new HashMap<>();
        for (var cube:world.ownedPositions()) expected.put(cube,world.stamp(cube));
        return delivery.begin(world.actionIdentity(player.getUUID(),world.nextActionSequence(player.getUUID())),expected);
    }

    /** Expected postcommit fault only: preserve the exact pending owner through physical closure. */
    void expectFencedPhysicalClose() {
        require(!shutdownAttempted && expectedFencedClose==null && actions!=null && actions.fenced()
                && !actions.hasCurrentAction(), "Expected-fenced disposal requires the closed exact failed action");
        expectedFencedClose=actions.pendingDelivery();
        require(expectedFencedClose!=null && expectedFencedClose.hasRetainedPreparedBatch()
                && expectedFencedClose.failure()!=null, "Expected-fenced disposal lacks a retained failure receipt/plan");
    }

    NativeWholeAction begin() {
        Map<CubePos, NativeSurvivalWorld.CubeStamp> expected = new HashMap<>();
        for (var cube : world.ownedPositions()) expected.put(cube, world.stamp(cube));
        var identity=world.actionIdentity(player.getUUID(), ++sequence);
        return delivery==null ? actions.begin(identity, expected, NativeWholeAction.inventoryOf(player)) : delivery.begin(identity,expected);
    }

    void standNear(CubeCell cell) {
        player.moveTo(cell.x()+0.5, cell.y(), cell.z()+2.5, 180, 0);
        player.setOnGround(true);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @Override public void close() throws IOException {
        if (shutdownAttempted) throw new SurvivalRefusal("Native fixture physical shutdown cannot be repeated");
        shutdownAttempted=true;
        Throwable failure=null;
        NativeCloseAudit closeAudit=null;
        try {
            if (level!=null && storage!=null) {
                closeAudit=new NativeCloseAudit(this);
                // Genuine reference block mutations enqueue native light updates. Finish them
                // while that exact unbound level is still open; never pump a detached owner.
                if (binding==null) closeAudit.awaitUnboundReferenceLight();
            }
        }
        catch (Throwable error) { failure=error; }
        try {
            if (binding!=null && !binding.isDetached()) {
                binding.beginClose();
                ((SurvivalLevelAccess) level).earthplatform$unbindSurvival(binding);
            }
        } catch (Throwable error) {
            if (expectedFencedClose!=null && error instanceof SurvivalRefusal
                    && "Earth survival: Survival action, dirty, checkpoint, pin or effect owner has not drained".equals(error.getMessage())
                    && actions.fenced() && actions.pendingDelivery()==expectedFencedClose && binding.isClosing() && !binding.isDetached())
                System.out.println("EARTH_NATIVE_EXPECTED_FENCED_DETACH receipt="+expectedFencedClose.receipt().commitSequence()+" retained=true "+error.getMessage());
            else failure=retain(failure,error);
        }
        try { if (player!=null) player.getTextFilter().leave(); }
        catch (Throwable error) { failure=retain(failure,error); }
        try { if (channel!=null) channel.finishAndReleaseAll(); }
        catch (Throwable error) { failure=retain(failure,error); }
        // Retain native forced tickets through genuine physical closure. Removing them here
        // initiates unadmitted ProtoChunk unload serialization; that production lifecycle gap
        // remains explicit. This bounded one-shot process retains all owners until termination.
        // Owners and all item references remain retained; do not discard or clear the roster.
        try { if (level!=null) level.close(); }
        catch (Throwable error) { failure=retain(failure,error); }
        try { if (checkpointStore!=null) checkpointStore.close(); }
        catch (Throwable error) { failure=retain(failure,error); }
        try { if (storage!=null) storage.close(); }
        catch (Throwable error) { failure=retain(failure,error); }
        try { if (closeAudit!=null) closeAudit.requirePhysicalClose(); }
        catch (Throwable error) { failure=retain(failure,error); }
        if (failure instanceof IOException error) throw error;
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
        if (failure!=null) throw new IOException("Native fixture shutdown failed",failure);
    }
    /** Read-only pinned-source audit; no close calls, queue pumping, ticket removal or map clearing. */
    private static final class NativeCloseAudit {
        private final NativeSurvivalTestFixture fixture;
        private final java.util.List<net.minecraft.world.level.chunk.storage.IOWorker> workers;
        private final java.util.List<net.minecraft.util.thread.ProcessorMailbox<?>> closedMailboxes=new java.util.ArrayList<>();
        private final java.util.List<net.minecraft.util.thread.ProcessorMailbox<?>> retainedIdleMailboxes=new java.util.ArrayList<>();
        private final net.minecraft.server.level.ChunkTaskPriorityQueueSorter sorter;
        private final net.minecraft.server.level.ChunkTaskPriorityQueueSorter ticketThrottler;
        private final net.minecraft.util.DirectoryLock lock;
        private final java.nio.channels.FileChannel lockChannel;
        NativeCloseAudit(NativeSurvivalTestFixture fixture) {
            this.fixture=fixture;
            require(fixture.server.isSameThread(), "Physical fixture close must run on its native owner thread");
            for (var serverLevel:fixture.server.getAllLevels()) require(serverLevel!=fixture.level,
                    "Disposable fixture unexpectedly belongs to the server autonomous tick roster");
            var map=fixture.level.getChunkSource().chunkMap;
            var chunkWorker=testOnlyReadField(net.minecraft.world.level.chunk.storage.ChunkStorage.class,map,
                    "worker",net.minecraft.world.level.chunk.storage.IOWorker.class);
            var poiStorage=testOnlyReadField(net.minecraft.world.level.chunk.storage.SectionStorage.class,
                    fixture.level.getChunkSource().getPoiManager(),"simpleRegionStorage",net.minecraft.world.level.chunk.storage.SimpleRegionStorage.class);
            var entities=testOnlyReadField(ServerLevel.class,fixture.level,"entityManager",net.minecraft.world.level.entity.PersistentEntitySectionManager.class);
            var permanent=testOnlyReadField(net.minecraft.world.level.entity.PersistentEntitySectionManager.class,entities,
                    "permanentStorage",net.minecraft.world.level.entity.EntityPersistentStorage.class);
            require(permanent.getClass()==net.minecraft.world.level.chunk.storage.EntityStorage.class,
                    "Unexpected native entity-storage implementation at physical close");
            var entityStorage=testOnlyReadField(net.minecraft.world.level.chunk.storage.EntityStorage.class,permanent,
                    "simpleRegionStorage",net.minecraft.world.level.chunk.storage.SimpleRegionStorage.class);
            workers=java.util.List.of(chunkWorker,worker(poiStorage),worker(entityStorage));
            require(workers.stream().distinct().count()==3,"Native close workers unexpectedly alias");
            for (var worker:workers) {
                require(worker.getClass()==net.minecraft.world.level.chunk.storage.IOWorker.class,"Unexpected native close worker implementation");
                closedMailboxes.add(testOnlyReadField(net.minecraft.world.level.chunk.storage.IOWorker.class,worker,
                        "mailbox",net.minecraft.util.thread.ProcessorMailbox.class));
            }
            sorter=testOnlyReadField(net.minecraft.server.level.ChunkMap.class,map,"queueSorter",net.minecraft.server.level.ChunkTaskPriorityQueueSorter.class);
            var queues=testOnlyReadField(net.minecraft.server.level.ChunkTaskPriorityQueueSorter.class,sorter,"queues",java.util.Map.class);
            require(queues.size()==3,"Native generation close processor roster changed");
            for (var processor:queues.keySet()) if (processor instanceof net.minecraft.util.thread.ProcessorMailbox<?> mailbox) closedMailboxes.add(mailbox);
            require(closedMailboxes.size()==5,"Native close must own three I/O and two generation mailboxes");
            retainedIdleMailboxes.add(testOnlyReadField(net.minecraft.server.level.ChunkTaskPriorityQueueSorter.class,sorter,
                    "mailbox",net.minecraft.util.thread.ProcessorMailbox.class));
            retainedIdleMailboxes.add(testOnlyReadField(net.minecraft.world.level.chunk.storage.EntityStorage.class,permanent,
                    "entityDeserializerQueue",net.minecraft.util.thread.ProcessorMailbox.class));
            ticketThrottler=testOnlyReadField(net.minecraft.server.level.DistanceManager.class,map.getDistanceManager(),
                    "ticketThrottler",net.minecraft.server.level.ChunkTaskPriorityQueueSorter.class);
            retainedIdleMailboxes.add(testOnlyReadField(net.minecraft.server.level.ChunkTaskPriorityQueueSorter.class,ticketThrottler,
                    "mailbox",net.minecraft.util.thread.ProcessorMailbox.class));
            lock=testOnlyReadField(LevelStorageSource.LevelStorageAccess.class,fixture.storage,"lock",net.minecraft.util.DirectoryLock.class);
            lockChannel=testOnlyReadField(net.minecraft.util.DirectoryLock.class,lock,"lockFile",java.nio.channels.FileChannel.class);
            require(lock.isValid() && lockChannel.isOpen(),"Native storage lock was not live before physical close");
            require(fixture.forcedColumns.size()<=9 && fixture.carriers.size()<=64
                    && fixture.level.getChunkSource().getLoadedChunksCount()<=1089,"Physical close owner census exceeded fixture admission");
        }
        private static net.minecraft.world.level.chunk.storage.IOWorker worker(net.minecraft.world.level.chunk.storage.SimpleRegionStorage storage) {
            return testOnlyReadField(net.minecraft.world.level.chunk.storage.SimpleRegionStorage.class,storage,
                    "worker",net.minecraft.world.level.chunk.storage.IOWorker.class);
        }
        private static boolean idle(net.minecraft.util.thread.ProcessorMailbox<?> mailbox,boolean closed) {
            require(mailbox.getClass()==net.minecraft.util.thread.ProcessorMailbox.class,"Unexpected native mailbox implementation");
            int status=testOnlyReadField(net.minecraft.util.thread.ProcessorMailbox.class,mailbox,
                    "status",java.util.concurrent.atomic.AtomicInteger.class).get();
            return (status & 2)==0 && (!closed || (status & 1)!=0) && mailbox.size()==0;
        }
        void awaitUnboundReferenceLight() {
            require(fixture.server.isSameThread() && fixture.binding==null
                    && ((SurvivalLevelAccess) fixture.level).earthplatform$survivalBinding()==null,
                    "Native reference light drain requires the exact still-unbound owner");
            var light=fixture.level.getChunkSource().getLightEngine();
            require(light.getClass()==net.minecraft.server.level.ThreadedLevelLightEngine.class,
                    "Unexpected native reference light implementation");
            var mailbox=testOnlyReadField(net.minecraft.server.level.ThreadedLevelLightEngine.class,light,
                    "taskMailbox",net.minecraft.util.thread.ProcessorMailbox.class);
            require(closedMailboxes.contains(mailbox),"Native reference light mailbox escaped its owned close roster");
            var barriers=new java.util.ArrayList<java.util.concurrent.CompletableFuture<?>>();
            require(fixture.forcedColumns.size()<=9,"Native reference light barrier count exceeded its existing column bound");
            for (var column:fixture.forcedColumns) barriers.add(light.waitForPendingTasks(column.x,column.z));
            long start=System.nanoTime(),deadline=start+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            String before=lightState()+" "+quiescenceState(barriers);
            int nativePolls=0;
            while (true) {
                require(fixture.binding==null
                        && ((SurvivalLevelAccess) fixture.level).earthplatform$survivalBinding()==null,
                        "Native reference lighting lost its unbound owner");
                require((testOnlyReadField(net.minecraft.util.thread.ProcessorMailbox.class,mailbox,
                        "status",java.util.concurrent.atomic.AtomicInteger.class).get() & 1)==0,
                        "Native reference light mailbox closed before its barrier");
                require(fixture.level.getChunkSource().getLoadedChunksCount()<=1089
                        && fixture.forcedColumns.size()<=9 && fixture.carriers.size()<=64,
                        "Native reference light drain exceeded existing fixture owner bounds");
                // Exact open unbound reference only: native distance/generation completion,
                // light scheduling and one main-thread task. No Level/server/player ticks.
                require(nativePolls<4096,"Native reference quiescence exceeded 4096 native polls: "+owner());
                requireActualHolderBounds();
                fixture.level.getChunkSource().pollTask();nativePolls++;
                requireActualHolderBounds();
                boolean completed=true;
                for (var barrier:barriers) {
                    if (barrier.isDone()) barrier.join(); // Preserve actual exceptional completion.
                    else completed=false;
                }
                boolean quiet=completed;
                for (var processor:closedMailboxes) quiet &= idle(processor,false);
                for (var processor:retainedIdleMailboxes) quiet &= idle(processor,false);
                quiet &= !sorter.hasWork() && !ticketThrottler.hasWork()
                        && fixture.level.getChunkSource().getPendingTasksCount()==0;
                // A per-column POST barrier alone does not cover another priority bucket.
                if (quiet && lightIdle()) { requireActualHolderBounds(); break; }
                require(System.nanoTime()<deadline,"Native reference light barrier exceeded five seconds: "+owner()+" "+lightState()+" "+quiescenceState(barriers));
                java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1));
            }
            System.out.printf("EARTH_NATIVE_REFERENCE_QUIESCENCE %s barriers=%d nativePolls=%d maxPolls=4096 elapsedMs=%d before=%s after=%s%n",
                    owner(),barriers.size(),nativePolls,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),before,lightState()+" "+quiescenceState(barriers));
        }
        private void requireActualHolderBounds() {
            var map=fixture.level.getChunkSource().chunkMap;
            var holders=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<net.minecraft.server.level.ChunkHolder,Boolean>());
            var full=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<net.minecraft.world.level.chunk.LevelChunk,Boolean>());
            for(String name:java.util.List.of("updatingChunkMap","visibleChunkMap","pendingUnloads")) {
                var actual=testOnlyReadField(net.minecraft.server.level.ChunkMap.class,map,name,
                        it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap.class);
                require(actual.size()<=1089,"Native reference holder view exceeds existing admission: "+name);
                for(Object value:actual.values()) {
                    require(value instanceof net.minecraft.server.level.ChunkHolder,"Unexpected native reference holder");
                    var holder=(net.minecraft.server.level.ChunkHolder)value;
                    var pos=holder.getPos();
                    require(fixture.forcedTarget!=null && Math.abs((long)pos.x-fixture.forcedTarget.cube().x())<=16
                            && Math.abs((long)pos.z-fixture.forcedTarget.cube().z())<=16,
                            "Native reference holder escaped admitted 33x33 footprint");
                    if(!holders.contains(holder)) {
                        require(holders.size()<1089,"Native reference holder identity union exceeds1089");holders.add(holder);
                    }
                    // getNow propagates an exceptional future; never treat it as absent or successful.
                    var chunk=holder.getFullChunkFuture().getNow(net.minecraft.server.level.ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
                    if(chunk!=null) {
                        require(chunk.getPos().equals(pos),"Native reference full future has foreign position");
                        if(!full.contains(chunk)) { require(full.size()<64,"Native reference full-chunk identity union exceeds64");full.add(chunk); }
                    }
                }
            }
        }
        private String quiescenceState(java.util.List<java.util.concurrent.CompletableFuture<?>> barriers) {
            var out=new StringBuilder("barriers=");
            for(int i=0;i<barriers.size();i++) {
                var value=barriers.get(i);out.append(i).append(':').append(value.isDone()).append('/').append(value.isCompletedExceptionally()).append(',');
            }
            out.append(" closedRoster=");
            for(var mailbox:closedMailboxes)out.append(mailbox).append("[idle=").append(idle(mailbox,false)).append("],");
            out.append(" retainedRoster=");
            for(var mailbox:retainedIdleMailboxes)out.append(mailbox).append("[idle=").append(idle(mailbox,false)).append("],");
            return out.append(" sorterWork=").append(sorter.hasWork()).append(" throttlerWork=").append(ticketThrottler.hasWork())
                    .append(" mainPending=").append(fixture.level.getChunkSource().getPendingTasksCount()).toString();
        }
        private boolean lightIdle() {
            var light=fixture.level.getChunkSource().getLightEngine();
            return !light.hasLightWork() && !testOnlyReadField(net.minecraft.server.level.ThreadedLevelLightEngine.class,light,
                    "scheduled",java.util.concurrent.atomic.AtomicBoolean.class).get()
                    && testOnlyReadField(net.minecraft.server.level.ThreadedLevelLightEngine.class,light,
                    "lightTasks",it.unimi.dsi.fastutil.objects.ObjectList.class).isEmpty();
        }
        private String owner() {
            return "fixture="+fixture.directory.getFileName()+" player="+(fixture.player==null?"none":fixture.player.getUUID())
                    +" binding="+(fixture.binding==null?"unbound":fixture.binding.isDetached()?"detached":"attached");
        }
        private String lightState() {
            var light=fixture.level.getChunkSource().getLightEngine();
            return "lightWork="+light.hasLightWork()+",scheduled="+testOnlyReadField(net.minecraft.server.level.ThreadedLevelLightEngine.class,light,
                    "scheduled",java.util.concurrent.atomic.AtomicBoolean.class).get()+",tasks="
                    +testOnlyReadField(net.minecraft.server.level.ThreadedLevelLightEngine.class,light,
                    "lightTasks",it.unimi.dsi.fastutil.objects.ObjectList.class).size()+",mailbox="
                    +testOnlyReadField(net.minecraft.server.level.ThreadedLevelLightEngine.class,light,
                    "taskMailbox",net.minecraft.util.thread.ProcessorMailbox.class);
        }
        void requirePhysicalClose() {
            // Native close can return just before a worker's scheduled bit clears. Await only
            // its completion, for at most five wall seconds; never execute detached fixture work.
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (true) {
                boolean quiet=true;
                for (var mailbox:closedMailboxes) quiet &= idle(mailbox,true);
                for (var mailbox:retainedIdleMailboxes) quiet &= idle(mailbox,false);
                if (quiet) break;
                require(System.nanoTime()<deadline,"Native physical close left an open, queued or running mailbox");
                java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1));
            }
            require(!sorter.hasWork() && !ticketThrottler.hasWork() && fixture.level.getChunkSource().getPendingTasksCount()==0,
                    "Native physical close left generation, ticket-throttler or main-thread work");
            require(lightIdle(),"Native physical close left light work: "+owner()+" "+lightState());
            int regionFiles=0;
            for (var worker:workers) {
                require(testOnlyReadField(net.minecraft.world.level.chunk.storage.IOWorker.class,worker,"shutdownRequested",
                        java.util.concurrent.atomic.AtomicBoolean.class).get(),"Native I/O shutdown was not requested");
                require(testOnlyReadField(net.minecraft.world.level.chunk.storage.IOWorker.class,worker,"pendingWrites",java.util.Map.class).isEmpty(),
                        "Native I/O shutdown retained pending writes");
                var regions=testOnlyReadField(net.minecraft.world.level.chunk.storage.IOWorker.class,worker,
                        "storage",net.minecraft.world.level.chunk.storage.RegionFileStorage.class);
                var cache=testOnlyReadField(net.minecraft.world.level.chunk.storage.RegionFileStorage.class,regions,
                        "regionCache",it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap.class);
                require(cache.size()<=256,"Native region cache exceeded its pinned bound");
                for (var region:cache.values()) {
                    require(region.getClass()==net.minecraft.world.level.chunk.storage.RegionFile.class,"Unexpected native region implementation");
                    require(!testOnlyReadField(net.minecraft.world.level.chunk.storage.RegionFile.class,region,
                            "file",java.nio.channels.FileChannel.class).isOpen(),"Native region file remained open after physical close");
                    regionFiles++;
                }
            }
            require(!lock.isValid() && !lockChannel.isOpen(),"Native fixture storage lock remained open");
            if (fixture.expectedFencedClose==null)
                require(fixture.binding==null || fixture.binding.isDetached(),"Native fixture binding remained attached after physical close");
            else require(fixture.actions.fenced() && fixture.actions.pendingDelivery()==fixture.expectedFencedClose
                    && !fixture.binding.isDetached() && fixture.binding.isClosing(),"Expected-fenced physical close lost exact binding/receipt ownership");
            require(PROCESS_RETAINED_FIXTURES.contains(fixture),"Closed fixture owner graph lost process-lifetime retention");
            System.out.printf("EARTH_NATIVE_FIXTURE_PHYSICAL_CLOSE ioWorkers=3 closedIdleMailboxes=5 retainedIdleMailboxes=3 closedRegionFiles=%d lockClosed=true autonomousLevel=false forcedRetained=%d holdersRetained=%d fullRetained=%d ticketMapsEmptied=false processRetained=%d/%d %s%n",
                    regionFiles,fixture.forcedColumns.size(),fixture.level.getChunkSource().getLoadedChunksCount(),fixture.carriers.size(),
                    PROCESS_RETAINED_FIXTURES.size(),MAX_PROCESS_FIXTURES,owner());
        }
    }
    private static Throwable retain(Throwable original,Throwable next) {
        if (original==null) return next;
        original.addSuppressed(next); return original;
    }
}
