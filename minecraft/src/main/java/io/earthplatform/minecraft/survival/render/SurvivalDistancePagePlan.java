package io.earthplatform.minecraft.survival.render;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable, bounded selected-distance CONTENT, never a publication or replacement capability.
 * Native ViewArea ownership is the inclusive horizontal cube square [focus-distance, focus+distance].
 * Signed 64 m pages merely batch its exact 16 m members. Camera/frustum/depth eligibility, complete
 * native uploads, current revisions, and the exact DH complement require separate owner proofs.
 *
 * Ground tops and cardinal cliffs are planned independently from procedural trees. In particular,
 * foliage does not hide terrain here and a tree member is never evidence of opaque ground coverage.
 * An owner-certified complete durable + live overlay index must bind CompleteEditSet before use.
 * Neither this class nor its public content constructors can certify the absence of other edits.
 */
public final class SurvivalDistancePagePlan {
    public static final int MIN_DISTANCE = 2, MAX_DISTANCE = 32;
    private static final long BASE_METADATA_BYTES = 2048;
    // Explicit logical allocation admission, including construction/canonicalization allowance.
    // These are not a measured Java heap, mesh/section payload, model, or GPU-memory estimate.
    private static final long PAGE_METADATA_BYTES = 768, EDIT_METADATA_BYTES = 96;
    private static final long TREE_QUERY_SCRATCH_BYTES = 65536;
    private static final int[][] FACE_NEIGHBORS = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0},
            {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    public static final class Refusal extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public Refusal(String message) { super(message); }
        public Refusal(String message, Throwable cause) { super(message, cause); }
    }

    /** A cap is recorded, never invented: zero means uncapped, otherwise effective=min(selected,cap). */
    public record DistanceChoice(int selectedChunks, int effectiveChunks, int effectiveCapChunks) {
        public DistanceChoice {
            distance(selectedChunks); distance(effectiveChunks);
            if (effectiveCapChunks != 0) distance(effectiveCapChunks);
            int expected = effectiveCapChunks == 0 ? selectedChunks : Math.min(selectedChunks, effectiveCapChunks);
            if (effectiveChunks != expected) throw new Refusal("Effective native distance requires its explicit legitimate cap");
        }
        public boolean selectedDistanceEffective() { return selectedChunks == effectiveChunks; }
    }

    /** All aggregate limits are supplied explicitly; sparse planning never reduces selected distance. */
    public record Limits(long maxSampledColumns, long maxHorizontalCubes, long maxPages,
            long maxMembers, long maxDependencyReferences, long maxHaloReferences,
            long maxEditedCubes, long maxMetadataBytes) {
        public Limits {
            if (maxSampledColumns < 1 || maxHorizontalCubes < 1 || maxPages < 1 || maxMembers < 1
                    || maxDependencyReferences < 1 || maxHaloReferences < 1
                    || maxEditedCubes < 0 || maxMetadataBytes < 1)
                throw new Refusal("Every planner limit must be explicit and positive; edited-cube limit may be zero");
        }
    }

    /**
     * Caller-attested complete content index, including unloaded edits and explicit AIR tombstones.
     * Identity describes coherent complete content, including removals. The revision is an
     * owner-local invalidation hint; equal counters never prove membership or currentness, and
     * counters are deliberately excluded from the semantic index hash.
     * This is NOT an owner attestation. Production must bind the exact set/revision/content to the
     * authoritative durable + live index and reject it if any part changed before publication.
     */
    public static final class CompleteEditSet {
        private final long contentRevision;
        private final String contentIdentity, indexIdentity;
        private final Set<CubePos> modifiedCubes;

        private CompleteEditSet(long revision, String contentIdentity, Set<CubePos> cubes, Limits limits) {
            if (revision < 0) throw new Refusal("Negative complete overlay revision");
            this.contentIdentity = identity(contentIdentity);
            Objects.requireNonNull(cubes, "complete modified cube index");
            Objects.requireNonNull(limits, "limits");
            long bytes = editBytes(cubes.size());
            if (cubes.size() > limits.maxEditedCubes || bytes > limits.maxMetadataBytes)
                throw new Refusal("Complete edit index exceeds admission before copying");
            var copy = new TreeSet<CubePos>();
            for (CubePos cube : cubes) {
                if (copy.size() >= limits.maxEditedCubes || editBytes(copy.size() + 1L) > limits.maxMetadataBytes)
                    throw new Refusal("Complete edit index changed or exceeded admission during copying");
                if (!copy.add(Objects.requireNonNull(cube, "modified cube")))
                    throw new Refusal("Complete edit index repeated a cube during copying");
            }
            if (copy.size() != cubes.size()) throw new Refusal("Complete edit index changed while being copied");
            modifiedCubes = Collections.unmodifiableSet(copy);
            contentRevision = revision;
            MessageDigest digest = digest();
            // The counter is capture/currentness metadata, not semantic content. Exact owner
            // validation remains mandatory even when this identity matches.
            update(digest, "complete-overlay-content-only-039-v2\n" + contentIdentity + "\n");
            for (CubePos cube : modifiedCubes) update(digest, cube + "\n");
            indexIdentity = finish(digest);
        }

        public static CompleteEditSet copyOf(long contentRevision, String contentIdentity,
                Set<CubePos> completeModifiedCubes, Limits limits) {
            try { return new CompleteEditSet(contentRevision, contentIdentity, completeModifiedCubes, limits); }
            catch (ArithmeticException overflow) { throw new Refusal("Complete edit index metadata overflow", overflow); }
        }
        public long contentRevision() { return contentRevision; }
        public String contentIdentity() { return contentIdentity; }
        public String indexIdentity() { return indexIdentity; }
        public Set<CubePos> modifiedCubes() { return modifiedCubes; }
    }

    /** Exact half-open selected block rectangle; its edges are 16 m, never rounded to 64 m pages. */
    public record Bounds(long minX, long minZ, long maxXExclusive, long maxZExclusive) {
        public Bounds {
            try {
                if (minX >= maxXExclusive || minZ >= maxZExclusive)
                    throw new Refusal("Inverted or empty selected footprint");
                Math.multiplyExact(Math.subtractExact(maxXExclusive, minX), Math.subtractExact(maxZExclusive, minZ));
            } catch (ArithmeticException overflow) { throw new Refusal("Selected footprint overflow", overflow); }
        }
        public long width() { return Math.subtractExact(maxXExclusive, minX); }
        public long depth() { return Math.subtractExact(maxZExclusive, minZ); }
        public long columns() { return Math.multiplyExact(width(), depth()); }
        public boolean contains(long x, long z) {
            return x >= minX && x < maxXExclusive && z >= minZ && z < maxZExclusive;
        }
        public boolean containsHorizontalCube(CubePos cube) {
            return cube.minBlockX() >= minX && cube.minBlockX() + 16 <= maxXExclusive
                    && cube.minBlockZ() >= minZ && cube.minBlockZ() + 16 <= maxZExclusive;
        }
    }

    /** Bits use (localY*4+localZ)*4+localX. Overlapping reasons never duplicate a member. */
    public record Page(SurvivalVisualPage address, long groundMask, long treeMask, long editExceptionMask) {
        public Page {
            Objects.requireNonNull(address, "page address");
            if ((groundMask | treeMask | editExceptionMask) == 0) throw new Refusal("Empty native page");
        }
        public long memberMask() { return groundMask | treeMask | editExceptionMask; }
        public int memberCount() { return Long.bitCount(memberMask()); }
        public boolean containsMember(CubePos cube) { return contains(cube, memberMask()); }
        public boolean containsGroundMember(CubePos cube) { return contains(cube, groundMask); }
        public boolean containsTreeMember(CubePos cube) { return contains(cube, treeMask); }
        public boolean containsEditException(CubePos cube) { return contains(cube, editExceptionMask); }
        private boolean contains(CubePos cube, long mask) {
            if (cube == null) return false;
            CubePos p = address.minimum();
            long x = (long) cube.x() - p.x(), y = (long) cube.y() - p.y(), z = (long) cube.z() - p.z();
            return x >= 0 && x < 4 && y >= 0 && y < 4 && z >= 0 && z < 4
                    && (mask & 1L << ((y * 4 + z) * 4 + x)) != 0;
        }
        public Set<CubePos> members() {
            var result = new TreeSet<CubePos>();
            for (int bit = 0; bit < 64; bit++) if ((memberMask() & 1L << bit) != 0)
                result.add(memberAt(address, bit));
            return Collections.unmodifiableSet(result);
        }
        /** Exact one-cube 26-neighbor meshing union, including members; at most 216 owners. */
        public Set<CubePos> dependencies() {
            var result = new TreeSet<CubePos>();
            for (CubePos member : members())
                for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++)
                    result.add(member.offset(x, y, z));
            return Collections.unmodifiableSet(result);
        }
    }

    /**
     * Dependencies/halo are per-page references: shared owners in two pages count twice.
     * Halo means dependencies minus selected members, including unselected page-interior neighbors.
     * exteriorHaloReferences counts just owners outside a physical 64 m page (at most 152/page).
     * featureRootVisits counts bounded query results, including roots revisited across page seams.
     */
    public record Census(long selectedColumns, long sampledColumns, long horizontalCubes,
            long pages, long members, long groundMembers, long treeMembers, long editExceptionMembers,
            long dependencyReferences, long haloReferences, long exteriorHaloReferences,
            long completeEditedCubes, long treeQueries, long featureRootVisits, long metadataBytes) {}

    private final CubePos focus;
    private final DistanceChoice distance;
    private final Bounds bounds;
    private final CompleteEditSet edits;
    private final Limits limits;
    private final Map<CubePos, Page> pageIndex;
    private final List<Page> pages;
    private final int sampleWidth;
    private final int[] heights;
    private final Census census;
    private final String sourceIdentity, identity;

    private SurvivalDistancePagePlan(MarinSurvivalDefinition definition, CubePos focus,
            DistanceChoice distance, CompleteEditSet edits, Limits limits) {
        Objects.requireNonNull(definition, "definition");
        this.focus = Objects.requireNonNull(focus, "focus");
        this.distance = Objects.requireNonNull(distance, "distance");
        this.edits = Objects.requireNonNull(edits, "complete edits");
        this.limits = Objects.requireNonNull(limits, "limits");
        definition.validateCenter(focus);
        sourceIdentity = definition.visualIdentity();
        int radius = distance.effectiveChunks;
        int minX = Math.subtractExact(focus.x(), radius), minZ = Math.subtractExact(focus.z(), radius);
        int maxX = Math.addExact(focus.x(), radius), maxZ = Math.addExact(focus.z(), radius);
        bounds = selectedBounds(focus, distance);
        long horizontal = Math.multiplyExact(2L * radius + 1, 2L * radius + 1);
        sampleWidth = Math.toIntExact(Math.addExact(bounds.width(), 2));
        long sampled = Math.multiplyExact((long) sampleWidth, Math.addExact(bounds.depth(), 2));
        long baseBytes = Math.addExact(BASE_METADATA_BYTES, Math.addExact(Math.multiplyExact(sampled, 4),
                Math.addExact(editBytes(edits.modifiedCubes.size()), TREE_QUERY_SCRATCH_BYTES)));
        if (horizontal > limits.maxHorizontalCubes || sampled > limits.maxSampledColumns
                || sampled > Integer.MAX_VALUE - 8L || edits.modifiedCubes.size() > limits.maxEditedCubes
                || baseBytes > limits.maxMetadataBytes)
            throw new Refusal("Selected footprint exceeds column/index/metadata admission before source sampling");
        // A rectangle wholly inside constructor-verified source support also proves its interior.
        // Validate every horizontal meshing halo extreme BEFORE any distance-wide loop/allocation.
        for (int x : new int[]{Math.subtractExact(minX, 1), Math.addExact(maxX, 1)})
            for (int z : new int[]{Math.subtractExact(minZ, 1), Math.addExact(maxZ, 1)})
                definition.validateVisualCube(new CubePos(x, focus.y(), z));
        // The complete index remains bounded even when edits lie outside the selected view.
        for (CubePos edited : edits.modifiedCubes) {
            definition.validateVisualCube(edited);
            if (bounds.containsHorizontalCube(edited)) validateMemberSupport(definition, edited);
            for (int[] delta : FACE_NEIGHBORS) {
                CubePos neighbor = edited.offset(delta[0], delta[1], delta[2]);
                if (bounds.containsHorizontalCube(neighbor)) validateMemberSupport(definition, neighbor);
            }
        }
        heights = new int[Math.toIntExact(sampled)];
        MessageDigest sourceDigest = digest();
        byte[] encodedHeight = new byte[4];
        long lowest = Long.MAX_VALUE, highest = Long.MIN_VALUE;
        for (long z = bounds.minZ - 1; z <= bounds.maxZExclusive; z++)
            for (long x = bounds.minX - 1; x <= bounds.maxXExclusive; x++) {
                long height = definition.surfaceY(x, z);
                if (height < MarinSurvivalDefinition.MIN_BLOCK_Y || height >= MarinSurvivalDefinition.MAX_BLOCK_Y_EXCLUSIVE - 1)
                    throw new Refusal("Source surface escapes signed generation/top-neighbor support");
                int value = Math.toIntExact(height);
                heights[sampleIndex(x, z)] = value;
                for (int i = 0; i < 4; i++) encodedHeight[i] = (byte) (value >>> (i * 8));
                sourceDigest.update(encodedHeight);
                lowest = Math.min(lowest, height); highest = Math.max(highest, height);
            }
        // Vertical dependency support is proved before the ground/cliff member loops.
        for (int y : new int[]{Math.subtractExact(CubePos.section(lowest), 1),
                Math.addExact(CubePos.section(highest), 1)})
            definition.validateVisualCube(new CubePos(minX, y, minZ));
        Builder builder = new Builder(definition, limits, baseBytes);
        for (long z = bounds.minZ; z < bounds.maxZExclusive; z++)
            for (long x = bounds.minX; x < bounds.maxXExclusive; x++) {
                int first = CubePos.section(lowestExposedGroundY(x, z)), last = CubePos.section(surfaceY(x, z));
                // Only each column's exposed side range, never the solid volume down to world bottom.
                for (int y = first; y <= last; y++) builder.add(CubePos.fromBlock(x, (long) y * 16, z), 0);
            }
        long queries = 0, roots = 0;
        // Bounded 64 m horizontal tiles discover seam roots outside the queried tile.
        for (long z = bounds.minZ; z < bounds.maxZExclusive; z += 64)
            for (long x = bounds.minX; x < bounds.maxXExclusive; x += 64) {
                long endX = Math.min(x + 64, bounds.maxXExclusive), endZ = Math.min(z + 64, bounds.maxZExclusive);
                var trees = definition.visualTreesIntersecting(x, z, endX, endZ);
                if (trees.size() > MarinSurvivalDefinition.MAX_VISUAL_TREE_CANDIDATES)
                    throw new Refusal("Source exceeded its bounded feature-query contract");
                queries = Math.incrementExact(queries); roots = Math.addExact(roots, trees.size());
                for (var tree : trees) {
                    long x0 = Math.max(x, tree.x() - 2), x1 = Math.min(endX, tree.x() + 3);
                    long z0 = Math.max(z, tree.z() - 2), z1 = Math.min(endZ, tree.z() + 3);
                    for (long tz = z0; tz < z1; tz++) for (long tx = x0; tx < x1; tx++)
                        for (long y = tree.groundY() + 1; y <= tree.topY() + 1; y++)
                            if (y > surfaceY(tx, tz) && !tree.stateAt(tx, y, tz).isAir())
                                builder.add(CubePos.fromBlock(tx, y, tz), 1);
                }
            }
        for (CubePos edited : edits.modifiedCubes) {
            addException(builder, edited);
            for (int[] neighbor : FACE_NEIGHBORS) addException(builder, edited.offset(neighbor[0], neighbor[1], neighbor[2]));
        }
        var frozen = new TreeMap<CubePos, Page>();
        long ground = 0, tree = 0, exceptional = 0, exterior = 0;
        MessageDigest resultDigest = digest();
        update(resultDigest, "marin-selected-native-content-039-v1\n" + sourceIdentity + "\n" + focus + "\n"
                + distance + "\n" + bounds + "\n" + edits.indexIdentity + "\n" + finish(sourceDigest) + "\n");
        for (var entry : builder.pages.entrySet()) {
            MutablePage scratch = entry.getValue();
            var page = new Page(scratch.address, scratch.ground, scratch.tree, scratch.exceptional);
            frozen.put(entry.getKey(), page);
            ground = Math.addExact(ground, Long.bitCount(page.groundMask));
            tree = Math.addExact(tree, Long.bitCount(page.treeMask));
            exceptional = Math.addExact(exceptional, Long.bitCount(page.editExceptionMask));
            exterior = Math.addExact(exterior, scratch.exterior);
            update(resultDigest, entry.getKey() + ":" + Long.toUnsignedString(page.groundMask, 16) + ":"
                    + Long.toUnsignedString(page.treeMask, 16) + ":" + Long.toUnsignedString(page.editExceptionMask, 16) + "\n");
        }
        pageIndex = Collections.unmodifiableMap(frozen);
        pages = List.copyOf(frozen.values());
        census = new Census(bounds.columns(), sampled, horizontal, pages.size(), builder.members,
                ground, tree, exceptional, builder.dependencies, builder.halo, exterior,
                edits.modifiedCubes.size(), queries, roots, builder.metadataBytes());
        identity = finish(resultDigest);
    }

    public static SurvivalDistancePagePlan build(MarinSurvivalDefinition definition, CubePos focus,
            DistanceChoice distance, CompleteEditSet edits, Limits limits) {
        try { return new SurvivalDistancePagePlan(definition, focus, distance, edits, limits); }
        catch (ArithmeticException overflow) { throw new Refusal("Selected-distance plan overflow; no clamping", overflow); }
    }
    /** Convenience for an uncapped selected distance. A mismatch requires the explicit-cap overload. */
    public static SurvivalDistancePagePlan build(MarinSurvivalDefinition definition, CubePos focus,
            int selectedChunks, int effectiveChunks, CompleteEditSet edits, Limits limits) {
        return build(definition, focus, new DistanceChoice(selectedChunks, effectiveChunks, 0), edits, limits);
    }

    /** Pure horizontal arithmetic only, useful for bounds preflight; this does not prove source availability. */
    public static Bounds selectedBounds(CubePos focus, DistanceChoice distance) {
        Objects.requireNonNull(focus, "focus"); Objects.requireNonNull(distance, "distance");
        try {
            int radius = distance.effectiveChunks;
            int minX = Math.subtractExact(focus.x(), radius), minZ = Math.subtractExact(focus.z(), radius);
            int maxX = Math.addExact(focus.x(), radius), maxZ = Math.addExact(focus.z(), radius);
            return new Bounds((long) minX * 16, (long) minZ * 16,
                    Math.multiplyExact(Math.addExact((long) maxX, 1), 16),
                    Math.multiplyExact(Math.addExact((long) maxZ, 1), 16));
        } catch (ArithmeticException overflow) { throw new Refusal("Selected native cube footprint overflow", overflow); }
    }

    private void addException(Builder builder, CubePos cube) {
        if (bounds.containsHorizontalCube(cube)) builder.add(cube, 2);
    }
    private int sampleIndex(long x, long z) {
        if (x < bounds.minX - 1 || x > bounds.maxXExclusive || z < bounds.minZ - 1 || z > bounds.maxZExclusive)
            throw new Refusal("Column outside sampled source-neighbor halo");
        return Math.toIntExact((z - bounds.minZ + 1) * sampleWidth + x - bounds.minX + 1);
    }
    public long surfaceY(long x, long z) {
        if (!bounds.contains(x, z)) throw new Refusal("Column outside exact selected footprint");
        return heights[sampleIndex(x, z)];
    }
    /** Source ground face range only. It does not include edits, foliage, or certify opaque coverage. */
    public long lowestExposedGroundY(long x, long z) {
        long height = surfaceY(x, z);
        long lowestNeighbor = Math.min(Math.min(heights[sampleIndex(x - 1, z)], heights[sampleIndex(x + 1, z)]),
                Math.min(heights[sampleIndex(x, z - 1)], heights[sampleIndex(x, z + 1)]));
        return Math.min(height, lowestNeighbor + 1);
    }
    public CubePos focus() { return focus; }
    public DistanceChoice distance() { return distance; }
    public Bounds bounds() { return bounds; }
    public CompleteEditSet edits() { return edits; }
    public Limits limits() { return limits; }
    /** Key is the aligned signed minimum cube, not a page ordinal. */
    public Map<CubePos, Page> pageIndex() { return pageIndex; }
    public List<Page> pages() { return pages; }
    public Census census() { return census; }
    public String sourceIdentity() { return sourceIdentity; }
    public String identity() { return identity; }
    public boolean containsMember(CubePos cube) {
        Page page = pageIndex.get(SurvivalVisualPage.containing(cube).minimum());
        return page != null && page.containsMember(cube);
    }

    private static final class MutablePage {
        final SurvivalVisualPage address;
        final long[] dependencyBits = new long[4]; // 216-bit exact sparse neighbor union
        long ground, tree, exceptional;
        int exterior;
        MutablePage(SurvivalVisualPage address) { this.address = address; }
        long members() { return ground | tree | exceptional; }
        void mark(int reason, long bit) {
            if (reason == 0) ground |= bit;
            else if (reason == 1) tree |= bit;
            else exceptional |= bit;
        }
    }
    private static final class Builder {
        final MarinSurvivalDefinition definition;
        final Limits limits;
        final long baseBytes;
        final TreeMap<CubePos, MutablePage> pages = new TreeMap<>();
        long members, dependencies, halo;
        Builder(MarinSurvivalDefinition definition, Limits limits, long baseBytes) {
            this.definition = definition; this.limits = limits; this.baseBytes = baseBytes;
        }
        long metadataBytes() { return Math.addExact(baseBytes, Math.multiplyExact(pages.size(), PAGE_METADATA_BYTES)); }
        void add(CubePos cube, int reason) {
            SurvivalVisualPage address = SurvivalVisualPage.containing(cube);
            CubePos key = address.minimum();
            MutablePage page = pages.get(key);
            int lx = Math.floorMod(cube.x(), 4), ly = Math.floorMod(cube.y(), 4), lz = Math.floorMod(cube.z(), 4);
            long bit = 1L << ((ly * 4 + lz) * 4 + lx);
            if (page != null && (page.members() & bit) != 0) { page.mark(reason, bit); return; }
            long nextPages = pages.size() + (page == null ? 1L : 0L);
            long nextMetadata = Math.addExact(baseBytes, Math.multiplyExact(nextPages, PAGE_METADATA_BYTES));
            if (nextPages > limits.maxPages || nextMetadata > limits.maxMetadataBytes || members + 1 > limits.maxMembers)
                throw new Refusal("Selected native page/member/metadata budget exceeded; no truncation");
            // Source checks precede each bounded neighbor loop's bit writes/retention.
            validateMemberSupport(definition, cube);
            int newDependencies = 0, newExterior = 0;
            for (int y = ly; y <= ly + 2; y++) for (int z = lz; z <= lz + 2; z++) for (int x = lx; x <= lx + 2; x++) {
                int index = (y * 6 + z) * 6 + x;
                if (page == null || (page.dependencyBits[index >>> 6] & 1L << (index & 63)) == 0) {
                    newDependencies++;
                    if (x == 0 || x == 5 || y == 0 || y == 5 || z == 0 || z == 5) newExterior++;
                }
            }
            long nextDependencies = Math.addExact(dependencies, newDependencies);
            long nextHalo = Math.subtractExact(nextDependencies, Math.addExact(members, 1));
            if (nextDependencies > limits.maxDependencyReferences || nextHalo > limits.maxHaloReferences)
                throw new Refusal("Selected native dependency/halo reference budget exceeded; no truncation");
            if (page == null) { page = new MutablePage(address); pages.put(key, page); }
            for (int y = ly; y <= ly + 2; y++) for (int z = lz; z <= lz + 2; z++) for (int x = lx; x <= lx + 2; x++) {
                int index = (y * 6 + z) * 6 + x;
                page.dependencyBits[index >>> 6] |= 1L << (index & 63);
            }
            page.mark(reason, bit); page.exterior += newExterior;
            members = Math.incrementExact(members); dependencies = nextDependencies; halo = nextHalo;
        }
    }

    private static CubePos memberAt(SurvivalVisualPage page, int bit) {
        return page.minimum().offset(bit & 3, bit >>> 4, bit >>> 2 & 3);
    }
    private static void validateMemberSupport(MarinSurvivalDefinition definition, CubePos cube) {
        for (int y : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) for (int x : new int[]{-1, 1})
            definition.validateVisualCube(cube.offset(x, y, z));
    }
    private static void distance(int value) {
        if (value < MIN_DISTANCE || value > MAX_DISTANCE) throw new Refusal("Native distance must be 2..32; no clamping");
    }
    private static long editBytes(long count) { return Math.addExact(256, Math.multiplyExact(count, EDIT_METADATA_BYTES)); }
    private static String identity(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new Refusal("Complete overlay content identity must be lowercase SHA-256");
        return value;
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }
    private static void update(MessageDigest digest, String value) { digest.update(value.getBytes(StandardCharsets.UTF_8)); }
    private static String finish(MessageDigest digest) { return HexFormat.of().formatHex(digest.digest()); }
}
