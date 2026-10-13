package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.client.PhotonCloudsHook;
import java.util.Map;
import java.util.Properties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Loads Photon with its clouds at the Earth's cloud height in the Earth, without saving that over
 * the player's own options; see {@code PhotonClouds}. Skipped when Iris is not installed.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
abstract class CubicIrisCloudsMixin {
    @ModifyArg(method = "loadExternalShaderpack", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/shaderpack/ShaderPack;<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V"),
            index = 1, require = 0, remap = false)
    private static Map<String, String> earthplatform$earthClouds(Map<String, String> changed) {
        return PhotonCloudsHook.options(changed);
    }

    @ModifyArg(method = "loadExternalShaderpack", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/Iris;tryUpdateConfigPropertiesFile(Ljava/nio/file/Path;Ljava/util/Properties;)V"),
            index = 1, require = 0, remap = false)
    private static Properties earthplatform$keepPlayerClouds(Properties properties) {
        return PhotonCloudsHook.toSave(properties);
    }
}
