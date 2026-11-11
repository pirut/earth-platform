package io.earthplatform.minecraft.cubic;

import java.util.Properties;

/** A world's generation floor, pinned independently of the engine's addressable height range. */
public enum WorldDepth {
    STANDARD("standard", 63 - 12_000),
    EARTH_DEPTH("earth_depth", CubeWorld.MIN_Y);

    public static final String KEY = "earth.depth";
    public final String id;
    public final int minimumY;

    WorldDepth(String id, int minimumY) { this.id = id; this.minimumY = minimumY; }

    public static WorldDepth newWorld() {
        return Boolean.getBoolean("earthplatform.earth.depth") ? EARTH_DEPTH : STANDARD;
    }

    /** Missing pins are old worlds and must keep their original generator, regardless of config. */
    public static WorldDepth read(Properties properties) {
        String id = properties.getProperty(KEY, EARTH_DEPTH.id);
        for (WorldDepth depth : values()) if (depth.id.equals(id)) return depth;
        throw new IllegalStateException("Unsupported Earth depth '" + id + "'");
    }
}
