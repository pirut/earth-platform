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

    /** Explicit bounded-resource refusal, distinct from arbitrary source failures. */
    public static final class BudgetExceeded extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public BudgetExceeded(String message) { super(message); }
    }

    /**
     * All counts describe this batch, never previously published/accumulated work.
     * sampledCells counts attempted leaves (known + unknown), including footprint rejects
     * before a height query. Source callback counters are separate; refinedCells
     * counts leaves smaller than 16m, including unknown leaves. transitionFaces
     * counts source walls joining differently sized known leaves, before clipping.
     */
    public record Batch(Map<DhGeographicLodData.TileKey, List<DhTerrainGeometry.PageFace>> tiles,
                        int faces, int clippedSourceFaces, int sampledCells, int knownCells,
                        int unknownCells, int refinedCells, int sourceFaces, int transitionFaces,
                        int centerSampledCells,int footprintRejectedCells,int fullySupportedCells) {
        public Batch {
            var copy = new LinkedHashMap<DhGeographicLodData.TileKey, List<DhTerrainGeometry.PageFace>>();
            tiles.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            tiles = Collections.unmodifiableMap(copy);
        }
    }

    private record SourceFace(DhTerrainGeometry.Face face, int tileSize) { }
    private record Footprint(long x0, long z0, long x1, long z1) { }
    private record Leaf(long x, long z, int size) { }
    private record Cell(Leaf leaf, long top, int argb, SceneSurfaceSampler.ColumnMaterials materials) { }
    private record Edge(long start, long end, Cell cell) { }
    private static final class EdgeLine {
        // Negative side means west/north of the plane; positive means east/south.
        final List<Edge> negative = new ArrayList<>(), positive = new ArrayList<>();
    }

    private final SceneSurfaceSampler sampler;
    private final DhLodLayout layout;
    private final String identity;
    private final boolean footprintCoverage, stratifiedSides;
    private final long originX, originZ;

    /** Pins identity and validates the footprint without reading terrain. */
    public DhAdaptiveTerrainData(SceneSurfaceSampler sampler, long centerX, long centerZ) {
        this(sampler, centerX, centerZ, DhLodLayout.FIXED_512);
    }

    /** The immutable layout changes detail and requested extent, never source support or readiness. */
    public DhAdaptiveTerrainData(SceneSurfaceSampler sampler, long centerX, long centerZ, DhLodLayout layout) {
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.layout = Objects.requireNonNull(layout, "layout");
        identity = sampler.identity();footprintCoverage=sampler.supportsFootprintCoverage();stratifiedSides=sampler.supportsStratifiedSides();
        if (layout.requiresFootprintCoverage() && !footprintCoverage)
            throw new IllegalArgumentException("Long-distance summaries require complete footprint coverage");
        if (identity == null || identity.isBlank()) throw new IllegalArgumentException("Source/appearance identity required");
        originX = Math.subtractExact(layout.normalizeCenter(centerX), layout.radiusBlocks());
        originZ = Math.subtractExact(layout.normalizeCenter(centerZ), layout.radiusBlocks());
    }

    public Batch clip(List<CubePos> actualReadyCubes) {
        return clip(actualReadyCubes, actualReadyCubes);
    }

    /** A retained refinement profile may keep fine geometry while readiness is empty. */
    public Batch clip(List<CubePos> actualReadyCubes, List<CubePos> refinementFootprints) {
        return clip(actualReadyCubes, refinementFootprints, () -> {});
    }

    /** Cooperatively cancelled pure summary work; callers must supply a thread-safe sampler off-thread. */
    public Batch clip(List<CubePos> actualReadyCubes, List<CubePos> refinementFootprints, Runnable cancellationCheck) {
        return clip(actualReadyCubes, refinementFootprints, List.of(), cancellationCheck);
    }

    /** Exact replacement ownership remains separate from both readiness and refinement. */
    public Batch clip(List<CubePos> actualReadyCubes, List<CubePos> refinementFootprints,
                      List<DhTerrainGeometry.Volume> replacementVolumes, Runnable cancellationCheck) {
        return clipWithRefinementVolumes(actualReadyCubes,refinementFootprints,replacementVolumes,List.of(),cancellationCheck);
    }

    /** One explicit bounded immutable replacement read footprint; it refines, never owns terrain. */
    public Batch clipWithRefinementVolumes(List<CubePos> actualReadyCubes,List<CubePos> refinementFootprints,
                      List<DhTerrainGeometry.Volume> replacementVolumes,List<DhTerrainGeometry.Volume> readFootprints,Runnable cancellationCheck) {
        Objects.requireNonNull(cancellationCheck, "cancellationCheck").run();
        readFootprints=List.copyOf(Objects.requireNonNull(readFootprints));
        if(readFootprints.size()>1)throw new BudgetExceeded("Only one bounded replacement read footprint supported");
        for(var v:readFootprints)if(v.maxXExclusive()-v.minX()>20||v.maxYExclusive()-v.minY()>20||v.maxZExclusive()-v.minZ()>20)
            throw new BudgetExceeded("Replacement read footprint exceeds20 cubed");
        var ready = validateCubes(actualReadyCubes);
        var refinement = validateCubes(refinementFootprints);
        Objects.requireNonNull(replacementVolumes, "replacementVolumes");
        if ((long) actualReadyCubes.size() + replacementVolumes.size() > DhTerrainGeometry.MAX_READY_CUBES)
            throw new BudgetExceeded("Combined ownership clipping budget exceeded");
        var replacements = List.copyOf(replacementVolumes);
        var leaves = partitionLeaves(refinement, readFootprints, cancellationCheck);
        requireIdentity();
        var cells = new ArrayList<Cell>(leaves.size());
        int unknown = 0, refined = 0, centerSamples=0, footprintRejected=0;
        for (Leaf leaf : leaves) {
            cancellationCheck.run();
            if (leaf.size < CELL_SIZE) refined++;
            long x = leaf.x + leaf.size / 2, z = leaf.z + leaf.size / 2;
            // size/2 is zero for a metre leaf: sample the exact native block.
            boolean queried=false;
            try {
                if(footprintCoverage&&!sampler.knownFootprint(leaf.x,leaf.z,leaf.x+leaf.size,leaf.z+leaf.size)){unknown++;footprintRejected++;continue;}
                queried=true;centerSamples++;
                long y = sampler.surfaceY(x, z);
                if (y < DhTerrainGeometry.MIN_Y || y >= DhTerrainGeometry.MAX_Y_EXCLUSIVE)
                    throw new IllegalArgumentException("Source exceeds native Earth envelope");
                int argb = sampler.argb(x, z, y);
                if ((argb >>> 24) != 255) throw new IllegalArgumentException("Opaque far surface required");
                var materials=stratifiedSides?sampler.columnMaterials(x,z,y):null;
                if(stratifiedSides&&(materials==null||materials.top().argb()!=argb))throw new IllegalStateException("Pinned top/side material contract changed");
                cells.add(new Cell(leaf, y + 1, argb,materials));
            } catch (MissingTerrainException absent) {
                unknown++;if(!queried)footprintRejected++;
            }
        }
        requireIdentity();
        if (cells.isEmpty()) throw new IllegalArgumentException("No known far cells in bounded view");

        var faces = new ArrayList<SourceFace>();
        var xEdges = new TreeMap<Long, EdgeLine>();
        var zEdges = new TreeMap<Long, EdgeLine>();
        for (Cell cell : cells) {
            cancellationCheck.run();
            Leaf leaf = cell.leaf;
            append(faces, new DhTerrainGeometry.Face(DhTerrainGeometry.Direction.UP,
                    leaf.x, cell.top, leaf.z, leaf.size, leaf.size, cell.argb,cell.materials==null?0:cell.materials.top().dhMaterial()), leaf.size);
            addEdge(xEdges, leaf.x, new Edge(leaf.z, leaf.z + leaf.size, cell), false);
            addEdge(xEdges, leaf.x + leaf.size, new Edge(leaf.z, leaf.z + leaf.size, cell), true);
            addEdge(zEdges, leaf.z, new Edge(leaf.x, leaf.x + leaf.size, cell), false);
            addEdge(zEdges, leaf.z + leaf.size, new Edge(leaf.x, leaf.x + leaf.size, cell), true);
        }
        int transitionFaces = joinEdges(xEdges, true, faces, cancellationCheck) + joinEdges(zEdges, false, faces, cancellationCheck);
        var tiles = new LinkedHashMap<DhGeographicLodData.TileKey, List<DhTerrainGeometry.PageFace>>();
        int count = 0, changed = 0;
        for (var summary : faces) {
            cancellationCheck.run();
            var source = summary.face;
            var fragments = DhTerrainGeometry.clip(source, ready, replacements, DhTerrainGeometry.MAX_PIECES);
            if (fragments.size() != 1 || !fragments.getFirst().equals(source)) changed++;
            for (var face : fragments) {
                long tileX = Math.floorDiv(face.x() - (face.direction() == DhTerrainGeometry.Direction.EAST ? 1 : 0), summary.tileSize) * summary.tileSize;
                long tileZ = Math.floorDiv(face.z() - (face.direction() == DhTerrainGeometry.Direction.SOUTH ? 1 : 0), summary.tileSize) * summary.tileSize;
                for (var page : DhTerrainGeometry.splitPages(face, tileX, tileZ)) {
                    if (++count > MAX_FACES) throw new BudgetExceeded("Clipped far face budget exceeded");
                    var key = new DhGeographicLodData.TileKey(tileX, tileZ, page.pageY2048());
                    var list = tiles.computeIfAbsent(key, ignored -> new ArrayList<>());
                    if (tiles.size() > MAX_TILES || list.size() >= MAX_PAGE_FACES)
                        throw new BudgetExceeded("Far tile owner or per-page face budget exceeded");
                    list.add(page);
                }
            }
        }
        return new Batch(tiles, count, changed, leaves.size(), cells.size(), unknown,
                refined, faces.size(), transitionFaces,centerSamples,footprintRejected,footprintCoverage?cells.size():0);
    }

    /** Package-private proof surface: exactly the production partition, with no terrain callbacks. */
    record PartitionCounts(int sampledCells, int flatTileOwners, int maxFlatPageFaces) { }

    PartitionCounts partitionCounts(List<CubePos> refinementFootprints) {
        var leaves = partitionLeaves(validateCubes(refinementFootprints), () -> {});
        var owners = new LinkedHashMap<DhGeographicLodData.TileKey, Integer>();
        int largest = 0;
        for (var leaf : leaves) {
            int tileSize = layout.tileSizeForCell(leaf.size);
            var key = new DhGeographicLodData.TileKey(Math.floorDiv(leaf.x, tileSize) * tileSize,
                    Math.floorDiv(leaf.z, tileSize) * tileSize, 0);
            int count = owners.merge(key, 1, Integer::sum);
            largest = Math.max(largest, count);
        }
        // For a certified constant-height analytic source, every leaf is one UP face
        // on a single signed page. Exact one-metre refinement at all ready X/Z cubes
        // means clipping only removes these faces; it cannot increase their counts.
        return new PartitionCounts(leaves.size(), owners.size(), largest);
    }

    private List<Leaf> partitionLeaves(List<CubePos> refinement, Runnable cancellationCheck) {
        return partitionLeaves(refinement,List.of(),cancellationCheck);
    }
    private List<Leaf> partitionLeaves(List<CubePos> refinement,List<DhTerrainGeometry.Volume> readFootprints,Runnable cancellationCheck) {
        var footprints = new ArrayList<>(refinement.stream().map(cube -> new Footprint(cube.minBlockX() - 1,
                cube.minBlockZ() - 1, cube.minBlockX() + CubePos.SIZE + 1,
                cube.minBlockZ() + CubePos.SIZE + 1)).distinct().toList());
        for(var v:readFootprints)footprints.add(new Footprint(v.minX(),v.minZ(),v.maxXExclusive(),v.maxZExclusive()));

        // Finish the entire partition, or reject its budget, before provider reads.
        var leaves = new ArrayList<Leaf>(CELLS * CELLS);
        long centerX = originX + layout.radiusBlocks(), centerZ = originZ + layout.radiusBlocks();
        int innerRadius = 0;
        for (var ring : layout.rings()) {
            int radius = ring.radiusBlocks(), size = ring.cellSize();
            for (long z = centerZ - radius; z < centerZ + radius; z += size)
                for (long x = centerX - radius; x < centerX + radius; x += size) {
                    cancellationCheck.run();
                    // All boundaries align to this ring's dyadic grid. Never pad an
                    // extent, overlap an inner ring, or omit a partial boundary cell.
                    if (innerRadius > 0 && x >= centerX - innerRadius && x < centerX + innerRadius
                            && z >= centerZ - innerRadius && z < centerZ + innerRadius) continue;
                    partition(x, z, size, footprints, leaves);
                }
            innerRadius = radius;
        }
        return leaves;
    }

    private static List<CubePos> validateCubes(List<CubePos> cubes) {
        Objects.requireNonNull(cubes, "cubes");
        if (cubes.size() > DhTerrainGeometry.MAX_READY_CUBES)
            throw new BudgetExceeded("Ready cube clipping budget exceeded");
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
            if (leaves.size() == MAX_CELLS) throw new BudgetExceeded("Adaptive partition cell budget exceeded before sampling");
            leaves.add(new Leaf(x, z, size));
        }
    }

    private static void addEdge(Map<Long, EdgeLine> lines, long plane, Edge edge, boolean negativeSide) {
        EdgeLine line = lines.computeIfAbsent(plane, ignored -> new EdgeLine());
        (negativeSide ? line.negative : line.positive).add(edge);
    }

    /** Match opposite intervals, including every fine interval along a coarse edge. */
    private int joinEdges(Map<Long, EdgeLine> lines, boolean xAxis, List<SourceFace> faces, Runnable cancellationCheck) {
        int transitions = 0;
        for (var entry : lines.entrySet()) {
            cancellationCheck.run();
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
                    appendWall(faces,direction,xAxis?entry.getKey():start,xAxis?start:entry.getKey(),
                            Math.toIntExact(end-start),Math.min(a.cell.top,b.cell.top),high,
                            Math.min(a.cell.leaf.size,b.cell.leaf.size),xAxis?0.72f:0.85f);
                    if (a.cell.leaf.size != b.cell.leaf.size) transitions++;
                }
                if (a.end <= b.end) i++;
                if (b.end <= a.end) j++;
            }
        }
        return transitions;
    }

    private void appendWall(List<SourceFace> faces,DhTerrainGeometry.Direction direction,long x,long z,
            int width,long bottom,Cell high,int leafSize,float shade) {
        if(high.materials==null){
            append(faces,new DhTerrainGeometry.Face(direction,x,bottom,z,width,Math.toIntExact(high.top-bottom),shade(high.argb,shade)),leafSize);
            return;
        }
        // Each visible native voxel side uses the solid column's exact vertical palette:
        // top block at H, three subsurface blocks H-3..H-1, deep stone below H-3.
        long topBlock=high.top-1,deepEnd=topBlock-high.materials.subsurfaceDepth();
        long y=bottom;
        while(y<high.top){
            var material=y<deepEnd?high.materials.deep():y<topBlock?high.materials.subsurface():high.materials.top();
            long end=y<deepEnd?Math.min(high.top,deepEnd):y<topBlock?Math.min(high.top,topBlock):high.top;
            // Exact compact representation: erase only a boundary whose full material
            // (opaque colour AND pinned DH category) is identical on both sides.
            while(end<high.top){
                var next=end<topBlock?high.materials.subsurface():high.materials.top();
                if(!material.equals(next))break;
                end=end<topBlock?topBlock:high.top;
            }
            append(faces,new DhTerrainGeometry.Face(direction,x,y,z,width,Math.toIntExact(end-y),DhTerrainGeometry.stratifiedFaceArgb(direction,material.argb()),material.dhMaterial()),leafSize);
            y=end;
        }
    }

    private void append(List<SourceFace> faces, DhTerrainGeometry.Face face, int leafSize) {
        if (faces.size() == MAX_FACES) throw new BudgetExceeded("Source face budget exceeded");
        // Every leaf and ownership grid is globally dyadic. At a tier seam,
        // matched intervals inherit the finer side's grouping. A positive
        // normal belongs to the solid-side tile even at its endpoint.
        faces.add(new SourceFace(face, layout.tileSizeForCell(leafSize)));
    }

    private static int shade(int color, float factor) {
        return 0xff000000 | Math.round(((color >>> 16) & 255) * factor) << 16
                | Math.round(((color >>> 8) & 255) * factor) << 8 | Math.round((color & 255) * factor);
    }

    private void requireIdentity() {
        if (!identity.equals(sampler.identity())||footprintCoverage!=sampler.supportsFootprintCoverage()||stratifiedSides!=sampler.supportsStratifiedSides()) throw new IllegalStateException("Source identity changed during adaptive summary");
    }

    public String identity() { return identity; }
    public DhLodLayout layout() { return layout; }
    public String layoutIdentity() { return layout.identity(); }
    public long originX() { return originX; }
    public long originZ() { return originZ; }
}
