package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Homeostatic scans nearby blocks for campfires, furnaces, torches, lava, water and shelter by
 * indexing {@code chunk.getSection((pos.getY() >> 4) - chunk.getMinSection())} inside a try/catch.
 * On the 1-section carrier chunk, every position outside Y [0, 15] threw ArrayIndexOutOfBounds and
 * silently ignored all local heat sources and indoor shelter. Route that section lookup to the
 * loaded cubic section instead.
 */
@Pseudo
@Mixin(targets = "homeostatic.common.temperature.Environment", remap = false)
abstract class HomeostaticSectionMixin {
    @WrapOperation(method = "get(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;)Lhomeostatic/common/temperature/EnvironmentInfo;",
            require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSection(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private static LevelChunkSection earthplatform$cubeSection(LevelChunk chunk, int sectionIndex,
                                                               Operation<LevelChunkSection> original) {
        CubeWorld cubes = CubicLevel.at(chunk.getLevel(), chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ());
        if (cubes == null) return original.call(chunk, sectionIndex);
        int cubeY = sectionIndex + chunk.getMinSection();
        Cube cube = cubes.cube(CubeKey.pack(chunk.getPos().x, cubeY, chunk.getPos().z));
        if (cube == null) throw new IndexOutOfBoundsException("Unloaded cube section " + cubeY);
        return cube.section;
    }
}
