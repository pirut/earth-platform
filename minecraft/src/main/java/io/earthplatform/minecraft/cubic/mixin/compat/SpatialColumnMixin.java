package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.compat.CubeSections;
import io.earthplatform.minecraft.cubic.compat.SpatialCompat;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** The block column AE2's spatial IO swaps one block at a time: in Earth its sections are the cubes'. */
@Pseudo
@Mixin(targets = "appeng.spatial.CachedPlane$Column", remap = false)
abstract class SpatialColumnMixin {
    @WrapOperation(method = "getSection", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSectionIndexFromSectionY(I)I"))
    private int earthplatform$cubeIndex(LevelChunk chunk, int sectionY, Operation<Integer> original) {
        return SpatialCompat.cubes(chunk) != null ? CubeSections.index(sectionY) : original.call(chunk, sectionY);
    }

    @WrapOperation(method = "getSection", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSection(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private LevelChunkSection earthplatform$cubeSection(LevelChunk chunk, int index, Operation<LevelChunkSection> original) {
        return SpatialCompat.cubes(chunk) != null ? SpatialCompat.section(chunk, index) : original.call(chunk, index);
    }
}
