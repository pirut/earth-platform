package io.earthplatform.minecraft.enginebridge;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubicWorld;
import io.earthplatform.core.EditSnapshot;
import io.earthplatform.core.FileEditStore;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeCubeCodecTest {
    private static EnginePaletteContext palette;
    private static WorldIdentity identity;
    private static NativeCubeCodec codec;

    @BeforeAll static void registries() {
        palette = createPalette();
        identity = world(palette);
        codec = new NativeCubeCodec(identity, palette);
    }

    static EnginePaletteContext createPalette() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        return EnginePaletteContext.create(registry.freeze());
    }

    private static WorldIdentity world(EnginePaletteContext context) {
        return new WorldIdentity(73, "native-codec-test", "full-3d-v1", "registered-states-v1",
                NativeCubeCodec.paletteIdentity(context));
    }

    private static int id(BlockState state) { return Block.BLOCK_STATE_REGISTRY.getId(state); }

    @Test void everyNativeCellAndExactTransportStampRoundTripsAtSignedExtremeHeights() {
        BlockState[] states = {Blocks.AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(),
                Blocks.VOID_AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                Blocks.GOLD_BLOCK.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)};
        int[] cells = new int[CubePos.VOLUME];
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            cells[CubePos.index(x, y, z)] = id(states[(x + 3 * y + 5 * z) % states.length]);
        ImmutableCube source = ImmutableCube.copyOf(cells);
        for (int y : new int[]{-688, -1, 0, 553}) {
            CubePos position = new CubePos(-312, y, 719);
            EngineCubeSnapshot snapshot = codec.materialize(position, source, 29, 51, 83);
            var wire = new FriendlyByteBuf(Unpooled.buffer());
            try {
                snapshot.write(wire);
                EngineCubeSnapshot decoded = EngineCubeSnapshot.readFrame(wire, identity, 29, palette);
                assertEquals(position, decoded.position());
                assertEquals(identity, decoded.world());
                assertEquals(29, decoded.epoch());
                assertEquals(51, decoded.leaseGeneration());
                assertEquals(83, decoded.revision());
                assertEquals(source, codec.capture(decoded));
                var nativeCube = decoded.materialize(palette);
                for (int ly = 0; ly < 16; ly++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                    assertSame(Block.BLOCK_STATE_REGISTRY.byId(source.get(x, ly, z)), nativeCube.get(x, ly, z));
            } finally { wire.release(); }
        }
    }

    @Test void capturesAnExistingNativeSnapshotAndDoesNotTransposeCoordinates() {
        var nativeCube = new EngineCubeSection(palette);
        nativeCube.set(1, 2, 3, Blocks.GOLD_BLOCK.defaultBlockState());
        nativeCube.set(3, 2, 1, Blocks.WATER.defaultBlockState());
        nativeCube.set(1, 11, 3, Blocks.STONE.defaultBlockState());
        var snapshot = EngineCubeSnapshot.capture(new CubePos(-1, 553, -2), identity, 1, 2, 3, nativeCube);
        ImmutableCube captured = codec.capture(snapshot);
        assertEquals(id(Blocks.GOLD_BLOCK.defaultBlockState()), captured.get(1, 2, 3));
        assertEquals(id(Blocks.WATER.defaultBlockState()), captured.get(3, 2, 1));
        assertEquals(id(Blocks.AIR.defaultBlockState()), captured.get(1, 7, 3));
        assertEquals(id(Blocks.STONE.defaultBlockState()), captured.get(1, 11, 3));
        nativeCube.set(1, 2, 3, Blocks.AIR.defaultBlockState());
        assertEquals(id(Blocks.GOLD_BLOCK.defaultBlockState()), captured.get(1, 2, 3));
    }

    @Test void rejectsUnknownIdsInsteadOfConflatingThemWithRegisteredAir() {
        int air = id(Blocks.AIR.defaultBlockState());
        for (int unknown : new int[]{Block.BLOCK_STATE_REGISTRY.size(), Integer.MAX_VALUE}) {
            assertNull(Block.BLOCK_STATE_REGISTRY.byId(unknown));
            assertThrows(IllegalArgumentException.class,
                    () -> codec.materialize(new CubePos(0, 0, 0), ImmutableCube.uniform(unknown), 1, 1, 0));
            int[] mixed = ImmutableCube.uniform(air).toArray();
            mixed[CubePos.index(15, 15, 15)] = unknown;
            assertThrows(IllegalArgumentException.class,
                    () -> codec.materialize(new CubePos(0, 0, 0), ImmutableCube.copyOf(mixed), 1, 1, 0));
        }
        for (BlockState state : new BlockState[]{Blocks.AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.VOID_AIR.defaultBlockState()}) {
            ImmutableCube knownAir = ImmutableCube.uniform(id(state));
            assertEquals(knownAir, codec.capture(codec.materialize(new CubePos(0, -688, 0), knownAir, 1, 1, 0)));
        }
    }

    @Test void sourcePaletteWorldAndTransportInputsMustBePinnedExplicitly() {
        var unpinned = new WorldIdentity(73, "native-codec-test", "full-3d-v1", "registered-states-v1", "materials-v1");
        assertThrows(IllegalArgumentException.class, () -> new NativeCubeCodec(unpinned, palette));
        var forged = new EnginePaletteContext(palette.biomes(), "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> new NativeCubeCodec(world(forged), forged));
        var another = new WorldIdentity(74, identity.generatorId(), identity.generatorVersion(),
                identity.datasetVersion(), identity.blockPaletteVersion());
        var nativeCube = new EngineCubeSection(palette);
        assertThrows(IllegalArgumentException.class, () -> codec.capture(
                EngineCubeSnapshot.capture(new CubePos(0, 0, 0), another, 1, 1, 0, nativeCube)));
        var wrongPalette = new EngineCubeSection(forged);
        assertThrows(DecoderException.class, () -> codec.capture(
                EngineCubeSnapshot.capture(new CubePos(0, 0, 0), identity, 1, 1, 0, wrongPalette)));
        var air = ImmutableCube.uniform(id(Blocks.AIR.defaultBlockState()));
        assertThrows(IllegalArgumentException.class, () -> codec.materialize(new CubePos(0, 0, 0), air, -1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> codec.materialize(new CubePos(0, 0, 0), air, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> codec.materialize(new CubePos(0, 0, 0), air, 0, 1, -1));
    }

    @Test void authoritativeExplicitAirEditKeepsItsRevisionAndLeavesBaseImmutable() {
        CubePos position = new CubePos(-1, -688, 0);
        ImmutableCube base = ImmutableCube.uniform(id(Blocks.STONE.defaultBlockState()));
        int edited = CubePos.index(15, 8, 1), air = id(Blocks.AIR.defaultBlockState());
        var edits = new EditSnapshot(position, 17, Map.of(edited, air));
        var view = new CubicWorld.CubeView(position, base, edits);
        var snapshot = codec.materialize(view, 23, 41);
        assertEquals(17, snapshot.revision());
        assertEquals(23, snapshot.epoch());
        assertEquals(41, snapshot.leaseGeneration());
        assertEquals(air, codec.capture(snapshot).get(edited));
        assertEquals(id(Blocks.STONE.defaultBlockState()), base.get(edited));
        assertTrue(view.edits().blocks().containsKey(edited));
    }
}
