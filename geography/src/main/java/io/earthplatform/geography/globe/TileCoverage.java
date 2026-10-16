package io.earthplatform.geography.globe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Where a national elevation source has data, as Web Mercator tiles of zoom 12, so its layer asks
 * its server only about tiles it covers. Read from a list built by {@code geography/tools/coverage.py}:
 * one line per zoom-8 tile with any data, its x and y, then 64 hex digits, a bit per zoom-12 tile
 * inside it (16 by 16, row by row from the north-west, most significant bit first).
 */
final class TileCoverage {
    static final int LIST_ZOOM = 8, CELL_ZOOM = 12;
    private static final int SIDE = 1 << (CELL_ZOOM - LIST_ZOOM);

    /** Each listed zoom-8 tile's cells, four longs of 64 bits from the north-west. */
    private final Map<Long, long[]> tiles;

    private TileCoverage(Map<Long, long[]> tiles) { this.tiles = tiles; }

    static TileCoverage load(String resource) {
        Map<Long, long[]> tiles = new HashMap<>();
        try (InputStream raw = TileCoverage.class.getResourceAsStream(resource)) {
            if (raw == null) throw new IOException("Missing " + resource);
            BufferedReader in = new BufferedReader(new InputStreamReader(raw, StandardCharsets.US_ASCII));
            for (String line; (line = in.readLine()) != null; ) {
                if (line.isBlank()) continue;
                String[] parts = line.trim().split(" ");
                if (parts.length != 3 || parts[2].length() != 64) throw new IOException("Bad coverage line '" + line + "'");
                long[] bits = new long[4];
                for (int i = 0; i < 4; i++) bits[i] = Long.parseUnsignedLong(parts[2].substring(i * 16, i * 16 + 16), 16);
                tiles.put(key(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])), bits);
            }
        } catch (IOException | RuntimeException failure) {
            throw new UncheckedIOException("Could not read the coverage list " + resource, failure instanceof IOException io ? io : new IOException(failure));
        }
        return new TileCoverage(Map.copyOf(tiles));
    }

    /** Whether any part of a tile may have data. */
    boolean covers(int zoom, int x, int y) {
        if (zoom >= CELL_ZOOM) {
            int shift = zoom - CELL_ZOOM;
            return cell(x >> shift, y >> shift);
        }
        if (zoom <= LIST_ZOOM) {
            int shift = LIST_ZOOM - zoom;
            for (long key : tiles.keySet()) {
                int tx = (int) (key >> 32), ty = (int) key;
                if (tx >> shift == x && ty >> shift == y) return true;
            }
            return false;
        }
        // Between the two: the tile's own block of cells in its zoom-8 tile.
        int shift = zoom - LIST_ZOOM, span = SIDE >> shift;
        long[] bits = tiles.get(key(x >> shift, y >> shift));
        if (bits == null) return false;
        int left = (x & ((1 << shift) - 1)) * span, top = (y & ((1 << shift) - 1)) * span;
        for (int j = top; j < top + span; j++)
            for (int i = left; i < left + span; i++)
                if (bit(bits, i, j)) return true;
        return false;
    }

    /** Whether a zoom-12 tile has data. */
    private boolean cell(int x, int y) {
        long[] bits = tiles.get(key(x / SIDE, y / SIDE));
        return bits != null && bit(bits, x % SIDE, y % SIDE);
    }

    private static boolean bit(long[] bits, int i, int j) {
        int at = j * SIDE + i;
        return (bits[at >> 6] >>> (63 - (at & 63)) & 1) != 0;
    }

    private static long key(int x, int y) { return (long) x << 32 | (y & 0xFFFFFFFFL); }
}
