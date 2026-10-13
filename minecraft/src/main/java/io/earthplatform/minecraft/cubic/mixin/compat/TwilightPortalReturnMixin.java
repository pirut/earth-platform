package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leaving the Twilight Forest normally lands in its origin dimension, the Overworld. Someone who
 * entered from the Earth world goes back to the Earth portal they used instead: the Twilight Forest's
 * own portal search can't place a portal in the cubic world, whose carrier is only 16 blocks tall.
 * Going in, Earth coordinates keep their scale: the Twilight Forest normally multiplies them by 8, which
 * would put almost all of Earth past its world border.
 */
@Pseudo
@Mixin(targets = "twilightforest.block.TFPortalBlock", remap = false)
abstract class TwilightPortalReturnMixin {
    private static final String EARTHPLATFORM$RETURN = "earthplatform:twilight_return";

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$earthReturn(ServerLevel level, Entity entity, BlockPos portal, CallbackInfoReturnable<DimensionTransition> result) {
        CompoundTag data = entity.getPersistentData();
        if (Cubic.isCubic(level.dimension())) {
            data.put(EARTHPLATFORM$RETURN, NbtUtils.writeBlockPos(portal));
            return;
        }
        if (!data.contains(EARTHPLATFORM$RETURN)) return;
        ServerLevel earth = level.getServer().getLevel(Cubic.LEVEL);
        BlockPos back = NbtUtils.readBlockPos(data, EARTHPLATFORM$RETURN).orElse(null);
        data.remove(EARTHPLATFORM$RETURN);
        if (earth == null || back == null || !(CubicLevel.of(earth) instanceof ServerCubeWorld cubes)) return;
        cubes.loadAround(back);
        result.setReturnValue(new DimensionTransition(earth, Vec3.atBottomCenterOf(earthplatform$beside(earth, back)), Vec3.ZERO,
                entity.getYRot(), entity.getXRot(), DimensionTransition.PLAY_PORTAL_SOUND));
    }

    @WrapOperation(method = "getPortalDestination", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/dimension/DimensionType;getTeleportationScale(Lnet/minecraft/world/level/dimension/DimensionType;Lnet/minecraft/world/level/dimension/DimensionType;)D"))
    private double earthplatform$earthScale(DimensionType from, DimensionType to, Operation<Double> original,
                                            ServerLevel level, Entity entity, BlockPos portal) {
        return Cubic.isCubic(level.dimension()) ? 1.0 : original.call(from, to);
    }

    /** A free spot next to the pool, so the player doesn't land back in the portal. */
    private static BlockPos earthplatform$beside(ServerLevel earth, BlockPos portal) {
        for (int r = 1; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                for (int dy = 1; dy >= -1; dy--) {
                    BlockPos feet = portal.offset(dx, dy, dz);
                    if (earth.getBlockState(feet.below()).isSolid() && earth.getBlockState(feet).getCollisionShape(earth, feet).isEmpty()
                            && earth.getBlockState(feet.above()).getCollisionShape(earth, feet.above()).isEmpty()
                            && !earth.getBlockState(feet).is(earth.getBlockState(portal).getBlock())) {
                        return feet;
                    }
                }
            }
        }
        return portal.above();
    }
}
