package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The client's ambient ticks, which try about 1,300 random blocks round the player every tick for
 * flames, drips and biome particles, look up less in the cubic level: the fluid comes from the block
 * state already read, and a cube none of whose biomes has ambient particles (Nether ash, spores) gives
 * its own biome at once instead of the fuzzy-zoomed one, which reads eight biome samples. Particles
 * stay the same: only biomes without them skip the zoom.
 */
@Mixin(ClientLevel.class)
abstract class CubicAnimateTickMixin {
    private static final Predicate<Holder<Biome>> PARTICLES = biome -> biome.value().getAmbientParticle().isPresent();

    @WrapOperation(method = "doAnimateTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"))
    private FluidState earthplatform$fluid(ClientLevel level, BlockPos pos, Operation<FluidState> original, @Local BlockState state) {
        return CubicLevel.at(level, pos.getX(), pos.getZ()) != null ? state.getFluidState() : original.call(level, pos);
    }

    @WrapOperation(method = "doAnimateTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"))
    private Holder<Biome> earthplatform$biome(ClientLevel level, BlockPos pos, Operation<Holder<Biome>> original) {
        CubeWorld cubes = CubicLevel.at(level, pos.getX(), pos.getZ());
        Cube cube = cubes == null ? null : cubes.cubeAtBlock(pos.getX(), pos.getY(), pos.getZ());
        if (cube == null || cube.section.getBiomes().maybeHas(PARTICLES)) return original.call(level, pos);
        return cube.section.getNoiseBiome((pos.getX() & 15) >> 2, (pos.getY() & 15) >> 2, (pos.getZ() & 15) >> 2);
    }
}
