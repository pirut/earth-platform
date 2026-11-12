package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.far.Far;
import io.earthplatform.minecraft.cubic.gen.SurfacePacketFixtures;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A coarsest column's sea is cut at {@link EarthView#WATER_GRID} only when its job says so, which it
 * does from the live shader-pack state (see {@link EarthView#cutsWater}), never from the startup flag.
 */
class FarWaterGridTest {
    private static Map<TagKey<Fluid>, List<Holder<Fluid>>> previousFluidTags;

    /** Water is told by its tag, which unit tests don't load. */
    @BeforeAll static void fluidTags() {
        SurfacePacketFixtures.biomes();
        var registry = BuiltInRegistries.FLUID;
        previousFluidTags = registry.getTags().collect(Collectors.toMap(p -> p.getFirst(), p -> p.getSecond().stream().toList()));
        var tags = new HashMap<>(previousFluidTags);
        tags.put(FluidTags.WATER, List.of(registry.wrapAsHolder(Fluids.WATER), registry.wrapAsHolder(Fluids.FLOWING_WATER)));
        registry.bindTags(tags);
    }

    @AfterAll static void restoreTags() { BuiltInRegistries.FLUID.bindTags(previousFluidTags); }

    @Test void wideWaterIsCutForTheCurveOnlyWhenTheJobSaysSo() throws Exception {
        int level = Far.MAX_LEVEL;
        assertEquals(4, Far.width(level) / EarthView.WATER_GRID, "a coarsest column is four water grids across");
        FarMesher.Side sea = sea();
        FarMesher.Side[] around = {sea, sea, sea, sea};
        FarFaces faces = faces();
        int[] vertices = new int[2];
        for (int cut = 0; cut < 2; cut++) {
            var job = new FarMesher.Job(1, 1, level, 0, 0, 0, 0, sea.sections(), sea.drops(), around, around, null, false, new FarLooks(), faces,
                    null, new float[] {.5f, 1, .8f, .8f, .6f, .6f}, new float[] {1, 1, 1, 1, 1, 1}, null, Integer.MAX_VALUE, cut == 1);
            var built = FarMesher.build(job);
            try {
                assertNotNull(built.water(), "the sea is meshed");
                vertices[cut] = built.water().mesh().drawState().vertexCount();
            } finally { built.free(); }
        }
        assertEquals(4, vertices[0], "one face for the whole sea without the curve, as under a shader pack");
        assertEquals(16 * 4, vertices[1], "sixteen faces under the curve");
        assertFalse(EarthView.cutsWater(true), "never under a shader pack");
        assertFalse(EarthView.cutsWater(false), "nor with far.curvature off, as in tests");
    }

    private static FarMesher.Side sea() {
        LevelChunkSection section = new LevelChunkSection(SurfacePacketFixtures.biomes());
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 4; y++) section.setBlockState(x, y, z, Blocks.STONE.defaultBlockState(), false);
            for (int y = 4; y < 8; y++) section.setBlockState(x, y, z, Blocks.WATER.defaultBlockState(), false);
        }
        section.recalcBlockCounts();
        return new FarMesher.Side(new LevelChunkSection[] {section}, 0, new char[256]);
    }

    @SuppressWarnings("unchecked") private static FarFaces faces() throws Exception {
        FarFaces result = new FarFaces();
        var field = FarFaces.class.getDeclaredField("faces");
        field.setAccessible(true);
        Map<BlockState, FarFaces.Face[]> cache = (Map<BlockState, FarFaces.Face[]>) field.get(result);
        for (BlockState state : new BlockState[] {Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState()}) {
            FarFaces.Face[] look = new FarFaces.Face[Direction.values().length];
            Arrays.fill(look, new FarFaces.Face(0, 0, 1, 1, state.is(Blocks.WATER) ? 0x604070a0 : 0x808080, -1));
            cache.put(state, look);
        }
        return result;
    }
}
