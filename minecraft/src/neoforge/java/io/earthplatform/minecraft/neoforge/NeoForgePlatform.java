package io.earthplatform.minecraft.neoforge;

import io.earthplatform.minecraft.platform.Platform;
import io.netty.channel.Channel;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** {@link Platform} on NeoForge: its hooks, exactly as the game code called them before the Fabric port. */
public final class NeoForgePlatform implements Platform {
    @Override @Nullable public MinecraftServer currentServer() { return ServerLifecycleHooks.getCurrentServer(); }

    @Override public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) { PacketDistributor.sendToPlayer(player, payload); }

    @Override public void sendToServer(CustomPacketPayload payload) { PacketDistributor.sendToServer(payload); }

    @Override @Nullable public Channel channel(ServerPlayer player) {
        Connection connection = player.connection == null ? null : player.connection.getConnection();
        return connection == null || connection.isMemoryConnection() ? null : connection.channel();
    }

    @Override public boolean capturingSnapshots(Level level) { return level.captureBlockSnapshots; }

    @Override @Nullable public Object captureSnapshot(Level level, BlockPos pos, int flags) {
        if (!level.captureBlockSnapshots || level.isClientSide) return null;
        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos, flags);
        level.capturedBlockSnapshots.add(snapshot);
        return snapshot;
    }

    @Override public void dropSnapshot(Level level, Object snapshot) { level.capturedBlockSnapshots.remove((BlockSnapshot) snapshot); }

    @Override public void onBlockStateChange(BlockState state, Level level, BlockPos pos, BlockState old) { state.onBlockStateChange(level, pos, old); }

    @Override public void addFreshBlockEntities(Level level, Collection<BlockEntity> entities) { level.addFreshBlockEntities(entities); }

    @Override public void onChunkUnloaded(BlockEntity entity) { entity.onChunkUnloaded(); }

    /** NeoForge's BlockEntity.setRemoved already drops the capabilities cached for it. */
    @Override public void removedBlockEntity(BlockEntity entity) {}

    @Override public void handleUpdateTag(BlockEntity entity, CompoundTag tag, HolderLookup.Provider registries) { entity.handleUpdateTag(tag, registries); }

    @Override public CompoundTag persistentData(net.minecraft.world.entity.Entity entity) { return entity.getPersistentData(); }

    @Override @Nullable public long[] tickTimes(MinecraftServer server, ResourceKey<Level> dimension) { return server.getTickTime(dimension); }
}
