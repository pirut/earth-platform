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

    /** A quadtree like the far pick: some columns drawn whole, some split into four, parents sometimes drawn too. */
    private static void add(FarCullTree tree, List<AABB> columns, Random random, double x, double z, double size, int depth) {
        if (depth == 0 || random.nextInt(4) == 0) {
            own(tree, columns, random, x, z, size);
            return;
        }
        if (random.nextInt(5) == 0) own(tree, columns, random, x, z, size);
        int outer = tree.open();
        double half = size / 2;
        for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) add(tree, columns, random, x + dx * half, z + dz * half, half, depth - 1);
        tree.close(outer);
    }

    private static void own(FarCullTree tree, List<AABB> columns, Random random, double x, double z, double size) {
        double bottom = random.nextInt(4000) - 2000;
        AABB box = new AABB(x, bottom, z, x + size, bottom + random.nextInt(3000), z + size);
        tree.own(columns.size(), box);
        columns.add(box);
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
