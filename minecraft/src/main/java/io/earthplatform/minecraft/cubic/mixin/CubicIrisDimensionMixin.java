package io.earthplatform.minecraft.cubic.mixin;

import java.util.Map;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Looks up Earth shaders as Overworld shaders without changing Iris's pipeline dimension. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.ShaderPack", remap = false)
abstract class CubicIrisDimensionMixin {
    @Unique private static final boolean earthplatform$overworld =
            Boolean.parseBoolean(System.getProperty("earthplatform.iris.overworld", "true"));
    @Unique private static final NamespacedId earthplatform$cubic = new NamespacedId("earthplatform", "cubic");

    @Shadow public abstract Map<NamespacedId, String> getDimensionMap();

    @ModifyVariable(method = "getProgramSet", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private NamespacedId earthplatform$shaderDimension(NamespacedId dimension) {
        return earthplatform$overworld && earthplatform$cubic.equals(dimension) && !getDimensionMap().containsKey(dimension)
                ? DimensionId.OVERWORLD : dimension;
    }
}
