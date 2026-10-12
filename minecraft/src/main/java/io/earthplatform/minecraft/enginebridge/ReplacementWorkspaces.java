package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Exact two-workspace gate geometry. This value grants neither residency nor edit authority. */
public final class ReplacementWorkspaces {
    public static final int COUNT = 2;
    public static final int SECOND_X_OFFSET = 4;
    public enum Mode { DISJOINT, ADJACENT }
    private final Mode mode;
    private final List<CubePos> positions;
    private final String identity;

    private ReplacementWorkspaces(CubePos original, Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(original, "original");
        CubePos second = new CubePos(Math.addExact(original.x(), mode == Mode.ADJACENT ? 1 : SECOND_X_OFFSET), original.y(), original.z());
        // Both centers must support the existing full moving window. No native envelope expansion.
        RollingCubePlan.validateWindowCenter(original);
        RollingCubePlan.validateWindowCenter(second);
        positions = List.of(original, second);
        if (mode == Mode.DISJOINT && dependencies(original).stream().anyMatch(dependencies(second)::contains))
            throw new IllegalArgumentException("Workspace dependency halos must be disjoint");
        identity = sha256(canonicalText());
    }

    public static ReplacementWorkspaces two(CubePos original) { return new ReplacementWorkspaces(original, Mode.DISJOINT); }

    public static ReplacementWorkspaces adjacent(CubePos original) { return new ReplacementWorkspaces(original, Mode.ADJACENT); }
    public Mode mode() { return mode; }
    public boolean isAdjacent() { return mode == Mode.ADJACENT; }

    /** Decode only the exact predefined pair, rejecting permutations and arbitrary extra regions. */
    public static ReplacementWorkspaces canonical(List<CubePos> positions) {
        Objects.requireNonNull(positions, "positions");
        if (positions.size() != COUNT) throw new IllegalArgumentException("Exactly two workspaces required");
        Mode mode = (long) positions.getLast().x() - positions.getFirst().x() == 1 ? Mode.ADJACENT : Mode.DISJOINT;
        ReplacementWorkspaces result = new ReplacementWorkspaces(positions.getFirst(), mode);
        if (!result.positions.equals(positions)) throw new IllegalArgumentException("Noncanonical workspace pair");
        return result;
    }

    public static ReplacementWorkspaces canonical(Mode mode, List<CubePos> positions) {
        ReplacementWorkspaces result = canonical(positions);
        if (result.mode != Objects.requireNonNull(mode)) throw new IllegalArgumentException("Workspace mode mismatch");
        return result;
    }

    public CubePos original() { return positions.getFirst(); }
    public CubePos second() { return positions.getLast(); }
    public List<CubePos> positions() { return positions; }
    public boolean contains(CubePos position) { return positions.contains(position); }
    public String identity() { return identity; }

    public List<CubePos> dependencies(CubePos position) {
        if (!contains(position)) throw new IllegalArgumentException("Unknown workspace");
        return CubeTickets.box(position, 1, 1, ReplacementCertificate.DEPENDENCIES).stream().sorted().toList();
    }

    /** No extra pin is needed when the target's entire halo already belongs to the moving window. */
    public boolean insideWindow(CubePos position, CubePos windowCenter) {
        if (!contains(position)) return false;
        RollingCubePlan.validateWindowCenter(windowCenter);
        return RollingCubePlan.adjacent(position, windowCenter);
    }

    String canonicalText() {
        StringBuilder value = new StringBuilder(isAdjacent() ? "earth-adjacent-replacement-workspaces-v1\n" : "earth-replacement-workspaces-v2\n");
        for (CubePos position : positions)
            value.append(position.x()).append(' ').append(position.y()).append(' ').append(position.z()).append('\n');
        return value.toString();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    @Override public boolean equals(Object value) {
        return value instanceof ReplacementWorkspaces other && mode == other.mode && positions.equals(other.positions);
    }
    @Override public int hashCode() { return positions.hashCode(); }
    @Override public String toString() { return positions.toString(); }
}
