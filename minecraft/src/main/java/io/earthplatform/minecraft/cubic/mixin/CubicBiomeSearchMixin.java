package io.earthplatform.minecraft.cubic.mixin;

import com.mojang.datafixers.util.Pair;
import io.earthplatform.minecraft.cubic.compat.CubicBiomes;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** /locate biome (and mods that search the same way) finds the Earth world's biomes, not the carrier's plains. */
@Mixin(ServerLevel.class)
abstract class CubicBiomeSearchMixin {
    @Inject(method = "findClosestBiome3d", at = @At("HEAD"), cancellable = true)
    private void earthplatform$earthBiomes(Predicate<Holder<Biome>> filter, BlockPos origin, int radius, int horizontalStep, int verticalStep,
            CallbackInfoReturnable<Pair<BlockPos, Holder<Biome>>> result) {
        CubeGenerator generator = CubicBiomes.generator((ServerLevel) (Object) this);
        if (generator != null) result.setReturnValue(CubicBiomes.closest(generator, filter, origin, radius, horizontalStep, verticalStep));
    }
}
