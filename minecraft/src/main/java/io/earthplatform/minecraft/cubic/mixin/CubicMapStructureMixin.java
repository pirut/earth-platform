package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eyes of ender, explorer maps and treasure maps find the cubic world's structures, which its
 * generator places itself rather than the carrier's chunk generator.
 */
@Mixin(ServerLevel.class)
abstract class CubicMapStructureMixin {
    @Inject(method = "findNearestMapStructure", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubicStructure(TagKey<Structure> tag, BlockPos pos, int radius, boolean skipKnown, CallbackInfoReturnable<BlockPos> result) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (!(CubicLevel.of(level) instanceof ServerCubeWorld cubes)) return;
        var structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        result.setReturnValue(cubes.generator().locate(s -> structures.wrapAsHolder(s).is(tag), pos.getX(), pos.getZ(), radius));
    }
}
