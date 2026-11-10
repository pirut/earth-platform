package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GiantsTest {
    @BeforeAll static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private record Placed(int x, int y, int z, int kind) {}

    private static List<Placed> blocks(TreeShape shape) {
        List<Placed> placed = new ArrayList<>();
        shape.place(0, 0, 0, 0, 1, false, 0, -64, -8, -64, 64, 200, 64, (x, z) -> -1, (x, y, z, state, kind) ->
                placed.add(new Placed(x, y, z, state.getBlock() instanceof LeavesBlock ? 3 : !(state.getBlock() instanceof RotatedPillarBlock) ? 0
                        : state.is(net.minecraft.world.level.block.Blocks.MANGROVE_WOOD) ? 1 : state.is(net.minecraft.world.level.block.Blocks.DARK_OAK_WOOD) ? 4 : 2)));
        return placed;
    }

    @Test void everyGiantIsATallTreeWithinAGrovesReach() throws Exception {
        String dump = System.getenv("EARTH_GIANTS_DUMP");
        PrintWriter out = dump == null ? null : new PrintWriter(Files.newBufferedWriter(Path.of(dump)));
        for (int form = 0; form < 3; form++) {
            TreeShape[] shapes = Giants.shapes(form);
            assertEquals(12, shapes.length);
            for (int i = 0; i < shapes.length; i++) {
                int wood = 0, leaves = 0, top = 0, wide = 0;
                for (Placed b : blocks(shapes[i])) {
                    if (b.kind == 3) leaves++; else if (b.kind != 0) wood++;
                    top = Math.max(top, b.y);
                    wide = Math.max(wide, Math.max(Math.abs(b.x), Math.abs(b.z)));
                    if (out != null) out.println(form + " " + i + " " + b.x + " " + b.y + " " + b.z + " " + b.kind);
                }
                String name = "form " + form + " variant " + i;
                assertTrue(top >= 60 && top < Sequoia.TALLEST, name + " stands " + top);
                assertTrue(wide <= Trees.REACH, name + " reaches " + wide);
                assertTrue(wood > 800 && leaves > 800, name + " has " + wood + " wood and " + leaves + " leaves");
            }
        }
        if (out != null) out.close();
    }

    @Test void aGiantGrowsTheSameEveryTime() {
        assertEquals(blocks(Giants.grow(42, Giants.REDWOOD)), blocks(Giants.grow(42, Giants.REDWOOD)));
    }
}
