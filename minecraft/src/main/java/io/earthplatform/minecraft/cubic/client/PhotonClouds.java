package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.play.EarthConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;

/**
 * Photon's clouds at the Earth's cloud height. Photon sizes its sky for vanilla terrain: one block
 * is ten metres of cloud ({@code CLOUDS_SCALE} 10), so its cumulus deck at 1200 m sits about 120
 * blocks over sea level, on the lowlands and below every hill of a world at real height. In the
 * Earth the pack is loaded with its own options set to one metre a block and the cumulus base at
 * {@link EarthConfig#CLOUD_HEIGHT}, where the vanilla-style deck floats too. Nothing in the pack is
 * edited, the player's saved options are left as they were, and a player who set either option
 * keeps theirs. Other worlds and other packs are untouched; Iris reloads the pack when the player
 * moves between the Earth and another world.
 */
final class PhotonClouds {
    private static final String SCALE = "CLOUDS_SCALE", ALTITUDE = "CLOUDS_CUMULUS_ALTITUDE";
    /** Whether the loaded pack was given the Earth's clouds. */
    private static boolean applied;
    /** The clouds the last reload was asked for, so a reload that can't change them is not repeated. */
    private static Boolean tried;
    /** The player's own values of the options, as their saved settings hold them (null: none saved). */
    private static final Map<String, String> saved = new HashMap<>();

    private PhotonClouds() {}

    private static boolean wanted() {
        var level = Minecraft.getInstance().level;
        return level != null && Cubic.isCubic(level.dimension()) && EarthConfig.CLOUD_HEIGHT.get() > 0;
    }

    /** Iris's option changes for a pack it is about to load, with the Earth's clouds added in the Earth. */
    static Map<String, String> options(Map<String, String> changed) {
        saved.clear();
        saved.put(SCALE, changed.get(SCALE));
        saved.put(ALTITUDE, changed.get(ALTITUDE));
        applied = wanted() && saved.get(SCALE) == null && saved.get(ALTITUDE) == null;
        if (!applied) return changed;
        Map<String, String> earth = new HashMap<>(changed);
        earth.put(SCALE, "1.0");
        earth.put(ALTITUDE, altitude(EarthConfig.CLOUD_HEIGHT.get()));
        return earth;
    }

    /** Photon's cumulus heights run in 100 m steps from 400 to 10,000 m. */
    static String altitude(int metres) {
        int stepped = Math.max(400, Math.min(10_000, Math.round(metres / 100.0F) * 100));
        return stepped + ".0";
    }

    /** The options Iris saves after a load, with the player's own cloud options put back. */
    static Properties toSave(Properties properties) {
        if (!applied) return properties;
        for (var entry : saved.entrySet()) {
            if (entry.getValue() == null) properties.remove(entry.getKey());
            else properties.setProperty(entry.getKey(), entry.getValue());
        }
        return properties;
    }

    /** Each client tick: reloads the pack when the world it was loaded for needs other clouds. */
    static void tick() {
        var pack = Iris.getCurrentPack();
        boolean photon = pack.isPresent() && pack.get().getShaderPackOptions().getOptionSet().getStringOptions().containsKey(SCALE);
        if (!photon || Minecraft.getInstance().level == null) return;
        boolean want = wanted() && saved.get(SCALE) == null && saved.get(ALTITUDE) == null;
        if (want == applied) { tried = null; return; }
        if (Boolean.valueOf(want).equals(tried)) return;
        tried = want;
        try {
            Iris.reload();
        } catch (Exception failure) {
            EarthPlatformMod.LOGGER.warn("Could not reload the shader pack for the Earth's clouds", failure);
        }
    }
}
