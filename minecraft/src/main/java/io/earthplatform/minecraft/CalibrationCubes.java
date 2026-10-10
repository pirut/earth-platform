package io.earthplatform.minecraft;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;

/** Synthetic calibration fixture, deliberately not represented as sampled Earth terrain. */
public final class CalibrationCubes {
    public static final int AIR = 0, STONE = 1, SURFACE = 2;
    private final int surfaceY;

    public CalibrationCubes(int surfaceY) { this.surfaceY = surfaceY; }

    public ImmutableCube generate(CubePos pos) {
        if (pos.minBlockY() > surfaceY) return ImmutableCube.uniform(AIR);
        if (pos.minBlockY() + 15 < surfaceY) return ImmutableCube.uniform(STONE);
        int[] states = new int[CubePos.VOLUME];
        for (int y = 0; y < 16; y++) {
            long earthY = pos.minBlockY() + y;
            int state = earthY < surfaceY ? STONE : earthY == surfaceY ? SURFACE : AIR;
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) states[CubePos.index(x, y, z)] = state;
        }
        return ImmutableCube.copyOf(states);
    }
}
