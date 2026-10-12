package io.earthplatform.minecraft.startupfixture;

/** Original, no-Minecraft fixtures. Names are used as strings so they are never loaded before transformation. */
public final class TerrainTargets {
    public static final class Priority1001 { public void renderSectionLayer() { Hooks.vanilla(); } }
    public static final class Priority900 { public void renderSectionLayer() { Hooks.vanilla(); } }
    public static final class Priority1000 { public void renderSectionLayer() { Hooks.vanilla(); } }
    private TerrainTargets() {}
}
