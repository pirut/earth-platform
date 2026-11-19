package io.earthplatform.geography.globe;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Regional bare-earth elevation from Schleswig-Holstein's ATKIS DGM5 survey. */
public final class Dgm5Elevation implements ElevationLayer {
    public static final String ID = "dgm5-helgoland-v1";
    public static final String ATTRIBUTION = "Helgoland: ATKIS DGM5, © GeoBasis-DE/LVermGeo SH 2025 (CC BY 4.0), resampled by Earth Platform";
    private static final int SIZE = TerrariumTileSource.TILE_SIZE;
    private static final int ZOOM = 15;
    private static final int MIN_ZOOM = 12;
    private static final TileCoverage COVERAGE = TileCoverage.load(ID + ".txt");

    @Override public String id() { return ID; }

    @Override public void refine(int zoom, int x, int y, float[] values) {
        if (zoom < MIN_ZOOM || zoom > ZOOM || !COVERAGE.covers(zoom, x, y)) return;
        int step = 1 << (ZOOM - zoom), left = x * SIZE * step, top = y * SIZE * step;
        int centre = (step - 1) / 2, across = step == 1 ? 1 : 2;
        float[] heights = new float[values.length];
        Arrays.fill(heights, Float.NaN);
        for (int j = 0; j < SIZE; j++) {
            int row = top + j * step;
            for (int i = 0; i < SIZE; i++) {
                int col = left + i * step, at = j * SIZE + i;
                int sum = 0, samples = 0;
                // Zoom 15 uses the original point. Coarser heights average the four pixels
                // surrounding a pixel centre, so their shore and cliff do not jump by a pixel.
                for (int dj = 0; dj < across; dj++) for (int di = 0; di < across; di++) {
                    short h = Data.height(col + centre + di, row + centre + dj);
                    if (h != Short.MIN_VALUE) { sum += h; samples++; }
                }
                if (samples * 2 <= across * across) continue;
                float height = sum / (samples * 10f);
                // The survey contains tidal flats and water surfaces, not underwater relief.
                if (values[at] <= 0 && height < MercatorTileLayer.VOID_ABOVE_SEA) continue;
                heights[at] = height;
            }
        }
        MercatorTileLayer.blend(values, heights);
    }

    private static int key(int x, int y) { return x << 16 | y; }

    private static final class Data {
        private static final Map<Integer, short[]> TILES = load();

        static short height(int x, int y) {
            short[] tile = TILES.get(key(x / SIZE, y / SIZE));
            return tile == null ? Short.MIN_VALUE : tile[(y % SIZE) * SIZE + x % SIZE];
        }

        private static Map<Integer, short[]> load() {
            Map<Integer, short[]> tiles = new HashMap<>();
            try (var resource = Dgm5Elevation.class.getResourceAsStream(ID + ".bin.gz")) {
                if (resource == null) throw new IOException("Missing " + ID);
                try (var input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(resource)))) {
                    int count = input.readInt();
                    for (int i = 0; i < count; i++) {
                        int x = input.readUnsignedShort(), y = input.readUnsignedShort();
                        short[] tile = new short[SIZE * SIZE];
                        for (int p = 0; p < tile.length; p++) tile[p] = input.readShort();
                        tiles.put(key(x, y), tile);
                    }
                }
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not read the bundled " + ID + " elevation", failure);
            }
            return tiles;
        }
    }
}
