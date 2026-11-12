package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.earthplatform.minecraft.cubic.gen.FarColumn;
import java.nio.file.Path;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ColumnSurveyTest {
    private static CubeGenerator ground(int y, boolean fails) {
        return new CubeGenerator() {
            public String id() { return "test-survey"; }
            public LevelChunkSection generate(int x, int y, int z) { return null; }
            public void farColumn(int level, int x, int z, FarColumn out) {}
            public int surfaceY(int x, int z) { if (fails) throw new IllegalStateException("no data"); return y; }
            public int waterY(int x, int z) { return 63; }
            public int seaLevel() { return 63; }
        };
    }

    /** Review: with surface flight off, each survey still carried two more longs than before it. */
    @Test void offASurveyIsTheColumnAndItsGroundAsBefore(@TempDir Path directory) throws Exception {
        CubeStorage storage = new CubeStorage(directory);
        long column = CubeKey.column(2, -3);
        assertEquals(2, ColumnSurvey.saved(false));
        assertArrayEquals(new long[] {column, 40}, ColumnSurvey.of(column, 2, -3, ground(40, false), storage, false));
        assertArrayEquals(new long[] {column, Integer.MIN_VALUE}, ColumnSurvey.of(column, 2, -3, ground(40, true), storage, false));
        storage.close();
    }

    @Test void onASurveyAlsoCarriesTheSurfaceBounds(@TempDir Path directory) throws Exception {
        CubeStorage storage = new CubeStorage(directory);
        long column = CubeKey.column(2, -3);
        assertEquals(4, ColumnSurvey.saved(true));
        assertArrayEquals(new long[] {column, 40, 63, 63}, ColumnSurvey.of(column, 2, -3, ground(40, false), storage, true));
        assertArrayEquals(new long[] {column, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE},
                ColumnSurvey.of(column, 2, -3, ground(40, true), storage, true));
        storage.close();
    }
}
