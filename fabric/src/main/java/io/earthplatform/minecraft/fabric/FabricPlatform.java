package io.earthplatform.minecraft.fabric;

import io.earthplatform.minecraft.fabric.mixin.ConnectionAccess;
import io.earthplatform.minecraft.fabric.mixin.PacketListenerAccess;
import io.earthplatform.minecraft.platform.Platform;
import io.netty.channel.Channel;
import java.util.Collection;
import javax.annotation.Nullable;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@link Platform} on Fabric. Fabric leaves vanilla's block code as it is, so NeoForge's block
 * hooks are vanilla's behaviour here: no snapshots, no load and unload calls.
 */
public final class FabricPlatform implements Platform {
    /** The running server, kept from Fabric's server lifecycle events. */
    @Nullable static volatile MinecraftServer server;

    @Override @Nullable public MinecraftServer currentServer() { return server; }

    @Override public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) { ServerPlayNetworking.send(player, payload); }

    @Override public void sendToServer(CustomPacketPayload payload) { ClientPlayNetworking.send(payload); }

    @Override @Nullable public Channel channel(ServerPlayer player) {
        if (player.connection == null) return null;
        Connection connection = ((PacketListenerAccess) player.connection).earthplatform$connection();
        return connection == null || connection.isMemoryConnection() ? null : ((ConnectionAccess) connection).earthplatform$channel();
    }

    @Override public boolean capturingSnapshots(Level level) { return false; }

    @Override @Nullable public Object captureSnapshot(Level level, BlockPos pos, int flags) { return null; }

    @Override public void dropSnapshot(Level level, Object snapshot) {}

    @Override public void onBlockStateChange(BlockState state, Level level, BlockPos pos, BlockState old) {}

    @Override public void addFreshBlockEntities(Level level, Collection<BlockEntity> entities) {}

    @Override public void onChunkUnloaded(BlockEntity entity) {}

    /** What vanilla's block entity data packet does with the tag. */
    @Override public void handleUpdateTag(BlockEntity entity, CompoundTag tag, HolderLookup.Provider registries) { entity.loadWithComponents(tag, registries); }

    @Override public CompoundTag persistentData(Entity entity) { return new CompoundTag(); }

    @Override @Nullable public long[] tickTimes(MinecraftServer server, ResourceKey<Level> dimension) { return null; }
}
