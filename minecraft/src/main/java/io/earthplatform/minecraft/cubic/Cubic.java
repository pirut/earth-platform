package io.earthplatform.minecraft.cubic;

import com.mojang.logging.LogUtils;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * The cubic engine's identity and its registry of cubic dimensions. A mod makes a dimension cubic by
 * registering it here (from its mod constructor or common setup, before any level loads) with the
 * generator its cubes come from; every other dimension keeps vanilla's chunks.
 */
public final class Cubic {
    /** The namespace of the engine's network messages and the mod id its event subscribers register under. */
    public static final String MOD_ID = "earthplatform";
    public static final Logger LOGGER = LogUtils.getLogger();
    /** Vertical cube radius kept loaded and sent around each player. */
    public static final int VERTICAL_RADIUS = 6;
    /** Horizontal cube radius cap, applied on top of the server view distance. */
    public static final int MAX_HORIZONTAL_RADIUS = 12;

    /** -Dearthplatform.cubic.trace=true logs cube traffic, for engine debugging. */
    public static final boolean TRACE = Boolean.getBoolean("earthplatform.cubic.trace");

    private static final Map<ResourceKey<Level>, CubicDimension> DIMENSIONS = new ConcurrentHashMap<>();
    private static final List<ResourceKey<Level>> ORDER = new CopyOnWriteArrayList<>();
    private static final List<Predicate<Entity>> TRANSIT = new CopyOnWriteArrayList<>();

    private Cubic() {}

    /**
     * Makes a dimension cubic. Its dimension type must be a 16-block carrier (min Y 0, height 16, as in
     * the engine's docs); its blocks then live in cubes from {@link CubeWorld#MIN_Y} to {@link CubeWorld#MAX_Y}.
     * The first dimension registered is the {@linkplain #home() home} that portals from vanilla's
     * Nether and End lead back to.
     */
    public static CubicDimension register(ResourceKey<Level> key, CubicDimension.Generators generators) {
        CubicDimension dimension = new CubicDimension(key, generators);
        if (DIMENSIONS.putIfAbsent(key, dimension) != null) throw new IllegalStateException("Cubic dimension " + key.location() + " is registered twice");
        ORDER.add(key);
        return dimension;
    }

    public static boolean isCubic(@Nullable ResourceKey<Level> key) { return key != null && DIMENSIONS.containsKey(key); }

    /** How a registered cubic dimension is made, or null for any other dimension. */
    public static @Nullable CubicDimension dimension(ResourceKey<Level> key) { return DIMENSIONS.get(key); }

    /** The first cubic dimension registered, which the Nether's and End's portals return to; null when there is none. */
    public static @Nullable ResourceKey<Level> home() { return ORDER.isEmpty() ? null : ORDER.get(0); }

    /** Every registered cubic dimension, in the order registered. */
    public static List<ResourceKey<Level>> dimensions() { return new ArrayList<>(ORDER); }

    /**
     * Adds a test for players (or other entities) passing far above the terrain, such as on a jet
     * stream: no cubes are kept loaded or sent about them, and they don't wait for cubes to arrive.
     */
    public static void addTransit(Predicate<Entity> transit) { TRANSIT.add(transit); }

    /** Whether any {@linkplain #addTransit transit test} says an entity is passing above the terrain. */
    public static boolean inTransit(Entity entity) {
        for (Predicate<Entity> transit : TRANSIT) if (transit.test(entity)) return true;
        return false;
    }

    /** Opens the generator for a cubic level, which the engine calls once as the level loads. */
    static CubeGenerator open(net.minecraft.server.level.ServerLevel level, java.nio.file.Path folder) {
        CubicDimension dimension = DIMENSIONS.get(level.dimension());
        if (dimension == null) throw new IllegalStateException(level.dimension().location() + " is not a cubic dimension");
        return dimension.generators().open(level, folder);
    }
}
