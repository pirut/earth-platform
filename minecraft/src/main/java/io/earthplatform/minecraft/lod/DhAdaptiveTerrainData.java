package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Bounded surface-only transition between native metre columns and far summaries.
 * Refinement changes sampling only: suppression always uses exact ready 3D cubes.
 * No cube generation, skirts into unknown coverage, or distant unit-column grid.
 */
public final class DhAdaptiveTerrainData {
    public static final int CELL_SIZE = 16, CELLS = 64, RADIUS = 512, TILE_SIZE = 256;
    public static final int FINE_TILE_SIZE = 32;
    public static final int MAX_CELLS = 12288, MAX_FACES = 32768, MAX_TILES = 64, MAX_PAGE_FACES = 4096;

    /**
     * All counts describe this batch, never previously published/accumulated work.
     * sampledCells counts attempted surface queries (known + unknown); refinedCells
     * counts leaves smaller than 16m, including unknown leaves. transitionFaces
     * counts source walls joining differently sized known leaves, before clipping.
     */
    public record Batch(Map<DhGeographicLodData.TileKey, List<DhTerrainGeometry.PageFace>> tiles,
                        int faces, int clippedSourceFaces, int sampledCells, int knownCells,
                        int unknownCells, int refinedCells, int sourceFaces, int transitionFaces) {
        public Batch {
            var copy = new LinkedHashMap<DhGeographicLodData.TileKey, List<DhTerrainGeometry.PageFace>>();
            tiles.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            tiles = Collections.unmodifiableMap(copy);
        }
    }

    private record SourceFace(DhTerrainGeometry.Face face, int tileSize) { }
    private record Footprint(long x0, long z0, long x1, long z1) { }
    private record Leaf(long x, long z, int size) { }
    private record Cell(Leaf leaf, long top, int argb) { }
    private record Edge(long start, long end, Cell cell) { }
    private static final class EdgeLine {
        // Negative side means west/north of the plane; positive means east/south.
        final List<Edge> negative = new ArrayList<>(), positive = new ArrayList<>();
    }

    private final SceneSurfaceSampler sampler;
    private final String identity;
    private final long originX, originZ;

    /** Pins identity and validates the footprint without reading terrain. */
    public DhAdaptiveTerrainData(SceneSurfaceSampler sampler, long centerX, long centerZ) {
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        identity = sampler.identity();
        if (identity == null || identity.isBlank()) throw new IllegalArgumentException("Source/appearance identity required");
        originX = Math.subtractExact(Math.multiplyExact(Math.floorDiv(centerX, CELL_SIZE), CELL_SIZE), RADIUS);
        originZ = Math.subtractExact(Math.multiplyExact(Math.floorDiv(centerZ, CELL_SIZE), CELL_SIZE), RADIUS);
        Math.addExact(originX, (long) CELLS * CELL_SIZE);
        Math.addExact(originZ, (long) CELLS * CELL_SIZE);
    }

    public Batch clip(List<CubePos> actualReadyCubes) {
        return clip(actualReadyCubes, actualReadyCubes);
    }

    /** A retained refinement profile may keep fine geometry while readiness is empty. */
    public Batch clip(List<CubePos> actualReadyCubes, List<CubePos> refinementFootprints) {
        var ready = validateCubes(actualReadyCubes);
        var refinement = validateCubes(refinementFootprints);
        var footprints = refinement.stream().map(cube -> new Footprint(cube.minBlockX() - 1,
                cube.minBlockZ() - 1, cube.minBlockX() + CubePos.SIZE + 1,
                cube.minBlockZ() + CubePos.SIZE + 1)).distinct().toList();

        // Finish the entire partition, or reject its budget, before provider reads.
        var leaves = new ArrayList<Leaf>(CELLS * CELLS);
        for (int z = 0; z < CELLS; z++) for (int x = 0; x < CELLS; x++)
            partition(originX + (long) x * CELL_SIZE, originZ + (long) z * CELL_SIZE,
                    CELL_SIZE, footprints, leaves);
        requireIdentity();
        var cells = new ArrayList<Cell>(leaves.size());
        int unknown = 0, refined = 0;
        for (Leaf leaf : leaves) {
            if (leaf.size < CELL_SIZE) refined++;
            long x = leaf.x + leaf.size / 2, z = leaf.z + leaf.size / 2;
            // size/2 is zero for a metre leaf: sample the exact native block.
            try {
                long y = sampler.surfaceY(x, z);
                if (y < DhTerrainGeometry.MIN_Y || y >= DhTerrainGeometry.MAX_Y_EXCLUSIVE)
                    throw new IllegalArgumentException("Source exceeds native Earth envelope");
                int argb = sampler.argb(x, z, y);
                if ((argb >>> 24) != 255) throw new IllegalArgumentException("Opaque far surface required");
                cells.add(new Cell(leaf, y + 1, argb));
            } catch (MissingTerrainException absent) {
                unknown++;
            }
        }
        requireIdentity();
        if (cells.isEmpty()) throw new IllegalArgumentException("No known far cells in bounded view");

        var faces = new ArrayList<SourceFace>();
        var xEdges = new TreeMap<Long, EdgeLine>();
        var zEdges = new TreeMap<Long, EdgeLine>();
        for (Cell cell : cells) {
            Leaf leaf = cell.leaf;
            append(faces, new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.UP,
                    leaf.x, cell.top, leaf.z, leaf.size, leaf.size, cell.argb), leaf.size < CELL_SIZE);
            addEdge(xEdges, leaf.x, new Edge(leaf.z, leaf.z + leaf.size, cell), false);
            addEdge(xEdges, leaf.x + leaf.size, new Edge(leaf.z, leaf.z + leaf.size, cell), true);
            addEdge(zEdges, leaf.z, new Edge(leaf.x, leaf.x + leaf.size, cell), false);
            addEdge(zEdges, leaf.z + leaf.size, new Edge(leaf.x, leaf.x + leaf.size, cell), true);
        }
        int transitionFaces = joinEdges(xEdges, true, faces) + joinEdges(zEdges, false, faces);
        var tiles = new LinkedHashMap<DhGeographicLodData.TileKey, List<DhTerrainGeometry.PageFace>>();
        int count = 0, changed = 0;
        for (var summary : faces) {
            var source = summary.face;
            var fragments = DhTerrainGeometry.clip(source, ready, DhTerrainGeometry.MAX_PIECES);
            if (fragments.size() != 1 || !fragments.getFirst().equals(source)) changed++;
            for (var face : fragments) {
                long tileX = Math.floorDiv(face.x() - (face.direction() == DhTerrainGeometry.Direction.EAST ? 1 : 0), summary.tileSize) * summary.tileSize;
                long tileZ = Math.floorDiv(face.z() - (face.direction() == DhTerrainGeometry.Direction.SOUTH ? 1 : 0), summary.tileSize) * summary.tileSize;
                for (var page : DhTerrainGeometry.splitPages(face, tileX, tileZ)) {
                    if (++count > MAX_FACES) throw new IllegalArgumentException("Clipped far face budget exceeded");
                    var key = new DhGeographicLodData.TileKey(tileX, tileZ, page.pageY2048());
                    var list = tiles.computeIfAbsent(key, ignored -> new ArrayList<>());
                    if (tiles.size() > MAX_TILES || list.size() >= MAX_PAGE_FACES)
                        throw new IllegalArgumentException("Far tile owner or per-page face budget exceeded");
                    list.add(page);
                }
            }
        }
        return new Batch(tiles, count, changed, leaves.size(), cells.size(), unknown,
                refined, faces.size(), transitionFaces);
    }

    private static List<CubePos> validateCubes(List<CubePos> cubes) {
        Objects.requireNonNull(cubes, "cubes");
        if (cubes.size() > DhTerrainGeometry.MAX_READY_CUBES)
            throw new IllegalArgumentException("Ready cube clipping budget exceeded");
        // Validate even cubes that cannot intersect a face; sorting also makes
        // duplicate/reordered readiness yield identical sample and output order.
        return cubes.stream().map(cube -> {
            Objects.requireNonNull(cube, "ready cube");
            if (cube.minBlockY() < DhTerrainGeometry.MIN_Y
                    || cube.minBlockY() + CubePos.SIZE > DhTerrainGeometry.MAX_Y_EXCLUSIVE)
                throw new IllegalArgumentException("Ready cube outside native Earth envelope");
            return cube;
        }).distinct().sorted().toList();
    }

    private static void partition(long x, long z, int size, List<Footprint> footprints, List<Leaf> leaves) {
        boolean intersect = false;
        if (size > 1) for (Footprint footprint : footprints) {
            if (x < footprint.x1 && x + size > footprint.x0 && z < footprint.z1 && z + size > footprint.z0) {
                intersect = true;
                break;
            }
        }
        if (intersect) {
            int half = size / 2;
            partition(x, z, half, footprints, leaves);
            partition(x + half, z, half, footprints, leaves);
            partition(x, z + half, half, footprints, leaves);
            partition(x + half, z + half, half, footprints, leaves);
        } else {
            if (leaves.size() == MAX_CELLS) throw new IllegalArgumentException("Adaptive partition cell budget exceeded before sampling");
            leaves.add(new Leaf(x, z, size));
        }
    }

    private static void addEdge(Map<Long, EdgeLine> lines, long plane, Edge edge, boolean negativeSide) {
        EdgeLine line = lines.computeIfAbsent(plane, ignored -> new EdgeLine());
        (negativeSide ? line.negative : line.positive).add(edge);
    }

    /** Match opposite intervals, including every fine interval along a coarse edge. */
    private static int joinEdges(Map<Long, EdgeLine> lines, boolean xAxis, List<SourceFace> faces) {
        int transitions = 0;
        for (var entry : lines.entrySet()) {
            EdgeLine line = entry.getValue();
            line.negative.sort(Comparator.comparingLong(Edge::start));
            line.positive.sort(Comparator.comparingLong(Edge::start));
            int i = 0, j = 0;
            while (i < line.negative.size() && j < line.positive.size()) {
                Edge a = line.negative.get(i), b = line.positive.get(j);
                long start = Math.max(a.start, b.start), end = Math.min(a.end, b.end);
                if (start < end && a.cell.top != b.cell.top) {
                    boolean positiveNormal = a.cell.top > b.cell.top;
                    Cell high = positiveNormal ? a.cell : b.cell;
                    var direction = xAxis
                            ? (positiveNormal ? DhTerrainGeometry.Direction.EAST : DhTerrainGeometry.Direction.WEST)
                            : (positiveNormal ? DhTerrainGeometry.Direction.SOUTH : DhTerrainGeometry.Direction.NORTH);
                    append(faces, new DhTerrainGeometry.Face(direction,
                            xAxis ? entry.getKey() : start, Math.min(a.cell.top, b.cell.top),
                            xAxis ? start : entry.getKey(), Math.toIntExact(end - start),
                            Math.toIntExact(Math.abs(a.cell.top - b.cell.top)), shade(high.argb, xAxis ? 0.72f : 0.85f)),
                            a.cell.leaf.size < CELL_SIZE || b.cell.leaf.size < CELL_SIZE);
                    if (a.cell.leaf.size != b.cell.leaf.size) transitions++;
                }
                if (a.end <= b.end) i++;
                if (b.end <= a.end) j++;
            }
        }
        return transitions;
    }

    private static void append(List<SourceFace> faces, DhTerrainGeometry.Face face, boolean refined) {
        if (faces.size() == MAX_FACES) throw new IllegalArgumentException("Source face budget exceeded");
        // Every leaf is globally dyadic and at most 16m: neither tops nor
        // matched edge intervals cross a 32m or 256m ownership boundary.
        faces.add(new SourceFace(face, refined ? FINE_TILE_SIZE : TILE_SIZE));
    }

    private static int shade(int color, float factor) {
        return 0xff000000 | Math.round(((color >>> 16) & 255) * factor) << 16
                | Math.round(((color >>> 8) & 255) * factor) << 8 | Math.round((color & 255) * factor);
    }

    private void requireIdentity() {
        if (!identity.equals(sampler.identity())) throw new IllegalStateException("Source identity changed during adaptive summary");
    }

    public String identity() { return identity; }
    public long originX() { return originX; }
    public long originZ() { return originZ; }
}
