package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.compat.EarthBiomes;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import java.util.stream.IntStream;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Nature's Compass in the Earth world. It samples the level's biome source, which is the carrier's
 * plains, at heights inside the 16-block carrier; it now samples the Earth generator at the surface
 * and around the player. See {@link EarthBiomes}.
 */
@Pseudo
@Mixin(targets = "com.chaosthedude.naturescompass.util.BiomeSearchWorker", remap = false)
abstract class NaturesCompassMixin {
    @Shadow private ServerLevel level;

    @WrapOperation(method = "<init>", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;outFromOrigin(IIII)Ljava/util/stream/IntStream;"))
    private IntStream earthplatform$heights(int origin, int lower, int upper, int step, Operation<IntStream> original) {
        return EarthBiomes.generator(level) != null ? IntStream.of(EarthBiomes.heights(origin, step)) : original.call(origin, lower, upper, step);
    }

    @WrapOperation(method = {"doWork", "checkConnections"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/BiomeSource;getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;"))
    private Holder<Biome> earthplatform$biome(BiomeSource source, int x, int y, int z, Climate.Sampler sampler, Operation<Holder<Biome>> original) {
        CubeGenerator generator = EarthBiomes.generator(level);
        Holder<Biome> biome = generator == null ? null : generator.biome(QuartPos.toBlock(x), QuartPos.toBlock(y), QuartPos.toBlock(z));
        return biome != null ? biome : original.call(source, x, y, z, sampler);
    }
}
