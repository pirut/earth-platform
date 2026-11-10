package io.earthplatform.minecraft.cubic.far;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.CubeCodec;
import io.earthplatform.minecraft.cubic.gen.FarColumn;
import io.earthplatform.minecraft.cubic.gen.FarSurfacePacket;
import io.earthplatform.minecraft.cubic.gen.SurfacePacketFixtures;
import io.earthplatform.minecraft.cubic.net.CubePayloads;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;

/** Far columns sent as runs: what the server makes, sends and keeps comes back as the very column made the long way. */
class FarColumnRunsTest {
    private static CubePayloads.FarColumnData throughTheWire(CubePayloads.FarColumnData column) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        CubePayloads.FarColumnData.CODEC.encode(buffer, column);
        CubePayloads.FarColumnData back = CubePayloads.FarColumnData.CODEC.decode(buffer);
        assertEquals(0, buffer.readableBytes());
        return back;
    }

    private static List<byte[]> bytes(List<LevelChunkSection> sections) {
        List<byte[]> bytes = new ArrayList<>();
        for (LevelChunkSection section : sections) bytes.add(section == null ? new byte[0] : CubeCodec.sectionBytes(section));
        return bytes;
    }

    private static void same(List<byte[]> a, List<byte[]> b) {
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) assertArrayEquals(a.get(i), b.get(i), "section " + i);
    }

    /** The sections a mesh is given number their biomes their own way, so they are compared block by block. */
    private static void sameBlocks(List<LevelChunkSection> a, List<LevelChunkSection> b) {
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            LevelChunkSection one = a.get(i), other = b.get(i);
            assertEquals(one == null, other == null, "air section " + i);
            if (one == null) continue;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                assertSame(one.getBlockState(x, y, z), other.getBlockState(x, y, z));
                assertSame(one.getBiomes().get(x >> 2, y >> 2, z >> 2), other.getBiomes().get(x >> 2, y >> 2, z >> 2));
            }
        }
    }

    @Test void aCompactColumnIsTheDenseColumnAfterTheWireAndTheDisk() throws Exception {
        var ids = SurfacePacketFixtures.biomes().asHolderIdMap();
        int runs = 0, dense = 0;
        long runBytes = 0, sectionBytes = 0;
        for (int mode = 0; mode <= 4; mode++) {
            var generator = new SurfacePacketFixtures.Country(mode);
            for (int level = Far.MIN_LEVEL; level <= Far.MAX_LEVEL; level++) for (int at = -3; at <= 3; at++) {
                long key = Far.key(level, at, 2 - at);
                FarColumn made = new FarColumn(), expected = new FarColumn();
                generator.farColumnCompact(level, at, 2 - at, made);
                generator.farColumn(level, at, 2 - at, expected);
                assertEquals(expected.bottom, made.bottom);
                assertArrayEquals(expected.drops, made.drops);
                if (made.sections.isEmpty() && made.packet != null) {
                    runs++;
                    for (byte[] section : bytes(expected.sections)) sectionBytes += 4 + section.length;
                    var sent = new CubePayloads.FarColumnData(key, made.bottom, List.of(), made.drops, made.packet.encode(ids));
                    runBytes += sent.packet().length;
                    for (var got : List.of(throughTheWire(sent), FarServer.stored(key, FarServer.stored(sent)))) {
                        assertTrue(got.compact());
                        assertEquals(key, got.key());
                        assertEquals(expected.bottom, got.bottom());
                        assertArrayEquals(expected.drops, got.drops());
                        assertArrayEquals(sent.packet(), got.packet());
                        assertEquals(FarPrints.of(sent), FarPrints.of(got));
                        FarSurfacePacket packet = FarSurfacePacket.decode(got.packet(), ids);
                        assertEquals(level, packet.level());
                        assertEquals(expected.bottom, packet.bottom());
                        same(bytes(expected.sections), bytes(packet.materialize(ids)));
                        sameBlocks(expected.sections, packet.materializeForMeshing());
                        assertSame(packet.materializeForMeshing(), packet.materializeForMeshing());
                    }
                } else {
                    dense++;
                    same(bytes(expected.sections), bytes(made.sections));
                }
            }
        }
        System.out.println("FAR_COLUMN_RUNS columns=" + runs + " bytesAsRuns=" + runBytes + " bytesAsSections=" + sectionBytes);
        // Plain ground from 8-block voxels up comes as runs; fine levels, giant groves and layered rock stay sections.
        assertTrue(runs > 100 && dense > 100, runs + " as runs, " + dense + " as sections");
    }

    @Test void aColumnOfSectionsIsSentAsItAlwaysWas() {
        short[] drops = new short[256];
        for (int i = 0; i < 256; i++) drops[i] = (short) (i * 37 - 4000);
        var column = new CubePayloads.FarColumnData(Far.key(2, 5, -7), -3, List.of(new byte[] {1, 2, 3}, new byte[0]), drops);
        RegistryFriendlyByteBuf old = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        old.writeLong(column.key());
        old.writeInt(column.bottom());
        old.writeVarInt(2);
        old.writeByteArray(new byte[] {1, 2, 3});
        old.writeByteArray(new byte[0]);
        for (short drop : drops) old.writeShort(drop);
        // Then whether it is a quick stand-in (build 70's stand-ins added this byte for every column).
        old.writeBoolean(false);
        RegistryFriendlyByteBuf now = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        CubePayloads.FarColumnData.CODEC.encode(now, column);
        assertEquals(old, now);
        var back = throughTheWire(column);
        assertFalse(back.compact());
        same(column.sections(), back.sections());
        assertArrayEquals(drops, back.drops());
    }

    @Test void theSameColumnInEitherFormHasItsOwnPrint() {
        short[] drops = new short[256];
        byte[] bytes = {9, 8, 7, 6, 5, 4, 3, 2, 1};
        long key = Far.key(4, 0, 0);
        assertNotEquals(FarPrints.of(new CubePayloads.FarColumnData(key, 0, List.of(bytes), drops)),
                FarPrints.of(new CubePayloads.FarColumnData(key, 0, List.of(), drops, bytes)));
    }
}
