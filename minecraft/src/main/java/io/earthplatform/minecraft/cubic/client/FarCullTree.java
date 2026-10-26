package io.earthplatform.minecraft.cubic.client;

import java.util.Arrays;
import net.minecraft.world.phys.AABB;
import org.joml.FrustumIntersection;

/**
 * Settles the view test for whole branches of the far pick at once: a branch whose columns'
 * combined box lies wholly inside the view, or wholly outside one of its planes, settles every
 * column under it the way testing each would. Only columns in branches the view's edge crosses
 * are tested one by one. Moving, every column used to be tested every frame.
 *
 * <p>Branches are opened and closed around each refined visit, so they nest in the order columns
 * are added; a column belongs to the innermost branch open when it was added. Render thread only.
 */
final class FarCullTree {
    static final byte PARTLY = 0, INSIDE = 1, OUTSIDE = 2;

    private boolean enabled;
    /** Whether each branch's box has taken in its branches' (see {@link #classify}), which a walk needs only once. */
    private boolean grown;
    private int nodes, current = -1, columns;
    private int[] parent = new int[64], start = new int[64], end = new int[64], owner = new int[1024];
    /** Each branch's combined box: min x, y, z, then max x, y, z. */
    private double[] box = new double[64 * 6];
    private byte[] state = new byte[1024];

    void clear(boolean enabled) {
        this.enabled = enabled;
        grown = false;
        nodes = 0;
        current = -1;
        columns = 0;
    }

    /** Opens a branch under the one open now, which it returns for {@link #close}. */
    int open() {
        int outer = current;
        if (!enabled) return outer;
        if (nodes == parent.length) {
            int size = nodes * 2;
            parent = Arrays.copyOf(parent, size);
            start = Arrays.copyOf(start, size);
            end = Arrays.copyOf(end, size);
            box = Arrays.copyOf(box, size * 6);
        }
        parent[nodes] = outer;
        start[nodes] = columns;
        end[nodes] = columns;
        box[nodes * 6] = box[nodes * 6 + 1] = box[nodes * 6 + 2] = Double.POSITIVE_INFINITY;
        box[nodes * 6 + 3] = box[nodes * 6 + 4] = box[nodes * 6 + 5] = Double.NEGATIVE_INFINITY;
        current = nodes++;
        return outer;
    }

    void close(int outer) {
        if (!enabled) return;
        end[current] = columns;
        current = outer;
    }

    /** The branch open now, which a column added now goes in. */
    int current() { return current; }

    /**
     * Opens again a branch a walk closed, for a patch of the pick (see FarRenderer#patch). The columns
     * added in it now come after all the others, outside its range, so the view tests each of them
     * unless a branch opened in it now settles them; its box takes theirs in, and stays one around
     * the columns in its range, which a settled branch settles as testing each would. Returns the
     * branch open before, for {@link #closeAgain}.
     */
    int reopen(int node) {
        int outer = current;
        if (!enabled) return outer;
        current = node;
        grown = false;
        return outer;
    }

    /** Closes a branch opened again (see {@link #reopen}), its range as the walk left it. */
    void closeAgain(int outer) {
        if (enabled) current = outer;
    }

    /** Puts the column at this index of the pick in the branch open now. */
    void own(int index, AABB column) { own(index, column.minX, column.minY, column.minZ, column.maxX, column.maxY, column.maxZ); }

    /** The same for a column's box given by its bounds. */
    void own(int index, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!enabled) return;
        if (index >= owner.length) owner = Arrays.copyOf(owner, Math.max(index + 1, owner.length * 2));
        owner[index] = current;
        columns = index + 1;
        if (current >= 0) grow(current, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Settles each branch against the view, the camera at the given place: the same float
     * camera-relative corners {@code Frustum.isVisible} tests, so a settled column comes out as testing
     * it would. A box wholly inside every plane holds only boxes that are; one wholly outside a plane
     * holds only boxes outside it. The branches stay as they are for the next view of the same
     * columns, as from a camera moving within the walk's ground (see FarRenderer#walkDue).
     */
    void classify(FrustumIntersection view, double camX, double camY, double camZ) {
        if (!enabled) return;
        // Branches come after the ones holding them, so going backwards a branch is complete before its holder takes it in.
        for (int n = nodes - 1; n >= 0 && !grown; n--) {
            int p = parent[n];
            if (p >= 0) grow(p, box[n * 6], box[n * 6 + 1], box[n * 6 + 2], box[n * 6 + 3], box[n * 6 + 4], box[n * 6 + 5]);
        }
        grown = true;
        if (state.length < columns) state = new byte[Math.max(columns, state.length * 2)];
        Arrays.fill(state, 0, columns, PARTLY);
        for (int n = 0; n < nodes; ) {
            if (start[n] == end[n] || box[n * 6] > box[n * 6 + 3]) { n++; continue; }
            int seen = view.intersectAab((float) (box[n * 6] - camX), (float) (box[n * 6 + 1] - camY), (float) (box[n * 6 + 2] - camZ),
                    (float) (box[n * 6 + 3] - camX), (float) (box[n * 6 + 4] - camY), (float) (box[n * 6 + 5] - camZ));
            if (seen == FrustumIntersection.INTERSECT) { n++; continue; }
            byte settled = seen == FrustumIntersection.INSIDE ? INSIDE : OUTSIDE;
            Arrays.fill(state, start[n], end[n], settled);
            // Its branches are settled with it: go on past them.
            int last = end[n];
            n++;
            while (n < nodes && start[n] < last) n++;
        }
    }

    /**
     * Takes in a new box for the column at this index of the pick, as when a column sent again takes
     * its place: its branch's box at once, and the branches holding that with the next classify.
     */
    void regrow(int index, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!enabled || index >= columns || owner[index] < 0) return;
        grow(owner[index], minX, minY, minZ, maxX, maxY, maxZ);
        grown = false;
    }

    /** How the column at this index of the pick stands against the view; {@link #PARTLY} means test it. */
    byte state(int index) { return enabled && index < columns ? state[index] : PARTLY; }

    private void grow(int n, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        int at = n * 6;
        if (minX < box[at]) box[at] = minX;
        if (minY < box[at + 1]) box[at + 1] = minY;
        if (minZ < box[at + 2]) box[at + 2] = minZ;
        if (maxX > box[at + 3]) box[at + 3] = maxX;
        if (maxY > box[at + 4]) box[at + 4] = maxY;
        if (maxZ > box[at + 5]) box[at + 5] = maxZ;
    }
}
