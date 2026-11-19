package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.cubic.Cubic;
import net.minecraft.world.level.Level;

/** Translates Create Sky's Overworld cloud settings to Earth's default cloud deck. */
public final class CreateSkyCompat {
    // Earth's 2500 m deck at sea level Y 63, relative to Create Sky's Y 192 default.
    private static final int CLOUD_OFFSET = 63 + 2500 - 192;

    private CreateSkyCompat() {}

    public static int cloudOffset(Level level) {
        return level != null && Cubic.isCubic(level.dimension()) ? CLOUD_OFFSET : 0;
    }
}
