package io.earthplatform.minecraft.cubic;

import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import java.nio.file.Path;
import java.util.function.IntSupplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** A dimension registered with {@link Cubic#register}: where its cubes come from, and its options. */
public final class CubicDimension {
    /** Opens a level's generator. {@code folder} is the dimension's own save folder, for the generator to pin its settings in. */
    @FunctionalInterface
    public interface Generators {
        CubeGenerator open(ServerLevel level, Path folder);
    }

    private final ResourceKey<Level> key;
    private final Generators generators;
    private volatile IntSupplier cloudHeight = () -> 0;

    CubicDimension(ResourceKey<Level> key, Generators generators) {
        this.key = key;
        this.generators = generators;
    }

    public ResourceKey<Level> key() { return key; }
    public Generators generators() { return generators; }

    /**
     * Where shader packs' clouds float, as a Y read each frame: 0 (the default) leaves the pack's
     * own height. Client side only.
     */
    public CubicDimension cloudHeight(IntSupplier height) { this.cloudHeight = height; return this; }
    public int cloudHeight() { return cloudHeight.getAsInt(); }
}
