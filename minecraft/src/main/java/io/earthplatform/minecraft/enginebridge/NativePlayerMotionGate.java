package io.earthplatform.minecraft.enginebridge;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Session-specific all-mode frontier. A rolling gate is separate from the legacy walking fence. */
public interface NativePlayerMotionGate {
    boolean ownsMotion(Player player);
    boolean admitMotion(Player player,Vec3 movement,boolean packet);
    boolean admitPosition(Player player,Vec3 destination);
    boolean admitBody(Player player,net.minecraft.world.phys.AABB body);
    boolean admitTeleport(Player player,Vec3 destination);
    void observeMotion(Player player);
    void correctMotion(Player player);
    void correctionSent(Player player,int teleportId);
    void correctionAcknowledged(Player player,int teleportId,boolean vanillaSettled);
    boolean correctionOutstanding(Player player);
}
