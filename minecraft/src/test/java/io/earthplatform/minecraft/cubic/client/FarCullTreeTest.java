package io.earthplatform.minecraft.cubic.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.world.phys.AABB;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FarCullTreeTest {
    /** Settled branches must give every column the answer testing it alone gives, as Frustum.isVisible does. */
    @Test void matchesTestingEachColumn() {
        Random random = new Random(9021);
        for (int round = 0; round < 40; round++) {
            Matrix4f view = new Matrix4f().perspective((float) Math.toRadians(50 + random.nextInt(60)), 16 / 9f, 0.05f, 400_000f)
                    .rotateX((float) (random.nextDouble() - 0.5) * 3f).rotateY((float) (random.nextDouble() * 6.3));
            FrustumIntersection frustum = new FrustumIntersection(view);
            double camX = random.nextDouble() * 2e7 - 1e7, camY = random.nextDouble() * 3000, camZ = random.nextDouble() * 2e7 - 1e7;
            FarCullTree tree = new FarCullTree();
            tree.clear(true);
            List<AABB> columns = new ArrayList<>();
            for (int tile = 0; tile < 25; tile++) {
                int outer = tree.open();
                double size = 262_144;
                double x = camX + (tile % 5 - 2) * size, z = camZ + (tile / 5 - 2) * size;
                add(tree, columns, random, x - x % size, z - z % size, size, 7);
                tree.close(outer);
            }
            tree.classify(frustum, camX, camY, camZ);
            int settled = 0;
            for (int i = 0; i < columns.size(); i++) {
                AABB box = columns.get(i);
                boolean alone = frustum.testAab((float) (box.minX - camX), (float) (box.minY - camY), (float) (box.minZ - camZ),
                        (float) (box.maxX - camX), (float) (box.maxY - camY), (float) (box.maxZ - camZ));
                byte state = tree.state(i);
                if (state != FarCullTree.PARTLY) settled++;
                if (state == FarCullTree.INSIDE) assertTrue(alone, "column " + i + " settled inside but tests outside");
                if (state == FarCullTree.OUTSIDE) assertFalse(alone, "column " + i + " settled outside but tests inside");
            }
            assertTrue(settled > 0, "nothing settled in round " + round);
        }
    }

    /**
     * A kept walk classifies the same branches again for each new view: every view after the first must still
     * give each column the answer testing it alone gives, with the branches' boxes grown only the first time.
     */
    @Test void keptBranchesSettleLaterViews() {
        Random random = new Random(4417);
        FarCullTree tree = new FarCullTree();
        tree.clear(true);
        List<AABB> columns = new ArrayList<>();
        double camX = 1_234_567, camZ = -7_654_321;
        for (int tile = 0; tile < 25; tile++) {
            int outer = tree.open();
            double size = 262_144;
            double x = camX + (tile % 5 - 2) * size, z = camZ + (tile / 5 - 2) * size;
            add(tree, columns, random, x - x % size, z - z % size, size, 7);
            tree.close(outer);
        }
        for (int round = 0; round < 30; round++) {
            Matrix4f view = new Matrix4f().perspective((float) Math.toRadians(50 + random.nextInt(60)), 16 / 9f, 0.05f, 400_000f)
                    .rotateX((float) (random.nextDouble() - 0.5) * 3f).rotateY((float) (random.nextDouble() * 6.3));
            FrustumIntersection frustum = new FrustumIntersection(view);
            // Moving about within the walk's ground, as between walks.
            double x = camX + random.nextDouble() * 64, y = random.nextDouble() * 3000, z = camZ + random.nextDouble() * 64;
            tree.classify(frustum, x, y, z);
            int settled = 0;
            for (int i = 0; i < columns.size(); i++) {
                AABB box = columns.get(i);
                boolean alone = frustum.testAab((float) (box.minX - x), (float) (box.minY - y), (float) (box.minZ - z),
                        (float) (box.maxX - x), (float) (box.maxY - y), (float) (box.maxZ - z));
                byte state = tree.state(i);
                if (state != FarCullTree.PARTLY) settled++;
                if (state == FarCullTree.INSIDE) assertTrue(alone, "column " + i + " settled inside but tests outside in view " + round);
                if (state == FarCullTree.OUTSIDE) assertFalse(alone, "column " + i + " settled outside but tests inside in view " + round);
            }
            assertTrue(settled > 0, "nothing settled in view " + round);
        }
    }

    /**
     * A patch of the walk (see FarRenderer#patch) opens kept branches again and adds columns after all the
     * others, some in branches of their own, and a column sent again takes another's place with a box of its
     * own (see FarRenderer#drawInPlace): every column must still settle as testing it alone does, by its box now.
     */
    @Test void patchedBranchesSettleAsTestingEach() {
        Random random = new Random(7310);
        for (int round = 0; round < 20; round++) {
            FarCullTree tree = new FarCullTree();
            tree.clear(true);
            List<AABB> columns = new ArrayList<>();
            List<Integer> branches = new ArrayList<>();
            double camX = random.nextDouble() * 2e7 - 1e7, camZ = random.nextDouble() * 2e7 - 1e7, size = 262_144;
            for (int tile = 0; tile < 25; tile++) {
                int outer = tree.open();
                branches.add(tree.current());
                double x = camX + (tile % 5 - 2) * size, z = camZ + (tile / 5 - 2) * size;
                add(tree, columns, random, x - x % size, z - z % size, size, 7, branches);
                tree.close(outer);
            }
            for (int patch = 0; patch < 8; patch++) {
                int outer = tree.reopen(branches.get(random.nextInt(branches.size())));
                double part = size / (1 << random.nextInt(5));
                double x = camX + (random.nextDouble() * 5 - 2.5) * size, z = camZ + (random.nextDouble() * 5 - 2.5) * size;
                add(tree, columns, random, x - x % part, z - z % part, part, 4, branches);
                tree.closeAgain(outer);
                for (int sent = 0; sent < 3; sent++) {
                    int index = random.nextInt(columns.size());
                    AABB box = columns.get(index);
                    double bottom = random.nextInt(4000) - 2000;
                    AABB again = new AABB(box.minX, bottom, box.minZ, box.maxX, bottom + random.nextInt(3000), box.maxZ);
                    columns.set(index, again);
                    tree.regrow(index, again.minX, again.minY, again.minZ, again.maxX, again.maxY, again.maxZ);
                }
                for (int view = 0; view < 4; view++) {
                    Matrix4f matrix = new Matrix4f().perspective((float) Math.toRadians(50 + random.nextInt(60)), 16 / 9f, 0.05f, 400_000f)
                            .rotateX((float) (random.nextDouble() - 0.5) * 3f).rotateY((float) (random.nextDouble() * 6.3));
                    FrustumIntersection frustum = new FrustumIntersection(matrix);
                    double cx = camX + random.nextDouble() * 64, cy = random.nextDouble() * 3000, cz = camZ + random.nextDouble() * 64;
                    tree.classify(frustum, cx, cy, cz);
                    for (int i = 0; i < columns.size(); i++) {
                        AABB box = columns.get(i);
                        boolean alone = frustum.testAab((float) (box.minX - cx), (float) (box.minY - cy), (float) (box.minZ - cz),
                                (float) (box.maxX - cx), (float) (box.maxY - cy), (float) (box.maxZ - cz));
                        byte state = tree.state(i);
                        if (state == FarCullTree.INSIDE) assertTrue(alone, "column " + i + " settled inside but tests outside, round " + round + " patch " + patch);
                        if (state == FarCullTree.OUTSIDE) assertFalse(alone, "column " + i + " settled outside but tests inside, round " + round + " patch " + patch);
                    }
                }
            }
        }
    }

    private static void add(FarCullTree tree, List<AABB> columns, Random random, double x, double z, double size, int depth) {
        add(tree, columns, random, x, z, size, depth, null);
    }

    /** A quadtree like the far pick: some columns drawn whole, some split into four, parents sometimes drawn too; the branches opened go in {@code branches}. */
    private static void add(FarCullTree tree, List<AABB> columns, Random random, double x, double z, double size, int depth, List<Integer> branches) {
        if (depth == 0 || random.nextInt(4) == 0) {
            own(tree, columns, random, x, z, size);
            return;
        }
        if (random.nextInt(5) == 0) own(tree, columns, random, x, z, size);
        int outer = tree.open();
        if (branches != null) branches.add(tree.current());
        double half = size / 2;
        for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) add(tree, columns, random, x + dx * half, z + dz * half, half, depth - 1, branches);
        tree.close(outer);
    }

    private static void own(FarCullTree tree, List<AABB> columns, Random random, double x, double z, double size) {
        double bottom = random.nextInt(4000) - 2000;
        AABB box = new AABB(x, bottom, z, x + size, bottom + random.nextInt(3000), z + size);
        tree.own(columns.size(), box);
        columns.add(box);
    }

    /** Under the Earth's curve, a column the curve drops into view must never settle outside, as in a view from high up. */
    @Test void curvedBranchesNeverHideAColumnTheCurveDropsIntoView() {
        Random random = new Random(4471);
        float curvature = EarthView.full(1);
        for (int round = 0; round < 40; round++) {
            Matrix4f view = new Matrix4f().perspective((float) Math.toRadians(30 + random.nextInt(60)), 16 / 9f, 0.05f, 400_000f)
                    .rotateX((float) (random.nextDouble() * 0.6)).rotateY((float) (random.nextDouble() * 6.3));
            FrustumIntersection frustum = new FrustumIntersection(view);
            double camX = random.nextDouble() * 2e6 - 1e6, camY = 2000 + random.nextDouble() * 8000, camZ = random.nextDouble() * 2e6 - 1e6;
            FarCullTree tree = new FarCullTree();
            tree.clear(true);
            List<AABB> columns = new ArrayList<>();
            for (int tile = 0; tile < 9; tile++) {
                int outer = tree.open();
                double size = 65_536;
                double x = camX + (tile % 3 - 1) * size, z = camZ + (tile / 3 - 1) * size;
                add(tree, columns, random, x - x % size, z - z % size, size, 6);
                tree.close(outer);
            }
            tree.classify(frustum, camX, camY, camZ, 0, curvature);
            for (int i = 0; i < columns.size(); i++) {
                AABB box = columns.get(i);
                double drop = EarthView.drop(Math.max(Math.abs(box.minX - camX), Math.abs(box.maxX - camX)),
                        Math.max(Math.abs(box.minZ - camZ), Math.abs(box.maxZ - camZ)), curvature);
                boolean dropped = frustum.testAab((float) (box.minX - camX), (float) (box.minY - drop - camY), (float) (box.minZ - camZ),
                        (float) (box.maxX - camX), (float) (box.maxY - camY), (float) (box.maxZ - camZ));
                if (tree.state(i) == FarCullTree.OUTSIDE) assertFalse(dropped, "column " + i + " settled outside but its drop is in view");
            }
        }
        // A tall far column above the top of a level view: flat it is out of sight, dropped by the curve it shows.
        FarCullTree tree = new FarCullTree();
        tree.clear(true);
        int outer = tree.open();
        AABB box = new AABB(-1000, 13_600, -60_000, 1000, 14_000, -58_000);
        tree.own(0, box);
        tree.close(outer);
        FrustumIntersection level = new FrustumIntersection(new Matrix4f().perspective((float) Math.toRadians(20), 16 / 9f, 0.05f, 400_000f));
        tree.classify(level, 0, 3000, 0, 0, 0);
        assertEquals(FarCullTree.OUTSIDE, tree.state(0));
        tree.classify(level, 0, 3000, 0, 0, curvature);
        assertNotEquals(FarCullTree.OUTSIDE, tree.state(0));
    }

    @Test void disabledLeavesEveryColumnToItsOwnTest() {
        FarCullTree tree = new FarCullTree();
        tree.clear(false);
        int outer = tree.open();
        tree.own(0, new AABB(0, 0, 0, 1, 1, 1));
        tree.close(outer);
        tree.classify(new FrustumIntersection(new Matrix4f()), 0, 0, 0);
        assertEquals(FarCullTree.PARTLY, tree.state(0));
    }
}
