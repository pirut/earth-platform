package io.earthplatform.minecraft.platform;

import io.netty.channel.Channel;
import java.util.Collection;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The game-side calls that differ between NeoForge and Fabric. NeoForge adds hooks to vanilla
 * (block snapshots, block entity load and unload, per-dimension tick times); on Fabric those are
 * plain vanilla, so its side does what vanilla does.
 */
public interface Platform {
    Platform INSTANCE = Loader.load(Platform.class);

    /** The running server on this side, or null when none is. */
    @Nullable MinecraftServer currentServer();

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    void sendToServer(CustomPacketPayload payload);

    /** The network channel to a player, or null for one in this process or not connected. */
    @Nullable Channel channel(ServerPlayer player);

    // Block hooks NeoForge adds -----------------------------------------------------------------

    /** Whether a level is recording block changes to undo them (NeoForge's place events); never on Fabric. */
    boolean capturingSnapshots(Level level);

    /** Records a block before a change while {@link #capturingSnapshots}, returning a handle for {@link #dropSnapshot}. */
    @Nullable Object captureSnapshot(Level level, BlockPos pos, int flags);

    void dropSnapshot(Level level, Object snapshot);

    /** Tells the new state's block about the change, as NeoForge's Level.setBlock does. */
    void onBlockStateChange(BlockState state, Level level, BlockPos pos, BlockState old);

    /** Block entities that just joined the level, for NeoForge's onLoad. */
    void addFreshBlockEntities(Level level, Collection<BlockEntity> entities);

    /** A block entity whose cube is unloading, for NeoForge's onChunkUnloaded. */
    void onChunkUnloaded(BlockEntity entity);

    /** Applies a block entity's update tag from the server. */
    void handleUpdateTag(BlockEntity entity, CompoundTag tag, HolderLookup.Provider registries);

    /** An entity's mod data that is saved with it (NeoForge's persistent data); a throwaway tag on Fabric, where only NeoForge-only mods' portals use it. */
    CompoundTag persistentData(net.minecraft.world.entity.Entity entity);

    /** Recent tick times of one dimension in nanoseconds, or null where the loader does not keep them. */
    @Nullable long[] tickTimes(MinecraftServer server, ResourceKey<Level> dimension);
}
