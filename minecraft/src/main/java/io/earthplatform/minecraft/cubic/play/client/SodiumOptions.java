package io.earthplatform.minecraft.cubic.play.client;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.play.EarthConfig;
import java.util.Locale;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/**
 * An Earth Platform page in Sodium's Video Settings, beside Sodium's own pages, with the client settings
 * players reach for while playing. Sodium finds this class by its annotation and only when Sodium is
 * installed, so nothing here loads without it. The values are the same ones the config file and the
 * pause menu use.
 */
@ConfigEntryPointForge(EarthPlatformMod.MOD_ID)
public final class SodiumOptions implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var detail = builder.createIntegerOption(id("far_distance"))
                .setName(shortName("distance"))
                .setTooltip(tooltip("farDetail"))
                .setRange(4, io.earthplatform.minecraft.cubic.far.Far.MAX_DETAIL, 1)
                .setValueFormatter(value -> Component.literal(distance(value)))
                .setDefaultValue(11)
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                .setBinding(EarthConfig.FAR_DETAIL::set, EarthConfig.FAR_DETAIL::get)
                .setStorageHandler(EarthConfig.CLIENT_SPEC::save);
        var sharpness = builder.createEnumOption(id("far_sharpness"), EarthConfig.Sharpness.class)
                .setName(shortName("sharpness"))
                .setTooltip(tooltip("farSharpness"))
                .setElementNameProvider(EarthConfig.Sharpness::getTranslatedName)
                .setDefaultValue(EarthConfig.Sharpness.BALANCED)
                .setImpact(OptionImpact.HIGH)
                .setBinding(EarthConfig::setSharpness, EarthConfig.FAR_SHARPNESS::get)
                .setStorageHandler(EarthConfig.CLIENT_SPEC::save);
        var textured = builder.createIntegerOption(id("far_textured_levels"))
                .setName(shortName("texturedLevels"))
                .setTooltip(tooltip("farTexturedLevels"))
                .setRange(0, 8, 1)
                .setValueFormatter(value -> Component.literal(Integer.toString(value)))
                .setDefaultValue(8)
                .setImpact(OptionImpact.MEDIUM)
                .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                .setBinding(EarthConfig.FAR_TEXTURED_LEVELS::set, EarthConfig.FAR_TEXTURED_LEVELS::get)
                .setStorageHandler(EarthConfig.CLIENT_SPEC::save);
        var clouds = builder.createIntegerOption(id("cloud_height"))
                .setName(shortName("cloudHeight"))
                .setTooltip(tooltip("cloudHeight"))
                .setRange(0, 9000, 100)
                .setValueFormatter(value -> value == 0 ? Component.translatable("earthplatform.sodium.vanillaClouds") : Component.literal(value + " m"))
                .setDefaultValue(2500)
                .setImpact(OptionImpact.LOW)
                .setBinding(EarthConfig.CLOUD_HEIGHT::set, () -> EarthConfig.CLOUD_HEIGHT.get() / 100 * 100)
                .setStorageHandler(EarthConfig.CLIENT_SPEC::save);
        var downloads = builder.createBooleanOption(id("show_downloads"))
                .setName(name("showDownloads"))
                .setTooltip(tooltip("showDownloads"))
                .setDefaultValue(true)
                .setImpact(OptionImpact.LOW)
                .setBinding(EarthConfig.SHOW_DOWNLOADS::set, EarthConfig.SHOW_DOWNLOADS::get)
                .setStorageHandler(EarthConfig.CLIENT_SPEC::save);
        var preselect = builder.createBooleanOption(id("preselect_earth"))
                .setName(name("preselectEarth"))
                .setTooltip(tooltip("preselectEarth"))
                .setDefaultValue(true)
                .setImpact(OptionImpact.LOW)
                .setBinding(EarthConfig.PRESELECT_EARTH::set, EarthConfig.PRESELECT_EARTH::get)
                .setStorageHandler(EarthConfig.CLIENT_SPEC::save);
        // Server and world settings stay on the full config screen, one click away.
        var allSettings = builder.createExternalButtonOption(id("all_settings"))
                .setName(Component.translatable("earthplatform.sodium.allSettings"))
                .setTooltip(Component.translatable("earthplatform.sodium.allSettings.tooltip"))
                .setScreenConsumer(parent -> Minecraft.getInstance().setScreen(
                        new ConfigurationScreen(modContainer(), parent)));

        builder.registerOwnModOptions()
                .setName("Earth Platform")
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable("earthplatform.sodium.page"))
                        .addOptionGroup(builder.createOptionGroup()
                                .setName(Component.translatable("earthplatform.sodium.farTerrain"))
                                .addOption(detail)
                                .addOption(sharpness)
                                .addOption(textured))
                        .addOptionGroup(builder.createOptionGroup()
                                .setName(Component.translatable("earthplatform.sodium.sky"))
                                .addOption(clouds))
                        .addOptionGroup(builder.createOptionGroup()
                                .setName(Component.translatable("earthplatform.sodium.general"))
                                .addOption(downloads)
                                .addOption(preselect)
                                .addOption(allSettings)));
    }

    private static net.neoforged.fml.ModContainer modContainer() {
        return net.neoforged.fml.ModList.get().getModContainerById(EarthPlatformMod.MOD_ID).orElseThrow();
    }

    /** The far distance as the pause-menu slider shows it: kilometers, with miles beside them. */
    static String distance(int detail) {
        int km = EarthConfig.distanceKm(detail);
        return String.format(Locale.ROOT, "%d km (%d mi)", km, Math.round(km * 0.621371));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, path);
    }

    private static Component name(String key) {
        return Component.translatable(EarthPlatformMod.MOD_ID + ".configuration." + key);
    }

    // Shorter names than the config screen's, since the group heading already says "Far terrain" and the
    // value shows its unit; Sodium cuts names that run into the value.
    private static Component shortName(String key) {
        return Component.translatable("earthplatform.sodium." + key);
    }

    private static Component tooltip(String key) {
        return Component.translatable(EarthPlatformMod.MOD_ID + ".configuration." + key + ".tooltip");
    }
}
