package io.earthplatform.minecraft.cubic;

import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;

/**
 * What the cube workers work out about a cube column for the server thread: the column, its lowest ground, with
 * {@link SurfaceFlight} on its lowest and highest ground or water, then the saved buried cubes in it.
 */
final class ColumnSurvey {
    private ColumnSurvey() {}

    /** Every bound of a survey that failed, as the column's data could not be had. */
    static final int FAILED = Integer.MIN_VALUE;

    /** Where the saved cubes start; without surface bounds, the layout is as it was before {@link SurfaceFlight}. */
    static int saved(boolean surfaces) { return surfaces ? 4 : 2; }

    /** Workers. Ground that can't be had yet counts as nothing buried there: its cubes are all made, as before. */
    static long[] of(long column, int x, int z, CubeGenerator generator, CubeStorage storage, boolean surfaces) {
        LongArrayList result = new LongArrayList();
        result.add(column);
        try {
            result.add(generator.lowestGround(x, z));
            if (surfaces) { result.add(generator.lowestSurface(x, z)); result.add(generator.highestSurface(x, z)); }
            result.addAll(storage.savedIn(x, x, z, z));
        }
        catch (IOException | RuntimeException failure) {
            result.size(1);
            for (int i = 1; i < saved(surfaces); i++) result.add(FAILED);
        }
        return result.toLongArray();
    }

    /** Whether a survey failed. */
    static boolean failed(long[] survey) { return survey[1] == FAILED; }
}
