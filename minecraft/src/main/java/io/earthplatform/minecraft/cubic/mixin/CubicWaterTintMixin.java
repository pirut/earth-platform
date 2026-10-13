package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubicLevel;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Deep and cold sea in the cubic world is the dark navy it is from above, not vanilla's bright
 * blue: open ocean, fjords and shelf seas read close to black navy from orbit. Warm shallows and
 * reefs, frozen seas, lakes and rivers keep vanilla's colours (glacial lakes are milky turquoise), and so does every other world. Near cubes and
 * far terrain both take water's tint from here.
 */
@Mixin(BiomeColors.class)
abstract class CubicWaterTintMixin {
    private static final Map<ResourceKey<Biome>, Integer> DARK = Map.of(
            Biomes.OCEAN, 0x2E5499, Biomes.DEEP_OCEAN, 0x2A4A8C, Biomes.COLD_OCEAN, 0x2E5578,
            Biomes.DEEP_COLD_OCEAN, 0x28496A, Biomes.DEEP_LUKEWARM_OCEAN, 0x2B5298,
            // Lakes fed by glaciers are milky turquoise with rock flour, as Lake Louise and Moraine Lake are.
            ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("earthplatform", "glacial_lake")), 0x52B8BE);

    @WrapOperation(method = "lambda$static$1", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/Biome;getWaterColor()I"))
    private static int earthplatform$darkSea(Biome biome, Operation<Integer> original) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || CubicLevel.of(level) == null) return original.call(biome);
        Integer dark = level.registryAccess().registryOrThrow(Registries.BIOME).getResourceKey(biome).map(DARK::get).orElse(null);
        return dark != null ? dark : original.call(biome);
    }
}
