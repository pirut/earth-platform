package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.lod.DhAdaptiveTerrainData.Batch;
import io.earthplatform.minecraft.lod.DhGeographicLodData.TileKey;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Face;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.PageFace;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact base/replacement/near-ready composition before official DH packing or any upload. */
public final class DhReplacementComposition {
    public static final long RESULT_OVERHEAD_BYTES = 4096;
    public static final long DECLARED_BYTES_PER_FACE = 128;
    public static final long MAX_RESULT_BYTES = 5L * 1024 * 1024;

    /** Known unsupported policy/coverage or bounded-resource refusal; never an arbitrary source fault. */
    public static final class Declined extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public Declined(String message) { super(message); }
        public Declined(String message, Throwable cause) { super(message, cause); }
    }
    public record Result(Batch batch, int replacementSourceFaces, int replacementVisibleFaces, int replacementOwners) {
        public Result { Objects.requireNonNull(batch, "batch"); }
    }
    private record Bucket(long originX, long originZ, int pageY) { }
    private DhReplacementComposition() { }

    /**
     * The one replacement shares all existing publication pools with the base.
     * A null replacement preserves the legacy batch exactly. Replacement content
     * is already immutable; this class does not assert certificate currency.
     */
    public static Result build(SceneSurfaceSampler sampler, long centerX, long centerZ, DhLodLayout layout,
                               List<CubePos> ready, DhReplacementInput.Data replacement,
                               long maxBytes, Runnable cancellationCheck) {
        Objects.requireNonNull(cancellationCheck, "cancellationCheck").run();
        if (layout == DhLodLayout.EARTH_ALPINE_2048 || replacement != null && replacement.capture().earthCapture()!=null)
            return DhEarthReplacementComposition.build(sampler,centerX,centerZ,layout,ready,replacement,maxBytes,cancellationCheck);
        Objects.requireNonNull(sampler, "sampler"); Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(ready, "ready");
        if (ready.size() > DhTerrainGeometry.MAX_READY_CUBES) throw new Declined("Ready cube clipping budget exceeded");
        ready = List.copyOf(ready);
        if (maxBytes < 0 || maxBytes > MAX_RESULT_BYTES) throw new Declined("Prepared-result byte budget exceeds the existing 5 MiB cap");
        long inputBytes = replacement == null ? 0 : replacement.declaredBytes();
        requirePooledBytes(0, inputBytes, maxBytes);
        if (replacement != null) {
            if (!DhReplacementInput.ANALYTIC_MATERIAL_POLICY.equals(replacement.certificate().materialPolicy()))
                throw new Declined("Unsupported replacement material policy");
            requireInView(replacement.capture().ownedVolume(), centerX, centerZ, layout);
        }
        if (layout.requiresFootprintCoverage() && !sampler.supportsFootprintCoverage())
            throw new Declined("Long-distance summaries require complete footprint coverage");

        // Preserve refinement at the edited cube after its native pin has retired.
        var refinement = new ArrayList<>(ready);
        if (replacement != null && !refinement.contains(replacement.capture().editedCube())) refinement.add(replacement.capture().editedCube());
        List<DhTerrainGeometry.Volume> volumes = replacement == null ? List.of() : List.of(replacement.capture().ownedVolume());
        final Batch base;
        try {
            base = new DhAdaptiveTerrainData(sampler, centerX, centerZ, layout).clip(ready, refinement, volumes, cancellationCheck);
        } catch (DhAdaptiveTerrainData.BudgetExceeded budget) {
            throw new Declined(budget.getMessage(), budget);
        }
        requirePooledBytes(base.faces(), inputBytes, maxBytes);
        if (replacement == null) {
            cancellationCheck.run();
            return new Result(base, 0, 0, 0);
        }

        long baseBytes = Math.addExact(Math.addExact(RESULT_OVERHEAD_BYTES, inputBytes),
                Math.multiplyExact((long) base.faces(), DECLARED_BYTES_PER_FACE));
        int remainingFaces = Math.toIntExact(Math.min(DhAdaptiveTerrainData.MAX_FACES - base.faces(),
                (maxBytes - baseBytes) / DECLARED_BYTES_PER_FACE));
        final DhVoxelReplacementGeometry.Mesh mesh;
        try {
            // Conservatively reserve every source face before allocation, even
            // if native-ready clipping would later remove some of those faces.
            mesh = DhVoxelReplacementGeometry.build(replacement.capture(), remainingFaces,
                    Math.multiplyExact((long) remainingFaces, DhVoxelReplacementGeometry.BYTES_PER_FACE), cancellationCheck);
        } catch (DhVoxelReplacementGeometry.BudgetExceeded budget) {
            throw new Declined("Combined base/replacement face or byte budget exceeded", budget);
        }
        var readySet = new HashSet<>(ready);
        var visible = new ArrayList<Face>();
        var counts = new LinkedHashMap<Bucket, Integer>();
        int replacementOwners = 0;
        for (Face face : mesh.faces()) {
            cancellationCheck.run();
            // Faces are exact unit quads. The solid's cube is the full XYZ
            // clipping oracle, including positive-normal boundary ownership.
            if (readySet.contains(solidCube(face))) continue;
            int totalFaces = Math.addExact(base.faces(), visible.size() + 1);
            if (totalFaces > DhAdaptiveTerrainData.MAX_FACES) throw new Declined("Combined base/replacement face budget exceeded");
            requirePooledBytes(totalFaces, inputBytes, maxBytes);
            Bucket bucket = bucket(face);
            int count = counts.getOrDefault(bucket, 0);
            if (count % DhAdaptiveTerrainData.MAX_PAGE_FACES == 0) {
                replacementOwners++;
                if (base.tiles().size() + replacementOwners > DhAdaptiveTerrainData.MAX_TILES)
                    throw new Declined("Combined base/replacement owner budget exceeded");
            }
            counts.put(bucket, count + 1);
            visible.add(face);
        }

        // All combined face/owner/byte counts are checked before page allocations.
        var tiles = new LinkedHashMap<TileKey, List<PageFace>>(base.tiles());
        var emitted = new LinkedHashMap<Bucket, Integer>();
        for (Face face : visible) {
            cancellationCheck.run();
            Bucket bucket = bucket(face);
            int index = emitted.getOrDefault(bucket, 0);
            int piece = index / DhAdaptiveTerrainData.MAX_PAGE_FACES;
            var key = new TileKey(bucket.originX, bucket.originZ, bucket.pageY, replacement.identity(), piece);
            var pages = DhTerrainGeometry.splitPages(face, bucket.originX, bucket.originZ);
            if (pages.size() != 1) throw new AssertionError("A unit face cannot cross signed pages");
            tiles.computeIfAbsent(key, ignored -> new ArrayList<>()).add(pages.getFirst());
            emitted.put(bucket, index + 1);
        }
        int visibleCount = visible.size();
        Batch combined = new Batch(tiles, base.faces() + visibleCount,
                base.clippedSourceFaces() + mesh.faceCount() - visibleCount, base.sampledCells(), base.knownCells(),
                base.unknownCells(), base.refinedCells(), base.sourceFaces() + mesh.faceCount(), base.transitionFaces(),
                base.centerSampledCells(), base.footprintRejectedCells(), base.fullySupportedCells());
        cancellationCheck.run();
        return new Result(combined, mesh.faceCount(), visibleCount, replacementOwners);
    }

    private static void requireInView(DhTerrainGeometry.Volume owned, long centerX, long centerZ, DhLodLayout layout) {
        long cx = layout.normalizeCenter(centerX), cz = layout.normalizeCenter(centerZ);
        long radius = layout.radiusBlocks();
        if (owned.minX() < cx - radius || owned.maxXExclusive() > cx + radius
                || owned.minZ() < cz - radius || owned.maxZExclusive() > cz + radius)
            throw new Declined("Exact replacement owned collar is outside the requested far view");
    }
    private static void requirePooledBytes(int faces, long inputBytes, long maxBytes) {
        long bytes = Math.addExact(Math.addExact(RESULT_OVERHEAD_BYTES, inputBytes),
                Math.multiplyExact((long) faces, DECLARED_BYTES_PER_FACE));
        if (bytes > maxBytes) throw new Declined("Combined prepared-result byte budget exceeded: " + bytes + " > " + maxBytes);
    }
    private static CubePos solidCube(Face face) {
        return CubePos.fromBlock(solidX(face), solidY(face), solidZ(face));
    }
    private static Bucket bucket(Face face) {
        return new Bucket(Math.multiplyExact(Math.floorDiv(solidX(face), 32), 32),
                Math.multiplyExact(Math.floorDiv(solidZ(face), 32), 32), Math.toIntExact(Math.floorDiv(solidY(face), 2048)));
    }
    private static long solidX(Face face) { return face.x() - (face.direction() == Direction.EAST ? 1 : 0); }
    private static long solidY(Face face) { return face.y() - (face.direction() == Direction.UP ? 1 : 0); }
    private static long solidZ(Face face) { return face.z() - (face.direction() == Direction.SOUTH ? 1 : 0); }
}
