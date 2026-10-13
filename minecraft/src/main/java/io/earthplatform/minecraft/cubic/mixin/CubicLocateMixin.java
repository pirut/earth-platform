package io.earthplatform.minecraft.cubic.mixin;

import com.mojang.datafixers.util.Pair;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** /locate structure (and anything else asking the chunk generator) finds the cubic world's structures. */
@Mixin(ChunkGenerator.class)
abstract class CubicLocateMixin {
    @Inject(method = "findNearestMapStructure", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubicLocate(ServerLevel level, HolderSet<Structure> structures, BlockPos pos, int radius, boolean skipKnown,
                                           CallbackInfoReturnable<Pair<BlockPos, Holder<Structure>>> result) {
        if (!(CubicLevel.of(level) instanceof ServerCubeWorld cubes)) return;
        Pair<BlockPos, Holder<Structure>> best = null;
        for (Holder<Structure> holder : structures) {
            BlockPos found = cubes.generator().locate(s -> s == holder.value(), pos.getX(), pos.getZ(), radius);
            if (found != null && (best == null || found.distSqr(pos) < best.getFirst().distSqr(pos))) best = Pair.of(found, holder);
        }
        result.setReturnValue(best);
    }
}
