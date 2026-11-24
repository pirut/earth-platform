package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.ChunkTickets;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.ForceLoadCommand;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code /forceload} in the Earth keeps the spot it was run from: the height of whoever ran it (a
 * player, a command block, {@code execute positioned}) is saved with each chunk it forces, and the
 * Earth keeps the cubes round that height loaded (see {@link ChunkTickets}). {@code query} says which.
 * Elsewhere the command is vanilla's.
 */
@Mixin(ForceLoadCommand.class)
abstract class CubicForceLoadMixin {
    @WrapOperation(method = "changeForceLoad", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setChunkForced(IIZ)Z"))
    private static boolean earthplatform$atHeight(ServerLevel level, int chunkX, int chunkZ, boolean add, Operation<Boolean> original,
                                                  CommandSourceStack source, ColumnPos from, ColumnPos to, boolean adding) {
        return ChunkTickets.force(level, chunkX, chunkZ, add, source, () -> original.call(level, chunkX, chunkZ, add));
    }

    @Inject(method = "changeForceLoad", at = @At("RETURN"))
    private static void earthplatform$tellHeight(CommandSourceStack source, ColumnPos from, ColumnPos to, boolean add, CallbackInfoReturnable<Integer> result) {
        if (add) ChunkTickets.tellAdded(source);
    }

    @Inject(method = "queryForceLoad", at = @At("RETURN"))
    private static void earthplatform$tellBand(CommandSourceStack source, ColumnPos pos, CallbackInfoReturnable<Integer> result) {
        ChunkTickets.tellBand(source, pos.toChunkPos());
    }
}
