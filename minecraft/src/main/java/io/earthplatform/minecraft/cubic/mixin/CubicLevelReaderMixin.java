package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Biomes come from cubes, and a box is loaded only when every cube it touches is. */
@Mixin(LevelReader.class)
interface CubicLevelReaderMixin {
    @Inject(method = "getNoiseBiome(III)Lnet/minecraft/core/Holder;", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeBiome(int x, int y, int z, CallbackInfoReturnable<Holder<Biome>> result) {
        if ((Object) this instanceof Level level && CubicLevel.of(level) instanceof CubeWorld cubes) {
            Holder<Biome> biome = cubes.getNoiseBiome(x, y, z);
            if (biome != null) result.setReturnValue(biome);
        }
    }

    @Inject(method = "hasChunksAt(IIIIII)Z", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeBox(int fromX, int fromY, int fromZ, int toX, int toY, int toZ, CallbackInfoReturnable<Boolean> result) {
        if (!((Object) this instanceof Level level) || !(CubicLevel.of(level) instanceof CubeWorld cubes)) return;
        for (int cy = fromY >> 4; cy <= toY >> 4; cy++) for (int cz = fromZ >> 4; cz <= toZ >> 4; cz++) for (int cx = fromX >> 4; cx <= toX >> 4; cx++) {
            if (cubes.cube(CubeKey.pack(cx, cy, cz)) == null) { result.setReturnValue(false); return; }
        }
        result.setReturnValue(true);
    }
}
