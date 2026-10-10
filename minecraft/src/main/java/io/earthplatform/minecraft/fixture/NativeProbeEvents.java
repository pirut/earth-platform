package io.earthplatform.minecraft.fixture;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Explicit operator diagnostic. Creates no playable dimension and never replaces the active level. */
@EventBusSubscriber(modid=EarthPlatformMod.MOD_ID)
public final class NativeProbeEvents {
    private static final AtomicLong NEXT_EPOCH=new AtomicLong();
    private NativeProbeEvents() {}
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("earth_probe").requires(source->source.hasPermission(2))
                .then(Commands.literal("run").executes(context->{
                    try { run(context.getSource().getPlayerOrException());return 1; }
                    catch(IOException error) {
                        EarthPlatformMod.LOGGER.error("Native sparse diagnostic I/O failed",error);
                        context.getSource().sendFailure(Component.literal("Native sparse diagnostic failed; inspect the log. No pass was recorded."));return 0;
                    }
                })));
    }
    private static byte[] frame(EngineCubeSnapshot snapshot) {
        var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try { snapshot.write(buffer);byte[] bytes=new byte[buffer.readableBytes()];buffer.readBytes(bytes);return bytes; }
        finally { buffer.release(); }
    }
    private static void run(ServerPlayer player) throws IOException {
        NativeProbeChecks.require(Boolean.getBoolean(SparseFixtureDimensions.OPT_IN)
                && Boolean.getBoolean(io.earthplatform.minecraft.residency.SparseCubeStorage.OPT_IN),"both native fixture opt-ins are required");
        var server=player.server;
        NativeProbeChecks.require(server.isSingleplayer()&&!server.isDedicatedServer(),"only the root-owned integrated development client is supported");
        NativeProbeChecks.require(server.isSameThread(),"server-thread ownership");
        long epoch=NEXT_EPOCH.updateAndGet(value->Math.incrementExact(value));
        Path temp=Files.createTempDirectory("earth-native-sparse-probe-");
        var source=LevelStorageSource.createDefault(temp);
        try(var storage=source.createAccess("disposable-probe")) {
            ServerLevel fixture=null;
            try(var scope=SparseFixtureConstruction.open(SparseFixtureConstruction.Endpoint.SERVER,player.serverLevel().dimensionType())) {
                var biome=server.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
                fixture=new ServerLevel(server,Runnable::run,storage,
                        new DerivedLevelData(server.getWorldData(),server.getWorldData().overworldData()),SparseFixtureConstruction.DIMENSION,
                        new LevelStem(Holder.direct(scope.type()),new SparseFixtureGenerator(biome)),new ChunkProgressListener() {
                            @Override public void updateSpawnPos(ChunkPos pos) {}
                            @Override public void onStatusChange(ChunkPos pos,ChunkStatus status) {}
                            @Override public void start() {}
                            @Override public void stop() {}
                        },false,0L,List.of(),false,new RandomSequences(0L));
                scope.verify(fixture);
            }
            try {
                var binding=NativeSparseBinding.find(fixture);
                binding.cubes().loadUniform(NativeSparseBinding.LOWER,Blocks.AIR.defaultBlockState());
                binding.cubes().loadUniform(NativeSparseBinding.UPPER,Blocks.AIR.defaultBlockState());
                NativeProbeChecks.require(fixture.setBlock(NativeProbeChecks.LOWER_BLOCK,Blocks.GOLD_BLOCK.defaultBlockState(),16),"server Level lower mutation");
                NativeProbeChecks.require(fixture.setBlock(NativeProbeChecks.UPPER_BLOCK,Blocks.DIAMOND_BLOCK.defaultBlockState(),16),"server Level upper mutation");
                var report=NativeProbeChecks.observe(binding,epoch,"server");
                NativeProbeChecks.require(fixture.getChunkSource().getLoadedChunksCount()==0,"no vanilla ChunkHolder creation");
                var identity=NativeProbeChecks.world(binding);
                byte[] lower=frame(binding.cubes().capture(NativeSparseBinding.LOWER,identity,epoch,1));
                byte[] upper=frame(binding.cubes().capture(NativeSparseBinding.UPPER,identity,epoch,2));
                binding.cubes().retire(NativeSparseBinding.LOWER);NativeProbeChecks.verifyLowerRetired(binding);
                binding.cubes().reload(NativeSparseBinding.LOWER);NativeProbeChecks.observe(binding,epoch,"server");
                byte[] renewed=frame(binding.cubes().capture(NativeSparseBinding.LOWER,identity,epoch,3));
                report.addProperty("retireReloadPreservedNativeBlocks",true);report.addProperty("vanillaLoadedColumnHolders",0);
                report.addProperty("status","PASS");var path=NativeProbeChecks.write(report,"server",epoch);
                EarthPlatformMod.LOGGER.info("NATIVE_SPARSE_SERVER_PASS {}",path);
                player.sendSystemMessage(Component.literal("Native sparse server fixture passed. Checking actual ClientLevel next; no full-height gameplay claim."));
                PacketDistributor.sendToPlayer(player,new NativeProbePayload(epoch,lower,upper,renewed));
            } finally {
                if(fixture!=null) {
                    var binding=NativeSparseBinding.find(fixture);if(binding!=null)binding.beginDisposableClose();
                    fixture.close();
                }
            }
        }
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("native-sparse-probe-1").playToClient(NativeProbePayload.TYPE,NativeProbePayload.CODEC,
                (payload,context)->NativeProbeClient.handle(payload));
    }
}
