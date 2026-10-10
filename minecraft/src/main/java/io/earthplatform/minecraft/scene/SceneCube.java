package io.earthplatform.minecraft.scene;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import java.util.Objects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** One immutable native-state materialization of an admitted, independently addressed cube. */
public final class SceneCube {
    private final EngineCubeSnapshot snapshot;
    private final BlockState[] states;

    public SceneCube(EngineCubeSnapshot snapshot, EnginePaletteContext palette) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(palette, "palette");
        CubePos position = snapshot.position();
        if (position.minBlockY() < SceneNeighborhood.MIN_Y
                || position.minBlockY() + CubePos.SIZE > SceneNeighborhood.MIN_Y + SceneNeighborhood.HEIGHT) {
            throw new IllegalArgumentException("Scene cube exceeds the native Earth height envelope");
        }
        // Native model APIs take unpacked, signed integer BlockPos coordinates.
        Math.toIntExact(position.minBlockX());
        Math.toIntExact(position.minBlockX() + 15);
        Math.toIntExact(position.minBlockZ());
        Math.toIntExact(position.minBlockZ() + 15);
        var section = snapshot.materialize(palette);
        BlockState[] materialized = new BlockState[CubePos.VOLUME];
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockState state = section.get(x, y, z);
            if (!supported(state) || !state.getFluidState().isEmpty() || state.hasBlockEntity()) {
                throw new SparseFacadeException("unsupported native scene state " + state + " at "
                        + position + " local " + x + "," + y + "," + z);
            }
            materialized[CubePos.index(x, y, z)] = state;
        }
        states = materialized;
    }

    private static boolean supported(BlockState state) {
        return state.is(Blocks.AIR) || state.is(Blocks.STONE) || state.is(Blocks.ANDESITE) || state.is(Blocks.TUFF)
                || state.is(Blocks.DIORITE) || state.is(Blocks.GRAVEL) || state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.MAGENTA_TERRACOTTA) || state.is(Blocks.CYAN_TERRACOTTA);
    }

    public EngineCubeSnapshot snapshot() { return snapshot; }

    /** BlockState is immutable; the private array and mutable decoded section never escape. */
    public BlockState state(int x, int y, int z) { return states[CubePos.index(x, y, z)]; }
}
