package io.earthplatform.minecraft.cubic;

import javax.annotation.Nullable;
import net.minecraft.world.level.Level;

/** Added to every Level by the cubic mixin. Non-cubic levels keep a null store and run vanilla code. */
public interface CubicLevel {
    @Nullable CubeWorld earthplatform$cubes();
    void earthplatform$setCubes(@Nullable CubeWorld cubes);

    @Nullable static CubeWorld of(Level level) { return ((CubicLevel) level).earthplatform$cubes(); }
}
