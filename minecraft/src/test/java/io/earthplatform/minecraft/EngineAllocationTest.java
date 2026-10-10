package io.earthplatform.minecraft;

import com.mojang.serialization.Lifecycle;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated engine objects only: no dedicated server, live level, renderer or EULA step. */
class EngineAllocationTest {
    private static Registry<Biome> biomes() {
        var lookup = VanillaRegistries.createLookup();
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        return registry.freeze();
    }
    private static ProtoChunk chunk(Registry<Biome> registry, int minY, int height) {
        return new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, LevelHeightAccessor.create(minY, height), registry, null);
    }
    private static long allocation(Registry<Biome> registry, int minY, int height, ThreadMXBean memory) {
        var retained = new ArrayList<ProtoChunk>();
        long before = memory.getThreadAllocatedBytes(Thread.currentThread().threadId());
        for (int repeat = 0; repeat < 8; repeat++) retained.add(chunk(registry, minY, height));
        long allocated = memory.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
        assertEquals(height / 16, retained.getLast().getSections().length);
        return allocated / 8;
    }

    @Test void fullHeightProtoChunksEagerlyAllocateEverySection() throws Exception {
        var registry = biomes();
        var tall = chunk(registry, -11008, 19872);
        assertEquals(1242, tall.getSections().length);
        assertEquals(1242, java.util.Arrays.stream(tall.getSections()).filter(java.util.Objects::nonNull).count());
        // Native BlockPos objects are sufficient for direct pre-light ProtoChunk block access.
        tall.setBlockState(new net.minecraft.core.BlockPos(0, 8849, 0), Blocks.DIAMOND_BLOCK.defaultBlockState(), false);
        tall.setBlockState(new net.minecraft.core.BlockPos(0, -11000, 0), Blocks.GOLD_BLOCK.defaultBlockState(), false);
        assertEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(), tall.getBlockState(new net.minecraft.core.BlockPos(0, 8849, 0)));
        assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), tall.getBlockState(new net.minecraft.core.BlockPos(0, -11000, 0)));

        var memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(memory.isThreadAllocatedMemorySupported());
        memory.setThreadAllocatedMemoryEnabled(true);
        for (int warm = 0; warm < 4; warm++) { chunk(registry, -64, 384); chunk(registry, -11008, 19872); }
        long normalBytes = allocation(registry, -64, 384, memory);
        long tallBytes = allocation(registry, -11008, 19872, memory);
        assertTrue(tallBytes > normalBytes * 10, "Full-height storage should reveal the eager-allocation regression");
        String result = "{\"scope\":\"warm single-thread allocation per empty ProtoChunk; not retained heap or gameplay benchmark\","
                + "\"normalSections\":24,\"earthSections\":1242,\"normalAllocatedBytes\":" + normalBytes
                + ",\"earthAllocatedBytes\":" + tallBytes + ",\"renderSlotsAtDistance8Calculated\":358938,"
                + "\"nativeDirectBlockAccess\":\"passed before lighting/network/save\"}";
        Path path = Path.of("build/reports/earthplatform/engine-allocation.json");
        Files.createDirectories(path.getParent()); Files.writeString(path, result + "\n");
        System.out.println("ENGINE_ALLOCATION " + result);
    }

    @Test void actualSectionPayloadCanExceedVanillaColumnPacketLimit() {
        var section = new LevelChunkSection(biomes());
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            section.setBlockState(x, y, z, ((x + y + z) & 1) == 0 ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState());
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (int sectionY = 0; sectionY < 1242; sectionY++) section.write(buffer);
            assertEquals(section.getSerializedSize() * 1242, buffer.readableBytes());
            assertTrue(buffer.readableBytes() > 2 * 1024 * 1024);
            System.out.println("ENGINE_COLUMN_PACKET {\"sections\":1242,\"twoStateBytesPerSection\":" + section.getSerializedSize()
                    + ",\"actualSectionDataBytes\":" + buffer.readableBytes() + ",\"vanillaMaximum\":2097152}");
        } finally { buffer.release(); }
    }
}
