package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla's "moved too quickly" check allows 10 blocks per move packet since the last tick, but
 * counts six or more packets as one (a client catching up after a stutter, or a server tick slower
 * than a quarter of a second). A player falling at full speed covers about 4 blocks a packet, so
 * on Earth's long drops a single hitch pulled them back up with a warning in the console. In a
 * cubic level the fall those packets could really cover is excused, as vanilla already excuses
 * the motion the server knows about; sideways movement is checked as before, and the move itself
 * still collides with blocks.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class CubicFallSpeedMixin {
    /** The fastest a player falls, in blocks per tick: gravity 0.08 against drag 0.98. */
    @Unique private static final double FALL_PER_PACKET = 3.92;
    /** At most a second of packets is counted, so a flood of packets cannot drop a player any faster. */
    @Unique private static final int MOST_PACKETS = 20;

    @Shadow public ServerPlayer player;
    @Shadow private double firstGoodY;
    @Shadow private int receivedMovePacketCount;
    @Shadow private int knownMovePacketCount;

    @ModifyExpressionValue(
        method = "handleMovePlayer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;lengthSqr()D", ordinal = 0))
    private double earthplatform$excuseFalling(double known, @Local(argsOnly = true) ServerboundMovePlayerPacket packet) {
        if (CubicLevel.of(player.level()) == null) return known;
        double drop = firstGoodY - packet.getY(player.getY());
        if (drop <= 0) return known;
        // This packet is not counted yet: vanilla counts it just after reading the player's motion.
        int packets = Math.clamp(receivedMovePacketCount + 1 - knownMovePacketCount, 1, MOST_PACKETS);
        double unexplained = Math.max(0, drop - packets * FALL_PER_PACKET);
        return known + drop * drop - unexplained * unexplained;
    }
}
