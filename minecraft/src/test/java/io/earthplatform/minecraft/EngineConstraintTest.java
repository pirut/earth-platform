package io.earthplatform.minecraft;

import io.earthplatform.core.CubePos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the actual pinned Minecraft classes, not a reimplementation of its bit layout. */
class EngineConstraintTest {
    @Test void actualDimensionCodecRejectsFullEarthVerticalRange() {
        var json = JsonParser.parseString("""
                {"has_skylight":true,"has_ceiling":false,"ultrawarm":false,"natural":true,
                 "coordinate_scale":1,"bed_works":true,"respawn_anchor_works":false,
                 "min_y":0,"height":256,"logical_height":256,
                 "infiniburn":"#minecraft:infiniburn_overworld","effects":"minecraft:overworld",
                 "ambient_light":0,"piglin_safe":false,"has_raids":true,
                 "monster_spawn_light_level":0,"monster_spawn_block_light_limit":0}
                """).getAsJsonObject();
        assertTrue(DimensionType.DIRECT_CODEC.parse(JsonOps.INSTANCE, json).result().isPresent());
        var tooLow = json.deepCopy(); tooLow.addProperty("min_y", -11008);
        assertTrue(DimensionType.DIRECT_CODEC.parse(JsonOps.INSTANCE, tooLow).error().isPresent());
        var tooTall = json.deepCopy(); tooTall.addProperty("height", 19872); tooTall.addProperty("logical_height", 19872);
        assertTrue(DimensionType.DIRECT_CODEC.parse(JsonOps.INSTANCE, tooTall).error().isPresent());
        assertEquals(-2032, DimensionType.MIN_Y);
        assertEquals(2031, DimensionType.MAX_Y);
    }
    @Test void explicitSectionAndOffsetProtocolPreservesWholeEarthAndExtremeY() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (long y : new long[]{-11000, -1, 0, 8849}) {
                EarthBlockAddress original = EarthBlockAddress.of(20_037_508, y, -10_018_754);
                original.write(buffer);
                EarthBlockAddress restored = EarthBlockAddress.read(buffer);
                assertEquals(original, restored);
                assertEquals(20_037_508, restored.x());
                assertEquals(y, restored.y());
                assertEquals(-10_018_754, restored.z());
            }
        } finally { buffer.release(); }
    }

    @Test void malformedLocalOffsetIsRejectedBeforeUse() {
        assertThrows(IllegalArgumentException.class, () -> new EarthBlockAddress(0, 4096));
    }
    @Test void vanillaPackedBlockPosCannotRepresentRequestedEarthHeights() {
        assertEquals(657, BlockPos.of(new BlockPos(0, 8849, 0).asLong()).getY());
        assertEquals(1288, BlockPos.of(new BlockPos(0, -11000, 0).asLong()).getY());
        assertNotEquals(8849, BlockPos.of(new BlockPos(0, 8849, 0).asLong()).getY());
        assertNotEquals(-11000, BlockPos.of(new BlockPos(0, -11000, 0).asLong()).getY());
    }

    @Test void actualBlockPosObjectsRetainHeightUntilPacked() {
        assertEquals(8849, new BlockPos(0, 8849, 0).getY());
        assertEquals(-11000, new BlockPos(0, -11000, 0).getY());
    }

    @Test void sectionKeysCanCarryEarthCubeCoordinatesWithoutLosingY() {
        for (long y : new long[]{-11000, -1, 0, 8849}) {
            CubePos cube = CubePos.fromBlock(20_037_508, y, 10_018_754);
            long key = SectionPos.asLong(cube.x(), cube.y(), cube.z());
            assertEquals(cube.x(), SectionPos.x(key));
            assertEquals(cube.y(), SectionPos.y(key));
            assertEquals(cube.z(), SectionPos.z(key));
        }
    }

    @Test void temporaryWindowsHaveExplicitExactMappingAndRejectOutOfWindowAccess() {
        assertEquals(145, new VerticalWindow(8704, 256).toEngineY(8849));
        assertEquals(136, new VerticalWindow(-11136, 256).toEngineY(-11000));
        assertEquals(-11000, new VerticalWindow(-11136, 256).toEarthY(136));
        assertThrows(IllegalArgumentException.class, () -> new VerticalWindow(8704, 256).toEngineY(-11000));
        assertThrows(IllegalArgumentException.class, () -> new VerticalWindow(1, 256));
        assertThrows(IllegalArgumentException.class, () -> new VerticalWindow(0, 19872));
    }

    @Test void calibrationCubeSurfacesUseCanonicalMetresAcrossNegativeBoundary() {
        for (int y : new int[]{-11000, -1, 0, 8849}) {
            var generator = new CalibrationCubes(y);
            CubePos pos = CubePos.fromBlock(-1, y, -1);
            var cube = generator.generate(pos);
            assertEquals(CalibrationCubes.SURFACE, cube.get(15, CubePos.local(y), 15));
            CubePos above = CubePos.fromBlock(-1, (long) y + 1, -1);
            assertEquals(CalibrationCubes.AIR, generator.generate(above).get(15, CubePos.local((long) y + 1), 15));
        }
    }
}
