package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AE2's meteorite compass indexes a chunk's sections by the level's section count and a block's
 * height. In Earth a meteorite is built in a single cube, and real heights fall far outside the
 * carrier, so both overran (meteorites failed to generate). The compass now scans the sections the
 * chunk really has and ignores later single-block edits; it still finds meteorites by column.
 */
@Pseudo
@Mixin(targets = "appeng.server.services.compass.ServerCompassService", remap = false)
abstract class AeCompassMixin {
    @WrapOperation(method = "updateArea(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)V", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getSectionsCount()I"))
    private static int earthplatform$chunkSections(ServerLevel level, Operation<Integer> original, ServerLevel self, ChunkAccess chunk) {
        return CubicLevel.of(level) != null ? Math.min(original.call(level), chunk.getSections().length) : original.call(level);
    }

    @Inject(method = "notifyBlockChange", require = 0, at = @At("HEAD"), cancellable = true)
    private static void earthplatform$skipCubicEdits(ServerLevel level, BlockPos pos, CallbackInfo info) {
        if (CubicLevel.of(level) != null) info.cancel();
    }
}
