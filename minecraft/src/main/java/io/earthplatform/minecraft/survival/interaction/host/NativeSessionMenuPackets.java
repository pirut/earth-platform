package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.persistence.NativeSessionExecutionGate;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Narrow real packet ingress. It never invokes or bypasses the original held native handler. */
public final class NativeSessionMenuPackets {
    private NativeSessionMenuPackets() {}
    private static NativeFirstLoopMetadata.Limits metadataLimits() {
        return new NativeFirstLoopMetadata.Limits(4096, 16384, 8192, 65536, 128, 128, 1);
    }
    private static NativeCraftingRecipeAdmission.Limits recipeLimits() {
        return new NativeCraftingRecipeAdmission.Limits(1024, 16384, 16, 131072, 4 * 1024 * 1024, 131072);
    }
    private static NativeInventoryMenuPreparation.Limits menuLimits() {
        // Existing16MiB ceiling; native packet/transcript scratch is distinct from the256KiB output cap.
        return new NativeInventoryMenuPreparation.Limits(1, 256, 256 * 1024, 16 * 1024 * 1024);
    }
    public static boolean click(ServerGamePacketListenerImpl receiver, ServerPlayer player, ServerboundContainerClickPacket packet) {
        requireReceiverIdentity(receiver, player); // Pure fields only; valid unbound subclasses keep vanilla behavior.
        var gate = NativeSessionExecutionGate.find(player);
        if (gate == null && !player.server.isSameThread()) return false;
        if (gate != null) requireExactReceiver(receiver, player); // Bound route, before any possible enqueue.
        PacketUtils.ensureRunningOnSameThread(packet, receiver, player.serverLevel());
        NativeEffectDeliveryHost host = attached(receiver, player); if (host == null) return false;
        player.resetLastActionTime(); // Pinned handler order, before any menu/container admission.
        try { host.dispatchMenuPacket(packet, metadataLimits(), recipeLimits(), menuLimits()); }
        catch (SurvivalRefusal refusal) { refused(player, host, refusal); }
        return true;
    }
    public static boolean close(ServerGamePacketListenerImpl receiver, ServerPlayer player, ServerboundContainerClosePacket packet) {
        requireReceiverIdentity(receiver, player);
        var gate = NativeSessionExecutionGate.find(player);
        if (gate == null && !player.server.isSameThread()) return false;
        if (gate != null) requireExactReceiver(receiver, player);
        PacketUtils.ensureRunningOnSameThread(packet, receiver, player.serverLevel());
        NativeEffectDeliveryHost host = attached(receiver, player); if (host == null) return false;
        // The pinned native close handler ignores packet.containerId and closes its current menu.
        try { host.dispatchMenuClose(metadataLimits(), menuLimits().menuSnapshotBytes()); }
        catch (SurvivalRefusal refusal) { refused(player, host, refusal); }
        return true;
    }
    private static void requireReceiverIdentity(ServerGamePacketListenerImpl receiver, ServerPlayer player) {
        if (player == null || receiver == null || receiver != player.connection || receiver.player != player)
            throw new SurvivalRefusal("Native menu packet receiver/player identity changed before scheduling");
    }
    private static void requireExactReceiver(ServerGamePacketListenerImpl receiver, ServerPlayer player) {
        requireReceiverIdentity(receiver, player);
        if (player.getClass() != ServerPlayer.class || receiver.getClass() != ServerGamePacketListenerImpl.class)
            throw new SurvivalRefusal("Bound native menu packet requires exact pinned receiver/player classes");
    }
    private static NativeEffectDeliveryHost attached(ServerGamePacketListenerImpl receiver, ServerPlayer player) {
        if (!player.server.isSameThread()) throw new SurvivalRefusal("Native menu host discovery requires its owner thread");
        var host = NativeEffectDeliveryHost.findAttached(player.serverLevel());
        if (host == null || host.player() != player) return null; // Valid ordinary/unbound subclasses remain vanilla.
        requireExactReceiver(receiver, player); return host;
    }
    private static void refused(ServerPlayer player, NativeEffectDeliveryHost host, SurvivalRefusal failure) {
        String message = host.actions().fenced()
                ? "Menu delivery could not finish. This session is held; saving or continuing is blocked."
                : "Menu action refused. Use ordinary left/right item clicks and leave enough inventory space to close.";
        player.displayClientMessage(Component.literal(message), true);
        // No broadcastFullState here: resync mutates stateID/remote caches and requires its own
        // retained action. A later admitted packet performs the native stale-state resync.
    }
}
