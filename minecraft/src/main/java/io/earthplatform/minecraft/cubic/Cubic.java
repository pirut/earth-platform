package io.earthplatform.minecraft.cubic;

import io.earthplatform.minecraft.EarthPlatformMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** Identity of the cubic dimension. */
public final class Cubic {
    public static final ResourceKey<Level> LEVEL =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "cubic"));
    /** Vertical cube radius kept loaded and sent around each player. */
    public static final int VERTICAL_RADIUS = 6;
    /** Horizontal cube radius cap, applied on top of the server view distance. */
    public static final int MAX_HORIZONTAL_RADIUS = 12;

    /** -Dearthplatform.cubic.trace=true logs cube traffic, for engine debugging. */
    public static final boolean TRACE = Boolean.getBoolean("earthplatform.cubic.trace");

    private Cubic() {}

    public static boolean isCubic(ResourceKey<Level> key) { return LEVEL.equals(key); }
}
