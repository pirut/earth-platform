package io.earthplatform.minecraft.cubic.mixin;

import com.mojang.datafixers.util.Pair;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * /locate biome (and anything else searching the level's biomes) finds the cubic world's biomes. The
 * carrier's own biome source is a fixed plains one, which finds plains everywhere and nothing else.
 */
@Mixin(ServerLevel.class)
abstract class CubicBiomeLocateMixin {
    @Inject(method = "findClosestBiome3d", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubicBiome(Predicate<Holder<Biome>> biomes, BlockPos pos, int radius, int horizontalStep, int verticalStep,
                                          CallbackInfoReturnable<Pair<BlockPos, Holder<Biome>>> result) {
        if (!(CubicLevel.of((ServerLevel) (Object) this) instanceof ServerCubeWorld cubes)) return;
        result.setReturnValue(cubes.generator().findBiome(biomes, pos, radius, horizontalStep));
    }
}
