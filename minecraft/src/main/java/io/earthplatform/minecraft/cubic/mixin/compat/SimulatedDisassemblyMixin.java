package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.CubeSections;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Create Aeronautics' physics assembler refuses to put a ship back outside the build range ("out of
 * world") or away from ground, which it looks for in chunk sections. In the cubic level the range
 * is the cubes' and the ground check reads the cubes.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity", remap = false)
abstract class SimulatedDisassemblyMixin {
    @WrapOperation(method = "throwDisassemblyExceptions", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private int earthplatform$cubicTop(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }

    @WrapOperation(method = "throwDisassemblyExceptions", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private int earthplatform$cubicBottom(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MIN_Y : original.call(level);
    }

    @WrapOperation(method = "throwDisassemblyExceptions", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSectionIndexFromSectionY(I)I"))
    private int earthplatform$cubicIndex(LevelChunk chunk, int sectionY, Operation<Integer> original) {
        return CubicLevel.of(chunk.getLevel()) != null ? CubeSections.index(sectionY) : original.call(chunk, sectionY);
    }

    @WrapOperation(method = "throwDisassemblyExceptions", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSectionsCount()I"))
    private int earthplatform$cubicCount(LevelChunk chunk, Operation<Integer> original) {
        return CubicLevel.of(chunk.getLevel()) != null ? CubeSections.COUNT : original.call(chunk);
    }

    @WrapOperation(method = "throwDisassemblyExceptions", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSection(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private LevelChunkSection earthplatform$cubeSection(LevelChunk chunk, int index, Operation<LevelChunkSection> original) {
        return CubicLevel.of(chunk.getLevel()) != null ? CubeSections.section(chunk, index) : original.call(chunk, index);
    }
}
