package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.*;
import io.earthplatform.minecraft.cubic.CubeCodec;
import java.io.IOException;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;

class FarSurfacePacketTest {
    static void compare(FarColumn expected, FarColumn actual) {
        assertEquals(expected.bottom, actual.bottom); assertArrayEquals(expected.drops, actual.drops);
        assertEquals(expected.sections.size(), actual.sections.size());
        for (int si = 0; si < expected.sections.size(); si++) {
            LevelChunkSection a = expected.sections.get(si), b = actual.sections.get(si);
            assertEquals(a == null, b == null, "air section " + si);
            if (a == null) continue;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                assertSame(a.getBlockState(x, y, z), b.getBlockState(x, y, z));
            for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++)
                assertSame(a.getBiomes().get(x, y, z), b.getBiomes().get(x, y, z));
            assertArrayEquals(CubeCodec.sectionBytes(a), CubeCodec.sectionBytes(b), "legacy encoding/order " + si);
        }
    }

    @Test void boundedGenerationAndEncodingMatchEveryDenseBlockBiomeDropAndSectionByte() throws Exception {
        int columns = 0; long voxels = 0;
        for (int mode : new int[] {0, 1, 4}) {
            var generator = new SurfacePacketFixtures.Country(mode);
            for (int level = 3; level <= 8; level++) for (int pos = -2; pos <= 2; pos++) {
                FarColumn a = generator.column(level, pos, -pos - 1, false), b = generator.column(level, pos, -pos - 1, true);
                assertNotNull(b.packet); compare(a, b);
                FarSurfacePacket decoded = FarSurfacePacket.decode(b.packet.encode(SurfacePacketFixtures.biomes().asHolderIdMap()), SurfacePacketFixtures.biomes().asHolderIdMap());
                FarColumn c = new FarColumn(); c.bottom = decoded.bottom();
                for (int i = 0; i < 256; i++) c.drops[i] = decoded.drop(i);
                c.sections.addAll(decoded.materialize(SurfacePacketFixtures.biomes().asHolderIdMap()));
                compare(a, c); columns++; voxels += b.packet.height() * 256L;
            }
        }
        System.out.println("SURFACE_PACKET_ORACLE columns=" + columns + " voxels=" + voxels + " dense+decoded exact");
    }

    @Test void unsupportedFineTreesGiantsAndStrataUseTheDenseOracle() {
        for (int mode : new int[] {0, 2, 3}) {
            var generator = new SurfacePacketFixtures.Country(mode);
            for (int level : new int[] {1, 2, 3, 4}) {
                boolean supported = mode == 0 && level >= 3 || mode == 2 && level >= 5;
                FarColumn a = generator.column(level, 0, 0, false), b = generator.column(level, 0, 0, true);
                if (!supported) assertNull(b.packet);
                compare(a, b);
            }
        }
    }

    @Test void sparseSnapshotsPreserveCavesOverhangsWaterBlockPropertiesAndRevisionOwnership() throws Exception {
        var generator = new SurfacePacketFixtures.Country(0);
        FarSurfacePacket base = generator.column(4, -1, 1, true).packet;
        int row = Math.min(10, base.height() - 2);
        BlockState cave = Blocks.CAVE_AIR.defaultBlockState(), log = Blocks.OAK_LOG.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
        List<FarSurfacePacket.Edit> changes = List.of(new FarSurfacePacket.Edit(1, row, 2, cave), new FarSurfacePacket.Edit(1, row + 1, 2, log), new FarSurfacePacket.Edit(3, row, 4, water));
        FarSurfacePacket edited = base.withEdits(0, 5, changes);
        assertEquals(0, base.revision()); assertEquals(0, base.editCount()); assertEquals(5, edited.revision());
        assertSame(cave, edited.block(1, row, 2)); assertSame(log, edited.block(1, row + 1, 2)); assertSame(water, edited.block(3, row, 4));
        assertThrows(IllegalArgumentException.class, () -> edited.withEdits(0, 6, changes));
        assertThrows(IllegalArgumentException.class, () -> edited.withEdits(5, 5, changes));
        var decoded = FarSurfacePacket.decode(edited.encode(SurfacePacketFixtures.biomes().asHolderIdMap()), SurfacePacketFixtures.biomes().asHolderIdMap());
        for (int y = 0; y < base.height(); y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) assertSame(edited.block(x, y, z), decoded.block(x, y, z));
        var reverted = edited.withEdits(5, 6, changes.stream().map(e -> new FarSurfacePacket.Edit(e.x(), e.row(), e.z(), base.block(e.x(), e.row(), e.z()))).toList());
        assertEquals(0, reverted.editCount()); assertEquals(6, reverted.revision());
    }

    @Test void decoderRejectsTruncationTrailingBytesBadIdsAndResourceBounds() throws Exception {
        var ids = SurfacePacketFixtures.biomes().asHolderIdMap();
        byte[] bytes = new SurfacePacketFixtures.Country(0).column(3, 0, 0, true).packet.encode(ids);
        for (int end : new int[] {0, 4, 64, bytes.length - 1}) assertThrows(IOException.class, () -> FarSurfacePacket.decode(Arrays.copyOf(bytes, end), ids));
        assertThrows(IOException.class, () -> FarSurfacePacket.decode(Arrays.copyOf(bytes, bytes.length + 1), ids));
        byte[] bad = bytes.clone(); bad[4] = 77;
        assertThrows(IOException.class, () -> FarSurfacePacket.decode(bad, ids));
        byte[] badBiome = bytes.clone(); java.util.Arrays.fill(badBiome, 524, 529, (byte) 255); badBiome[528]=7;
        assertThrows(IOException.class, () -> FarSurfacePacket.decode(badBiome, ids));
        int stateAt=524;
        for(int i=0;i<19;i++) { while((bytes[stateAt++] & 128)!=0) {} }
        byte[] badState = bytes.clone(); java.util.Arrays.fill(badState, stateAt, stateAt+5, (byte) 255); badState[stateAt+4]=7;
        assertThrows(IOException.class, () -> FarSurfacePacket.decode(badState, ids));
        assertThrows(IOException.class, () -> FarSurfacePacket.decode(new byte[FarSurfacePacket.MAX_BYTES + 1], ids));
    }
    @Test void immutableBaseOwnsInputsAndLargeEditOverlaysRequireDenseFallback() throws Exception {
        var registry = SurfacePacketFixtures.biomes(); var initial = registry.getAny().orElseThrow();
        java.util.List<net.minecraft.core.Holder<Biome>> biomes = new java.util.ArrayList<>(java.util.Collections.nCopies(16, initial));
        short[] drops = new short[256];
        List<List<FarSurfacePacket.Run>> columns = new ArrayList<>();
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int i=0;i<256;i++) columns.add(new ArrayList<>(List.of(new FarSurfacePacket.Run(1024, stone))));
        FarSurfacePacket base = new FarSurfacePacket(8, -32, 1024, drops, initial, biomes, columns);
        columns.getFirst().set(0,new FarSurfacePacket.Run(1024,Blocks.GOLD_BLOCK.defaultBlockState())); drops[0]=77; biomes.clear();
        assertSame(stone,base.block(0,1023,0)); assertEquals(0,base.drop(0));
        List<FarSurfacePacket.Edit> changes=new ArrayList<>();
        for(int i=0;i<=FarSurfacePacket.MAX_EDITS;i++) changes.add(new FarSurfacePacket.Edit(i&15,i>>8,i>>4&15,Blocks.CAVE_AIR.defaultBlockState()));
        assertThrows(IllegalArgumentException.class,()->base.withEdits(0,1,changes));
        assertEquals(0,base.editCount());
        assertThrows(IndexOutOfBoundsException.class,()->base.block(0,1024,0));
        var decoded=FarSurfacePacket.decode(base.encode(registry.asHolderIdMap()),registry.asHolderIdMap());
        assertSame(stone,decoded.block(15,1023,15));
    }

    @Test void compactCodecRoundTripsVariedRegistryStatesPropertiesAndUnsignedDrops() throws Exception {
        var registry=SurfacePacketFixtures.biomes(); var ids=registry.asHolderIdMap(); var biome=registry.getAny().orElseThrow();
        Random random=new Random(167); short[] drops=new short[256]; List<List<FarSurfacePacket.Run>> columns=new ArrayList<>();
        for(int i=0;i<256;i++) {
            drops[i]=(short)random.nextInt(65536);
            int a=random.nextInt(Block.BLOCK_STATE_REGISTRY.size()),b;
            do { b=random.nextInt(Block.BLOCK_STATE_REGISTRY.size()); } while(a==b);
            columns.add(List.of(new FarSurfacePacket.Run(17,Block.BLOCK_STATE_REGISTRY.byId(a)),new FarSurfacePacket.Run(32,Block.BLOCK_STATE_REGISTRY.byId(b))));
        }
        var packet=new FarSurfacePacket(8,-100,32,drops,biome,Collections.nCopies(16,biome),columns);
        var decoded=FarSurfacePacket.decode(packet.encode(ids),ids);
        for(int i=0;i<256;i++) {
            assertEquals(drops[i],decoded.drop(i));
            for(int y=0;y<32;y++) assertSame(packet.block(i&15,y,i>>4),decoded.block(i&15,y,i>>4));
        }
        byte[] overflowing=packet.encode(ids);Arrays.fill(overflowing,524,529,(byte)255);
        assertThrows(IOException.class,()->FarSurfacePacket.decode(overflowing,ids));
    }

    @Test void transientDenseViewKeepsExactStatesBiomesAndDropsAndRejectsVerticalBiomeChanges() {
        var column=new SurfacePacketFixtures.Country(4).column(3,-1,2,false);
        char[] drops=new char[256];for(int i=0;i<256;i++)drops[i]=(char)(column.drops[i]&65535);
        var sections=column.sections.toArray(LevelChunkSection[]::new);
        var packet=FarSurfacePacket.fromDense(3,column.bottom,sections,drops);assertNotNull(packet);
        for(int row=0;row<packet.height();row++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            var section=sections[row>>4];
            assertSame(section==null?Blocks.AIR.defaultBlockState():section.getBlockState(x,row&15,z),packet.block(x,row,z));
            if(section!=null)assertSame(section.getBiomes().get(x>>2,(row&15)>>2,z>>2),packet.biome(x,z));
            assertEquals(column.drops[z*16+x],packet.drop(z*16+x));
        }
        assertNull(FarSurfacePacket.fromDense(2,column.bottom,sections,drops));
        var section=java.util.Arrays.stream(sections).filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        var different=SurfacePacketFixtures.biomes().holders().filter(h->h!=section.getBiomes().get(0,0,0)).findFirst().orElseThrow();
        var changedBiomes=new net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.core.Holder<Biome>>(SurfacePacketFixtures.biomes().asHolderIdMap(),section.getBiomes().get(0,0,0),net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_BIOMES);
        for(int z=0;z<4;z++)for(int x=0;x<4;x++)for(int y=0;y<4;y++)changedBiomes.set(x,y,z,section.getBiomes().get(x,y,z));
        changedBiomes.set(0,1,0,different);
        var changedSection=new LevelChunkSection(section.getStates(),changedBiomes);changedSection.recalcBlockCounts();
        for(int i=0;i<sections.length;i++)if(sections[i]==section){sections[i]=changedSection;break;}
        assertNull(FarSurfacePacket.fromDense(3,column.bottom,sections,drops));
        assertNull(FarSurfacePacket.fromDense(3,0,new LevelChunkSection[65],drops));
    }

}
