package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The duff round giant trees keeps its coarse dirt by default and leaves it out when a world is pinned without it. */
class CoarseDirtToggleTest {
    @BeforeAll static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static List<BlockState> states(TreeShape shape) {
        List<BlockState> states = new ArrayList<>();
        shape.place(0, 0, 0, 0, 1, false, 0, -64, -8, -64, 64, 200, 64, (x, z) -> -1, (x, y, z, state, kind) -> states.add(state));
        return states;
    }

    private static long count(TreeShape[] shapes, net.minecraft.world.level.block.Block block) {
        long total = 0;
        for (TreeShape shape : shapes) total += states(shape).stream().filter(state -> state.is(block)).count();
        return total;
    }

    @Test void giantsKeepCoarseDirtByDefaultAndDropItWhenOff() {
        for (int form = 0; form < 3; form++) {
            assertTrue(count(Giants.shapes(form), Blocks.COARSE_DIRT) > 0, "form " + form + " lays coarse dirt by default");
            assertEquals(count(Giants.shapes(form), Blocks.COARSE_DIRT), count(Giants.shapes(form, true), Blocks.COARSE_DIRT));
            TreeShape[] plain = Giants.shapes(form, false);
            assertEquals(0, count(plain, Blocks.COARSE_DIRT), "form " + form + " has no coarse dirt when off");
            assertTrue(count(plain, Blocks.PODZOL) > 0, "form " + form + " still has podzol duff");
            assertTrue(count(plain, Blocks.DIRT) > 0, "form " + form + " puts plain dirt where coarse dirt was");
        }
    }

    @Test void sequoiasKeepCoarseDirtByDefaultAndDropItWhenOff() {
        assertTrue(count(Sequoia.shapes(), Blocks.COARSE_DIRT) > 0, "sequoias lay coarse dirt by default");
        TreeShape[] plain = Sequoia.shapes(false);
        assertEquals(0, count(plain, Blocks.COARSE_DIRT));
        assertTrue(count(plain, Blocks.PODZOL) > 0);
        assertTrue(count(plain, Blocks.DIRT) > 0);
        BlockState log = Blocks.OAK_WOOD.defaultBlockState(), leaves = Blocks.OAK_LEAVES.defaultBlockState();
        assertEquals(0, count(Sequoia.shapes(log, leaves, false), Blocks.COARSE_DIRT), "custom palettes drop it too");
        assertTrue(count(Sequoia.shapes(log, leaves, true), Blocks.COARSE_DIRT) > 0);
    }

    @Test void theTreesAreOtherwiseTheSame() {
        // Turning coarse dirt off swaps only those blocks: the random stream, and so every trunk and bough, is the same.
        TreeShape[] on = Giants.shapes(Giants.SEQUOIA, true), off = Giants.shapes(Giants.SEQUOIA, false);
        for (int i = 0; i < on.length; i++) {
            List<BlockState> a = states(on[i]), b = states(off[i]);
            assertEquals(a.size(), b.size());
            for (int k = 0; k < a.size(); k++) {
                BlockState was = a.get(k), now = b.get(k);
                if (was.is(Blocks.COARSE_DIRT)) assertTrue(now.is(Blocks.DIRT)); else assertEquals(was, now);
            }
        }
    }
}
