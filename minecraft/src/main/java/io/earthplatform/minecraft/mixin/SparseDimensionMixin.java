package io.earthplatform.minecraft.mixin;

import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;

/** Plugin applies the separately stack-verified exact constructor transformer. */
@Mixin(DimensionType.class)
abstract class SparseDimensionMixin {}
