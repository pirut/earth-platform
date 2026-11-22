package io.earthplatform.geography.globe;

import io.earthplatform.geography.GeoPoint;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Surveyed natural river centre lines from Estonia's topographic database. */
public final class EstonianRivers {
    public static final String ID = "etak-rivers-v1";
    public static final String ATTRIBUTION = "Estonian Topographic Database (ETAK) watercourses, 2026-11-14, Republic of Estonia Land and Spatial Development Board";
    private static final int CELL = 512;
    /** Nominal half-width, in ground metres, for a centre line without measured banks. */
    private static final double HALF_WIDTH = 3;

    private EstonianRivers() {}

    /** Whether a block or voxel covers one of the surveyed channels. */
    public static boolean contains(GlobeProjection grid, double x, double z, int voxel) {
        double mx, mz, scaleMetres;
        if (grid instanceof MercatorGrid mercator) {
            mx = x * mercator.metresPerBlock();
            mz = z * mercator.metresPerBlock();
            // Fast rejection outside Estonia. Avoid loading the index in worlds that never visit it.
            if (mx < 2_430_000 || mx > 3_150_000 || mz < -8_320_000 || mz > -7_860_000) return false;
            scaleMetres = mercator.metresPerBlock();
        } else {
            GeoPoint point = grid.tryGeographic(x, z);
            if (point == null || point.latitude() < 57.3 || point.latitude() > 60.0
                    || point.longitude() < 21.5 || point.longitude() > 28.5) return false;
            mx = MercatorGrid.UNIT.blockX(point);
            mz = MercatorGrid.UNIT.blockZ(point);
            if (mx < 2_430_000 || mx > 3_150_000 || mz < -8_320_000 || mz > -7_860_000) return false;
            scaleMetres = grid.groundMetresPerBlock(x, z) * Math.cosh(mz / MercatorGrid.RADIUS);
        }
        double width = HALF_WIDTH * Math.cosh(mz / MercatorGrid.RADIUS);
        // Retain a continuous trace at the first few far levels, where voxel centres are farther apart.
        double radius = Math.max(width, voxel * scaleMetres * Math.sqrt(0.5));
        return Index.contains(mx, mz, radius);
    }

    private static int cell(double v) { return (int) Math.floor(v / CELL); }
    private static int key(int x, int z) { return x << 16 | z & 0xffff; }

    private record Segment(int x, int z, int endX, int endZ) {
        boolean within(double px, double pz, double radiusSquared) {
            double dx = endX - x, dz = endZ - z;
            double t = Math.clamp(((px - x) * dx + (pz - z) * dz) / (dx * dx + dz * dz), 0, 1);
            double offsetX = px - (x + t * dx), offsetZ = pz - (z + t * dz);
            return offsetX * offsetX + offsetZ * offsetZ <= radiusSquared;
        }
    }

    private static final class Index {
        private static final Map<Integer, List<Segment>> CELLS = load();

        static boolean contains(double x, double z, double radius) {
            double r2 = radius * radius;
            for (int cz = cell(z - radius); cz <= cell(z + radius); cz++) {
                for (int cx = cell(x - radius); cx <= cell(x + radius); cx++) {
                    List<Segment> segments = CELLS.get(key(cx, cz));
                    if (segments == null) continue;
                    for (Segment segment : segments) if (segment.within(x, z, r2)) return true;
                }
            }
            return false;
        }

        private static Map<Integer, List<Segment>> load() {
            Map<Integer, List<Segment>> cells = new HashMap<>();
            try (var resource = EstonianRivers.class.getResourceAsStream(ID + ".bin.gz")) {
                if (resource == null) throw new IOException("Missing ETAK river data");
                try (var input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(resource)))) {
                    int lines = input.readInt();
                    for (int line = 0; line < lines; line++) {
                        int points = input.readInt();
                        int x = input.readInt(), z = input.readInt();
                        for (int point = 1; point < points; point++) {
                            int nextX = x + input.readShort(), nextZ = z + input.readShort();
                            if (x != nextX || z != nextZ) {
                                Segment segment = new Segment(x, z, nextX, nextZ);
                                for (int cz = cell(Math.min(z, nextZ)); cz <= cell(Math.max(z, nextZ)); cz++) {
                                    for (int cx = cell(Math.min(x, nextX)); cx <= cell(Math.max(x, nextX)); cx++) {
                                        cells.computeIfAbsent(key(cx, cz), unused -> new ArrayList<>()).add(segment);
                                    }
                                }
                            }
                            x = nextX;
                            z = nextZ;
                        }
                    }
                }
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not read the bundled ETAK river data", failure);
            }
            return cells;
        }
    }
}
