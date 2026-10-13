package io.earthplatform.minecraft.cubic.client;

import java.util.Map;
import java.util.Properties;

/** The public door to {@link PhotonClouds} for the Iris mixin, which only runs with Iris present. */
public final class PhotonCloudsHook {
    private PhotonCloudsHook() {}

    public static Map<String, String> options(Map<String, String> changed) { return PhotonClouds.options(changed); }

    public static Properties toSave(Properties properties) { return PhotonClouds.toSave(properties); }

    static void tick() { if (ShaderBlocks.IRIS) PhotonClouds.tick(); }
}
