package io.earthplatform.minecraft.neoforge;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.play.EarthConfig;
import io.earthplatform.minecraft.cubic.play.EngineToggles;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;

/**
 * The NeoForge entry point. It registers the settings; everything else is wired to NeoForge's
 * events in {@link NeoForgeEvents} and {@link NeoForgeClientEvents}, and the mixins are listed in
 * {@code earthplatform.cubic.mixins.json}.
 */
@Mod(EarthPlatformMod.MOD_ID)
public final class EarthPlatformNeoForge {
    public EarthPlatformNeoForge(ModContainer container) {
        // NeoForge loads the settings files only after the mods are built and the registries filled, which is after
        // classes that read engine flags once may have initialized; so the flags' settings are read here, first.
        EngineToggles.preload(FMLPaths.CONFIGDIR.get());
        container.registerConfig(ModConfig.Type.CLIENT, EarthConfig.CLIENT_SPEC);
        container.registerConfig(ModConfig.Type.COMMON, EarthConfig.COMMON_SPEC);
        EarthPlatformMod.LOGGER.info("Earth Platform loaded");
    }
}
