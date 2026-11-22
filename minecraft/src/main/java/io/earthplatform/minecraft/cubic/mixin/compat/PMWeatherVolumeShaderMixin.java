package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.earthplatform.minecraft.cubic.compat.EarthWeatherProfile;
import io.earthplatform.minecraft.cubic.compat.PMWeatherCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps PM Weather's volumetric cloud decks above high cubic terrain and aligns rendered overcast
 * and seasonal cloud phase with the player's current Earth location.
 */
@Pseudo
@Mixin(targets = "dev.protomanly.pmweather.shaders.post.VolumeShader", remap = false)
abstract class PMWeatherVolumeShaderMixin {
    @Unique
    private static Vec3 earthplatform$cameraPos() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
            return mc.gameRenderer.getMainCamera().getPosition();
        }
        return mc.player != null ? mc.player.position() : Vec3.ZERO;
    }

    @ModifyExpressionValue(method = "pre", require = 0,
            at = @At(value = "FIELD", target = "Ldev/protomanly/pmweather/config/ServerConfig;layer0Height:D"))
    private double earthplatform$cubicLayer0Height(double original) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return original;
        Vec3 cam = earthplatform$cameraPos();
        return PMWeatherCompat.cloudLayerHeight(level, cam.x, cam.z, original);
    }

    @ModifyExpressionValue(method = "pre", require = 0,
            at = @At(value = "FIELD", target = "Ldev/protomanly/pmweather/config/ServerConfig;layerCHeight:D"))
    private double earthplatform$cubicLayerCHeight(double original) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return original;
        Vec3 cam = earthplatform$cameraPos();
        return PMWeatherCompat.cloudLayerHeight(level, cam.x, cam.z, original);
    }

    @ModifyExpressionValue(method = "pre", require = 0,
            at = @At(value = "INVOKE", target = "Ldev/protomanly/pmweather/seasons/SeasonHandler;getSeasonEffectSine(Lnet/minecraft/world/level/Level;F)F"))
    private float earthplatform$hemisphereShaderSeason(float original) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return original;
        Vec3 cam = earthplatform$cameraPos();
        return original * PMWeatherCompat.seasonSign(level, cam.x, cam.z);
    }

    @ModifyExpressionValue(method = "pre", require = 0,
            at = @At(value = "FIELD", target = "Ldev/protomanly/pmweather/config/ServerConfig;overcastPercent:D"))
    private double earthplatform$regionalShaderOvercast(double original) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return original;
        Vec3 cam = earthplatform$cameraPos();
        EarthWeatherProfile profile = PMWeatherCompat.profile(level, cam.x, cam.y, cam.z);
        return profile != null ? PMWeatherCompat.overcastPercent(original, profile) : original;
    }
}
