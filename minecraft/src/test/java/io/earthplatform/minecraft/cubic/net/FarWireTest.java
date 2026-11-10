package io.earthplatform.minecraft.cubic.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.minecraft.cubic.far.Far;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

/** Far columns and the word of quick stand-ins cross the wire whole, with nothing left over. */
class FarWireTest {
    private static RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY); }

    @Test void columnKeepsItsStandInMark() {
        short[] drops = new short[256];
        for (int i = 0; i < 256; i++) drops[i] = (short) (i * 251);
        long key = Far.key(8, -2573, 877);
        for (boolean preview : new boolean[] {false, true}) {
            var buffer = buffer();
            CubePayloads.FarColumnData.CODEC.encode(buffer, new CubePayloads.FarColumnData(key, -3, List.of(new byte[] {1, 2, 3}, new byte[0]), drops, preview));
            CubePayloads.FarColumnData back = CubePayloads.FarColumnData.CODEC.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            assertEquals(key, back.key());
            assertEquals(-3, back.bottom());
            assertEquals(2, back.sections().size());
            assertArrayEquals(new byte[] {1, 2, 3}, back.sections().get(0));
            assertEquals(0, back.sections().get(1).length);
            assertArrayEquals(drops, back.drops());
            assertEquals(preview, back.preview());
        }
    }

    @Test void scaleSaysWhetherStandInsAreToBeHad() {
        for (boolean previews : new boolean[] {false, true}) {
            var buffer = buffer();
            CubePayloads.EarthScale.CODEC.encode(buffer, new CubePayloads.EarthScale(1.2643, previews));
            CubePayloads.EarthScale back = CubePayloads.EarthScale.CODEC.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            assertEquals(1.2643, back.metresPerBlock());
            assertEquals(previews, back.previews());
        }
    }

    @Test void standInRequestHoldsBothCoarsestLevels() {
        // The two coarsest levels at the sharpest normal setting, in one request each.
        long[] keys = new long[CubePayloads.FarPreviewRequest.MAX_KEYS];
        for (int i = 0; i < keys.length; i++) keys[i] = Far.key(Far.PREVIEW_LEVEL + (i & 1), i - 2000, 7 * i);
        var buffer = buffer();
        CubePayloads.FarPreviewRequest.CODEC.encode(buffer, new CubePayloads.FarPreviewRequest(keys));
        assertArrayEquals(keys, CubePayloads.FarPreviewRequest.CODEC.decode(buffer).keys());
        assertEquals(0, buffer.readableBytes());
        assertTrue(Far.valid(keys[0]) && Far.level(keys[1]) == Far.MAX_LEVEL);
        assertFalse(Far.PREVIEW_LEVEL < Far.MIN_LEVEL);
    }
}
