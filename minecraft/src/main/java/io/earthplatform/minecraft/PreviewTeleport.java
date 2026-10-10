package io.earthplatform.minecraft;

import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Discards source-world physics state before entering a bounded real-data dimension. */
public final class PreviewTeleport {
    private PreviewTeleport() {}

    static void resetSourcePhysics(Entity entity) {
        // Vanilla changeDimension reuses ServerPlayer without clearing its cached support.
        // getOnPos(0.500001F) would then read the OLD block in the NEW dimension before
        // the next collision update, requesting unsupported terrain near the old spawn.
        // Use the native API: clearing onGround also clears mainSupportingBlockPos.
        entity.setOnGround(false);
        entity.resetFallDistance();
        entity.setDeltaMovement(Vec3.ZERO);
    }

    static boolean teleport(ServerPlayer player, ServerLevel target, double x, double y, double z) {
        resetSourcePhysics(player);
        player.teleportTo(target, x, y, z, Set.of(), 0, 0);
        // The vanilla overload returns true even when another mod cancels dimension travel.
        return player.serverLevel() == target
                && player.getX() == x && player.getY() == y && player.getZ() == z;
    }
}
