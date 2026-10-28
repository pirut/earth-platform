package io.earthplatform.minecraft;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * The mod's name and log, shared by both loaders. The NeoForge entry point is
 * {@code io.earthplatform.minecraft.neoforge.EarthPlatformNeoForge} and the Fabric ones are in
 * {@code io.earthplatform.minecraft.fabric}; each wires the loader's events to the game code here.
 */
public final class EarthPlatformMod {
    public static final String MOD_ID = "earthplatform";
    public static final Logger LOGGER = LogUtils.getLogger();

    private EarthPlatformMod() {}
}
