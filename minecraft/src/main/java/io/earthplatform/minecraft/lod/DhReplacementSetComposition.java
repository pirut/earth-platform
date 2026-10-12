package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.lod.DhGeographicLodData.TileKey;
import io.earthplatform.minecraft.lod.DhReplacementComposition.Declined;
import io.earthplatform.minecraft.lod.DhReplacementComposition.Result;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Face;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.PageFace;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact union of two bounded analytic regions. Overlapping collars share one solid-voxel owner. */
public final class DhReplacementSetComposition {
    public static final int MAX_REGIONS = 2;
    public static final int MAX_NEAR_CUBES = 27;
    /** Exhaustive 4,096-template production-partition/clip proof, X/Z centers [-32,31]. */
    public static final int MAX_ANALYTIC_LEAVES = 11587;
    public static final int MAX_ANALYTIC_BASE_FACES = 11595;
    public static final int MAX_ANALYTIC_BASE_OWNERS = 41;
    public static final int MAX_ANALYTIC_BASE_PAGE_FACES = 2135;
    public static final int MAX_ANALYTIC_BASE_PIECES = 3;
    private record Bucket(long x, long z, int page) { }
    private record Visible(DhReplacementInput.Data region, Face face, Bucket bucket) { }

    public record RegionCensus(CubePos editedCube, int sourceFaces, int pieceOwners,
                               int largestOwnerFaces, long captureBytes) { }
    /** Conservative full-envelope reservation; it never relies on near-hidden replacement faces. */
    public record Census(List<RegionCensus> regions, int replacementSourceFaces,
                         int replacementPieceOwners, int largestReplacementOwnerFaces,
                         long captureBytes, int faceUpperBound, int ownerUpperBound,
                         long preparedBytesUpperBound) {
        public Census { regions = List.copyOf(regions); }
        public int baseBoundFaces() { return MAX_ANALYTIC_BASE_FACES; }
        public int baseBoundOwners() { return MAX_ANALYTIC_BASE_OWNERS; }
        public int sourceFaces() { return replacementSourceFaces; }
        public int pieceOwners() { return replacementPieceOwners; }
        public long resultBytes() { return preparedBytesUpperBound; }
        /** Any previously admitted full-cap publication plus this candidate; failed-retirement extras remain physical. */
        public long unionBytes() {
            return Math.multiplyExact(Math.addExact((long) DhAdaptiveTerrainData.MAX_FACES, faceUpperBound), 88L);
        }
        public int unionOwners() { return Math.addExact(DhAdaptiveTerrainData.MAX_TILES, ownerUpperBound); }
        public boolean fits() {
            return faceUpperBound <= DhAdaptiveTerrainData.MAX_FACES
                    && ownerUpperBound <= DhAdaptiveTerrainData.MAX_TILES
                    && largestReplacementOwnerFaces <= DhAdaptiveTerrainData.MAX_PAGE_FACES
                    && preparedBytesUpperBound <= DhReplacementComposition.MAX_RESULT_BYTES;
        }
    }
    private DhReplacementSetComposition() { }

    /**
     * Source-free exact voxel-face census before save/admission. Reads immutable captures only;
     * allocates no face objects and never calls a terrain provider, sampler, renderer, or GL.
     * The host remains responsible for authoritative captures/certificates and allowed movement.
     */
    public static Census inspect(DhAnalyticFlatPolicy policy, List<DhReplacementInput.Data> replacements,
                                 Runnable cancellationCheck) {
        Objects.requireNonNull(cancellationCheck, "cancellation check").run();
        var ordered = canonical(policy, replacements);
        return inspectCaptures(policy, ordered.stream().map(DhReplacementInput.Data::capture).toList(), cancellationCheck);
    }

    /** Same pre-save census, before a new authoritative certificate has been issued. */
    public static Census inspectCaptures(DhAnalyticFlatPolicy policy,
                                        List<DhVoxelReplacementGeometry.Capture> captures,
                                        Runnable cancellationCheck) {
        Objects.requireNonNull(cancellationCheck, "cancellation check").run();
        validateCaptures(policy, captures, cancellationCheck);
        var ordered = captures.stream().sorted(Comparator.comparing(DhVoxelReplacementGeometry.Capture::editedCube)).toList();
        var regions = new ArrayList<RegionCensus>();
        int sourceFaces = 0, owners = 0, largest = 0;
        long captureBytes = 0;
        for (var capture : ordered) {
            var owned = capture.ownedVolume();
            var buckets = new LinkedHashMap<Bucket, Integer>(); int count = 0;
            for (long y = owned.minY(); y < owned.maxYExclusive(); y++)
                for (long z = owned.minZ(); z < owned.maxZExclusive(); z++)
                    for (long x = owned.minX(); x < owned.maxXExclusive(); x++) {
                        cancellationCheck.run();
                        if (!owns(ordered, capture, x, y, z) || capture.argbAt(x, y, z) == 0) continue;
                        int exposed = 0;
                        if (capture.argbAt(x - 1, y, z) == 0) exposed++;
                        if (capture.argbAt(x + 1, y, z) == 0) exposed++;
                        if (capture.argbAt(x, y - 1, z) == 0) exposed++;
                        if (capture.argbAt(x, y + 1, z) == 0) exposed++;
                        if (capture.argbAt(x, y, z - 1) == 0) exposed++;
                        if (capture.argbAt(x, y, z + 1) == 0) exposed++;
                        if (exposed > 0) buckets.merge(bucket(x, y, z), exposed, Integer::sum);
                        count = Math.addExact(count, exposed);
                    }
            int pieces = 0, max = 0;
            for (int faces : buckets.values()) {
                pieces += Math.ceilDiv(faces, DhAdaptiveTerrainData.MAX_PAGE_FACES);
                max = Math.max(max, Math.min(faces, DhAdaptiveTerrainData.MAX_PAGE_FACES));
            }
            regions.add(new RegionCensus(capture.editedCube(), count, pieces, max, capture.declaredBytes()));
            sourceFaces = Math.addExact(sourceFaces, count); owners = Math.addExact(owners, pieces);
            largest = Math.max(largest, max); captureBytes = Math.addExact(captureBytes, capture.declaredBytes());
        }
        int faces = Math.addExact(MAX_ANALYTIC_BASE_FACES, sourceFaces);
        int allOwners = Math.addExact(MAX_ANALYTIC_BASE_OWNERS, owners);
        long bytes = Math.addExact(DhReplacementComposition.RESULT_OVERHEAD_BYTES,
                Math.addExact(captureBytes, Math.multiplyExact((long) faces, DhReplacementComposition.DECLARED_BYTES_PER_FACE)));
        cancellationCheck.run();
        return new Census(regions, sourceFaces, owners, largest, captureBytes, faces, allOwners, bytes);
    }

    public static Result build(DhAnalyticFlatPolicy policy, long centerX, long centerZ, DhLodLayout layout,
                               List<CubePos> ready, List<DhReplacementInput.Data> replacements,
                               long maxBytes, Runnable cancellationCheck) {
        Objects.requireNonNull(cancellationCheck, "cancellation check").run();
        if (layout != DhLodLayout.LONG_4096) throw new Declined("Two-region analytic composition requires LONG_4096");
        if (maxBytes < 0 || maxBytes > DhReplacementComposition.MAX_RESULT_BYTES)
            throw new Declined("Prepared-result byte budget exceeds the existing 5 MiB cap");
        var ordered = canonical(policy, replacements);
        if (ordered.size() != MAX_REGIONS) throw new Declined("Both predefined regions, including clean revision zero, are required");
        ready = validateReady(ready);
        requireAdmittedCenter(centerX, centerZ, ready);
        var census = inspect(policy, ordered, cancellationCheck);
        if (!census.fits() || census.preparedBytesUpperBound() > maxBytes)
            throw new Declined("Two-region full-envelope face/owner/byte reservation exceeds existing pooled caps");
        var volumes = ordered.stream().map(d -> d.capture().ownedVolume()).toList();
        for (var volume : volumes) {
            long cx = layout.normalizeCenter(centerX), cz = layout.normalizeCenter(centerZ), radius = layout.radiusBlocks();
            if (volume.minX() < cx - radius || volume.maxXExclusive() > cx + radius
                    || volume.minZ() < cz - radius || volume.maxZExclusive() > cz + radius)
                throw new Declined("Exact replacement owned collar lies outside the requested far view");
        }
        final DhAdaptiveTerrainData.Batch base;
        try {
            // The explicit exact-flat capability makes coarse subtraction exact. Retired
            // regions add XYZ ownership only, never sampler refinement or native readiness.
            base = new DhAdaptiveTerrainData(policy.constantSampler(), centerX, centerZ, layout)
                    .clip(ready, ready, volumes, cancellationCheck);
        } catch (DhAdaptiveTerrainData.BudgetExceeded exceeded) {
            throw new Declined(exceeded.getMessage(), exceeded);
        }
        requirePooled(base.faces(), base.tiles().size(), census.captureBytes(), maxBytes);
        var captures = ordered.stream().map(DhReplacementInput.Data::capture).toList();
        var readySet = new HashSet<>(ready);
        var visible = new ArrayList<Visible>();
        var bucketCounts = new LinkedHashMap<String, Map<Bucket, Integer>>();
        int sourceFaces = 0, replacementOwners = 0;
        for (var data : ordered) {
            var mesh = DhVoxelReplacementGeometry.build(data.capture(), DhVoxelReplacementGeometry.MAX_FACES,
                    DhVoxelReplacementGeometry.MAX_VERTEX_BYTES, cancellationCheck);
            var counts = new LinkedHashMap<Bucket, Integer>(); bucketCounts.put(data.identity(), counts);
            for (var face : mesh.faces()) {
                cancellationCheck.run();
                long x = solidX(face), y = solidY(face), z = solidZ(face);
                if (!owns(captures, data.capture(), x, y, z)) continue;
                sourceFaces = Math.addExact(sourceFaces, 1);
                if (readySet.contains(CubePos.fromBlock(x, y, z))) continue;
                Bucket bucket = bucket(x, y, z);
                int index = counts.getOrDefault(bucket, 0);
                if (index % DhAdaptiveTerrainData.MAX_PAGE_FACES == 0) replacementOwners++;
                counts.put(bucket, index + 1);
                requirePooled(base.faces() + visible.size() + 1, base.tiles().size() + replacementOwners,
                        census.captureBytes(), maxBytes);
                visible.add(new Visible(data, face, bucket));
            }
        }
        if (sourceFaces != census.replacementSourceFaces()) throw new AssertionError("Immutable source-face census mismatch");
        var tiles = new LinkedHashMap<TileKey, List<PageFace>>(base.tiles());
        bucketCounts.values().forEach(Map::clear);
        for (var value : visible) {
            cancellationCheck.run();
            var bucket = value.bucket(); var counts = bucketCounts.get(value.region().identity());
            int index = counts.getOrDefault(bucket, 0);
            var key = new TileKey(bucket.x(), bucket.z(), bucket.page(), value.region().identity(),
                    index / DhAdaptiveTerrainData.MAX_PAGE_FACES);
            var pages = DhTerrainGeometry.splitPages(value.face(), bucket.x(), bucket.z());
            if (pages.size() != 1) throw new AssertionError("Unit replacement face crosses signed pages");
            tiles.computeIfAbsent(key, ignored -> new ArrayList<>()).add(pages.getFirst());
            counts.put(bucket, index + 1);
        }
        var combined = new DhAdaptiveTerrainData.Batch(tiles, base.faces() + visible.size(),
                base.clippedSourceFaces() + sourceFaces - visible.size(), base.sampledCells(), base.knownCells(),
                base.unknownCells(), base.refinedCells(), base.sourceFaces() + sourceFaces, base.transitionFaces(),
                base.centerSampledCells(), base.footprintRejectedCells(), base.fullySupportedCells());
        cancellationCheck.run();
        return new Result(combined, sourceFaces, visible.size(), replacementOwners);
    }

    private static List<DhReplacementInput.Data> canonical(DhAnalyticFlatPolicy policy, List<DhReplacementInput.Data> values) {
        Objects.requireNonNull(policy, "explicit analytic policy"); Objects.requireNonNull(values, "replacement regions");
        if (values.size() > MAX_REGIONS) throw new Declined("At most two predefined replacement regions");
        var result = new ArrayList<DhReplacementInput.Data>();
        for (var data : values) {
            Objects.requireNonNull(data, "replacement data"); var certificate = data.certificate(); var cube = data.capture().editedCube();
            if (!policy.providerIdentity().equals(certificate.world())) throw new Declined("Replacement provider identity differs from analytic policy");
            if (!DhReplacementInput.ANALYTIC_MATERIAL_POLICY.equals(certificate.materialPolicy())) throw new Declined("Unsupported replacement material policy");
            requireRegion(policy, cube);
            for (var prior : result) {
                if (prior.capture().editedCube().equals(cube)) throw new Declined("Duplicate replacement region");
                if (prior.certificate().epoch() != certificate.epoch()
                        || prior.certificate().resourceGeneration() != certificate.resourceGeneration())
                    throw new Declined("Replacement set mixes session/resource generations");
            }
            result.add(data);
        }
        result.sort(Comparator.comparing(d -> d.capture().editedCube()));
        if (result.size() == 2 && result.get(1).capture().editedCube().x() == 1) {
            var a = result.get(0).certificate(); var b = result.get(1).certificate();
            if (!a.identity().equals(b.identity()) || a.summaryGeneration() != b.summaryGeneration())
                throw new Declined("Adjacent replacement members must bind one complete set certificate");
        }
        return List.copyOf(result);
    }
    /**
     * Source-free, face-allocation-free validation of a complete bounded capture set.
     * Every shared read voxel must agree, including known air, material and padding.
     * Only the union of the two actual editable cubes may depart from the certified
     * flat support; a neighboring edit inside another member's halo is legitimate.
     */
    public static void validateCaptures(DhAnalyticFlatPolicy policy,
                                        List<DhVoxelReplacementGeometry.Capture> captures,
                                        Runnable cancellationCheck) {
        Objects.requireNonNull(cancellationCheck, "cancellation check").run();
        Objects.requireNonNull(policy, "explicit analytic policy"); Objects.requireNonNull(captures, "captures");
        if (captures.size() != MAX_REGIONS) throw new Declined("Both predefined replacement captures are required");
        var ordered = captures.stream().map(c -> Objects.requireNonNull(c, "capture"))
                .sorted(Comparator.comparing(DhVoxelReplacementGeometry.Capture::editedCube)).toList();
        var first = ordered.get(0).editedCube(); var second = ordered.get(1).editedCube();
        requireRegion(policy, first); requireRegion(policy, second);
        if (first.x() != 0 || (second.x() != 1 && second.x() != 4))
            throw new Declined("Replacement set must be original plus adjacent +1 or legacy +4 region");
        for (var capture : ordered) {
            var b = capture.readBounds();
            for (long y = b.minY(); y < b.maxYExclusive(); y++)
                for (long z = b.minZ(); z < b.maxZExclusive(); z++)
                    for (long x = b.minX(); x < b.maxXExclusive(); x++) {
                        cancellationCheck.run();
                        int color = capture.argbAt(x, y, z);
                        if (color != 0 && color != policy.opaqueArgb() && color != 0xffdedede)
                            throw new Declined("Replacement contains an unsupported analytic palette value");
                        if (!editable(first, x, y, z) && !editable(second, x, y, z)
                                && color != (y <= policy.surfaceY() ? policy.opaqueArgb() : 0))
                            throw new Declined("Replacement read halo is not certified flat outside the editable union");
                    }
        }
        var a = ordered.get(0); var b = ordered.get(1);
        var ar = a.readBounds(); var br = b.readBounds();
        for (long y = Math.max(ar.minY(), br.minY()); y < Math.min(ar.maxYExclusive(), br.maxYExclusive()); y++)
            for (long z = Math.max(ar.minZ(), br.minZ()); z < Math.min(ar.maxZExclusive(), br.maxZExclusive()); z++)
                for (long x = Math.max(ar.minX(), br.minX()); x < Math.min(ar.maxXExclusive(), br.maxXExclusive()); x++) {
                    cancellationCheck.run();
                    if (a.argbAt(x, y, z) != b.argbAt(x, y, z))
                        throw new Declined("Replacement read captures disagree in their overlap at " + x + "," + y + "," + z);
                }
        cancellationCheck.run();
    }
    private static boolean editable(CubePos cube, long x, long y, long z) {
        return x >= cube.minBlockX() && x < cube.minBlockX() + 16
                && y >= cube.minBlockY() && y < cube.minBlockY() + 16
                && z >= cube.minBlockZ() && z < cube.minBlockZ() + 16;
    }
    /** Canonical first owned volume containing the solid voxel owns all six possible faces. */
    private static boolean owns(List<DhVoxelReplacementGeometry.Capture> ordered,
                                DhVoxelReplacementGeometry.Capture capture, long x, long y, long z) {
        for (var candidate : ordered) if (candidate.ownedVolume().contains(x, y, z)) return candidate == capture;
        throw new AssertionError("Replacement solid outside its owned union");
    }
    private static void requireRegion(DhAnalyticFlatPolicy policy, CubePos cube) {
        if ((cube.x() != 0 && cube.x() != 1 && cube.x() != 4) || cube.z() != 0 || cube.y() != Math.floorDiv(policy.surfaceY(), 16))
            throw new Declined("Replacement lies outside the predefined analytic regions");
    }
    private static void requireAdmittedCenter(long x, long z, List<CubePos> ready) {
        if (!axisAdmitted(x, ready, true) || !axisAdmitted(z, ready, false))
            throw new Declined("Far center/near footprint lies outside the proved 4096 analytic templates");
    }
    private static boolean axisAdmitted(long far, List<CubePos> ready, boolean xAxis) {
        for (int c = -32; c <= 31; c++) {
            if (Math.floorDiv(c * 16L + 136, 256) * 256 != far) continue;
            boolean fits = true;
            for (var p : ready) {
                long coordinate = xAxis ? p.x() : p.z();
                if (coordinate < c - 1 || coordinate > c + 1) { fits = false; break; }
            }
            if (fits) return true;
        }
        return false;
    }
    private static List<CubePos> validateReady(List<CubePos> values) {
        Objects.requireNonNull(values, "actual ready cubes");
        if (values.isEmpty()) return List.of();
        if (values.size() != 27 && values.size() != 18)
            throw new Declined("Two-region mode needs a complete 27-target or endpoint-eligible 18-target near window");
        var ready = values.stream().map(Objects::requireNonNull).distinct().sorted().toList();
        if (ready.size() != values.size()) throw new Declined("Actual-ready window contains duplicate cubes");
        int minX = ready.stream().mapToInt(CubePos::x).min().orElseThrow();
        int minY = ready.stream().mapToInt(CubePos::y).min().orElseThrow();
        int minZ = ready.stream().mapToInt(CubePos::z).min().orElseThrow();
        long x = (long) minX + 1, z = (long) minZ + 1;
        if (x < -32 || x > 31 || z < -32 || z > 31)
            throw new Declined("Near center lies outside the proved 4096 analytic templates");
        // Exactly one candidate matches, including both clipped endpoint masks.
        for (long centerY = (long) minY - 1; centerY <= (long) minY + 1; centerY++) {
            if (centerY < -688 || centerY > 553) continue;
            var expected = new ArrayList<CubePos>();
            for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                var p = new CubePos((int) x + dx, (int) centerY + dy, (int) z + dz);
                if (p.minBlockY() >= DhTerrainGeometry.MIN_Y && p.minBlockY() + 16 <= DhTerrainGeometry.MAX_Y_EXCLUSIVE)
                    expected.add(p);
            }
            expected.sort(Comparator.naturalOrder());
            if (expected.equals(ready)) return ready;
        }
        throw new Declined("Ready masks must be one complete eligible near window without a pin");
    }
    private static void requirePooled(int faces, int owners, long captureBytes, long maxBytes) {
        long bytes = Math.addExact(DhReplacementComposition.RESULT_OVERHEAD_BYTES,
                Math.addExact(captureBytes, Math.multiplyExact((long) faces, DhReplacementComposition.DECLARED_BYTES_PER_FACE)));
        if (faces > DhAdaptiveTerrainData.MAX_FACES || owners > DhAdaptiveTerrainData.MAX_TILES || bytes > maxBytes)
            throw new Declined("Combined two-region face/owner/byte budget exceeded");
    }
    private static Bucket bucket(long x, long y, long z) {
        return new Bucket(Math.floorDiv(x, 32) * 32, Math.floorDiv(z, 32) * 32, Math.toIntExact(Math.floorDiv(y, 2048)));
    }
    private static long solidX(Face face) { return face.x() - (face.direction() == Direction.EAST ? 1 : 0); }
    private static long solidY(Face face) { return face.y() - (face.direction() == Direction.UP ? 1 : 0); }
    private static long solidZ(Face face) { return face.z() - (face.direction() == Direction.SOUTH ? 1 : 0); }
}
