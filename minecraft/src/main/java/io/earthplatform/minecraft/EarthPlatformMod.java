package io.earthplatform.minecraft;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * The NeoForge entry point. It only names the mod: everything else registers itself through
 * {@code @EventBusSubscriber} classes in {@link io.earthplatform.minecraft.cubic} and its subpackages,
 * and the mixins listed in {@code earthplatform.cubic.mixins.json}.
 */
@Mod(EarthPlatformMod.MOD_ID)
public final class EarthPlatformMod {
    public static final String MOD_ID = "earthplatform";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EarthPlatformMod() {
        io.earthplatform.minecraft.cubic.earth.EarthWorld.register();
        LOGGER.info("Earth Platform loaded");
    }
}
