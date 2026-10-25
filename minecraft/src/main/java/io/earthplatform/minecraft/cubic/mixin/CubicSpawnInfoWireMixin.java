package io.earthplatform.minecraft.cubic.mixin;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.util.Mth;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The join and respawn packets carry where the player last died, and a death above Y 2047 (any
 * mountain top) would go out in {@link CubicBlockPosWireMixin}'s tall form. Replay Mod rewrites the
 * join packet of every recording by the vanilla layout and drops the extra Y, so the world never
 * loaded and the viewer restarted forever. The recovery compass only uses X and Z, so here Y is
 * clamped into the vanilla range and these two packets always keep the vanilla bytes.
 */
@Mixin(CommonPlayerSpawnInfo.class)
abstract class CubicSpawnInfoWireMixin {
    @Redirect(method = "write", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/network/protocol/game/CommonPlayerSpawnInfo;lastDeathLocation:Ljava/util/Optional;"))
    private Optional<GlobalPos> earthplatform$vanillaDeath(CommonPlayerSpawnInfo info) {
        return info.lastDeathLocation().map(CubicSpawnInfoWireMixin::earthplatform$vanillaY);
    }

    private static GlobalPos earthplatform$vanillaY(GlobalPos death) {
        BlockPos pos = death.pos();
        int y = Mth.clamp(pos.getY(), -2047, 2047);
        return y == pos.getY() ? death : GlobalPos.of(death.dimension(), new BlockPos(pos.getX(), y, pos.getZ()));
    }
}
